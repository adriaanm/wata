# Incremental Sync Flow - Ongoing Sync Loop

## Overview

This flow covers incremental /sync requests after the initial sync, delivering only new events since the last sync. This is the steady-state operation that keeps the client in sync with the server.

## Current Test Coverage

**test/integration/matrix.test.ts**
- Implicitly tests via message send/receive flows
- All tests rely on incremental sync to deliver events

**test/integration/voice-message-flow.test.ts**
- Implicitly tests via message propagation
- Workaround: Polling for messages instead of waiting for sync

## Preconditions

1. Initial sync has completed
2. SyncEngine has next_batch token
3. Background sync loop is running (isRunning = true)
4. Rooms loaded in SyncEngine.rooms map

## Flow Steps

### Step 1: Background Loop Calls Sync

**Component**: SyncEngine.runSyncLoop()

1. Check isRunning = true
2. Call MatrixApi.sync():
   ```typescript
   api.sync({
     timeout: 30000,  // 30 second long-poll
     since: next_batch  // "s1234567890_abcdef"
   })
   ```
3. HTTP request sent to server

**Timing**: Immediately after previous sync completes (continuous loop)

### Step 2: Server Long-Poll (Waiting for Events)

**Component**: Matrix Homeserver

**If Events Available**:
- Server returns immediately with new events
- Response time: <100ms

**If No Events**:
- Server holds connection open (long-poll)
- Waits up to timeout (30s) for new events
- If timeout expires with no events, returns empty response
- Response time: 0-30 seconds

**Event Sources**:
- New messages in rooms
- Membership changes (joins, leaves, kicks)
- Read receipts
- Account data updates
- Presence updates (if enabled)

### Step 3: Server Prepares Incremental Response

**Component**: Matrix Homeserver

**For Rooms with Updates**:
- **timeline.events**: Only NEW events since last sync
- **state.events**: Only NEW state changes (rare on incremental)
- **state_after.events**: State changes after timeline (membership during timeline)
- **ephemeral.events**: Current ephemeral state (receipts, typing)

**No Updates for Unchanged Rooms**:
- Rooms with no changes are omitted from response
- Keeps response small

**Response Structure**:
```json
{
  "next_batch": "s1234567891_ghijkl",
  "rooms": {
    "join": {
      "!abc:server": {
        "timeline": {
          "events": [
            { "type": "m.room.message", "event_id": "$new123", ... }
          ],
          "limited": false,
          "prev_batch": "t1234_5679"
        },
        "ephemeral": {
          "events": [
            { "type": "m.receipt", "content": { ... } }
          ]
        }
      }
    }
  }
}
```

**Response Size**: Typically small (1-10 KB), only deltas

### Step 4: MatrixApi Receives Response

**Component**: MatrixApi.sync()

1. Long-poll completes (either events arrived or timeout)
2. Parse JSON response
3. Return SyncResponse to SyncEngine

### Step 5: SyncEngine Processes Delta

**Component**: SyncEngine.processSyncResponse()

**For Each Room with Updates**:
1. Get existing RoomState from rooms map
2. Process timeline.events:
   - **Deduplication**: Check if event_id already in room.timeline
   - If duplicate: Skip (log warning)
   - If new: Append to room.timeline array
   - Emit `timelineEvent(roomId, event)` for each new event
3. Process state_after.events:
   - Update room.members, room.name, etc.
4. Process ephemeral.events:
   - m.receipt → Update room.readReceipts
   - Emit `receiptUpdated(roomId, eventId, userIds)`
5. Emit `roomUpdated(roomId, room)`

**Update Sync Token**:
1. Store next_batch = "s1234567891_ghijkl"
2. Emit `synced(next_batch)`

**Timing**: <10ms for typical delta (1-5 events)

### Step 6: WataClient Processes Updates

**Component**: WataClient (event listeners)

**For Each timelineEvent**:
- If voice message → Emit `messageReceived(message, conversation)`
- If redaction → Emit `messageDeleted(messageId, roomId)`

**For Each receiptUpdated**:
- If voice message receipt → Emit `messagePlayed(message, roomId)`

**For Each roomUpdated**:
- If family room → Emit `familyUpdated(family)`, `contactsUpdated(contacts)`

**Timing**: <10ms per event

### Step 7: Loop Continues

**Component**: SyncEngine.runSyncLoop()

1. Check isRunning = true
2. Reset retry delay to 1s (success)
3. Immediately call sync() again (goto Step 1)

**Continuous**: Loop runs until stop() called

