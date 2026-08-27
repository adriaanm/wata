# wataclient — design and architecture

`wataclient` is the portable core of the Matrix client shared by the wata
framebuffer device (`wata-fb`) and the terminal client (`wata-tui`). It
contains no device-specific or platform-specific code: no HTTP sockets, no
file I/O, no audio hardware access. It is a pure state machine (the sync
engine) plus a thin runtime that drives that state machine through
injected capabilities. An app links `wataclient` whole-program and
supplies the capability implementations (real HTTP client, real clock,
and — where there is hardware to drive — an audio thread).

`wataclient` is written in Sgola (a restricted Scala 3 dialect compiled to
Go source by the `sgo` compiler). It is a **Sgola-only library**: it has
no `@goexport` surface and exports nothing directly callable from plain
Go. Its consumers are `wata-fb` and `wata-tui`, each of which links it
whole-program at the Sgola level, resolving it as a source sibling in this
repo — `sgo` searches the declaring module's parent dir for an in-link
library before it looks at the toolchain home.

There is no published payload. `sgo emit` can generate one (a
`wataclient/sgola/{meta,tasty,src}` tree of embedded source and TASTy, for
consumers that resolve the module from `pkg/mod` instead), but nothing
consumes it: no consumer's emitted `go.mod` references it, and the
tree goes stale silently on any edit under `wataclient/src/`. Re-enabling
it means putting `publish` back in `wataclient/sgo.build`, running `sgo
emit`, and committing the result — plus a check that keeps it honest.

Total size: 13 files, ~3400 lines, under `wataclient/src/main/scala/`.

## Why a separate module

`wataclient` is the code that would be identical on any device running a
wata Matrix client — desktop, phone, or the framebuffer device this repo
currently targets. Nothing here touches `go` facades (the network, the
clock, audio hardware); those cross into the module only through two
capability traits (`HttpDo`, `Clock`) that the app layer
implements. This is what makes the module "portable": the same source
compiles and runs correctly wherever an app supplies those two traits.

## The public surface

A consumer interacts with `wataclient` mainly through `runtime.scala`'s
`Runtime` object and the `MatrixClient` handle:

| Type | File | Role |
|---|---|---|
| `Session` | `session.scala` | Stored login credentials (`homeserver`, `username`, `accessToken`, `userId`, `deviceId`), the thing persisted to and loaded from a device-local config file. |
| `ClientConfig` | `runtime.scala:63` | Login parameters (homeserver/username/password/sync timeout) plus a `Session` to try resuming from. |
| `MatrixClient` | `runtime.scala:73` | The client handle: config, the three capability instances, and five channels (`actions` in, `events` out, `snaps` out, `stop`, `retry`) plus the audio-command channel. |
| `Runtime` | `runtime.scala:92` | Construction (`make`/`makeWithAudio`, and the `…Stored` pair that takes an `OutboxStore`), lifecycle (`start`/`stopClient`), and polling helpers (`pollEvent`, `pollSnap`, `waitForConnection`, `waitForSnapshot`). |
| `Action` / `UiEvent` | `runtime.scala:36-59` | The command and notification vocabulary between app and client. `ActReceipt`, `ActSendVoice`, `ActPlay`, `ActSetName`, `ActRedact`, `ActFavorite` (the plan-0019 favorite toggle, fire-and-forget — its result arrives as room state on the next sync), `ActRetryOutbox`/`ActAckOutbox` (the outbox, below), `ActQuit`. |
| `Handle` / `ClientHandle` | `handle.scala` | The other way to run the client (below): a non-blocking `start`, the same surfaces as methods, a pushed dirty-flag channel, and `stop` + `join`. |
| `OutboxStore` | `outbox.scala` | The third capability: `CAP` numbered slots of opaque text the app maps onto files. `MemOutbox` is the fallback for a consumer with nowhere to write. |
| `StateSnapshot` | `domain.scala:95` | The immutable UI-facing view of everything the client knows: connection state, self user, contacts, conversations, family. |

The consumer's shape is: build a `MatrixClient` with `Runtime.make` (or
`makeWithAudio`), call `Runtime.start` inside a `supervised` scope (which
forks the sync loop and the action loop as goroutines), push `Action`s
with `Runtime.sendAction`, and poll `UiEvent`s / `StateSnapshot`s with
`Runtime.pollEvent`/`Runtime.pollSnap`. `Runtime.stopClient` closes the
`stop` channel and sends the `ActQuit` poison pill to wind both loops
down; the enclosing `supervised` scope is the join point. It is
**idempotent** — a consumer that both disconnects (`wata-fb`'s
Settings -> Network OFF) and later quits calls it twice, and closing a
channel twice panics.

