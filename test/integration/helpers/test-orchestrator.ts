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
   * Create a direct message room between two users using production code path
   *
   * Uses WataClient's DMRoomService to ensure proper DM registration with m.direct.
   * Only supports 1:1 DMs (one participant).
   */
  async createRoom(owner: string, ...participants: string[]): Promise<string> {
    if (participants.length !== 1) {
      throw new Error('createRoom() only supports 1:1 DMs. Use exactly one participant.');
    }

    const participant = participants[0];
    console.log(
      `[TestOrchestrator] Creating DM room: ${owner} <-> ${participant}`,
    );

    const ownerClient = this.getClient(owner);
    const participantClient = this.getClient(participant);

    // Use WataClient's DMRoomService.ensureDMRoom() (via getOrCreateDmRoom)
    // This properly registers the room with m.direct account data
    const participantUserId = `@${participant}:localhost`;
    const roomId = await ownerClient.createDMRoom(participantUserId);

    console.log(`[TestOrchestrator] DM room created: ${roomId}`);

    // Wait for participant to receive invite and auto-join
    // (Auto-join happens via WataClient's auto-join flow)
    await participantClient.waitForRoom(roomId, 15000);
    await participantClient.waitForDirectRoom(roomId, 15000);

    console.log(`[TestOrchestrator] ${participant} has joined and classified DM`);

    // Store room reference
    const roomKey = [owner, participant].sort().join('-');
    this.rooms.set(roomKey, roomId);

    console.log(`[TestOrchestrator] DM room ready: ${roomId}`);
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
