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
 *  script to the shell as one wire message. Bodies and the diff stay on the
 *  pump goroutine; only the shell's apply touches AppKit (wata-mac.md's
 *  threading rule — macshell routes it to the right thread per mode).
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
 *     order), `tree` prints the live NATIVE hierarchy, `key <name>
 *     <press|release|repeat>` injects a macOS key code through the real
 *     translation table, `quit` winds down. tools/mac-smoke.py drives this
 *     and asserts on the printed lines, tui-smoke style.
 *
 *  The two-step quit (Back on the contact list, again within 2s) terminates
 *  the windowed app; headless it only arms/renders, since the smoke owns the
 *  session. */
object Main:
  def main(args: Array[String]): Unit =
    val hs = pick(args, 0, "WATA_MAC_HS", "")
    val user = pick(args, 1, "WATA_MAC_USER", "")
    val passIn = pick(args, 2, "WATA_MAC_PASS", "")
    val sc = go.bufio.newScanner(go.osx.Stdin)
    // The stores decide what this run logs in as (plan 0036): an explicit
    // argument or environment variable wins, then the identity and the
    // secrets the last run left behind. So the FIRST run still needs
    // WATA_MAC_USER/PASS, and no run after it needs anything at all.
    var cfg = FbConfig.resolve(hs, user, passIn)
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
  undelivered: List[String]
)

