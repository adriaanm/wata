# Voice Message Receive Flow - Complete Receive Flow

## Overview

This flow covers the complete lifecycle of receiving a voice message: sync delivers event, SyncEngine processes it, WataClient converts to domain object, messageReceived event fires, and UI updates.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should receive messages in room timeline"
  - Tests: Bob receives Alice's message
  - Verifies: Message appears in Bob's timeline, correct sender/duration
  - Workaround: Polling for message with specific event ID

**test/integration/voice-message-flow.test.ts**
- "alice sends voice message, bob receives it"
  - Tests: Full receive flow
  - Verifies: sender, duration, audioUrl, isOwn flag
  - Workaround: verifyMessageReceived() polls for 15 seconds

**test/integration/e2e-flow.test.ts**
- Tests receive in E2E context
  - Workaround: Nested polling loops

## Preconditions

1. Bob is logged in and connected (syncing)
2. DM room exists between Alice and Bob
3. Both are joined members
4. Alice has sent a voice message (via Send Flow)

## Flow Steps

### Step 1: Alice Sends Message (Sender Side)

**Component**: MatrixApi, Matrix Server

1. Alice uploads audio → MXC URL
2. Alice sends m.audio event → event_id
3. Server appends event to room timeline
4. Server marks sync state dirty for all room members (including Bob)

**Server State**: Bob's next /sync will include this event

### Step 2: Bob's Sync Receives Event

**Component**: SyncEngine (Bob)

1. Bob's client calls /sync (30s long-poll)
2. Server responds with rooms.join[roomId].timeline.events:
   ```json
   {
     "type": "m.room.message",
     "event_id": "$abc123xyz",
     "sender": "@alice:server",
     "origin_server_ts": 1701234567890,
     "content": {
       "msgtype": "m.audio",
       "body": "Voice message",
       "url": "mxc://server/AbCdEfGh123456",
       "info": {
         "duration": 5000,
         "mimetype": "audio/mp4",
         "size": 12345
       }
     }
   }
   ```
3. SyncEngine calls processSyncResponse()

**Timing**: 0-30 seconds after Alice sends (depends on long-poll timeout)

### Step 3: SyncEngine Processes Timeline Event

**Component**: SyncEngine.processJoinedRoom()

1. Extract timeline.events array
2. For each event:
   - Check deduplication: event_id in room.timeline? → No
   - Append to room.timeline array
   - Check if state event (has state_key) → No (not a state event)
   - Emit `timelineEvent(roomId, event)`
3. After all events processed, emit `roomUpdated(roomId, room)`

**State Update**: room.timeline.push(event)

### Step 4: WataClient Receives Timeline Event

**Component**: WataClient.handleTimelineEvent()

1. Listener triggered: `timelineEvent(roomId, event)`
2. Check if voice message:
   - event.type === "m.room.message" ✓
   - event.content.msgtype === "m.audio" ✓
   - !event.unsigned?.redacted_because ✓
3. Proceed to convert event to VoiceMessage

### Step 5: Convert Event to VoiceMessage

**Component**: WataClient.eventToVoiceMessage()

1. Extract sender user info:
   - userId = event.sender = "@alice:server"
   - Look up room.members.get("@alice:server")
   - displayName = member.displayName || "alice"
   - avatarUrl = member.avatarUrl || null
2. Parse content:
   - mxcUrl = content.url = "mxc://server/AbCdEfGh123456"
   - audioUrl = mxcToHttp(mxcUrl) = "http://server:8008/_matrix/client/v1/media/download/server/AbCdEfGh123456"
   - duration = content.info.duration / 1000 = 5.0 seconds
   - timestamp = new Date(origin_server_ts) = Date(1701234567890)
3. Check read receipts:
   - playedBy = room.readReceipts.get("$abc123xyz") || []
   - isPlayed = playedBy.includes("@bob:server") → false (not played yet)
4. Build VoiceMessage object:
   ```typescript
   {
     id: "$abc123xyz",
     sender: { id: "@alice:server", displayName: "alice", avatarUrl: null },
     audioUrl: "http://server:8008/_matrix/client/v1/media/download/...",
     mxcUrl: "mxc://server/AbCdEfGh123456",
     duration: 5.0,
     timestamp: Date(1701234567890),
     isPlayed: false,
     playedBy: []
   }
   ```

### Step 6: Determine Conversation Type

**Component**: WataClient.handleTimelineEvent()

1. Check if family room:
   - isFamilyRoom(roomId) → Check canonical_alias === "#family:server"
   - If yes: type = 'family', contact = undefined
2. If not family:
   - type = 'dm'
   - contact = DMRoomService.getContactForRoom(roomId)
   - If contact not found (cache miss):
     - Infer from room membership (2-person room)
     - Build Contact object from room.members
