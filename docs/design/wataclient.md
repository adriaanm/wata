# wataclient — design and architecture

`wataclient` is the portable core of the Matrix client used by the wata
framebuffer device (`wata-fb`). It contains no device-specific or
platform-specific code: no HTTP sockets, no file I/O, no audio hardware
access. It is a pure state machine (the sync engine) plus a thin runtime
that drives that state machine through injected capabilities. `wata-fb`
links `wataclient` whole-program and supplies the capability
implementations (real HTTP client, real clock, ALSA/opus audio thread).

`wataclient` is written in Sgola (a restricted Scala 3 dialect compiled to
Go source by the `sgo` compiler). It is a **Sgola-only library**: it has
no `@goexport` surface and exports nothing directly callable from plain
Go. Its only consumer today is `wata-fb`, which links it whole-program at
the Sgola level, resolving it as a source sibling in this repo — `sgo`
searches the declaring module's parent dir for an in-link library before
it looks at the toolchain home.

There is no published payload. `sgo emit` can generate one (a
`wataclient/sgola/{meta,tasty,src}` tree of embedded source and TASTy, for
consumers that resolve the module from `pkg/mod` instead), but nothing
consumes it: `wata-fb`'s emitted `go.mod` never references it, and the
tree goes stale silently on any edit under `wataclient/src/`. Re-enabling
it means putting `publish` back in `wataclient/sgo.build`, running `sgo
emit`, and committing the result — plus a check that keeps it honest.

Total size: 13 files, ~3400 lines, under `wataclient/src/main/scala/`.

## Why a separate module

`wataclient` is the code that would be identical on any device running a
wata Matrix client — desktop, phone, or the framebuffer device this repo
currently targets. Nothing here touches `go` facades (the network, the
clock, audio hardware); those cross into the module only through three
capability traits (`HttpDo`, `Clock`, `Rand`) that the app layer
implements. This is what makes the module "portable": the same source
compiles and runs correctly wherever an app supplies those three traits.

## The public surface

A consumer (`wata-fb`) interacts with `wataclient` mainly through
`runtime.scala`'s `Runtime` object and the `MatrixClient` handle:

| Type | File | Role |
|---|---|---|
| `Session` | `session.scala` | Stored login credentials (`homeserver`, `username`, `accessToken`, `userId`, `deviceId`), the thing persisted to and loaded from a device-local config file. |
| `ClientConfig` | `runtime.scala:63` | Login parameters (homeserver/username/password/sync timeout) plus a `Session` to try resuming from. |
| `MatrixClient` | `runtime.scala:73` | The client handle: config, the two capability instances, and five channels (`actions` in, `events` out, `snaps` out, `stop`, `auth`) plus the audio-command channel. |
| `Runtime` | `runtime.scala:92` | Construction (`make`/`makeWithAudio`), lifecycle (`start`/`stopClient`), and polling helpers (`pollEvent`, `pollSnap`, `waitForConnection`, `waitForSnapshot`). |
| `Action` / `UiEvent` | `runtime.scala:36-59` | The command and notification vocabulary between app and client. |
| `StateSnapshot` | `domain.scala:95` | The immutable UI-facing view of everything the client knows: connection state, self user, contacts, conversations, family. |

The consumer's shape is: build a `MatrixClient` with `Runtime.make` (or
`makeWithAudio`), call `Runtime.start` inside a `supervised` scope (which
forks the sync loop and the action loop as goroutines), push `Action`s
with `Runtime.sendAction`, and poll `UiEvent`s / `StateSnapshot`s with
`Runtime.pollEvent`/`Runtime.pollSnap`. `Runtime.stopClient` closes the
`stop` channel and sends the `ActQuit` poison pill to wind both loops
down; the enclosing `supervised` scope is the join point.

## The sync engine — the heart of the module

