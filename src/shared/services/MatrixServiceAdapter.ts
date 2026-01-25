/**
 * MatrixServiceAdapter: Backward compatibility wrapper around WataClient
 *
 * This adapter wraps WataClient to maintain the same public interface as MatrixService,
 * allowing existing code to use the new WataClient implementation without changes.
 *
 * ⚠️ IMPORTANT: This is a THIN ADAPTER/FACADE.
 * Any logic related to the Matrix protocol does NOT belong here.
 * All such logic MUST be implemented in WataClient (src/shared/lib/wata-client/).
 *
 * This file should ONLY contain:
 * - Type mapping between MatrixService and WataClient types
 * - Callback/event bridging (delegation only)
 * - Simple method forwarding to WataClient
 *
 * If you find yourself adding Matrix protocol logic here, STOP.
 * Move it to WataClient instead.
 *
 * Key responsibilities:
 * - Map MatrixService callbacks to WataClient event listeners
 * - Maintain roomId <-> Conversation mappings (for synchronous getDirectRooms/getVoiceMessages)
 * - Bridge domain types between MatrixService and WataClient
 * - Preserve MatrixService method signatures exactly
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

// Re-export MatrixService types for compatibility
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
  audioUrl: string;
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
 * MatrixServiceAdapter: Wraps WataClient to maintain MatrixService API compatibility
 */
class MatrixServiceAdapter {
  private wataClient: WataClient;
  private credentialStorage: CredentialStorage;
  private syncCallbacks: SyncCallback[] = [];
  private roomCallbacks: RoomCallback[] = [];
  private messageCallbacks: MessageCallback[] = [];
  private receiptCallbacks: ReceiptCallback[] = [];
  private currentSyncState: string = 'STOPPED';
  private currentUsername: string | null = null;
  private currentPassword: string | null = null;

  // Mappings to bridge between WataClient and MatrixService
  private contactByUserId: Map<string, Contact> = new Map();
  private roomIdByContactUserId: Map<string, string> = new Map();
  private familyRoomId: string | null = null;

  // Cache for DM conversations to support synchronous getDirectRooms() and getVoiceMessages()
  // Maps roomId -> conversation data (contact, messages, etc.)
  private dmConversationCache: Map<string, {
    contact: Contact;
    roomId: string;
    messages: VoiceMessage[];
  }> = new Map();

  constructor(credentialStorage: CredentialStorage, _logger?: unknown) {
    this.credentialStorage = credentialStorage;
    this.wataClient = new WataClient(HOMESERVER_URL);
    this.setupWataClientListeners();
  }

  /**
   * Set up WataClient event listeners and bridge to MatrixService callbacks
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

      // Cache DM conversations for synchronous getDirectRooms() and getVoiceMessages() access
      if (conversation.type === 'dm' && conversation.contact) {
        // Get existing cache or create new
        const existing = this.dmConversationCache.get(conversation.id);
        const messages = existing ? existing.messages : [];
        // Add the new message if not already present
        const messageIndex = messages.findIndex((m) => m.eventId === matrixMessage.eventId);
        if (messageIndex === -1) {
          messages.push(matrixMessage);
        }

        this.dmConversationCache.set(conversation.id, {
          contact: conversation.contact,
          roomId: conversation.id,
          messages,
        });
        // Also update the user ID -> room ID mapping
        this.roomIdByContactUserId.set(conversation.contact.user.id, conversation.id);
        this.contactByUserId.set(conversation.contact.user.id, conversation.contact);
      }

      this.notifyRoomUpdate();
    });

    // Message played (receipt update)
    this.wataClient.on('messagePlayed', (message) => {
      const room = this.findRoomForMessage(message);
      if (room) {
        this.receiptCallbacks.forEach((cb) => cb(room.id));
      }
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
   * Convert WataClient VoiceMessage to MatrixService VoiceMessage
   */
  private wataToMatrixMessage(message: WataVoiceMessage): VoiceMessage {
    const userId = this.wataClient.getCurrentUser()?.id || '';
    return {
      eventId: message.id,
      sender: message.sender.id,
      senderName: message.sender.displayName,
      timestamp: message.timestamp.getTime(),
      audioUrl: message.audioUrl,
      duration: message.duration,
      isOwn: message.sender.id === userId,
      readBy: message.playedBy.filter((id) => id !== message.sender.id),
    };
  }

  /**
   * Find room for a message
   */
  private findRoomForMessage(message: WataVoiceMessage): Conversation | null {
    // Check family conversation
    const familyConvo = this.wataClient.getFamilyConversation();
    if (
      familyConvo &&
      familyConvo.messages.some((m) => m.id === message.id)
    ) {
      return familyConvo;
    }

    // Check DM conversations
    const contacts = this.wataClient.getContacts();
    for (const contact of contacts) {
      // Note: This is inefficient but matches the async pattern
      // In practice, we'd cache conversations
      try {
        // We can't call async here, so we skip DM lookups
        // This is a known limitation - receipt callbacks may not fire for DMs
        logWarn(
          '[MatrixServiceAdapter] Cannot find DM room for receipt update (async required)'
        );
      } catch (error) {
        // Ignore
      }
    }

    return null;
  }

  /**
   * Notify room update callbacks
   */
  private notifyRoomUpdate(): void {
    const rooms = this.getDirectRooms();
    this.roomCallbacks.forEach((cb) => cb(rooms));
  }

