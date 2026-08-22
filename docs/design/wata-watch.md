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

Its architecture is **wata-ios's**, and that started as more than a family
resemblance: most of its Scala files were byte-identical to wata-ios's. What
differs is the entry point, the input, the LAYOUT (the stage is this panel's,
not the handset's — below), the CONTACT SCREEN (plan 0070's rolodex, which
this client got first), and three surfaces the watch does not have.

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

The magnitude reaches `wataui`'s motion integrator as an IMPULSE on the
rolodex — `Motion.impulse`, and the physics decides where that lands (below).
Two quick detents are twice the shove and coast twice as far with no
acceleration curve anywhere in this shell. Inside a CONVERSATION the screen
is still a grid list of message rows, so there the magnitude is rounded to
whole rows and the arrow repeated: `Intents.steps` is what does that, and it
is the only thing it still does.

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
         cell=8.00x16.00 type=12.50/10.75/10.25 label=45.76/21.84
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
- **Type is by ROLE** — `wataui`'s `TypeRole.DISPLAY` / `NAME` / `CAPTION` /
  `STATUS` — resolved against the metrics, never one global point size.
  There are **two resolutions**, because there are two kinds of text and
  they are sized by different things:
  - `TypeRoles.points` sizes GRID text (`VText`), which is a run of
    character cells: each role takes a share of the largest size the cell
    can hold, so the names read louder than the legend under them. A
    `VText` has nowhere to carry a role, so the ROW says which it is
    (`forRow`: row 0 is status, the last row the footer legend, the rest
    content). Every grid-shaped body here is header/list/footer, so that is
    exact rather than a guess — and it goes away with the last grid body,
    not with the element vocabulary.
  - `TypeRoles.labelPoints` sizes PIXEL-PLACED text (`VLabel`), which
    carries its own role: a fraction of the panel's SHORT side, so plan
    0070's display name is full-bleed rather than cell-sized, then clamped
    to the box the element was given so a label can never overflow itself.
    That is the `label=` pair in the metrics line — 45.8pt for a display
    name on a Series 10, 21.8pt for a name.

The stage draws the three rolodex elements for real: `VFill` is `VRect`'s
plain `UIView` plus a corner radius (through the layer — `cornerRadius` is
`CALayer`'s and bindgen does not allow that class, so `iosui.SetCornerRadius`
is the one glue call) and an alpha in the colour; `VLabel` is a `UILabel`
framed to its box at the role's size, with `textAlignment` doing the
centring (also glue: `NSTextAlignment` has no Go mapping). A `VLabel` takes
the PROPORTIONAL system face — grid text is monospaced because its columns
must line up with cells a body counted, but a name on a card is read, not
tabulated.

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

