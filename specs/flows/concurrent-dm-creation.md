# Concurrent DM Creation Flow - Race Condition Handling

## Overview

This flow covers the edge case where both Alice and Bob simultaneously create DM rooms with each other, resulting in two DM rooms. DMRoomService's deterministic selection ensures both clients converge on the same primary room.

## Current Test Coverage

**test/integration/edge-cases.test.ts**
- "should handle concurrent DM room creation"
  - Tests: Both Alice and Bob call getOrCreateDmRoom() simultaneously
  - Verifies: Both clients select same primary room
  - Workaround: Polling for room synchronization (35s timeout)
  - Workaround: Exponential backoff for room discovery

## Preconditions

1. Alice and Bob are both logged in and syncing
2. No DM room exists between them yet
3. Both clients simultaneously send first message to each other
4. Network latency causes race condition

## Flow Steps

### Step 1: Simultaneous Message Send

**Component**: WataClient.sendVoiceMessage() (Both Clients)

**Alice's Client** (at T=0):
1. User Alice sends voice message to Bob
2. WataClient → DMRoomService.ensureDMRoom("@bob:server")
3. Cache miss, sync state scan finds nothing
4. Proceeds to createDMRoom()

**Bob's Client** (at T=0.1s):
1. User Bob sends voice message to Alice
2. WataClient → DMRoomService.ensureDMRoom("@alice:server")
3. Cache miss, sync state scan finds nothing
4. Proceeds to createDMRoom()

**Race Condition**: Both clients creating DM rooms simultaneously

### Step 2: Alice Creates Room First

**Component**: DMRoomService.createDMRoom() (Alice)

1. MatrixApi.createRoom():
   ```typescript
   {
     is_direct: true,
     invite: ["@bob:server"],
     preset: "trusted_private_chat",
     visibility: "private"
   }
   ```
2. Server creates Room A: "!roomA:server"
3. Server assigns creation_ts: 1701234567890
4. Alice's client receives: `{ room_id: "!roomA:server" }`
5. Alice updates m.direct:
   ```json
   { "@bob:server": ["!roomA:server"] }
   ```
6. Alice's cache: primaryRoomByContact.set("@bob:server", "!roomA:server")

### Step 3: Bob Creates Room Second

**Component**: DMRoomService.createDMRoom() (Bob)

1. MatrixApi.createRoom() (same parameters as Alice)
2. Server creates Room B: "!roomB:server"
3. Server assigns creation_ts: 1701234567891 (1ms later)
4. Bob's client receives: `{ room_id: "!roomB:server" }`
5. Bob updates m.direct:
   ```json
   { "@alice:server": ["!roomB:server"] }
   ```
6. Bob's cache: primaryRoomByContact.set("@alice:server", "!roomB:server")

**State**: Two DM rooms exist, neither client knows about the other room yet

### Step 4: Alice Receives Invite to Room B

**Component**: SyncEngine (Alice)

1. Alice's sync receives invite to "!roomB:server" from Bob
2. Auto-join logic triggers
3. Alice joins Room B
4. Alice's sync now shows TWO DM rooms with Bob:
   - Room A (created by Alice, ts: 1701234567890)
   - Room B (created by Bob, ts: 1701234567891)

**Timing**: 0-30s after Bob creates Room B

### Step 5: Bob Receives Invite to Room A

**Component**: SyncEngine (Bob)

1. Bob's sync receives invite to "!roomA:server" from Alice
2. Auto-join logic triggers
3. Bob joins Room A
4. Bob's sync now shows TWO DM rooms with Alice:
   - Room A (created by Alice, ts: 1701234567890)
   - Room B (created by Bob, ts: 1701234567891)

**Timing**: 0-30s after Alice creates Room A

### Step 6: Alice's Next Message (Triggers Deduplication)

**Component**: DMRoomService.ensureDMRoom() (Alice)

**Scenario**: Alice sends another message to Bob

1. Cache hit: "!roomA:server" (from Step 2)
2. Verify room still valid (alice is joined): ✓
3. Return "!roomA:server" immediately
4. Alice continues using Room A

**No Deduplication Yet**: Alice using cached room (Room A)

### Step 7: Bob's Next Message (Triggers Deduplication)

**Component**: DMRoomService.ensureDMRoom() (Bob)

**Scenario**: Bob sends another message to Alice

1. Cache hit: "!roomB:server" (from Step 3)
2. Verify room still valid (bob is joined): ✓
3. Return "!roomB:server" immediately
4. Bob continues using Room B

**Divergence**: Alice using Room A, Bob using Room B

### Step 8: Cache Invalidation (Triggers Re-Scan)

**Component**: DMRoomService (Either Client)

**Trigger**: One of these events causes cache invalidation:
- User logs out and back in
- App restart (cache is in-memory)
- refreshFromSync() called
- Cache entry removed due to left room

**Re-Scan**: ensureDMRoom() scans sync state again

### Step 9: Deterministic Selection (Convergence)

**Component**: DMRoomService.findExistingDMRoom()

