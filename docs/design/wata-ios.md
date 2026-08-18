# wata-ios — the iOS client (simulator-first)

wata-ios is wata-mac's architecture with UIKit under it (plan 0044):
Sgola bodies over a facade on generated UIKit bindings
(`go-pkgs/appleptt/uikit`), a thin Go shell owning the platform entry
point (`go-pkgs/iosshell`), glue for what a facade cannot say
(`go-pkgs/iosui`), and the same wataui dep. **Read
[wata-mac.md](wata-mac.md) first** — every mechanism described there
(the retained interpreter's element table and patch walk, the threading
rule, the flat-patch-list frame handoff, the purity rule, the pump's
frame shape and its one-drain rule) holds here unchanged; this doc
records only what UIKit changes. The module holds the retained
interpreter (`iosstage.scala`) with its `interptest` argv mode, and
wata-fb's screen bodies on it (plan 0044 stage 4): `applets.scala`,
`display.scala`, `input.scala`, `netstatus.scala`, `paint.scala` and
`syscall.scala` are COPIES of wata-fb's files (honest duplication per
the plan's out-of-scope ruling — wata-mac symlinks them, wata-ios does
not; a re-copy that stops compiling against `stubs.scala` is the
tripwire keeping the duplicate in step), and `main.scala` is wata-mac's
pump with the mac-only seams swapped.

## What differs from the mac stage

- **Geometry: no y flip.** UIKit's y axis points down, like the stage's
  own convention, so a semantic rect scales straight into a frame.
  `expectFrame` in interptest pins this independently, and the render
  probes' red-top/blue-bottom pin the row orientation end to end.
- **Elements.** VText/VGlyph are UILabels (text/font/colour bind
  DIRECTLY through the facade — no AppKit label glue); VRect is a plain
  background-filled UIView (no NSBox dance: a UIView's background is
  drawn by its layer, which `renderInContext:` sees); VImage is a
  UIImageView whose default scale-to-fill is exact because the pixels
  arrive pre-scaled.
- **Replace.** UIKit has no `replaceSubview:with:`; the interpreter's
  replace is `insertSubviewBelowSubview:` + `removeFromSuperview`
  (`IosStage.replaceIn`).
- **Font retention.** iOS has no retain glue, so the stage stores the
  POINT SIZE and mints the (autoreleased) font inside each label call —
  the label retains what it is handed, nothing outlives its pool. The
  mac stage instead retains one NSFont via `nativeui.retainFont`.
- **Colour assertions render.** iOS UIColor has no component reads
  (`getRed:…` takes CGFloat out-params, refused by bindgen), so every
  "this view really shows colour X" check goes through
  `iosui.RenderPixel` — an offscreen layer render needing no window.
- **The shell's inversion.** UIKit builds UI inside its own launch
  callback and `UIApplicationMain` never returns: main goes
  `iosshell.start()` → `iosshell.runApp(ready)`, and everything after
  (stage creation, adoptRoot, the keypad, forking the pump) runs inside
  `ready`, a `go.callback` trampoline invoked on the main thread once
  the window is key and visible. `ready` also paints the boot screen
  inline before the session goroutine starts, so the connect wait runs
  behind a painted "starting up..." frame, not a blank window. There is
  no headless mode; the harness always runs the real simulator.

## The seams stage 4 swaps (vs wata-mac's client)

- **Input is the shell's touch keypad** (`iosshell/keypad.go`): one
  UIButton per key of the handset's model — ▲ ▼ ◀ ▶ / BACK OK PTT —
  target-action on a synthesized ObjC class, queueing `code*4 + phase`
  in `IosKeys` codes (ioskeys.scala is the contract). Unlike macshell
  there is NO raw-platform-code translation table: the buttons ARE the
  model. PTT's press/release are the button's touch-down and
  touch-up/cancel edges, which is what makes hold-to-talk work. Real
  interaction design is `ADULT-UX-NONHAPPY`'s; these buttons make the
  shared applet logic drivable at all.
