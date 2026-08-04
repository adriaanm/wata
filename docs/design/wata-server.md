# wata-server — architecture

`wata-server` is a single-binary Matrix homeserver implementing a slice of the
Client-Server API, written in Sgola (a restricted Scala 3 dialect that
compiles to Go source — see the repo root for the toolchain) and built as an
`app` module over the `core` and `json` Sgola libraries (`wata-server/sgo.build`,
`wata-server/go.mod`). There is no JVM at runtime: `sgo build` emits Go, which
is compiled and run like any other Go program.

The source lives entirely in `wata-server/src/main/scala/` — 19 files, ~4700
lines:

| file | lines | role |
|---|---|---|
| `model.scala` | 126 | domain ADTs: errors, auth, users/devices, rooms/events/media/receipts, DM pairs |
| `config.scala` | 112 | the accounts, loaded once at boot from `$WATA_USERS`; `serverName` |
| `membership.scala` | 86 | the room-membership state machine (join/invite/leave/ban transitions) |
| `power.scala` | 80 | the `m.room.power_levels` authorization table |
| `jsonnav.scala` | 203 | JSON object/field helpers over the `json` module's `Json` type |
| `store.scala` | 1061 | the single in-memory store: one `Mutex[StoreState]`, all reads/writes, the DM pair map, long-poll waiter bookkeeping, media reclaim |
| `persist.scala` | 315 | append-only JSONL journal + boot-time replay + old-journal media migration |
| `mediafiles.scala` | 89 | the file-backed media blob store under `<dataDir>/media/` |
| `retain.scala` | 120 | the media retention sweep (boot + daily) |
| `handlers.scala` | 344 | routing table entry, auth middleware, login/logout/whoami/profile/account-data handlers |
| `keys.scala` | 83 | the E2EE device-key routes, as no-op stubs |
| `dm.scala` | 308 | canonical DMs: the dialect endpoint, the `net.wata.dm` identity, the boot migration, the `m.direct` compat projection |
| `rooms.scala` | 721 | createRoom/join/invite/leave/kick/ban/state/send/redact/receipt/upload/messages handlers |
| `sync.scala` | 509 | `/sync` (initial + incremental + leave) and the long-poll wait |
| `testhooks.scala` | 65 | fail-on-demand for the media edge; registered only under `WATA_TEST_HOOKS=1` |
| `server.scala` | 425 | HTTP boot, mux registration, request edge, `SelfCheck` |
| `iolimit.scala` | 11 | app-owned facade: `io.LimitReader` (the request-body cap) |
| `subtle.scala` | 12 | app-owned facade: `crypto/subtle` constant-time compare |
| `osfile.scala` | 24 | app-owned facade: `os.WriteFile`/`MkdirAll`/`Remove` for the blob store |

## Scope

Implemented: password login (`m.login.password`) against the configured users
(`config.scala`), device/access-token
sessions, profile (displayname/avatar_url), global and per-room account data,
room creation with the common presets, join (by id or alias), invite, leave,
kick, ban, setting arbitrary state events, sending and redacting `m.room.*`
events, read receipts, media upload/download, and `/sync` including
long-polling, plus no-op E2EE key stubs. Deliberately absent: any
encryption (no `m.room.encrypted` handling; the `/keys/*` routes store
nothing and hand no key to anyone — they exist only so a stock client
completes its post-login device-key handshake and reaches `/sync`),
federation, user
registration (accounts are provisioned, not self-served — see "Accounts"),
`/publicRooms`, and `createRoom`'s
`initial_state`/`creation_content`/`power_level_content_override` fields
(`rooms.scala:13-18`).

## Accounts

wata provisions accounts; nobody registers one. That is the trust model — the
network is the boundary, and who is on it is decided out of band — so the
accounts are configuration, read once at boot from the JSON file named by
`WATA_USERS`:

```
WATA_USERS=/etc/wata/users.json wata-server :8008
```

```json
[ {"user": "alice", "password": "…", "displayname": "Alice"},
  {"user": "bob",   "password": "…", "displayname": "Bob"} ]
```

`displayname` is optional and defaults to the localpart; an entry with no
`user` is skipped. An unset, unreadable, unparseable, or empty `WATA_USERS`
falls back to a built-in alice/bob pair with password `testpass123`, which is
what every harness and script in this repo logs in as, so they run unchanged
with no file present. A bad file falls back rather than refusing to boot: a
homeserver on a device has to come up on its own.

