import language.experimental.saferExceptions

/** wata-ios: the iOS client (plan 0044) — wata-fb's screens on the retained
 *  UIKit backend, wata-mac's pump with the mac-only seams swapped
 *  (macshell -> iosshell, real audio via the shared macaudio backend —
 *  plan 0063, key input as the shell's touch keypad).
 *
 *    WATA_IOS_HS / WATA_IOS_USER / WATA_IOS_PASS (simctl launch passes them
 *    as SIMCTL_CHILD_*): login is from the environment, with the stores
 *    remembering across launches (config.scala). There is no login sheet
 *    yet — that surface is ADULT-UX-NONHAPPY's iOS half.
 *
 *  THE SHELL'S INVERSION (go-pkgs/iosshell): UIKit builds the UI inside its
 *  own launch callback and UIApplicationMain never returns, so main goes
 *  `start()` → `runApp(ready)`; `ready` (a `go.callback` trampoline, main
 *  thread) creates the stage, adopts its root, lays the keypad, paints the
 *  BOOT SCREEN — the session's connect wait happens behind a painted frame,
 *  not a blank window — and forks the session goroutine.
 *
 *  THE PUMP is wata-mac's frame shape: drain the runtime's `UiEvent` queue,
 *  read the snapshot/connection, drain the keypad into
 *  `WataLogic.handleInput`, drain the audio thread's events, tick,
 *  body, diff, and hand the script to the retained interpreter (`IosStage`)
 *  — which publishes to the main queue (there is no headless mode). Dropped
 *  mac surfaces: the window title, the Dock badge, banners, the Devices
 *  window, the login sheet, the leak-bisect arms.
 *
 *  THE ASSERTABLE LINES (tools/ios-smoke.py's surface — launchd owns the
 *  process exit, so printed lines are the verdict, the interptest rule):
 *    ready <userId> / login failed        — the connect wait's outcome
 *    screen boot|contacts|conversation    — the top-level screen changed
 *    paint <screen> lit=<n>               — an offscreen render of the live
 *                                           stage found <n> non-black pixels
 *                                           (the proof UIKit really painted)
 *    notify: play|noted "…" "…" badge=<n> — the arrival decision (noted =
 *                                           no banner surface on iOS yet)
 *    push: …                              — APNs (plan 0065 tier 2): the
 *                                           device token, the registration's
 *                                           outcome, and every notification
 *                                           iOS handed the app
 *    ptt: …                               — PushToTalk (plan 0065 tier 3):
 *                                           every channel-manager delegate
 *                                           callback, the ephemeral token's
 *                                           registration, the join's refusals,
 *                                           and the receive episode a push
 *                                           woke the app for (`play room=…
 *                                           event=…`, `playing <event>`,
 *                                           `speaker done (<why>)`)
 *
 *  Argv modes: `interptest` runs the retained interpreter's suite
 *  (interptest.scala) instead of the client. */