The remaining grid-shaped bodies are wata-fb's screens; they read the grid
through `Display`/`Font`, which on this client answer from the metrics
rather than from constants (`display.scala`, the watch's own copy). The
CONTACT screen is no longer one of them — see the rolodex below.

## The rolodex (`rolodex.scala`)

Plan 0070's contact screen, and the reason this client exists first. At rest
the panel is ONE CONTACT, full bleed, in that person's colour: their name as
large as the panel allows (`TypeRole.DISPLAY`), one line of state
(`CAPTION`), and the unheard count in a band across the top when there is
one. Navigating shrinks the card to reveal a vertical stack — five rows, the
centre one under a fixed band — and 450 ms after the last input it grows back
over whoever is centred.

**One interpolation does the whole thing**, which is why there is no "closed"
layout and "open" layout to keep in agreement. Card `i`, at scroll position
`p` and openness `o`:

```
full-bleed stacking:  y = (i - p) * H            h = H
the open stack:       y = centreY + (i-p)*rowH   h = rowH - GAP
what is drawn:        the two, lerped by o
```

At `o = 0` that is one card filling the panel with its neighbours exactly one
panel away — off screen, and already correctly placed the instant a flick
starts. At `o = 1` it is five rows with a 2px gutter. Cards outside the panel
are culled, so at rest the tree is a single card and the differ has nothing to
say frame after frame.

### Which card the talk button reaches

An open stack of same-sized rows says nothing about which one a held talk button
will send to, and on this screen that is the one thing that must never be
ambiguous — a held press sends a voice message to whoever is centred. So the
centre card is drawn differently, and the card it marks is `Motion.centre`
itself, the very value `Pump.stepMotion` writes into `selected` and `pttPress`
reads back. Not an approximation of it: the emphasis flips exactly where the
send target flips, at the half-card mark.

Three means at once, because none of them alone survives a small dim panel with
eight different hues on it:

- **Inset.** A neighbour loses another `padOpen` on each side and `QUIET_INSET`
  top and bottom, so the centre card is plainly the widest and tallest row.
- **Dim.** A neighbour's card is drawn at `QUIET_ALPHA` over the black panel,
  which makes the centre card the brightest thing on screen whatever the two
  hues are — a size differential cannot promise that when a `sand` neighbour
  sits next to a `coral` centre. 0.65 rather than something lower because the
  ink on these cards is BLACK: dimming the card dims the contrast its name is
  read through, and at half strength a neighbour's name is nearly unreadable.
  The roster is the whole reason the stack opened.
- **Type.** The centre card keeps the `name` role and bold weight; a neighbour
  drops to `caption` and medium.

And the band the cards move under is DRAWN: a pair of white nubs at the panel's
edges, half a row tall, fixed in panel space and faded in with `o`. They are
built last so they sit on top of whatever is sliding under them, and they are
the only thing on this screen that does not move. That is what makes the
treatment hold MID-SCROLL — a partly-aligned centre card is judged against a
mark rather than by eye.

All three effects scale with `o`, so the closed card is untouched and the stack
opens *into* the emphasis rather than snapping into it.

Three more details are decisions rather than arithmetic:

- **The unheard count and the unheard bar are ONE element.** A yellow band
  across the top of the card, tall enough to hold "3 unheard" at full bleed
  and degrading continuously into a yellow rule along the top of a stack row.
  Two elements that had to agree about when to appear is how a design drifts.
- **Every card's ink is BLACK** (`Palette.INK`), because that is the
  palette's constraint (wataclient's `palette.scala`) rather than something
  this body decides. A hue that needed white text would put a second rule in
  every body.
- **The connectivity element shows only when the link is NOT healthy**
  (`NetStatus.isHealthy`), and then on a dark chip. Plan 0070 says the
  resting screen is the contact and "nothing else"; an indicator that is
  always green is not information, and green-on-yellow over the unheard band
  is unreadable. What went with it are the "WATA" title and the footer legend
  spelling out four key bindings — a wrist has no keys to spell.

What the rolodex replaced is only the LIST. The states this client actually
has are all still there and are still their own screens: the enrolment QR,
the boot screen, the connection line, the empty roster ("No contacts" is not
a card), the recording bar, and the send/play status flash.

**Holding the talk button talks to the centre card at any zoom**, and that
falls out rather than being a case: `Pump.stepMotion` writes
`Motion.centre` into the applet's `selected` every frame, and
`WataLogic.pttPress` reads `selected` exactly as it always did, never asking
how the selection got there. It is what `just watch-e2e`'s send leg proves.

`Rolodex.body` takes plain `RoloCard` values rather than a snapshot, so the
oracle can hand it three contacts with hand-picked hues and read the result
back as pixels; `Rolodex.cards` is the one function that reads the snapshot.

`Rolodex.cards` takes the roster's colours **all at once**
(`Palette.forRoster`), not one conversation at a time. A per-id hash and eight
hues put two of five contacts in the same colour about four times in five, and
that is what a real family on the simulator showed — Bob and Erin the same light
green. The set-based assignment is wataclient's; this file only has to ask for
the whole roster rather than for each card.

## The frame clock

