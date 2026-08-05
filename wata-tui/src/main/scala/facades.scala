package go

import language.experimental.saferExceptions

/** The APP-OWNED `@go.bind` facades the tui needs on top of the core `go.*`
 *  surface: stdin (the REPL's input), a line scanner over it, an external
 *  process runner with arguments (the audio player), and the three syscalls a
 *  binary file write takes.
 *
 *  Naming: the core facade already owns `go.os` (open/File), and a second
 *  `object os` in `package go` would collide, so the object that binds the
 *  same Go package for `os.Stdin` is `go.osx` — the same reason wata-fb's
 *  `net` binding is called `go.netif`. */

/** `go.osx` — the `os` package, curated to the one value the REPL reads from:
 *  the process's standard input. `@go.bind` emits a parameterless def as
 *  `os.Stdin`; `*os.File` satisfies Go's `io.Reader`, which is what the
 *  scanner takes. */
@go.bind("os")
object osx:
  @go.name("Stdin") def Stdin: go.io.Reader = ???

/** `go.bufio` — a line reader over a `go.io.Reader`. `Scanner` is the whole
 *  curation: `Scan()` advances to the next line (false at EOF), `Text()`
 *  returns it without the newline. The default 64KiB line cap is well past
 *  anything a REPL command needs, so no buffer-sizing surface is bound. */
@go.bind("bufio")
object bufio:
  /** Facade class for Go `*bufio.Scanner`. */
  final class Scanner private[go] ():
    @go.name("Scan") def scan(): Boolean = ???
    @go.name("Text") def text(): String = ???

  @go.name("NewScanner") def newScanner(r: go.io.Reader): go.bufio.Scanner = ???

/** `go.exec` — `os/exec`, curated to "run this command to completion" like
 *  wata-fb's, but WITH ARGUMENTS: the player is `mpv <file>` or
 *  `ffplay -nodisp -autoexit <file>`.
 *
 *  Go's `exec.Command(name string, arg ...string)` binds as a Scala varargs
 *  param (toolchain `4cbea19`, VARIADIC-FACADE-BIND): pass args
 *  individually, or spread an `Array[String]` with `xs*` — Array is the one
 *  legal spread vehicle (Scala star-arg typing; already Go-slice-shaped). */
@go.bind("os/exec")
object exec:
  /** Facade class for Go `*exec.Cmd`. */
  final class Cmd private[go] ():
    /** `Cmd.Run()` — lone `error`: run and wait. A non-zero exit is an error,
     *  which is exactly the "the player failed" signal the REPL prints. */
    @go.name("Run") def run(): Unit throws sgo.GoError = ???

  @go.name("Command") def command(name: String, arg: String*): go.exec.Cmd = ???

  /** `exec.LookPath(file)` — `(string, error)`; errors when the name is not on
   *  `$PATH`. How the default player is chosen. */
  @go.name("LookPath") def lookPath(file: String): String throws sgo.GoError = ???

/** `go.syscall` — the same open/write/close trio wata-fb's config store uses,
 *  bound here for the one write this app does: the downloaded Ogg to a
 *  temporary file the player process can open. `perm` is passed as a LITERAL
 *  at the call site so it lands as an untyped constant in Go's `uint32`. */
@go.bind("syscall")
object syscall:
  def O_WRONLY: scala.Int = ???
  def O_CREAT: scala.Int  = ???
  def O_TRUNC: scala.Int  = ???

  /** `syscall.Open(path, mode, perm)` — `(fd int, err error)`. */
  @go.name("Open") def open(path: String, mode: scala.Int, perm: scala.Int): scala.Int throws sgo.GoError = ???
  /** `syscall.Write(fd, p)` — `(n int, err error)` surfaced: a short or failed
   *  write must not be reported as a played message. */
  @go.name("Write") def write(fd: scala.Int, p: go.Bytes): scala.Int throws sgo.GoError = ???
  /** `syscall.Close(fd)` — error dropped. */
  @go.name("Close") def close(fd: scala.Int): Unit = ???
