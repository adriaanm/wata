# callback-spike — can an ObjC method's body be Sgola? (plan 0038, leg 2)

**ANSWERED: yes.** On sgola `cb15191` (where `go.callback` landed, gate
69/69) the spike compiles, runs, and its oracle holds — the first
C-to-Sgola control transfer in the project. It synthesizes a class at
runtime (`objc_allocateClassPair` on NSObject), installs the address
`go.callback` returned as the IMP of a method `wataProbe`
(`class_addMethod(cls, sel, cbAddr, "q@:")`), registers the pair,
alloc/inits an instance and msgSends it the selector. The dispatch jumps
through purego's trampoline into the Sgola literal, and its return value
comes back through `objc_msgSend`:

```
callback-spike: added = 1
callback-spike: probe = 42
callback-spike: PASS
```

Run it with `just callback-spike`, which builds, runs, and grep-asserts
the exact `PASS` line; ci includes it, so ci asserts the oracle — same
discipline as objc-spike. (No `os.Exit` in the dialect, so the process
exit is 0 either way; the grep is the assertion.)

Together with `tools/objc-spike` (the call-out leg) this closes plan
0038's spike phase: pure Sgola can both call the ObjC runtime and BE
CALLED by it.

## The landed v1 contract it exercises

`go.callback((self: go.Uintptr, cmd: go.Uintptr) => 42): go.Uintptr` —
a *registration* returning a **free** address value (a purego trampoline
has process lifetime, so unlike `go.cstring` no bracket is needed). The
clauses, as taken literally by the spike:

- **function LITERAL required in v1, with ASCRIBED param types** — the
  ascriptions read as the declared foreign signature (one generic member
  upstream, so inference has nothing to work from). The pre-landing
  spelling here was a named def (`go.callback(onCall)`); that does not
  compile. If the literal-only rule chafes in the real ports —
  dispatch/keyview reusing one body across selectors — that is a
  fileable-against edge of the ruling, not a bug;
- **ordinary-value vocabulary** (sgola `a48248e`, refined on this spike's
  pre-shaping evidence): params `go.Uintptr | Int`, result
  `go.Uintptr | Int | Unit`, arity ≤ 15 (purego's own SyscallN ceiling).
  The trampoline marshals, so a constant result is simply an `Int` — the
  oracle returns 42 directly, and the old return-self spelling (forced
  when the vocabulary was `go.Uintptr`-only) is gone. Unit callbacks emit
  a trampoline returning 0 uniformly (purego zero-result callbacks are
  SysV-only, so the portable form was forced);
- **module/startup scope only** — the ~2000 trampoline cap fails loudly
  if minted per-frame; `cbAddr` is a module-scope val;
- **captures face the CONC-8 fork predicate at the registration site** —
  this literal captures nothing, so the predicate is trivially
  satisfied. Real ports with mutable state hoist it into Atomic/Mutex
  cells.

The facade is the objc-spike one **unchanged** (`Dlopen`/`Dlsym`/
`SyscallN`, nothing added) — `go.callback` is a language form like
`go.cstring`, not a facade binding, because only the compiler can check
the literal's captures. `go-pkgs/puredep` is the same no-functions
dependency-plumbing module; there is no Go code of ours in the chain.

## Notes the real ports will want

- **Type encoding**: the method registers as `"q@:"` (long-long return,
  receiver, selector) because the callback answers an integer. The
  trampoline does the marshalling, but the encoding string is read by
  the frameworks, so it says what the method returns. The pre-landing
  `"L@:"` was not load-bearing; `"q"` matches a 64-bit integer result
  honestly. BOOL predicates (keyview) would encode the ObjC BOOL and
  answer 0/1 as `Int`; void menu targets encode `"v@:"` with a `Unit`
  result.
- **Zero is still spelled by omission** for `go.Uintptr` arguments:
  `objc_allocateClassPair`'s `extraBytes` is 0 by leaving the trailing
  argument off — SyscallN zero-fills unsupplied registers. The `Int`
  admission covers callback *results and params*, not general
  `go.Uintptr` argument positions, so this convention stands.
- **go.mod contract** (sgola `22a7c16`): the user owns go.mod — sgo
  never injects a require, it CHECKS the declared requires against the
  emitted import set and fails with a fix-menu error naming the exact
  line if purego is missing. This spike's `go-pkgs/puredep` godep (a
  blank-import of purego with a committed go.sum) is the correct
  spelling under that rule, not a workaround; a direct require in the
  module's go.mod would be equally first-class. See
  docs/design/sgola-ffi.md.

## What this unblocks

Everything that receives control from C was waiting on this one feature;
it is now portable (ports someone can schedule — a green spike is
evidence, not a mandate):

- `nativeui/dispatch.go` (94 lines) — the main-queue trampoline; its
  dlopen/dlsym half was expressible after leg 1, the callback was the
  remainder;
- `nativeui/keyview.go` and `macshell/menu.go` — class synthesis with
  method bodies in our language, which is *literally this spike* at
  scale;
- the `objcrt` split — autorelease push/pop are plain calls a facade can
  already bind, class registration was the callback half; the
  hand-written runtime under the bindings can split when a port is
  scheduled;
- bindgen's protocol delegates, further out.

## Running it

```
just callback-spike     # build + run + grep-assert the PASS line (in ci)
```
