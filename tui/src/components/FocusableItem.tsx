import React from 'react';
import { Box, Text } from 'ink';
import { colors } from '../theme.js';

interface Props {
  label: string;
  sublabel?: string;
  timestamp?: string;
  isFocused: boolean;
  onSelect: () => void;
}

/**
 * Focusable list item for keyboard navigation
 */
export function FocusableItem({ label, sublabel, timestamp, isFocused }: Props) {
  return (
    <Box
      flexDirection="column"
      borderStyle="single"
      borderColor={isFocused ? colors.focus : colors.backgroundLight}
      paddingX={1}
      marginY={0}
    >
      <Box justifyContent="space-between">
        <Text bold color={isFocused ? colors.accent : colors.text}>
          {isFocused ? '▶ ' : '  '}
          {label}
        </Text>
        {timestamp && (
          <Text dimColor>{timestamp}</Text>
        )}
      </Box>
      {sublabel && (
        <Box paddingLeft={2}>
          <Text dimColor>{sublabel}</Text>
        </Box>
      )}
    </Box>
  );
}
