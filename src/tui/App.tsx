import { Box, useInput } from 'ink';
import React, { useState, useEffect } from 'react';

import { MatrixService } from '../shared/services/MatrixService.js';

import { KeytarCredentialStorage } from './services/KeytarCredentialStorage';
import { LogService } from './services/LogService.js';
import { silentLogger } from './services/SilentLogger';
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

// Create TUI-specific MatrixService instance with keytar storage and silent logger
const credentialStorage = new KeytarCredentialStorage();
export const matrixService = new MatrixService(credentialStorage, silentLogger);

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
}

export function App({ initialProfile }: AppProps) {
  const [navigation, setNavigation] = useState<Navigation>({
    screen: 'loading',
  });
  const [syncState, setSyncState] = useState<string>('STOPPED');
  const [error, setError] = useState<string | null>(null);
  const [currentProfile, setCurrentProfile] = useState<ProfileKey>(
    initialProfile && PROFILES[initialProfile] ? initialProfile : 'alice',
  );

  useEffect(() => {
    const initAuth = async () => {
      try {
        // Try to restore session for current profile
        const restored = await matrixService.restoreSession(currentProfile);
        if (!restored) {
          // Fall back to auto-login with current profile
          await matrixService.autoLogin(currentProfile);
        }

        // Listen for sync state changes
        const unsubscribe = matrixService.onSyncStateChange(state => {
          setSyncState(state);
          if (state === 'PREPARED' || state === 'SYNCING') {
            // Only navigate to main if we're still on the loading screen
            setNavigation(prev =>
              prev.screen === 'loading' ? { screen: 'main' } : prev,
            );
          }
          if (state === 'ERROR') {
            setError('Sync error - retrying...');
          }
        });

        return () => {
          unsubscribe();
        };
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
      <LoadingView
        syncState={syncState}
        error={error}
        currentProfile={currentProfile}
      />
    );
  }

  if (navigation.screen === 'main') {
    return (
      <MainView
        onSelectContact={handleSelectContact}
        currentProfile={currentProfile}
      />
    );
  }

  if (navigation.screen === 'history' && navigation.contact?.roomId) {
    return (
      <HistoryView
        roomId={navigation.contact.roomId}
        contactName={navigation.contact.name}
        contactType={navigation.contact.type}
        onBack={handleBack}
        currentProfile={currentProfile}
      />
    );
  }

  if (navigation.screen === 'admin') {
    return <AdminView onBack={handleBack} currentProfile={currentProfile} />;
  }

  if (navigation.screen === 'log') {
    return (
      <LogView
        onBack={() => {
          setNavigation({
            screen: navigation.previousScreen || 'main',
            contact: navigation.contact,
          });
        }}
      />
    );
  }

  if (navigation.screen === 'profile-select') {
    return (
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
    );
  }

  return <Box>Unknown screen</Box>;
}
