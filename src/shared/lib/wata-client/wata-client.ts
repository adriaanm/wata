/**
 * WataClient: High-level domain interface for Wata walkie-talkie features
 *
 * This is the main client library that frontends interact with. It wraps
 * MatrixApi and SyncEngine to provide a domain-specific API (families,
 * contacts, voice messages) rather than exposing Matrix protocol details.
 *
 * See docs/planning/client-lib.md for design rationale.
 */

import { DMRoomService } from './dm-room-service';
import { EventBuffer } from './event-buffer';
import { MatrixApi } from './matrix-api';
import { SyncEngine } from './sync-engine';
import type { SyncEngineOptions } from './sync-engine';
import type { RoomState, MemberInfo } from './sync-engine';
import type { MatrixEvent } from './matrix-api';
import type {
  User,
  Contact,
  Family,
  Conversation,
  VoiceMessage,
  ConnectionState,
  WataClientEvents,
  WataClientEventName,
  Logger,
} from './types';

// ============================================================================
// No-op logger (default when no logger provided)
// ============================================================================

const noopLogger: Logger = {
  log: () => {},
  warn: () => {},
  error: () => {},
};

// ============================================================================
// WataClient Implementation
// ============================================================================

export class WataClient {
  private api: MatrixApi;
  private syncEngine!: SyncEngine;
  private dmRoomService!: DMRoomService;
  private eventBuffer!: EventBuffer;
  private bufferCheckTimer?: ReturnType<typeof setInterval>;
  private userId: string | null = null;
  private familyRoomId: string | null = null;
  private eventHandlers: Map<WataClientEventName, Set<Function>> = new Map();
  private isConnected = false;
  private logger: Logger;
  private syncOptions?: SyncEngineOptions;

  constructor(homeserverUrl: string, logger?: Logger, options?: { sync?: SyncEngineOptions }) {
    this.api = new MatrixApi(homeserverUrl);
    this.logger = logger ?? noopLogger;
    this.syncOptions = options?.sync;
  }

  // ==========================================================================
  // Event Emitter
  // ==========================================================================

  on<K extends WataClientEventName>(
    event: K,
    handler: WataClientEvents[K]
  ): void {
    if (!this.eventHandlers.has(event)) {
      this.eventHandlers.set(event, new Set());
    }
    this.eventHandlers.get(event)!.add(handler as Function);
  }

  off<K extends WataClientEventName>(
    event: K,
    handler: WataClientEvents[K]
  ): void {
    const handlers = this.eventHandlers.get(event);
    if (handlers) {
      handlers.delete(handler as Function);
    }
  }

  private emit<K extends WataClientEventName>(
    event: K,
    ...args: Parameters<WataClientEvents[K]>
  ): void {
    const handlers = this.eventHandlers.get(event);
    if (handlers) {
      handlers.forEach((handler) => {
        try {
          (handler as any)(...args);
        } catch (error) {
          this.logger.error(`[WataClient] Error in ${event} handler: ${error}`);
        }
      });
    }
  }

  // ==========================================================================
  // Lifecycle Methods
  // ==========================================================================

  /**
   * Login with username and password
   */
  async login(username: string, password: string): Promise<void> {
    this.logger.log(`[WataClient] Logging in as ${username}`);
    const response = await this.api.login(username, password, 'Wata Client');
    this.userId = response.user_id;
    this.logger.log(`[WataClient] Login successful: ${this.userId}`);

    // Create sync engine and set user ID
    this.syncEngine = new SyncEngine(this.api, this.logger, this.syncOptions);
    this.syncEngine.setUserId(this.userId);

    // Create DM room service
    this.dmRoomService = new DMRoomService(
      this.api,
      this.syncEngine,
      this.userId,
      this.logger
    );

    // Create event buffer for out-of-order events
    this.eventBuffer = new EventBuffer({
      maxAgeMs: 5 * 60 * 1000, // 5 minutes
      maxEventsPerRoom: 100,
    });

    // Wire up sync engine events
    this.setupSyncEngineListeners();
  }

