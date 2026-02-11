/**
 * Domain types for the WataClient library
 *
 * This module defines the core types used by WataClient to represent
 * walkie-talkie concepts (families, contacts, voice messages) independent
 * of the underlying Matrix protocol.
 */

// ============================================================================
// Identity & User Types
// ============================================================================

/**
 * A user in the system (identified by Matrix user ID)
 */
export interface User {
  /** Matrix user ID (e.g., @alice:server.local) */
  id: string;
  /** Display name */
  displayName: string;
  /** Avatar URL (MXC or HTTP URL), null if no avatar */
  avatarUrl: string | null;
}

// ============================================================================
// Family & Contact Types
// ============================================================================

/**
 * A family member (contact)
 */
export interface Contact {
  /** User information */
  user: User;
  /** Online status (future: presence) */
  isOnline?: boolean;
}

/**
 * The family group (maps to family room in Matrix)
 */
export interface Family {
  /** Room ID */
  id: string;
  /** Family name */
  name: string;
  /** List of family members (excluding self) */
  members: Contact[];
}

// ============================================================================
// Conversation Types
// ============================================================================

/**
 * A conversation (1:1 DM or family broadcast)
 */
export interface Conversation {
  /** Room ID */
  id: string;
  /** Conversation type */
  type: 'dm' | 'family';
  /** Contact for DM conversations (undefined for family) */
  contact?: Contact;
  /** Voice messages in this conversation */
  messages: VoiceMessage[];
  /** Number of unplayed messages */
  unplayedCount: number;
}

// ============================================================================
// Message Types
// ============================================================================

/**
 * A voice message
 */
export interface VoiceMessage {
  /** Event ID */
  id: string;
  /** Message sender */
  sender: User;
  /** HTTP download URL for playback */
  audioUrl: string;
  /** Original MXC URL from the Matrix event (for downloadMedia API) */
  mxcUrl: string;
  /** Duration in seconds */
  duration: number;
  /** Message timestamp */
  timestamp: Date;
  /** Has current user played this message */
  isPlayed: boolean;
  /** User IDs who have played this message */
  playedBy: string[];
}

// ============================================================================
// Connection State
// ============================================================================

/**
 * Client connection/sync state
 */
export type ConnectionState =
  | 'connecting' // Initial connection in progress
  | 'connected' // Connected, not yet synced
  | 'syncing' // Actively syncing
  | 'error' // Connection error
  | 'offline'; // Disconnected

// ============================================================================
// Event Handler Types
// ============================================================================

/**
 * Handler for connection state changes
 */
export type ConnectionStateChangedHandler = (state: ConnectionState) => void;

/**
 * Handler for family updates
 */
export type FamilyUpdatedHandler = (family: Family) => void;

/**
 * Handler for contacts list updates
 */
export type ContactsUpdatedHandler = (contacts: Contact[]) => void;

/**
 * Handler for new message received
 */
export type MessageReceivedHandler = (
  message: VoiceMessage,
  conversation: Conversation,
) => void;

/**
 * Handler for message deletion
 */
export type MessageDeletedHandler = (
  messageId: string,
  conversationId: string,
) => void;

/**
 * Handler for message played status update
 * Includes roomId to avoid needing to search for the room
 */
export type MessagePlayedHandler = (
  message: VoiceMessage,
  roomId: string,
) => void;

// ============================================================================
// Event Map
// ============================================================================

/**
 * Mapping of event names to their handler signatures
 */
export interface WataClientEvents {
  connectionStateChanged: ConnectionStateChangedHandler;
  familyUpdated: FamilyUpdatedHandler;
  contactsUpdated: ContactsUpdatedHandler;
  messageReceived: MessageReceivedHandler;
  messageDeleted: MessageDeletedHandler;
  messagePlayed: MessagePlayedHandler;
}

/**
 * Valid event names
 */
export type WataClientEventName = keyof WataClientEvents;

/**
 * Generic event handler (any event type)
 */
export type WataClientEventHandler = WataClientEvents[WataClientEventName];

// ============================================================================
// Logging
// ============================================================================

/**
 * Logger interface for WataClient
 * Platform-agnostic - each platform provides its own implementation
 */
export interface Logger {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}
