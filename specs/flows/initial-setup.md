# Initial Setup Flow - Family Room Creation and First Member Invitation

## Overview

This flow covers the first-time setup: creating a family room with the #family alias and inviting the first family member. This establishes the family group that all contacts belong to.

## Current Test Coverage

**test/integration/auto-login.test.ts**
- "alice creates family, bob joins"
  - Tests: Complete setup flow (login, createFamily, inviteToFamily)
  - Workaround: Polling for family room to appear in alice's rooms
  - Workaround: Polling for bob to auto-join (checks membership every 500ms)

**test/integration/family-room.test.ts**
- "should create family room and list members"
  - Tests: createFamily() and getContacts()
  - Workaround: Polling for invited member to appear in contacts list

**test/integration/contacts.test.ts**
- "alice invites bob to family, contacts update"
  - Tests: inviteToFamily() and contactsUpdated event
  - Workaround: Polling for contacts to update after invite

## Preconditions

1. User A (family creator) has logged in and connected
2. User B (invitee) has registered account but not yet in family
3. No #family room exists for this homeserver

## Flow Steps

### Step 1: Alice Creates Family Room

**Component**: WataClient.createFamily()

1. Alice calls `createFamily("Our Family")`
2. WataClient → MatrixApi.createRoom():
   - `name: "Our Family"`
   - `visibility: "private"`
   - `preset: "private_chat"`
   - `room_alias_name: "family"` → #family:server
3. MatrixApi returns `{ room_id: "!xyz:server" }`
4. WataClient caches `familyRoomId = "!xyz:server"`
5. WataClient polls SyncEngine.getRoom(roomId) until room appears (max 5s)
6. WataClient returns Family object:
   ```typescript
   {
     id: "!xyz:server",
     name: "Our Family",
     members: [] // No members yet (only alice, excluded from list)
   }
   ```

**Events Emitted**:
- SyncEngine: `roomUpdated(!xyz:server, roomState)`
- WataClient: `familyUpdated(family)`, `contactsUpdated([])`

### Step 2: Alice Invites Bob

**Component**: WataClient.inviteToFamily()

1. Alice calls `inviteToFamily("@bob:server")`
2. WataClient finds family room via cached `familyRoomId`
3. WataClient → MatrixApi.inviteToRoom(familyRoomId, { user_id: "@bob:server" })
4. MatrixApi sends invite
5. Returns immediately (does NOT wait for bob to join)

**Events Emitted**: None (invite is state event, arrives via sync)

### Step 3: Alice Sees Invite in Sync

**Component**: SyncEngine, WataClient

1. Alice's sync receives m.room.member event:
   - `state_key: "@bob:server"`
   - `content.membership: "invite"`
2. SyncEngine updates room.members.set("@bob:server", { membership: "invite", ... })
3. SyncEngine emits `roomUpdated(familyRoomId, roomState)`
4. WataClient.handleRoomUpdated():
   - Detects family room update
   - Builds contacts list (filters membership = 'join', excludes invites)
   - Emits `contactsUpdated([])` (bob not yet joined)

### Step 4: Bob Receives Invite via Sync

**Component**: SyncEngine (Bob's client)

1. Bob's sync receives invite in `rooms.invite`:
   ```json
   {
     "!xyz:server": {
       "invite_state": {
         "events": [
           { "type": "m.room.member", "state_key": "@bob:server", "content": { "membership": "invite" } },
           { "type": "m.room.name", "content": { "name": "Our Family" } }
         ]
       }
     }
   }
   ```
2. SyncEngine creates RoomState for "!xyz:server" with membership = "invite"
3. SyncEngine emits `membershipChanged(familyRoomId, "@bob:server", "invite")`

### Step 5: Bob Auto-Joins (WataClient Auto-Join Logic)

**Component**: WataClient.handleMembershipChanged()

1. WataClient receives `membershipChanged` event
2. Checks: `userId === this.userId && membership === "invite"`
3. Calls MatrixApi.joinRoom(familyRoomId)
4. Waits for room to appear in sync (waitForRoom with 3s timeout)
5. Calls DMRoomService.refreshFromSync() to discover potential DM rooms

**Events Emitted**: None yet (waiting for join to propagate)

### Step 6: Bob Sees Join in Sync

**Component**: SyncEngine (Bob's client)

1. Bob's sync receives room in `rooms.join`:
   ```json
   {
     "!xyz:server": {
       "state": { "events": [...] },
       "timeline": { "events": [...] }
     }
   }
   ```
2. SyncEngine updates room.members.set("@bob:server", { membership: "join" })
3. SyncEngine emits `roomUpdated(familyRoomId, roomState)`
4. WataClient emits `familyUpdated(family)`, `contactsUpdated([alice])`

### Step 7: Alice Sees Bob Join

**Component**: SyncEngine (Alice's client)

1. Alice's sync receives m.room.member event:
   - `state_key: "@bob:server"`
   - `content.membership: "join"`
2. SyncEngine updates room.members.set("@bob:server", { membership: "join" })
3. SyncEngine emits `roomUpdated(familyRoomId, roomState)`
4. WataClient.handleRoomUpdated():
   - Builds contacts list: [{ user: { id: "@bob:server", displayName: "bob", ... } }]
   - Emits `contactsUpdated([bob])`

## Postconditions

1. #family:server room exists with alice and bob as joined members
2. Alice's contacts list: [bob]
3. Bob's contacts list: [alice]
4. Both clients have familyRoomId cached
5. Both clients can send messages to family room

## Error Paths

### Family Already Exists

**Trigger**: Alice calls createFamily() when #family alias already exists

**Handling**:
- MatrixApi.createRoom() returns M_ROOM_IN_USE error
- WataClient propagates error to caller
- No state change

### Invite Fails (User Not Found)

**Trigger**: Alice invites non-existent user

**Handling**:
- MatrixApi.inviteToRoom() returns M_NOT_FOUND error
- WataClient propagates error to caller
- Family room remains, just without the invited user

### Auto-Join Fails

**Trigger**: Bob's auto-join fails (network error, server error)

**Handling**:
- WataClient.handleMembershipChanged() logs error
- Bob remains in "invite" state
- Alice sees bob as invited (not in contacts list)
- **Recovery**: Bob must manually join or retry

### Timeout Waiting for Room

**Trigger**: After createFamily(), room doesn't appear in sync within 5s

**Handling**:
- WataClient.waitForRoom() throws timeout error
- createFamily() fails with "Timeout waiting for room"
- Room may still exist on server (check via getRoomIdForAlias)

## Known Workarounds in Tests

1. **Polling for Family Room**: Tests poll getFamily() instead of waiting for familyUpdated event
2. **Polling for Membership**: Tests poll isRoomMember() or getDirectRooms() to detect bob's join
3. **Polling for Contacts**: Tests poll getContacts() instead of waiting for contactsUpdated event
4. **Fixed Delays**: Tests use arbitrary `await sleep(2000)` instead of event-driven waits

## Related Specs

- [WataClient](../components/wata-client.md) - Family and invite methods
- [SyncEngine](../components/sync-engine.md) - Room and membership updates
- [Auto-Join Invites Flow](./auto-join-invites.md) - Details of auto-join logic
