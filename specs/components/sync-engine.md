# SyncEngine - Real-time State Synchronization

## Overview

SyncEngine manages the Matrix /sync loop and maintains in-memory state for all joined/invited rooms. It processes sync responses, updates room state (members, timeline, receipts), and emits typed events for state changes. This is part of Layer 2 (State Management) in the three-layer architecture.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should reach SYNCING or PREPARED state after login"
  - Tests: start() performs initial sync and enters background loop
  - Verifies: Sync state transitions to SYNCING/PREPARED
  - Workaround: Uses waitForSync() polling helper

- "should notify sync state changes"
  - Tests: 'synced' event emission
  - Verifies: Callback receives state updates
  - Workaround: Polls states array until PREPARED/SYNCING appears

**test/integration/voice-message-flow.test.ts**
- Implicitly tests timeline event processing via message send/receive
- Workaround: Uses waitForCondition() with polling to verify events arrived

**test/integration/read-receipts.test.ts**
- Tests read receipt processing via receiptUpdated event
- Workaround: Polls for 15 seconds waiting for receipt callback to fire
- Workaround: Calls waitForSync() after receipt to ensure state is updated

**test/integration/edge-cases.test.ts**
- Tests auto-join behavior via membershipChanged event
- Workaround: Polling for room membership to appear

## Responsibilities

- Execute the Matrix /sync loop (initial sync + incremental long-polling)
- Process sync responses and update in-memory room state
- Maintain RoomState objects (members, timeline, receipts, account data)
- Emit events when state changes (timeline events, receipts, membership)
- Handle sync errors with exponential backoff
- Deduplicate timeline events across incremental syncs
- Track next_batch token for incremental sync

## API/Interface

### Constructor

```typescript
constructor(api: MatrixApi, logger?: Logger)
```
- **api**: MatrixApi instance for HTTP requests
- **logger**: Optional logger (defaults to noop)

### Lifecycle

```typescript
setUserId(userId: string): void
```
- **Precondition**: Must be called after login, before start()
- Sets the current user ID for membership detection

```typescript
async start(): Promise<void>
```
- **Precondition**: userId must be set
- Performs initial sync (5 second timeout)
- Starts background sync loop (30 second long-poll)
- Emits 'synced' event after initial sync completes
- **Invariant**: Initial sync completes before background loop starts
- **Error Handling**: If initial sync fails, emits 'error' event but continues to background loop

```typescript
async stop(): Promise<void>
```
- Stops the background sync loop
- Sets isRunning = false (loop exits after current sync completes)
- Does NOT wait for current sync to complete
- **Invariant**: After stop(), no new sync requests are initiated

### Event Emitter

```typescript
on<K extends SyncEngineEventName>(event: K, handler: SyncEngineEvents[K]): void
off<K extends SyncEngineEventName>(event: K, handler: SyncEngineEvents[K]): void
```

**Events:**
- `synced(nextBatch: string)` - After each successful sync cycle
- `roomUpdated(roomId: string, room: RoomState)` - When room state changes
- `timelineEvent(roomId: string, event: MatrixEvent)` - New timeline event arrives
- `receiptUpdated(roomId: string, eventId: string, userIds: Set<string>)` - Read receipts updated
- `membershipChanged(roomId: string, userId: string, membership: string)` - Membership state changed
- `accountDataUpdated(type: string, content: Record<string, any>)` - Global account data updated
- `error(error: Error)` - Sync error occurred

### State Access

```typescript
getRoom(roomId: string): RoomState | null
getRooms(): RoomState[]
getNextBatch(): string | null
setNextBatch(token: string): void
getUserId(): string | null
clear(): void
```

## Invariants

1. **Event Ordering**: Timeline events are stored in chronological order (oldest to newest)
2. **Event Deduplication**: Same event_id never appears twice in a room's timeline
3. **State Consistency**: State events (m.room.member, m.room.name) always reflect latest value
4. **Receipt Accumulation**: Read receipts are additive (userIds added to set, never removed)
5. **Sync Token Progression**: next_batch token is updated after each successful sync
6. **Initial Sync First**: Background loop only starts after initial sync completes (or fails)
7. **Error Resilience**: Sync loop continues despite errors, with exponential backoff

## State

### RoomState Structure

```typescript
interface RoomState {
  roomId: string
  name: string
  avatarUrl: string | null
  canonicalAlias: string | null
  summary?: RoomSummary
  unreadNotifications?: { highlight_count, notification_count }
  members: Map<string, MemberInfo>
  timeline: MatrixEvent[]
  accountData: Map<string, Record<string, any>>
  readReceipts: Map<string, Set<string>>  // eventId → Set<userId>
}
```

### Internal State

- **rooms**: `Map<string, RoomState>` - All known rooms (joined, invited, left)
- **userId**: Current user ID (set via setUserId())
- **nextBatch**: Sync token for incremental sync
- **isRunning**: Whether sync loop is active
- **syncLoopPromise**: Promise for background loop (for cleanup)

## Events

### Event Emission Order (per sync cycle)

1. **accountDataUpdated** - Global account data (m.direct)
2. **roomUpdated** - For each room with state changes
3. **timelineEvent** - For each new timeline event (per room)
4. **receiptUpdated** - For each receipt update (per event)
5. **membershipChanged** - For each membership change (per user)
6. **synced** - After all updates processed

### Event Handler Guarantees

- Handlers are called synchronously in order
- If a handler throws, error is logged but other handlers still run
- Events are emitted in the order they appear in sync response

## Error Handling

### Sync Loop Error Handling

**Exponential Backoff:**
- Initial retry delay: 1 second
- Multiply delay by 2 on each failure
- Maximum delay: 60 seconds
- Jitter: Random 0-1000ms added to delay

**Error Flow:**
1. Sync request fails
2. Emit 'error' event with Error object
3. Check if still running (stop() may have been called)
4. Sleep for retry delay (with jitter)
5. Retry sync request

**Error Types:**
- Network errors (timeout, connection refused)
- Server errors (500, 502, 503)
- Invalid token errors (M_UNKNOWN_TOKEN) - NOT handled, propagates to caller

### State Event Processing

- Invalid state events are skipped (e.g., m.room.member without state_key)
- Unknown event types are stored in timeline but not processed for state
- Redacted events are stored with unsigned.redacted_because field

### Receipt Processing

- Invalid receipt format is silently ignored
- Receipts for unknown events are stored (event may arrive later)
- Receipts are cumulative (no removal on error)

## Known Limitations

1. **No Persistence**: All state is in-memory, lost on restart
2. **No Backfill**: Only syncs forward from next_batch, no timeline backfill
3. **No E2EE**: Does not handle encrypted rooms (m.room.encrypted)
4. **No Typing Indicators**: Ephemeral events except receipts are ignored
5. **No Presence**: Presence events are ignored
6. **No Room State Reset**: State events are only additive, no full state reset handling
7. **Timeline Growth**: Timeline grows unbounded (no automatic pruning)

## Related Specs

- [MatrixApi](./matrix-api.md) - HTTP client used for sync requests
- [Initial Sync Flow](../flows/initial-sync.md) - First sync after login
- [Incremental Sync Flow](../flows/incremental-sync.md) - Ongoing sync loop
- [Auto-Join Invites Flow](../flows/auto-join-invites.md) - membershipChanged event handling
