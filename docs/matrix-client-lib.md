# WataClient Library Reference

WataClient is a high-level domain interface for Wata walkie-talkie features. It wraps the Matrix Client-Server API to provide a domain-specific API (families, contacts, voice messages) rather than exposing Matrix protocol details.

See also: [Matrix Client-Server API for Wata](wata-matrix-spec.md) for the underlying protocol specification.

## Table of Contents

- [Quick Start](#quick-start)
- [Type Definitions](#type-definitions)
- [API Reference](#api-reference)
  - [Lifecycle Methods](#lifecycle-methods)
  - [Family Methods](#family-methods)
  - [Conversation Methods](#conversation-methods)
  - [Voice Message Methods](#voice-message-methods)
  - [Profile Methods](#profile-methods)
  - [Event Handling](#event-handling)
- [Matrix Protocol Mapping](#matrix-protocol-mapping)
- [Implementation Notes](#implementation-notes)
- [Planning & Design](#planning--design)

## Quick Start

```typescript
import { WataClient } from '@shared/lib/wata-client';

// Create client instance
const client = new WataClient('https://matrix.example.com', logger);

// Login and connect
await client.login('alice', 'password123');
await client.connect();

// Get family and contacts
const family = client.getFamily();
const contacts = client.getContacts();

// Send voice message
await client.sendVoiceMessage(contacts[0], audioBuffer, 5.2);

// Listen for new messages
client.on('messageReceived', (message, conversation) => {
  console.log(`New message from ${message.sender.displayName}`);
});
```

## Type Definitions

### User

A user in the system (identified by Matrix user ID).

```typescript
interface User {
  id: string;           // Matrix user ID (e.g., @alice:server.local)
  displayName: string;  // Display name
  avatarUrl: string | null;  // Avatar URL (null if no avatar)
}
```

### Contact

A family member (contact).

```typescript
interface Contact {
  user: User;           // User information
  isOnline?: boolean;   // Online status (future: presence)
}
```

### Family

The family group (maps to family room in Matrix).

```typescript
interface Family {
  id: string;           // Room ID
  name: string;         // Family name
  members: Contact[];   // List of family members (excluding self)
}
```

### Conversation

A conversation (1:1 DM or family broadcast).

```typescript
interface Conversation {
  id: string;               // Room ID
  type: 'dm' | 'family';    // Conversation type
  contact?: Contact;        // Contact for DM conversations
  messages: VoiceMessage[]; // Voice messages in this conversation
  unplayedCount: number;    // Number of unplayed messages
}
```

### VoiceMessage

A voice message.

```typescript
interface VoiceMessage {
  id: string;           // Event ID
  sender: User;         // Message sender
  audioUrl: string;     // Audio URL (MXC or HTTP URL)
  duration: number;     // Duration in seconds
  timestamp: Date;      // Message timestamp
  isPlayed: boolean;    // Has current user played this message
  playedBy: string[];   // User IDs who have played this message
}
```

### ConnectionState

Client connection/sync state.

```typescript
type ConnectionState =
  | 'connecting'  // Initial connection in progress
  | 'connected'   // Connected, not yet synced
  | 'syncing'     // Actively syncing
  | 'error'       // Connection error
  | 'offline';    // Disconnected
```

### Logger

Platform-agnostic logger interface.

```typescript
interface Logger {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}
```

## API Reference

### Lifecycle Methods

#### `constructor(homeserverUrl: string, logger?: Logger)`

Creates a new WataClient instance.

```typescript
const client = new WataClient('https://matrix.example.com', logger);
```

#### `login(username: string, password: string): Promise<void>`

Login with username and password. See [POST /login](wata-matrix-spec.md#post-login).

```typescript
await client.login('alice', 'password123');
```

#### `connect(): Promise<void>`

Start real-time sync. See [GET /sync](wata-matrix-spec.md#get-sync).

```typescript
await client.connect();
```

#### `disconnect(): Promise<void>`

Stop sync and cleanup.

```typescript
await client.disconnect();
```

#### `logout(): Promise<void>`

Logout and invalidate session. See [POST /logout](wata-matrix-spec.md#post-logout).

```typescript
await client.logout();
```

#### `getCurrentUser(): User | null`

Get the current user.

```typescript
const user = client.getCurrentUser();
console.log(user.displayName); // "alice"
```

#### `getAccessToken(): string | null`

Get the current access token (for authenticated media downloads).

```typescript
const token = client.getAccessToken();
```

#### `getConnectionState(): ConnectionState`

Get the current connection state.

```typescript
const state = client.getConnectionState(); // 'syncing'
```

### Family Methods

#### `getFamily(): Family | null`

Get the family (null if not in a family). The family room is identified by the `#family:server` canonical alias.

```typescript
const family = client.getFamily();
if (family) {
  console.log(`Family: ${family.name}`);
}
```

#### `getContacts(): Contact[]`

Get all contacts (family members excluding self).

```typescript
const contacts = client.getContacts();
contacts.forEach(contact => {
  console.log(contact.user.displayName);
});
```

#### `createFamily(name: string): Promise<Family>`

Create family room with `#family` alias. See [POST /createRoom](wata-matrix-spec.md#post-createroom).

```typescript
const family = await client.createFamily('My Family');
```

#### `inviteToFamily(userId: string): Promise<void>`

Invite user to family room. See [POST /rooms/{roomId}/invite](wata-matrix-spec.md#post-roomsroomidinvite).

```typescript
await client.inviteToFamily('@bob:server.local');
```

### Conversation Methods

#### `getConversation(contact: Contact): Promise<Conversation>`

Get conversation with a contact (creates DM if needed).

```typescript
const bob = contacts.find(c => c.user.displayName === 'Bob');
const conversation = await client.getConversation(bob);
console.log(`Unplayed: ${conversation.unplayedCount}`);
```

#### `getFamilyConversation(): Conversation | null`

Get family broadcast conversation.

```typescript
const familyConvo = client.getFamilyConversation();
```

#### `getConversationByRoomId(roomId: string): Conversation | null`

Get conversation by room ID (synchronous, for existing rooms only).

```typescript
const conversation = client.getConversationByRoomId('!abc123:server.local');
```

#### `getUnplayedConversations(): Conversation[]`

Get all conversations with unplayed messages.

```typescript
const unplayed = client.getUnplayedConversations();
```

### Voice Message Methods

#### `sendVoiceMessage(target: Contact | 'family', audio: ArrayBuffer, duration: number): Promise<VoiceMessage>`

Send voice message to contact or family. See [POST /media/v3/upload](wata-matrix-spec.md#post-mediav3upload) and [PUT /rooms/{roomId}/send/{eventType}/{txnId}](wata-matrix-spec.md#put-roomsroomidsendeventtypetxnid).

```typescript
const message = await client.sendVoiceMessage(
  contacts[0],
  audioBuffer,
  5.2  // duration in seconds
);
```

#### `markAsPlayed(message: VoiceMessage): Promise<void>`

Mark message as played. See [POST /rooms/{roomId}/receipt/{receiptType}/{eventId}](wata-matrix-spec.md#post-roomsroomidreceiptreceipttypeeventid).

```typescript
await client.markAsPlayed(message);
```

#### `markAsPlayedById(roomId: string, eventId: string): Promise<void>`

Mark message as played by room and event ID (simpler interface).

```typescript
await client.markAsPlayedById('!abc123:server.local', '$event123');
```

#### `deleteMessage(message: VoiceMessage): Promise<void>`

Delete a message (own messages only). See [PUT /rooms/{roomId}/redact/{eventId}/{txnId}](wata-matrix-spec.md#put-roomsroomidredacteventidtxnid).

```typescript
await client.deleteMessage(message);
```

#### `getAudioData(message: VoiceMessage): Promise<ArrayBuffer>`

Get audio data for playback. See [GET /media/v1/download/{serverName}/{mediaId}](wata-matrix-spec.md#get-mediav1downloadservernamemediaid).

```typescript
const audio = await client.getAudioData(message);
```

### Profile Methods

#### `setDisplayName(name: string): Promise<void>`

Update current user's display name. See [PUT /profile/{userId}/{keyName}](wata-matrix-spec.md#put-profileuseridkeyname).

```typescript
await client.setDisplayName('Alice');
```

### Event Handling

#### `on<K extends WataClientEventName>(event: K, handler: WataClientEvents[K]): void`

Subscribe to events.

```typescript
client.on('messageReceived', (message, conversation) => {
  console.log(`New message from ${message.sender.displayName}`);
});
```

#### `off<K extends WataClientEventName>(event: K, handler: WataClientEvents[K]): void`

Unsubscribe from events.

```typescript
client.off('messageReceived', handler);
```

#### Available Events

| Event | Handler Signature | Description |
|-------|-------------------|-------------|
| `connectionStateChanged` | `(state: ConnectionState) => void` | Connection state changed |
| `familyUpdated` | `(family: Family) => void` | Family room updated |
| `contactsUpdated` | `(contacts: Contact[]) => void` | Contacts list updated |
| `messageReceived` | `(message: VoiceMessage, conversation: Conversation) => void` | New voice message received |
| `messageDeleted` | `(messageId: string, conversationId: string) => void` | Message deleted |
| `messagePlayed` | `(message: VoiceMessage, roomId: string) => void` | Message marked as played |

## Matrix Protocol Mapping

| Wata Concept | Matrix Concept | Related Spec |
|--------------|----------------|--------------|
| Family | Room with `#family:server` alias | [m.room.canonical_alias](wata-matrix-spec.md#mroomcanonical_alias) |
| Contact | Room member (excluding self) | [m.room.member](wata-matrix-spec.md#mroommember) |
| 1:1 conversation | DM room via `m.direct` | [m.direct](wata-matrix-spec.md#mdirect) |
| Voice message | `m.audio` event | [m.room.message](wata-matrix-spec.md#mroommessage) |
| Message played | Read receipt | [m.receipt](wata-matrix-spec.md#mreceipt) |
| Delete message | Redaction | [PUT /rooms/{roomId}/redact](wata-matrix-spec.md#put-roomsroomidredacteventidtxnid) |
| Add family member | Room invite + auto-join | [POST /rooms/{roomId}/invite](wata-matrix-spec.md#post-roomsroomidinvite) |

## Implementation Notes

### DM Room Idempotency

The `getOrCreateDMRoom()` method uses a deterministic selection function (oldest by creation timestamp) to handle race conditions when both users create DM rooms simultaneously. See [Direct Messaging](wata-matrix-spec.md#direct-messaging) for protocol limitations.

### Auto-Join for DM Invites

The client automatically joins DM invites (trusted family environment). After joining, it checks if the room is a DM room (via `is_direct` flag) and updates `m.direct` account data accordingly.

### Optimistic Message Updates

When sending voice messages, the client immediately adds the message to the local timeline (optimistic update) and emits a `messageReceived` event for the sender, ensuring immediate UI feedback without waiting for sync.

### MXC URL Conversion

The client automatically converts MXC URLs (`mxc://server/mediaId`) to HTTP URLs for download using the configured homeserver base URL.

---

## Planning & Design

This section contains planning and design documentation from the original feasibility study.

### Current SDK Status

WataClient is implemented as a custom Matrix client library that replaces `matrix-js-sdk` for Wata's walkie-talkie use case. The implementation is complete and handles:

- Authentication (login, logout, whoami)
- Room management (create, join, invite, alias resolution)
- Messaging (send voice messages, redaction)
- Receipts (read receipts for played status)
- Media repository (upload/download)
- Profile (display name)
- Account data (m.direct management)
- Synchronization (/sync polling loop)

### Domain Model Mapping

| Wata Concept | Matrix Concept | Implementation |
|--------------|----------------|----------------|
| Family | Room with `#family:server` alias | `findFamilyRoom()` scans for canonical alias |
| Contact | Room member (excluding self) | `getContactsFromRoom()` filters joined members |
| 1:1 conversation | DM room via `m.direct` | `getOrCreateDMRoom()` handles DM creation |
| Voice message | `m.audio` event | `sendVoiceMessage()` uploads and sends |
| Message played | Read receipt | `markAsPlayed()` sends m.read receipt |
| Delete message | Redaction | `deleteMessage()` calls redact API |
| Add family member | Room invite + auto-join | `inviteToFamily()` + auto-join handler |

### Matrix Endpoints Used

| Category | Endpoints |
|----------|-----------|
| Authentication | [POST /login](wata-matrix-spec.md#post-login), [POST /logout](wata-matrix-spec.md#post-logout), [GET /account/whoami](wata-matrix-spec.md#get-accountwhoami) |
| Sync | [GET /sync](wata-matrix-spec.md#get-sync) |
| Rooms | [POST /createRoom](wata-matrix-spec.md#post-createroom), [POST /rooms/{roomId}/join](wata-matrix-spec.md#post-roomsroomidjoin), [POST /join/{roomIdOrAlias}](wata-matrix-spec.md#post-joinroomidoralias), [POST /rooms/{roomId}/invite](wata-matrix-spec.md#post-roomsroomidinvite), [GET /directory/room/{roomAlias}](wata-matrix-spec.md#get-directoryroomroomalias) |
| Messages | [PUT /rooms/{roomId}/send/{eventType}/{txnId}](wata-matrix-spec.md#put-roomsroomidsendeventtypetxnid), [PUT /rooms/{roomId}/redact/{eventId}/{txnId}](wata-matrix-spec.md#put-roomsroomidredacteventidtxnid) |
| Receipts | [POST /rooms/{roomId}/receipt/{receiptType}/{eventId}](wata-matrix-spec.md#post-roomsroomidreceiptreceipttypeeventid) |
| Media | [POST /media/v3/upload](wata-matrix-spec.md#post-mediav3upload), [GET /media/v1/download/{serverName}/{mediaId}](wata-matrix-spec.md#get-mediav1downloadservernamemediaid) |
| Profile | [GET /profile/{userId}](wata-matrix-spec.md#get-profileuserid), [PUT /profile/{userId}/displayname](wata-matrix-spec.md#put-profileuseridkeyname) |
| Account Data | [GET /user/{userId}/account_data/{type}](wata-matrix-spec.md#get-useruseridaccount_datatype), [PUT /user/{userId}/account_data/{type}](wata-matrix-spec.md#put-useruseridaccount_datatype) |

### DM Room Idempotency Notes

The `getOrCreateDMRoom()` pattern has a fundamental race condition due to `m.direct` being per-user account data (not server-enforced) and invite propagation latency.

**Mitigation Strategy:**

1. **Auto-join all DM invites** - See `handleMembershipChanged()` in `wata-client.ts:1111-1177`
2. **Update `m.direct` on join** - Detect `is_direct` flag in membership event
3. **Scan for candidate DMs** - Check 2-person rooms with `is_direct` flag before creating
4. **Deterministic selection** - Pick oldest room by creation timestamp when multiple DMs exist

**Test Implications:**

Integration tests must account for this race condition by waiting for invite propagation:

```typescript
// ❌ Relies on timing luck
const aliceRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
const bobRoomId = await bobService.getOrCreateDmRoom('@alice:localhost');
expect(aliceRoomId).toBe(bobRoomId); // May fail

// ✅ Deterministic
const aliceRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
await bobService.waitForRoom(aliceRoomId); // Wait for invite
const bobRoomId = await bobService.getOrCreateDmRoom('@alice:localhost');
expect(aliceRoomId).toBe(bobRoomId);
```

For v1, **accept the limitation** and document it. Duplicate DMs are rare in practice (require concurrent creation within the sync window), and the family room broadcast works perfectly as a fallback.

### Implementation Files

- `src/shared/lib/wata-client/wata-client.ts` - Main WataClient class
- `src/shared/lib/wata-client/types.ts` - Domain types
- `src/shared/lib/wata-client/matrix-api.ts` - Matrix API client
- `src/shared/lib/wata-client/sync-engine.ts` - Sync loop and state management

### References

- [Matrix Spec: Account Data](https://spec.matrix.org/latest/client-server-api/#account-data)
- [matrix-js-sdk #2672](https://github.com/matrix-org/matrix-js-sdk/issues/2672) - "Automatically add to account_data 'm.direct'"
