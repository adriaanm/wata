import React from 'react';
import { Box, Text } from 'ink';
import Spinner from 'ink-spinner';
import { PROFILES, type ProfileKey } from '../types/profile';

interface Props {
  syncState: string;
  error: string | null;
  currentProfile: ProfileKey;
}

export function LoadingView({ syncState, error, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];

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
          <Text> Connecting as {profile.displayName}...</Text>
        </Box>
      )}

      <Box marginTop={1}>
        <Text dimColor>Sync State: {syncState}</Text>
      </Box>

      {error && (
        <Box marginTop={1}>
          <Text dimColor>Check Matrix server connection and credentials</Text>
        </Box>
      )}
    </Box>
  );
}
