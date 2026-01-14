import React, { useState, useMemo } from 'react';
import { Box, Text, useInput, useApp, useStdout } from 'ink';
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
  const { stdout } = useStdout();

  // Calculate viewport dimensions
  const terminalHeight = stdout?.rows || 24;
  const { visibleRooms, hasMore, startIndex } = useMemo(() => {
    // Reserve space for: header (2), help (2), scroll indicators (2), margins (2)
    // Each room item takes ~3 lines (border + content + margin)
    const HEADER_HEIGHT = 2;
    const HELP_HEIGHT = 2;
    const INDICATOR_HEIGHT = 2;
    const MARGIN_HEIGHT = 2;
    const ITEM_HEIGHT = 3;

    const availableHeight = terminalHeight - HEADER_HEIGHT - HELP_HEIGHT - INDICATOR_HEIGHT - MARGIN_HEIGHT;
    const maxItems = Math.max(1, Math.floor(availableHeight / ITEM_HEIGHT));

    // On larger terminals, leave some breathing room (don't fill entire screen)
    const preferredMaxItems = terminalHeight > 30
      ? Math.floor(maxItems * 0.7)  // Use 70% of available space
      : maxItems;

    // Calculate window around selected item
    const halfWindow = Math.floor(preferredMaxItems / 2);
    let startIndex = Math.max(0, selectedIndex - halfWindow);
    let endIndex = Math.min(rooms.length, startIndex + preferredMaxItems);

    // Adjust if we're near the end
    if (endIndex - startIndex < preferredMaxItems) {
      startIndex = Math.max(0, endIndex - preferredMaxItems);
    }

    return {
      visibleRooms: rooms.slice(startIndex, endIndex),
      hasMore: {
        above: startIndex > 0,
        below: endIndex < rooms.length,
        aboveCount: startIndex,
        belowCount: rooms.length - endIndex,
      },
      startIndex,
    };
  }, [rooms, selectedIndex, terminalHeight]);

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

      {/* Scroll indicator - more above */}
      {hasMore.above && (
        <Box justifyContent="center" marginBottom={0}>
          <Text dimColor>⬆ {hasMore.aboveCount} more above ⬆</Text>
        </Box>
      )}

      {/* Rooms list */}
      {visibleRooms.map((room, visibleIndex) => {
        const actualIndex = startIndex + visibleIndex;
        return (
          <FocusableItem
            key={room.roomId}
            label={room.name}
            sublabel={room.lastMessage || undefined}
            timestamp={formatTimestamp(room.lastMessageTime)}
            isFocused={actualIndex === selectedIndex}
            onSelect={() => onSelectContact(room.roomId, room.name)}
          />
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
          ↑↓/jk Navigate  Enter Select  q Quit
        </Text>
      </Box>
    </Box>
  );
}
