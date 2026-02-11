# First DM Flow - Creating First DM with a Contact

## Overview

This flow covers creating the first DM room with a contact. DMRoomService detects no existing DM room and creates a new one, then updates m.direct account data.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should create a direct message room"
  - Tests: getOrCreateDmRoom() when no room exists
  - Verifies: Room ID returned, starts with !

**test/integration/voice-message-flow.test.ts**
- Implicitly tests via TestOrchestrator.createRoom()
- Tests: DM room creation and message send in one flow

## Preconditions

1. Alice and Bob are both logged in and syncing
2. Alice and Bob are family members (both in #family room)
3. No DM room exists between Alice and Bob
4. m.direct account data for Alice is empty or missing Bob

## Flow Steps

### Step 1: Alice Initiates DM

**Component**: WataClient.getConversation()

1. Alice calls `getConversation(bobContact)`
2. WataClient extracts `contactUserId = "@bob:server"`
3. WataClient → DMRoomService.ensureDMRoom(contactUserId)

### Step 2: DMRoomService Cache Check (Miss)

**Component**: DMRoomService.ensureDMRoom()

1. Check `primaryRoomByContact.get("@bob:server")` → null (cache miss)
2. Proceed to scan sync state for existing rooms

### Step 3: Scan Sync State (No Match)

**Component**: DMRoomService.findExistingDMRoom()

1. Get all rooms from SyncEngine.getRooms()
2. For each room:
   - Skip if not joined (membership !== 'join')
   - Count joined members
   - Skip if not exactly 2 members
   - Check if other member is "@bob:server"
   - Check is_direct flag in m.room.create or m.room.member
3. No matching room found → return null

### Step 4: Create DM Room

**Component**: DMRoomService.createDMRoom()

1. MatrixApi.createRoom():
   ```typescript
   {
     is_direct: true,
     invite: ["@bob:server"],
     preset: "trusted_private_chat",
     visibility: "private"
   }
   ```
2. MatrixApi returns `{ room_id: "!abc:server" }`
3. DMRoomService → updateMDirectForRoom()

### Step 5: Update m.direct Account Data

**Component**: DMRoomService.updateMDirectForRoom()

1. Fetch current m.direct via MatrixApi.getAccountData(userId, "m.direct")
   - Response: `{}` (empty, no existing DMs) or `M_NOT_FOUND`
2. Build updated m.direct:
   ```json
   {
     "@bob:server": ["!abc:server"]
   }
   ```
3. MatrixApi.setAccountData(userId, "m.direct", updatedData)

### Step 6: Update DMRoomService Cache

**Component**: DMRoomService.addRoomToCache()

1. `allRoomsByContact.set("@bob:server", Set(["!abc:server"]))`
2. `contactByRoom.set("!abc:server", "@bob:server")`
3. `primaryRoomByContact.set("@bob:server", "!abc:server")`
4. Return "!abc:server" to WataClient

### Step 7: Alice's Sync Receives m.direct Update

**Component**: SyncEngine (Alice)

1. Sync response includes account_data.events:
   ```json
   {
     "type": "m.direct",
     "content": {
       "@bob:server": ["!abc:server"]
     }
   }
   ```
2. SyncEngine emits `accountDataUpdated("m.direct", content)`
3. WataClient.handleAccountDataUpdated() → DMRoomService.handleMDirectUpdate(content)
4. DMRoomService updates caches (already up-to-date in this case)

### Step 8: Alice's Sync Receives Room

**Component**: SyncEngine (Alice)

1. Sync response includes rooms.join["!abc:server"]
2. SyncEngine creates RoomState for "!abc:server"
3. Process state events (m.room.create with is_direct: true)
4. Process membership (alice: join, bob: invite)
5. SyncEngine emits `roomUpdated("!abc:server", roomState)`

### Step 9: WataClient Builds Conversation

**Component**: WataClient.roomToConversation()

1. Get room from SyncEngine.getRoom("!abc:server")
2. Filter timeline for voice messages (empty at this point)
3. Count unplayed messages: 0
4. Return Conversation:
   ```typescript
   {
     id: "!abc:server",
     type: "dm",
     contact: bobContact,
     messages: [],
     unplayedCount: 0
   }
   ```

### Step 10: Bob Receives Invite and Auto-Joins

**Component**: WataClient (Bob)

1. Bob's sync receives invite in rooms.invite["!abc:server"]
2. SyncEngine emits `membershipChanged("!abc:server", "@bob:server", "invite")`
3. WataClient.handleMembershipChanged():
   - Detects self-invite
   - Calls MatrixApi.joinRoom("!abc:server")
4. Bob's sync receives join confirmation
5. DMRoomService.refreshFromSync() discovers DM room
6. DMRoomService adds to cache

### Step 11: Bob's Sync Receives m.direct Update

**Component**: SyncEngine (Bob), **Timing Issue**

Bob's client did NOT initiate the DM creation, so Bob's m.direct may not include Alice yet.

**Expected behavior**: Bob's client should infer DM room from:
- is_direct flag in room
- 2-person membership
- Auto-populate bob's m.direct locally (or rely on server eventually consistent update)

**Current behavior**: DMRoomService.refreshFromSync() populates cache from room scan, even without m.direct entry.

## Postconditions

1. DM room "!abc:server" exists with alice (joined), bob (joined)
2. Alice's m.direct: `{ "@bob:server": ["!abc:server"] }`
3. Bob's m.direct: May or may not include alice (eventually consistent)
4. Both clients have DM room in DMRoomService cache
5. getConversation(bobContact) returns same roomId on subsequent calls

## Error Paths

### Room Creation Fails

**Trigger**: MatrixApi.createRoom() fails (network, permissions, quota)

**Handling**:
- DMRoomService.createDMRoom() throws error
- ensureDMRoom() propagates error to WataClient
- getConversation() fails
- **No state change** (cache remains empty, can retry)

### m.direct Update Fails

**Trigger**: MatrixApi.setAccountData() fails

**Handling**:
- DMRoomService logs error but does NOT throw
- Room still usable (cached in DMRoomService)
- **Limitation**: Other clients won't see this DM room in m.direct
- **Recovery**: m.direct can be fixed manually or via refreshFromSync on other device

### Bob Doesn't Auto-Join

**Trigger**: Auto-join fails or is delayed

**Handling**:
- Alice sees room with bob in "invite" state
- Alice can send messages, but bob won't receive until joined
- **Recovery**: Bob must manually join or wait for auto-join retry

### Cache Desync

**Trigger**: Alice creates DM on device A, then uses device B before sync

**Handling**:
- Device B has stale cache (no DM room)
- ensureDMRoom() scans sync state → finds existing room
- Updates cache and m.direct
- **Convergence**: Both devices eventually agree on primary room

## Known Workarounds in Tests

1. **Polling for Membership**: Tests poll isRoomMember() to detect bob's join
2. **No m.direct Verification**: Tests don't verify m.direct was updated correctly
3. **Fixed Delays**: Tests use sleep() instead of waiting for specific events

## Related Specs

- [DMRoomService](../components/dm-room-service.md) - DM creation logic
- [Auto-Join Invites Flow](./auto-join-invites.md) - Bob's auto-join behavior
- [Repeat DM Flow](./repeat-dm.md) - What happens on second message
- [Concurrent DM Creation Flow](./concurrent-dm-creation.md) - Race conditions
