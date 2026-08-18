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
| 2 | `alert` pushes | **done** — gated; on-device leg is `IOS-PUSH-ON-DEVICE` |
| 3 | `pushtotalk` pushes | server half **done**; client half device-only, blocked |

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

### 2026-08-18 — tier 2, chunk C: the iOS client half landed; tier 2 done

`dc2e89a`: `iosshell/push.go` (four delegate methods on the same
synthesized `WataAppDelegate`), `wata-ios/push.scala` (the
registration POST, retried until 200, re-armed per session),
`main.scala`'s one-drain `pushStep`, three names added to the
`usernotifications` bindgen allowlist, and
`tools/ios-push-smoke.py` + `just ios-push-smoke`.

Verified here: `just ios-build-check` (0), `just ios-push-smoke` (0),
`just ios-smoke` (0, **inbound latency 0.00s — unperturbed**). No
bindgen refusal blocked anything; the refusal list is byte-identical
to before.

**Reviewer change on top of the chunk**: the smoke's PASS line claimed
the app "registered for remote notifications" when registration had in
fact FAILED. A green line that overstates what ran is worse than a red
one, so it now states exactly what each half proves.

What tier 2's gates prove, and what they cannot:

- **Presentation — proven.** `simctl push` → the delegate fired,
  wata's room and event ids came out of `userInfo` intact, the app
  told iOS to present.
- **Registration — proven only as SURVIVABLE FAILURE.** A
  hand-bundled ad-hoc-signed simulator app has no `aps-environment`
  entitlement, so no device token is ever issued: `push.scala`'s
  POST has never executed in a gate, and neither has the hex
  encoding of a real token, the server storing it, the
  token-changed re-registration, or the retry.
- **The tap is unexercised** — no harness can tap a banner.
- **Nothing touches Apple**: no APNs connection, no
  `interruption-level: time-sensitive` breaking through Focus, no
  lock-screen or backgrounded delivery, no badge.

That residue is now `IOS-PUSH-ON-DEVICE` in the queue, with the
owner's three steps in order.

Decisions made in the chunk, accepted:

- **`WATA_IOS_APNS_ENV`, defaulting to sandbox.** Nothing short of
  parsing the embedded provisioning profile tells an app whether its
  token is sandbox or production, and every build this repo produces
  is a sandbox build. An App Store build must set it — flagged by the
  implementer as the decision most likely to bite, and worth
  re-reading the day a TestFlight build exists.
- Fixed 10s retry rather than a backoff: a registration that never
  lands is a phone that never rings, and it is one small POST.
- `registerForPush` runs from the `ready` hop, not the launch
  callback, so `interptest` raises no permission prompt.
- `tools/ios-device.py` deliberately UNTOUCHED: adding
  `aps-environment` would make signing fail until the App ID has the
  Push Notifications capability. That is an owner/portal step, not a
  blind edit.

Next: tier 3 (`pushtotalk` pushes and the live arc), noting that tier
2's own on-device leg is unproven — tier 3 builds on a foundation the
owner has not yet field-tested.

### 2026-08-18 — tier 3, server half: ephemeral tokens and the pushtotalk push

`e38f153`: `POST /_wata/v1/push/channel/{join,leave}`, channel rows
kept apart from tier 2's stable alert rows, the `pushtotalk`-typed
push at the `.voip-ptt` topic, and `tools/wata-ptt-smoke.py` (26
checks) in `just ci`. `go-pkgs/apns` grew per-send topic and push-type
overrides and a PTT payload.

Verified here: the module tests, both smokes, and a full `just ci` —
**exit 0**.

Decisions made in the chunk, all accepted:

- **Both tokens → PushToTalk wins exclusively, with the alert as a
  FALLBACK.** The two answer the same question at different levels, so
  sending both would give one message a banner *and* a live handover.
  A device with a live channel token is dropped from the alert list;
  its alert row is used only if the channel push is REJECTED, and then
  that same message goes out as an alert. Losing the handover is not a
  reason to lose the message. Both target lists are built BEFORE
  either is pushed — otherwise the fallback and the ordinary alert leg
  double-push. That ordering is load-bearing and silent; the gate's
  "exactly once" assertion is what would catch a regression.
- **Any 4xx deletes a channel row, not just 410.** A phone that left
  the channel yields `400 BadDeviceToken`. Deliberately stricter than
  tier 2's 410-only rule, and correct because an ephemeral token APNs
  refuses is never coming back — the client mints a fresh one on the
  next join.
