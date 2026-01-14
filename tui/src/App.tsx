import React, { useState, useEffect } from 'react';
import { Box } from 'ink';
import { MatrixService } from '@shared/services/MatrixService';
import { KeytarCredentialStorage } from './services/KeytarCredentialStorage';
import { LoadingView } from './views/LoadingView';

// Create TUI-specific MatrixService instance with keytar storage
const credentialStorage = new KeytarCredentialStorage();
export const matrixService = new MatrixService(credentialStorage);

type Screen = 'loading' | 'contacts' | 'chat';

interface Navigation {
  screen: Screen;
  roomId?: string;
  roomName?: string;
}

export function App() {
  const [navigation, setNavigation] = useState<Navigation>({ screen: 'loading' });
  const [syncState, setSyncState] = useState<string>('STOPPED');

  useEffect(() => {
    const initAuth = async () => {
      try {
        // Try to restore session
        const restored = await matrixService.restoreSession();
        if (!restored) {
          // Fall back to auto-login
          await matrixService.autoLogin();
        }

        // Listen for sync state changes
        const unsubscribe = matrixService.onSyncStateChange((state) => {
          setSyncState(state);
          if (state === 'PREPARED' || state === 'SYNCING') {
            setNavigation({ screen: 'contacts' });
          }
        });

        return () => {
          unsubscribe();
        };
      } catch (error) {
        console.error('Failed to initialize auth:', error);
      }
    };

    initAuth();
  }, []);

  if (navigation.screen === 'loading') {
    return <LoadingView syncState={syncState} />;
  }

  // TODO: Implement ContactListView and ChatView
  return (
    <Box flexDirection="column">
      <Box>Screen: {navigation.screen}</Box>
      <Box>Sync State: {syncState}</Box>
    </Box>
  );
}
