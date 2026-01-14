import React, { useRef, useEffect } from 'react';
import { View, Text, FlatList, StyleSheet, Animated } from 'react-native';
import { Buffer } from 'buffer';
import RNFS from 'react-native-fs';

import { FocusablePressable } from '../components/FocusablePressable';
import { useAudioRecorder, useAudioPlayer } from '../hooks/useAudioRecorder';
import { useVoiceMessages } from '../hooks/useMatrix';
import { matrixService, VoiceMessage } from '../services/MatrixService';
import { colors, typography, spacing, components } from '../theme';

interface Props {
  roomId: string;
  roomName: string;
  onBack: () => void;
}

export function ChatScreen({ roomId, roomName, onBack }: Props) {
  const messages = useVoiceMessages(roomId);
  const {
    isRecording,
    recordingDuration,
    startRecording,
    stopRecording,
    formatDuration,
  } = useAudioRecorder();
  const { isPlaying, currentUri, play, stop } = useAudioPlayer();

  const pulseAnim = useRef(new Animated.Value(1)).current;
  const flatListRef = useRef<FlatList>(null);

  // Pulse animation when recording
  useEffect(() => {
    if (isRecording) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 0.3,
            duration: 400,
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1,
            duration: 400,
            useNativeDriver: true,
          }),
        ]),
      ).start();
    } else {
      pulseAnim.setValue(1);
    }
  }, [isRecording, pulseAnim]);

  // Scroll to bottom when new messages arrive
  useEffect(() => {
    if (messages.length > 0) {
      setTimeout(() => {
        flatListRef.current?.scrollToEnd({ animated: true });
      }, 100);
    }
  }, [messages.length]);

  // Hardware PTT button handlers - will be connected to native module
  const handlePTTPress = async () => {
    try {
      await startRecording();
    } catch (err) {
      console.error('Failed to start recording:', err);
    }
  };

  const handlePTTRelease = async () => {
    try {
      const result = await stopRecording();

      // Read the audio file as a buffer
      const fileContent = await RNFS.readFile(result.uri, 'base64');
      const buffer = Buffer.from(fileContent, 'base64');

      // Send the voice message
      await matrixService.sendVoiceMessage(
        roomId,
        buffer,
        result.mimeType,
        result.duration,
        result.size,
      );
    } catch (err) {
      console.error('Failed to send voice message:', err);
    }
  };

  // Expose handlers for hardware PTT button (will be used by native module)
  // For now, also expose via global for testing
  useEffect(() => {
    (global as Record<string, unknown>).pttPress = handlePTTPress;
    (global as Record<string, unknown>).pttRelease = handlePTTRelease;
    return () => {
      delete (global as Record<string, unknown>).pttPress;
      delete (global as Record<string, unknown>).pttRelease;
    };
  });

  const handlePlayMessage = async (message: VoiceMessage) => {
    if (isPlaying && currentUri === message.audioUrl) {
      await stop();
    } else {
      await play(message.audioUrl);
    }
  };

  const renderMessage = ({ item }: { item: VoiceMessage }) => {
    const isCurrentlyPlaying = isPlaying && currentUri === item.audioUrl;

    return (
      <FocusablePressable
        style={[
          styles.messageItem,
          item.isOwn ? styles.ownMessage : styles.otherMessage,
        ]}
        focusedStyle={styles.messageItemFocused}
        onPress={() => handlePlayMessage(item)}
      >
        <View style={styles.messageRow}>
          <View
            style={[
              styles.playIcon,
              isCurrentlyPlaying && styles.playIconActive,
            ]}
          />
          <Text style={styles.duration}>{formatDuration(item.duration)}</Text>
          <Text style={styles.sender} numberOfLines={1}>
            {item.isOwn ? 'You' : item.senderName}
          </Text>
        </View>
      </FocusablePressable>
    );
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <FocusablePressable
          style={styles.backButton}
          focusedStyle={styles.backButtonFocused}
          onPress={onBack}
        >
          <Text style={styles.backText}>{'<'}</Text>
        </FocusablePressable>
        <Text style={styles.title} numberOfLines={1}>
          {roomName}
        </Text>
      </View>

      {/* Recording status bar */}
      {isRecording ? (
        <Animated.View style={[styles.recordingBar, { opacity: pulseAnim }]}>
          <Text style={styles.recordingText}>
            REC {formatDuration(recordingDuration)}
          </Text>
        </Animated.View>
      ) : (
        <View style={styles.statusBar}>
          <Text style={styles.statusText}>PTT to record</Text>
        </View>
      )}

      {/* Message list */}
      <FlatList
        ref={flatListRef}
        data={messages}
        keyExtractor={item => item.eventId}
        renderItem={renderMessage}
        contentContainerStyle={styles.messageList}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>No messages</Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    ...components.screen,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.sm,
    backgroundColor: colors.bgSecondary,
    borderBottomWidth: 1,
    borderBottomColor: colors.bgHighlight,
  },
  backButton: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  backButtonFocused: {
    borderColor: colors.focus,
  },
  backText: {
    ...typography.large,
    color: colors.primary,
    fontWeight: 'bold',
  },
  title: {
    ...typography.header,
    flex: 1,
    marginLeft: spacing.sm,
  },
  recordingBar: {
    backgroundColor: colors.recording,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  recordingText: {
    ...typography.status,
    color: colors.text,
  },
  statusBar: {
    backgroundColor: colors.bgSecondary,
    paddingVertical: spacing.sm,
    alignItems: 'center',
  },
  statusText: {
    ...typography.small,
    color: colors.textMuted,
  },
  messageList: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.sm,
    flexGrow: 1,
  },
  messageItem: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    marginBottom: spacing.xs,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  messageItemFocused: {
    borderColor: colors.focus,
  },
  ownMessage: {
    backgroundColor: colors.primary,
    alignSelf: 'flex-end',
    maxWidth: '85%',
  },
  otherMessage: {
    backgroundColor: colors.bgSecondary,
    alignSelf: 'flex-start',
    maxWidth: '85%',
  },
  messageRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  playIcon: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: colors.textSecondary,
    marginRight: spacing.sm,
  },
  playIconActive: {
    backgroundColor: colors.playing,
  },
  duration: {
    ...typography.body,
    marginRight: spacing.sm,
  },
  sender: {
    ...typography.small,
    flex: 1,
  },
  emptyContainer: {
    ...components.emptyState,
  },
  emptyText: {
    ...components.emptyText,
  },
});
