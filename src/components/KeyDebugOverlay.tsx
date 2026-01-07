import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';

import { useKeyEvents } from '../hooks/useKeyEvents';
import { colors, typography, spacing } from '../theme';

export function KeyDebugOverlay() {
  const { lastKey, keyHistory } = useKeyEvents(8);

  return (
    <View style={styles.container}>
      <View style={styles.currentKey}>
        <Text style={styles.label}>LAST KEY:</Text>
        {lastKey ? (
          <Text style={styles.keyCode}>
            {lastKey.keyName} ({lastKey.keyCode})
          </Text>
        ) : (
          <Text style={styles.keyCode}>Press any key...</Text>
        )}
      </View>

      <View style={styles.history}>
        <Text style={styles.label}>HISTORY:</Text>
        <ScrollView style={styles.historyList}>
          {keyHistory.map((key, index) => (
            <Text key={`${key.timestamp}-${index}`} style={styles.historyItem}>
              {key.keyCode} {key.keyName.replace('KEYCODE_', '')}
            </Text>
          ))}
        </ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.9)',
    padding: spacing.sm,
    borderTopWidth: 2,
    borderTopColor: colors.focus,
  },
  currentKey: {
    marginBottom: spacing.sm,
  },
  label: {
    ...typography.small,
    color: colors.focus,
    marginBottom: spacing.xs,
  },
  keyCode: {
    ...typography.title,
    color: colors.text,
  },
  history: {
    maxHeight: 100,
  },
  historyList: {
    flexGrow: 0,
  },
  historyItem: {
    ...typography.small,
    color: colors.textSecondary,
    marginBottom: 2,
  },
});
