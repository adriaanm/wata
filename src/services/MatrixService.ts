import { Buffer } from 'buffer';

import * as matrix from 'matrix-js-sdk';
import { MsgType } from 'matrix-js-sdk';
import RNFS from 'react-native-fs';
import * as Keychain from 'react-native-keychain';

import { MATRIX_CONFIG } from '../config/matrix';
import {
  loginToMatrix,
  createStoredCredentials,
  type StoredCredentials,
} from '../lib/matrix-auth';
import { createFixedFetch } from '../lib/fixed-fetch-api';

// Configurable for testing - defaults to config
let HOMESERVER_URL = MATRIX_CONFIG.homeserverUrl;
const KEYCHAIN_SERVICE = 'wata-matrix-credentials';

// Allow overriding homeserver for tests
export function setHomeserverUrl(url: string): void {
  HOMESERVER_URL = url;
}

export function getHomeserverUrl(): string {
  return HOMESERVER_URL;
}

export interface MatrixRoom {
  roomId: string;
  name: string;
  avatarUrl: string | null;
  lastMessage: string | null;
  lastMessageTime: number | null;
  isDirect: boolean;
}

export interface VoiceMessage {
  eventId: string;
  sender: string;
  senderName: string;
  timestamp: number;
  audioUrl: string;
  duration: number;
  isOwn: boolean;
}

type SyncCallback = (state: string) => void;
type RoomCallback = (rooms: MatrixRoom[]) => void;
type MessageCallback = (roomId: string, message: VoiceMessage) => void;

class MatrixService {
  private client: matrix.MatrixClient | null = null;
  private syncCallbacks: SyncCallback[] = [];
  private roomCallbacks: RoomCallback[] = [];
  private messageCallbacks: MessageCallback[] = [];

  async login(username: string, password: string): Promise<void> {
    console.log('[MatrixService] login() called with:', { username, homeserver: HOMESERVER_URL });

    // Use shared login helper to ensure consistency with tests
    this.client = await loginToMatrix(HOMESERVER_URL, username, password, 'Wata');

    console.log('[MatrixService] Login successful, storing credentials');

    // Store credentials securely for session restoration
    const credentials: StoredCredentials = {
      accessToken: this.client.getAccessToken() || '',
      userId: this.client.getUserId() || '',
      deviceId: this.client.getDeviceId() || '',
      homeserverUrl: HOMESERVER_URL,
    };

    await Keychain.setGenericPassword(
      credentials.userId,
      JSON.stringify(credentials),
      { service: KEYCHAIN_SERVICE },
    );

    console.log('[MatrixService] Starting client sync');
    this.setupEventListeners();

    // Start client with options that don't require unsupported Conduit endpoints
    await this.client.startClient({
      initialSyncLimit: 20,
      // These features require endpoints Conduit doesn't implement
      disablePresence: true, // Disable presence to reduce errors
    });
  }

  /**
   * Auto-login using credentials from config.
   * This is used for devices without keyboard input.
   */
  async autoLogin(): Promise<void> {
    await this.login(MATRIX_CONFIG.username, MATRIX_CONFIG.password);
  }

  async restoreSession(): Promise<boolean> {
    try {
      const credentials = await Keychain.getGenericPassword({
        service: KEYCHAIN_SERVICE,
      });

      if (!credentials) {
        return false;
      }

      const stored: StoredCredentials = JSON.parse(credentials.password);

      this.client = matrix.createClient({
        baseUrl: stored.homeserverUrl,
        accessToken: stored.accessToken,
        userId: stored.userId,
        deviceId: stored.deviceId,
        fetchFn: createFixedFetch(),
      });

      this.setupEventListeners();

      // Start client with options that don't require unsupported Conduit endpoints
      await this.client.startClient({
        initialSyncLimit: 20,
        disablePresence: true,
      });

      return true;
    } catch (error) {
      console.error('[MatrixService] Failed to restore session:', error);
      return false;
    }
  }

  async logout(): Promise<void> {
    if (this.client) {
      this.client.stopClient();
      await this.client.logout();
      this.client = null;
    }
    await Keychain.resetGenericPassword({ service: KEYCHAIN_SERVICE });
  }

  private setupEventListeners(): void {
    if (!this.client) return;

    // Suppress non-critical errors from unsupported Conduit endpoints
    this.client.on(matrix.ClientEvent.Sync, (state, _prevState, data) => {
      // Log sync state changes
      if (state === 'ERROR') {
        console.warn('[MatrixService] Sync error (non-fatal):', data);
      }

      this.syncCallbacks.forEach(cb => cb(state));

      if (state === 'PREPARED' || state === 'SYNCING') {
        this.notifyRoomUpdate();
      }
    });

    this.client.on(matrix.RoomEvent.Timeline, (event, room) => {
      if (!room) return;

      // Check if it's a voice message
      if (event.getType() === 'm.room.message') {
        const content = event.getContent();
        if (content.msgtype === 'm.audio') {
          const voiceMessage = this.eventToVoiceMessage(event);
          if (voiceMessage) {
            this.messageCallbacks.forEach(cb => cb(room.roomId, voiceMessage));
          }
        }
      }

      this.notifyRoomUpdate();
    });

    // Catch and log HTTP errors without crashing
    this.client.on(matrix.HttpApiEvent.SessionLoggedOut, () => {
      console.warn('[MatrixService] Session logged out');
    });

    this.client.on(matrix.HttpApiEvent.NoConsent, () => {
      console.warn('[MatrixService] No consent');
    });
  }

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

