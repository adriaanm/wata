import language.experimental.saferExceptions

/** The on-device UI runtime: the main loop that ties the sync runtime, the
 *  audio thread, the framebuffer/input/LED device layer, and the
 *  shell/applets together into the live fbclient.
 *
 *    wata-fb ui <base> <user> <pass>   run the full client against a live
 *                                      wata-server (device only).
 *
 *  LOOP: each frame — pick up the newest snapshot, drain UI events
 *  (connection -> status + LEDs, send/play -> wata flash), poll input, route to
 *  the shell, screensaver idle-timeout, then update + render + present + ~33ms
 *  sleep (~30fps for the panel).
 *
 *  THREADING: sync loop + action loop (Runtime.start) + audio thread all run as
 *  `fork`s in the app's `supervised` scope; the main loop IS the UI thread. The
 *  shell state is a module var touched only by this loop (see shell.scala's
 *  header for the single-goroutine-per-cell discipline). A sibling failure
 *  cancels the whole scope (structured concurrency).
 *
 *  QUIT: `back` from the contacts view is the only quit edge here — the
 *  device is otherwise meant to run until powered off; this gives a dev/ssh
 *  run a clean way to terminate. Cleanup restores the backlight and tears the
 *  loops down through the ordinary stop edges. */
object Ui:

  // The UI-goroutine shell state lives in `val`-held Atomic cells: still
  // driven by the one UI goroutine by protocol, but data-race-freedom is
  // enforced structurally rather than merely by convention. Immutable
  // snapshots swap through the cells (ShellState / ConnectionState box;
  // Double boxes since Go has no atomic float word; Boolean rides
  // atomic.Bool). Reads keep their old names via private accessor defs.
  private val stateC: sgo.Atomic[ShellState] = sgo.atomic(Shell.initial())
  private val connC: sgo.Atomic[ConnectionState] = sgo.atomic(Disconnected())
  private val idleC: sgo.Atomic[scala.Double] = sgo.atomic(0.0)
  private val offC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private def stateV: ShellState = stateC.get()
  private def connV: ConnectionState = connC.get()
  private def idleTime: scala.Double = idleC.get()
  private def displayOff: Boolean = offC.get()

  def run(args: Array[String]): Unit =
    if args.length < 4 then println("ui: want  wata-fb ui <base> <user> <pass>")
    else runUi(args(1), args(2), args(3))

  def runUi(base: String, user: String, pass: String): Unit =
    println("ui: opening /dev/fb0 ...")
    try
      val fd = go.syscall.open("/dev/fb0", go.syscall.O_RDWR, 0)
      val mem = go.syscall.mmap(fd, 0L, Display.BYTES,
        go.syscall.PROT_READ | go.syscall.PROT_WRITE, go.syscall.MAP_SHARED)
      loopWithDevice(base, user, pass, fd, mem)
    catch case e: sgo.GoError => println("ui: device unavailable: " + e.message)

  def loopWithDevice(base: String, user: String, pass: String, fd: scala.Int, mem: go.Bytes): Unit =
    val clock = FbCaps.clock()
    val cfg = ClientConfig(base, user, pass, 5000, Session("", "", "", "", ""))
    val c = Runtime.makeWithAudio(cfg, FbCaps.httpDo(), clock)
    // reset UI cells (a fresh session per run)
    stateC.set(Shell.initial())
    connC.set(Disconnected())
    idleC.set(0.0)
    offC.set(false)
    // device init: backlight + button LEDs on
    Led.setBacklight(40)
    Led.setButtonBacklight(true)
    val fds = Evdev.open()
    println("ui: input devices open: " + Evdev.count(fds))
    val px = Draw.newBuffer()
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts))
      Runtime.start(c)
      frameLoop(c, clock, evts, fds, mem, px)
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
    }
    // teardown: clear screen + LEDs, release fb
    Draw.clear(px, Color.black)
    FbTest.present(mem, px)
    Led.setBacklight(0)
    Led.setButtonBacklight(false)
    Led.setRedLed(false)
    Led.setGreenLed(false)
    Evdev.closeAll(fds)
    go.syscall.munmap(mem)
    go.syscall.close(fd)
    println("ui: done")

  /** the frame loop. The `FrameCtx` is rebuilt each frame from the live
   *  snapshot + connection. Returns on a quit edge. */
  def frameLoop(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt],
                fds: List[scala.Int], mem: go.Bytes, px: go.Bytes): Unit =
    var snap = Runtime.emptySnapshot()
    var lastMs = clock.nowUnixMillis()
    var run = true
    while run do
      val nowMs = clock.nowUnixMillis()
      val dtMs = clampDt(nowMs - lastMs)
      lastMs = nowMs
      val dt = dtMs.toDouble / 1000.0

      // pick up the newest snapshot (Runtime.pollSnap TAKES it)
      Runtime.pollSnap(c) match
        case s: Some[StateSnapshot] => snap = s.value
        case None => ()

      // drain UI events (connection -> status/LEDs, send/play -> wata flash)
      drainUiEvents(c)

      // build this frame's context: ONE unified FrameCtx shared by every applet
      val ctx = FrameCtx(snap, connV, c, c.audioCmds, evts)

      // poll input; a quit edge (back in contacts) ends the loop
      val keyEvents = Evdev.poll(fds)
      val quit = handleFrameInput(keyEvents, ctx)
      if quit then run = false
      else
        // screensaver idle timeout
        idleC.set(tickIdle(dt, keyEvents))
        // update + render + present (skip render when display off)
        stateC.set(Shell.update(stateV, dt, ctx))
        stateC.set(Shell.withStatus(stateV, ShellStatus.fromConnection(connV)))
        if !displayOff then
          Draw.clear(px, Color.black)
          Shell.render(stateV, px, ctx)
          FbTest.present(mem, px)
        FbCaps.sleepMs(33L)

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  /** apply this frame's input to the shell; returns true if a quit edge fired
   *  (back pressed while on the contacts view with no active applet override).
   *  Wake-from-screensaver swallows the first input. */
  def handleFrameInput(evs: List[KeyEvent], ctx: FrameCtx): Boolean =
    var cur = evs
    var quit = false
    var going = true
    while going do
      cur match
        case ev :: t =>
          if displayOff then wake()               // swallow the wake input
          else quit = applyOne(ev, ctx) || quit
          cur = t
        case Nil => going = false
    quit

  /** route one key event; a `back` press on the contacts view = quit. */
  def applyOne(ev: KeyEvent, ctx: FrameCtx): Boolean =
    val isBackOnContacts = isQuitEdge(ev)
    stateC.set(Shell.handleInput(stateV, ev.key, ev.state, ctx))
    isBackOnContacts

  def isQuitEdge(ev: KeyEvent): Boolean =
    Shell.isPressed(ev.state) && isBack(ev.key) &&
      stateV.active == Shell.WATA && isContacts(Shell.wataState(stateV).view)

  def isBack(k: Key): Boolean = k match
    case KBack() => true
    case _       => false

  def isContacts(v: WataView): Boolean = v match
    case _: VContacts => true
    case _              => false

  def wake(): Unit =
    if displayOff then
      Led.setBacklight(SettingsLogic.getBrightness(Shell.settingsState(stateV)))
      Led.setButtonBacklight(true)
      offC.set(false)
    idleC.set(0.0)

  /** accumulate idle time; blank the panel past the settings timeout (0=never). */
  def tickIdle(dt: scala.Double, evs: List[KeyEvent]): scala.Double =
    if hasEvent(evs) then 0.0
    else
      val t = idleTime + dt
      if !displayOff then
        val timeout = SettingsLogic.getScreenTimeout(Shell.settingsState(stateV))
        if timeout > 0 && t >= timeout.toDouble then
          Led.setBacklight(0)
          Led.setButtonBacklight(false)
          offC.set(true)
      t

  def hasEvent(evs: List[KeyEvent]): Boolean = evs match
    case _ :: _ => true
    case Nil  => false

  // ---- UI event drain (connection -> LEDs/status, send/play flash) ------------
  def drainUiEvents(c: MatrixClient): Unit =
    var run = true
    while run do
      Runtime.pollEvent(c) match
        case e: Some[UiEvent] => onUiEvent(e.value)
        case None => run = false

  def onUiEvent(e: UiEvent): Unit = e match
    case cs: EvConn        => onConn(cs.state)
    case _: EvSendComplete => stateC.set(Shell.notifyWataSend(stateV, false))
    case _: EvSendFailed   => stateC.set(Shell.notifyWataSend(stateV, true))
    case _: EvPlaybackError => stateC.set(Shell.notifyWataPlayError(stateV))
    case _: EvSnapshot     => () // snapshot is picked up via pollSnap

  /** connection change -> mirror on the red/green LEDs. */
  def onConn(cs: ConnectionState): Unit =
    connC.set(cs)
    Led.setGreenLed(isLive(cs))
    Led.setRedLed(isBad(cs))

  def isLive(cs: ConnectionState): Boolean = cs match
    case _: Syncing   => true
    case _: Connected => true
    case _            => false

  def isBad(cs: ConnectionState): Boolean = cs match
    case _: ConnError    => true
    case _: Disconnected => true
    case _               => false

