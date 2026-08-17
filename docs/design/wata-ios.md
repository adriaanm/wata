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
  prompt lands at audio-thread start, not mid-PTT-press. A denied mic
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
