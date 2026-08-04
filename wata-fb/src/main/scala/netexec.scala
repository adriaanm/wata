package go

import language.experimental.saferExceptions

/** `go.exec` — the APP-OWNED facade over `os/exec`, bound the same `@go.bind`
 *  way as `go.syscall` (app-side device-layer code may bind stdlib packages
 *  directly). Curated to the one shape the settings applet's diagnostics
 *  need: build a command, run it to completion, read its stdout — how the
 *  info rows run `ip` and the power rows run poweroff / reboot-* , the same
 *  commands system-menu shells out to. */
@go.bind("os/exec")
object exec:
  /** Facade class for Go `*exec.Cmd`. */
  final class Cmd private[go] ():
    /** `Cmd.Output()` — `([]byte, error)`: runs the command, waits, returns
     *  stdout. Chosen over `Run()` because its `(T, error)` shape rides the
     *  ordinary throws val-bind lowering (a lone-`error` return does not). */
    @go.name("Output") def output(): go.Bytes throws sgo.GoError = ???

  /** `exec.Command(name, arg1, arg2, arg3)` — one binding per arity used
   *  (the facade layer has no varargs). `name` without a path separator is
   *  resolved through `$PATH` (how "poweroff" finds /sbin/poweroff). */
  @go.name("Command") def command(name: String): go.exec.Cmd = ???
  @go.name("Command") def command4(name: String, a1: String, a2: String, a3: String, a4: String): go.exec.Cmd = ???
