# The FFI frontier — what Sgola reaches today, what is queued, what stays Go

The map of the language boundary under wata's Apple clients, kept
current as rulings land. The evidence behind every row is a committed
spike or a shipped module: [plan 0038](../plans/0038-ffi-in-sgola.md)
(the two spikes and their tickets), [bindgen.md](bindgen.md) (the
generated-Go layer), `tools/objc-spike/REPORT.md`,
`tools/callback-spike/REPORT.md` and `tools/interp-spike/REPORT.md`
(the exact walls and crossings, spelled the way the code wants to be). wata is sgola's proving ground, so "write that bit in
Go" is a *finding*, and this doc is where the findings add up.

The one-paragraph summary: **calling C is closed; being called by C is
closed.** Everything a call-out needs — open a library, find a symbol,
make the call, pass a C string, carry the results — works on the
current pin, and so does the reverse crossing: `go.callback` registers
a Sgola literal as a C-callable address, proven end to end by
`tools/callback-spike` (an ObjC method whose body is Sgola, asserted in
ci). The biggest remaining language gap is now **by-value structs
across facades** (`FACADE-VALUE-STRUCT`) — the blocker for the interp
port and the frame wire's deletion; everything else that stays Go is a
technology boundary (cgo, reflection, the Go runtime), not a compiler
gap.

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
| callbacks — control entering Sgola from C | `go.callback(literal): go.Uintptr` — a registration returning a free address, ordinary-value vocabulary (details below) | landed `cb15191`; callback-spike runs 42 in ci |
| opaque handles + scalars + strings + bytes | the facade bread and butter | every shipped module |

So an ObjC message send is expressible in Sgola **today** end to end —
`objc_getClass`, `sel_registerName`, `objc_msgSend` through `syscallN`,
C strings through the bracket — and `tools/objc-spike` proves it in ci:
it runs the full round-trip and asserts `objc-spike: length = 5`. The
reverse dispatch is equally proven: `tools/callback-spike` synthesizes
an ObjC class whose method IMP is a `go.callback`-registered Sgola
literal, and ci asserts the msgSend answers its 42.

### The callback contract (landed `cb15191`, v1)

`go.callback(f): go.Uintptr` deliberately inverts cstring's bracket: a
purego trampoline has process lifetime (never released,
platform-capped), so the liveness objection that forced `cstr` into a
bracket does not exist here — the address is a free value.

- `f` must be a **function literal with ascribed param types** in v1 —
  the ascriptions read as the declared foreign signature (a named def
  does not compile);
- ordinary-value vocabulary, monomorphic: params `go.Uintptr | Int`,
  result `go.Uintptr | Int | Unit`, arity ≤ 15 (purego's SyscallN
  ceiling). The emitted trampoline is the uintptr-slotted func purego
  requires and MARSHALS to the declared signature — void methods work
  (the trampoline returns 0 uniformly; purego zero-result callbacks are
  SysV-only, so the portable form was forced), a BOOL predicate
  declares `Int` and answers 0/1, and a constant is simply an `Int`.
  `go.Uintptr` stays fully opaque; conversions live only in the glue;
- register at module/startup scope — the ~2000 trampoline cap makes
  registration-in-a-loop fail loudly, so callbacks are never minted
  per-frame;
- the registration is a **crossing**: the literal's captures face the
  same CONC-8 predicate a fork capture does (Shareable / pure /
  synchronizer) — callback bodies that need mutable state hoist it into
  `Atomic`/`Mutex` cells.

The go.mod contract (permanent, not a wrinkle — sgola `22a7c16`,
reversing an earlier injection approach): **the user owns go.mod, and
sgo never writes a require you did not declare.** A module whose
emitted Go imports an external module (today: purego, via
`go.callback`) must declare the require itself; forget it and sgo
fails with a fix-menu error naming the exact line to add (never Go's
raw `no required module provides package`), and a declared-but-
different version warns against the tested one. Our spikes carry
`go-pkgs/puredep` — a blank-import of purego with a committed go.sum —
which under this rule is simply the correct spelling; a direct require
in the module's go.mod is equally first-class.

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
- **Equality over generic families** (`GENERIC-FAMILY-EQUALS`, landed
  and verified — sgola `7c228f9`, repinned 2026-08-08). Not FFI, but it
  bit the FFI spikes: `==` on a case class carrying `List[T]` was a
  loud DATA-4 wall; the stamped per-instantiation equals now answers
  it, and both halves are consumed. The `Bytes` half is fixed too: a
  stub-and-map core type maps to its declared content semantics
  (`bytes.Equal`, content hash) before any generation, so `VImage`'s
  case emits `bytes.Equal(x.pixels, y.pixels)` inline — no helper over
  an unemitted type, ever. Verified by diff-spike's `eq` arm (eqcheck
  suite green, Bytes-content cases included); the `rootLen` workaround
  is deleted.

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

**Unblocked — portable now.** Everything that receives control from C
was one feature (`go.callback`) wearing four costumes; the feature
landed and callback-spike proves the pattern, so these are Go by
schedule, not by necessity — ports someone can plan, not ports this doc
mandates (plan 0038's rule: a green spike is evidence, not a mandate).
- `nativeui/dispatch.go` (94 lines): dlopen/dlsym was expressible after
  leg 1; the callback trampoline — the remainder — is exactly what the
  spike registers. The *purest* FFI file in the package, not the
  FFI-free one the first inventory claimed.
- `macshell/shell.go`'s FFI core and `nativeui/keyview.go` /
  `macshell/menu.go` class synthesis: callback-spike at scale —
  synthesize the class, install literal-backed IMPs, register.
- `objcrt` (the hand-written runtime under the bindings): autorelease
  pool push/pop are plain calls a facade can bind; class registration
  was the callback half. The split can be scheduled.
- bindgen's protocol delegates, further out — a record of literals,
  each registered.

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

So rung 2's gating set is: the cstring bracket (landed), callbacks
(landed — leg 2, with the v1 literal-only note above), and a decision
about struct-call ABI (keep a Go kernel vs a new primitive). Nothing else in the sketch is speculative — every
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

- `FACADE-VALUE-STRUCT` ruling → decides `WIRE-DIES-INTERP-TO-SGOLA`
  (the wire's ~400 lines) and the shape of rung 2's geometry. Now the
  biggest open language gap on this frontier.
- purego **v0.11.0** — the struct-by-value pin is on alphas (upstream
  issue #225, milestone v0.11.0; see docs/design/bindgen.md). Bump to
  the release when it ships.
- `go.callback`'s v1 literal-only rule against the real ports — if
  reusing one body across selectors chafes, that is fileable.
  (`go.cstring` `1c6d6ed`, `go.callback` `cb15191` and
  `BINDGEN-TYPED-STRUCTS` are landed and verified; their records live
  in the sections above and in docs/design/bindgen.md.)
