/**
 * WataClient: High-level domain interface for Wata walkie-talkie features
 *
 * This is the main client library that frontends interact with. It wraps
 * MatrixApi and SyncEngine to provide a domain-specific API (families,
 * contacts, voice messages) rather than exposing Matrix protocol details.
 *
 * See docs/planning/client-lib.md for design rationale.
 */

import { MatrixApi } from './matrix-api';
import { SyncEngine } from './sync-engine';
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
} from './types';

// ============================================================================
// WataClient Implementation
// ============================================================================

export class WataClient {
  private api: MatrixApi;
  private syncEngine!: SyncEngine;
  private userId: string | null = null;
  private familyRoomId: string | null = null;
  private dmRooms: Map<string, string> = new Map(); // contactUserId -> roomId
  private eventHandlers: Map<WataClientEventName, Set<Function>> = new Map();
  private isConnected = false;

  constructor(homeserverUrl: string) {
    this.api = new MatrixApi(homeserverUrl);
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
          console.error(`Error in ${event} handler:`, error);
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
    const response = await this.api.login(username, password, 'Wata Client');
    this.userId = response.user_id;

    // Create sync engine and set user ID
    this.syncEngine = new SyncEngine(this.api);
    this.syncEngine.setUserId(this.userId);

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

    // Start sync loop (includes initial sync)
    // Room state and account data will be populated during sync
    await this.syncEngine.start();

    this.isConnected = true;
  }

  /**
   * Stop sync and cleanup
   */
  async disconnect(): Promise<void> {
    if (!this.isConnected) {
      return;
    }

    await this.syncEngine.stop();
    this.isConnected = false;
    this.emit('connectionStateChanged', 'offline');
  }

