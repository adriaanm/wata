# Integration Test Improvements - Feb 11, 2026

## Problem Statement

Integration tests were failing with timing sensitivity and state accumulation issues:
- 25 "Room not found" errors in initial runs
- Tests assumed empty server state
- Hardcoded `setTimeout` waits (20-30 seconds)
- Message verification by position instead of ID
- Stress tests completely disabled

## Root Cause

The `getOrCreateDmRoom()` method in the DM room service **finds and reuses existing rooms** from previous test runs. When test clients logged in fresh, they weren't members of these old rooms, causing systematic "Room not found" errors.

## Complete Solution

### 1. Raw HTTP API for Room Creation

**File:** `test/integration/helpers/test-client.ts`

**Problem:** `getOrCreateDmRoom()` reuses rooms, ignoring unique names.

**Solution:** Bypass DM room service entirely using Matrix HTTP API:

```typescript
async createRoom(options): Promise<{ room_id: string }> {
  const accessToken = await this.getAccessToken();

  const response = await fetch(`${this.homeserverUrl}/_matrix/client/v3/createRoom`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      is_direct: options.is_direct,
      invite: options.invite,
      preset: options.preset || 'trusted_private_chat',
      name: options.name, // Unique name prevents reuse
      ...
    }),
  });

  const result = await response.json();
  await this.waitForRoom(result.room_id, 25000); // Increased timeout
  return result;
}
```

**Key changes:**
- Direct HTTP API calls instead of `getOrCreateDmRoom()`
- Respects unique `name` parameter
- 25s timeout (was 5s) for room sync

### 2. Unique Room Names

**File:** `test/integration/helpers/test-orchestrator.ts`

**Solution:** Add unique names to prevent DM service from matching old rooms:

```typescript
async createRoom(owner: string, ...participants: string[]): Promise<string> {
  // Unique name prevents room reuse across test runs
  const uniqueName = `test-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

  const result = await ownerClient.createRoom({
    is_direct: true,
    invite: participants.map(u => `@${u}:localhost`),
    preset: 'trusted_private_chat',
    name: uniqueName, // Critical for preventing reuse
  });

  // Increased timeouts for accumulated state
  await ownerClient.waitForRoom(roomId, 15000); // was 10s
  await participant.waitForRoom(roomId, 20000); // was 15s
  ...
}
```

### 3. Adaptive Polling for Event IDs

**File:** `test/integration/helpers/test-orchestrator.ts`

**Problem:** Fixed 200ms polling was too slow for some scenarios.

**Solution:** Adaptive polling with re-pagination:

```typescript
async waitForEventIds(username, roomId, eventIds, timeoutMs = 30000) {
  await this.paginateTimeline(username, roomId, 100);

  let checkCount = 0;
  while (eventIds.size > 0 && Date.now() - startTime < timeoutMs) {
    checkCount++;
    const messages = await this.getAllVoiceMessages(username, roomId, 100);

    // Check which event IDs are present
    for (const msg of messages) {
      if (eventIds.has(msg.eventId)) {
        eventIds.delete(msg.eventId);
      }
    }

    if (eventIds.size > 0) {
      // Adaptive polling: 100ms → 500ms
      const pollDelay = Math.min(100 + checkCount * 10, 500);
      await new Promise(resolve => setTimeout(resolve, pollDelay));

      // Re-paginate every 10 checks
      if (checkCount % 10 === 0) {
        await this.paginateTimeline(username, roomId, 100);
      }
    }
  }
}
```

### 4. Event ID Verification

**File:** `test/integration/matrix.test.ts`

**Problem:** Checking "last message" fails with accumulated state.

**Solution:** Find messages by event ID:

```typescript
// Before: Assumes last message is ours
const lastMessage = aliceMessages[aliceMessages.length - 1];
expect(lastMessage.duration).toBe(3000);

// After: Find our specific message
const eventId = await aliceService.sendVoiceMessage(...);
await waitForCondition(
  'message with event ID in timeline',
  () => aliceService.getVoiceMessages(testRoomId).some(m => m.eventId === eventId)
);
const sentMessage = aliceMessages.find(m => m.eventId === eventId);
expect(sentMessage!.duration).toBe(3000);
```

### 5. Stress Test Fixes

**File:** `test/integration/stress-tests.test.ts`

**Problems:**
- Hardcoded `setTimeout` waits (20-30 seconds)
- Accepted only 50-60% message delivery
- Entire suite disabled with `describe.skip`

**Solutions:**

```typescript
// Before: Hardcoded wait
await new Promise(resolve => setTimeout(resolve, 20000));
const messages = await orchestrator.getAllVoiceMessages('bob', roomId, 50);
expect(messages.length).toBeGreaterThanOrEqual(18); // Only 60%!

