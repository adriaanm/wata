/**
 * Hook to build contacts list from Matrix data.
 * Combines family members and direct message rooms.
 * Tracks real unread counts from Matrix rooms.
 */

import { useState, useEffect, useMemo, useCallback } from 'react';

import { matrixService } from '../services/matrixService';
import type { Contact } from '../types.js';

import { useMatrixSync } from './useMatrix.js';

interface UnreadCounts {
  // Map of contact ID (userId or 'family') -> unread count
  [contactId: string]: number;
}

interface DmRoomMap {
  // Map of userId -> roomId
  [userId: string]: string;
}

export function useContacts(): Contact[] {
  const { isReady } = useMatrixSync();
  const [familyMembers, setFamilyMembers] = useState<
    Array<{
      userId: string;
      displayName: string;
      avatarUrl: string | null;
    }>
  >([]);
  const [familyRoomId, setFamilyRoomId] = useState<string | null>(null);
  const [dmRoomMap, setDmRoomMap] = useState<DmRoomMap>({});
  const [unreadCounts, setUnreadCounts] = useState<UnreadCounts>({});

  // Get unread count for a room using Matrix SDK
  const getUnreadCount = useCallback((roomId: string): number => {
    const client = matrixService.getClient();
    if (!client) return 0;

    const room = client.getRoom(roomId);
    if (!room) return 0;

    // Get unread notification count from Matrix
    // This counts messages since the last read receipt
    return room.getUnreadNotificationCount() ?? 0;
  }, []);

  // Update unread counts for all known rooms
  const refreshUnreadCounts = useCallback(() => {
    const newCounts: UnreadCounts = {};

    // Check family room
    if (familyRoomId) {
      newCounts.family = getUnreadCount(familyRoomId);
    }

    // Check DM rooms
    for (const [userId, roomId] of Object.entries(dmRoomMap)) {
      newCounts[userId] = getUnreadCount(roomId);
    }

    setUnreadCounts(newCounts);
  }, [familyRoomId, dmRoomMap, getUnreadCount]);

  // Load family members from family room
  useEffect(() => {
    const loadFamily = async () => {
      try {
        const members = await matrixService.getFamilyMembers();
        setFamilyMembers(members);
        const roomId = await matrixService.getFamilyRoomId();
        setFamilyRoomId(roomId);

        // Load DM room IDs for each family member
        const newDmRoomMap: DmRoomMap = {};
        for (const member of members) {
          try {
            // Get or create DM room (won't create if already exists)
            const dmRoomId = await matrixService.getOrCreateDmRoom(
              member.userId,
            );
            newDmRoomMap[member.userId] = dmRoomId;
          } catch (err) {
            console.error(`Failed to get DM room for ${member.userId}:`, err);
          }
        }
        setDmRoomMap(newDmRoomMap);
      } catch (err) {
        console.error('Failed to load family room:', err);
      }
    };
    if (isReady) {
      loadFamily();
    }
  }, [isReady]);

  // Subscribe to new voice messages to update unread counts
  useEffect(() => {
    const unsubscribe = matrixService.onNewVoiceMessage((roomId, _message) => {
      // Find which contact this room belongs to
      if (roomId === familyRoomId) {
        setUnreadCounts(prev => ({
          ...prev,
          family: (prev.family ?? 0) + 1,
        }));
        return;
      }

      for (const [userId, dmRoomId] of Object.entries(dmRoomMap)) {
        if (dmRoomId === roomId) {
          setUnreadCounts(prev => ({
            ...prev,
            [userId]: (prev[userId] ?? 0) + 1,
          }));
          return;
        }
      }
    });

    return unsubscribe;
  }, [familyRoomId, dmRoomMap]);

  // Subscribe to read receipt updates
  useEffect(() => {
    const unsubscribe = matrixService.onReceiptUpdate(roomId => {
      // Refresh count for this room
      if (roomId === familyRoomId) {
        setUnreadCounts(prev => ({
          ...prev,
          family: getUnreadCount(roomId),
        }));
        return;
      }

      for (const [userId, dmRoomId] of Object.entries(dmRoomMap)) {
        if (dmRoomId === roomId) {
          setUnreadCounts(prev => ({
            ...prev,
            [userId]: getUnreadCount(roomId),
          }));
          return;
        }
      }
    });

    return unsubscribe;
  }, [familyRoomId, dmRoomMap, getUnreadCount]);

  // Initial refresh of unread counts when rooms are loaded
  useEffect(() => {
    if (familyRoomId || Object.keys(dmRoomMap).length > 0) {
      refreshUnreadCounts();
    }
  }, [familyRoomId, dmRoomMap, refreshUnreadCounts]);

  // Build contacts list from family members
  const contacts: Contact[] = useMemo(() => {
    const result: Contact[] = [];

    // Add family members as contacts
    for (const member of familyMembers) {
      result.push({
        id: member.userId,
        name: member.displayName,
        type: 'dm',
        unreadCount: unreadCounts[member.userId] ?? 0,
        hasError: false,
        avatarUrl: member.avatarUrl || undefined,
      });
    }

    // Add family broadcast option if we have family room
    if (familyRoomId && familyMembers.length > 0) {
      result.push({
        id: 'family',
        name: 'Family',
        type: 'family',
        unreadCount: unreadCounts.family ?? 0,
        hasError: false,
      });
    }

    return result;
  }, [familyMembers, familyRoomId, unreadCounts]);

  return contacts;
}
