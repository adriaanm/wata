/**
 * WataService: Service interface around WataClient
 *
 * This service wraps WataClient to provide a clean, idiomatic interface
 * for Wata applications to interact with the Matrix protocol.
 *
 *  IMPORTANT: This is a THIN SERVICE/FACADE.
 * Any logic related to the Matrix protocol does NOT belong here.
 * All such logic MUST be implemented in WataClient (src/shared/lib/wata-client/).
 *
 * This file should ONLY contain:
 * - Type mapping between WataService and WataClient types
 * - Callback/event bridging (delegation only)
 * - Simple method forwarding to WataClient
 *
 * If you find yourself adding Matrix protocol logic here, STOP.
 * Move it to WataClient instead.
 *
 * Key responsibilities:
 * - Map WataService callbacks to WataClient event listeners
 * - Maintain roomId <-> Conversation mappings (for synchronous getDirectRooms/getVoiceMessages)
 * - Bridge domain types between WataService and WataClient
 * - Preserve public API for backward compatibility
 */

import { Buffer } from 'buffer';
import { MATRIX_CONFIG } from '@shared/config/matrix';
import type { StoredCredentials } from '@shared/lib/matrix-auth';
import type { CredentialStorage } from '@shared/services/CredentialStorage';
import { WataClient } from '@shared/lib/wata-client/wata-client';
import type {
  Contact,
  Conversation,
  VoiceMessage as WataVoiceMessage,
  User,
  Family,
} from '@shared/lib/wata-client/types';

// Export public types
export interface Logger {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
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
  /** HTTP download URL for playback */
  audioUrl: string;
  /** Original MXC URL from the Matrix event (for downloadMedia API) */
  mxcUrl: string;
  duration: number;
  isOwn: boolean;
  readBy?: string[];
}

type SyncCallback = (state: string) => void;
type RoomCallback = (rooms: MatrixRoom[]) => void;
type MessageCallback = (roomId: string, message: VoiceMessage) => void;
type ReceiptCallback = (roomId: string) => void;

// Optional logger interface that platforms can provide
let logger: Logger | undefined;

export function setLogger(l: Logger | undefined): void {
  logger = l;
}

// Helper to log to platform logger (no-op if not set)
const log = (message: string): void => {
  logger?.log(message);
};

const logWarn = (message: string): void => {
  logger?.warn(message);
};

const logError = (message: string): void => {
  logger?.error(message);
};

// Configurable for testing - defaults to config
let HOMESERVER_URL = MATRIX_CONFIG.homeserverUrl;
let FAMILY_ALIAS_PREFIX = 'family';

// Allow overriding homeserver for tests
export function setHomeserverUrl(url: string): void {
  HOMESERVER_URL = url;
}

export function getHomeserverUrl(): string {
  return HOMESERVER_URL;
}

// Allow overriding family alias prefix for tests
export function setFamilyAliasPrefix(prefix: string): void {
  FAMILY_ALIAS_PREFIX = prefix;
}

export function getFamilyAliasPrefix(): string {
  return FAMILY_ALIAS_PREFIX;
}

/**
 * WataService: Wraps WataClient to provide a clean service interface
 */
class WataService {
  private wataClient: WataClient;
  private credentialStorage: CredentialStorage;
  private syncCallbacks: SyncCallback[] = [];
  private roomCallbacks: RoomCallback[] = [];
  private messageCallbacks: MessageCallback[] = [];
  private receiptCallbacks: ReceiptCallback[] = [];
  private currentSyncState: string = 'STOPPED';
  private currentUsername: string | null = null;
  private currentPassword: string | null = null;

  // Mappings to bridge between WataClient and WataService
  private contactByUserId: Map<string, Contact> = new Map();
  /** contactUserId -> PRIMARY roomId (deterministically selected, one per contact) */
  private primaryRoomIdByContactUserId: Map<string, string> = new Map();
  /** contactUserId -> Set of ALL roomIds (may be multiple due to race conditions) */
  private roomIdsByContactUserId: Map<string, Set<string>> = new Map();
  private familyRoomId: string | null = null;

