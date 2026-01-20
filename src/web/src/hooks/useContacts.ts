/**
 * Hook to build contacts list from Matrix data.
 * Combines family members and direct message rooms.
 */

import { useState, useEffect, useMemo } from 'react';

import { matrixService } from '../services/matrixService';
import type { Contact } from '../types.js';

import { useMatrixSync } from './useMatrix.js';

interface ContactStatus {
  hasUnread: boolean;
  hasError: boolean;
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
  const [contactStatus, _setContactStatus] = useState<
    Record<string, ContactStatus>
  >({});

  // Load family members from family room
  useEffect(() => {
    const loadFamily = async () => {
      try {
        const members = await matrixService.getFamilyMembers();
        setFamilyMembers(members);
        const roomId = await matrixService.getFamilyRoomId();
        setFamilyRoomId(roomId);
      } catch (err) {
        console.error('Failed to load family room:', err);
      }
    };
    if (isReady) {
      loadFamily();
    }
  }, [isReady]);

  // Build contacts list from family members
  const contacts: Contact[] = useMemo(() => {
    const result: Contact[] = [];

    // Add family members as contacts
    for (const member of familyMembers) {
      const status = contactStatus[member.userId] || {
        hasUnread: false,
        hasError: false,
      };

      result.push({
        id: member.userId,
        name: member.displayName,
        type: 'dm',
        unreadCount: status.hasUnread ? 1 : 0,
        hasError: status.hasError,
        avatarUrl: member.avatarUrl || undefined,
      });
    }

    // Add family broadcast option if we have family room
    if (familyRoomId && familyMembers.length > 0) {
      const status = contactStatus['family'] || {
        hasUnread: false,
        hasError: false,
      };

      result.push({
        id: 'family',
        name: 'Family',
        type: 'family',
        unreadCount: status.hasUnread ? 1 : 0,
        hasError: status.hasError,
      });
    }

    return result;
  }, [familyMembers, familyRoomId, contactStatus]);

  return contacts;
}
