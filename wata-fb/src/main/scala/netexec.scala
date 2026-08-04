package go

import language.experimental.saferExceptions

/** `go.exec` — the APP-OWNED facade over `os/exec`, bound the same `@go.bind`
 *  way as `go.syscall` (app-side device-layer code may bind stdlib packages
 *  directly). Curated to the two shapes the settings applet's diagnostics
 *  need: run a command to completion (the power rows and the wifi/data
 *  toggles, which only care whether it succeeded) and run one capturing its
 *  stdout (the gateway address and the modem's signal strength).
 *
 *  Go's `exec.Command(name string, arg ...string)` is VARIADIC and this
 *  dialect has no slice-spread lowering, so the arity is bound explicitly,
 *  the same way `wata-tui`'s facade does it. Two arguments is all `Diag`
 *  uses: every command it runs is one of system-menu's own shell lines,
 *  passed as `sh -c <line>` so the redirections in those lines (`2>&1`,
 *  `2>/dev/null`, the backgrounding `&`) mean what they mean there. */
@go.bind("os/exec")
object exec:
  /** Facade class for Go `*exec.Cmd`. */
  final class Cmd private[go] ():
    /** `Cmd.Run()` — lone `error`: runs the command and waits. A non-zero
     *  exit is an error, which is exactly the "the probe failed" signal the
     *  net test and the toggles report. */
    @go.name("Run") def run(): Unit throws sgo.GoError = ???
    /** `Cmd.Output()` — `([]byte, error)`: stdout of a command that ran to
     *  completion. Stderr is NOT captured, which is why the shell lines that
     *  need it carry their own `2>&1`. */
    @go.name("Output") def output(): go.Bytes throws sgo.GoError = ???

  /** `exec.Command(name)` — `name` without a path separator is resolved
   *  through `$PATH` (how "poweroff" finds /sbin/poweroff). */
  @go.name("Command") def command(name: String): go.exec.Cmd = ???
  /** `exec.Command(name, a1, a2)` — the `sh -c <line>` arity. */
  @go.name("Command") def command2(name: String, a1: String, a2: String): go.exec.Cmd = ???

/** `go.netif` — the APP-OWNED facade over stdlib `net`, curated to the one
 *  read the settings applet's IP rows need: a named interface's assigned
 *  addresses. Named `netif` because the toolchain core already owns
 *  `go.net` (the url/http facades), so a second `object net` in
 *  `package go` would collide. `net.Addr` is a Go INTERFACE, so the trait
 *  facade rides the bare-interface element rendering in `go.Slice`. */
@go.bind("net")
object netif:
  /** Facade trait for Go's builtin `net.Addr` interface. `String()` on an
   *  interface address is CIDR text ("192.168.1.5/24"). */
  trait Addr:
    @go.name("Network") def network(): String
    @go.name("String") def show(): String

  /** Facade class for Go `*net.Interface`. */
  final class Interface private[go] ():
    /** `Interface.Addrs()` — `([]Addr, error)`: the addresses assigned to
     *  the interface. */
    @go.name("Addrs") def addrs(): go.Slice[go.netif.Addr] throws sgo.GoError = ???

  /** `net.InterfaceByName(name)` — `(*Interface, error)`; errors when the
   *  named interface does not exist. */
  @go.name("InterfaceByName") def interfaceByName(name: String): go.netif.Interface throws sgo.GoError = ???
