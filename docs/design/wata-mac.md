# wata-mac — design notes

The macOS client (plan 0032): every wata screen is already a pure
`wataui` body, and `go-pkgs/nativeui` renders those bodies as a retained
NSView tree on a scaled 160×128 stage. This doc describes what exists
(the backend layers) and pins the facts the app module needs; the
`wata-mac/` Sgola module itself (main, caps, the frame pump over
`ClientHandle`) is plan 0032's second chunk and lands here when it does.

## The layers

| layer | what it is |
|---|---|
| `go-pkgs/appleptt/appkit` | generated AppKit bindings (bindgen target `appkit`; see [bindgen.md](bindgen.md) for what was refused and why it shaped the backend) |
| `go-pkgs/nativeui` | plain Go: the algebra mirror, the retained interpreter (`Stage`), pixels, glyphs, keys, the dispatch seam |
| `wata-mac/` | (chunk 2) the Sgola module: caps, frame pump, window + delegate wiring |

## The retained interpreter (`nativeui.Stage`)

- **The stage is the unit.** `NewStage(scale)` makes one container NSView
  of `scale·160 × scale·128` (default scale 4 → 640×512); `SetTree`
  mounts a view tree, `Apply` runs a differ script in script order.
  `Mirror()` is the plain view tree the native tree currently shows —
  `Patches.applyAll` semantics, ported (`view.go`), and the invariant the
  tests hold against the real hierarchy.
- **Element table:** VText/VGlyph → `NSTextField` label, VRect → `NSBox`
  (custom, borderless, `fillColor`; NOT a layer-backed view — CGColorRef
  is unmappable, and NSBox draws via `drawRect:` so offscreen renders
  work), VImage → `NSImageView` over RGB565→RGBA widening +
  nearest-neighbour pre-scaling + PNG → `initWithData:` (raw bitmap
  planes are refused pointers), VGroup → plain container NSView spanning
  the full stage.
- **Geometry:** semantic coordinates exactly as wataui defines them
  (VText on the 26×15 grid of 6×8 cells, text rows starting 1px down;
  the rest on stage pixels), scaled by the integer factor, then y-flipped
  once per leaf (AppKit's origin is bottom-left). Group containers all
  span the stage, so child coordinates stay stage-absolute and nesting
  adds no offsets.
- **Fonts:** monospaced system font at 6.8pt per scale unit — sized so
  the LINE fits the 8px row pitch. The advance (~0.6em) is narrower than
  the 6px cell; column alignment still holds because every VText is
  framed from its own grid cell. Only intra-string width shrinks, and
  the semantic oracle for appearance stays the fb goldens.
- **Glyphs:** icon codes past 0x7F map to Unicode equivalents
  (`glyphs.go`: ✓ ▶ ★ ↑ ⚠ ≈ Ψ for the codes wata's bodies emit);
  anything unmapped renders `□`, visibly wrong on purpose.
- **Paint order = subview order**, AppKit's own rule; `PInsert` uses
  `addSubview:positioned:NSWindowBelow relativeTo:` to splice at an
  index, `PSet` mutates properties in place when the constructor is
  unchanged and `replaceSubview:with:` otherwise.

## Facts chunk 2 needs

- **Threading:** `Stage` methods must run on the AppKit thread and never
  hop by themselves. The frame pump runs body+diff on its own goroutine
  and submits whole `Apply` calls through `nativeui.MainQueue().Async`
  (libdispatch `dispatch_async_f` over purego — `dispatch.go`). The main
  queue only drains under `NSApplication.run`; the seam itself is proven
  headless on a private serial queue in `dispatch_test.go`.
- **Headless AppKit works.** View construction, mutation and
  `cacheDisplayInRect:` offscreen rendering — including NSTextField text
  drawing — all run with NO NSApplication, no runloop, no window, on a
  locked non-main OS thread inside an autorelease pool (`onAppKit` in
  `interp_test.go`). `mac-smoke` can assert hierarchies and probe pixels
  without ever opening a window.
- **Autorelease pools are the caller's job.** The interpreter allocates
  autoreleased ObjC objects; wrap each frame's apply (and each test) in
  `objc_autoreleasePoolPush/Pop` or the temporaries accumulate.
- **`-init` may return a different object than `-alloc`** — always adopt
  the returned id (the interpreter does; new wiring code must too).
- **Key translation is a pure table** (`keys.go`): NSEvent keyCode →
  the five-key model (arrows, return/keypad-enter, esc, space=PTT).
  What is NOT built yet: the synthesized first-responder NSView subclass
  whose `keyDown:`/`keyUp:` feeds it, and press/release/repeat state.
  Synthesizing it must follow bindgen.md's encoding rules ("Structs into
  callbacks") — `keyDown:` takes only an object, so no struct
  decomposition is needed there.
- **Probing rendered output:** `bitmapImageRepForCachingDisplayInRect:`
  + `colorAtX:y:`, addressing through the rep's `pixelsWide/High` so a
  non-1 backing scale cannot skew probe coordinates (`render_test.go`).

## Verification

`just nativeui-tests` (macOS-only, beside `bindgen-runtime`; not in ci —
ci has no darwin-only leg): the retained invariant on the real toolkit
(hierarchy classes/order/frames/labels mirror `applyAll` after both
build-from-scratch and patch scripts), patch splicing and in-place
mutation, the offscreen probe render, the dispatch seam, and the pure
pixel/glyph/key tables.
