# 0065 — working state

The live state of the inbound-messages epic (plan
[0065](0065-ios-inbound-messages.md)). Newest entry last. This file is
scratch that outlives a session: what is done, what is in flight, what
the next session should pick up. It is deleted when the epic closes and
its durable content has moved into the plan and the design docs.

## Where things stand

| tier | what | state |
|------|------|-------|
| 1 | foreground sync latency | **done** — measured, gated, nothing to reduce |
| 2 | `alert` pushes | in progress |
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

### 2026-08-18 — tier 1 closed: foreground delivery is instant, on both transports

Both gate legs landed (`7544f34`) and both pass. Measured `sent` →
the app's `notify:` line:

| transport | gate | latency |
|-----------|------|---------|
| plain HTTP | `just ios-smoke` | 0.02s, 0.00s, −0.01s |
| iroh | `just ios-enroll-smoke` | −0.00s, −0.01s, −0.02s |

Reviewed and independently re-run by the main session (`just
ios-smoke`, exit 0, latency −0.01s) rather than accepted on report.

**iroh costs nothing measurable over plain HTTP** — both sit under the
measurement's own ~0.1s resolution. A small NEGATIVE reading is real
and expected: the two endpoints are read off different pipes and the
arrival can beat the sender's own stdout out of the kernel. The server
wakes the sync long-poll the instant the event lands; nobody waits out
a poll interval on either transport. The budget is a deliberately
loose 15s ceiling (`simrun.LATENCY_BUDGET_S`) — nothing this small can
be fitted, so the bound exists to catch a slide to tens of seconds,
not to police jitter. A negative control (sender suppressed) was run
and correctly FAILS, so the new assertion can fail.

**Conclusion: there is nothing to reduce in the foreground path**, and
the owner's observed delay is device-side. The leading hypothesis is
app suspension — a backgrounded iOS app is suspended with its sockets
torn down, so a message sent while the phone is in a pocket cannot
land until the app is next foregrounded, at which point it arrives
"suddenly" and looks delayed. That is exactly the gap tiers 2 and 3
exist to close, so it is not separately chased: if the hypothesis is
right, tier 2 fixes it; if tier 2 lands and the phone still lags with
the app OPEN, that is a new and much better-specified bug.

Harness gotchas from the chunk, kept because they generalize:

- **Never judge a gate through a pipe in this environment** — the
  shell is fish, which has no `$PIPESTATUS` (it is `$pipestatus`), so
  a failing run read as statusless. Redirect to a file and read
  `$status`.
- **A done-marker can race its own stimulus.** The arrival line ends
  the run, and it lands BEFORE the sending tui exits — so reading the
  sender's result straight after the launch returned reported a
  failed sender on a healthy run. Both smokes now wait on an event
  the sender sets. Any harness whose terminator can precede the
  completion of the thing that caused it has this shape.
- **Timestamp the stimulus as it happens, not at process exit** —
  `tui_send` streams the tui's stdout and stamps the `sent` line, so
  the measurement is delivery rather than process teardown.
- `@phone` is already in the family room by construction (enrol's
  approve-with-user runs `createBound` → `seeded` → `Family.ensure`),
  so the enroll gate needed no extra setup to receive.
- The enroll smoke's SENDER rides plain TCP deliberately (the iroh
  server also serves matrix on its localhost admin listener), keeping
  the leg a measurement of the APP's transport and avoiding an
  iroh-capable tui.

Next: tier 2.

### 2026-08-18 — tier 2, chunk A: the pusher module landed

`go-pkgs/apns` (`7487be4`): ES256 JWT auth, a configurable host, the
alert payload builder, and `just apns-tests` wired into `ci`. No
external dependency, as the plan required — stdlib crypto signs and
`net/http` negotiates HTTP/2 by itself. Reviewed and re-run here from
a clean cache (`go build`/`go vet`/`go test -count=1`/`gofmt -l` all
green) rather than accepted on report; the gopls diagnostics claiming
`undefined: mintToken` were a workspace artifact (the module is not in
a `go.work`), not a real breakage.

Two things the implementation got RIGHT that are worth keeping stated,
because a later refactor could quietly undo either:

