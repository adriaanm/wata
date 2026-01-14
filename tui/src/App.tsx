import React, { useState, useEffect } from 'react';
import { Box } from 'ink';
import { MatrixService } from '@shared/services/MatrixService';
import { KeytarCredentialStorage } from './services/KeytarCredentialStorage.js';
import { LoadingView } from './views/LoadingView.js';
import { ContactListView } from './views/ContactListView.js';
import { ChatView } from './views/ChatView.js';

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
  const [error, setError] = useState<string | null>(null);

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

  if (navigation.screen === 'loading') {
    return <LoadingView syncState={syncState} error={error} />;
  }

  if (navigation.screen === 'contacts') {
    return <ContactListView onSelectContact={handleSelectContact} />;
  }

  if (navigation.screen === 'chat' && navigation.roomId && navigation.roomName) {
    return (
      <ChatView
        roomId={navigation.roomId}
        roomName={navigation.roomName}
        onBack={handleBack}
      />
    );
  }

  return <Box>Unknown screen</Box>;
}
