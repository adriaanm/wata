import language.experimental.saferExceptions

/** wata-mac: the macOS client (plan 0032) — wata-fb's screens on the
 *  retained AppKit backend.
 *
 *    WATA_MAC_USER=alice WATA_MAC_PASS=testpass123 wata-mac
 *    wata-mac <homeserver> <user> [password]
 *
 *  Login is from the environment — `WATA_MAC_HS` (default
 *  `http://127.0.0.1:8008`), `WATA_MAC_USER`, `WATA_MAC_PASS` — with
 *  positional arguments overriding, the same rule as wata-tui (with a user
 *  but no password, it is prompted from stdin). Startup prints exactly one
 *  line: `ready <userId>`, or `login failed`.
 *
 *  THE PUMP (one shape, two drivers). Each frame: drain the runtime's
 *  `UiEvent` queue, drain the shell's key queue into `WataLogic.handleInput`,
 *  drain the audio thread's `AudioEvt` queue, tick `WataLogic.update`, read
 *  the handle's snapshot/connection, run `WataLogic.body` — the SAME body the
 *  device runs — then diff against the last tree (`Diff.diff`) and hand the
 *  script to the retained interpreter (`MacStage`, interp.scala) by DIRECT
 *  call. Bodies and the diff stay on the pump goroutine; only the stage
 *  apply touches AppKit (wata-mac.md's threading rule — `MacStage.submit*`
 *  routes it to the right thread per mode: windowed it publishes and a
 *  main-queue callback applies, headless it applies inline).
 *
 *  AUDIO (plan 0033): the client is `Runtime.makeWithAudio` and each driver
 *  forks wata-fb's OWN audio thread (`audiothread.scala`, symlinked in) into
 *  a supervised scope, over `go-pkgs/macaudio` — the `go.audio` facade here
 *  differs from wata-fb's only in its `@go.bind` path, an identity
 *  `just facade-check` enforces. Each mailbox is drained exactly once per
 *  frame; two drains on one channel eat each other's events (plan 0009).
 *
 *   - WINDOWED (default): `macshell.start` on the main goroutine (macshell's
 *     init pinned it to the main OS thread), the pump forked, then
 *     `macshell.runApp` = NSApplication.run owning the main thread. Frames
 *     tick on `waitEvent(FRAME_MS)` — the handle's dirty-flag channel as the
 *     wake-up, with a frame-pace deadline so held-key timers advance.
 *
 *   - HEADLESS (`WATA_MAC_HEADLESS=1`): no NSApplication, no window; the
 *     stage lives on macshell's own locked thread and the main goroutine
 *     runs a line command loop — `wait <ms>` pumps frames and prints each
 *     applied patch (`patch <script line>`, the differ's own script, in
 *     order), `tree` prints the live NATIVE hierarchy, `title` the window
 *     title's connectivity words, `key <name>
 *     <press|release|repeat>` injects a macOS key code through the real
 *     translation table, `quit` winds down. tools/mac-smoke.py drives this
 *     and asserts on the printed lines, tui-smoke style.
 *
 *  The two-step quit (Back on the contact list, again within 2s) terminates
 *  the windowed app; headless it only arms/renders, since the smoke owns the
 *  session. */
object Main:
  def main(args: Array[String]): Unit =
    // the retained interpreter's test suite as an argv mode (the wata-fb
    // difftest idiom): no server, no login, no window — the recipe greps
    // the printed verdict (interptest.scala).
    if args.length > 0 && args(0) == "interptest" then
      val failures = InterpTest.run()
    else runClient(args)

  def runClient(args: Array[String]): Unit =
    val hs = pick(args, 0, "WATA_MAC_HS", "")
    val user = pick(args, 1, "WATA_MAC_USER", "")
    val passIn = pick(args, 2, "WATA_MAC_PASS", "")
    val sc = go.bufio.newScanner(go.osx.Stdin)
    // The stores decide what this run logs in as (plan 0036): an explicit
    // argument or environment variable wins, then the identity and the
    // secrets the last run left behind. So the FIRST run still needs
    // WATA_MAC_USER/PASS, and no run after it needs anything at all.
    var cfg = FbConfig.resolve(hs, user, passIn)
    // The walkie-talkie toggle, read once and pushed to the chrome so the
    // Settings checkbox draws the stored answer. Primed here because every
    // config WRITE re-emits it from the same cell (config.scala).
    go.macshell.setNotifyPlay(Notify.playsNow(FbConfig.loadNotifyMode()))
    // the password that got us in is worth keeping only if it is the one the
    // user just supplied; a stored one is already where it belongs.
    if passIn != "" then FbConfig.savePassword(cfg.homeserver, cfg.username, passIn)
    if go.sys.getenv("WATA_MAC_HEADLESS") != "" then
      // headless has no runloop to put a modal on, and a harness that hung
      // waiting for a click would be worse than one that says what it needs.
      if cfg.username != "" && !hasCredentials(cfg) then
        cfg = withPassword(cfg, askPass(sc))
      if !hasCredentials(cfg) then
        println("wata-mac: set WATA_MAC_USER (and WATA_MAC_PASS), or pass <homeserver> <user> [password]")
      else Pump.runHeadless(cfg, scale(), sc)
    else Pump.runWindowed(cfg, scale())

  /** enough to attempt a session: a name, and either a password or a stored
   *  token to try first. */
  def hasCredentials(c: ClientConfig): Boolean =
    c.username != "" && (c.password != "" || c.stored.accessToken != "")

  def withPassword(c: ClientConfig, pass: String): ClientConfig =
    ClientConfig(c.homeserver, c.username, pass, c.syncTimeoutMs, c.stored)

  def askPass(sc: go.bufio.Scanner): String =
    println("password?")
    if sc.scan() then sc.text() else ""

  /** positional argument `i` wins, else the env var, else the default. */
  def pick(args: Array[String], i: scala.Int, env: String, dflt: String): String =
    var out = go.sys.getenv(env)
    if out == "" then out = dflt
    if args.length > i && args(i) != "" then out = args(i)
    out

  def scale(): scala.Int =
    var out = MacStr.num(go.sys.getenv("WATA_MAC_SCALE"), 4)
    if out < 1 then out = 1
    out

