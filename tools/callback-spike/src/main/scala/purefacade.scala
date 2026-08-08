package go

import language.experimental.saferExceptions

/** `purego`, bound directly — plan 0038's call-in leg needs the same
 *  non-reflective surface the call-out leg proved: open a library, find a
 *  symbol, call it. This facade is the objc-spike one, unchanged — the leg
 *  under test here adds nothing TO it, and that is the point.
 *
 *  What it deliberately does NOT declare is `go.callback`. The ruling
 *  (sgola `29536af`) makes callback registration a LANGUAGE-provided form,
 *  like `go.cstring`: `go.callback(f): go.Uintptr`. A facade decl for it
 *  would be inventing a different feature — the registration is a crossing
 *  (`f`'s captures face the fork predicate) and only the compiler can check
 *  that, so it cannot be an ordinary bound Go function. */
@go.path("github.com/ebitengine/purego")
object purego:
  /** `Dlopen(path string, mode int) (uintptr, error)` — rides the `throws`
   *  lowering. */
  @go.name("Dlopen") def dlopen(path: String, mode: scala.Int): go.Uintptr throws sgo.GoError = ???

  /** `Dlsym(handle uintptr, name string) (uintptr, error)`. */
  @go.name("Dlsym") def dlsym(handle: go.Uintptr, name: String): go.Uintptr throws sgo.GoError = ???

  /** `SyscallN(fn uintptr, args ...uintptr) (r1, r2, err uintptr)` — the
   *  three results are a 3-tuple, discarded by tuple-pattern binding.
   *  Registers beyond the args given are ZERO-filled by purego, which is
   *  how a zero argument is passed at all: `go.Uintptr` is opaque and has
   *  no literals, so a trailing C `0` (e.g. `objc_allocateClassPair`'s
   *  `extraBytes`) is spelled by omission. */
  @go.name("SyscallN") def syscallN(fn: go.Uintptr, args: go.Uintptr*): (go.Uintptr, go.Uintptr, go.Uintptr) = ???
