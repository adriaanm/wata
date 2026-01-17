import { Box, Text } from 'ink';
import React from 'react';

import type { VoiceMessage } from '../../shared/services/MatrixService.js';
import { colors } from '../theme.js';

interface Props {
  message: VoiceMessage;
  isFocused: boolean;
  isPlaying: boolean;
  isSelected?: boolean;
  selectionMode?: boolean;
}

/**
 * Display a voice message in the chat view
 */
export function MessageItem({
  message,
  isFocused,
  isPlaying,
  isSelected = false,
  selectionMode = false,
}: Props) {
  const formatDuration = (ms: number): string => {
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };

  const formatTime = (timestamp: number): string => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: true,
    });
  };

  const icon = isPlaying ? '[▌▌]' : '[▶]';

  // Determine border styling based on selection state
  const borderColor = isSelected
    ? 'yellow'
    : isFocused
      ? colors.focus
      : colors.backgroundLight;
  const borderStyle = isSelected ? 'double' : 'single';

  return (
    <Box
      flexDirection="row"
      borderStyle={borderStyle}
      borderColor={borderColor}
      paddingX={1}
      marginY={0}
      justifyContent={message.isOwn ? 'flex-end' : 'flex-start'}
    >
      {/* Checkbox in visual mode */}
      {selectionMode && (
        <Box marginRight={1}>
          <Text>{isSelected ? '[✓] ' : '[ ] '}</Text>
        </Box>
      )}

      <Box flexDirection="column">
        <Box>
          <Text color={message.isOwn ? colors.accent : colors.text}>
            {message.senderName}
          </Text>
          <Text> </Text>
          <Text color={isPlaying ? colors.playing : colors.text}>{icon}</Text>
          <Text> </Text>
          <Text dimColor>{formatDuration(message.duration)}</Text>
          <Text> </Text>
          <Text dimColor>{formatTime(message.timestamp)}</Text>
        </Box>
        {isFocused && (
          <Box>
            <Text dimColor>▶ Press Enter to play</Text>
          </Box>
        )}
      </Box>
    </Box>
  );
}