`syncengine.scala` (~800 lines) is a **pure state machine**: it turns a
parsed `/sync` response `Json` into a state delta plus a list of
`SyncEvent`s, with zero transport or time dependency. It is implemented
as a Sgola `object` (`SyncEngine`) holding module-level mutable state in
`sgo.Atomic` cells, not a class — the language subset here has no
ordinary mutable classes (only case classes and objects), and the engine
is a process-wide singleton: one sync loop per process, `reset()` between
independent uses (e.g. between test scenarios, or between processing
different users' streams).

### State it tracks

THREE atomic cells hold everything:

- `rooms: List[RoomState]` — one entry per room the engine knows about, in
  insertion order. `RoomState` carries `name`, `hasAlias`/`alias`,
  `members` (`List[MemberInfo]`), `timelineEventIds` (a dedup set),
  `voiceMessages` (accumulated `VoiceMessageRaw`s in timeline order),
  `receipts`, `prevBatch` (pagination token), and `dmMembers` — the
  `net.wata.dm` pair, empty when the room is not a DM.
- `selfUserId: String` — set once after login via `setSelfUser`.
- `batch: String` — the `next_batch` token to send on the next `/sync`
  call.

There is deliberately no `m.direct` state. DM identity belongs to the
server (`docs/plans/0007-canonical-dms.md`); the `m.direct` the server
still emits is a compat projection for stock clients, and this engine
ignores it.

All of these are `List`s with **insertion-order, replace-keeps-position**
update semantics: updating an existing room/member/receipt swaps it in
place; a new one is appended at the end. This mirrors an insertion-ordered
map and is important because display order (e.g. conversation order in
`buildSnapshot`) depends on it. There is no hashing — lookups are linear
scans (`findRoom`, `findMember`, etc.), which is fine at "family"-sized
room/member counts but would not scale to a large multi-room client.

### `process`: ingesting one sync response

`SyncEngine.process(resp: Json): List[SyncEvent]` (`syncengine.scala:243`)
is the entry point. It always:

1. Updates `next_batch`.
2. Walks `rooms.join`, once per room: state events, then timeline events
   (dedup against `timelineEventIds`, then dispatch by type: `m.room.name`
   updates the room name, `net.wata.dm` records the room's DM pair,
   `m.room.member` upserts a `MemberInfo` and emits `MembershipChanged`,
   `m.room.message` with `msgtype: m.audio` extracts a `VoiceMessageRaw`,
   `m.room.redaction` removes any voice message it redacts), then
   ephemeral events (`m.receipt`, appending user ids to a `ReceiptEntry`
   and emitting `ReceiptUpdated`), then emits `RoomUpdated` for the room.
3. Walks `rooms.invite` similarly (state comes from `invite_state`) and
   emits `RoomUpdated`.

`account_data` is not walked at all.

Event emission order is deliberate and pinned by the fixture oracle
(`syncdescribe.scala`): joined rooms in wire order (state events, then
timeline events, then receipt events, then `RoomUpdated` last for that
room), then invited rooms the same way.

### DM classification

A room is a DM exactly when its state carries the server's `net.wata.dm`
event, whose `content.members` pair says who it is with (`stateDm`). That
is the whole rule. The engine infers nothing from `is_direct` — a room
carrying only that flag is not classified — and nothing is sticky, because
a structural state event needs no stickiness: it either is in the room's
state or it is not.

What this replaced is worth recording, since it was most of the module's
historical brittleness: an `m.direct` ingest that rebuilt a
`userId -> [roomIds]` map wholesale on every account-data event, an
append-only `m.direct` serializer in `mhttp.scala`, a sticky `isDm` flag
inferred from `is_direct`, an inference pass that synthesized
conversations for rooms `m.direct` did not cover, and two *different*
"primary room" rules — one in `resolveDmRoom`, one in `buildSnapshot`.
All of it is gone.

### `buildSnapshot`: deriving the UI view

`SyncEngine.buildSnapshot(): StateSnapshot` (`syncengine.scala:614`)
derives the immutable `StateSnapshot` from the accumulated state on
demand (it is not incrementally maintained). The derivation, in order:

It derives from exactly two things: the rooms the server classified, and
the family roster.

1. **Conversations from the classified DM rooms** (`dmConvs`): each room
   whose `dmMembers` pair names *us* is the conversation with the other
   member (`dmPeerOf`), and that member is a `Contact` with the display
   name their member event carries in that room. A pair that does not name
   us is somebody else's DM and is skipped. If two rooms name the same
   peer — possible only for a room that lost the server's pair claim and
   stayed joined — the first in room order wins.
2. **Family room** (`findFamily`): the first room whose canonical alias
   starts with `"#family:"` becomes the family conversation, prepended at
   index 0. Its joined members (excluding self) become `Family.members`,
   and every one of them is a contact whether or not a DM room exists yet
   (`rosterContacts`).
3. **Roomless family conversations** (`roomlessFamilyConvs`): a roster
   member with no DM conversation yet gets a placeholder `Conversation`
   with `roomId == ""`. The first send resolves the pair through the
   server's DM endpoint, which is what fills the room in (see
   `Runtime.resolveDmRoom`).
