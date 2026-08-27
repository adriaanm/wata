import language.experimental.saferExceptions
import sgo.add  // the Atomic[Int] add extension (the session tally cells)

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
 *  EXIT: `back` from the contacts view, twice inside `QUIT_ARM_S`, opens the
 *  EXIT MENU (plan 0040) — it does not quit. The two-step arm survives because
 *  the device boots straight into this app and a single stray red key should
 *  not take over the screen; what changed is what the second press reaches. The
 *  menu is modal and is the only thing that ends the loop, and only through its
 *  `Restart app` row (inittab respawns the app, which IS the restart); its
 *  other rows reboot, power off, or reach the two cable-only modes. Cleanup
 *  restores the backlight and tears the loops down through the ordinary stop
 *  edges (`Runtime.stopClient` is idempotent, so the Settings -> Network OFF
 *  path may already have run it). */

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
  /** un-blank the display: the real device's kernel framebuffer blanks
   *  independently of the backlight (a blanked ST7735S shows white with the
   *  backlight on), so waking is backlight AND unblank; a host backend has no
   *  blank state and no-ops. */
  def unblank(): Unit
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
  def unblank(): Unit = Led.unblankFb()
  def buttonBacklight(on: Boolean): Unit = Led.setButtonBacklight(on)
  def frameSleep(ms: Long): Unit = FbCaps.sleepMs(ms)

/** the arrival banner (plan 0041): what quiet mode shows at the top of the
 *  panel for a few seconds after an arrival. `roomId` names the announced
 *  conversation (the one screen the banner never draws over) and `untilMs`
 *  is the frame clock's expiry. */
case class NotifyBanner(title: String, body: String, roomId: String, untilMs: Long)

/** one frame's LED decision, as the arbiter computes it. */
case class LedState(green: Boolean, red: Boolean)

