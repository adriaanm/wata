# Custom Matrix Client Library

## Overview

This document tracks the feasibility study for replacing `matrix-js-sdk` with a minimal custom client library tailored to Wata's walkie-talkie use case.

**Goal:** Reduce bundle size, simplify debugging, and create an API that speaks our domain language (families, contacts, voice messages) rather than generic Matrix concepts.

## Current SDK Usage Analysis

### SDK Version
`matrix-js-sdk v40.0.0` (from `src/shared/package.json`)

### Architecture

All Matrix SDK usage is concentrated in `src/shared/services/MatrixService.ts`. The frontends (TUI, Web, RN) never call the SDK directly—they use `MatrixService` as an abstraction layer.

```
┌─────────┐  ┌─────────┐  ┌─────────┐
│   TUI   │  │   Web   │  │   RN    │
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
     └────────────┼────────────┘
                  │
          ┌───────▼───────┐
          │ MatrixService │  ← Single integration point
          └───────┬───────┘
                  │
          ┌───────▼───────┐
          │ matrix-js-sdk │  ← To be replaced
          └───────────────┘
```

### MatrixClient Methods Used

#### Authentication & Lifecycle
| Method | Location | Purpose |
|--------|----------|---------|
| `createClient(options)` | MatrixService.ts:321, matrix-auth.ts:202 | Create client instance |
| `startClient(options)` | MatrixService.ts:216, 326 | Start syncing |
| `stopClient()` | MatrixService.ts:340 | Stop syncing |
| `login(flowName, params)` | matrix.test.ts:75, 89, 100 | Login with password |
| `logout()` | MatrixService.ts:342 | Invalidate session |
| `getAccessToken()` | MatrixService.ts:204, 163, 273 | Get current token |
| `getUserId()` | MatrixService.ts:205, 367, 378 | Get logged-in user ID |
| `getDeviceId()` | MatrixService.ts:206, 174 | Get device ID |

#### Rooms
| Method | Location | Purpose |
|--------|----------|---------|
| `getRooms()` | MatrixService.ts:582, 709, 829 | Get all joined rooms |
| `getRoom(roomId)` | MatrixService.ts:626, 695, 970, 1127, 1147, 1162 | Get specific room |
| `createRoom(options)` | MatrixService.ts:756, 895 | Create DM or family room |
| `joinRoom(roomIdOrAlias)` | MatrixService.ts:948, 962 | Join a room |
| `getRoomIdForAlias(alias)` | MatrixService.ts:624, 987 | Resolve alias to room ID |
| `invite(roomId, userId)` | MatrixService.ts:930 | Invite user to room |

#### Messaging
| Method | Location | Purpose |
|--------|----------|---------|
| `uploadContent(buffer, options)` | MatrixService.ts:1035 | Upload audio to media repo |
| `sendMessage(roomId, content)` | MatrixService.ts:1063 | Send voice message |
| `redactEvent(roomId, eventId)` | MatrixService.ts:1087, 1111 | Delete a message |
| `sendReadReceipt(event)` | MatrixService.ts:1153 | Mark as played |

#### Profile & Account Data
| Method | Location | Purpose |
|--------|----------|---------|
| `getProfileInfo(userId)` | MatrixService.ts:382, 659 | Get displayName/avatar |
| `setDisplayName(name)` | MatrixService.ts:394 | Update display name |
| `getUser(userId)` | MatrixService.ts:565, 585 | Get user object |
| `getAccountData(type)` | MatrixService.ts:480, 688, 788, 815 | Get m.direct mapping |
| `setAccountData(type, content)` | MatrixService.ts:799, 873 | Update m.direct |

#### Sync State
| Method | Location | Purpose |
|--------|----------|---------|
| `getSyncState()` | test-client.ts:63 | Check sync state |
| `once(event, callback)` | matrix.test.ts:36 | Listen for single sync event |

### Event Listeners

