# 0038 — how far does Sgola reach into the FFI layer?

Status: accepted (leg 1 CLOSED — oracle runs green; leg 2 open)

## The problem

wata is sgola's proving ground, so "write that bit in Go" is a finding,
not a default. Today a hard boundary sits under the macOS client: 1139
lines of `go-pkgs/macshell` and 957 of `go-pkgs/nativeui` are Go because
nobody has established whether Sgola *can* express them. That is an
assumption, and it has been shaping the architecture unexamined.

The boundary also costs something concrete. The frame wire
(`macshell/wire.go` 236 lines, `wata-mac/wire.scala` 95, `wire_test.go`
68) exists **only** because a language edge sits between the differ and
the retained tree: one string per frame, a grammar written twice, and two
file headers reminding whoever edits them that the halves must agree byte
for byte. Delete the edge and the wire has no reason to exist.

## What is actually in the way

An inventory of the two packages, by what each line needs:

| what | where | needs |
|---|---|---|
| pure logic — the mirror, the differ target, keys, pixels, glyphs | `nativeui/view,keys,pixels,glyphs` (397 lines) | **nothing**; zero FFI of any kind |
| the main-queue seam | `nativeui/dispatch.go` (94 lines) | `Dlopen`/`Dlsym`/`NewCallback`/`uintptr` — the FFI core, see the correction below |
| the retained interpreter | `nativeui/interp.go` (359 lines) | only *typed* `appkit.*` wrappers — ordinary Go funcs, facade-able today |
| the chrome | `macshell/login,menu,prefs` (581 lines) | raw ObjC messaging: 64 `objc.Send`/`RegisterName` sites |
| class synthesis | `nativeui/keyview.go`, `macshell/menu.go` | Go **func values** as ObjC method bodies |
| the FFI core | `macshell/shell.go` | `Dlopen`/`Dlsym`/`SyscallN`/`NewCallback`, `uintptr`, autorelease pools |

Raw messaging is **not** a compiler gap. `objc.Send[T](id, sel, ...any)`
is generic and variadic-`any` and no facade will ever express it — but
bindgen already emits *typed* wrappers, which are ordinary Go functions a
facade binds today. Every raw site exists because its class is not in the
allowlist. So the chrome's 64 sites are an allowlist entry (a `macui`
target), not a language question, and bindgen keeps emitting Go.

The two real questions are the FFI core's, and they are the same
mechanism at two depths:

- **Call-out.** `purego`'s primitive layer is already facade-shaped:
  `SyscallN(fn uintptr, args ...uintptr) (r1, r2, err uintptr)`,
  `Dlopen(string, int) (uintptr, error)`, `Dlsym`. No generics, no
  reflect, no `any` — and variadic facade params were ruled in from this
  repo's own inbox (`VARIADIC-FACADE-BIND`, 2026-08-05). The one missing
  piece is `uintptr`, which does not exist anywhere in sgola: not in
  `core/gocore.scala`, not in the emitter.
- **Call-in.** `purego.NewCallback` takes a func value and reflects on
  its signature. This is exactly what `objc.RegisterClass`,
  `MainQueue().Async` and `NewKeyView` all need, so the callback half of
  FFI and the chrome's hardest dependency are ONE feature. Evidence it is
  a small one: sgola's own `SAM-CLOSURE-LOWERING` item describes "the
  SAME closure emission as FunctionN (**plain Go func literal**)" as the
  existing mechanism — so the representation is already right, and what
  is missing is the *binding* of a func-typed facade parameter, not a
  lowering.

## The decision

**Spike it, smallest first, and let the spike write the tickets.**

`tools/objc-spike/` — an ObjC bridge with no Go in it at all. Not
macshell, not a port; the smallest program that answers the question.

1. **Call-out leg.** `objc_getClass`, `sel_registerName`, `objc_msgSend`
   through `SyscallN`. Success: Sgola alone builds an `NSString`, sends
   it `length`, and gets the right number back.
2. **Call-in leg.** An ObjC method whose body is Sgola, via
   `NewCallback`. Success: AppKit calls it and the value arrives.

The spike is the evidence for the tickets, not the other way round: file
after the wall is hit, with the exact diagnostic, so sgola gets a repro
rather than a wish.

