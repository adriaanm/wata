# The FFI frontier — what Sgola reaches today, what is queued, what stays Go

The map of the language boundary under wata's Apple clients, kept
current as rulings land. The evidence behind every row is a committed
spike or a shipped module: [plan 0038](../plans/0038-ffi-in-sgola.md)
(the two spikes and their tickets), [bindgen.md](bindgen.md) (the
generated-Go layer), `tools/objc-spike/REPORT.md` and
`tools/interp-spike/REPORT.md` (the exact walls, spelled the way the
code wants to be). wata is sgola's proving ground, so "write that bit in
Go" is a *finding*, and this doc is where the findings add up.

The one-paragraph summary: **calling C is closed; being called by C is
the open half.** Everything a call-out needs — open a library, find a
symbol, make the call, pass a C string, carry the results — works on
the current pin. Every remaining "must be Go" that is not a
technology boundary (cgo, reflection, the Go runtime) traces to one
missing feature: a Go **func value crossing a facade**, which is what
callbacks, ObjC class synthesis, and the main-queue seam all are.

## What works today (on the current pin)

Facades bind ordinary Go functions, and the emitted call is what a
hand-written FFI looks like — the objc-spike's finding was precisely
that everything *around* its two gaps was already right.

| capability | shape | proven by |
|---|---|---|
| bind a Go package's functions | `@go.bind("module/path") object x` + `@go.name` defs | every `go.*` facade in the tree (qr, memprobe, macshell, …) |
| errors | Go's trailing `error` rides `throws` — `def f(...): T throws sgo.GoError` | all clients; objc-spike leg 1 |
| multiple results | tuple correspondence, `flatten(R) ++ (error if throws)`; discard is the ordinary tuple pattern `val (r1, _, _) = …` | ruled 2026-08-07 (plan 0038); objc-spike compiles on it |
| variadic Go functions | a Scala vararg emits the bare Go variadic call | `VARIADIC-FACADE-BIND` ruling; `syscallN(fn, args*)` |
| pointer-sized addresses | `go.Uintptr`, an **opaque** scalar — deliberately not a reference, keeps nothing alive | landed `b85a713`; objc-spike builds and runs |
| dlopen / dlsym / syscall | `go.purego.dlopen/dlsym/syscallN` — purego's whole non-reflective surface | `tools/objc-spike` runs green |
| C-string addresses | the bracket `go.cstring(s) { p => body }` — `p` addresses a NUL-terminated copy, live for exactly `body`'s extent (compiler-emitted `KeepAlive`), NON-RETENTION callee contract; nest for multiple string args; lint rejects `p` escaping the bracket | landed `1c6d6ed`; objc-spike runs, `length = 5` |
| opaque handles + scalars + strings + bytes | the facade bread and butter | every shipped module |

So an ObjC message send is expressible in Sgola **today** end to end —
`objc_getClass`, `sel_registerName`, `objc_msgSend` through `syscallN`,
C strings through the bracket — and `tools/objc-spike` proves it in ci:
it runs the full round-trip and asserts `objc-spike: length = 5`.

## Ruled and queued — what we know will be possible

These have designer rulings and sit in sgola's queue; the shape is
settled, only the landing is pending.

- **By-value structs across facades** (`FACADE-VALUE-STRUCT`, filed; no
  ruling yet). A facade class is always a Go pointer today, and cannot
  be constructed. AppKit geometry (`CGRect` and friends) and the ObjC
  handle types themselves (`struct{ objc.ID }`) are values, so *every*
  crossing into the generated `appkit` surface mismatches. This is the
  single blocker for porting `nativeui/interp.go` and deleting the
  frame wire (`WIRE-DIES-INTERP-TO-SGOLA`); `tools/interp-spike` is
  committed not-building, spelled the way the interpreter wants it.
- **Equality over generic families** (`GENERIC-FAMILY-EQUALS`, queued
  position 3). Not FFI, but it bit the FFI spikes: `==` on a case class
  carrying `List[T]` is a loud DATA-4 wall until the reference-collapsed
  instantiation learns a real equals.

