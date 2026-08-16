# wata-server — architecture

`wata-server` is a single-binary Matrix homeserver implementing a slice of the
Client-Server API, written in Sgola (a restricted Scala 3 dialect that
compiles to Go source — see the repo root for the toolchain) and built as an
`app` module over the `core` and `json` Sgola libraries (`wata-server/sgo.build`,
`wata-server/go.mod`). There is no JVM at runtime: `sgo build` emits Go, which
is compiled and run like any other Go program.

The source lives entirely in `wata-server/src/main/scala/` — 27 files, ~6000
lines:

| file | lines | role |
|---|---|---|
| `model.scala` | 138 | domain ADTs: errors, auth, users/devices, rooms/events/media/receipts, DM pairs |
| `config.scala` | 334 | the accounts: read at boot from `$WATA_USERS`, hashed at rest, rewritten by admin mutations, first-run setup mode; `serverName` |
| `membership.scala` | 86 | the room-membership state machine (join/invite/leave/ban transitions) |
| `power.scala` | 80 | the `m.room.power_levels` authorization table |
| `jsonnav.scala` | 204 | JSON object/field helpers over the `json` module's `Json` type |
| `store.scala` | 1134 | the single in-memory store: one `Mutex[StoreState]`, all reads/writes, the DM pair map, long-poll waiter bookkeeping, media reclaim |
| `persist.scala` | 319 | append-only JSONL journal + boot-time replay + old-journal media migration |
| `mediafiles.scala` | 89 | the file-backed media blob store under `<dataDir>/media/` |
| `retain.scala` | 130 | the media retention sweep (boot + daily), favorites exempted |
| `favorite.scala` | 100 | the favorite toggle endpoint + the `net.wata.favorite` marker |
| `handlers.scala` | 359 | routing table entry, auth middleware, login/logout/whoami/profile/account-data handlers |
| `keys.scala` | 83 | the E2EE device-key routes, as no-op stubs |
| `dm.scala` | 308 | canonical DMs: the dialect endpoint, the `net.wata.dm` identity, the boot migration, the `m.direct` compat projection |
| `family.scala` | 137 | the canonical family room: boot/provisioning ensure, the `net.wata.family` stamp, server-side joins (shared with groups) |
| `group.scala` | 110 | groups: the `POST /_wata/v1/group` get-or-extend endpoint and the `net.wata.group` stamp |
| `rooms.scala` | 721 | createRoom/join/invite/leave/kick/ban/state/send/redact/receipt/upload/messages handlers |
| `sync.scala` | 510 | `/sync` (initial + incremental + leave) and the long-poll wait |
| `testhooks.scala` | 65 | fail-on-demand for the media edge; registered only under `WATA_TEST_HOOKS=1` |
| `server.scala` | 517 | HTTP boot, mux registration, request edge, the `/admin` page edge, `SelfCheck` |
| `iolimit.scala` | 11 | app-owned facade: `io.LimitReader` (the request-body cap) |
| `subtle.scala` | 12 | app-owned facade: `crypto/subtle` constant-time compare |
| `pwhash.scala` | 222 | PBKDF2-HMAC-SHA256: the derivation, the stored-hash format, verification |
| `adminapi.scala` | 321 | the admin surface: the admin gate, the ungated first-run `mode`/`setup` pair, `/_wata/v1/admin/…` status + accounts CRUD + enrolment routing |
| `enroll.scala` | 296 | device enrolment: the unauthenticated announce, the bounded pending set, approve (allowlist file + live listener) / deny |
| `devicecmd.scala` | 290 | the device-command mailbox: per-user queues + latest-wins reports behind their own mutex, the four routes, the long-poll wait — in-memory only, never journaled |
| `osfile.scala` | 44 | app-owned facade: `os.WriteFile`/`MkdirAll`/`Remove`/`Rename`/`Stat`, plus `io/fs.FileInfo` |
| `gocrypto.scala` | 54 | app-owned facades: `hash.Hash`, `crypto/sha256`, `crypto/hmac`, base64 `StdEncoding` |
| `webembed.scala` | 16 | app-owned `@go.bind` facade for `wata-server/adminui` (the `go:embed`ed admin page) |
| `irohnet.scala` | 32 | app-owned `@go.bind` facade for `go-pkgs/irohnet` (the embedded iroh transport: `Serve`, `Allow`) |

## Serving transports

The mux serves over one of two transports, selected at boot
(`Server.serve`): plain TCP HTTP (`:8008` by default — every harness, and
the default), or, when `WATA_IROH_CONFIG=<json>` is set, an embedded iroh
listener (`go.irohnet.serve`, plan 0013) — the server IS the iroh endpoint,
and the node-id allowlist is enforced at accept inside `go-pkgs/irohnet`.
The handler surface is identical in both modes.

**iroh mode serves BOTH listeners** (plan 0021): alongside the iroh endpoint,
the same mux is served over plain TCP at `$WATA_LISTEN` (defaulting to the
positional listen argument, i.e. `:8008`), on a spawned goroutine, while the
iroh listener keeps the main goroutine so a terminal iroh error still stops
the process. The reason is the admin interface: no browser can dial iroh, so
`/admin` would be unreachable in the transport the family server actually
runs. The TCP listener sits inside exactly the trust boundary the default
mode already relies on — the LAN. The real
iroh transport is compiled in only with the `iroh` Go build tag on the wired
targets — darwin (`just tunnel-smoke` builds it) and linux/arm, the device
cross-build (`just iroh-lan-smoke`); every other build links the package's
loud-error stub, so ordinary builds need no cargo.

