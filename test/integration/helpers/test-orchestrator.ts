/**
 * TestOrchestrator - High-level test scenario orchestration
 *
 * Manages multiple TestClients and provides helpers for common multi-client
 * testing scenarios like "alice sends, bob receives".
 */

import type { MessageFilter } from './test-client';
import { TestClient } from './test-client';
import type { VoiceMessage } from '../../../src/services/MatrixService';

export class TestOrchestrator {
  private clients = new Map<string, TestClient>();
  private rooms = new Map<string, string>();
  private homeserverUrl: string;

  constructor(homeserverUrl = 'http://localhost:8008') {
    this.homeserverUrl = homeserverUrl;
  }

  /**
   * Create and login a test client
   */
  async createClient(
    username: string,
    password: string,
  ): Promise<TestClient> {
    console.log(`[TestOrchestrator] Creating client for ${username}`);

    const client = new TestClient(username, password, this.homeserverUrl);
    await client.login();
    await client.waitForSync();

    this.clients.set(username, client);
    console.log(`[TestOrchestrator] Client ready: ${username}`);

    return client;
  }

  /**
   * Get an existing client by username
   */
  getClient(username: string): TestClient {
    const client = this.clients.get(username);
    if (!client) {
      throw new Error(`Client not found: ${username}`);
    }
    return client;
  }

  /**
   * Create a direct message room between users
   *
   * Includes retry logic for Conduit's eventual consistency.
   */
  async createRoom(
    owner: string,
    ...participants: string[]
  ): Promise<string> {
    console.log(
      `[TestOrchestrator] Creating room: ${owner} with ${participants.join(', ')}`,
    );

    const ownerClient = this.getClient(owner);

    // Create the room with invites
    const result = await ownerClient.createRoom({
      is_direct: true,
      invite: participants.map(u => `@${u}:localhost`),
      preset: 'trusted_private_chat' as any,
    });

    const roomId = result.room_id;

    // Wait for owner to see the room first
    await ownerClient.waitForRoom(roomId, 10000);

    // Wait for all participants to see the room and join if needed
    const joinPromises = participants.map(async participant => {
      const client = this.getClient(participant);

      // Wait for participant to see the room
      await client.waitForRoom(roomId, 15000);

      // Try to join the room (may already be auto-joined)
      let joined = false;
      let attempts = 0;
      const maxAttempts = 3;

      while (!joined && attempts < maxAttempts) {
        try {
          attempts++;
          await client.joinRoom(roomId);
          joined = true;
          console.log(`[TestOrchestrator] ${participant} joined room`);
        } catch (error) {
          // Check if already in the room
          const room = client.getRooms().find(r => r.roomId === roomId);
          if (room) {
            console.log(
              `[TestOrchestrator] ${participant} already in room`,
            );
            joined = true;
          } else if (attempts < maxAttempts) {
            console.log(
              `[TestOrchestrator] ${participant} join attempt ${attempts} failed, retrying...`,
            );
            // Wait a bit before retrying
            await new Promise(resolve => setTimeout(resolve, 1000));
          } else {
            console.warn(
              `[TestOrchestrator] ${participant} failed to join after ${maxAttempts} attempts:`,
              error,
            );
          }
        }
      }
    });

    await Promise.all(joinPromises);

    // Store room reference
    const roomKey = [owner, ...participants].sort().join('-');
    this.rooms.set(roomKey, roomId);

    console.log(`[TestOrchestrator] Room ready: ${roomId}`);
    return roomId;
  }

  /**
   * Get a room ID by participant names
   */
  getRoomId(...participants: string[]): string | undefined {
    const key = participants.sort().join('-');
    return this.rooms.get(key);
  }

  /**
   * Send a voice message from one user to a room
   */
  async sendVoiceMessage(
    sender: string,
    roomId: string,
    audioBuffer: Buffer,
    mimeType = 'audio/mp4',
    duration = 5000,
  ): Promise<string> {
    console.log(
      `[TestOrchestrator] ${sender} sending voice message to ${roomId}`,
    );

    const client = this.getClient(sender);
    return await client.sendVoiceMessage(roomId, audioBuffer, mimeType, duration);
  }

  /**
   * Verify that a message was received by a specific user
   */
  async verifyMessageReceived(
    receiver: string,
    roomId: string,
    filter: MessageFilter,
    timeoutMs = 10000,
  ): Promise<VoiceMessage> {
    console.log(
      `[TestOrchestrator] Verifying ${receiver} receives message in ${roomId}`,
    );

    const client = this.getClient(receiver);
    return await client.waitForMessage(roomId, filter, timeoutMs);
  }

  /**
   * Get all voice messages in a room for a specific client
   */
  getVoiceMessages(username: string, roomId: string): VoiceMessage[] {
    const client = this.getClient(username);
    return client.getVoiceMessages(roomId);
  }

  /**
   * Complete scenario: create room, send message, verify receipt
   */
  async sendAndVerifyVoiceMessage(
    sender: string,
    receiver: string,
    audioBuffer: Buffer,
    duration = 5000,
  ): Promise<{
    roomId: string;
    eventId: string;
    receivedMessage: VoiceMessage;
  }> {
    // Create or get room
    let roomId = this.getRoomId(sender, receiver);
    if (!roomId) {
      roomId = await this.createRoom(sender, receiver);
    }

    // Send message
    const eventId = await this.sendVoiceMessage(
      sender,
      roomId,
      audioBuffer,
      'audio/mp4',
      duration,
    );

    // Verify receipt
    const receivedMessage = await this.verifyMessageReceived(
      receiver,
      roomId,
      { eventId },
      10000,
    );

    return { roomId, eventId, receivedMessage };
  }

  /**
   * Cleanup all clients
   */
  async cleanup(): Promise<void> {
    console.log('[TestOrchestrator] Cleaning up all clients...');

    // Stop all clients (don't wait for logout to avoid hanging tests)
    for (const [username, client] of this.clients.entries()) {
      try {
        client.stop();
        console.log(`[TestOrchestrator] Stopped client: ${username}`);
      } catch (error) {
        console.error(
          `[TestOrchestrator] Error stopping ${username}:`,
          error,
        );
      }
    }

    this.clients.clear();
    this.rooms.clear();

    console.log('[TestOrchestrator] Cleanup complete');
  }
}
