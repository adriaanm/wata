# 0065 — working state

The live state of the inbound-messages epic (plan
[0065](0065-ios-inbound-messages.md)). Newest entry last. This file is
scratch that outlives a session: what is done, what is in flight, what
the next session should pick up. It is deleted when the epic closes and
its durable content has moved into the plan and the design docs.

## Where things stand

| tier | what | state |
|------|------|-------|
| 1 | foreground sync (the defect) | in progress — gates first |
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