  /**
   * Start real-time sync
   *
   * Room identification now uses on-demand lookups:
   * - Family room: detected by canonical alias (#family:server)
   * - DM rooms: detected by m.direct account data or 2-member room inference
   *
   * No pre-loading required - sync populates room state and account data.
   */
  async connect(): Promise<void> {
    if (!this.userId) {
      throw new Error('Not logged in - call login() first');
    }

    if (this.isConnected) {
      throw new Error('Already connected');
    }

    this.logger.log('[WataClient] Starting sync');

    // Start sync loop (includes initial sync)
    // Room state and account data will be populated during sync
    await this.syncEngine.start();

    // Start buffer retry timer - checks for newly-classified rooms every 300ms,
    // and prunes stale events every 10 seconds
    let pruneCounter = 0;
    this.bufferCheckTimer = setInterval(() => {
      // Always check for newly-classified rooms
      this.flushAllClassifiedRooms();

      // Prune every ~10 seconds (33 iterations at 300ms)
      pruneCounter++;
      if (pruneCounter >= 33) {
        const pruned = this.eventBuffer.prune();
        if (pruned > 0) {
          this.logger.log(`[WataClient] Pruned ${pruned} stale buffered events`);
        }
        pruneCounter = 0;
      }
    }, 300);

    this.isConnected = true;
    this.logger.log('[WataClient] Connected and syncing');
  }

  /**
   * Stop sync and cleanup
   */
  async disconnect(): Promise<void> {
    if (!this.isConnected) {
      return;
    }

    this.logger.log('[WataClient] Disconnecting');

    // Stop buffer check timer
    if (this.bufferCheckTimer) {
      clearInterval(this.bufferCheckTimer);
      this.bufferCheckTimer = undefined;
    }

    await this.syncEngine.stop();
    this.isConnected = false;
    this.emit('connectionStateChanged', 'offline');
  }

  /**
   * Logout and invalidate session
   */
  async logout(): Promise<void> {
    this.logger.log('[WataClient] Logging out');
    if (this.isConnected) {
      await this.disconnect();
    }

    await this.api.logout();

    // Clear state
    this.userId = null;
    this.familyRoomId = null;
    this.dmRoomService?.clear();
    this.syncEngine.clear();
    this.eventBuffer?.clear();
    this.logger.log('[WataClient] Logged out');
  }

  /**
   * Get current user
   */
  getCurrentUser(): User | null {
    if (!this.userId) {
      return null;
    }

    // Get display name from profile
    // For now, just use user ID as display name
    // We can enhance this by fetching profile on login
    return {
      id: this.userId,
      displayName: this.userId.split(':')[0].substring(1), // @alice:server -> alice
      avatarUrl: null,
    };
  }

  /**
   * Verify the current user by calling whoami API
   * Returns the user ID if authenticated, null otherwise
   */
  async whoami(): Promise<string | null> {
    try {
      const response = await this.api.whoami();
      return response.user_id;
    } catch {
      return null;
    }
  }

  /**
   * Get current access token for authenticated media downloads
   */
  getAccessToken(): string | null {
    return this.api.getAccessToken();
  }

  /**
   * Get the underlying MatrixApi instance
   */
  getApi(): MatrixApi {
    return this.api;
  }

  /**
   * Get the underlying SyncEngine instance
   */
  getSyncEngine(): SyncEngine {
    return this.syncEngine;
  }

  /**
   * Get connection state
   */
  getConnectionState(): ConnectionState {
    if (!this.isConnected) {
      return 'offline';
    }

    // Map sync engine state to connection state
    // For now, we assume if sync is running, we're syncing
    // TODO: Track actual sync state from sync engine events
    return 'syncing';
  }

  // ==========================================================================
  // Family Methods
  // ==========================================================================

  /**
   * Find the family room by scanning rooms for the #family alias.
   * Updates familyRoomId cache if found.
   */
  private findFamilyRoom(): RoomState | null {
    // Check cache first
    if (this.familyRoomId) {
      const room = this.syncEngine.getRoom(this.familyRoomId);
      if (room) {
        return room;
      }
      // Cache is stale, clear it
      this.familyRoomId = null;
    }

    // Scan rooms for #family alias
    const server = this.userId?.split(':')[1];
    if (!server) return null;

    const familyAlias = `#family:${server}`;
    for (const room of this.syncEngine.getRooms()) {
      if (room.canonicalAlias === familyAlias) {
        // Update cache
        this.familyRoomId = room.roomId;
        return room;
      }
    }

    return null;
  }

