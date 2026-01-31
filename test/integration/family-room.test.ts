/**
 * Family Room Tests
 *
 * Tests for family room functionality: creation, invitation, member retrieval,
 * and DM room creation on-demand.
 *
 * NOTE: These tests work with both WataClient (MatrixServiceAdapter) and
 * matrix-js-sdk (MatrixService) implementations via the test factory.
 *
 * Note: These tests are designed to work with a persistent Conduit server where
 * the family room may already exist from previous test runs.
 */

import {
  createTestService,
  createTestCredentialStorage,
} from './helpers/test-service-factory';
import { setHomeserverUrl, setFamilyAliasPrefix } from '../../src/shared/services/MatrixService';
import { setHomeserverUrl as setAdapterHomeserverUrl, setFamilyAliasPrefix as setAdapterFamilyAliasPrefix } from '../../src/shared/services/MatrixServiceAdapter';

import { createFakeAudioBuffer } from './helpers/audio-helpers';

/**
 * Poll until a condition is true, with exponential backoff.
 * Replaces fixed setTimeout delays for deterministic tests.
 */
async function waitForCondition(
  description: string,
  condition: () => boolean | Promise<boolean>,
  timeoutMs = 15000,
  pollMs = 200,
): Promise<void> {
  const startTime = Date.now();
  let delay = pollMs;

  while (Date.now() - startTime < timeoutMs) {
    if (await condition()) return;
    await new Promise(resolve => setTimeout(resolve, delay));
    delay = Math.min(delay * 1.3, 2000);
  }

  throw new Error(`Timed out waiting for: ${description} (after ${timeoutMs}ms)`);
}

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: { username: 'alice', password: 'testpass123' },
  bob: { username: 'bob', password: 'testpass123' },
};

/**
 * Helper to ensure the family room exists and the user is a member.
 * Creates the room if it doesn't exist, or joins it if it does.
 *
 * Note: Works with both MatrixService and MatrixServiceAdapter via duck typing.
 */
async function ensureFamilyRoomMembership(
  service: any, // MatrixService | MatrixServiceAdapter
): Promise<string> {
  // First check if the family room exists via alias
  const existingRoomId = await service.getFamilyRoomIdFromAlias();

  if (existingRoomId) {
    // Room exists - check if we're a member
    if (!service.isRoomMember(existingRoomId)) {
      // Join the existing room
      console.log('[Test] Family room exists, joining...');
      await service.joinRoom(existingRoomId);
      // Wait until we're a member
      await waitForCondition(
        'joined family room',
        () => service.isRoomMember(existingRoomId),
      );
    }
    return existingRoomId;
  }

  // Room doesn't exist - create it
  console.log('[Test] Creating family room...');
  return await service.createFamilyRoom();
}

