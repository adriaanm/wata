/**
 * Stress Tests
 *
 * High-load tests to verify system stability and performance under stress.
 * These tests push the limits of message throughput and concurrency.
 */

import {
  TestOrchestrator,
  createAudioBuffers,
  AudioDurations,
} from './helpers';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

describe('Stress Tests', () => {
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
  }, 15000);

  describe('Rapid Fire Messaging', () => {
    test('30 messages in rapid succession', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send 30 messages as fast as possible
      const audioBuffers = createAudioBuffers(30, AudioDurations.SHORT);
      const eventIds: string[] = [];

      console.log('[STRESS] Starting rapid fire of 30 messages...');
      const startTime = Date.now();

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

      const sendDuration = Date.now() - startTime;
      console.log(`[STRESS] Sent 30 messages in ${sendDuration}ms`);

      // Wait for all messages to sync
      await new Promise(resolve => setTimeout(resolve, 10000));

      // Verify bob received all messages
      const bobClient = orchestrator.getClient('bob');
      const messages = bobClient?.getVoiceMessages(roomId) || [];

      console.log(`[STRESS] Bob received ${messages.length} messages`);
      expect(messages.length).toBeGreaterThanOrEqual(30);

      // Verify ordering
      for (let i = 1; i < Math.min(messages.length, 30); i++) {
        expect(messages[i].timestamp).toBeGreaterThanOrEqual(
          messages[i - 1].timestamp,
        );
      }

      // Verify all event IDs are present
      const receivedEventIds = messages.map(m => m.eventId);
      const missingEvents = eventIds.filter(
        id => !receivedEventIds.includes(id),
      );

      expect(missingEvents.length).toBe(0);
    }, 120000);

    test('50 messages stress test', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send 50 messages
      const audioBuffers = createAudioBuffers(50, AudioDurations.SHORT);
      const eventIds: string[] = [];

      console.log('[STRESS] Starting stress test with 50 messages...');
      const startTime = Date.now();

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

      const sendDuration = Date.now() - startTime;
      console.log(`[STRESS] Sent 50 messages in ${sendDuration}ms`);

      // Wait for sync
      await new Promise(resolve => setTimeout(resolve, 15000));

      // Verify
      const bobClient = orchestrator.getClient('bob');
      const messages = bobClient?.getVoiceMessages(roomId) || [];

      console.log(`[STRESS] Bob received ${messages.length} messages`);
      expect(messages.length).toBeGreaterThanOrEqual(50);

      // Check for duplicates
      const eventIdSet = new Set(messages.map(m => m.eventId));
      expect(eventIdSet.size).toBe(messages.length);
    }, 180000);

    test('concurrent sends from both users (50 total)', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Each user sends 25 messages concurrently
      const aliceBuffers = createAudioBuffers(25, AudioDurations.SHORT);
      const bobBuffers = createAudioBuffers(25, AudioDurations.SHORT);

      console.log('[STRESS] Starting concurrent sends from both users...');
      const startTime = Date.now();

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

      const allEventIds = await Promise.all([...aliceSends, ...bobSends]);
      const sendDuration = Date.now() - startTime;

      console.log(
        `[STRESS] Sent 50 concurrent messages in ${sendDuration}ms`,
      );

      // Wait for sync
      await new Promise(resolve => setTimeout(resolve, 15000));

      // Both clients should see all 50 messages
      const aliceClient = orchestrator.getClient('alice');
      const bobClient = orchestrator.getClient('bob');

      const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
      const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

      console.log(
        `[STRESS] Alice sees ${aliceMessages.length}, Bob sees ${bobMessages.length}`,
      );

      expect(aliceMessages.length).toBeGreaterThanOrEqual(50);
      expect(bobMessages.length).toBeGreaterThanOrEqual(50);

      // Verify all event IDs are present
      const aliceEventIds = new Set(aliceMessages.map(m => m.eventId));
      const bobEventIds = new Set(bobMessages.map(m => m.eventId));

      for (const eventId of allEventIds) {
        expect(aliceEventIds.has(eventId)).toBe(true);
        expect(bobEventIds.has(eventId)).toBe(true);
      }
    }, 180000);
  });

  describe('Message Burst Patterns', () => {
    test('burst pattern: 10 messages, pause, 10 more', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // First burst: 10 messages
      const burst1Buffers = createAudioBuffers(10, AudioDurations.SHORT);
      const burst1EventIds: string[] = [];

      for (const buffer of burst1Buffers) {
        const eventId = await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          buffer,
          'audio/mp4',
          AudioDurations.SHORT,
        );
        burst1EventIds.push(eventId);
      }

      // Wait
      await new Promise(resolve => setTimeout(resolve, 5000));

      // Second burst: 10 messages
      const burst2Buffers = createAudioBuffers(10, AudioDurations.SHORT);
      const burst2EventIds: string[] = [];

      for (const buffer of burst2Buffers) {
        const eventId = await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          buffer,
          'audio/mp4',
          AudioDurations.SHORT,
        );
        burst2EventIds.push(eventId);
      }

      // Wait for sync
      await new Promise(resolve => setTimeout(resolve, 5000));

      // Verify bob received all 20 messages
      const bobClient = orchestrator.getClient('bob');
      const messages = bobClient?.getVoiceMessages(roomId) || [];

      expect(messages.length).toBeGreaterThanOrEqual(20);

      // Verify all messages are present
      const allEventIds = [...burst1EventIds, ...burst2EventIds];
      const receivedEventIds = messages.map(m => m.eventId);

      for (const eventId of allEventIds) {
        expect(receivedEventIds.includes(eventId)).toBe(true);
      }
    }, 90000);

    test('sustained load: 20 messages over 10 seconds', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send 20 messages with 500ms delay between each
      const audioBuffers = createAudioBuffers(20, AudioDurations.SHORT);
      const eventIds: string[] = [];

      const startTime = Date.now();

      for (const buffer of audioBuffers) {
        const eventId = await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          buffer,
          'audio/mp4',
          AudioDurations.SHORT,
        );
        eventIds.push(eventId);

        // 500ms delay
        await new Promise(resolve => setTimeout(resolve, 500));
      }

      const totalTime = Date.now() - startTime;
      console.log(`[STRESS] Sustained load completed in ${totalTime}ms`);

      // Wait for final sync
      await new Promise(resolve => setTimeout(resolve, 3000));

      // Verify
      const bobClient = orchestrator.getClient('bob');
      const messages = bobClient?.getVoiceMessages(roomId) || [];

      expect(messages.length).toBeGreaterThanOrEqual(20);

      // Verify ordering
      for (let i = 1; i < messages.length; i++) {
        expect(messages[i].timestamp).toBeGreaterThanOrEqual(
          messages[i - 1].timestamp,
        );
      }
    }, 90000);
  });

  describe('Performance Metrics', () => {
    test('measure send latency for 10 messages', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Measure time to send 10 messages
      const audioBuffers = createAudioBuffers(10, AudioDurations.SHORT);
      const latencies: number[] = [];

      for (const buffer of audioBuffers) {
        const start = Date.now();

        await orchestrator.sendVoiceMessage(
          'alice',
          roomId,
          buffer,
          'audio/mp4',
          AudioDurations.SHORT,
        );

        const latency = Date.now() - start;
        latencies.push(latency);
      }

      const avgLatency =
        latencies.reduce((a, b) => a + b, 0) / latencies.length;
      const minLatency = Math.min(...latencies);
      const maxLatency = Math.max(...latencies);

      console.log('[PERF] Send latency statistics:');
      console.log(`  Average: ${avgLatency.toFixed(2)}ms`);
      console.log(`  Min: ${minLatency}ms`);
      console.log(`  Max: ${maxLatency}ms`);

      // Sanity check: average latency should be reasonable
      expect(avgLatency).toBeLessThan(5000); // Less than 5 seconds per message
    }, 90000);

    test('measure end-to-end message delivery time', async () => {
      await orchestrator.createClient(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await orchestrator.createClient(
        TEST_USERS.bob.username,
        TEST_USERS.bob.password,
      );

      const roomId = await orchestrator.createRoom('alice', 'bob');

      // Send message and measure time until bob receives it
      const audio = createAudioBuffers(1, AudioDurations.SHORT)[0];

      const startTime = Date.now();

      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      await orchestrator.verifyMessageReceived('bob', roomId, { eventId });

      const deliveryTime = Date.now() - startTime;

      console.log(`[PERF] End-to-end delivery time: ${deliveryTime}ms`);

      // Sanity check: delivery should happen within reasonable time
      expect(deliveryTime).toBeLessThan(30000); // Less than 30 seconds
    }, 45000);
  });
});