  /**
   * Check if a room is the family room (by canonical alias)
   */
  private isFamilyRoom(roomId: string): boolean {
    const room = this.syncEngine.getRoom(roomId);
    if (!room) return false;

    const server = this.userId?.split(':')[1];
    if (!server) return false;

    return room.canonicalAlias === `#family:${server}`;
  }

  /**
   * Get the family (null if not in a family)
   */
  getFamily(): Family | null {
    const room = this.findFamilyRoom();
    if (!room) {
      return null;
    }

    return {
      id: room.roomId,
      name: room.name || 'Family',
      members: this.getContactsFromRoom(room),
    };
  }

  /**
   * Get all contacts (family members excluding self)
   */
  getContacts(): Contact[] {
    const family = this.getFamily();
    return family?.members || [];
  }

  /**
   * Create family room with #family alias
   */
  async createFamily(name: string): Promise<Family> {
    const response = await this.api.createRoom({
      name,
      visibility: 'private',
      preset: 'private_chat',
      room_alias_name: 'family',
    });

    this.familyRoomId = response.room_id;

    // Wait for room to appear in sync
    await this.waitForRoom(response.room_id);

    const family = this.getFamily();
    if (!family) {
      throw new Error('Failed to create family - room not found after creation');
    }

    return family;
  }

  /**
   * Invite user to family room
   */
  async inviteToFamily(userId: string): Promise<void> {
    const familyRoom = this.findFamilyRoom();
    if (!familyRoom) {
      throw new Error('Not in a family - create or join a family first');
    }

    await this.api.inviteToRoom(familyRoom.roomId, { user_id: userId });
  }

  // ==========================================================================
  // Conversation Methods
  // ==========================================================================

  /**
   * Get conversation with a contact (creates DM if needed)
   */
  async getConversation(contact: Contact): Promise<Conversation> {
    const roomId = await this.getOrCreateDMRoom(contact.user.id);
    const room = this.syncEngine.getRoom(roomId);

    if (!room) {
      throw new Error(`Room ${roomId} not found in sync state`);
    }

    return this.roomToConversation(room, 'dm', contact);
  }

  /**
   * Get family broadcast conversation
   */
  getFamilyConversation(): Conversation | null {
    const room = this.findFamilyRoom();
    if (!room) {
      return null;
    }

    return this.roomToConversation(room, 'family');
  }

  /**
   * Get conversation by room ID (synchronous, for existing rooms only)
   * Returns null if room not found. Does not create rooms.
   */
  getConversationByRoomId(roomId: string): Conversation | null {
    const room = this.syncEngine.getRoom(roomId);
    if (!room) {
      return null;
    }

    // Check if this is the family room
    if (this.isFamilyRoom(roomId)) {
      return this.roomToConversation(room, 'family');
    }

    // Otherwise, treat as DM and find the contact
    const contact = this.getContactForDMRoom(roomId);
    if (!contact) {
      // Room exists but we can't determine the contact
      // This shouldn't happen for valid DM rooms
      return null;
    }

    return this.roomToConversation(room, 'dm', contact);
  }

  /**
   * Get all conversations with unplayed messages
   */
  getUnplayedConversations(): Conversation[] {
    const conversations: Conversation[] = [];

    // Check family conversation
    const familyConvo = this.getFamilyConversation();
    if (familyConvo && familyConvo.unplayedCount > 0) {
      conversations.push(familyConvo);
    }

    // Check DM conversations using DMRoomService
    const contacts = this.getContacts();
    for (const contact of contacts) {
      const primaryRoomId = this.dmRoomService.getDMRoomId(contact.user.id);
      if (primaryRoomId) {
        const room = this.syncEngine.getRoom(primaryRoomId);
        if (room) {
          const convo = this.roomToConversation(room, 'dm', contact);
          if (convo.unplayedCount > 0) {
            conversations.push(convo);
          }
        }
      }
    }

    return conversations;
  }

  // ==========================================================================
  // Voice Message Methods
  // ==========================================================================