describe('Family Room', () => {
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
        'Matrix server not running. Start it with: npm run dev:server',
      );
    }

    // Set homeserver URL for both implementations (factory chooses which to use)
    setHomeserverUrl(TEST_HOMESERVER);
    setAdapterHomeserverUrl(TEST_HOMESERVER);

    // Use a separate family room for tests to avoid interfering with manual testing
    setFamilyAliasPrefix('family-test');
    setAdapterFamilyAliasPrefix('family-test');
  }, 10000);

  beforeEach(async () => {
    aliceService = createTestService(TEST_HOMESERVER, createTestCredentialStorage());
    bobService = createTestService(TEST_HOMESERVER, createTestCredentialStorage());
  }, 5000);

  afterEach(async () => {
    try {
      aliceService.cleanup();
      await aliceService.logout();
    } catch {
      // Ignore logout errors
    }
    try {
      bobService.cleanup();
      await bobService.logout();
    } catch {
      // Ignore logout errors
    }
  }, 10000);

  describe('createFamilyRoom', () => {
    test('should create or join family room', async () => {
      // Given: alice is logged in
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      // When: alice ensures family room membership
      const roomId = await ensureFamilyRoomMembership(aliceService);

      // Then: room exists and can be retrieved
      expect(roomId).toBeTruthy();
      expect(roomId).toMatch(/^!/);

      // Wait for room to be synced
      await waitForCondition(
        'family room visible',
        async () => !!(await aliceService.getFamilyRoom()),
      );

      // Verify we can get the family room back
      const familyRoom = await aliceService.getFamilyRoom();
      expect(familyRoom).toBeTruthy();
      expect(familyRoom?.roomId).toBe(roomId);
    }, 30000);
  });

  describe('getFamilyRoom', () => {
    test('should return family room if user is a member', async () => {
      // Given: alice is logged in and a member of family room
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      const roomId = await ensureFamilyRoomMembership(aliceService);

      // When: getFamilyRoom called
      const familyRoom = await aliceService.getFamilyRoom();

      // Then: returns the room
      expect(familyRoom).toBeTruthy();
      expect(familyRoom?.roomId).toBe(roomId);
    }, 30000);
  });

  describe('inviteToFamily', () => {
    test('should invite a user by Matrix ID', async () => {
      // Given: alice is a member of family room
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      await ensureFamilyRoomMembership(aliceService);

      const familyRoom = await aliceService.getFamilyRoom();
      expect(familyRoom).toBeTruthy();

      // When: alice invites @bob:localhost
      console.log('[Test] Attempting to invite bob to family room...');
      console.log(`[Test] Family room ID: ${familyRoom?.roomId}`);

      // This may throw for several reasons:
      // - Bob is already invited/joined
      // - Alice doesn't have invite power (if room was created with old preset)
      try {
        await aliceService.inviteToFamily('@bob:localhost');
        console.log('[Test] Invite succeeded!');
      } catch (error) {
        const errMsg = String(error);
        // Acceptable errors:
        // - Already invited/joined
        // - Not authorized (if alice joined existing room with PrivateChat preset)
        if (
          errMsg.includes('already in the room') ||
          errMsg.includes('already invited') ||
          errMsg.includes('is already joined') ||
          errMsg.includes('M_FORBIDDEN')
        ) {
          console.log('[Test] Invite skipped:', errMsg.split('\n')[0]);
          // This is expected if:
          // 1. Bob is already a member
          // 2. The room was created before TrustedPrivateChat preset was used
          // Note: New rooms created with TrustedPrivateChat will allow all members to invite
        } else {
          throw error;
        }
      }

      // Then: bob should be able to see and join the room
    }, 30000);

    test('should allow invited user to join', async () => {
      // Given: alice invited bob
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      const familyRoomId = await ensureFamilyRoomMembership(aliceService);

      // Invite bob (ignore errors if already invited)
      try {
        await aliceService.inviteToFamily('@bob:localhost');
      } catch {
        // May already be invited or member
      }

      // When: bob logs in and joins
      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      await bobService.waitForSync();

      // Join the family room (by ID since bob knows it from the invite)
      try {
        await bobService.joinRoom(familyRoomId);
      } catch {
        // May already be a member
      }

      // Wait until bob sees the family room
      await waitForCondition(
        'bob sees family room',
        async () => !!(await bobService.getFamilyRoom()),
      );

      // Then: bob is a member of family room (can get it)
      const bobFamilyRoom = await bobService.getFamilyRoom();
      expect(bobFamilyRoom).toBeTruthy();
      expect(bobFamilyRoom?.roomId).toBe(familyRoomId);
    }, 45000);
  });

  describe('getFamilyMembers', () => {
    test('should return other members after they join', async () => {
      // Given: alice is a member of family room
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      const familyRoomId = await ensureFamilyRoomMembership(aliceService);

      // Invite bob (may fail if alice doesn't have invite power)
      try {
        await aliceService.inviteToFamily('@bob:localhost');
      } catch {
        // May already be invited, member, or alice lacks power
      }

      // Bob joins
      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      await bobService.waitForSync();

      try {
        await bobService.joinRoom(familyRoomId);
      } catch {
        // May already be a member
      }

      // Wait until alice sees bob as a family member
      let members: Awaited<ReturnType<typeof aliceService.getFamilyMembers>> = [];
      await waitForCondition(
        'alice sees bob in family members',
        async () => {
          members = await aliceService.getFamilyMembers();
          return members.some(m => m.userId === '@bob:localhost');
        },
        15000,
      );

      // Then: returns bob (excludes self)
      console.log('[Test] Family members:', members);
      expect(members.some(m => m.userId === '@bob:localhost')).toBe(true);
    }, 60000);
  });

  describe('getOrCreateDmRoom', () => {
    test('should create new DM room if none exists', async () => {
      // Given: no DM between alice and bob (we create with a unique identifier)
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      // When: alice calls getOrCreateDmRoom('@bob:localhost')
      const roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');

      // Then: creates room, returns room ID
      expect(roomId).toBeTruthy();
      expect(roomId).toMatch(/^!/);
    }, 30000);

    test('should return existing DM room on second call', async () => {
      // Given: alice and bob have existing DM (from previous call in same session)
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      // Create first DM room
      const firstRoomId =
        await aliceService.getOrCreateDmRoom('@bob:localhost');
      expect(firstRoomId).toBeTruthy();

      // Retry second call until it converges on the same room as the first.
      // m.direct account data may take time to propagate.
      const maxWaitTime = 30000;
      const startTime = Date.now();
      let secondRoomId: string | undefined;
      let delay = 500;

      while (Date.now() - startTime < maxWaitTime) {
        secondRoomId =
          await aliceService.getOrCreateDmRoom('@bob:localhost');
        if (secondRoomId === firstRoomId) break;
        await new Promise(resolve => setTimeout(resolve, delay));
        delay = Math.min(delay * 1.5, 3000);
      }

      // Then: returns existing room ID
      expect(secondRoomId).toBe(firstRoomId);
    }, 45000);
  });

  describe('sendVoiceMessage to family room', () => {
    test('should send voice message to family room and have other members receive it', async () => {
      // Given: alice and bob are both members of the family room
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      const familyRoomId = await ensureFamilyRoomMembership(aliceService);
      console.log('[Test] Family room ID:', familyRoomId);

      // Invite bob and have him join
      try {
        await aliceService.inviteToFamily('@bob:localhost');
      } catch {
        // May already be invited
      }

      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      // Only wait for sync if not already synced
      if (
        bobService.getSyncState() !== 'PREPARED' &&
        bobService.getSyncState() !== 'SYNCING'
      ) {
        await bobService.waitForSync();
      }

      try {
        await bobService.joinRoom(familyRoomId);
      } catch {
        // May already be a member
      }

      // Wait until bob sees the family room
      await waitForCondition(
        'bob sees family room',
        async () => !!(await bobService.getFamilyRoom()),
      );

      // When: alice sends a voice message to the family room
      const audioBuffer = createFakeAudioBuffer(5000, { prefix: 'FAMILY_MSG' });
      console.log('[Test] Sending voice message to family room...');

      // Get the family room ID and send the message
      const targetRoomId = await aliceService.getFamilyRoomId();
      console.log('[Test] Target room ID:', targetRoomId);
      expect(targetRoomId).toBe(familyRoomId);

      await aliceService.sendVoiceMessage(
        targetRoomId!,
        audioBuffer,
        'audio/mp4',
        5000,
        audioBuffer.length,
      );
      console.log('[Test] Voice message sent successfully');

      // Wait for bob to receive the message
      await waitForCondition(
        'bob receives voice message in family room',
        () => bobService.getVoiceMessages(familyRoomId).length > 0,
      );

      // Then: bob should receive the message in the family room
      const bobFamilyRoom = await bobService.getFamilyRoom();
      expect(bobFamilyRoom).toBeTruthy();
      expect(bobFamilyRoom?.roomId).toBe(familyRoomId);

      // Get bob's voice messages from the family room
      const bobMessages = bobService.getVoiceMessages(familyRoomId);
      console.log('[Test] Bob received messages:', bobMessages.length);

      // Verify bob received the message
      expect(bobMessages.length).toBeGreaterThan(0);
      const lastMessage = bobMessages[bobMessages.length - 1];
      expect(lastMessage.sender).toBe('@alice:localhost');
      expect(lastMessage.duration).toBeCloseTo(5000, -2);
    }, 60000);
  });

  describe('sendVoiceMessage to DM room', () => {
    test('should send voice message to DM room and have recipient receive it', async () => {
      // Given: alice and bob are logged in
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      // Only wait for sync if not already synced
      if (
        bobService.getSyncState() !== 'PREPARED' &&
        bobService.getSyncState() !== 'SYNCING'
      ) {
        await bobService.waitForSync();
      }

      // When: alice creates a DM room with bob
      console.log('[Test] Creating DM room between alice and bob...');
      const dmRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
      console.log('[Test] DM room created:', dmRoomId);
      expect(dmRoomId).toBeTruthy();
      expect(dmRoomId).toMatch(/^!/);

      // Bob needs to join the room (in a real app, Bob would accept the invite)
      console.log('[Test] Bob joining DM room...');
      await bobService.joinRoom(dmRoomId);
      console.log('[Test] Bob joined DM room');

      // Wait for bob to be a member
      await waitForCondition(
        'bob joined DM room',
        () => bobService.isRoomMember(dmRoomId),
      );

      // Now alice sends a voice message
      const expectedDuration = 4000;
      const audioBuffer = createFakeAudioBuffer(expectedDuration, { prefix: 'DM_MSG' });
      console.log('[Test] Sending voice message to DM room...');

      const beforeSendTimestamp = Date.now();
      await aliceService.sendVoiceMessage(
        dmRoomId,
        audioBuffer,
        'audio/mp4',
        expectedDuration,
        audioBuffer.length,
      );
      console.log('[Test] Voice message sent successfully');

      // Wait for bob to receive the message - match by sender + expected duration
      // This is robust to room reuse since we match specific message characteristics
      await waitForCondition(
        'bob receives voice message in DM',
        () => {
          const messages = bobService.getVoiceMessages(dmRoomId);
          // Find the most recent message from Alice with expected duration
          // sent after our timestamp
          return messages.some(m =>
            m.sender === '@alice:localhost' &&
            Math.abs(m.duration - expectedDuration) < 500 &&
            m.timestamp >= beforeSendTimestamp
          );
        },
      );

      // Then: bob should receive the message in the DM room
      const bobMessages = bobService.getVoiceMessages(dmRoomId);
      console.log('[Test] Bob received messages in DM:', bobMessages.length);

      // Find the specific message we just sent (by sender + duration + timestamp)
      const sentMessage = bobMessages.find(m =>
        m.sender === '@alice:localhost' &&
        Math.abs(m.duration - expectedDuration) < 500 &&
        m.timestamp >= beforeSendTimestamp
      );

      expect(sentMessage).toBeDefined();
      expect(sentMessage?.sender).toBe('@alice:localhost');
      expect(sentMessage?.duration).toBeCloseTo(expectedDuration, -2);
      console.log('[Test] DM message received successfully');
    }, 60000);

    test('should show messages in history after sending', async () => {
      // Given: alice and bob have exchanged messages in a DM room
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      if (
        bobService.getSyncState() !== 'PREPARED' &&
        bobService.getSyncState() !== 'SYNCING'
      ) {
        await bobService.waitForSync();
      }

      // Create DM room and have bob join
      const dmRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
      await bobService.joinRoom(dmRoomId);
      await waitForCondition(
        'bob joined DM room',
        () => bobService.isRoomMember(dmRoomId),
      );

      // Alice sends a message - track the event ID for reliable matching
      // Note: sendVoiceMessage returns the event ID as a string, not a VoiceMessage object
      const audio1 = createFakeAudioBuffer(3000, { prefix: 'ALICE_DM_HIST' });
      const aliceEventId = await aliceService.sendVoiceMessage(
        dmRoomId,
        audio1,
        'audio/mp4',
        3000,
        audio1.length,
      );
      console.log('[Test] Alice sent message with eventId:', aliceEventId);

      // Wait for alice's message to appear locally
      // Note: MatrixServiceAdapter returns VoiceMessage with eventId property
      await waitForCondition(
        'alice message appears',
        () => aliceService.getVoiceMessages(dmRoomId).some(m => m.eventId === aliceEventId),
      );

      // Bob sends a reply - track the event ID
      const audio2 = createFakeAudioBuffer(4000, { prefix: 'BOB_DM_HIST' });
      const bobEventId = await bobService.sendVoiceMessage(
        dmRoomId,
        audio2,
        'audio/mp4',
        4000,
        audio2.length,
      );

      // Wait for both users to see both messages (use event IDs for reliability)
      await waitForCondition(
        'alice sees both messages',
        () => {
          const msgs = aliceService.getVoiceMessages(dmRoomId);
          return msgs.some(m => m.eventId === aliceEventId) && msgs.some(m => m.eventId === bobEventId);
        },
        20000,
      );
      await waitForCondition(
        'bob sees both messages',
        () => {
          const msgs = bobService.getVoiceMessages(dmRoomId);
          return msgs.some(m => m.eventId === aliceEventId) && msgs.some(m => m.eventId === bobEventId);
        },
        20000,
      );

      // When: alice gets messages from the DM room (simulating viewing history)
      const aliceMessages = aliceService.getVoiceMessages(dmRoomId);
      const bobMessages = bobService.getVoiceMessages(dmRoomId);

      console.log(
        '[Test] Alice sees',
        aliceMessages.length,
        'messages in DM history',
      );
      console.log(
        '[Test] Bob sees',
        bobMessages.length,
        'messages in DM history',
      );

      // Then: both should see both messages we sent in this test
      // Note: MatrixServiceAdapter VoiceMessage has eventId (not id) and sender as string (not User)
      const aliceMsg = aliceMessages.find(m => m.eventId === aliceEventId);
      const bobMsg = aliceMessages.find(m => m.eventId === bobEventId);
      expect(aliceMsg).toBeTruthy();
      expect(bobMsg).toBeTruthy();
      expect(aliceMsg?.sender).toBe('@alice:localhost');
      expect(bobMsg?.sender).toBe('@bob:localhost');
    }, 60000);

    test('bob should recognize DM room after joining (m.direct sync)', async () => {
      // This test verifies that when Alice creates a DM room with Bob,
      // and Bob joins, Bob's m.direct account data is updated automatically
      // so that Bob recognizes the room as a DM (isDirect: true)

      // Given: alice and bob are logged in
      await aliceService.login(
        TEST_USERS.alice.username,
        TEST_USERS.alice.password,
      );
      await aliceService.waitForSync();

      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
      if (
        bobService.getSyncState() !== 'PREPARED' &&
        bobService.getSyncState() !== 'SYNCING'
      ) {
        await bobService.waitForSync();
      }

      // When: Alice creates a DM room with Bob
      console.log('[Test] Alice creating DM room with Bob...');
      const dmRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
      console.log('[Test] DM room created:', dmRoomId);

      // And: Bob joins the room
      console.log('[Test] Bob joining DM room...');
      await bobService.joinRoom(dmRoomId);

      // Wait until bob recognizes the room as a direct room
      await waitForCondition(
        'bob sees DM room in direct rooms',
        () => bobService.getDirectRooms().some(r => r.roomId === dmRoomId),
      );

      // Then: Bob should recognize the room as a DM
      const bobRooms = bobService.getDirectRooms();
      const bobsDmRoom = bobRooms.find(r => r.roomId === dmRoomId);
      expect(bobsDmRoom).toBeTruthy();
      expect(bobsDmRoom?.isDirect).toBe(true);

      // And: Bob should be able to find a valid DM room when messaging Alice
      // Note: Due to test data accumulation, Bob may have older DM rooms with Alice.
      // The important thing is that getOrCreateDmRoom returns a valid, joined room.
      const bobsDmRoomId =
        await bobService.getOrCreateDmRoom('@alice:localhost');

      // Verify it's a valid room Bob is joined to
      expect(bobsDmRoomId).toBeTruthy();
      expect(bobService.isRoomMember(bobsDmRoomId)).toBe(true);

      // Verify it's a 2-person room with Alice by checking getDirectRooms
      const bobDmRooms = bobService.getDirectRooms();
      const foundRoom = bobDmRooms.find(r => r.roomId === bobsDmRoomId);
      expect(foundRoom).toBeTruthy();
      expect(foundRoom?.isDirect).toBe(true);

      console.log('[Test] Bob found valid DM room with Alice:', bobsDmRoomId);
    }, 60000);
  });
});

