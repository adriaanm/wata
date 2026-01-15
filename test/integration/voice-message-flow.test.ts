/**
 * Voice Message Flow Tests
 *
 * Integration tests for end-to-end voice message scenarios using the
 * new TestOrchestrator infrastructure.
 *
 * These tests demonstrate the modular testing architecture for sending
 * and receiving voice messages between multiple clients.
 */

import {
  TestOrchestrator,
  createFakeAudioBuffer,
  createAudioBuffers,
  AudioDurations,
} from './helpers';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

describe('Voice Message Flow (with TestOrchestrator)', () => {
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

  describe('Basic Send and Receive', () => {
    test('alice sends voice message, bob receives it', async () => {
      // Setup clients
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      // Create room
      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Create test audio
      const audioBuffer = createFakeAudioBuffer(5000);

      // Send message
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audioBuffer,
        'audio/mp4',
        5000,
      );

      // Verify bob receives it
      const receivedMessage = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        15000,
      );

      // Assertions
      expect(receivedMessage.eventId).toBe(eventId);
      expect(receivedMessage.sender).toBe('@alice:localhost');
      expect(receivedMessage.duration).toBeCloseTo(5000, -2); // within 100ms
      expect(receivedMessage.audioUrl).toMatch(/^http/);
      expect(receivedMessage.isOwn).toBe(false); // From bob's perspective
    }, 30000);

    test('using sendAndVerifyVoiceMessage helper', async () => {
      // Setup clients
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      // Send and verify in one call
      const audioBuffer = createFakeAudioBuffer(3000);
      const result = await orchestrator.sendAndVerifyVoiceMessage(
        'alice',
        'bob',
        audioBuffer,
        3000,
      );

      expect(result.eventId).toBeDefined();
      expect(result.receivedMessage.sender).toBe('@alice:localhost');
      expect(result.receivedMessage.duration).toBeCloseTo(3000, -2);
    }, 30000);
  });

  describe('Multiple Messages', () => {
    test('send multiple messages in sequence', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send 5 messages
      const audioBuffers = createAudioBuffers(5, 2000);
      const eventIds: string[] = [];

      for (const buffer of audioBuffers) {
        const eventId = await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          buffer,
          'audio/mp4',
          2000,
        );
        eventIds.push(eventId);
      }

      // Wait a bit for all messages to sync
      await new Promise(resolve => setTimeout(resolve, 2000));

      // Verify all messages received
      const bobMessages = orchestrator.getVoiceMessages('bob', roomId);
      expect(bobMessages.length).toBeGreaterThanOrEqual(5);

      // Verify order (newer messages have higher timestamps)
      const timestamps = bobMessages.map(m => m.timestamp);
      for (let i = 1; i < timestamps.length; i++) {
        expect(timestamps[i]).toBeGreaterThanOrEqual(timestamps[i - 1]);
      }
    }, 45000);
  });

  describe('Bidirectional Communication', () => {
    test('alice and bob exchange messages', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Alice sends first message
      const audio1 = createFakeAudioBuffer(3000, { prefix: 'ALICE_MSG_1' });
      const event1 = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio1,
        'audio/mp4',
        3000,
      );

      // Bob receives alice's message
      await orchestrator.verifyMessageReceived('bob', roomId, {
        eventId: event1,
      });

      // Bob replies
      const audio2 = createFakeAudioBuffer(4000, { prefix: 'BOB_REPLY' });
      const event2 = await orchestrator.sendVoiceMessage(
        'bob',
        roomId,
        audio2,
        'audio/mp4',
        4000,
      );

      // Alice receives bob's reply
      const reply = await orchestrator.verifyMessageReceived(
        'alice',
        roomId,
        { eventId: event2 },
      );

      expect(reply.sender).toBe('@bob:localhost');
      expect(reply.duration).toBeCloseTo(4000, -2);

      // Check both clients see both messages
      const aliceMessages = orchestrator.getVoiceMessages('alice', roomId);
      const bobMessages = orchestrator.getVoiceMessages('bob', roomId);

      expect(aliceMessages.length).toBeGreaterThanOrEqual(2);
      expect(bobMessages.length).toBeGreaterThanOrEqual(2);
    }, 45000);
  });

  describe('Edge Cases', () => {
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
    }, 30000);

    test('longer voice message (15 seconds)', async () => {
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
    }, 30000);
  });

  describe('Audio Download', () => {
    test('bob can download audio uploaded by alice', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Create and send test audio
      const audioBuffer = createFakeAudioBuffer(3000);
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audioBuffer,
        'audio/mp4',
        3000,
      );

      // Verify bob receives the message
      const receivedMessage = await orchestrator.verifyMessageReceived(
        'bob',
        roomId,
        { eventId },
        15000,
      );

      // Verify the audio URL is valid
      expect(receivedMessage.audioUrl).toMatch(/^http/);

      // Actually download the audio to verify it works
      const response = await fetch(receivedMessage.audioUrl);
      expect(response.ok).toBe(true);
      expect(response.headers.get('content-type')).toMatch(/audio/);

      // Verify we got the audio data
      const downloadedBuffer = Buffer.from(await response.arrayBuffer());
      expect(downloadedBuffer.length).toBeGreaterThan(0);
      // The downloaded data should match what we uploaded
      expect(downloadedBuffer.equals(audioBuffer)).toBe(true);
    }, 30000);

    test('alice can download audio uploaded by bob', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Bob sends audio
      const audioBuffer = createFakeAudioBuffer(5000);
      const eventId = await orchestrator.sendVoiceMessage(
        'bob',
        roomId,
        audioBuffer,
        'audio/mp4',
        5000,
      );

      // Alice receives it
      const receivedMessage = await orchestrator.verifyMessageReceived(
        'alice',
        roomId,
        { eventId },
        15000,
      );

      // Verify Alice can download Bob's audio
      const response = await fetch(receivedMessage.audioUrl);
      expect(response.ok).toBe(true);

      const downloadedBuffer = Buffer.from(await response.arrayBuffer());
      expect(downloadedBuffer.equals(audioBuffer)).toBe(true);
    }, 30000);
  });
});
