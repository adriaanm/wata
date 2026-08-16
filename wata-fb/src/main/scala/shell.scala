import language.experimental.saferExceptions

/** The SHELL + main loop (the UI-thread half). Device-layer app code.
 *
 *  UI state is an IMMUTABLE record (`ShellState` + per-applet state records)
 *  threaded through pure transition functions, rather than stateful
 *  applet objects with mutable fields — matching the immutable-record style
 *  used elsewhere in this codebase (e.g. the sync engine). The live cell is
 *  ONE module var (`stateV`) touched by ONE goroutine (the UI/main loop).
 *
 *  APPLET SET: `wata` (index 0) + `settings` (index 1, the kid panel) +
 *  `snake` (index 2), plus `dev` (index 3) OUTSIDE the dot rotation — the
 *  developer settings panel, reachable only through the kid panel's hidden
 *  development row (plan 0053). dot1/dot2 rotate over the first three; PTT
 *  is the talk key everywhere — it records inside the wata applet and
 *  chimes-and-switches to it from any other (plan 0052, `pttGlobal`); red
 *  pressed in the snake applet returns to wata, and in the dev applet back
 *  to the kid settings (the red-goes-back convention — neither applet sees
 *  the key).
 *
 *  dot2 CAVEAT: input handling opens ONLY /dev/input/event{0,1,2} — the SAME
 *  three devices Evdev.open() opens. It binds dot2 (KEY_F10) but discovers no
 *  other bus, so if dot2's hardware lives on a fourth device node, it will
 *  never be seen. This gap is shared with the original device firmware this
 *  client was ported from — the reference implementation has the same
 *  three-device limit. Applet switching works via dot1 (KEY_F3), which is
 *  wired correctly. See WATA-TODO.md. */

// ---- status line state -------------------------------------------------------
sealed trait Status derives CanEqual
case class StIdle() extends Status
case class StConnected() extends Status
case class StSyncing() extends Status
case class StRecording() extends Status
case class StErr() extends Status
case class StDisconnected() extends Status

object ShellStatus:
  /** status -> RGB565 color. */
  def color(s: Status): scala.Int = s match
    case _: StIdle         => Color.midGray
    case _: StConnected    => Color.green
    case _: StSyncing      => Color.cyan
    case _: StRecording    => Color.yellow
    case _: StErr          => Color.red
    case _: StDisconnected => Color.red

  /** the 1px line's status, from the SAME computed connectivity the wata
   *  applet's header element draws (netstatus.scala): a device with no
   *  interface holding an address is down whatever the sync loop last
   *  managed to say, and otherwise the connection's own state carries the
   *  colors it always had. Deriving both from one `NetState` is what keeps
   *  the line and the header from making disagreeing claims. */
  def fromNet(n: NetState, c: ConnectionState): Status =
    if NetStatus.isNoPipe(n.pipe) then StDisconnected() else fromConnection(c)

  /** connection -> status. */
  def fromConnection(c: ConnectionState): Status = c match
    case _: Disconnected => StDisconnected()
    case _: Connecting   => StIdle()
    case _: Connected    => StConnected()
    case _: Syncing      => StSyncing()
    case _: ConnError    => StErr()
    case _: ConnAuthRejected => StErr()

// ---- the immutable shell state (the UI half) ---------------------------------
/** `active` indexes the APPLET LIST — each element an immutable `Applet`
 *  holding its own typed state behind the interface (0 = wata, 1 = settings;
 *  adding an applet = one append in `initial()`). `status` mirrors the
 *  connection. Derives `Shareable`: the record rides the UI goroutine's
 *  `Atomic[ShellState]` cell, so the aggregate must be shareable — every field
 *  is pure or an `IArray` of `Shareable` applets. */
case class ShellState(active: scala.Int, status: Status, applets: IArray[Applet]) extends Shareable

