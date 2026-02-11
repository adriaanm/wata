import { unlink } from 'fs/promises';

import { Box, Text, useInput } from 'ink';
import TextInput from 'ink-text-input';
import React, { useState, useEffect } from 'react';

import {
  encodeAudioCode,
  decodeAudioCode,
  DEFAULT_CONFIG,
} from '../../shared/lib/audiocode.js';
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

type AdminMode = 'menu' | 'invite' | 'set-name' | 'audiocode' | 'send-test';

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

  // AudioCode state
  const [audioCodeStatus, setAudioCodeStatus] = useState<string>('Ready');
  const [audioCodeDecoded, setAudioCodeDecoded] = useState<string | null>(null);
  const [audioCodeRecording, setAudioCodeRecording] = useState(false);

  // Send test message state
  const [testRoomId, setTestRoomId] = useState('!4VPiIIGGXjsWj3a3KKma8IwfLqhpj5m0u40juCFIIF4');
  const [testMessageStatus, setTestMessageStatus] = useState<string>('Ready');

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

  // AudioCode: Send onboarding data
  const handleAudioCodeSend = async () => {
    try {
      setAudioCodeStatus('Encoding onboarding data...');
      setError(null);
      setSuccess(null);

      // Encode data to AudioCode samples
      const samples = encodeAudioCode(EXAMPLE_ONBOARDING_DATA, DEFAULT_CONFIG);
      const duration = samples.length / DEFAULT_CONFIG.sampleRate;
      setAudioCodeStatus(
        `Encoded ${duration.toFixed(1)}s of AudioCode tones, playing...`,
      );

      // Convert to WAV and save to temp file
      const wavBuffer = encodeWav(samples, DEFAULT_CONFIG.sampleRate);
      const wavPath = await writeWavTempFile(wavBuffer);

      // Play using afplay
      await tuiAudioService.playWav(wavPath);

      setAudioCodeStatus('Sent! Playing AudioCode tones...');
      setSuccess('Audio onboarding transmission complete!');

      // Wait for playback to finish, then clean up
      await new Promise(resolve =>
        setTimeout(resolve, Math.ceil(duration * 1000) + 500),
      );
      await unlink(wavPath).catch(() => {});

      setAudioCodeStatus('Ready');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Audio send failed: ${msg}`);
      setAudioCodeStatus(`Error: ${msg}`);
      LogService.getInstance().addEntry('error', `Audio send failed: ${msg}`);
    }
  };

  // AudioCode: Receive onboarding data
  const handleAudioCodeReceive = async () => {
    try {
      setAudioCodeRecording(true);
      setAudioCodeStatus('Recording AudioCode tones (16s)...');
      setError(null);
      setSuccess(null);
      setAudioCodeDecoded(null);

      // Record for 16 seconds (AudioCode with 100% RS redundancy)
      const RECORDING_DURATION = 16000;
      const samples = await tuiAudioService.recordRawPcm(RECORDING_DURATION);

      setAudioCodeStatus(`Decoding ${samples.length} samples...`);

      // Decode AudioCode
      const data = await decodeAudioCode(samples, DEFAULT_CONFIG);
      setAudioCodeDecoded(JSON.stringify(data, null, 2));
      setSuccess('Audio onboarding decoded successfully!');
      setAudioCodeStatus('Ready');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Audio receive failed: ${msg}`);
      setAudioCodeStatus(`Error: ${msg}`);
      LogService.getInstance().addEntry(
        'error',
        `Audio receive failed: ${msg}`,
      );
    } finally {
      setAudioCodeRecording(false);
    }
  };

  // Send test message to specified room
  const handleSendTestMessage = async () => {
    const roomId = testRoomId.trim();
    if (!roomId.startsWith('!')) {
      setError('Invalid room ID (must start with !)');
      return;
    }

    setError(null);
    setSuccess(null);
    setTestMessageStatus('Sending test message...');

    try {
      // Generate 0.5 seconds of silence (16kHz PCM)
      const SAMPLE_RATE = 16000;
      const DURATION = 0.5; // seconds
      const silenceSamples = new Float32Array(Math.floor(SAMPLE_RATE * DURATION)).fill(0);

      // Encode as WAV
      const wavBuffer = encodeWav(silenceSamples, SAMPLE_RATE);

      // Convert ArrayBuffer to Buffer
      const buffer = Buffer.from(wavBuffer);

      // Send using WataService API
      const eventId = await matrixService.sendVoiceMessage(
        roomId,
        buffer,
        'audio/ogg', // mimeType
        DURATION * 1000, // duration in milliseconds
        buffer.length, // size in bytes
      );

      setSuccess(`Sent test message to ${roomId.slice(-8)} (event: ${eventId.slice(-8)})`);
      setTestMessageStatus('Ready');
      LogService.getInstance().addEntry('log', `[TEST] Sent message to ${roomId.slice(-8)}: ${eventId.slice(-8)}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Send failed: ${msg}`);
      setTestMessageStatus(`Error: ${msg}`);
      LogService.getInstance().addEntry('error', `Send test message failed: ${msg}`);
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
    if (mode === 'audiocode') {
      if (key.escape) {
        setMode('menu');
        setAudioCodeStatus('Ready');
        setAudioCodeDecoded(null);
      }
      if (input === 's') {
        handleAudioCodeSend();
      }
      if (input === 'r' && !audioCodeRecording) {
        handleAudioCodeReceive();
      }
      return;
    }

    if (mode === 'send-test') {
      if (key.escape) {
        setMode('menu');
        setError(null);
        setSuccess(null);
        setTestMessageStatus('Ready');
      }
      if (input === 's') {
        handleSendTestMessage();
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
      setMode('audiocode');
      setAudioCodeStatus('Ready');
      setAudioCodeDecoded(null);
      setError(null);
      setSuccess(null);
    }

    if (input === 't') {
      setMode('send-test');
      setError(null);
      setSuccess(null);
      setTestMessageStatus('Ready');
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
              ? '[n] Set name  [i] Invite  [r] Refresh  [m] Audio Test  [t] Send Test  [Esc] Back'
              : '[n] Set name  [c] Create family  [m] Audio Test  [t] Send Test  [Esc] Back'
            : mode === 'invite'
              ? '[Enter] Invite  [Esc] Cancel'
              : mode === 'audiocode'
                ? '[s] Send  [r] Receive  [Esc] Back'
                : mode === 'send-test'
                  ? '[s] Send  [Esc] Back'
                  : '[Enter] Save  [Esc] Cancel'}
        </Text>
      </Box>

      {/* Audio Onboarding Test Mode */}
      {mode === 'audiocode' && (
        <Box
          marginTop={1}
          flexDirection="column"
          borderStyle="single"
          paddingX={1}
        >
          <Box marginBottom={1}>
            <Text bold color={profile.color}>
              AudioCode Test (QR over Audio)
            </Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text>Robust credential transfer via multi-tone audio</Text>
            <Text dimColor>
              16-MFSK | {DEFAULT_CONFIG.numTones} tones |{' '}
              {DEFAULT_CONFIG.baseFrequency}-
              {DEFAULT_CONFIG.baseFrequency +
                (DEFAULT_CONFIG.numTones - 1) * DEFAULT_CONFIG.frequencySpacing}
              Hz | 100% RS FEC
            </Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text bold>Example Payload:</Text>
            <Text dimColor>{JSON.stringify(EXAMPLE_ONBOARDING_DATA)}</Text>
          </Box>

          <Box marginBottom={1}>
            <Text>Status: </Text>
            <Text
              color={
                audioCodeStatus === 'Ready' ? colors.playing : colors.textMuted
              }
            >
              {audioCodeStatus}
            </Text>
          </Box>

          {audioCodeDecoded && (
            <Box marginBottom={1} flexDirection="column">
              <Text bold color={colors.playing}>
                Decoded Data:
              </Text>
              <Text>{audioCodeDecoded}</Text>
            </Box>
          )}
        </Box>
      )}

      {/* Send Test Message Mode */}
      {mode === 'send-test' && (
        <Box
          marginTop={1}
          flexDirection="column"
          borderStyle="single"
          paddingX={1}
        >
          <Box marginBottom={1}>
            <Text bold color={profile.color}>
              Send Test Message
            </Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text>Send a test voice message (0.5s silence) to a specific room.</Text>
            <Text dimColor>Useful for testing Android app reactivity.</Text>
          </Box>

          <Box marginBottom={1} flexDirection="column">
            <Text>Room ID:</Text>
            <Box>
              <TextInput
                value={testRoomId}
                onChange={setTestRoomId}
                placeholder="!roomId:server"
              />
            </Box>
          </Box>

          <Box marginBottom={1}>
            <Text>Status: </Text>
            <Text
              color={
                testMessageStatus === 'Ready' ? colors.playing : colors.textMuted
              }
            >
              {testMessageStatus}
            </Text>
          </Box>
        </Box>
      )}
    </Box>
  );
}
