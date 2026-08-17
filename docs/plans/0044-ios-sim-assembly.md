# 0044 — the iOS client, simulator first

Status: accepted

## Problem

The iOS client (queue `IOS-SIM-ASSEMBLY`, split out of
`IOS-CLIENT-ASSEMBLY`) has a decided architecture and no code.
`tools/ios-spike` proved architecture (A) end to end in the simulator —
one pure-Go binary owns `UIApplicationMain`, drives UIKit through
purego/objc, and receives callbacks on runtime-synthesized classes; no
Swift, no gomobile, no Xcode project — and the purego pin it required
(v0.11.0-alpha.8) already carries the mac client. The owner ruled
2026-08-09: build simulator-first to flush every bug the simulator can
flush, accepting the spike's argued-low risk that the signed-device
build later vetoes (A) (`IOS-HELLO-ON-PHONE` remains the arbiter of
that; developer-program enrollment is underway for the PTT leg).

The mac client is the template, and it is in the best shape it has ever
been: the retained interpreter is Sgola (`MacStage`, plan 0038), the
appkit facade uses value structs and defined scalars end to end, and
`go-pkgs/nativeui` is down to the glue a facade genuinely cannot say.
The iOS client is that architecture with UIKit under it.

## Decision

A new app module `wata-ios/`, structured exactly as `wata-mac/` is:
Sgola bodies over a facade on generated UIKit bindings, a thin Go shell
owning the platform entry point, and the same wataclient/wataui deps.
Port by transliteration of the proven files, not redesign — every place
the mac client solved a problem (threading rule, retention rule, the
frame hop, the glue categories), the iOS client copies the solution.

Simulator only: no signing beyond ad-hoc, no device slices in the
gates, audio stubbed (the real iOS audio stack is the PTT leg's,
hardware-gated). All `simctl` use goes through the custom device set
`~/.wata-simdevices` (this Mac's `~/Library/Developer` symlink breaks
the default set — see the 2026-08-05 learnings entry);
`tools/ios-spike/spike.py` is the working driver to generalize.

## What changes (file-level), in gate-able stages

**Stage 1 — the `uikit` bindgen target.** `tools/bindgen/bindgen.json`
grows a target `{package: uikit, out: go-pkgs/appleptt/uikit, sdk:
iphonesimulator, triple: arm64-apple-ios…-simulator, frameworks:
[UIKit]}` binding the interpreter's surface: `UIApplication`,
`UIWindow`, `UIScreen`, `UIView`, `UILabel`, `UIImageView`, `UIColor`,
`UIFont`, `UIImage`, structs `CGPoint/CGSize/CGRect`, and the enums
those classes' bound methods need. Regenerate (`just bindgen`, needs
Xcode), then a `just ios-build-check` recipe: `go vet` + `go build`
of the new package for `GOOS=ios GOARCH=arm64 CGO_ENABLED=1` against
the iphonesimulator sysroot (the spike's build env, productized).
Gate: ios-build-check green; bindgen-tests stay green.

**Stage 2 — the Go shell and glue.** `go-pkgs/iosshell` productizes
the spike's `main.go`: `UIApplicationMain` ownership, the synthesized
`WataAppDelegate`, window + root-view creation, `AdoptRoot`, `OnMain`
(the same registered-trampoline frame hop macshell has), and package
globals retaining what ARC would have (the spike's friction #5). An
`iosui` glue block mirrors `nativeui/glue.go`'s categories for UIKit:
cross-class cast facets, and the raw-RGBA-to-`UIImage` crossing
(CGBitmapContext path — the bindgen-refusal category). Everything else
is facade-bindable and stays out of Go.
Gate: a hello built on iosshell (not the spike) boots in the simulator
and paints — the spike's own assertions, re-taken through the product
packages. Taken green 2026-08-17 (runtime restored; `just ios-hello`
5.75s, `just ios-spike` 2.91s): the hello's band probes read
top=ff0000 / bottom=0000ff, so iosui's render-flip convention is
empirically right — no CTM change. First launch on a cold-booted fresh
runtime can exceed the 90s watchdog with zero markers and no crash
report; a rerun on the warm device is the discriminator before
suspecting the app.

**Stage 3 — the interpreter and its tests.** `wata-ios/` is born:
`uikit.scala` (the facade — value structs, defined scalars, bound-subset
handles, exactly appkit.scala's shapes), `iosstage.scala` (the
transliteration of `interp.scala`: same MacNode/patch walk, UILabel for
glyphs, UIView+backgroundColor for rects, UIImageView for images), and
an `interptest` argv mode running the same case tables wata-mac's does,
executed in the simulator by the stage-2 harness.
Gate: `just ios-interptest` green (all wata-mac interptest cases that
are not AppKit-specific).

**Stage 4 — the bodies and the smoke.** wata-mac's bodies
(main/applets/config/input-analog/netstatus/paint) cross with mac-only
seams swapped: macshell→iosshell, audio stubbed off, key input replaced
by touch/target-action where the applet expects keys (the minimal
viable input mapping; real interaction design is `ADULT-UX-NONHAPPY`).
`tools/ios-smoke.py` modelled on mac-smoke: boot the app in the
simulator against a live wata-server, assert the boot screen frames and
the login applet paints. `just ios-smoke`; not in ci (Xcode-gated),
same posture as nativeui-tests/mac-smoke.
Gate: ios-smoke green.

## Verification

Each stage's gate above, run foreground; plus the standing repo gate
(`just ci`) stays green throughout — nothing in this plan touches the
existing modules except bindgen.json (whose committed-fixture tests
pin the generator) and the justfile.

## Out of scope

- Device builds, signing identities, `IOS-HELLO-ON-PHONE` (its own
  queue item; the arbiter of (A) on hardware).
- PTT, APNs, real audio, packaging (`PTT-HELLO-HARDWARE` and later
  work; paid-program-gated).
- Interaction/visual design beyond the minimal input mapping
  (`ADULT-UX-NONHAPPY` owns that for both Apple clients).
- A shared-source scheme between wata-mac and wata-ios. Start with
  honest duplication of the bodies; fold into shared files only when
  both sides are green and the seams are visible (a follow-up plan if
  the duplication hurts).
