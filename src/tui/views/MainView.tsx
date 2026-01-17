import { Box, Text, useInput, useApp, useStdout } from 'ink';
import React, { useState, useMemo, useEffect, useCallback } from 'react';

import { matrixService } from '../App.js';
import { FocusableItem } from '../components/FocusableItem.js';
import { useAudioRecorder } from '../hooks/useAudioRecorder.js';
import { useContactStatus } from '../hooks/useContactStatus.js';
import { useRooms, useMatrixSync } from '../hooks/useMatrix.js';
import { usePtt } from '../hooks/usePtt.js';
import { LogService } from '../services/LogService.js';
import { colors } from '../theme.js';
import { PROFILES, type ProfileKey } from '../types/profile';

interface Contact {
  id: string; // Either a room ID (for DMs) or 'family' for broadcast
  name: string;
  type: 'dm' | 'family';
  roomId: string | null; // The actual room ID (null if DM not yet created)
  userId: string | null; // The user ID for DM targets
  hasUnread: boolean;
  hasError: boolean;
}

interface Props {
  onSelectContact: (contact: Contact) => void;
  currentProfile: ProfileKey;
}

export function MainView({ onSelectContact, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];
  const rooms = useRooms();
  const { isReady } = useMatrixSync();
  const [selectedIndex, setSelectedIndex] = useState(0);
  const { exit } = useApp();
  const { stdout } = useStdout();

  // Audio recording
  const {
    isRecording,
    recordingDuration,
    recordingError,
    startRecording,
    stopRecording,
    clearError: clearRecordingError,
    formatDuration,
  } = useAudioRecorder();

  // Contact status tracking (unread messages and send errors)
  const {
    getStatus,
    markAsRead,
    setSendError: setContactSendError,
    clearSendError: clearContactSendError,
  } = useContactStatus();

  // Family members state (loaded async from family room)
  const [familyMembers, setFamilyMembers] = useState<
    Array<{
      userId: string;
      displayName: string;
    }>
  >([]);
  const [familyRoomId, setFamilyRoomId] = useState<string | null>(null);
  const [familyError, setFamilyError] = useState<string | null>(null);

  // Load family members from family room
  useEffect(() => {
    const loadFamily = async () => {
      try {
        const members = await matrixService.getFamilyMembers();
        setFamilyMembers(members);
        const roomId = await matrixService.getFamilyRoomId();
        setFamilyRoomId(roomId);
        setFamilyError(null);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry('warn', `No family room: ${msg}`);
        setFamilyError('No family room found. Run admin setup.');
      }
    };
    if (isReady) {
      loadFamily();
    }
  }, [isReady]);

  // Build contacts list from family members
  // Each family member is a contact; DM room is looked up or created on-demand
  const contacts: Contact[] = useMemo(() => {
    const result: Contact[] = [];

    // Build a map of userId -> roomId from existing DM rooms
    const dmRoomByUser = new Map<string, string>();
    for (const room of rooms) {
      if (room.isDirect) {
        // Extract the other user's ID from the room name or members
        // For now, use the room as-is; proper extraction would need room members
        // This is a simplification - in practice we'd query room members
      }
    }

    // Add family members as contacts
    for (const member of familyMembers) {
      // Find existing DM room with this user
      const existingDmRoom = rooms.find(
        r => r.isDirect && r.name === member.displayName,
      );

      const contactId = member.userId;
      const roomId = existingDmRoom?.roomId || null;
      const status = getStatus(contactId, roomId);

      result.push({
        id: contactId,
        name: member.displayName,
        type: 'dm',
        roomId: roomId, // null = will create on first message
        userId: member.userId,
        hasUnread: status.hasUnread,
        hasError: status.hasError,
      });
    }

    // Add family broadcast option if we have family members
    if (familyRoomId && familyMembers.length > 0) {
      const status = getStatus('family', familyRoomId);
      result.push({
        id: 'family',
        name: 'Family',
        type: 'family',
        roomId: familyRoomId,
        userId: null,
        hasUnread: status.hasUnread,
        hasError: status.hasError,
      });
    }

    return result;
  }, [rooms, familyMembers, familyRoomId, getStatus]);

  // Send callback for PTT
  const handleSend = useCallback(
    async (result: {
      buffer: Buffer;
      mimeType: string;
      duration: number;
      size: number;
    }) => {
      const contact = contacts[selectedIndex];
      if (!contact) {
        throw new Error('No contact selected');
      }

      // Get or create the room to send to
      let targetRoomId: string | null = null;

      if (contact.type === 'family') {
        LogService.getInstance().addEntry(
          'log',
          `Sending to family room: ${contact.name}`,
        );
        targetRoomId = await matrixService.getFamilyRoomId();
        if (!targetRoomId) {
          throw new Error('Family room not available');
        }
      } else {
        LogService.getInstance().addEntry(
          'log',
          `Sending to DM: ${contact.name} (${contact.userId})`,
        );
        targetRoomId = contact.roomId;
        if (!targetRoomId && contact.userId) {
          LogService.getInstance().addEntry(
            'log',
            `Creating DM room with ${contact.userId}`,
          );
          targetRoomId = await matrixService.getOrCreateDmRoom(contact.userId);
        }
      }

      if (!targetRoomId) {
        throw new Error('No room to send to');
      }

      LogService.getInstance().addEntry(
        'log',
        `Sending voice message to ${targetRoomId}`,
      );
      await matrixService.sendVoiceMessage(
        targetRoomId,
        result.buffer,
        result.mimeType,
        result.duration,
        result.size,
      );

      LogService.getInstance().addEntry(
        'success',
        `Voice message sent to ${contact.name}`,
      );
      clearContactSendError(contact.id);
    },
    [contacts, selectedIndex, clearContactSendError],
  );

  // PTT hook
  const { isHoldingSpace, sendError, handleSpacePress } = usePtt({
    isRecording,
    startRecording,
    stopRecording,
    onSend: handleSend,
    onRecordingStart: () => {
      const contact = contacts[selectedIndex];
      if (contact) {
        clearContactSendError(contact.id);
      }
    },
  });

  // Track send errors per contact
  useEffect(() => {
    if (sendError) {
      const contact = contacts[selectedIndex];
      if (contact) {
        setContactSendError(contact.id, sendError);
      }
    }
  }, [sendError, contacts, selectedIndex, setContactSendError]);

  // Calculate viewport dimensions
  const terminalHeight = stdout?.rows || 24;
  const { visibleContacts, hasMore, startIndex } = useMemo(() => {
    const HEADER_HEIGHT = 2;
    const STATUS_HEIGHT = isRecording ? 3 : 0;
    const HELP_HEIGHT = 2;
    const INDICATOR_HEIGHT = 2;
    const MARGIN_HEIGHT = 2;
    const ITEM_HEIGHT = 2; // Simpler items without sublabel

    const availableHeight =
      terminalHeight -
      HEADER_HEIGHT -
      STATUS_HEIGHT -
      HELP_HEIGHT -
      INDICATOR_HEIGHT -
      MARGIN_HEIGHT;
    const maxItems = Math.max(1, Math.floor(availableHeight / ITEM_HEIGHT));

    const preferredMaxItems =
      terminalHeight > 30 ? Math.floor(maxItems * 0.7) : maxItems;

    const halfWindow = Math.floor(preferredMaxItems / 2);
    let start = Math.max(0, selectedIndex - halfWindow);
    const endIndex = Math.min(contacts.length, start + preferredMaxItems);

    if (endIndex - start < preferredMaxItems) {
      start = Math.max(0, endIndex - preferredMaxItems);
    }

    return {
      visibleContacts: contacts.slice(start, endIndex),
      hasMore: {
        above: start > 0,
        below: endIndex < contacts.length,
        aboveCount: start,
        belowCount: contacts.length - endIndex,
      },
      startIndex: start,
    };
  }, [contacts, selectedIndex, terminalHeight, isRecording]);

  // Keyboard navigation
  useInput((input, key) => {
    if (input === 'q' || (key.ctrl && input === 'c')) {
      exit();
    }

    // Navigation
    // Don't clear send error on navigation - keep it visible until user records again
    if (key.upArrow || input === 'k') {
      setSelectedIndex(prev => Math.max(0, prev - 1));
      clearRecordingError();
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex(prev => Math.min(contacts.length - 1, prev + 1));
      clearRecordingError();
    }

    // Enter to view history (if contact has unread messages)
    if (key.return && contacts[selectedIndex]) {
      const contact = contacts[selectedIndex];
      // For now, always allow viewing history
      // TODO: Only show if hasUnread
      if (contact.type === 'dm') {
        // For DMs, we need a roomId. If it doesn't exist, create it on-demand
        if (contact.roomId) {
          markAsRead(contact.roomId);
          onSelectContact(contact);
        } else if (contact.userId) {
          // Create DM room on-demand for viewing history
          matrixService
            .getOrCreateDmRoom(contact.userId)
            .then(roomId => {
              markAsRead(roomId);
              // Update the contact with the new roomId and select it
              const updatedContact = { ...contact, roomId };
              onSelectContact(updatedContact);
            })
            .catch(err => {
              const errorMsg = err instanceof Error ? err.message : String(err);
              LogService.getInstance().addEntry(
                'error',
                `Failed to create DM room: ${errorMsg}`,
              );
            });
        }
      } else if (contact.type === 'family') {
        if (contact.roomId) {
          markAsRead(contact.roomId);
        }
        onSelectContact(contact);
      }
    }

    // Space for PTT (hold-to-record)
    if (input === ' ') {
      handleSpacePress();
    }
  });

  // Status indicator for a contact
  const getStatusIndicator = (
    contact: Contact,
  ): { symbol: string; color: string } | null => {
    if (contact.hasError) {
      return { symbol: '⚠', color: colors.error };
    }
    if (contact.hasUnread) {
      return { symbol: '●', color: colors.accent };
    }
    return null;
  };

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={profile.color}>
          WATA
        </Text>
        <Text dimColor>[p] switch [l] logs [q] quit</Text>
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
            → {contacts[selectedIndex]?.name || 'Unknown'}
            {isHoldingSpace ? ' (Release to send)' : ''}
          </Text>
        </Box>
      )}

      {/* Error Status */}
      {(recordingError || sendError) && !isRecording && (
        <Box
          marginBottom={1}
          borderStyle="double"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>⚠ {recordingError || sendError}</Text>
        </Box>
      )}

      {/* Loading state - Matrix sync not ready */}
      {!isReady && (
        <Box>
          <Text color={colors.textMuted}>Connecting to server...</Text>
        </Box>
      )}

      {/* No family room */}
      {isReady && !familyRoomId && (
        <Box flexDirection="column">
          <Text color={colors.textMuted}>No family room found.</Text>
          <Text color={colors.textMuted}>Press [a] to create one.</Text>
        </Box>
      )}

      {/* Family room exists but no members */}
      {isReady && familyRoomId && contacts.length === 0 && (
        <Box flexDirection="column">
          <Text color={colors.textMuted}>No family members yet.</Text>
          <Text color={colors.textMuted}>Press [a] to invite members.</Text>
        </Box>
      )}

      {/* Scroll indicator - more above */}
      {hasMore.above && (
        <Box justifyContent="center" marginBottom={0}>
          <Text dimColor>⬆ {hasMore.aboveCount} more above ⬆</Text>
        </Box>
      )}

      {/* Contacts list */}
      {visibleContacts.map((contact, visibleIndex) => {
        const actualIndex = startIndex + visibleIndex;
        const isFocused = actualIndex === selectedIndex;
        const status = getStatusIndicator(contact);
        const isSeparator = contact.type === 'family';

        return (
          <Box key={contact.id} flexDirection="column">
            {/* Separator before Family */}
            {isSeparator && (
              <Box marginY={0}>
                <Text dimColor>{'─'.repeat(40)}</Text>
              </Box>
            )}
            <FocusableItem
              label={contact.name}
              isFocused={isFocused}
              onSelect={() => onSelectContact(contact)}
              statusIndicator={status}
            />
          </Box>
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
          ↑↓ Navigate Space Talk Enter History a Admin l Logs q Quit
        </Text>
      </Box>
    </Box>
  );
}