4. **Self display name** (`resolveSelfDisplay`): the first room where self
   has a non-empty display name different from the raw user id.

`is_played` on a `VoiceMessage` (`isPlayed`, `syncengine.scala:756`) is
computed as "self's user id appears in that event's receipt user-id list"
— read receipts, not a separate read-marker.

### `syncoracle.scala` / `syncdescribe.scala` — the test machinery

These are not part of the engine's runtime behavior; they exist purely to
pin the engine's behavior deterministically in CI.

- **`syncoracle.scala`** (`SyncOracle`) is a self-contained set of 15
  scripted scenarios (hand-built JSON sync responses covering empty syncs,
  joins, voice messages, DM classification, family rooms, dedup,
  redactions, read receipts, etc.), each driving `SyncEngine.process` /
  `buildSnapshot` directly and rendering the resulting event names, room
  state, and snapshot into a deterministic text report (`report()`). This
  report is diffed against a pinned expected-output file in CI.
- **`syncdescribe.scala`** (`SyncDescribe`, 306 lines) does the same job
  but is driven by *captured real server traffic* instead of hand-built
  JSON — see the fixtures section below. `fixtureReport` takes a list of
  `(selfUserId, fixtureName, jsonBody)` triples, resets/re-seeds the
  engine whenever the self user changes, feeds each body through
  `SyncEngine.process`, and renders events + full accumulated room/member/
  receipt/mdirect state + the built snapshot as text.

Both render everything as plain deterministic `String`s (no floating
timestamps or map-iteration nondeterminism) so their output can be
byte-compared against a golden file — this is the "oracle" pattern used
throughout this module (see also `oracle.scala`).

## The domain model

`domain.scala` (115 lines) defines the plain data types the engine and
snapshot are built from:

- `ConnectionState` (sealed, 5 nullary cases: `Disconnected`,
  `Connecting`, `Connected`, `Syncing`, `ConnError`) and
  `ConversationType` (`DmConv`, `FamilyConv`) are sealed families of empty
  case classes rather than enums — there is no native enum construct in
  this language subset.
- `User`/`Contact`/`VoiceMessage`/`Conversation`/`Family` are the
  snapshot-facing, already-resolved types — e.g. `Conversation.roomId ==
  ""` specifically means "no DM room created yet."
- `MemberInfo`/`VoiceMessageRaw`/`ReceiptEntry`/`RoomState` are the
  engine's internal working representations, later resolved into the
  snapshot types by `buildSnapshot`.
- ID types (room id, user id, event id, mxc url) are all plain `String` —
  there is no newtype/opaque-alias layer distinguishing them at the type
  level.
- `SyncEvent` (sealed, 4 cases: `RoomUpdated`, `TimelineEventE`,
  `MembershipChanged`, `ReceiptUpdated`) is the engine's
  per-`process()`-call output vocabulary.

## HTTP transport and JSON handling

Two small modules separate "shape the Matrix wire protocol" from "send
bytes over a socket":

- **`matrix.scala`** (105 lines, object `Matrix`) is pure request/response
  *shaping*: building JSON request bodies (`loginBody`, `voiceContent`,
  `emptyBody`), parsing typed results out of response JSON
  (`parseLogin`/`parseWhoami`/`parseSend`/`parseUpload`/`parseRoom`), and
  small string utilities (`mxcToDownloadUrl`, `syncQuery`, a hand-rolled
  `intStr` decimal formatter — used because the language subset here
  disallows `Long` string conversion via a `strconv` facade). It has zero
  transport dependency.
