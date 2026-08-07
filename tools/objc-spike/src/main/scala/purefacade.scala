package go

import language.experimental.saferExceptions

/** `purego`, bound directly — plan 0038's call-out leg.
 *
 *  The three entry points below are the whole non-reflective surface of
 *  purego, and between them they are a complete C FFI: open a library, find a
 *  symbol, call it. Everything else purego offers (`RegisterLibFunc`,
 *  `RegisterFunc`, `NewCallback`) is reflect-driven over Go func VALUES,
 *  which is the call-IN half and a separate question.
 *
 *  Two things here are the experiment:
 *
 *  1. `uintptr`. Every one of these signatures is pointer-sized-integer in
 *     and out. It is a distinct Go type — not `int`, not `int64` — so it
 *     cannot be spelled with what sgola has.
 *  2. `SyscallN` returns THREE values (`r1, r2, err uintptr`) and the FFI
 *     caller wants only the first. Go writes that `r1, _, _ := …`. */
@go.path("github.com/ebitengine/purego")
object purego:
  /** `Dlopen(path string, mode int) (uintptr, error)` — the `(T, error)`
   *  shape `throws` already lowers to, so this one should ride existing
   *  machinery once the return TYPE is expressible. */
  @go.name("Dlopen") def dlopen(path: String, mode: scala.Int): go.Uintptr throws sgo.GoError = ???

  /** `Dlsym(handle uintptr, name string) (uintptr, error)`. */
  @go.name("Dlsym") def dlsym(handle: go.Uintptr, name: String): go.Uintptr throws sgo.GoError = ???

  /** `SyscallN(fn uintptr, args ...uintptr) (r1, r2, err uintptr)` — the
   *  variadic is fine (VARIADIC-FACADE-BIND, ruled from this repo's inbox);
   *  the three results are not. */
  @go.name("SyscallN") def syscallN(fn: go.Uintptr, args: go.Uintptr*): go.Uintptr = ???