object Pump:

  val FRAME_MS: Long = 33L
  val QUIT_ARM_S: scala.Double = 2.0

  def initial(clock: Clock): PumpSt =
    PumpSt(WataLogic.initial(), None, 0.0, clock.nowUnixMillis(), false, Nil, Nil)

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
   *  window on its boot screen with nothing a user could do about it. */
  def windowedSession(cfg0: ClientConfig): Unit =
    var cfg = cfg0
    var going = true
    while going do
      cfg = ensureCredentials(cfg)
      if cfg.username == "" then going = false          // the sheet was cancelled
      else
        go.macshell.setAccount(cfg.homeserver, cfg.username)
        val h = startAudioClient(cfg)
        val ending = windowedPump(h)
        h.stop()
        val joined = ClientHandle.join(h, 5000L)
        // Rejected and signed-out end the same way — forget the secrets and
        // ask again — and differ only in who decided. Quitting ends the loop.
        if ending == "quit" then going = false
        else
          go.macshell.setAccount("", "")
          cfg = Main.withPassword(FbConfig.forgetAndReload(cfg), "")
    go.macshell.terminate()

  /** what the sheet is for: nothing usable stored, so ask. Cancelling answers
   *  an empty config, which ends the app — "Quit" is the sheet's other button
   *  and has to mean it. */
  def ensureCredentials(c: ClientConfig): ClientConfig =
    if Main.hasCredentials(c) then c
    else
      val line = go.macshell.login(c.homeserver, c.username)
      if line == "" then ClientConfig("", "", "", c.syncTimeoutMs, FbConfig.empty())
      else
        val hs = MacStr.tabField(line, 0)
        val user = MacStr.tabField(line, 1)
        val pass = MacStr.tabField(line, 2)
        FbConfig.setRemember(MacStr.tabField(line, 3) == "1")
        FbConfig.savePassword(hs, user, pass)
        FbConfig.resolve(hs, user, pass)

  /** how the session ended: `"quit"` (the window's own quit gesture),
   *  `"rejected"` (the server refused the account) or `"signout"` (the user
   *  asked, from the menu or the Settings window). The last two both mean
   *  "ask again"; the first ends the app. */
  def windowedPump(h: Handle): String =
    val clock = MacCaps.clock()
    NetStatus.reset()
    if Runtime.waitForConnection(h.client, Syncing(), connectMs()) then
      println("ready " + Runtime.lastAuth.userId)
    else println("login failed") // keep pumping: the boot screen shows the state
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record. Same idiom as
      // wata-fb's `Ui.loopWithDevice`.
      val audioCmds = h.client.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts))
      var st = initial(clock)
      while !st.quit && !isRejected(h.connection()) && !signedOutC.get() do
        val took = h.waitEvent(FRAME_MS)
        st = frame(h, clock, evts, st, false)
        pollChrome()
      audioCmds.send(AcQuit()) // the fork's only exit; the scope joins it
    }
    if signedOutC.get() then
      signedOutC.set(false)
      "signout"
    else if isRejected(h.connection()) then "rejected"
    else "quit"

  /** the chrome's commands, drained once a frame beside the key queue. A menu
   *  click lands on the main thread; the session ends here, on the pump's own
   *  thread, where the client and the stores are. */
  private val signedOutC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def pollChrome(): Unit =
    val cmd = go.macshell.nextCommand()
    if cmd == "signout" then signedOutC.set(true)

  def isRejected(c: ConnectionState): Boolean = c match
    case _: ConnAuthRejected => true
    case _                   => false

  def runHeadless(cfg: ClientConfig, scale: scala.Int, sc: go.bufio.Scanner): Unit =
    go.macshell.startHeadless(scale)
    NetStatus.reset()
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
      sgo.fork(AudioThread.mainLoop(audioCmds, evts))
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
        else if cmd == "key" then doKey(MacStr.nth(ts, 1), MacStr.nth(ts, 2))
        else if cmd != "" then println("? " + cmd)

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
    var st = PumpSt(st0.wata, st0.last, st0.quitArm, nowMs, st0.quit, st0.unsent, st0.undelivered)
    st = drainUiEvents(h, st)
    val snap = h.snapshot()
    val conn = h.connection()
    val net = NetStatus.poll(conn)
    // the same two connectivity duties wata-fb's frame step has (plan 0035):
    // one log line per change, and an immediate retry when the pipe arrives.
    NetStatus.logTransition(net, conn, NetStatus.clockOk())
    NetStatus.logSnapshot(snap, conn)
    persistSession(h.client)
    if NetStatus.takePipeArrival() then Runtime.retryNow(h.client)
    val ctx = FrameCtx(snap, conn, net, h.client, h.client.audioCmds, evts,
      st.unsent, st.undelivered, st.quitArm > 0.0)
    st = applyKeys(st, ctx)
    st = drainAudio(st, ctx)
    st = PumpSt(WataLogic.update(st.wata, dt, ctx), st.last, tickArm(st.quitArm, dt),
      st.lastMs, st.quit, st.unsent, st.undelivered)
    val v = WataLogic.body(st.wata, snap, net, conn, st.quitArm > 0.0,
      st.unsent, st.undelivered,
      NetStatus.everLive(), FbCaps.transportUnavailable(), None, false, NetStatus.clockOk())
    st.last match
      case old: Some[View] => patchTo(old.value, v, verbose)
      case None            => setTree(v, verbose)
    PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered)

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
      PumpSt(st.wata, st.last, st.quitArm, st.lastMs, st.quit, ob.unsent, ob.undelivered)
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
    else withWata(st, WataLogic.onAudioEvent(st.wata, e, ctx))

  /** the same predicate `Shell.isEchoEvt` names, restated here because the
   *  mac's `Shell` stub carries only the key predicates. */
  def isEchoEvt(e: AudioEvt): Boolean = e match
    case _: AeEchoRecording => true
    case _: AeEchoPlaying   => true
    case _: AeEchoDone      => true
    case _: AeEchoError     => true
    case _                  => false

  def withWata(st: PumpSt, w: WataState): PumpSt =
    PumpSt(w, st.last, st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered)

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  /** the quit confirmation ages out (0 = unarmed). */
  def tickArm(arm: scala.Double, dt: scala.Double): scala.Double =
    var out = arm
    if arm > 0.0 then
      out = arm - dt
      if out < 0.0 then out = 0.0
    out

  def setTree(v: View, verbose: Boolean): Unit =
    try
      go.macshell.applyWire(Wire.encodeTree(v))
      if verbose then println("tree set")
    catch case e: sgo.GoError => println("? apply: " + e.message)

  def patchTo(old: View, v: View, verbose: Boolean): Unit =
    val ps = Diff.diff(old, v)
    if Wire.lenPatches(ps) > 0 then
      try
        go.macshell.applyWire(Wire.encodeScript(ps))
        if verbose then printPatches(ps)
      catch case e: sgo.GoError => println("? apply: " + e.message)

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
   *  a second press inside the window quits). */
  def applyKeys(st0: PumpSt, ctx: FrameCtx): PumpSt =
    var st = st0
    var going = true
    while going do
      val packed = go.macshell.nextKey()
      if packed < 0 then going = false
      else st = applyKey(st, decode(packed), ctx)
    st

  /** `key*4 + phase` back into a KeyEvent (macshell's packing; the key
   *  numbering is nativeui's — None/Up/Down/Left/Right/Enter/Back/Ptt). */
  def decode(packed: scala.Int): KeyEvent =
    KeyEvent(keyOf(packed / 4), stateOf(packed % 4))

  def keyOf(code: scala.Int): Key =
    if code == 1 then KUp()
    else if code == 2 then KDown()
    else if code == 3 then KLeft()
    else if code == 4 then KRight()
    else if code == 5 then KEnter()
    else if code == 6 then KBack()
    else if code == 7 then KPtt()
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
      st.unsent, st.undelivered)

  def isQuitEdge(s: WataState, ev: KeyEvent): Boolean =
    Shell.isPressed(ev.state) && Shell.isBackKey(ev.key) && isContacts(s.view)

  def isContacts(v: WataView): Boolean = v match
    case _: VContacts => true
    case _            => false
