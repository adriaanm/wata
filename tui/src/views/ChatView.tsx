import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { useVoiceMessages } from '../hooks/useMatrix.js';
import { MessageItem } from '../components/MessageItem.js';
import { colors } from '../theme.js';

interface Props {
  roomId: string;
  roomName: string;
  onBack: () => void;
}

export function ChatView({ roomId, roomName, onBack }: Props) {
  const messages = useVoiceMessages(roomId);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [isRecording, setIsRecording] = useState(false);
  const [playingMessageId, setPlayingMessageId] = useState<string | null>(null);

  // Keyboard navigation
  useInput((input, key) => {
    if (key.escape || key.backspace) {
      onBack();
    }

    if (key.upArrow || input === 'k') {
      setSelectedIndex(prev => Math.max(0, prev - 1));
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex(prev => Math.min(messages.length - 1, prev + 1));
    }

    if (key.return && messages[selectedIndex]) {
      // TODO: Implement playback in Phase 4
      const message = messages[selectedIndex];
      if (playingMessageId === message.eventId) {
        setPlayingMessageId(null);
      } else {
        setPlayingMessageId(message.eventId);
      }
    }

    // Space for PTT (toggle for now, will be hold-to-record in Phase 5)
    if (input === ' ') {
      setIsRecording(prev => !prev);
    }
  });

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={colors.accent}>
          ← {roomName}
        </Text>
        <Text dimColor>[Esc] back</Text>
      </Box>

      {/* Recording Status */}
      {isRecording && (
        <Box marginBottom={1} borderStyle="single" borderColor={colors.recording} paddingX={1}>
          <Text color={colors.recording}>● REC</Text>
          <Text> </Text>
          <Text>0:00</Text>
        </Box>
      )}

      {/* Ready Status */}
      {!isRecording && (
        <Box marginBottom={1} borderStyle="single" borderColor={colors.textMuted} paddingX={1}>
          <Text dimColor>Space to record</Text>
        </Box>
      )}

      {/* Messages */}
      {messages.length === 0 && (
        <Box>
          <Text dimColor>No messages yet. Press Space to record.</Text>
        </Box>
      )}

      {messages.map((message, index) => (
        <MessageItem
          key={message.eventId}
          message={message}
          isFocused={index === selectedIndex}
          isPlaying={playingMessageId === message.eventId}
        />
      ))}

      {/* Help text */}
      <Box marginTop={1}>
        <Text dimColor>
          ↑↓/jk Navigate  Enter Play  Space PTT  Esc Back
        </Text>
      </Box>
    </Box>
  );
}
