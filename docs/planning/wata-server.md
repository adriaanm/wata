# Wata Server — Implementation Plan

A minimal Matrix Client-Server API server for Wata. One family per server, all state in memory, zero external dependencies. Replaces Conduit for development and browser demos.

## AGENT INSTRUCTIONS
We are currently improving the wata-server implementation by using the wata-client integration tests as an oracle. The test suite MUST NOT be altered. (Exceptionally, you may add retries to existing test logic. You may also add logging anywhere to confirm theories.)

When tests fail, always assume the bug is in the server prototype. Remember that the client-side of things is working perfectly with conduit, and we are building a drop-in replacement. If the tests are failing, more than likely the problem lies in our server prototype.

Before changing server logic, use a subagent to ask relevant questions about the matrix spec in ~/g/matrix-spec.

**Development Workflow**

A rapid iteration workflow has been added to speed up server development:

```bash
# TDD Mode: Run one test at a time, iterate until green, move to next
pnpm test:server --tdd

# TDD Mode starting at a specific test
pnpm test:server --tdd "should login with valid"

# Interactive mode with full control
pnpm test:server

# Run with debug logging enabled
pnpm wata-server:debug
```

**TDD Mode Commands**:
- `r` - Run current test (auto-retry on failure until it passes)
- `s` - Skip current test and move to next
- `l` - Show recent server logs
- `j` - Jump to specific test number (1-N)
- `q` - Quit

**Interactive Mode Commands**:
- `t` - Run all integration tests
- `o` - Run one test (prompt for name)
- `l` - Show recent server logs
- `r` - Restart wata-server
- `s` - Stop wata-server
- `--` - List all available tests
- `q` - Quit

**Recommended Starting Point**:
Begin with the authentication tests (`matrix.test.ts`) as they're the simplest:
Then move to room operations, messaging, etc.

**Test targets**: All test files should pass:
- `matrix.test.ts` (8 tests)
- `auto-login.test.ts` (7 tests)
- `voice-message-flow.test.ts` (6 tests)
- `family-room.test.ts`
- `contacts.test.ts` (8 tests)
- `message-ordering.test.ts` (6 tests)
- `e2e-flow.test.ts` (7 tests)
- `edge-cases.test.ts` (18 tests)
- `read-receipts.test.ts`

**Benefits**:
- Logs at `/tmp/wata-server.log`


**Expected issues to address**:
- Timing issues in tests that expect eventual consistency (Conduit) vs immediate consistency (wata-server). Our server is synchronous, so events appear instantly — tests should pass faster.
- URL encoding edge cases: room IDs contain `!`, event IDs contain `$`, aliases contain `#`. Router must handle percent-encoded path segments.
- The SDK may call `GET /rooms/{roomId}/messages` for pagination (used by `scrollback()`). Implement as: return timeline events in reverse chronological order with `start`/`end` pagination tokens.



# Server prototype
The remainder of this document is the original plan for the server prototype. Use it as a reference when checking implementation for correctness.
Also consult docs/wata-matrix-spec.md.

## Goals

1. **Drop-in Conduit replacement**: The existing `wata-client` tests must work against this server without any client-side changes.
2. **Spec-correct enough for other clients (v2)**: Don't take shortcuts that would break standard Matrix clients (e.g., Element) on the supported endpoint surface.
3. **Browser-demoable**: Server runs in one browser tab, clients in others. No hosted infrastructure.
4. **Low footprint**: All state in memory. No database, no background workers.
5. **Portable**: Only Web Platform APIs (`Request`, `Response`, `crypto`). No Node-specific frameworks. Designed for eventual Go/WASM port.

## Non-Goals

Federation, E2EE, multitenancy, persistence, user registration, push notifications, presence, typing indicators, rate limiting.

## Validation Strategy

The existing integration tests (`test/integration/*.test.ts`) currently run against Conduit on `http://localhost:8008`. These tests are the acceptance criteria. The server is done when all existing tests pass against wata-server instead of Conduit.

**Test harness**: Tests use `TestOrchestrator`. They expect server name `localhost`, users `alice`/`bob` with password `testpass123`.

**How to run**: `node src/server/index.ts` on port 8008, then `pnpm test:integration`.

Each task below lists the test files that should pass after it's complete. Tasks are ordered so each one adds endpoints that unlock more tests. Tests may only fully pass once their dependencies (earlier tasks) are complete.

---

## Architecture

### Module Structure