## Postconditions

1. All new events appended to room timelines
2. Read receipts updated
3. next_batch token advanced
4. Events emitted to WataClient
5. UI updated via event handlers
6. Ready for next sync cycle

## Event Deduplication

### Scenario: Same Event Delivered Twice

**Causes**:
- Network retry after timeout
- Server bug
- Overlapping sync windows

**Handling**:
1. SyncEngine checks: `room.timeline.some(e => e.event_id === newEvent.event_id)`
2. If found:
   - Log: "Skipping duplicate event {event_id}"
   - Skip append
   - No timelineEvent emission
3. If not found:
   - Append normally

**Guarantee**: Timeline never contains duplicate event_ids

## Long-Poll Timeout Handling

### Scenario: 30s Timeout with No Events

**Response**:
```json
{
  "next_batch": "s1234567891_ghijkl",
  "rooms": {}  // Empty, no updates
}
```

**Handling**:
1. SyncEngine processes empty response
2. Update next_batch token
3. Emit `synced(next_batch)` (even though no events)
4. Loop continues immediately with new token

**Idle Behavior**: Continuous 30s long-polls, keeping connection alive

## Error Handling

### Network Error

**Trigger**: Connection timeout, network down, server unreachable

**Handling**:
1. fetch() throws error
2. SyncEngine catches in runSyncLoop()
3. Emit `error(err)`
4. Check isRunning (may have been stopped)
5. Sleep for retry delay (exponential backoff: 1s → 2s → 4s → ... → 60s max)
6. Retry sync with same next_batch token

**Recovery**: Automatic retry with backoff

### Server Error (500, 502, 503)

**Trigger**: Server overload, maintenance, crash

**Handling**:
- Same as network error
- Exponential backoff
- Max delay 60s

**Recovery**: Automatic retry

### Invalid Token (M_UNKNOWN_TOKEN)

**Trigger**: Session expired, token revoked

**Handling**:
1. MatrixApi throws M_UNKNOWN_TOKEN error
2. SyncEngine emits `error(err)`
3. Retry loop continues (will keep failing)
4. **No automatic recovery**

**User Action**: Must re-login

### Malformed Response

**Trigger**: Server sends invalid JSON or unexpected schema

**Handling**:
1. JSON.parse() throws or SyncEngine validation fails
2. Emit `error(err)`
3. Retry with exponential backoff
4. **May continue to fail** if server broken

## Performance Characteristics

### Typical Incremental Sync

**Idle (No Events)**:
- Request: ~500 bytes
- Response: ~200 bytes (empty + next_batch)
- Network: 30s long-poll (no transfer during wait)
- Processing: <1ms

**Single Message Event**:
- Response: ~1-2 KB (event + metadata)
- Processing: ~10ms
- End-to-end latency: <30s (depends on when sender sent)

**Burst (10 Messages)**:
- Response: ~10-20 KB
- Processing: ~50ms
- All events processed in one sync cycle

### Network Efficiency

**Bandwidth**:
- Idle: ~0.01 KB/s (30s long-poll, 200 byte response)
- Active (1 msg/min): ~0.05 KB/s
- **Extremely efficient** due to long-polling

**Connection**:
- 1 HTTP connection open continuously
- Keep-alive prevents reconnection overhead
- Server push-like behavior via long-poll

## State Accumulation Over Time

### Timeline Growth

**Problem**: Timeline array grows unbounded

**Current Behavior**:
- All events appended forever
- No automatic pruning
- Memory usage grows linearly with message count

**Limitation**: Long-running clients may use excessive memory

**Future**: Could prune old events (keep last N or last M days)

### Receipt Map Growth

**Problem**: readReceipts map grows with every receipt

**Current Behavior**:
- Receipts accumulated forever
- Even for deleted messages

**Limitation**: Memory usage grows

## Known Workarounds in Tests

1. **Polling Instead of Events**: Tests poll getVoiceMessages() instead of waiting for messageReceived
2. **Fixed Delays**: Tests use sleep() assuming sync will complete within delay
3. **No Sync Timing Verification**: Tests don't verify events arrive within expected time

## Related Specs

- [SyncEngine](../components/sync-engine.md) - Sync loop implementation
- [MatrixApi](../components/matrix-api.md) - sync() endpoint
- [Initial Sync Flow](./initial-sync.md) - First sync
- [Voice Message Receive Flow](./voice-message-receive.md) - How messages arrive via sync
- [Read Receipt Flow](./read-receipt.md) - How receipts arrive via sync
