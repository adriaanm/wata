# objc-spike — can Sgola reach the C ABI on its own? (plan 0038, leg 1)

**Result: two named gaps, and everything else already works.** The
emitted Go for an ObjC message send is *correct* — the throws lowering,
the variadic spread, the call shape — apart from one missing type and one
missing arity rule. The spike does not build, on purpose: it is left
spelled the way it WANTS to be spelled, so the day both gaps close, it
compiles and the leg is answered.

## What was tried

`src/main/scala/purefacade.scala` binds purego's entire non-reflective
surface — `Dlopen`, `Dlsym`, `SyscallN` — and `main.scala` uses it to do
`[[NSString stringWithUTF8String:"hello"] length]`, which is 5. There is
**no Go code of ours anywhere in it**: `go-pkgs/puredep` is a module with
a blank import and no functions, existing only because `sgo.build`'s
`godep` names a local directory. If a function ever appears in that
package, the spike has failed.

Those three entry points are a complete C FFI between them: open a
library, find a symbol, call it. Everything else purego offers
(`RegisterLibFunc`, `RegisterFunc`, `NewCallback`) is reflect-driven over
Go func VALUES — that is the call-IN half, leg 2, and a different
question.

## Gap 1 — `uintptr` does not exist

```
-- [E008] Not Found Error: purefacade.scala:28:38
28 |  @go.name("Dlsym") def dlsym(handle: go.Uintptr, name: String): …
   |                                      ^^^^^^^^^^
   |                                      type Uintptr is not a member of go
```

Seven sites. It is absent from `core/gocore.scala` AND from the emitter
(no occurrence of "uintptr" anywhere in `plugin/` or `core/`). Every
pointer-sized value in an FFI is this type, and it is not `Int` and not
`Long`: Go treats it as distinct and rejects both.

**RULED A** (sgola inbox drain, 2026-08-07): `go.Uintptr` is being added
as an opaque address-sized scalar — bindable in facade signatures,
passable between facade calls, and nothing else. No arithmetic, no
`Int`/`Long` conversions, no literals.

The ticket reasoned from "`go.Int`'s opaque IOP-2 posture", and that
citation was **wrong**: IOP-2 was revised 2026-07-12 and opaque `go.Int`
is retired — Go's `int` maps to `Int` now. The ask was right anyway, and
the ruling gives opacity a better rationale than the one borrowed: a
`uintptr` is not a reference, it does not keep its referent alive, and
arithmetic on one is valid only inside a single `unsafe.Pointer`
expression. Opacity here is the semantics, not staging.

## Gap 2 — a facade cannot discard extra Go results

`SyscallN(fn uintptr, args ...uintptr) (r1, r2, err uintptr)` returns
three values and an FFI caller wants only `r1`. Go spells that
`r1, _, _ := …`; a facade has no way to say it, because `throws` →
`(T, error)` is the only multi-result shape sgola binds.

```
./main.go:40:13: assignment mismatch: 1 variable but purego.SyscallN returns 3 values
```

Worth stating as a discard rule rather than as "support N-tuples":
nothing wants `r2` or `err` here, tuples would need a representation, and
the emitter change is one line at the call site.

**RULED A** (same drain) — but not as this section asked. The annotation
is dead and the ruling is a tuple correspondence; see "Gap 2 is respelled"
below, which is the shape the spike now carries.

## What already works — the more useful half of the result

Substituting `Long` for `go.Uintptr` runs the whole pipeline to
`go build`, and the emitted Go is exactly what a hand-written FFI would
look like:

```go
getClass, err := purego.Dlsym(libobjc, "objc_getClass")
if err != nil {
    fmt.Println(("objc-spike: FAILED " + err.Error()))
} else {
    …
    cls = purego.SyscallN(getClass, Main_cstr("NSString"))
    str = purego.SyscallN(msgSend, cls, selStr, Main_cstr("hello"))
```

Three things are pinned by that output:

1. **`(uintptr, error)` rides the existing `throws` lowering unchanged** —
   `Dlopen`/`Dlsym` emit the idiomatic `v, err := …; if err != nil` with
   no complaint. The error half of FFI is done.
2. **Variadic facade binding carries FFI** — `SyscallN(a, b, c)` emits as
   a bare Go variadic call, no `[]uintptr{…}` wrapper. That is
   `VARIADIC-FACADE-BIND`, ruled from this repo's own inbox on
   2026-08-05, doing real work in its first serious consumer. Reported
   back upstream and banked — with a gap named back at us: every call
   site here passes INDIVIDUAL args, so the `Array` spread form (`expr*`)
   is untouched and still rests on its own scenario alone
   (`VERIFY-VARIADIC-ARRAY-SPREAD`).
3. **The call shape needs nothing else.** Every `go build` error is one
   of the two gaps above. There is no third problem hiding behind them.

## Gap 1 is closed — verified 2026-08-07

`go.Uintptr` landed upstream (`b85a713`) and all **seven** `[E008]` sites
here are gone; the compile stage passes for the first time. It binds as a
facade parameter, a result and a variadic element, survives being stored
in a `val`, and passes on to the next facade call. The emitted Go is what
a hand-written FFI looks like:

```go
var cls uintptr
cls = purego.SyscallN(getClass, Main_cstr("NSString"))
```

The `(uintptr, error)` prediction is now compiled rather than asserted:
`Dlopen`/`Dlsym` emit `x, err := purego.Dlsym(…)` with the error arm
inline — no wrapper, no adapter. `def cstr(s: String): go.Uintptr` also
compiles as a declaration, so the type is no longer what stops it; the
liveness question that ticket exists for is untouched, since nothing has
called it into a real address yet.

## Gap 2 is respelled — the tuple ruling, in the tree 2026-08-08