```
src/server/
├── index.ts              # Entry point: parse config, start Node.js HTTP server
├── config.ts             # ServerConfig type definition
├── server.ts             # Router: Request → handler → Response (Web Fetch API)
├── store.ts              # In-memory state (users, devices, rooms, media, account data)
├── handlers/
│   ├── auth.ts           # GET/POST /login, POST /logout, GET /whoami
│   ├── sync.ts           # GET /sync (initial, incremental, long-poll)
│   ├── rooms.ts          # createRoom, join, invite, alias resolution
│   ├── events.ts         # send event, redact
│   ├── receipts.ts       # read receipts
│   ├── media.ts          # upload, download
│   ├── profile.ts        # get/set displayname, avatar_url
│   └── account-data.ts   # get/set global and room account data
├── transport/
│   ├── node.ts           # http.createServer ↔ Request/Response adapter
│   └── sw.ts             # Service Worker adapter (Task 10)
└── utils.ts              # ID generation, error helpers, auth middleware
```

The core (`server.ts`) operates on Web Fetch API `Request`/`Response`. Transport adapters are thin wrappers. This makes the Go port mechanical: replace adapters with `net/http`.

### Data Model

See the "Data Model", "Event Structure", "Sync Tokens", "ID Generation", "Auth Model", "Sync Algorithm", "Room Presets", and "Error Responses" sections in the git history of this file (commit before this rewrite) for full reference. Summary:

- **Users**: From config, immutable. `@localpart:serverName`.
- **Devices**: Created on login. Map access token → device → user.
- **Rooms**: Room ID → state map + timeline (append-only event list).
- **Events**: Each has a global sequence number for sync tokens (`s0`, `s1`, ...).
- **Media**: Media ID → `{ data: ArrayBuffer, contentType, filename }`.
- **Account Data**: Per-user, global or per-room, keyed by type.
- **Sync**: Long-poll via per-user wake channels. Incremental sync computes delta by filtering events with sequence > since token.

### Dependencies

None. Only Web Platform APIs: `Request`, `Response`, `crypto.randomUUID()`, `TextEncoder`. Works in Node.js 18+, browsers, Service Workers.

### Configuration

```typescript
// Default test config (matches existing test expectations)
{
  serverName: 'localhost',
  port: 8008,
  users: [
    { localpart: 'alice', password: 'testpass123', displayName: 'Alice' },
    { localpart: 'bob',   password: 'testpass123', displayName: 'Bob' },
  ],
}
```

---

## Implementation Tasks

### Task 1: Foundation — Store, Router, Node Transport

**Create**: `config.ts`, `store.ts`, `utils.ts`, `server.ts`, `transport/node.ts`, `index.ts`

**Scope**:
- `ServerConfig` type (serverName, port, users array)
- `Store` class: in-memory state for users, devices, rooms, aliases, media, account data, receipts. Methods to query and mutate. Global event sequence counter.
- `utils.ts`: `generateId()` (room, event, device, token, media), `matrixError(errcode, error, status)` response helper, `authenticate(request, store)` middleware that extracts Bearer token and returns user/device or error response.
- `server.ts`: URL pattern router. Takes `(request: Request) => Promise<Response>`. Dispatches to handler functions by method + path pattern. Returns 404/405 for unknown routes. Adds CORS headers.
- `transport/node.ts`: `http.createServer` adapter. Converts `IncomingMessage` → `Request`, calls router, writes `Response` to `ServerResponse`. Handles body streaming for media uploads.
- `index.ts`: Loads config, creates store, starts server on configured port.
- Also implement `GET /_matrix/client/versions` (returns `{"versions":["v1.1"]}`) since the test setup script health-checks this endpoint.

**Validates**: Server starts on port 8008 and responds to `curl http://localhost:8008/_matrix/client/versions`.

### Task 2: Auth — Login, Logout, Whoami

**Create**: `handlers/auth.ts`

**Endpoints**:
- `GET /_matrix/client/v3/login` → return `{"flows":[{"type":"m.login.password"}]}`
- `POST /_matrix/client/v3/login` → validate credentials, create device + access token, return `{user_id, access_token, device_id, home_server}`
- `POST /_matrix/client/v3/logout` → invalidate token, delete device
- `GET /_matrix/client/v3/account/whoami` → return `{user_id, device_id}`

**Details**:
- Login accepts `identifier.type: "m.id.user"` format
- Also accept deprecated `user` field at top level for SDK compatibility
- Access token format: `syt_<localpart>_<random>`
- Device ID: random uppercase string
- Error on wrong password: `M_FORBIDDEN`
- Error on unknown user: `M_FORBIDDEN`
- Error on invalid/missing token: `M_UNKNOWN_TOKEN`

