import React, { useState, useEffect } from 'react';
import { Box, useInput } from 'ink';
import { MatrixService } from '../shared/services/MatrixService.js';
import { KeytarCredentialStorage } from './services/KeytarCredentialStorage';
import { silentLogger } from './services/SilentLogger';
import { PROFILES, type ProfileKey } from './types/profile';
import { LoadingView } from './views/LoadingView';
import { ContactListView } from './views/ContactListView';
import { ChatView } from './views/ChatView';
import { LogView } from './views/LogView';
import { ProfileSelectorView } from './views/ProfileSelectorView';

// Create TUI-specific MatrixService instance with keytar storage and silent logger
const credentialStorage = new KeytarCredentialStorage();
export const matrixService = new MatrixService(credentialStorage, silentLogger);

type Screen = 'loading' | 'contacts' | 'chat' | 'log' | 'profile-select';

interface Navigation {
  screen: Screen;
  roomId?: string;
  roomName?: string;
  previousScreen?: Screen;
}

interface AppProps {
  initialProfile?: ProfileKey | null;
}

export function App({ initialProfile }: AppProps) {
  const [navigation, setNavigation] = useState<Navigation>({ screen: 'loading' });
  const [syncState, setSyncState] = useState<string>('STOPPED');
  const [error, setError] = useState<string | null>(null);
  const [currentProfile, setCurrentProfile] = useState<ProfileKey>(
    (initialProfile && PROFILES[initialProfile]) ? initialProfile : 'alice'
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
        const unsubscribe = matrixService.onSyncStateChange((state) => {
          setSyncState(state);
          if (state === 'PREPARED' || state === 'SYNCING') {
            setNavigation({ screen: 'contacts' });
          }
          if (state === 'ERROR') {
            setError('Sync error - retrying...');
          }
        });

        return () => {
          unsubscribe();
        };
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error';
        console.error('Failed to initialize auth:', error);
        setError(`Failed to connect: ${errorMessage}`);
      }
    };

    initAuth();
  }, []);

  const handleSelectContact = (roomId: string, roomName: string) => {
    setNavigation({ screen: 'chat', roomId, roomName });
  };

  const handleBack = () => {
    setNavigation({ screen: 'contacts' });
  };

  const handleSelectProfile = (profileKey: ProfileKey) => {
    setNavigation({ screen: 'loading' });
    switchProfile(profileKey);
  };

  // Profile switching handler
  const switchProfile = async (newProfile: ProfileKey) => {
    try {
      console.log(`[App] Switching profile to ${PROFILES[newProfile].displayName}`);

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
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      console.error('[App] Failed to switch profile:', error);
      setError(`Failed to switch to ${PROFILES[newProfile].displayName}: ${errorMessage}`);
    }
  };

  // Global key handlers
  useInput((input, key) => {
    // Log viewer (always available except in profile-select)
    if (input === 'l' && navigation.screen !== 'profile-select') {
      setNavigation({
        ...navigation,
        screen: 'log',
        previousScreen: navigation.screen,
      });
    }

    // Profile switching (always available, even in error state)
    if (input === 'p' && navigation.screen !== 'profile-select') {
      setNavigation({
        ...navigation,
        screen: 'profile-select',
        previousScreen: navigation.screen,
      });
    }

    // Escape key closes profile selector
    if (key.escape && navigation.screen === 'profile-select') {
      setNavigation({
        screen: navigation.previousScreen || 'contacts',
        roomId: navigation.roomId,
        roomName: navigation.roomName,
      });
    }
  });

  if (navigation.screen === 'loading') {
    return <LoadingView syncState={syncState} error={error} currentProfile={currentProfile} />;
  }

  if (navigation.screen === 'contacts') {
    return <ContactListView onSelectContact={handleSelectContact} currentProfile={currentProfile} />;
  }

  if (navigation.screen === 'chat' && navigation.roomId && navigation.roomName) {
    return (
      <ChatView
        roomId={navigation.roomId}
        roomName={navigation.roomName}
        onBack={handleBack}
        currentProfile={currentProfile}
      />
    );
  }

  if (navigation.screen === 'log') {
    return (
      <LogView
        onBack={() => {
          setNavigation({
            screen: navigation.previousScreen || 'contacts',
            roomId: navigation.roomId,
            roomName: navigation.roomName,
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
            screen: navigation.previousScreen || 'contacts',
            roomId: navigation.roomId,
            roomName: navigation.roomName,
          });
        }}
      />
    );
  }

  return <Box>Unknown screen</Box>;
}
