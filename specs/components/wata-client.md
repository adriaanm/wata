# WataClient - High-Level Walkie-Talkie API

## Overview

WataClient is the main client library that frontends interact with. It provides a domain-specific API (families, contacts, voice messages) by wrapping MatrixApi, SyncEngine, and DMRoomService. This is Layer 3 (Domain Layer) in the three-layer architecture.

## Current Test Coverage

**test/integration/auto-login.test.ts**
- Tests login() and connect() flow
- Tests whoami() for session verification
- Tests getCurrentUser() and getAccessToken()
- Workaround: Polling for family room to appear

**test/integration/contacts.test.ts**
- Tests getContacts() after family creation
- Tests inviteToFamily() and contact discovery
- Workaround: Polling for membership changes

**test/integration/voice-message-flow.test.ts**
- Tests sendVoiceMessage() and message receipt
- Tests getVoiceMessages() for timeline access
- Workaround: TestOrchestrator polls for messages via waitForEventIds()

**test/integration/e2e-flow.test.ts**
- Tests createFamily(), sendVoiceMessage(), markAsPlayed()
- Workaround: Extensive polling for room/message/receipt propagation

**test/integration/read-receipts.test.ts**
- Tests markAsPlayed() and messagePlayed event
- Workaround: 15s polling for receipt callback, extra waitForSync() call

**test/integration/family-room.test.ts**
- Tests family room creation and member listing
- Workaround: Polling for member joins

## Responsibilities

- **Authentication**: Login, logout, session management
- **Family Management**: Create family, invite members, get contacts
- **DM Conversations**: Get or create DM rooms with contacts
- **Voice Messaging**: Send, receive, mark as played, delete messages
- **Domain Mapping**: Convert Matrix concepts (rooms, events) to domain types (Family, Contact, VoiceMessage)
- **Event Aggregation**: Emit high-level events (messageReceived, messagePlayed, contactsUpdated)
- **Auto-Join**: Automatically join invites (trusted family environment)

## API/Interface

### Constructor

```typescript
constructor(homeserverUrl: string, logger?: Logger)
```
- Creates MatrixApi instance
- SyncEngine and DMRoomService are created after login

### Lifecycle

```typescript
async login(username: string, password: string): Promise<void>
```
- **Postconditions**:
  - userId is set
  - SyncEngine created and userId set
  - DMRoomService created
  - Sync engine event listeners wired up
- **Errors**: Propagates MatrixApi login errors

```typescript
async connect(): Promise<void>
```
- **Precondition**: Must call login() first
- **Postconditions**:
  - Initial sync completes
  - Background sync loop running
  - connectionStateChanged event emitted
- **Errors**: Throws if not logged in or already connected

```typescript
async disconnect(): Promise<void>
async logout(): Promise<void>
```
- **disconnect()**: Stops sync, emits 'offline' state
- **logout()**: Disconnects, invalidates session, clears state

```typescript
getCurrentUser(): User | null
async whoami(): Promise<string | null>
getAccessToken(): string | null
getConnectionState(): ConnectionState
```

### Family Methods

```typescript
getFamily(): Family | null
```
- **Returns**: Family if #family:server room exists, null otherwise
- **Family.members**: All joined members except current user
- **Invariant**: Family room detected by canonical alias #family:{server}

```typescript
async createFamily(name: string): Promise<Family>
```
- **Creates**: Room with alias #family, preset: private_chat
- **Postconditions**:
  - Family room ID cached
  - Room appears in sync
- **Errors**: Throws if family already exists (alias conflict)

```typescript
async inviteToFamily(userId: string): Promise<void>
```
- **Precondition**: Family must exist (call createFamily first)
- **Invitee**: Auto-joins via WataClient membership handler

```typescript
getContacts(): Contact[]
```
- **Returns**: All family members except self
- **Filters**: Only 'join' membership state
- **Invariant**: Returns same list as getFamily().members

### Conversation Methods

```typescript
async getConversation(contact: Contact): Promise<Conversation>
```
- **Creates DM**: If no DM room exists
- **Returns**: Conversation with messages, unplayedCount
- **Invariant**: Conversation.id is stable for same contact (deterministic DM selection)

```typescript
getFamilyConversation(): Conversation | null
getConversationByRoomId(roomId: string): Conversation | null
```
- **getFamilyConversation()**: Returns family broadcast conversation
- **getConversationByRoomId()**: Synchronous lookup, does not create rooms

```typescript
getUnplayedConversations(): Conversation[]
```
- **Returns**: All conversations with unplayedCount > 0
- **Includes**: Family conversation and all DM conversations

### Voice Message Methods

```typescript
async sendVoiceMessage(target: Contact | 'family', audio: ArrayBuffer, duration: number): Promise<VoiceMessage>
```
- **Uploads**: Audio to media repo
- **Sends**: m.audio event with MXC URL
- **Returns**: VoiceMessage with event_id (optimistic, before sync)
- **Warning**: For DM targets, uses getOrCreateDMRoom() which may select different room if duplicates exist

```typescript
async sendVoiceMessageToRoom(roomId: string, audio: ArrayBuffer, duration: number): Promise<VoiceMessage>
```
- **Lower-level**: Sends to specific room ID (bypasses DM lookup)
- **Use Case**: Sending to a specific DM room when duplicates exist