**The trusted node-id header** (plan 0027). Requests that arrive over the
iroh listener reach the handler with `X-Wata-Node-Id` set to the
connection's **authenticated** remote node id — the id the accept gate
verified before surfacing any stream. The iroh bridge
(`go-pkgs/irohnet/nodeid.go` + `Serve`) deletes any inbound copy of the
header before injecting, and **every TCP listener serves through the
matching strip** (`irohnet.StripNodeID`, wrapped in `Server.serveTcp` — the
strip half is untagged Go, so it is real in stub builds too). A handler that
sees the header may therefore treat it as proof of key possession; a
request without it arrived where peer identity cannot be proven.
Device-login authenticates on exactly this, and the command mailbox's device
side accepts it as one of its two credentials (see "The device-command
mailbox").

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
accounts are configuration, read at boot from the JSON file named by
`WATA_USERS`:

```
WATA_USERS=/etc/wata/users.json wata-server :8008
```

```json
[ {"user": "alice", "hash": "pbkdf2-sha256$600000$…$…", "displayname": "Alice", "admin": true},
  {"user": "bob",   "hash": "pbkdf2-sha256$600000$…$…", "displayname": "Bob",   "admin": false} ]
```

`displayname` is optional and defaults to the localpart; `admin` defaults to
false and is what gates the `/_wata/v1/admin/` surface (see "The admin
surface"); an entry with no `user` is skipped.

A **username** is the login identity and never changes — it is the mxid's
localpart, and everything that names a user on the wire uses it. A **display
name** is what people read, is set by an admin, and can change at any time;
the two are shown as separate columns on the admin page and are never
conflated. A client with no display name for a member falls back to the
localpart, never the raw mxid (see wataclient.md).

Two boots have no usable accounts, and they are not the same boot:

- **`WATA_USERS` unset** — harness mode. The built-in alice/bob pair with
  password `testpass123` applies (**alice carries the admin flag**), which is
  what every harness and script in this repo logs in as, so they run unchanged
  with no file present.
- **`WATA_USERS` set, but the file is missing, unreadable, unparseable, or
  holds zero accounts** — a fresh install, which boots into **setup mode**
  (below). A real deployment always sets the variable, so a real deployment
  never has a baked-in credential.

Either way the server boots rather than refusing to: a homeserver on a device
has to come up on its own and say what it needs.

### First-run setup

A server in setup mode has no accounts at all. `GET /admin` serves a
create-the-first-account screen instead of the login form, and exactly two
routes answer:

| route | what it does |
|---|---|
| `GET /_wata/v1/admin/mode` | `{"setup": …}`, a single boolean — unauthenticated, the whole surface the page needs to pick its screen. Always answers, in either mode |
| `POST /_wata/v1/admin/setup` | `{user, password, displayname}` — unauthenticated, and valid ONLY in setup mode: creates that account with the admin flag, writes `users.json` (hashed, atomic, 0600), and ends setup mode |

Every *other* `/_wata/v1/admin/…` route answers `503 M_NOT_READY` while setup
is open — nothing half-works without an account. Ordinary client routes are
left exactly as they are: with zero accounts nobody can log in anyway, so
there is nothing to gate.

**The window closes with the first write, and it is one transaction.**
`Config.claimSetup` takes the config lock and, under it, checks the setup
flag, clears it, seats the new account, and writes the file; a second claim
finds the flag already clear and is refused. So two browsers racing on the
family LAN produce exactly one account — first comer claims the install,
router-style — and the loser gets `409` (as does anyone who tries later). The
PBKDF2 hash is minted *before* the lock, since it is deliberately slow; a
loser has only wasted that work. Nothing is written before a claim, so an
unparseable hand-written file is never overwritten by the setup path.

`tools/server-service.py` installs no accounts file, so a fresh install boots
into setup mode and the first thing an owner does is browse `/admin`;
`server-selftest` runs that round trip (setup mode reported, other routes 503,
the claim, a second claim 409, the file hashed and 0600, login, restart, the
account still there).

`Config.load` is called from `Store.init`, so the server and `SelfCheck` see
the same accounts; `Store.init` then seeds each user's default profile. The
loaded list sits behind its own small `Mutex` (separate from the store's cell,
like the journal's) because logins and admin requests read it from per-request
goroutines.

**No plaintext at rest.** A password is stored as
`pbkdf2-sha256$<iterations>$<saltB64>$<dkB64>` — PBKDF2-HMAC-SHA256, standard
base64, a 16-byte random salt, a 32-byte derived key, 600000 iterations
(`Pwhash.defaultIterations`, the one place the cost is written).
`pwhash.scala` implements the RFC 2898 §5.2 derivation directly over Go's
`crypto/hmac` + `crypto/sha256` (the `go.hmac`/`go.sha256`/`go.hashpkg`
facades in `gocrypto.scala`), so there is no external dependency; the loop is
oracled by `SelfCheck` against the published PBKDF2-HMAC-SHA256 test vectors
(including a dkLen-40 vector, the only one that exercises the multi-block
`T1 || T2` path) and byte-compared by `tools/wata-smoke.sh`. Verification
derives with the *stored* hash's own parameters and compares constant-time
through `Store.ctEq`, so raising the iteration count later re-hashes lazily on
the next password set — the number rides the string, and no migration exists.
600000 iterations cost ~70 ms of HMAC on a dev Mac, which is why the built-in
fallback pair is hashed at boot like anything else rather than getting a
cheaper path.

A hand-written plaintext `"password"` field is still accepted as **input** —
provisioning by hand means typing a password once — but it is hashed as the
file is read and the file is rewritten, so plaintext never survives the first
boot. An entry carrying both fields keeps its `hash`. An entry with neither is
an account nothing can log in as, which is the deliberate outcome for a
malformed line rather than an open door. A file that did not *parse* is left
untouched (the boot fell back to the built-in pair): rewriting it would
destroy whatever the human meant to write (that boot is in setup mode, and
setup writes nothing until someone claims it).

**The server owns the file.** Admin mutations (below) reseat the in-memory
list and rewrite `users.json` in the same `Config` transaction, so a created
account can log in with no restart while the file stays the source of truth
for the next boot. The write is atomic — a temp file in the *same* directory,
mode 0600, then `os.Rename` over the target — so a reader (the next boot, or a
human) sees either the old file or the new one, never a partial write. The
rendering is one JSON object per line, so the file stays something a human
opens and edits. Accounts remain config, not journal: nothing about them is
written to the event log.

## The admin surface

`adminapi.scala` (plan 0021). Every route under `/_wata/v1/admin/` requires an
authenticated session whose *account* carries `"admin": true`: no token is
`401`, a non-admin token is `403 M_FORBIDDEN`. There is no second credential —
login is the ordinary password login, so the browser page and a handset use
the same endpoint. The gate lives in one predicate (`Router.isAdminPath`
routes the whole prefix to `Admin.route`, which authenticates before it
dispatches), so a new admin route cannot be added ungated by accident.

| route | what it does |
|---|---|
| `GET /_wata/v1/admin/mode` | ungated: is this server still in first-run setup (see "First-run setup") |
| `POST /_wata/v1/admin/setup` | ungated, setup mode only: create the first admin account and close the window; `409` once any account exists |
| `GET /_wata/v1/admin/status` | version, uptime, transport, the accounts file and journal paths + journal size, retention setting, room count, media count/bytes, and a per-account row: display name, admin flag, live device count, and how long ago that user last synced |
| `GET /_wata/v1/admin/users` | the account rows on their own |
| `POST /_wata/v1/admin/users` | create `{user, password, displayname, admin}` — `400 M_USER_IN_USE` if taken, `400 M_BAD_JSON` for an invalid localpart or a missing password |
| `POST /_wata/v1/admin/users/{user}/password` | reset a password |
| `POST /_wata/v1/admin/users/{user}/displayname` | rename |
| `POST /_wata/v1/admin/users/{user}/admin` | grant/revoke the flag |
| `DELETE /_wata/v1/admin/users/{user}` | remove the account |
| `GET /_wata/v1/admin/enroll` | the devices waiting to be approved, the ids already allowlisted, the account roster the approve picker offers, and the node-id→account bindings (see "Device enrolment") |
| `POST /_wata/v1/admin/enroll/{nodeId}/approve` | allowlist that node id; an optional body `{"user": lp}` binds an account — creating a passwordless one when the name is new (see "Account provisioning") |
| `POST /_wata/v1/admin/enroll/{nodeId}/deny` | drop the pending row |

Two of them are more than a file edit. A **rename** also runs the store's
profile fan-out (`Store.setDisplayName`), which rewrites the user's
`m.room.member` event in every room they have JOINED — an invite keeps the
name it was sent with, as in Matrix at large — so a client that is syncing
sees the new name on its next sync round, with no restart. The integ
scenario `admin-rename` (wataclient.md) is that property's live oracle; a **create** seeds the new account's profile the same way
a boot seeds a configured one. A **removal** revokes the account's live
sessions in one store transaction (`Store.dropUserDevices`): its devices and
tokens are dropped and its long-poll waiters are woken inside the same block,
so the removed user's token is dead on its very next request rather than at
its next login. Removing the *calling* account is refused (`403`) — an admin
cannot lock themselves out mid-session. The admin API never touches rooms,
events, or media: a removed user's messages stay where they are, and
retention (`retain.scala`) remains the only thing that deletes content. It
also never hands a password hash to a client; the account rows carry no
`hash` field.

Last-sync ages come from one transient store slice (`StoreState.lastSync`,
stamped by the `/sync` handler) — never journaled, because it describes this
process's uptime rather than the account.

**The page.** `GET /admin` serves a hand-written, dependency-free HTML page:
a login form, the status panel, the account table, and add/rename/reset/remove
controls, all plain `fetch` against the routes above, with the access token
kept in `localStorage`. The bytes are compiled in — `wata-server/adminui/` is
a small plain-Go package whose `index.html` sits next to it and rides a
`go:embed`, reached through the `go.webembed` facade — because `go:embed`
cannot read outside its own package directory and a deployment should still
copy exactly one binary. The route itself is *unauthenticated* and
deliberately so: the page is inert markup carrying no data, and it is what a
browser must be able to load in order to reach the login form. It is served
off the HTML edge (`AdminPage.serve`, alongside the media-download special
case) rather than through the JSON pipeline.

`tools/wata-admin-smoke.py` (`just admin-smoke`, in `just ci`) is the gate for
all of this: the plaintext rewrite (no plaintext substring survives the first
boot), the 401/403 gate on every admin route, create → login with no restart,
reset, rename, remove → the token dead mid-session, the admin flag toggling on
a live token, every mutation landing in `users.json`, a reboot reading it back,
and `/admin` answering 200 `text/html`. `tools/tunnel-smoke.py` covers the
same page over the iroh mode's TCP listener.

## Device enrolment

`enroll.scala` (plan 0021 milestone B; the server half of plan 0014). In iroh
mode the listener's allowlist decides who is admitted — an unknown node id is
closed at accept with `401 not allowlisted`, before any stream. Enrolment is
how a new handset crosses that line without anyone editing a file over ssh.

```
POST /_wata/v1/enroll   {"nodeId": "<64 hex>", "nonce": "AB12"}   (no auth)
```

**The announce is unauthenticated, and inert.** A device outside the allowlist
has no way to obtain a token, so requiring one would be circular; what makes
that safe is that the announce grants nothing. It parks a `(nodeId, nonce)`
pair in memory for a parent to look at, and only `approve` — behind the admin
gate — turns a pending row into an allowlist entry.

The pending set is bounded on both axes, so the open endpoint cannot be turned
into a memory sink: entries expire (`WATA_ENROLL_EXPIRY_MS`, default 10
minutes, pruned as the list is read rather than on a timer), the list is capped
(`WATA_ENROLL_MAX`, default 8) with the **oldest** evicted, and a re-announce of
the same node id refreshes its row rather than adding one — so a flood rotates
within the cap instead of growing. Nothing is journaled; a restart drops the
set, which is the right outcome for a ten-minute approval window. A node id is
shape-checked at announce (64 lowercase hex, or the 52-character z-base-32
spelling of the same key) because a junk id that reached the allowlist file
would fail the *listener's* parse at the next boot — one bad announce would
otherwise be able to stop the server from listening at all.

**An empty allowlist is the bootstrap state, and it listens.** A fresh
install has approved nobody, so its config's `allowlist` is empty — and the
listener comes up anyway, refusing every peer with the ordinary loud
not-allowlisted refusal, because the only way out of that state is an
enrolment approval landing on a server that is already up. `["*"]` still
means admit any peer. The refuse-everything and refuse-this-node paths are
one path (the accept gate in `go-pkgs/irohnet/rust/src/lib.rs`), so the
client-side refusal cooldown and redial behavior are identical, and the first
approved node is admitted live with no restart. `tools/tunnel-smoke.py`'s
enrolment leg runs its server from exactly this state.

**Approval is durable first, live second.**

1. the node id is appended to the `allowlist` array of the iroh config
   (`WATA_IROH_CONFIG`, or `WATA_ENROLL_ALLOWLIST` when a deployment — or the
   admin smoke, which has no iroh transport — points enrolment at another
   file). The rewrite is a temp file in the same directory plus a rename, mode
   `0600` (the file also holds the node secret key), and it is **confirmed by
   re-reading the file**, since the `os` facade drops write errors. A write
   that cannot be confirmed answers `500` with the row left pending: a row that
   vanished while nothing was granted is the one outcome nobody can debug.
2. it is applied to the running listener through `irohnet.Allow` — one new FFI
   (`irohnet_server_allow`, `go-pkgs/irohnet/rust/src/lib.rs`). The Rust side
   keeps the allowlist in a shared `Gate` (an `Arc` holding the id set, the
   `*`-admits-all flag, and the live connections it admitted) that the accept
   loop consults per connection, so an id added after the listener was built
   takes effect on the next dial. Its counterpart `irohnet_server_disallow`
   drops an id **and** closes the connections it already holds.

A live apply that fails — a plain-TCP deployment, or a build without the
transport — still leaves a successful approval: the response carries
`"live": false` and the reason, and the file is what the next boot reads. Deny
removes the row and writes nothing; a denied device may announce again, which
is what makes deny the safe answer to "I don't recognize this".

**"Already enrolled" is an answer, not an absence.** An enrolled handset has
no pending row — and neither does one whose announce expired. The page could
not tell the two apart, so it reported the expiry over both, including
immediately after a *successful* approve: the fragment re-runs, finds the row
cleared, re-announces, gets no row back, and says "the announce may have
expired" over a device that had just been admitted. The server therefore marks
it, in the two places a page can be holding an id:

- the announce answers `{"node_id":…, "pending": false, "allowlisted": true}`
  and records **nothing** — a pending row asks for a decision that is made
  (an ordinary announce answers `"pending": true, "allowlisted": false`);
- `GET /_wata/v1/admin/enroll` carries `"allowlisted": [<id>, …]` — the ids the
  allowlist file holds — beside `"pending"`.

The file is the source of truth and `approve` writes it before it answers, so
the refresh that follows an approval sees the id there. A `"*"` entry admits
every peer and answers for any id. The page's copy states **outcomes, not
verbs** — a parent watching this page after a successful approve once read it
as an error, so every success line says what happened and what happens next:
the approve action reports "approved `<id>` — bound to `<user>`; the handset
will connect itself" (the not-applied-live case keeps its warning styling on
top of the same statement). The page reads the allowlisted marker in three
places, each naming the bound account from `bindings`: a scanned fragment
whose id is allowlisted says "already enrolled — bound to `<user>`; this
handset is done" (calm, not an error, and it never re-announces); the same
line covers the post-approve refresh, except when a warning is already up (an
approval that did not apply live outranks it); and the typed code, which
carries only a prefix, matches that prefix against the listed ids to answer
"that device is already enrolled — bound to `<user>`" instead of "no waiting
device matches".

**The QR contract.** The handset displays
`<adminUrl>/admin#enroll/<nodeId>/<nonce>`, so a stock camera app lands the
parent on the admin page with that row highlighted. The base URL defaults to
`http://wata.local:8008` — the Bonjour name the server install publishes (see
"Running as a service") — so a QR outlives the server's DHCP lease; the iroh
config's `adminUrl` / `WATA_ADMIN_URL` override it. The nonce is a
human-visible correlator — "this is the handset in my hand" — not a secret and
not a credential. The device half (first-boot keypair mint, the QR screen, the
typed code) is in `docs/design/wata-fb.md`.

**The page announces** (plan 0014's ruling; `adminui/index.html`). When the
fragment names a node with no pending row, the page POSTs the announce
**itself**, from the parent's authenticated session, and then highlights the
row it just created. That is what makes the QR work for a handset with no route
to this server at all — a cellular-only device, or one refused by the very
allowlist it is trying to join — which is the common case rather than the
exotic one: a device that has never been enrolled is by definition a device the
transport refuses. The id's provenance is a parent reading a physical screen,
which is a better provenance than an unauthenticated POST arriving from the
network. The page shape-checks the fragment first (the same 64-hex / 52-z32
forms the server accepts) so a mangled link never becomes an announce, and it
announces a given id at most once per page load so a failure cannot turn
refreshes into a POST loop.

The announce endpoint nevertheless stays open and unauthenticated: a
LAN-reachable handset still uses it, and it grants nothing either way.

**The typed code** (plan 0014 milestone 4) is the fallback for a screen too dim
or scratched to scan. The handset prints `<nonce>-<first 8 hex of its node id>`
under the QR; the admin page's "type the code" box matches that against the
pending rows it has already fetched and highlights the one it picks.

*It selects; it never creates.* Eight hex characters are not an identity, and
the reason that matters is the injection this design has to refuse: if a typed
prefix could be turned into an announce, the server would have to invent the
other 56 characters, and whatever it invented would be an allowlist entry no
device ever asked for. So no short form is accepted anywhere on the enrol
surface — `Enroll.validNodeId` rejects anything but a full key, and
`approve`/`deny` take only ids that are already pending. The typed path
therefore requires the device to have announced itself, i.e. **to be reachable
on the family network**; a cellular-only handset has the QR and only the QR.
That limitation is real and recorded in plan 0014's fallback section rather
than papered over.

### Account provisioning: device-login

Plan 0027; `bindings.scala`. Approving the transport is half an onboarding —
the handset still needs an account and a session, and the field test that
motivated the plan closed that gap with a `curl` and an ssh. Now the approval
closes it:

- **Approve binds an account.** `POST …/approve` takes an optional body
  `{"user": lp}`. An existing localpart binds as-is; a NEW valid name is the
  **inline create-and-bind** (the owner's ruling): a passwordless account —
  stored hash `""`, which `Pwhash.verify` accepts nothing against, so
  password login is impossible by construction — created, profile-seeded and
  family-joined like any admin-created account, then bound, one step. The
  binding `nodeId -> localpart` lives in `Bindings` (its own mutex), is
  journaled (`bind` op), and is written after the allowlist file held but
  before the live allow — a bound-but-unadmitted node cannot log in anyway,
  while an admitted-but-unbound one would race device-login. The approve
  response then carries `user` + `user_id`; the enroll listing carries
  `users` (the roster the page's picker offers) and `bindings` (so enrolled
  rows can name their account).

- **`POST /_wata/v1/device-login`** takes **no credentials**: the handler
  reads the trusted `X-Wata-Node-Id` header (see "Serving transports") and
  answers a fresh token + `user_id` + `device_id` for the bound account —
  the password login's exact response shape (`Router.loginOk`, so the minted
  device is journaled and revoked like any other). Where the header is
  absent — every TCP-path request, however decorated, since both edges strip
  inbound copies — it is **403 unconditionally**. An admitted node with no
  binding is 404 (the handset retries and gets in when a binding lands); a
  binding to a since-removed account is 403, with the binding left in place
  so re-creating the account restores the handset without a re-enrolment.

The trust argument is the plan's: the iroh handshake proves possession of
the device's secret key, and the admin approved that exact key while looking
at the physical handset — the connection *is* the authenticated channel, and
a token sent over it would add friction, not security. Passwords remain a
human-at-the-admin-page concern.

Revocation: removing the *account* revokes its sessions today (the admin
DELETE); a per-handset revoke endpoint (disallow + drop the binding +
invalidate tokens) is still the unclaimed half — the FFI exists
(`Listener.Disallow` closes live connections) but no route drives it.

### Gates

`tools/wata-admin-smoke.py` covers the announce, its validation, the
dedupe, the cap and the expiry, the 401/403 gate, the atomic file write and
its preservation of the rest of the config, the deny path, the endpoint
sequence the page performs for a fragment nobody announced (page JS cannot run
in the smoke, so the sequence is what is asserted), and the prefix negative —
a prefix is refused as an announce, as a 63-character near-miss, and as an
approve target, leaving no row behind. It also pins the already-enrolled
answer: the listing carries the allowlisted ids, a re-announce of one of them
answers the marker and leaves no row, and an id nobody approved still answers
pending. For provisioning it pins the TCP half — device-login 403 with no
header AND with a forged header naming a genuinely bound node — plus the
approve-side binding, the roster, the inline create's passwordlessness, and
the bindings' journal round-trip across a reboot.
`tools/tunnel-smoke.py` runs the loop end to end over real iroh — the node the
server just refused announces itself, an admin approves it **with an
inline-created account**, and the **same key** is accepted by the **same
server process** and by the **same client process**, which is left running
across the approval, redials its way in, and reaches an authenticated sync
as the bound account through device-login, no credential anywhere.

## The device-command mailbox

`devicecmd.scala` (plan 0020). Commanding a handset from the admin side —
wifi provisioning today, any future remote-admin op on the same seam — rides
a small dialect surface:

| route | who | what it does |
|---|---|---|
| `POST /_wata/v1/cmd/{userId}` | ADMIN | queue `{"op": …, …}` for that user's device; the whole body is the command, `op` mandatory, everything else rides along as the op's arguments |
| `GET /_wata/v1/cmd/poll?wait=<s>` | the device | take every command queued for the calling account, oldest first; with `wait` (capped at 60 s) an empty queue parks until a command lands or the timer fires |
| `POST /_wata/v1/cmd/report` | the device | store `{"op": …, "result": …}` as the latest report for (account, op), stamped with a monotonic `seq` |
| `GET /_wata/v1/cmd/{userId}/report?op=…` | ADMIN | the latest report — `{op, seq, result}` — or 404 while none |

**In-memory only, never journaled.** Commands are transient, and `wifi_join`
carries a PSK — the journal is append-only with no compaction, and a secret
that outlives its use in a file is a defect. A restart drops queues and
reports; the tui retries. (The PSK still crosses the wire: iroh is
encrypted, plain LAN HTTP is inside the trust boundary — recorded, not
solved, here.)

**Auth.** Queueing and report-reading require the ADMIN flag
(`Admin.requireAdmin` — pushing wifi credentials at a handset is an admin
act; the tui logs in as an admin account). The device side authenticates as
its own account by either of two credentials (`DeviceCmd.deviceUser`):

- the trusted `X-Wata-Node-Id` header — its presence proves the iroh accept
  gate verified the peer, and `Bindings.userFor` turns that transport proof
  into the account whose queue this is. The BINDING is required, not just
  the proof: the mailbox is addressed per account, so an
  admitted-but-unbound node is 403. Over TCP this path is unreachable by
  construction (both edges strip inbound copies of the header), which is
  the whole TCP-refusal property — pinned in the smoke as "a forged header
  without a token answers the ordinary 401".
- an ordinary bearer token. The device's poller runs inside a logged-in
  client session (device-login already exchanged the node id for a token),
  and the tui can play the device in a harness — both arrive with a token,
  over either transport, inside the same trust boundary every
  token-authenticated route already accepts.

**Delivery is take-once** — a poll clears the account's queue, there is no
ack; a command lost to a dying device surfaces as the admin's report poll
timing out, and the tui re-queues. **Reports are latest-wins per (user,
op)**; the `seq` stamp is what makes the admin's wait skew-free (read the
current seq, queue, poll until it moves — no cross-machine clock
comparison). The long-poll reuses the store's waiter discipline
(close-signalled channel, register-then-recheck) under the mailbox's own
mutex and waiter list, so a mailbox wake never touches `/sync` pollers.

The ops that ride it — `wifi_scan`, `wifi_join`, `wifi_off` (plans
0020/0031) — are the device's business, not the server's: the mailbox
validates only the envelope, and a new op needs no server change. The
device half is wata-fb's command poller (wata-fb.md), the admin half the
tui's `wifi`/`join` flow (wata-tui.md). `tools/wata-cmd-smoke.py`
(`just cmd-smoke`, in `just ci`) is the gate: the admin gate on all four
routes, take-once delivery in queue order, the long-poll wake, latest-wins
reports and their seq, the TCP-refused header path, and the
in-memory-only property across a restart.

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
elsewhere) — there is no impersonation concept. The one authorization
question that is *not* per-path ownership is the admin flag, which
`Admin.requireAdmin` asks on top of `requireAuth` for the whole
`/_wata/v1/admin/` prefix (see "The admin surface").

Secret comparisons are constant-time: `Store.ctEq` wraps
`crypto/subtle.ConstantTimeCompare` through the app-owned `go.subtle`
facade (`subtle.scala`, same mechanism as `go.iolimit`), and both the login
password check (`Router.loginCheck` -> `Pwhash.verify`, which compares the
base64 of the freshly derived key against the stored one) and the token
resolution go through it. `Store.deviceByToken` deliberately does NOT `HashMap.get` the guess —
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
rooms (`HashMap[String, Room]`), aliases, media, a flat `List[Receipt]`
(**per message**, not Matrix's one-marker-per-user: `Store.sameReceiptKey`
includes the event id, so a user's receipts on different events coexist —
wata's clients receipt each message when its playback completes and read
`played`/`playedByPeer` per event, and the read-up-to replacement semantics
would erase a sender's played check whenever the peer played anything else;
plan 0050), a
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

**Conformance.** The original TypeScript wata's jest suites, run against this
binary, were **84/84 green** with all of the above in place — including the
suites that exercise DM creation, DM reuse, and "bob
should recognize DM room after joining (m.direct sync)". The compat
projection is what holds them up; no suite had to be recorded as exercising
the retired client-authored mechanism. The suite and its runner
(`test/integration/`, `tools/wata-tests.sh`, `just conformance`) live in git
history — last present at commit `27a2f75`.

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
at boot, before listening, and then daily on a spawned goroutine.

**Favorites are the exception** (`Favorite`, favorite.scala; plan 0019). A
favorite is ROOM STATE the server writes: `net.wata.favorite`, `state_key` =
the favorited event's id, content `{"by": userId}`; unfavoriting rewrites the
slot with `{}` (the state-resolution idiom for clearing one). One
representation buys three things — it journals and replays as the existing
`event` op, it reaches every client through ordinary `/sync` state (the star a
device draws needs no new transport), and the sweep reads it out of the room
record it already walks: an event whose room state carries a *non-empty*
`net.wata.favorite` slot under its id is skipped. `Retain.exemptEventIds`
survives as an additional list-shaped seam, empty in production.

The write path is a DIALECT ENDPOINT, `POST
/_wata/v1/favorite/{roomId}/{eventId}` → `{"favorite": true|false}`, auth
required, TOGGLING. It is an endpoint rather than a raw `PUT /state` because
the rule it applies — *any joined member* may favorite — is one power levels
cannot express: `state_default` is 50 and members sit at 0, and lowering the
level for one event type in every existing room is a migration this does not
need. Gates, in order: unknown room → `404 M_NOT_FOUND`, caller not joined →
`403 M_FORBIDDEN`, target not an event of that room → `404 M_NOT_FOUND`,
target not an `m.room.message` or already redacted → `400 M_BAD_JSON`.
Favoriting a *text* message is allowed and simply has no retention meaning
today. Favorites are GLOBAL in effect: retention is server-global, so anyone's
favorite keeps the message for everyone — `by` records who, for the UI and for
a possible per-user view later. The persist smoke covers the whole loop:
favorite → age → reboot-sweep leaves event and blob, unfavorite → the next
sweep reclaims both, and each survives a `kill -9` replay.

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

## The family room and groups

The family room is a server concept, not a convention (`family.scala`; the
reasoning is `docs/plans/0018-canonical-family-rooms.md`). The same move as
canonical DMs: **the server owns the identity**, and identity lives in room
state.

**Server-minted, stamped.** `Family.ensure()` makes THE family room exist:
alias `#family:<server>`, name `Family`, the public-chat shape family-model.md
specifies, and a `net.wata.family` state event (`{}` — the stamp is the
identity, like `net.wata.dm`; clients classify by it, never by alias). It runs
at boot (`Server.serve`, after `Journal.boot` and `Dm.migrate`) and after
every account-provisioning write (`Admin.created` — both the setup claim and
an admin create), so a newly provisioned account is in the family before its
client ever syncs. Convergence is the `Dm.migrate` rule, one transaction in
`Store.familyGetOrCreate`: a room already stamped wins (oldest first — the
journal-replay path), else the room the alias names is stamped in place,
else a room is minted; nothing is deleted. With no accounts at all (first-run
setup) nothing is minted — a room needs a creator (the first admin account,
else the first account), and the first claimed account becomes it.

**Server-membered, no-leave.** Every account is joined, always: `ensure()`
writes an `m.room.member` join for each configured account not already
joined (`Family.joinUsers`). There is no join flow because there is no
unjoined state, and leaving is refused at the API (`Rooms.leaveFamilyGate`,
`403`): the account list IS the roster. A ban is the one membership the
ensure does not walk over — kick undoes itself at the next ensure, ban holds
until lifted. Client-side auto-join survives only as compat for stock
clients' invite flows.

Two edges to keep in mind. The alias is a courtesy, not the identity:
`Store.setAlias` overwrites, so a room later claiming `#family:<server>`
steals the alias mapping while classification (stamp-keyed everywhere)
stays put — an alias lookup can drift, a stamp lookup cannot. And account
*removal* does not touch membership: `ensure()` joins configured accounts
but removes nobody, so a removed account's join event stays in the room
(harmless — its tokens are dead — but roster-derived UI must count
accounts, not `m.room.member` rows).

**Groups are the same concept with a member list** (`group.scala`). A group
is a room stamped `net.wata.group` with `{"name": …}` — the name is the key,
one group per name, like one DM per pair — minted through one dialect
endpoint:

    POST /_wata/v1/group   {"name": "kids", "members": ["bob", …]}
      -> {"room_id": …}

Auth required; the caller is included implicitly; members are bare localparts
or full MXIDs (`Dm.normalize`), and an entry naming nobody here is `404
M_NOT_FOUND` *before* anything is created or joined. The server creates the
room (private shape, no alias, power levels creator 100 / members 0 /
`events_default` 0 so everyone speaks), stamps it, and **joins every listed
member server-side** — the DM-resolve precedent: membership is an act of
whoever created the group, acceptable because the server population is the
family. Re-POSTing the same name with more members is the idempotent
GET-OR-EXTEND (`Group.getOrExtend`: `Store.groupGetOrCreate` is one
transaction keyed on the stamp's name; `Family.joinUsers` is shared with the
family path). Nothing removes members; kick/ban exist for the pathological
case.

**No new journal ops.** Rooms, events, and aliases already journal; the
stamps are ordinary state events, so a replay reconstructs both keys by
itself and the store lookups just scan the (family-sized) room list. The
persist smoke pins the loop: family room id, stamp, membership, the no-leave
403, and the group's get-or-extend identity all survive `kill -9` + replay.

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
The op kinds logged: `device`, `rmDevice`, `profile`, `acct`,
`room`, `event`, `redact`, `alias`, `media` (metadata only —
`{media_id, content_type, size}`; the bytes live in the blob file, written
before the op — see "Media" above), `receipt`, `txn`, `dmpair` (a canonical
DM's pair -> room claim), and `bind` (a device-account binding
nodeId -> user, plan 0027 — a re-bind of the same node overwrites, so
replay in commit order converges on the latest binding). Long-poll waiters are
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

## Running as a service (macOS)

`[SRV-PACKAGE]` `tools/server-service.py` (plan
[0015](../plans/0015-server-service-mac.md)) packages `wata-server` as a
launchd **LaunchDaemon** — the flavor that survives both reboot and logout —
so the family homeserver runs like an appliance instead of a terminal tab.

**Layout**, versioned code separated from stable data so an upgrade never
touches the journal:

```
/usr/local/wata/
  releases/<version>/wata-server     one dir per packaged build
  current -> releases/<version>      the running release (rollback = re-point)
  bin/wata-server-run                stable wrapper the daemon execs
  etc/wata.env                       config as env lines (sourced by the wrapper)
  etc/users.json                     accounts (WATA_USERS) — NOT installed;
                                     written by the server when the first
                                     admin is created at /admin
  data/                              WATA_DATA: journal.jsonl, media/, FORMAT
  log/wata-server.log                daemon stdout+stderr
/Library/LaunchDaemons/net.wata.server.plist
/etc/newsyslog.d/wata-server.conf   size-based rotation of the daemon log
```

`<version>` is `<yyyymmdd>-<git-short-sha>` from the wata checkout at package
time, `-dirty` suffixed when the tree has uncommitted changes. `install`
never overwrites an existing `etc/wata.env`, `etc/users.json`, or anything
under `data/` — those hold the human's config and the journal, not the
package. The wrapper, plist, and newsyslog conf ARE regenerated on every
install (they are infrastructure, not config); `releases/<version>` is
replaced in place if the same version is reinstalled.

The plist itself is a **constant** — launchd cannot source an env file, so
baking config into it would turn every settings change into a sudo plist
regen. It just execs `bin/wata-server-run`, which does the sourcing:

```bash
set -a; source etc/wata.env; set +a
exec current/wata-server "${WATA_LISTEN:-:8008}"
```

`Main.addrOf` (`server.scala`) reads the listen address from `argv(0)`,
defaulting to `:8008` — that argument, not an env var, is the server's actual
listen-address mechanism, so the wrapper supplies it positionally. The
`WATA_LISTEN` env var is the wrapper's own override knob (not part of the
shipped `wata.env`): the daemon plist relies on the `:8008` default, while
`selftest` exports `WATA_LISTEN` to grab a free port without touching any
file.

