# 0065 — working state

The live state of the inbound-messages epic (plan
[0065](0065-ios-inbound-messages.md)). Newest entry last. This file is
scratch that outlives a session: what is done, what is in flight, what
the next session should pick up. It is deleted when the epic closes and
its durable content has moved into the plan and the design docs.

## Where things stand

| tier | what | state |
|------|------|-------|
| 1 | foreground sync latency | in progress — gates first |
| 2 | `alert` pushes | not started |
| 3 | `pushtotalk` pushes | not started |

## Log

### 2026-08-18 — the epic opened

Owner report: the phone sends but does not receive. Tickets filed
(`IOS-INBOUND-MESSAGES`, `IOS-PUSH-TO-TALK` blocked on it), the APNs
self-hosting ruling recorded in `docs/design/wata-ios.md`, plan 0065
written.

Reading done before any code, so the next session need not repeat it:

- The whole inbound chain below the app is PORTABLE and shared with
  clients where it works: `wataclient`'s `SyncEngine` (pure, fixture
  -oracled), `Notify.step` (the arrival decision), and the
  conversation view. `mac-smoke` pins the identical arc end to end
  (bob sends via the tui mid-session → the differ patches exactly the
  unplayed underline + badge → the row appears). So the fault is in
  what iOS actually differs in.
- `wata-ios`'s pump (`main.scala`) drives that chain the same way
  wata-mac's does: `drainUiEvents` → `h.snapshot()` → `notifyStep` →
  body → diff → publish. `announce` prints `notify: play …` when
  walkie-talkie mode auto-plays and `notify: noted …` otherwise.
- **The default notify mode is `MODE_QUIET`** (`FbConfig` reads
  `notify_mode`, defaulting to `Notify.MODE_QUIET`), and iOS has no
  banner surface — so on iOS a correctly-arriving message is SILENT
  and its only trace is the conversation row, the unplayed underline,
  the badge, and a printed `notify: noted`. "Nothing happened" is
  therefore consistent with both a real delivery failure and a
  working delivery nobody can perceive. The gate must assert the
  printed line, not the sound.
- Neither iOS gate covers inbound at all: `ios-smoke` and
  `ios-enroll-smoke` both stop at "the contact list painted". That is
  why this went unnoticed.

Candidate causes, to be split by the tier-1 gates:

1. the iroh transport under the sync long-poll (the phone runs iroh;
   the send path is proven, which only exercises short requests);
2. app lifecycle — a backgrounded/suspended app receives nothing, by
   design, and the owner's test may have crossed that edge;
3. something genuinely iOS-specific in the pump's snapshot handling.

Next: the inbound leg on `ios-smoke` (plain HTTP) and on
`ios-enroll-smoke` (iroh). Passing plain HTTP + failing iroh points at
the transport; both passing points at the device, and `just ios-log`
after a send-to-phone is the next probe.

### 2026-08-18 — tier-2 groundwork (read-only, while tier 1 is in flight)

Where an APNs pusher would land in `wata-server`, established so
tier 2's brief can be concrete:

- **Endpoint convention** is `/_wata/v1/…`, routed in `server.scala`
  and mirrored one-for-one in the module that owns the surface.
  `devicecmd.scala` is the closest structural model for a push
  registration: per-device state, an admin side and a device side,
  and it already long-polls.
- **Feasibility of the push itself.** APNs token auth needs an ES256
  (P-256 ECDSA) JWT and an HTTP/2 POST — neither expressible in the
  dialect. The established shape for exactly this is a small plain-Go
  module under `go-pkgs/` reached as a `godep` behind a narrow
  facade, the way `go-pkgs/irohnet` and `go-pkgs/audio` already work;
  the server's own `gocrypto.scala` shows the alternative (a
  `@go.bind` facade per Go import path) which is right for stdlib
  surface but wrong for a multi-step protocol. So: `go-pkgs/apns`,
  facade-thin.
- Config surface needed: key file (`.p8`), key id, team id, bundle
  id, and the environment (`development` vs `production`, which are
  different APNs hosts). Per the owner ruling these are the
  self-hoster's OWN credentials, so they are ordinary server config,
  not baked constants.

### 2026-08-18 — CORRECTION: inbound is not broken, it is slow

The owner re-tested: messages DO arrive on the phone, and the last one
played correctly within seconds. The original report was DELAY, not
loss. The defect hunt is void — plan 0065's problem statement is
rewritten around latency, and the in-flight gate chunk was re-aimed
mid-run: build the same two legs, but time `sent` → the app's
`notify:` line, assert a bound, and report plain HTTP vs iroh
separately.

Two consequences worth keeping:

- The reading below still stands, and one item of it is now the
  likely explanation of the whole report rather than a caveat: the
  default notify mode is quiet and iOS has no banner, so a message
  that arrives while the owner is not looking at the conversation
  leaves almost no trace. "Delayed" and "arrived quietly, noticed
  later" are hard to tell apart from the outside — which is an
  argument for tier 2 independent of any latency number.
- Tier 1's value moved from "fix it" to "know it, and never regress
  it". A correct-but-late arrival is a product defect for a
  walkie-talkie while every existing assertion passes.