- **Audio is real** (plan 0063): wata-fb's own `audiothread.scala`
  symlinked in — the same seam wata-mac uses — over an `audio.scala`
  facade bound to `go-pkgs/macaudio` (facade-check holds all three
  declarations identical). macaudio was iOS-clean by construction; the
  one iOS-only piece is its `session_ios.go`: AVAudioSession set to
  PlayAndRecord (DefaultToSpeaker — walkie-talkie audio belongs on the
  speaker — plus AllowBluetooth) and activated before the engine is
  built, with the record-permission ask fired there so the system
  prompt lands at audio-thread start, not mid-PTT-press; and its
  `prepareInput`, which touches the engine's input node before the
  first start — on iOS the IO unit is configured lazily, and an engine
  started input-node-less runs output-only, the input node reporting a
  0 Hz format on every capture open (found on hardware; macOS's HAL
  vends the format regardless, so the mac never saw it). A denied mic
  still surfaces as MIC FAILED per command, never a wedge. Foreground
  only: the PushToTalk framework (background transmit) is a follow-up
  plan.
- **The stores are sandbox files** (`config.scala`): wata-mac's three
  stores with the keychain swapped for `secrets.json` (0600) in the
  app's own data container — the honest simulator-grade store; the iOS
  keychain crossing is the signed-device legs' work. Env overrides:
  `WATA_IOS_CONFIG`/`WATA_IOS_OUTBOX`; login from
  `WATA_IOS_HS`/`WATA_IOS_USER`/`WATA_IOS_PASS`.
