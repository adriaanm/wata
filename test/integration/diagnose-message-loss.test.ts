/**
 * Diagnostic test to trace message sending and receiving
 *
 * This test sends messages one at a time and verifies each arrives
 * before sending the next, to isolate where message loss occurs.
 */

import {
  TestOrchestrator,
  createFakeAudioBuffer,
  AudioDurations,
} from './helpers';

const TEST_HOMESERVER = 'http://localhost:8008';

describe('Message Loss Diagnosis', () => {
  let orchestrator: TestOrchestrator;

  beforeAll(async () => {
    try {
      const response = await fetch(`${TEST_HOMESERVER}/_matrix/client/versions`);
      if (!response.ok) {
        throw new Error('Matrix server not responding');
      }
    } catch {
      throw new Error('Matrix server not running. Start it with: npm run dev:server');
    }
  }, 10000);

  beforeEach(async () => {
    orchestrator = new TestOrchestrator(TEST_HOMESERVER);
  }, 5000);

  afterEach(async () => {
    await orchestrator.cleanup();
  }, 10000);

  test('send 5 messages one-by-one, verify each arrives', async () => {
    const alice = 'alice';
    const bob = 'bob';
    const password = 'testpass123';

    console.log(`\n=== Test with users: ${alice} <-> ${bob} ===\n`);

    // Create and login users
    await orchestrator.createClient(alice, password);
    await orchestrator.createClient(bob, password);

    const roomId = await orchestrator.createRoom(alice, bob);
    console.log(`Room created: ${roomId}`);

    const sentEventIds: string[] = [];

    // Send messages one at a time
    for (let i = 0; i < 5; i++) {
      console.log(`\n--- Sending message ${i + 1}/5 ---`);

      const audio = createFakeAudioBuffer(AudioDurations.SHORT, {
        prefix: `MSG_${i + 1}`,
      });

      const eventId = await orchestrator.sendVoiceMessage(
        alice,
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      console.log(`  Sent with event_id: ${eventId}`);
      sentEventIds.push(eventId);

      // Wait for Bob to receive THIS specific message
      try {
        await orchestrator.verifyMessageReceived(
          bob,
          roomId,
          { eventId },
          10000,
        );
        console.log(`  ✓ Bob received message ${i + 1}`);
      } catch (error) {
        console.error(`  ✗ Bob did NOT receive message ${i + 1}`);

        // Get what Bob actually sees
        const bobMessages = orchestrator.getVoiceMessages(bob, roomId);
        console.log(`  Bob's timeline has ${bobMessages.length} messages total`);
        console.log(`  Bob's event IDs: ${bobMessages.map(m => m.eventId.slice(-8)).join(', ')}`);

        throw error;
      }
    }

    console.log(`\n=== All 5 messages sent and received ===`);
    console.log(`Sent event IDs (last 8 chars):`);
    sentEventIds.forEach((id, i) => {
      console.log(`  ${i + 1}. ${id.slice(-8)}`);
    });

    // Final verification
    const bobMessages = orchestrator.getVoiceMessages(bob, roomId);
    const bobEventIds = new Set(bobMessages.map(m => m.eventId));

    console.log(`\nBob's final timeline: ${bobMessages.length} messages`);

    const missingIds = sentEventIds.filter(id => !bobEventIds.has(id));
    if (missingIds.length > 0) {
      console.error(`\n✗ MISSING ${missingIds.length} messages:`);
      missingIds.forEach(id => console.error(`  - ${id}`));
    } else {
      console.log(`\n✓ All 5 messages accounted for`);
    }

    expect(missingIds.length).toBe(0);
  }, 60000);

  test('send 20 messages RAPIDLY (no wait), check delivery', async () => {
    const alice = 'alice';
    const bob = 'bob';
    const password = 'testpass123';

    console.log(`\n=== RAPID FIRE TEST: 20 messages ===\n`);

    await orchestrator.createClient(alice, password);
    await orchestrator.createClient(bob, password);

    const roomId = await orchestrator.createRoom(alice, bob);
    console.log(`Room: ${roomId}\n`);

    const sentEventIds: string[] = [];

    // Send 20 messages AS FAST AS POSSIBLE (await each send, but don't wait for receipt)
    console.log('Sending 20 messages rapidly...');
    const startTime = Date.now();

    for (let i = 0; i < 20; i++) {
      const audio = createFakeAudioBuffer(AudioDurations.SHORT);
      const eventId = await orchestrator.sendVoiceMessage(
        alice,
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      sentEventIds.push(eventId);
    }

    const sendDuration = Date.now() - startTime;
    console.log(`✓ Sent 20 messages in ${sendDuration}ms (${Math.round(sendDuration / 20)}ms per message)`);
    console.log(`Sent event IDs (last 8 chars): ${sentEventIds.map(id => id.slice(-8)).join(', ')}\n`);

    // NOW wait for all to arrive
    console.log('Waiting for Bob to receive all messages...');
    const waitStart = Date.now();

    try {
      await orchestrator.waitForEventIds(bob, roomId, new Set(sentEventIds), 30000);
      const waitDuration = Date.now() - waitStart;
      console.log(`✓ All 20 messages received in ${waitDuration}ms`);
    } catch (error) {
      const waitDuration = Date.now() - waitStart;
      console.error(`✗ Timeout after ${waitDuration}ms`);

      // Diagnose what Bob actually has
      const bobMessages = await orchestrator.getAllVoiceMessages(bob, roomId, 100);
      const bobEventIds = new Set(bobMessages.map(m => m.eventId));

      const receivedIds = sentEventIds.filter(id => bobEventIds.has(id));
      const missingIds = sentEventIds.filter(id => !bobEventIds.has(id));

      console.error(`\nBob's timeline: ${bobMessages.length} messages total`);
      console.error(`Received: ${receivedIds.length}/20`);
      console.error(`Missing: ${missingIds.length}/20`);
      console.error(`Missing IDs (last 8 chars): ${missingIds.map(id => id.slice(-8)).join(', ')}`);

      throw error;
    }

    // Final verification
    const bobMessages = orchestrator.getVoiceMessages(bob, roomId);
    const bobEventIds = new Set(bobMessages.map(m => m.eventId));
    const missingIds = sentEventIds.filter(id => !bobEventIds.has(id));

    expect(missingIds.length).toBe(0);
  }, 60000);
});
