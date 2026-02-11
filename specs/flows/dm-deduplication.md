# DM Deduplication Flow - Handling Multiple DM Rooms

## Overview

This flow covers the scenario where multiple DM rooms exist with the same contact (due to race conditions or bugs) and how DMRoomService deterministically selects one as the primary room.

## Current Test Coverage

**test/integration/edge-cases.test.ts**
- "should handle concurrent DM room creation"
  - Tests: Both Alice and Bob create DM simultaneously
  - Verifies: Both clients eventually select same primary room
  - Workaround: Polling for room synchronization (up to 35s timeout)
  - Workaround: Uses exponential backoff for room discovery

## Preconditions

1. Multiple DM rooms exist between Alice and Bob:
   - Room A: "!room1:server" (created first, older timestamp)
   - Room B: "!room2:server" (created second, newer timestamp)
2. Both rooms have is_direct = true
3. Both rooms have exactly 2 members (alice, bob) with membership = 'join'
4. m.direct may contain both or neither room

## Flow Steps

### Step 1: Alice Calls ensureDMRoom()

**Component**: DMRoomService.ensureDMRoom()

1. Check cache: `primaryRoomByContact.get("@bob:server")` → null (cache miss)
2. Proceed to scan sync state

### Step 2: Scan Sync State (Multiple Matches)

**Component**: DMRoomService.findExistingDMRoom()

1. Get all rooms from SyncEngine.getRooms()
2. Scan for 2-person rooms with bob:
   - "!room1:server": 2 members, has bob, is_direct = true ✓
   - "!room2:server": 2 members, has bob, is_direct = true ✓
   - Both are candidates!
3. Build candidate list with creation timestamps:
   ```typescript
   [
     { roomId: "!room1:server", creationTs: 1000000, messageCount: 5 },
     { roomId: "!room2:server", creationTs: 2000000, messageCount: 2 }
   ]
   ```
4. Log warning: "Multiple DM rooms with @bob:server: !room1 (2024-01-01, 5 msgs), !room2 (2024-01-02, 2 msgs). Selecting oldest."

### Step 3: Deterministic Selection (Oldest Wins)

**Component**: DMRoomService.selectPrimaryRoom()

**Algorithm**:
1. Sort candidates by creation timestamp (oldest first)
2. Tie-breaker: If timestamps equal, use room ID lexicographic order
3. Select first entry: "!room1:server"

**Invariant**: Given same set of rooms, always returns same primary room

### Step 4: Update Cache with All Rooms

**Component**: DMRoomService.addRoomToCache()

1. `allRoomsByContact.set("@bob:server", Set(["!room1:server", "!room2:server"]))`
2. `primaryRoomByContact.set("@bob:server", "!room1:server")`
3. `contactByRoom.set("!room1:server", "@bob:server")`
4. `contactByRoom.set("!room2:server", "@bob:server")`
5. Return "!room1:server" to caller

### Step 5: Update m.direct (Optional)

**Component**: DMRoomService.updateMDirectForRoom()

1. Fetch current m.direct
2. Update to include primary room:
   ```json
   {
     "@bob:server": ["!room1:server", "!room2:server"]
   }
   ```
3. Push to server via setAccountData()

### Step 6: Subsequent Calls Use Cache

**Component**: DMRoomService.ensureDMRoom()

1. Alice sends another message to Bob
2. Cache hit: `primaryRoomByContact.get("@bob:server")` → "!room1:server"
3. Return "!room1:server" immediately
4. **Consistent**: Always uses same room for new messages

## Postconditions

1. Primary room: "!room1:server" (oldest)
2. Secondary room: "!room2:server" (still accessible via getAllDMRoomIds)
3. Alice's cache:
   - Primary: "!room1:server"
   - All: ["!room1:server", "!room2:server"]
4. Future messages go to "!room1:server"
5. Old messages in "!room2:server" remain accessible (not consolidated)

## Cross-Client Consistency

### Scenario: Alice and Bob Both Run Selection

**Alice's Selection**:
1. Scans rooms: finds Room1 (ts=1000000), Room2 (ts=2000000)
2. Sorts by timestamp: [Room1, Room2]
3. Selects Room1 (oldest)

**Bob's Selection**:
1. Scans rooms: finds Room1 (ts=1000000), Room2 (ts=2000000)
2. Sorts by timestamp: [Room1, Room2]
3. Selects Room1 (oldest)

**Result**: Both clients select same primary room ✓

**Invariant**: Deterministic selection ensures convergence

## Edge Cases

### Same Creation Timestamp

**Scenario**: Rooms created simultaneously (within same millisecond)

**Handling**:
1. Timestamps are equal: creationTs = 1000000
2. Tie-breaker: Sort by room ID lexicographically
3. "!aaaa:server" < "!zzzz:server"
4. Select "!aaaa:server"

**Invariant**: Lexicographic ordering is consistent across all clients

### No Creation Timestamp

**Scenario**: m.room.create event missing origin_server_ts

**Handling**:
1. creationTs = null for affected room
2. Rooms with null timestamp excluded from candidates
3. Fall back to first room in m.direct list
4. **Limitation**: May not be deterministic across clients

### Message Consolidation

**Question**: How to view messages from both rooms?

**Current Behavior**:
- getConversation(bobContact) returns primary room messages only
- getAllDMRoomIds(bobContact) returns all room IDs
- Caller can manually fetch messages from all rooms

**Limitation**: No automatic message merging across duplicate rooms

## Error Paths

### Timestamp Parsing Fails

**Trigger**: origin_server_ts is invalid or missing

**Handling**:
- creationTs = null for that room
- Room excluded from timestamp-based selection
- Falls back to m.direct order or room ID order

### Room Becomes Invalid After Selection

**Trigger**: User leaves primary room, cache not updated

**Handling**:
1. Next ensureDMRoom() call hits cache
2. Validation fails (membership != 'join')
3. removeRoomFromCache()
4. Re-scan and re-select (may pick Room2 now)

**Recovery**: Automatic re-selection on validation failure

## Known Workarounds in Tests

1. **Exponential Backoff**: Test polls with increasing delays (200ms → 260ms → 338ms...)
2. **Long Timeout**: Up to 35s timeout for room sync
3. **Multiple Room Checks**: Tests verify both clients see the same primary room

## Related Specs

- [DMRoomService](../components/dm-room-service.md) - Deduplication algorithm
- [Concurrent DM Creation Flow](./concurrent-dm-creation.md) - How duplicates are created
- [First DM Flow](./first-dm.md) - Normal DM creation
