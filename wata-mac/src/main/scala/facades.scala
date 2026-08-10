package go

import language.experimental.saferExceptions

/** The APP-OWNED `@go.bind` facades wata-mac needs beyond the core surface
 *  (the symlinked syscall.scala carries `go.syscall`): stdin + a line
 *  scanner for the headless command loop (wata-tui's pair, and the same
 *  rename — core owns `go.os`, so an app-owned facade for the same Go
 *  package cannot reuse the name, and the object reaching `os.Stdin` is
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

/** `go.macshell` — the AppKit shell (go-pkgs/macshell): the window (or the
 *  headless flag), the raw key queue, the chrome, and TreeDump. The frame
 *  handoff is NOT here any more: the retained interpreter is Sgola
 *  (interp.scala), so frames cross by direct call — the shell only adopts
 *  the stage's root view.
 *
 *  THREADING (wata-mac.md): `start` must be the Sgola main's FIRST device
 *  call (macshell's package init pins the main goroutine to the main OS
 *  thread), then `MacStage.create` + `adoptRoot` on that same thread, then
 *  fork the pump and call `runApp` (NSApplication.run — it never returns)
 *  last. Headless there is no second thread at all: the main goroutine IS
 *  the stage's thread. */
@go.bind("github.com/adriaanm/wata/go-pkgs/macshell")
object macshell:
  /** windowed init: NSApplication + window + key view (no stage — that is
   *  `MacStage.create`'s). Main thread. */
  @go.name("Start") def start(scale: scala.Int, title: String): Unit = ???
  /** take the Sgola stage's root: windowed, into the content view below the
   *  key view; both modes, as TreeDump's walk root. Stage thread. */
  @go.name("AdoptRoot") def adoptRoot(v: go.appkit.NSView): Unit = ???

  /** the login sheet (plan 0037): an NSAlert with server/name/password
   *  fields and a "stay signed in" checkbox, prefilled with what is passed.
   *  BLOCKS until the user commits or cancels — it runs the modal on the
   *  main thread and waits, so call it from the pump, never before
   *  `runApp` is on its way (a sheet asked for earlier simply waits for the
   *  queue to drain, which is right: there is no window to put it on yet).
   *
   *  `reason` is why the sheet is back — the session loop's one sentence,
   *  drawn as a red line above the fields; empty on a first ask (plan 0045
   *  slice 2). An empty homeserver or name does not commit: the sheet
   *  reshows itself naming the missing field.
   *
   *  Answers "" for cancel, else a tab-separated
   *  `homeserver 	 user 	 password 	 0|1` — the flag being the checkbox.
   *  Strings only, like the rest of this facade. */
  @go.name("Login") def login(hs: String, user: String, reason: String): String = ???
  /** headless init: just the flag and the key queue — the stage lives on
   *  the calling goroutine (the locked main OS thread). */
  @go.name("StartHeadless") def startHeadless(): Unit = ???
  /** NSApplication.run — never returns. */
  @go.name("RunApp") def runApp(): Unit = ???
  /** [NSApp terminate:] — the windowed quit edge; does not return. */
  @go.name("Terminate") def terminate(): Unit = ???
  /** one pending key event as `rawCode*4 + phase` (phase 0 release / 1 press
   *  / 2 repeat), or -1 — never blocks. Codes are RAW macOS virtual key
   *  codes; `MacKeys.translate` runs at the drain. */
  @go.name("NextKey") def nextKey(): scala.Int = ???
  /** inject a raw macOS virtual key code into the same queue the key view
   *  feeds — the headless smoke's key path. */
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

  // ---- the Devices window (plan 0037 slice 5) ------------------------------
  // The admin surface wata-tui drives from a command line. The window holds
  // no logic: it draws what these setters hand it and pushes a command onto
  // the same queue `nextCommand` drains, and `Devices` does the work on its
  // own goroutine — a scan can take a minute, which is a minute the frame
  // pump must not spend blocked.

  /** open it (the menu item's ⌘D does this itself; this is the way in from
   *  the session and the headless harness). */
  @go.name("ShowDevices") def showDevices(): Unit = ???
  /** the handset picker: `<userId>\t<display name>` per line. */
  @go.name("SetHandsets") def setHandsets(tsv: String): Unit = ???
  /** one handset's scan report: `<ssid>\t<signal dBm>\t<0|1 secured>`. */
  @go.name("SetNetworks") def setNetworks(tsv: String): Unit = ???
  /** the handsets waiting for a verdict: `<nodeId>\t<code>` per line. */
  @go.name("SetPending") def setPending(tsv: String): Unit = ???
  /** the accounts an approval may bind to, one per line. */
  @go.name("SetRoster") def setRoster(tsv: String): Unit = ???
  /** the one line under the buttons; an OUTCOME, never a verb. */
  @go.name("SetDevStatus") def setDevStatus(s: String): Unit = ???
  /** the wifi password, read out of the NSSecureTextField AND CLEARED in the
   *  same call. This is the only way it leaves the chrome; it must not be
   *  logged, stored or put in a command — argv and the environment are
   *  world-readable, which is why wata-fb's own join helper pipes it over
   *  stdin. */
  @go.name("TakePSK") def takePsk(): String = ???

  // driving the window with no mouse — the headless smoke's way in. Each is
  // exactly what a click or a keystroke does: `devClick` calls the same
  // function the button's action calls, so a green harness is evidence about
  // the real path rather than a parallel one.
  /** pick row `i` of "handset" / "network" / "pending". */
  @go.name("DevSelect") def devSelect(kind: String, i: scala.Int): Unit = ???
  /** type into "psk" (the secure field) or "account". */
  @go.name("DevType") def devType(field: String, s: String): Unit = ???
  /** press "scan" / "join" / "off" / "approve" / "deny" / "refresh". */
  @go.name("DevClick") def devClick(name: String): Unit = ???
  /** the sentence shown beside Approve and Deny — what the user is told
   *  before an irreversible click. */
  @go.name("DevDecision") def devDecision(): String = ???

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