**The Bonjour name.** A real `install` also publishes `wata.local` — the name
the handsets' enrolment QRs default to (`http://wata.local:8008`,
`docs/design/wata-fb.md`) — by setting the machine's mDNS `LocalHostName` via
`scutil`. mDNSResponder then answers the name with whatever address the
machine currently holds, which is what makes the QR DHCP-proof where a baked
IP goes stale. `--mdns-name <name>` publishes a different name (handsets then
need `adminUrl`/`WATA_ADMIN_URL` pinned to match) and `--no-mdns` leaves the
machine's name alone; `status` reports the current name and flags a mismatch.
A `--root` install never touches it.

Restart-on-failure is `KeepAlive.SuccessfulExit=false` plus
`ThrottleInterval` 10 — a crash loop retries forever but slowly; a clean
`launchctl bootout` (i.e. `uninstall`) stays down. The daemon runs as the
installing user (`UserName` in the plist), not root — 8008 needs no
privileged bind, and the data dir stays owned by whoever administers it.
Log rotation is newsyslog's job (5 MB, keep 5, gzip-compressed); the journal
is never rotated — it's the database, not a log.

**Lifecycle** (`sudo` is required for anything that touches the real
`/usr/local`, `/Library`, or `/etc`; the tool never calls `sudo` itself —
it checks for real root and refuses politely if absent):

