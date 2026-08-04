import language.experimental.saferExceptions

/** The UI runtime: the frame loop that ties the sync runtime, the audio thread,
 *  a `UiDevice` backend, and the shell/applets together.
 *
 *    wata-fb ui   <base> <user> <pass>   the real device (fbdev/evdev/sysfs).
 *    wata-fb sim  <base> <user> <pass>   the host terminal front end (sim.scala).
 *    wata-fb uitest <script> …           the scripted CI driver (uiscript.scala).
 *
 *  LOOP: each frame — pick up the newest snapshot, drain UI events
 *  (connection -> status + LEDs, send/play -> wata flash), poll input, route to
 *  the shell, screensaver idle-timeout, then update + render + present + ~33ms
 *  sleep (~30fps for the panel). `frameStep` IS one frame; `frameLoop` is the
 *  device's run-until-quit driver over it, and the host front ends run their own
 *  driver over the same `frameStep` so every backend shares one frame.
 *
 *  THREADING: sync loop + action loop (Runtime.start) + audio thread all run as
 *  `fork`s in the app's `supervised` scope; the main loop IS the UI thread. The
 *  shell state is a module cell touched only by this loop (see shell.scala's
 *  header for the single-goroutine-per-cell discipline). A sibling failure
 *  cancels the whole scope (structured concurrency).
 *
 *  QUIT: `back` from the contacts view is the only quit edge here — the
 *  device is otherwise meant to run until powered off; this gives a dev/ssh
 *  run a clean way to terminate. Cleanup restores the backlight and tears the
 *  loops down through the ordinary stop edges. */

/** The FOUR device edges of the frame loop, behind one seam so the same loop
 *  drives real hardware, a terminal, and a deterministic script. Time is NOT
 *  here: the loop already takes a `Clock` capability (capabilities.scala), and
 *  the frame pace is the one sleep this trait owns.
 *
 *  Not `Shareable`: a `UiDevice` never crosses a goroutine boundary — it is
 *  built and used by the UI goroutine alone, so the real impl may hold the
 *  mmap'd framebuffer slice as a plain field. */
trait UiDevice:
  /** every input event pending since the last call (never blocks). */
  def pollInput(): List[KeyEvent]
  /** blit the RGB565 pixel buffer to the display. */
  def present(px: go.Bytes): Unit
  /** the connection-state LEDs, set together (green = live, red = bad). */
  def leds(green: Boolean, red: Boolean): Unit
  /** panel backlight, 0..255 (0 = off). */
  def backlight(level: scala.Int): Unit
  /** keypad backlight. */
  def buttonBacklight(on: Boolean): Unit
  /** pace one frame. */
  def frameSleep(ms: Long): Unit

/** the REAL device: evdev fds in, the mmap'd /dev/fb0 out, LEDs over sysfs.
 *  Every method is the call `frameLoop` used to make inline, in the same
 *  order — the emitted device path is unchanged. */
final class FbUiDevice(fds: List[scala.Int], mem: go.Bytes) extends UiDevice:
  def pollInput(): List[KeyEvent] = Evdev.poll(fds)
  def present(px: go.Bytes): Unit = FbTest.present(mem, px)
  def leds(green: Boolean, red: Boolean): Unit =
    Led.setGreenLed(green)
    Led.setRedLed(red)
  def backlight(level: scala.Int): Unit = Led.setBacklight(level)
  def buttonBacklight(on: Boolean): Unit = Led.setButtonBacklight(on)
  def frameSleep(ms: Long): Unit = FbCaps.sleepMs(ms)

