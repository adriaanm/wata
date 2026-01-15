import { Buffer } from 'buffer';

import * as matrix from 'matrix-js-sdk';
import { MsgType } from 'matrix-js-sdk';

import { MATRIX_CONFIG } from '@shared/config/matrix';
import {
  loginToMatrix,
  createStoredCredentials,
  type StoredCredentials,
} from '@shared/lib/matrix-auth';
import { createFixedFetch } from '@shared/lib/fixed-fetch-api';
import type { CredentialStorage } from '@shared/services/CredentialStorage';
import { LogService } from '@tui/services/LogService';

// Helper to log to LogService (works in both TUI and RN environments)
const log = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('log', message);
  } catch {
    // LogService not available (e.g., in React Native), silently ignore
  }
};

const logWarn = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('warn', message);
  } catch {
    // LogService not available (e.g., in React Native), silently ignore
  }
};

const logError = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('error', message);
  } catch {
    // LogService not available (e.g., in React Native), silently ignore
  }
};

// Configurable for testing - defaults to config
let HOMESERVER_URL = MATRIX_CONFIG.homeserverUrl;

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

// Logger interface matching matrix-js-sdk's Logger type
interface MatrixLogger {
  trace(...msg: unknown[]): void;
  debug(...msg: unknown[]): void;
  info(...msg: unknown[]): void;
  warn(...msg: unknown[]): void;
  error(...msg: unknown[]): void;
  getChild(namespace: string): MatrixLogger;
}

class MatrixService {
  private client: matrix.MatrixClient | null = null;
  private syncCallbacks: SyncCallback[] = [];
  private roomCallbacks: RoomCallback[] = [];
  private messageCallbacks: MessageCallback[] = [];
  private credentialStorage: CredentialStorage;
  private logger?: MatrixLogger;

  constructor(credentialStorage: CredentialStorage, logger?: MatrixLogger) {
    this.credentialStorage = credentialStorage;
    this.logger = logger;
  }

  async login(username: string, password: string): Promise<void> {
    log('[MatrixService] login() called with:');
    log(`  username: ${username}, homeserver: ${HOMESERVER_URL}`);

    // Use shared login helper to ensure consistency with tests
    this.client = await loginToMatrix(HOMESERVER_URL, username, password, {
      deviceName: 'Wata',
      logger: this.logger,
    });

    log('[MatrixService] Login successful, storing credentials');

    // Store credentials securely for session restoration
    const credentials: StoredCredentials = {
      accessToken: this.client.getAccessToken() || '',
      userId: this.client.getUserId() || '',
      deviceId: this.client.getDeviceId() || '',
      homeserverUrl: HOMESERVER_URL,
    };

    await this.credentialStorage.storeSession(username, credentials);

    log('[MatrixService] Starting client sync');
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
   * @param username - Optional username to login as (defaults to config username)
   */
  async autoLogin(username?: string): Promise<void> {
    const user = username || MATRIX_CONFIG.username;
    const password = MATRIX_CONFIG.password; // Same password for all test users
    await this.login(user, password);
  }

  async restoreSession(username?: string): Promise<boolean> {
    try {
      const user = username || MATRIX_CONFIG.username;
      const stored = await this.credentialStorage.retrieveSession(user);

      if (!stored) {
        return false;
      }

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const clientOpts: any = {
        baseUrl: stored.homeserverUrl,
        accessToken: stored.accessToken,
        userId: stored.userId,
        deviceId: stored.deviceId,
        fetchFn: createFixedFetch(),
      };

      // Add custom logger if provided (for TUI to suppress console output)
      if (this.logger) {
        clientOpts.logger = this.logger;
      }

      this.client = matrix.createClient(clientOpts);

      this.setupEventListeners();

      // Start client with options that don't require unsupported Conduit endpoints
      await this.client.startClient({
        initialSyncLimit: 20,
        disablePresence: true,
      });

      return true;
    } catch (error) {
      logError(`[MatrixService] Failed to restore session: ${error}`);
      return false;
    }
  }

  async logout(): Promise<void> {
    if (this.client) {
      this.client.stopClient();
      await this.client.logout();
      this.client = null;
    }
    await this.credentialStorage.clear();
  }

  /**
   * Get the currently logged-in username (without homeserver domain)
   * @returns Username (e.g., 'alice') or null if not logged in
   */
  getCurrentUsername(): string | null {
    if (!this.client) return null;
    const userId = this.client.getUserId();
    if (!userId) return null;
    // Extract username from Matrix ID (@username:homeserver)
    return userId.split(':')[0].substring(1);
  }

  private setupEventListeners(): void {
    if (!this.client) return;

    // Suppress non-critical errors from unsupported Conduit endpoints
    this.client.on(matrix.ClientEvent.Sync, (state, _prevState, data) => {
      // Log sync state changes
      if (state === 'ERROR') {
        const dataStr = JSON.stringify(data, null, 2);
        logWarn(`[MatrixService] Sync error (non-fatal): ${dataStr}`);
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
      logWarn('[MatrixService] Session logged out');
    });

    this.client.on(matrix.HttpApiEvent.NoConsent, () => {
      logWarn('[MatrixService] No consent');
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
    audioBuffer: Buffer,
    mimeType: string,
    duration: number,
    size: number,
  ): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    // Upload audio buffer to Matrix
    const uploadResponse = await this.client.uploadContent(audioBuffer, {
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

  /**
   * Redact (delete) a single message
   * @param roomId - Room ID containing the message
   * @param eventId - Event ID of the message to redact
   * @param reason - Optional reason for redaction
   */
  async redactMessage(roomId: string, eventId: string, reason?: string): Promise<void> {
    if (!this.client) throw new Error('Not logged in');
    await this.client.redactEvent(roomId, eventId, undefined, reason ? { reason } : undefined);
  }

  /**
   * Redact (delete) multiple messages sequentially
   * @param roomId - Room ID containing the messages
   * @param eventIds - Array of event IDs to redact
   * @param reason - Optional reason for redaction
   */
  async redactMessages(roomId: string, eventIds: string[], reason?: string): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    // Sequential redaction to avoid rate limits
    for (const eventId of eventIds) {
      try {
        await this.client.redactEvent(roomId, eventId, undefined, reason ? { reason } : undefined);
      } catch (error) {
        logError(`Failed to redact message ${eventId}: ${error}`);
        // Continue with remaining messages even if one fails
      }
    }
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

// Export MatrixService class for instantiation with platform-specific adapters
export { MatrixService };
