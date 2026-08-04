import language.experimental.saferExceptions

/** The SHELL + main loop (the UI-thread half). Device-layer app code.
 *
 *  UI state is an IMMUTABLE record (`ShellState` + per-applet state records)
 *  threaded through pure transition functions, rather than stateful
 *  applet objects with mutable fields — matching the immutable-record style
 *  used elsewhere in this codebase (e.g. the sync engine). The live cell is
 *  ONE module var (`stateV`) touched by ONE goroutine (the UI/main loop).
 *
 *  APPLET SET: `wata` (index 0) + `settings` (index 1). dot1/dot2 switch
 *  applets; PTT always routes to wata regardless of the active applet.
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

  /** connection -> status. */
  def fromConnection(c: ConnectionState): Status = c match
    case _: Disconnected => StDisconnected()
    case _: Connecting   => StIdle()
    case _: Connected    => StConnected()
    case _: Syncing      => StSyncing()
    case _: ConnError    => StErr()

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

  /** the boot state. The settings applet starts from the STORED preferences,
   *  so brightness and screen timeout survive a restart; everything else
   *  starts from its own defaults. */
  def initial(prefs: FbPrefs): ShellState =
    ShellState(WATA, StIdle(),
      IArray[Applet](WataApplet(WataLogic.initial()), SettingsApplet(SettingsLogic.restored(prefs))))

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
  /** the settings applet's typed state (slot SETTINGS, same construction). */
  def settingsState(s: ShellState): SettingsState = s.applets(SETTINGS) match
    case a: SettingsApplet => a.state
    case _                 => SettingsLogic.initial()
  /** send/play status feedback lands on the wata applet regardless of the
   *  active one. */
  def notifyWataSend(s: ShellState, isError: Boolean): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifySend(wataState(s), isError)))
  def notifyWataPlayError(s: ShellState): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifyPlayError(wataState(s))))

  // ---- input routing ----------------------------------------------------------
  /** applet switching on dot1/dot2 (press only); PTT always -> wata; else
   *  DYNAMIC DISPATCH to the active applet through the `Applet` interface
   *  (rather than a hand-rolled `if active == SETTINGS` two-arm check here and
   *  at update/render). The per-frame ctx carries the snapshot + queues. */
  def handleInput(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    if isPressed(ks) && isDot(k) then switchApplet(s, k)
    else if isPtt(k) then routeWata(s, k, ks, ctx)   // PTT is global
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

  def switchApplet(s: ShellState, k: Key): ShellState = k match
    case KDot2() => withActive(s, (s.active + 1) % s.applets.length)
    case _       => withActive(s, prevIdx(s.active, s.applets.length))

  def prevIdx(active: scala.Int, n: scala.Int): scala.Int =
    var out = active - 1
    if active == 0 then out = n - 1
    out

  /** PTT routes to the wata applet regardless of the active one — a
   *  special-cased direct call, outside the uniform dispatch. */
  def routeWata(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, WATA, s.applets(WATA).handleInput(k, ks, ctx))

  /** the dispatch site — one dynamic call through the interface. */
  def routeActive(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, s.active, s.applets(s.active).handleInput(k, ks, ctx))

  // ---- per-frame update --------------------------------------------------------
  /** EVERY applet ticks every frame (the wata applet must drain audio events —
   *  recording-done -> send — even when settings is active, so both applets
   *  are updated on every frame regardless of which one is displayed). */
  def update(s: ShellState, dt: scala.Double, ctx: FrameCtx): ShellState =
    ShellState(s.active, s.status, IArray.tabulate(s.applets.length)(i => tickOne(s, i, dt, ctx)))
  def tickOne(s: ShellState, i: scala.Int, dt: scala.Double, ctx: FrameCtx): Applet =
    s.applets(i).update(dt, ctx)

  // ---- render (1px status line + active applet) --------------------------------
  def render(s: ShellState, px: go.Bytes, ctx: FrameCtx): Unit =
    Draw.hline(px, 0, 0, Display.W, ShellStatus.color(s.status))
    s.applets(s.active).render(px, ctx)
