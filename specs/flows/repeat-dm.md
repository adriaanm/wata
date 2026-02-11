# Repeat DM Flow - Sending to Existing DM Room

## Overview

This flow covers sending a second (and subsequent) messages to an existing DM room. DMRoomService uses its cache for fast lookup, avoiding room creation.

## Current Test Coverage

**test/integration/voice-message-flow.test.ts**
- "send multiple messages in sequence"
  - Tests: Sending 5 messages to same room
  - Verifies: All messages arrive in chronological order
  - Workaround: Uses waitForEventIds() polling

**test/integration/e2e-flow.test.ts**
- Tests multiple sends to same DM
- Workaround: Polling for message count updates

## Preconditions

1. DM room "!abc:server" exists between Alice and Bob
2. Both are joined members
3. DMRoomService cache populated:
   - `primaryRoomByContact.get("@bob:server")` = "!abc:server"
   - `contactByRoom.get("!abc:server")` = "@bob:server"
4. Alice has sent at least one message previously (first DM established)

## Flow Steps

### Step 1: Alice Sends Second Message

**Component**: WataClient.sendVoiceMessage()

1. Alice calls `sendVoiceMessage(bobContact, audioBuffer, duration)`
2. WataClient → DMRoomService.ensureDMRoom("@bob:server")

### Step 2: DMRoomService Cache Hit (Fast Path)

**Component**: DMRoomService.ensureDMRoom()

1. Check `primaryRoomByContact.get("@bob:server")` → "!abc:server" (cache hit)
2. Verify room still valid:
   - `SyncEngine.getRoom("!abc:server")` → RoomState exists
   - `room.members.get("@alice:server").membership` → "join" ✓
3. Return "!abc:server" immediately (no network calls)

**Performance**: Cache hit is O(1), no sync state scan, no room creation

### Step 3: Alice Uploads Audio

**Component**: WataClient.sendVoiceMessage()

1. MatrixApi.uploadMedia(audioBuffer, "audio/mp4", "voice-1234.m4a")
2. Server stores media, returns `{ content_uri: "mxc://server/mediaId" }`

### Step 4: Alice Sends Message Event

**Component**: WataClient.sendVoiceMessage()

1. MatrixApi.sendMessage("!abc:server", "m.room.message", content):
   ```json
   {
     "msgtype": "m.audio",
     "body": "Voice message",
     "url": "mxc://server/mediaId",
     "info": {
       "duration": 5000,
       "mimetype": "audio/mp4",
       "size": 12345
     }
   }
   ```
2. Server returns `{ event_id: "$event123" }`
3. WataClient returns optimistic VoiceMessage (before sync)

### Step 5: Alice's Sync Receives Own Message

**Component**: SyncEngine (Alice)

1. Sync includes timeline.events for "!abc:server":
   ```json
   {
     "type": "m.room.message",
     "event_id": "$event123",
     "sender": "@alice:server",
     "origin_server_ts": 1234567890,
     "content": { "msgtype": "m.audio", "url": "mxc://...", ... }
   }
   ```
2. SyncEngine checks for duplicate event_id in room.timeline → not found
3. Appends event to room.timeline
4. SyncEngine emits `timelineEvent("!abc:server", event)`

### Step 6: WataClient Processes Timeline Event

**Component**: WataClient.handleTimelineEvent()

1. Check if voice message (msgtype = "m.audio", not redacted)
2. Convert event to VoiceMessage:
   - Extract sender from room.members.get("@alice:server")
   - Convert MXC URL to HTTP URL for audioUrl
   - Extract duration from content.info.duration (convert ms → s)
   - Check readReceipts for playedBy list
3. Determine conversation type:
   - Not family room (no #family alias)
   - Get contact via DMRoomService.getContactForRoom("!abc:server") → bob
4. Build Conversation object with all messages in room
5. Emit `messageReceived(voiceMessage, conversation)`

### Step 7: Bob's Sync Receives Message

**Component**: SyncEngine (Bob)

1. Same as Step 5, but for Bob's client
2. Bob's SyncEngine emits `timelineEvent("!abc:server", event)`
3. Bob's WataClient emits `messageReceived(voiceMessage, conversation)`
4. Bob's UI shows new message notification

## Postconditions

1. Room "!abc:server" timeline contains new message event
2. Alice's conversation with Bob shows new message (isOwn = true)
3. Bob's conversation with Alice shows new message (isOwn = false)
4. DMRoomService cache unchanged (still points to "!abc:server")
5. Both clients ready to send/receive more messages

## Comparison to First DM

| Aspect | First DM | Repeat DM |
|--------|----------|-----------|
| Cache lookup | Miss | **Hit** |
| Sync state scan | Yes (finds nothing) | **No** |
| Room creation | Yes | **No** |
| m.direct update | Yes | **No** |
| Network calls | createRoom, setAccountData, uploadMedia, sendMessage | **uploadMedia, sendMessage only** |
| Latency | High (room creation overhead) | **Low (cache hit)** |

## Error Paths

### Cache Invalid (Room Left)

**Trigger**: Alice left the room between messages, cache not updated

**Handling**:
1. ensureDMRoom() cache hit → "!abc:server"
2. Verify room: membership = "leave" (invalid!)
3. removeRoomFromCache("!abc:server")
4. Fall back to sync state scan → may find different room or create new one

**Recovery**: Automatic fallback to room scan/creation

### Room Deleted on Server

**Trigger**: Server admin deleted room, cache stale

**Handling**:
1. Cache hit returns "!abc:server"
2. sendMessage("!abc:server", ...) → M_NOT_FOUND error
3. Error propagates to caller
4. **No automatic recovery** (cache not invalidated)

**Limitation**: Stale cache entry persists until logout or manual cache clear

### Upload Fails

**Trigger**: Media upload fails (network, quota, size limit)

**Handling**:
- uploadMedia() throws error
- sendVoiceMessage() propagates error to caller
- **No state change** (message not sent, can retry)

### Send Fails

**Trigger**: sendMessage() fails (permissions, rate limit)

**Handling**:
- Media already uploaded (orphaned MXC URL)
- sendMessage() throws error
- sendVoiceMessage() propagates error
- **Orphaned media** remains on server (no cleanup)

## Known Workarounds in Tests

1. **Polling for Message Count**: Tests poll getVoiceMessages().length instead of waiting for messageReceived event
2. **Event ID Polling**: TestOrchestrator uses waitForEventIds() with exponential backoff
3. **No Cache Verification**: Tests don't verify cache hit/miss behavior

## Optimizations

1. **Cache Hit**: O(1) lookup, no network calls for room lookup
2. **Deduplication**: SyncEngine prevents duplicate events in timeline
3. **Batching**: Multiple sends can happen before sync completes

## Related Specs

- [DMRoomService](../components/dm-room-service.md) - Cache lookup logic
- [First DM Flow](./first-dm.md) - Initial room creation
- [Voice Message Send Flow](./voice-message-send.md) - Detailed send flow
- [DM Deduplication Flow](./dm-deduplication.md) - Handling duplicate rooms
