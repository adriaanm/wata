/**
 * Message Ordering Tests
 *
 * Stress tests to verify message ordering and consistency across multiple clients.
 * These tests ensure messages arrive in the correct order even under high load.
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

describe('Message Ordering', () => {
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

  test('rapid fire: 10 messages maintain order', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Send 10 messages as fast as possible
    const audioBuffers = createAudioBuffers(10, AudioDurations.SHORT);
    const eventIds: string[] = [];

    for (const buffer of audioBuffers) {
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      eventIds.push(eventId);
    }

    // Wait for all messages to sync
    await new Promise(resolve => setTimeout(resolve, 5000));

    // Verify bob received all messages in order
    const bobClient = orchestrator.getClient('bob');
    const messages = bobClient?.getVoiceMessages(roomId) || [];

    expect(messages.length).toBeGreaterThanOrEqual(10);

    // Check timestamps are monotonically increasing
    for (let i = 1; i < Math.min(messages.length, 10); i++) {
      expect(messages[i].timestamp).toBeGreaterThanOrEqual(
        messages[i - 1].timestamp,
      );
    }
  }, 60000);

  test('rapid fire: 20 messages maintain order', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Send 20 messages
    const audioBuffers = createAudioBuffers(20, AudioDurations.SHORT);
    const eventIds: string[] = [];

    for (const buffer of audioBuffers) {
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      eventIds.push(eventId);
    }

    // Wait for all messages to sync
    await new Promise(resolve => setTimeout(resolve, 8000));

    // Verify message ordering
    const bobClient = orchestrator.getClient('bob');
    const messages = bobClient?.getVoiceMessages(roomId) || [];

    expect(messages.length).toBeGreaterThanOrEqual(20);

    // Verify timestamps are ordered
    for (let i = 1; i < messages.length; i++) {
      expect(messages[i].timestamp).toBeGreaterThanOrEqual(
        messages[i - 1].timestamp,
      );
    }
  }, 90000);

  test('alternating senders maintain order', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Send messages alternating between alice and bob
    const sends = [];
    for (let i = 0; i < 10; i++) {
      const sender = i % 2 === 0 ? 'alice' : 'bob';
      const audio = createFakeAudioBuffer(AudioDurations.SHORT, {
        prefix: `${sender.toUpperCase()}_MSG_${i}`,
      });

      sends.push(
        orchestrator.sendVoiceMessage(
          sender,
          roomId,
          audio,
          'audio/mp4',
          AudioDurations.SHORT,
        ),
      );

      // Small delay between sends to ensure ordering
      await new Promise(resolve => setTimeout(resolve, 100));
    }

    await Promise.all(sends);

    // Wait for all messages to sync
    await new Promise(resolve => setTimeout(resolve, 8000));

    // Both clients should see the same ordering
    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
    const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

    expect(aliceMessages.length).toBeGreaterThanOrEqual(10);
    expect(bobMessages.length).toBeGreaterThanOrEqual(10);

    // Timestamps should be monotonically increasing for both clients
    for (let i = 1; i < Math.min(aliceMessages.length, 10); i++) {
      expect(aliceMessages[i].timestamp).toBeGreaterThanOrEqual(
        aliceMessages[i - 1].timestamp,
      );
    }

    for (let i = 1; i < Math.min(bobMessages.length, 10); i++) {
      expect(bobMessages[i].timestamp).toBeGreaterThanOrEqual(
        bobMessages[i - 1].timestamp,
      );
    }
  }, 70000);

  test('concurrent sends from both users', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Both users send 5 messages concurrently
    const aliceBuffers = createAudioBuffers(5, AudioDurations.SHORT);
    const bobBuffers = createAudioBuffers(5, AudioDurations.SHORT);

    const aliceSends = aliceBuffers.map(buffer =>
      orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      ),
    );

    const bobSends = bobBuffers.map(buffer =>
      orchestrator.sendVoiceMessage(
        'bob',
        roomId,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      ),
    );

    await Promise.all([...aliceSends, ...bobSends]);

    // Wait for sync (longer for concurrent sends)
    await new Promise(resolve => setTimeout(resolve, 10000));

    // Both clients should see all 10 messages
    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
    const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

    expect(aliceMessages.length).toBeGreaterThanOrEqual(10);
    expect(bobMessages.length).toBeGreaterThanOrEqual(10);

    // Messages should be ordered by timestamp
    for (let i = 1; i < aliceMessages.length; i++) {
      expect(aliceMessages[i].timestamp).toBeGreaterThanOrEqual(
        aliceMessages[i - 1].timestamp,
      );
    }

    for (let i = 1; i < bobMessages.length; i++) {
      expect(bobMessages[i].timestamp).toBeGreaterThanOrEqual(
        bobMessages[i - 1].timestamp,
      );
    }
  }, 70000);

  test('messages have unique event IDs', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Send multiple messages
    const audioBuffers = createAudioBuffers(15, AudioDurations.SHORT);
    const eventIds: string[] = [];

    for (const buffer of audioBuffers) {
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      eventIds.push(eventId);
    }

    // Wait for sync
    await new Promise(resolve => setTimeout(resolve, 5000));

    // Verify all event IDs are unique
    const uniqueEventIds = new Set(eventIds);
    expect(uniqueEventIds.size).toBe(eventIds.length);

    // Verify bob sees messages with same event IDs
    const bobClient = orchestrator.getClient('bob');
    const messages = bobClient?.getVoiceMessages(roomId) || [];

    expect(messages.length).toBeGreaterThanOrEqual(15);

    // Check that bob's messages have the same event IDs
    const bobEventIds = messages.slice(0, 15).map(m => m.eventId);
    const matchingIds = bobEventIds.filter(id => eventIds.includes(id));

    expect(matchingIds.length).toBe(eventIds.length);
  }, 70000);

  test('timestamp consistency across clients', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Send a message
    const audio = createFakeAudioBuffer(AudioDurations.SHORT);
    const eventId = await orchestrator.sendVoiceMessage(
      'alice',
      roomId,
      audio,
      'audio/mp4',
      AudioDurations.SHORT,
    );

    // Wait for sync
    await new Promise(resolve => setTimeout(resolve, 2000));

    // Both clients should see the same timestamp for this message
    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
    const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

    const aliceMsg = aliceMessages.find(m => m.eventId === eventId);
    const bobMsg = bobMessages.find(m => m.eventId === eventId);

    expect(aliceMsg).toBeDefined();
    expect(bobMsg).toBeDefined();

    // Timestamps should be identical (server-side timestamp)
    expect(aliceMsg?.timestamp).toBe(bobMsg?.timestamp);
  }, 35000);
});
