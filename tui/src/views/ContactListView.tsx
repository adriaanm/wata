import React, { useState } from 'react';
import { Box, Text, useInput, useApp } from 'ink';
import { useRooms, useMatrixSync } from '../hooks/useMatrix.js';
import { FocusableItem } from '../components/FocusableItem.js';
import { colors } from '../theme.js';

interface Props {
  onSelectContact: (roomId: string, roomName: string) => void;
}

export function ContactListView({ onSelectContact }: Props) {
  const rooms = useRooms();
  const { isReady } = useMatrixSync();
  const [selectedIndex, setSelectedIndex] = useState(0);
  const { exit } = useApp();

  // Keyboard navigation
  useInput((input, key) => {
    if (input === 'q' || (key.ctrl && input === 'c')) {
      exit();
    }

    if (key.upArrow || input === 'k') {
      setSelectedIndex(prev => Math.max(0, prev - 1));
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex(prev => Math.min(rooms.length - 1, prev + 1));
    }

    if (key.return && rooms[selectedIndex]) {
      const room = rooms[selectedIndex];
      onSelectContact(room.roomId, room.name);
    }
  });

  const formatTimestamp = (timestamp: number | null): string => {
    if (!timestamp) return '';

    const now = Date.now();
    const diff = now - timestamp;
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    if (hours < 24) return `${hours} hour${hours > 1 ? 's' : ''} ago`;
    if (days === 1) return 'yesterday';
    return `${days} days ago`;
  };

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={colors.accent}>
          WATA - Contacts
        </Text>
        <Text dimColor>[q] quit</Text>
      </Box>

      {/* Loading state */}
      {!isReady && (
        <Box>
          <Text color={colors.textMuted}>Loading contacts...</Text>
        </Box>
      )}

      {/* Empty state */}
      {isReady && rooms.length === 0 && (
        <Box>
          <Text color={colors.textMuted}>No contacts yet</Text>
        </Box>
      )}

      {/* Rooms list */}
      {rooms.map((room, index) => (
        <FocusableItem
          key={room.roomId}
          label={room.name}
          sublabel={room.lastMessage || undefined}
          timestamp={formatTimestamp(room.lastMessageTime)}
          isFocused={index === selectedIndex}
          onSelect={() => onSelectContact(room.roomId, room.name)}
        />
      ))}

      {/* Help text */}
      <Box marginTop={1}>
        <Text dimColor>
          ↑↓/jk Navigate  Enter Select  q Quit
        </Text>
      </Box>
    </Box>
  );
}
