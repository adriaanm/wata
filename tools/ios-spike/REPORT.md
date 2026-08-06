# iOS architecture derisk report — one pure-Go binary driving UIKit

Queue item IOS-CLIENT-ASSEMBLY, run 2026-08-06 on macOS arm64, Xcode 26.2,
Go 1.26.5, iOS simulator runtime 26.3 (iPhone 17), purego v0.11.0-alpha.8.
Rerun: `just ios-spike` (unattended, ~90s); `just ios-spike --only run`.

## The answer

**(A) works. A single pure-Go binary — no Swift, no Objective-C source, no
Xcode project, no gomobile — is an iOS app, owns `UIApplicationMain`, drives
UIKit through purego/objc, and receives UIKit callbacks on classes Go
synthesized at runtime.** It ran in the simulator and painted the screen:

```
spike: dlopen ok /System/Library/Frameworks/UIKit.framework/UIKit handle=0x10ed30
spike: getclass ok UIApplication UIWindow=true UILabel=true
spike: registerclass ok WataAppDelegate=0x600000c082d0
spike: calling UIApplicationMain
spike: callback didFinishLaunching app=<UIApplication: 0x103a14f20>
spike: screen bounds 402x874
spike: window visible
spike: callback layoutSubviews on WataSpikeView
spike: callback nstimer on the main runloop
spike: callback control-action from <UIButton: 0x10390b210; frame = (20 200; 200 44); ...>
spike: all checks passed
spike: screenshot 1206x2622, centre pixel rgb(0, 0, 255)
```

The three killers, in the order the brief posed them:

1. **dlopen inside an iOS app process: works.** `purego.Dlopen` of
   `/System/Library/Frameworks/UIKit.framework/UIKit` returns a live handle
   and `objc.GetClass` sees UIKit's classes immediately afterwards. This is
   the exact mechanism `go-pkgs/appleptt`'s generated `frameworks.go` uses.
2. **A pure-Go binary can be an iOS app: yes, and gomobile is not needed
   for it.** `gomobile build -target=ios` itself just runs `go build`
   (`x/mobile/cmd/gomobile/build_apple.go:goAppleBuild`) and hands the
   resulting executable to a generated Xcode project purely for packaging
   and signing. We do the packaging in 20 lines of `plistlib` +
   `codesign -s -`. The spike's `.app` is a hand-written `Info.plist`, the
   Go executable, and an ad-hoc signature; `simctl install` accepts it.
