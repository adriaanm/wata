/**
 * Read Receipt Tests
 *
 * Tests for read receipt flow: Alice sends message, Bob plays it, Alice sees readBy update.
 * These tests specifically target the WataClient/MatrixServiceAdapter implementation.
 */

import {
  createTestService,
  createTestCredentialStorage,
  getImplementationName,
} from './helpers/test-service-factory';
import { createFakeAudioBuffer } from './helpers';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

describe('Read Receipts (WataClient)', () => {
  let aliceService: ReturnType<typeof createTestService>;
  let bobService: ReturnType<typeof createTestService>;

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
        'Matrix server not running. Start it with: pnpm dev:server',
      );
    }

    // Log which implementation is being used
    console.log(`\n  Using implementation: ${getImplementationName()}\n`);
  }, 10000);

  beforeEach(async () => {
    // Create separate services for alice and bob
    aliceService = createTestService(
      TEST_HOMESERVER,
      createTestCredentialStorage(),
    );
    bobService = createTestService(
      TEST_HOMESERVER,
      createTestCredentialStorage(),
    );

    // Login both users
    await aliceService.login(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);

    // Wait for sync
    await aliceService.waitForSync(10000);
    await bobService.waitForSync(10000);
  }, 30000);

  afterEach(async () => {
    try {
      await aliceService.logout();
    } catch {
      // Ignore logout errors
    }
    try {
      await bobService.logout();
    } catch {
      // Ignore logout errors
    }
  }, 10000);

  test('bob marks message as played, alice sees readBy update', async () => {
    // Step 1: Alice creates DM with Bob
    console.log('[Test] Step 1: Alice creates DM with Bob');
    const roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
    expect(roomId).toBeTruthy();
    console.log(`[Test] Room created: ${roomId}`);

    // Step 2: Wait for Bob to auto-join the room via sync
    // WataClient auto-joins invites, so we wait for the room to appear in Bob's rooms
    console.log('[Test] Step 2: Waiting for Bob to auto-join the room...');
    let bobRooms = bobService.getDirectRooms();
    let attempts = 0;
    while (!bobRooms.some(r => r.roomId === roomId) && attempts < 20) {
      await new Promise(resolve => setTimeout(resolve, 500));
      bobRooms = bobService.getDirectRooms();
      attempts++;
    }
    console.log(`[Test] Bob has ${bobRooms.length} rooms after ${attempts} attempts`);
    console.log(`[Test] Bob's rooms: ${bobRooms.map(r => r.roomId).join(', ')}`);

    // Step 3: Alice sends voice message
    console.log('[Test] Step 3: Alice sends voice message');
    const audioBuffer = createFakeAudioBuffer(3000);
    await aliceService.sendVoiceMessage(
      roomId,
      audioBuffer,
      'audio/mp4',
      3000,
      audioBuffer.length,
    );

    // Wait for sync
    await new Promise(resolve => setTimeout(resolve, 2000));

    // Step 4: Verify Alice's message is in her timeline
    console.log('[Test] Step 4: Verify Alice sees her message');
    let aliceMessages = aliceService.getVoiceMessages(roomId);
    console.log(`[Test] Alice has ${aliceMessages.length} messages`);
    expect(aliceMessages.length).toBeGreaterThanOrEqual(1);

    const sentMessage = aliceMessages[aliceMessages.length - 1];
    console.log(`[Test] Message eventId: ${sentMessage.eventId}`);
    console.log(`[Test] Message readBy (before): ${JSON.stringify(sentMessage.readBy)}`);

    // Step 5: Wait for Bob to receive the message
    console.log('[Test] Step 5: Waiting for Bob to receive message...');
    let bobMessages = bobService.getVoiceMessages(roomId);  // Use Alice's roomId!
    attempts = 0;
    while (bobMessages.length === 0 && attempts < 20) {
      await new Promise(resolve => setTimeout(resolve, 500));
      bobMessages = bobService.getVoiceMessages(roomId);
      attempts++;
    }
    console.log(`[Test] Bob has ${bobMessages.length} messages after ${attempts} attempts`);

    expect(bobMessages.length).toBeGreaterThanOrEqual(1);
    const messageToPlay = bobMessages.find(m => m.eventId === sentMessage.eventId) || bobMessages[bobMessages.length - 1];
    console.log(`[Test] Bob will play message: ${messageToPlay.eventId}`);

    // Step 6: Bob marks message as played
    console.log('[Test] Step 6: Bob marks message as played');
    await bobService.markMessageAsPlayed(roomId, messageToPlay.eventId);  // Use Alice's roomId!
    console.log('[Test] Bob marked message as played');

    // Step 7: Wait for Alice to receive the receipt via sync
    console.log('[Test] Step 7: Waiting for Alice to receive receipt...');

    // Set up a listener for receipt updates
    let receiptReceived = false;
    const unsubscribe = aliceService.onReceiptUpdate((receiptRoomId: string) => {
      console.log(`[Test] Alice received receipt update for room ${receiptRoomId}`);
      if (receiptRoomId === roomId) {
        receiptReceived = true;
      }
    });

    // Wait for the receipt to propagate (up to 15 seconds)
    const startTime = Date.now();
    while (!receiptReceived && Date.now() - startTime < 15000) {
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    unsubscribe();

    console.log(`[Test] Receipt received: ${receiptReceived}`);

    // Step 8: Verify Alice's message now shows Bob in readBy
    console.log('[Test] Step 8: Verify Alice sees Bob in readBy');
    aliceMessages = aliceService.getVoiceMessages(roomId);
    const updatedMessage = aliceMessages.find(
      m => m.eventId === sentMessage.eventId,
    );

    console.log(`[Test] Updated message readBy: ${JSON.stringify(updatedMessage?.readBy)}`);

    expect(updatedMessage).toBeDefined();
    // TODO: Condition need not hold when running with matrix-js-sdk — this test is
    // WataClient-specific (readBy requires WataClient's receipt tracking). The describe
    // block is named "Read Receipts (WataClient)" but the test is not actually skipped
    // when running against matrix-js-sdk. Fix: skip when !isUsingWataClient().
    expect(updatedMessage!.readBy).toContain('@bob:localhost');
  }, 90000);

  test('receipt callback fires when message is played', async () => {
    // Create DM room
    console.log('[Test] Creating DM room...');
    const roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
    console.log(`[Test] Room created: ${roomId}`);

    // Wait for Bob to auto-join
    console.log('[Test] Waiting for Bob to auto-join...');
    let attempts = 0;
    while (attempts < 20) {
      const bobRooms = bobService.getDirectRooms();
      if (bobRooms.some(r => r.roomId === roomId)) {
        console.log('[Test] Bob auto-joined the room');
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 500));
      attempts++;
    }

    // Alice sends message
    console.log('[Test] Alice sending message...');
    const audioBuffer = createFakeAudioBuffer(2000);
    await aliceService.sendVoiceMessage(
      roomId,
      audioBuffer,
      'audio/mp4',
      2000,
      audioBuffer.length,
    );
    await new Promise(resolve => setTimeout(resolve, 1000));

    // Get message ID
    const aliceMessages = aliceService.getVoiceMessages(roomId);
    console.log(`[Test] Alice has ${aliceMessages.length} messages`);
    expect(aliceMessages.length).toBeGreaterThanOrEqual(1);
    const sentMessage = aliceMessages[aliceMessages.length - 1];
    console.log(`[Test] Sent message: ${sentMessage.eventId}`);

    // Wait for Bob to receive the message
    console.log('[Test] Waiting for Bob to receive message...');
    attempts = 0;
    let bobMessages = bobService.getVoiceMessages(roomId);  // Use Alice's roomId
    while (bobMessages.length === 0 && attempts < 20) {
      await new Promise(resolve => setTimeout(resolve, 500));
      bobMessages = bobService.getVoiceMessages(roomId);
      attempts++;
    }
    console.log(`[Test] Bob has ${bobMessages.length} messages`);

    // Set up receipt callback on Alice's side BEFORE Bob plays
    let callbackFired = false;
    let callbackRoomId: string | null = null;
    const unsubscribe = aliceService.onReceiptUpdate((rid: string) => {
      console.log(`[Test] Receipt callback fired for room ${rid}`);
      callbackFired = true;
      callbackRoomId = rid;
    });

    // Bob marks as played
    if (bobMessages.length > 0) {
      const messageToPlay = bobMessages.find(m => m.eventId === sentMessage.eventId) || bobMessages[bobMessages.length - 1];
      console.log(`[Test] Bob marking message as played: ${messageToPlay.eventId}`);
      await bobService.markMessageAsPlayed(roomId, messageToPlay.eventId);  // Use Alice's roomId
    }

    // Wait for callback
    console.log('[Test] Waiting for receipt callback...');
    const startTime = Date.now();
    while (!callbackFired && Date.now() - startTime < 15000) {
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    unsubscribe();

    console.log(`[Test] Callback fired: ${callbackFired}, roomId: ${callbackRoomId}`);
    expect(callbackFired).toBe(true);
    expect(callbackRoomId).toBe(roomId);
  }, 60000);
});