- **Channel rows are journaled.** "Ephemeral" describes the token's
  binding to a join, not durability across OUR restart: the framework
  maintains the channel across app termination, and re-joining needs a
  foregrounded user action the server cannot trigger, so dropping the
  table on boot would silence exactly the phone tier 3 exists to wake.
  A genuinely dead token is self-correcting via the 4xx rule.
- The apns client cache is now keyed by (host, TOPIC) — it was host
  alone, which would have served PTT pushes carrying the bare bundle
  id as `apns-topic`.

What this gate does NOT prove — the seam left for the phone:

- **No PushToTalk framework exists anywhere in it.** The fake Apple
  accepts whatever we send; nothing shows iOS accepts our payload,
  that `activeSpeaker` is the key the framework wants, or that the
  app is woken into live audio.
- The `.voip-ptt` topic and the `pushtotalk` type are asserted against
  our own constants. Mitigating this: the topic is corroborated by
  plan 0008's independent 2026-08-04 reading of Apple's docs, so it is
  two sources rather than one — but a wrong constant would still pass
  every check here and fail every real push with `TopicDisallowed`.
- Transport is plaintext HTTP/1.1 as in tier 2; HTTP/2 and the JWT
  stay gated by the module's own tests.
- No entitlement, portal or signing leg. Real APNs refuses a PTT push
  without the push-to-talk entitlement on the App ID.
- Sandbox-vs-production host selection is untested for BOTH tiers —
  the gates always use the `WATA_APNS_HOST` override.
- Apple's "speaker stopped" convention (an empty `activeSpeaker`) is
  representable but unused; nothing decides when the server would
  send it. That belongs with the client half.

Next: tier 3's client half — productizing the PTT hello into wata-ios.
It is device-only and should wait on the owner's tier-2 on-device leg
(`IOS-PUSH-ON-DEVICE`), since building a live arc on an unproven
foundation is how a session spends a day on the wrong bug.

### 2026-08-18 — tier 3, client half: landed BUILD-CHECKED, not proven

`79b14e1`: the channel manager, both delegates, join/leave against the
family room, the ephemeral token POST, the transmit path through the
framework, the `macaudio` session handoff, and a profile-driven
entitlement/background-mode stage in `tools/ios-device.py`.

**Nothing in this chunk is behaviourally tested, and no gate here can
test it** — the PushToTalk framework does not exist in a simulator.
What was verified is that it compiles, links and perturbs nothing:
`ios-build-check`, `facade-check`, `ios-smoke` (latency 0.03s,
unperturbed), `ios-push-smoke`, `ios-interptest` and `just ci`, all
re-run here, all 0. The chunk was briefed NOT to fabricate a mock PTT
layer for a green gate, and did not.

The design decisions worth keeping are folded into
`docs/design/wata-ios.md` (the session handoff, the single transmit
path, the derived talk event, the foreground-join rule). The owner's
ordered device checklist is in the plan's Verification section.

Two decisions worth noting here:

- **`incomingPushResult` is answered synchronously** with the
  payload's `activeSpeaker` — an unanswered PTT push costs the app its
  channel, so leaving it unimplemented was not an option. It does not
  yet fetch and play the message; that receive half is open.
- **`tools/ios-device.py` reads entitlements OFF the profile** and
  writes the PTT background modes only when the profile grants both
  capabilities, so the owner's existing flow is unbroken until they do
  the portal step. `WATA_IOS_REQUIRE_PTT=1` turns the missing
  capability into a refusal.

### 2026-08-18 — an unrelated red gate, verified not ours

`just mac-smoke` is RED, and was already red before this epic: it
fails identically at the pre-epic commit `be3504d`, checked in a
clean worktree with the toolchain linked in (the first attempt proved
nothing — a fresh worktree has no `.toolchain`, which is gitignored).
Diagnosed while confirming: the contacts footer legend is now
`UP/DN sel OK open PTT talk` while `tools/mac-smoke.py` still asserts
the exact old string — a stale assertion, not a product regression.
Filed as `MAC-SMOKE-STALE-LEGEND`. A permanently red gate masks every
real regression behind it, so it is worth fixing promptly even though
it is nobody's current task.

### 2026-08-18 — LIVE ON HARDWARE: the push path works end to end

