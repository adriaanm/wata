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
closed; by-value structs are closed and consumed at scale.** Everything
a call-out needs — open a library, find a symbol, make the call, pass a
C string, carry the results — works on the current pin, and so does the
reverse crossing: `go.callback` registers a Sgola literal as a
C-callable address, proven end to end by `tools/callback-spike` and now
LIVE in the shipping mac client (the windowed frame hop). And
`FACADE-VALUE-STRUCT` landed and paid off immediately: the retained
AppKit interpreter is Sgola (`wata-mac`'s `MacStage`, plan 0038's
combined move), the frame wire's ~400 lines are deleted, and the whole
generated-appkit surface the interpreter needs binds through value
facades. What stays Go is a technology boundary (cgo, reflection, the
Go runtime, raw-pointer shapes bindgen refuses) plus two small filed
gaps (`FACADE-GO-NAMED-SCALAR`, `SUM-CASE-GENERIC-FIELD-EMITS-BARE-LIST`
— below), not a wall.

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

Since pin `f46551f` the DRF crossing checker (CONC-8..11) runs
default-on in consumer builds (it had been in-tree-only since M9); the
whole tree is clean under it — zero crossing walls. No
`.sgo/crossings-*.txt` inventories are written today; upstream ruled
that a DEFECT on our report (`CROSSING-INVENTORY-EMPTY-FILE`, queued
there): a clean module should write an explicit `total=0` inventory,
because absence-of-file cannot distinguish "clean" from "checker
silently off". When it lands, our ci grows an inventory-exists
assertion (`CI-ASSERT-CROSSING-INVENTORY` in the queue). Escapes exist
(`SGOLA_NOCROSSING=1` module-wide, `SGOLA_XCROSS=<key>` per-site) and
are unused. Captures wall at the EXPANSION site of an inline helper —
relevant only if we ever wrap `sgo.fork` in inline helpers of our own.

## Ruled and queued — what we know will be possible

These have designer rulings and sit in sgola's queue; the shape is
settled, only the landing is pending.

- **By-value structs across facades** (`FACADE-VALUE-STRUCT`,
  ratified 2026-08-09 at sgola `49411be`, **LANDED at the current pin
  `329656e` and CONSUMED**): a facade `case class` is a Go **value
  struct** — named-field composite-literal construction, by-value
  crossing, Go `==` — and a plain `final class` stays the opaque
  pointer handle. `tools/interp-spike` builds and runs green in ci, and
  the real consumer shipped: `wata-mac`'s appkit facade
  (`wata-mac/src/main/scala/appkit.scala`) binds geometry and ObjC
  handles as value structs and the Sgola interpreter runs the mac
  client on them (`WIRE-DIES-INTERP-TO-SGOLA` done — interp ported,
  frame wire deleted). Terms met when consuming the fix:
  - every facade case class must carry
    `override def toString: String = go.native` (opt-in, js.native
    style; missing it is a loud wall) — bindgen's facade emission must
    include it;
  - `hashCode`/`##` are synthesized faithfully, nothing to do;
  - Go **pointer-receiver methods** on a value facade wall loudly in
    v1 — if a binding needs one, that type stays a handle;
  - the documented handle-ergonomics idiom is an opaque type over the
    case class plus extension methods — no new facade machinery.
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

A sweep over every hand-written Go package in the tree (2026-08-19, plan
0068) found exactly one that was Go by history rather than by any of
these reasons: `go-pkgs/apns` carried the whole APNs protocol — the JWT,
the header set, the POST, and what a status code means — behind a header
sentence claiming an HTTP/2 POST was inexpressible. It is not: the bound
`net/http` facade issues arbitrary requests with arbitrary headers, and
Go negotiates HTTP/2 over TLS on the client itself, so the protocol
version is a property of the client the caller already holds. The pusher
now lives in `wata-server/src/main/scala/apnspush.scala` and Go keeps the
key alone. The test for whether something belongs here is worth stating
plainly, because that sentence passed review for a while: *name the Go
API that has no facade spelling*. "This protocol is complicated" is not
one.