  // Cache for DM room -> contact mappings (for getDirectRooms())
  // Messages are fetched fresh from WataClient via getConversationByRoomId()
  private dmRoomContacts: Map<string, Contact> = new Map();

  constructor(credentialStorage: CredentialStorage, externalLogger?: Logger) {
    this.credentialStorage = credentialStorage;
    // Pass external logger to WataClient (falls back to no-op if not provided)
    this.wataClient = new WataClient(HOMESERVER_URL, externalLogger);
    this.setupWataClientListeners();
  }

  /**
   * Set up WataClient event listeners and bridge to WataService callbacks
   */
  private setupWataClientListeners(): void {
    // Connection state changes
    this.wataClient.on('connectionStateChanged', (state) => {
      // Map WataClient connection states to sync states
      let syncState: string;
      switch (state) {
        case 'connecting':
          syncState = 'SYNCING';
          break;
        case 'syncing':
          syncState = 'SYNCING';
          break;
        case 'error':
          syncState = 'ERROR';
          break;
        case 'offline':
          syncState = 'STOPPED';
          break;
        default:
          syncState = 'SYNCING';
      }
      this.currentSyncState = syncState;
      this.syncCallbacks.forEach((cb) => cb(syncState));
    });

    // Message received
    this.wataClient.on('messageReceived', (message, conversation) => {
      const roomId = conversation.id;
      const matrixMessage = this.wataToMatrixMessage(message);
      this.messageCallbacks.forEach((cb) => cb(roomId, matrixMessage));

      // Update DM room -> contact mapping for getDirectRooms()
      if (conversation.type === 'dm' && conversation.contact) {
        const contactId = conversation.contact.user.id;

        // Track PRIMARY room for this contact
        // The conversation.id from WataClient is PRIMARY room (deterministically selected)
        const existingPrimary = this.primaryRoomIdByContactUserId.get(contactId);
        if (existingPrimary && existingPrimary !== conversation.id) {
          log(`[WataService] Contact ${contactId}: primary room changed from ${existingPrimary.slice(-12)} to ${conversation.id.slice(-12)}`);
        }
        this.primaryRoomIdByContactUserId.set(contactId, conversation.id);

        // Add to set of ALL room IDs for this contact
        if (!this.roomIdsByContactUserId.has(contactId)) {
          this.roomIdsByContactUserId.set(contactId, new Set());
        }
        const roomIds = this.roomIdsByContactUserId.get(contactId)!;
        if (!roomIds.has(conversation.id)) {
          if (roomIds.size > 0) {
            const existing = Array.from(roomIds).join(', ');
            log(`[WataService] Contact ${contactId} now has multiple rooms: ${existing}, ${conversation.id}`);
          }
          roomIds.add(conversation.id);
        }
        this.dmRoomContacts.set(conversation.id, conversation.contact);
        this.contactByUserId.set(contactId, conversation.contact);
      }

      this.notifyRoomUpdate();
    });

    // Message played (receipt update)
    // roomId is now passed directly from WataClient, no need to search
    this.wataClient.on('messagePlayed', (message, roomId) => {
      log(`[WataService] messagePlayed event for message ${message.id} in room ${roomId}`);
      log(`[WataService] Notifying ${this.receiptCallbacks.length} callbacks`);
      this.receiptCallbacks.forEach((cb) => cb(roomId));
    });

    // Family/contacts updated
    this.wataClient.on('familyUpdated', (family) => {
      this.familyRoomId = family.id;
      this.updateContactMappings(family.members);
      this.notifyRoomUpdate();
    });

    this.wataClient.on('contactsUpdated', (contacts) => {
      this.updateContactMappings(contacts);
      this.notifyRoomUpdate();
    });
  }

  /**
   * Update contact and room mappings
   */
  private updateContactMappings(contacts: Contact[]): void {
    for (const contact of contacts) {
      this.contactByUserId.set(contact.user.id, contact);
    }
  }

