import { Buffer } from 'buffer';

import * as matrix from 'matrix-js-sdk';
import { MsgType } from 'matrix-js-sdk';
import RNFS from 'react-native-fs';
import * as Keychain from 'react-native-keychain';

import { MATRIX_CONFIG } from '../config/matrix';

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
    const client = matrix.createClient({
      baseUrl: HOMESERVER_URL,
    });

    const response = await client.login('m.login.password', {
      user: username,
      password: password,
    });

    // Store credentials securely
    await Keychain.setGenericPassword(
      response.user_id,
      JSON.stringify({
        accessToken: response.access_token,
        userId: response.user_id,
        deviceId: response.device_id,
      }),
      { service: KEYCHAIN_SERVICE },
    );

    // Create authenticated client
    this.client = matrix.createClient({
      baseUrl: HOMESERVER_URL,
      accessToken: response.access_token,
      userId: response.user_id,
      deviceId: response.device_id,
    });

    this.setupEventListeners();
    await this.client.startClient({ initialSyncLimit: 20 });
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

      const { accessToken, userId, deviceId } = JSON.parse(
        credentials.password,
      );

      this.client = matrix.createClient({
        baseUrl: HOMESERVER_URL,
        accessToken,
        userId,
        deviceId,
      });

      this.setupEventListeners();
      await this.client.startClient({ initialSyncLimit: 20 });
      return true;
    } catch {
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

    this.client.on(matrix.ClientEvent.Sync, state => {
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
}

// Export singleton instance
export const matrixService = new MatrixService();