  /**
   * Send voice message to contact or family
   *
   * WARNING: For DM targets, this method calls getOrCreateDMRoom() which
   * deterministically selects a room if multiple DM rooms exist with the
   * same contact. This means the message may be sent to a different room
   * than expected if the caller has a specific roomId in mind.
   *
   * If you need to send to a specific roomId, use sendVoiceMessageToRoom()
   * instead.
   *
   * TODO: Decouple DM room creation from message sending. Consider adding
   * a method that takes a roomId directly and validates membership, or
   * refactor to avoid the getOrCreateDMRoom lookup entirely.
   */
  async sendVoiceMessage(
    target: Contact | 'family',
    audio: ArrayBuffer,
    duration: number
  ): Promise<VoiceMessage> {
    // Upload audio to media repo
    const uploadResponse = await this.api.uploadMedia(
      audio,
      'audio/mp4',
      `voice-${Date.now()}.m4a`
    );

    // Determine target room
    let roomId: string;
    if (target === 'family') {
      const familyRoom = this.findFamilyRoom();
      if (!familyRoom) {
        throw new Error('Not in a family');
      }
      roomId = familyRoom.roomId;
    } else {
      roomId = await this.getOrCreateDMRoom(target.user.id);
    }

    // Send m.audio event
    const sendResponse = await this.api.sendMessage(roomId, 'm.room.message', {
      msgtype: 'm.audio',
      body: 'Voice message',
      url: uploadResponse.content_uri,
      info: {
        duration: Math.round(duration * 1000), // Matrix uses milliseconds
        mimetype: 'audio/mp4',
        size: audio.byteLength,
      },
    });

    // Return a VoiceMessage with known values
    // The actual event will arrive via sync and trigger messageReceived
    const currentUser = this.getCurrentUser()!;
    const mxcUrl = uploadResponse.content_uri;
    return {
      id: sendResponse.event_id,
      sender: currentUser,
      audioUrl: this.mxcToHttp(mxcUrl),
      mxcUrl,
      duration,
      timestamp: new Date(),
      isPlayed: false,
      playedBy: [],
    };
  }

  /**
   * Send voice message to a specific room ID
   *
   * This is a lower-level method that sends directly to a specific room,
   * bypassing the getOrCreateDMRoom lookup. Use this when you already know
   * the exact room ID you want to send to.
   *
   * @param roomId - The exact room ID to send the message to
   * @param audio - Audio data as ArrayBuffer
   * @param duration - Duration in seconds
   * @returns VoiceMessage with event ID and metadata
   */
  async sendVoiceMessageToRoom(
    roomId: string,
    audio: ArrayBuffer,
    duration: number
  ): Promise<VoiceMessage> {
    // Upload audio to media repo
    const uploadResponse = await this.api.uploadMedia(
      audio,
      'audio/mp4',
      `voice-${Date.now()}.m4a`
    );

    // Send m.audio event to the specified room
    const sendResponse = await this.api.sendMessage(roomId, 'm.room.message', {
      msgtype: 'm.audio',
      body: 'Voice message',
      url: uploadResponse.content_uri,
      info: {
        duration: Math.round(duration * 1000), // Matrix uses milliseconds
        mimetype: 'audio/mp4',
        size: audio.byteLength,
      },
    });

    // Return a VoiceMessage with known values
    const currentUser = this.getCurrentUser()!;
    const mxcUrl = uploadResponse.content_uri;
    return {
      id: sendResponse.event_id,
      sender: currentUser,
      audioUrl: this.mxcToHttp(mxcUrl),
      mxcUrl,
      duration,
      timestamp: new Date(),
      isPlayed: false,
      playedBy: [],
    };
  }

  /**
   * Mark message as played
   */
  async markAsPlayed(message: VoiceMessage): Promise<void> {
    // Find the room containing this message
    const room = this.findRoomForEvent(message.id);
    if (!room) {
      throw new Error(`Room not found for message ${message.id}`);
    }

    await this.api.sendReadReceipt(room.roomId, message.id);

    // Update local state and emit event
    const updatedMessage = { ...message, isPlayed: true };
    if (!message.playedBy.includes(this.userId!)) {
      updatedMessage.playedBy = [...message.playedBy, this.userId!];
    }
    this.emit('messagePlayed', updatedMessage, room.roomId);
  }