  /**
   * Convert WataClient VoiceMessage to WataService VoiceMessage
   */
  private wataToMatrixMessage(message: WataVoiceMessage): VoiceMessage {
    const userId = this.wataClient.getCurrentUser()?.id || '';
    return {
      eventId: message.id,
      sender: message.sender.id,
      senderName: message.sender.displayName,
      timestamp: message.timestamp.getTime(),
      audioUrl: message.audioUrl,
      mxcUrl: message.mxcUrl,
      duration: message.duration * 1000, // Convert WataClient seconds to WataService milliseconds
      isOwn: message.sender.id === userId,
      readBy: message.playedBy.filter((id) => id !== message.sender.id),
    };
  }

  /**
   * Notify room update callbacks
   */
  private notifyRoomUpdate(): void {
    const rooms = this.getDirectRooms();
    this.roomCallbacks.forEach((cb) => cb(rooms));
  }

  /**
   * Convert WataClient data to WataService MatrixRoom format
   * Returns PRIMARY room for each contact (deterministically selected by WataClient)
   */
  getDirectRooms(): MatrixRoom[] {
    const rooms: MatrixRoom[] = [];

    // Add family room if exists
    const familyConvo = this.wataClient.getFamilyConversation();
    if (familyConvo) {
      const lastMessage = familyConvo.messages[familyConvo.messages.length - 1];
      rooms.push({
        roomId: familyConvo.id,
        name: 'Family',
        avatarUrl: null,
        lastMessage: lastMessage ? 'Voice message' : null,
        lastMessageTime: lastMessage ? lastMessage.timestamp.getTime() : null,
        isDirect: false,
      });
    }

    // Collect all unique room IDs from both sources
    const allRoomIds = new Set<string>();

    // Add primary rooms
    for (const roomId of this.primaryRoomIdByContactUserId.values()) {
      allRoomIds.add(roomId);
    }

    // Add all rooms from roomIdsByContactUserId
    for (const roomIdSet of this.roomIdsByContactUserId.values()) {
      for (const roomId of roomIdSet) {
        allRoomIds.add(roomId);
      }
    }

    // Build rooms list by querying WataClient for each room
    // This ensures we get fresh contact info even for rooms that were just joined
    for (const roomId of allRoomIds) {
      // Check if this is the family room (already added)
      if (this.familyRoomId === roomId) {
        continue;
      }

      // Try to get contact for this DM room
      const contact = this.wataClient.getContactForDMRoom(roomId);

      if (contact) {
        const convo = this.wataClient.getConversationByRoomId(roomId);
        const messageCount = convo?.messages.length ?? 0;

        log(`[WataService] getDirectRooms: DM with ${contact.user.id} -> room ${roomId} (${messageCount} msgs)`);
        rooms.push({
          roomId,
          name: contact.user.displayName,
          avatarUrl: contact.user.avatarUrl,
          lastMessage: null,
          lastMessageTime: null,
          isDirect: true,
        });
      } else {
        // If getContactForDMRoom returns null, this might not be a DM room
        // or we don't have enough info yet. Skip it.
        log(`[WataService] getDirectRooms: Skipping room ${roomId} (no contact found)`);
      }
    }

    log(`[WataService] getDirectRooms returning ${rooms.length} rooms`);
    return rooms;
  }

  /**
   * Login with username and password
   */
  async login(username: string, password: string): Promise<void> {
    log('[WataService] login() called with:');
    log(`  username: ${username}, homeserver: ${HOMESERVER_URL}`);

    this.currentUsername = username;
    this.currentPassword = password;

    await this.wataClient.login(username, password);
    await this.wataClient.connect();

    // Store credentials
    const userId = this.wataClient.getCurrentUser()?.id || '';
    const credentials: StoredCredentials = {
      accessToken: 'wata-client-session', // WataClient manages tokens internally
      userId,
      deviceId: 'wata-client-device',
      homeserverUrl: HOMESERVER_URL,
    };

    await this.credentialStorage.storeSession(username, credentials);

    log('[WataService] Login successful');

    // Update sync state to PREPARED
    this.currentSyncState = 'PREPARED';
    this.syncCallbacks.forEach((cb) => cb('PREPARED'));
  }

  /**
   * Auto-login using credentials from config
   */
  async autoLogin(username?: string): Promise<void> {
    const user = username || MATRIX_CONFIG.username;
    const password = MATRIX_CONFIG.password;
    await this.login(user, password);
  }

