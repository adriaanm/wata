import { Buffer } from 'buffer';

import * as matrix from 'matrix-js-sdk';
import { MsgType } from 'matrix-js-sdk';
import type { MatrixClient } from 'matrix-js-sdk';

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

export interface FamilyMember {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
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
  private currentSyncState: string = 'STOPPED';
  private currentUsername: string | null = null;
  private currentPassword: string | null = null;

  constructor(credentialStorage: CredentialStorage, logger?: MatrixLogger) {
    this.credentialStorage = credentialStorage;
    this.logger = logger;
  }

  async login(username: string, password: string): Promise<void> {
    log('[MatrixService] login() called with:');
    log(`  username: ${username}, homeserver: ${HOMESERVER_URL}`);

    // Store credentials for token refresh
    this.currentUsername = username;
    this.currentPassword = password;

    // Create refresh callback for token renewal
    const refreshToken = async () => {
      log('[MatrixService] Refreshing access token...');
      if (!this.currentUsername || !this.currentPassword) {
        logError('[MatrixService] Cannot refresh token: missing credentials');
        throw new Error('No credentials available for token refresh');
      }

      try {
        // Re-login to get a fresh access token
        const newClient = await loginToMatrix(
          HOMESERVER_URL,
          this.currentUsername,
          this.currentPassword,
          {
            deviceName: 'Wata',
            logger: this.logger,
          },
        );

        const newAccessToken = newClient.getAccessToken();
        if (!newAccessToken) {
          throw new Error('No access token in refresh response');
        }

        log('[MatrixService] Token refresh successful');

        // Update stored credentials
        const credentials: StoredCredentials = {
          accessToken: newAccessToken,
          userId: newClient.getUserId() || '',
          deviceId: newClient.getDeviceId() || '',
          homeserverUrl: HOMESERVER_URL,
        };
        await this.credentialStorage.storeSession(
          this.currentUsername,
          credentials,
        );

        // The newClient will be discarded; the SDK will update the current client
        // with the new access token
        return { access_token: newAccessToken };
      } catch (error) {
        logError(`[MatrixService] Token refresh failed: ${error}`);
        throw error;
      }
    };

    // Use shared login helper to ensure consistency with tests
    this.client = await loginToMatrix(HOMESERVER_URL, username, password, {
      deviceName: 'Wata',
      logger: this.logger,
      refreshToken,
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

      // Store username for token refresh
      this.currentUsername = user;
      // For prototype, use config password for refresh
      this.currentPassword = MATRIX_CONFIG.password;

      // Create refresh callback for token renewal
      const refreshToken = async () => {
        log('[MatrixService] Refreshing access token...');
        if (!this.currentUsername || !this.currentPassword) {
          logError('[MatrixService] Cannot refresh token: missing credentials');
          throw new Error('No credentials available for token refresh');
        }

        try {
          // Re-login to get a fresh access token
          const newClient = await loginToMatrix(
            HOMESERVER_URL,
            this.currentUsername,
            this.currentPassword,
            {
              deviceName: 'Wata',
              logger: this.logger,
            },
          );

          const newAccessToken = newClient.getAccessToken();
          if (!newAccessToken) {
            throw new Error('No access token in refresh response');
          }

          log('[MatrixService] Token refresh successful');

          // Update stored credentials
          const credentials: StoredCredentials = {
            accessToken: newAccessToken,
            userId: newClient.getUserId() || '',
            deviceId: newClient.getDeviceId() || '',
            homeserverUrl: HOMESERVER_URL,
          };
          await this.credentialStorage.storeSession(
            this.currentUsername,
            credentials,
          );

          return { access_token: newAccessToken };
        } catch (error) {
          logError(`[MatrixService] Token refresh failed: ${error}`);
          throw error;
        }
      };

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const clientOpts: any = {
        baseUrl: stored.homeserverUrl,
        accessToken: stored.accessToken,
        userId: stored.userId,
        deviceId: stored.deviceId,
        fetchFn: createFixedFetch(),
        refreshToken,
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
      try {
        await this.client.logout();
      } catch (error) {
        // If we get a 401, we're already logged out - continue with cleanup
        const isUnknownToken =
          error instanceof Error &&
          (error.message.includes('M_UNKNOWN_TOKEN') ||
            error.message.includes('401'));
        if (!isUnknownToken) {
          throw error;
        }
      }
      this.client = null;
    }
    // Clear stored credentials and in-memory credentials
    await this.credentialStorage.clear();
    this.currentUsername = null;
    this.currentPassword = null;
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

  /**
   * Get the current user's display name from their profile
   */
  async getDisplayName(): Promise<string | null> {
    if (!this.client) return null;
    const userId = this.client.getUserId();
    if (!userId) return null;

    try {
      const profile = await this.client.getProfileInfo(userId);
      return profile.displayname || null;
    } catch {
      return null;
    }
  }

  /**
   * Set the current user's display name (friendly name shown to others)
   */
  async setDisplayName(displayName: string): Promise<void> {
    if (!this.client) throw new Error('Not logged in');
    await this.client.setDisplayName(displayName);
    log(`[MatrixService] Display name set to: ${displayName}`);
  }

  private setupEventListeners(): void {
    if (!this.client) return;

    // Suppress non-critical errors from unsupported Conduit endpoints
    this.client.on(matrix.ClientEvent.Sync, async (state, _prevState, data) => {
      // Track current sync state
      this.currentSyncState = state;

      // Log sync state changes
      if (state === 'ERROR') {
        const dataStr = JSON.stringify(data, null, 2);
        logWarn(`[MatrixService] Sync error (non-fatal): ${dataStr}`);
      }

      this.syncCallbacks.forEach(cb => cb(state));

      if (state === 'PREPARED' || state === 'SYNCING') {
        this.notifyRoomUpdate();
      }

      // On initial sync, check for DM rooms that need m.direct update
      // This handles legacy rooms or rooms where the membership listener was missed
      if (state === 'PREPARED') {
        await this.syncDirectRoomsFromMembership();
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

    // Handle room membership changes to sync m.direct for DM rooms
    // When we join a room that was created as a DM by another user,
    // we need to update our own m.direct account data
    this.client.on(
      matrix.RoomMemberEvent.Membership,
      async (event, member, oldMembership) => {
        if (!this.client) return;

        // Only care about our own membership changes
        if (member.userId !== this.client.getUserId()) return;

        // Only care when we join a room (from any previous state)
        if (member.membership !== 'join') return;

        // Skip if we were already joined (no change)
        if (oldMembership === 'join') return;

        // Check if this room was created as a DM using getDMInviter()
        // This is the recommended way per matrix-js-sdk docs
        const dmInviter = member.getDMInviter();
        if (!dmInviter) return;

        const roomId = event.getRoomId();
        if (!roomId) return;

        // Check if already in m.direct
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const dmData = this.client.getAccountData('m.direct' as any);
        const existingDirect = dmData
          ? (dmData.getContent() as Record<string, string[]>)
          : {};
        if (existingDirect[dmInviter]?.includes(roomId)) {
          // Already tracked, no need to update
          return;
        }

        log(
          `[MatrixService] Joined DM room ${roomId} from ${dmInviter}, updating m.direct`,
        );

        // Update our m.direct to recognize this as a DM room
        try {
          await this.updateDirectRoomData(dmInviter, roomId);
          // Notify about room updates so UI refreshes
          this.notifyRoomUpdate();
        } catch (err) {
          logError(`[MatrixService] Failed to update m.direct: ${err}`);
        }
      },
    );

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

    const mxcUrl = content.url;
    // Use authenticated media endpoint (requires Authorization header)
    // The v3 endpoint is always unauthenticated in Conduit, so we use v1
    const mxcMatch = mxcUrl.match(/^mxc:\/\/([^/]+)\/(.+)$/);
    const audioUrl = mxcMatch
      ? `${HOMESERVER_URL}/_matrix/client/v1/media/download/${mxcMatch[1]}/${mxcMatch[2]}`
      : '';

    // Log URL conversion for debugging audio playback issues
    log(
      `[MatrixService] Audio URL conversion: MXC=${mxcUrl} -> HTTP=${audioUrl}`,
    );

    // Verify the MXC URL format and log the server name
    if (mxcMatch) {
      const [, serverName, mediaId] = mxcMatch;
      log(`[MatrixService] MXC parsed: server=${serverName}, id=${mediaId}`);

      // Log what we'd expect the URL to be (authenticated v1 endpoint)
      const expectedUrl = `${HOMESERVER_URL}/_matrix/client/v1/media/download/${serverName}/${mediaId}`;
      log(`[MatrixService] Expected HTTP URL: ${expectedUrl}`);
      log(`[MatrixService] Actual HTTP URL: ${audioUrl}`);
      log(`[MatrixService] URLs match: ${audioUrl === expectedUrl}`);
    } else {
      logWarn(`[MatrixService] Invalid MXC URL format: ${mxcUrl}`);
    }

    return {
      eventId: event.getId() || '',
      sender: sender,
      senderName: this.client?.getUser(sender)?.displayName || sender,
      timestamp: event.getTs(),
      audioUrl,
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

  /**
   * Get the family room by alias (#family:server)
   * Returns null if not found or not joined
   */
  async getFamilyRoom(): Promise<matrix.Room | null> {
    if (!this.client) return null;

    try {
      // Extract server name from homeserver URL
      const serverName = new URL(HOMESERVER_URL).hostname;
      const alias = `#family:${serverName}`;

      // Try to resolve the alias
      const result = await this.client.getRoomIdForAlias(alias);
      if (result?.room_id) {
        return this.client.getRoom(result.room_id);
      }
    } catch (error) {
      // Room alias doesn't exist or we don't have access
      log(`[MatrixService] Family room not found: ${error}`);
    }

    return null;
  }

  /**
   * Get family members from the family room
   * Returns empty array if family room doesn't exist
   * @param includeSelf - If true, includes the current user in the list (default: false)
   */
  async getFamilyMembers(includeSelf = false): Promise<FamilyMember[]> {
    const familyRoom = await this.getFamilyRoom();
    if (!familyRoom) return [];

    const myUserId = this.client?.getUserId();
    const members = familyRoom.getJoinedMembers();

    return members
      .filter(member => includeSelf || member.userId !== myUserId)
      .map(member => ({
        userId: member.userId,
        displayName: member.name || member.userId.split(':')[0].substring(1),
        avatarUrl:
          member.getAvatarUrl(HOMESERVER_URL, 48, 48, 'crop', false, false) ||
          null,
      }));
  }

  /**
   * Get or create a DM room with another user
   * If a DM room already exists, return it. Otherwise, create a new one.
   */
  async getOrCreateDmRoom(userId: string): Promise<string> {
    if (!this.client) throw new Error('Not logged in');

    // First, check existing DM rooms from m.direct
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const dmData = this.client.getAccountData('m.direct' as any);
    if (dmData) {
      const directRooms = dmData.getContent() as Record<string, string[]>;
      const existingRoomIds = directRooms[userId];
      if (existingRoomIds && existingRoomIds.length > 0) {
        // Find the first room we're actually a member of AND the target user is also in
        for (const roomId of existingRoomIds) {
          const room = this.client.getRoom(roomId);
          if (room && room.getMyMembership() === 'join') {
            // Check if the target user is also in the room
            const targetMember = room.getMember(userId);
            if (targetMember && targetMember.membership === 'join') {
              return roomId;
            }
          }
        }
      }
    }

    // Second, check for rooms where this user invited us (may not be in m.direct yet)
    const myUserId = this.client.getUserId();
    const rooms = this.client.getRooms();
    for (const room of rooms) {
      if (room.getMyMembership() !== 'join') continue;

      const members = room.getJoinedMembers();
      if (members.length !== 2) continue;

      // Check if this is a DM with the target user
      const hasTargetUser = members.some(m => m.userId === userId);
      if (!hasTargetUser) continue;

      // Check if this is a DM room (via getDMInviter)
      const myMember = room.getMember(myUserId!);
      const dmInviter = myMember?.getDMInviter();
      if (dmInviter === userId) {
        // Found a DM room with this user that wasn't in m.direct
        // Update m.direct and return this room
        log(
          `[MatrixService] Found existing DM room ${room.roomId} with ${userId}, updating m.direct`,
        );
        await this.updateDirectRoomData(userId, room.roomId);
        return room.roomId;
      }
    }

    // Create new DM room
    log(`[MatrixService] Creating DM room with ${userId}`);
    const result = await this.client.createRoom({
      is_direct: true,
      invite: [userId],
      preset: matrix.Preset.TrustedPrivateChat,
    });

    // Update m.direct account data
    await this.updateDirectRoomData(userId, result.room_id);

    return result.room_id;
  }

  /**
   * Update m.direct account data to mark a room as a DM
   */
  private async updateDirectRoomData(
    userId: string,
    roomId: string,
  ): Promise<void> {
    if (!this.client) return;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const dmData = this.client.getAccountData('m.direct' as any);
    const content = dmData ? { ...dmData.getContent() } : {};

    if (!content[userId]) {
      content[userId] = [];
    }
    if (!content[userId].includes(roomId)) {
      content[userId].push(roomId);
    }

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await this.client.setAccountData('m.direct' as any, content as any);
  }

  /**
   * Sync m.direct account data from room membership events
   * This handles legacy rooms or rooms where the membership event listener was missed.
   * Called on initial sync to ensure m.direct is up to date.
   */
  private async syncDirectRoomsFromMembership(): Promise<void> {
    if (!this.client) return;

    const myUserId = this.client.getUserId();
    if (!myUserId) return;

    // Get current m.direct data
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const dmData = this.client.getAccountData('m.direct' as any);
    const existingDirect = dmData
      ? (dmData.getContent() as Record<string, string[]>)
      : {};

    // Build a set of all room IDs already in m.direct
    const knownDmRoomIds = new Set<string>();
    for (const roomIds of Object.values(existingDirect)) {
      for (const roomId of roomIds) {
        knownDmRoomIds.add(roomId);
      }
    }

    // Check all joined rooms for DM markers
    const rooms = this.client.getRooms();
    let updated = false;

    for (const room of rooms) {
      // Skip if already known as DM
      if (knownDmRoomIds.has(room.roomId)) continue;

      // Skip if not joined
      if (room.getMyMembership() !== 'join') continue;

      // Check if this is a 2-person room (likely DM)
      const members = room.getJoinedMembers();
      if (members.length !== 2) continue;

      // Get the other member
      const otherMember = members.find(m => m.userId !== myUserId);
      if (!otherMember) continue;

      // Check if this room was created as a DM using getDMInviter()
      // This is the recommended way per matrix-js-sdk docs
      const myMember = room.getMember(myUserId);
      if (!myMember) continue;

      const dmInviter = myMember.getDMInviter();
      if (dmInviter) {
        log(
          `[MatrixService] Found untracked DM room ${room.roomId} with ${dmInviter}, adding to m.direct`,
        );

        // Add to existing direct data using the inviter
        if (!existingDirect[dmInviter]) {
          existingDirect[dmInviter] = [];
        }
        if (!existingDirect[dmInviter].includes(room.roomId)) {
          existingDirect[dmInviter].push(room.roomId);
          updated = true;
        }
      }
    }

    // Save updated m.direct if we found new DM rooms
    if (updated) {
      log('[MatrixService] Updating m.direct with newly discovered DM rooms');
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      await this.client.setAccountData('m.direct' as any, existingDirect as any);
      this.notifyRoomUpdate();
    }
  }

  /**
   * Create the family room (admin operation)
   * Creates a private room with alias #family:server
   * Uses TrustedPrivateChat preset so all members can invite new family members
   */
  async createFamilyRoom(): Promise<string> {
    if (!this.client) throw new Error('Not logged in');

    const serverName = new URL(HOMESERVER_URL).hostname;
    const alias = `family`; // Will become #family:server

    log(
      `[MatrixService] Creating family room with alias #${alias}:${serverName}`,
    );

    const result = await this.client.createRoom({
      name: 'Family',
      room_alias_name: alias,
      visibility: matrix.Visibility.Private,
      // TrustedPrivateChat allows all members to invite, which is appropriate
      // for a family room where any parent should be able to add members
      preset: matrix.Preset.TrustedPrivateChat,
    });

    log(`[MatrixService] Family room created: ${result.room_id}`);
    return result.room_id;
  }

  /**
   * Invite a user to the family room (admin operation)
   */
  async inviteToFamily(userId: string): Promise<void> {
    const familyRoom = await this.getFamilyRoom();
    if (!familyRoom) {
      throw new Error('Family room does not exist');
    }

    if (!this.client) throw new Error('Not logged in');

    log(`[MatrixService] Inviting ${userId} to family room`);
    await this.client.invite(familyRoom.roomId, userId);
  }

  /**
   * Join a room by room ID or alias
   */
  async joinRoom(roomIdOrAlias: string): Promise<void> {
    if (!this.client) throw new Error('Not logged in');
    log(`[MatrixService] Joining room ${roomIdOrAlias}`);
    await this.client.joinRoom(roomIdOrAlias);
  }

  /**
   * Check if user is a member of a room
   */
  isRoomMember(roomId: string): boolean {
    if (!this.client) return false;
    const room = this.client.getRoom(roomId);
    if (!room) return false;
    const myMembership = room.getMyMembership();
    return myMembership === 'join';
  }

  /**
   * Get family room ID by resolving alias (even if not joined)
   * This is useful for joining the family room if it exists
   */
  async getFamilyRoomIdFromAlias(): Promise<string | null> {
    if (!this.client) return null;

    try {
      const serverName = new URL(HOMESERVER_URL).hostname;
      const alias = `#family:${serverName}`;

      const result = await this.client.getRoomIdForAlias(alias);
      return result?.room_id || null;
    } catch {
      return null;
    }
  }

  /**
   * Get the family room ID (for sending broadcast messages)
   */
  async getFamilyRoomId(): Promise<string | null> {
    const familyRoom = await this.getFamilyRoom();
    return familyRoom?.roomId || null;
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
    const extension =
      mimeType.includes('ogg') || mimeType.includes('opus') ? 'ogg' : 'm4a';

    log(`[MatrixService] Uploading audio: ${size} bytes, type=${mimeType}`);

    // Log the full upload response for debugging
    const uploadResponse = await this.client.uploadContent(audioBuffer, {
      type: mimeType,
      name: `voice-message.${extension}`,
    });

    // Log the complete upload response object
    log(
      `[MatrixService] Upload complete. Response: ${JSON.stringify(uploadResponse)}`,
    );

    // Verify the MXC URL format and log the server name
    const mxcMatch = uploadResponse.content_uri.match(
      /^mxc:\/\/([^/]+)\/(.+)$/,
    );
    if (mxcMatch) {
      const [, serverName, mediaId] = mxcMatch;
      log(`[MatrixService] MXC parsed: server=${serverName}, id=${mediaId}`);

      // Verify the content is accessible by attempting to construct the download URL
      const testUrl = `${HOMESERVER_URL}/_matrix/media/v3/download/${serverName}/${mediaId}`;
      log(`[MatrixService] Content should be accessible at: ${testUrl}`);
    } else {
      logWarn(
        `[MatrixService] Invalid MXC URL format: ${uploadResponse.content_uri}`,
      );
    }

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
  async redactMessage(
    roomId: string,
    eventId: string,
    reason?: string,
  ): Promise<void> {
    if (!this.client) throw new Error('Not logged in');
    await this.client.redactEvent(
      roomId,
      eventId,
      undefined,
      reason ? { reason } : undefined,
    );
  }

  /**
   * Redact (delete) multiple messages sequentially
   * @param roomId - Room ID containing the messages
   * @param eventIds - Array of event IDs to redact
   * @param reason - Optional reason for redaction
   */
  async redactMessages(
    roomId: string,
    eventIds: string[],
    reason?: string,
  ): Promise<void> {
    if (!this.client) throw new Error('Not logged in');

    // Sequential redaction to avoid rate limits
    for (const eventId of eventIds) {
      try {
        await this.client.redactEvent(
          roomId,
          eventId,
          undefined,
          reason ? { reason } : undefined,
        );
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
  getSyncState(): string {
    return this.currentSyncState;
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
   * Get the current access token for authenticated requests
   */
  getAccessToken(): string | null {
    return this.client?.getAccessToken() || null;
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