3. Build Conversation object via roomToConversation():
   - Filter timeline for all voice messages
   - Count unplayed messages
   - Return Conversation with messages array

### Step 7: Emit messageReceived Event

**Component**: WataClient

1. Call `emit('messageReceived', voiceMessage, conversation)`
2. All registered handlers are called:
   - UI handler updates message list
   - Notification handler may show notification
   - Audio handler may auto-play (if enabled)
3. Handlers run synchronously, errors are caught and logged

**UI Update**: New message appears in conversation view

## Postconditions

1. Bob's room.timeline includes the new message event
2. Bob's Conversation with Alice includes the VoiceMessage
3. VoiceMessage.isPlayed = false (not yet played)
4. VoiceMessage.audioUrl is ready for download/playback
5. messageReceived event has fired
6. UI shows new message

## Timing Analysis

| Step | Duration | Notes |
|------|----------|-------|
| 1. Alice sends | 150-2200ms | See Send Flow |
| 2. Sync delivery | 0-30s | Long-poll delay |
| 3. Process timeline | <10ms | Append to array, emit event |
| 4-5. Convert event | <10ms | Lookup members, parse content |
| 6. Determine conversation | <10ms | Cache lookup or inference |
| 7. Emit event | <10ms | Call registered handlers |

**Total (end-to-end)**: Alice's send complete → Bob's UI updates: **0-30s**

**Bottleneck**: Sync long-poll delay (unavoidable in Matrix protocol)

## Event Deduplication

### Scenario: Sync Delivers Same Event Twice

**Cause**: Network retry, incremental sync overlap

**Handling**:
1. SyncEngine checks: event_id in room.timeline?
2. If found: Skip append, log "Skipping duplicate event"
3. No timelineEvent emission
4. No duplicate messageReceived event

**Guarantee**: Same message never appears twice

## Member Info Handling

### Scenario: Sender Not in Room Members

**Cause**: Sender left room before event arrives

**Handling**:
1. room.members.get(senderId) → undefined
2. Fallback to user ID:
   - displayName = userId.split(':')[0].substring(1) = "alice"
   - avatarUrl = null
3. VoiceMessage still created (degraded info)

**Limitation**: No display name or avatar for left members

### Scenario: Sender Info Updates After Message

**Cause**: User changes display name after sending

**Handling**:
- **Current**: VoiceMessage uses member info from time of event receipt
- **No retroactive update**: Changing display name doesn't update old messages
- **Limitation**: Message sender info may be stale

## Read Receipt State

### Initial State

When Bob first receives the message:
- isPlayed = false
- playedBy = []

### After Bob Plays

After Bob marks as played (separate flow):
- Bob's client sends read receipt
- Alice's sync receives receipt
- Alice's room.readReceipts updated
- Alice sees playedBy = ["@bob:server"] on her VoiceMessage

## Error Paths

### Timeline Event Missing Content

**Trigger**: Malformed event, missing content.url or content.info

**Handling**:
- isVoiceMessageEvent() returns false
- Event not processed as voice message
- No messageReceived event
- Event still stored in timeline (for debugging)

**UI**: Message not displayed

### Redacted Message

**Trigger**: Message was redacted (deleted) before Bob syncs

**Handling**:
1. Event has unsigned.redacted_because field
2. isVoiceMessageEvent() returns false (redaction check)
3. No messageReceived event
4. messageDeleted event may fire (if Bob saw message before)

**UI**: Message not displayed or removed

### Room Not Found

**Trigger**: Event arrives for room not in sync state (rare)

**Handling**:
- SyncEngine creates room if missing (invite/join handling)
- Event processed normally
- No error

### Contact Inference Fails

**Trigger**: DM room has >2 members or 0 members

**Handling**:
1. getContactForRoom() returns null
2. WataClient logs warning and drops event
3. No messageReceived event

**Limitation**: Group DMs not supported

## Known Workarounds in Tests

1. **Polling for Event ID**: verifyMessageReceived() polls getVoiceMessages() checking for specific event_id
2. **Exponential Backoff**: Polling delay increases: 200ms → 260ms → 338ms...
3. **Long Timeout**: Tests wait up to 15-30 seconds for receive
4. **No Event Listeners**: Tests don't use messageReceived event, poll state instead

## Related Specs

- [SyncEngine](../components/sync-engine.md) - Timeline event processing
- [WataClient](../components/wata-client.md) - Event to domain object conversion
- [Voice Message Send Flow](./voice-message-send.md) - Sender's perspective
- [Read Receipt Flow](./read-receipt.md) - Marking message as played
