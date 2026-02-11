# Read Receipt Flow - Read Receipt Propagation

## Overview

This flow covers marking a voice message as played (sending a read receipt), the receipt propagating via sync, and the sender seeing the playedBy list update.

## Current Test Coverage

**test/integration/read-receipts.test.ts**
- "bob marks message as played, alice sees readBy update"
  - Tests: Complete receipt flow
  - Verifies: Bob sends receipt, Alice sees Bob in playedBy
  - Workaround: 15s polling for onReceiptUpdate callback to fire
  - Workaround: Extra waitForSync() call after receipt to ensure matrix-js-sdk state updates

- "receipt callback fires when message is played"
  - Tests: onReceiptUpdate callback mechanism
  - Verifies: Callback fires with correct roomId
  - Workaround: 15s polling for callback flag

**test/integration/voice-message-flow.test.ts**
- "should include playedBy when alice marks message as played" (in comments)
  - Gap: Doesn't test receipt event structure or timing

## Preconditions

1. Alice sent voice message to Bob in DM room "!abc:server"
2. Message event_id: "$msg123"
3. Bob received the message (via Receive Flow)
4. Bob's UI shows the message
5. Bob has NOT yet marked it as played (playedBy = [])

## Flow Steps

### Step 1: Bob Marks Message as Played

**Component**: WataClient.markAsPlayed() or markAsPlayedById()

**Option A: Using VoiceMessage object**
```typescript
await bobClient.markAsPlayed(voiceMessage)
```

**Option B: Using room + event ID**
```typescript
await bobClient.markAsPlayedById(roomId, eventId)
```

Both call MatrixApi.sendReadReceipt(roomId, eventId)

### Step 2: Send Read Receipt to Server

**Component**: MatrixApi.sendReadReceipt()

1. Build HTTP request:
   - Method: POST
   - Path: `/_matrix/client/v3/rooms/{roomId}/receipt/m.read/{eventId}`
   - Headers: `Authorization: Bearer {token}`
   - Body: `{}` (empty) or `{ "thread_id": "..." }` if threading
2. Server receives receipt:
   - Validates: Bob is in room, event exists
   - Stores receipt: Marks all events up to eventId as read by Bob
   - Triggers: Sync updates for all room members (including Alice)
3. Server responds: `{}` (empty success response)

**Network Call**: ~50-200ms

### Step 3: Bob's Local State Update (Optimistic)

**Component**: WataClient.markAsPlayedById()

1. Find event in Bob's timeline
2. If found and is voice message:
   - Build updated VoiceMessage:
     ```typescript
     {
       ...message,
       isPlayed: true,
       playedBy: [...message.playedBy, "@bob:server"]
     }
     ```
   - Emit `messagePlayed(updatedMessage, roomId)`
3. Bob's UI immediately updates (optimistic)

**Timing**: Before sync confirms receipt

### Step 4: Bob's Sync Receives Receipt Confirmation

**Component**: SyncEngine (Bob)

1. Next /sync includes ephemeral.events for the room:
   ```json
   {
     "type": "m.receipt",
     "content": {
       "$msg123": {
         "m.read": {
           "@bob:server": { "ts": 1701234567890 }
         }
       }
     }
   }
   ```
2. SyncEngine.processReceiptEvent():
   - Extract eventId = "$msg123"
   - Extract userIds who read: ["@bob:server"]
   - Update room.readReceipts.set("$msg123", Set(["@bob:server"]))
   - Emit `receiptUpdated(roomId, "$msg123", Set(["@bob:server"]))`
3. WataClient.handleReceiptUpdated():
   - Find event in timeline
   - If voice message, emit `messagePlayed(message, roomId)` again
   - **Deduplication**: playedBy already includes bob (from optimistic update)

**Timing**: 0-30s after sending receipt

### Step 5: Alice's Sync Receives Receipt

**Component**: SyncEngine (Alice)

1. Alice's next /sync includes ephemeral.events:
   ```json
   {
     "type": "m.receipt",
     "content": {
       "$msg123": {
         "m.read": {
           "@bob:server": { "ts": 1701234567890 }
         }
       }
     }
   }
   ```
2. SyncEngine.processReceiptEvent():
   - Update alice's room.readReceipts.set("$msg123", Set(["@bob:server"]))
   - Emit `receiptUpdated(roomId, "$msg123", Set(["@bob:server"]))`

**Timing**: 0-30s after Bob sends receipt

### Step 6: Alice's WataClient Processes Receipt

**Component**: WataClient.handleReceiptUpdated()

