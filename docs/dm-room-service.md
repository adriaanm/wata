# DM Room Service

Guide to direct message room management in Wata, handling the complexities of Matrix's `m.direct` account data.

## Overview

The DM Room Service (`DmRoomService` for Kotlin, `DMRoomService` for TypeScript) encapsulates all logic for managing direct message rooms between users. It handles:

- **Lookup** - Finding existing DM rooms for a contact (fast, cached)
- **Creation** - Creating new DM rooms when needed (rare, async)
- **Deduplication** - Selecting the "primary" room when multiple exist
- **Sync** - Updating caches from Matrix sync responses

## Event Buffering

Matrix events can arrive out of order. A message for a room might arrive before we know whether it's a DM room (via `m.direct` account data or `is_direct` flag).

The `EventBuffer` class handles this by:

1. **Buffering** - When a message arrives for an unclassified room, buffer it
2. **Retrying** - Every 300ms, check if buffered rooms are now classified as DMs
3. **Flushing** - When room is classified as DM, process buffered events

**No heuristics** - We never guess based on room membership. We only classify a room as DM when we have definitive evidence:
- `m.direct` account data includes the room
- `is_direct: true` flag in our member event

**Flush triggers:**
- `m.direct` account data update
- `refreshFromSync()` discovers room with `is_direct` flag
- Retry timer (every 300ms)

**Buffer limits:**
- Max 5 minutes age (pruned every ~10 seconds)
- Max 100 events per room

## Why This Service Exists

Matrix has no protocol-level guarantee of a single DM room between two users. The `m.direct` account data is:

- **Per-user, client-side** - Not enforced by the server
- **Independent** - Each user maintains their own `m.direct` mapping
- **Non-unique** - Multiple DM rooms can exist between the same two users

**Example scenario:**
1. Alice creates a DM with Bob → Room `!room1`
2. Bob creates a DM with Alice (before seeing Alice's invite) → Room `!room2`
3. Now two DM rooms exist between Alice and Bob

The service handles this gracefully by selecting the oldest room as the "primary" for messaging.

## Matrix Protocol Details

### `m.direct` Account Data

```json
{
  "@user1:example.com": ["!room1:example.com", "!room2:example.com"],
  "@user2:example.com": ["!room3:example.com"]
}
```

- Stored per-user as account data (`m.direct`)
- Lists room IDs that should be treated as DMs
- **No server enforcement** - purely client-side convention
- Both parties must maintain independently

### `is_direct` Flag

- Set to `true` when creating a room via `POST /_matrix/client/v3/createRoom` with `is_direct: true`
- Signals to clients that this room should be added to `m.direct`
- Not enforced by the server

## Architecture

### Key Principles

1. **Single source of truth** - All `userId → roomId` mappings owned by this service
2. **Deterministic selection** - Oldest room wins when duplicates exist
3. **Separation of concerns** - Lookup (sync) vs. creation (async)
4. **Lazy creation** - Rooms only created explicitly, never implicitly

### API (TypeScript)

```typescript
interface DMRoomService {
  // === Lookup (fast, synchronous) ===

  /**
   * Get the primary DM room ID for a contact.
   * Returns null if no DM room exists in cache.
   * Does NOT create a room or make network calls.
   */
  getDMRoomId(contactUserId: string): string | null;

  /**
   * Get all known DM room IDs for a contact.
   * Used for message consolidation across duplicate rooms.
   */
  getAllDMRoomIds(contactUserId: string): string[];

  /**
   * Check if a room ID is a known DM room.
   */
  isDMRoom(roomId: string): boolean;

  /**
   * Get the contact for a DM room (reverse lookup).
   */
  getContactForRoom(roomId: string): string | null;

  // === Creation (async) ===

  /**
   * Ensure a DM room exists with the contact.
   * - First tries to find existing room via sync state + m.direct
   * - Creates new room only if none found
   * - Updates m.direct and internal caches
   *
   * This is the ONLY method that creates DM rooms.
   */
  ensureDMRoom(contactUserId: string): Promise<string>;

  // === Cache Management ===

  /**
   * Called by sync engine when rooms/membership change.
   * Rescans for DM rooms and updates internal state.
   */
  refreshFromSync(): void;

  /**
   * Clear all cached state (on logout).
   */
  clear(): void;
}
```

### API (Kotlin - DmRoomService)

```kotlin
class DmRoomService(
    private val api: MatrixApi,
    private val syncState: SyncState
) {
    // Lookup
    fun getDMRoomId(contactUserId: String): String?
    fun getAllDMRoomIds(contactUserId: String): List<String>
    fun isDMRoom(roomId: String): Boolean
    fun getContactForRoom(roomId: String): String?

    // Creation
    suspend fun ensureDMRoom(contactUserId: String): String

    // Cache management
    fun refreshFromSync(syncResponse: SyncResponse)
    fun clear()
}
```

## Implementation Details

### Files

| Platform | Location |
|----------|----------|
| TypeScript | `src/shared/lib/wata-client/dm-room-service.ts` |
| Kotlin | `src/android/app/src/main/java/com/wata/client/DmRoomService.kt` |

### How It Works

1. **On sync completion**: `refreshFromSync()` scans:
   - Joined rooms with exactly 2 members
   - `m.direct` account data
   - Builds `userId → roomId` mapping

2. **When sending a message**:
   ```typescript
   // Fast path: cached room
   let roomId = dmRoomService.getDMRoomId(contactUserId);

   // Slow path: first message to this contact
   if (!roomId) {
     roomId = await dmRoomService.ensureDMRoom(contactUserId);
   }
   ```

3. **When multiple rooms exist**: Selects oldest by `room.createTimestamp`

### Key Invariants

1. **Single source of truth**: Service owns all `userId → roomId` mappings
2. **Deterministic selection**: Oldest room wins for duplicates
3. **Lazy creation**: Rooms only created in `ensureDMRoom()`
4. **Cache invalidation**: `refreshFromSync()` called after each sync
5. **Idempotent**: Multiple `ensureDMRoom()` calls return same room (per session)

## Usage Examples

### Getting a Room to Send a Message

```typescript
// WataClient.sendVoiceMessage()
async sendVoiceMessage(target: Contact, audio: Buffer, duration: number) {
  let roomId: string;

  if (target.type === 'family') {
    roomId = this.findFamilyRoom()?.roomId;
  } else {
    // Fast lookup first
    roomId = this.dmRoomService.getDMRoomId(target.user.id);

    // Create if needed (first message to this contact)
    if (!roomId) {
      roomId = await this.dmRoomService.ensureDMRoom(target.user.id);
    }
  }

  return this.sendVoiceMessageToRoom(roomId, audio, duration);
}
```

### Displaying a Conversation

```typescript
// Just need the room ID - no creation needed
const roomId = dmRoomService.getDMRoomId(contactUserId);
if (roomId) {
  const conversation = await wataClient.getConversation(roomId);
  // Display conversation...
}
```

### Checking If Room Is a DM

```typescript
// For room type detection in UI
if (dmRoomService.isDMRoom(roomId)) {
  // Show DM-specific UI elements
}
```

## Known Limitations

- **Cross-session deduplication**: Requires server coordination (out of scope)
- **Message migration**: Duplicate rooms remain; messages not consolidated
- **Room cleanup**: Old duplicate rooms are not auto-archived

## Related Documentation

- [docs/family-model.md](family-model.md) - Room types and Matrix mapping
- [docs/android-development.md](android-development.md) - Kotlin implementation details
