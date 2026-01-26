import { Box, useInput, useStdout } from 'ink';
import React, { useState, useEffect } from 'react';

import { MatrixService, setLogger } from '../shared/services/MatrixService.js';
import { createMatrixService } from '../shared/services/index.js';

import { KeytarCredentialStorage } from './services/KeytarCredentialStorage';
import { LogService } from './services/LogService.js';
import { PROFILES, type ProfileKey } from './types/profile';
import { AdminView } from './views/AdminView';
import { HistoryView } from './views/HistoryView';
import { LoadingView } from './views/LoadingView';
import { LogView } from './views/LogView';
import { MainView } from './views/MainView';
import { ProfileSelectorView } from './views/ProfileSelectorView';

// Logging helpers
const log = (message: string): void => {
  LogService.getInstance().addEntry('log', message);
};

const logError = (message: string): void => {
  LogService.getInstance().addEntry('error', message);
};

// Create a logger that routes to TUI's LogService
const tuiLogger = {
  log: (message: string) => LogService.getInstance().addEntry('log', message),
  warn: (message: string) => LogService.getInstance().addEntry('warn', message),
  error: (message: string) => LogService.getInstance().addEntry('error', message),
};

// Wire up TUI's LogService to the shared MatrixServiceAdapter code
setLogger(tuiLogger);

// Create TUI-specific MatrixService instance with LogService-based logger
const credentialStorage = new KeytarCredentialStorage();
export const matrixService = createMatrixService({
  credentialStorage,
  logger: tuiLogger,
});

type Screen =
  | 'loading'
  | 'main'
  | 'history'
  | 'admin'
  | 'log'
  | 'profile-select';

interface Contact {
  id: string;
  name: string;
  type: 'dm' | 'family';
  roomId: string | null;
  userId: string | null;
  hasUnread: boolean;
  hasError: boolean;
}

interface Navigation {
  screen: Screen;
  contact?: Contact;
  previousScreen?: Screen;
}

interface AppProps {
  initialProfile?: ProfileKey | null;
  debugMode?: boolean;
}

