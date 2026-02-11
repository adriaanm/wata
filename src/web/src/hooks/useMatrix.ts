/**
 * Web-specific hooks for Matrix integration.
 */

import { useState, useEffect, useCallback } from 'react';

import { matrixService } from '../services/matrixService';

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

export function useRooms() {
  const [rooms, setRooms] = useState(matrixService.getDirectRooms());

  useEffect(() => {
    // Subscribe to updates
    const unsubscribe = matrixService.onRoomUpdate(updatedRooms => {
      setRooms(updatedRooms);
    });

    return unsubscribe;
  }, []);

  return rooms;
}

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Try to restore session on mount
    const restore = async () => {
      try {
        const restored = await matrixService.restoreSession();
        setIsLoggedIn(restored);
      } catch {
        setIsLoggedIn(false);
      } finally {
        setIsLoading(false);
      }
    };

    restore();
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    setIsLoading(true);
    setError(null);
    try {
      await matrixService.login(username, password);
      setIsLoggedIn(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
      throw err;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    setIsLoading(true);
    try {
      await matrixService.logout();
      setIsLoggedIn(false);
    } finally {
      setIsLoading(false);
    }
  }, []);

  return { isLoggedIn, isLoading, error, login, logout };
}

export function useVoiceMessages(roomId: string) {
  const [messages, setMessages] = useState(
    matrixService.getVoiceMessages(roomId),
  );

  useEffect(() => {
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
