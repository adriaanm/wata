# 0071 — the surface boundary: one domain UI, replaceable shells

status: accepted (steps 1 and 2 landed 2026-08-22 — the watch reports
intents, its stage/grid/type come from the panel it is on, and the MOTION
INTEGRATOR is in `wataui` and drives the watch's rolodex. `Intents.steps`
survives only for the conversation's grid list of message rows. Steps 3-5 —
the Apple renderer, the Swift shell, the handset's baked strikes — are open.)

## Why this exists

Two questions arrived together and turn out to have one answer. The watch stage
is built on `UIView`, which the watchOS SDK marks `API_UNAVAILABLE(watchos)` —
undocumented surface under what is meant to become a shipping product
(plan 0070). And the UI logic above it is not wata's domain: the applets think in
BQ268 key codes, a 160×128 grid and a 6×8 character cell, so the watch had to be
taught to speak handset — `code*4 + phase` — before it could show anything.

Both are the same defect: **the platform reaches too far up**. The fix is a
boundary low enough that everything above it is wata, and everything below it is
one small, replaceable thing per device.

## What the SDK actually allows

Checked against the watchOS 26.2 SDK rather than recalled:

- `UIView` / `UIWindow` / `UILabel` are `API_UNAVAILABLE(watchos)`. **But a
  drawing subset of UIKit is fully available since watchOS 2.0**: `UIImage`,
  `UIFont`, `UIColor`, `UIBezierPath`, `NSAttributedString` and
  `NSStringDrawing`. CoreGraphics, CoreText, SpriteKit and SceneKit are public
  and present.
- WatchKit's interface objects — `WKInterfaceController`, `WKInterfaceImage`,
  `WKInterfaceGroup`, `WKInterfaceSKScene`, `WKGestureRecognizer`,
  `WKCrownSequencer` — are present, watchOS-only, and **not deprecated** in this
  SDK.
- **Swift links against a Go core for a real watch.** Built and checked rather
  than inferred: a SwiftUI `@main` app compiled for `arm64-apple-watchos26.0`,
  linked against a `go build -buildmode=c-archive` of a `GOOS=ios GOARCH=arm64`
  package built with the watchOS sysroot, produces one executable stamped
  `platform WATCHOS`, `minos 26.0`, arch arm64, with both the Swift entry point
  and the exported Go symbol in it.

  The SDK's Swift *module interfaces* for watch devices cover `arm64_32`,
  `arm64e` and `armv7k` and not `arm64`, which looks like it forecloses this —
  Apple builds their own watch code as arm64e, and Go emits arm64, which cannot
  be mixed with it. It does not: `swiftc` resolves SwiftUI for an arm64 device
  target regardless, and `SDKSettings.plist` lists arm64 among the supported
  watchOS device archs. The missing interface files are a red herring, and an
  earlier revision of this plan was wrong to conclude otherwise.

Every Objective-C framework we need — WatchKit, SpriteKit, CoreText,
CoreGraphics, AVFAudio — also ships `arm64-watchos`, so an all-Objective-C shell
(a `WKInterfaceImage` or an `SKScene`) remains available as a fallback with no
Swift at all.

**The decision (owner, 2026-08-22) is to use Swift on the watch.** Sgola is an
experiment in how far a restricted dialect can be pushed, and spending that
budget fighting a platform's own front door is the wrong fight: the shell is the
one place where being native costs nothing and being clever costs a product. So
the watch shell is SwiftUI on the documented path, and everything above the
boundary stays Sgola.

## The boundary

Four layers. The top three are wata's and are shared; only the bottom one is per
device, and it is small.

```
  wata domain UI     rolodex logic, states, what a press means
        │  View (the element vocabulary) + Intent (what the person did)
  renderer           View -> pixels. CoreText on Apple, baked strikes on the BQ268
        │  Frame (pixels + damage)   Metrics (size, scale, type roles)
  surface            per device: show a frame, deliver intents
        │
  platform           WatchKit / framebuffer+evdev / AppKit / UIKit
```

**Intents, not key codes.** A shell reports what the person did in wata's terms:
`Navigate`, `Choose`, `Back`, `TalkDown`, `TalkUp`, plus lifecycle (`Wake`,
`Sleep`) and a raw escape hatch for the device-specific applets (the handset's
diag and exit menus). **`Raw` carries a phase**, because the applets that
motivate it read press and release edges; the watch's port left it phaseless
since nothing there emits one, and that must be fixed before the handset's shell
is ported rather than discovered by it. The crown, the arrow keys and a swipe all become
`Navigate`; a long press and a hardware PTT button both become `TalkDown`. The
applets stop containing the sentence "the watch has no keypad".

`Navigate` carries **an axis and a magnitude**, not a direction:
`Navigate(axis, amount)`. Both exist for reasons the design already has.

- *Magnitude*, because plan 0070's scrolling is physical — impulse, friction,
  detent, bounce — and a shell that can only say "down was pressed" cannot
  express a flick. A crown reports angular velocity, a held key reports its
  repeat ramp, a drag reports its speed at release; each device says how hard it
  was pushed and the physics lives once, above the boundary.
  Two conventions, settled when step 1 landed rather than left to each shell:
  **positive is toward the end of the list** (down, right), and the **unit is
  items** - 1.0 is one card or one row, the only unit a crown, a key and a thumb
  can all express, and it converts straight into plan 0070's constants (cards
  per second).

