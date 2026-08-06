# 0032 — wataui backend 2: the AppKit leg, and the macOS walking skeleton

Status: accepted (owner 2026-08-06: the scaled stage first, native layout
as a later deliberate step)

## Problem

`[WATAUI-NATIVE-APPKIT]` Plan 0023 M4's second half: every wata screen
is already a pure `wataui` body and the differ is proven backend-free,
but no retained backend exists — the framebuffer interpreter repaints
whole frames and never diffs. The macOS client (owner-ruled 2026-08-06:
macOS first, iOS as a port) needs the missing piece: an interpreter
that applies the differ's patch script to a retained native view tree.
Every prerequisite is now green: struct-args-in-callbacks has a proven
contract (bindgen.md "Structs into callbacks"), audio needs no cgo
(AUDIO-APPLE-DERISK), and the bound client has a real handle surface
(plan 0025).

## Decision

**The backend renders the five constructors as native views at scaled
semantic coordinates.** wataui's own contract decides this: a `VText`
addresses a grid cell, the rest address pixels, and a non-panel backend
scales both. So the macOS window is the 160×128 stage at an integer
scale (default 4×, 640×512), `VText` becomes a label on the scaled
grid, and no layout reinterpretation happens — the bodies stay the
single source of what every screen shows, and the fb goldens stay the
semantic oracle for them. Native-feeling layout (dynamic type, real
lists) is a later, deliberate step that would add body-level elements,
not a backend liberty.

**The retained mirror is the differ's own model.** The backend keeps
the previous `View` tree plus a parallel native tree; each frame runs
the body, `Diff.diff(old, new)`, and applies the script in order —
`PSet` mutates one native view's properties, `PInsert`/`PDelete` splice
subviews. `Patches.applyAll`'s invariant already pins the script
semantics; this plan only adds the arm that mutates NSViews instead of
plain values.

Per constructor: `VText` → `NSTextField` label (monospaced system font
sized to the scaled cell), `VGlyph` → the same (the glyph range maps to
text), `VRect` → a layer-backed `NSView` with a background color,
`VImage` → `NSImageView` over an RGB565→RGBA conversion, `VGroup` → a
plain container view whose subview order is the paint order (AppKit
draws later subviews over earlier — same rule). RGB565 colors widen
exactly as the Gio shell already widens them.

**Threading follows the purity rule.** Bodies and the diff run on the
client's frame goroutine; only the apply step touches AppKit, hopped to
the main thread via libdispatch (`dispatch_async_f` with a purego
callback — a C-pointer API, the shape purego likes best). The main
thread belongs to `NSApplication.run`; the frame loop is
`ClientHandle`'s dirty-flag channel driving body→diff→dispatch, exactly
the pump shape the phone spike's `Watch(EventSink)` proved.

**Input maps to the five-key model.** A custom `NSView` subclass
(synthesized via `objcrt`, with the true type encodings the
struct-callback spike mandates) takes first-responder and translates
`keyDown:` — arrows/enter/escape and a PTT key (space, hold-to-talk) —
into the same key events the fb input layer produces. Mouse taps land
later; the walkie-talkie key model is what the bodies already
understand, and the skeleton's job is to prove the loop, not to design
macOS interaction.

**The app is `wata-mac/`**: a Sgola module like `wata-tui` (wataclient
in-link, TCP transport first — `IROH-APPLE` is its own queue item),
whose device layer is the AppKit backend + generated `appkit` bindings
package (bindgen allowlist: NSApplication, NSWindow, NSView,
NSTextField, NSImageView, NSColor, NSFont, NSImage, NSEvent, plus the
window-delegate types). Emitted through `emitpackage` and linked into a
plain Go binary the way the audio spike's driver is — no gomobile, no
xcframework, no signing for the local build.

## What changes (file-level)

- `tools/bindgen/bindgen.json`: the `appkit` target (new generated
  package under `go-pkgs/appleptt/appkit`, same layout as `avfaudio`).
- `go-pkgs/nativeui/` (new, plain Go): the retained interpreter — mirror
  tree, patch application, RGB565→RGBA, the main-thread dispatch seam,
  the key translation. Pure Go against the generated bindings so it is
  testable without Sgola in the loop.
- `wata-mac/` (new Sgola module): main, caps (`HttpDo`/`Clock`, lifted
  from wata-tui), the frame pump over `ClientHandle`, the `sgo.deps`
  wiring.
- `justfile`: `mac-build`, `mac-smoke`.
- Docs: wataui.md (the second backend arm), a new
  `docs/design/wata-mac.md`, bindgen.md (allowlist additions).

## Verification

- `go-pkgs/nativeui` unit tests: build-from-scratch and patch-apply
  against real body output, asserting the NATIVE tree (hierarchy walk:
  classes, order, frames, label strings) mirrors `applyAll`'s plain
  tree — the retained invariant, checked on the real toolkit.
- Offscreen render sanity: `cacheDisplayInRect:toBitmapImageRep:` (the
  machinery the struct spike already exercised headlessly in ci) over a
  known body, asserting non-blank output and a couple of probe pixels —
  NOT byte-goldens; native fonts are not the fb font, and the semantic
  oracle stays the fb goldens over the same bodies.
- `just mac-smoke`: scripted end-to-end against a throwaway
  wata-server — app boots, logs in, contact list renders (hierarchy
  asserted), a message arriving over sync patches exactly the rows the
  differ says. Foreground, exit-code-checked, in ci if runtime allows
  (macOS-only leg, like bindgen-tests).
- The owner's leg: run it, look at it, click nothing — keyboard only.

## Out of scope

- Audio (next queue item once this lands: the audio-spike pieces wired
  into `ActPlay`/record for wata-mac), iroh transport (`IROH-APPLE`),
  mouse/trackpad interaction, native layout/chrome, menu bar, app
  bundling/signing/distribution, and the UIKit port (`IOS-CLIENT-
  ASSEMBLY` — it reuses `nativeui`'s shape with a UIKit element table).
