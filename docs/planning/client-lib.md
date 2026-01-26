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

## DM Room Idempotency: A Known Matrix Protocol Limitation

### The Problem

The `getOrCreateDmRoom()` pattern has a fundamental race condition:

```
Timeline: Alice creates DM → invites Bob → updates her m.direct
          Bob immediately calls getOrCreateDmRoom(alice)
          Bob's m.direct is empty → creates NEW room
          Result: Two separate DM rooms between the same users
```

This occurs because:
1. `m.direct` is **per-user account data** - not server-enforced
2. Invite propagation via `/sync` has latency (up to long-poll timeout)
3. Both users can independently create rooms before seeing the other's invite

### Why Matrix Can't Fix This

| Feature | Status | Reason |
|---------|--------|--------|
| Server-side `m.direct` | ❌ Not available | `m.direct` is client-side account data only |
| Atomic `getOrCreateDM` endpoint | ❌ Not in spec | No Matrix API for this |
| Deterministic DM room IDs | ❌ Not supported | Room IDs are server-generated random strings |
| Room aliases for DMs | ⚠️ Possible but inadvisable | Privacy + exposure + doesn't prevent race |

### How Other Clients Handle This

**Element Web / matrix-js-sdk:**
- Accepts the race condition as a known limitation
- Uses auto-join + post-sync deduplication
- Manual user resolution if duplicates occur

