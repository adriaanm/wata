/**
 * TestOrchestrator - High-level test scenario orchestration
 *
 * Manages multiple TestClients and provides helpers for common multi-client
 * testing scenarios like "alice sends, bob receives".
 */

import type { VoiceMessage } from '../../../src/shared/services/MatrixService';

import type { MessageFilter } from './test-client';
import { TestClient } from './test-client';

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
  async createClient(username: string, password: string): Promise<TestClient> {
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
  async createRoom(owner: string, ...participants: string[]): Promise<string> {
    console.log(
      `[TestOrchestrator] Creating room: ${owner} with ${participants.join(', ')}`,
    );

    const ownerClient = this.getClient(owner);

    // Add a unique name to prevent room reuse across test runs
    // The DM room service may find existing rooms, so we make each test's room unique
    const uniqueName = `test-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

    // Create the room with invites
    const result = await ownerClient.createRoom({
      is_direct: true,
      invite: participants.map(u => `@${u}:localhost`),
      preset: 'trusted_private_chat' as any,
      name: uniqueName, // Unique name prevents reuse
    });

    const roomId = result.room_id;

    // Wait for owner to see the room first
    await ownerClient.waitForRoom(roomId, 15000);

    // Wait for all participants to join the room
    const joinPromises = participants.map(async participant => {
      const client = this.getClient(participant);

      // Try to join the room with retries
      // Note: We skip waitForRoom here because participants can't see invited rooms
      // until after joining, creating a chicken-and-egg problem
      let joined = false;
      let attempts = 0;
      const maxAttempts = 5; // Increased from 3 for better reliability

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
            console.log(`[TestOrchestrator] ${participant} already in room`);
            joined = true;
          } else if (attempts < maxAttempts) {
            console.log(
              `[TestOrchestrator] ${participant} join attempt ${attempts} failed, retrying in ${1000 * attempts}ms...`,
            );
            // Exponential backoff: 1s, 2s, 3s, 4s
            await new Promise(resolve => setTimeout(resolve, 1000 * attempts));
          } else {
            console.warn(
              `[TestOrchestrator] ${participant} failed to join after ${maxAttempts} attempts:`,
              error,
            );
          }
        }
      }

      // After joining, wait for room to appear in the participant's room list
      if (joined) {
        await client.waitForRoom(roomId, 15000);
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
    return await client.sendVoiceMessage(
      roomId,
      audioBuffer,
      mimeType,
      duration,
    );
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
   * Paginate timeline to fetch more events from server
   */
  async paginateTimeline(
    username: string,
    roomId: string,
    limit = 50,
  ): Promise<void> {
    const client = this.getClient(username);
    await client.paginateTimeline(roomId, limit);
  }

  /**
   * Get all voice messages with pagination to ensure all are fetched
   */
  async getAllVoiceMessages(
    username: string,
    roomId: string,
    limit = 100,
  ): Promise<VoiceMessage[]> {
    const client = this.getClient(username);
    return await client.getAllVoiceMessages(roomId, limit);
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
    // Always create a new room to avoid issues with accumulated server state
    // where clients don't have access to rooms from previous test sessions
    const roomId = await this.createRoom(sender, receiver);

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
      15000, // Increased timeout for accumulated state scenarios
    );

    return { roomId, eventId, receivedMessage };
  }

  /**
   * Wait for a condition to become true via a client
   */
  async waitForCondition(
    username: string,
    description: string,
    condition: () => boolean,
    timeoutMs = 15000,
  ): Promise<void> {
    const client = this.getClient(username);
    return client.waitForCondition(description, condition, timeoutMs);
  }

  /**
   * Wait for at least N voice messages in a room for a specific client
   */
  async waitForMessageCount(
    username: string,
    roomId: string,
    minCount: number,
    timeoutMs = 20000,
  ): Promise<VoiceMessage[]> {
    const client = this.getClient(username);
    return client.waitForMessageCount(roomId, minCount, timeoutMs);
  }

  /**
   * Wait for all specified event IDs to be received by a user
   *
   * @param username - The user who should receive the messages
   * @param roomId - The room to check
   * @param eventIds - Set of event IDs to wait for
   * @param timeoutMs - Maximum time to wait in milliseconds
   */
  async waitForEventIds(
    username: string,
    roomId: string,
    eventIds: Set<string>,
    timeoutMs = 30000,
  ): Promise<void> {
    const startTime = Date.now();
    const client = this.getClient(username);
    const initialCount = eventIds.size;
    const expectedIds = Array.from(eventIds); // Save original IDs for error message
    console.log(
      `[TestOrchestrator] Waiting for ${initialCount} events for ${username} in ${roomId}`,
    );

    // Paginate once at the start to ensure we're not missing messages
    await this.paginateTimeline(username, roomId, 100);

    let checkCount = 0;
    while (Date.now() - startTime < timeoutMs && eventIds.size > 0) {
      checkCount++;

      // Use getAllVoiceMessages to ensure we paginate
      const messages = await this.getAllVoiceMessages(username, roomId, 100);
      const foundIds = new Set<string>();

      for (const msg of messages) {
        if (eventIds.has(msg.eventId)) {
          foundIds.add(msg.eventId);
        }
      }

      // Remove found IDs from the expected set
      for (const id of foundIds) {
        eventIds.delete(id);
      }

      if (foundIds.size > 0) {
        console.log(
          `[TestOrchestrator] ${username} received ${foundIds.size} more events, ${eventIds.size} remaining`,
        );
      }

      if (eventIds.size > 0) {
        // Use adaptive polling: start fast, slow down gradually
        // This helps with both quick responses and not overwhelming the system
        const pollDelay = Math.min(100 + checkCount * 10, 500);
        await new Promise(resolve => setTimeout(resolve, pollDelay));

        // Re-paginate every 10 checks to ensure we're not missing server updates
        if (checkCount % 10 === 0) {
          await this.paginateTimeline(username, roomId, 100);
        }
      }
    }

    if (eventIds.size > 0) {
      const messages = await this.getAllVoiceMessages(username, roomId, 100);
      const actualEventIds = new Set(messages.map(m => m.eventId));
      const missingIds = Array.from(eventIds);
      throw new Error(
        `Timed out waiting for ${eventIds.size}/${initialCount} events after ${timeoutMs}ms. ` +
          `Missing IDs: ${missingIds.join(', ')}. ` +
          `Expected IDs: ${expectedIds.join(', ')}. ` +
          `Client has ${messages.length} messages with ${actualEventIds.size} unique IDs. ` +
          `Sample IDs in timeline: ${Array.from(actualEventIds).slice(0, 5).join(', ')}...`,
      );
    }

    console.log(
      `[TestOrchestrator] ${username} received all ${initialCount} events after ${checkCount} checks`,
    );
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
        console.error(`[TestOrchestrator] Error stopping ${username}:`, error);
      }
    }

    this.clients.clear();
    this.rooms.clear();

    console.log('[TestOrchestrator] Cleanup complete');
  }
}
