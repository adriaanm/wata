# wata-ios — the iOS client (simulator-first)

wata-ios is wata-mac's architecture with UIKit under it (plan 0044):
Sgola bodies over a facade on generated UIKit bindings
(`go-pkgs/appleptt/uikit`), a thin Go shell owning the platform entry
point (`go-pkgs/iosshell`), glue for what a facade cannot say
(`go-pkgs/iosui`), and the same wataui dep. **Read
[wata-mac.md](wata-mac.md) first** — every mechanism described there
(the retained interpreter's element table and patch walk, the threading
rule, the flat-patch-list frame handoff, the purity rule) holds here
unchanged; this doc records only what UIKit changes. Today the module
holds the retained interpreter (`iosstage.scala`) and its `interptest`
argv mode; the bodies cross in plan 0044 stage 4.

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
  (stage creation, adoptRoot, the pump — for now, the interptest) runs
  inside `ready`, a `go.callback` trampoline invoked on the main thread
  once the window is key and visible. There is no headless mode; the
  harness always runs the real simulator.

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

Neither gate is in ci (Xcode-gated), the same posture as
nativeui-tests/mac-smoke.