  /**
   * Restore session from stored credentials
   */
  async restoreSession(username?: string): Promise<boolean> {
    try {
      const user = username || MATRIX_CONFIG.username;
      const stored = await this.credentialStorage.retrieveSession(user);

      if (!stored) {
        return false;
      }

      // For WataClient, we need to login with password (no session restoration yet)
      // Use config password for prototype
      this.currentUsername = user;
      this.currentPassword = MATRIX_CONFIG.password;

      await this.wataClient.login(user, this.currentPassword);
      await this.wataClient.connect();

      this.currentSyncState = 'PREPARED';
      this.syncCallbacks.forEach((cb) => cb('PREPARED'));

      return true;
    } catch (error) {
      logError(`[WataService] Failed to restore session: ${error}`);
      return false;
    }
  }

  /**
   * Logout and clear session
   */
  async logout(): Promise<void> {
    await this.wataClient.logout();
    await this.credentialStorage.clear();
    this.currentUsername = null;
    this.currentPassword = null;
    this.contactByUserId.clear();
    this.primaryRoomIdByContactUserId.clear();
    this.roomIdsByContactUserId.clear();
    this.familyRoomId = null;
    this.dmRoomContacts.clear();
  }

  /**
   * Get currently logged-in username (without homeserver domain)
   */
  getCurrentUsername(): string | null {
    const user = this.wataClient.getCurrentUser();
    if (!user) return null;
    // Extract username from Matrix ID (@username:homeserver)
    return user.id.split(':')[0].substring(1);
  }

  /**
   * Get current user's display name from their profile
   */
  async getDisplayName(): Promise<string | null> {
    const user = this.wataClient.getCurrentUser();
    return user?.displayName || null;
  }

  /**
   * Set current user's display name
   */
  async setDisplayName(displayName: string): Promise<void> {
    await this.wataClient.setDisplayName(displayName);
    log(`[WataService] Display name set to: ${displayName}`);
  }

  /**
   * Get family members
   */
  async getFamilyMembers(includeSelf = false): Promise<FamilyMember[]> {
    const contacts = this.wataClient.getContacts();
    const members: FamilyMember[] = contacts.map((contact) => ({
      userId: contact.user.id,
      displayName: contact.user.displayName,
      avatarUrl: contact.user.avatarUrl,
    }));

    if (includeSelf) {
      const currentUser = this.wataClient.getCurrentUser();
      if (currentUser) {
        members.push({
          userId: currentUser.id,
          displayName: currentUser.displayName,
          avatarUrl: currentUser.avatarUrl,
        });
      }
    }

    return members;
  }

  /**
   * Get or create DM room with a user
   */
  async getOrCreateDmRoom(userId: string): Promise<string> {
    // Check if we have this contact
    let contact = this.contactByUserId.get(userId);

    if (!contact) {
      // Create a stub contact
      contact = {
        user: {
          id: userId,
          displayName: userId.split(':')[0].substring(1),
          avatarUrl: null,
        },
      };
      this.contactByUserId.set(userId, contact);
    }

    // Get or create conversation
    const conversation = await this.wataClient.getConversation(contact);

    // Track PRIMARY room for this contact
    // WataClient.getConversation() returns primary room (deterministically selected)
    this.primaryRoomIdByContactUserId.set(userId, conversation.id);

    // Add to set of ALL room IDs for this contact (may be multiple due to races)
    if (!this.roomIdsByContactUserId.has(userId)) {
      this.roomIdsByContactUserId.set(userId, new Set());
    }
    this.roomIdsByContactUserId.get(userId)!.add(conversation.id);

    // Update room -> contact mapping for getDirectRooms()
    this.dmRoomContacts.set(conversation.id, contact);

    return conversation.id;
  }

  /**
   * Create family room
   */
  async createFamilyRoom(): Promise<string> {
    log(
      `[WataService] Creating family room with alias #${FAMILY_ALIAS_PREFIX}`
    );

    const family = await this.wataClient.createFamily('Family');
    this.familyRoomId = family.id;

    log(`[WataService] Family room created: ${family.id}`);
    return family.id;
  }

