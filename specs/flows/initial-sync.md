# Initial Sync Flow - First Sync After Login

## Overview

This flow covers the first /sync request after login, which loads all room state, membership, and recent timeline events. This establishes the baseline state for incremental syncs.

## Current Test Coverage

**test/integration/matrix.test.ts**
- "should reach SYNCING or PREPARED state after login"
  - Tests: SyncEngine.start() completes initial sync
  - Verifies: Sync state transitions to SYNCING/PREPARED
  - Workaround: waitForSync() polls until state changes

**test/integration/auto-login.test.ts**
- Tests connect() which triggers initial sync
  - Workaround: Polling for family room to appear

## Preconditions

1. User has called login() successfully
2. Access token is set in MatrixApi
3. SyncEngine.userId is set
4. No prior sync has occurred (nextBatch = null)

## Flow Steps

### Step 1: WataClient Initiates Connection

**Component**: WataClient.connect()

1. Verify logged in (userId !== null)
2. Verify not already connected
3. Call SyncEngine.start()

### Step 2: SyncEngine Starts Initial Sync

**Component**: SyncEngine.start()

1. Set isRunning = true
2. Call MatrixApi.sync() with SHORT timeout for initial sync:
   ```typescript
   api.sync({
     timeout: 5000,  // 5 seconds, not the usual 30s
     since: undefined  // No since token = initial sync
   })
   ```
3. Short timeout reason: Get started quickly, don't wait 30s for first response

### Step 3: Server Processes Initial Sync

**Component**: Matrix Homeserver

1. Server detects initial sync (no since parameter)
2. Server prepares FULL state for all rooms user is in:
   - All joined rooms
   - All invited rooms
   - All left rooms (if recently left)
3. For each room, includes:
   - **state.events**: FULL room state (m.room.create, m.room.name, all m.room.member events)
   - **timeline.events**: Recent messages (default: last 20 events)
   - **ephemeral.events**: Current receipts, typing indicators
   - **account_data.events**: Room-specific settings
4. Also includes:
   - **account_data.events**: Global account data (m.direct, push rules, etc.)
   - **presence.events**: Online status of contacts (if enabled)
5. Generates next_batch token for future incremental syncs

**Response Size**: Large (can be 100KB - 10MB depending on room count and history)

### Step 4: MatrixApi Receives Response

**Component**: MatrixApi.sync()

1. HTTP response received
2. Parse JSON:
   ```json
   {
     "next_batch": "s1234567890_abcdef",
     "rooms": {
       "join": {
         "!room1:server": { ... },
         "!room2:server": { ... }
       },
       "invite": {
         "!room3:server": { ... }
       }
     },
     "account_data": {
       "events": [
         { "type": "m.direct", "content": { ... } }
       ]
     }
   }
   ```
3. Return SyncResponse to SyncEngine

### Step 5: SyncEngine Processes Initial Sync

**Component**: SyncEngine.processSyncResponse()

**Global Account Data**:
1. Process account_data.events:
   - Emit `accountDataUpdated("m.direct", content)`
   - WataClient → DMRoomService.handleMDirectUpdate()

**For Each Joined Room**:
1. Create RoomState object (rooms map is empty initially)
2. Process state.events:
   - m.room.create → Extract creation_ts, is_direct flag
   - m.room.name → Set room.name
   - m.room.canonical_alias → Set room.canonicalAlias
   - m.room.member → Populate room.members map
3. Process timeline.events:
   - Append to room.timeline (chronological order)
   - Extract voice messages, other events
4. Process ephemeral.events:
   - m.receipt → Populate room.readReceipts
5. Emit `roomUpdated(roomId, roomState)`

**For Each Invited Room**:
1. Create RoomState with stripped state
2. Process invite_state.events (limited info)
3. Emit `membershipChanged(roomId, userId, "invite")`

**After All Rooms**:
1. Store next_batch token
2. Emit `synced(next_batch)`

**Timing**: 50-500ms for processing (depends on room count)

### Step 6: WataClient Processes Initial State

**Component**: WataClient (via event listeners)

**For Each accountDataUpdated**:
- m.direct → DMRoomService populates cache

