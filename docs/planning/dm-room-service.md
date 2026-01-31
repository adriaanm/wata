# DM Room Management Refactoring Plan

## Problem Statement

The current DM room handling is scattered across `WataClient` and `MatrixServiceAdapter`, with the following issues:

1. **Coupled retrieval and creation**: `getOrCreateDMRoom()` is called on every `sendVoiceMessage()`, mixing the common case (lookup) with the rare case (creation)
2. **Multiple tracking mechanisms**: `dmRoomIds`, `primaryRoomIdByContactUserId`, `roomIdsByContactUserId`, `dmRoomContacts` - all partially overlapping
3. **Race condition workarounds** spread across multiple files instead of encapsulated
4. **No clear API boundary** between "give me the DM room" and "create one if needed"

## Matrix Protocol Constraints

From `docs/wata-matrix-spec.md` (lines 956-984) and [matrix-js-sdk #2672](https://github.com/matrix-org/matrix-js-sdk/issues/2672):

**`m.direct` account data** (the standard mechanism):
```json
{
  "@user1:example.com": ["!room1:example.com", "!room2:example.com"],
  "@user2:example.com": ["!room3:example.com"]
}
```

Key constraints:
- It's **per-user, client-side data** - no server enforcement
- Both parties must maintain their own `m.direct` independently
- The `is_direct: true` flag on room creation/invite is the signal, but not enforced
- **No protocol-level uniqueness** for DM rooms between two users
- The spec explicitly notes: "This is a known Matrix protocol limitation"

**Conclusion**: There's no Matrix mechanism we can leverage to guarantee a single DM room. We must handle duplicates gracefully at the application layer.

## Design Goals

1. **Encapsulate all DM room logic** in a single service: `DMRoomService`
2. **Separate concerns**: lookup (fast, cached) vs. creation (rare, async)
3. **Single source of truth** for userId → roomId mapping
4. **Hide complexity** from callers - they just ask for a room to send to
5. **Deterministic room selection** when duplicates exist (oldest wins)
6. **Future-proof** for potential locking/coordination mechanisms

## Proposed API: `DMRoomService`

```typescript
interface DMRoomService {
  // === Lookup (fast, synchronous) ===

  /**
   * Get the primary DM room ID for a contact (if known).
   * Returns null if no DM room exists in our cache.
   * Does NOT create a room or make network calls.
   */
  getDMRoomId(contactUserId: string): string | null;

  /**
   * Get all known DM room IDs for a contact (for message consolidation).
   * Returns empty array if none known.
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

  // === Creation (async, rate) ===

  /**
   * Ensure a DM room exists with the contact.
   * - First tries to find existing room via sync state + m.direct
   * - Creates new room only if none found
   * - Updates m.direct and internal caches
   *
   * This is the ONLY method that can create DM rooms.
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

## Implementation Strategy

### Phase 1: Extract `DMRoomService` from `WataClient`

1. Create `src/shared/lib/wata-client/dm-room-service.ts`
2. Move all DM room logic from `WataClient.getOrCreateDMRoom()` into the new service
3. Move `updateDMRoomData()`, `dmRoomIds` map
4. Have `WataClient` delegate to `DMRoomService`
5. No external API changes yet - just internal refactoring

### Phase 2: Simplify `MatrixServiceAdapter`

1. Remove duplicate tracking (`primaryRoomIdByContactUserId`, `roomIdsByContactUserId`)
2. Delegate to `WataClient.dmRoomService` for all DM room queries
3. Keep only the adapter-specific concerns (contact objects, event emission)

### Phase 3: Separate Lookup from Creation

1. Add `getDMRoomId()` (synchronous lookup) to the public API
2. Change `sendVoiceMessage(contact)` to:
   ```typescript
   async sendVoiceMessage(target, audio, duration) {
     let roomId: string;
     if (target === 'family') {
       roomId = this.findFamilyRoom()?.roomId;
     } else {
       // Fast path: use cached room
       roomId = this.dmRoomService.getDMRoomId(target.user.id);

       // Slow path: only if no cached room (first message to this contact)
       if (!roomId) {
         roomId = await this.dmRoomService.ensureDMRoom(target.user.id);
       }
     }
     return this.sendVoiceMessageToRoom(roomId, audio, duration);
   }
   ```
3. Callers who just want to display a conversation can use sync lookup

### Phase 4: Future Improvements (not in this PR)

- **Locking mechanism**: Use Matrix room state or custom account data to coordinate room creation
- **Room consolidation**: Detect duplicate rooms and migrate messages to primary
- **Cleanup**: Leave/forget duplicate rooms after migration

## File Changes

### New Files
- `src/shared/lib/wata-client/dm-room-service.ts` - All DM room logic

### Modified Files
- `src/shared/lib/wata-client/wata-client.ts` - Delegate to DMRoomService
- `src/shared/services/MatrixServiceAdapter.ts` - Remove duplicate tracking

### No Changes Needed
- Tests should continue to work (API unchanged externally)
- Documentation updates can follow

## Migration Path

1. **Phase 1**: Internal refactor, no API changes, all tests pass
2. **Phase 2**: Simplify adapter, no API changes, all tests pass
3. **Phase 3**: Add new `getDMRoomId()` method, update `sendVoiceMessage()` internally
4. **Commit after each phase** with working tests

## Key Invariants

1. **Single source of truth**: `DMRoomService` owns all `userId → roomId` mappings
2. **Deterministic selection**: When multiple rooms exist, always pick the oldest by creation timestamp
3. **Lazy creation**: Rooms are only created in `ensureDMRoom()`, never implicitly
4. **Cache invalidation**: `refreshFromSync()` called after each sync batch
5. **Idempotent**: Multiple calls to `ensureDMRoom()` return same room (within session)

## Out of Scope

- Cross-session room deduplication (requires server coordination)
- Message migration between duplicate rooms
- Room cleanup/deletion
- E2E encryption considerations