Its ruling is in and is **not** the annotation this report's ticket asked
for. A Scala tuple in a result type *is* Go's result parameters — a
method's Go results are `flatten(R) ++ (error if throws)`, one level, in
both directions, unambiguous because Go has no tuple type. So the spike is
respelled, and now carries

```scala
def syscallN(fn: go.Uintptr, args: go.Uintptr*): (go.Uintptr, go.Uintptr, go.Uintptr)
val (r1, _, _) = purego.syscallN(msgSend, cls, sel)
```

which is to emit `r1, _, _ := purego.SyscallN(…)`. Scala's discard spelling
and Go's are the same spelling, so nothing had to be invented. Two things
follow: "want `r2` but not `r1`" is just `val (_, r2, _) = …`, no wall; and
the **destructured** form is the one to use for anything reference-shaped,
because the bound-whole leg depends on `TUPLE-REF-COMPONENT-ASSIGN` (a
tuple component lowering to a Go interface erases to `any` and the
assertion is missing at assignments — wata hit that independently the same
day, from `wataclient`'s notification edge detector). Everything here is
`uintptr`, so this spike does not exercise that.

Against the pin (`e35b162`, pre-fix) the wall is now Gap 2 alone and it is
worth reading, because it shows both legs of the ruling in one output. The
compile stage passes — the facade's tuple result type is accepted — and
`go build` reports two distinct failures:

```
./main.go:38:15: undefined: Tuple3__R__R__R
./main.go:41:7: assignment mismatch: 1 variable but purego.SyscallN returns 3 values
```

The second is leg (1): the facade call is still emitted as a single-valued
Go call. The first says the tuple type the result now names is never
instantiated — a facade result type does not reach whatever mints the
`TupleN__…` struct, so even the materialized leg has nothing to build. And
the emitted body shows leg (2)'s target sitting there fully formed:

```go
_2_ = func() Tuple3__R__R__R {
	var x1 Tuple3__R__R__R
	x1 = purego.SyscallN(getClass, Main_cstr("NSString"))
	var x2 Tuple3__R__R__R
	x2 = x1
	var cls uintptr
	cls = x2._1
	…
```

That is dotc's tuple-pattern desugaring verbatim — temp val, alias, `._N`
selection — which is exactly the shape leg (2) collapses into a direct
multi-assign. The unread slots are already `_`-shaped in the source, so the
`_` columns should fall out of the existing unread-locals scan.

This report's ticket argued against tuples on the grounds that they would
force a representation and allocation decision. The representation was
settled long ago — tuples are a blessed value composite with a Go struct
rep — so only allocation was live, and it is ruled acceptable here. The
instinct was right and the premise was stale; worth remembering as a
reminder to check a constraint before arguing from it.

## Gap 2 is closed — verified 2026-08-08 (pin `40cc1f8`)

**The spike builds.** `just objc-spike` runs clean through `go build`, so
leg 1 is answered the way this report said it would be: the spike was left
spelled the way it wants to be spelled, and the day the fix landed it
compiled. It is in `ci` now, so it keeps compiling.

Both failures are gone, and they were two independent defects: the stamp
pass had no leg for tuples, so a tuple only ever RECEIVED from a facade was
minted nowhere (compile and link both reported success — only `go build`
saw it), and a facade call whose result is a tuple is now wrapped to bind N
results and build the tuple value.

Running the binary reaches `cstr` and panics on its `???`, which is the
correct stopping point: every compiler-shaped question in this leg is
answered, and the one left is `FFI-CSTR-ADDRESS`.

**Leg (2) is not done, and the emitted Go says what it costs.** The
prediction above was `x2._1` — a direct field read. What is actually
emitted is

```go
x1 = func() Tuple3__R__R__R { _r1, _r2, _r3 := purego.SyscallN(getClass, Main_cstr("NSString")); return Tuple3__R__R__R{_1: _r1, _2: _r2, _3: _r3} }()
var cls uintptr
cls = x2._1.(uintptr)
```

with

```go
type Tuple3__R__R__R struct { _1 any; _2 any; _3 any }
```

So the slots are `any`, not `uintptr`. The cost of the materialized leg is
therefore not "an allocation" but a boxing per slot plus the struct — three
interface values built and one asserted back, for a call whose whole point
is to be a bare register move. That also corrects this report's claim that
"everything here is `uintptr`, so this spike does not exercise
`TUPLE-REF-COMPONENT-ASSIGN`": the assertion is right there in the output.
It is *sound* — `uintptr` is concrete, so the assertion cannot fail — which
is why this is an optimization and not a correctness item, as ruled.

Where that cost would show up is worth naming precisely, because the
obvious place is the wrong one: the handset does not use purego at all
(its FFI is cgo through `go-pkgs/audio`), so no handset profile will ever
see this. The surface that would is the macOS/iOS client, where every
AppKit call in a frame goes through `SyscallN`. Nothing there is written
against this path yet.

Arity is not a constraint here: `SyscallN` returns exactly 3, so the
`> 3` tuple wall is not reachable from this spike.

## What this does NOT answer

Leg 2, the callback half: `purego.NewCallback` takes a func value and
reflects on its signature, and that is what `objc.RegisterClass`,
`MainQueue().Async` and `NewKeyView` all need. Blocked on func-typed
facade params — which sgola's own `SAM-CLOSURE-LOWERING` suggests is a
binding question rather than a lowering one, since closures already emit
as plain Go func literals.

`cstr` is also still `???`: taking the address of a NUL-terminated string
is its own small question (Go's own answer is `unsafe.Pointer` +
`syscall.BytePtrFromString`), deliberately left unanswered until the two
gaps above close and it can be tried rather than guessed.

## Running it

```
just objc-spike     # builds it; the two gaps are the expected output
```
