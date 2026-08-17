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
- **Audio is stubbed off** (`audiostub.scala`): the same
  `AudioThread.mainLoop` seam as wata-fb's audio thread, answering
  `AcRecordStart` with `AeRecordingError` and `AcPlay` with
  `AePlaybackError` — honestly broken (MIC FAILED flashes), never
  wedged (the command channel is always drained). The real iOS audio
  stack is the PTT leg's, hardware-gated.
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
  `ADULT-UX-NONHAPPY`'s iOS half). No iroh seam yet:
  `FbCaps.transportUnavailable()` is constant false.
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

None of the gates are in ci (Xcode-gated), the same posture as
nativeui-tests/mac-smoke.
