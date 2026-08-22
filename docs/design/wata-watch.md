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
fifteen of its Scala files are byte-identical to wata-ios's. What differs
is the entry point, the input, the LAYOUT (the stage is this panel's, not
the handset's — below), and three surfaces the watch does not have.

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

So the client is the retained UIKit stage: `glyphs.scala` and
`pixels.scala` are wata-ios's files unchanged, `iosstage.scala`,
`display.scala` and `interptest.scala` are wata-ios's with the geometry
made the panel's (below), and `go-pkgs/iosui` is reused as-is — it is
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

## Input: intents, not key codes

`go-pkgs/watchshell/input.go` queues **intents** — what the person did, in
wata's terms (plan 0071's first step). It used to queue the BQ268's
`code*4 + phase` key codes, which meant a wrist gesture had to be described
as an arrow key on a walkie-talkie that is not present:

| gesture | intent |
|---|---|
| Digital Crown rotate | `Navigate(vertical, ±detents)` |
| swipe up / down | `Navigate(vertical, ∓3)` — a flick is harder than a nudge |
| tap | `Choose` |
| swipe right | `Back` |
| **long press** | **`TalkDown` / `TalkUp`** — hold-to-talk |
| `willActivate` / `didDeactivate` | `Wake` / `Sleep` |
| — | `Raw(code)`, the escape hatch; nothing on the watch emits one |

**`Navigate` carries an axis and a signed magnitude, not a direction.**
Magnitude, because plan 0070's scrolling is physical and a shell that can
only say "down was pressed" cannot express a flick; the units are cards, and
1.0 is one crown detent. Positive is toward the END of a list. The
horizontal axis is **reserved and unused** — plumbed so the integrator runs
per axis, with no gesture spent on it.

The integrator itself is plan 0071's step 2 and does not exist yet, so
`Intents.steps` (intents.scala) rounds a magnitude to whole rows and the
pump repeats the arrow that many times — the felt behaviour of a crown
detent is unchanged. The magnitude is **logged, not discarded**:
`input: navigate axis=v amount=3.00 steps=3`.

The seam passes scalars, so `NextIntent` pops a record and answers its
KIND, and `IntentAxis` / `IntentAmount` / `IntentCode` read the rest of the
one it popped. That is safe because there is exactly one consumer — the
frame pump — and it is what keeps a float64 magnitude from being rounded or
packed on the way across.

UIKit's recognizers, not WatchKit's: WatchKit's attach to a storyboard's
objects and there is no storyboard. That this can work rests on the app's
own raised window being hit-testable, which was probed — `hitTest:` at the
centre of the panel resolves to the app's container, not to WatchKit's
hierarchy underneath.

The crown is continuous, so rotation accumulates and reports one `Navigate`
per batch of whole detents crossed, carrying how many. `detentPerCard` is a
starting point, not a measured value; it wants tuning on a wrist. A
`UISwipeGestureRecognizer` reports THAT a flick happened and never how fast
— it has no velocity, unlike a pan — so a swipe's magnitude is the constant
`swipeFlick` until this moves to a pan recognizer reading `velocityInView:`
at release.

`ScriptIntents` replays `what@atMs[+holdMs]` onto the same queue
(`$WATA_WATCH_SCRIPT_INTENTS`, e.g. `talk@6000+1500`, `down:3@5300`) — a
harness seam, because the simulator can tap a coordinate but cannot
synthesize a long press, which would otherwise leave the whole send half
ungateable. `holdMs` means something only for `talk`; every other intent is
a single edge.

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

## Layout: the stage is the screen's, not the handset's

The stage used to be wata's 160x128 grid at an integer scale, which is the
handset's panel worn on a wrist: at scale 2 the footer legend fell off the
right-hand edge (`PTT talk` with it), at scale 1 half the panel was black
and the type was 6.8 points. **Nothing in this client is a size any more**
(plan 0071 step 2): `metrics.scala` asks the surface what it is, and every
number is derived from the answer.

```
metrics: wrist 208.00x248.00pt @2.00 inset=53.00/36.00 grid=26x12
         cell=8.00x16.00 type=12.50/10.75/10.25
```

That line is printed once at launch, and it is the whole layout. What it
says, and where each number comes from:

- **The panel** is `UIScreen`'s, through `watchshell.ContainerBounds` —
  208x248 points is an OBSERVATION. A different watch is a different
  observation and no edit.
- **The columns come from the LINE, not the panel.** wata's screens are
  written to 26 columns; that is a property of the text, so the cell width
  is the panel divided by it. A wider cell (bigger type) would truncate
  every footer legend, which is the cropping this removed. Plan 0070's
  rolodex changes the line, and this with it.
- **The rows come from the panel**: a row is two cell-widths tall — more
  air than the fb's 6x8 cell, which is what a short line read at arm's
  length wants — so the usable height divided by that is how many there
  are. Twelve here, where the handset has fifteen.
- **Usable height excludes the TOP safe inset** (53 points on a Series 10):
  watchOS draws its time overlay in that band, over the app, and a status
  row underneath it is unreadable. The bottom inset is deliberately NOT
  excluded — it is a cosmetic corner margin, and the footer sitting in it
  costs a descender where excluding it would cost a whole row.
- **Type is by ROLE** — name / caption / status — resolved against the
  metrics, never one global point size. Each role takes a share of the
  largest size the cell can hold, so the names read louder than the legend
  under them.

`IosStage` maps the semantic space (still the fb's 6x8 glyph cell, which is
what the bodies address) onto that grid with **the two axes scaling
independently**: a wrist's cell is 8.0 x 16.0 points. Two consequences
worth knowing before touching it:

- **Only the ROOT view carries the top inset.** Group containers span the
  grid at origin zero — a subview's frame is relative to its parent, so a
  group repeating the inset pushes its children down the panel once per
  level of nesting. That is what the first run after this change did: the
  title landed 3 x 53 points down, and every screen looked empty.
- **A label whose text is exactly as wide as its frame TRUNCATES.** The
  frame is one grid cell per character, so sizing the font by the nominal
  0.6em advance renders `Family` as `Fami…` and `Bob` as `B…`. The measured
  advance on watchOS 26 is 0.616em and `TypeRoles.ADVANCE_EM` is 0.64, a
  margin on top of it.

The bodies are still wata-fb's grid-shaped screens; they read the grid
through `Display`/`Font`, which on this client answer from the metrics
rather than from constants (`display.scala`, the watch's own copy). Plan
0070's rolodex is what replaces the screens themselves.

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