- **Dropped mac chrome**: window title, Dock badge, banners, the login
  sheet, the Devices window, headless mode, the leak-bisect arms. The
  arrival decision still prints (`notify: play|noted …` — `noted`
  because there is no banner surface yet); a rejected session ends the
  pump with a printed `rejected` (the ask-again arc is
  `ADULT-UX-NONHAPPY`'s iOS half).
- **The iroh transport + enrolment are real** (plan 0062): `Enrol`
  (enrol.scala) is wata-fb's enrolment with the QR replaced by the app
  itself bouncing to Safari. The fresh-install arc: `setupWait` paints
  the setup screen and opens `<adminUrl>/admin`; the page's "Add this
  phone" link comes back as `wata://configure?peer&relay&admin&addrs`
  (iosshell's URL delegate queues it, `takeURL` drains — the scheme is
  registered by both simrun's bundler and ios-device.py); the config
  is merge-written to the sandbox `iroh.json` (a minted secretKey
  survives) and the session starts on the iroh transport
  (`IosCaps.httpDo` swaps clients when `Enrol.configured()`). A
  not-allowlisted refusal paints the shared enrol screen, whose first
  frame announces over plain TCP and THEN bounces to
  `/admin#enroll/<id>/<nonce>` — strictly after the announce lands
  (`openAfterAnnounce`), because opening Safari suspends this app and
  freezes the announce mid-POST. Approval allowlists the node and binds
  a passwordless account; login is the iroh connection proving its
  identity (wataclient's device-login). A configure link arriving
  mid-session (`pollUrl`) restarts the session onto the new server.
- **The assertable surface is printed lines + the render probe**:
  `ready <userId>`, `screen boot|contacts|conversation` per change, and
  `paint <screen> lit=<n>/<m>` — a main-queue trampoline renders the
  live stage offscreen after the frame that changed the screen applied
  (one serial queue, publish order) and counts non-black pixels. That
  is the "did UIKit really paint it" evidence a windowless-assertion
  harness needs, since there is no TreeDump and no headless REPL.

## Open work

Items with a `[KEY]` tag have a line in `TODO.jsonl`; grep the key here
for the body.

> `[IOS-INBOUND-MESSAGES]` **Open: the phone sends but does not
> receive.** Owner report, 2026-08-18, on the phone: recording and
> sending a voice message works end to end (plan 0063's roundtrip —
> the BQ268 receives and plays it), but a message sent TO the phone
> never surfaces there. Unknown at filing which half is broken: the
> sync arriving at all over iroh, the arrival decision
> (`notify: play|noted` — iOS prints `noted` because there is no
> banner surface, so a message could be arriving and going nowhere
> visible), the conversation view not repainting, or playback. The
> first evidence is the persistent log (`just ios-log`, plan 0064):
> it carries the session's own lines and every audio failure with its
> cause. This blocks `[IOS-PUSH-TO-TALK]` — a walkie-talkie that
> cannot hear is not worth backgrounding — and is the owner's next
> priority.

> `[IOS-PUSH-TO-TALK]` **Open: the PushToTalk framework, background
> transmit, and the system PTT UI.** Plan 0063 deliberately shipped
> foreground-only voice; this is the follow-up it names. The hardware
> risk is already retired — the PTT hello (`just ptt-hello`,
> `tools/bindgen/hello/`) drove Apple's framework from Go on the
> phone 2026-08-17: channel manager, join, ephemeral push token,
> transmit, and the system handing over an activated
> `AVAudioSession`, every line a delegate callback arriving in Go
> through `go-pkgs/appleptt`. What is left is product work in two
> halves, and only the first is self-contained: (1) TRANSMIT — join
> the family channel while the app is up, let the system talk button
> (Dynamic Island, lock screen) drive the same record/send arc the
> on-screen PTT button drives, and let macaudio yield session
> ownership to the framework for the duration of a transmission
> (`session_ios.go` activates its own session today, which plan 0008
> records as incompatible with PTT); plus the `push-to-talk` and
> `audio` background modes and the `com.apple.developer.push-to-talk`
> + `aps-environment` entitlements in `tools/ios-device.py` (the sign
> stage's comment already promises them). (2) RECEIVE while
> backgrounded — an APNs `pushtotalk` push to the per-join ephemeral
> channel token, which needs a server-side APNs pusher and a
> registration endpoint (plan 0008's prerequisites) and is a plan of
> its own. **Owner ruling 2026-08-18 on APNs and self-hosting:** an
> APNs credential is team-owned by construction — a `.p8` token key is
> scoped to the whole team, the older `.p12` is per-App-ID but still
> issued to the team, and a push is accepted only from credentials
> authorized for the `apns-topic`'s bundle id. Apple offers no
> delegation, so a family server cannot be handed a narrowed key.
> That is fine, because a self-hoster already needs their own
> developer account to sideload the app at all: self-hosters bring
> their own team, bundle id and key. The hosted tier (paid, v2 — our
> server, our key, and no iroh) is the answer for everyone else. The
> remaining combination — our App Store build pointed at someone
> else's server — is **not a supported configuration** (owner, same
> ruling): that server could never push without our key, and the only
> ways to make it work are a push relay we operate or handing out a
> key, neither of which we want. Self-host means building it
> yourself; using our build means using our service. Whether half 1 alone yields background RECEIVE over our
> own iroh transport turns on whether a joined channel keeps the app
> running rather than suspended — an Apple-docs claim to verify, not
> assume, before the plan commits to it. Blocked on
> `[IOS-INBOUND-MESSAGES]`.

## The persistent log (plan 0064)

On a physical iPhone the app's stdout/stderr are visible only through
a tethered `devicectl … launch --console`, so the first thing `main`
does — before any output — is `go.iosshell.teeLog(FbConfig.logPath())`:
`iosshell.TeeLog` (log.go) dup2's a pipe under fds 1 and 2 and copies
every chunk to BOTH the original console (tethered launches and the
simulator harnesses keep their output unchanged) and
`Documents/wata.log` in the app's sandbox — truncated at each launch,
growth capped at 4 MiB per run (past the cap the console copy
continues, the file stops). Raw fd work is not expressible in the
dialect, so the mechanism is Go; the facade returns "" or the error
text, and a failed tee is printed and ignored. Pull the log off the
phone with `just ios-log` (tools/ios-log.py): `devicectl device copy
from --domain-type appDataContainer --domain-identifier
$WATA_BUNDLE_ID --source Documents/wata.log`, device from
`--device`/`$WATA_DEVICE` or the single attached iPhone. Audio
failures are readable there because the shared audio thread's
non-throws boundaries (wata-fb's audiothread.scala — the handset and
the mac print the same lines) name their cause:
`audio: <record|play|playback|echo record|echo play> failed: <err>`.

## The gates

- `just ios-build-check` — vet + build of uikit/iosui/iosshell, the
  hello, and (when emitted) wata-ios's generated module for
  GOOS=ios/arm64 against the iphonesimulator sysroot.
- `just ios-interptest` (tools/ios-interptest.py) — the stage-3 gate:
  `sgo build` emits (and native-builds — the type gate), the emitted
  module cross-builds for the simulator (tools/iosenv.py), is bundled by
  hand and launched on the shared device (tools/simrun.py, custom device
  set `~/.wata-simdevices`) with argv `interptest`. The verdict is the
  app's printed `interptest: PASS` — launchd owns the process exit, so
  the line IS the exit code. The suite is wata-mac's interptest
  transliterated; dropped as AppKit-specific: keyTranslation (MacKeys
  maps raw macOS virtual key codes; stage 4's touch mapping brings its
  own tests). A cold-booted fresh runtime can blow the first launch's
  watchdog with zero output — the harness retries a zero-line run once
  on the warm device before failing (plan 0044's recorded gotcha).

- `just ios-smoke` (tools/ios-smoke.py) — the stage-4 gate: one fresh
  wata-server on a random localhost port (the simulator shares the
  host's loopback), the app launched on the shared device with alice's
  credentials in the environment (`SIMCTL_CHILD_*` is how simctl hands
  env vars through), asserting the printed lines: the boot screen
  painted before the session connected, `ready @alice:localhost`, the
  contact list painted. Harness gotcha, recorded twice now: simrun's
  `expect` regexes are searched against the JOINED output without
  MULTILINE, and console-pty lines can carry a stray `\r` — never
  anchor them with `^`/`$`.

- `just ios-enroll-smoke` (tools/ios-enroll-smoke.py) — plan 0062's
  gate: one iroh-mode wata-server on the host, the app from a FRESH
  sandbox with no credentials, the harness playing the admin page
  (`simctl openurl` with the configure link built from
  `GET /_wata/v1/enroll/server`) and the approving owner (poll pending,
  approve as `phone`). Asserts, off the printed lines: the setup
  screen, the configure claim, the announce landing 200, the restart,
  `ready @phone:` (device-login — no password exists by construction),
  the painted contacts. Both this gate and ios-smoke run the app with
  `WATA_MAC_AUDIO=fake` (macaudio's tone/clock hardware ends — the env
  name is the package's, kept for facade parity): the REAL audio
  thread links, starts and pumps, and no mic-permission prompt can
  block a harness. Three simulator facts it depends on, learned
  the hard way: (1) a custom scheme opened from outside shows a
  consent alert no harness can tap — pre-seed the per-device
  `com.apple.launchservices.schemeapproval.plist`
  (simrun.approve_scheme; lsd reads it at boot, so a fresh entry needs
  a device reboot); (2) `simctl openurl` delivers WITHOUT activating
  the target — the backgrounded app is suspended (Go runtime frozen)
  seconds later, so every delivery is followed by a bare
  `simctl launch`, which foregrounds an already-running app without
  restarting it; (3) `login failed` is a mid-arc line here (the
  pre-approve session's honest verdict), not a terminal marker.

None of the gates are in ci (Xcode-gated), the same posture as
nativeui-tests/mac-smoke.
