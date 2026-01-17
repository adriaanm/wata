import React, {
  useState,
  useMemo,
  useEffect,
  useRef,
  useCallback,
} from 'react';
import { Box, Text, useInput, useStdout } from 'ink';
import { useVoiceMessages } from '../hooks/useMatrix.js';
import { useAudioPlayer } from '../hooks/useAudioPlayer.js';
import { useAudioRecorder } from '../hooks/useAudioRecorder.js';
import { matrixService } from '../App.js';
import { PROFILES, type ProfileKey } from '../types/profile';
import { colors } from '../theme.js';
import { LogService } from '../services/LogService.js';

// PTT hold-to-record: detect key release by gap in key repeat events
const PTT_RELEASE_TIMEOUT_MS = 200;

interface Props {
  roomId: string;
  contactName: string;
  contactType: 'dm' | 'family';
  onBack: () => void;
  currentProfile: ProfileKey;
}

export function HistoryView({
  roomId,
  contactName,
  contactType,
  onBack,
  currentProfile,
}: Props) {
  const profile = PROFILES[currentProfile];
  const allMessages = useVoiceMessages(roomId);
  const accessToken = matrixService.getAccessToken();
  const { isPlaying, currentUri, playbackError, play, stop, clearError } =
    useAudioPlayer(accessToken || undefined);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const { stdout } = useStdout();

  // Audio recording
  const {
    isRecording,
    recordingDuration,
    recordingError,
    startRecording,
    stopRecording,
    clearError: clearRecordingError,
    formatDuration: formatRecordingDuration,
  } = useAudioRecorder();

  // PTT state
  const pttTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isStoppingRef = useRef(false);
  const [isHoldingSpace, setIsHoldingSpace] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  // Stop recording and send message
  const doStopAndSend = useCallback(async () => {
    if (isStoppingRef.current || !isRecording) return;
    isStoppingRef.current = true;

    try {
      const result = await stopRecording();

      LogService.getInstance().addEntry(
        'log',
        `Sending voice message to room ${roomId}`,
      );
      await matrixService.sendVoiceMessage(
        roomId,
        result.buffer,
        result.mimeType,
        result.duration,
        result.size,
      );

      LogService.getInstance().addEntry(
        'success',
        `Voice message sent to ${contactName}`,
      );
      setSendError(null);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      LogService.getInstance().addEntry('error', `Failed to send: ${errorMsg}`);
      setSendError(errorMsg);
    } finally {
      isStoppingRef.current = false;
    }
  }, [isRecording, stopRecording, roomId, contactName]);

  // Clear PTT timeout on unmount or when recording stops
  useEffect(() => {
    if (!isRecording) {
      if (pttTimeoutRef.current) {
        clearTimeout(pttTimeoutRef.current);
        pttTimeoutRef.current = null;
      }
      setIsHoldingSpace(false);
    }
    return () => {
      if (pttTimeoutRef.current) {
        clearTimeout(pttTimeoutRef.current);
      }
    };
  }, [isRecording]);

  // Show all messages (both sent and received), most recent first
  const messages = useMemo(() => {
    return [...allMessages].reverse();
  }, [allMessages]);

  // Reset selection when messages change
  useEffect(() => {
    if (messages.length > 0) {
      setSelectedIndex(0); // Most recent (first after reverse)
    }
  }, [messages.length]);

  // Calculate viewport dimensions
  const terminalHeight = stdout?.rows || 24;
  const { visibleMessages, hasMore, startIndex } = useMemo(() => {
    const HEADER_HEIGHT = 2;
    const RECORDING_HEIGHT = isRecording ? 3 : 0;
    const ERROR_HEIGHT = playbackError || recordingError || sendError ? 3 : 0;
    const HELP_HEIGHT = 2;
    const INDICATOR_HEIGHT = 2;
    const MARGIN_HEIGHT = 2;
    const ITEM_HEIGHT = 2;

    const availableHeight =
      terminalHeight -
      HEADER_HEIGHT -
      RECORDING_HEIGHT -
      ERROR_HEIGHT -
      HELP_HEIGHT -
      INDICATOR_HEIGHT -
      MARGIN_HEIGHT;
    const maxItems = Math.max(1, Math.floor(availableHeight / ITEM_HEIGHT));

    const preferredMaxItems =
      terminalHeight > 30 ? Math.floor(maxItems * 0.7) : maxItems;

    const halfWindow = Math.floor(preferredMaxItems / 2);
    let start = Math.max(0, selectedIndex - halfWindow);
    let endIndex = Math.min(messages.length, start + preferredMaxItems);

    if (endIndex - start < preferredMaxItems) {
      start = Math.max(0, endIndex - preferredMaxItems);
    }

    return {
      visibleMessages: messages.slice(start, endIndex),
      hasMore: {
        above: start > 0,
        below: endIndex < messages.length,
        aboveCount: start,
        belowCount: messages.length - endIndex,
      },
      startIndex: start,
    };
  }, [
    messages,
    selectedIndex,
    terminalHeight,
    playbackError,
    isRecording,
    recordingError,
    sendError,
  ]);

  // Format duration (ms to mm:ss)
  const formatDuration = (ms: number): string => {
    const seconds = Math.floor(ms / 1000);
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  // Format timestamp
  const formatTimestamp = (timestamp: number): string => {
    const date = new Date(timestamp);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    const isYesterday = date.toDateString() === yesterday.toDateString();

    if (isToday) {
      return date.toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
      });
    } else if (isYesterday) {
      return 'Yesterday';
    } else {
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    }
  };

  // Keyboard navigation
  useInput((input, key) => {
    // Back navigation
    if (key.escape || key.backspace) {
      onBack();
      return;
    }

    // Navigation
    if (key.upArrow || input === 'k') {
      setSelectedIndex(prev => Math.max(0, prev - 1));
      clearError();
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex(prev => Math.min(messages.length - 1, prev + 1));
      clearError();
    }

    // Enter to play/pause
    if (key.return) {
      const message = messages[selectedIndex];
      if (message) {
        LogService.getInstance().addEntry(
          'log',
          `HistoryView: Playing message ${message.eventId}`,
        );

        if (isPlaying && currentUri === message.audioUrl) {
          stop();
        } else {
          play(message.audioUrl);
          // Mark incoming messages as played by sending a read receipt
          if (!message.isOwn) {
            matrixService
              .markMessageAsPlayed(roomId, message.eventId)
              .catch(err => {
                LogService.getInstance().addEntry(
                  'warn',
                  `Failed to mark message as played: ${err}`,
                );
              });
          }
        }
      }
    }

    // Delete message
    if (input === 'd' || key.delete) {
      const message = messages[selectedIndex];
      if (message) {
        // Stop playback if this message is playing
        if (isPlaying && currentUri === message.audioUrl) {
          stop();
        }

        matrixService.redactMessage(roomId, message.eventId).catch(err => {
          const errorMsg = err instanceof Error ? err.message : String(err);
          LogService.getInstance().addEntry(
            'error',
            `Failed to delete message: ${errorMsg}`,
          );
        });

        // Adjust selection if we deleted the last item
        if (selectedIndex >= messages.length - 1 && selectedIndex > 0) {
          setSelectedIndex(selectedIndex - 1);
        }
      }
    }

    // Space for PTT (hold-to-record)
    if (input === ' ') {
      // Stop any playing audio when recording
      if (isPlaying) {
        stop();
      }

      if (isRecording) {
        setIsHoldingSpace(true);
        if (pttTimeoutRef.current) {
          clearTimeout(pttTimeoutRef.current);
        }
        pttTimeoutRef.current = setTimeout(() => {
          doStopAndSend();
        }, PTT_RELEASE_TIMEOUT_MS);
      } else {
        // Clear any previous send error when starting a new recording
        setSendError(null);
        clearRecordingError();
        startRecording().catch(err => {
          const errorMsg = err instanceof Error ? err.message : String(err);
          LogService.getInstance().addEntry(
            'error',
            `Failed to start recording: ${errorMsg}`,
          );
        });
        pttTimeoutRef.current = setTimeout(() => {
          doStopAndSend();
        }, PTT_RELEASE_TIMEOUT_MS);
      }
    }
  });

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={profile.color}>
          ← {contactName}
        </Text>
        <Text dimColor>[Esc] back</Text>
      </Box>

      {/* Recording Status */}
      {isRecording && (
        <Box
          marginBottom={1}
          borderStyle="single"
          borderColor={colors.recording}
          paddingX={1}
        >
          <Text color={colors.recording}>● REC</Text>
          <Text> </Text>
          <Text>{formatRecordingDuration(recordingDuration)}</Text>
          <Text> </Text>
          <Text dimColor>
            → {contactName}
            {isHoldingSpace ? ' (Release to send)' : ''}
          </Text>
        </Box>
      )}

      {/* Error Status */}
      {(playbackError || recordingError || sendError) && !isRecording && (
        <Box
          marginBottom={1}
          borderStyle="double"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>
            ⚠ {playbackError || recordingError || sendError}
          </Text>
        </Box>
      )}

      {/* Empty state */}
      {messages.length === 0 && (
        <Box>
          <Text color={colors.textMuted}>
            {contactType === 'family'
              ? 'No messages from family members yet'
              : `No messages from ${contactName} yet`}
          </Text>
        </Box>
      )}

      {/* Scroll indicator - more above */}
      {hasMore.above && (
        <Box justifyContent="center" marginBottom={0}>
          <Text dimColor>⬆ {hasMore.aboveCount} older ⬆</Text>
        </Box>
      )}

      {/* Messages list */}
      {visibleMessages.map((message, visibleIndex) => {
        const actualIndex = startIndex + visibleIndex;
        const isFocused = actualIndex === selectedIndex;
        const isCurrentlyPlaying = isPlaying && currentUri === message.audioUrl;
        const wasPlayed = message.readBy && message.readBy.length > 0;

        return (
          <Box key={message.eventId} paddingX={1} marginY={0}>
            {/* Focus indicator */}
            <Text color={isFocused ? colors.focus : undefined}>
              {isFocused ? '▶ ' : '  '}
            </Text>

            {/* Direction indicator and status for own messages */}
            {message.isOwn ? (
              <Text color={wasPlayed ? colors.accent : colors.textMuted}>
                {wasPlayed ? '✓✓' : '✓ '}
              </Text>
            ) : (
              <Text color={colors.textMuted}>{'  '}</Text>
            )}

            {/* Duration */}
            <Text color={isCurrentlyPlaying ? colors.playing : undefined}>
              {formatDuration(message.duration)}
            </Text>

            {/* Spacer */}
            <Box flexGrow={1} />

            {/* Sender name (for family room or to distinguish own messages) */}
            {(contactType === 'family' || message.isOwn) && (
              <Text
                color={message.isOwn ? colors.accent : colors.textMuted}
                dimColor={!message.isOwn}
              >
                {message.isOwn ? 'You' : message.senderName}
                {'  '}
              </Text>
            )}

            {/* Timestamp */}
            <Text dimColor>{formatTimestamp(message.timestamp)}</Text>

            {/* Playing indicator */}
            {isCurrentlyPlaying && <Text color={colors.playing}> ▌▌</Text>}
          </Box>
        );
      })}

      {/* Scroll indicator - more below */}
      {hasMore.below && (
        <Box justifyContent="center" marginTop={0}>
          <Text dimColor>⬇ {hasMore.belowCount} newer ⬇</Text>
        </Box>
      )}

      {/* Help text */}
      <Box marginTop={1}>
        <Text dimColor>
          ↑↓ Navigate Space Talk Enter Play d Delete Esc Back
        </Text>
      </Box>
    </Box>
  );
}