object Main:

  /** the trampolines, minted ONCE at module init (the registration
   *  contract) — the shell invokes them on the main thread. */
  val interptestCb: go.Uintptr = go.callback(() => Main.runInterptest())
  val readyCb: go.Uintptr = go.callback(() => Pump.onReady())

  def main(args: Array[String]): Unit =
    initLog()
    if args.length > 0 && args(0) == "interptest" then
      go.iosshell.start()
      go.iosshell.runApp(interptestCb) // never returns
    else runClient(args)

  /** plan 0064: tee stdout+stderr into the sandbox log FIRST, so an
   *  icon-tap launch's lines survive to be pulled by `just ios-log`; the
   *  console copy keeps tethered launches and the harnesses unchanged. */
  def initLog(): Unit =
    val p = FbConfig.logPath()
    if p != "" then
      val err = go.iosshell.teeLog(p)
      if err != "" then println("log: tee failed: " + err)

  def runClient(args: Array[String]): Unit =
    val hs = pick(args, 0, "WATA_IOS_HS", "")
    val user = pick(args, 1, "WATA_IOS_USER", "")
    val passIn = pick(args, 2, "WATA_IOS_PASS", "")
    var cfg = FbConfig.resolve(hs, user, passIn)
    // prime the walkie-talkie toggle's cell (every config write re-emits it)
    val primed = FbConfig.loadNotifyMode()
    // the password that got us in is worth keeping only if it is the one
    // just supplied; a stored one is already where it belongs.
    if passIn != "" then FbConfig.savePassword(cfg.homeserver, cfg.username, passIn)
    Pump.setConfig(cfg)
    go.iosshell.start()
    go.iosshell.runApp(readyCb) // never returns

  def runInterptest(): Unit =
    // (The stage-3 PRUNE-DANGLING-MODULE-INIT workaround — a no-op
    // IosStage.submit(Nil) here — came out when the pump became `submit`'s
    // real caller; the upstream ticket about pruning a def a module-init
    // callback literal still calls remains open in sgola's queue.)
    val failures = InterpTest.run()
    // The verdict travels by the printed line: launchd owns the process's
    // exit, so the harness greps `interptest: PASS`/`FAIL` and terminates
    // the app itself. Nothing to do here but stay in the runloop.
    ()

  /** enough to attempt a session: a name, and either a password or a stored
   *  token to try first. */
  def hasCredentials(c: ClientConfig): Boolean =
    c.username != "" && (c.password != "" || c.stored.accessToken != "")

  /** positional argument `i` wins, else the env var, else the default. */
  def pick(args: Array[String], i: scala.Int, env: String, dflt: String): String =
    var out = go.sys.getenv(env)
    if out == "" then out = dflt
    if args.length > i && args(i) != "" then out = args(i)
    out

/** one pump step's carried state — wata-mac's PumpSt verbatim (the wata
 *  applet state, the last tree, the two-step quit's arm window, the frame
 *  clock, the quit edge, the outbox marks, and the notify marks). */
case class PumpSt(
  wata: WataState,
  last: Option[View],
  quitArm: scala.Double,
  lastMs: Long,
  quit: Boolean,
  unsent: List[String],
  undelivered: List[String],
  marks: NotifyState
)

