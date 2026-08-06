package go

import language.experimental.saferExceptions

/** The APP-OWNED `@go.bind` facades wata-mac needs beyond the core surface
 *  (the symlinked syscall.scala carries `go.syscall`): stdin + a line
 *  scanner for the headless command loop (wata-tui's pair, same naming
 *  workaround — core owns `go.os`, so the object reaching `os.Stdin` is
 *  `go.osx`), and the macshell package that owns the AppKit side. */

/** `go.osx` — the `os` package, curated to standard input. */
@go.bind("os")
object osx:
  @go.name("Stdin") def Stdin: go.io.Reader = ???

/** `go.bufio` — a line reader over a `go.io.Reader`. */
@go.bind("bufio")
object bufio:
  /** Facade class for Go `*bufio.Scanner`. */
  final class Scanner private[go] ():
    @go.name("Scan") def scan(): Boolean = ???
    @go.name("Text") def text(): String = ???

  @go.name("NewScanner") def newScanner(r: go.io.Reader): go.bufio.Scanner = ???

/** `go.macshell` — the AppKit shell (go-pkgs/macshell): the window or the
 *  headless stage, the wire-encoded frame handoff, and the key queue. The
 *  surface is strings and ints only; the wire grammar lives in macshell's
 *  wire.go and this module's wire.scala, which must agree byte for byte.
 *
 *  THREADING (wata-mac.md): `start` must be the Sgola main's FIRST device
 *  call (macshell's package init pins the main goroutine to the main OS
 *  thread), `runApp` is NSApplication.run — call it last, it never returns —
 *  and `apply` may be called from the pump goroutine in both modes: windowed
 *  it dispatches to the main queue, headless it runs on the stage's own
 *  locked thread. */
@go.bind("github.com/adriaanm/wata/go-pkgs/macshell")
object macshell:
  /** windowed init: NSApplication + window + stage + key view. Main thread. */
  @go.name("Start") def start(scale: scala.Int, title: String): Unit = ???
  /** headless init: the stage on a dedicated locked OS thread. */
  @go.name("StartHeadless") def startHeadless(scale: scala.Int): Unit = ???
  /** NSApplication.run — never returns. */
  @go.name("RunApp") def runApp(): Unit = ???
  /** [NSApp terminate:] — the windowed quit edge; does not return. */
  @go.name("Terminate") def terminate(): Unit = ???
  /** apply one wire message (a whole tree or a differ script). */
  @go.name("Apply") def applyWire(wire: String): Unit throws sgo.GoError = ???
  /** one pending key event as `key*4 + phase` (phase 0 release / 1 press /
   *  2 repeat), or -1 — never blocks. */
  @go.name("NextKey") def nextKey(): scala.Int = ???
  /** inject a macOS virtual key code through the real translation table —
   *  the headless smoke's key path. */
  @go.name("PushKeyCode") def pushKeyCode(code: scala.Int, phase: scala.Int): Unit = ???
  /** the live native hierarchy, one view per line (headless only). */
  @go.name("TreeDump") def treeDump(): String throws sgo.GoError = ???
