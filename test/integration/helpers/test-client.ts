/**
 * TestClient - High-level test helper for Matrix operations
 *
 * Wraps MatrixService with test-friendly utilities for waiting on async operations,
 * creating rooms, sending/receiving messages, and managing test state.
 */

import * as matrix from 'matrix-js-sdk';

import { loginToMatrix } from '../../../src/lib/matrix-auth';
import type { VoiceMessage } from '../../../src/services/MatrixService';

export interface MessageFilter {
  sender?: string;
  eventId?: string;
  minDuration?: number;
  maxDuration?: number;
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
   * Note: Push rules requests are intercepted by createFixedFetch() in
   * src/lib/fixed-fetch-api.ts to work around Conduit's missing pushrules
   * endpoint. See that file and TEST_STRATEGY.md for details.
   *
   * If sync still fails with ERROR states, the workaround may need adjustment.
   */
  async waitForSync(timeoutMs = 30000): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    console.log(`[TestClient:${this.username}] Waiting for sync...`);

    return new Promise((resolve, reject) => {
      let resolved = false;

      const timeout = setTimeout(() => {
        if (!resolved) {
          cleanup();
          reject(new Error(`Sync timeout after ${timeoutMs}ms - never reached PREPARED state`));
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
            console.log(`[TestClient:${this.username}] Sync complete (${rooms.length} rooms)`);
            resolve();
          }
        }
        // With the push rules workaround in fixed-fetch-api.ts, we shouldn't see
        // ERROR states from pushrules 404s anymore. If we do, log it for debugging.
        else if (state === 'ERROR') {
          console.log(`[TestClient:${this.username}] ERROR state - check if pushrules workaround is active`);
          // Don't fail immediately - keep waiting for PREPARED
        }
      };

      this.client!.on(matrix.ClientEvent.Sync, onSync);

      // Start the client with Conduit-compatible options
      // Note: Push rules requests are intercepted by createFixedFetch()
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
      const timeout = setTimeout(() => {
        clearInterval(pollInterval);
        this.client?.off(matrix.ClientEvent.Room, checkRoom);
        reject(new Error(`Room ${roomId} not found after ${timeoutMs}ms`));
      }, timeoutMs);

      let checkCount = 0;

      const checkRoom = () => {
        const room = this.client!.getRoom(roomId);
        if (room) {
          clearTimeout(timeout);
          clearInterval(pollInterval);
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

      const pollInterval = setInterval(() => {
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

      const timeout = setTimeout(() => {
        if (!resolved) {
          clearInterval(pollInterval);
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
          clearInterval(pollInterval);
          this.client?.off(matrix.RoomEvent.Timeline, onTimeline);
          console.log(
            `[TestClient:${this.username}] Message received (${source})`,
          );
          resolve(msg);
        }
      };

      const onTimeline = (
        event: matrix.MatrixEvent,
        room?: matrix.Room,
      ) => {
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
      const pollInterval = setInterval(() => {
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
    console.log(`[TestClient:${this.username}] Room created: ${result.room_id}`);

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

    return {
      eventId: event.getId() || '',
      sender: sender,
      senderName: this.client?.getUser(sender)?.displayName || sender,
      timestamp: event.getTs(),
      audioUrl: this.client?.mxcUrlToHttp(content.url) || '',
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
}