`Runtime.sendAction` **never blocks**. It is a `trySend`; on a full queue
a best-effort action (receipt, favorite, name, redact) drops silently and
a send/play surfaces its ordinary failure event. This is a hard rule
rather than a tuning choice: the caller is typically a UI thread, and the
action loop can legitimately sit inside a request for the length of the
HTTP deadline, so a blocking enqueue is a frozen screen.

## Two ways to run the client: the scope, or the handle

Everything above is the **scope** shape: the consumer opens
`sgo.supervised`, calls `Runtime.start` inside it, drives, and calls
`Runtime.stopClient`; the scope's exit is the join. `wata-fb` is built
that way — its frame loop and the client share a process lifetime, and
every frame it POLLS the cells (`pollEvent`, `pollSnap`) on its way to
drawing. Nothing about that changes, and nothing forces it to change.

`handle.scala` is the same runtime for a consumer that cannot lend its
call stack: a phone toolkit owns the main loop and the app starts,
observes, pokes and stops the client from callbacks. `ClientHandle.start`
returns immediately — the supervised scope moves onto a goroutine the
handle owns — and the `Handle` is the outside view:

```scala
ClientHandle.start(cfg, http, clock, spawner): Handle
  h.sendAction(a): Boolean     // trySend, as Runtime.sendAction
  h.snapshot(): StateSnapshot  // the newest published one, else the last seen
  h.connection(): ConnectionState
  h.events(): sgo.Chan[Event]  // the dirty-flag pump
  h.stop(): Unit               // idempotent — the same closed cell
  h.join(timeoutMs): Boolean   // the goroutine is gone
```

Every method is a packaging of a surface `Runtime` already has; the loops,
the channels and the module cells are literally the same ones. One client
per process still holds, so a second `start` waits for the first handle's
`join` (`ClientHandle.stopAndJoin` is the pair).

**The event pump is dirty flags, not data.** `events()` is a bounded
(`Runtime.TOPIC_QUEUE` = 16) channel of `EvSnapshotDirty` / `EvConnDirty`
/ `EvOutboxDirty` / `EvStopped` — a topic and nothing else. The runtime
publishes with `trySend` ONLY, at the points that already write the
fb-visible state: the end of a sync round, every connection-state
transition (`Runtime.emitConn`, which also fills the health cell
`connection()` reads), and `Outbox.publish`. So a slow or absent consumer
can neither block a loop nor grow a queue — `wata-fb` never reads this
channel at all and simply lets it stay full. Dropping is sound because the
consumer answers a flag by READING the current state: what matters is that
one later event of a topic survives, and for a live consumer it always
does. A pump that sleeps through ten rounds converges on its next read
(the `client-handle` integ scenario proves exactly this, overflow
included).

A shell's pump is a blocking `events().recv()` loop; `EvStopped` — which
`ClientHandle.runScope` delivers after the loops are gone, making room in
the queue if it must — is the last event it ever gets, so the pump ends
without the channel ever being closed under it. Callers that want a
deadline instead use `waitEvent`/`pollEvent`.

**The goroutine is the core's own.** `startClient` spawns its scope with
`sgo.spawn` — the portable spelling of a detached spawn, added for
exactly this shape (`SGO-DETACHED-SPAWN`, ruled A upstream) — so no
app-side capability is involved and a handle needs only `HttpDo` and
`Clock` from its host.

The handle's first consumer is the phone spike (`tools/phone-spike`),
whose Go shim drains `events()` into an `EventSink` the Swift/Kotlin host
implements — the gobind-shaped mapping of the same channel.

## The outbox: a failed send is kept, not lost

A recording exists exactly once — someone pressed the key and spoke — so a
send that fails on the network becomes a queue entry rather than a flash
and a hole in the conversation (`outbox.scala`, plan 0022).

- **Bounded and persistent.** `CAP` = 16 entries, one per slot of the
  injected `OutboxStore`; `wata-fb` writes them as
  `<config dir>/outbox/eN.msg`. Slots rather than names because the subset
  has no directory listing: a fixed slot scan at construction
  (`Runtime.mk` -> `Outbox.reset`) is the whole load protocol, and the
  order comes from the sequence number inside each entry. At the cap the
  OLDEST entry is dropped, as loudly as an undeliverable one.
