import { useState, useEffect } from 'react';
import type { MatrixRoom, VoiceMessage } from '../../shared/services/MatrixService.js';
import { matrixService } from '../App.js';

/**
 * Hook to monitor Matrix sync state
 */
export function useMatrixSync() {
  const [syncState, setSyncState] = useState<string>('STOPPED');
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    const unsubscribe = matrixService.onSyncStateChange(state => {
      setSyncState(state);
      setIsReady(state === 'PREPARED' || state === 'SYNCING');
    });

    return unsubscribe;
  }, []);

  return { syncState, isReady };
}

/**
 * Hook to get and subscribe to room updates
 */
export function useRooms() {
  const [rooms, setRooms] = useState<MatrixRoom[]>([]);

  useEffect(() => {
    // Get initial rooms
    setRooms(matrixService.getDirectRooms());

    // Subscribe to updates
    const unsubscribe = matrixService.onRoomUpdate(updatedRooms => {
      setRooms(updatedRooms);
    });

    return unsubscribe;
  }, []);

  return rooms;
}

/**
 * Hook to get and subscribe to voice messages in a room
 */
export function useVoiceMessages(roomId: string) {
  const [messages, setMessages] = useState<VoiceMessage[]>([]);

  useEffect(() => {
    // Get initial messages
    setMessages(matrixService.getVoiceMessages(roomId));

    // Subscribe to new messages
    const unsubscribe = matrixService.onNewVoiceMessage(
      (msgRoomId, message) => {
        if (msgRoomId === roomId) {
          setMessages(prev => [...prev, message]);
        }
      },
    );

    return unsubscribe;
  }, [roomId]);

  return messages;
}
