package go

import language.experimental.saferExceptions

/** `purego`, bound directly — the same app-owned facade tools/objc-spike
 *  carries, copied here so the watch's ObjC excursions stay Sgola
 *  end-to-end. The three entry points are the whole non-reflective surface
 *  of purego: open a library, find a symbol, call it.
 *
 *  `SyscallN` returns THREE values (`r1, r2, err uintptr`); the FFI caller
 *  wants only the first, dropped by tuple-pattern binding. Registers beyond
 *  the args given are ZERO-filled by purego, which is how a zero argument is
 *  passed at all — `go.Uintptr` has no literal, so a trailing C `0` (a nil
 *  delegateQueue, `atomically:NO`) is spelled by OMISSION. */
@go.path("github.com/ebitengine/purego")
object purego:
  /** `Dlopen(path string, mode int) (uintptr, error)` — rides the `throws`
   *  lowering. */
  @go.name("Dlopen") def dlopen(path: String, mode: scala.Int): go.Uintptr throws sgo.GoError = ???

  /** `Dlsym(handle uintptr, name string) (uintptr, error)`. */
  @go.name("Dlsym") def dlsym(handle: go.Uintptr, name: String): go.Uintptr throws sgo.GoError = ???

  /** `SyscallN(fn uintptr, args ...uintptr) (r1, r2, err uintptr)` — three
   *  results are a 3-tuple (FACADE-DISCARD-EXTRA-RESULTS); variadic rides
   *  VARIADIC-FACADE-BIND. */
  @go.name("SyscallN") def syscallN(fn: go.Uintptr, args: go.Uintptr*): (go.Uintptr, go.Uintptr, go.Uintptr) = ???
