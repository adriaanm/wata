import React, { useState, useMemo } from 'react';
import { Box, Text, useInput, useStdout } from 'ink';
import { LogService } from '../services/LogService.js';
import { colors } from '../theme.js';

interface Props {
  onBack: () => void;
}

export function LogView({ onBack }: Props) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const { stdout } = useStdout();
  const logs = LogService.getInstance().getLogs();

  // Calculate viewport dimensions (similar to ChatView/ContactListView)
  const terminalHeight = stdout?.rows || 24;
  const { visibleLogs, hasMore, startIndex } = useMemo(() => {
    const HEADER_HEIGHT = 2;
    const HELP_HEIGHT = 2;
    const MARGIN_HEIGHT = 2;
    const INDICATOR_HEIGHT = 2;

    const availableHeight =
      terminalHeight - HEADER_HEIGHT - HELP_HEIGHT - MARGIN_HEIGHT - INDICATOR_HEIGHT;
    const maxItems = Math.max(1, availableHeight);

    // Center viewport around selected item
    const halfWindow = Math.floor(maxItems / 2);
    let startIndex = Math.max(0, selectedIndex - halfWindow);
    let endIndex = Math.min(logs.length, startIndex + maxItems);

    // Adjust if we're near the end
    if (endIndex - startIndex < maxItems) {
      startIndex = Math.max(0, endIndex - maxItems);
    }

    return {
      visibleLogs: logs.slice(startIndex, endIndex),
      hasMore: {
        above: startIndex > 0,
        below: endIndex < logs.length,
        aboveCount: startIndex,
        belowCount: logs.length - endIndex,
      },
      startIndex,
    };
  }, [logs, selectedIndex, terminalHeight]);

  // Keyboard navigation
  useInput((input, key) => {
    if (key.escape || input === 'l') {
      onBack();
    }

    if (key.upArrow || input === 'k') {
      setSelectedIndex((prev) => Math.max(0, prev - 1));
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex((prev) => Math.min(logs.length - 1, prev + 1));
    }

    // Jump to top
    if (input === 'g') {
      setSelectedIndex(0);
    }

    // Jump to bottom
    if (input === 'G') {
      setSelectedIndex(Math.max(0, logs.length - 1));
    }

    // Clear logs
    if (input === 'c') {
      LogService.getInstance().clear();
      setSelectedIndex(0);
    }
  });

  const formatTime = (timestamp: number): string => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  };

  const getLevelColor = (level: string) => {
    switch (level) {
      case 'error':
        return colors.error;
      case 'warn':
        return 'yellow';
      default:
        return colors.textMuted;
    }
  };

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={colors.accent}>
          Logs ({logs.length})
        </Text>
        <Text dimColor>[l/Esc] back</Text>
      </Box>

      {/* Empty state */}
      {logs.length === 0 && (
        <Box>
          <Text dimColor>No logs yet</Text>
        </Box>
      )}

      {/* Scroll indicator - more above */}
      {hasMore.above && (
        <Box justifyContent="center" marginBottom={0}>
          <Text dimColor>⬆ {hasMore.aboveCount} more above ⬆</Text>
        </Box>
      )}

      {/* Log entries */}
      {visibleLogs.map((log, visibleIndex) => {
        const actualIndex = startIndex + visibleIndex;
        const isFocused = actualIndex === selectedIndex;
        return (
          <Box
            key={actualIndex}
            backgroundColor={isFocused ? 'gray' : undefined}
            paddingX={isFocused ? 1 : 0}
          >
            <Text color={getLevelColor(log.level)}>{formatTime(log.timestamp)}</Text>
            <Text> </Text>
            <Text color={getLevelColor(log.level)} bold>
              [{log.level.toUpperCase()}]
            </Text>
            <Text> {log.message}</Text>
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
        <Text dimColor>↑↓/jk Navigate  g/G Top/Bottom  c Clear  l/Esc Back</Text>
      </Box>
    </Box>
  );
}
