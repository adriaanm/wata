# Family Room Testing Plan

## Overview

The new family room functionality needs integration tests to verify:
1. Family room creation
2. User invitation to family room
3. Family member retrieval
4. DM room creation on-demand
5. End-to-end flow from TUI perspective

## Current Issue

The `inviteToFamily` function is failing with:
```
MatrixError: [400] M_BAD_JSON: Failed to deserialize request.
```

This suggests the Matrix SDK's `invite()` call is malformed. Need to investigate and fix.

## Test Infrastructure

### Existing Test Setup

Location: `test/integration/`

- `helpers/TestOrchestrator.ts` - Manages test users (alice, bob), room creation, message sending
- `helpers/TestConduit.ts` - Docker-based Conduit server management
- `voice-messaging.test.ts` - Existing voice message tests

The test setup already:
- Spins up Conduit in Docker
- Creates test users (alice, bob with password `testpass123`)
- Creates rooms and sends messages
- Uses `MatrixService` directly

### Test Users

Pre-configured in Conduit:
- `alice` / `testpass123`
- `bob` / `testpass123`

## New Tests Needed

### 1. Family Room Creation Test

**File:** `test/integration/family-room.test.ts`

```typescript
describe('Family Room', () => {
  describe('createFamilyRoom', () => {
    it('should create a family room with correct alias', async () => {
      // Given: alice is logged in
      // When: alice creates a family room
      // Then: room exists with alias #family:localhost
    });

    it('should fail if family room already exists', async () => {
      // Given: family room already exists
      // When: trying to create another
      // Then: should throw appropriate error
    });
  });
});
```

### 2. Family Room Lookup Test

```typescript
describe('getFamilyRoom', () => {
  it('should return null if no family room exists', async () => {
    // Given: fresh server, no family room
    // When: getFamilyRoom called
    // Then: returns null
  });

  it('should return family room if it exists', async () => {
    // Given: alice created family room
    // When: getFamilyRoom called
    // Then: returns the room
  });

  it('should return family room for invited member', async () => {
    // Given: alice created family room, bob is invited and joined
    // When: bob calls getFamilyRoom
    // Then: returns the room
  });
});
```

### 3. User Invitation Test (PRIORITY - Current Bug)

```typescript
describe('inviteToFamily', () => {
  it('should invite a user by Matrix ID', async () => {
    // Given: alice created family room
    // When: alice invites @bob:localhost
    // Then: bob receives invitation
  });

  it('should allow invited user to join', async () => {
    // Given: alice invited bob
    // When: bob accepts invitation
    // Then: bob is a member of family room
  });

  it('should fail for non-existent user', async () => {
    // Given: alice created family room
    // When: alice invites @nonexistent:localhost
    // Then: appropriate error
  });
});
```

**Debug steps for current bug:**
1. Log the exact request being sent to `/_matrix/client/v3/rooms/{roomId}/invite`
2. Compare with Matrix spec: https://spec.matrix.org/v1.6/client-server-api/#post_matrixclientv3roomsroomidinvite
3. Check if matrix-js-sdk version has known issues
4. Try manual invite via curl to isolate SDK vs server issue

### 4. Family Members Retrieval Test

```typescript
describe('getFamilyMembers', () => {
  it('should return empty array if no family room', async () => {
    // Given: no family room
    // When: getFamilyMembers called
    // Then: returns []
  });

  it('should return empty array if only self in room', async () => {
    // Given: alice created family room, no invites
    // When: alice calls getFamilyMembers
    // Then: returns [] (excludes self)
  });

  it('should return other members', async () => {
    // Given: alice created room, bob joined
    // When: alice calls getFamilyMembers
    // Then: returns [{ userId: '@bob:localhost', displayName: 'bob', ... }]
  });

  it('should use display name from profile', async () => {
    // Given: bob has display name "Bob Smith"
    // When: getFamilyMembers called
    // Then: displayName is "Bob Smith"
  });
});
```

### 5. DM Room Creation Test

```typescript
describe('getOrCreateDmRoom', () => {
  it('should return existing DM room', async () => {
    // Given: alice and bob have existing DM
    // When: alice calls getOrCreateDmRoom('@bob:localhost')
    // Then: returns existing room ID
  });

  it('should create new DM room if none exists', async () => {
    // Given: no DM between alice and bob
    // When: alice calls getOrCreateDmRoom('@bob:localhost')
    // Then: creates room, returns new room ID
  });

  it('should mark room as direct in account data', async () => {
    // Given: alice creates DM with bob
    // When: checking m.direct account data
    // Then: room is listed under bob's user ID
  });
});
```

