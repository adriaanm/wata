/**
 * TestClient - High-level test helper for Matrix operations
 *
 * Wraps MatrixService with test-friendly utilities for waiting on async operations,
 * creating rooms, sending/receiving messages, and managing test state.
 */

import * as matrix from 'matrix-js-sdk';

import { loginToMatrix } from '../../../src/shared/lib/matrix-auth';
import type { VoiceMessage as BaseVoiceMessage } from '../../../src/shared/services/MatrixService';

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

export class TestClient {
  private client: matrix.MatrixClient | null = null;
  private username: string;
  private password: string;
  private homeserverUrl: string;

  constructor(username: string, password: string, homeserverUrl: string) {
    this.username = username;
    this.password = password;
    this.homeserverUrl = homeserverUrl;
  }

  /**
   * Login and start syncing
   */
  async login(): Promise<void> {
    console.log(`[TestClient:${this.username}] Logging in...`);
    this.client = await loginToMatrix(
      this.homeserverUrl,
      this.username,
      this.password,
      `test-${this.username}`,
    );
    console.log(`[TestClient:${this.username}] Login successful`);
  }

  /**
   * Wait for initial sync to complete
   *
   * Note: createFixedFetch() in src/shared/lib/fixed-fetch-api.ts normalizes
   * URLs for Conduit compatibility (e.g., adding trailing slash to pushrules).
   */
  async waitForSync(timeoutMs = 30000): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for sync...`);

    // Check if already synced
    const currentState = this.client.getSyncState();
    if (currentState === 'PREPARED' || currentState === 'SYNCING') {
      const rooms = this.client.getRooms() || [];
      console.log(
        `[TestClient:${this.username}] Already synced (${rooms.length} rooms)`,
      );
      return Promise.resolve();
    }

    return new Promise((resolve, reject) => {
      let resolved = false;

      const timeout = setTimeout(() => {
        if (!resolved) {
          cleanup();
          reject(
            new Error(
              `Sync timeout after ${timeoutMs}ms - never reached PREPARED state`,
            ),
          );
        }
      }, timeoutMs);

      const cleanup = () => {
        this.client?.off(matrix.ClientEvent.Sync, onSync);
      };

      const onSync = (state: matrix.SyncState) => {
        // Log all state changes for debugging
        console.log(`[TestClient:${this.username}] Sync state: ${state}`);

        // Accept PREPARED or SYNCING as success
        if (state === 'PREPARED' || state === 'SYNCING') {
          if (!resolved) {
            resolved = true;
            clearTimeout(timeout);
            cleanup();

            const rooms = this.client?.getRooms() || [];
            console.log(
              `[TestClient:${this.username}] Sync complete (${rooms.length} rooms)`,
            );
            resolve();
          }
        }
        // With the push rules workaround in fixed-fetch-api.ts, we shouldn't see
        // ERROR states from pushrules 404s anymore. If we do, log it for debugging.
        else if (state === 'ERROR') {
          console.log(
            `[TestClient:${this.username}] ERROR state - check if pushrules workaround is active`,
          );
          // Don't fail immediately - keep waiting for PREPARED
        }
      };

      this.client!.on(matrix.ClientEvent.Sync, onSync);

      // Start the client with Conduit-compatible options
      this.client!.startClient({
        initialSyncLimit: 20,
        disablePresence: true, // Conduit doesn't fully support presence
      });
    });
  }

  /**
   * Wait for a specific room to appear in the client's room list
   *
   * Uses polling with retries to handle Conduit's eventual consistency.
   */
  async waitForRoom(roomId: string, timeoutMs = 15000): Promise<matrix.Room> {
    if (!this.client) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for room ${roomId}...`);

    // Check if room already exists
    const existingRoom = this.client.getRoom(roomId);
    if (existingRoom) {
      console.log(`[TestClient:${this.username}] Room already available`);
      return existingRoom;
    }

    // Wait for room to appear with retry logic
    return new Promise((resolve, reject) => {
      let pollInterval: ReturnType<typeof setInterval> | undefined;

      const timeout = setTimeout(() => {
        if (pollInterval) clearInterval(pollInterval);
        this.client?.off(matrix.ClientEvent.Room, checkRoom);
        reject(new Error(`Room ${roomId} not found after ${timeoutMs}ms`));
      }, timeoutMs);

      let checkCount = 0;

      const checkRoom = () => {
        const room = this.client!.getRoom(roomId);
        if (room) {
          clearTimeout(timeout);
          if (pollInterval) clearInterval(pollInterval);
          this.client!.off(matrix.ClientEvent.Room, checkRoom);
          console.log(
            `[TestClient:${this.username}] Room found (after ${checkCount} checks)`,
          );
          resolve(room);
          return true;
        }
        return false;
      };

      // Listen for room events
      this.client.on(matrix.ClientEvent.Room, checkRoom);

      // Poll for room with exponential backoff
      let pollDelay = 100;
      const maxPollDelay = 2000;

      pollInterval = setInterval(() => {
        checkCount++;
        if (!checkRoom()) {
          // Exponential backoff up to max delay
          pollDelay = Math.min(pollDelay * 1.2, maxPollDelay);
          if (checkCount % 10 === 0) {
            console.log(
              `[TestClient:${this.username}] Still waiting for room... (${checkCount} checks)`,
            );
          }
        }
      }, pollDelay);
    });
  }

  /**
   * Wait for a voice message matching the filter
   *
   * Checks existing messages first, then waits for new ones.
   * Uses polling as fallback in case timeline events are missed.
   */
  async waitForMessage(
    roomId: string,
    filter: MessageFilter,
    timeoutMs = 20000,
  ): Promise<VoiceMessage> {
    if (!this.client) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Waiting for message in room ${roomId}...`,
      filter,
    );

    return new Promise((resolve, reject) => {
      let resolved = false;
      let pollInterval: ReturnType<typeof setInterval> | undefined;

      const timeout = setTimeout(() => {
        if (!resolved) {
          if (pollInterval) clearInterval(pollInterval);
          this.client?.off(matrix.RoomEvent.Timeline, onTimeline);
          reject(
            new Error(
              `Message not received in room ${roomId} after ${timeoutMs}ms`,
            ),
          );
        }
      }, timeoutMs);

      const resolveWithMessage = (msg: VoiceMessage, source: string) => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timeout);
          if (pollInterval) clearInterval(pollInterval);
          this.client?.off(matrix.RoomEvent.Timeline, onTimeline);
          console.log(
            `[TestClient:${this.username}] Message received (${source})`,
          );
          resolve(msg);
        }
      };

      const onTimeline = (event: matrix.MatrixEvent, room?: matrix.Room) => {
        if (resolved || !room || room.roomId !== roomId) return;

        if (event.getType() === 'm.room.message') {
          const content = event.getContent();
          if (content.msgtype === 'm.audio') {
            const voiceMessage = this.eventToVoiceMessage(event);
            if (voiceMessage && this.matchesFilter(voiceMessage, filter)) {
              resolveWithMessage(voiceMessage, 'timeline event');
            }
          }
        }
      };

      this.client.on(matrix.RoomEvent.Timeline, onTimeline);

      // Check existing messages first
      const checkExisting = () => {
        const room = this.client!.getRoom(roomId);
        if (room) {
          const messages = this.getVoiceMessages(roomId);
          const existing = messages.find(msg =>
            this.matchesFilter(msg, filter),
          );
          if (existing) {
            resolveWithMessage(existing, 'existing timeline');
            return true;
          }
        }
        return false;
      };

      // Initial check
      if (checkExisting()) {
        return;
      }

      // Poll periodically as fallback (handles missed events)
      let pollCount = 0;
      pollInterval = setInterval(() => {
        if (!resolved) {
          pollCount++;
          if (pollCount % 5 === 0) {
            console.log(
              `[TestClient:${this.username}] Still waiting for message... (${pollCount} checks)`,
            );
          }
          checkExisting();
        }
      }, 1000);
    });
  }

  /**
   * Create a room and wait for it to be ready
   */
  async createRoom(
    options: matrix.ICreateRoomOpts,
  ): Promise<{ room_id: string }> {
    if (!this.client) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Creating room...`);
    const result = await this.client.createRoom(options);
    console.log(
      `[TestClient:${this.username}] Room created: ${result.room_id}`,
    );

    // Wait for room to appear in client
    await this.waitForRoom(result.room_id, 5000);

    return result;
  }

  /**
   * Join a room
   */
  async joinRoom(roomId: string): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Joining room ${roomId}...`);
    await this.client.joinRoom(roomId);
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
    if (!this.client) throw new Error('Not logged in');

    console.log(
      `[TestClient:${this.username}] Sending voice message to room ${roomId}...`,
    );

    // Upload audio content
    const uploadResponse = await this.client.uploadContent(audioBuffer, {
      type: mimeType,
      name: 'test-voice.m4a',
    });

    // Send the message
    const result = await this.client.sendMessage(roomId, {
      msgtype: matrix.MsgType.Audio,
      body: 'Voice message',
      url: uploadResponse.content_uri,
      info: {
        mimetype: mimeType,
        duration: duration,
        size: audioBuffer.length,
      },
    });

    console.log(
      `[TestClient:${this.username}] Voice message sent: ${result.event_id}`,
    );
    return result.event_id;
  }

  /**
   * Get all voice messages from a room
   */
  getVoiceMessages(roomId: string): VoiceMessage[] {
    if (!this.client) return [];

    const room = this.client.getRoom(roomId);
    if (!room) return [];

    return room.timeline
      .filter(event => {
        const content = event.getContent();
        return (
          event.getType() === 'm.room.message' && content.msgtype === 'm.audio'
        );
      })
      .map(event => this.eventToVoiceMessage(event))
      .filter((msg): msg is VoiceMessage => msg !== null);
  }

  /**
   * Paginate room timeline to fetch more events from server
   * Useful for stress tests where many messages were sent rapidly
   */
  async paginateTimeline(roomId: string, limit = 50): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    const room = this.client.getRoom(roomId);
    if (!room) {
      console.log(
        `[TestClient:${this.username}] Room ${roomId} not found for pagination`,
      );
      return;
    }

    console.log(
      `[TestClient:${this.username}] Paginating timeline for room ${roomId} (limit: ${limit})...`,
    );

    try {
      await this.client.scrollback(room, limit);
      console.log(
        `[TestClient:${this.username}] Pagination complete, timeline now has ${room.timeline.length} events`,
      );
    } catch (error) {
      console.log(`[TestClient:${this.username}] Pagination failed:`, error);
    }
  }

  /**
   * Get all voice messages with pagination to ensure we fetch all from server
   */
  async getAllVoiceMessages(
    roomId: string,
    limit = 100,
  ): Promise<VoiceMessage[]> {
    if (!this.client) return [];

    const room = this.client.getRoom(roomId);
    if (!room) return [];

    // Paginate to ensure we have all events
    await this.paginateTimeline(roomId, limit);

    return this.getVoiceMessages(roomId);
  }

  /**
   * Get rooms for this client
   */
  getRooms(): matrix.Room[] {
    if (!this.client) return [];
    return this.client.getRooms();
  }

  /**
   * Get the Matrix user ID
   */
  getUserId(): string | null {
    return this.client?.getUserId() || null;
  }

  /**
   * Check if client is logged in
   */
  isLoggedIn(): boolean {
    return this.client !== null;
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
    if (!this.client) return [];

    const rooms = this.client.getRooms();
    const directRooms = [];

    for (const room of rooms) {
      const isDirect = this.isDirectRoom(room);

      const lastEvent = room.timeline
        .filter(e => e.getType() === 'm.room.message')
        .pop();

      directRooms.push({
        roomId: room.roomId,
        name: room.name || 'Unknown',
        avatarUrl: room.getAvatarUrl(this.homeserverUrl, 48, 48, 'crop'),
        lastMessage: lastEvent?.getContent()?.body || null,
        lastMessageTime: lastEvent?.getTs() || null,
        isDirect,
      });
    }

    // Sort by last message time, most recent first
    return directRooms.sort((a, b) => {
      if (!a.lastMessageTime) return 1;
      if (!b.lastMessageTime) return -1;
      return b.lastMessageTime - a.lastMessageTime;
    });
  }

  /**
   * Get message count for a room
   */
  getMessageCount(roomId: string): number {
    return this.getVoiceMessages(roomId).length;
  }

  /**
   * Get the access token for authenticated requests
   */
  getAccessToken(): string | undefined {
    return this.client?.getAccessToken();
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
    if (!this.client) return;

    console.log(`[TestClient:${this.username}] Logging out...`);
    this.client.stopClient();
    await this.client.logout();
    this.client = null;
    console.log(`[TestClient:${this.username}] Logged out`);
  }

  /**
   * Stop client without logout (for cleanup)
   */
  stop(): void {
    if (this.client) {
      console.log(`[TestClient:${this.username}] Stopping client...`);
      this.client.stopClient();
    }
  }

  // Private helpers

  private eventToVoiceMessage(event: matrix.MatrixEvent): VoiceMessage | null {
    const content = event.getContent();
    if (content.msgtype !== 'm.audio') return null;

    const sender = event.getSender();
    if (!sender) return null;

    // Convert MXC URL to HTTP URL manually for Conduit compatibility
    // Matrix SDK's mxcUrlToHttp() doesn't work reliably with Conduit
    const mxcUrl = content.url;
    const mxcMatch = mxcUrl.match(/^mxc:\/\/([^/]+)\/(.+)$/);
    const audioUrl = mxcMatch
      ? `${this.homeserverUrl}/_matrix/client/v1/media/download/${mxcMatch[1]}/${mxcMatch[2]}`
      : '';

    return {
      eventId: event.getId() || '',
      sender: sender,
      senderName: this.client?.getUser(sender)?.displayName || sender,
      timestamp: event.getTs(),
      audioUrl,
      mxcUrl,
      duration: content.info?.duration || 0,
      isOwn: sender === this.client?.getUserId(),
    };
  }

  private matchesFilter(message: VoiceMessage, filter: MessageFilter): boolean {
    if (filter.sender && message.sender !== filter.sender) return false;
    if (filter.eventId && message.eventId !== filter.eventId) return false;
    if (filter.minDuration && message.duration < filter.minDuration)
      return false;
    if (filter.maxDuration && message.duration > filter.maxDuration)
      return false;
    return true;
  }

  private isDirectRoom(room: matrix.Room): boolean {
    const dmData = this.client?.getAccountData('m.direct' as any);
    if (!dmData) return false;

    const directRooms = dmData.getContent() as Record<string, string[]>;
    for (const userId of Object.keys(directRooms)) {
      if (directRooms[userId]?.includes(room.roomId)) {
        return true;
      }
    }
    return false;
  }
}
