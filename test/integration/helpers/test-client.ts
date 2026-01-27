/**
 * TestClient - High-level test helper for Matrix operations
 *
 * Wraps MatrixService with test-friendly utilities for waiting on async operations,
 * creating rooms, sending/receiving messages, and managing test state.
 *
 * IMPORTANT: This uses the same MatrixService as production code to ensure
 * tests validate the actual code path used by apps.
 */

import type {
  CredentialStorage,
  MatrixRoom,
  VoiceMessage as BaseVoiceMessage,
} from '@shared/services';
import { setHomeserverUrl } from '@shared/services/MatrixService';

import { createTestService, createTestCredentialStorage } from './test-service-factory';

export interface MessageFilter {
  sender?: string;
  eventId?: string;
  minDuration?: number;
  maxDuration?: number;
}

// Extended VoiceMessage interface for tests that includes MXC URL
export interface VoiceMessage extends BaseVoiceMessage {
  mxcUrl?: string;
}

// Type union for the service (could be MatrixService or MatrixServiceAdapter)
type MatrixServiceLike = ReturnType<typeof createTestService>;

/**
 * TestClient wraps MatrixService for testing
 *
 * All operations go through the production MatrixService, ensuring tests
 * validate the actual code path used by apps.
 */
export class TestClient {
  private service: MatrixServiceLike | null = null;
  private username: string;
  private password: string;
  private homeserverUrl: string;
  private credentialStorage: CredentialStorage;

  constructor(username: string, password: string, homeserverUrl: string) {
    this.username = username;
    this.password = password;
    this.homeserverUrl = homeserverUrl;
    this.credentialStorage = createTestCredentialStorage();
  }

  /**
   * Login and start syncing
   */
  async login(): Promise<void> {
    console.log(`[TestClient:${this.username}] Logging in...`);

    // Set homeserver URL for this test
    setHomeserverUrl(this.homeserverUrl);

    // Create service using the production factory
    this.service = createTestService(this.homeserverUrl, this.credentialStorage);

    // Login through MatrixService (same as production)
    await this.service.login(this.username, this.password);

    console.log(`[TestClient:${this.username}] Login successful`);
  }