### Leg 1 result (run 2026-08-07) — `tools/objc-spike/REPORT.md`

Two gaps, and **everything else already works**. `(uintptr, error)`
rides the `throws` lowering unchanged; the variadic facade binding emits
a bare Go variadic call; every `go build` error is one of the two, with
no third problem behind them. The emitted Go is what a hand-written FFI
looks like.

1. `go.Uintptr` does not exist — not in `gocore.scala`, not in the
   emitter. Neither `Int` nor `Long` substitutes; Go rejects both.
2. A facade cannot discard a Go function's extra results, so
   `SyscallN(…) (r1, r2, err uintptr)` is unbindable. Asked as a
   discard rule, not as N-tuple support.

The spike is left spelled the way it WANTS to be spelled and does not
build: the day both gaps close it compiles, and the leg is answered
without anyone having to reconstruct what it was asking.

**Both RULED A the same day** (sgola inbox drain 2026-08-07, minted at
the top of its queue; Sonnet-tier, serialized behind each other, no date
promised). `go.Uintptr` becomes an opaque address-sized scalar — landed
`b85a713`, pinned here at `e35b162`, and verified: all seven `[E008]`
sites are gone and the compile stage passes. Carry forward that the
ticket's precedent citation was **wrong** — IOP-2 was revised 2026-07-12
and opaque `go.Int` is retired. The opacity stands on its own semantics:
a `uintptr` is not a reference and does not keep its referent alive.

