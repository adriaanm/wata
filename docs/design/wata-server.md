# wata-server — architecture

`wata-server` is a single-binary Matrix homeserver implementing a slice of the
Client-Server API, written in Sgola (a restricted Scala 3 dialect that
compiles to Go source — see the repo root for the toolchain) and built as an
`app` module over the `core` and `json` Sgola libraries (`wata-server/sgo.build`,
`wata-server/go.mod`). There is no JVM at runtime: `sgo build` emits Go, which
is compiled and run like any other Go program.

The source lives entirely in `wata-server/src/main/scala/` — 9 files, ~2900
lines:

| file | lines | role |
|---|---|---|
| `model.scala` | 131 | domain ADTs: errors, auth, users/devices, rooms/events/media/receipts, config |
| `membership.scala` | 89 | the room-membership state machine (join/invite/leave/ban transitions) |
| `jsonnav.scala` | 194 | JSON object/field helpers over the `json` module's `Json` type |
| `store.scala` | 802 | the single in-memory store: one `Mutex[StoreState]`, all reads/writes, long-poll waiter bookkeeping |
| `persist.scala` | 280 | append-only JSONL journal + boot-time replay |
| `handlers.scala` | 249 | routing table entry, auth middleware, login/logout/whoami/profile/account-data handlers |
| `rooms.scala` | 457 | createRoom/join/invite/send/redact/receipt/upload/messages handlers |
| `sync.scala` | 398 | `/sync` (initial + incremental) and the long-poll wait |
| `server.scala` | 302 | HTTP boot, mux registration, request edge, `SelfCheck` |

## Scope

Implemented: password login (`m.login.password`) against two hardcoded users
(alice/bob — `model.scala:120-131`, `Config.userByLocalpart`), device/access-token
sessions, profile (displayname/avatar_url), global and per-room account data,
room creation with the common presets, join (by id or alias), invite, leave,
kick, ban, setting arbitrary state events, sending and redacting `m.room.*`
events, read receipts, media upload/download, and `/sync` including
long-polling. Deliberately absent: any encryption (no
`m.room.encrypted` handling, no device-key endpoints), federation, user
registration (users are a fixed, hardcoded pair — `wata-server/src/main/scala/model.scala:120-131`),
`/publicRooms`, and `createRoom`'s
`initial_state`/`creation_content`/`power_level_content_override` fields
(`rooms.scala:13-18`).

## Request lifecycle

Entry point: `Main.main` (`server.scala:123`) calls `Server.serve` (or
`SelfCheck.run` if invoked with the argument `selfcheck`). `Server.serve`
(`server.scala:77`) calls `Store.init()` to seed the two config users'
profiles, `Journal.boot()` to replay any existing log, builds a Go 1.22
`ServeMux`, registers one `WataHandler` instance against every method+path
pattern (`Server.registerRoutes`, `server.scala:93`), adds a catch-all `"/"` →
`NotFound`, and calls `ListenAndServe`.

Every registered route shares the *same* `WataHandler` object
(`server.scala:15`). `serveHTTP` reads the whole request body up front (the
only place a real `throws sgo.GoError` is caught in the server) and hands off
to `MediaEdge.dispatch` (`server.scala:27`), which special-cases media
*download* — the only endpoint that writes raw, non-JSON bytes with a stored
`Content-Type` (`MediaEdge.download`, `server.scala:36`) — and routes
everything else, including media *upload*, through the JSON pipeline
(`MediaEdge.jsonReply`, `server.scala:32`).

