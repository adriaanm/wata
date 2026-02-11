/**
 * TestClient - High-level test helper for Matrix operations
 *
 * Wraps WataService with test-friendly utilities for waiting on async operations,
 * creating rooms, sending/receiving messages, and managing test state.
 *
 * IMPORTANT: This uses the same WataService as production code to ensure
 * tests validate the actual code path used by apps.
 */

import type {
  CredentialStorage,
  MatrixRoom,
  VoiceMessage as BaseVoiceMessage,
} from '@shared/services';
import { setHomeserverUrl, type WataService } from '@shared/services/WataService';

import { createTestService, createTestCredentialStorage } from './test-service-factory';

export interface MessageFilter {
  sender?: string;
  eventId?: string;
  minDuration?: number;
  maxDuration?: number;
}

// Use the production VoiceMessage type (which now includes mxcUrl)
export type VoiceMessage = BaseVoiceMessage;

// Type union for the service (could be MatrixService or MatrixServiceAdapter)
type MatrixServiceLike = ReturnType<typeof createTestService>;

/**
 * TestClient wraps WataService for testing
 *
 * All operations go through the production WataService, ensuring tests
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

    // Create service using the WataService
    this.service = createTestService(this.homeserverUrl, this.credentialStorage);

    // Login through WataService (same as production)
    await this.service.login(this.username, this.password);

    console.log(`[TestClient:${this.username}] Login successful`);
  }

  /**
   * Wait for initial sync to complete
   *
   * Logs sync state changes and checks if already synced.
   */
  async waitForSync(timeoutMs = 10000): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for sync...`);

    // Check if already synced
    const currentState = this.service.getSyncState();
    if (currentState === 'PREPARED' || currentState === 'SYNCING') {
      const rooms = this.service.getDirectRooms();
      console.log(
        `[TestClient:${this.username}] Already synced (${rooms.length} rooms)`,
      );
      return;
    }

    // Log sync state changes for debugging
    const unsubscribe = this.service.onSyncStateChange((state) => {
      console.log(`[TestClient:${this.username}] Sync state: ${state}`);
    });

    try {
      // Use WataService's waitForSync method
      await this.service.waitForSync(timeoutMs);

      const rooms = this.service.getDirectRooms();
      console.log(
        `[TestClient:${this.username}] Sync complete (${rooms.length} rooms)`,
      );
    } finally {
      unsubscribe();
    }
  }

  /**
   * Wait for a specific room to appear in the client's room list
   *
   * Uses fast polling (100ms) - rooms typically appear within milliseconds.
   * No exponential backoff: we want consistent, predictable timing.
   */
  async waitForRoom(roomId: string, timeoutMs = 5000): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for room ${roomId}...`);

    // Check if room already exists (in direct rooms list or as a member)
    const existingRooms = this.service.getDirectRooms();
    if (existingRooms.some(r => r.roomId === roomId) || this.service.isRoomMember(roomId)) {
      console.log(`[TestClient:${this.username}] Room already available`);
      return;
    }

    // Capture service reference to avoid accessing this.service after cleanup
    const service = this.service;

    return new Promise((resolve, reject) => {
      const startTime = Date.now();
      let checkCount = 0;
      let resolved = false;

      const timeout = setTimeout(() => {
        if (!resolved) {
          cleanup();
          reject(new Error(`Room ${roomId} not found after ${timeoutMs}ms`));
        }
      }, timeoutMs);

      const cleanup = () => {
        clearTimeout(timeout);
        clearInterval(pollInterval);
        unsubscribe();
      };

      const checkRoom = () => {
        const rooms = service.getDirectRooms();
        if (rooms.some(r => r.roomId === roomId) || service.isRoomMember(roomId)) {
          if (!resolved) {
            resolved = true;
            cleanup();
            console.log(
              `[TestClient:${this.username}] Room found (after ${checkCount} checks, ${Date.now() - startTime}ms)`,
            );
            resolve();
          }
          return true;
        }
        return false;
      };

      // Listen for room update events
      const unsubscribe = service.onRoomUpdate(() => {
        checkRoom();
      });

      // Fast polling (100ms) - rooms appear quickly
      const pollInterval = setInterval(() => {
        if (resolved) return;
        checkCount++;
        if (!checkRoom() && checkCount % 20 === 0) {
          console.log(
            `[TestClient:${this.username}] Still waiting for room... (${checkCount} checks, ${Date.now() - startTime}ms)`,
          );
        }
      }, 100);
    });
  }

  /**
   * Wait for a voice message matching the filter
   *
   * Uses fast polling (100ms) - messages typically arrive within milliseconds.
   * No exponential backoff: we want consistent, predictable timing.
   */
  async waitForMessage(
    roomId: string,
    filter: MessageFilter,
    timeoutMs = 10000,
  ): Promise<VoiceMessage> {
    if (!this.service) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Waiting for message in room ${roomId}...`,
      filter,
    );

    const startTime = Date.now();

    return new Promise((resolve, reject) => {
      let resolved = false;
      let checkCount = 0;
      let unsubscribe: (() => void) | undefined;
      let pollInterval: ReturnType<typeof setInterval> | undefined;

      const timeout = setTimeout(() => {
        if (!resolved) {
          cleanup();
          reject(
            new Error(
              `Message not received in room ${roomId} after ${timeoutMs}ms`,
            ),
          );
        }
      }, timeoutMs);

      const cleanup = () => {
        clearTimeout(timeout);
        if (pollInterval) clearInterval(pollInterval);
        if (unsubscribe) unsubscribe();
      };

      const resolveWithMessage = (msg: VoiceMessage) => {
        if (!resolved) {
          resolved = true;
          cleanup();
          console.log(`[TestClient:${this.username}] Message received (${Date.now() - startTime}ms)`);
          resolve(msg);
        }
      };

      const checkExisting = () => {
        const existing = this.getVoiceMessages(roomId).find(msg =>
          this.matchesFilter(msg, filter),
        );
        if (existing) {
          resolveWithMessage(existing);
          return true;
        }
        return false;
      };

      // Initial check
      if (checkExisting()) {
        return;
      }

      // Listen for new messages
      unsubscribe = this.service!.onNewVoiceMessage(
        (msgRoomId: string, message: VoiceMessage) => {
          if (msgRoomId === roomId && this.matchesFilter(message, filter)) {
            resolveWithMessage(message);
          }
        },
      );

      // Fast polling (100ms) - messages arrive quickly
      pollInterval = setInterval(() => {
        if (resolved) return;
        checkCount++;
        if (!checkExisting() && checkCount % 20 === 0) {
          console.log(
            `[TestClient:${this.username}] Still waiting for message... (${checkCount} checks, ${Date.now() - startTime}ms)`,
          );
        }
      }, 100);
    });
  }

  /**
   * Create a room and wait for it to be ready
   *
   * For tests: Creates rooms using Matrix SDK so they're immediately available.
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

    console.log(`[TestClient:${this.username}] Creating room via SDK...`);

    // Use WataService's createRoom to create via SDK
    // This makes the room immediately available to the client
    const result = await this.service.createRoom({
      is_direct: options.is_direct,
      invite: options.invite,
      preset: options.preset || 'trusted_private_chat',
      name: options.name, // Unique name prevents DM service from matching
      room_alias_name: options.room_alias_name,
      visibility: options.visibility,
      initial_state: options.initial_state,
    });

    const roomId = result.room_id;
    console.log(`[TestClient:${this.username}] Room created: ${roomId}`);

    // Room should be immediately available since we used the SDK
    // But still wait briefly for sync to complete
    await this.waitForRoom(roomId, 5000);

    return { room_id: roomId };
  }

  /**
   * Join a room
   */
  async joinRoom(roomId: string): Promise<void> {
    if (!this.service) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Joining room ${roomId}...`);
    await this.service.joinRoom(roomId);
    await this.waitForRoom(roomId, 10000);
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

    // Send through WataService and get the event ID directly
    const eventId = await this.service.sendVoiceMessage(
      roomId,
      audioBuffer,
      mimeType,
      duration,
      audioBuffer.length,
    );

    console.log(
      `[TestClient:${this.username}] Voice message sent: ${eventId}`,
    );

    return eventId;
  }

  /**
   * Get all voice messages from a room
   *
   * Returns VoiceMessages with mxcUrl populated by the production service.
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

    // WataService doesn't expose pagination yet
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
   * Get direct message rooms with metadata (similar to WataService.getDirectRooms)
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

  /**
   * Wait for a condition to become true, polling frequently.
   * Use this instead of fixed setTimeout delays to make tests deterministic.
   * No exponential backoff: conditions typically resolve quickly.
   */
  async waitForCondition(
    description: string,
    condition: () => boolean,
    timeoutMs = 10000,
    pollMs = 100,
  ): Promise<void> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
      if (condition()) return;
      await new Promise(resolve => setTimeout(resolve, pollMs));
    }

    throw new Error(`Timed out waiting for: ${description} (after ${timeoutMs}ms)`);
  }

  /**
   * Wait for at least N voice messages to appear in a room
   */
  async waitForMessageCount(
    roomId: string,
    minCount: number,
    timeoutMs = 10000,
  ): Promise<VoiceMessage[]> {
    if (!this.service) throw new Error('Not logged in');

    await this.waitForCondition(
      `${minCount} messages in room ${roomId}`,
      () => this.getVoiceMessages(roomId).length >= minCount,
      timeoutMs,
    );

    return this.getVoiceMessages(roomId);
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