`Config.load` is called from `Store.init`, so the server and `SelfCheck` see
the same accounts; `Store.init` then seeds each user's default profile. The
loaded list sits behind its own small `Mutex` (separate from the store's cell,
like the journal's) because logins read it from per-request goroutines; it is
written exactly once, before serving.

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
only place a real `throws sgo.GoError` is caught in the server), through an
`io.LimitReader` capped at `MediaEdge.maxBodyBytes + 1` (8 MiB + 1;
`go.iolimit`, iolimit.scala, is the app-owned facade for `io.LimitReader`) —
so one request can hand the server at most that much memory. A read that
fills past `maxBodyBytes` means the body was over the cap and gets `413
M_TOO_LARGE` (`MediaEdge.dispatchSized`); the cap leaves orders-of-magnitude
headroom over the largest legitimate payload, the device voice-message Ogg
uploads (~120 KB/min at 16 kbps opus). Under-cap bodies hand off
to `MediaEdge.dispatch` (`server.scala:27`), which special-cases media
*download* — the only endpoint that writes raw, non-JSON bytes with a stored
`Content-Type` (`MediaEdge.download`, `server.scala:36`) — and routes
everything else, including media *upload*, through the JSON pipeline
(`MediaEdge.jsonReply`, `server.scala:32`).

The JSON pipeline calls `Router.route` (`handlers.scala`), which re-derives
the handler from `r.URL.Path` — the facade's `Request` does not expose the
mux's matched pattern, so the path is re-parsed. The dispatch is a flat
if/else chain and EXACT: literal paths compare whole, and each parameterized
pattern has a predicate matching on segment count plus its literal segments
(`Router.seg`/`segCount`), mirroring `registerRoutes` one for one — never a
raw substring, which a parameter value could collide with. The download edge
(`MediaEdge.isDownload`, server.scala) matches its three registered patterns
the same way. `route` dispatches to per-area objects: `Router` itself for auth/profile/account-data,
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

Secret comparisons are constant-time: `Store.ctEq` wraps
`crypto/subtle.ConstantTimeCompare` through the app-owned `go.subtle`
facade (`subtle.scala`, same mechanism as `go.iolimit`), and both the login
password check (`Router.loginCheck`) and the token resolution go through
it. `Store.deviceByToken` deliberately does NOT `HashMap.get` the guess —
a hash lookup's early exit is its own timing channel — but folds over
every stored token comparing each in constant time, then resolves the
matched stored key; work per request is constant in the guess and linear
in the (family-sized) device count.

Response envelope: every JSON response goes through `Respond.finish`
(`server.scala:54`), which sets `Content-Type: application/json` and CORS
headers, then writes the status and body. The advertised origin is
`Respond.corsOrigin()`: `*` by default — deliberately wide open, because
the trust boundary is the family network and the devices plus local dev
tooling call from arbitrary origins — or, when `WATA_CORS_ORIGIN` is set,
that exact origin echoed instead, so browsers on any other origin fail
their CORS check; the env var is read per response (one getenv, like
`TestHooks.enabled`), so there is no boot-order dependency. A body
write that fails (`Respond.writeBody` — usually the client hanging up
mid-response) is logged as one `wata: response write failed: …` line and
dropped; the connection is already gone, but the line keeps partial-response
bugs visible in server logs. `NotFound`
(`server.scala:46`) answers unmatched paths with a Matrix 404 envelope, and
answers `OPTIONS` (CORS preflight) with 204 — this is the *only* place CORS
preflight is handled, and it only works because `"/"` catches every otherwise
unmatched pattern including `OPTIONS` requests to routes that exist under GET
or PUT.

## Data model and store

All server state lives in one process-wide guarded cell:
`Store.cell: Mutex[StoreState]` (`store.scala:63`). `StoreState`
(`store.scala:34`) is a plain mutable-field class — 14 `var`s — covering
devices/tokens (two HashMaps, one for lookup by id and one by token),
profiles, a flat `List[AcctData]` for all account data (global and per-room),
rooms (`HashMap[String, Room]`), aliases, media, a flat `List[Receipt]`, a
`HashMap` for per-device transaction idempotency, the ordered list of room
ids (newest-first), a flat `List[DmPair]` of canonical DM claims, a global
monotonic `seq` counter, and a flat
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

## Canonical DMs

Matrix has no first-class DMs: `m.direct` is client-written account data,
`is_direct` is a hint on an invite, and nothing enforces uniqueness. wata does
not live with that. **The server owns DM identity**: at most one room per
unordered pair of users, and the pair *is* the key (`dm.scala`; the reasoning
is `docs/plans/0007-canonical-dms.md`).