  private notifyRoomUpdate(): void {
    const rooms = this.getDirectRooms();
    this.roomCallbacks.forEach(cb => cb(rooms));
  }

  getDirectRooms(): MatrixRoom[] {
    if (!this.client) return [];

    const rooms = this.client.getRooms();
    const directRooms: MatrixRoom[] = [];

    for (const room of rooms) {
      // Check if it's a DM (direct message room)
      const isDirect = this.isDirectRoom(room);

      const lastEvent = room.timeline
        .filter(e => e.getType() === 'm.room.message')
        .pop();

      directRooms.push({
        roomId: room.roomId,
        name: room.name || 'Unknown',
        avatarUrl: room.getAvatarUrl(HOMESERVER_URL, 48, 48, 'crop'),
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

  private isDirectRoom(room: matrix.Room): boolean {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
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

  async sendVoiceMessage(
    roomId: string,
    audioUri: string,
    mimeType: string,
    duration: number,
    size: number,
  ): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    // Read file and upload to Matrix
    const fileContent = await RNFS.readFile(audioUri, 'base64');
    const buffer = Buffer.from(fileContent, 'base64');

    const uploadResponse = await this.client.uploadContent(buffer, {
      type: mimeType,
      name: 'voice-message.m4a',
    });

    // Send the message
    await this.client.sendMessage(roomId, {
      msgtype: MsgType.Audio,
      body: 'Voice message',
      url: uploadResponse.content_uri,
      info: {
        mimetype: mimeType,
        duration: duration,
        size: size,
      },
    });
  }

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

  onSyncStateChange(callback: SyncCallback): () => void {
    this.syncCallbacks.push(callback);
    return () => {
      this.syncCallbacks = this.syncCallbacks.filter(cb => cb !== callback);
    };
  }

  onRoomUpdate(callback: RoomCallback): () => void {
    this.roomCallbacks.push(callback);
    return () => {
      this.roomCallbacks = this.roomCallbacks.filter(cb => cb !== callback);
    };
  }

  onNewVoiceMessage(callback: MessageCallback): () => void {
    this.messageCallbacks.push(callback);
    return () => {
      this.messageCallbacks = this.messageCallbacks.filter(
        cb => cb !== callback,
      );
    };
  }

  getUserId(): string | null {
    return this.client?.getUserId() || null;
  }

  isLoggedIn(): boolean {
    return this.client !== null;
  }

  // Test interface methods
  // These methods are primarily for testing and provide observability
  // into async operations

  /**
   * Get the underlying Matrix client (for advanced test scenarios)
   */
  getClient(): MatrixClient | null {
    return this.client;
  }

  /**
   * Get current sync state
   */
  getSyncState(): string | null {
    if (!this.client) return null;
    // The sync state is tracked internally by the client
    // We'll need to listen to sync events to track it
    return 'UNKNOWN'; // TODO: Track sync state in a property
  }

  /**
   * Wait for sync to complete (useful for tests)
   */
  async waitForSync(timeoutMs = 10000): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error(`Sync timeout after ${timeoutMs}ms`));
      }, timeoutMs);

      const onSync = (state: string) => {
        if (state === 'PREPARED' || state === 'SYNCING') {
          clearTimeout(timeout);
          const unsubscribe = this.onSyncStateChange(onSync);
          unsubscribe();
          resolve();
        }
      };

      this.onSyncStateChange(onSync);
    });
  }

  /**
   * Wait for a specific voice message (useful for tests)
   */
  async waitForMessage(
    roomId: string,
    predicate: (msg: VoiceMessage) => boolean,
    timeoutMs = 10000,
  ): Promise<VoiceMessage> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(
          new Error(
            `Message not received in room ${roomId} after ${timeoutMs}ms`,
          ),
        );
      }, timeoutMs);

      // Check existing messages first
      const existing = this.getVoiceMessages(roomId).find(predicate);
      if (existing) {
        clearTimeout(timeout);
        resolve(existing);
        return;
      }

      // Listen for new messages
      const onMessage = (msgRoomId: string, message: VoiceMessage) => {
        if (msgRoomId === roomId && predicate(message)) {
          clearTimeout(timeout);
          const unsubscribe = this.onNewVoiceMessage(onMessage);
          unsubscribe();
          resolve(message);
        }
      };

      this.onNewVoiceMessage(onMessage);
    });
  }

  /**
   * Get message count for a room (useful for tests)
   */
  getMessageCount(roomId: string): number {
    return this.getVoiceMessages(roomId).length;
  }

  /**
   * Cleanup all callbacks (useful for test isolation)
   */
  cleanup(): void {
    this.syncCallbacks = [];
    this.roomCallbacks = [];
    this.messageCallbacks = [];
  }
}

// Export singleton instance
export const matrixService = new MatrixService();
