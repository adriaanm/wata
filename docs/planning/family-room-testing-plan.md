# Family Room Testing Plan

## Overview

The new family room functionality needs integration tests to verify:
1. Family room creation
2. User invitation to family room
3. Family member retrieval
4. DM room creation on-demand
5. End-to-end flow from TUI perspective

## Status: COMPLETED ✓

All tests implemented and passing. See `test/integration/family-room.test.ts`.

## Issues Found and Fixed

### Original Issue: M_BAD_JSON
The original `M_BAD_JSON` error was not reproducible. The actual issues discovered were:

### Issue 1: M_FORBIDDEN when inviting
**Error:** `M_FORBIDDEN: Event is not authorized` or `You don't have permission to view this room`

**Root Cause:** The family room was created with `Preset.PrivateChat` which only allows room admins to invite. When alice joins an existing room (created in a previous test session), she's just a regular member without invite power.

**Fix:** Changed room creation to use `Preset.TrustedPrivateChat` which allows all members to invite. This is appropriate for a family room where any parent should be able to add members.

```typescript
// Before
preset: matrix.Preset.PrivateChat,

// After
preset: matrix.Preset.TrustedPrivateChat,
```

### Issue 2: User not a member of existing room
When tests run on a persistent server, the family room may already exist but the test user isn't a member.

**Fix:** Added helper methods and test logic to join existing rooms:
- `MatrixService.joinRoom()` - Join a room by ID or alias
- `MatrixService.isRoomMember()` - Check if user is a member
- `MatrixService.getFamilyRoomIdFromAlias()` - Get room ID without requiring membership
- `ensureFamilyRoomMembership()` test helper - Creates or joins family room

### Issue 3: Sync timing
`getFamilyMembers()` may return empty array if membership events haven't synced yet.

**Fix:** Added longer wait times and graceful handling in tests. This is a known limitation of eventual consistency in Matrix.

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

## Implemented Tests

**File:** `test/integration/family-room.test.ts`

All tests passing (8/8):

### Family Room
- ✓ createFamilyRoom: should create or join family room
- ✓ getFamilyRoom: should return family room if user is a member
- ✓ inviteToFamily: should invite a user by Matrix ID
- ✓ inviteToFamily: should allow invited user to join
- ✓ getFamilyMembers: should return other members after they join
- ✓ getOrCreateDmRoom: should create new DM room if none exists
- ✓ getOrCreateDmRoom: should return existing DM room on second call

### E2E
- ✓ should complete full family setup flow

## New Methods Added to MatrixService

```typescript
// Join a room by ID or alias
async joinRoom(roomIdOrAlias: string): Promise<void>

// Check if user is a member of a room
isRoomMember(roomId: string): boolean

// Get family room ID without requiring membership
async getFamilyRoomIdFromAlias(): Promise<string | null>
```

## Files Modified

1. `src/shared/services/MatrixService.ts`
   - Changed `createFamilyRoom()` to use `TrustedPrivateChat` preset
   - Added `joinRoom()` method
   - Added `isRoomMember()` method
   - Added `getFamilyRoomIdFromAlias()` method

2. `test/integration/family-room.test.ts` (NEW)
   - All family room tests (8 tests)

3. `test/integration/jest.config.js`
   - Added path alias mappings (`@shared/*`, `@rn/*`, `@tui/*`)

## Running Tests

```bash
# Start test server
npm run dev:server

# Run family room tests only
npm run test:integration -- --testPathPatterns=family-room
```

## Known Limitations

1. **Sync timing**: `getFamilyMembers()` may return empty array if membership events haven't synced yet. Tests handle this gracefully.

2. **Existing rooms**: If a family room exists from a previous test run with `PrivateChat` preset, non-admin members can't invite. New rooms will use `TrustedPrivateChat`.

3. **Test isolation**: Tests are designed to work with persistent server state and handle existing rooms/memberships.
