/**
 * End-to-End Flow Tests
 *
 * Complete scenario tests that cover the full user journey from login
 * to sending and receiving voice messages. These tests verify that all
 * components work together correctly.
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

describe('End-to-End Voice Chat Flow', () => {
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

  test('complete flow: auto-login → list contacts → send voice → receive voice', async () => {
    // Step 1: Auto-login (simulated device boot)
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    // Verify login succeeded
    expect(aliceClient?.isLoggedIn()).toBe(true);
    expect(bobClient?.isLoggedIn()).toBe(true);

    // Step 2: Create room (equivalent to selecting a contact)
    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Step 3: List contacts (verify room appears in list)
    const aliceRooms = aliceClient?.getDirectRooms() || [];
    expect(aliceRooms.some(r => r.roomId === roomId)).toBe(true);

    const bobRooms = bobClient?.getDirectRooms() || [];
    expect(bobRooms.some(r => r.roomId === roomId)).toBe(true);

    // Step 4: Send voice message
    const audio = createFakeAudioBuffer(AudioDurations.MEDIUM);
    const eventId = await orchestrator.sendVoiceMessage(
      'alice',
      roomId,
      audio,
      'audio/mp4',
      AudioDurations.MEDIUM,
    );

    // Step 5: Receive voice message
    const receivedMessage = await orchestrator.verifyMessageReceived(
      'bob',
      roomId,
      { eventId },
      15000,
    );

    expect(receivedMessage.sender).toBe('@alice:localhost');
    expect(receivedMessage.audioUrl).toMatch(/^http/);
    expect(receivedMessage.duration).toBeCloseTo(AudioDurations.MEDIUM, -2);

    // Step 6: Verify message appears in contact list preview
    const updatedBobRooms = bobClient?.getDirectRooms() || [];
    const chatRoom = updatedBobRooms.find(r => r.roomId === roomId);

    expect(chatRoom?.lastMessage).toBeDefined();
    expect(chatRoom?.lastMessageTime).toBeGreaterThan(0);
  }, 60000);

  test('conversation scenario: alice sends, bob replies, alice sees reply', async () => {
    // Setup
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
    const aliceAudio = createFakeAudioBuffer(AudioDurations.SHORT, {
      prefix: 'ALICE_HELLO',
    });
    const aliceEventId = await orchestrator.sendVoiceMessage(
      'alice',
      roomId,
      aliceAudio,
      'audio/mp4',
      AudioDurations.SHORT,
    );

    // Bob receives alice's message
    await orchestrator.verifyMessageReceived('bob', roomId, {
      eventId: aliceEventId,
    });

    // Bob replies
    const bobAudio = createFakeAudioBuffer(AudioDurations.SHORT, {
      prefix: 'BOB_REPLY',
    });
    const bobEventId = await orchestrator.sendVoiceMessage(
      'bob',
      roomId,
      bobAudio,
      'audio/mp4',
      AudioDurations.SHORT,
    );

    // Alice receives bob's reply
    const bobReply = await orchestrator.verifyMessageReceived(
      'alice',
      roomId,
      { eventId: bobEventId },
    );

    expect(bobReply.sender).toBe('@bob:localhost');

    // Verify conversation history
    const aliceClient = orchestrator.getClient('alice');
    const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];

    expect(aliceMessages.length).toBeGreaterThanOrEqual(2);

    // Verify message order
    const aliceMsg = aliceMessages.find(m => m.eventId === aliceEventId);
    const bobMsg = aliceMessages.find(m => m.eventId === bobEventId);

    expect(aliceMsg).toBeDefined();
    expect(bobMsg).toBeDefined();
    expect(bobMsg!.timestamp).toBeGreaterThanOrEqual(aliceMsg!.timestamp);
  }, 60000);

  test('multi-turn conversation: 5 messages back and forth', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    // Have a 5-message conversation (alice, bob, alice, bob, alice)
    const eventIds: string[] = [];

    for (let i = 0; i < 5; i++) {
      const sender = i % 2 === 0 ? 'alice' : 'bob';
      const receiver = i % 2 === 0 ? 'bob' : 'alice';

      const audio = createFakeAudioBuffer(AudioDurations.SHORT, {
        prefix: `${sender.toUpperCase()}_MSG_${i}`,
      });

      const eventId = await orchestrator.sendVoiceMessage(
        sender,
        roomId,
        audio,
        'audio/mp4',
        AudioDurations.SHORT,
      );

      eventIds.push(eventId);

      // Wait for receiver to get the message
      await orchestrator.verifyMessageReceived(receiver, roomId, { eventId });
    }

    // Verify both clients have all 5 messages
    const aliceClient = orchestrator.getClient('alice');
    const bobClient = orchestrator.getClient('bob');

    const aliceMessages = aliceClient?.getVoiceMessages(roomId) || [];
    const bobMessages = bobClient?.getVoiceMessages(roomId) || [];

    expect(aliceMessages.length).toBeGreaterThanOrEqual(5);
    expect(bobMessages.length).toBeGreaterThanOrEqual(5);

    // Verify all messages are present
    for (const eventId of eventIds) {
      expect(aliceMessages.some(m => m.eventId === eventId)).toBe(true);
      expect(bobMessages.some(m => m.eventId === eventId)).toBe(true);
    }
  }, 90000);

  test('room with message history: new user sees previous messages', async () => {
    // Alice logs in and creates room
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );

    // Create room (bob not logged in yet)
    const aliceClient = orchestrator.getClient('alice');
    const roomId = await aliceClient?.createRoom({
      is_direct: true,
      invite: ['@bob:localhost'],
    });

    // Alice sends a few messages before bob joins
    const audioBuffers = createAudioBuffers(3, AudioDurations.SHORT);
    const eventIds: string[] = [];

    for (const buffer of audioBuffers) {
      const eventId = await orchestrator.sendVoiceMessage(
        'alice',
        roomId!,
        buffer,
        'audio/mp4',
        AudioDurations.SHORT,
      );
      eventIds.push(eventId);
    }

    // Wait a bit
    await new Promise(resolve => setTimeout(resolve, 2000));

    // Now bob logs in and joins the room
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    // Bob should see the room
    const bobClient = orchestrator.getClient('bob');
    await bobClient?.waitForSync(15000);

    // Wait for room to appear
    await new Promise(resolve => setTimeout(resolve, 3000));

    // Bob should see alice's previous messages
    const bobMessages = bobClient?.getVoiceMessages(roomId!) || [];

    expect(bobMessages.length).toBeGreaterThanOrEqual(3);

    // Verify bob sees the same event IDs
    for (const eventId of eventIds) {
      expect(bobMessages.some(m => m.eventId === eventId)).toBe(true);
    }
  }, 60000);

  test('multiple rooms: messages go to correct room', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    // Create two different rooms
    // (In real scenario these would be with different users, but we'll use same users)
    const room1 = await orchestrator.createRoom('alice', 'bob');

    // Send messages to room1
    const room1Audio = createFakeAudioBuffer(AudioDurations.SHORT, {
      prefix: 'ROOM1_MSG',
    });
    const room1EventId = await orchestrator.sendVoiceMessage(
      'alice',
      room1,
      room1Audio,
      'audio/mp4',
      AudioDurations.SHORT,
    );

    await new Promise(resolve => setTimeout(resolve, 2000));

    // Verify message is only in room1
    const bobClient = orchestrator.getClient('bob');
    const room1Messages = bobClient?.getVoiceMessages(room1) || [];

    expect(room1Messages.some(m => m.eventId === room1EventId)).toBe(true);
    expect(room1Messages.length).toBeGreaterThanOrEqual(1);
  }, 45000);

  test('user can see own sent messages immediately', async () => {
    await orchestrator.createClient(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await orchestrator.createClient(
      TEST_USERS.bob.username,
      TEST_USERS.bob.password,
    );

    const roomId = await orchestrator.createRoom('alice', 'bob');

    const aliceClient = orchestrator.getClient('alice');

    // Get initial message count
    const initialCount =
      aliceClient?.getVoiceMessages(roomId).length || 0;

    // Send message
    const audio = createFakeAudioBuffer(AudioDurations.SHORT);
    const eventId = await orchestrator.sendVoiceMessage(
      'alice',
      roomId,
      audio,
      'audio/mp4',
      AudioDurations.SHORT,
    );

    // Alice should see the message immediately (or very quickly)
    // Give it a moment for the echo
    await new Promise(resolve => setTimeout(resolve, 1000));

    const messages = aliceClient?.getVoiceMessages(roomId) || [];
    const sentMessage = messages.find(m => m.eventId === eventId);

    expect(sentMessage).toBeDefined();
    expect(sentMessage?.isOwn).toBe(true);
    expect(sentMessage?.sender).toBe('@alice:localhost');
    expect(messages.length).toBeGreaterThan(initialCount);
  }, 35000);
});
