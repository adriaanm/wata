# 0065 — inbound messages on iOS: the three delivery tiers

Status: proposed

`[IOS-INBOUND-MESSAGES]` `[IOS-PUSH-TO-TALK]`

The iPhone is a full wata client in one direction only. Plan 0063's
roundtrip proved the send path on hardware — record on the phone, hear
it on the BQ268 — but the owner reports (2026-08-18) that a message
sent TO the phone never surfaces there. This plan covers inbound end
to end, in three tiers that ship independently.

## The problem

Two problems wearing one name, and separating them is most of the
work:

1. **A defect.** With the app open and a live session, an arriving
   message should already appear: the sync engine, the arrival
   decision (`Notify.step`) and the conversation view are all
   PORTABLE code shared with wata-fb and wata-mac, where the same
   arc is pinned by `mac-smoke`'s inbound leg (bob sends via the tui
   mid-session; the differ patches exactly the unplayed underline and
   the badge). Nothing in that chain is iOS-specific, so the fault is
   in what iOS does differ in — the iroh transport under the sync
   long-poll, or the app's own lifecycle — and no iOS gate covers
   inbound at all today. That gap is why this went unnoticed: both
   simulator gates stop at "the contact list painted".

2. **A structural gap.** Even fixed, tier 1 only works while the app
   is FOREGROUNDED. iOS suspends a backgrounded app and tears down
   its sockets, so a polling client cannot hear anything while the
   phone is in a pocket — the state a walkie-talkie is normally in.
   Reaching a suspended app is APNs or nothing (`BGAppRefresh` is
   opportunistic — minutes to hours — and useless here). This is not
   a defect; it is the platform, and it is why the tiers below exist.

## The decision

Three tiers, in this order, each independently useful:

**Tier 1 — foreground sync (the defect).** Diagnose and fix inbound
delivery with the app open, and pin it with the iOS gate that should
have caught it. The gate comes FIRST: it is the diagnosis. An
inbound leg on `ios-smoke` (plain HTTP) and on `ios-enroll-smoke`
(iroh) splits the two candidate causes in one run — if the plain-HTTP
leg passes and the iroh one fails, the fault is the transport under
the long-poll; if both pass, the fault is device-only (lifecycle) and
the phone's own log is next.

**Tier 2 — `alert` pushes.** A server-side APNs pusher and a
`/_wata/v1/push` registration endpoint (plan 0008's stated
prerequisites, reusable by any future Apple client), delivering a
time-sensitive notification when a message lands for a device that is
not currently syncing. The user learns a message arrived and taps to
hear it. This is the big step — from "only works while you are
looking at it" to "you find out" — and it needs no PushToTalk
entitlement and no ephemeral-token lifecycle.

**Tier 3 — `pushtotalk` pushes.** The PushToTalk framework proper:
join the family channel, the system talk button (Dynamic Island, lock
screen) driving both transmit and receive, and a `pushtotalk` push to
the per-join ephemeral channel token waking the app straight into
live audio with no tap. This is `IOS-PUSH-TO-TALK`, and it reuses
tier 2's pusher.

Tiers 2 and 3 are sketched here and specified when reached — tier 1's
diagnosis may change what they need. This document is updated as each
tier lands; `0065-state.md` carries the running state.

## Platform facts this rests on

Verified against Apple's documentation and developer forums
(2026-08-18), because the tiering is a consequence of them:

- A backgrounded PushToTalk app that is not transmitting or receiving
  **is suspended, and its network connections are disconnected**. A
  joined channel does not keep the app alive.
- Joining a channel from the background fails outright
  (`PTChannelError.appNotForeground`) — the user must open the app
  once after a reboot or an explicit Leave.
- The framework maintains the channel across app termination, and
  incoming audio arrives only as an APNs push to the token handed
  over at join.
- Tapping the PTT status-bar pill grants a bounded background window
  (~15s on iOS 16) before suspension.

**APNs and self-hosting** (owner ruling 2026-08-18, recorded in
`docs/design/wata-ios.md`): APNs credentials are team-owned with no
delegation primitive, so self-hosters bring their own developer
account, bundle id and key — which sideloading already requires of
them. The paid hosted tier carries our key. Our build pointed at
someone else's server is not a supported configuration.

## What changes — tier 1

- `tools/ios-smoke.py`: an inbound leg after the contact list paints
  — bob sends a voice message via `wata-tui` (mac-smoke's mechanism,
  host-side, and the simulator shares the host's loopback), and the
  app's printed lines must show the arrival (`notify: …`) and the
  conversation repaint.
- `tools/ios-enroll-smoke.py`: the same leg over the iroh transport,
  which is what the phone actually runs.
- The fix itself: unknown until the gates report. Recorded here when
  it lands.

## Verification

- Tier 1: both simulator gates green, then the owner's roundtrip in
  the other direction — a message sent from the mac or the BQ268
  appears and plays on the phone with the app open.
- Tiers 2 and 3: specified when reached.

## Out of scope

- The banner/notification SURFACE beyond what each tier needs;
  `ADULT-UX-NONHAPPY` owns iOS presentation polish.
- Anything that would make our App Store build talk to a self-hosted
  server (ruled unsupported above).
- The watch client (plan 0008), which shares tier 2's pusher but is
  not part of this epic.
