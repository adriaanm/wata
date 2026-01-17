import { Box, Text, useInput } from 'ink';
import TextInput from 'ink-text-input';
import React, { useState, useEffect } from 'react';

import { matrixService } from '../App.js';
import { LogService } from '../services/LogService.js';
import { colors } from '../theme.js';
import { PROFILES, type ProfileKey } from '../types/profile';

interface FamilyMember {
  userId: string;
  displayName: string;
}

interface Props {
  onBack: () => void;
  currentProfile: ProfileKey;
}

type AdminMode = 'menu' | 'invite' | 'set-name';

export function AdminView({ onBack, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];
  const [mode, setMode] = useState<AdminMode>('menu');
  const [familyRoomId, setFamilyRoomId] = useState<string | null>(null);
  const [familyMembers, setFamilyMembers] = useState<FamilyMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [inviteUsername, setInviteUsername] = useState('');
  const [myDisplayName, setMyDisplayName] = useState<string | null>(null);
  const [newDisplayName, setNewDisplayName] = useState('');

  // Load family room state
  const loadFamilyState = async () => {
    setLoading(true);
    setError(null);
    try {
      // Load current display name
      const displayName = await matrixService.getDisplayName();
      setMyDisplayName(displayName);

      const roomId = await matrixService.getFamilyRoomId();
      setFamilyRoomId(roomId);
      if (roomId) {
        // Include self in the member list for admin view
        const members = await matrixService.getFamilyMembers(true);
        setFamilyMembers(members);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      LogService.getInstance().addEntry('error', `Admin: ${msg}`);
    } finally {
      setLoading(false);
    }
  };

  // Set display name
  const handleSetDisplayName = async () => {
    if (!newDisplayName.trim()) return;

    setError(null);
    setSuccess(null);

    try {
      await matrixService.setDisplayName(newDisplayName.trim());
      setMyDisplayName(newDisplayName.trim());
      setSuccess(`Display name set to "${newDisplayName.trim()}"`);
      setNewDisplayName('');
      setMode('menu');
      // Reload members to show updated name
      if (familyRoomId) {
        const members = await matrixService.getFamilyMembers(true);
        setFamilyMembers(members);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      LogService.getInstance().addEntry(
        'error',
        `Set display name failed: ${msg}`,
      );
    }
  };

  useEffect(() => {
    loadFamilyState();
  }, []);

  // Create family room
  const handleCreateFamily = async () => {
    setError(null);
    setSuccess(null);
    try {
      const roomId = await matrixService.createFamilyRoom();
      setFamilyRoomId(roomId);
      setSuccess('Family room created!');
      LogService.getInstance().addEntry(
        'log',
        `Created family room: ${roomId}`,
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      LogService.getInstance().addEntry(
        'error',
        `Failed to create family: ${msg}`,
      );
    }
  };

  // Invite user to family
  const handleInvite = async () => {
    if (!inviteUsername.trim()) return;

    setError(null);
    setSuccess(null);

    // Get server name from current user's Matrix ID (@user:server)
    const myUserId = matrixService.getUserId();
    const serverName = myUserId?.split(':')[1] || 'localhost';

    // Build full Matrix ID if needed
    const userId = inviteUsername.startsWith('@')
      ? inviteUsername
      : `@${inviteUsername}:${serverName}`;

    LogService.getInstance().addEntry('log', `Inviting ${userId} to family`);

    try {
      await matrixService.inviteToFamily(userId);
      setSuccess(`Invited ${userId}`);
      setInviteUsername('');
      setMode('menu');
      // Reload members (include self for admin view)
      const members = await matrixService.getFamilyMembers(true);
      setFamilyMembers(members);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      LogService.getInstance().addEntry('error', `Invite failed: ${msg}`);
    }
  };

  // Keyboard handling
  useInput((input, key) => {
    if (mode === 'invite') {
      if (key.escape) {
        setMode('menu');
        setInviteUsername('');
      }
      return; // Let TextInput handle other keys
    }

    if (mode === 'set-name') {
      if (key.escape) {
        setMode('menu');
        setNewDisplayName('');
      }
      return; // Let TextInput handle other keys
    }

    // Menu mode
    if (key.escape) {
      onBack();
      return;
    }

    if (input === 'c' && !familyRoomId) {
      handleCreateFamily();
    }

    if (input === 'i' && familyRoomId) {
      setMode('invite');
      setError(null);
      setSuccess(null);
    }

    if (input === 'n') {
      setMode('set-name');
      setNewDisplayName(myDisplayName || '');
      setError(null);
      setSuccess(null);
    }

    if (input === 'r') {
      loadFamilyState();
    }
  });

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1} justifyContent="space-between">
        <Text bold color={profile.color}>
          Admin Setup
        </Text>
        <Text dimColor>[Esc] back</Text>
      </Box>

      {/* Loading */}
      {loading && <Text color={colors.textMuted}>Loading...</Text>}

      {/* Error */}
      {error && (
        <Box
          marginBottom={1}
          borderStyle="single"
          borderColor={colors.error}
          paddingX={1}
        >
          <Text color={colors.error}>{error}</Text>
        </Box>
      )}

      {/* Success */}
      {success && (
        <Box
          marginBottom={1}
          borderStyle="single"
          borderColor={colors.playing}
          paddingX={1}
        >
          <Text color={colors.playing}>{success}</Text>
        </Box>
      )}

      {/* Your display name */}
      {!loading && (
        <Box marginBottom={1} flexDirection="column">
          <Text bold>Your name: </Text>
          <Text color={myDisplayName ? colors.text : colors.textMuted}>
            {myDisplayName || '(not set)'}
          </Text>
        </Box>
      )}

      {/* Set name mode */}
      {mode === 'set-name' && (
        <Box marginTop={1} marginBottom={1} flexDirection="column">
          <Text>Enter your friendly name:</Text>
          <Box>
            <TextInput
              value={newDisplayName}
              onChange={setNewDisplayName}
              onSubmit={handleSetDisplayName}
              placeholder="e.g., Mom, Dad, Alice"
            />
          </Box>
          <Text dimColor>Press Enter to save, Esc to cancel</Text>
        </Box>
      )}

      {/* No family room - offer to create */}
      {!loading && !familyRoomId && mode === 'menu' && (
        <Box flexDirection="column" marginBottom={1}>
          <Text>No family room found.</Text>
          <Text color={colors.accent}>[c] Create family room</Text>
        </Box>
      )}

      {/* Family room exists */}
      {!loading && familyRoomId && (
        <Box flexDirection="column">
          <Text color={colors.playing}>
            Family room: {familyRoomId.substring(0, 20)}...
          </Text>

          <Box marginTop={1} flexDirection="column">
            <Text bold>Members ({familyMembers.length}):</Text>
            {familyMembers.length === 0 && (
              <Text color={colors.textMuted}> No members yet</Text>
            )}
            {familyMembers.map(member => {
              const isMe = member.userId === matrixService.getUserId();
              return (
                <Text key={member.userId}>
                  {'  '}- {member.displayName}
                  {isMe && <Text color={colors.textMuted}> (you)</Text>}
                </Text>
              );
            })}
          </Box>

          {/* Invite mode */}
          {mode === 'invite' && (
            <Box marginTop={1} flexDirection="column">
              <Text>Enter username to invite:</Text>
              <Box>
                <Text>@</Text>
                <TextInput
                  value={inviteUsername}
                  onChange={setInviteUsername}
                  onSubmit={handleInvite}
                  placeholder="username"
                />
              </Box>
              <Text dimColor>Press Enter to invite, Esc to cancel</Text>
            </Box>
          )}
        </Box>
      )}

      {/* Help */}
      <Box marginTop={1}>
        <Text dimColor>
          {mode === 'menu'
            ? familyRoomId
              ? '[n] Set name  [i] Invite  [r] Refresh  [Esc] Back'
              : '[n] Set name  [c] Create family  [Esc] Back'
            : mode === 'invite'
              ? '[Enter] Invite  [Esc] Cancel'
              : '[Enter] Save  [Esc] Cancel'}
        </Text>
      </Box>
    </Box>
  );
}
