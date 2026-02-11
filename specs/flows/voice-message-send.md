# Voice Message Send Flow - Complete Send Flow

## Overview

This flow covers the complete lifecycle of sending a voice message: audio encoding, media upload, message event creation, and optimistic UI update.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should send an audio message"
  - Tests: sendVoiceMessage() with fake audio buffer
  - Verifies: Message appears in alice's timeline
  - Workaround: Polling for message count to increase

**test/integration/voice-message-flow.test.ts**
- "alice sends voice message, bob receives it"
  - Tests: Full send-receive flow
  - Verifies: EventId matches, duration correct, audioUrl valid
  - Workaround: TestOrchestrator polls for message arrival (15s timeout)

**test/integration/e2e-flow.test.ts**
- Tests send in context of full E2E flow
  - Workaround: Multiple polling loops for send/sync/receive

## Preconditions

1. Alice is logged in and connected (syncing)
2. Target room exists and Alice is joined member
3. Audio data is available as ArrayBuffer
4. Duration is known (in milliseconds)

## Flow Steps

### Step 1: Determine Target Room

**Component**: WataClient.sendVoiceMessage()

**For DM Target**:
1. Alice calls `sendVoiceMessage(bobContact, audioBuffer, 5000)`
2. Extract contactUserId from contact.user.id
3. Call DMRoomService.ensureDMRoom(contactUserId)
4. DMRoomService returns roomId (from cache or creates new)

**For Family Target**:
1. Alice calls `sendVoiceMessage('family', audioBuffer, 5000)`
2. Find family room via findFamilyRoom()
3. If no family exists, throw "Not in a family"
4. Use familyRoomId

**Component Boundary**: WataClient → DMRoomService or internal family lookup

### Step 2: Upload Audio to Media Repository

**Component**: MatrixApi.uploadMedia()

1. WataClient calls:
   ```typescript
   uploadMedia(audioBuffer, "audio/mp4", "voice-1234567890.m4a")
   ```
2. MatrixApi builds HTTP request:
   - Method: POST
   - Path: `/_matrix/media/v3/upload?filename=voice-1234567890.m4a`
   - Headers:
     - `Authorization: Bearer {access_token}`
     - `Content-Type: audio/mp4`
   - Body: audioBuffer (raw binary)
3. Server stores media, generates unique media ID
4. Server responds:
   ```json
   {
     "content_uri": "mxc://server/AbCdEfGh123456"
   }
   ```
5. MatrixApi returns { content_uri: "mxc://..." }

**Network Call 1**: Upload audio (size-dependent latency)

### Step 3: Send Message Event

**Component**: MatrixApi.sendMessage()

1. WataClient builds message content:
   ```json
   {
     "msgtype": "m.audio",
     "body": "Voice message",
     "url": "mxc://server/AbCdEfGh123456",
     "info": {
       "duration": 5000,
       "mimetype": "audio/mp4",
       "size": 12345
     }
   }
   ```
2. Generate transaction ID (if not provided):
   - Format: `wata-{timestamp}-{counter}`
   - Example: `wata-1701234567890-0`
3. MatrixApi sends HTTP request:
   - Method: PUT
   - Path: `/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`
   - Body: message content
4. Server processes event:
   - Validates room membership
   - Assigns event_id
   - Appends to room timeline
   - Triggers /sync updates for all room members
5. Server responds:
   ```json
   {
     "event_id": "$abc123xyz"
   }
   ```

**Network Call 2**: Send message event (low latency)

### Step 4: Optimistic UI Update

**Component**: WataClient.sendVoiceMessage()

1. Build optimistic VoiceMessage object:
   ```typescript
   {
     id: "$abc123xyz",
     sender: getCurrentUser(),
     audioUrl: mxcToHttp(mxcUrl),
     mxcUrl: "mxc://server/AbCdEfGh123456",
     duration: 5.0,  // Convert ms to seconds
     timestamp: new Date(),
     isPlayed: false,
     playedBy: []
   }
   ```
2. Return VoiceMessage to caller
3. Caller can immediately show message in UI (optimistic)

**Timing**: Before sync delivers the actual event

### Step 5: Alice's Sync Receives Own Message