  /**
   * Invite user to family room
   */
  async inviteToFamily(userId: string): Promise<void> {
    log(`[WataService] Inviting ${userId} to family room`);
    await this.wataClient.inviteToFamily(userId);
  }

  /**
   * Get family room
   */
  async getFamilyRoom(): Promise<{ roomId: string } | null> {
    const family = this.wataClient.getFamily();
    return family ? { roomId: family.id } : null;
  }

  /**
   * Get family room ID
   */
  async getFamilyRoomId(): Promise<string | null> {
    const family = this.wataClient.getFamily();
    return family?.id || null;
  }

  /**
   * Get family room ID from alias (even if not joined)
   */
  async getFamilyRoomIdFromAlias(): Promise<string | null> {
    // WataClient doesn't expose alias resolution without joining
    // Return current family room ID if available
    return this.familyRoomId;
  }

  /**
   * Join room by ID or alias
   */
  async joinRoom(_roomIdOrAlias: string): Promise<void> {
    // WataClient auto-joins rooms, so this is a no-op
    log('[WataService] joinRoom() called - WataClient auto-joins');
  }

  /**
   * Check if user is member of a room
   */
  isRoomMember(roomId: string): boolean {
    // Check if it's family room
    if (this.familyRoomId === roomId) {
      return this.wataClient.getFamily() !== null;
    }

    // Check if it's a DM room (may have multiple room IDs per contact)
    for (const [, roomIdSet] of this.roomIdsByContactUserId.entries()) {
      if (roomIdSet.has(roomId)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Send voice message
   */
  async sendVoiceMessage(
    roomId: string,
    audioBuffer: Buffer,
    _mimeType: string,
    duration: number,
    _size: number
  ): Promise<string> {
    log(`[WataService] Sending voice message to ${roomId}`);

    // Convert Buffer to ArrayBuffer
    const arrayBuffer: ArrayBuffer = audioBuffer.buffer.slice(
      audioBuffer.byteOffset,
      audioBuffer.byteOffset + audioBuffer.byteLength
    ) as ArrayBuffer;

    // Convert milliseconds to seconds (WataClient expects seconds)
    const durationSeconds = duration / 1000;

    // Send message to specific room ID
    // Use sendVoiceMessageToRoom to ensure message goes to the exact room specified,
    // not a deterministically-selected room from getOrCreateDMRoom
    const sentMessage: WataVoiceMessage = roomId === this.familyRoomId
      ? await this.wataClient.sendVoiceMessage('family', arrayBuffer, durationSeconds)
      : await this.wataClient.sendVoiceMessageToRoom(roomId, arrayBuffer, durationSeconds);

    return sentMessage.id; // WataClient uses 'id' for event ID
  }

  /**
   * Find contact for a room ID
   * Delegates to WataClient which handles both creator and recipient sides
   */
  private findContactForRoomId(roomId: string): Contact | null {
    return this.wataClient.getContactForDMRoom(roomId);
  }

  /**
   * Redact (delete) a message
   */
  async redactMessage(
    _roomId: string,
    _eventId: string,
    _reason?: string
  ): Promise<void> {
    // TODO: Implement via WataClient when deleteMessage is exposed
    logWarn(
      '[WataService] redactMessage() not yet implemented in WataClient'
    );
  }

  /**
   * Redact multiple messages
   */
  async redactMessages(
    roomId: string,
    eventIds: string[],
    reason?: string
  ): Promise<void> {
    for (const eventId of eventIds) {
      await this.redactMessage(roomId, eventId, reason);
    }
  }

  /**
   * Get voice messages for a room
   * Always fetches fresh data from WataClient to ensure readBy status is current
   */
  getVoiceMessages(roomId: string): VoiceMessage[] {
    // Use synchronous getConversationByRoomId to get fresh data
    // This ensures readBy/playedBy reflects the latest receipt updates
    const convo = this.wataClient.getConversationByRoomId(roomId);
    if (convo) {
      return convo.messages.map((m) => this.wataToMatrixMessage(m));
    }

    return [];
  }

  /**
   * Mark message as played
   */
  async markMessageAsPlayed(roomId: string, eventId: string): Promise<void> {
    log(`[WataService] markMessageAsPlayed: room=${roomId}, event=${eventId}`);
    await this.wataClient.markAsPlayedById(roomId, eventId);
  }

  /**
   * Get list of users who have read/played a message
   */
  getMessageReadBy(roomId: string, eventId: string): string[] {
    const messages = this.getVoiceMessages(roomId);
    const message = messages.find((m) => m.eventId === eventId);
    return message?.readBy || [];
  }

  // ==========================================================================
  // Callback Registration
  // ==========================================================================

  onSyncStateChange(callback: SyncCallback): () => void {
    this.syncCallbacks.push(callback);
    return () => {
      this.syncCallbacks = this.syncCallbacks.filter((cb) => cb !== callback);
    };
  }

  onRoomUpdate(callback: RoomCallback): () => void {
    this.roomCallbacks.push(callback);
    return () => {
      this.roomCallbacks = this.roomCallbacks.filter((cb) => cb !== callback);
    };
  }

  onNewVoiceMessage(callback: MessageCallback): () => void {
    this.messageCallbacks.push(callback);
    return () => {
      this.messageCallbacks = this.messageCallbacks.filter(
        (cb) => cb !== callback
      );
    };
  }

  onReceiptUpdate(callback: ReceiptCallback): () => void {
    this.receiptCallbacks.push(callback);
    return () => {
      this.receiptCallbacks = this.receiptCallbacks.filter(
        (cb) => cb !== callback
      );
    };
  }

  // ==========================================================================
  // User Info
  // ==========================================================================

  getUserId(): string | null {
    return this.wataClient.getCurrentUser()?.id || null;
  }

  isLoggedIn(): boolean {
    return this.wataClient.getCurrentUser() !== null;
  }

  /**
   * Get current access token for authenticated media downloads
   */
  getAccessToken(): string | null {
    return this.wataClient.getAccessToken();
  }

  // ==========================================================================
  // Test Interface
  // ==========================================================================

  /**
   * Get underlying Matrix client (not available in WataService)
   * WataService does not expose the underlying client.
   */
  getClient(): null {
    logWarn(
      '[WataService] getClient() returns null - direct client access not available'
    );
    return null;
  }

  /**
   * Get current sync state
   */
  getSyncState(): string {
    return this.currentSyncState;
  }

  /**
   * Wait for sync to complete
   */
  async waitForSync(timeoutMs = 10000): Promise<void> {
    return new Promise((resolve, reject) => {
      let resolved = false;
      const timeout = setTimeout(() => {
        if (!resolved) {
          reject(new Error(`Sync timeout after ${timeoutMs}ms`));
        }
      }, timeoutMs);

      const cleanup = () => {
        clearTimeout(timeout);
        unsubscribe();
      };

      const onSync = (state: string) => {
        if (state === 'PREPARED' || state === 'SYNCING') {
          if (!resolved) {
            resolved = true;
            cleanup();
            resolve();
          }
        }
      };

      // Subscribe first to avoid race condition
      const unsubscribe = this.onSyncStateChange(onSync);

      // Check if already synced after subscribing
      const currentState = this.currentSyncState;
      if (currentState === 'PREPARED' || currentState === 'SYNCING') {
        if (!resolved) {
          resolved = true;
          cleanup();
          resolve();
        }
      }
    });
  }

  /**
   * Wait for a specific voice message
   */
  async waitForMessage(
    roomId: string,
    predicate: (msg: VoiceMessage) => boolean,
    timeoutMs = 10000
  ): Promise<VoiceMessage> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(
          new Error(
            `Message not received in room ${roomId} after ${timeoutMs}ms`
          )
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
   * Get message count for a room
   */
  getMessageCount(roomId: string): number {
    return this.getVoiceMessages(roomId).length;
  }

  /**
   * Cleanup all callbacks
   */
  cleanup(): void {
    this.syncCallbacks = [];
    this.roomCallbacks = [];
    this.messageCallbacks = [];
    this.receiptCallbacks = [];
  }
}

// Export WataService
export { WataService };
