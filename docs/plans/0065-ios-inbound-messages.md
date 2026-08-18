# 0065 — inbound messages on iOS: the three delivery tiers

Status: proposed

`[IOS-INBOUND-MESSAGES]` `[IOS-PUSH-TO-TALK]`

The iPhone is a full wata client, but not yet a prompt one. This plan
covers inbound delivery end to end, in three tiers that ship
independently.

## The problem

Inbound was first reported as broken and is not. The owner re-tested
(2026-08-18): messages DO arrive on the phone, and the last one
played correctly within seconds. The symptom was **delay**, not loss
— which leaves two real problems, and separating them is most of the
work:

1. **Latency, unmeasured and ungated.** The whole inbound chain is
   PORTABLE code shared with wata-fb and wata-mac, where the arc is
   pinned by `mac-smoke`'s inbound leg (bob sends via the tui
   mid-session; the differ patches exactly the unplayed underline and
   the badge). It demonstrably works on iOS too. What no gate
   anywhere asserts is HOW FAST — and for a walkie-talkie, an arrival
   that is correct but late is a product defect even though every
   assertion passes. Both iOS gates stop at "the contact list
   painted", so nothing would notice a regression from seconds to a
   minute. Open question this tier answers: does the iroh long-poll
   (what the phone actually runs) add material delay over plain
   HTTP?

2. **A structural gap.** However fast tier 1 gets, it only works
   while the app is FOREGROUNDED. iOS suspends a backgrounded app and
   tears down its sockets, so a polling client is deaf while the
   phone is in a pocket — the state a walkie-talkie is normally in.
   Reaching a suspended app is APNs or nothing (`BGAppRefresh` is
   opportunistic — minutes to hours — and useless here). This is not
   a defect; it is the platform, and it is why the tiers below exist.

## The decision

Three tiers, in this order, each independently useful:

**Tier 1 — foreground sync latency.** Measure inbound delivery with
the app open, gate it, and reduce it if the measurement says there is
something to reduce. The gate comes first because it is also the
measurement: an inbound leg on `ios-smoke` (plain HTTP) and on
`ios-enroll-smoke` (iroh), each timing `sent` → the app's `notify:`
line and asserting an upper bound. Comparing the two transports
answers whether iroh's long-poll is the cost. A bound, not just a
pass: "eventually" cannot catch the regression that matters.

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

Tier 3 is sketched here and specified when reached. This document is
updated as each tier lands; `0065-state.md` carries the running state.

## Tier 2 in detail

**The pusher is a plain-Go module.** APNs token auth needs an ES256
JWT and an HTTP/2 POST, neither expressible in the dialect, so
`go-pkgs/apns` is ordinary Go behind a thin facade — the shape
`go-pkgs/irohnet` and `go-pkgs/audio` already use. It needs **no
external dependency**: `crypto/ecdsa` + `crypto/x509` sign the JWT,
and Go's `net/http` negotiates HTTP/2 over TLS by itself.

- `POST https://api{,.sandbox}.push.apple.com/3/device/<token>`,
  headers `authorization: bearer <jwt>`, `apns-topic` (the bundle id),
  `apns-push-type`, `apns-priority`, `apns-expiration`.
- The JWT is cached and refreshed on a timer — Apple rejects a token
  younger than 20 minutes on refresh and older than 60.
- **410 Gone means the token is dead** and the caller must forget it;
  a pusher that ignores this re-sends to uninstalled apps forever.
  4xx reasons are surfaced, not swallowed.

**Registration is a wata endpoint.** `POST /_wata/v1/push/register`
`{platform, token, env}` authenticated as the calling device, stored
per (user, device) — `devicecmd.scala` is the structural model. The
token is per-install and changes; re-registration overwrites.

**When a push fires.** A message event landing in a room pushes to
every registered device of every room member except the sender's own.
Deliberately NOT conditioned on "is that device currently syncing":
the check is racy, and iOS already suppresses a banner the foreground
app consumes. Simple and idempotent beats clever here.

**How it is gated without Apple credentials.** The APNs host is
configurable, so a local fake APNs server standing in for Apple gates
the whole path: the module's own Go tests assert the JWT header and
claims, the request path and headers, and the 410-forgets-the-token
rule; a server-side scenario asserts that sending a message to a
registered device produces a push with the right payload. Nothing in
this tier needs a real key, a phone, or the developer portal — those
are needed only for the owner's final on-device leg.

**Client-side** (`simctl push` gates it without a real APNs
connection): permission request, `registerForRemoteNotifications`,
the token POSTed to the server, and the notification presented. The
payload carries `interruption-level: time-sensitive` and the room and
event ids, so a tap can open the right conversation.

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
- Both legs time `sent` → `notify:` and assert an upper bound chosen
  from observed medians, not fitted to one run.
- Any latency reduction the measurement justifies: unknown until the
  gates report, and recorded here when it lands. The sync loop's
  own cadence (long-poll timeout, retry backoff) is the first place
  to look if the numbers are poor.

## Verification

- Tier 1: both simulator gates green WITH their latency bounds, the
  two transports' numbers compared, then the owner's roundtrip — a
  message sent from the mac or the BQ268 appears and plays on the
  phone within seconds, with the app open.
- Tiers 2 and 3: specified when reached.

## Out of scope

- The banner/notification SURFACE beyond what each tier needs;
  `ADULT-UX-NONHAPPY` owns iOS presentation polish.
- Anything that would make our App Store build talk to a self-hosted
  server (ruled unsupported above).
- The watch client (plan 0008), which shares tier 2's pusher but is
  not part of this epic.