**Technology boundaries — Go stays, and should.**
- **Signing with an operator key**: `go-pkgs/apns` — read a PEM-wrapped
  PKCS#8 EC key and produce a raw R||S ES256 signature. `crypto/ecdsa`
  over an opaque `*ecdsa.PrivateKey` a Sgola caller cannot hold, and the
  R||S-not-DER detail wants a Go test that verifies against the public
  key. Everything else about a push is dialect code.
- **cgo**: `go-pkgs/audio` (opus + tinyalsa, the handset's audio),
  `go-pkgs/macaudio`'s C shims where it has them, `go-pkgs/irohnet`
  (the Rust static library). A facade binds Go, not C; cgo modules are
  the Go side of a *different* FFI and stay Go by definition.
- **Reflect-driven dispatch**: `objc.Send[T](id, sel, ...any)`,
  `objc.ID.Send(sel, ...any)`, `purego.RegisterFunc`/`RegisterLibFunc`.
  Go permanently, by a **sgola designer ruling (2026-08-19)** rather than
  by any inability — and the difference matters, because this entry read
  as the latter for months and was wrong on both grounds it gave.

  The ruling: *even where a bind of the generic send is expressible,
  sgola rules against binding the reflect-driven entry point from the
  dialect.* Grounds are the curated-surface doctrine (M9 — the crossing
  checker and the curation manifest are the product; types restrict,
  values mean, local and loud): `Send[T](sel, ...any)` is untyped
  dispatch whose type and marshaling claims are unverifiable at the
  boundary, so every guarantee the facade tier exists to give is voided
  at exactly the call sites that would use it, and a manifest entry for
  it would be a signed blank check. bindgen emitting typed per-selector
  wrappers — arity and types pinned per selector, curation meaningful —
  is therefore **spec, not a workaround for a gap**.

  What was wrong before, kept because reasoning stated as impossibility
  is the failure worth remembering:
  - "No fixed arity" was false since 2026-08-05: Go `...T` binds as a
    Scala repeated param (`VARIADIC-FACADE-BIND`, ruled from this repo's
    own inbox — the table above says so two sections up, and wata-tui's
    facade uses it).
  - "`...any` is unbindable" cited an ABSENCE as a prohibition. There is
    no restriction row and no ruling on it; it has simply never been
    exercised. Scala `Any` ↔ Go `any` is the emitter's standing boxed
    carrier. The real caveat is marshaling, not binding: what purego's
    reflect dispatch does with sgola's representations (Int is `int32` —
    mind NSInteger widths; value-family structs mean nothing to ObjC) is
    unverified, so it would be a verification ticket plus a curation
    contract, never a compiler feature.
  - "No concrete result type" is the half that lands, for a reason the
    old text did not give: `Send[T]`'s `T` occurs only in the RESULT, so
    Go cannot infer it and any bind must emit an explicit instantiation
    (`objc.Send[bool](…)`). The facade language has no spelling for "this
    member is Go generic F at T" — the generic members that exist
    (`makeChan`, `makeSlice`, `cstring`, `callback`) are compiler
    intrinsics with dedicated emitter legs, and the generated bind
    surface is 100% monomorphic. Mintable in principle, deliberately
    unminted.
- **The Go runtime**: `go-pkgs/memprobe` (ReadMemStats), macshell's
  heap profiler, anything touching `runtime`/`unsafe` — the measurement
  and plumbing layer under the app, a few dozen lines each.

**`go-pkgs/httpc` is on a retirement path.** It exists to set a single
struct field: `net/http.Client.Timeout`. The facade binds the `Client`
type but not its fields, and an unbounded client is not an option here
(one half-open connection wedged the device — plan 0022), so every wata
client reaches its timeout through that shim. Ruled upstream on
2026-08-19 (`HTTP-CLIENT-TIMEOUT-FIELD`, queued): curated surfaces do
**not** grow raw mutable-field access, and the closure is curated members
on the bound `net/http` facade instead — a `newClient(timeout)` and/or
`withTimeout`, i.e. exactly this shim's two functions, upstream. When
that lands, the shim goes.

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

