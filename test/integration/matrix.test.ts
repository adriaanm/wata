/**
 * Integration tests for MatrixService
 *
 * These tests run against a local Conduit Matrix server.
 * Start the server first: cd test/docker && ./setup.sh
 */

import * as matrix from 'matrix-js-sdk';

const TEST_HOMESERVER = 'http://localhost:8008';
const TEST_USERS = {
  alice: {username: 'alice', password: 'testpass123'},
  bob: {username: 'bob', password: 'testpass123'},
};

describe('Matrix Integration Tests', () => {
  let aliceClient: matrix.MatrixClient;
  let bobClient: matrix.MatrixClient;

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
  }, 10000);

  afterAll(async () => {
    if (aliceClient) {
      aliceClient.stopClient();
    }
    if (bobClient) {
      bobClient.stopClient();
    }
  });

  describe('Authentication', () => {
    test('should login with valid credentials', async () => {
      const client = matrix.createClient({
        baseUrl: TEST_HOMESERVER,
      });

      const response = await client.login('m.login.password', {
        identifier: {type: 'm.id.user', user: TEST_USERS.alice.username},
        password: TEST_USERS.alice.password,
      });

      expect(response.user_id).toBe('@alice:localhost');
      expect(response.access_token).toBeDefined();
      expect(response.device_id).toBeDefined();
    });

    test('should fail login with invalid password', async () => {
      const client = matrix.createClient({
        baseUrl: TEST_HOMESERVER,
      });

      await expect(
        client.login('m.login.password', {
          identifier: {type: 'm.id.user', user: TEST_USERS.alice.username},
          password: 'wrongpassword',
        }),
      ).rejects.toThrow();
    });

    test('should fail login with non-existent user', async () => {
      const client = matrix.createClient({
        baseUrl: TEST_HOMESERVER,
      });

      await expect(
        client.login('m.login.password', {
          identifier: {type: 'm.id.user', user: 'nonexistent'},
          password: 'password',
        }),
      ).rejects.toThrow();
    });
  });

  describe('Room Operations', () => {
    beforeAll(async () => {
      // Login both users
      const aliceLoginClient = matrix.createClient({baseUrl: TEST_HOMESERVER});
      const aliceResponse = await aliceLoginClient.login('m.login.password', {
        identifier: {type: 'm.id.user', user: TEST_USERS.alice.username},
        password: TEST_USERS.alice.password,
      });

      aliceClient = matrix.createClient({
        baseUrl: TEST_HOMESERVER,
        accessToken: aliceResponse.access_token,
        userId: aliceResponse.user_id,
        deviceId: aliceResponse.device_id,
      });

      const bobLoginClient = matrix.createClient({baseUrl: TEST_HOMESERVER});
      const bobResponse = await bobLoginClient.login('m.login.password', {
        identifier: {type: 'm.id.user', user: TEST_USERS.bob.username},
        password: TEST_USERS.bob.password,
      });

      bobClient = matrix.createClient({
        baseUrl: TEST_HOMESERVER,
        accessToken: bobResponse.access_token,
        userId: bobResponse.user_id,
        deviceId: bobResponse.device_id,
      });
    }, 15000);

    test('should create a direct message room', async () => {
      const room = await aliceClient.createRoom({
        is_direct: true,
        invite: ['@bob:localhost'],
        preset: 'trusted_private_chat' as matrix.Preset,
      });

      expect(room.room_id).toBeDefined();
      expect(room.room_id).toMatch(/^!/);
    });

    test('should sync and receive rooms', async () => {
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Sync timeout')), 10000);

        aliceClient.once(matrix.ClientEvent.Sync, (state) => {
          clearTimeout(timeout);
          if (state === 'PREPARED' || state === 'SYNCING') {
            resolve();
          }
        });

        aliceClient.startClient({initialSyncLimit: 10});
      });

      const rooms = aliceClient.getRooms();
      expect(rooms.length).toBeGreaterThan(0);
    }, 15000);
  });

  describe('Messaging', () => {
    let testRoomId: string;

    beforeAll(async () => {
      // Create a test room
      const room = await aliceClient.createRoom({
        is_direct: true,
        invite: ['@bob:localhost'],
        preset: 'trusted_private_chat' as matrix.Preset,
      });
      testRoomId = room.room_id;

      // Wait for bob to receive the invite and join
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Start bob's client and accept invite
      await new Promise<void>((resolve) => {
        bobClient.once(matrix.ClientEvent.Sync, () => resolve());
        bobClient.startClient({initialSyncLimit: 10});
      });

      // Bob joins the room
      try {
        await bobClient.joinRoom(testRoomId);
      } catch {
        // May already be joined or auto-accepted
      }
    }, 20000);

    test('should send a text message', async () => {
      const result = await aliceClient.sendMessage(testRoomId, {
        msgtype: matrix.MsgType.Text,
        body: 'Hello from integration test!',
      });

      expect(result.event_id).toBeDefined();
      expect(result.event_id).toMatch(/^\$/);
    });

    test('should send an audio message', async () => {
      // Simulate uploading audio (in real app, this would be actual audio data)
      const fakeAudioData = Buffer.from('fake audio content for testing');

      const uploadResponse = await aliceClient.uploadContent(fakeAudioData, {
        type: 'audio/mp4',
        name: 'test-voice.m4a',
      });

      expect(uploadResponse.content_uri).toBeDefined();
      expect(uploadResponse.content_uri).toMatch(/^mxc:\/\//);

      // Send the audio message
      const result = await aliceClient.sendMessage(testRoomId, {
        msgtype: matrix.MsgType.Audio,
        body: 'Voice message',
        url: uploadResponse.content_uri,
        info: {
          mimetype: 'audio/mp4',
          duration: 5000,
          size: fakeAudioData.length,
        },
      });

      expect(result.event_id).toBeDefined();
    });

    test('should receive messages in room timeline', async () => {
      // Wait for sync to catch up
      await new Promise(resolve => setTimeout(resolve, 2000));

      const room = aliceClient.getRoom(testRoomId);
      expect(room).toBeDefined();

      const timeline = room!.timeline;
      const messages = timeline.filter(
        event => event.getType() === 'm.room.message',
      );

      expect(messages.length).toBeGreaterThan(0);

      // Check we have both text and audio messages
      const hasText = messages.some(
        m => m.getContent().msgtype === matrix.MsgType.Text,
      );
      const hasAudio = messages.some(
        m => m.getContent().msgtype === matrix.MsgType.Audio,
      );

      expect(hasText).toBe(true);
      expect(hasAudio).toBe(true);
    });
  });
});
