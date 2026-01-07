import React from 'react';
import {
  View,
  Text,
  FlatList,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';

import { FocusablePressable } from '../components/FocusablePressable';
import { useRooms, useMatrixSync } from '../hooks/useMatrix';
import { MatrixRoom } from '../services/MatrixService';
import { colors, typography, spacing, components } from '../theme';

interface Props {
  onSelectContact: (roomId: string, roomName: string) => void;
  onLogout: () => void;
}

export function ContactListScreen({ onSelectContact, onLogout }: Props) {
  const rooms = useRooms();
  const { isReady, syncState } = useMatrixSync();

  // Filter to show only DM rooms (direct messages)
  const directRooms = rooms.filter(room => room.isDirect);

  const renderContact = ({ item }: { item: MatrixRoom }) => (
    <FocusablePressable
      style={styles.contactItem}
      focusedStyle={styles.contactItemFocused}
      onPress={() => onSelectContact(item.roomId, item.name)}
    >
      <Text style={styles.contactName} numberOfLines={1}>
        {item.name}
      </Text>
      {item.lastMessage && (
        <Text style={styles.lastMessage} numberOfLines={1}>
          {item.lastMessage}
        </Text>
      )}
    </FocusablePressable>
  );

  if (!isReady) {
    return (
      <View style={styles.loading}>
        <ActivityIndicator size="large" color={colors.primary} />
        <Text style={styles.loadingText}>Syncing...</Text>
        <Text style={styles.syncState}>{syncState}</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Contacts</Text>
        <FocusablePressable
          style={styles.logoutButton}
          focusedStyle={styles.logoutButtonFocused}
          onPress={onLogout}
        >
          <Text style={styles.logoutText}>Exit</Text>
        </FocusablePressable>
      </View>

      {directRooms.length === 0 ? (
        <View style={styles.emptyContainer}>
          <Text style={styles.emptyText}>No contacts</Text>
          <Text style={styles.emptyHint}>Start chat in Element</Text>
        </View>
      ) : (
        <FlatList
          data={directRooms}
          keyExtractor={item => item.roomId}
          renderItem={renderContact}
          contentContainerStyle={styles.list}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    ...components.screen,
  },
  loading: {
    ...components.loading,
  },
  loadingText: {
    ...components.loadingText,
  },
  syncState: {
    ...typography.small,
    color: colors.textMuted,
    marginTop: spacing.xs,
  },
  header: {
    ...components.header,
  },
  title: {
    ...typography.header,
  },
  logoutButton: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  logoutButtonFocused: {
    borderColor: colors.focus,
  },
  logoutText: {
    ...typography.body,
    color: colors.primary,
  },
  list: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.sm,
  },
  contactItem: {
    ...components.listItem,
  },
  contactItemFocused: {
    ...components.listItemFocused,
  },
  contactName: {
    ...typography.large,
    fontWeight: '600',
  },
  lastMessage: {
    ...typography.small,
    marginTop: spacing.xs,
  },
  emptyContainer: {
    ...components.emptyState,
  },
  emptyText: {
    ...typography.body,
    color: colors.textSecondary,
  },
  emptyHint: {
    ...typography.small,
    marginTop: spacing.xs,
  },
});