**Test targets**: `matrix.test.ts` authentication tests (login, invalid credentials), `auto-login.test.ts` (login with config credentials, invalid credentials).

### Task 3: Rooms — Create, Join, Invite, Alias

**Create**: `handlers/rooms.ts`

**Endpoints**:
- `POST /_matrix/client/v3/createRoom`
- `POST /_matrix/client/v3/join/{roomIdOrAlias}`
- `POST /_matrix/client/v3/rooms/{roomId}/join`
- `POST /_matrix/client/v3/rooms/{roomId}/invite`
- `GET /_matrix/client/v3/directory/room/{roomAlias}`

**createRoom details**:
1. Generate room ID `!<random>:localhost`
2. Create room in store with empty state and timeline
3. Emit `m.room.create` state event (sender = creator, `state_key: ""`)
4. Apply preset: emit `m.room.join_rules`, `m.room.history_visibility`, `m.room.guest_access` state events
5. Emit `m.room.member` join event for creator (`state_key: creator userId`)
6. If `name`: emit `m.room.name` state event
7. If `room_alias_name`: register alias `#alias:localhost` → room ID, emit `m.room.canonical_alias`
8. If `initial_state`: emit those state events
9. If `invite`: emit `m.room.member` invite events for each user (with `is_direct` if set)
10. If `trusted_private_chat` preset: set power levels giving invitees level 100

**Join details**: Look up room (by alias if needed), add `m.room.member` join event for the user. If user was invited, transition from invite → join.

**Invite details**: Add `m.room.member` invite event. Error if user already joined.

**Alias resolution**: Look up alias in store, return room ID + `[serverName]`.

**Test targets**: `matrix.test.ts` room tests, `family-room.test.ts` (create, join, invite, alias).

### Task 4: Sync — Initial and Incremental

**Create**: `handlers/sync.ts`

**Endpoint**: `GET /_matrix/client/v3/sync`

**Query params**: `since`, `timeout`, `full_state`, `set_presence`, `filter`

**Initial sync** (no `since`):
- For each room where user is joined:
  - `state.events`: all current state events for the room
  - `timeline.events`: all timeline events (state + message events in order)
  - `timeline.limited`: false (v1 has no pagination)
  - `timeline.prev_batch`: `"s0"`
  - `summary`: `m.heroes` (other members, max 5), `m.joined_member_count`, `m.invited_member_count`
  - `ephemeral.events`: current receipt events for this room
  - `account_data.events`: room-specific account data
  - `unread_notifications`: `{highlight_count: 0, notification_count: 0}`
- For each room where user is invited:
  - `invite_state.events`: stripped state events (type, state_key, content, sender)
- Global `account_data.events`: all user account data
- `next_batch`: current global sequence as `"s<N>"`

**Incremental sync** (with `since`):
- Parse `since` → sequence number N
- For each joined room: find events with sequence > N, split into state/timeline
- For newly invited rooms: include invite_state
- Include new account data, new receipts
- `next_batch`: current global sequence

**Long-poll**:
- If no new events and `timeout` > 0: await on user's wake channel (promise + setTimeout)
- Wake channel is notified whenever events are added to rooms the user is in, or account data changes

**Important SDK compatibility notes**:
- the client calls `/sync` immediately on `startClient()`. The initial sync must return enough state for the SDK to consider itself "PREPARED".
- The SDK also fetches push rules via `/pushrules/`. We need to handle `GET /_matrix/client/v3/pushrules/` returning `{"global":{"override":[],"underride":[],"sender":[],"room":[],"content":[]}}` (empty rules). This is what Conduit returns and the SDK expects it.
- The `unsigned.age` field should be set to `Date.now() - origin_server_ts`.
- The `unsigned.transaction_id` should be included for events sent by the requesting user's device (echo back the txnId).

**Test targets**: `matrix.test.ts` (sync and receive rooms), `auto-login.test.ts` (waitForSync, multiple users). These tests should now pass because they can login, create rooms, and sync.

### Task 5: Events — Send Message, Redact

**Create**: `handlers/events.ts`

**Endpoints**:
- `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}`
- `PUT /_matrix/client/v3/rooms/{roomId}/redact/{eventId}/{txnId}`

**Send details**:
1. Verify user is joined to room
2. Check txnId for idempotency (per-device dedup map, return same event_id if seen)
3. Create event with new event_id, increment global sequence
4. Append to room timeline
5. Notify all room members via wake channels
6. Return `{event_id}`