object Pump:

  val FRAME_MS: Long = 33L
  val QUIT_ARM_S: scala.Double = 2.0

  /** the config the ready trampoline picks up (the callback literal captures
   *  nothing — module cells are its reach, IosStage's applyCb idiom). */
  private val cfgC: sgo.Atomic[Option[ClientConfig]] = sgo.atomic(None)
  /** the stage's root and scale, for the paint probe. */
  private val rootC: sgo.Atomic[Option[go.uikit.UIView]] = sgo.atomic(None)
  private val scaleC: sgo.Atomic[Int] = sgo.atomic(1)
  /** which screen the pending paint probe reports on. */
  private val probeScreenC: sgo.Atomic[String] = sgo.atomic("")
  /** the last screen name printed (one `screen` line per change). */
  private val screenC: sgo.Atomic[String] = sgo.atomic("")

  /** the paint-probe trampoline: renders the live stage on the main queue
   *  AFTER the published frames applied (one serial queue, publish order). */
  val probeCb: go.Uintptr = go.callback(() => Pump.runProbe())

  def setConfig(cfg: ClientConfig): Unit = cfgC.set(Some(cfg))

  def initial(clock: Clock): PumpSt =
    PumpSt(WataLogic.initial(), None, 0.0, clock.nowUnixMillis(), false, Nil, Nil,
      Notify.initial())

  def scale(): scala.Int =
    var out = IosStr.num(go.sys.getenv("WATA_IOS_SCALE"), 2)
    if out < 1 then out = 1
    out

  def connectMs(): Long = IosStr.num(go.sys.getenv("WATA_IOS_CONNECT_MS"), 30000).toLong

  // ---- the ready hop (main thread, inside UIApplicationMain) ----------------

  def onReady(): Unit =
    val sc = scale()
    scaleC.set(sc)
    val pool = go.iosui.poolPush()
    val root = IosStage.create(sc, true)
    go.iosui.poolPop(pool)
    go.iosshell.adoptRoot(root)
    go.iosshell.addKeypad()
    // APNs (plan 0065 tier 2): permission, the notification-centre delegate
    // and registerForRemoteNotifications. Here rather than in the shell's
    // launch callback so `interptest` never raises a permission prompt.
    go.iosshell.registerForPush()
    // PushToTalk (plan 0065 tier 3): the framework requires a channel manager
    // at LAUNCH whether or not this app will ever join, so it is created here
    // and not at the first join. Inert wherever the framework is missing.
    go.iosshell.pttStart()
    rootC.set(Some(root))
    // Paint the boot screen NOW, inline (this is the UIKit thread): the
    // session's connect wait runs behind a painted "starting up..." frame.
    // The pump's first frame then root-PSets over it.
    val pool2 = go.iosui.poolPush()
    IosStage.setTree(bootTree())
    go.iosui.poolPop(pool2)
    noteScreen("boot")
    sgo.spawn(() => Pump.session())

  /** the pre-session boot screen: the same body the pump would build before
   *  `everLive`, from an initial state and an empty context. */
  def bootTree(): View =
    WataLogic.bodyBoot(NetStatus.poll(Disconnected()), Disconnected(),
      false, false, false, NetStatus.clockOk())

  // ---- the session ----------------------------------------------------------

  /** the client both stages of the session run: `makeWithAudioStored`, so a
   *  recording queued while the server was unreachable survives the app
   *  closing. */
  def startAudioClient(cfg: ClientConfig): Handle =
    ClientHandle.startClient(
      Runtime.makeWithAudioStored(cfg, IosCaps.httpDo(), IosCaps.clock(), FbConfig.outbox()))

  /** the token this session actually got, kept for the next launch — written
   *  once per session and only when it is real. */
  private val savedC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def persistSession(c: MatrixClient): Unit =
    if !savedC.get() then
      val creds = Runtime.lastAuth
      if creds.accessToken != "" then
        savedC.set(true)
        FbConfig.saveLogin(c.cfg.homeserver, c.cfg.username, creds)

  /** set when a `wata://configure` link lands (setup wait or mid-session):
   *  the current pump ends and the session restarts onto the new transport. */
  private val restartC: sgo.Atomic[Boolean] = sgo.atomic(false)

  def session(): Unit =
    var looping = true
    while looping do
      restartC.set(false)
      looping = sessionOnce()

  /** one session arc; true = run another (a configure link landed). Three
   *  ways in: password/stored credentials (the harness path), an iroh
   *  config (device-login — the enrolled phone, plan 0062), or neither —
   *  the fresh install, which waits in `setupWait` for the admin page's
   *  configure link. */
  def sessionOnce(): Boolean = cfgC.get() match
    case c: Some[ClientConfig] =>
      val cfg = c.value
      if !Main.hasCredentials(cfg) && !Enrol.configured() then setupWait()
      else
        val h = startAudioClient(cfg)
        pump(h)
        h.stop()
        val joined = ClientHandle.join(h, 5000L)
        if restartC.get() then
          println("session restarting (configured)")
          true
        else
          println("session ended")
          false
    case None =>
      println("wata-ios: no config")
      false

  /** the fresh-install arc (plan 0062): paint the setup screen, bounce to
   *  the family admin page once (its "Add this phone" link is the way
   *  back), and wait for `wata://configure`. Always answers true — the only
   *  way out is being configured. */
  def setupWait(): Boolean =
    noteScreen("setup")
    Enrol.openSetupOnce()
    var old = bootTree()
    var going = true
    while going do
      val u = go.iosshell.takeURL()
      if u != "" && Enrol.handleConfigure(u) then going = false
      else
        val v = setupTree()
        patchTo(old, v)
        old = v
        IosCaps.clock().sleepMs(200L)
    true

  def setupTree(): View =
    val l1 = "set up wata"
    val l2 = "tap Add this phone on the"
    val l3 = "family admin page (Safari)"
    VGroup(
      Keyed("title", VText(0, 0, "WATA", Color.cyan)) ::
        Keyed("head", VText(FbPaint.centerCol(l1), 4, l1, Color.white)) ::
        Keyed("sub1", VText(FbPaint.centerCol(l2), 6, l2, Color.midGray)) ::
        Keyed("sub2", VText(FbPaint.centerCol(l3), 7, l3, Color.midGray)) :: Nil)

  /** one non-blocking look at the URL queue, from the pump loop: a configure
   *  link mid-session (re-pairing to a new server) restarts the session. */
  def pollUrl(): Unit =
    val u = go.iosshell.takeURL()
    if u != "" && Enrol.handleConfigure(u) then restartC.set(true)

  /** `Runtime.waitForConnection(Syncing)`, except a `ConnAuthRejected` ends
   *  the wait at once (wata-mac's shape). */
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

  def pump(h: Handle): Unit =
    val clock = IosCaps.clock()
    NetStatus.reset()
    PushReg.newSession()
    PttChan.newSession()
    val live = waitLiveOrRejected(h, connectMs())
    if live then println("ready " + Runtime.lastAuth.userId)
    else println("login failed") // stored creds keep pumping on the boot screen (plan 0022's never-terminate)
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      val audioCmds = h.client.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      var st = initial(clock)
      // the session can go live AFTER the connect window — an enrol approve
      // landing mid-pump does exactly that — so the ready line also arms here.
      var readyLate = live
      // A rejected session ends the pump; there is no sheet to bounce to
      // yet, so the printed line is the surface (ADULT-UX-NONHAPPY's iOS
      // half owns the ask-again arc). The two-step quit edge only ever
      // re-arms: an iOS app does not terminate itself.
      while !st.quit && !isRejected(h.connection()) && !restartC.get() do
        pollUrl()
        val took = h.waitEvent(FRAME_MS)
        st = frame(h, clock, evts, st)
        if !readyLate && NetStatus.everLive() then
          readyLate = true
          println("ready " + Runtime.lastAuth.userId)
      audioCmds.send(AcQuit())
    }
    if isRejected(h.connection()) then println("rejected")

  def isRejected(c: ConnectionState): Boolean = c match
    case _: ConnAuthRejected => true
    case _                   => false

  // ---- one frame ------------------------------------------------------------

  /** one frame: UI events -> flash/outbox, keys -> input, audio events, tick,
   *  body, diff, publish — wata-mac's frame minus the mac chrome. */
  def frame(h: Handle, clock: Clock, evts: sgo.Chan[AudioEvt], st0: PumpSt): PumpSt =
    val nowMs = clock.nowUnixMillis()
    val dt = clampDt(nowMs - st0.lastMs).toDouble / 1000.0
    var st = PumpSt(st0.wata, st0.last, st0.quitArm, nowMs, st0.quit, st0.unsent, st0.undelivered, st0.marks)
    st = drainUiEvents(h, st)
    val snap = h.snapshot()
    val conn = h.connection()
    val net = NetStatus.poll(conn)
    NetStatus.logTransition(net, conn, NetStatus.clockOk())
    NetStatus.logSnapshot(snap, conn)
    persistSession(h.client)
    if NetStatus.takePipeArrival() then Runtime.retryNow(h.client)
    val ctx = FrameCtx(snap, conn, net, h.client, h.client.audioCmds, evts,
      st.unsent, st.undelivered, st.quitArm > 0.0)
    st = applyKeys(st, ctx)
    st = drainAudio(st, ctx)
    st = pushStep(h, st, ctx, nowMs)
    st = PttChan.step(h, st, ctx, nowMs)
    st = notifyStep(h, st, snap)
    st = PumpSt(WataLogic.update(st.wata, dt, ctx), st.last, tickArm(st.quitArm, dt),
      st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)
    val v = WataLogic.body(st.wata, snap, net, conn, st.quitArm > 0.0,
      st.unsent, st.undelivered,
      NetStatus.everLive(), FbCaps.transportUnavailable(),
      WataLogic.enrolSnap(ctx, NetStatus.everLive()), Enrol.provisioning(),
      NetStatus.clockOk(), nowMs)
    st.last match
      case old: Some[View] => patchTo(old.value, v)
      case None            => IosStage.submitTree(v)
    noteScreen(screenName(st.wata))
    PumpSt(st.wata, Some(v), st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)

  def patchTo(old: View, v: View): Unit =
    val ps = Diff.diff(old, v)
    if lenPatches(ps) > 0 then IosStage.submitScript(ps)

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

  def clampDt(raw: Long): Long =
    if raw < 0L then 0L else if raw > 1000L then 1000L else raw

  /** the quit confirmation ages out (0 = unarmed). */
  def tickArm(arm: scala.Double, dt: scala.Double): scala.Double =
    var out = arm
    if arm > 0.0 then
      out = arm - dt
      if out < 0.0 then out = 0.0
    out

  // ---- the screen line + the paint probe ------------------------------------

  def screenName(s: WataState): String =
    if !NetStatus.everLive() then "boot"
    else s.view match
      case _: VContacts     => "contacts"
      case _: VConversation => "conversation"

  /** print `screen <name>` on change and queue a paint probe for it — the
   *  probe runs on the main queue AFTER the frame that changed it applied. */
  def noteScreen(name: String): Unit =
    if screenC.getAndSet(name) != name then
      println("screen " + name)
      probeScreenC.set(name)
      go.iosui.onMain(probeCb)

  /** render the live stage offscreen and count non-black pixels over a
   *  coarse grid (stopping early — each probe is a full layer render). Every
   *  screen draws SOMETHING light on the black stage, so lit=0 means UIKit
   *  painted nothing where a tree was submitted. */
  def runProbe(): Unit = rootC.get() match
    case r: Some[go.uikit.UIView] =>
      val sc = scaleC.get()
      var lit = 0
      var probes = 0
      var y = 4
      while y < 128 && lit < 8 do
        var x = 2
        while x < 160 && lit < 8 do
          val c = go.iosui.renderPixel(r.value, x * sc + sc / 2, y * sc + sc / 2)
          probes += 1
          if c > 0 then lit += 1
          x += 12
        y += 8
      println("paint " + probeScreenC.get() + " lit=" + lit + "/" + probes)
    case None => ()

  // ---- the two mailbox drains ------------------------------------------------

  /** the runtime's `UiEvent` queue, ONCE per frame (plan 0009's one-drain
   *  rule). */
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

  /** the audio thread's `AudioEvt` queue, ONCE per frame. The echo events
   *  are filtered as on the mac: the settings applet's echo UI is the
   *  handset's surface, not this client's. */
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

  def isEchoEvt(e: AudioEvt): Boolean = e match
    case _: AeEchoRecording => true
    case _: AeEchoPlaying   => true
    case _: AeEchoDone      => true
    case _: AeEchoError     => true
    case _                  => false

  // ---- APNs (plan 0065 tier 2) -----------------------------------------------

  /** the push mailbox, ONCE a frame (the one-drain rule): the registration's
   *  own progress, then every notification iOS handed the app since the last
   *  frame. The printed `push:` lines are this client's whole push surface —
   *  the BANNER is the system's, drawn from the payload the server sent
   *  (`interruption-level: time-sensitive`), and a foreground one is
   *  presented by the shell's delegate answering banner|list|sound|badge.
   *
   *  A TAP opens its conversation, which is the same edge ENTER makes on the
   *  contact list: select the row, ack its outbox, enter. Nothing about the
   *  applet's state machine is special-cased for a push. */
  def pushStep(h: Handle, st0: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    var note = go.iosshell.takePushNote()
    while note != "" do
      println("push: " + note)
      note = go.iosshell.takePushNote()
    PushReg.step(h.client, nowMs)
    var st = st0
    var going = true
    while going do
      val rec = go.iosshell.takePush()
      if rec == "" then going = false
      else
        println("push: " + rec)
        if go.iosshell.pushTapped() then
          st = openRoom(st, ctx, go.iosshell.pushRoom())
    st

  /** open the conversation a tapped notification names, if this snapshot has
   *  it — a room the client has not synced yet is simply left alone; the
   *  arrival itself will bring the user there. */
  def openRoom(st: PumpSt, ctx: FrameCtx, roomId: String): PumpSt =
    val idx = indexOfRoom(ctx.snap.conversations, roomId, 0)
    if idx < 0 then st
    else
      WataLogic.ackOutbox(ctx, idx)
      withWata(st, WataLogic.enterConv(WataLogic.withSel(st.wata, idx, 0), idx))

  def indexOfRoom(xs: List[Conversation], roomId: String, i: scala.Int): scala.Int = xs match
    case h :: t => indexOfRoomStep(h, t, roomId, i)
    case Nil    => -1

  def indexOfRoomStep(h: Conversation, t: List[Conversation], roomId: String,
                      i: scala.Int): scala.Int =
    if roomId != "" && h.roomId == roomId then i
    else indexOfRoom(t, roomId, i + 1)

  // ---- arrival notification --------------------------------------------------

  /** the arrival edge, once a frame (wataclient's `Notify`, shared with the
   *  handset and the mac). iOS has no banner/badge surface yet, so the
   *  DECISION line is the whole presentation: `play` when walkie-talkie mode
   *  auto-plays, `noted`
   *  otherwise. Real notifications ride the signed-device legs (APNs is
   *  hardware-gated). */
  def notifyStep(h: Handle, st0: PumpSt, snap: StateSnapshot): PumpSt =
    val r = Notify.step(st0.marks, snap)
    val badge = Notify.totalUnplayed(snap)
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

  def announce(h: Handle, st: PumpSt, a: Arrival, badge: scala.Int): PumpSt =
    var out = st
    if Notify.playsNow(FbConfig.notifyMode()) && canAutoPlay(st.wata) then
      Runtime.sendAction(h.client, ActPlay(a.mxcUrl))
      out = withWata(st, WataLogic.withPlaying(st.wata, true, a.roomId, a.eventId))
      println(notifyLine("play", a, badge))
    else println(notifyLine("noted", a, badge))
    out

  def canAutoPlay(s: WataState): Boolean = !s.playing && !s.pttHeld

  def notifyLine(what: String, a: Arrival, badge: scala.Int): String =
    "notify: " + what + " \"" + Notify.title(a) + "\" \"" + Notify.body(a) +
      "\" badge=" + badge

  def withWata(st: PumpSt, w: WataState): PumpSt =
    PumpSt(w, st.last, st.quitArm, st.lastMs, st.quit, st.unsent, st.undelivered, st.marks)

  // ---- input ----------------------------------------------------------------

  /** drain the keypad into the applet — and run the two-step quit edge as
   *  the device loop does, though on iOS the confirmed edge only ends the
   *  session loop (an app does not self-terminate). The queue carries
   *  IosKeys codes: the buttons are the model, no translation table.
   *
   *  ONE key is not applied directly: while a PushToTalk channel is joined
   *  the PTT button REQUESTS a transmission instead (ptt.scala), and the
   *  recording starts when the framework says the transmission is live and
   *  the audio session is activated. Same path as the system talk button,
   *  and the system UI cannot fall out of step with what is recording. */
  def applyKeys(st0: PumpSt, ctx: FrameCtx): PumpSt =
    var st = st0
    var going = true
    while going do
      val packed = go.iosshell.nextKey()
      if packed < 0 then going = false
      else
        val k = packed / 4
        if k != IosKeys.NONE && k <= IosKeys.PTT then
          val ks = stateOf(packed % 4)
          if k == IosKeys.PTT && PttChan.routing() then requestTransmit(ks)
          else st = applyKey(st, KeyEvent(keyOf(k), ks), ctx)
    st

  /** the on-screen PTT button while a channel is joined: press asks the
   *  framework to transmit, release asks it to stop. A repeat is neither. */
  def requestTransmit(ks: KeyState): Unit = ks match
    case Pressed()  => go.iosshell.pttTransmit(true)
    case Released() => go.iosshell.pttTransmit(false)
    case _          => ()

  def keyOf(code: scala.Int): Key =
    if code == IosKeys.UP then KUp()
    else if code == IosKeys.DOWN then KDown()
    else if code == IosKeys.LEFT then KLeft()
    else if code == IosKeys.RIGHT then KRight()
    else if code == IosKeys.ENTER then KEnter()
    else if code == IosKeys.BACK then KBack()
    else if code == IosKeys.PTT then KPtt()
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