**The pair map.** `StoreState.dmPairs` is a flat `List[DmPair]` — the pair
stored sorted (`a < b`, via `Store.strLess`), so the two orderings are one
entry — alongside the acct and receipt slices. It is a list rather than a map
because it is also walked *per user* (`Store.dmPeersOf`), and is family-sized
either way.

**Get-or-create is ONE transaction.** `Store.dmGetOrCreate` does the lookup,
the room mint, the alias registration, every seed state event (via
`addEventLocked`), and the pair claim inside a *single* `withLock`. That is
what makes concurrent first sends from the two sides converge: there is no
window in which two callers both find the pair unclaimed. The events are built
as pure `StateSeed` values before the lock is taken; the after-effects (the
compat `m.direct` write, the two long-poll wakes) run after it, and only for
the call that actually created the room.

**Identity lives in room state.** Every canonical DM carries a `net.wata.dm`
state event, `{"members": [a, b]}` sorted, and the canonical alias
`#dm.<a>.<b>:server` with sorted localparts. A client classifies a room the
moment its state arrives — no account data, no ordering race, no inference.
There is deliberately no custom `m.room.create` `type`: Element hides rooms of
an unknown type, which would kill the degraded stock-client mode.

**The endpoint.** `POST /_wata/v1/dm/{userId}` → `{"room_id": …}`, auth
required. Idempotent get-or-create of *the* room for (caller, userId): the
server joins the caller, invites the peer, and stamps the identity. Repeat
calls, and calls from the two sides, all return the same room. `{userId}` may
be a bare localpart or a full MXID; one that names nobody with an account here
— unknown, or another server, since there is no federation — is `404
M_NOT_FOUND`, and a self-DM is `403 M_FORBIDDEN`. A caller holding only an
invite (the peer's side of a room the other minted) is *joined* by the answer,
so a first send needs no separate join round-trip; a ban is the one membership
this does not walk over.

**Power levels are symmetric.** A DM's `m.room.power_levels` puts both members
at 100 (the `trusted_private_chat` shape): either side may resolve the room
first, so an asymmetric owner would be arbitrary. The consequence worth
knowing is that neither side can kick or ban the other out of their own DM —
`Power.canKick`/`canBan` require the actor to strictly outrank the target.

**The compat projection** is one-way and disposable. Stock clients know only
`m.direct`, so the server derives it. `Dm.assertDirect` writes it for both
users when a pair is claimed — a real account-data write, so the change rides
the ordinary incremental `/sync` delta — and `Dm.project` re-asserts the
server's pairs *on top of* whatever the client last wrote, at emission time,
for both the initial and the incremental account-data block. Client writes to
`m.direct` are accepted and stored but never load-bearing: a client that
overwrites the entry is corrected on its next sync. `is_direct` is likewise
derived rather than echoed — it now rides the join as well as the invite
(`Rooms.joinPerform`, `Rooms.inviteContentFor`), keyed off the room's stamp,
which is what fixed the old asymmetry where only one side of a DM carried the
flag. Deleting this whole projection later touches nothing in the core.

**Stock-client creation stays safe.** A DM-shaped `createRoom` — `is_direct`
with exactly one invitee who has an account here — is answered with the
canonical room for that pair, minting it on first ask
(`Rooms.createMaybeDm`). So even a client that never heard of the dialect
endpoint cannot produce a duplicate DM. Any other shape takes the ordinary
`createPlain` path.

**Persistence and migration.** The claim is journalled as a `dmpair` op, so DM
identity survives a restart with no re-derivation. Boot then runs
`Dm.migrate()` over the rooms already in the store (after replay), oldest room
first; a claim never overwrites, so the oldest room wins a contested pair and
the losers stay joined but unmapped — nothing deletes a room. A room qualifies
if it carries the `net.wata.dm` stamp, or is in the legacy shape a stock
client leaves behind: exactly two join/invite members with `is_direct` on a
member event. Creation order is read off `roomIds` (newest-first, reversed),
so no create-timestamp tracking was needed.

**Conformance.** `just conformance` — the original TypeScript wata's jest
suites run against this binary — is **84/84 green** with all of the above in
place, including the suites that exercise DM creation, DM reuse, and "bob
should recognize DM room after joining (m.direct sync)". The compat
projection is what holds them up; no suite had to be recorded as exercising
the retired client-authored mechanism.

**Media** (`MediaItem`, model.scala) is file-backed whenever persistence is
on: an upload writes the blob to `<dataDir>/media/<mediaId>` (`MediaFiles`,
mediafiles.scala — the data dir is `$WATA_DATA`, defaulting to the `$WATA_LOG`
journal file's directory) *before* the store/journal transaction, so a crash
between the two leaves an orphan file, never a journal ref to a missing blob.
The in-memory `HashMap` then holds metadata only (`data` = "") and
`Store.getMedia` reads the file on demand (voice blobs are ~15 KB; no cache
until profiling says so). Media bytes travel as a byte-preserving `String`
(Go's `string([]byte)` round-trips arbitrary bytes including invalid UTF-8)
rather than a byte slice, to keep the case class free of an opaque Go type.
Stateless runs (no `WATA_LOG`/`WATA_DATA`) keep the bytes in the `HashMap` as
before. **Redaction reclaims the blob**: `Store.redactRoom` keys on the
target event's `url` field and, when it names a stored media id, drops the
metadata entry and deletes the file (`reclaimMedia` — the event is the only
referrer; media ids are not shared). The reclaim runs on live redactions and
on replayed ones alike, so a reboot converges.

**Media retention** (`Retain`, retain.scala): wata is ephemeral by design — a
walkie-talkie, not an archive (plan 0012's product ruling). Voice media older
than `WATA_MEDIA_RETAIN_DAYS` (default 7; `0` or negative disables) is swept:
the referring message is redacted *server-side* through the same store
sequence a manual redaction takes (an `m.room.redaction` event + the target
redact, both journaled — so a replay converges and clients render the
ordinary message-removed row), and the redact reclaims the blob as above.
Candidates are `m.room.message` events whose `url` names a *stored* media id:
text messages are untouched, already-redacted events have empty content and
fall through, and unreferenced blobs (e.g. a migrated orphan) are never
swept. Age is judged by the event's `origin_server_ts`, which survives replay
verbatim (a blob file's mtime would reset on migration). The sweep runs once
at boot, before listening, and then daily on a spawned goroutine. The
exempt-set seam (`Retain.exemptEventIds`, empty today) is where a future
"favorite a message" marker slots in to keep a message.

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
`room`, `event`, `redact`, `alias`, `media` (metadata only —
`{media_id, content_type, size}`; the bytes live in the blob file, written
before the op — see "Media" above), `receipt`, `txn`, `dmpair` (a canonical
DM's pair -> room claim). Long-poll waiters are
explicitly *not* logged (they're in-flight goroutines, transient by nature).

Replaying a `media` op probes the blob file rather than decoding a payload; a
missing file logs one line and skips the metadata insert, so fetches of that
id 404 — degraded, not fatal. A journal written before the file-backed store
carries the bytes base64url-encoded in a `data` field; replay *migrates* such
an op by writing the blob out to the media dir (`Journal.migrateMedia`). The
write truncates and the journal is not compacted (out of scope in plan 0012),
so the migration re-runs idempotently on every boot until a compaction
mechanism exists.

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

Every mutation added since — the generic state PUT, and the `m.room.member`
writes behind leave/kick/ban — is an ordinary `Store.addEvent`, so it is
already carried by the `event` op with no new record kind. `tools/wata-persist-smoke.sh`
pins that: it sets a topic through `PUT /state/...` and bans a user before the
`kill -9`, and asserts after the reboot that the topic is in the room's state
and that the banned user still cannot join. It also resolves a canonical DM
before the kill and asserts afterwards that re-resolving the pair — from
either side, and through a DM-shaped `createRoom` — returns the *same* room
rather than minting a second one, and that its alias still resolves.

The same script pins the media file-store behaviors: an uploaded blob serves
back byte-identical from its blob file after the reboot while the journal's
`media` ops stay payload-free; a live redaction of a media message deletes
the blob (and stays deleted across the replay); a crafted old-style base64
`media` op is migrated out to the media dir on boot and serves; and a crafted
slim op with no blob file replays as a logged skip whose id then 404s. For
the retention sweep it ages a journaled voice message's `origin_server_ts` by
two days, reboots with `WATA_MEDIA_RETAIN_DAYS=1`, and asserts the blob is
gone, the event reads as redacted, an unreferenced blob was spared — and that
one more reboot at default retention replays to the same state.

## Test hooks

`WATA_TEST_HOOKS=1` (env, checked both at route registration and at dispatch)
adds ONE route the production server does not have:

```
POST /_wata/v1/test/fail   {"count": N}   -> {}
```

It arms a shared counter (`TestHooks`, testhooks.scala): the next `N` media
operations — upload (`Rooms.upload1`) or download (`MediaEdge.download2`),
whichever arrive first — answer `500 M_UNKNOWN` instead of running. The
counter disarms itself as it is consumed; re-arming replaces the count, and
`{"count": 0}` disarms explicitly. This exists for the UI golden suite's
`send-play-failed` scenario (the `SEND FAILED`/`PLAY FAILED` flashes need a
server that fails on demand); the endpoint is unauthenticated because it only
ever lives inside a harness's private, per-scenario server.

Without the env var the route is not registered and its dispatch predicate is
off, so the path falls through to the Matrix 404 catch-all like any unknown
path — the production surface is unchanged. `tools/fb-ui-tests.py` asserts
that gate on every run: each scenario's server is probed with a
`POST /_wata/v1/test/fail` right after readiness and must answer 404 (200
for the one scenario that opts into hooks).

## File-by-file map

- **`model.scala`** — every domain ADT: `ErrCode`/`MErr` (errors as values), `Auth`, `UserCfg`, `Device`, `Profile`, `AcctData`, `Event`, `Room`, `MediaItem`, `Receipt`, `Waiter`, and the canonical-DM values (`DmPair`, `DmPeer`, `DmRoom`, `StateSeed`).
- **`config.scala`** — `Config`: the accounts, loaded once at boot from `WATA_USERS` (built-in alice/bob otherwise), and `serverName`.
- **`membership.scala`** — the membership sealed types and the join/invite/leave/ban transition table; every row is reachable from an HTTP route.
- **`jsonnav.scala`** — `JsonNav`: field lookup/typed accessors on `Json`, object/array builder helpers (`obj1`..`obj4`, `arr1`, `endObj`), `errEnvelope`, `eventToJson`, and the account-data profile-merge helper.
- **`power.scala`** — `Power`: the `m.room.power_levels` authorization table (send/state/redact/invite/kick/ban).
- **`store.scala`** — `StoreState` + `Store`: every store mutation and read, ID generation, the long-poll waiter lifecycle, and the boot-replay entry points (`replay*`) that `persist.scala` calls into.
- **`persist.scala`** — `Journal`: the JSONL op log, its own mutex, boot replay, per-op-kind (de)serialization, and the old-journal media migration.
- **`mediafiles.scala`** — `MediaFiles`: the file-backed blob store (`<dataDir>/media/<mediaId>`): dir resolution from `$WATA_DATA`/`$WATA_LOG`, write/load/exists/delete.
- **`osfile.scala`** — `go.osfile`: the app-owned `os` facade (`WriteFile`/`MkdirAll`/`Remove`) the blob store needs; perms passed as literals, errors dropped (best-effort).
- **`retain.scala`** — `Retain`: the media retention sweep — `WATA_MEDIA_RETAIN_DAYS`, the boot + daily passes, the server-side redaction of expired voice messages, and the favorites exempt-set seam.
- **`handlers.scala`** — `Router`: the top-level route dispatch, `requireAuth`, `/versions`, login/logout/whoami, profile, and account-data handlers.
- **`keys.scala`** — `Keys`: the three E2EE device-key routes as authenticated no-op stubs; `/keys/upload` tallies the one-time-key counts back per algorithm, which matrix-dart-sdk requires, and discards the keys.
- **`rooms.scala`** — `Rooms`: createRoom, join, invite, leave/kick/ban, the generic state PUT, send/redact events, receipts, media upload, and `GET /messages` pagination.
- **`dm.scala`** — `Dm`: canonical DMs. The `POST /_wata/v1/dm/{userId}` endpoint, the `net.wata.dm`/alias identity, the boot migration, and the one-way `m.direct` compat projection.
- **`sync.scala`** — `Sync`: the pure sync-parts builder (initial + incremental, account data through `Dm.project`) and the long-poll orchestration.
- **`testhooks.scala`** — `TestHooks`: the `WATA_TEST_HOOKS=1`-only fail-on-demand counter and its `POST /_wata/v1/test/fail` route (see "Test hooks").
- **`server.scala`** — `WataHandler`/`MediaEdge`/`NotFound`/`Respond` (the HTTP edge), `Server` (boot + route table), `Main`, and `SelfCheck` (a deterministic smoke test of the store/handler logic, diffed against a golden file by the build's smoke script).

## Known gaps / debt

Beyond what `WATA-TODO.md` already tracks (unexercised `/publicRooms`,
the unscheduled `/sync` allocation cost, and the
explicitly-deferred actor-store refactor), reading the code surfaced the following. Items with a `[KEY]` tag have a
line in `TODO.jsonl`; grep the key here for the body.

- **Every list-shaped store slice (`acct`, `receiptList`, `roomIds`,
  `waiters`, room `state`/`timeline`) is scanned linearly on every read and
  write** (`store.scala`, throughout — e.g. `findAcct`, `filterReceipts`,
  `lookupState`). Documented as an accepted tradeoff for small/toy room and
  user counts, but worth naming as the load-bearing scalability ceiling.
