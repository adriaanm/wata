import React, {
  useState,
  useMemo,
  useRef,
  useEffect,
  useCallback,
} from 'react';
import { Box, Text, useInput, useStdout } from 'ink';
import { useVoiceMessages } from '../hooks/useMatrix.js';
import { useAudioPlayer } from '../hooks/useAudioPlayer.js';
import { useAudioRecorder } from '../hooks/useAudioRecorder.js';
import { matrixService } from '../App.js';
import { MessageItem } from '../components/MessageItem.js';
import { PROFILES, type ProfileKey } from '../types/profile';
import { colors } from '../theme.js';
import { LogService } from '../services/LogService.js';

// PTT hold-to-record: detect key release by gap in key repeat events
const PTT_RELEASE_TIMEOUT_MS = 200; // If no Space for this long, assume key released

interface Props {
  roomId: string;
  roomName: string;
  onBack: () => void;
  currentProfile: ProfileKey;
}

interface SelectionState {
  mode: 'normal' | 'visual';
  selectedEventIds: Set<string>;
}

export function ChatView({ roomId, roomName, onBack, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];
  const messages = useVoiceMessages(roomId);
  // Get access token for authenticated media downloads
  const accessToken = matrixService.getAccessToken();
  const { isPlaying, currentUri, playbackError, play, stop, clearError } =
    useAudioPlayer(accessToken || undefined);
  const {
    isRecording,
    recordingDuration,
    recordingError,
    startRecording,
    stopRecording,
    clearError: clearRecordingError,
    formatDuration,
  } = useAudioRecorder();
  const [selectedIndex, setSelectedIndex] = useState(() => 0); // Default to 0, will be updated by useEffect
  const [selection, setSelection] = useState<SelectionState>({
    mode: 'normal',
    selectedEventIds: new Set(),
  });
  const [showConfirmDelete, setShowConfirmDelete] = useState(false);
  const { stdout } = useStdout();

  // Initialize selectedIndex to the most recent message when messages change
  useEffect(() => {
    if (messages.length > 0) {
      // Select the most recent message (last in array)
      setSelectedIndex(messages.length - 1);
      LogService.getInstance().addEntry(
        'log',
        `ChatView: Selected message [${messages.length - 1}] (most recent)`,
      );
    } else {
      setSelectedIndex(0);
    }
  }, [messages.length]); // Only re-run when message count changes (new message added)

  // Log messages when they change (for debugging audio playback issues)
  useEffect(() => {
    LogService.getInstance().addEntry(
      'log',
      `ChatView: Room ${roomId} has ${messages.length} message(s)`,
    );
    // Log each message's details
    messages.forEach((msg, idx) => {
      LogService.getInstance().addEntry(
        'log',
        `ChatView: [${idx}] ${msg.eventId} from ${msg.sender} - ${msg.audioUrl.substring(0, 80)}...`,
      );
    });
  }, [roomId, messages]);

  // PTT hold-to-record state
  const pttTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isStoppingRef = useRef(false); // Prevent double-stop
  const [isHoldingSpace, setIsHoldingSpace] = useState(false); // True if key repeat detected

  // Stop recording and send message
  const doStopAndSend = useCallback(async () => {
    if (isStoppingRef.current || !isRecording) return;
    isStoppingRef.current = true;

    try {
      const result = await stopRecording();
      await matrixService.sendVoiceMessage(
        roomId,
        result.buffer,
        result.mimeType,
        result.duration,
        result.size,
      );
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      LogService.getInstance().addEntry(
        'error',
        `Failed to send voice message: ${errorMsg}`,
      );
    } finally {
      isStoppingRef.current = false;
    }
  }, [isRecording, stopRecording, roomId]);

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

  // Calculate viewport dimensions
  const terminalHeight = stdout?.rows || 24;
  const { visibleMessages, hasMore, startIndex } = useMemo(() => {
    // Reserve space for: header (2), recording status (3), help (2), scroll indicators (2), margins (2)
    // Each message item takes ~4 lines (border + content + hint)
    const HEADER_HEIGHT = 2;
    const STATUS_HEIGHT = 3;
    const HELP_HEIGHT = 2;
    const INDICATOR_HEIGHT = 2;
    const MARGIN_HEIGHT = 2;
    const ITEM_HEIGHT = 4;

    const availableHeight =
      terminalHeight -
      HEADER_HEIGHT -
      STATUS_HEIGHT -
      HELP_HEIGHT -
      INDICATOR_HEIGHT -
      MARGIN_HEIGHT;
    const maxItems = Math.max(1, Math.floor(availableHeight / ITEM_HEIGHT));

    // On larger terminals, leave some breathing room (don't fill entire screen)
    const preferredMaxItems =
      terminalHeight > 30
        ? Math.floor(maxItems * 0.7) // Use 70% of available space
        : maxItems;

    // Calculate window around selected item
    const halfWindow = Math.floor(preferredMaxItems / 2);
    let startIndex = Math.max(0, selectedIndex - halfWindow);
    let endIndex = Math.min(messages.length, startIndex + preferredMaxItems);

    // Adjust if we're near the end
    if (endIndex - startIndex < preferredMaxItems) {
      startIndex = Math.max(0, endIndex - preferredMaxItems);
    }

    return {
      visibleMessages: messages.slice(startIndex, endIndex),
      hasMore: {
        above: startIndex > 0,
        below: endIndex < messages.length,
        aboveCount: startIndex,
        belowCount: messages.length - endIndex,
      },
      startIndex,
    };
  }, [messages, selectedIndex, terminalHeight]);

  // Keyboard navigation
  useInput((input, key) => {
    // Confirmation dialog handling
    if (showConfirmDelete) {
      if (input === 'y') {
        matrixService
          .redactMessages(
            roomId,
            Array.from(selection.selectedEventIds),
            'Bulk deletion',
          )
          .then(() => {
            setSelection({ mode: 'normal', selectedEventIds: new Set() });
            setShowConfirmDelete(false);
          })
          .catch(err => {
            const errorMsg = err instanceof Error ? err.message : String(err);
            LogService.getInstance().addEntry(
              'error',
              `Failed to delete messages: ${errorMsg}`,
            );
            setShowConfirmDelete(false);
          });
      }
      if (input === 'n' || key.escape) {
        setShowConfirmDelete(false);
      }
      return; // Don't process other keys when in confirmation dialog
    }

    // Back navigation
    if (key.escape || key.backspace) {
      if (selection.mode === 'visual') {
        // Exit visual mode first
        setSelection({ mode: 'normal', selectedEventIds: new Set() });
      } else {
        onBack();
      }
      return;
    }

    // Navigation (works in both modes)
    if (key.upArrow || input === 'k') {
      setSelectedIndex(prev => Math.max(0, prev - 1));
      clearError();
      clearRecordingError();
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex(prev => Math.min(messages.length - 1, prev + 1));
      clearError();
      clearRecordingError();
    }

    // Toggle visual mode
    if (input === 'v' && !isRecording) {
      setSelection(prev => ({
        mode: prev.mode === 'normal' ? 'visual' : 'normal',
        selectedEventIds:
          prev.mode === 'visual' ? new Set() : prev.selectedEventIds,
      }));
      return;
    }

    // Visual mode actions
    if (selection.mode === 'visual') {
      // Space toggles selection in visual mode
      if (input === ' ') {
        const message = messages[selectedIndex];
        if (message) {
          setSelection(prev => {
            const newSet = new Set(prev.selectedEventIds);
            if (newSet.has(message.eventId)) {
              newSet.delete(message.eventId);
            } else {
              newSet.add(message.eventId);
            }
            return { ...prev, selectedEventIds: newSet };
          });
        }
        return;
      }

      // Select all
      if (input === 'a') {
        setSelection(prev => ({
          ...prev,
          selectedEventIds: new Set(messages.map(m => m.eventId)),
        }));
        return;
      }

      // Deselect all
      if (input === 'A') {
        setSelection(prev => ({
          ...prev,
          selectedEventIds: new Set(),
        }));
        return;
      }

      // Delete selected
      if (
        (input === 'd' || key.delete) &&
        selection.selectedEventIds.size > 0
      ) {
        setShowConfirmDelete(true);
        return;
      }
    }

    // Normal mode actions
    if (selection.mode === 'normal') {
      // Enter to play/pause
      if (key.return) {
        const message = messages[selectedIndex];
        if (message) {
          // Log which message is being played
          LogService.getInstance().addEntry(
            'log',
            `ChatView: Playing message [${selectedIndex}] ${message.eventId} from ${message.sender}`,
          );
          LogService.getInstance().addEntry(
            'log',
            `ChatView: Audio URL = ${message.audioUrl}`,
          );

          if (isPlaying && currentUri === message.audioUrl) {
            stop();
          } else {
            play(message.audioUrl);
          }
        } else {
          LogService.getInstance().addEntry(
            'warn',
            `ChatView: No message at index ${selectedIndex} (total: ${messages.length})`,
          );
        }
      }

      // Space for PTT (hold-to-record)
      // When holding Space, terminal sends repeated key events.
      // We detect key release by noticing when events stop coming.
      if (input === ' ') {
        if (isRecording) {
          // Already recording - this is a repeated key event (holding) or explicit tap to stop
          setIsHoldingSpace(true); // We've detected key repeat, so user is holding
          if (pttTimeoutRef.current) {
            clearTimeout(pttTimeoutRef.current);
          }
          // Set timeout to auto-stop when key appears released
          pttTimeoutRef.current = setTimeout(() => {
            doStopAndSend();
          }, PTT_RELEASE_TIMEOUT_MS);
        } else {
          // Start recording
          startRecording().catch(err => {
            const errorMsg = err instanceof Error ? err.message : String(err);
            LogService.getInstance().addEntry(
              'error',
              `Failed to start recording: ${errorMsg}`,
            );
          });
          // Set initial timeout in case user just taps Space briefly
          pttTimeoutRef.current = setTimeout(() => {
            doStopAndSend();
          }, PTT_RELEASE_TIMEOUT_MS);
        }
      }
    }
  });

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={profile.color}>
          ← {roomName}
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
          <Text>{formatDuration(recordingDuration)}</Text>
          <Text> </Text>
          <Text dimColor>
            {isHoldingSpace
              ? '(Release Space to send)'
              : '(Press Space again to send)'}
          </Text>
        </Box>
      )}

      {/* Playback Error Status */}
      {playbackError && !isRecording && (
        <Box
          marginBottom={1}
          borderStyle="double"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>⚠ Playback Error: {playbackError}</Text>
        </Box>
      )}

      {/* Recording Error Status */}
      {recordingError && !isRecording && (
        <Box
          marginBottom={1}
          borderStyle="double"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>⚠ Recording Error: {recordingError}</Text>
        </Box>
      )}

      {/* Ready Status */}
      {!isRecording &&
        selection.mode === 'normal' &&
        !playbackError &&
        !recordingError && (
          <Box
            marginBottom={1}
            borderStyle="single"
            borderColor={colors.textMuted}
            paddingX={1}
          >
            <Text dimColor>Hold Space to record</Text>
          </Box>
        )}

      {/* Visual mode indicator */}
      {selection.mode === 'visual' && !showConfirmDelete && (
        <Box
          marginBottom={1}
          borderStyle="single"
          borderColor="yellow"
          paddingX={1}
        >
          <Text color="yellow">
            VISUAL MODE - {selection.selectedEventIds.size} selected
          </Text>
        </Box>
      )}

      {/* Confirmation dialog */}
      {showConfirmDelete && (
        <Box
          marginBottom={1}
          borderStyle="double"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>
            Delete {selection.selectedEventIds.size} message(s)? [y/n]
          </Text>
        </Box>
      )}

      {/* Messages */}
      {messages.length === 0 && (
        <Box>
          <Text dimColor>No messages yet. Press Space to record.</Text>
        </Box>
      )}

      {/* Scroll indicator - more above */}
      {hasMore.above && (
        <Box justifyContent="center" marginBottom={0}>
          <Text dimColor>⬆ {hasMore.aboveCount} more above ⬆</Text>
        </Box>
      )}

      {visibleMessages.map((message, visibleIndex) => {
        const actualIndex = startIndex + visibleIndex;
        return (
          <MessageItem
            key={message.eventId}
            message={message}
            isFocused={actualIndex === selectedIndex}
            isPlaying={isPlaying && currentUri === message.audioUrl}
            isSelected={selection.selectedEventIds.has(message.eventId)}
            selectionMode={selection.mode === 'visual'}
          />
        );
      })}

      {/* Scroll indicator - more below */}
      {hasMore.below && (
        <Box justifyContent="center" marginTop={0}>
          <Text dimColor>⬇ {hasMore.belowCount} more below ⬇</Text>
        </Box>
      )}

      {/* Help text */}
      <Box marginTop={1}>
        <Text dimColor>
          {selection.mode === 'normal'
            ? '↑↓/jk Navigate  Enter Play  Space PTT  v Visual  l Logs  Esc Back'
            : '↑↓/jk Navigate  Space Toggle  a All  A None  d Delete  v/Esc Exit'}
        </Text>
      </Box>
    </Box>
  );
}
