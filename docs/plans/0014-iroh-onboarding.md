# 0014 — enrolling a device onto the family's iroh network

Status: **accepted** (hold lifted 2026-08-05 — server half landed, rulings below)

Held 2026-08-05: the approach depends on a phone acting on the device's
QR, and there was no surface for that. Re-pointed same day: the QR now
encodes an ADMIN-PAGE URL (`/admin#enroll/<nodeId>/<nonce>`), so the
stock camera app + a browser is enough — no phone client needed. The
server half (pending-enrolment store, approval, live allowlist-add) is
plan 0021 milestone B; the device half (first-boot keypair mint, the QR
screen) stays here and unblocks once 0021-B lands.

**The server half has landed** (2026-08-05, plan 0021 milestone B):
`POST /_wata/v1/enroll`, the bounded pending set, the admin
approve/deny surface and its page section, and the live allowlist-add
FFI (`irohnet_server_allow`) — proven end to end by `just tunnel-smoke`,
which approves a refused node and sees it admitted with no restart. This
plan is unblocked on the device side.

## The problem

Plan 0013 gives a device an iroh transport, but the identities are
minted by hand: `just iroh-roam-smoke` runs `irohnet-keygen` twice,
writes both JSON configs, and puts the device's node id in the server's
allowlist. A device someone actually owns has to reach the same state
with no keyboard, no camera, and a parent who is not going to paste a
64-character hex string.

Three facts have to end up in the right places:

| fact | where it must land | secret? |
|------|--------------------|---------|
| the device's own keypair | the device | **yes** — must never travel |
| the device's node id | the server's allowlist | no |
| the server's node id | the device's config | no |

## The decision this plan asks for

**Never transmit a secret.** The device mints its own keypair on first
boot and keeps it. Enrolment is then an *approval of a public id*, not
a key delivery, which removes the entire class of "what if someone
records the enrolment" questions. Everything below is only about how
the two public ids cross the gap.

That reframing matters: the audio-modem idea was born when enrolment
looked like "deliver a config to the device". It is a much better fit
for the small remaining job than for the original one.

## Getting the device's id to the server — the phone approach

Ruled 2026-08-05: **the device displays, the parent's phone scans.**

The device shows a QR of its node id plus a short-lived enrolment
nonce; the parent scans it with the phone they already have and
approves. The handset needs no camera — the camera is on the phone —
and 128×160 is enough for a ~32-byte payload at a readable module
size. The server grows an allowlist entry only on that approval, so
approval is the security boundary: it needs a short expiry and a rate
limit, and the pending entry is a *reference* to an announced id, not
a way to inject one.

A short typed code stays available as the fallback for a scratched or
dim screen, using the same pending-enrolment endpoint — it is the same
handshake with a different reader.

## Getting the server's id to the device

**Baked into the image at flash time.** We build the family's images,
so the family's own server id is known then. Zero UX, honest for the
first sweep, and nothing to design.

## Audio: dropped (recorded, not forgotten)

The audio modem was the original idea here, and it is dropped as an
onboarding transport — ruled 2026-08-05 after the reframing above.
Once the device mints its own keypair, nothing secret needs to cross
the gap, and the job shrinks to moving two public ids. A phone camera
does that in a second, against 13.3 s of tones that need quiet, a
working speaker, and a working microphone. Audio only won when
enrolment meant *delivering a config*, and it no longer does.

What that reasoning does not cover is a device we did not flash — a
second-hand handset, a factory reset, a server that moved — where the
server id has to arrive some other way. If that need becomes real, the
proven implementation is still there to port: `audiocode.ts` in the
original repo (16-MFSK, 1500–3375 Hz, 4 bits/symbol, 35 ms/symbol,
Reed-Solomon at 100% redundancy, 111 bytes in 13.3 s), with the TS
version as its oracle the way the Ogg writer had one. Until then it is
not built.

## Rulings (owner, 2026-08-05 — unblocking implementation)

- **The admin page announces.** The QR fragment
  (`#enroll/<nodeId>/<nonce>`) makes the logged-in page post the
  announce itself when no pending row exists, then highlight it for
  approval. The device therefore needs ZERO server connectivity to
  enroll — a cellular-only handset works — and the id's provenance is
  the parent's authenticated session reading the physical screen.
  `POST /_wata/v1/enroll` stays for the typed-code fallback.
- **QR encoding: `rsc.io/qr` as an ORDINARY Go dependency** (ruling
  revised same day, superseding "vendor it"): a normal `require` +
  committed `go.sum`, fetched through the module proxy like any Go
  project. The offline-build invariant was self-imposed while sgola
  flew under the radar; the owner wants external deps EXERCISED now —
  this is the first real-world proof that normal Go dependencies work
  through the sgo build, and any friction it surfaces is a ticket,
  not a reason to retreat to vendoring. CLAUDE.md's deps section
  updates when this lands.
- **The handset flips to iroh.** Server node id baked into the device
  config at deploy time (as ruled above), keypair minted on first
  boot, enrolment run for real on hardware. TCP-LAN remains the
  fallback transport and the harnesses' default.

### The handset flip — prepared, not done

Everything the flip needs is in place and nothing has been flipped:
`tools/fb-deploy.sh` provisions `/etc/wata/iroh.json` (peer, relay,
`adminUrl`; never a secret) when `BQ268_IROH_PEER` is set and runs the
transient binary against it. Making iroh the handset's PERMANENT
transport means editing the real device's `/opt/wata/start.sh` (and the
`bq268-alpine` overlay it comes from) to pass `WATA_IROH_CONFIG`, which
is an on-hardware step for whoever holds the device. The exact command
sequence — server side, device side, the flip, the first refusal, the
approval — is written down in the deploy section of
`docs/design/wata-fb.md`.