```
just server-package             # build, stages under .service-stage/ (no sudo)
sudo just server-install        # newest staged release -> layout + launchctl bootstrap
just server-status               # layout, current release, journal size, launchd state
sudo just server-restart         # launchctl kickstart -k, after an etc/wata.env edit
sudo just server-uninstall                    # bootout, remove plist + newsyslog conf
sudo just server-uninstall --purge --yes      # also delete /usr/local/wata (data included)
```

Editing `etc/wata.env` and running `server-restart` is the whole
config-change workflow — no plist regen, no reinstall. **An install writes no
accounts file at all**: `WATA_USERS` names `etc/users.json`, which does not
exist yet, so a fresh install boots into setup mode and the first thing its
owner does is browse `http://<host>:8008/admin` and create the admin account
there (see "First-run setup"). A shipped placeholder account would be a
shipped credential. From then on accounts are managed on that page and the
server owns the file; hand-editing it and restarting still works.
`tools/server-service.py prune` deletes every release except the one
`current` points at, when old builds pile up. A real-root `install`
deliberately does **not** build: it consumes the newest staged release from
a prior unprivileged `server-package`, because building under sudo would
root-own the `.sgo` emit tree, the Go build cache, and the toolchain clone.

**Testing without sudo**: every subcommand that touches system paths accepts
`--root <dir>`, which re-roots the install prefix, `/Library/LaunchDaemons`,
and `/etc/newsyslog.d` under `<dir>` — with `--root`, `launchctl` and
`newsyslog` are never invoked, only files are written. `just server-selftest`
(`tools/server-service.py selftest`) is the no-sudo gate: it packages a
plain (non-iroh) build, installs into a fresh `mkdtemp` root, `plutil -lint`s
the generated plist, runs the wrapper in the foreground against a free
localhost port, polls `GET /_matrix/client/versions` until it answers, then
runs the **first-run setup round trip** an owner would do by hand: the fresh
install reports setup mode and 503s every other admin route, `POST
/_wata/v1/admin/setup` creates the first admin, a second one is refused 409,
`etc/users.json` appears holding exactly that account — hashed, no plaintext,
mode 0600 — and the account logs in. It asserts `data/journal.jsonl` exists
and is non-empty, `SIGTERM`s the process and confirms it exits, then boots a
second process against the same root to prove the account survives a restart
(no longer in setup mode, still the admin). It
prints a `SRV-PACKAGE SELFTEST PASS`/`FAIL` line and a non-zero exit on
failure.

