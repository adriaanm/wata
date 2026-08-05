# 0021 — the admin web interface

Status: accepted (A, B landed; C added by owner ruling 2026-08-05)

## Problem

Administering a wata server today means ssh and files: accounts are a
JSON file read once at boot, observability is `tail` on a log, and
device enrolment (plan 0014, on hold) has an approval step with no
surface to live on. The tui (plan 0016) covers poking the server, but
configuring it and onboarding people/devices wants a real interface a
parent can use from any browser on the family network.

Plan 0017 proposed a web app for a different job — a phone *client* —
and is retired by this plan (ruling 2026-08-05): the phone client
should be native (plan 0008's direction, not urgent), while the parts
of 0017 that were really administration land here.

## Rulings (owner, 2026-08-05)

- **Auth: an `admin` flag on accounts.** `users.json` entries gain
  `"admin": true`; every `/_wata/v1/admin/*` endpoint requires an
  authenticated session whose account carries it. Parents are admins;
  handset accounts are not. The built-in fallback pair marks `alice`
  admin so harnesses and a fresh server have a usable admin without a
  file. Login is the ordinary password login — no second credential.
- **V1 scope: status + users + enrolment.** Groups (plan 0018) and
  editable server config are explicitly later; config is shown
  read-only (retention, transport, data dir, version).
- **Phone: native, backlogged.** 0017 abandoned; IROH-ONBOARD's
  approval surface is this interface, not a phone app.
- **No plaintext passwords at rest** (added same day). `users.json`
  stores `"hash": "pbkdf2-sha256$<iter>$<saltB64>$<dkB64>"` instead of
  `"password"`. PBKDF2-SHA256 is implementable over the Go stdlib
  (`crypto/hmac` + `crypto/sha256` facades; the derivation loop is
  ~30 lines of Sgola, oracled against the RFC 6070/7914 test vectors
  the way the Ogg CRC is), so no external dependency. Verification
  goes through the existing constant-time compare. A plaintext
  `"password"` field stays accepted as *input* — a human provisioning
  by hand writes plaintext once, and the server rewrites the file
  hashed at boot (the same server-owns-the-file mechanics the admin
  mutations use), so plaintext never persists past the first boot.
  Admin create/reset hashes before writing. The built-in no-file
  alice/bob fallback is unchanged (it exists only for harnesses).
  Iterations ride in the format string, so raising them later
  re-hashes lazily on next password set, no migration.

## Shape

**Serving.** `GET /admin` serves a dependency-free, hand-written
static page embedded in the binary (Go `embed` behind a small facade),
same registration pattern as every other route. When the transport is
iroh, the server ALSO serves plain HTTP (`WATA_LISTEN`, default
`:8008`) — a browser cannot dial iroh, and the TCP listener stays
inside the LAN trust boundary exactly like today's default mode. The
handler surface is one mux on both listeners.

**Milestone A — status + users.**

- `GET /_wata/v1/admin/status` — version/uptime, per-user device count
  and last-sync age, journal size, media count/bytes, retention
  setting, transport mode. All reads the store already can answer.
- Account management: `GET/POST/DELETE /_wata/v1/admin/users[...]` —
  create (name, password, displayname), reset password, rename
  display, remove. **The server owns `users.json` writes now**: a
  mutation rewrites the file atomically (temp + rename, preserving the
  admin flags) and applies in memory without restart; removing a user
  revokes their live devices/tokens in the same store transaction.
  The file stays the source of truth and stays human-editable — a boot
  still just reads it; only "read once" changed to "read at boot,
  rewritten by admin mutations". Accounts remain config, not journal.
- The page: one screen, status panel + user table + add/edit forms,
  plain fetch calls, no framework, theme-free.

**Milestone B — enrolment (plan 0014's server half, landing here).**

- `POST /_wata/v1/enroll {nodeId, nonce}` — unauthenticated announce
  from a device, held in memory with a short expiry and a small
  pending cap (it references an announced id; it cannot inject one
  into the allowlist).
- Admin: pending list with approve/deny; approval appends the node id
  to the iroh config's allowlist file (durable) AND applies it to the
  live listener — which needs one new FFI in `go-pkgs/irohnet`
  (`irohnet_server_allow(id)`), since today the allowlist is fixed at
  listener creation. Revoke = remove from file + FFI remove +
  connection close.
- The device-side milestones (first-boot keypair mint, the QR screen)
  stay in plan 0014 and unblock separately; the QR encodes
  `http://<server>/admin#enroll/<nodeId>/<nonce>` so the stock camera
  app lands the parent on the approval row after login.

**Milestone C — first-run setup (owner ruling 2026-08-05: acceptance
is "start from an empty install and do the initial admin flow").**

- **Setup mode**: when `WATA_USERS` is SET but the file is missing or
  holds zero accounts, the server is in setup mode — `/admin` serves a
  create-the-admin-account screen, and `POST /_wata/v1/admin/setup
  {user, password, displayname}` (unauthenticated, valid ONLY in setup
  mode) creates that account with the admin flag, writes the file
  (hashed, atomic, 0600), and ends setup mode in the same operation —
  first-comer-claims on the LAN, router-style; the window closes with
  the first write. Every other admin route answers 503 during setup so
  nothing half-works without an account.
- **The fallback pair narrows to harness mode**: alice/bob apply only
  when `WATA_USERS` is UNSET (every harness runs that way). A real
  install always sets it, so a real install never has a baked-in
  credential.
- **The install stops seeding accounts**: `tools/server-service.py`'s
  template no longer writes placeholder users; a fresh install boots
  into setup mode. `server-selftest` covers the setup round-trip.
- Acceptance (owner-run): wipe/point at an empty prefix, install,
  browse `/admin`, create the admin account + password, log in, create
  a member account, reboot the service, both survive; the users file
  holds only hashes.

**Names (owner ruling 2026-08-05: "both usernames and display names,
exposed through the admin interface and display name used consistently
through the client UI").** `[DISPLAY-NAMES]`

- Admin side (largely landed with A): the user table shows BOTH columns
  — username (the immutable login/localpart) and display name
  (editable); create and setup (C) take a display name. Audit the page
  so both are always visible, never conflated.
- Client consistency: everywhere a human sees a user, it is the display
  name; the fallback for a member with no profile displayname becomes
  the LOCALPART, never the full mxid (today `syncengine.scala:438`
  falls back to the raw `@user:server` id). tui `display()` gets the
  same fallback instead of `-`.
- The device settings' preset NAME PICKER is removed: it cycles
  hardcoded names and overwrites the server-side displayname, which
  now belongs to the admin interface. The server's profile fan-out
  (m.room.member) is the propagation path; verify a rename lands on
  a syncing device without restart and add the integ leg if missing.
  (The `ActSetName` action stays in wataclient — the tui/admin may use
  it; only the fb settings row goes.)

## What changes (file-level)

- `wata-server/src/main/scala/adminapi.scala` (new), `webembed.scala`
  (new, the embed facade), `config.scala` (admin flag, hot apply,
  file rewrite), `store.scala` (session revoke on user removal),
  `server.scala` (dual listener + routes), `wata-server/adminui/`
  (the static page).
- Milestone B adds `enroll.scala` and the irohnet FFI/Go allowlist-add.
- Docs: wata-server.md (admin surface, accounts lifecycle, dual
  listener), plan 0014 (re-pointed unblock), plan 0017 (abandoned).

## Verification

- Integ: admin-auth gate (non-admin account → 403 on every admin
  route), user create → login works with no restart, password reset
  invalidates nothing but the password, remove → the removed user's
  token is dead mid-session, `users.json` on disk reflects every
  mutation and round-trips a reboot.
- Passwords: PBKDF2 oracle vectors byte-exact; a hand-written
  plaintext `password` entry logs in, and after one boot the file
  holds only a hash that still logs in; no plaintext substring of any
  password appears in the rewritten file.
- A static-page smoke: `/admin` answers 200 text/html on both
  transports' HTTP listener.
- Milestone B: extend `tunnel-smoke` — intruder refused (existing
  leg), announces, admin approves via the endpoint, same key then
  accepted without a server restart; deny/expiry paths covered.
- Conformance stays 84/84.

## Out of scope

- Group management (plan 0018), editable env config, TLS, any
  member-facing (non-admin) web UI, the native phone app.
- Multi-admin roles/audit beyond the flag.