### 6. End-to-End Flow Test

```typescript
describe('Family Onboarding Flow', () => {
  it('should complete full family setup', async () => {
    // 1. Alice creates family room
    const familyRoomId = await aliceService.createFamilyRoom();
    expect(familyRoomId).toBeTruthy();

    // 2. Alice invites Bob
    await aliceService.inviteToFamily('@bob:localhost');

    // 3. Bob joins family room
    await bobService.joinRoom(familyRoomId);

    // 4. Both see each other as family members
    const aliceMembers = await aliceService.getFamilyMembers();
    expect(aliceMembers).toContainEqual(expect.objectContaining({
      userId: '@bob:localhost'
    }));

    const bobMembers = await bobService.getFamilyMembers();
    expect(bobMembers).toContainEqual(expect.objectContaining({
      userId: '@alice:localhost'
    }));

    // 5. Alice sends message to Bob (creates DM on demand)
    const dmRoomId = await aliceService.getOrCreateDmRoom('@bob:localhost');
    await aliceService.sendVoiceMessage(dmRoomId, ...);

    // 6. Bob receives message
    const messages = bobService.getVoiceMessages(dmRoomId);
    expect(messages.length).toBe(1);
  });
});
```

## Implementation Steps

### Step 1: Debug Current Invite Issue

1. Add logging to `MatrixService.inviteToFamily`:
   ```typescript
   async inviteToFamily(userId: string): Promise<void> {
     const familyRoom = await this.getFamilyRoom();
     log(`[MatrixService] inviteToFamily: roomId=${familyRoom?.roomId}, userId=${userId}`);

     // Try the raw HTTP call to see what's happening
     const response = await this.client?.http.authedRequest(
       Method.Post,
       `/rooms/${encodeURIComponent(familyRoom.roomId)}/invite`,
       undefined,
       { user_id: userId }
     );
     log(`[MatrixService] invite response: ${JSON.stringify(response)}`);
   }
   ```

2. Check if SDK `invite()` method is sending correct body format

3. Test with curl:
   ```bash
   curl -X POST \
     -H "Authorization: Bearer $ACCESS_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"user_id": "@bob:localhost"}' \
     "http://localhost:8008/_matrix/client/v3/rooms/$ROOM_ID/invite"
   ```

### Step 2: Create Test File

Create `test/integration/family-room.test.ts` with:
- Basic setup using existing TestOrchestrator patterns
- Tests for each function in order of dependency

### Step 3: Fix Invite Bug

Based on debugging, fix the `inviteToFamily` method. Possible issues:
- Matrix ID format
- Request body format
- Room ID encoding
- SDK version issue

### Step 4: Implement Remaining Tests

After invite works, implement full test suite.

### Step 5: Add joinRoom to MatrixService

Currently missing - needed for bob to accept invitation:
```typescript
async joinRoom(roomIdOrAlias: string): Promise<void> {
  if (!this.client) throw new Error('Not logged in');
  await this.client.joinRoom(roomIdOrAlias);
}
```

## Files to Modify

1. `src/shared/services/MatrixService.ts`
   - Debug/fix `inviteToFamily`
   - Add `joinRoom` method
   - Add logging for debugging

2. `test/integration/family-room.test.ts` (NEW)
   - All family room tests

3. `test/integration/helpers/TestOrchestrator.ts`
   - Add helpers for family room testing if needed

## Running Tests

```bash
# Start test server
npm run dev:server

# Run family room tests only
npm run test:integration -- --testPathPattern=family-room

# Run with verbose logging
DEBUG=* npm run test:integration -- --testPathPattern=family-room
```

## Success Criteria

1. All tests pass
2. `inviteToFamily` works correctly
3. Full onboarding flow verified
4. TUI admin can create family and invite members

## Notes

- Conduit may have quirks vs Synapse - check Conduit-specific issues
- The matrix-js-sdk `invite()` method signature: `invite(roomId: string, userId: string)`
- Matrix spec for invite: POST `/_matrix/client/v3/rooms/{roomId}/invite` with body `{"user_id": "@user:server"}`