- **`mhttp.scala`** (225 lines, `Hs` + object `MatrixHttp`) is the actual
  Matrix Client-Server API surface: one function per endpoint (`login`,
  `sync`, `setDisplayName`, `redactEvent`, `sendReadReceipt`,
  `uploadMedia`, `downloadMedia`, `joinRoom`, `dmRoom`,
  `createRoomWithAlias`, `createRoomStockDm`, `getMessages`,
  `sendVoiceMessage`), all going through
  a single `request`/`send1` chokepoint that adds the bearer token and
  content-type header and retries on HTTP 429 (up to 3 times, honoring
  `retry_after_ms` from the response body, defaulting to 1000ms and
  clamped to 60000ms — the sync loop's own backoff ceiling — so a
  server-supplied value can never stall the caller longer than an
  ordinary error backoff round). `Hs`
  bundles the two capabilities (`HttpDo`, `Clock`) with the base URL and
  current access token; it is immutable, so re-authenticating means
  building a new `Hs` (`withToken`). Media upload/download bodies cross
  the `HttpDo` capability boundary as raw-byte `String`s via
  `Bytes.rawString`/`Bytes.fromRawString` — exploiting the fact that a Go
  string is just immutable bytes, verified byte-exact end to end
  (`oracle.scala:135-145`).

- **`wjson.scala`** (61 lines, object `WJson`) is read-side JSON field
  navigation: `strField`/`boolField`/`longField`/`objField`/`hasStr` with
  defaulting, over the `json` module's `JObj`/`JArr`/`JStr`/... value
  types. It exists separately from the `json` dependency because `json`
  itself is a generic parser/AST module; `WJson` is the small,
  wataclient-specific "read a field with a default, tolerating absence or
  wrong type" convenience layer every parse site in this module uses.
  Every field read in `syncengine.scala` goes through it.

## Audio: Ogg/Opus and the command protocol

- **`ogg.scala`** (193 lines) is a from-scratch Ogg container reader and
  writer for Opus audio, over the portable `Bytes`/`BytesBuilder`
  prelude — no codec logic (Opus encode/decode itself lives outside this
  module, in the device layer). It implements:
  - `Crc.crc32`/`crc32Update`: a bit-serial (not table-driven) CRC-32
    matching Ogg's poly `0x04C11DB7`, init 0, no reflection. The comment
    at `ogg.scala:9-13` explains the bit-serial choice: a lookup table
    would need module-level state, which conflicts with a determinism
    requirement in this codebase, and a naively-sized table would hit the
    same 32-bit-on-64-bit-Go width issue the code works around by
    carrying the CRC in a `Long` masked to 32 bits each step.
  - `Ogg.opusHead`/`opusTags`: the two required Opus identification/
    comment header payloads (mono, 48kHz, 312-sample pre-skip; vendor
    string `"wata"`).
  - `Ogg.writeStream(frames: List[Bytes]): Bytes`: OpusHead page (BOS) +
    OpusTags page + one page per audio frame + an empty EOS page, with
    correct multi-segment lacing for frames over 255 bytes and a
    correctly patched-in page CRC.
  - `Ogg.readFrames`/`frameCount`: extracts just the audio payload of each
    page, skipping the BOS page, the second page (OpusTags, identified by
    page count rather than content), and any empty page.
  This is consumed by the recording path (`AcRecordStart`/`AeRecordingDone`
  in `audiocmd.scala`, encoded to Ogg by the device audio thread) and the
  playback path (`ActPlay` in `runtime.scala` downloads Ogg bytes and
  hands them to the audio thread as `AcPlay`).

