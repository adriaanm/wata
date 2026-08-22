# 0071 — the surface boundary: one domain UI, replaceable shells

status: proposed

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
- **SwiftUI ships no `arm64-apple-watchos` module interface.** The device
  interfaces are `arm64_32`, `arm64e` and `armv7k` only; across the whole SDK
  exactly one framework offers an arm64 device interface. Apple builds watch
  apps as arm64e.

That last one decides the shape of a SwiftUI shell. Go emits arm64 and has no
arm64e target, and object files cannot be mixed across the two — so a SwiftUI
front end linked against a Go core **cannot be built for a real watch today**.
It builds for the simulator, which is arm64, and stops there. The path is not
closed forever (an arm64 interface, or a Swift-side split, would open it), but it
cannot be the plan.

Every Objective-C framework we need — WatchKit, SpriteKit, CoreText,
CoreGraphics, AVFAudio — ships `arm64-watchos`. So the approved *and reachable*
shell is WatchKit's: a full-screen `WKInterfaceImage` (or an `SKScene`, which
animates better), fed by our own renderer, with WatchKit's own gesture and crown
APIs for input. That is a supported-API shell, in the architecture Go can
produce, and it is close to what plan 0069 built and deleted.

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
`Navigate(±n)`, `Choose`, `Back`, `TalkDown`, `TalkUp`, plus lifecycle
(`Wake`, `Sleep`) and a raw escape hatch for the device-specific applets (the
handset's diag and exit menus). The crown, the arrow keys and a swipe all become
`Navigate`; a long press and a hardware PTT button both become `TalkDown`. The
applets stop containing the sentence "the watch has no keypad".

**Metrics, not constants.** A shell states its size in points, its scale, its
safe insets and its kind (wrist / handset / desktop). Layout is computed from
those, so 208×248, 160×128 and the next watch size are inputs rather than
edits. Type comes from *roles* — name, caption, status — which the renderer
resolves against the metrics, and on Apple against the system's preferred size,
which is what Dynamic Type and VoiceOver need.

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

1. **Intents.** Replace the key-code queue with the intent set, per shell. Small,
   and it immediately deletes the handset-key mapping from the watch.
2. **Metrics.** Bodies read size, scale and type roles instead of `Display`
   constants. Unblocks plan 0070's layouts on both devices.
3. **The renderer on Apple.** CoreGraphics + CoreText behind the existing element
   vocabulary, feeding one image view per shell. This is the step that drops
   `UIView`, and it can land on WatchKit's `WKInterfaceImage` with no Swift at
   all.
4. **Baked strikes on the handset**, same renderer, same design.
5. **A Swift shell — only if the architecture opens.** By then it is a shell
   swap, not a rewrite.

## Risks and unknowns

- **Frame cost on the watch.** Handing a full-screen image over per frame is not
  what `WKInterfaceImage` was designed for. Measure before committing the zoom
  animation to it; `SKScene` with a texture is the fallback, and the boundary
  makes swapping cheap.
- **No `CADisplayLink` on watchOS** (`API_UNAVAILABLE(watchos)`), so animation
  cadence comes from a timer. The settle-and-zoom timing is ours to schedule.
- **The retained AppKit/UIKit backends** (plan 0032) become redundant on the
  clients that move to frames. They can stay until they are in the way — the
  boundary lets both coexist — but the duplicated design must not.
- **This is a refactor across every client**, proposed while the product is a
  prototype and specifically because it is one. It should not start until plan
  0070's design is settled enough that the layouts it enables are known.