## Milestones

All four have landed. Milestone 2 came with plan 0021-B; 1, 3 and 4 with
this plan's device half (`wata-fb/src/main/scala/enrol.scala`,
`go-pkgs/qr`, `irohnet.EnsureKey`, the admin page's announce and code
box). What is left is not a milestone but a step on hardware: the flip
itself — see "The handset flip" below.

1. **Device-minted identity** — DONE. `irohnet.EnsureKey(configPath)`
   mints an ed25519 key into the device's iroh config on first use
   (temp + rename, `0600`, every other field preserved) and answers
   only the public node id. A handset is deployed with a config that
   names the family's server and carries no secret at all. Idempotent,
   so a re-deploy never re-mints an enrolled identity.
   `tools/tunnel-smoke.py`'s allowlist-negative leg now provisions its
   device exactly that way, driving the same call through
   `irohnet-keygen -config`; nothing in the repo mints a device key by
   hand any more.
2. **Pending enrolment** — DONE (plan 0021-B).
3. **The QR** — DONE. `enrol.scala` renders
   `<adminUrl>/admin#enroll/<nodeId>/<nonce>` through `go-pkgs/qr`
   (a thin adapter over `rsc.io/qr`, fetched as an ordinary Go
   dependency; level L) into the framebuffer: 37 modules at
   2px each, an 82x82 block with a two-module quiet zone. It appears
   automatically as the boot screen when the transport has refused this
   node id (`not allowlisted`) and on demand from Settings -> Enroll.
   Goldened by the `enroll` uitest scenario. The page half is the
   admin interface announcing on the fragment, per the ruling above.
4. **The typed-code fallback** — DONE, with a scope limit; see below.

### The fallback, and what it can honestly do

The QR screen also prints `<nonce>-<first 8 hex of the node id>`, and
the admin page matches that against the pending rows.

It **selects** a row; it cannot create one. The alternative was
considered and refused: if a typed prefix were accepted as an announce,
the server would have to invent the other 56 characters of the id, and
whatever it invented would be an allowlist entry no device ever asked
for. The pending set is a *reference* to an announced id, and the only
way to keep that true is to accept nothing shorter than a full key
anywhere on the enrol surface — which the server already did.

The consequence is the honest one, and it is the reason page-side
announce exists: **the typed path needs a device that could reach the
server to announce itself**, i.e. one on the family LAN (or already
holding a working iroh route). A cellular-only handset — the case that
made page-side announce the ruling — has the QR and only the QR,
because the full node id has to reach the server somehow and a parent's
phone that only knows what was typed cannot carry it.

That is not a gap to close later by loosening the endpoint. If typing
ever has to work for an unreachable device, the code has to carry the
whole id (a longer typed string, or a second channel that carries the
remaining bytes), not a prefix the server completes.

## Out of scope

Multi-family or hosted enrolment (plan 0008's territory), key rotation
and revocation (worth its own plan once there is anything to revoke),
and any onboarding UI beyond what the two ids require.

## Verification

An enrolment test in the same shape as `just tunnel-smoke`'s
allowlist-negative leg: a fresh device identity is refused, completes
the pending-enrolment handshake, and is then accepted — with the
refusal still loud for an id that never enrolled.

Built, and green:

- `just tunnel-smoke` — its enrolment-leg server boots in the BOOTSTRAP
  state: an EMPTY allowlist, which listens and refuses every peer (a fresh
  install has approved nobody; `irohnet` no longer demands a non-empty list
  to start, so no placeholder node id is needed anywhere). The device
  identity is MINTED (a config
  deployed with no secret; `irohnet.EnsureKey`), and the leg asserts the
  mint wrote a 0600 file, preserved every other field, and is
  idempotent. That id is refused (loudly, `not allowlisted`), announces,
  is approved, and is accepted with no restart of the server AND none of
  the client: the refused `wata-fb` process is left running across the
  approval and has to redial its way in.
- `just admin-smoke` — the endpoint sequence the page performs for a
  scanned fragment nobody announced, the already-enrolled answer, and the
  prefix negative: a node-id prefix is refused as an announce, as a
  63-character near-miss, and as an approve target, leaving no row behind.

## What the first hardware enrolment found

Both are fixed; the surfaces are described in the design docs
(`docs/design/wata-server.md` "Device enrolment", `docs/design/wata-fb.md`
"Two ways in").

- **"Already enrolled" had no answer.** An enrolled device has no pending
  row, and neither does one whose announce expired, so the page reported
  the expiry over both — including right after a SUCCESSFUL approve, whose
  fragment re-run re-announces and gets no row back. The server now marks
  it: the announce answers `"allowlisted": true` (and records nothing),
  and the admin listing carries the allowlisted ids beside the pending
  rows, which is also what lets the typed code — a prefix — say "that
  device is already enrolled".
- **A refused client never redialed.** The approval was live and the app
  sat on its QR until it was restarted. The latch was the transport's
  refusal cooldown re-stamping itself on every fast local failure, so a
  device that kept retrying never earned the fresh handshake the cooldown
  was supposed to allow — trying harder kept it out. Only a real handshake
  stamps it now, the cooldown is 5s, and a dial that gets through clears
  the refusal so the QR screen yields on its own.
- `just fb-ui-tests enroll` — four goldens: the not-allowlisted boot
  frame, the Settings -> Enroll row, the QR opened from it, and the
  close. The QR's module grid was checked against the encoder's own
  output pixel for pixel when the goldens were minted.