- **`audiocmd.scala`** (44 lines) defines the mailbox protocol between the
  portable runtime and the device-side audio thread: `AudioCmd`
  (`AcRecordStart`/`AcRecordStop`/`AcPlay`/`AcStopPlayback`/`AcEchoTest`/
  `AcQuit`) and `AudioEvt` (`AeRecordingDone`/`AeRecordingError`/
  `AePlaybackDone`/`AePlaybackError`/`AeEchoRecording`/`AeEchoPlaying`/
  `AeEchoDone`/`AeEchoError`). These types are defined here (portable)
  because `MatrixClient` carries the `audioCmds: sgo.Chan[AudioCmd]`
  channel and the runtime's action loop produces `AcPlay` values; the
  thread that actually *consumes* them and drives real audio hardware
  lives in `wata-fb` (outside this module's scope), not here.

## `oracle.scala` and `capabilities.scala`

- **`capabilities.scala`** (55 lines) is the capability seam: `HttpDo`
  (`send(req) -> resp`), `Clock` (`nowUnixMillis`, `sleepMs`), and `Rand`
  (`nextU32`, currently unused — transaction ids are generated from an
  `Atomic[Int]` counter, not randomness) are Go-interface-shaped traits
  the app supplies implementations of. `HttpRequest`/`HttpResponse` are
  the plain records that cross the boundary; network failure is
  represented as a non-2xx (or, per `mhttp.scala:97`, status `0` for a
  malformed mxc URL) status rather than a Go error — the portable core
  never observes a Go `error` value.
- **`oracle.scala`** (398 lines, `OggOracle`) is a second, independent
  self-test report, orthogonal to the sync-engine oracles: it exercises
  CRC-32 golden vectors, an Ogg write/read round trip (verifying page CRC
  validity and exact frame-content round-tripping across a multi-segment
  frame), narrowing/widening/comparison of computed `Byte` values,
  `Bytes`-to-raw-`String` round-tripping over the full 0..255 byte range,
  `BytesBuilder.freeze` snapshot-then-mutate semantics, and
  `IArray`/`Array.toBytes` behavior. Its `report()` is designed to be
  **fully portable** (no `go`-facade use). Only the sgola-compiled side is
  exercised here (`just client-tests`, leg 4/5); running the same source
  under plain Scala/JVM and byte-comparing the two outputs is a claim
  about the compiler, so it is sgola's to make, not this repo's. It also
  contains `foreignReport` (`oracle.scala:321`), a
  page-by-page walk of a foreign (non-wataclient-authored) Ogg/Opus
  container, driven from a separately maintained pinned fixture, used to
  confirm the reader correctly parses containers that differ from the
  writer's own conventions (e.g. randomized serial numbers, audio data
  carried in the EOS page).

## Test fixtures

`wataclient/test-fixtures/` holds four captured `/sync` response bodies
from a real wata-server instance: `alice__01-initial.json` (an initial
sync, 4.2KB), `alice__02-incr.json` and `alice__03-incr.json`
(incremental syncs), and `bob__01-initial.json` (5.2KB). These are real
server output, not hand-constructed — they exercise the full shape of
what a genuine sync response looks like (room creation state events,
power levels, `m.room.member` with `unsigned.age`, etc.), which the 15
hand-built scenarios in `syncoracle.scala` do not attempt to be
comprehensive about.

They are consumed by `SyncDescribe.fixtureReport` (`syncdescribe.scala`),
driven by an app-side driver outside this module (`wata-fb`, per the
comment at `syncdescribe.scala:4`) that reads the fixture files and calls
into this reporting function; the resulting deterministic text output is
checked against a separately pinned expected-output file in CI.

## File-by-file map

| File | Lines | Contents |
|---|---|---|
| `audiocmd.scala` | 44 | Audio-thread command/event protocol types (`AudioCmd`, `AudioEvt`). |
| `capabilities.scala` | 55 | The three injected capability traits: `HttpDo`, `Clock`, `Rand`, plus header-list helpers. |
| `domain.scala` | 115 | Core domain types: connection state, conversation type, users/contacts/messages, room/engine working state, sync events. |
| `matrix.scala` | 105 | Matrix C-S API request-body shaping and response parsing (pure, no transport). |
| `mhttp.scala` | 225 | The actual HTTP call surface for every Matrix endpoint this client uses, with 429 retry. |
| `ogg.scala` | 193 | Ogg container reader/writer for Opus audio, plus a bit-serial CRC-32. |
| `oracle.scala` | 398 | Portable byte-level self-test report (CRC, Ogg round trip, `Bytes`/`IArray` conformance) plus a foreign-container fixture walker. |
| `runtime.scala` | 443 | `MatrixClient` handle, `Runtime` object: construction, sync loop, action loop, backfill orchestration, polling helpers. |
| `session.scala` | 41 | `Session` record (stored login credentials) and its JSON (de)serialization. |
| `syncdescribe.scala` | 306 | Renders engine state/events/snapshot as deterministic text, driven by real captured fixtures. |
| `syncengine.scala` | 932 | The sync engine: `process()` (ingest) and `buildSnapshot()` (derive UI view). The core of the module. |
| `syncoracle.scala` | 408 | 15 hand-scripted sync scenarios rendered as a deterministic text report, for CI pinning. |
| `wjson.scala` | 61 | Defaulting JSON field-read helpers used everywhere a sync/response body is parsed. |

## Backfill: the `limited` timeline gap

