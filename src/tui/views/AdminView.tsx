import { unlink } from 'fs/promises';

import { Box, Text, useInput } from 'ink';
import TextInput from 'ink-text-input';
import React, { useState, useEffect } from 'react';

import {
  encodeMfsk,
  decodeMfsk,
  DEFAULT_CONFIG,
} from '../../shared/lib/mfsk.js';
import { encodeWav, writeWavTempFile } from '../../shared/lib/wav.js';
import { matrixService } from '../App.js';
import { LogService } from '../services/LogService.js';
import { tuiAudioService } from '../services/TuiAudioService.js';
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

type AdminMode = 'menu' | 'invite' | 'set-name' | 'afsk';

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

  // AFSK state
  const [afskStatus, setAfskStatus] = useState<string>('Ready');
  const [afskDecoded, setAfskDecoded] = useState<string | null>(null);
  const [afskRecording, setAfskRecording] = useState(false);

  // Example onboarding data
  const EXAMPLE_ONBOARDING_DATA = {
    homeserver: 'https://matrix.org',
    username: 'alice',
    password: 'walkietalkie123',
    room: '!family:matrix.org',
  };

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

  // MFSK: Send onboarding data
  const handleAfskSend = async () => {
    try {
      setAfskStatus('Encoding onboarding data...');
      setError(null);
      setSuccess(null);

      // Encode data to MFSK samples
      const samples = encodeMfsk(EXAMPLE_ONBOARDING_DATA, DEFAULT_CONFIG);
      const duration = samples.length / DEFAULT_CONFIG.sampleRate;
      setAfskStatus(
        `Encoded ${duration.toFixed(1)}s of MFSK tones, playing...`,
      );

      // Convert to WAV and save to temp file
      const wavBuffer = encodeWav(samples, DEFAULT_CONFIG.sampleRate);
      const wavPath = await writeWavTempFile(wavBuffer);

      // Play using afplay
      await tuiAudioService.playWav(wavPath);

      setAfskStatus('Sent! Playing MFSK tones...');
      setSuccess('Audio onboarding transmission complete!');

      // Wait for playback to finish, then clean up
      await new Promise(resolve =>
        setTimeout(resolve, Math.ceil(duration * 1000) + 500),
      );
      await unlink(wavPath).catch(() => {});

      setAfskStatus('Ready');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Audio send failed: ${msg}`);
      setAfskStatus(`Error: ${msg}`);
      LogService.getInstance().addEntry('error', `Audio send failed: ${msg}`);
    }
  };

  // MFSK: Receive onboarding data
  const handleAfskReceive = async () => {
    try {
      setAfskRecording(true);
      setAfskStatus('Recording MFSK tones (8s)...');
      setError(null);
      setSuccess(null);
      setAfskDecoded(null);

      // Record for 8 seconds (MFSK is slower but more robust)
      const RECORDING_DURATION = 8000;
      const samples = await tuiAudioService.recordRawPcm(RECORDING_DURATION);

      setAfskStatus(`Decoding ${samples.length} samples...`);

      // Decode MFSK
      const data = await decodeMfsk(samples, DEFAULT_CONFIG);
      setAfskDecoded(JSON.stringify(data, null, 2));
      setSuccess('Audio onboarding decoded successfully!');
      setAfskStatus('Ready');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Audio receive failed: ${msg}`);
      setAfskStatus(`Error: ${msg}`);
      LogService.getInstance().addEntry('error', `Audio receive failed: ${msg}`);
    } finally {
      setAfskRecording(false);
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
    if (mode === 'afsk') {
      if (key.escape) {
        setMode('menu');
        setAfskStatus('Ready');
        setAfskDecoded(null);
      }
      if (input === 's') {
        handleAfskSend();
      }
      if (input === 'r' && !afskRecording) {
        handleAfskReceive();
      }
      return;
    }

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

    if (input === 'm') {
      setMode('afsk');
      setAfskStatus('Ready');
      setAfskDecoded(null);
      setError(null);
      setSuccess(null);
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
              ? '[n] Set name  [i] Invite  [r] Refresh  [m] Audio Test  [Esc] Back'
              : '[n] Set name  [c] Create family  [m] Audio Test  [Esc] Back'
            : mode === 'invite'
              ? '[Enter] Invite  [Esc] Cancel'
              : mode === 'afsk'
                ? '[s] Send  [r] Receive  [Esc] Back'
                : '[Enter] Save  [Esc] Cancel'}
        </Text>
      </Box>

      {/* Audio Onboarding Test Mode */}
      {mode === 'afsk' && (
        <Box
          marginTop={1}
          flexDirection="column"
          borderStyle="single"
          paddingX={1}
        >
          <Box marginBottom={1}>
            <Text bold color={profile.color}>
              Audio Onboarding Test
            </Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text>Robust credential transfer via multi-tone audio</Text>
            <Text dimColor>
              16-MFSK | {DEFAULT_CONFIG.numTones} tones | {DEFAULT_CONFIG.baseFrequency}-
              {DEFAULT_CONFIG.baseFrequency + (DEFAULT_CONFIG.numTones - 1) * DEFAULT_CONFIG.frequencySpacing}Hz | RS(15,9) FEC
            </Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text bold>Example Payload:</Text>
            <Text dimColor>{JSON.stringify(EXAMPLE_ONBOARDING_DATA)}</Text>
          </Box>

          <Box marginBottom={1}>
            <Text>Status: </Text>
            <Text
              color={afskStatus === 'Ready' ? colors.playing : colors.textMuted}
            >
              {afskStatus}
            </Text>
          </Box>

          {afskDecoded && (
            <Box marginBottom={1} flexDirection="column">
              <Text bold color={colors.playing}>
                Decoded Data:
              </Text>
              <Text>{afskDecoded}</Text>
            </Box>
          )}
        </Box>
      )}
    </Box>
  );
}
