import { useState, useEffect } from 'react';
import type {
  MatrixRoom,
  VoiceMessage,
} from '../../shared/services/MatrixService.js';
import { matrixService } from '../App.js';

/**
 * Hook to monitor Matrix sync state
 */
export function useMatrixSync() {
  // Initialize with current sync state (important for late-mounting components)
  const initialState = matrixService.getSyncState();
  const [syncState, setSyncState] = useState<string>(initialState);
  const [isReady, setIsReady] = useState(
    initialState === 'PREPARED' || initialState === 'SYNCING',
  );

  useEffect(() => {
    // Check current state immediately in case it changed before subscription
    const currentState = matrixService.getSyncState();
    if (currentState !== syncState) {
      setSyncState(currentState);
      setIsReady(currentState === 'PREPARED' || currentState === 'SYNCING');
    }

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