| Event | Enum | Purpose |
|-------|------|---------|
| Sync state change | `ClientEvent.Sync` | Track PREPARED/SYNCING/ERROR |
| Room added | `ClientEvent.Room` | Room joined/created |
| New message | `RoomEvent.Timeline` | Voice messages arrive |
| Read receipt | `RoomEvent.Receipt` | Message played status |
| Membership change | `RoomMemberEvent.Membership` | Join/invite handling |
| Session expired | `HttpApiEvent.SessionLoggedOut` | Re-auth needed |

### Object Methods Used

#### MatrixEvent
- `getType()` - Event type (m.room.message)
- `getContent()` - Message body/content
- `getSender()` - Sender user ID
- `getId()` - Event ID
- `getTs()` - Timestamp
- `getRoomId()` - Room ID

#### Room
- `roomId` - Room ID property
- `name` - Display name
- `timeline` - Array of events
- `getAvatarUrl()` - Room avatar
- `getMyMembership()` - Check if joined
- `getMember(userId)` - Get member object
- `getJoinedMembers()` - List of members
- `getUsersReadUpTo(event)` - Who played message
- `findEventById(eventId)` - Find event
- `currentState.getStateEvents()` - State events

#### RoomMember
- `userId` - Member user ID
- `membership` - State (join/invite)
- `name` - Display name in room
- `getDMInviter()` - Who initiated DM
- `getAvatarUrl()` - Member avatar

### Enums/Constants Used
- `Preset.TrustedPrivateChat` - Room preset
- `Visibility.Private` - Room visibility
- `MsgType.Audio` - Voice messages
- `MsgType.Text` - Text messages (tests only)

### SDK Features NOT Used
- Encryption/E2EE (deferred to v1)
- Push notifications
- Presence
- Threads
- Voice/video calls (WebRTC)
- Key backup
- Device verification
- Reactions
- Edits

---

## Domain Model Mapping

Cross-referencing with [family-model.md](../family-model.md), here's how Matrix concepts map to our domain:

| Wata Concept | Matrix Concept | SDK Usage |
|--------------|----------------|-----------|
| Family | Room with `#family:server` alias | `getRoomIdForAlias()`, `createRoom()` |
| Contact | Room member (excluding self) | `getJoinedMembers()`, `getProfileInfo()` |
| 1:1 conversation | DM room via `m.direct` | `getAccountData('m.direct')`, `createRoom({is_direct: true})` |
| Voice message | `m.audio` event | `uploadContent()`, `sendMessage()` |
| Message played | Read receipt | `sendReadReceipt()`, `getUsersReadUpTo()` |
| Delete message | Redaction | `redactEvent()` |
| Add family member | Room invite + auto-join | `invite()`, `joinRoom()` |

---

## Proposed API Design

### Design Principles

1. **Domain-first**: API speaks walkie-talkie language, not Matrix language
2. **Minimal surface**: Only expose what Wata needs
3. **Event-driven**: Push model for real-time updates
4. **Async-native**: All operations return promises
5. **Immutable data**: Return plain objects, not mutable SDK objects

### Core Types

```typescript
// Identity
interface User {
  id: string;           // @alice:server.local
  displayName: string;
  avatarUrl: string | null;
}

// A family member (contact)
interface Contact {
  user: User;
  isOnline?: boolean;   // Future: presence
}

// The family group
interface Family {
  id: string;           // Room ID
  name: string;
  members: Contact[];
}

// A conversation (1:1 or broadcast)
interface Conversation {
  id: string;           // Room ID
  type: 'dm' | 'family';
  contact?: Contact;    // For DM only
  messages: VoiceMessage[];
  unplayedCount: number;
}

// A voice message
interface VoiceMessage {
  id: string;           // Event ID
  sender: User;
  audioUrl: string;     // MXC URL or HTTP URL
  duration: number;     // Seconds
  timestamp: Date;
  isPlayed: boolean;    // Has current user played it
  playedBy: string[];   // User IDs who played it
}

// Sync state
type ConnectionState = 'connecting' | 'connected' | 'syncing' | 'error' | 'offline';
```

### Client Interface

