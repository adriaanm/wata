import { useState, useEffect, useCallback, useMemo } from 'react';

import { matrixService } from '../App.js';

/**
 * Status tracking for contacts - unread incoming messages and send failures
 */
export interface ContactStatus {
  hasUnread: boolean;
  hasError: boolean;
}

/**
 * Hook to track contact status (unread messages and send errors)
 *
 * Tracks:
 * - Unread incoming voice messages per room/user
 * - Failed outgoing message attempts per contact
 */
export function useContactStatus() {
  // Map of roomId -> unread count (incoming messages not yet viewed)
  const [unreadByRoom, setUnreadByRoom] = useState<Map<string, number>>(
    new Map(),
  );

  // Map of contactId -> error message (last send failure)
  const [errorByContact, setErrorByContact] = useState<Map<string, string>>(
    new Map(),
  );

  // Subscribe to new incoming voice messages
  useEffect(() => {
    const myUserId = matrixService.getUserId();

    const unsubscribe = matrixService.onNewVoiceMessage((roomId, message) => {
      // Only track incoming messages (not our own)
      if (message.sender === myUserId) return;

      setUnreadByRoom(prev => {
        const next = new Map(prev);
        next.set(roomId, (prev.get(roomId) || 0) + 1);
        return next;
      });
    });

    return unsubscribe;
  }, []);

  /**
   * Get status for a contact
   */
  const getStatus = useCallback(
    (contactId: string, roomId: string | null): ContactStatus => {
      const unreadCount = roomId ? unreadByRoom.get(roomId) || 0 : 0;
      const errorMessage = errorByContact.get(contactId);

      return {
        hasUnread: unreadCount > 0,
        hasError: !!errorMessage,
      };
    },
    [unreadByRoom, errorByContact],
  );

  /**
   * Mark a room as read (clear unread count)
   * Call this when user views the history
   */
  const markAsRead = useCallback((roomId: string) => {
    setUnreadByRoom(prev => {
      const next = new Map(prev);
      next.delete(roomId);
      return next;
    });
  }, []);

  /**
   * Record a send error for a contact
   */
  const setSendError = useCallback((contactId: string, error: string) => {
    setErrorByContact(prev => {
      const next = new Map(prev);
      next.set(contactId, error);
      return next;
    });
  }, []);

  /**
   * Clear send error for a contact
   * Call this when user starts a new recording
   */
  const clearSendError = useCallback((contactId: string) => {
    setErrorByContact(prev => {
      const next = new Map(prev);
      next.delete(contactId);
      return next;
    });
  }, []);

  /**
   * Check if any contact has unread messages
   */
  const hasAnyUnread = useMemo(() => {
    for (const count of unreadByRoom.values()) {
      if (count > 0) return true;
    }
    return false;
  }, [unreadByRoom]);

  /**
   * Check if any contact has errors
   */
  const hasAnyError = useMemo(() => {
    return errorByContact.size > 0;
  }, [errorByContact]);

  return {
    getStatus,
    markAsRead,
    setSendError,
    clearSendError,
    hasAnyUnread,
    hasAnyError,
  };
}