- **The entry** is `seq`, the target (room id, or a CONTACT id when the DM
  room does not exist yet — the retry re-resolves it), duration, the
  transaction id every attempt reuses (so a send the server accepted but
  never answered is deduplicated rather than delivered twice), and the Ogg
  bytes VERBATIM. Nothing is re-encoded on retry.
- **The classified failure policy** (`Outbox.classify`, the owner's ruling
  of 2026-08-05) is the whole design:

  | class | statuses | what happens |
  |---|---|---|
  | delivered | 200 | entry gone; the unsent marker clears when the queue empties |
  | undeliverable | 4xx except 401/429 | entry DROPPED, the conversation marked — a poisoned head must not block the queue behind it |
  | retry | transport (status 0), 5xx, 401, 429 | entry stays in place; a long outage never loses a message |

  401 and 429 are deliberately not in the "refused" class: they describe
  our token and our rate, not the message.
- **When it retries.** Every successful sync round ends in `Outbox.kick`,
  which pokes the action loop (one poke in flight at a time). The action
  loop then drains the queue oldest-first and stops at the first
  retry-class failure, so nothing overtakes the message before it. All
  outbox state changes and all its disk IO are the ACTION loop's; the sync
  loop only reads `pending`.
- **The UI is told, not asked.** `EvOutbox(unsent, undelivered)` carries
  the conversation keys on every change, including once at `Runtime.start`
  for what came off disk. `unsent` holds ONE KEY PER QUEUED ENTRY (a
  conversation with two sends waiting appears twice), so a UI can count a
  conversation's queued sends — the rolodex card's "N sending" band — as
  well as ask membership; `undelivered` stays one key per conversation. It is an event rather than a snapshot field
  because a device that cannot reach the server publishes no snapshots —
  and that is exactly the device with a queue. `ActAckOutbox(key)` clears
  an undelivered mark when the user opens that conversation.
- **Degradation is announced.** A store that reports `persistent() ==
  false` (a host build, or a device whose config directory will not take a
  write) still queues for the session and prints one line saying so.

## The connect lifecycle

The client core **never terminates on failure — it reports and retries**
(plan 0022). Concretely, in `runtime.scala`:

- **`syncLoop` is a session loop.** Each pass publishes `Connecting`,
  calls `loginOrResume`, and on success runs `runSession` (engine reset +
  `syncRounds`) until that session ends. On failure it publishes the
  failure state, sleeps the backoff, and goes round again. The only exit
  is `stop`.
- **Backoff is one shape everywhere**: 1s doubling to a 60s ceiling
  (`nextBackoff`), reset to 1s on any success. `backoffSleep` serves it in
  `SLEEP_SLICE_MS` (200ms) slices, checking `stop` and the retry poke
  between slices — so a quit never freezes the last frame for up to a
  minute, and a poke is felt within a slice.
- **Which login door is the config's password field** (plan 0027,
  `freshLogin`). A config carrying a password logs in with it; a config with
  NONE — a handset provisioned by enrolment, which has nothing to type —
  calls `POST /_wata/v1/device-login` instead: no body worth the name, the
  iroh connection's proven node id is the credential, and the same response
  shape comes back, so everything downstream (`AuthCreds`, the session
  save, resume) is door-blind. Never both: a config WITH a password asked
  for that account explicitly. Over TCP device-login is 403 by design,
  which reads as an ordinary rejection below.
- **Rejection is its own state.** A `/login` answered 401/403 (`isAuthFail`)
  yields `LoginOutcome(_, rejected = true)`, which publishes
  `ConnAuthRejected` and jumps straight to the 60s ceiling. It still
  retries — an account can be provisioned a minute later — but the UI can
  now say "check the account" instead of "waiting for network", which is a
  different instruction to whoever is holding the device.
- **A dead token ends its session rather than backing off forever.** A
  401/403 on `/sync` clears `lastAuthC` and drops out of `syncRounds`, so
  the outer loop logs in again; an expired token is not something a
  reconnect can fix.
- **`retryNow`** arms the capacity-1 `retry` channel. The device's boot
  screen binds it to OK, so a user staring at "can't reach server" can
  force the next attempt instead of waiting out the ceiling.
  `Runtime.loginAttempts` counts attempts — the handle a test uses to see
  the loop turning and a poke landing.
- **The action loop runs from process start and never stands down.** It
  takes `lastAuthC` at the moment each action is dequeued, so it serves
  whatever session is current, and while there is none it builds a
  token-less `Hs` whose requests the server answers 401 — each action's
  ordinary failure path. (It used to block on a one-shot `auth` channel
  and stand down when login failed, which is what let the action queue
  fill behind it.)
