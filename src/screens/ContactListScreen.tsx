import React from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';

import { useRooms, useMatrixSync } from '../hooks/useMatrix';
import { MatrixRoom } from '../services/MatrixService';

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
    <TouchableOpacity
      style={styles.contactItem}
      onPress={() => onSelectContact(item.roomId, item.name)}
    >
      <View style={styles.avatar}>
        <Text style={styles.avatarText}>
          {item.name.charAt(0).toUpperCase()}
        </Text>
      </View>
      <View style={styles.contactInfo}>
        <Text style={styles.contactName} numberOfLines={1}>
          {item.name}
        </Text>
        {item.lastMessage && (
          <Text style={styles.lastMessage} numberOfLines={1}>
            {item.lastMessage}
          </Text>
        )}
      </View>
    </TouchableOpacity>
  );

  if (!isReady) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#4a90d9" />
        <Text style={styles.loadingText}>Syncing...</Text>
        <Text style={styles.syncState}>{syncState}</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Contacts</Text>
        <TouchableOpacity onPress={onLogout} style={styles.logoutButton}>
          <Text style={styles.logoutText}>Logout</Text>
        </TouchableOpacity>
      </View>

      {directRooms.length === 0 ? (
        <View style={styles.emptyContainer}>
          <Text style={styles.emptyText}>No contacts yet</Text>
          <Text style={styles.emptyHint}>
            Start a conversation in Element to see contacts here
          </Text>
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
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  loadingContainer: {
    flex: 1,
    backgroundColor: '#1a1a2e',
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    color: '#fff',
    fontSize: 18,
    marginTop: 16,
  },
  syncState: {
    color: '#666',
    fontSize: 14,
    marginTop: 8,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 20,
    paddingTop: 48,
    borderBottomWidth: 1,
    borderBottomColor: '#2a2a4a',
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#fff',
  },
  logoutButton: {
    padding: 8,
  },
  logoutText: {
    color: '#4a90d9',
    fontSize: 16,
  },
  list: {
    padding: 16,
  },
  contactItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#2a2a4a',
    borderRadius: 16,
    padding: 16,
    marginBottom: 12,
  },
  avatar: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#4a90d9',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 16,
  },
  avatarText: {
    color: '#fff',
    fontSize: 24,
    fontWeight: 'bold',
  },
  contactInfo: {
    flex: 1,
  },
  contactName: {
    color: '#fff',
    fontSize: 20,
    fontWeight: '600',
  },
  lastMessage: {
    color: '#888',
    fontSize: 14,
    marginTop: 4,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  emptyText: {
    color: '#888',
    fontSize: 20,
    marginBottom: 8,
  },
  emptyHint: {
    color: '#666',
    fontSize: 14,
    textAlign: 'center',
  },
});
