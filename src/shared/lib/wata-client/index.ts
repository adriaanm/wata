/**
 * WataClient - A minimal Matrix client for walkie-talkie functionality
 *
 * This library provides a domain-focused API for the Wata app,
 * abstracting Matrix protocol details into walkie-talkie concepts
 * like families, contacts, and voice messages.
 *
 * @example
 * ```typescript
 * import { WataClient } from '@shared/lib/wata-client';
 *
 * const client = new WataClient('https://matrix.example.com');
 * await client.login('alice', 'password');
 * await client.connect();
 *
 * const contacts = client.getContacts();
 * await client.sendVoiceMessage(contacts[0], audioData, 5.2);
 *
 * client.on('messageReceived', (message, conversation) => {
 *   console.log(`New message from ${message.sender.displayName}`);
 * });
 * ```
 */

// Main client class
export { WataClient } from './wata-client';

// Domain types
export type {
  User,
  Contact,
  Family,
  Conversation,
  VoiceMessage,
  ConnectionState,
  // Event handler types
  ConnectionStateChangedHandler,
  FamilyUpdatedHandler,
  ContactsUpdatedHandler,
  MessageReceivedHandler,
  MessageDeletedHandler,
  MessagePlayedHandler,
  WataClientEvents,
  WataClientEventName,
  WataClientEventHandler,
  // Logging
  Logger,
} from './types';
