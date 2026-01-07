import React, {useRef, useEffect} from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  Animated,
} from 'react-native';
import {useVoiceMessages} from '../hooks/useMatrix';
import {useAudioRecorder, useAudioPlayer} from '../hooks/useAudioRecorder';
import {matrixService, VoiceMessage} from '../services/MatrixService';

interface Props {
  roomId: string;
  roomName: string;
  onBack: () => void;
}

export function ChatScreen({roomId, roomName, onBack}: Props) {
  const messages = useVoiceMessages(roomId);
  const {
    isRecording,
    recordingDuration,
    startRecording,
    stopRecording,
    formatDuration,
  } = useAudioRecorder();
  const {isPlaying, currentUri, play, stop} = useAudioPlayer();

  const pulseAnim = useRef(new Animated.Value(1)).current;
  const flatListRef = useRef<FlatList>(null);

  // Pulse animation when recording
  useEffect(() => {
    if (isRecording) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 1.2,
            duration: 500,
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1,
            duration: 500,
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
        flatListRef.current?.scrollToEnd({animated: true});
      }, 100);
    }
  }, [messages.length]);

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

      // Send the voice message
      await matrixService.sendVoiceMessage(
        roomId,
        result.uri,
        result.mimeType,
        result.duration,
        result.size,
      );
    } catch (err) {
      console.error('Failed to send voice message:', err);
    }
  };

  const handlePlayMessage = async (message: VoiceMessage) => {
    if (isPlaying && currentUri === message.audioUrl) {
      await stop();
    } else {
      await play(message.audioUrl);
    }
  };

  const renderMessage = ({item}: {item: VoiceMessage}) => {
    const isCurrentlyPlaying = isPlaying && currentUri === item.audioUrl;

    return (
      <TouchableOpacity
        style={[
          styles.messageItem,
          item.isOwn ? styles.ownMessage : styles.otherMessage,
        ]}
        onPress={() => handlePlayMessage(item)}>
        <View style={styles.messageContent}>
          <Text style={styles.senderName}>
            {item.isOwn ? 'You' : item.senderName}
          </Text>
          <View style={styles.audioIndicator}>
            <View
              style={[
                styles.playIcon,
                isCurrentlyPlaying && styles.playIconActive,
              ]}
            />
            <Text style={styles.duration}>
              {formatDuration(item.duration)}
            </Text>
          </View>
        </View>
      </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={onBack} style={styles.backButton}>
          <Text style={styles.backText}>Back</Text>
        </TouchableOpacity>
        <Text style={styles.title} numberOfLines={1}>
          {roomName}
        </Text>
        <View style={styles.placeholder} />
      </View>

      <FlatList
        ref={flatListRef}
        data={messages}
        keyExtractor={item => item.eventId}
        renderItem={renderMessage}
        contentContainerStyle={styles.messageList}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>No voice messages yet</Text>
            <Text style={styles.emptyHint}>
              Hold the button below to record
            </Text>
          </View>
        }
      />

      <View style={styles.pttContainer}>
        {isRecording && (
          <Text style={styles.recordingDuration}>
            {formatDuration(recordingDuration)}
          </Text>
        )}

        <Animated.View style={{transform: [{scale: pulseAnim}]}}>
          <TouchableOpacity
            style={[styles.pttButton, isRecording && styles.pttButtonActive]}
            onPressIn={handlePTTPress}
            onPressOut={handlePTTRelease}
            activeOpacity={0.8}>
            <Text style={styles.pttText}>
              {isRecording ? 'Recording...' : 'Hold to Talk'}
            </Text>
          </TouchableOpacity>
        </Animated.View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    paddingTop: 48,
    borderBottomWidth: 1,
    borderBottomColor: '#2a2a4a',
  },
  backButton: {
    padding: 8,
  },
  backText: {
    color: '#4a90d9',
    fontSize: 16,
  },
  title: {
    flex: 1,
    fontSize: 20,
    fontWeight: 'bold',
    color: '#fff',
    textAlign: 'center',
    marginHorizontal: 8,
  },
  placeholder: {
    width: 50,
  },
  messageList: {
    padding: 16,
    flexGrow: 1,
  },
  messageItem: {
    maxWidth: '80%',
    borderRadius: 16,
    padding: 12,
    marginBottom: 12,
  },
  ownMessage: {
    backgroundColor: '#4a90d9',
    alignSelf: 'flex-end',
  },
  otherMessage: {
    backgroundColor: '#2a2a4a',
    alignSelf: 'flex-start',
  },
  messageContent: {
    flexDirection: 'column',
  },
  senderName: {
    color: 'rgba(255, 255, 255, 0.7)',
    fontSize: 12,
    marginBottom: 4,
  },
  audioIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  playIcon: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: 'rgba(255, 255, 255, 0.5)',
    marginRight: 8,
  },
  playIconActive: {
    backgroundColor: '#4caf50',
  },
  duration: {
    color: '#fff',
    fontSize: 16,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 48,
  },
  emptyText: {
    color: '#888',
    fontSize: 18,
    marginBottom: 8,
  },
  emptyHint: {
    color: '#666',
    fontSize: 14,
  },
  pttContainer: {
    padding: 24,
    paddingBottom: 40,
    alignItems: 'center',
  },
  recordingDuration: {
    color: '#ff6b6b',
    fontSize: 18,
    marginBottom: 16,
  },
  pttButton: {
    width: 160,
    height: 160,
    borderRadius: 80,
    backgroundColor: '#4a90d9',
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 8,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 8,
  },
  pttButtonActive: {
    backgroundColor: '#ff6b6b',
  },
  pttText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
});