The JSON pipeline calls `Router.route` (`handlers.scala:20`), which is a flat
if/else chain matching on HTTP method and `r.URL.Path` (substring/suffix
tests, not the mux's own pattern variables beyond `PathValue`). `route`
dispatches to per-area objects: `Router` itself for auth/profile/account-data,
`Rooms` for room/messaging/media/receipt endpoints, `Sync` for `/sync`. Each
handler function threads down to a leaf that returns `Either[MErr, Json]` —
`Left` for a Matrix-style error, `Right` for the 200 body. There is no
exception-based control flow for domain errors; `MErr` (`model.scala:38`) is
a plain value carrying an HTTP status, a sealed `ErrCode` tag, and a message.
`WataHandler`/`MediaEdge.jsonReply` is the only place that turns an `Either`
into bytes on the wire (`Json.write`, `Respond.finish`).

Auth is not middleware in the Go-http sense; it's a function each handler
calls first: `Router.requireAuth(r)` (`handlers.scala:49`) reads the
`Authorization: Bearer <token>` header and resolves it via
`Store.deviceByToken`. A missing/malformed header is `401 M_MISSING_TOKEN`,
an unrecognized token is `401 M_UNKNOWN_TOKEN`. Ownership checks (e.g. "can
only set your own displayname") are then done by comparing the resolved
`Auth.userId` against the path's `{userId}` (`handlers.scala:166` and
elsewhere) — there is no admin/impersonation concept.

Response envelope: every JSON response goes through `Respond.finish`
(`server.scala:54`), which sets `Content-Type: application/json` and
wide-open CORS headers (`Access-Control-Allow-Origin: *`, plus explicit
methods/headers) unconditionally, then writes the status and body. `NotFound`
(`server.scala:46`) answers unmatched paths with a Matrix 404 envelope, and
answers `OPTIONS` (CORS preflight) with 204 — this is the *only* place CORS
preflight is handled, and it only works because `"/"` catches every otherwise
unmatched pattern including `OPTIONS` requests to routes that exist under GET
or PUT.

## Data model and store

All server state lives in one process-wide guarded cell:
`Store.cell: Mutex[StoreState]` (`store.scala:63`). `StoreState`
(`store.scala:34`) is a plain mutable-field class — 13 `var`s — covering
devices/tokens (two HashMaps, one for lookup by id and one by token),
profiles, a flat `List[AcctData]` for all account data (global and per-room),
rooms (`HashMap[String, Room]`), aliases, media, a flat `List[Receipt]`, a
`HashMap` for per-device transaction idempotency, the ordered list of room
ids (newest-first), a global monotonic `seq` counter, and a flat
`List[Waiter]` for long-poll registrations plus its own sequence counter.

Every operation on the store — reads and writes alike — takes the *same*
single lock via `cell.withLock`. There is no reader/writer split (Sgola's
`Mutex` has no RWMutex variant); the store comment (`store.scala:6-28`)
frames this as intentionally coarse, mirroring what would have been a
single-threaded event loop in an earlier implementation this was ported from.
Every named store method is one `withLock` transaction, i.e. one atomic
operation; there are no multi-step operations that hold the lock across two
separate `Store` calls. `Store.updateMemberProfile` (`store.scala:162`) is the
one place that deliberately *snapshots* under the lock and then does further
work (rewriting `m.room.member` state events room-by-room, notifying members)
*outside* the lock, each such follow-up being its own transaction — the
snapshot is a small `RoomsProfileSnap` case class (`store.scala:59`) rather
than the raw guarded lists.

**Rooms and events.** `Room` (`model.scala:87`) holds `state: List[(String,
Event)]` — an insertion-ordered association list keyed by
`stateKeyOf(etype, sk) = etype + "|SK|" + sk` (`store.scala:348`) — and
`timeline: List[Event]`, the full chronological event log (append-only,
nothing is ever trimmed). `addEvent` (`store.scala:395`) appends to the
timeline and, if the event carries a state key, replaces-or-appends the
matching state slot (`putState`, `store.scala:417`). Both operations do a
linear scan of the list; this is a genuine (documented) tradeoff for rooms
that stay small. Optional Matrix fields (`state_key`, `redacts`, `unsigned`)
are represented as flat `(hasX: Boolean, x: T)` pairs rather than
`Option[T]`, because the composite-key/state-map code needs cheap value
equality rather than `Option` boxing.

**Membership** is *not* stored as a separate field; it is read out of the
current `m.room.member` state event's `content.membership` string
(`Store.getMembership`, `store.scala:380`, via `Mem.parse` in
`membership.scala:52`). `membership.scala` defines the actual state machine —
a sealed `Membership`/`MAction`/`Trans` family and a `transition` table
(`Mem.transition`) — that handlers consult before allowing a membership
change. All four actions are reachable: `AJoin` from `POST /join`, `AInvite`
from `POST /invite`, `ALeave` from both `POST /leave` (the caller moves
themselves out) and `POST /kick` (a member moves someone else out), and
`ABan` from `POST /ban`. `ALeave`/`ABan` are always `allowed` in the table —
*who* may perform them is a power-level question (`Power.canKick`/`canBan`),
not a from-state one.

Each of the three writes an ordinary `m.room.member` state event through
`Store.addEvent`, so the state map, `/sync`, and the journal need no special
case. A departure is notified to the room *and* to the departed user
explicitly (`Rooms.depart`): once their member event says `leave`/`ban`,
`notifyRoomMembers` no longer counts them, so their own long-poll would
otherwise sit until timeout instead of waking on the `rooms.leave` block that
tells them they are gone.

**`PUT /rooms/{roomId}/state/{eventType}/{stateKey}`** (`Rooms.setState`) is
the generic state-setting endpoint: caller must be joined and have the power
level for that event type, the body must be a JSON object, and the state key
is optional in the path. Clients write the key three ways — omitted, empty (a
trailing slash), or present — and a plain `{stateKey}` segment matches none of
the first two, so the route is registered twice, the second time with a
trailing wildcard `{stateKey...}` that also matches an empty remainder.

**Power levels** (`power.scala`) are the second half of every authorization
decision: `Mem.transition` says whether a change is legal at all, `Power` says
whether *this actor* may drive it, and handlers ask them in that order. The
whole module is lookups with defaults out of the room's one
`m.room.power_levels` state event, compared with `>=`; the defaults are the
spec's, and are also what `Rooms.powerLevels` writes into a new room —
`users_default` 0, `events_default` 0, `state_default` 50, `invite` 0,
`kick`/`ban`/`redact` 50, creator 100. A room with no power-levels event falls
through to those defaults, which leaves message sending open and state setting
closed.

| attempt | check |
|---|---|
| `PUT /send/{eventType}` | `Power.canSend(..., isState = false)` — `events[type]`, else `events_default` |
| `PUT /state/{eventType}/…` | `Power.canSend(..., isState = true)` — `events[type]`, else `state_default` |
| `PUT /redact/{eventId}` | `Power.canRedact` — the `m.room.redaction` send level, plus the `redact` level when the target is someone else's event |
| `POST /invite` | `Power.canInvite` — the `invite` level |
| `POST /kick` / `/ban` | `Power.canKick`/`canBan` — the named level, AND the actor must strictly outrank the target, so equals cannot evict equals |

A failure is `403 M_FORBIDDEN`. Membership events the server writes on a user's
own behalf — joining, and the display-name fan-out that rewrites
`m.room.member` in every joined room — do not go through the state route and
are not power-checked; membership rules govern those.

**Account data** (`AcctData`, `model.scala:58`) is one flat list for both
global and per-room entries, disambiguated by a `(hasRoom, roomId)` pair
rather than `Option[String]`; each entry carries the `seq` at which it was
last set, which is what makes `/sync`'s incremental account-data delta
possible (`Store.acctSinceGlobal`, `store.scala:715`).

**Media** (`MediaItem`, `model.scala:91`) is stored as a byte-preserving
`String` (Go's `string([]byte)` round-trips arbitrary bytes including invalid
UTF-8) rather than a byte slice, to keep the case class free of an opaque Go
type. There is no eviction, size cap, or expiry: every uploaded blob lives in
the `HashMap` for the life of the process (and, if persistence is on,
forever in the journal too — see "Known gaps").

**Long-poll waiters.** `Waiter` (`model.scala:109`) pairs a monotonic `id`
with an *unbuffered* `Chan[Boolean]` that is used purely as a
close-signalled wake — nothing is ever sent on it, only closed. `notifyUser`
(`store.scala:303`) removes a user's waiters from the shared list and closes
their channels *inside* the same lock transaction that removes them (closing
a channel cannot block, so this is safe under the lock); this ordering is
also what the code's no-lost-wake argument depends on: a waiter is only ever
closed by whoever removed it from the list under the lock, so a channel is
closed at most once. The blocking wait itself (`Store.waitForEvents`,
`store.scala:333`) is *not* done under the lock — it's a `select` over the
waiter channel and a timer (`sgo.select2` + `go.time.After`), executed by the
handling goroutine outside any lock.

## Sync

`/sync` (`sync.scala`) has a pure part and a blocking part.

**Pure builder.** `Sync.buildParts` (`sync.scala:118`) takes a fresh
`upTo = Store.globalSeq()` snapshot of the store's sequence counter *before*
reading anything else, then branches: no `since` token, or `full_state=true`,
goes to `initialParts` (`sync.scala:138`); otherwise `incrParts`
(`sync.scala:165`). The comment at `sync.scala:110-117` explains why `upTo`
is captured first: any event committed between reading `upTo` and reading the
room timelines has `seq > upTo` and is simply picked up by the *next*
incremental sync (harmless — the client dedupes by event id), while capturing
it *after* could silently drop an event forever. This is the subtlest
correctness property in the file and is called out explicitly because each
of the several store reads that build a sync response takes its own lock —
concurrent writers between them can produce a torn (but self-correcting)
view.

*Initial sync* returns, for every room the user has joined or been invited
to, full current state and the full timeline (`initialParts`), account data,
and receipts. *Incremental sync* (`incrParts`) computes, per joined room,
`Store.timelineSince(roomId, sinceSeq)` (new timeline events) and
`Store.receiptsSinceRoom` (new receipts since the token), and includes a
room's block **only if** it has new timeline events or new receipts
(`buildJoinsIncrStep`, `sync.scala:183`). The comment at `sync.scala:175-182`
documents this as a deliberate deviation from the earlier reference
behavior, which included a room whenever it had *any* receipts at all
(regardless of recency) — that made `hasChanges` permanently true for any
room that ever received a receipt, which would have made long-polling never
engage for such a room. `Store.receiptsSinceRoom` (`store.scala:663`) exists
specifically to support this: the store method comment there notes it was
present-but-unused in the reference this was ported from, i.e. its dormant
existence is itself the evidence the original intent had rotted away.
Invited rooms are included incrementally only if the user's own
`m.room.member` invite event is newer than the since-token
(`Sync.memberEventIsNew`).

**Timeline window, `limited`, `prev_batch`.** A room block carries at most
`Sync.timelineWindow` (20) timeline events. When more would qualify, the
oldest are withheld, `limited` is `true`, and `prev_batch` names the
position immediately before the oldest event sent, so
`GET /messages(from = prev_batch, dir = b)` returns exactly the withheld
run. When nothing is withheld, `limited` is `false` and `prev_batch` is the
position the block was measured from — `s0` for an initial sync, the
since-token for an incremental one.

A joined room whose member event for this user is newer than the
since-token is *new to them in this delta*: its history predates the token,
so a timeline delta would omit everything said before they joined.
`Sync.incrBlock` sends such a room in the INITIAL block shape instead —
full state plus a windowed timeline — which is what makes a mid-history
join recoverable by a stock Matrix client. (A display-name change rewrites
the member event too, so it re-sends the block; clients dedup, and the
alternative is tracking per-user membership history in the store.)
An incremental room block's `state` still carries the state deltas of every
new event, including any the window withheld, so current state stays
complete even when the timeline is `limited`.

**Long-poll.** `Sync.handle` → `handleAuthed` → `syncResult`
(`sync.scala:57`) builds the parts once; if it's an incremental sync
(`hasSince`), the timeout is positive, and the built parts show no changes
(`hasChangesOf`, `sync.scala:123`, which checks the three delta lists without
re-walking the assembled JSON), it calls `longPoll` (`sync.scala:68`).
`longPoll` registers a waiter *first*, then re-checks for changes — this
register-then-recheck order is what closes the race between "no changes
found" and "a write lands before we start waiting" (a write between the two
checks will `notifyUser`, which finds and wakes the just-registered waiter).
If the recheck already has data the waiter is dropped and the response
returned immediately (`finishNoWait`); otherwise the handler blocks in
`Store.waitForEvents` until either the waiter's channel closes or the timer
fires, then removes the waiter (idempotently — `removeWaiter` is a no-op if
`notifyUser` already removed it), rebuilds `buildParts` once more from the
*original* since-token, and returns whatever that shows — win, spurious
wake, or timeout are handled identically (rebuild once, return; there is no
re-loop).

