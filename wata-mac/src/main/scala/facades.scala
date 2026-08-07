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

  /** the login sheet (plan 0037): an NSAlert with server/name/password
   *  fields and a "stay signed in" checkbox, prefilled with what is passed.
   *  BLOCKS until the user commits or cancels — it runs the modal on the
   *  main thread and waits, so call it from the pump, never before
   *  `runApp` is on its way (a sheet asked for earlier simply waits for the
   *  queue to drain, which is right: there is no window to put it on yet).
   *
   *  Answers "" for cancel, else a tab-separated
   *  `homeserver 	 user 	 password 	 0|1` — the flag being the checkbox.
   *  Strings only, like the rest of this facade. */
  @go.name("Login") def login(hs: String, user: String): String = ???
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

  /** one pending CHROME command, or "" — never blocks, polled once a frame
   *  like `nextKey`. A menu item cannot do the work itself: signing out means
   *  ending the session and clearing the stores, which is the Sgola side's.
   *  Today the only command that reaches here is `"signout"` (Settings opens
   *  its own window without asking anyone). */
  @go.name("NextCommand") def nextCommand(): String = ???
  /** what the Settings window says the session is signed in as. */
  @go.name("SetAccount") def setAccount(hs: String, user: String): Unit = ???
  /** open Settings from outside the menu (the smoke's way in). */
  @go.name("ShowPrefs") def showPrefs(): Unit = ???

  // ---- arrival notifications (plan 0037 slice 4) ---------------------------
  // The DECISION is `Notify`'s, in wataclient, and shared with the handset;
  // these are only its macOS presentation. Everything here is gated on a
  // BUNDLE: UNUserNotificationCenter raises on a process with no bundle id,
  // and headless has no NSApplication to hang a Dock tile off.

  /** ask for alert+badge permission, once. Called from `start`; a no-op
   *  unbundled, and its answer is only ever logged — a denial is the user's
   *  decision, not something to retry per arrival. */
  @go.name("RequestNotifyAuth") def requestNotifyAuth(): Unit = ???
  /** can a banner be posted at all — a windowed run inside a bundle? */
  @go.name("NotifyAvailable") def notifyAvailable(): Boolean = ???
  /** post one banner. "" when it was handed to the notification centre, else
   *  the reason it was not; a client that silently stops announcing looks
   *  exactly like one with nothing to announce, so the caller logs it. */
  @go.name("Notify") def notify(title: String, body: String): String = ???
  /** the Dock tile's unplayed count; 0 clears the badge. */
  @go.name("SetBadge") def setBadge(n: scala.Int): Unit = ???
  /** is the user looking at this app? Windowed, `[NSApp isActive]`; headless,
   *  whatever `setFrontmost` was last told. */
  @go.name("Frontmost") def frontmost(): Boolean = ???
  /** the headless override, so a harness drives both sides of the rule. */
  @go.name("SetFrontmost") def setFrontmost(on: Boolean): Unit = ???
  /** which way the Settings checkbox draws — the session's mode, since the
   *  chrome does not own it. */
  @go.name("SetNotifyPlay") def setNotifyPlay(on: Boolean): Unit = ???

/** `go.mackeychain` — the login keychain (go-pkgs/mackeychain), where the
 *  access token and the password live instead of the environment (plan 0036).
 *
 *  The surface is strings both ways on purpose: an item that is not there is
 *  the ORDINARY first-run case, so `lookup` answers "" rather than throwing,
 *  and `store`/`forget` answer "" for success or the error text — a client
 *  that cannot reach the keychain still works, it just cannot remember. */
@go.bind("github.com/adriaanm/wata/go-pkgs/mackeychain")
object mackeychain:
  /** false = no Security.framework; every call below is then a no-op. */
  @go.name("Available") def available(): Boolean = ???
  /** the stored secret, or "" for absent/unreachable. */
  @go.name("Lookup") def lookup(service: String, account: String): String = ???
  /** "" on success, else the error text. */
  @go.name("Store") def store(service: String, account: String, secret: String): String = ???
  /** "" on success (including "it was not there"), else the error text. */
  @go.name("Forget") def forget(service: String, account: String): String = ???