The discard ruling is **not** the annotation this plan first recorded.
`@go.discardResults(n)` is dead, rejected for asking new syntax to
express what the language already has. What was ruled instead is a tuple
correspondence: a method's Go result parameters are `flatten(R) ++ (error
if throws)`, one level only, unambiguous because Go has no tuple type,
and running in both directions. So discarding is Scala's ordinary
tuple-pattern binding, which is character-for-character Go's:

```scala
def syscallN(fn: go.Uintptr, args: go.Uintptr*): (go.Uintptr, go.Uintptr, go.Uintptr)
val (r1, _, _) = purego.syscallN(msgSend, cls, sel)   // -> r1, _, _ := purego.SyscallN(…)
```

Two consequences for this plan. "Want `r2` but not `r1`" is no longer a
wall — it is `val (_, r2, _) = …`. And the composition worry about
`throws` putting a discard in the middle dissolves: `throws` claims the
trailing `error` and the tuple accounts for every result before it, so
there is no unclaimed middle to authorize. The spike is spelled this way
in the tree; the wall it now hits is the ticket itself, not a guess about
it.

`cstr` was deliberately NOT ruled, and leaving it `???` was endorsed:
obtaining a `uintptr` from a String's address is where GC liveness
actually bites, so it gets its own ticket when the spike reaches it —
carrying whatever we learn about what shape the address has to survive.

**Addendum 2026-08-08 — the call-out leg is CLOSED.** `go.cstring`
landed (sgola `1c6d6ed`, pinned here); the `cstr` stub is gone and the
spike's string sites are brackets. It builds, runs, and prints its
oracle:

```
objc-spike: length = 5
```

The first real `objc_msgSend` round-trip from pure Sgola. `just
objc-spike` (in ci) now runs the binary and asserts that line, so leg 1
stays answered. Both liveness lints (bracket result may not be
`go.Uintptr`; `p` may not escape to an outer binding) were provoked
deliberately and fire as spec-citing compile errors — detail in
`tools/objc-spike/REPORT.md`. What remains of this plan is leg 2, the
call-in half.

**What this plan does NOT decide** is whether macshell should be ported.
That question is downstream of the answer, and it changes shape depending
on it — a port if FFI is expressible, a rewrite-around if it is not.
Recording it here so a green spike does not get read as a mandate.

## Independently, and not blocked on any of it

Stages 1 and 2 need nothing from the compiler and should not wait:

1. `nativeui/view,keys,pixels,glyphs` → Sgola. 397 lines, zero FFI.
2. `nativeui/interp.go` → Sgola, over facades on the generated `appkit`
   bindings. **The wire dies here** — with the mirror types on the Sgola
   side, `wataui`'s `View` crosses by direct call and ~400 lines of
   encoder, decoder and grammar test go with it.

### The staging is inverted — corrected 2026-08-07

Two things the inventory above got wrong, found by reading the files
before starting stage 1:

**`dispatch.go` is not FFI-free.** It was counted in the "needs nothing"
row (it is the 94 lines that made 397 into 491), but it is the *purest*
FFI file in the package: `purego.Dlopen` on libSystem, `Dlsym` for
`_dispatch_main_q`, `purego.NewCallback` for the work trampoline, and
`uintptr` as the queue handle, the context word and the callback address.
It needs both gaps this plan filed **and** the func-typed facade param the
call-in leg is blocked on, so it belongs in the FFI-core row and moves
with `macshell/shell.go`, not before it.

**Stage 1 cannot precede stage 2; it rides with it.** The pure logic is
pure, but it has no Sgola consumer yet and three plain-Go ones: `interp.go`
walks the `View` mirror and calls `pixels`/`glyphs`, `macshell/wire.go`
*builds* mirror values, and `macshell/shell.go` calls `TranslateKeyCode`.
Moving the definitions to Sgola while the consumers stay in Go needs the
emitted Sgola package to be importable from plain Go — `emitpackage` makes
that possible now (it landed for `tools/phone-spike`), but it buys the
wrong thing: `wata-mac` links these sources whole-program *and* Go imports
their emission, so the types exist twice and the wire is still needed to
cross between the copies. The wire dies only when the interpreter is on
the Sgola side. So the order is: **port `interp.go`, pulling `view`,
`pixels` and `glyphs` across with it, in one move.** `keys.go` is the one
piece with a consumer that is staying (`shell.go`'s key view); it crosses
when `shell.go` hands raw `keyCode`s over the seam instead of translated
ones — a change local to that file.

### The interp gate, answered — `tools/interp-spike/REPORT.md`

The combined move was gated on whether a facade can express the `appkit`
surface `interp.go` uses. It cannot, for **one** reason in two sizes, and
nothing else is in the way: method binding, chained calls, nested field
reads and `@go.name` all come out exactly as a hand-written binding would.

A facade class type is always a Go **pointer**. `appkit`'s types are
values — `CGRect` is a struct of floats, and even the ObjC handles are
`type NSView struct{ objc.ID }` — so every crossing mismatches. And a
facade class cannot be constructed, which the interpreter needs to build
rects from wataui coordinates: a `case class` emits the Sgola-side name
(`undefined: appkit_CGRect`) and a `new` on a plain facade class crashes
the plugin. Filed as `FACADE-VALUE-STRUCT`; the spike is committed not
building, spelled the way the interpreter wants it.

The gate's other half needs no compiler change: `interp.go`'s `image/png`
encode is only how it gets bytes into an `NSImage`, and
`NSBitmapImageRep` over raw RGBA is already in the bindings.

Queue effect: `NATIVEUI-LOGIC-TO-SGOLA` is not a separate item and
`WIRE-DIES-INTERP-TO-SGOLA` is not blocked on it. What actually gates the
combined move is whether a facade can express the `appkit` surface
`interp.go` uses — in particular `CGRect`/`CGPoint`/`CGSize` passed and
returned **by value** (today's facades bind opaque handles and scalars),
and `image/png` encoding, which `interp.go` uses to hand `VImage` bytes to
`NSImage`. Those are the questions to answer before the port, and they are
the same shape as `BINDGEN-TYPED-STRUCTS`.

## Verification

- The spike's own oracle is arithmetic: `[[NSString stringWithUTF8String:
  "hello"] length]` is 5, and a wrong marshalling gives garbage, not a
  near miss. It gets a `just` recipe and a REPORT.md, following
  `tools/ios-spike/`.
- Each leg is reported as PASS, or as a WALL with the exact compiler or
  `go build` diagnostic, in the report. A wall is a result.
- Stage 1/2 are judged by the existing oracles — `just nativeui-tests`
  and `just mac-smoke` must stay green across the move, which is what
  makes them safe to do incrementally.

## Out of scope

- Porting macshell (see above).
- bindgen emitting Sgola instead of Go. If the call-out leg passes this
  becomes a real option; it is not an argument for doing it.
- The chrome's `macui` bindgen target — needed for plan 0037 slices 4
  and 5 regardless, tracked there.
