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
 *  THE PUMP (one shape, two drivers). Each frame: drain the shell's key
 *  queue into `WataLogic.handleInput`, tick `WataLogic.update`, read the
 *  handle's snapshot/connection, run `WataLogic.body` — the SAME body the
 *  device runs — then diff against the last tree (`Diff.diff`) and hand the
 *  script to the shell as one wire message. Bodies and the diff stay on the
 *  pump goroutine; only the shell's apply touches AppKit (wata-mac.md's
 *  threading rule — macshell routes it to the right thread per mode).
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
    val hs = pick(args, 0, "WATA_MAC_HS", "http://127.0.0.1:8008")
    val user = pick(args, 1, "WATA_MAC_USER", "")
    var pass = pick(args, 2, "WATA_MAC_PASS", "")
    val sc = go.bufio.newScanner(go.osx.Stdin)
    if user != "" && pass == "" then pass = askPass(sc)
    if user == "" || pass == "" then
      println("wata-mac: set WATA_MAC_USER (and WATA_MAC_PASS), or pass <homeserver> <user> [password]")
    else run(hs, user, pass, sc)

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

  def run(hs: String, user: String, pass: String, sc: go.bufio.Scanner): Unit =
    val cfg = ClientConfig(hs, user, pass, 1000, Session("", "", "", "", ""))
    if go.sys.getenv("WATA_MAC_HEADLESS") != "" then Pump.runHeadless(cfg, scale(), sc)
    else Pump.runWindowed(cfg, scale())

/** one pump step's carried state: the wata applet state, the last tree the
 *  shell was handed (None before the first frame), the two-step quit's arm
 *  window, the frame clock, and whether a confirmed quit edge fired. */
case class PumpSt(
  wata: WataState,
  last: Option[View],
  quitArm: scala.Double,
  lastMs: Long,
  quit: Boolean
)

object Pump:

  val FRAME_MS: Long = 33L
  val QUIT_ARM_S: scala.Double = 2.0

  def initial(clock: Clock): PumpSt =
    PumpSt(WataLogic.initial(), None, 0.0, clock.nowUnixMillis(), false)

  // ---- the two drivers ------------------------------------------------------

  def runWindowed(cfg: ClientConfig, scale: scala.Int): Unit =
    go.macshell.start(scale, "Wata")
    val h = ClientHandle.start(cfg, MacCaps.httpDo(), MacCaps.clock())
    sgo.spawn(() => Pump.windowedPump(h))
    go.macshell.runApp() // never returns; quit leaves through macshell.terminate

  def windowedPump(h: Handle): Unit =
    val clock = MacCaps.clock()
    NetStatus.reset()
    if Runtime.waitForConnection(h.client, Syncing(), 30000L) then
      println("ready " + Runtime.lastAuth.userId)
    else println("login failed") // keep pumping: the boot screen shows the state
    val evts = sgo.makeChan[AudioEvt](16)
    var st = initial(clock)
    while !st.quit do
      val took = h.waitEvent(FRAME_MS)
      st = frame(h, clock, evts, st, false)
    h.stop()
    val joined = ClientHandle.join(h, 5000L)
    go.macshell.terminate()

  def runHeadless(cfg: ClientConfig, scale: scala.Int, sc: go.bufio.Scanner): Unit =
    go.macshell.startHeadless(scale)
    NetStatus.reset()
    val h = ClientHandle.start(cfg, MacCaps.httpDo(), MacCaps.clock())
    if Runtime.waitForConnection(h.client, Syncing(), 30000L) then
      println("ready " + Runtime.lastAuth.userId)
      repl(h, sc)
    else println("login failed")
    h.stop()
    val joined = ClientHandle.join(h, 5000L)
    println("bye")

  // ---- the headless command loop -------------------------------------------

  def repl(h: Handle, sc: go.bufio.Scanner): Unit =
    val clock = MacCaps.clock()
    val evts = sgo.makeChan[AudioEvt](16)
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

  /** one frame: keys -> input, tick, body, diff, apply. `verbose` prints the
   *  applied script (headless). */
  def frame(h: Handle, clock: Clock, evts: sgo.Chan[AudioEvt], st0: PumpSt, verbose: Boolean): PumpSt =
    val nowMs = clock.nowUnixMillis()
    val dt = clampDt(nowMs - st0.lastMs).toDouble / 1000.0
    val snap = h.snapshot()
    val conn = h.connection()
    val net = NetStatus.poll(conn)
    val ctx = FrameCtx(snap, conn, net, h.client, h.client.audioCmds, evts,
      Nil, Nil, st0.quitArm > 0.0)
    var st = PumpSt(st0.wata, st0.last, st0.quitArm, nowMs, st0.quit)
    st = applyKeys(st, ctx)
    st = PumpSt(WataLogic.update(st.wata, dt, ctx), st.last, tickArm(st.quitArm, dt),
      st.lastMs, st.quit)
    val v = WataLogic.body(st.wata, snap, net, conn, st.quitArm > 0.0, Nil, Nil,
      NetStatus.everLive(), FbCaps.transportUnavailable(), None, false)
    st.last match
      case old: Some[View] => patchTo(old.value, v, verbose)
      case None            => setTree(v, verbose)
    PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit)

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
    PumpSt(WataLogic.handleInput(st.wata, ev.key, ev.state, ctx), st.last, arm, st.lastMs, quit)

  def isQuitEdge(s: WataState, ev: KeyEvent): Boolean =
    Shell.isPressed(ev.state) && Shell.isBackKey(ev.key) && isContacts(s.view)

  def isContacts(v: WataView): Boolean = v match
    case _: VContacts => true
    case _            => false