**Done — the interp port (2026-08-09, `WIRE-DIES-INTERP-TO-SGOLA`).**
`nativeui/interp.go`, `view/pixels/glyphs` and `keys` are Sgola
(`wata-mac`'s `MacStage` + `pixels/glyphs/mackeys.scala`); the frame
wire (~400 lines, both halves) was deleted, not ported. What remains in
`go-pkgs/nativeui` is exactly this doc's frontier, one function per
reason (`glue.go`'s header names them): the dispatch seam + pool
brackets and `OnMain` (the callback machinery above — and the windowed
frame hop is the first SHIPPING `go.callback` consumer), the
synthesized key view (class synthesis, now forwarding raw codes),
cross-class casts (a zero-field bound-subset facade cannot adopt
another class's id), the raw-RGBA bitmap crossing
(`initWithBitmapDataPlanes:` is a bindgen refusal — objcrt.NSData's
category), and two filed gaps:
- `FACADE-GO-NAMED-SCALAR`: RESOLVED same-day at sgola `47b2758`
  (repinned 2026-08-09). The spelling: `@go.name("NSBoxType") opaque
  type NSBoxType = Int` inside the facade object; members type
  params/results with it, the emitter mints the conversions both ways,
  and Go consts bind as parameterless defs. Scala-side minting needs an
  `inline def apply` companion (a plain def on a mapped-not-emitted
  facade object is called but never defined). Grounds `Int`/`Long` in
  v1 — and the ground deliberately need NOT equal the Go type's
  underlying kind: it is chosen by the VALUES (Int where Go's docs
  bound them, which an enumeration always does), so `uint`-grounded Go
  enums on the `Int` ground are the intended spelling, ruled in
  contract (spec sentence at sgola `19eb1e6`). One wall to know: a
  member returning a defined scalar AND declaring `throws` is walled.
  The enum-typed glue wrappers are gone; what remains of that block is
  the pure cast facets (`AsBox`/`AsImageView`).
- `SUM-CASE-GENERIC-FIELD-EMITS-BARE-LIST`: RESOLVED same-day at sgola
  `fb9621c` (repinned 2026-08-09) — the sum shape compiles now. The
  dissolved-sum design stays on its own merits: a whole-tree handoff is
  a root `PSet`, so `MacStage`'s pending cell is a flat `List[Patch]`.

Two authoring facts the port surfaced, worth knowing before writing the
next facade over generated bindings:

- **A zero-field bound-subset facade's `==` is vacuous.** Binding no
  fields (the `struct{ objc.ID }` handles) makes every two instances
  compare equal — Go struct equality over an empty bound set. Any real
  identity check needs a glue function over the Go field
  (`glue.SameView`); never compare handle facades with `==`.
- **Facade files sit in `package go`, which cannot name empty-package
  types.** Core `Bytes` resolves to `go.Bytes` there and
  `_root_.Bytes` does not exist (empty-package members are invisible
  from named packages) — so byte-carrying facade params are spelled
  `go.Bytes`, and producers build one with `go.makeSlice[Byte]`, not
  `Array[Byte].toBytes`.

The chrome (`macshell/login,menu,prefs,devices`, 581 lines of raw
`id.Send` sites) is in none of these categories: raw messaging here is not
a compiler gap but an *allowlist* gap — every raw site exists because its
class is not in bindgen's allowlist yet (`NSAlert`, `NSButton`,
`NSSecureTextField`; the `macui` target, plan 0037). Generating those
classes is the plan of record, endorsed upstream in the same 2026-08-19
ruling: do not wait on a generic-send surface, because there will not be
one.

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

- `FACADE-GO-NAMED-SCALAR` and `SUM-CASE-GENERIC-FIELD-EMITS-BARE-LIST`
  (filed 2026-08-09) → each landing deletes glue/workaround sites named
  by its key (grep the tree). (`FACADE-VALUE-STRUCT` landed and is
  consumed — the record moved to the sections above; the interp port
  and the wire deletion it gated are done.)
- purego **v0.11.0** — the struct-by-value pin is on alphas (upstream
  issue #225, milestone v0.11.0; see docs/design/bindgen.md). Bump to
  the release when it ships.
- `go.callback`'s v1 literal-only rule against the real ports — if
  reusing one body across selectors chafes, that is fileable.
  (`go.cstring` `1c6d6ed`, `go.callback` `cb15191` and
  `BINDGEN-TYPED-STRUCTS` are landed and verified; their records live
  in the sections above and in docs/design/bindgen.md.)
