import { Box, Text } from 'ink';
import Spinner from 'ink-spinner';
import React from 'react';

import { PROFILES, type ProfileKey } from '../types/profile';

interface Props {
  syncState: string;
  error: string | null;
  currentProfile: ProfileKey;
}

// Map sync states to user-friendly messages
const getSyncMessage = (state: string): string => {
  switch (state) {
    case 'STOPPED':
      return 'Starting...';
    case 'PREPARED':
      return 'Ready';
    case 'SYNCING':
      return 'Syncing...';
    case 'ERROR':
      return 'Reconnecting...'; // Transient - will auto-retry
    default:
      return 'Connecting...';
  }
};

export function LoadingView({ syncState, error, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];
  const syncMessage = getSyncMessage(syncState);

  return (
    <Box flexDirection="column" padding={1}>
      <Box marginBottom={1}>
        <Text bold color={profile.color}>
          WATA - Voice Messaging ({profile.displayName})
        </Text>
      </Box>

      {error ? (
        <Box>
          <Text color="red">✗ {error}</Text>
        </Box>
      ) : (
        <Box>
          <Text color="green">
            <Spinner type="dots" />
          </Text>
          <Text> {syncMessage}</Text>
        </Box>
      )}

      {error && (
        <Box marginTop={1}>
          <Text dimColor>Press Ctrl+C to exit</Text>
        </Box>
      )}
    </Box>
  );
}