The Matrix backfill trigger is `rooms.join.<roomId>.timeline.limited ==
true` in a sync response: the server truncated that room's timeline, and
the client pages backward through `/messages` with `from = <that room's
prev_batch>` to close the gap. `wata-server` implements exactly that
contract — it sets `limited` whenever a room block withholds history, and
its `/messages` `from` parameter takes the same opaque `s<seq>` position
tokens `/sync` hands out — so `wataclient` runs the standard recipe and
carries no server-specific compensation.

The paging is **deferred work with a per-round bound**, so a deep gap
never stalls the round that discovered it — the sync loop stays a single
goroutine, and the queue is how it time-slices:

- `Runtime.processRound` (engine ingest -> auto-join -> `queueBackfills`
  -> `drainBackfill` -> `publishSnapshot`) turns each `limited` room in
  the response's `rooms.join` map into a `BackfillJob` (room id, the
  `from` token to page next, pages fetched so far) on the sync loop's
  queue cell (`backfillQC`, sync-loop-only, reset per session). A room
  already queued is *replaced*: the newer trigger holds a newer
  `prev_batch`, and the engine's dedup absorbs the overlap.
- `Runtime.drainBackfill` then fetches at most `backfillPagesPerRound`
  (2) pages before the snapshot is published — each page one
  `MatrixHttp.getMessages(dir = b, limit = 50)` call
  (`Runtime.backfillPage`), its chunk arriving newest-first and ingested
  in that order. A page whose walk isn't finished re-queues its job at
  the tail, so several limited rooms page round-robin.
- A job leaves the queue when its gap closes — an empty chunk, a missing
  `end`, an `end` equal to the `from` just used (the server's no-progress
  signal), or an HTTP failure — or at `Runtime.maxBackfillPages` (10
  pages, i.e. 500 events per `limited` trigger). A gap deeper than the
  cap stays unrecovered, deliberately: the oldest history of a very long
  absence is not worth unbounded paging, and no later trigger reopens it
  — a later `limited` sync starts from a *newer* `prev_batch`, so it
  re-covers the recent side of history, not the abandoned deep end.
- While the queue is non-empty, `Runtime.syncRounds` issues the next
  `/sync` with **timeout 0** (`roundTimeoutMs`) instead of the configured
  long poll: the loop keeps interleaving fresh sync ingest with backfill
  slices instead of parking a pending walk behind a long-poll expiry.
  Net effect: each round's snapshot publish and next sync call wait for
  at most two `/messages` calls, and a 10-page gap drains in ~5 rounds.
- Ingestion goes through `SyncEngine.ingestBackfill`, which is deliberately
  narrower than `process()`: it only dedups against `timelineEventIds` and
  extracts voice messages from `m.room.message` events — it does not
  process state events or emit `SyncEvent`s, because "backfill repairs
  message history, it does not replay the room's life."

A room the user joins mid-history needs no separate trigger: the server
sends a newly-visible room in the initial block shape — full state and a
windowed timeline that reports `limited` when it withheld anything — so the
standard path recovers the history. The engine's dedup absorbs any overlap
between a backfilled chunk and the room's later live `/sync` timeline.

## Known gaps / debt (beyond `WATA-TODO.md`)

Items with a `[KEY]` tag have a line in `TODO.jsonl`; grep the key here
for the body. The untagged ones are recorded as things to know before
touching the surrounding code, not as work owed.

- `[CLI-RAND-DEAD]` **`Rand` (`capabilities.scala:45`) has no consumer today** — it's
  defined for symmetry with `HttpDo`/`Clock` but transaction ids are
  produced from an `Atomic[Int]` counter (`runtime.scala:402`,
  `txnCounterC.add(1)`) rather than randomness. Not necessarily a bug,
  just dead surface worth knowing about if the capability contract is
  ever trimmed.
- **`applyRedaction` reads `redacts` as a plain string field on the event
  and falls back to `content.redacts`** (`syncengine.scala:521-524`) —
  this matches Matrix spec versions before and after the v1.10 move of
  `redacts` to the top level, but there's no explicit versioning; it
  silently prefers the top-level field when both are present.
- **The domain model's IDs are all plain `String`** (noted in the file's
  own comment, `domain.scala:7-10`) — room ids, user ids, event ids, and
  mxc URLs are interchangeable at the type level. This is a deliberate
  language-subset constraint (no top-level type aliases), not an
  oversight, but it does mean nothing stops a caller from passing an
  event id where a room id is expected; nothing in the reviewed code
  actually does this, but the type system provides no defense.
