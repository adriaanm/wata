import React, { useState, useEffect } from 'react';
import { Box, Text, useInput } from 'ink';
import TextInput from 'ink-text-input';
import { matrixService } from '../App.js';
import { PROFILES, type ProfileKey } from '../types/profile';
import { colors } from '../theme.js';
import { LogService } from '../services/LogService.js';

interface FamilyMember {
  userId: string;
  displayName: string;
}

interface Props {
  onBack: () => void;
  currentProfile: ProfileKey;
}

type AdminMode = 'menu' | 'invite';

export function AdminView({ onBack, currentProfile }: Props) {
  const profile = PROFILES[currentProfile];
  const [mode, setMode] = useState<AdminMode>('menu');
  const [familyRoomId, setFamilyRoomId] = useState<string | null>(null);
  const [familyMembers, setFamilyMembers] = useState<FamilyMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [inviteUsername, setInviteUsername] = useState('');

  // Load family room state
  const loadFamilyState = async () => {
    setLoading(true);
    setError(null);
    try {
      const roomId = await matrixService.getFamilyRoomId();
      setFamilyRoomId(roomId);
      if (roomId) {
        const members = await matrixService.getFamilyMembers();
        setFamilyMembers(members);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      LogService.getInstance().addEntry('error', `Admin: ${msg}`);
    } finally {
      setLoading(false);
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
      LogService.getInstance().addEntry('log', `Created family room: ${roomId}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      LogService.getInstance().addEntry('error', `Failed to create family: ${msg}`);
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
      // Reload members
      const members = await matrixService.getFamilyMembers();
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
      {loading && (
        <Text color={colors.textMuted}>Loading...</Text>
      )}

      {/* Error */}
      {error && (
        <Box marginBottom={1} borderStyle="single" borderColor={colors.error} paddingX={1}>
          <Text color={colors.error}>{error}</Text>
        </Box>
      )}

      {/* Success */}
      {success && (
        <Box marginBottom={1} borderStyle="single" borderColor={colors.playing} paddingX={1}>
          <Text color={colors.playing}>{success}</Text>
        </Box>
      )}

      {/* No family room - offer to create */}
      {!loading && !familyRoomId && (
        <Box flexDirection="column" marginBottom={1}>
          <Text>No family room found.</Text>
          <Text color={colors.accent}>[c] Create family room</Text>
        </Box>
      )}

      {/* Family room exists */}
      {!loading && familyRoomId && (
        <Box flexDirection="column">
          <Text color={colors.playing}>Family room: {familyRoomId.substring(0, 20)}...</Text>

          <Box marginTop={1} flexDirection="column">
            <Text bold>Members ({familyMembers.length}):</Text>
            {familyMembers.length === 0 && (
              <Text color={colors.textMuted}>  No other members yet</Text>
            )}
            {familyMembers.map(member => (
              <Text key={member.userId}>  - {member.displayName}</Text>
            ))}
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
            ? (familyRoomId
                ? '[i] Invite member  [r] Refresh  [Esc] Back'
                : '[c] Create family  [Esc] Back')
            : '[Enter] Invite  [Esc] Cancel'}
        </Text>
      </Box>
    </Box>
  );
}