3. **objc -> Go callbacks survive, including class synthesis.** Two
   synthesized classes: `WataAppDelegate` (NSObject subclass, UIKit
   instantiates it from the name passed to `UIApplicationMain`) and
   `WataSpikeView` (UIView subclass whose `layoutSubviews` UIKit calls
   during the layout pass — the iOS analog of `nativeui`'s `WataKeyView`).
   Three distinct dispatch paths reached Go: the app-delegate protocol
   method, an `NSTimer` on the main runloop, and UIKit target-action
   (`sendActionsForControlEvents:` -> `-[WataAppDelegate wataButtonHit:]`).

**One change is required to adopt (A): repin purego from v0.10.2 to
v0.11.0-alpha.8 (or the eventual v0.11.0).** See friction #1 — v0.10.2
refuses every struct-by-value call under `GOOS=ios`, which means every
`CGRect`, i.e. every `initWithFrame:`/`bounds`/`frame` in the entire
binding surface. The bump is verified green for the existing macOS
packages (out-of-tree copy: `appleptt`, `nativeui`, `macshell` tests all
pass with it, including the `objcruntime`-tagged runtime tests).

**What is NOT proven: execution on real hardware.** No iPhone and no
signing identity here, so nothing ran on a device. The device slice
*compiles* (`platform IOS`, minos 17.0, 2,367,200 bytes) and section
"The device question" below argues the risk is low and — crucially — is
**not specific to (A)**: audio-spike already put purego on the critical
path for the iOS audio stack, so "does purego work on an iOS device" must
be answered whichever architecture we pick.

## What it took

- `tools/ios-spike/app/main.go` — ~230 lines of Go. It reuses the repo's own
  `go-pkgs/appleptt/objcrt` (NSString/GoString bridging) rather than a
  private copy: whether *that* compiles and runs under `GOOS=ios` is part of
  the question, and it does.
- `tools/ios-spike/spike.py` — three stages, `--only <stage>`:
  - `build` — `go build` for `GOOS=ios GOARCH=arm64 CGO_ENABLED=1` against
    the iphonesimulator sysroot (the same env gomobile's `appleEnv` sets),
    plus a compile-only iphoneos slice.
  - `bundle` — hand-rolled `.app` + `codesign --sign -`.
  - `run` — create/boot a device in the custom device set, install, launch
    with a console pty, assert ten expected lines, screenshot, and decode
    the centre pixel (stdlib zlib PNG decode; there is no Pillow here).
- No product-tree change. No Sgola involved, so no compiler tickets.

## Friction log (everything that did not work first try)

**1. purego v0.10.2 panics on every struct argument under `GOOS=ios`.** The
first run died at the first `CGRect`:

```
panic: purego: struct arguments are only supported on darwin and linux
  purego.ensureStructSupportedForRegisterFunc(...) func.go:491
  purego/objc.Send[...](...) objc_runtime_darwin.go:165
  main.didFinishLaunching  -- objc.Send[CGRect](screen, "bounds")
```

`func.go:490` reads `if runtime.GOOS != "darwin" && runtime.GOOS != "linux"`,
and under `GOOS=ios` `runtime.GOOS` is `"ios"`. **The panic is the lucky
part.** The same string comparison appears ~8 more times in
`struct_arm64.go` (`if runtime.GOOS != "darwin"` selecting the *non-Apple*
AAPCS struct-classification variant); had the gate not fired first, iOS
would have silently used the wrong ABI for struct passing and returning.
Anyone tempted to patch this locally must fix all of them, not just the
gate. Upstream already did exactly that: v0.11.0-alpha.8 introduces
`const isDarwin = runtime.GOOS == "darwin" || runtime.GOOS == "ios"`
(`func.go:544`) and routes every branch through it. With that version the
run is green on the first try.

**2. `CGO_ENABLED=1` is mandatory on iOS.** purego's `is_ios.go` fails the
build with `_PUREGO_REQUIRES_CGO_ON_IOS` under `CGO_ENABLED=0` — fakecgo
does not support iOS. Not a burden (the iOS Go port needs cgo anyway), but
it means the iOS client build always carries a clang/sysroot, unlike the
current macOS binaries.

**3. `gomobile build -target=ios` could not have produced this app anyway.**
It calls `detectTeamID()`, which shells out to `security find-certificate -c
"Apple Development"` and fails without a developer certificate, then builds
`-configuration Release` and moves `build/Release-iphoneos/main.app` — a
*device* build. There is no simulator path in it. Reading it was still the
key move: it showed that the Go->app-binary step is a plain `go build`, which
is what made hand-packaging obvious.

**4. `simctl launch --console-pty` blocks until the app exits**, so the
screenshot has to be taken while the app is still alive. The driver pumps
the pty on a thread, waits for `all checks passed` (or `FAIL`), screenshots,
then terminates. A 90s in-app watchdog goroutine exits non-zero so a stuck
runloop can never wedge the run.

**5. No ARC — Go must retain.** The `UIWindow` and `UIButton` are held in Go
package globals. Dropping the window reference after `didFinishLaunching`
returns is the classic blank-screen bug; this is the same discipline
`go-pkgs/macshell` already keeps.

**6. `UIApplicationMain(0, NULL, nil, @"WataAppDelegate")` is fine.** Passing
argc=0/argv=NULL saves marshalling a C argv from Go; UIKit does not care.
The principal class argument is nil (default `UIApplication`).

**7. The simulator device set.** Inherited wholesale from phone-spike:
every `simctl` call runs with `--set ~/.wata-simdevices` (override
`WATA_SIM_DEVSET`) because `~/Library/Developer` is a symlink to an external
volume and CoreSimulatorService takes an unpromptable TCC EPERM there. No
new friction — the precedent saved the whole afternoon.

**8. First boot of a fresh device costs ~30s** (data migration plugins).
`bootstatus -b` handles it; it dominates the wall time of the `run` stage.

## Numbers

| thing | value |
|-------|-------|
| Go source in the spike app | 230 lines, no Swift, no ObjC, no `import "C"` |
| simulator binary (`-ldflags=-w`) | 2,369,298 B |
| device binary (compiled, never run) | 2,367,200 B, `platform IOS`, minos 17.0 |
| `.app` bundle | 2.3 MB (binary + Info.plist + ad-hoc signature) |
| cross-build, cold cache | 2.7s (sim) + 0.4s (device slice) |
| cross-build, warm | 0.2s |
| device create + boot | ~31s (first boot; dominated by data migration) |
| launch -> all callbacks fired | **2.75s** (warm device), 10.9s on a cold one |
| dynamic libs the binary links | libSystem, CoreFoundation, libresolv — **not UIKit** |
| purego static callback slots | 2000 (`zcallback_arm64.s`), no runtime codegen |
| screenshot check | 1206x2622, centre pixel rgb(0,0,255) = the colour Go set |

For scale: phone-spike measured the *client core* at a 16 MB device slice
through gomobile. This spike's 2.3 MB is the UIKit-driving half alone; the
real app is roughly the sum, i.e. the 16-20 MB floor phone-spike already
called.

## The device question — the honest state

The simulator is a lenient host: its code-signing enforcement is not the
device's, its dyld is the host's, and `/System/Library/Frameworks/...` is
rerooted into the runtime image. **A green simulator result is not proof for
device.** What can be said, with what it rests on:

Reasons to expect device to work:

- **purego declares iOS a Tier 1 platform** (README, "iOS: amd64, arm64",
  footnote "requires CGO_ENABLED=1"). Tier 1 means critical bugs block
  releases. That is the vendor's claim, not our measurement, but the iOS
  support is deliberate, not incidental — `is_ios.go`, `cgo.go`, and the
  `isDarwin` constant all exist specifically for it.
- **No runtime code generation anywhere in the callback path.** This was the
  risk I expected to kill (A) on device: iOS forbids `mmap(PROT_EXEC)`
  without the dynamic-codesigning entitlement. purego does not need it —
  `zcallback_arm64.s` is a *statically assembled* table of 2000
  `MOVD $n, R12; B callbackasm1` slots, and `grep -rn 'mmap\|PROT_EXEC'`
  over the package finds nothing. A synthesized ObjC method's IMP is the
  address of one of those static slots. So class synthesis and callbacks
  need no JIT, no W^X flip, no entitlement. (Cap worth knowing: **2000 live
  callbacks per process**.)
- **`objc_allocateClassPair` and friends are public ObjC runtime API on
  iOS**, in the same `libobjc.A.dylib`. Nothing in `objc.RegisterClass`
  is macOS-specific.
- **`dlopen` is a public POSIX API on iOS** and the framework paths resolve
  out of the dyld shared cache (the files do not exist on disk on device;
  dyld special-cases cache paths). What iOS restricts is loading code that
  is *not* part of the app bundle or the system — a system framework is
  neither. Apps that `dlopen` system frameworks are commonplace.
- **The "Go binary as the app executable" half is already device-proven by
  gomobile**: shipped iOS apps built with `gomobile build` are exactly a
  `go build` executable inside a `.app`. Our packaging differs only in who
  writes the plist and the signature.
- The device slice compiles clean with the identical source and tags.

What could still bite, and is genuinely untested:

- `dlopen` returning NULL under a distribution-signed (App Store /
  TestFlight) binary — library validation interacts with dlopen of
  *unsigned or foreign-team* code; a shared-cache system framework should be
  exempt, but I have not observed it.
- App Review's opinion of `dlopen` + `objc_allocateClassPair`. This is a
  policy question, not a technical one, and it has no bearing on the
  sideload/TestFlight path wata would most likely take first.
- Anything about launch-time watchdogs, memory limits, or scene lifecycle on
  a real device.

**The decisive framing:** this risk is already on the critical path.
`tools/audio-spike/REPORT.md` chose purego + AudioToolbox + generated
`avfaudio` bindings as the Apple audio architecture, with no cgo fallback.
Under architecture (B) the Go side is still Go running purego inside an iOS
process — just loaded as a gomobile framework rather than as the main
executable — and `purego.Dlopen`/`objc.RegisterClass` still have to work
there for audio to work at all. **(A) therefore adds no new device-side
purego risk over (B).** It only adds the "Go owns `UIApplicationMain`"
element, which is precisely the part gomobile already ships on devices.

## Open questions (marked, not chased)

- **Real touch delivery.** Proven: `layoutSubviews` (UIKit-driven) and
  target-action dispatch. Not proven: `touchesBegan:withEvent:` on a
  synthesized view from an actual finger — `simctl` has no touch injection,
  so this needs either XCUITest or a device. Note the shape risk:
  `touchesBegan:withEvent:` takes objects only (fine), but any override
  taking a struct by value (`drawRect:`) hits the decomposed-trampoline rule
  `docs/design/bindgen.md` pins.
- **UIScene lifecycle.** The spike uses the classic
  `UIWindow` + `makeKeyAndVisible` path with no `UIApplicationSceneManifest`,
  which iOS still honours. Whether we ever want scenes (multi-window, iPad)
  is a product question.
- **Text and drawing.** The spike used one `UILabel`. Mapping wataui's view
  algebra onto UIKit — the actual port work — is untouched; only the
  mechanism is answered here.
- **Keyboard/PTT input model.** `nativeui`'s key view has no iOS analog
  (there is no hardware keypad); the iOS input model is touch, and the
  PushToTalk framework needs a paid-account entitlement.
- **`AVAudioSession`** activation before the audio engine, flagged already by
  audio-spike, still unexercised.
- **Binary size and launch time with the real client linked** (net/http,
  crypto/tls, wataclient): extrapolated, not measured.

## Recommendation

**Go with (A): the iOS client is a UIKit backend variant beside the AppKit
one, in one pure-Go binary.**

Reasoning:

1. Every mechanism (A) depends on is now observed working in an iOS process:
   framework dlopen, class synthesis, framework-driven callbacks, struct
   ABI, the runloop handoff, the repo's own `objcrt`.
2. It preserves the architecture that already exists. `go-pkgs/nativeui` is a
   retained-tree interpreter for wataui patches; a `uikit` sibling of the
   generated `appkit` package plus a UIKit-flavoured interpreter is
   incremental work against a proven shape. (B) means a second UI
   implementation in Swift, driven across gobind's tiny type surface —
   phone-spike friction #3 already showed the domain cannot cross that
   boundary without being flattened to strings or bytes, and driving a
   *differ* across it would mean serializing every patch.
3. It keeps the toolchain line of plans 0026/0023: CLI-only, no Xcode
   project, no gomobile owning the build. The packaging is a 40-line script.
4. `tools/bindgen` already has an `iphonesimulator`/`arm64-apple-ios26.2`
   target (the `appleptt` PushToTalk package), so generating `uikit` is a
   configuration entry, not new machinery.
5. The one prerequisite — the purego repin — is small, already upstream, and
   verified non-regressive against the existing macOS packages here.

Sequence I would write into the plan: (i) repin purego to v0.11.0-alpha.8 and
run the full `just ci` plus the mac smoke; (ii) generate a `uikit` bindgen
target; (iii) a `wata-ios` app that is `wata-mac`'s bodies on a UIKit
interpreter, with the simulator smoke modelled on `tools/mac-smoke.py`;
(iv) **before any hardware-dependent work, get a one-page hello onto a real
iPhone** — a free personal-team provisioning profile is enough, no paid
program needed, only a phone. That is the single check that closes the last
open question, and it should happen early: (B) stays a cheap fallback only
until UI code has been written against (A).

Fallback trigger, stated so it is falsifiable: if `purego.Dlopen` of a
system framework returns NULL, or a synthesized class's IMP faults, on a
signed device build. That same failure would also break the audio-spike
architecture, so it would be a bigger reversal than a UI-layer choice —
which is itself an argument for testing it on hardware soon, independently
of the iOS client.