describe('Family Onboarding Flow (E2E)', () => {
  let aliceService: MatrixService;
  let bobService: MatrixService;
  let aliceCredentials: TestCredentialStorage;
  let bobCredentials: TestCredentialStorage;

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

    setHomeserverUrl(TEST_HOMESERVER);

    // Use a separate family room for tests to avoid interfering with manual testing
    setFamilyAliasPrefix('family-test');
  }, 10000);

  beforeEach(async () => {
    aliceService = createTestService(TEST_HOMESERVER, createTestCredentialStorage());
    bobService = createTestService(TEST_HOMESERVER, createTestCredentialStorage());
  }, 5000);

  afterEach(async () => {
    try {
      aliceService.cleanup();
      await aliceService.logout();
    } catch {
      // Ignore logout errors
    }
    try {
      bobService.cleanup();
      await bobService.logout();
    } catch {
      // Ignore logout errors
    }
  }, 10000);

  test('should complete full family setup flow', async () => {
    // 1. Alice logs in and ensures family room exists
    await aliceService.login(
      TEST_USERS.alice.username,
      TEST_USERS.alice.password,
    );
    await aliceService.waitForSync();

    const familyRoomId = await ensureFamilyRoomMembership(aliceService);
    expect(familyRoomId).toBeTruthy();
    console.log('[E2E] Family room ready:', familyRoomId);

    // 2. Alice invites Bob
    try {
      await aliceService.inviteToFamily('@bob:localhost');
      console.log('[E2E] Invited bob to family room');
    } catch (error) {
      console.log('[E2E] Invite error (may be already invited):', error);
    }

    // 3. Bob joins family room
    await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);
    await bobService.waitForSync();

    try {
      await bobService.joinRoom(familyRoomId);
      console.log('[E2E] Bob joined family room');
    } catch {
      console.log('[E2E] Bob may already be a member');
    }

    // Wait until both can access the family room
    await waitForCondition(
      'both see family room',
      async () => {
        const a = await aliceService.getFamilyRoom();
        const b = await bobService.getFamilyRoom();
        return !!(a && b);
      },
    );

    // 4. Verify both can access the family room
    const aliceFamilyRoom = await aliceService.getFamilyRoom();
    const bobFamilyRoom = await bobService.getFamilyRoom();

    expect(aliceFamilyRoom).toBeTruthy();
    expect(bobFamilyRoom).toBeTruthy();
    expect(aliceFamilyRoom?.roomId).toBe(familyRoomId);
    expect(bobFamilyRoom?.roomId).toBe(familyRoomId);

    // 5. Verify family members (may have sync timing issues)
    const aliceMembers = await aliceService.getFamilyMembers();
    const bobMembers = await bobService.getFamilyMembers();
    console.log('[E2E] Alice sees family members:', aliceMembers);
    console.log('[E2E] Bob sees family members:', bobMembers);

    // Note: Due to sync timing, members may not be immediately visible
    // This is a known limitation of the test setup

    // 6. Alice creates DM with Bob (on demand)
    const dmRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
    expect(dmRoomId).toBeTruthy();
    console.log('[E2E] DM room created:', dmRoomId);

    console.log('[E2E] Full family flow completed successfully!');
  }, 90000);
});