```typescript
async markAsPlayed(message: VoiceMessage): Promise<void>
async markAsPlayedById(roomId: string, eventId: string): Promise<void>
```
- **Sends**: Read receipt for event
- **Updates**: Local state (isPlayed = true, playedBy includes userId)
- **Emits**: messagePlayed event with updated message

```typescript
async deleteMessage(message: VoiceMessage): Promise<void>
```
- **Precondition**: Can only delete own messages (sender.id === userId)
- **Sends**: Redaction event
- **Emits**: messageDeleted event

```typescript
async getAudioData(message: VoiceMessage): Promise<ArrayBuffer>
```
- **Downloads**: Audio from media repo via audioUrl
- **Returns**: Raw audio data

### Event Emitter

```typescript
on<K extends WataClientEventName>(event: K, handler: WataClientEvents[K]): void
off<K extends WataClientEventName>(event: K, handler: WataClientEvents[K]): void
```

**Events:**
- `connectionStateChanged(state: ConnectionState)` - Sync state changes
- `familyUpdated(family: Family)` - Family room state changed
- `contactsUpdated(contacts: Contact[])` - Contacts list changed
- `messageReceived(message: VoiceMessage, conversation: Conversation)` - New message arrived
- `messageDeleted(messageId: string, conversationId: string)` - Message redacted
- `messagePlayed(message: VoiceMessage, roomId: string)` - Read receipt received

## Invariants

1. **Family Room Uniqueness**: At most one family room (by #family alias)
2. **Contact Membership**: Contacts list only includes users with membership = 'join'
3. **Message Ordering**: Messages in conversation are chronologically ordered
4. **Event ID Uniqueness**: VoiceMessage.id is globally unique (Matrix event_id)
5. **Receipt Accumulation**: playedBy array is append-only (never removes users)
6. **Auto-Join Guarantee**: All invites are auto-joined via membershipChanged handler
7. **DM Room Stability**: getConversation(contact) returns same roomId across calls (until cache refresh)

## State

### Internal State

- **userId**: Current user ID (set on login)
- **familyRoomId**: Cached family room ID (null until found)
- **isConnected**: Whether sync loop is running
- **eventHandlers**: Map of event listeners

### Delegated State

- **MatrixApi**: Access token, base URL
- **SyncEngine**: Rooms, timeline, receipts, membership
- **DMRoomService**: DM room mappings, primary room selection

### State Lifecycle

1. **Construction**: MatrixApi created
2. **Login**: userId set, SyncEngine + DMRoomService created
3. **Connect**: Sync starts, rooms discovered
4. **Disconnect**: Sync stops (state preserved)
5. **Logout**: All state cleared

## Events

### Event Flow (Timeline Event)

1. SyncEngine emits `timelineEvent(roomId, event)`
2. WataClient.handleTimelineEvent() called
3. If voice message: Convert to VoiceMessage, determine conversation type
4. Emit `messageReceived(message, conversation)`

### Event Flow (Receipt Update)

1. SyncEngine emits `receiptUpdated(roomId, eventId, userIds)`
2. WataClient.handleReceiptUpdated() called
3. If voice message: Convert to VoiceMessage with updated playedBy
4. Emit `messagePlayed(message, roomId)`

### Event Flow (Room Update)

1. SyncEngine emits `roomUpdated(roomId, room)`
2. WataClient.handleRoomUpdated() called
3. If family room: Get updated family, emit `familyUpdated` and `contactsUpdated`

### Event Flow (Membership Change)

1. SyncEngine emits `membershipChanged(roomId, userId, membership)`
2. WataClient.handleMembershipChanged() called
3. If invite to self: Auto-join room, refresh DM cache
4. If family room: Emit `familyUpdated` and `contactsUpdated`

## Error Handling

### Login/Connect Errors

- **Login Failure**: Propagates MatrixApi error (invalid credentials, network error)
- **Connect Without Login**: Throws "Not logged in"
- **Double Connect**: Throws "Already connected"

### Message Send Errors

- **Upload Failure**: Propagates MatrixApi error
- **Send Failure**: Propagates MatrixApi error
- **No Family**: Throws "Not in a family" if target is 'family' but no family exists

### Mark as Played Errors

- **Room Not Found**: Throws "Room not found for message {id}"
- **Network Failure**: Propagates MatrixApi error

### Delete Message Errors

- **Not Owner**: Throws "Can only delete own messages"
- **Room Not Found**: Throws "Room not found for message {id}"

## Known Limitations

1. **No Message Pagination**: Only syncs messages in initial/incremental sync (no /messages backfill)
2. **No E2EE**: Does not support encrypted rooms
3. **No Typing Indicators**: Does not emit or track typing state
4. **No Presence**: Does not track online/offline status
5. **No Message Editing**: No support for m.room.message edits
6. **No Reactions**: No support for message reactions
7. **Auto-Join All**: Automatically joins ALL invites (no selective join)
8. **No DM Room Cleanup**: Duplicate DM rooms remain indefinitely

## Related Specs

- [MatrixApi](./matrix-api.md) - HTTP client
- [SyncEngine](./sync-engine.md) - State synchronization
- [DMRoomService](./dm-room-service.md) - DM room management
- [Initial Setup Flow](../flows/initial-setup.md) - Family creation
- [Voice Message Send Flow](../flows/voice-message-send.md) - Message sending
- [Voice Message Receive Flow](../flows/voice-message-receive.md) - Message receipt
- [Read Receipt Flow](../flows/read-receipt.md) - Mark as played