**Source:** [matrix-js-sdk #2672](https://github.com/matrix-org/matrix-js-sdk/issues/2672) - "Automatically add to account_data 'm.direct'"

> "Manual management is impossible for servers that configure auto-joining on invites. Without `m.direct` update, it's impossible to tell which rooms are DM rooms."

### Our Mitigation Strategy

Our implementation follows Matrix best practices:

1. **Auto-join all DM invites** - `MatrixService.ts:943-954` and `wata-client.ts:847-903`
2. **Update `m.direct` on join** - Detect `is_direct` flag in membership event
3. **Scan for candidate DMs** - Check 2-person rooms with `is_direct` flag before creating
4. **Post-sync cleanup** - `syncDirectRoomsFromMembership()` fixes stale `m.direct` entries

**Code locations:**
- `MatrixService.ts:683-776` - `getOrCreateDmRoom()` with fallback scanning
- `MatrixService.ts:448-503` - Auto-join handler
- `MatrixService.ts:807-879` - Post-sync `m.direct` repair
- `wata-client.ts:526-622` - Enhanced DM discovery with multiple fallback checks

### Test Implications

Integration tests must account for this race condition:

```typescript
// ❌ This test relies on timing luck
const aliceRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
const bobRoomId = await bobService.getOrCreateDmRoom('@alice:localhost');
expect(aliceRoomId).toBe(bobRoomId); // May fail if sync is too fast/slow

// ✅ This test is deterministic
const aliceRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
await bobService.waitForRoom(aliceRoomId); // Wait for invite to propagate
const bobRoomId = await bobService.getOrCreateDmRoom('@alice:localhost');
expect(aliceRoomId).toBe(bobRoomId); // Now deterministic
```

See: `test/integration/matrix.test.ts:328-389` for the fixed test approach.

### Recommendations for Wata

| Option | Approach | Trade-off |
|--------|----------|-----------|
| **Accept limitation** | Document that rare duplicates can occur | Honest, but may confuse users |
| **Family room only** | Skip 1:1 DMs, use broadcast for everything | Simpler, but less private |
| **Post-facto merge** | Detect duplicates, leave older room | Complex, may lose messages |
| **User-facing fix** | Add "Fix duplicate chats" button | User-controlled cleanup |

For v1, **accept the limitation** and document it. Duplicate DMs are rare in practice (require concurrent creation within the sync window), and the family room broadcast works perfectly as a fallback.

### Roadmap: Two-Phase Commit Protocol for DM Room Deduplication

**Problem:** Current code has multiple vulnerable patterns where room IDs are checked for equality:

| Pattern | Location | Vulnerability |
|---------|----------|---------------|
| `dmRooms.get(contactUserId)` | `wata-client.ts:396, 712, 1137` | Returns single roomId, but multiple may exist |
| `familyRoomId` cache | `wata-client.ts:227-244` | Assumes single family room ID |
| `roomIdByContactUserId` mapping | `MatrixServiceAdapter.ts:135` | 1:1 map, no duplicate handling |
| `m.direct` handling | `wata-client.ts:1137` | Uses `roomIds[0]` without consolidation |
| Auto-join updates | `wata-client.ts:1114` | Sets roomId without checking for duplicates |

**Current Mitigation (Partial):**
- `getOrCreateDMRoom()` scans for ALL candidate DM rooms and picks the one with the most messages
- This only runs when explicitly called, not during auto-join or `m.direct` handling
- Other code paths don't benefit from this consolidation logic

**Proposed Solution: Two-Phase Commit Protocol**

A robust solution that allows both users to deterministically agree on which DM room is the primary one:

1. **Phase 1: Discovery** - Both parties exchange a list of their existing DM room IDs with each other
2. **Phase 2: Consensus** - Apply a deterministic selection function (e.g., lexicographic comparison of room IDs, message count, or creation timestamp) to agree on the primary room
3. **Phase 3: Cleanup** - Both users leave/abandon the non-primary rooms

**Implementation sketch:**

```typescript
// Send via m.room.message with msgtype: "m.wata.dm_discovery"
interface DmDiscoveryMessage {
  type: 'dm_discovery';
  rooms: string[];  // List of DM room IDs with this user
}

// Deterministic selection function
function selectPrimaryRoom(ourRooms: string[], theirRooms: string[]): string {
  const allRooms = [...new Set([...ourRooms, ...theirRooms])];
  // Pick the room with the most messages, tie-break by lexicographic room ID
  const sorted = allRooms
    .map(id => ({ id, count: getMessageCount(id) }))
    .sort((a, b) => b.count - a.count || a.id.localeCompare(b.id));
  return sorted[0].id;
}
```

**Benefits:**
- Eliminates race conditions entirely
- Works even when both users create rooms simultaneously
- Self-healing: duplicates are automatically resolved on next contact

**Challenges:**
- Requires custom message type (interop with other Matrix clients may be weird)
- Requires both users to run Wata client
- Network partition scenarios need careful handling

**Status:** Design exploration. Not planned for v1.

### References

- [Matrix Spec: Account Data](https://spec.matrix.org/latest/client-server-api/#account-data)
- [matrix-js-sdk #2672](https://github.com/matrix-org/matrix-js-sdk/issues/2672)
- [matrix-js-sdk #720](https://github.com/matrix-org/matrix-js-sdk/issues/720)
- [Synapse #9804](https://github.com/matrix-org/synapse/issues/9804) - Room invite race condition

---

## Next Steps

1. [x] Review and approve API design
2. [x] Prototype HTTP client with login + sync
3. [ ] Test against Conduit to validate endpoint behavior
4. [x] Implement minimal sync engine
5. [x] Build domain layer on top
6. [ ] Migrate MatrixService to use new client

---

## Test Migration Plan

The current integration tests (`test/integration/matrix.test.ts`) use `matrix-js-sdk` directly. To validate the new WataClient, we need to migrate these tests to use MatrixService, then swap in MatrixServiceAdapter.

### Current Test Structure

```typescript
// Current: Uses matrix-js-sdk directly
import * as matrix from 'matrix-js-sdk';

const client = matrix.createClient({ baseUrl: TEST_HOMESERVER });
await client.login('m.login.password', { ... });
await client.createRoom({ ... });
await client.sendMessage(roomId, { ... });
```

### Target Test Structure

```typescript
// Target: Uses MatrixService (can swap implementation)
import { MatrixService } from '@shared/services/MatrixService';
// OR: import { MatrixServiceAdapter as MatrixService } from '@shared/services/MatrixServiceAdapter';

const service = new MatrixService();
service.setHomeserverUrl(TEST_HOMESERVER);
await service.login(username, password);
await service.getOrCreateDmRoom(userId);
await service.sendVoiceMessage(roomId, buffer, 'audio/mp4', 5000, buffer.length);
```

### Migration Steps

#### Step 1: Create test factory function

Add to `test/integration/helpers/`:

```typescript
// test-service-factory.ts
import { MatrixService } from '@shared/services/MatrixService';
// Toggle this import to switch implementations:
// import { MatrixServiceAdapter as MatrixService } from '@shared/services/MatrixServiceAdapter';

export function createTestService(homeserver: string): MatrixService {
  const service = new MatrixService();
  service.setHomeserverUrl(homeserver);
  return service;
}
```

#### Step 2: Map SDK calls to MatrixService methods

| Current (matrix-js-sdk) | Target (MatrixService) |
|------------------------|------------------------|
| `matrix.createClient({ baseUrl })` | `createTestService(baseUrl)` |
| `client.login('m.login.password', {...})` | `service.login(username, password)` |
| `client.startClient()` + wait for sync | `service.login()` (handles sync internally) |
| `client.stopClient()` | `service.logout()` |
| `client.createRoom({ is_direct, invite })` | `service.getOrCreateDmRoom(userId)` |
| `client.joinRoom(roomId)` | `service.joinRoom(roomId)` |
| `client.getRooms()` | `service.getDirectRooms()` |
| `client.getRoom(roomId)` | Access via `getDirectRooms().find(...)` |
| `client.uploadContent(buffer, opts)` | Handled internally by `sendVoiceMessage` |
| `client.sendMessage(roomId, content)` | `service.sendVoiceMessage(roomId, ...)` |
| `room.timeline` | `service.getVoiceMessages(roomId)` |

#### Step 3: Rewrite test cases

**Authentication tests:**
```typescript
describe('Authentication', () => {
  test('should login with valid credentials', async () => {
    const service = createTestService(TEST_HOMESERVER);
    await service.login(TEST_USERS.alice.username, TEST_USERS.alice.password);

    expect(service.isLoggedIn()).toBe(true);
    expect(service.getUserId()).toBe('@alice:localhost');
  });

  test('should fail login with invalid password', async () => {
    const service = createTestService(TEST_HOMESERVER);
    await expect(
      service.login(TEST_USERS.alice.username, 'wrongpassword')
    ).rejects.toThrow();
  });
});
```

**Room tests:**
```typescript
describe('Room Operations', () => {
  let aliceService: MatrixService;

  beforeAll(async () => {
    aliceService = createTestService(TEST_HOMESERVER);
    await aliceService.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
  });

  test('should create a DM room', async () => {
    const roomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
    expect(roomId).toMatch(/^!/);
  });

  test('should list rooms after sync', async () => {
    const rooms = aliceService.getDirectRooms();
    expect(rooms.length).toBeGreaterThan(0);
  });
});
```

**Messaging tests:**
```typescript
describe('Messaging', () => {
  let aliceService: MatrixService;
  let testRoomId: string;

  beforeAll(async () => {
    aliceService = createTestService(TEST_HOMESERVER);
    await aliceService.login(TEST_USERS.alice.username, TEST_USERS.alice.password);
    testRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
  });

  test('should send a voice message', async () => {
    const fakeAudio = Buffer.from('fake audio');
    await aliceService.sendVoiceMessage(
      testRoomId,
      fakeAudio,
      'audio/mp4',
      5000,
      fakeAudio.length
    );

    // Wait for sync
    await new Promise(r => setTimeout(r, 500));

    const messages = aliceService.getVoiceMessages(testRoomId);
    expect(messages.length).toBeGreaterThan(0);
  });
});
```

#### Step 4: Add adapter toggle

In `test/integration/helpers/test-service-factory.ts`:

```typescript
const USE_WATA_CLIENT = process.env.USE_WATA_CLIENT === 'true';

export function createTestService(homeserver: string) {
  if (USE_WATA_CLIENT) {
    const { MatrixServiceAdapter } = require('@shared/services/MatrixServiceAdapter');
    const service = new MatrixServiceAdapter();
    service.setHomeserverUrl(homeserver);
    return service;
  } else {
    const { MatrixService } = require('@shared/services/MatrixService');
    const service = new MatrixService();
    service.setHomeserverUrl(homeserver);
    return service;
  }
}
```

#### Step 5: Run tests with both implementations

```bash
# Test with original MatrixService (baseline)
pnpm test:integration

# Test with WataClient via adapter
USE_WATA_CLIENT=true pnpm test:integration
```

### Tests to Skip Initially

Some tests may not be supported by WataClient initially:

| Test | Reason | Action |
|------|--------|--------|
| Text message send | WataClient only supports audio | Skip or adapt |
| Direct SDK access | `getClient()` returns null in adapter | Skip |
| Room timeline access | Different API shape | Adapt to use `getVoiceMessages()` |

### Success Criteria

1. All tests pass with original MatrixService
2. Authentication tests pass with MatrixServiceAdapter
3. Room creation tests pass with MatrixServiceAdapter
4. Voice message send/receive tests pass with MatrixServiceAdapter
5. No regressions when switching between implementations