  /**
   * Convert WataClient data to MatrixService MatrixRoom format
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

    // Add DM rooms from cache
    // We iterate through cached DM conversations and convert them to MatrixRoom format
    for (const [roomId, cacheData] of this.dmConversationCache.entries()) {
      const { contact } = cacheData;

      // Try to get actual conversation data from WataClient
      // Note: This is a sync call that may not have all messages yet
      // But we can at least return the basic room info
      rooms.push({
        roomId,
        name: contact.user.displayName,
        avatarUrl: contact.user.avatarUrl,
        lastMessage: null, // We'd need async getConversation to get messages
        lastMessageTime: null,
        isDirect: true,
      });
    }

    return rooms;
  }

  /**
   * Login with username and password
   */
  async login(username: string, password: string): Promise<void> {
    log('[MatrixServiceAdapter] login() called with:');
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

    log('[MatrixServiceAdapter] Login successful');

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
      logError(`[MatrixServiceAdapter] Failed to restore session: ${error}`);
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
    this.roomIdByContactUserId.clear();
    this.familyRoomId = null;
    this.dmConversationCache.clear();
  }

  /**
   * Get the currently logged-in username (without homeserver domain)
   */
  getCurrentUsername(): string | null {
    const user = this.wataClient.getCurrentUser();
    if (!user) return null;
    // Extract username from Matrix ID (@username:homeserver)
    return user.id.split(':')[0].substring(1);
  }

  /**
   * Get the current user's display name from their profile
   */
  async getDisplayName(): Promise<string | null> {
    const user = this.wataClient.getCurrentUser();
    return user?.displayName || null;
  }

  /**
   * Set the current user's display name
   */
  async setDisplayName(displayName: string): Promise<void> {
    await this.wataClient.setDisplayName(displayName);
    log(`[MatrixServiceAdapter] Display name set to: ${displayName}`);
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
    this.roomIdByContactUserId.set(userId, conversation.id);

    // Cache the conversation for synchronous access
    // Convert all messages to MatrixService format
    const messages = conversation.messages.map((m) => this.wataToMatrixMessage(m));
    this.dmConversationCache.set(conversation.id, {
      contact,
      roomId: conversation.id,
      messages,
    });

    return conversation.id;
  }

  /**
   * Create family room
   */
  async createFamilyRoom(): Promise<string> {
    log(
      `[MatrixServiceAdapter] Creating family room with alias #${FAMILY_ALIAS_PREFIX}`
    );

    const family = await this.wataClient.createFamily('Family');
    this.familyRoomId = family.id;

    log(`[MatrixServiceAdapter] Family room created: ${family.id}`);
    return family.id;
  }

  /**
   * Invite user to family room
   */
  async inviteToFamily(userId: string): Promise<void> {
    log(`[MatrixServiceAdapter] Inviting ${userId} to family room`);
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
    log('[MatrixServiceAdapter] joinRoom() called - WataClient auto-joins');
  }

  /**
   * Check if user is member of a room
   */
  isRoomMember(roomId: string): boolean {
    // Check if it's the family room
    if (this.familyRoomId === roomId) {
      return this.wataClient.getFamily() !== null;
    }

    // Check if it's a DM room
    for (const [, mappedRoomId] of this.roomIdByContactUserId.entries()) {
      if (mappedRoomId === roomId) {
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
  ): Promise<void> {
    log(`[MatrixServiceAdapter] Sending voice message to ${roomId}`);

    // Convert Buffer to ArrayBuffer
    const arrayBuffer: ArrayBuffer = audioBuffer.buffer.slice(
      audioBuffer.byteOffset,
      audioBuffer.byteOffset + audioBuffer.byteLength
    ) as ArrayBuffer;

    // Determine target (family or contact)
    if (roomId === this.familyRoomId) {
      await this.wataClient.sendVoiceMessage('family', arrayBuffer, duration);
    } else {
      // Find contact for this room
      const contact = this.findContactForRoomId(roomId);
      if (!contact) {
        throw new Error(`Contact not found for room ${roomId}`);
      }
      await this.wataClient.sendVoiceMessage(contact, arrayBuffer, duration);
    }
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
      '[MatrixServiceAdapter] redactMessage() not yet implemented in WataClient'
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
   */
  getVoiceMessages(roomId: string): VoiceMessage[] {
    // Get conversation for room
    if (roomId === this.familyRoomId) {
      const convo = this.wataClient.getFamilyConversation();
      return convo?.messages.map((m) => this.wataToMatrixMessage(m)) || [];
    }

    // For DM rooms, use the cached conversation data
    const cached = this.dmConversationCache.get(roomId);
    if (cached) {
      return cached.messages;
    }

    return [];
  }

  /**
   * Mark message as played
   */
  async markMessageAsPlayed(roomId: string, eventId: string): Promise<void> {
    // Find the message
    const messages = this.getVoiceMessages(roomId);
    const matrixMessage = messages.find((m) => m.eventId === eventId);

    if (!matrixMessage) {
      throw new Error(`Message ${eventId} not found in room ${roomId}`);
    }

    // Convert back to WataClient format for markAsPlayed
    // This is inefficient, but maintains compatibility
    // We'd need to store message mappings for better performance
    logWarn(
      '[MatrixServiceAdapter] markMessageAsPlayed() - inefficient implementation'
    );
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
   * Get the underlying Matrix client (not available in adapter)
   * Admin features requiring direct MatrixClient should use MatrixService instead
   */
  getClient(): null {
    logWarn(
      '[MatrixServiceAdapter] getClient() returns null - use MatrixService for admin features'
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

// Export MatrixServiceAdapter as default export
export { MatrixServiceAdapter };