**For Each roomUpdated**:
- If family room (canonical_alias = #family) → Emit `familyUpdated`, `contactsUpdated`

**For Each timelineEvent**:
- If voice message → Emit `messageReceived`

**For Each membershipChanged**:
- If self-invite → Auto-join room
- If family room membership → Update contacts

### Step 7: Background Sync Loop Starts

**Component**: SyncEngine.runSyncLoop()

1. Initial sync complete
2. Start background loop with LONG timeout:
   ```typescript
   api.sync({
     timeout: 30000,  // 30 seconds
     since: next_batch  // Incremental sync
   })
   ```
3. Loop continues until stop() called

### Step 8: WataClient Emits Connection State

**Component**: WataClient

1. After 'synced' event from SyncEngine
2. Emit `connectionStateChanged('syncing')`
3. Application is now ready to use

## Postconditions

1. All rooms loaded into SyncEngine.rooms map
2. Timeline populated with recent messages
3. Members loaded for all rooms
4. m.direct processed, DM cache populated
5. Family room identified (if exists)
6. Contacts list populated
7. Background sync loop running
8. next_batch token stored for incremental syncs

## Response Structure

### Joined Room Full State

```json
{
  "!abc:server": {
    "state": {
      "events": [
        {
          "type": "m.room.create",
          "state_key": "",
          "content": { "creator": "@alice:server", "room_version": "10" },
          "sender": "@alice:server",
          "origin_server_ts": 1701234567890,
          "event_id": "$create123"
        },
        {
          "type": "m.room.member",
          "state_key": "@alice:server",
          "content": { "membership": "join", "displayname": "Alice" },
          "sender": "@alice:server",
          "origin_server_ts": 1701234567891,
          "event_id": "$member123"
        },
        {
          "type": "m.room.member",
          "state_key": "@bob:server",
          "content": { "membership": "join", "displayname": "Bob" },
          "sender": "@bob:server",
          "origin_server_ts": 1701234567892,
          "event_id": "$member124"
        }
      ]
    },
    "timeline": {
      "events": [
        {
          "type": "m.room.message",
          "content": { "msgtype": "m.audio", ... },
          "sender": "@alice:server",
          "origin_server_ts": 1701234568000,
          "event_id": "$msg123"
        }
      ],
      "limited": false,
      "prev_batch": "t1234_5678"
    },
    "ephemeral": {
      "events": [
        {
          "type": "m.receipt",
          "content": {
            "$msg123": {
              "m.read": {
                "@bob:server": { "ts": 1701234568100 }
              }
            }
          }
        }
      ]
    }
  }
}
```

## Error Paths

### Initial Sync Fails (Network Error)

**Trigger**: Network timeout, connection refused

**Handling**:
1. MatrixApi.sync() throws error
2. SyncEngine catches error in start()
3. Emit `error(err)` event
4. **Continue to background loop** (will retry)
5. Background loop uses exponential backoff

**Recovery**: Automatic retry in background loop

### Initial Sync Fails (Invalid Token)

**Trigger**: Access token expired or revoked

**Handling**:
1. Server returns M_UNKNOWN_TOKEN
2. MatrixApi throws error
3. SyncEngine emits `error(err)`
4. Background loop retries → Continues to fail
5. **No automatic recovery**

**User Action**: Must re-login

### Initial Sync Timeout (Server Overload)

**Trigger**: Server takes >5s to respond

**Handling**:
- fetch() times out (if timeout configured)
- Or server eventually responds
- If timeout: Same as network error, retry

### Malformed Response

**Trigger**: Server returns invalid JSON or schema

**Handling**:
1. JSON.parse() throws or type assertion fails
2. SyncEngine emits `error(err)`
3. Background loop retries
4. **May continue to fail** if server consistently broken

## Performance Characteristics

### Response Size by Room Count

| Rooms | State Events | Timeline Events | Approx Size |
|-------|--------------|-----------------|-------------|
| 1 | ~10 | ~20 | ~10 KB |
| 5 | ~50 | ~100 | ~50 KB |
| 10 | ~100 | ~200 | ~100 KB |
| 50 | ~500 | ~1000 | ~500 KB |
| 100 | ~1000 | ~2000 | ~1 MB |

**Wata Typical**: 2-10 rooms (family + DMs) → 20-100 KB

### Processing Time

| Rooms | Events | Processing Time |
|-------|--------|-----------------|
| 1 | 30 | ~10ms |
| 5 | 150 | ~50ms |
| 10 | 300 | ~100ms |
| 50 | 1500 | ~500ms |

**Wata Typical**: <100ms processing time

## Known Workarounds in Tests

1. **Polling for Sync State**: Tests poll getSyncState() or use waitForSync() instead of waiting for 'synced' event
2. **Polling for Rooms**: Tests poll getFamily() or getDirectRooms() instead of waiting for roomUpdated
3. **No Performance Verification**: Tests don't verify initial sync completes within reasonable time

## Related Specs

- [SyncEngine](../components/sync-engine.md) - Sync processing logic
- [MatrixApi](../components/matrix-api.md) - sync() endpoint
- [Incremental Sync Flow](./incremental-sync.md) - Subsequent syncs
- [Initial Setup Flow](./initial-setup.md) - What happens after initial sync