- **Requests are bounded.** The core assumes its `HttpDo` has a
  per-request deadline; `wata-fb` supplies one (30s, or
  `WATA_HTTP_TIMEOUT_MS`) on both transports. A hung request must become a
  failed round, which the machinery above already handles.

### The two consumers

- **`wata-fb`** ([wata-fb.md](wata-fb.md)) is the device client: it builds
  with `makeWithAudio` and runs an ALSA/opus audio thread on the other end
  of `audioCmds`, so `ActPlay` reaches a speaker.
- **`wata-tui`** ([wata-tui.md](wata-tui.md)) is the host-side terminal
  client and admin REPL. It is **headless** — `Runtime.make`, no audio
  thread — so it never sends `ActPlay`; playback calls
  `MatrixHttp.downloadMedia` through an `Hs` built on `Runtime.lastAuth`,
  writes the bytes to a file, and hands that to an external player
  process. That same `Hs` is what its `raw`
  command rides, which is the only place a consumer builds an `Hs` of its
  own rather than letting the runtime own it. It is the second exercise of
  the capability seam and of `WATA_IROH_CONFIG`, and it proves the module
  really is device-independent: nothing under `wataclient/` changed to
  gain it.

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
  `receipts`, `prevBatch` (pagination token), `dmMembers` — the
  `net.wata.dm` pair, empty when the room is not a DM — and `favorites`,
  the event ids that currently carry a `net.wata.favorite` marker.
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
   `net.wata.favorite` adds (non-empty content) or removes (empty content)
   its `state_key` from the room's `favorites`,
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

### Classification — by stamp, never by inference

A room is a DM exactly when its state carries the server's `net.wata.dm`
event, whose `content.members` pair says who it is with (`stateDm`). That
is the whole rule. The engine infers nothing from `is_direct` — a room
carrying only that flag is not classified — and nothing is sticky, because
a structural state event needs no stickiness: it either is in the room's
state or it is not.

Family and groups follow the same rule (plan 0018): THE family room is the
room stamped `net.wata.family` (`stateFamily` sets `RoomState.isFamily`;
alias-prefix matching is retired — a room whose only claim is a `#family:`
alias is not classified), and a group is a room stamped `net.wata.group`,
whose `content.name` names it (`stateGroup` sets `RoomState.groupName`).
The server mints and members both — every account is joined to the family
room at provisioning time, and `POST /_wata/v1/group` joins a group's
members server-side — so the client's only job is to render what the
stamps say.

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

It derives from the rooms the server classified — DM stamps, group stamps,
the family stamp — and the family roster. List order: Family, then groups
in room (stamp-creation) order, then DMs, then the roomless roster rows.

**A conversation's `messages` come back NEWEST FIRST** (`buildMessages`),
which is the opposite of the room's own `voiceMessages` — those stay in
timeline order, because the backfill insert (`insertVoiceByTs`) and the
dedup both reason chronologically. The reversal happens once, at the
snapshot boundary, and it is free: the accumulator is built newest-first
anyway, so the old order was the one that paid for a reversal.

The order is a product decision, not a presentation detail, which is why
it lives here rather than in each client. Voice messages are ephemeral —
what a kid opens a conversation for is the thing that just arrived, and
under the old order that was the LAST row, reached by scrolling past
everything already heard. Newest-first also makes index 0 the message a
conversation opens on and the one a read receipt names.

The **conversation list is deliberately NOT sorted by recency**, though
the same argument seems to apply. It is a contact list, and its order is
something a kid learns as a position: papa is the second row. A list that
reorders itself whenever somebody talks means hunting for a name that
used to be where the thumb already is, and mis-sending to whoever was
promoted. Voice messages are ephemeral and interchangeable; people are
not. Unplayed messages are surfaced by the row's badge instead, which
draws the eye without moving the target.

Everything reading the list positionally follows from that: the fb chat
view renders row 0 at the top, `receiptLatest` takes the head, and the
tui's `msgs`/`play N` number from the newest. The one place still
thinking chronologically is the integ oracle, which asserts the order the
SENDER produced — so `lastDursIn` reverses the newest *k* back, and
`dursRun` counts its duration run down rather than up.

1. **Conversations from the classified DM rooms** (`dmConvs`): each room
   whose `dmMembers` pair names *us* is the conversation with the other
   member (`dmPeerOf`), and that member is a `Contact` with the display
   name their member event carries in that room. A pair that does not name
   us is somebody else's DM and is skipped. If two rooms name the same
   peer — possible only for a room that lost the server's pair claim and
   stayed joined — the first in room order wins.