`next_batch`/`prev_batch` tokens are the literal string `"s" + seq`
(`Sync.tok`, `parseSince`/`stripS`) — the global `seq` counter formatted
with an `s` prefix, not a room-scoped token. `GET /messages` reads and
writes the same positions (`from`, `start`, `end`), so a token is
interchangeable between the two endpoints: `s<N>` names the boundary just
after the event whose sequence is N. Backward paging (`dir=b`, also the
default) returns the newest `limit` events at or below the boundary,
newest-first, with `end` one position below the oldest sent; forward paging
returns the oldest `limit` above it, oldest-first, with `end` at the newest
sent. `/messages` also still accepts an EVENT ID as `from` — it resolves to
the boundary just outside that event in the paging direction, so a page
never repeats its own anchor. A `from` that is neither a position nor an
event of the room yields an empty chunk rather than an error.

## Persistence

`persist.scala` implements an append-only JSONL journal, off by default and
enabled only when the `WATA_LOG` environment variable is set
(`Journal.boot`, `persist.scala:58`). When enabled, every mutating `Store`
method calls `Journal.rec` with a small `Json` object describing the
*concrete post-generation record* — the already-minted event id, token,
timestamp, and seq, never the generator inputs — so replay reinserts state
verbatim without re-invoking `crypto/rand` or the wall clock (`persist.scala:14-21`).
Fourteen op kinds are logged: `device`, `rmDevice`, `profile`, `acct`,
`room`, `event`, `redact`, `alias`, `media` (bytes base64url-encoded, since
raw bytes aren't JSON-string-safe), `receipt`, `txn`. Long-poll waiters are
explicitly *not* logged (they're in-flight goroutines, transient by nature).

The journal has its own, separate `Mutex[JournalState]` (`persist.scala:45`),
distinct from the store's — `Journal.rec` is called from *inside* a
`Store.withLock` span (via the `if Journal.enabled then Journal.rec(...)`
calls scattered through `store.scala`), so if it shared the store's lock this
would deadlock; a second mutex avoids the re-entrancy problem entirely.

**Boot replay** (`Journal.replay`, `persist.scala:185`): reads the whole log
file (a missing file is not an error — first boot), splits on `\n` by hand
(no `String.split` in this dialect), and replays each line in file order,
which is guaranteed to equal commit order because every `rec` call happens
under the store's write lock. Replay runs *before* `Journal.on` is flipped
true, so the `Store.replay*` methods it calls reuse the same mutation
helpers (`redactRoom`, `addToState`, etc.) *without* re-triggering journal
writes. Each replayed record bumps the store's global `seq` past whatever it
carries (`Store.bumpSeq`, `store.scala:737`) so that post-reboot mutations
stay strictly monotonic relative to anything replayed. A malformed line is
silently skipped (best-effort, not validated).

## File-by-file map

- **`model.scala`** — every domain ADT: `ErrCode`/`MErr` (errors as values), `Auth`, `UserCfg`, `Device`, `Profile`, `AcctData`, `Event`, `Room`, `MediaItem`, `Receipt`, `Waiter`, and `Config` (the two hardcoded users, `serverName = "localhost"`).
- **`membership.scala`** — the membership sealed types and the join/invite/leave/ban transition table; every row is reachable from an HTTP route.
- **`jsonnav.scala`** — `JsonNav`: field lookup/typed accessors on `Json`, object/array builder helpers (`obj1`..`obj4`, `arr1`, `endObj`), `errEnvelope`, `eventToJson`, and the account-data profile-merge helper.
- **`power.scala`** — `Power`: the `m.room.power_levels` authorization table (send/state/redact/invite/kick/ban).
- **`store.scala`** — `StoreState` + `Store`: every store mutation and read, ID generation, the long-poll waiter lifecycle, and the boot-replay entry points (`replay*`) that `persist.scala` calls into.
- **`persist.scala`** — `Journal`: the JSONL op log, its own mutex, boot replay, and per-op-kind (de)serialization.
- **`handlers.scala`** — `Router`: the top-level route dispatch, `requireAuth`, `/versions`, login/logout/whoami, profile, and account-data handlers.
- **`rooms.scala`** — `Rooms`: createRoom, join, invite, leave/kick/ban, the generic state PUT, send/redact events, receipts, media upload, and `GET /messages` pagination.
- **`sync.scala`** — `Sync`: the pure sync-parts builder (initial + incremental) and the long-poll orchestration.
- **`server.scala`** — `WataHandler`/`MediaEdge`/`NotFound`/`Respond` (the HTTP edge), `Server` (boot + route table), `Main`, and `SelfCheck` (a deterministic smoke test of the store/handler logic, diffed against a golden file by the build's smoke script).

## Known gaps / debt

Beyond what `WATA-TODO.md` already tracks (unexercised `/publicRooms`,
the unscheduled `/sync` allocation cost, and the
explicitly-deferred actor-store refactor), reading the code surfaced the following. Items with a `[KEY]` tag have a
line in `TODO.jsonl`; grep the key here for the body.

- `[SRV-MEDIA-UNBOUNDED]` **Unbounded memory and journal growth for media.** `Store.storeMedia`
  (`store.scala:513`) never evicts, caps, or expires blobs; with persistence
  on, every upload is also base64-encoded into the JSONL log
  (`Journal.mediaOp`, `persist.scala:153`) with no size check, so a handful of
  large uploads could make the journal (and boot-replay time) balloon.
- `[SRV-BODY-LIMIT]` **No request body size limit.** `WataHandler.serveHTTP` (`server.scala:18`)
  calls `go.io.readAll(r.body)` unconditionally — no `MaxBytesReader` or
  equivalent — so a single request (including a media upload) can consume
  unbounded memory.
- `[SRV-HARDENING]` **Password and token comparisons are plain string equality**
  (`Router.loginCheck`, `handlers.scala:116-118`; `Store.deviceByToken`,
  `store.scala:114`), not constant-time — a timing side channel in principle,
  though the fixed two-user, no-registration setup makes this low-stakes
  today.
- `[SRV-HARDENING]` **CORS is wide open unconditionally** (`Respond.finish`,
  `server.scala:56-59`: `Access-Control-Allow-Origin: *` on every response)
  with no configuration knob; fine for the current trusted-network / dev
  posture but worth flagging if this is ever exposed more broadly.
- `[SRV-ROUTE-SUBSTRING]` **`Router.route`'s dispatch is substring/suffix matching on the raw path**
  (`handlers.scala:20-40`, e.g. `path.contains("/send/")`,
  `path.endsWith("/join")`) layered on top of the mux's own exact
  method+pattern registration — the mux already disambiguates the concrete
  routes, so this second, looser dispatch inside `route` is redundant with,
  and looser than, what got the request there in the first place. It works
  today because the registered pattern set doesn't create an ambiguous
  substring collision, but it's a latent trap for the next added route
  (e.g. any future path containing `/join/` as a substring of something
  unrelated would misroute).
- **`GET /_matrix/client/v1/directory/room/{roomAlias}` vs.
  `Router.route`'s `path.contains("/directory/room/")` test**
  (`handlers.scala:32`) — this would also match an alias that itself
  contained the literal substring `/directory/room/`, though Matrix room
  aliases realistically won't.
- **Every list-shaped store slice (`acct`, `receiptList`, `roomIds`,
  `waiters`, room `state`/`timeline`) is scanned linearly on every read and
  write** (`store.scala`, throughout — e.g. `findAcct`, `filterReceipts`,
  `lookupState`). Documented as an accepted tradeoff for small/toy room and
  user counts, but worth naming as the load-bearing scalability ceiling.
- `[SRV-WRITE-ERR-SILENT]` **`Respond.writeBody`'s write error is silently swallowed**
  (`server.scala:71-75`, `catch case e: sgo.GoError => ()`) — a failed write
  to the client is not logged anywhere, which would make partial-response
  bugs hard to diagnose from server-side logs alone.