- **The ECDSA JWT signature is raw R||S, not ASN.1 DER.** The natural
  call — `(*ecdsa.PrivateKey).Sign`, the `crypto.Signer` method —
  returns DER (~70–72 bytes, leading `0x30`), and every JWT/JWS ES256
  consumer including APNs wants exactly 64 bytes for P-256. The code
  calls `ecdsa.Sign` directly and `FillBytes`-pads R and S. A test
  pins the 64-byte width, and it was drilled: swapping in the DER
  path made it fail with "signature is 72 bytes, want exactly 64",
  which is the only reason to believe a negative test.
- **`error` is reserved for transport-level failure.** Every
  HTTP-level outcome (200/410/4xx/5xx) comes back in the result, so
  `Gone` and `Reason` cannot be silently dropped by an
  `if err != nil` early return — which was the whole point of asking
  for 410 as a checkable outcome rather than an error string.

Open choice, accepted: the JWT refresh threshold is 45 minutes
(`tokenRefreshAfter`), inside Apple's "not more often than 20, at
least every 60" band with 15 minutes of margin against clock skew in
either direction. Nothing suggests a better number; revisit only if
real APNs rejects a token as stale.

Also free and kept: `apns-id` from the response header, as a
correlation id for logs once real APNs is involved.

Next: chunk B — the facade plus the server wiring (registration
endpoint, push on message, the fake-APNs scenario).

### 2026-08-18 — tier 2, chunk B: the server half landed

`ff35898`: the facade, `POST /_wata/v1/push/register` with persistence
and a forget path, the push-on-message fan-out, and
`tools/wata-push-smoke.py` (19 assertions) wired into `just ci`.
Verified here, not accepted on report: `go test -count=1` on the
module, `tools/wata-push-smoke.py` (PASS), and a full `just ci` run in
the foreground — **exit 0**. The gopls `undefined:` diagnostics on
`facade.go` were the same `go.work` artifact as chunk A.

**The facade bound cleanly with no dialect workaround and no sgola
ticket** — `@go.bind` object, `go.Int` for Go `int`, `(int, error)` →
`go.Int throws sgo.GoError`, `go.spawn` for the fan-out. That is a
positive datum about the compiler, and worth reporting upstream as
one: a non-trivial multi-step Go dependency reached from Sgola without
a single restriction hit.

Decisions the implementation had to make, all now folded into the plan
text:

- **"Every room member" was wrong**: it must be joined OR invited (the
  population `Store.notifyRoomMembers` wakes). Caught by the gate — a
  canonical DM leaves the peer holding an invite until they ask for
  the room, so the first message in a DM pushed to nobody.
- **The APNs host is per-registration**, not per-server: a dev-build
  token is sandbox-only and an App Store token production-only, and
  one server can hold both. `env` rides the row, server config is the
  fallback, a host override is the test seam. **Confirmed.**
- **Registrations are keyed by token as well as by (user, device)** —
  one token is one app INSTALL, so a logout/login (fresh device id)
  must drop the old row or the install collects a push per stale
  registration.
- **Only the sending DEVICE is excluded**, not the sending user: a
  message sent from the mac should still reach that person's pocket.
- **The badge is the recipient's unplayed count** across joined and
  invited rooms. Costs a walk of the user's rooms per push — the
  first thing to revisit if timelines grow.
- Config is `WATA_APNS_*` env vars, matching `WATA_USERS` /
  `WATA_LOG` / `WATA_IROH_CONFIG`; a broken key file is reported and
  left disarmed rather than fatal.

Two limits of the gate, stated so it is not over-trusted:

- The smoke's fake APNs is plaintext, so Go falls back to HTTP/1.1.
  The smoke proves the FAN-OUT (who is pushed, who is not, 410
  deleting the row); the wire shape stays gated by `go-pkgs/apns`'s
  own HTTP/2 tests. Nothing yet exercises both together.
- `just ci` now needs `openssl` on PATH (the throwaway ES256 test key
  — Python's stdlib cannot generate an EC key). Mild, but new.

Real behaviour learned, not a bug: **re-login turns your own prior
registration into a push target**, because the new session has a fresh
device id and the old row is no longer "the sender's own device",
until that install re-registers.

Next: chunk C — the iOS client half (permission, token registration,
notification presentation), gated with `simctl push`.