```typescript
interface WataClient {
  // === Lifecycle ===

  /** Login with username/password, returns authenticated client */
  login(homeserver: string, username: string, password: string): Promise<void>;

  /** Start real-time sync */
  connect(): Promise<void>;

  /** Stop sync and cleanup */
  disconnect(): Promise<void>;

  /** Logout and invalidate session */
  logout(): Promise<void>;

  /** Current user */
  getCurrentUser(): User | null;

  /** Connection state */
  getConnectionState(): ConnectionState;

  // === Family ===

  /** Get the family (null if not in a family) */
  getFamily(): Family | null;

  /** Get all contacts (family members excluding self) */
  getContacts(): Contact[];

  /** Create family room (admin only) */
  createFamily(name: string): Promise<Family>;

  /** Invite user to family (admin only) */
  inviteToFamily(userId: string): Promise<void>;

  // === Conversations ===

  /** Get conversation with a contact (creates DM if needed) */
  getConversation(contact: Contact): Promise<Conversation>;

  /** Get family broadcast conversation */
  getFamilyConversation(): Conversation | null;

  /** Get all conversations with unplayed messages */
  getUnplayedConversations(): Conversation[];

  // === Voice Messages ===

  /** Send voice message to contact or family */
  sendVoiceMessage(
    target: Contact | 'family',
    audio: ArrayBuffer,
    duration: number
  ): Promise<VoiceMessage>;

  /** Mark message as played */
  markAsPlayed(message: VoiceMessage): Promise<void>;

  /** Delete a message (own messages only) */
  deleteMessage(message: VoiceMessage): Promise<void>;

  /** Get audio data for playback */
  getAudioData(message: VoiceMessage): Promise<ArrayBuffer>;

  // === Profile ===

  /** Update current user's display name */
  setDisplayName(name: string): Promise<void>;

  // === Events ===

  on(event: 'connectionStateChanged', handler: (state: ConnectionState) => void): void;
  on(event: 'familyUpdated', handler: (family: Family) => void): void;
  on(event: 'contactsUpdated', handler: (contacts: Contact[]) => void): void;
  on(event: 'messageReceived', handler: (message: VoiceMessage, conversation: Conversation) => void): void;
  on(event: 'messageDeleted', handler: (messageId: string, conversationId: string) => void): void;
  on(event: 'messagePlayed', handler: (message: VoiceMessage) => void): void;

  off(event: string, handler: Function): void;
}
```

### Usage Example

```typescript
// Initialize and connect
const client = new WataClient();
await client.login('https://matrix.example.com', 'alice', 'password');
await client.connect();

// Get family and contacts
const family = client.getFamily();
const contacts = client.getContacts();
console.log(`Family: ${family.name}, ${contacts.length} contacts`);

// Send voice message to a contact
const bob = contacts.find(c => c.user.displayName === 'Bob');
await client.sendVoiceMessage(bob, audioBuffer, 5.2);

// Send broadcast to family
await client.sendVoiceMessage('family', audioBuffer, 3.1);

// Listen for new messages
client.on('messageReceived', (message, conversation) => {
  console.log(`New message from ${message.sender.displayName}`);
  playAudio(message.audioUrl);
  client.markAsPlayed(message);
});

// Cleanup
await client.disconnect();
```

### Comparison: Current vs Proposed

| Operation | Current (MatrixService) | Proposed (WataClient) |
|-----------|------------------------|----------------------|
| Get contacts | `getDirectRooms()` + filter + map | `getContacts()` |
| Send to contact | `getOrCreateDMRoom()` + `sendVoiceMessage()` | `sendVoiceMessage(contact, audio)` |
| Send broadcast | `sendVoiceMessage(familyRoomId, ...)` | `sendVoiceMessage('family', audio)` |
| Check played | `room.getUsersReadUpTo(event)` | `message.isPlayed` / `message.playedBy` |
| New message | `RoomEvent.Timeline` + filter | `'messageReceived'` event |

---

## Implementation Strategy

