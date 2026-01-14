import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { useVoiceMessages } from '../hooks/useMatrix.js';
import { useAudioPlayer } from '../hooks/useAudioPlayer.js';
import { useAudioRecorder } from '../hooks/useAudioRecorder.js';
import { matrixService } from '../App.js';
import { MessageItem } from '../components/MessageItem.js';
import { colors } from '../theme.js';

interface Props {
  roomId: string;
  roomName: string;
  onBack: () => void;
}

export function ChatView({ roomId, roomName, onBack }: Props) {
  const messages = useVoiceMessages(roomId);
  const { isPlaying, currentUri, play, stop } = useAudioPlayer();
  const {
    isRecording,
    recordingDuration,
    startRecording,
    stopRecording,
    formatDuration,
  } = useAudioRecorder();
  const [selectedIndex, setSelectedIndex] = useState(0);

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
      const message = messages[selectedIndex];
      if (isPlaying && currentUri === message.audioUrl) {
        stop();
      } else {
        play(message.audioUrl);
      }
    }

    // Space for PTT (toggle mode)
    if (input === ' ') {
      if (isRecording) {
        // Stop recording and send
        stopRecording()
          .then(async (result) => {
            await matrixService.sendVoiceMessage(
              roomId,
              result.buffer,
              result.mimeType,
              result.duration,
              result.size
            );
          })
          .catch((err) => {
            console.error('Failed to send voice message:', err);
          });
      } else {
        // Start recording
        startRecording().catch((err) => {
          console.error('Failed to start recording:', err);
        });
      }
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
          <Text>{formatDuration(recordingDuration)}</Text>
          <Text> </Text>
          <Text dimColor>(Press Space to stop and send)</Text>
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
          isPlaying={isPlaying && currentUri === message.audioUrl}
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
