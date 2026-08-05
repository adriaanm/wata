# 0014 — enrolling a device onto the family's iroh network

Status: **accepted, on hold**

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
- **QR encoding: vendor `rsc.io/qr`** (pure Go, BSD) in-tree as
  `go-pkgs/qr`; the offline build is preserved. No hand-written
  encoder unless the vendored one proves inadequate.
- **The handset flips to iroh.** Server node id baked into the device
  config at deploy time (as ruled above), keypair minted on first
  boot, enrolment run for real on hardware. TCP-LAN remains the
  fallback transport and the harnesses' default.

## Milestones

Nothing here is started; the hold above is why.

1. **Device-minted identity**: the device generates its keypair on
   first boot and persists it with the rest of its config; nothing
   mints keys by hand any more. This one is independently useful — it
   removes hand-minted keys whatever the enrolment channel turns out
   to be — so it is the natural first thing to pick up when the hold
   lifts, or sooner if key handling starts to itch.
2. **Pending enrolment**: the server takes an announced id + nonce,
   holds it with an expiry, and grows the allowlist on approval.
3. **The QR**: rendered on the device's own framebuffer, goldened like
   every other frame, plus the approval page the phone lands on.
4. **The typed-code fallback** over the same endpoint.

## Out of scope

Multi-family or hosted enrolment (plan 0008's territory), key rotation
and revocation (worth its own plan once there is anything to revoke),
and any onboarding UI beyond what the two ids require.

## Verification

An enrolment test in the same shape as `just tunnel-smoke`'s
allowlist-negative leg: a fresh device identity is refused, completes
the pending-enrolment handshake, and is then accepted — with the
refusal still loud for an id that never enrolled.