  /**
   * Mark message as played by room and event ID
   * Simpler interface for adapter compatibility - doesn't require full VoiceMessage object
   */
  async markAsPlayedById(roomId: string, eventId: string): Promise<void> {
    this.logger.log(`[WataClient] markAsPlayedById: room=${roomId}, event=${eventId}`);

    await this.api.sendReadReceipt(roomId, eventId);

    // Find the event and emit messagePlayed if found
    const roomState = this.syncEngine.getRoom(roomId);
    if (roomState) {
      const event = roomState.timeline.find((e) => e.event_id === eventId);
      if (event && this.isVoiceMessageEvent(event)) {
        const message = this.eventToVoiceMessage(event, roomState);
        const updatedMessage = { ...message, isPlayed: true };
        if (!message.playedBy.includes(this.userId!)) {
          updatedMessage.playedBy = [...message.playedBy, this.userId!];
        }
        this.emit('messagePlayed', updatedMessage, roomId);
      }
    }
  }

  /**
   * Delete a message (own messages only)
   */
  async deleteMessage(message: VoiceMessage): Promise<void> {
    if (message.sender.id !== this.userId) {
      throw new Error('Can only delete own messages');
    }

    const room = this.findRoomForEvent(message.id);
    if (!room) {
      throw new Error(`Room not found for message ${message.id}`);
    }

    await this.api.redactEvent(room.roomId, message.id, 'Deleted by user');

    this.emit('messageDeleted', message.id, room.roomId);
  }

  /**
   * Get audio data for playback
   */
  async getAudioData(message: VoiceMessage): Promise<ArrayBuffer> {
    return this.api.downloadMedia(message.audioUrl);
  }

  // ==========================================================================
  // Profile Methods
  // ==========================================================================

  /**
   * Update current user's display name
   */
  async setDisplayName(name: string): Promise<void> {
    if (!this.userId) {
      throw new Error('Not logged in');
    }

    await this.api.setDisplayName(this.userId, name);
  }

  /**
   * Update current user's avatar URL
   */
  async setAvatarUrl(avatarUrl: string): Promise<void> {
    if (!this.userId) {
      throw new Error('Not logged in');
    }

    await this.api.setAvatarUrl(this.userId, avatarUrl);
  }

  // ==========================================================================
  // Internal Helper Methods
  // ==========================================================================

  /**
   * Set up listeners for sync engine events
   */
  private setupSyncEngineListeners(): void {
    // Emit connection state changes and flush any newly-classified rooms
    this.syncEngine.on('synced', () => {
      this.emit('connectionStateChanged', 'syncing');

      // Refresh DM room cache from sync state
      this.dmRoomService.refreshFromSync();

      // Flush buffered events for rooms that are now classified as DMs
      this.flushAllClassifiedRooms();
    });

    this.syncEngine.on('error', () => {
      this.emit('connectionStateChanged', 'error');
    });

    // Handle timeline events
    this.syncEngine.on('timelineEvent', (roomId, event) => {
      this.handleTimelineEvent(roomId, event);
    });

    // Handle room updates (membership changes, state changes)
    this.syncEngine.on('roomUpdated', (roomId, room) => {
      this.handleRoomUpdated(roomId, room);
    });

    // Handle read receipts
    this.syncEngine.on('receiptUpdated', (roomId, eventId, userIds) => {
      this.handleReceiptUpdated(roomId, eventId, userIds);
    });

    // Handle membership changes
    this.syncEngine.on('membershipChanged', (roomId, userId, membership) => {
      this.handleMembershipChanged(roomId, userId, membership);
    });

    // Handle account data updates (m.direct, etc.)
    this.syncEngine.on('accountDataUpdated', (type, content) => {
      this.handleAccountDataUpdated(type, content);
    });
  }

  /**
   * Get or create DM room with a contact.
   * Delegates to DMRoomService for all DM room management.
   */
  private async getOrCreateDMRoom(contactUserId: string): Promise<string> {
    return this.dmRoomService.ensureDMRoom(contactUserId);
  }

  /**
   * Convert RoomState to Conversation
   */
  private roomToConversation(
    room: RoomState,
    type: 'dm' | 'family',
    contact?: Contact
  ): Conversation {
    // Get voice messages from timeline
    // Pass room to eventToVoiceMessage since events don't have room_id set
    const messages = room.timeline
      .filter((event) => this.isVoiceMessageEvent(event))
      .map((event) => this.eventToVoiceMessage(event, room));

    // Count unplayed messages
    const unplayedCount = messages.filter((msg) => !msg.isPlayed).length;

    return {
      id: room.roomId,
      type,
      contact,
      messages,
      unplayedCount,
    };
  }

