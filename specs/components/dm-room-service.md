# DMRoomService - DM Room Management and Deduplication

## Overview

DMRoomService encapsulates all DM room management logic: lookup, creation, and deterministic deduplication when multiple DM rooms exist with the same contact. It maintains internal caches for fast lookups and delegates to MatrixApi for room creation. This is part of Layer 2 (State Management).

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should create a direct message room"
  - Tests: getOrCreateDmRoom() creates new room
  - Verifies: Room ID returned, starts with !

- "should get direct rooms"
  - Tests: Room listing after creation
  - Verifies: Direct rooms include the created DM

**test/integration/voice-message-flow.test.ts**
- Implicitly tests getOrCreateDmRoom() via TestOrchestrator.createRoom()

**test/integration/edge-cases.test.ts**
- "should handle concurrent DM room creation"
  - Tests: ensureDMRoom() deterministic selection
  - Verifies: Both clients select same room when multiple exist
  - Workaround: Polling to wait for room synchronization

## Responsibilities

- **DM Room Lookup**: Fast, synchronous lookup of primary DM room for a contact
- **DM Room Creation**: Create new DM room when none exists
- **Deduplication**: Deterministically select primary room when multiple DM rooms exist
- **m.direct Management**: Update and sync m.direct account data
- **Cache Management**: Maintain bidirectional mappings (contact↔room)
- **Contact Inference**: Determine contact from room membership when m.direct is missing

## API/Interface

### Constructor

```typescript
constructor(api: MatrixApi, syncEngine: SyncEngine, userId: string, logger?: Logger)
```
- **api**: MatrixApi for room creation and account data
- **syncEngine**: SyncEngine for room state access
- **userId**: Current user ID
- **logger**: Optional logger

### Lookup Methods (Synchronous)

```typescript
getDMRoomId(contactUserId: string): string | null
```
- **Returns**: Primary DM room ID for contact, or null if none known
- **Invariant**: Returns same room ID until cache is refreshed
- **Guarantees**: Does NOT create rooms or make network calls

```typescript
getAllDMRoomIds(contactUserId: string): string[]
```
- **Returns**: All known DM room IDs for contact (empty if none)
- **Use Case**: Message consolidation across duplicate rooms

```typescript
isDMRoom(roomId: string): boolean
```
- **Returns**: true if room is a known DM room

```typescript
getContactUserId(roomId: string): string | null
```
- **Returns**: Contact user ID for a DM room (reverse lookup)

```typescript
getContactForRoom(roomId: string): Contact | null
```
- **Returns**: Full Contact object with user info from room membership
- **Fallback**: If room not in cache, infers from membership (2-person room with is_direct flag)

### Creation Method (Async)

```typescript
async ensureDMRoom(contactUserId: string): Promise<string>
```
- **Returns**: Room ID (existing or newly created)
- **Algorithm**:
  1. Check cache (fast path)
  2. Verify cached room still valid (joined)
  3. Scan sync state for existing DM rooms
  4. Create new room if none found
  5. Update m.direct account data
- **Invariant**: Returns same room for same contact (deterministic selection)
- **Deduplication**: If multiple rooms found, selects oldest by m.room.create timestamp

### Cache Management

```typescript
handleMDirectUpdate(content: Record<string, string[]>): void
```
- **Called by**: WataClient when m.direct account data changes
- **Updates**: primaryRoomByContact, allRoomsByContact, contactByRoom

```typescript
refreshFromSync(): void
```
- **Called by**: WataClient after sync batches
- **Discovers**: New DM rooms by scanning 2-person rooms with is_direct flag

```typescript
clear(): void
```
- **Called on**: Logout
- **Clears**: All cached mappings

## Invariants

1. **Primary Room Selection**: For a given contact, always returns same room ID until cache refresh
2. **Deterministic Deduplication**: When multiple rooms exist, oldest room (by creation_ts) is primary
3. **Bidirectional Consistency**: contactByRoom[roomId] = userId ⟺ roomId ∈ allRoomsByContact[userId]
4. **Cache Validity**: Cached room is only valid if user is joined (membership = 'join')
5. **is_direct Flag**: All tracked DM rooms have is_direct = true in m.room.create or m.room.member
6. **Account Data Sync**: m.direct updates are eventually reflected in cache (via handleMDirectUpdate)

## State

### Internal Caches

```typescript
private primaryRoomByContact: Map<string, string>      // contactUserId → primaryRoomId
private allRoomsByContact: Map<string, Set<string>>     // contactUserId → Set<roomId>
private contactByRoom: Map<string, string>              // roomId → contactUserId
```

### Cache Update Flow

1. **m.direct update** → handleMDirectUpdate() → Update all 3 caches
2. **ensureDMRoom()** → findExistingDMRoom() → Update all 3 caches
3. **createDMRoom()** → updateMDirectForRoom() → m.direct update → handleMDirectUpdate()
4. **refreshFromSync()** → Scan rooms → addRoomToCache() → Update all 3 caches

## Events

DMRoomService does not emit events. State changes are observable via:
- Return value of getDMRoomId() (changes when cache updated)
- WataClient events (familyUpdated, contactsUpdated) triggered by sync

## Error Handling

### ensureDMRoom() Errors

- **Network Errors**: Propagates from MatrixApi (createRoom, setAccountData)
- **Sync State Missing**: If room created but not in sync yet, waits with polling (in WataClient)
- **Invalid Contact**: No validation, relies on Matrix server to reject invalid user IDs

### Deduplication Edge Cases

1. **No Creation Timestamp**: Falls back to room ID lexicographic order
2. **Race Condition**: Both clients create room simultaneously
   - Both rooms exist in m.direct
   - Oldest room becomes primary
   - Newer room remains accessible via getAllDMRoomIds()
3. **Membership Mismatch**: If user is no longer joined, cache entry is invalidated

### Account Data Errors

- **updateMDirectForRoom() Failure**: Logs error but does not throw (room still usable)
- **m.direct Read Failure**: Assumes empty (creates new mapping)
- **m.direct Format Invalid**: Silently skips malformed entries

## Known Limitations

1. **No Orphaned Room Cleanup**: Duplicate rooms remain in m.direct forever
2. **No Invite Handling**: Assumes rooms are auto-joined (WataClient handles this)
3. **Timestamp Tie-Breaking**: If creation timestamps identical, uses room ID lexicographic order (arbitrary)
4. **Cache Staleness**: Cache may lag behind m.direct updates from other devices
5. **No Room Verification**: Does not verify room is actually a DM (trusts is_direct flag)
6. **Message Consolidation**: Does not merge messages from duplicate rooms

## Related Specs

- [WataClient](./wata-client.md) - Delegates all DM operations to DMRoomService
- [First DM Flow](../flows/first-dm.md) - Creating first DM with a contact
- [Repeat DM Flow](../flows/repeat-dm.md) - Sending to existing DM room
- [DM Deduplication Flow](../flows/dm-deduplication.md) - Handling multiple DM rooms
- [Concurrent DM Creation Flow](../flows/concurrent-dm-creation.md) - Race condition handling
