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
   vocabulary, producing a `CGImage` per frame. This is the step that drops
   `UIView`.
4. **The Swift shell.** A SwiftUI app that owns the scene, shows the frame, and
   forwards crown/tap/long-press as intents into the Go archive — with the Go
   client built `-buildmode=c-archive` instead of owning `main`. The bundle and
   signing path is `tools/watch-device.py`'s, with a Swift compile in front of
   it.
5. **Baked strikes on the handset**, same renderer, same design.

## Risks and unknowns

- **Frame cost on the watch.** A `CGImage` per frame across the Swift boundary
  wants measuring before the zoom animation is committed to it — one shared
  buffer with a flip, not an allocation per frame. `SpriteView` over an
  `SKScene` texture is the fallback, and the boundary makes swapping cheap.
- **What the link check did not prove.** It proved the toolchain: Swift +
  SwiftUI + a Go c-archive produce one arm64 watchOS executable. It says nothing
  about the Go runtime starting from a Swift `main` on a real watch, the
  bundle/signing path, or the first launch — all of which the device install
  (still waiting on a watchOS provisioning profile) will answer.
- **The Go client stops owning `main`.** Under `-buildmode=c-archive` the
  runtime starts at load and `main()` never runs, so today's startup arc has to
  move into an exported entry point the shell calls.
- **No `CADisplayLink` on watchOS** (`API_UNAVAILABLE(watchos)`), so animation
  cadence comes from a timer. The settle-and-zoom timing is ours to schedule.
- **The retained AppKit/UIKit backends** (plan 0032) become redundant on the
  clients that move to frames. They can stay until they are in the way — the
  boundary lets both coexist — but the duplicated design must not.
- **This is a refactor across every client**, proposed while the product is a
  prototype and specifically because it is one. It should not start until plan
  0070's design is settled enough that the layouts it enables are known.