  /**
   * Convert MatrixEvent to VoiceMessage
   * @param event - The Matrix event
   * @param room - The room containing this event (events don't have room_id set)
   */
  private eventToVoiceMessage(event: MatrixEvent, room?: RoomState): VoiceMessage {
    const sender = this.getUserFromEvent(event, room);
    const content = event.content;
    const mxcUrl = content.url || '';
    const audioUrl = this.mxcToHttp(mxcUrl);
    const duration = (content.info?.duration || 0) / 1000; // Convert ms to seconds
    const timestamp = new Date(event.origin_server_ts || 0);

    // Check if current user has played this message
    const playedBy = this.getPlayedByForEvent(event, room);
    const isPlayed = playedBy.includes(this.userId!);

    return {
      id: event.event_id!,
      sender,
      audioUrl,
      mxcUrl,
      duration,
      timestamp,
      isPlayed,
      playedBy,
    };
  }

  /**
   * Get User object from event sender
   * @param event - The Matrix event
   * @param room - The room containing this event (optional, will lookup from event.room_id if not provided)
   */
  private getUserFromEvent(event: MatrixEvent, room?: RoomState): User {
    const userId = event.sender!;

    // Use provided room or try to look up from event.room_id
    const roomState = room || (event.room_id ? this.syncEngine.getRoom(event.room_id) : undefined);
    const member = roomState?.members.get(userId);

    return {
      id: userId,
      displayName: member?.displayName || userId.split(':')[0].substring(1),
      avatarUrl: member?.avatarUrl || null,
    };
  }

  /**
   * Get list of user IDs who have played a message
   * @param event - The Matrix event
   * @param room - The room containing this event (optional, will lookup from event.room_id if not provided)
   */
  private getPlayedByForEvent(event: MatrixEvent, room?: RoomState): string[] {
    // Use provided room or try to look up from event.room_id
    const roomState = room || (event.room_id ? this.syncEngine.getRoom(event.room_id) : undefined);
    if (!roomState) {
      this.logger.warn(`[WataClient] getPlayedByForEvent: no room available for event ${event.event_id}`);
      return [];
    }

    const eventId = event.event_id!;
    const receipts = roomState.readReceipts.get(eventId);

    return receipts ? Array.from(receipts) : [];
  }

  /**
   * Get contacts from a room's membership
   */
  private getContactsFromRoom(room: RoomState): Contact[] {
    return Array.from(room.members.values())
      .filter((member) => member.userId !== this.userId)
      .filter((member) => member.membership === 'join')
      .map((member) => ({
        user: {
          id: member.userId,
          displayName: member.displayName,
          avatarUrl: member.avatarUrl,
        },
      }));
  }

  /**
   * Check if event is a voice message
   */
  private isVoiceMessageEvent(event: MatrixEvent): boolean {
    return (
      event.type === 'm.room.message' &&
      event.content.msgtype === 'm.audio' &&
      !event.unsigned?.redacted_because
    );
  }

  /**
   * Find room containing a specific event
   */
  private findRoomForEvent(eventId: string): RoomState | null {
    const rooms = this.syncEngine.getRooms();
    for (const room of rooms) {
      const found = room.timeline.find((e) => e.event_id === eventId);
      if (found) {
        return room;
      }
    }
    return null;
  }

  /**
   * Wait for a room to appear in sync
   */
  private async waitForRoom(roomId: string, timeoutMs = 5000): Promise<void> {
    const startTime = Date.now();
    while (Date.now() - startTime < timeoutMs) {
      const room = this.syncEngine.getRoom(roomId);
      if (room) {
        return;
      }
      await this.sleep(100);
    }
    throw new Error(`Timeout waiting for room ${roomId}`);
  }

  /**
   * Wait for an event to appear in timeline
   */
  private async waitForEvent(
    roomId: string,
    eventId: string,
    timeoutMs = 5000
  ): Promise<MatrixEvent> {
    const startTime = Date.now();
    while (Date.now() - startTime < timeoutMs) {
      const room = this.syncEngine.getRoom(roomId);
      if (room) {
        const event = room.timeline.find((e) => e.event_id === eventId);
        if (event) {
          return event;
        }
      }
      await this.sleep(100);
    }
    throw new Error(`Timeout waiting for event ${eventId} in room ${roomId}`);
  }

