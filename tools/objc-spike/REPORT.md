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

**RULED A and EXPLICIT** (same drain): loud by default, opt in with
`@go.discardResults`. One refinement on the rule as sketched —
trailing-only does not compose with `throws`, because `(T, U, error)`
puts the discard in the MIDDLE. The ruled rule: declared results bind
left-to-right **from the front**, `throws` claims the trailing `error`,
and the annotation authorizes dropping whatever is left unclaimed in
between. That covers `r1, _, _` and the throws shape both. Wanting `r2`
but not `r1` stays a loud wall, and tuples stay refused on the grounds
the ticket gave.

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