object Shell:
  val WATA = 0
  val SETTINGS = 1
  val SNAKE = 2
  val DEV = 3
  /** how many applets the dot rotation covers — DEV sits past it, reachable
   *  only through the kid panel's development row (plan 0053). */
  val ROTATION = 3

  /** the boot state. BOTH settings applets start from the STORED preferences,
   *  so brightness and screen timeout survive a restart; everything else
   *  starts from its own defaults. */
  def initial(prefs: FbPrefs): ShellState =
    ShellState(WATA, StIdle(),
      IArray[Applet](WataApplet(WataLogic.initial()),
        KidSettingsApplet(KidSettingsLogic.restored(prefs)),
        SnakeApplet(SnakeLogic.initial()),
        SettingsApplet(SettingsLogic.restored(prefs))))

  // ---- record withers (no `.copy` on sgola — see WataLogic) -------------------
  def withActive(s: ShellState, a: scala.Int): ShellState =
    ShellState(a, s.status, s.applets)
  def withStatus(s: ShellState, st: Status): ShellState =
    ShellState(s.active, st, s.applets)
  def withApplet(s: ShellState, idx: scala.Int, a: Applet): ShellState =
    ShellState(s.active, s.status, replaceAt(s.applets, idx, a))
  def replaceAt(xs: IArray[Applet], idx: scala.Int, a: Applet): IArray[Applet] =
    IArray.tabulate(xs.length)(i => pick(xs, i, idx, a))
  def pick(xs: IArray[Applet], i: scala.Int, idx: scala.Int, a: Applet): Applet =
    if i == idx then a else xs(i)

  // ---- the wata-specific seams (PTT + quit/notify are special cases OUTSIDE
  // the uniform dispatch — they target the wata applet by construction) -------
  /** the wata applet's typed state (slot WATA is the wata applet by
   *  construction; the default arm is unreachable). */
  def wataState(s: ShellState): WataState = s.applets(WATA) match
    case w: WataApplet => w.state
    case _             => WataLogic.initial()
  /** the kid settings applet's typed state (slot SETTINGS, same construction). */
  def kidState(s: ShellState): KidSettingsState = s.applets(SETTINGS) match
    case a: KidSettingsApplet => a.state
    case _                    => KidSettingsLogic.initial()
  /** the developer settings applet's typed state (slot DEV, same construction). */
  def devState(s: ShellState): SettingsState = s.applets(DEV) match
    case a: SettingsApplet => a.state
    case _                 => SettingsLogic.initial()
  /** send/play status feedback lands on the wata applet regardless of the
   *  active one. */
  def notifyWataSend(s: ShellState, isError: Boolean): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifySend(wataState(s), isError)))
  def notifyWataPlayError(s: ShellState, fetchFailed: Boolean): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifyPlayError(wataState(s), fetchFailed)))
  /** an arrival auto-play (plan 0041): mark the wata applet playing exactly as
   *  its own OK press does, so the existing `AePlaybackDone` arm sends the
   *  read receipt. The mac's pump mutates `PumpSt.wata` directly; wata-fb's
   *  applet state lives behind `stateC`, so the mutation rides this shim like
   *  `notifyWataSend`'s. */
  def notifyWataPlaying(s: ShellState, room: String, id: String): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.withPlaying(wataState(s), true, room, id)))

  // ---- input routing ----------------------------------------------------------
  /** applet switching on dot1/dot2 (press only); PTT always -> wata; else
   *  DYNAMIC DISPATCH to the active applet through the `Applet` interface
   *  (rather than a hand-rolled `if active == SETTINGS` two-arm check here and
   *  at update/render). The per-frame ctx carries the snapshot + queues. */
  def handleInput(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    if isPressed(ks) && isDot(k) then switchApplet(s, k)
    else if isPtt(k) then pttGlobal(s, k, ks, ctx)   // PTT is global
    else if isPressed(ks) && isSnakeBack(s, k) then withActive(s, WATA)
    else if isPressed(ks) && isDevBack(s, k) then devReturn(s)
    else if isPressed(ks) && isDevDoor(s, k) then withActive(s, DEV)
    else routeActive(s, k, ks, ctx)

  def isPressed(ks: KeyState): Boolean = ks match
    case Pressed() => true
    case _         => false

  def isDot(k: Key): Boolean = k match
    case KDot1() => true
    case KDot2() => true
    case _       => false

  def isPtt(k: Key): Boolean = k match
    case KPtt() => true
    case _      => false

  /** red pressed while the snake applet is active: leave the game, back to
   *  wata (the release then lands on wata's contacts view, a no-op). */
  def isSnakeBack(s: ShellState, k: Key): Boolean =
    s.active == SNAKE && isBackKey(k)

  def isBackKey(k: Key): Boolean = k match
    case KBack() => true
    case _       => false

  def isEnterKey(k: Key): Boolean = k match
    case KEnter() => true
    case _        => false

  /** red pressed while the developer applet is active: back to the kid
   *  settings — the snake-back convention, one applet further in. The one
   *  exception is the enrolment QR, whose ONLY way out is Back (its screen
   *  says so), so the key routes into the applet while it is open. */
  def isDevBack(s: ShellState, k: Key): Boolean =
    s.active == DEV && isBackKey(k) && !devState(s).enrolOpen

  /** leaving the developer applet drops its confirm latch — an armed power
   *  row must not stay armed behind a closed door. */
  def devReturn(s: ShellState): ShellState =
    withActive(withApplet(s, DEV, SettingsApplet(SettingsLogic.disarmed(devState(s)))), SETTINGS)

  /** OK on the kid panel's hidden development row opens the developer
   *  applet (the row itself does nothing inside the kid applet). */
  def isDevDoor(s: ShellState, k: Key): Boolean =
    s.active == SETTINGS && isEnterKey(k) && kidState(s).selected == KidSettingsLogic.DEV_ROW

  /** the dot rotation covers the first `ROTATION` applets; from the DEV
   *  applet (outside it) a dot leaves through the settings slot it is the
   *  hidden room of. */
  def switchApplet(s: ShellState, k: Key): ShellState = k match
    case KDot2() => withActive(s, (rotBase(s.active) + 1) % ROTATION)
    case _       => withActive(s, prevIdx(rotBase(s.active), ROTATION))

  def rotBase(a: scala.Int): scala.Int =
    var out = a
    if a == DEV then out = SETTINGS
    out

  def prevIdx(active: scala.Int, n: scala.Int): scala.Int =
    var out = active - 1
    if active == 0 then out = n - 1
    out

  /** PTT is the TALK key everywhere (plan 0052): inside the wata applet it
   *  records; from any OTHER applet the press chimes and brings the wata
   *  screen up instead — a kid mid-snake must not broadcast invisibly. The
   *  press is swallowed (no recording starts), and the matching release then
   *  lands on the wata applet as a no-op (`pttHeld` is false), so the SECOND
   *  press is the one that talks — with the screen now saying to whom. */
  def pttGlobal(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    if s.active == WATA then routeWata(s, k, ks, ctx)
    else if isPressed(ks) then
      ctx.audioCmds.trySend(AcChime())
      withActive(s, WATA)
    else s

  /** the wata-applet direct call — a special-cased dispatch outside the
   *  uniform one (PTT and the notify shims target wata by construction). */
  def routeWata(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, WATA, s.applets(WATA).handleInput(k, ks, ctx))

  /** the dispatch site — one dynamic call through the interface. */
  def routeActive(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, s.active, s.applets(s.active).handleInput(k, ks, ctx))

  // ---- per-frame update --------------------------------------------------------
  /** Drain the audio-event mailbox ONCE, here, routing each event to its
   *  owning applet by type — then tick every applet (the wata and settings
   *  applets are updated on every frame regardless of which one is displayed;
   *  the snake alone ticks only while active — see `tickOne`). The single drain is
   *  load-bearing: two per-applet drains on the one channel would each eat the
   *  other's events, and which drain won depended on when the audio goroutine's
   *  send landed inside the frame — an `AeRecordingDone` falling between them
   *  was silently discarded, i.e. a recording dropped on the floor
   *  (docs/plans/0009-audio-event-routing.md). */
  def update(s: ShellState, dt: scala.Double, ctx: FrameCtx): ShellState =
    val d = drainAudio(s, ctx)
    syncPrefs(ShellState(d.active, d.status,
      IArray.tabulate(d.applets.length)(i => tickOne(d, i, dt, ctx))))

  /** the two settings applets edit the SAME persisted `FbPrefs` (brightness,
   *  screen timeout) through the same `FbConfig.savePrefs` path; their
   *  state records each hold a mirror, so after every frame the INACTIVE
   *  panel's mirror is refreshed from the active one's — only the active
   *  applet receives input, so the active side is always the side that
   *  changed. This is what lets `Ui` read brightness/timeout from the DEV
   *  state alone and both panels' rows agree whenever either is looked at.
   *  The notify mode needs no mirror since plan 0055: the kid row is its
   *  only editor, and `FbConfig`'s cell is the shared authority. */
  def syncPrefs(s: ShellState): ShellState =
    if s.active == DEV then syncKidFromDev(s) else syncDevFromKid(s)

  def syncKidFromDev(s: ShellState): ShellState =
    val k = kidState(s)
    val d = devState(s)
    if k.brightness != d.brightness || k.timeoutIdx != d.screenTimeoutIdx then
      withApplet(s, SETTINGS, KidSettingsApplet(
        KidSettingsLogic.mirrored(k, d.brightness, d.screenTimeoutIdx)))
    else s

  def syncDevFromKid(s: ShellState): ShellState =
    val k = kidState(s)
    val d = devState(s)
    if d.brightness != k.brightness then
      withApplet(s, DEV, SettingsApplet(SettingsLogic.withBrightness(d, k.brightness)))
    else s
  /** every applet ticks every frame — EXCEPT the snake, which ticks only
   *  while it is the active applet: the Zig shell ticks the active applet
   *  alone, so switching away from its snake implicitly pauses the game, and
   *  an always-ticking port would instead run it into a wall unwatched. The
   *  always-tick rule exists for the audio-event routing (plan 0009), which
   *  the snake takes no part in. */
  def tickOne(s: ShellState, i: scala.Int, dt: scala.Double, ctx: FrameCtx): Applet =
    if i == SNAKE && s.active != SNAKE then s.applets(i)
    else s.applets(i).update(dt, ctx)

  /** the ONE consumer of `ctx.audioEvts`: every pending event, routed. */
  def drainAudio(s0: ShellState, ctx: FrameCtx): ShellState =
    var s = s0
    var run = true
    while run do
      ctx.audioEvts.tryReceive() match
        case e: Some[AudioEvt] => s = routeAudio(s, e.value, ctx)
        case None => run = false
    s

  /** echo events belong to the developer applet's test widget (the echo row
   *  lives there); recording and playback events to the wata applet's
   *  send/play paths. */
  def routeAudio(s: ShellState, e: AudioEvt, ctx: FrameCtx): ShellState =
    if isEchoEvt(e) then
      withApplet(s, DEV, SettingsApplet(SettingsLogic.onEcho(devState(s), e)))
    else
      withApplet(s, WATA, WataApplet(WataLogic.onAudioEvent(wataState(s), e, ctx)))

  def isEchoEvt(e: AudioEvt): Boolean = e match
    case _: AeEchoRecording => true
    case _: AeEchoPlaying   => true
    case _: AeEchoDone      => true
    case _: AeEchoError     => true
    case _                  => false

  // ---- render (1px status line + active applet) --------------------------------
  def render(s: ShellState, px: go.Bytes, ctx: FrameCtx): Unit =
    Draw.hline(px, 0, 0, Display.W, ShellStatus.color(s.status))
    s.applets(s.active).render(px, ctx)