  /**
   * Sleep for specified milliseconds
   */
  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * Convert MXC URL to HTTP URL
   */
  private mxcToHttp(mxcUrl: string): string {
    // Parse mxc://server/mediaId
    const match = mxcUrl.match(/^mxc:\/\/([^/]+)\/(.+)$/);
    if (!match) {
      return mxcUrl; // Already HTTP or invalid
    }

    const [, serverName, mediaId] = match;
    return `${this.api['baseUrl']}/_matrix/client/v1/media/download/${encodeURIComponent(serverName)}/${encodeURIComponent(mediaId)}`;
  }

  // ==========================================================================
  // Event Handlers
  // ==========================================================================

  /**
   * Handle timeline events from sync engine
   */
  private handleTimelineEvent(roomId: string, event: MatrixEvent): void {
    // Handle voice messages
    if (this.isVoiceMessageEvent(event)) {
      this.logger.log(`[WataClient] Voice message received in room ${roomId} from ${event.sender}`);

      const room = this.syncEngine.getRoom(roomId);
      if (!room) {
        this.logger.warn(`[WataClient] Room ${roomId} not found in sync state`);
        return;
      }

      // Check if this is the family room (always process immediately)
      if (this.isFamilyRoom(roomId)) {
        this.logger.log(`[WataClient] Message is in family room`);
        const message = this.eventToVoiceMessage(event, room);
        const conversation = this.roomToConversation(room, 'family');
        this.emit('messageReceived', message, conversation);
        return;
      }

      // Check if this is a known DM room
      const contact = this.getContactForDMRoom(roomId);
      if (contact) {
        this.logger.log(`[WataClient] Message is DM from ${contact.user.displayName}`);
        const message = this.eventToVoiceMessage(event, room);
        const conversation = this.roomToConversation(room, 'dm', contact);
        this.emit('messageReceived', message, conversation);
        return;
      }

      // Unknown room type - buffer for later processing
      // Will be retried until DM info arrives or event ages out
      this.logger.log(`[WataClient] Room ${roomId} not yet classified as DM, buffering event`);
      const buffered = this.eventBuffer.buffer(roomId, event);
      if (!buffered) {
        this.logger.warn(`[WataClient] Buffer full for room ${roomId}, dropping event`);
      }
    }

    // Handle redacted events
    if (event.unsigned?.redacted_because) {
      this.emit('messageDeleted', event.event_id!, roomId);
    }
  }

  /**
   * Handle room updates
   */
  private handleRoomUpdated(roomId: string, room: RoomState): void {
    // If family room updated, emit family/contacts events
    if (this.isFamilyRoom(roomId)) {
      const family = this.getFamily();
      if (family) {
        this.emit('familyUpdated', family);
        this.emit('contactsUpdated', family.members);
      }
    }
  }

  /**
   * Handle receipt updates
   */
  private handleReceiptUpdated(
    roomId: string,
    eventId: string,
    userIds: Set<string>
  ): void {
    this.logger.log(`[WataClient] Receipt update for event ${eventId} in room ${roomId}, users: ${Array.from(userIds).join(', ')}`);

    const room = this.syncEngine.getRoom(roomId);
    if (!room) {
      this.logger.warn(`[WataClient] Room ${roomId} not found for receipt update`);
      return;
    }

    // Verify the receipt is stored in room.readReceipts
    const storedReceipts = room.readReceipts.get(eventId);
    this.logger.log(`[WataClient] Room readReceipts for ${eventId.slice(-12)}: ${storedReceipts ? Array.from(storedReceipts).join(', ') : 'NONE'}`);
    this.logger.log(`[WataClient] Room has ${room.readReceipts.size} total receipt entries`);

    const event = room.timeline.find((e) => e.event_id === eventId);
    if (!event) {
      this.logger.warn(`[WataClient] Event ${eventId} not found in room timeline`);
      return;
    }

    if (!this.isVoiceMessageEvent(event)) {
      // Not a voice message, ignore silently
      return;
    }

    this.logger.log(`[WataClient] Emitting messagePlayed for ${eventId} in room ${roomId}`);
    const message = this.eventToVoiceMessage(event);
    this.emit('messagePlayed', message, roomId);
  }