  /**
   * Wait for initial sync to complete
   */
  async waitForSync(timeoutMs = 30000): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for sync...`);

    // Use MatrixService's waitForSync method
    await this.service.waitForSync(timeoutMs);

    const rooms = this.service.getDirectRooms();
    console.log(
      `[TestClient:${this.username}] Sync complete (${rooms.length} rooms)`,
    );
  }

  /**
   * Wait for a specific room to appear in the client's room list
   *
   * Uses polling with retries to handle Conduit's eventual consistency.
   */
  async waitForRoom(roomId: string, timeoutMs = 15000): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for room ${roomId}...`);

    const startTime = Date.now();
    const pollInterval = 100;

    while (Date.now() - startTime < timeoutMs) {
      const rooms = this.service.getDirectRooms();
      if (rooms.some(r => r.roomId === roomId)) {
        console.log(`[TestClient:${this.username}] Room found`);
        return;
      }
      await new Promise(resolve => setTimeout(resolve, pollInterval));
    }

    throw new Error(`Room ${roomId} not found after ${timeoutMs}ms`);
  }

  /**
   * Wait for a voice message matching the filter
   *
   * Checks existing messages first, then waits for new ones.
   */
  async waitForMessage(
    roomId: string,
    filter: MessageFilter,
    timeoutMs = 20000,
  ): Promise<VoiceMessage> {
    if (!this.service) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Waiting for message in room ${roomId}...`,
      filter,
    );

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        unsubscribe();
        reject(
          new Error(
            `Message not received in room ${roomId} after ${timeoutMs}ms`,
          ),
        );
      }, timeoutMs);

      const resolveWithMessage = (msg: VoiceMessage) => {
        clearTimeout(timeout);
        unsubscribe();
        console.log(`[TestClient:${this.username}] Message received`);
        resolve(msg);
      };

      // Check existing messages first
      const existing = this.getVoiceMessages(roomId).find(msg =>
        this.matchesFilter(msg, filter),
      );
      if (existing) {
        resolveWithMessage(existing);
        return;
      }

      // Listen for new messages
      const unsubscribe = this.service.onNewVoiceMessage(
        (msgRoomId: string, message: VoiceMessage) => {
          if (msgRoomId === roomId && this.matchesFilter(message, filter)) {
            resolveWithMessage(message);
          }
        },
      );
    });
  }

  /**
   * Create a room and wait for it to be ready
   */
  async createRoom(options: {
    is_direct?: boolean;
    invite?: string[];
    preset?: string;
    name?: string;
    room_alias_name?: string;
    visibility?: string;
    initial_state?: Array<{ type: string; state_key: string; content: any }>;
  }): Promise<{ room_id: string }> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Creating room...`);

    // For DM rooms, use the production getOrCreateDmRoom
    if (options.is_direct && options.invite && options.invite.length > 0) {
      const otherUserId = options.invite[0];
      const roomId = await this.service.getOrCreateDmRoom(otherUserId);
      console.log(`[TestClient:${this.username}] DM room created: ${roomId}`);

      // Wait for room to appear
      await this.waitForRoom(roomId, 5000);

      return { room_id: roomId };
    }

    // For other room types, we'd need to add createRoom to MatrixService
    // For now, throw an error since tests should use DM rooms
    throw new Error(
      'Non-DM room creation not yet supported - tests should use DM rooms',
    );
  }

  /**
   * Join a room
   */
  async joinRoom(roomId: string): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Joining room ${roomId}...`);
    await this.service.joinRoom(roomId);
    await this.waitForRoom(roomId, 5000);
    console.log(`[TestClient:${this.username}] Room joined`);
  }

  /**
   * Send a voice message
   */
  async sendVoiceMessage(
    roomId: string,
    audioBuffer: Buffer,
    mimeType = 'audio/mp4',
    duration = 5000,
  ): Promise<string> {
    if (!this.service) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Sending voice message to room ${roomId}...`,
    );

    // Track message count before sending
    const beforeCount = this.service.getMessageCount(roomId);

    // Send through MatrixService
    await this.service.sendVoiceMessage(
      roomId,
      audioBuffer,
      mimeType,
      duration,
      audioBuffer.length,
    );

    // Wait for the message to appear in timeline
    // The eventId is the hash we generate for tracking
    const startTime = Date.now();
    const timeoutMs = 10000;

    while (Date.now() - startTime < timeoutMs) {
      const messages = this.service.getVoiceMessages(roomId);
      if (messages.length > beforeCount) {
        const newMessage = messages[messages.length - 1];
        console.log(
          `[TestClient:${this.username}] Voice message sent: ${newMessage.eventId}`,
        );
        return newMessage.eventId;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }

    throw new Error('Voice message not found in timeline after sending');
  }

  /**
   * Get all voice messages from a room
   */
  getVoiceMessages(roomId: string): VoiceMessage[] {
    if (!this.service) return [];

    return this.service.getVoiceMessages(roomId);
  }

  /**
   * Paginate room timeline to fetch more events from server
   * Useful for stress tests where many messages were sent rapidly
   */
  async paginateTimeline(roomId: string, limit = 50): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Paginating timeline for room ${roomId} (limit: ${limit})...`,
    );

    // MatrixService doesn't expose pagination yet
    // For now, just wait a bit for any pending messages to arrive
    await new Promise(resolve => setTimeout(resolve, 500));

    console.log(
      `[TestClient:${this.username}] Pagination complete, timeline has ${this.service.getMessageCount(roomId)} events`,
    );
  }

  /**
   * Get all voice messages with pagination to ensure we fetch all from server
   */
  async getAllVoiceMessages(
    roomId: string,
    limit = 100,
  ): Promise<VoiceMessage[]> {
    if (!this.service) return [];

    // Paginate first
    await this.paginateTimeline(roomId, limit);

    return this.getVoiceMessages(roomId);
  }

  /**
   * Get rooms for this client
   */
  getRooms(): Array<{ roomId: string; name: string }> {
    if (!this.service) return [];

    return this.service
      .getDirectRooms()
      .map(r => ({ roomId: r.roomId, name: r.name }));
  }

  /**
   * Get the Matrix user ID
   */
  getUserId(): string | null {
    return this.service?.getUserId() || null;
  }

  /**
   * Check if client is logged in
   */
  isLoggedIn(): boolean {
    return this.service !== null;
  }

  /**
   * Get direct message rooms with metadata (similar to MatrixService.getDirectRooms)
   */
  getDirectRooms(): Array<{
    roomId: string;
    name: string;
    avatarUrl: string | null;
    lastMessage: string | null;
    lastMessageTime: number | null;
    isDirect: boolean;
  }> {
    if (!this.service) return [];

    return this.service.getDirectRooms();
  }

  /**
   * Get message count for a room
   */
  getMessageCount(roomId: string): number {
    return this.service?.getMessageCount(roomId) || 0;
  }

  /**
   * Get the access token for authenticated requests
   */
  getAccessToken(): string | undefined {
    return this.service?.getAccessToken() || undefined;
  }

  /**
   * Download media with authentication
   */
  async downloadMedia(
    mxcUrl: string,
  ): Promise<{ buffer: Buffer; contentType: string }> {
    const mxcMatch = mxcUrl.match(/^mxc:\/\/([^/]+)\/(.+)$/);
    if (!mxcMatch) {
      throw new Error(`Invalid MXC URL: ${mxcUrl}`);
    }

    const url = `${this.homeserverUrl}/_matrix/client/v1/media/download/${mxcMatch[1]}/${mxcMatch[2]}`;
    const token = this.getAccessToken();

    const response = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });

    if (!response.ok) {
      throw new Error(
        `Failed to download media: ${response.status} ${response.statusText}`,
      );
    }

    const buffer = Buffer.from(await response.arrayBuffer());
    const contentType =
      response.headers.get('content-type') || 'application/octet-stream';

    return { buffer, contentType };
  }

  /**
   * Logout and cleanup
   */
  async logout(): Promise<void> {
    if (!this.service) return;

    console.log(`[TestClient:${this.username}] Logging out...`);
    await this.service.logout();
    this.service = null;
    console.log(`[TestClient:${this.username}] Logged out`);
  }

  /**
   * Stop client without logout (for cleanup)
   */
  stop(): void {
    if (this.service) {
      console.log(`[TestClient:${this.username}] Stopping client...`);
      this.service.cleanup();
      this.service = null;
    }
  }

  // Private helpers

  private matchesFilter(message: VoiceMessage, filter: MessageFilter): boolean {
    if (filter.sender && message.sender !== filter.sender) return false;
    if (filter.eventId && message.eventId !== filter.eventId) return false;
    if (filter.minDuration && message.duration < filter.minDuration)
      return false;
    if (filter.maxDuration && message.duration > filter.maxDuration)
      return false;
    return true;
  }
}
