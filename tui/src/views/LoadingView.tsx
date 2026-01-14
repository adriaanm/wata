import React from 'react';
import { Box, Text } from 'ink';
import Spinner from 'ink-spinner';

interface Props {
  syncState: string;
  error: string | null;
}

export function LoadingView({ syncState, error }: Props) {
  return (
    <Box flexDirection="column" padding={1}>
      <Box marginBottom={1}>
        <Text bold color="cyan">
          WATA - Voice Messaging
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
          <Text> Connecting to Matrix...</Text>
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