export function App({ initialProfile, debugMode = false }: AppProps) {
  const [navigation, setNavigation] = useState<Navigation>({
    screen: 'loading',
  });
  const [syncState, setSyncState] = useState<string>('STOPPED');
  const [error, setError] = useState<string | null>(null);
  const [currentProfile, setCurrentProfile] = useState<ProfileKey>(
    initialProfile && PROFILES[initialProfile] ? initialProfile : 'alice',
  );
  const { stdout } = useStdout();
  // Force re-render key that increments on resize
  const [renderKey, setRenderKey] = useState(0);

  // Handle terminal resize - force re-render when dimensions change
  useEffect(() => {
    const handleResize = () => {
      // Increment render key to force complete re-mount of components
      setRenderKey(prev => prev + 1);
    };

    // Listen for SIGWINCH (terminal resize signal)
    stdout?.on('resize', handleResize);

    return () => {
      stdout?.off('resize', handleResize);
    };
  }, [stdout]);

  useEffect(() => {
    // Listen for sync state changes
    // Note: ERROR state is transient and handled by Matrix SDK auto-retry + token refresh
    // We don't show ERROR to users - it will recover automatically
    const unsubscribe = matrixService.onSyncStateChange(state => {
      setSyncState(state);
      if (state === 'PREPARED' || state === 'SYNCING') {
        // Only navigate to main if we're still on the loading screen
        setNavigation(prev =>
          prev.screen === 'loading' ? { screen: 'main' } : prev,
        );
      }
      // Clear error when syncing recovers
      if (state === 'PREPARED' || state === 'SYNCING') {
        setError(null);
      }
    });

    return () => {
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    const initAuth = async () => {
      try {
        // Try to restore session for current profile
        const restored = await matrixService.restoreSession(currentProfile);
        if (!restored) {
          // Fall back to auto-login with current profile
          await matrixService.autoLogin(currentProfile);
        }
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : 'Unknown error';
        logError(`Failed to initialize auth: ${errorMessage}`);
        setError(`Failed to connect: ${errorMessage}`);
      }
    };

    initAuth();
  }, []);

  const handleSelectContact = (contact: Contact) => {
    setNavigation({ screen: 'history', contact });
  };

  const handleBack = () => {
    setNavigation({ screen: 'main' });
  };

  const handleSelectProfile = (profileKey: ProfileKey) => {
    setNavigation({ screen: 'loading' });
    switchProfile(profileKey);
  };

  // Profile switching handler
  const switchProfile = async (newProfile: ProfileKey) => {
    try {
      log(`[App] Switching profile to ${PROFILES[newProfile].displayName}`);

      // Stop current session
      await matrixService.logout();

      // Update profile state
      setCurrentProfile(newProfile);
      setNavigation({ screen: 'loading' });
      setError(null);

      // Try to restore session for new profile
      const restored = await matrixService.restoreSession(newProfile);
      if (!restored) {
        // Login with new profile credentials
        await matrixService.autoLogin(newProfile);
      }

      // Sync state listener will transition to 'contacts' screen
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : 'Unknown error';
      logError(`[App] Failed to switch profile: ${errorMessage}`);
      setError(
        `Failed to switch to ${PROFILES[newProfile].displayName}: ${errorMessage}`,
      );
    }
  };

  // Global key handlers
  useInput((input, key) => {
    // Log viewer (always available except in profile-select and admin)
    if (
      input === 'l' &&
      navigation.screen !== 'profile-select' &&
      navigation.screen !== 'admin'
    ) {
      setNavigation({
        ...navigation,
        screen: 'log',
        previousScreen: navigation.screen,
      });
    }

    // Profile switching (always available, even in error state)
    if (
      input === 'p' &&
      navigation.screen !== 'profile-select' &&
      navigation.screen !== 'admin'
    ) {
      setNavigation({
        ...navigation,
        screen: 'profile-select',
        previousScreen: navigation.screen,
      });
    }

    // Admin view (from main screen)
    if (input === 'a' && navigation.screen === 'main') {
      setNavigation({
        ...navigation,
        screen: 'admin',
        previousScreen: navigation.screen,
      });
    }

    // Escape key closes profile selector
    if (key.escape && navigation.screen === 'profile-select') {
      setNavigation({
        screen: navigation.previousScreen || 'main',
        contact: navigation.contact,
      });
    }
  });

  if (navigation.screen === 'loading') {
    return (
      <Box key={renderKey}>
        <LoadingView
          syncState={syncState}
          error={error}
          currentProfile={currentProfile}
        />
      </Box>
    );
  }

  if (navigation.screen === 'main') {
    return (
      <Box key={renderKey}>
        <MainView
          onSelectContact={handleSelectContact}
          currentProfile={currentProfile}
        />
      </Box>
    );
  }

  if (navigation.screen === 'history' && navigation.contact?.roomId) {
    return (
      <Box key={renderKey}>
        <HistoryView
          roomId={navigation.contact.roomId}
          contactName={navigation.contact.name}
          contactType={navigation.contact.type}
          onBack={handleBack}
          currentProfile={currentProfile}
        />
      </Box>
    );
  }

  if (navigation.screen === 'admin') {
    return (
      <Box key={renderKey}>
        <AdminView onBack={handleBack} currentProfile={currentProfile} />
      </Box>
    );
  }

  if (navigation.screen === 'log') {
    return (
      <Box key={renderKey}>
        <LogView
          onBack={() => {
            setNavigation({
              screen: navigation.previousScreen || 'main',
              contact: navigation.contact,
            });
          }}
        />
      </Box>
    );
  }

  if (navigation.screen === 'profile-select') {
    return (
      <Box key={renderKey}>
        <ProfileSelectorView
          currentProfile={currentProfile}
          onSelectProfile={handleSelectProfile}
          onBack={() => {
            setNavigation({
              screen: navigation.previousScreen || 'main',
              contact: navigation.contact,
            });
          }}
        />
      </Box>
    );
  }

  return <Box>Unknown screen</Box>;
}
