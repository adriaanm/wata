package go

import language.experimental.saferExceptions

/** `go.exec` — the APP-OWNED facade over `os/exec`, bound the same `@go.bind`
 *  way as `go.syscall` (app-side device-layer code may bind stdlib packages
 *  directly). Curated to the one shape the settings applet's diagnostics
 *  need: build a command, run it to completion, read its stdout — how the
 *  power rows run poweroff / reboot-* , the same commands system-menu runs. */
@go.bind("os/exec")
object exec:
  /** Facade class for Go `*exec.Cmd`. */
  final class Cmd private[go] ():
    /** `Cmd.Output()` — `([]byte, error)`: runs the command, waits, returns
     *  stdout. Chosen over `Run()` because its `(T, error)` shape rides the
     *  ordinary throws val-bind lowering (a lone-`error` return does not). */
    @go.name("Output") def output(): go.Bytes throws sgo.GoError = ???

  /** `exec.Command(name)` — `name` without a path separator is resolved
   *  through `$PATH` (how "poweroff" finds /sbin/poweroff). */
  @go.name("Command") def command(name: String): go.exec.Cmd = ???

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
