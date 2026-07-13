import language.experimental.saferExceptions

/** M8 chunk 7 — the SHELL + main loop, ported from fbclient `shell.zig` +
 *  `main.zig` (the UI-thread half). Device-layer app code.
 *
 *  HOUSE-STYLE PORT (DATA-9 flag, chunk-7 designer-visible call): the Zig
 *  applets are stateful modules (`var state = State{}`) driven through fn
 *  pointers. Porting that literally would force a stateful `object` with init
 *  effects — the DATA-9 lazy-val-init trigger the brief flagged. Instead the
 *  UI state is an IMMUTABLE record (`ShellState` + per-applet state records)
 *  threaded through pure transition functions, exactly the SyncEngine/integ
 *  house shape. The live cell is ONE module var (`stateV`) touched by ONE
 *  goroutine (the UI/main loop — the Runtime single-goroutine-per-cell
 *  adjudication). No stateful init-effect object is produced, so DATA-9's
 *  lowering is NOT forced here (recorded for the designer).
 *
 *  APPLET SET (decision 6 — gate scope): `wata` (index 0) + `settings` (index
 *  1). Clock/snake/charmap are chunk 8. dot1/dot2 switch applets; PTT always
 *  routes to wata regardless of the active applet (shell.zig handleInput).
 *
 *  dot2 CAVEAT (chunk-5 flag, RESOLVED here): the Zig client opens ONLY
 *  /dev/input/event{0,1,2} (input.zig `paths`) — the SAME three devices
 *  Evdev.open() opens. It binds dot2 (KEY_F10) but discovers no other bus, so
 *  the chunk-5 "dot2 on a different bus" gap is SHARED by the reference client,
 *  not a port defect. We mirror the three-device open faithfully (no fourth
 *  device); applet switching works via dot1 (KEY_F3, chunk-5 confirmed). See
 *  the chunk-7 report. */

// ---- status line state (shell.zig Status) -----------------------------------
sealed trait Status derives CanEqual
case class StIdle() extends Status
case class StConnected() extends Status
case class StSyncing() extends Status
case class StRecording() extends Status
case class StErr() extends Status
case class StDisconnected() extends Status

object ShellStatus:
  /** status -> RGB565 color (shell.zig Status.color). */
  def color(s: Status): scala.Int = s match
    case _: StIdle         => Color.midGray
    case _: StConnected    => Color.green
    case _: StSyncing      => Color.cyan
    case _: StRecording    => Color.yellow
    case _: StErr          => Color.red
    case _: StDisconnected => Color.red

  /** connection -> status (shell.zig Status.fromConnection). */
  def fromConnection(c: ConnectionState): Status = c match
    case _: Disconnected => StDisconnected()
    case _: Connecting   => StIdle()
    case _: Connected    => StConnected()
    case _: Syncing      => StSyncing()
    case _: ConnError    => StErr()

// ---- the immutable shell state (shell.zig Shell — the UI half) ---------------
/** M10 chunk 5: `active` indexes the APPLET LIST — each element an immutable
 *  `Applet` holding its own typed state behind the interface (0 = wata, 1 =
 *  settings; adding an applet = one append in `initial()`). `status` mirrors
 *  the connection. Derives `Shareable`: the record rides the UI goroutine's
 *  `Atomic[ShellState]` cell (CONC-4), so the aggregate must be shareable —
 *  every field is pure or an `IArray` of `Shareable` applets (CONC-7). */
case class ShellState(active: scala.Int, status: Status, applets: IArray[Applet]) extends Shareable

object Shell:
  val WATA = 0
  val SETTINGS = 1

  def initial(): ShellState =
    ShellState(WATA, StIdle(),
      IArray[Applet](WataApplet(WataLogic.initial()), SettingsApplet(SettingsLogic.initial())))

  // ---- record withers (no `.copy` on sgola — see WataLogic) -----------------
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

  // ---- the wata-specific seams (the RATIFIED caveats: PTT + quit/notify are
  // special cases OUTSIDE the uniform dispatch — they target the wata applet
  // by construction) -----------------------------------------------------------
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
   *  active one (main.zig UiEvents). */
  def notifyWataSend(s: ShellState, isError: Boolean): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifySend(wataState(s), isError)))
  def notifyWataPlayError(s: ShellState): ShellState =
    withApplet(s, WATA, WataApplet(WataLogic.notifyPlayError(wataState(s))))

  // ---- input routing (shell.zig handleInput) --------------------------------
  /** applet switching on dot1/dot2 (press only); PTT always -> wata; else
   *  DYNAMIC DISPATCH to the active applet (M10 ch.5 — formerly a hand-rolled
   *  two-arm `if active == SETTINGS` vtable here and at update/render). The
   *  per-frame ctx carries the snapshot + queues. */
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

  /** PTT routes to the wata applet regardless of the active one (the RATIFIED
   *  caveat: a special-cased direct call, outside the uniform dispatch). */
  def routeWata(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, WATA, s.applets(WATA).handleInput(k, ks, ctx))

  /** M10 ch.5: THE dispatch site — one dynamic call through the interface. */
  def routeActive(s: ShellState, k: Key, ks: KeyState, ctx: FrameCtx): ShellState =
    withApplet(s, s.active, s.applets(s.active).handleInput(k, ks, ctx))

  // ---- per-frame update (shell.zig update) -----------------------------------
  /** EVERY applet ticks every frame (the wata applet must drain audio events —
   *  recording-done -> send — even when settings is active; the Zig main loop
   *  calls setContext + drains for BOTH each frame). */
  def update(s: ShellState, dt: scala.Double, ctx: FrameCtx): ShellState =
    ShellState(s.active, s.status, IArray.tabulate(s.applets.length)(i => tickOne(s, i, dt, ctx)))
  def tickOne(s: ShellState, i: scala.Int, dt: scala.Double, ctx: FrameCtx): Applet =
    s.applets(i).update(dt, ctx)

  // ---- render (shell.zig render: 1px status line + active applet) -----------
  def render(s: ShellState, px: go.Bytes, ctx: FrameCtx): Unit =
    Draw.hline(px, 0, 0, Display.W, ShellStatus.color(s.status))
    s.applets(s.active).render(px, ctx)