  /**
   * Handle membership changes
   */
  private async handleMembershipChanged(
    roomId: string,
    userId: string,
    membership: string
  ): Promise<void> {
    // Auto-join invites (trusted family environment)
    if (userId === this.userId && membership === 'invite') {
      try {
        await this.api.joinRoom(roomId);

        // After joining, refresh DM room service from sync state
        // This will detect and track new DM rooms
        await this.waitForRoom(roomId, 3000);
        this.dmRoomService.refreshFromSync();

        // Flush any buffered events for this room now that it's classified
        this.flushBufferedEvents(roomId);
      } catch (error) {
        this.logger.error(`Failed to auto-join room ${roomId}: ${error}`);
      }
    }
  }

  /**
   * Handle account data updates (m.direct, etc.)
   */
  private handleAccountDataUpdated(
    type: string,
    content: Record<string, any>
  ): void {
    if (type === 'm.direct') {
      // Get list of rooms before update to detect new ones
      const knownRooms = new Set(this.dmRoomService.getAllDMRoomIds(Object.keys(content).join(',')));

      // Delegate m.direct handling to DMRoomService
      this.dmRoomService.handleMDirectUpdate(content as Record<string, string[]>);

      // Flush buffered events for any newly-classified DM rooms
      for (const roomIds of Object.values(content)) {
        for (const roomId of roomIds as string[]) {
          if (this.eventBuffer.hasBufferedEvents(roomId)) {
            this.flushBufferedEvents(roomId);
          }
        }
      }
    }
  }

  /**
   * Get contact for a DM room.
   * Delegates to DMRoomService.
   */
  getContactForDMRoom(roomId: string): Contact | null {
    return this.dmRoomService.getContactForRoom(roomId);
  }

  /**
   * Check if a room is a known DM room.
   */
  isDMRoom(roomId: string): boolean {
    return this.dmRoomService.isDMRoom(roomId);
  }

  /**
   * Get the primary DM room ID for a contact (synchronous lookup).
   * Returns null if no DM room exists in cache.
   */
  getDMRoomId(contactUserId: string): string | null {
    return this.dmRoomService.getDMRoomId(contactUserId);
  }

  /**
   * Get all known DM room IDs for a contact.
   * Useful for message consolidation across duplicate rooms.
   */
  getAllDMRoomIds(contactUserId: string): string[] {
    return this.dmRoomService.getAllDMRoomIds(contactUserId);
  }

  /**
   * Get all known DM room IDs (across all contacts).
   */
  getAllKnownDMRoomIds(): string[] {
    return this.dmRoomService.getAllKnownDMRoomIds();
  }

  /**
   * Get contact user ID for a DM room.
   */
  getContactUserIdForRoom(roomId: string): string | null {
    return this.dmRoomService.getContactUserIdForRoom(roomId);
  }

  // ==========================================================================
  // Event Buffer Management
  // ==========================================================================

  /**
   * Flush buffered events for a specific room.
   * Called when a room is classified as a DM.
   */
  private flushBufferedEvents(roomId: string): void {
    if (!this.eventBuffer.hasBufferedEvents(roomId)) {
      return;
    }

    const count = this.eventBuffer.flush(roomId, (roomId, event) => {
      // Re-process the event now that we know the room type
      this.handleTimelineEvent(roomId, event);
    });

    if (count > 0) {
      this.logger.log(`[WataClient] Flushed ${count} buffered events for room ${roomId}`);
    }
  }

  /**
   * Flush buffered events for all rooms that are now classified.
   * Called after each sync cycle and by the retry timer.
   */
  private flushAllClassifiedRooms(): void {
    const bufferedRoomIds = this.eventBuffer.getBufferedRoomIds();
    for (const roomId of bufferedRoomIds) {
      // Only flush if DMRoomService explicitly knows this room
      if (this.dmRoomService.isDMRoom(roomId)) {
        this.flushBufferedEvents(roomId);
      }
    }
  }

  /**
   * Get statistics about the event buffer (for debugging).
   */
  getEventBufferStats(): { roomCount: number; eventCount: number; oldestEventAge: number } {
    return this.eventBuffer.getStats();
  }
}
