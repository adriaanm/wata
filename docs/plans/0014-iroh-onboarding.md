# 0014 — enrolling a device onto the family's iroh network

Status: **proposed**

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

## Getting the device's id to the server

The device has a screen, which is the asset to use.

- **Device displays, phone scans.** The device shows a QR of its node
  id; the parent scans it with their phone, which posts it to the
  server. The BQ268 has no camera, but nothing here needs it to have
  one — the camera is on the parent's phone. 128×160 is enough for a
  ~32-byte payload at a readable module size.
- **Device displays a short code.** A 6–8 character code derived from
  the pending node id, typed into a web page or the tui. No optics, no
  alignment, works when the screen is scratched; the code is a
  reference to a pending enrolment the device also announces, not the
  id itself.

Both want a *pending enrolment* endpoint on the server: the device
announces "I am id X, code ABC-123, awaiting approval", the parent
approves out of band, the allowlist grows by one. Approval is the
security boundary, so it needs a rate limit and a short expiry.

## Getting the server's id to the device

- **Baked into the image.** A family's own server id written at flash
  time. Zero UX, and honest for the first sweep — we build the images.
- **Audio.** The proven path: `src/shared/lib/audiocode.ts` in the
  original repo does 16-MFSK, 1500–3375 Hz, 4 bits/symbol, 35 ms/symbol
  with Reed-Solomon at 100% redundancy — 111 bytes in 13.3 s, which
  comfortably carries a 32-byte node id. A phone browser plays it; the
  device already has the microphone and the Opus path. Its real value
  is *re-provisioning a device we did not flash* — a second-hand
  handset, a factory reset, a server that moved.
- **Rendezvous.** A well-known bootstrap node the device asks. Most
  convenient, most infrastructure, and it puts a third party in the
  trust path. Not for the family tier.

## Recommendation

Start with device-minted keys plus the pending-enrolment endpoint, and
bake the server id at flash time. That makes the first family sweep
work with no new hardware and no new protocol. Add the QR display
next, since it is a small change to a screen we control. Keep the
audio channel as the answer to re-provisioning, and port `audiocode.ts`
when that need is real — the TS implementation is the oracle, the same
way the Ogg writer was.

## Out of scope

Multi-family or hosted enrolment (plan 0008's territory), key rotation
and revocation (worth its own plan once there is anything to revoke),
and any onboarding UI beyond what the two ids require.

## Verification

An enrolment test in the same shape as `just tunnel-smoke`'s
allowlist-negative leg: a fresh device identity is refused, completes
the pending-enrolment handshake, and is then accepted — with the
refusal still loud for an id that never enrolled.