2. **Group conversations** (`prependGroupConvs`): every room with a
   `groupName` becomes a `GroupConv` `Conversation` — family-shaped (no
   contact, PTT by roomId), named by the stamp (`Conversation.name`, the
   one conversation kind that carries its own name) — prepended between
   the family thread and the DMs, oldest group first.
3. **Family room** (`findFamily`): the first room stamped
   `net.wata.family` becomes the family conversation, prepended at
   index 0. Its joined members (excluding self) become `Family.members`,
   and every one of them is a contact whether or not a DM room exists yet
   (`rosterContacts`).
4. **Roomless family conversations** (`roomlessFamilyConvs`): a roster
   member with no DM conversation yet gets a placeholder `Conversation`
   with `roomId == ""`. The first send resolves the pair through the
   server's DM endpoint, which is what fills the room in (see
   `Runtime.resolveDmRoom`).
5. **Self display name** (`resolveSelfDisplay`): the first room where self
   has a display name that is more than the fallback below.

**Names.** A display name belongs to the ACCOUNT — an admin sets it on the
server (plan 0021) and it reaches a live client through the profile fan-out
onto `m.room.member`, so a rename lands on a syncing device with no restart
(integ scenario `admin-rename`). A member with no display name falls back to
the **localpart** of their mxid (`Names.displayOr`/`Names.localpart`,
domain.scala) — never the raw `@kid:example.org`, whose server half is noise
on a 26-column screen and means nothing to a family. The same fallback is
used everywhere a person is named: the member table the sync engine builds,
DM contacts, message senders, self, and the tui's `display()`.

`is_played` on a `VoiceMessage` (`isPlayed`, `syncengine.scala`) is
computed as "self's user id appears in that event's receipt user-id list"
— read receipts, not a separate read-marker.

**A receipt means HEARD, and only playback may post one.** A text client
has no better signal than "it was on screen", so it receipts on display;
this product's messages are audio, and the audio has to be played, which
is a signal worth having. So no client receipts on opening a conversation
or on rendering a row: the fb/mac bodies post one from `AePlaybackDone`
(`WataLogic.onAudioEvent`) and the tui from a `Player.run` that returned
no error. A playback that FAILED posts nothing — a broken speaker must
not tell a sender their message got through, which is exactly the failure
this hardware produces.

This makes the sender's double check mean something specific: not "they
were in the room when it arrived" but "it came out of a speaker". It also
costs a visible round-trip — the check appears when the receipt returns
through `/sync`, a moment after the audio ends, and nothing is drawn
optimistically in between.

Two consequences fall out of it, both of which used to be masked by the
on-open receipt covering the sender's own message:

- `unplayedOf` skips messages sent by self. There is nothing to hear in
  a message you recorded, and without the exclusion your own send would
  badge your own conversation until you played it back to yourself.
- `playedByPeer` (`SyncEngine.playedByPeer`) — the flag a SENT message's
  second check renders — is "a user other than the message's SENDER
  appears in that event's receipt list". Sender-relative, not
  self-relative: in a DM the non-sender is the peer, in the family/group
  thread any listener counts. It was already written to ignore a
  sender's own receipt; now no such receipt is posted in the first place.

Both flags derive from the same `ReceiptEntry` list; they just ask it
different questions.

`isFavorite` is the room's `favorites` membership for that event id — the
server's `net.wata.favorite` marker (plan 0019), which keeps the message
past media retention. It is NOT per-user: retention is server-global, so
anyone's favorite marks the message for everyone, and clearing it is an
empty-content rewrite of the same state slot. The client never guesses at
it — `ActFavorite` posts the toggle and the resulting state arrives on the
next sync round, so a starred row means the server has recorded it.

### `syncoracle.scala` / `syncdescribe.scala` — the test machinery

These are not part of the engine's runtime behavior; they exist purely to
pin the engine's behavior deterministically in CI.

- **`syncoracle.scala`** (`SyncOracle`) is a self-contained set of 19
  scripted scenarios (hand-built JSON sync responses covering empty syncs,
  joins, voice messages, DM classification, family rooms, dedup,
  redactions, read receipts — including the sender-relative
  `playedByPeer` rule — etc.), each driving `SyncEngine.process` /
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

`domain.scala` (142 lines) defines the plain data types the engine and
snapshot are built from:

- `ConnectionState` (sealed, 6 nullary cases: `Disconnected`,
  `Connecting`, `Connected`, `Syncing`, `ConnError`, `ConnAuthRejected` —
  the server refusing these credentials, which is a different sentence on
  screen and a different backoff than a transport failure) and
  `ConversationType` (`DmConv`, `FamilyConv`, `GroupConv`) are sealed families of empty
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

### A person's colour (`palette.scala`)

Plan 0070's rolodex makes identity the screen rather than a field on it, and
identity is a hue. The colour is meant to be a property of the PERSON, stored
server-side and fanned out the way display names already are; that field does
not exist yet, and what does exist here is the fallback the plan specifies —
a deterministic derivation from the user id, so every screen is colourful
from the first sync rather than waiting on a setup gate.

It lives in the client core because each client must NOT invent its own. A
handset and a wrist showing the same kid two different colours breaks the one
thing the design is built on ("roll to my colour and hold the button"), and
one shared function is the only way to prevent it. `Palette.subjectOf` is the
conversation-level rule about *whose* colour a thread wears — a DM takes its
CONTACT's, a group its room's, and the family thread answers `""` and keeps
cyan, which is not in the rotation.

**The assignment is over the SET, not over each id.** `Palette.forRoster` takes
the roster's subjects and answers one colour each: the ids are sorted, walked in
that order, and each takes its hashed preference or the next free hue after it.
That is what a screen showing several people must call. `forUser` is only the
PREFERENCE one id brings — on its own it is the birthday problem, and eight hues
with five contacts made two of them identical about four times in five, which
was visible on the simulator the day the rolodex first ran with a real family in
it. Sorting is what keeps the answer client-independent: two devices with the
same roster compute the same mapping without talking, which is the property that
made a derived colour acceptable at all. A roster larger than the palette wraps
— it must collide eventually — but only after all eight are spent. The real
answer is still plan 0070's server-side profile colour; this is what keeps the
months before it from demoing two identical greens.

The hash folds its whole accumulator before anyone takes it modulo eight (a hue
index is three bits, and without the fold those three bits are all that ever
mattered), and it reduces modulo a prime at every step so the answer cannot
depend on how wide an `Int` is on the platform doing the asking. Byte-lexicographic
`lessId` is spelled out over UTF-8 bytes for the same reason: a locale-aware
compare is exactly the kind of thing two clients could disagree about.