### Phase 1: HTTP Client Layer
- Implement Matrix Client-Server API calls
- Authentication (`/_matrix/client/v3/login`)
- Room operations (`/rooms/{roomId}/*`)
- Media upload/download (`/_matrix/media/v3/*`)
- Account data (`/user/{userId}/account_data/*`)

### Phase 2: Sync Engine
- Implement `/sync` polling loop
- Parse sync response into domain objects
- Maintain in-memory state (rooms, members, messages)
- Handle pagination and gaps

### Phase 3: Domain Layer
- Implement `WataClient` interface
- Map Matrix events to domain events
- Handle DM room management (`m.direct`)
- Handle family room discovery (alias resolution)

### Phase 4: Migration
- Create adapter that implements current `MatrixService` interface
- Swap implementation behind the scenes
- Verify all tests pass
- Remove `matrix-js-sdk` dependency

---

## Matrix Client-Server API Endpoints Needed

Based on the SDK usage analysis, we need these endpoints.

### Authentication Mechanism

All authenticated endpoints require the `Authorization` header:

```
Authorization: Bearer {access_token}
```

The `?access_token=` query parameter method is deprecated as of Matrix 1.11 and should not be used.

### Endpoint Reference

#### Authentication (No Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/login` | POST | Login with password, returns `access_token` |

#### Authentication (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/logout` | POST | Invalidate current access token |

#### Sync (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/sync` | GET | Long-poll for events, returns rooms/messages/state |

#### Rooms (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/createRoom` | POST | Create room with options |
| `/_matrix/client/v3/join/{roomIdOrAlias}` | POST | Join room by ID or alias |
| `/_matrix/client/v3/rooms/{roomId}/invite` | POST | Invite user to room |
| `/_matrix/client/v3/directory/room/{roomAlias}` | GET | Resolve alias to room ID |

#### Messages (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}` | PUT | Send message event |
| `/_matrix/client/v3/rooms/{roomId}/redact/{eventId}/{txnId}` | PUT | Redact (delete) event |
| `/_matrix/client/v3/rooms/{roomId}/receipt/m.read/{eventId}` | POST | Send read receipt |

#### Media (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/media/v3/upload` | POST | Upload file, returns `mxc://` URL |
| `/_matrix/client/v1/media/download/{serverName}/{mediaId}` | GET | Download file by MXC URL components |

#### Profile & Account Data (Auth Required)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/_matrix/client/v3/profile/{userId}` | GET | Get display name and avatar |
| `/_matrix/client/v3/profile/{userId}/displayname` | PUT | Set own display name |
| `/_matrix/client/v3/user/{userId}/account_data/{type}` | GET | Get account data (e.g., `m.direct`) |
| `/_matrix/client/v3/user/{userId}/account_data/{type}` | PUT | Set account data |

### Summary

| Category | Count | Auth |
|----------|-------|------|
| Login | 1 | No |
| Logout | 1 | Yes |
| Sync | 1 | Yes |
| Rooms | 4 | Yes |
| Messages | 3 | Yes |
| Media | 2 | Yes (upload + download) |
| Profile/Account | 4 | Yes |
| **Total** | **16** | |

This is ~16 endpoints vs 100+ in the full Matrix spec.

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Sync complexity | High | Start with simple polling, add incremental sync later |
| Edge cases in Matrix spec | Medium | Test against Conduit (our target server) |
| Timeline gaps | Medium | Implement `/messages` pagination if needed |
| Future E2EE | High | Design for pluggable crypto layer |
| Maintenance burden | Medium | Keep scope minimal, only add features when needed |

---

## Open Questions

1. **Token refresh**: Conduit supports refresh tokens. Should we implement this or rely on re-login?
2. **Offline support**: Do we need to persist state for offline viewing?
3. **Message history**: How much history to load on initial sync?
4. **E2EE timeline**: When do we need encryption? This significantly increases complexity.

---

## Next Steps

1. [ ] Review and approve API design
2. [ ] Prototype HTTP client with login + sync
3. [ ] Test against Conduit to validate endpoint behavior
4. [ ] Implement minimal sync engine
5. [ ] Build domain layer on top
6. [ ] Migrate MatrixService to use new client