  /**
   * Logout and invalidate session
   */
  async logout(): Promise<void> {
    if (this.isConnected) {
      await this.disconnect();
    }

    await this.api.logout();

    // Clear state
    this.userId = null;
    this.familyRoomId = null;
    this.dmRooms.clear();
    this.syncEngine.clear();
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
   * Get current access token for authenticated media downloads
   */
  getAccessToken(): string | null {
    return this.api.getAccessToken();
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
   * Get all conversations with unplayed messages
   */
  getUnplayedConversations(): Conversation[] {
    const conversations: Conversation[] = [];

    // Check family conversation
    const familyConvo = this.getFamilyConversation();
    if (familyConvo && familyConvo.unplayedCount > 0) {
      conversations.push(familyConvo);
    }

    // Check DM conversations
    const contacts = this.getContacts();
    for (const contact of contacts) {
      const roomId = this.dmRooms.get(contact.user.id);
      if (roomId) {
        const room = this.syncEngine.getRoom(roomId);
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
      },
    });

    // Optimistically add the event to the timeline immediately
    // This ensures sent messages are available right away without waiting for sync
    const optimisticEvent: MatrixEvent = {
      event_id: sendResponse.event_id,
      room_id: roomId,
      sender: this.userId!,
      type: 'm.room.message',
      content: {
        msgtype: 'm.audio',
        body: 'Voice message',
        url: uploadResponse.content_uri,
        info: {
          duration: Math.round(duration * 1000),
          mimetype: 'audio/mp4',
        },
      },
      origin_server_ts: Date.now(),
    };

    // Add to room timeline
    const room = this.syncEngine.getRoom(roomId);
    if (room) {
      room.timeline.push(optimisticEvent);

      // Emit messageReceived for own messages too
      // This ensures UI updates immediately when sending
      const message = this.eventToVoiceMessage(optimisticEvent);
      if (this.isFamilyRoom(roomId)) {
        const conversation = this.roomToConversation(room, 'family');
        this.emit('messageReceived', message, conversation);
      } else {
        const contact = this.getContactForDMRoom(roomId);
        if (contact) {
          const conversation = this.roomToConversation(room, 'dm', contact);
          this.emit('messageReceived', message, conversation);
        }
      }
      return message;
    }

    return this.eventToVoiceMessage(optimisticEvent);
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
    this.emit('messagePlayed', updatedMessage);
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

  // ==========================================================================
  // Internal Helper Methods
  // ==========================================================================

  /**
   * Set up listeners for sync engine events
   */
  private setupSyncEngineListeners(): void {
    // Emit connection state changes
    this.syncEngine.on('synced', () => {
      this.emit('connectionStateChanged', 'syncing');
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
   * Get or create DM room with a contact
   */
  private async getOrCreateDMRoom(contactUserId: string): Promise<string> {
    // First, check if DM room already exists in m.direct
    const existingRoomId = this.dmRooms.get(contactUserId);
    if (existingRoomId) {
      // Verify we're still a member of this room
      const room = this.syncEngine.getRoom(existingRoomId);
      if (room && room.members.get(this.userId!)?.membership === 'join') {
        // Verify the target user is also in the room
        const targetMember = room.members.get(contactUserId);
        if (targetMember && targetMember.membership === 'join') {
          return existingRoomId;
        }
      }
    }

    // Second, check for existing rooms where this user invited us or we created (may not be in m.direct yet)
    const rooms = this.syncEngine.getRooms();
    for (const room of rooms) {
      // Skip if not joined
      if (room.members.get(this.userId!)?.membership !== 'join') {
        continue;
      }

      // Check if this is a 2-person room (likely DM)
      const joinedMembers = Array.from(room.members.values()).filter(
        (m) => m.membership === 'join'
      );
      if (joinedMembers.length !== 2) {
        continue;
      }

      // Check if this is a DM with the target user
      const hasTargetUser = joinedMembers.some((m) => m.userId === contactUserId);
      if (!hasTargetUser) {
        continue;
      }

      // Check if this is a DM room (via is_direct flag in membership event or creation event)
      let isDirectRoom = false;

      // Check membership events for is_direct flag
      for (const event of room.timeline) {
        if (event.type === 'm.room.member' && event.state_key === this.userId) {
          if (event.content?.is_direct === true) {
            isDirectRoom = true;
            break;
          }
        }
      }

      // If still unsure, check the room creation event for is_direct flag
      if (!isDirectRoom) {
        for (const event of room.timeline) {
          if (event.type === 'm.room.create') {
            if (event.content?.is_direct === true) {
              isDirectRoom = true;
              break;
            }
          }
        }
      }

      if (isDirectRoom) {
        // Found a DM room with this user that wasn't in m.direct
        // Update m.direct and return this room
        const roomId = room.roomId;
        await this.updateDMRoomData(contactUserId, roomId);
        this.dmRooms.set(contactUserId, roomId);
        return roomId;
      }
    }

    // Create new DM room
    const response = await this.api.createRoom({
      is_direct: true,
      invite: [contactUserId],
      preset: 'trusted_private_chat',
      visibility: 'private',
    });

    const roomId = response.room_id;

    // Update m.direct account data
    await this.updateDMRoomData(contactUserId, roomId);

    // Store in local map
    this.dmRooms.set(contactUserId, roomId);

    return roomId;
  }

  /**
   * Update m.direct account data with new DM room
   */
  private async updateDMRoomData(
    contactUserId: string,
    roomId: string
  ): Promise<void> {
    // Get current m.direct data
    let directData: Record<string, string[]> = {};
    try {
      directData = await this.api.getAccountData(this.userId!, 'm.direct');
    } catch {
      // No existing data
    }

    // Add new DM room
    if (!directData[contactUserId]) {
      directData[contactUserId] = [];
    }
    if (!directData[contactUserId].includes(roomId)) {
      directData[contactUserId].push(roomId);
    }

    // Save updated data
    await this.api.setAccountData(this.userId!, 'm.direct', directData);
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
    const messages = room.timeline
      .filter((event) => this.isVoiceMessageEvent(event))
      .map((event) => this.eventToVoiceMessage(event));

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
   */
  private eventToVoiceMessage(event: MatrixEvent): VoiceMessage {
    const sender = this.getUserFromEvent(event);
    const content = event.content;
    const audioUrl = this.mxcToHttp(content.url || '');
    const duration = (content.info?.duration || 0) / 1000; // Convert ms to seconds
    const timestamp = new Date(event.origin_server_ts || 0);

    // Check if current user has played this message
    const playedBy = this.getPlayedByForEvent(event);
    const isPlayed = playedBy.includes(this.userId!);

    return {
      id: event.event_id!,
      sender,
      audioUrl,
      duration,
      timestamp,
      isPlayed,
      playedBy,
    };
  }

  /**
   * Get User object from event sender
   */
  private getUserFromEvent(event: MatrixEvent): User {
    const userId = event.sender!;

    // Try to get display name from room membership
    const room = this.syncEngine.getRoom(event.room_id!);
    const member = room?.members.get(userId);

    return {
      id: userId,
      displayName: member?.displayName || userId.split(':')[0].substring(1),
      avatarUrl: member?.avatarUrl || null,
    };
  }

  /**
   * Get list of user IDs who have played a message
   */
  private getPlayedByForEvent(event: MatrixEvent): string[] {
    const room = this.syncEngine.getRoom(event.room_id!);
    if (!room) {
      return [];
    }

    const receipts = room.readReceipts.get(event.event_id!);
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
      const message = this.eventToVoiceMessage(event);

      const room = this.syncEngine.getRoom(roomId);
      if (!room) return;

      // Determine conversation type by checking canonical alias
      let conversation: Conversation;
      if (this.isFamilyRoom(roomId)) {
        conversation = this.roomToConversation(room, 'family');
      } else {
        // Find the contact for this DM
        const contact = this.getContactForDMRoom(roomId);
        if (!contact) return;
        conversation = this.roomToConversation(room, 'dm', contact);
      }

      this.emit('messageReceived', message, conversation);
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
    const room = this.syncEngine.getRoom(roomId);
    if (!room) return;

    const event = room.timeline.find((e) => e.event_id === eventId);
    if (!event || !this.isVoiceMessageEvent(event)) return;

    const message = this.eventToVoiceMessage(event);
    this.emit('messagePlayed', message);
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

        // After joining, check if this is a DM room and update m.direct
        // We need to wait for the room to appear in sync
        await this.waitForRoom(roomId, 3000);

        const room = this.syncEngine.getRoom(roomId);
        if (room) {
          // Check if this is a 2-person room (likely DM)
          const joinedMembers = Array.from(room.members.values()).filter(
            (m) => m.membership === 'join'
          );

          if (joinedMembers.length === 2) {
            // Find the other member
            const otherMember = joinedMembers.find((m) => m.userId !== this.userId);
            if (otherMember) {
              // Check if this room was created as a DM (is_direct flag)
              let isDirectRoom = false;

              // Check membership events for is_direct flag
              for (const event of room.timeline) {
                if (event.type === 'm.room.member' && event.state_key === this.userId) {
                  if (event.content?.is_direct === true) {
                    isDirectRoom = true;
                    break;
                  }
                }
              }

              // If still unsure, check the room creation event for is_direct flag
              if (!isDirectRoom) {
                for (const event of room.timeline) {
                  if (event.type === 'm.room.create') {
                    if (event.content?.is_direct === true) {
                      isDirectRoom = true;
                      break;
                    }
                  }
                }
              }

              // If it's a DM room, update m.direct
              if (isDirectRoom) {
                await this.updateDMRoomData(otherMember.userId, roomId);
                this.dmRooms.set(otherMember.userId, roomId);
              }
            }
          }
        }
      } catch (error) {
        console.error(`Failed to auto-join room ${roomId}:`, error);
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
      // Update local dmRooms map from m.direct account data
      // m.direct format: { "@user:server": ["!roomId"] }
      for (const [userId, roomIds] of Object.entries(content)) {
        if (Array.isArray(roomIds) && roomIds.length > 0) {
          this.dmRooms.set(userId, roomIds[0]);
        }
      }
    }
  }

  /**
   * Get contact for a DM room
   * @param roomId - Room ID to look up
   * @returns Contact if this is a DM room, null otherwise
   *
   * This handles both sides of DM room creation:
   * - Creator side: m.direct account data has the mapping
   * - Recipient side: Falls back to room membership inference
   */
  getContactForDMRoom(roomId: string): Contact | null {
    // First, try to find from m.direct account data mapping
    for (const [userId, mappedRoomId] of this.dmRooms.entries()) {
      if (mappedRoomId === roomId) {
        const room = this.syncEngine.getRoom(roomId);
        const member = room?.members.get(userId);
        if (member) {
          return {
            user: {
              id: userId,
              displayName: member.displayName,
              avatarUrl: member.avatarUrl,
            },
          };
        }
      }
    }

    // Fallback: If m.direct doesn't have the mapping (recipient-side issue),
    // infer the contact from room membership.
    // A DM room has exactly 2 members: the current user and the contact.
    const room = this.syncEngine.getRoom(roomId);
    if (room) {
      for (const [userId, member] of room.members.entries()) {
        // Skip current user and any invited/left users
        if (userId !== this.userId && member.membership === 'join') {
          // Verify this is a DM room by checking member count
          const joinCount = Array.from(room.members.values()).filter(
            m => m.membership === 'join'
          ).length;
          if (joinCount === 2) {
            return {
              user: {
                id: userId,
                displayName: member.displayName,
                avatarUrl: member.avatarUrl,
              },
            };
          }
        }
      }
    }

    return null;
  }
}