**Redact details**:
1. Find event in room timeline
2. Replace content with empty object, add `unsigned.redacted_because` event
3. Notify room members
4. Return new event_id for the redaction event

**Test targets**: `matrix.test.ts` (send text/audio messages), `voice-message-flow.test.ts` (send voice messages, bidirectional).

### Task 6: Media — Upload and Download

**Create**: `handlers/media.ts`

**Endpoints**:
- `POST /_matrix/media/v3/upload`
- `GET /_matrix/client/v1/media/download/{serverName}/{mediaId}`

**Upload**: Read raw body bytes, store in memory keyed by random media ID. Return `{"content_uri": "mxc://localhost/<mediaId>"}`. Support `filename` query param and `Content-Type` header.

**Download**: Look up media ID, return bytes with stored `Content-Type` header and `Content-Disposition: inline; filename="<filename>"`.

**Transport note**: The Node.js adapter must handle raw body reading for uploads (not JSON parsing). The request's `Content-Type` header indicates the media type, not `application/json`.

**Test targets**: `voice-message-flow.test.ts` (send + receive audio via MXC), `edge-cases.test.ts` (audio sizes, metadata validation, download).

### Task 7: Profile and Account Data

**Create**: `handlers/profile.ts`, `handlers/account-data.ts`

**Profile endpoints**:
- `GET /_matrix/client/v3/profile/{userId}` → return `{displayname, avatar_url}`
- `PUT /_matrix/client/v3/profile/{userId}/displayname` → update display name
- `PUT /_matrix/client/v3/profile/{userId}/avatar_url` → update avatar URL

Profile changes should also update `m.room.member` state events in all joined rooms (Matrix spec requires this).

**Account data endpoints**:
- `GET /_matrix/client/v3/user/{userId}/account_data/{type}` → return content
- `PUT /_matrix/client/v3/user/{userId}/account_data/{type}` → set content
- `GET /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type}`
- `PUT /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type}`

Account data changes should notify the user's wake channel (so incremental sync picks them up).

**Test targets**: `contacts.test.ts` (room names, DM detection via m.direct), `family-room.test.ts` (member retrieval, display names).

### Task 8: Receipts

**Create**: `handlers/receipts.ts`

**Endpoint**: `POST /_matrix/client/v3/rooms/{roomId}/receipt/{receiptType}/{eventId}`

**Details**:
- Store receipt: `(roomId, receiptType, userId) → {eventId, ts}`
- Notify room members via wake channels
- Receipts appear in sync as ephemeral `m.receipt` events in the room's `ephemeral.events` list
- Format: `{ "$eventId": { "m.read": { "@user:server": { "ts": 12345 } } } }`

**Test targets**: `read-receipts.test.ts` (mark as played, receipt callback).




### Task 10: Browser Transport (Service Worker)

**Create**: `transport/sw.ts`, server tab entry point

**Scope**: Service Worker that intercepts `fetch()` to `/_matrix/*` and routes to the server core. Server tab registers the SW and provides a UI showing server status/logs.

**Deferred** — implement after Node.js transport is validated. Design is documented in the architecture section below.

---

## Browser Architecture (Task 10 Reference)

```
┌───────────────────────────────────────────────┐
│  Browser                                       │
│                                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Alice    │  │ Bob      │  │ Server   │    │
│  │ (tab)    │  │ (tab)    │  │ (tab)    │    │
│  │ WataClient│  │ WataClient│  │ registers│    │
│  └────┬─────┘  └────┬─────┘  │ SW       │    │
│       │              │        └──────────┘    │
│       │  fetch()     │  fetch()               │
│       └──────┬───────┘                        │
│              ▼                                │
│     ┌─────────────────┐                       │
│     │ Service Worker   │                       │
│     │ (sw.ts)          │                       │
│     │ intercepts fetch │                       │
│     │ → server.ts      │                       │
│     └─────────────────┘                       │
└───────────────────────────────────────────────┘
```

The server tab registers a Service Worker scoped to its origin. The SW intercepts all `/_matrix/*` fetch requests and routes them through `server.ts`. Client tabs point their homeserver URL at the server tab's origin.

---

## v2 Considerations

- **Go/Rust rewrite**: Handler logic ports directly. Replace `Map`/`Array` with Go maps/slices. Transport → `net/http`.
- **SQLite persistence**: Replace in-memory store with three databases: `users.db`, `rooms.db`, `media.db`.
- **WASM**: Go compiles to WASM. SW adapter works identically. Media storage via IndexedDB/OPFS.
- **Cloudflare Workers**: Durable Objects for room state, R2 for media.
