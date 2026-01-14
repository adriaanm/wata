import React from 'react';
import { Box, Text } from 'ink';
import Spinner from 'ink-spinner';

interface Props {
  syncState: string;
}

export function LoadingView({ syncState }: Props) {
  return (
    <Box flexDirection="column" padding={1}>
      <Box marginBottom={1}>
        <Text bold color="cyan">
          WATA - Voice Messaging
        </Text>
      </Box>

      <Box>
        <Text color="green">
          <Spinner type="dots" />
        </Text>
        <Text> Connecting to Matrix...</Text>
      </Box>

      <Box marginTop={1}>
        <Text dimColor>Sync State: {syncState}</Text>
      </Box>
    </Box>
  );
}