object Ui:

  /** the frame pace (~30fps for the panel). */
  val FRAME_MS: Long = 33L
  /** the pace while the rolodex motion is live (plan 0077 stage 2): a coasting
   *  flick wants the panel's best rate, so the sleep drops to ~60fps while
   *  `Motion.live` and returns to `FRAME_MS` at rest. Whether the ST7735S path
   *  can actually SHOW 60fps is stage 4's hardware measurement — if it cannot,
   *  this goes back to 33 and only the physics dt cares. The scripted driver
   *  is untouched: its `frameSleep` ignores the argument (a fixed real pace)
   *  and its clock ticks a fixed 33ms per frame regardless. */
  val MOTION_FRAME_MS: Long = 16L

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
  // the scripted driver's connection override (uitest only): -1 = report the
  // live connection, else a `Runtime.connTag` the frame loop reports instead.
  // The sync loop republishes `Syncing` on every snapshot, so a scripted
  // reconnecting/disconnected frame cannot be pinned by writing the live cell
  // — the override is read where the frame reads the state.
  private val connForceC: sgo.Atomic[scala.Int] = sgo.atomic(-1)
  // the two-step quit's arming window, in seconds remaining (0 = unarmed).
  // The device boots straight into wata-fb and inittab respawns it, so a
  // single stray red key on the contact list must not black the screen out —
  // the first press arms and says so, a second one within the window OPENS
  // THE EXIT MENU (plan 0040), which is where quitting now lives.
  private val quitArmC: sgo.Atomic[scala.Double] = sgo.atomic(0.0)
  // the DOT-DOT RECOVERY gesture (the white-panel incident, 2026-08-27):
  // holding BOTH dot buttons for DOT_HOLD_FRAMES runs the ST7735
  // powerdown-blank cycle and then ends the frame loop exactly as the exit
  // menu's confirmed `Restart app` does (tty1 respawns the app) — the way
  // back from a glass gone white when nothing on it can be read. The held
  // flags and the frame counter live HERE, beside the quit arm, because the
  // gesture must work from EVERY screen — including behind the modal exit
  // menu, which Shell.handleInput never sees.
  private val dot1HeldC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val dot2HeldC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val dotArmC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val dotFiredC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  // the exit menu, or None while it is closed. A mode of the shell rather
  // than an applet: it is modal, it outlives no session, and an applet would
  // put it in the left/right rotation where it must not be. Touched only by
  // the UI loop, like every other cell here.
  private val exitC: sgo.Atomic[Option[ExitMenuState]] = sgo.atomic(None)
  // the outbox markers (plan 0022), refreshed from `EvOutbox` on the ordinary
  // event drain: the conversation keys with a send still queued, and the ones
  // that lost a message for good. Cells rather than a snapshot field because
  // an offline device publishes no snapshots — and the queue is exactly what
  // an offline device accumulates.
  private val unsentC: sgo.Atomic[List[String]] = sgo.atomic(Nil)
  private val undelivC: sgo.Atomic[List[String]] = sgo.atomic(Nil)
  // the arrival-notification marks (plan 0041): the unplayed counts the
  // per-frame `Notify.step` measures the edge against — wataclient's model,
  // the same record the mac pump carries in `PumpSt.marks`.
  private val notifyC: sgo.Atomic[NotifyState] = sgo.atomic(Notify.initial())
  // the quiet-mode banner, or None while nothing is announced.
  private val bannerC: sgo.Atomic[Option[NotifyBanner]] = sgo.atomic(None)
  // the last pair actually written to the LEDs, so the ~30fps loop only
  // touches sysfs on a change (a blink is two writes a second, not sixty).
  private val lastLedC: sgo.Atomic[Option[LedState]] = sgo.atomic(None)
  private def stateV: ShellState = stateC.get()
  private def connV: ConnectionState = connOf(connForceC.get(), connC.get())
  private def idleTime: scala.Double = idleC.get()
  private def displayOff: Boolean = offC.get()

  /** the live shell state — what a host driver renders assertions against. */
  def shellState: ShellState = stateC.get()
  /** the snapshot this frame drew from. */
  def frameSnap: StateSnapshot = snapC.get()
  /** conversations with a send still queued (what the row marker draws). */
  def unsentKeys: List[String] = unsentC.get()
  /** conversations that lost a message for good. */
  def undeliveredKeys: List[String] = undelivC.get()

  /** is the screensaver holding the panel blanked? */
  def screenOff: Boolean = offC.get()
  /** the connection the status line and the LEDs are mirroring (the override
   *  included — this is what the frame drew). */
  def connection: ConnectionState = connV

  /** force the connection the frames report (uitest only; -1 = the live one). */
  def forceConn(tag: scala.Int): Unit = connForceC.set(tag)

  /** seconds the quit confirmation stays armed after the first Back press. */
  val QUIT_ARM_S: scala.Double = 2.0

  /** is the quit confirmation armed right now (what the applet draws its
   *  "again to exit" line from, and what a scripted run probes)? */
  def quitArmed: Boolean = quitArmC.get() > 0.0

  /** the exit menu's state, or None while it is closed — what a scripted run
   *  probes and what the frame renders instead of the shell. */
  def exitMenu: Option[ExitMenuState] = exitC.get()

  /** is the exit menu open? */
  def exitMenuOpen: Boolean = exitMenu match
    case _: Some[ExitMenuState] => true
    case None                   => false

  /** the row the exit menu is on, or -1 while it is closed. */
  def exitMenuRow: scala.Int = exitMenu match
    case s: Some[ExitMenuState] => s.value.selected
    case None                   => -1

  /** how many OK presses have landed on that row (0 while closed). */
  def exitMenuConfirm: scala.Int = exitMenu match
    case s: Some[ExitMenuState] => s.value.confirm
    case None                   => 0

  def connOf(tag: scala.Int, live: ConnectionState): ConnectionState =
    if tag < 0 then live else stateOfTag(tag)

  def stateOfTag(tag: scala.Int): ConnectionState =
    if tag == 0 then Disconnected()
    else if tag == 1 then Connecting()
    else if tag == 2 then Connected()
    else if tag == 3 then Syncing()
    else if tag == 4 then ConnError()
    else ConnAuthRejected()

  // Session tallies of the terminal UI events — what a wait-timeout in the
  // scripted driver reports, so "the echo never came" is distinguishable
  // from "the send failed" and "sync fell into error backoff" after the fact.
  private val sendOkC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val sendFailC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val playFailC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val connErrC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  /** sends completed this session (`EvSendComplete` count). */
  def sendOks: scala.Int = sendOkC.get()
  /** sends failed this session (`EvSendFailed` count). */
  def sendFails: scala.Int = sendFailC.get()
  /** plays failed this session (`EvPlaybackError` count). */
  def playFails: scala.Int = playFailC.get()
  /** `ConnError` transitions this session (each one = sync-loop backoff). */
  def connErrs: scala.Int = connErrC.get()
  /** frames completed this session. A frame loop that a blocked channel send
   *  has frozen stops advancing this, which is what makes "the UI never blocks
   *  on the client" an assertion a scripted run can make. */
  def frames: scala.Int = frameC.get()
  private val frameC: sgo.Atomic[scala.Int] = sgo.atomic(0)

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
    val c = Runtime.makeWithAudioStored(cfg, FbCaps.httpDo(), clock, FbConfig.outbox())
    resetCells()
    val fds = Evdev.open()
    println("ui: input devices open: " + Evdev.count(fds))
    val dev = FbUiDevice(fds, mem)
    // device init: UNBLANK first — the kernel's console-blank timer may have
    // blanked the panel before this respawn, and a blanked panel shows white
    // and ignores fb writes (FB-FIRST-FRAME-WHITE) — then the STORED backlight
    // level (resetCells has just restored the settings applets from the config
    // store; the DEV state is the one Ui reads — Shell.syncPrefs keeps the kid
    // panel's mirror equal to it) + button LEDs on.
    dev.unblank()
    dev.backlight(SettingsLogic.getBrightness(Shell.devState(stateV)))
    dev.buttonBacklight(true)
    val px = Draw.newBuffer()
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, true))
      Runtime.start(c)
      CmdPoller.start(c)     // the device-command mailbox (plan 0020)
      frameLoop(c, clock, evts, dev, px)
      CmdPoller.stop()
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
    // the notify-mode cell first: `Shell.initial` restores BOTH settings
    // applets (`KidSettingsLogic.restored` / `SettingsLogic.restored`), and
    // each reads it for its notify row, so it is primed before the shell.
    FbConfig.loadNotifyMode()
    stateC.set(Shell.initial(FbConfig.loadPrefs()))
    connC.set(Disconnected())
    idleC.set(0.0)
    offC.set(false)
    snapC.set(Runtime.emptySnapshot())
    lastMsC.set(0L)
    savedC.set(false)
    sendOkC.set(0)
    sendFailC.set(0)
    playFailC.set(0)
    connErrC.set(0)
    frameC.set(0)
    connForceC.set(-1)
    quitArmC.set(0.0)
    dot1HeldC.set(false)
    dot2HeldC.set(false)
    dotArmC.set(0)
    dotFiredC.set(0)
    exitC.set(None)
    unsentC.set(Nil)
    undelivC.set(Nil)
    notifyC.set(Notify.initial())
    bannerC.set(None)
    lastLedC.set(None)
    repKeyC.set(0)
    repTimeC.set(0.0)
    repNextC.set(0.0)
    NetStatus.reset()
    ChargeStatus.reset()
    Enrol.reset()
    Diag.resetNetTest()

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

    // this frame's connectivity: the interface pipe (re-read on its own ~5s
    // cadence) plus the client's health, computed ONCE — the header element
    // and the status line both draw from it. `poll` advances the refresh
    // countdown and the reconnecting blink, so it belongs here, once a frame.
    val conn = connV
    val net = NetStatus.poll(conn)
    // the charge-anomaly poll (plan 0073): same shape, its own cadence —
    // advances the sysfs read countdown and the debounce streak the header
    // mark (`WataLogic.chargeAlertView`) renders.
    ChargeStatus.poll()
    // one log line per change of (pipe, connection, clock) — a boot's whole
    // connectivity story, against a transport that logs a dial failure once
    // per distinct reason and then looks silent (plan 0035).
    NetStatus.logTransition(net, conn, NetStatus.clockOk())
    // and one line per change of what the client HAS — a healthy transport
    // whose message count never moves is a different bug from a moving count
    // that never reaches the screen, and only this line tells them apart.
    NetStatus.logSnapshot(snapC.get(), conn)
    // the network ARRIVING is the one moment worth retrying immediately: the
    // sync loop's backoff may be at its 60s ceiling from dialling with no
    // interface at all.
    if NetStatus.takePipeArrival() then Runtime.retryNow(c)

    // arrival notifications (plan 0041): the edge, once a frame, off the
    // snapshot this frame picked up; then the LED arbiter and the banner's
    // aging — every channel derived from the same unplayed counts.
    notifyFrame(c, snapC.get(), nowMs)
    applyLeds(dev, ledArbiter(conn, Notify.totalUnplayed(snapC.get()), nowMs))
    tickBanner(nowMs)

    // build this frame's context: ONE unified FrameCtx shared by every applet
    val ctx = FrameCtx(snapC.get(), conn, net, c, c.audioCmds, evts,
      unsentC.get(), undelivC.get(), quitArmed)

    // poll input — synthesizing the auto-repeat the keypad hardware cannot
    // (no EV_REP; see `withSynthRepeats`) — then route it
    val keyEvents = withSynthRepeats(dev.pollInput(), dt)
    var quit = handleFrameInput(keyEvents, ctx, dev)
    // the dot-dot recovery's hold clock, one frame — its firing is a quit
    // edge exactly like the exit menu's confirmed Restart (tty1 respawns).
    if !quit && tickDotHold() then quit = true
    if !quit then
      // the quit confirmation ages out; a press this frame re-armed it, so
      // this runs after the input and one frame of dt never expires it
      tickQuitArm(dt)
      tickExitMenu(dt)
      // screensaver idle timeout
      idleC.set(tickIdle(dt, keyEvents, dev))
      // update + render + present (skip render when display off). The shell
      // still UPDATES behind an open menu — the sync loop keeps running and
      // its state should not be stale when the menu closes — but the menu is
      // what gets drawn, whole-frame: it is a decision, not an overlay.
      stateC.set(Shell.update(stateV, dt, ctx))
      stateC.set(Shell.withStatus(stateV, ShellStatus.fromNet(net, conn)))
      // the rolodex motion integrator (plan 0077 stage 2), once per frame
      // with the same clamped dt as everything else on the frame clock
      stepMotion(dt)
      logMotionFps(nowMs, dtMs)
      if !displayOff then
        Draw.clear(px, Color.black)
        exitMenu match
          case s: Some[ExitMenuState] => ExitMenu.render(s.value, px, ctx)
          case None                   => Shell.render(stateV, px, ctx)
        drawBanner(px)
        dev.present(px)
      dev.frameSleep(framePaceMs())
    tally(frameC)
    quit

  // ---- the rolodex motion pump (plan 0077 stage 2, FB-MOTION-PUMP) ------------

  /** is the contact list — the rolodex integrator's screen — what the shell
   *  is showing? The watch's `isContacts` gating, restated for a shell that
   *  has more than one applet. */
  def motionShowing: Boolean =
    stateV.active == Shell.WATA && isContacts(Shell.wataState(stateV).view)

  /** is the conversation — the thread integrator's screen (plan 0078) —
   *  what the shell is showing? */
  def convMotionShowing: Boolean =
    stateV.active == Shell.WATA && isConversation(Shell.wataState(stateV).view)

  /** Step the wata applet's motion integrator with this frame's real clamped
   *  dt, then hand the applet the item the centre band is over — the fb half
   *  of the watch's `Pump.stepMotion`, write included (plan 0077 stage 3).
   *
   *  `selected` stays what it always was — the conversation a press acts on —
   *  and the integrator is what MOVES it, so **holding the talk button talks
   *  to the centre card at any zoom** falls out rather than being a special
   *  case: `WataLogic.pttPress` reads `selected` and never asks how the
   *  selection got there. A position past the end of a list that SHRANK is
   *  put back first (`placeAt`, the watch's rule — the end spring must not
   *  argue with the model).
   *
   *  While the contact screen is NOT showing, the integrator is not stepped —
   *  it is SEATED on `selected` instead, so a selection that moved by other
   *  means (entering a conversation, a shrink's discrete clamp) is where the
   *  rolodex starts from when the screen comes back, not something the
   *  physics argues with. */
  def stepMotion(dt: scala.Double): Unit =
    if stateV.active != Shell.WATA then ()
    else
      val w = Shell.wataState(stateV)
      stateC.set(Shell.withApplet(stateV, Shell.WATA, WataApplet(
        WataLogic.stepMotions(w, dt, snapC.get(), unsentC.get(), undelivC.get()))))

  // motion throughput (plan 0077 stage 4): while the rolodex coasts, count
  // real frames against the real clock and say what rate the panel actually
  // sustained — one line per ~second to stdout (/tmp/wata.log on the device),
  // plus a flush when the motion settles. Device-only (`Diag.onDevice()`),
  // so the host front ends and the scripted harness never see a byte; the
  // counting itself is three atomic ints off the render path.
  private val mFramesC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val mWinMsC: sgo.Atomic[Long] = sgo.atomic(0L)
  private val mLiveMsC: sgo.Atomic[Long] = sgo.atomic(0L)

  /** account one frame of live motion (or flush after the last one). `nowMs`
   *  and `dtMs` are the frame's real clock values, so the reported fps is what
   *  the panel sustained, not what the sleep asked for. */
  def logMotionFps(nowMs: Long, dtMs: Long): Unit =
    if (motionShowing || convMotionShowing) && motionLive then
      if mFramesC.get() == 0 then mWinMsC.set(nowMs - dtMs)
      tally(mFramesC)
      mLiveMsC.set(mLiveMsC.get() + dtMs)
      if nowMs - mWinMsC.get() >= 1000L then flushMotionFps(nowMs)
    else if mFramesC.get() > 0 then
      flushMotionFps(nowMs)   // the episode's last (partial) window
      mLiveMsC.set(0L)

  def flushMotionFps(nowMs: Long): Unit =
    val span = nowMs - mWinMsC.get()
    val n = mFramesC.get()
    if span > 0L && n > 1 && Diag.onDevice() then
      println("motion: fps=" + (n.toLong * 1000L / span) + " frames=" + n +
        " span_ms=" + span + " live_ms=" + mLiveMsC.get())
    mFramesC.set(0)
    mWinMsC.set(nowMs)

  /** this frame's pace: the motion rate while the shown rolodex is coasting,
   *  the ordinary ~30fps otherwise. */
  def framePaceMs(): Long =
    if motionShowing && Motion.live(Shell.wataState(stateV).motion) then MOTION_FRAME_MS
    else if convMotionShowing && Motion.live(Shell.wataState(stateV).convMotion) then MOTION_FRAME_MS
    else FRAME_MS

  /** the SHOWN integrator's centre index against its live list — the
   *  `motioncentre` probe's value: the thread's while the conversation shows,
   *  else the rolodex's. */
  def motionCentre: scala.Int =
    val w = Shell.wataState(stateV)
    if convMotionShowing then
      Motion.centre(w.convMotion, WataLogic.threadCount(snapC.get(),
        w.convContactIdx, unsentC.get(), undelivC.get()))
    else Motion.centre(w.motion, WataLogic.convCount(snapC.get()))

  /** is the shown integrator still moving (the `motionlive` probe)? */
  def motionLive: Boolean =
    val w = Shell.wataState(stateV)
    if convMotionShowing then Motion.live(w.convMotion) else Motion.live(w.motion)

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  // ---- held-arrow auto-repeat (the keypad has no EV_REP) ----------------------
  // The matrix keypad's input node advertises EV=0x13 — SYN|KEY|MSC, NO
  // EV_REP — so the kernel never autorepeats these keys: a held arrow
  // delivered exactly one `Pressed`, one detent, no coast (the owner's
  // "holding scrolls once then stops"). The plan-0077 hardware ramp was
  // measured with INJECTED value=2 trains, which masked this; and EVIOCSREP
  // cannot enable the kernel's repeat on a node without EV_REP (ENOSYS). So
  // the frame loop synthesizes the missing edges itself: a held up/down
  // arms a hold clock, and after `REPEAT_DELAY_S` a `Repeat()` edge fires
  // every `REPEAT_PERIOD_S` (at most one per frame), routed through the
  // ordinary input path — the impulse feeds see exactly what a kernel
  // repeat would be, and the scripted driver's fixed 33 ms clock makes the
  // ramp deterministic under `advance N`.

  /** the typematic pace: first repeat after ~1/3 s, then ~15/s — between the
   *  kernel default (250/33 ms, too eager at one detent per edge) and the
   *  ~50 ms trains the plan-0077 hardware session ramped with. */
  val REPEAT_DELAY_S: scala.Double = 0.33
  val REPEAT_PERIOD_S: scala.Double = 0.066

  // 0 = nothing held, 1 = up, 2 = down; the hold clock and the next-edge
  // threshold (seconds since the press).
  private val repKeyC: sgo.Atomic[scala.Int] = sgo.atomic(0)
  private val repTimeC: sgo.Atomic[scala.Double] = sgo.atomic(0.0)
  private val repNextC: sgo.Atomic[scala.Double] = sgo.atomic(0.0)

  /** track held arrows from this frame's real events and prepend the
   *  synthesized `Repeat` edge when one is due. A wake-swallowed press
   *  (display off) never arms — the hold that woke the screen must not
   *  scroll it — and only the LAST pressed arrow repeats, the typematic
   *  rule. */
  def withSynthRepeats(evs: List[KeyEvent], dt: scala.Double): List[KeyEvent] =
    if displayOff then
      repKeyC.set(0)
      evs
    else
      trackHeld(evs)
      if repKeyC.get() == 0 then evs
      else
        repTimeC.set(repTimeC.get() + dt)
        if repTimeC.get() >= repNextC.get() then
          repNextC.set(repNextC.get() + REPEAT_PERIOD_S)
          KeyEvent(heldKey(), Repeat()) :: evs
        else evs

  def heldKey(): Key = if repKeyC.get() == 1 then KUp() else KDown()

  def trackHeld(evs: List[KeyEvent]): Unit =
    var cur = evs
    var going = true
    while going do
      cur match
        case ev :: t =>
          trackOne(ev)
          cur = t
        case Nil => going = false

  def trackOne(ev: KeyEvent): Unit =
    val arrow = arrowOf(ev.key)
    if arrow != 0 then
      if Shell.isPressed(ev.state) then
        repKeyC.set(arrow)
        repTimeC.set(0.0)
        repNextC.set(REPEAT_DELAY_S)
      else if isReleased(ev.state) && repKeyC.get() == arrow then
        repKeyC.set(0)

  def arrowOf(k: Key): scala.Int = k match
    case _: KUp   => 1
    case _: KDown => 2
    case _        => 0

  def isReleased(ks: KeyState): Boolean = ks match
    case Released() => true
    case _          => false

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

  /** route one key event. The exit menu is MODAL — while it is open it takes
   *  every key and the shell sees none of them, so nothing behind it moves
   *  under a person who is choosing how to leave.
   *
   *  Otherwise `back` on the contacts view is the exit gesture, still TWO-STEP
   *  (the applet says "again to exit"): the second press inside the window
   *  opens the menu rather than quitting. Only the menu quits now. */
  def applyOne(ev: KeyEvent, ctx: FrameCtx): Boolean =
    if trackDotHold(ev) then false
    else applyRouted(ev, ctx)

  def applyRouted(ev: KeyEvent, ctx: FrameCtx): Boolean =
    exitMenu match
      case s: Some[ExitMenuState] => applyExitMenu(s.value, ev, ctx)
      case None =>
        if isQuitEdge(ev) && quitArmed then
          quitArmC.set(0.0)
          exitC.set(Some(ExitMenu.initial()))
        else
          if isQuitEdge(ev) then quitArmC.set(QUIT_ARM_S)
          stateC.set(Shell.handleInput(stateV, ev.key, ev.state, ctx))
        false

  /** the menu's own key routing; true only when a confirmed `Restart app`
   *  ends the frame loop. BACK closes the menu from any row, armed or not. */
  def applyExitMenu(s: ExitMenuState, ev: KeyEvent, ctx: FrameCtx): Boolean =
    if ExitMenu.closes(ev.key, ev.state) then
      exitC.set(None)
      false
    else
      val quit = Shell.isPressed(ev.state) && isOkKey(ev.key) && ExitMenu.quitsOnOk(s)
      exitC.set(Some(ExitMenu.handleInput(s, ev.key, ev.state, ctx)))
      if quit then exitC.set(None)
      quit

  def isOkKey(k: Key): Boolean = k match
    case _: KEnter => true
    case _         => false

  // ---- the dot-dot recovery gesture -------------------------------------------
  // Holding BOTH dots for ~3 s = recovery: the ST7735 powerdown-blank cycle
  // (Led.powerCycleFb — the Learnings-log recipe for the white-glass panel,
  // device-gated) followed by a clean app exit through the same edge the exit
  // menu's `Restart app` uses; tty1 respawns the app. Ordinary single-dot
  // taps are UNTOUCHED: applet cycling still fires on the press, exactly as
  // before (the snake's goldens count applet ticks from the press frame, so
  // release-fired cycling would move pixels) — only a dot pressed WHILE THE
  // OTHER DOT IS HELD is swallowed as the combo's second half. The cost is
  // that the combo's FIRST press still cycles once, which is invisible on
  // the white glass the gesture exists for and moot once the app restarts;
  // an aborted combo leaves the shell one applet over, one tap from home.

  /** frames both dots must be concurrently held (~3 s at the 33 ms clock). */
  val DOT_HOLD_FRAMES: scala.Int = 90

  /** frames the both-dots hold has currently run (the `dotarm` probe). */
  def dotArmFrames: scala.Int = dotArmC.get()
  /** recovery firings this session (the `dotfired` probe — on the device the
   *  loop ends at 1; the scripted driver ignores quit edges and reads it). */
  def dotFired: scala.Int = dotFiredC.get()

  /** track dot press/release; true = the event is the combo's second half
   *  and must not reach the router. Any release resets the arm count. */
  def trackDotHold(ev: KeyEvent): Boolean =
    val d = dotOf(ev.key)
    if d == 0 then false
    else if Shell.isPressed(ev.state) then
      val otherHeld = if d == 1 then dot2HeldC.get() else dot1HeldC.get()
      setDotHeld(d, true)
      otherHeld   // the combo's join is swallowed; a solo press routes (cycles)
    else
      if isReleased(ev.state) then
        setDotHeld(d, false)
        dotArmC.set(0)
      false       // releases route as they always did (a no-op everywhere)

  def dotOf(k: Key): scala.Int = k match
    case _: KDot1 => 1
    case _: KDot2 => 2
    case _        => 0

  def setDotHeld(d: scala.Int, held: Boolean): Unit =
    if d == 1 then dot1HeldC.set(held) else dot2HeldC.set(held)

  /** one frame of the hold clock; true when the recovery fired — the frame
   *  loop's quit edge, the exit-menu Restart's plumbing. Fires once per hold
   *  (the held flags are dropped, so a re-fire needs fresh presses). */
  def tickDotHold(): Boolean =
    var fired = false
    if displayOff then
      // wake swallows the inputs, so a hold started against a dark panel
      // never armed; drop anything stale rather than counting blind.
      dot1HeldC.set(false)
      dot2HeldC.set(false)
      dotArmC.set(0)
    else if dot1HeldC.get() && dot2HeldC.get() then
      dotArmC.set(dotArmC.get() + 1)
      if dotArmC.get() >= DOT_HOLD_FRAMES then
        tally(dotFiredC)
        dot1HeldC.set(false)
        dot2HeldC.set(false)
        dotArmC.set(0)
        println("recovery: dot-dot hold — panel powerdown cycle, then restart")
        if Diag.onDevice() then Led.powerCycleFb()
        fired = true
    fired

  /** age the exit menu's arming out. An armed menu left alone must not sit one
   *  keypress away from EDL until the screensaver takes the panel. */
  def tickExitMenu(dt: scala.Double): Unit =
    exitMenu match
      case s: Some[ExitMenuState] => exitC.set(Some(ExitMenu.update(s.value, dt)))
      case None                   => ()

  /** age the quit confirmation out (0 = unarmed). */
  def tickQuitArm(dt: scala.Double): Unit =
    val cur = quitArmC.get()
    if cur > 0.0 then
      val left = cur - dt
      // if-expression at a primitive argument type: the standing proof of
      // the IF-EXPR-DOUBLE-BOXES fix (pin 4834bec).
      quitArmC.set(if left < 0.0 then 0.0 else left)

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
      dev.unblank()
      dev.backlight(SettingsLogic.getBrightness(Shell.devState(stateV)))
      dev.buttonBacklight(true)
      offC.set(false)
    idleC.set(0.0)

  /** accumulate idle time; blank the panel past the settings timeout (0=never). */
  def tickIdle(dt: scala.Double, evs: List[KeyEvent], dev: UiDevice): scala.Double =
    if hasEvent(evs) then 0.0
    else
      val t = idleTime + dt
      if !displayOff then
        val timeout = SettingsLogic.getScreenTimeout(Shell.devState(stateV))
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
    case _: EvSendComplete =>
      tally(sendOkC)
      stateC.set(Shell.notifyWataSend(stateV, false))
    case _: EvSendFailed   =>
      tally(sendFailC)
      stateC.set(Shell.notifyWataSend(stateV, true))
    case pe: EvPlaybackError =>
      tally(playFailC)
      stateC.set(Shell.notifyWataPlayError(stateV, pe.fetchFailed))
    case ob: EvOutbox      =>
      unsentC.set(ob.unsent)
      undelivC.set(ob.undelivered)
    case _: EvSnapshot     => () // snapshot is picked up via pollSnap

  /** bump a tally cell, discarding `add`'s returned new value (a bare value
   *  statement is not legal Go; a call is, so the discard rides a def). */
  def tally(cell: sgo.Atomic[scala.Int]): Unit =
    val n = cell.add(1)
    ()

  /** connection change -> the status cell (the per-frame LED arbiter reads it
   *  from there), and — the first time a session actually comes up — write the
   *  credentials to the config store so the next boot resumes without
   *  arguments. */
  def onConn(c: MatrixClient, cs: ConnectionState, dev: UiDevice): Unit =
    connC.set(cs)
    if Runtime.connTag(cs) == 4 then tally(connErrC)
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
    case _: ConnError        => true
    case _: ConnAuthRejected => true
    case _: Disconnected     => true
    case _                   => false

  // ---- arrival notifications (plan 0041) --------------------------------------

  /** how long the quiet banner stays up (frame-clock ms). */
  val BANNER_MS: Long = 4000L
  /** half a blink period: green toggles every 500ms while messages wait. */
  val BLINK_HALF_MS: Long = 500L

  /** The arrival edge, once a frame, off the snapshot the frame already read:
   *  `Notify.step` (wataclient, shared with the mac) answers which
   *  conversations' unplayed counts ROSE and who to name. There is
   *  deliberately no second channel through the sync engine — one number
   *  drives the LED, the banner, the row highlight and the contact list's own
   *  badge, so they cannot disagree. */
  def notifyFrame(c: MatrixClient, snap: StateSnapshot, nowMs: Long): Unit =
    val r = Notify.step(notifyC.get(), snap)
    notifyC.set(r.marks)
    val unplayed = Notify.totalUnplayed(snap)
    var cur = r.arrivals
    var going = true
    while going do
      cur match
        case a: ::[Arrival] =>
          announce(c, a.head, snap, unplayed, nowMs)
          cur = a.tail
        case Nil => going = false

  /** What one arrival does, and the ONE decision line it prints:
   *
   *      notify: play|chime|quiet|suppressed "<title>" "<body>" unplayed=<n>
   *
   *  `play` (auto-play) is the future focus-modes seam — unreachable from the
   *  device UI since plan 0047, kept as the same `ActPlay` the applet's OK
   *  press sends plus `withPlaying`, so the existing `AePlaybackDone` arm
   *  sends the read receipt and an auto-played message really becomes played.
   *  An arrival that loses the `canAutoPlay` gate falls through to the quiet
   *  channels rather than queueing (the audio thread does one thing at a
   *  time, and the count is still up). `chime` is the device default: the
   *  startup chirp through the audio thread's mailbox (serialized, so it
   *  never cuts into a recording or playback; the analog volume knob is the
   *  mute switch), plus the same banner the quiet arm sets — the LED blink
   *  rides the unplayed count either way. `suppressed` = the person is
   *  already looking at that conversation on a lit screen — told already. */
  def announce(c: MatrixClient, a: Arrival, snap: StateSnapshot,
               unplayed: scala.Int, nowMs: Long): Unit =
    val mode = FbConfig.notifyMode()
    if Notify.playsNow(mode) && canAutoPlay(Shell.wataState(stateV)) then
      Runtime.sendAction(c, ActPlay(a.mxcUrl))
      stateC.set(Shell.notifyWataPlaying(stateV, a.roomId, a.eventId))
      println(notifyLine("play", a, unplayed))
    else if viewingConv(snap, a.roomId) then println(notifyLine("suppressed", a, unplayed))
    else
      bannerC.set(Some(NotifyBanner(Notify.title(a), Notify.body(a), a.roomId,
        nowMs + BANNER_MS)))
      if Notify.chimes(mode) then
        val sent = c.audioCmds.trySend(AcChime())
        println(notifyLine("chime", a, unplayed))
      else println(notifyLine("quiet", a, unplayed))

  /** the mac's gate, restated: an auto-play waits rather than cutting into a
   *  playback or a recording in progress. */
  def canAutoPlay(w: WataState): Boolean = !w.playing && !w.pttHeld

  /** is the announced conversation the one on a lit screen right now? The
   *  exit menu is modal over everything, so an open menu means no. */
  def viewingConv(snap: StateSnapshot, roomId: String): Boolean =
    var out = false
    if !displayOff && !exitMenuOpen && stateV.active == Shell.WATA then
      val w = Shell.wataState(stateV)
      if isConversation(w.view) && WataLogic.roomIdAt(snap, w.convContactIdx) == roomId then
        out = true
    out

  def isConversation(v: WataView): Boolean = v match
    case _: VConversation => true
    case _                => false

  def notifyLine(what: String, a: Arrival, unplayed: scala.Int): String =
    "notify: " + what + " \"" + Notify.title(a) + "\" \"" + Notify.body(a) +
      "\" unplayed=" + unplayed

  // ---- the LED arbiter --------------------------------------------------------

  /** ONE pure function decides the two LEDs each frame (`onConn` used to write
   *  them directly, which left no room for a second meaning on the green).
   *  Red steady = connection bad, exactly as before. Green carries two
   *  meanings ordered by urgency: BLINKING (~1 Hz off the frame clock —
   *  led.scala has no blink primitive) = unplayed messages waiting, which is
   *  the screen-off channel; steady = connected and idle. */
  def ledArbiter(cs: ConnectionState, unplayed: scala.Int, nowMs: Long): LedState =
    LedState(greenOf(ledGreen(cs, unplayed), nowMs), isBad(cs))

  /** the green LED's policy: 0 = off, 1 = steady (live, nothing waiting),
   *  2 = blinking (unplayed messages) — what the `notifyled` probe reads. */
  def ledGreen(cs: ConnectionState, unplayed: scala.Int): scala.Int =
    var out = 0
    if unplayed > 0 then out = 2
    else if isLive(cs) then out = 1
    out

  def greenOf(mode: scala.Int, nowMs: Long): Boolean =
    if mode == 2 then blinkOn(nowMs) else mode == 1

  def blinkOn(nowMs: Long): Boolean = (nowMs / BLINK_HALF_MS) % 2L == 0L

  /** write the pair through the device seam, only on change — a blink is two
   *  sysfs writes a second, not sixty. */
  def applyLeds(dev: UiDevice, l: LedState): Unit =
    if ledChanged(lastLedC.get(), l) then
      lastLedC.set(Some(l))
      dev.leds(l.green, l.red)

  def ledChanged(last: Option[LedState], l: LedState): Boolean = last match
    case s: Some[LedState] => s.value.green != l.green || s.value.red != l.red
    case None              => true

  // ---- the banner -------------------------------------------------------------

  /** is the arrival banner up (what the `notifybanner` probe reads)? */
  def bannerOn: Boolean = bannerC.get() match
    case _: Some[NotifyBanner] => true
    case None                  => false

  /** the green-LED policy THIS frame's arbiter would compute — the scripted
   *  driver's probe surface. */
  def ledGreenNow: scala.Int = ledGreen(connV, Notify.totalUnplayed(snapC.get()))
  def ledRedNow: Boolean = isBad(connV)

  def tickBanner(nowMs: Long): Unit =
    bannerC.get() match
      case b: Some[NotifyBanner] => if nowMs >= b.value.untilMs then bannerC.set(None)
      case None                  => ()

  /** paint the banner over the frame — only while the screen is on (the
   *  caller's `!displayOff` gate; an arrival never wakes the screen, the LED
   *  is the screen-off channel) and never over the modal exit menu. */
  def drawBanner(px: go.Bytes): Unit =
    bannerC.get() match
      case b: Some[NotifyBanner] =>
        if !exitMenuOpen then FbPaint.draw(px, bannerView(b.value))
      case None => ()

  /** two rows at the top of the panel: sender, then what landed where — the
   *  shared `Notify.title`/`Notify.body` strings, so both clients say the
   *  same thing. */
  def bannerView(b: NotifyBanner): View =
    VGroup(
      Keyed("bg", VRect(0, 0, Display.W, 2 * Font.GLYPH_H + 2, Color.darkGray)) ::
        (Keyed("rule", VRect(0, 2 * Font.GLYPH_H + 1, Display.W, 1, Color.yellow)) ::
          (Keyed("title", VText(0, 0, WataLogic.clip(b.title, Font.COLS), Color.yellow)) ::
            (Keyed("body", VText(0, 1, WataLogic.clip(b.body, Font.COLS), Color.white)) :: Nil))))