**Component**: SyncEngine (Alice)

1. Next /sync response includes timeline.events:
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
       "info": { "duration": 5000, "mimetype": "audio/mp4", "size": 12345 }
     }
   }
   ```
2. SyncEngine deduplication check:
   - Does event_id exist in room.timeline? → No (first time)
   - Append to room.timeline
3. SyncEngine emits `timelineEvent(roomId, event)`

**Timing**: 0-30 seconds after send (depends on long-poll)

### Step 6: WataClient Processes Own Message

**Component**: WataClient.handleTimelineEvent()

1. Check if voice message event
2. Convert to VoiceMessage (same as optimistic version)
3. Determine conversation type (DM or family)
4. Emit `messageReceived(voiceMessage, conversation)`

**UI Update**: Replace optimistic message with confirmed message (by event_id)

## Postconditions

1. Audio uploaded to media repository (permanent, MXC URL)
2. Message event sent to room (event_id assigned)
3. Alice's timeline includes the message
4. messageReceived event emitted
5. Room state updated (bob will receive via sync)

## Timing Analysis

| Step | Duration | Network | Notes |
|------|----------|---------|-------|
| 1. Determine room | <10ms | No | Cache hit (DM) or alias lookup (family) |
| 2. Upload audio | 100-2000ms | **Yes** | Depends on audio size (typically 5-50KB for Opus) |
| 3. Send message | 50-200ms | **Yes** | JSON payload, low latency |
| 4. Optimistic UI | <10ms | No | Return immediately |
| 5. Sync receives | 0-30s | No | Next /sync cycle (long-poll) |
| 6. Event processing | <10ms | No | Event conversion and emit |

**Total (send call)**: 150-2200ms
**Total (confirmation)**: +0-30s via sync

## Error Paths

### Upload Fails (Network Error)

**Trigger**: Network timeout, connection refused

**Handling**:
- uploadMedia() throws error
- sendVoiceMessage() propagates error to caller
- **No state change** (can retry from scratch)

**UI**: Show error, allow retry

### Upload Fails (Quota Exceeded)

**Trigger**: User exceeded media quota

**Handling**:
- Server returns M_FORBIDDEN or M_LIMIT_EXCEEDED
- uploadMedia() throws error with errcode
- **No cleanup** (quota issue must be resolved)

**UI**: Show quota error, disable send

### Send Fails (Permission Denied)

**Trigger**: User lost send permission (kicked, banned, power level changed)

**Handling**:
- Media already uploaded (orphaned MXC URL)
- sendMessage() throws M_FORBIDDEN
- sendVoiceMessage() propagates error
- **Orphaned media** remains on server (no automatic cleanup)

**UI**: Show permission error

### Send Fails (Room Not Found)

**Trigger**: Room deleted between upload and send

**Handling**:
- uploadMedia() succeeds
- sendMessage() throws M_NOT_FOUND
- **Orphaned media** remains on server

**UI**: Show "Room not found" error

### Deduplication on Sync

**Trigger**: Retry with same txnId, sync receives duplicate

**Handling**:
- SyncEngine checks event_id in timeline
- Duplicate found → Skip append
- No duplicate messageReceived event

**Guarantee**: Same message never appears twice in timeline

## Known Workarounds in Tests

1. **Polling for Message**: Tests poll getVoiceMessages() or getMessageCount() instead of waiting for messageReceived event
2. **Event ID Polling**: TestOrchestrator.waitForEventIds() uses exponential backoff polling
3. **Fixed Delays**: Some tests use sleep(2000) instead of event-driven waits

## Optimizations

1. **Parallel Upload**: Could upload multiple audios in parallel (not implemented)
2. **Resume Upload**: Could resume failed uploads (not implemented)
3. **Compression**: Could compress audio before upload (handled by audio encoder, not WataClient)

## Related Specs

- [WataClient](../components/wata-client.md) - sendVoiceMessage() implementation
- [MatrixApi](../components/matrix-api.md) - uploadMedia() and sendMessage()
- [Voice Message Receive Flow](./voice-message-receive.md) - Recipient's perspective
- [Repeat DM Flow](./repeat-dm.md) - Room lookup for subsequent messages
