# Wata Project Memory

## Git Workflow
- After `git add`, always run `git status --short` to verify only expected files are staged before committing
- The project root is `/Users/adriaan/g/wata`, not subdirectories like `test/docker/`

## Buffer and Retry Pattern

When information needed for decision-making arrives asynchronously, buffer events until all information is available.

**Example:** Matrix DM messages arrive before `m.direct` account data or `is_direct` flag.

**Implementation:**
1. Buffer events that can't be processed yet
2. Retry frequently (e.g., every 300ms) to check if conditions are met
3. Prune stale events after a timeout (e.g., 5 minutes)

**Key insight:** Don't use heuristics to guess. Wait for definitive information - it usually arrives within milliseconds.

```
Event arrives → Can we process? → No → Buffer
                    ↓ Yes
              Process immediately

Retry timer (300ms) → Check buffered events → Can process now? → Flush
```

**See:** `src/shared/lib/wata-client/event-buffer.ts`, `docs/dm-room-service.md`

## Integration Test Patterns

Apply the same buffer-and-retry philosophy to tests:

**Poll fast, timeout fast:**
- Poll every 100ms (not exponential backoff)
- Short timeouts: 5-10 seconds (not 30+)
- Things happen quickly; don't wait longer than needed

**No exponential backoff in tests:**
- Exponential backoff is for production resilience
- Tests should fail fast with predictable timing
- Log elapsed time to debug slow operations

**See:** `test/integration/helpers/test-client.ts` - `waitForRoom`, `waitForMessage`, `waitForCondition`

## DM Room Detection

**Check ALL member events for `is_direct` flag:**
When Bob joins a room created by Alice, Bob's join event may not have `is_direct=true` (only the invite did). The fix: check ALL member events in the room for the flag, not just the current user's.

**getDirectRooms() must query DMRoomService cache:**
Rooms weren't appearing until a message was received. The fix: include rooms from DMRoomService cache in `getDirectRooms()`, not just rooms that have received messages.

**See:** `src/shared/lib/wata-client/dm-room-service.ts` - `hasIsDirectFlag`, `getAllKnownDMRoomIds`

## Test Infrastructure

**Tests MUST use production code paths:**
- ✅ Use WataClient → DMRoomService for DM room creation
- ✅ Properly registers rooms with `m.direct` account data
- ❌ Don't call Matrix API directly (bypasses DM detection)

**DM Room Reuse is REQUIRED:**
- Production behavior: `getOrCreateDmRoom()` reuses existing DM rooms
- Tests MUST account for room reuse between test runs
- Use event IDs to verify specific messages, not message counts
- Per spec: "You cannot rely on message counts" (specs/README.md:167)

**See:** `test/integration/helpers/test-orchestrator.ts`

## Message Delivery Bug: Rapid Sends

**CRITICAL BUG FOUND:** Messages are lost when sent rapidly (2026-02-11)

**Symptoms:**
- Slow sends (one-by-one with verification): ✅ All messages delivered
- Rapid sends (20 messages sequential): ❌ ~50% message loss

**Diagnosis:**
- Event IDs are correctly returned from server
- Missing messages never arrive in receiver's timeline
- Not a test artifact - real message loss

**Likely causes:**
1. Server-side: Conduit may drop messages under load
2. Client-side: Race condition in concurrent upload/send
3. Sync issue: Messages sent faster than sync delivers

**See:** `test/integration/diagnose-message-loss.test.ts`

**TODO:** Investigate WataClient send path for concurrency issues
