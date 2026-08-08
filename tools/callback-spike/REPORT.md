# callback-spike — can an ObjC method's body be Sgola? (plan 0038, leg 2)

The call-out leg is closed: `tools/objc-spike` does a real `objc_msgSend`
round-trip from pure Sgola and runs green in ci. This spike is the other
direction — control ENTERING Sgola from the ObjC runtime. It synthesizes a
class at runtime (`objc_allocateClassPair` on NSObject), installs a Sgola
function's address as the IMP of a method `wataProbe`
(`class_addMethod(cls, sel, cbAddr, "L@:")`), registers the pair,
alloc/inits an instance and msgSends it the selector. The dispatch jumps
into the Sgola function, and its return value comes back through
`objc_msgSend`.

Run it with `just callback-spike`. It does **not** build, and that is the
finding — the spike is committed spelled the way the ruled contract says
it will be spelled, so the landing day is a compile-and-run, not a design
session.

## The contract it is spelled against

`go.callback` is **RULED** (sgola `29536af`, 2026-08-08; restated in
`docs/design/sgola-ffi.md` "Ruled — the call-in half"):
`go.callback(f): go.Uintptr` is a *registration* returning a **free**
address value — deliberately inverting `go.cstring`'s bracket, because a
purego trampoline has process lifetime and the liveness objection that
forced `cstr` into a bracket does not exist here. The spike takes each
clause literally:

- **module/startup scope only** (the platform cap makes per-frame minting
  fail loudly) → `val cbAddr: go.Uintptr = go.callback(onCall)` is a
  module-scope val;
- **address-sized vocabulary** (`go.Uintptr` params and result,
  monomorphic) → `def onCall(self: go.Uintptr, cmd: go.Uintptr):
  go.Uintptr`;
- **captures face the fork predicate** → `onCall` captures nothing, so the
  predicate is trivially satisfied;
- the result is opaque `go.Uintptr`, consumed only as `class_addMethod`'s
  IMP argument.

`go.callback` is a language-provided form like `go.cstring`, not a facade
binding — the registration is a crossing and only the compiler can check
`f`'s captures — so the facade here is the objc-spike one **unchanged**:
`Dlopen`/`Dlsym`/`SyscallN`, nothing added. `go-pkgs/puredep` is the same
no-functions dependency-plumbing module; there is no Go code of ours
anywhere in the chain.

## The current wall — verbatim

Implementation is queued upstream (verdict A, one behind
`GENERIC-FAMILY-EQUALS`) and has **not** landed. On the current pin the
build dies at exactly the registration site, and nowhere else:

```
-- [E008] Not Found Error: …/tools/callback-spike/src/main/scala/main.scala:54:30
54 |  val cbAddr: go.Uintptr = go.callback(onCall)
   |                           ^^^^^^^^^^^
   |                           value callback is not a member of go
1 error found
sgo: compile stage: frontend: callback-spike compile failed (exit 1)
```

One error, no second problem behind it: every other line — the seven
dlsym'd symbols, five `go.cstring` brackets, the tuple-pattern discards,
the all-underscore discard of `objc_registerClassPair`'s void call —
already compiles on today's pin. The call-out vocabulary carries the
whole class-synthesis dance; the missing piece is precisely the one the
ruling names.

## Two contract edges the spelling surfaced

Findings from pre-shaping, worth carrying to the landing:

1. **A callback cannot return a constant.** The natural oracle for this
   spike is an IMP returning 42 — the classic distinctive value. It
   cannot be spelled: `go.Uintptr` is opaque by its own safety argument
   (no literals, no `Int`/`Long` conversions, gocore.scala), and the
   ruled callback signature admits only `go.Uintptr`. So a callback body
   can today produce a result ONLY from its parameters or from another
   FFI call. The spike's oracle returns `self` instead — see below —
   which is fine here, but real method bodies (`keyview.go` returns YES/
   NO booleans; menu targets return void) will need either the
   void-result form the ruling already defers to implementation, or a
   story for small constants. Worth pinning alongside the arity bound.

   **Answered upstream the same day** (sgola `a48248e`, replying to our
   `CALLBACK-RESULT-VOCABULARY` note): the trampoline marshals to a
   Scala-facing signature of ordinary values — params
   `go.Uintptr | Int`, result `go.Uintptr | Int | Unit` — so void
   methods are admitted, a BOOL predicate declares `Int` and answers
   0/1, and constants are simply `Int`s. On the landing, this spike's
   oracle should simplify to `def onCall(...): Int = 42` and assert the
   42; the return-self spelling below is the pre-refinement record.
   (`==` on `go.Uintptr` stays deliberately ungranted —
   `UINTPTR-IDENTITY-COMPARE` upstream is the fileable-against key —
   but the Int oracle removes this spike's need for it.)
2. **Zero is spelled by omission.** `objc_allocateClassPair`'s
   `extraBytes` argument is 0, and there is no `go.Uintptr` zero. The
   spike leans on purego's `SyscallN` zero-filling the registers it is
   not given, and simply omits the trailing argument. It works and is
   honest, but it is a convention riding an ABI detail, and the same
   constant problem as (1) in different clothes.

## What PASS will look like

The oracle is arithmetic and unforgiving, like the other spikes': `onCall`
returns its receiver, so the msgSend's r1 must be exactly the address of
the instance we just alloc/init'd. A mis-registered IMP — wrong
trampoline ABI, wrong argument order, a callback table off by one — gives
a crash or garbage, never the one address in play. Opaque `go.Uintptr`
has no `==`, so the comparison goes through its render surface (string
concat, which objc-spike already exercises):

```
callback-spike: added = 1
callback-spike: inst  = <some address>
callback-spike: probe = <the same address>
callback-spike: PASS
```

## What unblocks on a pass

Everything that receives control from C is this one feature (sgola-ffi.md
"what to watch"):

- `nativeui/dispatch.go` (94 lines) — the main-queue trampoline; its
  dlopen/dlsym half was expressible after leg 1, the callback was the
  remainder;
- `nativeui/keyview.go` and `macshell/menu.go` — class synthesis with Go
  func-value method bodies, which is *literally this spike* at scale;
- the `objcrt` split — autorelease push/pop are plain calls a facade can
  already bind, class registration was the callback half; the
  hand-written runtime under the bindings splits when this lands;
- bindgen's protocol delegates, further out.

On the landing notice: repin, this spike must compile and print PASS, then
wire `just callback-spike` into ci the way objc-spike is, and file the
VERIFY ticket back.

## Running it

```
just callback-spike     # dies at the go.callback site; that IS the finding
```