- *Axis*, because the horizontal one is **reserved and unused**. Nothing moves
  sideways today and nothing should until there is a reason, but keeping it free
  is nearly free now and structural later — so the integrator runs per axis
  rather than on a scalar, and layout positions items from an (x, y) offset
  rather than a scroll index.

**Metrics, not constants.** A shell states its size in points, its scale, its
safe insets and its kind (wrist / handset / desktop). Layout is computed from
those, so 208×248, 160×128 and the next watch size are inputs rather than
edits. Type comes from *roles* — name, caption, status — which the renderer
resolves against the metrics, and on Apple against the system's preferred size,
which is what Dynamic Type and VoiceOver need.

**Motion above the boundary.** The scroll position is not an index the shell
moves; it is a simulated quantity the domain owns — velocity from impulses,
exponential friction, a critically damped spring into the nearest detent, a
stiffer one at each end. The shell contributes impulses and a frame clock and
nothing else, which is what keeps the feel identical on a crown, a keypad and a
trackpad rather than three tunings of three shells. Plan 0070 has the model and
its constants.

That has a consequence for the layer below: **a frame can differ from the last
one because of time alone**, not only because state changed. The surface needs a
"there is motion, keep painting" signal, and the renderer needs to be cheap
enough to run at the device's frame rate while it is true.

**Frames, not view trees.** The renderer produces pixels; the shell shows them.
On Apple that is a `CGImage` into an image view or a texture; on the handset it
is the framebuffer already. The differ survives as a *damage* calculator — what
changed, so what must be repainted — rather than as a mutator of native views.

This is what removes `UIView`: with the renderer producing the frame, the Apple
shells need exactly one image view each, and every one of those is public API on
its platform.

## What it buys beyond the fix

- **The UI logic becomes domain logic.** "Which person is centred, are we
  talking, is this card open" — no cells, no scancodes, no scroll offsets in
  glyph rows.
- **Per-device layout stops being per-device code.** One body can read metrics
  and lay out differently, or a device can keep its own body; either way the
  divergence is a choice rather than a fork forced by the algebra.
- **Tests get device-independent.** A headless surface renders frames to PNG and
  replays intents — today's `fb-ui-tests` generalised, with the watch's goldens
  produced by the same renderer at different metrics. The current harness
  replays BQ268 key codes into a watch, which is exactly the leak this removes.
- **The shell becomes swappable.** If Apple ships an arm64 SwiftUI interface, or
  if SpriteKit turns out to animate the rolodex better than an image view, that
  is a change to one small file with a known interface.

## Staging

Each step is useful alone, and the order is chosen so the risky part comes last.

1. **Intents.** Replace the key-code queue with the intent set, per shell —
   `Navigate` carrying an axis and a magnitude from the start, since retrofitting
   either into a settled interface is the expensive version. Small, and it
   immediately deletes the handset-key mapping from the watch.