## Believed possible — the call-in half

**Func-typed facade parameters** (`OBJC-SPIKE-CALLBACK-LEG`, blocked on
sgola's `SAM-CLOSURE-LOWERING`) is the load-bearing unknown. Everything
that *receives control from C* needs it, and it is one feature wearing
four costumes:

- `purego.NewCallback(fn)` — a C-callable pointer to Sgola code;
- `objc.RegisterClass` — an ObjC method whose body is Sgola;
- `MainQueue().Async(work)` — the dispatch seam (`nativeui/dispatch.go`
  is the *purest* FFI file in the package, not the FFI-free one the
  first inventory claimed);
- bindgen's protocol delegates — a struct of func fields, one IMP per
  non-nil field.

The evidence it is small: sgola's own notes describe closures as
already emitting plain Go func literals — the *representation* is
right, what is missing is the *binding* of a func-typed parameter. But
it is a belief, not a ruling; leg 2 of the objc-spike exists to convert
it into a wall with a diagnostic or a pass.

## What genuinely requires hand-written Go

Three categories, in decreasing permanence.

**Technology boundaries — Go stays, and should.**
- **cgo**: `go-pkgs/audio` (opus + tinyalsa, the handset's audio),
  `go-pkgs/macaudio`'s C shims where it has them, `go-pkgs/irohnet`
  (the Rust static library). A facade binds Go, not C; cgo modules are
  the Go side of a *different* FFI and stay Go by definition.
- **Reflection and generics**: `objc.Send[T](id, sel, ...any)`,
  `purego.RegisterFunc`/`RegisterLibFunc` — generic, variadic-`any`,
  reflect-driven. No facade will ever express a generic Go function,
  and none should: bindgen exists to emit the *typed* wrappers a facade
  binds trivially. This is a division of labor, not a gap.
- **The Go runtime**: `go-pkgs/memprobe` (ReadMemStats), macshell's
  heap profiler, anything touching `runtime`/`unsafe` — the measurement
  and plumbing layer under the app, a few dozen lines each.

**Waiting on the call-in half — Go for now, portable after.**
- `nativeui/dispatch.go` (94 lines): dlopen/dlsym it could do today;
  the callback trampoline it cannot.
- `macshell/shell.go`'s FFI core and `nativeui/keyview.go` /
  `macshell/menu.go` class synthesis: same single dependency.
- `objcrt` (the hand-written runtime under the bindings): autorelease
  pool push/pop are plain calls a facade could bind now; class
  registration needs callbacks. Splits when the feature lands.

**Waiting on by-value structs.**
- `nativeui/interp.go` (359 lines) and with it `view/pixels/glyphs`
  (397 lines of zero-FFI logic that ride with their consumer), plus
  both halves of the frame wire (~400 lines) — deleted, not ported,
  when the seam disappears.

The chrome (`macshell/login,menu,prefs`, 581 lines of raw `objc.Send`
sites) is in none of these categories: raw messaging is not a compiler
gap but an *allowlist* gap — every raw site exists because its class is
not in bindgen's allowlist yet (the `macui` target, plan 0037).

## bindgen: Go today — what a Sgola emission would look like

bindgen keeps emitting Go, and that is the right call while the rulings
above land: generated Go is consumed through facades, so nothing about
the choice is architectural debt. But it is worth being precise about
what "bindgen emits Sgola" would mean, because the distance is shorter
than it looks — and because it names which rulings actually gate it.

There are two rungs, and the first is nearly free:

**Rung 1 — emit the facade declarations.** The generated Go wrappers
are exactly facade-shaped (typed, no generics, no reflect). Today
someone hand-writes `@go.bind` objects over them per consumer; a
`--emit-facade` flag could write those `.scala` files mechanically from
the same IR — one `object` per class, `@go.name` per selector-derived
method, `throws sgo.GoError` where the trailing `NSError**`→`error`
mapping fired. No new compiler features needed; it deletes the one
hand-maintained mirror in the stack today. The IR already knows
everything the facade needs.

**Rung 2 — emit Sgola bodies: the bindings *are* Sgola.** The wrappers
stop being Go entirely; `objc_msgSend` is reached from Sgola the way
the objc-spike does it. In prose, the emitted shape per class would be:

```scala
// generated — appkit/NSView.scala (sketch, not current syntax for all of it)
object NSViewClass:
  val cls: go.Uintptr = ObjcRt.getClass("NSView")        // cached at init
  def alloc(): NSView = NSView(ObjcRt.send0(cls, Sel.alloc))

case class NSView(id: go.Uintptr):                        // a VALUE wrapper
  def initWithFrame(frame: CGRect): NSView =
    NSView(ObjcRt.sendRect1(id, Sel.initWithFrame, frame))
  def setNeedsDisplay(v: Boolean): Unit =
    ObjcRt.send1(id, Sel.setNeedsDisplay, if v then 1L else 0L)

object Sel:                                               // registered once
  val alloc: go.Uintptr = ObjcRt.sel("alloc")
  val initWithFrame: go.Uintptr = ObjcRt.sel("initWithFrame:")
```

with `ObjcRt` a small generated (or once-written) Sgola module over
`go.purego.syscallN`, replacing Go's `objcrt`. What each piece of that
sketch stands on:

- `Sel.sel(...)` is `sel_registerName` over a C string — needs the
  **cstring bracket** (landed `1c6d6ed`). Selector caching in module vals
  needs module-init effects, which the dialect already allows for
  `val`s.
- `CGRect` as a constructible value — **FACADE-VALUE-STRUCT**, or in a
  full-Sgola emission simply a `case class` of doubles, *if* the ABI
  classification (HFA in d0–d3, the x8 indirect return) can be spoken.
  That is the honest hard part of rung 2: purego's struct machinery is
  reflect-driven Go, so a Sgola emission either keeps a thin Go kernel
  for struct calls (realistic) or sgola grows an ABI-aware call
  primitive (a language feature nobody has asked for yet, and this doc
  is not asking).
- Protocol delegates — a struct of func fields becomes a Sgola record
  of closures; needs **func-typed facade params** and `NewCallback`
  reachable through them. Same single feature as everything else.
- Class synthesis (`ObjcRt.registerClass`) — same again.

So rung 2's gating set is: the cstring bracket (landed), func-typed
params (leg 2), and a decision about struct-call ABI (keep a Go kernel
vs a new primitive). Nothing else in the sketch is speculative — every
other line is the objc-spike's proven vocabulary. The realistic end
state is rung 2 **with a Go kernel**: ~a hundred lines of Go doing
reflect-y struct dispatch and callback registration, everything above
it generated Sgola, and the per-class/per-selector surface — the 13k
generated lines of `appleptt` — no longer Go at all.

Neither rung is scheduled. Rung 1 becomes attractive the moment a
second consumer hand-writes an appkit facade; rung 2 waits for its
three gates, and plan 0038's rule applies: a green spike is evidence,
not a mandate.

## What to watch

Each landing below moves a row in this doc; the tickets name the
verification.

- `go.cstring` — **landed** (`1c6d6ed`): objc-spike closed leg 1 fully
  (runs green, `length = 5`); `Sel`-style selector caching is writable,
  with the caveat that a cached value must be the bracket's *result*
  (the selector uintptr `sel_registerName` returns), never the bound
  `p` itself — the lint enforces exactly that.
- func-typed facade params land → leg 2 runs; if it passes, dispatch,
  keyview, menu synthesis and the objcrt split all unblock at once.
- `FACADE-VALUE-STRUCT` ruling → decides `WIRE-DIES-INTERP-TO-SGOLA`
  (the wire's ~400 lines) and the shape of rung 2's geometry.
- `BINDGEN-TYPED-STRUCTS` — **landed**: callbacks carry the generated
  struct types directly and struct/CGFloat callback returns emit (the
  `objcrt.CGFloatRet` spelling; true encodings via `class_addMethod`) —
  most of the remaining AppKit/UIKit delegate surface, and the shape the
  UIKit interpreter's delegates will ride on.