**Alice's Re-Scan**:
1. Find 2-person rooms with Bob:
   - Room A: is_direct=true, creationTs=1701234567890 ✓
   - Room B: is_direct=true, creationTs=1701234567891 ✓
2. Sort by creation timestamp (oldest first):
   - [Room A (890), Room B (891)]
3. Select Room A (oldest)
4. Update cache: primaryRoomByContact.set("@bob:server", "!roomA:server")
5. Update m.direct: `{ "@bob:server": ["!roomA:server", "!roomB:server"] }`

**Bob's Re-Scan**:
1. Same scan logic
2. Same candidates: [Room A (890), Room B (891)]
3. Same selection: Room A (oldest)
4. Update cache: primaryRoomByContact.set("@alice:server", "!roomA:server")

**Convergence**: Both clients now use Room A ✓

## Postconditions

1. Two DM rooms exist: Room A (primary), Room B (orphaned)
2. Both clients use Room A for new messages
3. Room B remains accessible via getAllDMRoomIds()
4. Old messages in Room B are not consolidated into Room A
5. Both m.direct entries contain both rooms

## Timeline

```
T=0s     Alice sends msg → creates Room A (ts: 1701234567890)
T=0.1s   Bob sends msg → creates Room B (ts: 1701234567891)
T=5s     Alice syncs → sees invite to Room B → auto-joins
T=5s     Bob syncs → sees invite to Room A → auto-joins
T=10s    Alice sends msg 2 → uses Room A (cached)
T=10s    Bob sends msg 2 → uses Room B (cached)
         [DIVERGENCE: Alice in Room A, Bob in Room B]
T=60s    Alice restarts app → cache cleared → re-scan → Room A (oldest)
T=60s    Bob restarts app → cache cleared → re-scan → Room A (oldest)
         [CONVERGENCE: Both use Room A]
```

## Deterministic Selection Algorithm

### Sorting Logic

```typescript
candidateRooms.sort((a, b) => {
  // Primary: Creation timestamp (oldest first)
  if (a.creationTs !== b.creationTs) {
    return a.creationTs - b.creationTs;
  }
  // Tie-breaker: Room ID lexicographic order
  return a.roomId.localeCompare(b.roomId);
});
```

### Invariant

**Given**: Same set of rooms with same creation timestamps

**Guarantee**: All clients select same primary room

**Proof**:
1. Creation timestamps are server-assigned (same for all clients)
2. Room IDs are server-assigned (same for all clients)
3. Sort is deterministic (stable comparison)
4. Result is identical across all clients

## Edge Cases

### Same Creation Timestamp (Unlikely)

**Scenario**: Rooms created in same millisecond

**Handling**:
- creationTs equal for both rooms
- Tie-breaker: Room ID lexicographic order
- "!aaa:server" < "!zzz:server"
- **Still deterministic** (room IDs are unique and stable)

### No Creation Timestamp

**Scenario**: m.room.create event missing origin_server_ts

**Handling**:
- creationTs = null
- Room excluded from timestamp-based sort
- Falls back to m.direct order or room ID order
- **May not be deterministic** (m.direct order varies by client)

**Limitation**: Rare edge case, no current mitigation

### Three-Way Race

**Scenario**: Alice, Bob, and Charlie all create DM with each other simultaneously

**Handling**:
- Creates 3 rooms (Alice→Bob, Bob→Alice, Charlie→Alice, etc.)
- Each client selects oldest room
- **Convergence still works** (oldest room is global minimum)

### Messages in Orphaned Room

**Scenario**: Bob sent 5 messages to Room B before convergence

**Handling**:
- Room B messages remain in Room B
- Alice doesn't see Bob's old messages from Room B
- **No automatic consolidation**

**Limitation**: Messages split across rooms

## Known Workarounds in Tests

1. **Long Timeout**: Test waits up to 35 seconds for convergence
2. **Exponential Backoff**: Poll interval increases over time
3. **Multiple Verification Points**: Test checks both clients multiple times
4. **No Automatic Trigger**: Test doesn't automatically trigger re-scan, relies on sync timing

## Prevention Strategies (Not Implemented)

### Server-Side Room Lookup

**Idea**: Before creating, query server for existing DM rooms

**Implementation**: Use room directory or custom account data

**Limitation**: Still has race condition window

### Optimistic Locking

**Idea**: Use transaction ID to detect concurrent creation

**Implementation**: Store txnId in room state, reject duplicates

**Limitation**: Requires server support

### Client Coordination

**Idea**: One client always creates (e.g., lexicographically lower user ID)

**Implementation**:
```typescript
if (myUserId < contactUserId) {
  // I create the room
} else {
  // I wait for invite
}
```

**Limitation**: Requires protocol change, delays first message

## Related Specs

- [DMRoomService](../components/dm-room-service.md) - Deduplication algorithm
- [DM Deduplication Flow](./dm-deduplication.md) - How selection works
- [First DM Flow](./first-dm.md) - Normal DM creation
- [Auto-Join Invites Flow](./auto-join-invites.md) - How both clients join both rooms