The real `sudo just server-install` on the mac that hosts the family server
is a human step outside this gate; `just server-status` afterward is the
acceptance check for that run.

## File-by-file map

- **`model.scala`** — every domain ADT: `ErrCode`/`MErr` (errors as values), `Auth`, `UserCfg`, `Device`, `Profile`, `AcctData`, `Event`, `Room`, `MediaItem`, `Receipt`, `Waiter`, and the canonical-DM values (`DmPair`, `DmPeer`, `DmRoom`, `StateSeed`).
- **`config.scala`** — `Config`: the accounts — read at boot from `WATA_USERS` (built-in alice/bob when it is UNSET; setup mode when it is set with nothing behind it), hashed at rest, and rewritten atomically by the admin mutations and by `claimSetup` — plus `serverName`.
- **`pwhash.scala`** — `Pwhash`: PBKDF2-HMAC-SHA256 derivation, the `pbkdf2-sha256$…` stored form, constant-time verification, and the hex rendering `SelfCheck` oracles.
- **`adminapi.scala`** — `Admin`: the admin gate, the two ungated first-run routes (`mode`, `setup`), the status panel, the accounts CRUD, and the boot clock uptime is measured from.
- **`gocrypto.scala`** — `go.hashpkg`/`go.sha256`/`go.hmac`/`go.b64std`: the app-owned facades the hasher needs (`hash.Hash` as a trait — Go interfaces are traits here; `sha256.New` bound parenless so it lands as the function *value* `hmac.New` takes).
- **`webembed.scala`** — `go.webembed`: the `@go.bind` facade for `wata-server/adminui`, the `go:embed`ed admin page.
- **`membership.scala`** — the membership sealed types and the join/invite/leave/ban transition table; every row is reachable from an HTTP route.
- **`jsonnav.scala`** — `JsonNav`: field lookup/typed accessors on `Json`, object/array builder helpers (`obj1`..`obj4`, `arr1`, `endObj`), `errEnvelope`, `eventToJson`, and the account-data profile-merge helper.
- **`power.scala`** — `Power`: the `m.room.power_levels` authorization table (send/state/redact/invite/kick/ban).
- **`store.scala`** — `StoreState` + `Store`: every store mutation and read, ID generation, the long-poll waiter lifecycle, and the boot-replay entry points (`replay*`) that `persist.scala` calls into.
- **`bindings.scala`** — `Bindings` (the journaled nodeId→user map an approval writes) and `DeviceLogin` (`POST /_wata/v1/device-login`: the trusted-header check, then a fresh session for the bound account).
- **`devicecmd.scala`** — `DeviceCmd`: the device-command mailbox (queue / poll / report / read-report), the admin gate on the admin half, the two-credential device auth, and its own waiter list for the poll's long-poll.
- **`persist.scala`** — `Journal`: the JSONL op log, its own mutex, boot replay, per-op-kind (de)serialization, and the old-journal media migration.
- **`mediafiles.scala`** — `MediaFiles`: the file-backed blob store (`<dataDir>/media/<mediaId>`): dir resolution from `$WATA_DATA`/`$WATA_LOG`, write/load/exists/delete.
- **`osfile.scala`** — `go.osfile`/`go.fsx`: the app-owned `os` facade (`WriteFile`/`MkdirAll`/`Remove` for the blob store, `Rename` for the accounts file's atomic write, `Stat` for the sizes the status panel reports) plus `io/fs.FileInfo`; perms passed as literals, errors dropped except `Stat`'s.
- **`retain.scala`** — `Retain`: the media retention sweep — `WATA_MEDIA_RETAIN_DAYS`, the boot + daily passes, the server-side redaction of expired voice messages, and the favorite/exempt checks that spare one.
- **`handlers.scala`** — `Router`: the top-level route dispatch, `requireAuth`, `/versions`, login/logout/whoami, profile, and account-data handlers.
- **`keys.scala`** — `Keys`: the three E2EE device-key routes as authenticated no-op stubs; `/keys/upload` tallies the one-time-key counts back per algorithm, which matrix-dart-sdk requires, and discards the keys.
- **`rooms.scala`** — `Rooms`: createRoom, join, invite, leave/kick/ban, the generic state PUT, send/redact events, receipts, media upload, and `GET /messages` pagination.
- **`favorite.scala`** — `Favorite`: the `POST /_wata/v1/favorite/{roomId}/{eventId}` toggle, the joined-member rule, and the `net.wata.favorite` marker's read side (`isFavorited`, which the sweep calls).
- **`dm.scala`** — `Dm`: canonical DMs. The `POST /_wata/v1/dm/{userId}` endpoint, the `net.wata.dm`/alias identity, the boot migration, and the one-way `m.direct` compat projection.
- **`family.scala`** — `Family`: the canonical family room. `ensure()` (boot + every provisioning write), the `net.wata.family` stamp, the server-side `joinUsers` shared with groups; the no-leave rule lives at `Rooms.leaveFamilyGate`.
- **`group.scala`** — `Group`: groups. The `POST /_wata/v1/group` idempotent get-or-extend endpoint, keyed on the `net.wata.group` stamp's name.
- **`sync.scala`** — `Sync`: the pure sync-parts builder (initial + incremental, account data through `Dm.project`) and the long-poll orchestration.
- **`testhooks.scala`** — `TestHooks`: the `WATA_TEST_HOOKS=1`-only fail-on-demand counter and its `POST /_wata/v1/test/fail` route (see "Test hooks").
- **`server.scala`** — `WataHandler`/`MediaEdge`/`AdminPage`/`NotFound`/`Respond` (the HTTP edge), `Server` (boot + route table + the dual listener), `Main`, and `SelfCheck` (a deterministic smoke test of the store/handler logic, diffed against a golden file by the build's smoke script).

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
