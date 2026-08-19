# wata-watch — the standalone Apple Watch client

How the watch client is built today. The decisions and the probes that
established them are in [plan 0069](../plans/0069-wata-on-the-watch.md);
this describes the result.

## What it is

A watch-only app (no paired iOS app, no watch extension of anything) that
runs the whole wata client: login, sync, receiving voice messages, and
sending them from a held talk button. Written in Sgola, compiled to Go,
linked into a watchOS arm64 binary. No Swift, no storyboard, no Xcode
project.

Its architecture is **wata-ios's**, and that is not a family resemblance:
fifteen of its Scala files are wata-ios's, copied unchanged. What differs
is the entry point, the input, and three surfaces the watch does not have.

## Why Go reaches watchOS at all

Go has no `GOOS=watchos` and will not get one (golang/go#60180, closed
frozen). But a cgo build links externally through clang, and it is clang —
not the Go linker — that stamps `LC_BUILD_VERSION`. So the watch builds
with `GOOS=ios GOARCH=arm64` against the **watchOS** sysroot, and every
object comes out stamped `platform WATCHOS`. `tools/iosenv.py` is the whole
seam: `go_env("watchos")` / `go_env("watchsimulator")`.

**arm64 only, forever.** Go has no 32-bit ARM Darwin target, so every watch
before the Series 9 — all arm64_32 — is out of reach. That is why
`MIN_WATCHOS` is 26.0: not a compatibility choice, the first watchOS
release running full arm64.

`tools/watch-device.py --only build` re-checks the stamp with `vtool` on
every build rather than trusting it.

## UIKit exists here

The watch SDK's headers mark `UIView`, `UIWindow`, `UILabel`,
`UIImageView` and friends `API_UNAVAILABLE(watchos)`. The **runtime** has
them and they work — probed class by class before anything was built on
them (22 of 23), and then proven by running wata-ios's entire stage test
suite on the watch (`just watch-interptest`).

So the client is the retained UIKit stage: `iosstage.scala`,
`display.scala`, `glyphs.scala`, `pixels.scala` and `interptest.scala` are
wata-ios's files, and `go-pkgs/iosui` is reused unchanged — it is
libdispatch, libobjc and CoreGraphics, none of which is iOS-specific.

## The entry point: `go-pkgs/watchshell`

watchOS refuses `UIApplicationMain` — it blocks forever and never delivers
a launch callback. The shell therefore owns:

- **`WKApplicationMain`** with a Go-synthesized `WKExtensionDelegate`. The
  protocol must be attached BY NAME (`objc.GetProtocol`) or WatchKit aborts
  saying the class does not conform.
- **`applicationRootInterfaceControllerClass`**, which is what removes the
  storyboard entirely. Without it WatchKit aborts asking for an
  `Interface.plist`. (A hand-written storyboard route was built first, worked,
  and was deleted as strictly worse.)
- **A window joined to watchOS's own scene** (`setWindowScene:`) and raised
  above WatchKit's (`setWindowLevel:`). A window with no scene is never
  composited, and a scene-joined window at WatchKit's level loses to it —
  both produce a black panel while every offscreen probe passes.

The inversion above it is iosshell's exactly: `start()` → `runApp(ready)`,
and everything the app builds happens inside `ready`.

## Input: the watch's gestures, the phone's key codes

`go-pkgs/watchshell/input.go` queues `code*4 + phase` on iosshell's
contract, so the shared applets never learn the watch has no keypad:

| gesture | key |
|---|---|
| Digital Crown rotate | UP / DOWN |
| tap | ENTER |
| swipe right | BACK |
| **long press** | **PTT press and release edges** — hold-to-talk |
| swipe up / down | UP / DOWN |

UIKit's recognizers, not WatchKit's: WatchKit's attach to a storyboard's
objects and there is no storyboard. That this can work rests on the app's
own raised window being hit-testable, which was probed — `hitTest:` at the
centre of the panel resolves to the app's container, not to WatchKit's
hierarchy underneath.

The crown is continuous, so rotation accumulates and emits one arrow per
`detentPerKey` of travel. That constant is a starting point, not a measured
value; it wants tuning on a wrist.

`ScriptKeys` replays `code@atMs+holdMs` onto the same queue — a harness
seam, because the simulator can tap a coordinate but cannot synthesize a
long press, which would otherwise leave the whole send half ungateable.

## What the watch does NOT have

- **APNs.** No push registration, no push step. No watch story yet.
- **PushToTalk.** An iOS framework with no watch equivalent — so a press
  talks to the app's own audio thread directly. This is the *simpler* arc
  the phone cannot use: a joined phone has no audio session of its own
  (plan 0066). The watch is missing a problem, not a feature.
- **A browser.** `TakeURL` always answers `""` and `OpenURL` logs and
  ignores, so `enrol.scala`'s configure-link half is inert. `Enrol` still
  reads the config that enrollment LEFT, which is the half that matters:
  the watch is enrolled from the companion app, and its independent job is
  sending and receiving.

## Audio

`go-pkgs/macaudio` runs unmodified — engine, playback, real microphone
capture, both codec ends. Its `session_ios.go` is `//go:build ios` and the
watch rides `GOOS=ios`, so the AVAudioSession work comes along. Both audio
modules cross-build for the device sysroot, including the cgo one.

**The microphone permission has three behaviours and two look like
crashes.** With no `NSMicrophoneUsageDescription` the process SIGABRTs
inside `SetupMixer`, with a crash report pointing nowhere near audio. With
the key but no grant, the system puts an alert on the watch and the app
BLOCKS on it — a hang, and a crash after a watchdog. Only a screenshot
separates them. Every bundle here carries the usage string; harnesses
pre-grant with `simctl privacy grant microphone`. On a wrist nobody
pre-grants: the alert on first press is expected.

## Layout is the open gap

wata's grid is 160x128, wider than tall. The watch panel is 208x248,
taller than wide. At the phone's scale of 2 the stage is 320x256 and the
panel crops the footer's right-hand end — where `PTT talk` lives — so the
watch runs at **scale 1** (`WATA_WATCH_SCALE`), where nothing is cropped
and about half the panel's height is unused.

Neither is right, and the fix is not a scale factor: the watch wants a
layout of its own, with rows readable at arm's length. `[WATCH-LAYOUT]`

## Gates

| recipe | what it proves |
|---|---|
| `just watch-spike` | the platform probes: classes, lifecycle, window, input reachability, network, audio |
| `just watch-hello` | `watchshell` + `iosui` drive UIKit on watchOS, through the product packages |
| `just watch-interptest` | wata-ios's whole stage suite passes on watchOS |
| `just watch-e2e` | the whole client: login, sync, an arrival, and a SEND the server confirms |
| `just watch-device` | build, sign, install on real hardware |

None is in `ci` — they need Xcode and a watchOS simulator runtime
(mac-smoke's posture).

`watch-e2e` ends by having bob snapshot the family room server-side and
find both messages, because `send: complete` is only the app's word for it.

## Hardware

`just watch-device` build and bundle are verified; sign and install need
two things only the owner can provide, and both are checked with the
portal/setup steps printed rather than a codesign message that names
neither:

1. **A watchOS App Development profile.** Profiles carry a `Platform` list
   and an iOS profile cannot sign a watch app, whatever its bundle id.
2. **The watch in Developer Mode, paired to Xcode**, so it appears in
   `xcrun devicectl list devices`.

Nothing has run on a real watch yet. The open hardware questions are
whether a real long press is delivered (`WATCH-INPUT-DELIVERY`) and the
real-audio round trip (`WATCH-AUDIO`).