Motion means a frame can differ from the last one because of TIME alone
(plan 0071), so the pump needs a "keep painting" signal — and, just as much,
has to stop. `Pump.frameMs` is three cadences:

| when | wait |
|---|---|
| `Motion.live` — coasting, springing, or the stack opening/closing | 16 ms |
| something else on a clock: the recording meter, a status flash, playback | 33 ms |
| a still picture (the rolodex at rest is one) | 50 ms |

The idle figure is a POLL interval, not a paint interval — nothing repaints
unless the tree changed — and it is 50 ms rather than a second because it
bounds how long a crown turn can wait to be noticed. `Motion.live` can only
go false because the integrator snaps to rest exactly; a model that was
forever a hair off a detent would hold the pump at 60 fps redrawing the same
frame.

## Gates

| recipe | what it proves |
|---|---|
| `just watch-spike` | the platform probes: classes, lifecycle, window, input reachability, network, audio |
| `just watch-hello` | `watchshell` + `iosui` drive UIKit on watchOS, through the product packages |
| `just watch-interptest` | wata-ios's whole stage suite passes on watchOS, plus the rolodex vocabulary drawn and read back as pixels |
| `just watch-e2e` | the whole client: login, sync, an arrival, and a SEND the server confirms |
| `just watch-rolodex` | not a gate — timed screenshots of the rolodex at rest, mid-scroll and settled |
| `just watch-device` | build, sign, install on real hardware |

None is in `ci` — they need Xcode and a watchOS simulator runtime
(mac-smoke's posture).

`watch-e2e` ends by having bob snapshot the family room server-side and
find both messages, because `send: complete` is only the app's word for it.

`watch-interptest` holds plan 0070 in three places. `rolodexAtRestIsOneCard`
and `rolodexMidScrollIsAStack` are the ROLODEX's oracle: the same body, the
same three cards, two different `Motion` values, read back as rendered
pixels — one card reaching the panel's corner with its name centred and its
neighbours nowhere on the panel, against five rounded rows with black gutters
between them, the right colour in each, and names against their leading
edges. Both were seen to fail with the motion inverted (the rest case with the
stack open loses all four of its colour probes; the stack case at rest reports
one card where it wants three) before either was believed.

`rolodexCentreCardIsMarked` is the third, and it is the one that says the send
target is VISIBLE. Its three cards are deliberately the SAME COLOUR: identity is
removed from the frame, so the only thing that can distinguish the centre row
from a neighbour is the emphasis itself. It reads the centre card's brightness
against a neighbour's, reads a point that is inside the centre card and outside
an inset neighbour, and reads the band's nub at the centre row's height and the
panel where the nub is not — then does the width pair again at `p = 1.3`, where
the centre card is only partly aligned with the band. It was seen to FAIL with
the emphasis disabled (`quiet` forced to zero and the nubs suppressed) before it
was believed: four of its probes go red, including "the centre card is no
brighter than its neighbour (255 vs 255)".

The pure arm holds the INTEGRATOR — one detent lands exactly on card 1 and comes
to a full stop, a flick coasts further, the end gives and bounces back to the
last card, the reserved axis does not move, and a five-second hitch handed over
in one step does not blow the springs up — and the PALETTE, which is now two
claims rather than one: the derivation is a function of the id, and a
five-person roster comes out in five DIFFERENT colours, in the same colours
whatever order the list arrives in, with the family thread still cyan and a
20-person roster still answering one colour each.

`rolodexVocabularyDraws` is where plan 0070's element
set stops being a declaration: it mounts a rounded card, a `display`-role
name centred in it, a caption under that and a translucent black band over
its top, then reads the rendered pixels back. Each probe discriminates one
claim, and each was **seen to fail** with the claim inverted before its
green twin was believed — square corners put half-black-over-blue where the
arc cuts the card away (0,0,0 vs 0,0,128), an opaque band reads 0,0,255
where a 50% one reads 0,0,127, and a leading-aligned name puts ink against
the box's left edge where a centred one leaves it empty.

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