// After: Wait for specific event IDs
await orchestrator.waitForEventIds('bob', roomId, new Set(eventIds), 90000);
const messages = await orchestrator.getAllVoiceMessages('bob', roomId, 50);
const ourMessages = messages.filter(m => eventIds.includes(m.eventId));
expect(ourMessages.length).toBe(30); // 100%!
```

**Re-enabled entire suite:** Removed `describe.skip`

## Testing Philosophy

### Handling Accumulated Server State

Tests now assume the server has accumulated state from previous runs:

**DO:**
- ✅ Create fresh rooms with unique names
- ✅ Verify messages by event ID
- ✅ Filter to test-specific messages when checking counts
- ✅ Use `toBeGreaterThanOrEqual` for accumulated counts
- ✅ Wait for specific event IDs instead of hardcoded times

**DON'T:**
- ❌ Assume you're the only room between two users
- ❌ Check "last message" without verifying event ID
- ❌ Use hardcoded `setTimeout` waits
- ❌ Expect exact message counts without filtering
- ❌ Assume room creation is instant

## Files Modified

1. **test/integration/helpers/test-client.ts**
   - Added `getAccessToken()` helper
   - Modified `createRoom()` to use HTTP API
   - Increased timeout to 25s

2. **test/integration/helpers/test-orchestrator.ts**
   - Added unique room names
   - Implemented adaptive polling in `waitForEventIds()`
   - Increased timeouts to 15-20s
   - Better error messages with event ID details

3. **test/integration/matrix.test.ts**
   - Changed "should receive messages in room timeline" to use event ID

4. **test/integration/stress-tests.test.ts**
   - Replaced all `setTimeout` waits with `waitForEventIds()`
   - Filter to test-specific messages
   - Expect 100% delivery instead of 50-60%
   - Removed `describe.skip` to re-enable suite

## Expected Results

**Before:**
- 25 "Room not found" errors
- Tests fail on second run due to state pollution
- Stress tests disabled
- Flaky timing-dependent tests

**After:**
- 0 "Room not found" errors
- Tests work with accumulated server state
- Stress tests enabled and robust
- Event ID-based verification is deterministic

## Additional Fix: Join-Then-Wait Pattern

**Problem:** After implementing HTTP API room creation, tests still failed with "Room not found after 25000ms" even though unique rooms were being created.

**Root Cause:** The orchestrator called `waitForRoom()` for participants BEFORE joining. But `waitForRoom()` only checks for joined rooms (`isRoomMember()` returns `true` only for `membership === 'join'`). Participants invited to a room couldn't see it until after joining, creating a chicken-and-egg problem.

**Solution:** Modified test-orchestrator.ts to join FIRST, then wait:

```typescript
// BEFORE: Wait for room, then join
await client.waitForRoom(roomId, 20000); // Fails for invited users!
await client.joinRoom(roomId);

// AFTER: Join first with retries, then wait
let joined = false;
let attempts = 0;
const maxAttempts = 5; // Increased from 3

while (!joined && attempts < maxAttempts) {
  try {
    attempts++;
    await client.joinRoom(roomId);
    joined = true;
  } catch (error) {
    if (attempts < maxAttempts) {
      // Exponential backoff: 1s, 2s, 3s, 4s
      await new Promise(resolve => setTimeout(resolve, 1000 * attempts));
    }
  }
}

// After joining, wait for room to appear in participant's list
if (joined) {
  await client.waitForRoom(roomId, 15000);
}
```

This eliminates the chicken-and-egg problem by joining immediately upon seeing the invite.

## Remaining Work

**family-room.test.ts** still has issues:
- Uses old `waitForCondition` pattern instead of TestOrchestrator
- Creates rooms directly without unique names
- Should be migrated to TestOrchestrator pattern

## References

- [DM Room Service Documentation](dm-room-service.md) - Explains why `getOrCreateDmRoom` reuses rooms
- [Testing Documentation](testing.md) - Test running instructions