/** one pump step's carried state: the wata applet state, the last tree the
 *  shell was handed (None before the first frame), the two-step quit's arm
 *  window, the frame clock, whether a confirmed quit edge fired, and the
 *  outbox marks the runtime's last `EvOutbox` published (wata-fb keeps these
 *  in cells; the mac pump carries them, and they feed BOTH the `FrameCtx` and
 *  `WataLogic.body`). */
case class PumpSt(
  wata: WataState,
  last: Option[View],
  quitArm: scala.Double,
  lastMs: Long,
  quit: Boolean,
  unsent: List[String],
  undelivered: List[String],
  // the unplayed marks the arrival edge is measured against (wataclient's
  // `Notify`) — the last count this pump saw per conversation. Named `marks`
  // and not `notify`: a `notify` member cannot override Object's final one.
  marks: NotifyState
)

object Pump:

  val FRAME_MS: Long = 33L
  val QUIT_ARM_S: scala.Double = 2.0

  def initial(clock: Clock): PumpSt =
    PumpSt(WataLogic.initial(), None, 0.0, clock.nowUnixMillis(), false, Nil, Nil,
      Notify.initial())

  // ---- the two drivers ------------------------------------------------------

  /** the client both drivers run: `makeWithAudio`, so the action loop's
   *  `AcPlay` reaches the audio thread this app forks — and now with a STORED
   *  outbox (plan 0036), so a recording queued while the server was
   *  unreachable is still there after the window closes. It was `MemOutbox`
   *  before, which quietly threw those away.
   *  `ClientHandle.startClient` is the seam that takes a caller-built client. */
  def startAudioClient(cfg: ClientConfig): Handle =
    ClientHandle.startClient(
      Runtime.makeWithAudioStored(cfg, MacCaps.httpDo(), MacCaps.clock(), FbConfig.outbox()))

  /** the token this session actually got, kept for the next launch — written
   *  once per session and only when it is real, so a failed login cannot
   *  overwrite a good stored one with nothing. */
  private val savedC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def persistSession(c: MatrixClient): Unit =
    if !savedC.get() then
      val creds = Runtime.lastAuth
      if creds.accessToken != "" then
        savedC.set(true)
        FbConfig.saveLogin(c.cfg.homeserver, c.cfg.username, creds)

  /** how long startup waits for the first live sync before saying so. The
   *  answer is only a PRINT — both drivers keep pumping either way — so
   *  `WATA_MAC_CONNECT_MS` shortening it costs nothing but lets a harness
   *  reach the boot screen of a transport that will never come up without
   *  sitting out the full default. */
  def connectMs(): Long = MacStr.num(go.sys.getenv("WATA_MAC_CONNECT_MS"), 30000).toLong

  def runWindowed(cfg: ClientConfig, scale: scala.Int): Unit =
    go.macshell.start(scale, "Wata")
    // The stage is Sgola's (interp.scala) and is built HERE, on the main
    // goroutine — macshell's init pinned it to the main OS thread — then the
    // window adopts its root below the key view. After this, only the main
    // queue touches it (MacStage.submit* publish; the callback applies).
    val pool = go.nativeui.poolPush()
    val root = MacStage.create(scale, true)
    go.nativeui.poolPop(pool)
    go.macshell.adoptRoot(root)
    // The session runs on the pump goroutine, INCLUDING the login sheet: a
    // modal has to go on the main queue, and that queue only drains once
    // `runApp` is going. So the client is built here rather than on the main
    // thread, after the credentials are known.
    sgo.spawn(() => Pump.windowedSession(cfg))
    go.macshell.runApp() // never returns; quit leaves through macshell.terminate

  /** the outer loop around a windowed session: get credentials (from the
   *  stores, or by asking), run until the session ends, and ask again if what
   *  ended it was the server rejecting the account. That last arc is why the
   *  sheet exists at all — a token invalidated server-side used to leave the
   *  window on its boot screen with nothing a user could do about it.
   *
   *  `reason` is what the NEXT sheet says about why it is back (plan 0045
   *  slice 2): the loop knows how the last attempt ended, and a sheet that
   *  reshows identically after a refused password blames nobody and helps
   *  nobody. Sign-out carries no reason — nothing failed. */
  def windowedSession(cfg0: ClientConfig): Unit =
    var cfg = cfg0
    var reason = ""
    var going = true
    while going do
      // whether THIS attempt's credentials came off the sheet: only then may
      // an unreachable server bounce back to it (see windowedPump).
      val asked = reason != "" || !Main.hasCredentials(cfg)
      cfg = ensureCredentials(cfg, reason)
      reason = ""
      if cfg.username == "" then going = false          // the sheet was cancelled
      else
        go.macshell.setAccount(cfg.homeserver, cfg.username)
        val h = startAudioClient(cfg)
        val ending = windowedPump(h, asked)
        h.stop()
        val joined = ClientHandle.join(h, 5000L)
        if ending == "quit" then going = false
        else
          go.macshell.setAccount("", "")
          // no frames run while the sheet is up, so a dead session's last
          // title ("Wata — reconnecting…") would linger behind it
          go.macshell.setTitle("Wata")
          if ending == "unreachable" then
            // nothing was REFUSED — the secrets stay stored; the sheet is
            // back to fix WHERE, and says so.
            reason = "Could not reach " + cfg.homeserver + "."
          else
            // Rejected and signed-out end the same way — forget the secrets
            // and ask again — and differ only in who decided, which is
            // exactly what the reason line carries.
            if ending == "rejected" then reason = "The server refused this password."
            cfg = Main.withPassword(FbConfig.forgetAndReload(cfg), "")
    go.macshell.terminate()

  /** what the sheet is for: nothing usable stored, so ask — and always ask
   *  when there is a `reason`, because a reason means the stored answer just
   *  failed and silently retrying it is the loop the sheet exists to end.
   *  Cancelling answers an empty config, which ends the app — "Quit" is the
   *  sheet's other button and has to mean it. */
  def ensureCredentials(c: ClientConfig, reason: String): ClientConfig =
    if reason == "" && Main.hasCredentials(c) then c
    else
      val line = go.macshell.login(c.homeserver, c.username, reason)
      if line == "" then ClientConfig("", "", "", c.syncTimeoutMs, FbConfig.empty())
      else
        val hs = MacStr.tabField(line, 0)
        val user = MacStr.tabField(line, 1)
        val pass = MacStr.tabField(line, 2)
        FbConfig.setRemember(MacStr.tabField(line, 3) == "1")
        FbConfig.savePassword(hs, user, pass)
        FbConfig.resolve(hs, user, pass)

  /** `Runtime.waitForConnection(Syncing)`, except a `ConnAuthRejected` ends
   *  the wait at once: a refused password should be answerable in one auth
   *  round-trip, not after sitting out the whole connect window. Same
   *  event-draining shape as the original; `h.connection()` is an atomic
   *  read beside it. */
  def waitLiveOrRejected(h: Handle, timeoutMs: Long): Boolean =
    val deadline = h.client.clock.nowUnixMillis() + timeoutMs
    var live = false
    var run = true
    while run do
      if isRejected(h.connection()) then run = false
      else if h.client.clock.nowUnixMillis() >= deadline then run = false
      else
        Runtime.pollEvent(h.client) match
          case s: Some[UiEvent] =>
            if Runtime.isConnEvent(s.value, Syncing()) then
              live = true
              run = false
          case None => h.client.clock.sleepMs(20L)
    live

  /** how the session ended: `"quit"` (the window's own quit gesture),
   *  `"rejected"` (the server refused the account), `"signout"` (the user
   *  asked, from the menu or the Settings window) or `"unreachable"` (the
   *  credentials came off the sheet and the server never answered inside the
   *  connect window). All but the first mean "ask again"; `fromSheet` gates
   *  the last one — with STORED credentials an unreachable server keeps the
   *  boot screen and the client's own backoff (plan 0022's never-terminate),
   *  because nobody is standing at a sheet waiting to retype a hostname. */
  def windowedPump(h: Handle, fromSheet: Boolean): String =
    val clock = MacCaps.clock()
    NetStatus.reset()
    val live = waitLiveOrRejected(h, connectMs())
    if live then println("ready " + Runtime.lastAuth.userId)
    else println("login failed") // keep pumping: the boot screen shows the state
    if fromSheet && !live && !isRejected(h.connection()) then "unreachable"
    else windowedFrames(h, clock)

  def windowedFrames(h: Handle, clock: Clock): String =
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record. Same idiom as
      // wata-fb's `Ui.loopWithDevice`.
      val audioCmds = h.client.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      // The Devices window's engine, on its own goroutine for the same reason
      // the audio thread is on one: a wifi scan can take a minute to come
      // back, and a frame pump that waited for it would freeze the stage.
      val devCmds = sgo.makeChan[String](8)
      Devices.resume()
      sgo.fork(Devices.worker(devCmds, h.client))
      var st = initial(clock)
      while !st.quit && !isRejected(h.connection()) && !signedOutC.get() do
        val took = h.waitEvent(FRAME_MS)
        st = frame(h, clock, evts, st, false)
        pollChrome(devCmds)
      // both forks' only exit; the scope joins them. The stop flag is what
      // makes a worker parked in a 60-second report poll notice.
      Devices.stop()
      val handed = devCmds.trySend("")
      audioCmds.send(AcQuit())
    }
    if signedOutC.get() then
      signedOutC.set(false)
      "signout"
    else if isRejected(h.connection()) then "rejected"
    else "quit"

  // ---- the window title (plan 0045 slice 3) ---------------------------------

  /** continuous non-live time after which "reconnecting…" becomes a
   *  euphemism: the sync loop's own backoff ceiling — past it, retries are a
   *  minute apart and the honest word is offline. */
  val TITLE_OFFLINE_MS: Long = 60000L

  /** when the health went non-live (0 = live now, or still pre-everLive). */
  private val titleBadSinceC: sgo.Atomic[Long] = sgo.atomic(0L)

  /** the title as connectivity words — derived from the SAME `NetState` the
   *  header's dots draw (the plan's one-source law), rendered louder where
   *  an adult looks: the title bar and the Dock. Pre-everLive it stays plain
   *  "Wata": the boot screen owns that presentation (plan 0035's
   *  calm-outranks-failure), and a title crying offline over a window saying
   *  "starting up…" would be a second opinion. */
  def titleStep(net: NetState, everLive: Boolean, nowMs: Long): String =
    var out = "Wata"
    if everLive && !isLiveHealth(net.health) then
      if titleBadSinceC.get() == 0L then titleBadSinceC.set(nowMs)
      if nowMs - titleBadSinceC.get() >= TITLE_OFFLINE_MS then
        out = "Wata — offline"
      else out = "Wata — reconnecting…"
    else titleBadSinceC.set(0L)
    out

  def isLiveHealth(h: NetHealth): Boolean = h match
    case _: NetLive => true
    case _          => false

  /** the chrome's commands, drained once a frame beside the key queue. A menu
   *  click lands on the main thread; the session ends here, on the pump's own
   *  thread, where the client and the stores are. */
  private val signedOutC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def pollChrome(devCmds: sgo.Chan[String]): Unit =
    val cmd = go.macshell.nextCommand()
    // A Devices command goes to the worker; a full buffer means eight
    // requests are already outstanding, and dropping the ninth beats blocking
    // the frame that would draw the answer to the first.
    if isDevCmd(cmd) then
      val handed = devCmds.trySend(cmd)
      if !handed then go.macshell.setDevStatus("Still working on the last request…")
    else applyChrome(cmd)

  /** the Devices window's commands are the ones that go somewhere else. */
  def isDevCmd(cmd: String): Boolean = MacStr.tabField(cmd, 0).startsWith("dev:")

  def applyChrome(cmd: String): Unit =
    if cmd == "signout" then signedOutC.set(true)
    else if cmd == "notify:play" then setMode(NotifyPlayNow())
    else if cmd == "notify:quiet" then setMode(NotifyQuiet())

  /** the Settings checkbox moved: persist it, and say so once. The chrome
   *  already drew the new state; this side owns what it MEANS. */
  def setMode(m: NotifyMode): Unit =
    FbConfig.saveNotifyMode(m)
    println("notify: mode " + Notify.spellMode(m))

  def isRejected(c: ConnectionState): Boolean = c match
    case _: ConnAuthRejected => true
    case _                   => false

  def runHeadless(cfg: ClientConfig, scale: scala.Int, sc: go.bufio.Scanner): Unit =
    go.macshell.startHeadless()
    // No second thread headless: this goroutine (locked to the main OS
    // thread by macshell's init) IS the stage's thread — applies run inline,
    // and TreeDump's walk is coherent with them by construction.
    val pool = go.nativeui.poolPush()
    val root = MacStage.create(scale, false)
    go.nativeui.poolPop(pool)
    go.macshell.adoptRoot(root)
    NetStatus.reset()
    Devices.resume()
    val h = startAudioClient(cfg)
    if Runtime.waitForConnection(h.client, Syncing(), connectMs()) then
      println("ready " + Runtime.lastAuth.userId)
    else println("login failed")
    // The REPL runs either way, as the windowed driver pumps either way: the
    // boot screen IS the state, and an unbringable transport is exactly what a
    // caller has to be able to look at (`transport unavailable`, not "waiting
    // for network"). mac-iroh-smoke's negative reads it off `tree`.
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      val audioCmds = h.client.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      repl(h, sc, evts)
      audioCmds.send(AcQuit())
    }
    h.stop()
    val joined = ClientHandle.join(h, 5000L)
    println("bye")

  // ---- the headless command loop -------------------------------------------

  def repl(h: Handle, sc: go.bufio.Scanner, evts: sgo.Chan[AudioEvt]): Unit =
    val clock = MacCaps.clock()
    var st = initial(clock)
    var going = true
    while going do
      if !sc.scan() then going = false
      else
        val ts = MacStr.splitWs(sc.text())
        val cmd = MacStr.nth(ts, 0)
        if cmd == "quit" then going = false
        else if cmd == "wait" then st = doWait(h, clock, evts, st, MacStr.num(MacStr.nth(ts, 1), 0))
        else if cmd == "tree" then printTree()
        else if cmd == "title" then println("title " + go.macshell.title())
        else if cmd == "key" then doKey(MacStr.nth(ts, 1), MacStr.nth(ts, 2))
        else if cmd == "front" then doFront(MacStr.nth(ts, 1))
        else if cmd == "mode" then doMode(MacStr.nth(ts, 1))
        else if cmd == "dev" then doDev(h, sc, ts)
        else if cmd != "" then println("? " + cmd)

  /** the Devices window, without a mouse. Each subcommand is exactly what a
   *  click or a keystroke does — `dev click join` calls the same function the
   *  Join button's action calls — and the command the chrome pushes is then
   *  drained and RUN INLINE here, rather than handed to the worker goroutine
   *  the windowed driver forks. Headless is a script: a request that finished
   *  before the next line is read is what makes the printed lines assertable,
   *  and the worker exists to keep a window redrawing, which headless has no
   *  window to do.
   *
   *      dev show                    build the window's content
   *      dev sel handset|network|pending <i>
   *      dev psk                     read the password from the NEXT line
   *      dev acct <name>
   *      dev click scan|join|off|approve|deny|refresh
   *      dev decision                the sentence Approve/Deny commits to */
  def doDev(h: Handle, sc: go.bufio.Scanner, ts: List[String]): Unit =
    val sub = MacStr.nth(ts, 1)
    if sub == "show" then
      go.macshell.showDevices()
      println("dev shown")
    else if sub == "sel" then
      go.macshell.devSelect(MacStr.nth(ts, 2), MacStr.num(MacStr.nth(ts, 3), 0))
      println("dev sel " + MacStr.nth(ts, 2) + " " + MacStr.nth(ts, 3))
    else if sub == "psk" then doPsk(sc)
    else if sub == "acct" then
      go.macshell.devType("account", MacStr.nth(ts, 2))
      println("dev acct " + MacStr.nth(ts, 2))
    else if sub == "click" then doDevClick(h, MacStr.nth(ts, 2))
    else if sub == "decision" then println("dev decision " + go.macshell.devDecision())
    else println("? dev " + sub)

  /** the password comes on its OWN line, the way wata-tui's `join` prompts
   *  for it: a secret does not belong in a command line that a shell history
   *  or a process listing would keep. It goes straight into the window's
   *  NSSecureTextField and nowhere else. */
  def doPsk(sc: go.bufio.Scanner): Unit =
    println("psk?")
    var psk = ""
    if sc.scan() then psk = sc.text()
    go.macshell.devType("psk", psk)
    println("dev psk set")

  /** `dev done <what>` is the terminator, printed after the request has
   *  finished — an operation answers with a variable number of lines (a scan
   *  prints one per network, a refresh one per waiting handset), so a reader
   *  that stopped at the first of them would see only the first. */
  def doDevClick(h: Handle, what: String): Unit =
    go.macshell.devClick(what)
    val cmd = go.macshell.nextCommand()
    if cmd == "" then println("? dev click " + what + " did nothing")
    else Devices.run(h.client, cmd)
    println("dev done " + what)

  /** pump frames for `ms`, printing each applied patch — the smoke's window
   *  onto the differ. The handle's event channel is the sleep: a dirty flag
   *  wakes the next frame early, the deadline caps it at the frame pace. */
  def doWait(h: Handle, clock: Clock, evts: sgo.Chan[AudioEvt], st0: PumpSt, ms: scala.Int): PumpSt =
    val deadline = clock.nowUnixMillis() + ms.toLong
    var st = st0
    var going = true
    while going do
      if clock.nowUnixMillis() >= deadline then going = false
      else
        val took = h.waitEvent(FRAME_MS)
        st = frame(h, clock, evts, st, true)
    println("waited " + ms)
    st

  /** headless has no NSApplication to ask whether it is frontmost, so the
   *  harness says — which is what makes the "not while the user is looking at
   *  it" rule testable at all. */
  def doFront(arg: String): Unit =
    val on = arg == "1" || arg == "on"
    go.macshell.setFrontmost(on)
    println("front " + (if on then "1" else "0"))

  /** the walkie-talkie toggle, without a mouse: the same path the Settings
   *  checkbox takes (persist + tell the chrome). */
  def doMode(arg: String): Unit =
    val m = Notify.parseMode(arg)
    setMode(m)
    go.macshell.setNotifyPlay(Notify.playsNow(m))

  def printTree(): Unit =
    try
      val dump = go.macshell.treeDump()
      print(dump)
      println("tree end")
    catch case e: sgo.GoError => println("? tree: " + e.message)

  /** inject one key through the REAL translation table (macshell feeds the
   *  macOS virtual key code to nativeui.TranslateKeyCode — the same table
   *  the window's key view uses). */
  def doKey(name: String, phase: String): Unit =
    val code = keyCode(name)
    val ph = phaseCode(phase)
    if code < 0 || ph < 0 then println("? key " + name + " " + phase)
    else
      go.macshell.pushKeyCode(code, ph)
      println("key ok")

  /** macOS virtual key codes (HIToolbox kVK_*), by the name the smoke uses. */
  def keyCode(name: String): scala.Int =
    if name == "up" then 126
    else if name == "down" then 125
    else if name == "left" then 123
    else if name == "right" then 124
    else if name == "enter" then 36
    else if name == "esc" then 53
    else if name == "space" then 49
    else -1

  def phaseCode(phase: String): scala.Int =
    if phase == "release" then 0
    else if phase == "press" then 1
    else if phase == "repeat" then 2
    else -1

  // ---- one frame ------------------------------------------------------------

  /** one frame: UI events -> flash/outbox, keys -> input, audio events, tick,
   *  body, diff, apply. `verbose` prints the applied script (headless). */
  def frame(h: Handle, clock: Clock, evts: sgo.Chan[AudioEvt], st0: PumpSt, verbose: Boolean): PumpSt =
    val nowMs = clock.nowUnixMillis()
    val dt = clampDt(nowMs - st0.lastMs).toDouble / 1000.0
    var st = PumpSt(st0.wata, st0.last, st0.quitArm, nowMs, st0.quit, st0.unsent, st0.undelivered, st0.marks)
    st = drainUiEvents(h, st)
    val snap = h.snapshot()
    val conn = h.connection()
    val net = NetStatus.poll(conn)
    // the same two connectivity duties wata-fb's frame step has (plan 0035):
    // one log line per change, and an immediate retry when the pipe arrives.
    NetStatus.logTransition(net, conn, NetStatus.clockOk())
    NetStatus.logSnapshot(snap, conn)
    go.macshell.setTitle(titleStep(net, NetStatus.everLive(), nowMs))
    persistSession(h.client)
    if NetStatus.takePipeArrival() then Runtime.retryNow(h.client)
    val ctx = FrameCtx(snap, conn, net, h.client, h.client.audioCmds, evts,
      st.unsent, st.undelivered, st.quitArm > 0.0)
    st = applyKeys(st, ctx)
    st = drainAudio(st, ctx)
    st = notifyStep(h, st, snap)
    Devices.publishHandsets(snap) // the Devices window's picker, when it moves
    st = PumpSt(WataLogic.update(st.wata, dt, ctx), st.last, tickArm(st.quitArm, dt),
      st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    val v = WataLogic.body(st.wata, snap, net, conn, st.quitArm > 0.0,
      st.unsent, st.undelivered,
      NetStatus.everLive(), FbCaps.transportUnavailable(), None, false, NetStatus.clockOk())
    // MAC-IDLE-LEAK bisect arms, env-gated and COMMITTED so a bisect run is
    // reproducible instead of a per-session working-tree edit (drive them with
    // `just mac-leak --arm <x>`):
    //   build     tree built, nothing diffed, nothing sent — the flat control
    //   diffonly  Diff.diff runs, nothing encoded, nothing sent — the leaking
    //             bisect state, kept reproducible for heap profiling
    //   diffself  Diff.diff(v, v) — the all-equal walk over real content,
    //             pointer-identical arguments, no previous frame involved
    //   consttree the FULL pipeline, but diffing a constant spike-shaped tree
    //             in place of the body's — splits the real trees' content from
    //             the pump's structure (tools/diff-spike cleared the differ
    //             itself on constant trees)
    val armv = leakArm()
    if armv == "build" then
      PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    else if armv == "diffonly" then
      st.last match
        case old: Some[View] => leakSink(Diff.diff(old.value, v))
        case None            => ()
      PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    else if armv == "diffself" then
      // the real tree against ITSELF: the all-equal walk over real content,
      // with no previous-frame tree involved at all
      leakSink(Diff.diff(v, v))
      PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    else if armv == "consttree" then
      val c = leakTree(((nowMs / 100L) % 2L).toInt)
      st.last match
        case old: Some[View] => patchTo(old.value, c, false)
        case None            => setTree(c, false)
      PumpSt(st.wata, Some(c), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    else
      st.last match
        case old: Some[View] => patchTo(old.value, v, verbose)
        case None            => setTree(v, verbose)
      PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)

  // ---- the two mailbox drains ------------------------------------------------

  /** the runtime's `UiEvent` queue, ONCE per frame. `EvConn` needs nothing
   *  here — `h.connection()` already carries it, and the mac has no LEDs and
   *  no config store to react with; `EvSnapshot` is picked up by
   *  `h.snapshot()`. What is left is the send/play flash and the outbox
   *  marks, which wata-fb keeps in cells and this pump carries in `PumpSt`. */
  def drainUiEvents(h: Handle, st0: PumpSt): PumpSt =
    var st = st0
    var run = true
    while run do
      Runtime.pollEvent(h.client) match
        case e: Some[UiEvent] => st = onUiEvent(st, e.value)
        case None => run = false
    st

  def onUiEvent(st: PumpSt, e: UiEvent): PumpSt = e match
    case _: EvSendComplete =>
      withWata(st, WataLogic.notifySend(st.wata, false))
    case _: EvSendFailed =>
      withWata(st, WataLogic.notifySend(st.wata, true))
    case pe: EvPlaybackError =>
      withWata(st, WataLogic.notifyPlayError(st.wata, pe.fetchFailed))
    case ob: EvOutbox =>
      PumpSt(st.wata, st.last, st.quitArm, st.lastMs, st.quit, ob.unsent, ob.undelivered, st.marks)
    case _ => st // EvConn -> h.connection(), EvSnapshot -> h.snapshot()

  /** the audio thread's `AudioEvt` queue, ONCE per frame, before the applet
   *  tick — plan 0009: two drains on one channel eat each other's events, so
   *  there is exactly this one. wata-fb's `Shell.routeAudio` splits echo
   *  events off to the settings applet; nothing here drives the echo test
   *  (the settings applet compiles in but is never active), so the four
   *  `AeEcho*` events — the set `Shell.isEchoEvt` names — are dropped, and
   *  everything else goes to `WataLogic.onAudioEvent`. */
  def drainAudio(st0: PumpSt, ctx: FrameCtx): PumpSt =
    var st = st0
    var run = true
    while run do
      ctx.audioEvts.tryReceive() match
        case e: Some[AudioEvt] => st = onAudioEvt(st, e.value, ctx)
        case None => run = false
    st

  def onAudioEvt(st: PumpSt, e: AudioEvt, ctx: FrameCtx): PumpSt =
    if isEchoEvt(e) then st
    else
      noteMicError(e)
      withWata(st, WataLogic.onAudioEvent(st.wata, e, ctx))

  /** On a Mac the likely cause of a recording failure is a denied
   *  Microphone permission (TCC), so the first failure per run also names
   *  the fix in chrome — the grid's flash says MIC FAILED, this says where
   *  the switch is. Once per run: the flash repeats with every attempt, a
   *  nagging banner would teach the user to ignore banners. The printed
   *  line is the assertable part, notifyLine-style. */
  private val micToldC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def noteMicError(e: AudioEvt): Unit = e match
    case _: AeRecordingError =>
      if !micToldC.get() then
        micToldC.set(true)
        val err = go.macshell.notify("Microphone unavailable",
          "Wata could not record. Check System Settings > Privacy & Security > Microphone.")
        var line = "mic: banner"
        if err != "" then line = line + " (" + err + ")"
        println(line)
    case _ => ()

  /** the same predicate `Shell.isEchoEvt` names, restated here because the
   *  mac's `Shell` stub carries only the key predicates. */
  def isEchoEvt(e: AudioEvt): Boolean = e match
    case _: AeEchoRecording => true
    case _: AeEchoPlaying   => true
    case _: AeEchoDone      => true
    case _: AeEchoError     => true
    case _                  => false

  // ---- arrival notification (plan 0037 slice 4) -----------------------------

  /** The arrival edge, once a frame, off the snapshot the frame already read:
   *  `Notify.step` (wataclient, shared with the handset) answers which
   *  conversations' unplayed counts ROSE and who to name, and the badge is
   *  the same counts added up. There is deliberately no second channel
   *  through the sync engine — one number drives the badge, the banner and
   *  the contact list's own badge, so they cannot disagree.
   *
   *  Every arrival prints ONE decision line, in both drivers:
   *
   *      notify: play|banner|suppressed "<title>" "<body>" badge=<n>
   *
   *  That line is the assertable part — whether macOS actually drew a banner
   *  is macOS's business and is not observable from here — and it is worth
   *  having in the windowed app's log for the same reason the `net:` lines
   *  are. */
  def notifyStep(h: Handle, st0: PumpSt, snap: StateSnapshot): PumpSt =
    val r = Notify.step(st0.marks, snap)
    val badge = Notify.totalUnplayed(snap)
    go.macshell.setBadge(badge)
    var st = PumpSt(st0.wata, st0.last, st0.quitArm, st0.lastMs, st0.quit,
      st0.unsent, st0.undelivered, r.marks)
    var cur = r.arrivals
    var going = true
    while going do
      cur match
        case c: ::[Arrival] =>
          st = announce(h, st, c.head, badge)
          cur = c.tail
        case Nil => going = false
    st

  /** what one arrival does. Being FRONTMOST silences the banner — a person
   *  looking at the window has already been told, and an app that banners
   *  over itself is the thing everyone turns notifications off for. It does
   *  NOT silence walkie-talkie mode: a walkie-talkie does not go quiet
   *  because you happen to be holding it. */
  def announce(h: Handle, st: PumpSt, a: Arrival, badge: scala.Int): PumpSt =
    val front = go.macshell.frontmost()
    var out = st
    if Notify.playsNow(FbConfig.notifyMode()) && canAutoPlay(st.wata) then
      // exactly what OK on the message does (`WataLogic.playSelected`),
      // including marking the applet as playing — which is what makes the
      // existing `AePlaybackDone` arm send the read receipt, so an
      // auto-played message really becomes played rather than badging forever.
      Runtime.sendAction(h.client, ActPlay(a.mxcUrl))
      out = withWata(st, WataLogic.withPlaying(st.wata, true, a.roomId, a.eventId))
      println(notifyLine("play", a, badge, ""))
    else if front then println(notifyLine("suppressed", a, badge, ""))
    else println(notifyLine("banner", a, badge, go.macshell.notify(Notify.title(a), Notify.body(a))))
    out

  /** the audio thread does one thing at a time, so an auto-play waits rather
   *  than cutting into a playback or a recording in progress. It is not
   *  queued: the badge and the conversation still carry the message, and a
   *  burst of arrivals playing back to back over each other is worse than one
   *  the user presses OK on. */
  def canAutoPlay(s: WataState): Boolean = !s.playing && !s.pttHeld

  def notifyLine(what: String, a: Arrival, badge: scala.Int, err: String): String =
    var out = "notify: " + what + " \"" + Notify.title(a) + "\" \"" + Notify.body(a) +
      "\" badge=" + badge
    if err != "" then out = out + " (" + err + ")"
    out

  def withWata(st: PumpSt, w: WataState): PumpSt =
    PumpSt(w, st.last, st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  /** the quit confirmation ages out (0 = unarmed). */
  def tickArm(arm: scala.Double, dt: scala.Double): scala.Double =
    var out = arm
    if arm > 0.0 then
      out = arm - dt
      if out < 0.0 then out = 0.0
    out

  /** the MAC-IDLE-LEAK bisect arm, read per frame so no module state rides
   *  on a debug switch. Empty (the normal path) unless the env names one. */
  def leakArm(): String = go.sys.getenv("WATA_MAC_LEAK_ARM")

  /** keep a bisect arm's diff result un-elidable without sending it — and
   *  NAME it: an idle frame's script should be empty, so any non-empty one
   *  says exactly which element changes every frame (the pinning suspect). */
  def leakSink(ps: List[Patch]): Unit =
    val n = lenPatches(ps)
    if n > 0 then
      ps match
        case p :: _ => println("leakdiff n=" + n + " first=" + DiffOracle.showPatch(p))
        case Nil    => ()

  /** the constant spike-shaped tree (tools/diff-spike's): 10 keyed rows of a
   *  highlight VRect + a VText, row 0's text carrying `tag` so consecutive
   *  frames produce a non-empty script. */
  def leakTree(tag: Int): View =
    var rows: List[Keyed] = Nil
    var i = 9
    while i >= 0 do
      val hl = VRect(0, i * 12, 160, 12, if i == 0 then 0x1234 else 0)
      val txt =
        if i == 0 then VText(2, i, "row " + i + " v" + tag, 0xFFFF)
        else VText(2, i, "row " + i, 0xFFFF)
      rows = Keyed("row-" + i, VGroup(Keyed("", hl) :: Keyed("", txt) :: Nil)) :: rows
      i -= 1
    VGroup(rows)

  def setTree(v: View, verbose: Boolean): Unit =
    MacStage.submitTree(v)
    if verbose then println("tree set")

  def patchTo(old: View, v: View, verbose: Boolean): Unit =
    val ps = Diff.diff(old, v)
    if lenPatches(ps) > 0 then
      MacStage.submitScript(ps)
      if verbose then printPatches(ps)

  def lenPatches(ps: List[Patch]): Int =
    var n = 0
    var cur = ps
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  def printPatches(ps: List[Patch]): Unit =
    var cur = ps
    var going = true
    while going do
      cur match
        case p :: t =>
          println("patch " + DiffOracle.showPatch(p))
          cur = t
        case Nil => going = false

  // ---- input ----------------------------------------------------------------

  /** drain the shell's key queue into the applet — and run the two-step quit
   *  edge exactly as the device loop does (Back on the contact list arms;
   *  a second press inside the window quits). The queue carries RAW macOS
   *  virtual key codes now; `MacKeys.translate` runs here, so the window
   *  path and the harness's `pushKeyCode` share one table, and a code the
   *  table does not know is dropped at the drain. */
  def applyKeys(st0: PumpSt, ctx: FrameCtx): PumpSt =
    var st = st0
    var going = true
    while going do
      val packed = go.macshell.nextKey()
      if packed < 0 then going = false
      else
        val k = MacKeys.translate(packed / 4)
        if k != MacKeys.NONE then
          st = applyKey(st, KeyEvent(keyOf(k), stateOf(packed % 4)), ctx)
    st

  def keyOf(code: scala.Int): Key =
    if code == MacKeys.UP then KUp()
    else if code == MacKeys.DOWN then KDown()
    else if code == MacKeys.LEFT then KLeft()
    else if code == MacKeys.RIGHT then KRight()
    else if code == MacKeys.ENTER then KEnter()
    else if code == MacKeys.BACK then KBack()
    else if code == MacKeys.PTT then KPtt()
    else KUnknown()

  def stateOf(phase: scala.Int): KeyState =
    if phase == 1 then Pressed()
    else if phase == 2 then Repeat()
    else Released()

  def applyKey(st: PumpSt, ev: KeyEvent, ctx: FrameCtx): PumpSt =
    val edge = isQuitEdge(st.wata, ev)
    var quit = st.quit
    var arm = st.quitArm
    if edge && arm > 0.0 then quit = true
    if edge then arm = QUIT_ARM_S
    PumpSt(WataLogic.handleInput(st.wata, ev.key, ev.state, ctx), st.last, arm, st.lastMs, quit,
      st.unsent, st.undelivered, st.marks)

  def isQuitEdge(s: WataState, ev: KeyEvent): Boolean =
    Shell.isPressed(ev.state) && Shell.isBackKey(ev.key) && isContacts(s.view)

  def isContacts(v: WataView): Boolean = v match
    case _: VContacts => true
    case _            => false