2. **Metrics, and the integrator.** Bodies read size, scale and type roles
   instead of `Display` constants, and scroll position becomes simulated rather
   than indexed. Unblocks plan 0070's layouts and its motion on both devices.

   Landed as two, and they turned out to be independent: the metrics half is
   a property of the SURFACE (what is this panel, how big is a row, how big
   is type) and the integrator is a property of the DOMAIN (where is the
   list, how fast is it moving). Doing the metrics alone is a complete
   change — the watch stopped being a cropped handset — and the integrator
   can land against any layout. **The integrator landed 2026-08-22** as
   `wataui/motion.scala`, above every platform: `Motion.impulse` from a
   `Navigate`, `Motion.step` from the shell's frame clock, and `centre` /
   `offset` / `openness` / `live` as what a body and a pump read. Two things
   it taught. Explicit Euler at these stiffnesses needs a FIXED SUB-STEP
   accumulated against real time — a spring handed a whole frame diverges,
   and a dropped frame would turn a bounce into an explosion. And the model
   has to come to an EXACT stop (snap to the detent, zero the velocity), or
   `live` never goes false and the "keep painting" signal this plan asks for
   becomes "paint forever". One correction to what this section
   predicted: type roles cannot be a property of GRID text, because a
   `VText` is a run of character cells and has nowhere to carry one. The
   watch resolves a role from the grid ROW instead (header / list / footer,
   which is what every body here is).

   Plan 0070's element vocabulary landed after this (2026-08-22): a
   `VLabel` names its own role, so pixel-placed text no longer needs the
   row. `TypeRoles.forRow` stays for the grid-shaped bodies that are still
   here and goes away with the last of them — it is how `VText` gets a
   role, not a stand-in for a missing one.
3. **The renderer on Apple.** CoreGraphics + CoreText behind the existing element
   vocabulary, producing a `CGImage` per frame. This is the step that drops
   `UIView`.
4. **The Swift shell.** A SwiftUI app that owns the scene, shows the frame, and
   forwards crown/tap/long-press as intents into the Go archive — with the Go
   client built `-buildmode=c-archive` instead of owning `main`. The bundle and
   signing path is `tools/watch-device.py`'s, with a Swift compile in front of
   it.
5. **Baked strikes on the handset**, same renderer, same design.

## Risks and unknowns

- **Frame cost, on both, and it is the same risk twice.** Motion means every
  frame differs, so the question stops being "how fast can we repaint a change"
  and becomes "can we hold the device's frame rate for as long as a flick
  coasts". On the watch that is a `CGImage` per frame across the Swift boundary —
  one shared buffer with a flip, not an allocation per frame, with `SpriteView`
  over an `SKScene` texture as the fallback. On the handset it is whole frames
  over SPI; the stack is rectangles and a few strings, which is the cheap case,
  but a physics model that runs at 8 fps is worse than no physics at all. Measure
  before the constants are tuned, on the handset first, because it is the floor.
- **What the link check did not prove.** It proved the toolchain: Swift +
  SwiftUI + a Go c-archive produce one arm64 watchOS executable. It says nothing
  about the Go runtime starting from a Swift `main` on a real watch, the
  bundle/signing path, or the first launch — all of which the device install
  (still waiting on a watchOS provisioning profile) will answer.
- **The Go client stops owning `main`.** Under `-buildmode=c-archive` the
  runtime starts at load and `main()` never runs, so today's startup arc has to
  move into an exported entry point the shell calls.
- **A swipe cannot report how hard it was.** `UISwipeGestureRecognizer` says
  that a flick happened and nothing more; only `UIPanGestureRecognizer` has
  `velocityInView:`. So the watch's swipe magnitude is a constant until the
  shell moves to a pan recognizer, and plan 0070's "a drag reports its speed at
  release" is true of the crown and a future pan, not of today's swipe.
- **No `CADisplayLink` on watchOS** (`API_UNAVAILABLE(watchos)`), so animation
  cadence comes from a timer. The settle-and-zoom timing is ours to schedule.
- **The retained AppKit/UIKit backends** (plan 0032) become redundant on the
  clients that move to frames. They can stay until they are in the way — the
  boundary lets both coexist — but the duplicated design must not.
- **This is a refactor across every client**, proposed while the product is a
  prototype and specifically because it is one. It should not start until plan
  0070's design is settled enough that the layouts it enables are known.