object Ui:

  /** the frame pace (~30fps for the panel). */
  val FRAME_MS: Long = 33L

  // The UI-goroutine shell state lives in `val`-held Atomic cells: still
  // driven by the one UI goroutine by protocol, but data-race-freedom is
  // enforced structurally rather than merely by convention. Immutable
  // snapshots swap through the cells (ShellState / ConnectionState box;
  // Double boxes since Go has no atomic float word; Boolean rides
  // atomic.Bool). Reads keep their old names via private accessor defs.
  private val stateC: sgo.Atomic[ShellState] = sgo.atomic(Shell.initial(FbConfig.loadPrefs()))
  private val connC: sgo.Atomic[ConnectionState] = sgo.atomic(Disconnected())
  private val idleC: sgo.Atomic[scala.Double] = sgo.atomic(0.0)
  private val offC: sgo.Atomic[Boolean] = sgo.atomic(false)
  // the frame carry-over that used to be `frameLoop`'s two loop vars — cells
  // now, so `frameStep` is one self-contained frame any driver can call.
  private val snapC: sgo.Atomic[StateSnapshot] = sgo.atomic(Runtime.emptySnapshot())
  private val lastMsC: sgo.Atomic[Long] = sgo.atomic(0L)
  // one config write per session: set once the credentials are known good.
  private val savedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private def stateV: ShellState = stateC.get()
  private def connV: ConnectionState = connC.get()
  private def idleTime: scala.Double = idleC.get()
  private def displayOff: Boolean = offC.get()

  /** the live shell state — what a host driver renders assertions against. */
  def shellState: ShellState = stateC.get()
  /** the snapshot this frame drew from. */
  def frameSnap: StateSnapshot = snapC.get()
  /** is the screensaver holding the panel blanked? */
  def screenOff: Boolean = offC.get()
  /** the connection the status line and the LEDs are mirroring. */
  def connection: ConnectionState = connC.get()

  /** every credential is optional: what the arguments do not give,
   *  `FbConfig.resolve` takes from the stored session, which is what lets the
   *  device boot straight into the client. */
  def run(args: Array[String]): Unit =
    runUi(FbConfig.argAt(args, 1), FbConfig.argAt(args, 2), FbConfig.argAt(args, 3))

  def runUi(base: String, user: String, pass: String): Unit =
    val cfg = FbConfig.resolve(base, user, pass, 5000)
    if cfg.homeserver == "" then println(FbConfig.noServerMsg("ui"))
    else openAndLoop(cfg)

  def openAndLoop(cfg: ClientConfig): Unit =
    println("ui: opening /dev/fb0 ...")
    try
      val fd = go.syscall.open("/dev/fb0", go.syscall.O_RDWR, 0)
      val mem = go.syscall.mmap(fd, 0L, Display.BYTES,
        go.syscall.PROT_READ | go.syscall.PROT_WRITE, go.syscall.MAP_SHARED)
      loopWithDevice(cfg, fd, mem)
    catch case e: sgo.GoError => println("ui: device unavailable: " + e.message)

  def loopWithDevice(cfg: ClientConfig, fd: scala.Int, mem: go.Bytes): Unit =
    val clock = FbCaps.clock()
    val c = Runtime.makeWithAudio(cfg, FbCaps.httpDo(), clock)
    resetCells()
    val fds = Evdev.open()
    println("ui: input devices open: " + Evdev.count(fds))
    val dev = FbUiDevice(fds, mem)
    // device init: the STORED backlight level (resetCells has just restored
    // the settings applet from the config store) + button LEDs on.
    dev.backlight(SettingsLogic.getBrightness(Shell.settingsState(stateV)))
    dev.buttonBacklight(true)
    val px = Draw.newBuffer()
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts))
      Runtime.start(c)
      frameLoop(c, clock, evts, dev, px)
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
    }
    // teardown: clear screen + LEDs, release fb
    Draw.clear(px, Color.black)
    dev.present(px)
    dev.backlight(0)
    dev.buttonBacklight(false)
    dev.leds(false, false)
    Evdev.closeAll(fds)
    go.syscall.munmap(mem)
    go.syscall.close(fd)
    println("ui: done")

  /** reset the UI cells — a fresh session per run (the host drivers reuse the
   *  process across sequential sessions, so this is not merely cosmetic). */
  def resetCells(): Unit =
    stateC.set(Shell.initial(FbConfig.loadPrefs()))
    connC.set(Disconnected())
    idleC.set(0.0)
    offC.set(false)
    snapC.set(Runtime.emptySnapshot())
    lastMsC.set(0L)
    savedC.set(false)

  /** seed the frame clock — every driver calls this once before its first
   *  `frameStep`, so the first frame's dt is a frame and not the epoch. */
  def beginFrames(clock: Clock): Unit = lastMsC.set(clock.nowUnixMillis())

  /** the device's run-until-quit driver: frames until a quit edge. */
  def frameLoop(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt],
                dev: UiDevice, px: go.Bytes): Unit =
    beginFrames(clock)
    var run = true
    while run do
      if frameStep(c, clock, evts, dev, px) then run = false

  /** ONE frame. The `FrameCtx` is rebuilt from the live snapshot + connection.
   *  Returns true on a quit edge — on which nothing is updated, rendered,
   *  presented or slept, exactly as the device loop always behaved. */
  def frameStep(c: MatrixClient, clock: Clock, evts: sgo.Chan[AudioEvt],
                dev: UiDevice, px: go.Bytes): Boolean =
    val nowMs = clock.nowUnixMillis()
    val dtMs = clampDt(nowMs - lastMsC.get())
    lastMsC.set(nowMs)
    val dt = dtMs.toDouble / 1000.0

    // pick up the newest snapshot (Runtime.pollSnap TAKES it)
    Runtime.pollSnap(c) match
      case s: Some[StateSnapshot] => snapC.set(s.value)
      case None => ()

    // drain UI events (connection -> status/LEDs + session persist,
    // send/play -> wata flash)
    drainUiEvents(c, dev)

    // build this frame's context: ONE unified FrameCtx shared by every applet
    val ctx = FrameCtx(snapC.get(), connV, c, c.audioCmds, evts)

    // poll input; a quit edge (back in contacts) ends the loop
    val keyEvents = dev.pollInput()
    val quit = handleFrameInput(keyEvents, ctx, dev)
    if !quit then
      // screensaver idle timeout
      idleC.set(tickIdle(dt, keyEvents, dev))
      // update + render + present (skip render when display off)
      stateC.set(Shell.update(stateV, dt, ctx))
      stateC.set(Shell.withStatus(stateV, ShellStatus.fromConnection(connV)))
      if !displayOff then
        Draw.clear(px, Color.black)
        Shell.render(stateV, px, ctx)
        dev.present(px)
      dev.frameSleep(FRAME_MS)
    quit

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  /** apply this frame's input to the shell; returns true if a quit edge fired
   *  (back pressed while on the contacts view with no active applet override).
   *  Wake-from-screensaver swallows the first input. */
  def handleFrameInput(evs: List[KeyEvent], ctx: FrameCtx, dev: UiDevice): Boolean =
    var cur = evs
    var quit = false
    var going = true
    while going do
      cur match
        case ev :: t =>
          if displayOff then wake(dev)              // swallow the wake input
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

  def wake(dev: UiDevice): Unit =
    if displayOff then
      dev.backlight(SettingsLogic.getBrightness(Shell.settingsState(stateV)))
      dev.buttonBacklight(true)
      offC.set(false)
    idleC.set(0.0)

  /** accumulate idle time; blank the panel past the settings timeout (0=never). */
  def tickIdle(dt: scala.Double, evs: List[KeyEvent], dev: UiDevice): scala.Double =
    if hasEvent(evs) then 0.0
    else
      val t = idleTime + dt
      if !displayOff then
        val timeout = SettingsLogic.getScreenTimeout(Shell.settingsState(stateV))
        if timeout > 0 && t >= timeout.toDouble then
          dev.backlight(0)
          dev.buttonBacklight(false)
          offC.set(true)
      t

  def hasEvent(evs: List[KeyEvent]): Boolean = evs match
    case _ :: _ => true
    case Nil  => false

  // ---- UI event drain (connection -> LEDs/status, send/play flash) ------------
  def drainUiEvents(c: MatrixClient, dev: UiDevice): Unit =
    var run = true
    while run do
      Runtime.pollEvent(c) match
        case e: Some[UiEvent] => onUiEvent(c, e.value, dev)
        case None => run = false

  def onUiEvent(c: MatrixClient, e: UiEvent, dev: UiDevice): Unit = e match
    case cs: EvConn        => onConn(c, cs.state, dev)
    case _: EvSendComplete => stateC.set(Shell.notifyWataSend(stateV, false))
    case _: EvSendFailed   => stateC.set(Shell.notifyWataSend(stateV, true))
    case _: EvPlaybackError => stateC.set(Shell.notifyWataPlayError(stateV))
    case _: EvSnapshot     => () // snapshot is picked up via pollSnap

  /** connection change -> mirror on the red/green LEDs, and — the first time a
   *  session actually comes up — write the credentials to the config store so
   *  the next boot resumes without arguments. */
  def onConn(c: MatrixClient, cs: ConnectionState, dev: UiDevice): Unit =
    connC.set(cs)
    dev.leds(isLive(cs), isBad(cs))
    if isLive(cs) then persistSession(c)

  /** `Runtime.lastAuth` is set immediately before the runtime publishes
   *  `Connected`, so by the time this frame drains that event the credentials
   *  are there. Written once per session (`savedC`), and only when the token
   *  is non-empty — a failed login publishes `ConnError`, never `Connected`,
   *  but the guard keeps a stale store from being overwritten with nothing. */
  def persistSession(c: MatrixClient): Unit =
    if !savedC.get() then
      val creds = Runtime.lastAuth
      if creds.accessToken != "" then
        savedC.set(true)
        FbConfig.saveLogin(c.cfg.homeserver, c.cfg.username, creds)

  def isLive(cs: ConnectionState): Boolean = cs match
    case _: Syncing   => true
    case _: Connected => true
    case _            => false

  def isBad(cs: ConnectionState): Boolean = cs match
    case _: ConnError    => true
    case _: Disconnected => true
    case _               => false