1. Listener triggered: `receiptUpdated(roomId, eventId, userIds)`
2. Verify event is in room timeline
3. Check if voice message event
4. Convert event to VoiceMessage:
   - playedBy = room.readReceipts.get("$msg123") = Set(["@bob:server"])
   - isPlayed = playedBy.includes("@alice:server") → false (Alice hasn't played her own message)
5. Emit `messagePlayed(voiceMessage, roomId)`

**Timing**: <10ms after sync delivers receipt

### Step 7: Alice's UI Updates

**Component**: Frontend (TUI/Android/Web)

1. messagePlayed event handler triggered
2. Find message in conversation by event_id
3. Update message state:
   - playedBy: ["@bob:server"]
   - Show "read by bob" indicator
4. Re-render conversation view

**UI**: Alice sees that Bob played the message

## Postconditions

1. Bob's state:
   - message.isPlayed = true
   - message.playedBy = ["@bob:server"]
2. Alice's state:
   - message.isPlayed = false (Alice is sender, hasn't played own message)
   - message.playedBy = ["@bob:server"]
3. Server state:
   - Read receipt stored for @bob:server on $msg123
4. Both clients ready for next message

## Timing Analysis

| Step | Duration | Notes |
|------|----------|-------|
| 1. Mark as played | <10ms | Local call |
| 2. Send receipt | 50-200ms | Network call |
| 3. Bob's optimistic update | <10ms | Emit messagePlayed |
| 4. Bob's sync confirm | 0-30s | Next /sync |
| 5. Alice's sync receive | 0-30s | Independent long-poll |
| 6. Alice's process | <10ms | Convert and emit |
| 7. Alice's UI update | <10ms | Re-render |

**End-to-End**: Bob clicks "played" → Alice sees update: **50ms - 60s**

**Typical**: 5-10 seconds (depends on sync timing)

## Receipt Semantics

### Matrix Read Receipt Behavior

- **Implicit Read**: Receipt for $msg3 implies read for $msg1, $msg2 as well
- **Monotonic**: Receipts only move forward (can't un-read)
- **User Scoped**: Each user has one receipt pointer per room
- **Ephemeral**: Receipts are NOT in timeline, delivered via ephemeral events

### Wata Interpretation

- **Explicit Per-Message**: Each voice message has independent playedBy list
- **Not Cumulative**: Playing $msg3 does NOT mark $msg1, $msg2 as played
- **Workaround**: Sends receipt for each message individually

**Semantic Mismatch**: Matrix's cumulative model vs. Wata's per-message model

## Multi-User Receipts

### Scenario: Alice, Bob, Charlie in Family Room

1. Alice sends message $msg1
2. Bob plays → receipt: playedBy = ["@bob:server"]
3. Charlie plays → receipt: playedBy = ["@bob:server", "@charlie:server"]
4. Alice sees both: "Read by Bob, Charlie"

**Accumulation**: playedBy set grows as more users play

### Receipt Update Events

**After Bob plays**:
- Alice receives: `receiptUpdated(roomId, "$msg1", Set(["@bob:server"]))`
- Alice emits: `messagePlayed(message with playedBy=["@bob:server"], roomId)`

**After Charlie plays**:
- Alice receives: `receiptUpdated(roomId, "$msg1", Set(["@bob:server", "@charlie:server"]))`
- Alice emits: `messagePlayed(message with playedBy=["@bob:server", "@charlie:server"], roomId)`

## Error Paths

### Send Receipt Fails (Network Error)

**Trigger**: Network timeout during sendReadReceipt()

**Handling**:
- markAsPlayed() throws error
- No local state update (no optimistic update made)
- **Can retry**: Re-call markAsPlayed()

**UI**: Show error, allow retry

### Send Receipt Fails (Not in Room)

**Trigger**: User left room before marking as played

**Handling**:
- Server returns M_FORBIDDEN
- markAsPlayed() throws error
- **No state change**

**UI**: Show "Not in room" error

### Receipt Not Received by Sender

**Trigger**: Sender (Alice) offline when Bob sends receipt

**Handling**:
- Receipt stored on server
- When Alice reconnects:
  - Sync delivers receipt in next /sync
  - Alice's state updates normally
- **Eventually consistent**

### Duplicate Receipt

**Trigger**: Bob marks as played twice (UI bug, retry)

**Handling**:
1. First receipt: playedBy = ["@bob:server"]
2. Second receipt: playedBy = ["@bob:server"] (same, no change)
3. SyncEngine emits receiptUpdated both times
4. WataClient emits messagePlayed both times
5. **Idempotent**: Final state same as single receipt

**UI**: May flicker, but final state correct

## Known Workarounds in Tests

1. **Polling for Receipt Callback**: Tests poll for 15 seconds waiting for onReceiptUpdate to fire
2. **Extra waitForSync() Call**: Tests call waitForSync() after receipt callback to ensure matrix-js-sdk updates getUsersReadUpTo() state
3. **Polling for playedBy Update**: Tests poll getVoiceMessages() checking playedBy field instead of using messagePlayed event

## Implementation Differences

### WataClient (MatrixServiceAdapter)

- **Immediate Update**: getVoiceMessages() queries room.readReceipts directly
- **No Extra Sync**: State always fresh from SyncEngine
- **No Workaround Needed**: Tests work without extra waitForSync()

### matrix-js-sdk (MatrixService)

- **Delayed Update**: getUsersReadUpTo() has internal cache
- **Extra Sync Needed**: Must wait for SDK to process receipt internally
- **Workaround Required**: Tests must call waitForSync() after receipt callback

**Test Strategy**: Tests accommodate both implementations by always calling waitForSync()

## Related Specs

- [WataClient](../components/wata-client.md) - markAsPlayed() implementation
- [SyncEngine](../components/sync-engine.md) - Receipt processing
- [Voice Message Receive Flow](./voice-message-receive.md) - Initial isPlayed = false state
