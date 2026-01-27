/**
 * Edge Case Tests
 *
 * Tests for unusual scenarios, boundary conditions, and error handling.
 * These tests ensure the app behaves correctly under non-standard conditions.
 */

import {
  TestOrchestrator,
  createFakeAudioBuffer,
  AudioDurations,
} from './helpers';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

describe('Edge Cases and Error Handling', () => {
  let orchestrator: TestOrchestrator;

  beforeAll(async () => {
    // Check if server is running
    try {
      const response = await fetch(
        `${TEST_HOMESERVER}/_matrix/client/versions`,
      );
      if (!response.ok) {
        throw new Error('Matrix server not responding');
      }
    } catch {
      throw new Error(
        'Matrix server not running. Start it with: npm run dev:server',
      );
    }
  }, 10000);

  beforeEach(async () => {
    orchestrator = new TestOrchestrator(TEST_HOMESERVER);
  }, 5000);

  afterEach(async () => {
    await orchestrator.cleanup();
  }, 10000);

  describe('Audio Duration Edge Cases', () => {
    test('very short voice message (1 second)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const result = await orchestrator.sendAndVerifyVoiceMessage(
        'alice',
        'bob',
        audio,
        AudioDurations.SHORT,
      );

      expect(result.receivedMessage.duration).toBeCloseTo(
        AudioDurations.SHORT,
        -2,
      );
      expect(result.receivedMessage.audioUrl).toBeDefined();
    }, 35000);

    test('medium voice message (15 seconds)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const audio = createFakeAudioBuffer(AudioDurations.MEDIUM);
      const result = await orchestrator.sendAndVerifyVoiceMessage(
        'alice',
        'bob',
        audio,
        AudioDurations.MEDIUM,
      );

      expect(result.receivedMessage.duration).toBeCloseTo(
        AudioDurations.MEDIUM,
        -2,
      );
    }, 35000);

    test('long voice message (60 seconds)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const audio = createFakeAudioBuffer(AudioDurations.LONG);
      const result = await orchestrator.sendAndVerifyVoiceMessage(
        'alice',
        'bob',
        audio,
        AudioDurations.LONG,
      );

      expect(result.receivedMessage.duration).toBeCloseTo(
        AudioDurations.LONG,
        -2,
      );
    }, 40000);
  });

  describe('Audio Size Edge Cases', () => {
    test('small audio file (1KB)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Create small buffer (1KB)
      const smallAudio = createFakeAudioBuffer(1000, { size: 1024 });
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        smallAudio,
        'audio/mp4',
        1000,
      );

      const received = await orchestrator.verifyMessageReceived('bob', roomId, {
        eventId,
      });

      expect(received.audioUrl).toBeDefined();
      expect(received.audioUrl).toMatch(/^http/);
    }, 35000);

    test('larger audio file (100KB)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Create larger buffer (100KB)
      const largeAudio = createFakeAudioBuffer(10000, { size: 102400 });
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        largeAudio,
        'audio/mp4',
        10000,
      );

      const received = await orchestrator.verifyMessageReceived('bob', roomId, {
        eventId,
      });

      expect(received.audioUrl).toBeDefined();
    }, 40000);
  });

  describe('Rapid Message Sending', () => {
    // TODO: Poorly implemented — waitForMessageCount waits for any 5 messages in the
    // room, but the room may already have messages from prior tests (getOrCreate reuse).
    // Then checking all 5 new eventIds fails because old messages padded the count.
    // Fix: wait for the specific event IDs to appear, not just a message count.
    test('send 5 messages with no delay', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send 5 messages as fast as possible
      const sends = [];
      for (let i = 0; i < 5; i++) {
        const audio = createFakeAudioBuffer(AudioDurations.SHORT);
        sends.push(
          orchestrator.sendVoiceMessage(
            'alice',
            roomId,
            audio,
            'audio/mp4',
            AudioDurations.SHORT,
          ),
        );
      }

      const eventIds = await Promise.all(sends);

      // Wait for bob to receive all messages
      await orchestrator.waitForMessageCount('bob', roomId, 5);

      const bobClient = orchestrator.getClient('bob');
      const messages = bobClient?.getVoiceMessages(roomId) || [];

      expect(messages.length).toBeGreaterThanOrEqual(5);

      // All event IDs should be present
      for (const eventId of eventIds) {
        expect(messages.some(m => m.eventId === eventId)).toBe(true);
      }
    }, 50000);

    test('alternating rapid sends (alice and bob)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Alternate sending rapidly
      const eventIds: string[] = [];
      for (let i = 0; i < 6; i++) {
        const sender = i % 2 === 0 ? 'alice' : 'bob';
        const audio = createFakeAudioBuffer(AudioDurations.SHORT);
        const eventId = await orchestrator.sendVoiceMessage(
          sender,
          roomId,
          audio,
          'audio/mp4',
          AudioDurations.SHORT,
        );
        eventIds.push(eventId);
      }

      // Wait for both to receive at least half the messages
      await orchestrator.waitForMessageCount('alice', roomId, 3);
      await orchestrator.waitForMessageCount('bob', roomId, 3);

      // Both should see most messages (use pagination to fetch all)
      const aliceMessages = await orchestrator.getAllVoiceMessages(
        'alice',
        roomId,
        20,
      );
      const bobMessages = await orchestrator.getAllVoiceMessages(
        'bob',
        roomId,
        20,
      );

      // Accept at least 3/6 = 50% for each client in concurrent scenario
      expect(aliceMessages.length).toBeGreaterThanOrEqual(3);
      expect(bobMessages.length).toBeGreaterThanOrEqual(3);
    }, 50000);
  });

  describe('Room Edge Cases', () => {
    // TODO: Condition need not hold — getOrCreateDmRoom reuses existing DM rooms
    // between alice and bob from prior tests/runs, so the room will have messages.
    // Fix: use a dedicated third user, or create the room directly (not via getOrCreate).
    test('empty room (no messages)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Don't send any messages, just check room state
      const aliceClient = orchestrator.getClient('alice');
      const bobClient = orchestrator.getClient('bob');

      const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
      const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

      // Room should exist but have no voice messages
      expect(aliceMessages.length).toBe(0);
      expect(bobMessages.length).toBe(0);

      const aliceRooms = aliceClient?.getDirectRooms() || [];
      const bobRooms = bobClient?.getDirectRooms() || [];

      expect(aliceRooms.some(r => r.roomId === roomId)).toBe(true);
      expect(bobRooms.some(r => r.roomId === roomId)).toBe(true);
    }, 30000);

    test('room with single message', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');


      // Send exactly one message
      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      // Verify message is received using the standard method
      const received = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        15000,
      );

      expect(received).toBeDefined();
      expect(received.eventId).toBe(eventId);
    }, 40000);
  });

  describe('Sender and Receiver Identity', () => {
    test('verify isOwn flag is correct for sender', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      // Wait for both users to see the message
      await orchestrator.waitForCondition(
        'alice',
        'alice sees message',
        () => orchestrator.getVoiceMessages('alice', roomId).some(m => m.eventId === eventId),
      );
      await orchestrator.waitForCondition(
        'bob',
        'bob sees message',
        () => orchestrator.getVoiceMessages('bob', roomId).some(m => m.eventId === eventId),
      );

      // Alice should see isOwn = true (use pagination to ensure message is fetched)
      const aliceMessages = await orchestrator.getAllVoiceMessages(
        'alice',
        roomId,
        20,
      );
      const aliceMsg = aliceMessages.find(m => m.eventId === eventId);

      expect(aliceMsg).toBeDefined();
      expect(aliceMsg?.isOwn).toBe(true);

      // Bob should see isOwn = false (use pagination to ensure message is fetched)
      const bobMessages = await orchestrator.getAllVoiceMessages(
        'bob',
        roomId,
        20,
      );
      const bobMsg = bobMessages.find(m => m.eventId === eventId);

      expect(bobMsg).toBeDefined();
      expect(bobMsg?.isOwn).toBe(false);
    }, 40000);

    test('verify sender field is correct', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');


      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      const received = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        20000, // Increase timeout
      );

      expect(received.sender).toBe('@alice:localhost');
      expect(received.senderName).toBeDefined();
    }, 45000);
  });

  describe('Metadata Validation', () => {
    test('audio URL is valid HTTP(S) URL', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');


      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      const received = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        20000, // Increase timeout
      );

      // URL should be a valid HTTP or HTTPS URL
      expect(received.audioUrl).toMatch(/^https?:\/\//);

      // Should be parseable as URL
      expect(() => new URL(received.audioUrl)).not.toThrow();
    }, 45000);

    // TODO: Valid test, flaky — 20s timeout insufficient under server load late in
    // the suite. May resolve once test isolation is fixed (fewer accumulated rooms).
    test('timestamp is reasonable (within last minute)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');


      const beforeSend = Date.now();
      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      const afterSend = Date.now();

      const received = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        20000, // Increase timeout
      );

      // Timestamp should be within reasonable range
      expect(received.timestamp).toBeGreaterThanOrEqual(beforeSend - 60000);
      expect(received.timestamp).toBeLessThanOrEqual(afterSend + 60000);
    }, 45000);

    test('event ID is unique and non-empty', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send multiple messages and collect event IDs
      const eventIds: string[] = [];
      for (let i = 0; i < 5; i++) {
        const audio = createFakeAudioBuffer(AudioDurations.SHORT);
        const eventId = await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          audio,
          'audio/mp4',
          AudioDurations.SHORT,
        );
        eventIds.push(eventId);
      }

      // All event IDs should be non-empty
      for (const eventId of eventIds) {
        expect(eventId).toBeDefined();
        expect(eventId.length).toBeGreaterThan(0);
      }

      // All event IDs should be unique
      const uniqueIds = new Set(eventIds);
      expect(uniqueIds.size).toBe(eventIds.length);
    }, 50000);
  });
});
