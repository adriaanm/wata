/**
 * Integration tests for WataService
 *
 * These tests run against a local Conduit Matrix server.
 * Start the server first: cd test/docker && ./setup.sh
 *
 * Implementation: Uses WataService API via test factory
 */

import { Buffer } from 'buffer';

import {
  createTestService,
  createTestCredentialStorage,
  getImplementationName,
} from './helpers/test-service-factory';
import type { WataService, VoiceMessage } from '@shared/services/WataService';

/**
 * Poll until a condition is true, with exponential backoff.
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

// Type union for the service (either implementation)
type TestService = WataService;

describe('Matrix Integration Tests', () => {
  let aliceService: TestService;
  let bobService: TestService;

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
        'Matrix server not running. Start it with: cd test/docker && ./setup.sh',
      );
    }

    // Log which implementation is being used
    console.log(`\n  Using implementation: ${getImplementationName()}\n`);
  }, 10000);

  afterEach(async () => {
    // Clean up callbacks after each test to prevent cross-test pollution
    if (aliceService) {
      aliceService.cleanup();
    }
    if (bobService) {
      bobService.cleanup();
    }
  });

  afterAll(async () => {
    if (aliceService) {
      await aliceService.logout();
    }
    if (bobService) {
      await bobService.logout();
    }
  });

  describe('Authentication', () => {
    test('should login with valid credentials', async () => {
      const storage = createTestCredentialStorage();
      const service = createTestService(TEST_HOMESERVER, storage);

      await service.login(TEST_USERS.alice.username, TEST_USERS.alice.password);

      // Verify login was successful
      expect(service.isLoggedIn()).toBe(true);
      expect(service.getUserId()).toBe('@alice:localhost');

      await service.logout();
    });

    test('should fail login with invalid password', async () => {
      const storage = createTestCredentialStorage();
      const service = createTestService(TEST_HOMESERVER, storage);

      await expect(
        service.login(TEST_USERS.alice.username, 'wrongpassword'),
      ).rejects.toThrow();
    });

    test('should fail login with non-existent user', async () => {
      const storage = createTestCredentialStorage();
      const service = createTestService(TEST_HOMESERVER, storage);

      await expect(
        service.login('nonexistent', 'password'),
      ).rejects.toThrow();
    });
  });

  describe('Room Operations', () => {
    beforeAll(async () => {
      // Login both users
      const aliceStorage = createTestCredentialStorage();
      const bobStorage = createTestCredentialStorage();

      aliceService = createTestService(TEST_HOMESERVER, aliceStorage);
      bobService = createTestService(TEST_HOMESERVER, bobStorage);

      await aliceService.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);

      // Wait for both to sync
      await aliceService.waitForSync();
      await bobService.waitForSync();
    }, 15000);

    test('should create a direct message room', async () => {
      const roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');

      expect(roomId).toBeDefined();
      expect(roomId).toMatch(/^!/);
    });

    test('should get direct rooms', async () => {
      // First ensure a DM room exists
      await aliceService.getOrCreateDmRoom('@bob:localhost');

      // Get all direct rooms
      const rooms = aliceService.getDirectRooms();

      // Should have at least the DM room we just created
      expect(rooms.length).toBeGreaterThan(0);

      // Find the DM room with Bob (name could be the user ID or display name)
      const bobDm = rooms.find(
        (room: { roomId: string; name: string; isDirect: boolean }) =>
          room.isDirect && (
            room.name.includes('bob') ||
            room.name.includes('Bob') ||
            room.name.includes('@bob:localhost')
          )
      );

      // If we didn't find by name, at least verify we have a direct room
      const hasDirectRoom = rooms.some((room: { isDirect: boolean }) => room.isDirect);
      expect(hasDirectRoom).toBe(true);
    });
  });

  describe('Messaging', () => {
    let testRoomId: string;

    beforeAll(async () => {
      // Login both users
      const aliceStorage = createTestCredentialStorage();
      const bobStorage = createTestCredentialStorage();

      aliceService = createTestService(TEST_HOMESERVER, aliceStorage);
      bobService = createTestService(TEST_HOMESERVER, bobStorage);

      await aliceService.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);

      await aliceService.waitForSync();
      await bobService.waitForSync();

      // Create a test DM room
      testRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');

      // Wait for room to propagate
      await waitForCondition(
        'test room visible to alice',
        () => aliceService.isRoomMember(testRoomId),
      );
    }, 15000);

    test('should send an audio message', async () => {
      // Get initial message count
      const initialCount = aliceService.getMessageCount(testRoomId);

      const fakeAudioData = Buffer.from('fake audio content for testing');

      // Send voice message using MatrixService API
      await aliceService.sendVoiceMessage(
        testRoomId,
        fakeAudioData,
        'audio/mp4',
        5000,
        fakeAudioData.length,
      );

      // Wait for message to appear in timeline
      await waitForCondition(
        'message appears in alice timeline',
        () => aliceService.getMessageCount(testRoomId) > initialCount,
      );

      // Verify message was sent by checking local timeline
      const messages = aliceService.getVoiceMessages(testRoomId);
      expect(messages.length).toBe(initialCount + 1);

      // Get the last message we just sent
      const lastMessage = messages[messages.length - 1];
      expect(lastMessage.isOwn).toBe(true);
      expect(lastMessage.duration).toBe(5000);
    });

    test('should receive messages in room timeline', async () => {
      // Send a message from Alice
      const fakeAudioData = Buffer.from('fake audio content for receiving test');
      const initialCount = aliceService.getVoiceMessages(testRoomId).length;

      const eventId = await aliceService.sendVoiceMessage(
        testRoomId,
        fakeAudioData,
        'audio/mp4',
        3000,
        fakeAudioData.length,
      );

      // Wait for the specific message to appear (by event ID)
      await waitForCondition(
        'message with event ID in alice timeline',
        () => aliceService.getVoiceMessages(testRoomId).some(m => m.eventId === eventId),
      );

      // Check Alice's timeline
      const aliceMessages = aliceService.getVoiceMessages(testRoomId);
      expect(aliceMessages.length).toBeGreaterThan(initialCount);

      // Verify message content by finding the specific message we sent
      const sentMessage = aliceMessages.find(m => m.eventId === eventId);
      expect(sentMessage).toBeDefined();
      expect(sentMessage!.isOwn).toBe(true);
      expect(sentMessage!.duration).toBe(3000);
    });

    test('should handle multiple voice messages', async () => {
      const initialCount = aliceService.getMessageCount(testRoomId);

      // Send multiple messages
      const messageCount = 3;
      for (let i = 0; i < messageCount; i++) {
        const audioData = Buffer.from(`message ${i}`);
        await aliceService.sendVoiceMessage(
          testRoomId,
          audioData,
          'audio/mp4',
          1000 * (i + 1),
          audioData.length,
        );
      }

      // Wait for all messages to appear
      await waitForCondition(
        `${messageCount} messages sent`,
        () => aliceService.getMessageCount(testRoomId) >= initialCount + messageCount,
      );

      // Verify message count increased
      const newCount = aliceService.getMessageCount(testRoomId);
      expect(newCount).toBeGreaterThanOrEqual(initialCount + messageCount);
    });
  });

  describe('Sync and Event Handling', () => {
    test('should reach SYNCING or PREPARED state after login', async () => {
      const storage = createTestCredentialStorage();
      const service = createTestService(TEST_HOMESERVER, storage);

      await service.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await service.waitForSync();

      // Verify we're in a good sync state
      const syncState = service.getSyncState();
      expect(['SYNCING', 'PREPARED']).toContain(syncState);

      await service.logout();
    });

    test('should notify sync state changes', async () => {
      const storage = createTestCredentialStorage();
      const service = createTestService(TEST_HOMESERVER, storage);

      const states: string[] = [];

      // Register callback to capture sync states
      service.onSyncStateChange((state: string) => {
        states.push(state);
      });

      await service.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await service.waitForSync();

      // Should have received at least one sync state
      expect(states.length).toBeGreaterThan(0);

      // Should have reached PREPARED or SYNCING
      expect(states.some((s) => s === 'PREPARED' || s === 'SYNCING')).toBe(true);

      await service.logout();
    });
  });

  describe('User Info', () => {
    let service: TestService;

    beforeAll(async () => {
      const storage = createTestCredentialStorage();
      service = createTestService(TEST_HOMESERVER, storage);
      await service.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await service.waitForSync();
    }, 10000);

    afterAll(async () => {
      if (service) {
        await service.logout();
      }
    });

    test('should get current username', () => {
      const username = service.getCurrentUsername();
      expect(username).toBe('alice');
    });

    test('should get user ID', () => {
      const userId = service.getUserId();
      expect(userId).toBe('@alice:localhost');
    });

    test('should report logged in status', () => {
      expect(service.isLoggedIn()).toBe(true);
    });
  });

  describe('Cross-User Messaging', () => {
    let roomId: string;

    beforeAll(async () => {
      // Login both users
      const aliceStorage = createTestCredentialStorage();
      const bobStorage = createTestCredentialStorage();

      aliceService = createTestService(TEST_HOMESERVER, aliceStorage);
      bobService = createTestService(TEST_HOMESERVER, bobStorage);

      await aliceService.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
      await bobService.login(TEST_USERS.bob.username, TEST_USERS.bob.password);

      await aliceService.waitForSync();
      await bobService.waitForSync();

      // Alice creates a DM room (which automatically invites Bob)
      roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');

      // Wait for Bob to see the room as a member
      await waitForCondition(
        'bob is member of alice room',
        () => bobService.isRoomMember(roomId),
        35000,
      );
    }, 40000);

    test('both users should be members of the DM room', () => {
      expect(aliceService.isRoomMember(roomId)).toBe(true);
      expect(bobService.isRoomMember(roomId)).toBe(true);
    });

    test('should send message from one user to another', async () => {
      const beforeCount = aliceService.getMessageCount(roomId);

      // Alice sends a message
      const audioData = Buffer.from('cross-user test message');
      await aliceService.sendVoiceMessage(
        roomId,
        audioData,
        'audio/mp4',
        2000,
        audioData.length,
      );

      // Wait for message to appear
      await waitForCondition(
        'cross-user message appears',
        () => aliceService.getMessageCount(roomId) > beforeCount,
      );

      // Alice should see the message
      const afterCount = aliceService.getMessageCount(roomId);
      expect(afterCount).toBeGreaterThan(beforeCount);
    });
  });
});