Against the owner's iPhone (`foon`) and the real APNs sandbox, with
the server holding a real `.p8`. What the phone's log shows:

```
push: authorized
push: device token (64 hex chars)
ptt: restoring the channel
ptt: ephemeral token (64 hex chars)
ptt: joined (restoration)
ptt: channel manager ready
push: registered with the server
ptt: channel registered with the server
notify: noted "Alma" "sent you a voice message" badge=5
ptt: incoming push (Alma)
macaudio: PushToTalk owns the audio session
ptt: audio session activated
```

Everything the simulator could not reach is now proven:

- **A real APNs device token was issued and POSTed** —
  `push.scala`'s registration, which had never executed in any gate.
- **The channel manager instantiated in the wata app**, so the
  profile-driven sign stage writes correct entitlements. The portal
  step turned out to be already done: the app rides the
  `net.wa-ta.hello` identity, whose App ID already carries Push
  Notifications AND Push to Talk (profile valid to 2027).
- **The ephemeral channel token registered** — tier 3's whole server
  contract.
- **Real `pushtotalk` pushes were delivered** from our own server: the
  right topic, the right push type, the entitlement, the JWT, the
  fan-out. Three of them, one per message from the BQ268.
- **The session handoff engaged**: `macaudio: PushToTalk owns the
  audio session`.
- `joined (restoration)` — the framework restoring the channel across
  launches, and our restoration delegate answering with the
  descriptor.

**And the user saw nothing.** That is plan 0065's own ruling, which is
premature rather than wrong: a `pushtotalk` push shows no banner, it
wakes the app to play live audio, and the receive half does not yet
play anything. Suppressing the alert for a channel-holding device
therefore bought pure silence — strictly worse than the banner it
replaced. Fixed in `afa81d1` with a named constant
(`Push.ChannelSuppressesAlert = false`) carrying the reasoning and the
condition for flipping it: the same commit that lands the receive
half. The 4xx fallback is gated on the same constant, since with the
alert already sent a fallback would double-deliver.

Two operational notes from the session:

- `just server-package` builds the STUB transport; `server-install`
  defaults to `--iroh` and packaging by hand bypasses that. The server
  started, armed the pusher, and exited on `irohnet: stub build`.
  Always `just server-package --iroh` when staging by hand.
- `tools/ios-log.py` defaults to bundle id `net.wa-ta.ios` while the
  app is signed `net.wa-ta.hello`, so it needs
  `WATA_BUNDLE_ID=net.wa-ta.hello`. That is `IOS-ON-DEVICE`'s stale
  identity biting; worth resolving rather than remembering.
- A successful push logs NOTHING server-side, which made "did it even
  fire?" unanswerable from the log and sent this debugging through the
  journal instead. Worth a line.

### 2026-08-18 — tier 2 is DEVICE-PROVEN; and one hypothesis that did not survive

With `afa81d1` deployed, the owner confirmed a notification delivered
on the phone. Combined with the same run's log, tier 2 is now proven
end to end on hardware, including the two paths no gate here could
reach:

```
push: tap room=!Rz4sZuLaWNRB:localhost event=$HxvVjBevFjX5:localhost
screen conversation
```

— the banner tapped, and the tap opening the RIGHT conversation
through the same edge ENTER makes.

**A correction worth keeping, because the reasoning was wrong in an
instructive way.** The same log showed `audio: play failed:
FillComplexBuffer(decode)` shortly after a PTT session activation, and
it was tempting to read it as the audio-session handoff breaking
playback. The owner then confirmed playback works fine on the phone.
So that was a one-off — most likely a bad or partial media blob — and
the causal story was invented, not observed. It was flagged to the
implementer as CORRELATED-NOT-PROVEN when it was raised, and
retracted when the evidence arrived; had it been asserted, a session
lifecycle would now be carrying machinery justified by a coincidence.

What survives that retraction, on its own separate evidence:
**`ptt: audio session activated` arrived FIVE times with no matching
`deactivated`, ever.** That is an observation about the callback and
owes nothing to the playback error. So `macaudio`'s ownership flag can
latch for the life of the process, and the receive half must not rely
on a callback we have now watched fail to arrive. Sized to that, and
no longer justified by the decode line.

Remaining device legs are tier 3's alone: transmit from the system UI,
the session handoff under a real transmission, leave, restoration, and
the receive half once it lands.