**The eight hues are spread on three axes, not one.** Every one carries black
text (`Palette.INK`) at 7.5:1 or better — that is the palette's constraint rather
than a decision each body makes, and it is why a card can be full bleed with its
name drawn straight onto it. But eight LIGHT saturated colours spread evenly
around the wheel are not eight distinguishable colours: the first pass put a
`lime` at 75° beside a `green` at 120° and they read as one, and three of the
eight were warm pinks. So the spread is by **hue** (coral, amber, yellow, green,
sky, violet, magenta — with a deliberate hole at 180°, where cyan is the family
thread's), by **lightness** (the yellow is the brightest thing here and the green
next to it a third darker, which is what separates them at 22 px and moving), and
by **chroma** — the eighth is a low-saturation `sand` rather than a second pink,
because a near-neutral is instantly not-a-hue and buys more separation than an
eighth saturated colour crammed between two others.

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
  `deviceLogin`, `sync`, `setDisplayName`, `redactEvent`, `sendReadReceipt`,
  `uploadMedia`, `downloadMedia`, `joinRoom`, `dmRoom`, `setFavorite`,
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
    correctly patched-in page CRC. A packet whose length is an exact
    multiple of 255 needs a **trailing zero segment**: the segment
    shorter than 255 is what ends a packet, so `[255, 255]` alone says
    "continues on the next page" and a reader that walks the table drops
    it. `OggOracle` carries a frame of exactly 2*255 for that reason —
    it is the one length the rule is visible at.
  - `Ogg.readFrames`/`frameCount`: extracts the audio **packets**, which
    is not the same as the audio pages. The lacing table divides a page's
    payload into segments of at most 255 bytes, and a packet runs until a
    segment shorter than 255 ends it — so one page may carry many packets
    (ffmpeg's muxer defaults to about a second of them), and a packet
    whose final segment is a full 255 continues onto the next page, which
    flags itself `FLAG_CONT`. The reader walks the table, carries an
    unfinished packet across the page boundary, and drops a carried
    packet if the next page does not claim to continue it — a stream cut
    mid-packet has half a packet, and half a packet is noise.

    The two Opus headers are skipped by their 8-byte magic (`OpusHead`/
    `OpusTags`) rather than by page ordinal, and only until the first
    audio packet, so a foreign stream may place them where it likes,
    including a tags packet long enough to span pages. Empty packets are
    skipped, which is what retires the EOS page.

    Our own writer emits exactly one packet per page and never spans, so
    nothing it produces distinguishes a packet-reading reader from a
    page-reading one. `OggOracle` therefore *builds* the foreign shapes
    out of the same frames — `packedStream` (all frames on one page),
    `spannedStream` (a 600-byte packet cut at a 255 boundary across two
    pages) and `truncatedStream` (that cut left unfinished) — and checks
    the frames come back identical. Against a page-reading reader those
    report 1, 2 and 1 frames instead of 5, 5 and 2.
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

## The woken-playback queue (`playq.scala`)

`PlayQ` decides which of several messages a client was woken to play
plays next, when one has waited too long to be worth playing live at
all, and when the burst is over. Pure scheduling over a clock and a
list — no audio, no platform, no session — which is why it is here and
not in the client that has all three.

Its only consumer today is wata-ios's PushToTalk receive half, where the
platform forces the shape: the system raises ONE speaker and activates
ONE audio session for a burst of pushes, and every message in that burst
has to play on it, in order, one at a time. Two rules are the whole
point, and both were got wrong on hardware first (2026-08-18):

- **The burst is the unit, not the message.** A message arriving while
  another is playing is appended; nothing is ever displaced by something
  newer. Four voice messages in a row is what a walkie-talkie does. The
  last-one-wins version played one of four.
- **A message's window counts only its own waiting.**
  `PLAY_WINDOW_MS` exists so a message does not play out of nowhere long
  after it was sent — a phone that was asleep, media that never
  resolves, a session handover that never arrives. It is charged against
  a message only while that message is at the HEAD of the queue with
  nothing playing, plus the age it arrived with (real waiting the client
  did not observe). So a burst whose playback together outlasts the
  window still plays in full, and a message that never resolves costs
  itself the window and the ones behind it nothing.

`NO_PROGRESS_MS` is a wedge-breaker, not a limit on burst length: every
offer, start and finish renews it, so only a playback that reports
neither an end nor a failure can reach it — which would otherwise hold a
speaker on screen and an audio session out of the app's hands for the
life of the process.

`playqoracle.scala` drives it over a virtual clock with the caller's own
loop written out once, so the composed behaviour is pinned rather than
each function in isolation: `wata-fb playqtest`, byte-diffed against
`tools/wataclient-playq.expected.txt` by `just client-tests`.

## `oracle.scala` and `capabilities.scala`

- **`capabilities.scala`** is the capability seam: `HttpDo`
  (`send(req) -> resp`) and `Clock` (`nowUnixMillis`, `sleepMs`) are
  Go-interface-shaped traits the app supplies implementations of. (The
  third capability, `OutboxStore`, is declared next to the queue that
  consumes it, `outbox.scala`, and is the same shape: slot IO the app
  performs, no filesystem in the core.) There
  is deliberately no randomness capability: transaction ids are generated
  from an `Atomic[Int]` counter (`Runtime.txnCounterC`) — deterministic
  and sufficient for a single-client process — and nothing else in the
  core consumes randomness, so a `Rand` trait would be dead contract
  surface every consumer had to implement. The seam is also where the
  transport is swappable without this module knowing: wata-fb's `HttpDo`
  impl can carry either a plain net/http client or one whose connections
  are embedded iroh streams (plan 0013; `WATA_IROH_CONFIG`) — same trait,
  same records. `HttpRequest`/`HttpResponse` are
  the plain records that cross the boundary; network failure is
  represented as a non-2xx (or, per `mhttp.scala:97`, status `0` for a
  malformed mxc URL) status rather than a Go error — the portable core
  never observes a Go `error` value. This holds even for an iroh dial
  refused by the peer (e.g. the server's allowlist gate): `HttpDo`'s
  Go-side impl (`wata-fb`/`wata-tui` `caps.scala`, `FbCaps.send` /
  `TuiCaps.send`) still catches the thrown `sgo.GoError` and folds it into
  `HttpResponse(0, "")` — but the refusal is not silent, it just does not
  cross this boundary. `go-pkgs/irohnet`'s `Dialer` logs the refusal
  reason once per distinct reason string (`irohnet_cgo.go`,
  `logDialError`) before the error ever reaches this trait, so both
  server-side client use and wata-fb/wata-tui get the log line for free —
  see `go-pkgs/irohnet/irohnet.go`'s package doc for the exact contract
  and `go-pkgs/irohnet/rust/src/lib.rs` (`irohnet_client_dial`,
  `format_application_close`) for where the reason
  (`"server refused: <code> <reason>"`) is recovered from iroh's
  `ConnectionError::ApplicationClosed`. [IROH-REFUSAL-LOUD], plan 0013 M5.
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
| `capabilities.scala` | 45 | Two of the three injected capability traits: `HttpDo`, `Clock`, plus header-list helpers (the third, `OutboxStore`, lives with its queue in `outbox.scala`). |
| `domain.scala` | 142 | Core domain types: connection state, conversation type, users/contacts/messages, room/engine working state, sync events, and `Names` (the display-name/localpart fallback). |
| `handle.scala` | 224 | `ClientHandle`/`Handle`: the non-blocking start (its scope on `sgo.spawn`) and the dirty-topic `Event`s — the client for a consumer that owns its own loop. |
| `matrix.scala` | 105 | Matrix C-S API request-body shaping and response parsing (pure, no transport). |
| `mhttp.scala` | 225 | The actual HTTP call surface for every Matrix endpoint this client uses, with 429 retry. |
| `ogg.scala` | 193 | Ogg container reader/writer for Opus audio, plus a bit-serial CRC-32. |
| `outbox.scala` | 465 | The bounded, persistent outbox: the `OutboxStore` capability, `MemOutbox`, the classified send/retry policy, and the entry format. |
| `playq.scala` | 166 | The woken-playback queue: a burst of messages a push woke the client to play, drained in arrival order, with the per-message live-play window and the no-progress cap. |
| `palette.scala` | 309 | A person's colour: the eight-hue palette (black text on every one, spread by hue, lightness and chroma), the family thread's cyan, and the set-based derivation (`forRoster`) that stands in for the server-side profile field. |
| `playqoracle.scala` | 183 | The caller's serve loop over a virtual clock, eight scripted bursts, rendered as a deterministic transcript for CI pinning. |
| `oracle.scala` | 398 | Portable byte-level self-test report (CRC, Ogg round trip, `Bytes`/`IArray` conformance) plus a foreign-container fixture walker. |
| `runtime.scala` | 796 | `MatrixClient` handle, `Runtime` object: construction, the retrying session loop, backoff, action loop, backfill orchestration, polling helpers. |
| `session.scala` | 41 | `Session` record (stored login credentials) and its JSON (de)serialization. |
| `syncdescribe.scala` | 306 | Renders engine state/events/snapshot as deterministic text, driven by real captured fixtures. |
| `syncengine.scala` | 932 | The sync engine: `process()` (ingest) and `buildSnapshot()` (derive UI view). The core of the module. |
| `syncoracle.scala` | 460 | 19 hand-scripted sync scenarios rendered as a deterministic text report, for CI pinning. |
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
- Because the walk runs BACKWARD, a backfilled message cannot simply be
  appended to the room's (chronological, oldest-first) message list the
  way a live timeline event is: `SyncEngine.extractVoiceBackfill` instead
  inserts it before the first message with an equal-or-newer
  `origin_server_ts` (`insertVoiceByTs`). The `>=` tie rule is what keeps
  a same-millisecond run ordered — of two equal-timestamp messages the
  one ingested later in the walk is the older one, so it lands in front
  of the one already inserted.
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

The whole recipe is pinned live by two integ scenarios
(`wata-fb/src/main/scala/integ.scala`, run by `tools/wataclient-integ.sh`),
each forcing a deep gap by pumping voice events into a DM by direct HTTP
before the client's first sync, with message *i* given duration *i* so one
walk of the final snapshot asserts completeness, exact count, and
chronological order at once (`dursRun` — which walks the snapshot's
newest-first list, so it counts the run DOWN from the last duration):

- **`backfill-paged`** — 130 pumped messages: 20 arrive in the limited
  initial window, the other 110 only through three backward `/messages`
  pages, so the exact 1..130 run cannot pass without the multi-page walk.
- **`backfill-cap`** — 531 pumped messages: exactly
  20 (window) + 10 pages × 50 = 520 arrive, starting at duration 12, and a
  follow-up wait asserts no later snapshot ever exceeds 520 — the
  documented stays-a-gap behavior at `maxBackfillPages`.

## Known gaps / debt (beyond `WATA-TODO.md`)

Items with a `[KEY]` tag have a line in `TODO.jsonl`; grep the key here
for the body. The untagged ones are recorded as things to know before
touching the surrounding code, not as work owed.

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
