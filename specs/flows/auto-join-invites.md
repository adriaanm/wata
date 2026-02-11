# Auto-Join Invites Flow - Automatic Invite Acceptance

## Overview

This flow covers WataClient's automatic joining of room invites. In the trusted family environment, all invites are assumed to be legitimate and are automatically accepted via the membershipChanged event handler.

## Current Test Coverage

**test/integration/auto-login.test.ts**
- "alice creates family, bob joins"
  - Tests: Bob auto-joins alice's family invite
  - Workaround: Polling for bob to become member (checks every 500ms, up to 20 attempts)

**test/integration/edge-cases.test.ts**
- Tests auto-join for DM rooms
  - Workaround: Polling for room membership

**test/integration/family-room.test.ts**
- Tests auto-join for family invites
  - Workaround: Polling for contacts list to update

## Preconditions

1. Bob is logged in and syncing
2. Alice invites Bob to a room (family or DM)
3. Invite event delivered via sync to Bob

## Flow Steps

### Step 1: Alice Sends Invite

**Component**: MatrixApi.inviteToRoom() (Alice's client)

1. Alice calls `inviteToFamily("@bob:server")` or creates DM with Bob
2. MatrixApi sends invite:
   - Method: POST
   - Path: `/_matrix/client/v3/rooms/{roomId}/invite`
   - Body: `{ "user_id": "@bob:server" }`
3. Server stores invite, marks Bob's sync dirty

### Step 2: Bob's Sync Receives Invite

**Component**: SyncEngine (Bob)

1. Next /sync includes rooms.invite:
   ```json
   {
     "!abc:server": {
       "invite_state": {
         "events": [
           {
             "type": "m.room.member",
             "state_key": "@bob:server",
             "sender": "@alice:server",
             "content": {
               "membership": "invite",
               "displayname": "Bob"
             }
           },
           {
             "type": "m.room.name",
             "content": { "name": "Our Family" }
           }
         ]
       }
     }
   }
   ```
2. SyncEngine processes invited room

### Step 3: SyncEngine Processes Invite

**Component**: SyncEngine.processInvitedRoom()

1. Get or create RoomState for "!abc:server"
2. Process invite_state.events (stripped state):
   - m.room.member → Set room.members.set("@bob:server", { membership: "invite" })
   - m.room.name → Set room.name
3. Emit `roomUpdated(roomId, room)`
4. Extract membership from m.room.member where state_key = "@bob:server"
5. Emit `membershipChanged("!abc:server", "@bob:server", "invite")`

### Step 4: WataClient Detects Self-Invite

**Component**: WataClient.handleMembershipChanged()

**Event Listener**:
```typescript
this.syncEngine.on('membershipChanged', (roomId, userId, membership) => {
  this.handleMembershipChanged(roomId, userId, membership);
});
```

**Check Condition**:
```typescript
if (userId === this.userId && membership === 'invite') {
  // Auto-join
}
```

**Condition Met**: userId = "@bob:server", this.userId = "@bob:server", membership = "invite" ✓

### Step 5: Auto-Join Room

**Component**: WataClient.handleMembershipChanged()

1. Log: "Auto-joining room {roomId}"
2. Call MatrixApi.joinRoom(roomId):
   - Method: POST
   - Path: `/_matrix/client/v3/join/{roomId}`
   - Body: `{}` (empty, or optional 3PID signature)
3. Server processes join:
   - Validates: Bob is invited
   - Updates: membership = "join"
   - Adds: Bob to room member list
   - Triggers: Sync updates for all room members
4. Server responds: `{ "room_id": "!abc:server" }`

**Network Call**: ~50-200ms

### Step 6: Wait for Join Confirmation

**Component**: WataClient.waitForRoom()

1. Poll SyncEngine.getRoom(roomId) every 100ms
2. Wait for room to appear with membership = "join"
3. Timeout: 3 seconds
4. If timeout: Throw "Timeout waiting for room {roomId}"

**Workaround**: Polling instead of event-driven wait

### Step 7: Refresh DM Cache

**Component**: DMRoomService.refreshFromSync()

1. Called after waitForRoom() succeeds
2. Scans all rooms in SyncEngine
3. Detects 2-person DM rooms with is_direct flag
4. Adds to DM cache if not already tracked

**Purpose**: Discover DM rooms created by other party

### Step 8: Bob's Sync Confirms Join

**Component**: SyncEngine (Bob)

1. Next /sync includes room in rooms.join (no longer in rooms.invite):
   ```json
   {
     "!abc:server": {
       "state": {
         "events": [
           {
             "type": "m.room.member",
             "state_key": "@bob:server",
             "content": { "membership": "join" }
           }
         ]
       },
       "timeline": { "events": [...] }
     }
   }
   ```
2. SyncEngine updates room.members.set("@bob:server", { membership: "join" })
3. Emit `membershipChanged("!abc:server", "@bob:server", "join")`
4. Emit `roomUpdated(roomId, room)`

**Timing**: 0-30s after joinRoom() call

### Step 9: Alice Sees Bob Join

**Component**: SyncEngine (Alice)

1. Alice's sync receives Bob's join event:
   ```json
   {
     "type": "m.room.member",
     "state_key": "@bob:server",
     "content": { "membership": "join" }
   }
   ```
2. SyncEngine updates alice's room.members
3. If family room: Emit `familyUpdated`, `contactsUpdated`

**Timing**: 0-30s after Bob joins

## Postconditions

1. Bob's membership = "join" in the room
2. Bob can send/receive messages in the room
3. DM cache updated (if DM room)
4. Alice sees Bob in contacts (if family room)
5. Room ready for communication

## Auto-Join Policy

### Current Policy: Auto-Join All Invites

**Assumption**: Trusted family environment, all invites are legitimate

**Risk**: No protection against spam or malicious invites

**Mitigation**: Out of scope (family members trust each other)

### Future: Selective Auto-Join

**Possible Rules**:
- Only auto-join from family members
- Only auto-join DM rooms (is_direct = true)
- Require user confirmation for group rooms
- Whitelist/blacklist specific users

**Not Implemented**: Currently auto-joins everything

## Error Paths

### Join Fails (Permission Denied)

**Trigger**: Invite revoked before Bob joins, or Bob banned

**Handling**:
1. MatrixApi.joinRoom() throws M_FORBIDDEN
2. handleMembershipChanged() catches error
3. Log error: "Failed to auto-join room {roomId}: {error}"
4. **No retry** (single attempt)

**State**: Bob remains in "invite" state

### Join Fails (Network Error)

**Trigger**: Network timeout, connection lost

**Handling**:
1. MatrixApi.joinRoom() throws network error
2. handleMembershipChanged() catches error
3. Log error
4. **No retry** (single attempt)

**Recovery**: Bob must manually join or wait for next invite event (if re-synced)

### Timeout Waiting for Room

**Trigger**: Join succeeds but sync doesn't deliver confirmation within 3s

**Handling**:
1. waitForRoom() throws timeout error
2. handleMembershipChanged() catches error
3. Log error
4. refreshFromSync() may still be called (partial recovery)

**State**: Join likely succeeded on server, will eventually sync

### Invite Loop (Alice Re-Invites)

**Trigger**: Bob auto-joins, leaves, Alice re-invites

**Handling**:
- membershipChanged event fires again with "invite"
- Auto-join logic triggers again
- **Potential infinite loop** if join keeps failing

**Mitigation**: None (rare scenario)

## Timing Analysis

| Step | Duration | Notes |
|------|----------|-------|
| 1. Alice invites | 50-200ms | Network call |
| 2. Sync delivers | 0-30s | Long-poll delay |
| 3-4. Process invite | <10ms | Event processing |
| 5. Auto-join call | 50-200ms | Network call |
| 6. Wait for room | 0-3s | Polling (workaround) |
| 7. Refresh cache | <10ms | Room scan |
| 8. Sync confirms | 0-30s | Long-poll delay |
| 9. Alice sees join | 0-30s | Alice's long-poll |

**End-to-End**: Alice invites → Bob joined → Alice sees join: **0-60s**

**Typical**: 5-10 seconds

## Known Workarounds in Tests

1. **Polling for Membership**: Tests poll isRoomMember() or getDirectRooms() to detect join
2. **Long Timeouts**: Tests wait 20-35 seconds for join to complete
3. **Exponential Backoff**: Some tests use increasing poll intervals
4. **No Event Listeners**: Tests don't wait for membershipChanged or roomUpdated events

## Security Considerations

### Malicious Invites

**Risk**: Attacker invites user to spam room, auto-join accepts

**Current Mitigation**: None (assumes trusted environment)

**Future Mitigation**:
- Only auto-join from known contacts
- Require user confirmation for unknown senders
- Rate limit auto-joins

### Invite Bombing

**Risk**: Attacker sends 1000 invites, client auto-joins all

**Current Mitigation**: None

**Future Mitigation**:
- Rate limit auto-joins (e.g., max 10/minute)
- Reject invites from non-family members

## Related Specs

- [WataClient](../components/wata-client.md) - handleMembershipChanged() implementation
- [SyncEngine](../components/sync-engine.md) - Invite processing
- [Initial Setup Flow](./initial-setup.md) - Bob auto-joins family invite
- [First DM Flow](./first-dm.md) - Bob auto-joins DM invite
