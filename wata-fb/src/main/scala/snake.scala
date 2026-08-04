/** The snake applet — a port of the Zig client's `applets/snake.zig` (the
 *  in-tree behavioral spec), on the landscape grid this module draws.
 *  Device-layer app code; same immutable-record + wither style as the other
 *  applets (see applets.scala's header).
 *
 *  GEOMETRY. The Zig client plays on its portrait grid (21x18 cells of
 *  6x8px, bottom row reserved for the score); this module's panel is
 *  landscape, so the board is `Font.COLS` x `Font.ROWS - 1` = 26x14 with the
 *  same 6x8px cells, the same 1px status-line offset, and the score on the
 *  last grid row — the parity table's usual same-information-equivalent-place
 *  rule. Movement, key guards, scoring (+10, speed-up 5ms/food to a 60ms
 *  floor), wall/self death, and the game-over/restart flow mirror the Zig
 *  code move for move.
 *
 *  DETERMINISM — the one deliberate deviation. The Zig applet seeds its PRNG
 *  from the wall clock; food placement here comes from a FIXED-SEED minstd
 *  LCG threaded through the state record and stepped only by game events
 *  (two draws per placement attempt), so a scripted run replays byte-for-byte
 *  under the uitest virtual clock and the goldens can pin an eat. A restart
 *  continues the sequence rather than reseeding, so consecutive games differ.
 *
 *  PAUSE/LEAVE. The Zig shell ticks only the ACTIVE applet, so switching
 *  away from its snake implicitly pauses the game; this module's shell ticks
 *  every applet every frame (a deliberate fix for audio routing), so
 *  `Shell.tickOne` special-cases this one slot back to active-only ticking —
 *  leaving the game freezes it instead of letting it run into a wall
 *  unwatched. The red key returns to the wata applet (`Shell.handleInput`),
 *  the shell's red-goes-back convention; the Zig applet had no leave key at
 *  all (only the dot buttons moved you out). */

// ---- direction ----------------------------------------------------------------
sealed trait SnakeDir derives CanEqual
case class DirUp() extends SnakeDir
case class DirDown() extends SnakeDir
case class DirLeft() extends SnakeDir
case class DirRight() extends SnakeDir

/** a food placement: the packed cell plus the PRNG state after the draws. */
case class FoodPick(food: scala.Int, rng: Long)

/** snake state. `body` is packed cells (`y * GRID_W + x`), head first;
 *  `nextDir` is the buffered turn applied at the next step (so two quick
 *  presses inside one tick can't fold the snake onto itself); `rng` is the
 *  deterministic food PRNG, threaded through every placement. */
case class SnakeState(
  body: IArray[scala.Int],
  dir: SnakeDir,
  nextDir: SnakeDir,
  food: scala.Int,
  alive: Boolean,
  score: scala.Int,
  tickTimer: scala.Double,
  tickRate: scala.Double,
  rng: Long
)

/** the snake applet: a thin dynamic-dispatch shell over `SnakeLogic` (the
 *  per-frame ctx is ignored — the game touches no snapshot and no queues). */
final class SnakeApplet(val state: SnakeState) extends Applet:
  def handleInput(k: Key, ks: KeyState, ctx: FrameCtx): Applet =
    SnakeApplet(SnakeLogic.handleInput(state, k, ks))
  def update(dt: scala.Double, ctx: FrameCtx): Applet =
    SnakeApplet(SnakeLogic.update(state, dt))
  def render(px: go.Bytes, ctx: FrameCtx): Unit =
    SnakeLogic.render(state, px)

object SnakeLogic:
  val GRID_W: scala.Int = Font.COLS          // 26 (Zig: font.cols = 21)
  val GRID_H: scala.Int = Font.ROWS - 1      // 14 — last grid row is the score
  val MAX_LEN: scala.Int = GRID_W * GRID_H
  val CELL_W: scala.Int = Font.GLYPH_W       // 6px
  val CELL_H: scala.Int = Font.GLYPH_H       // 8px
  val GRID_Y: scala.Int = 1                  // below the 1px status line

  val TICK_START: scala.Double = 0.15        // seconds per step
  val TICK_MIN: scala.Double = 0.06
  val TICK_DECR: scala.Double = 0.005

  /** the fixed PRNG seed (Zig seeds from the wall clock — see the header). */
  val SEED: Long = 88L

  // ---- the deterministic food PRNG (minstd: x -> x*48271 mod 2^31-1) ---------
  /** all-Long arithmetic, so the products stay well inside 64 bits on every
   *  backend and the Python design mirror in tools/ reproduces it exactly. */
  def nextRand(r: Long): Long = (r * 48271L) % 2147483647L

  // ---- packed-cell helpers ----------------------------------------------------
  def pack(x: scala.Int, y: scala.Int): scala.Int = y * GRID_W + x
  def posX(p: scala.Int): scala.Int = p % GRID_W
  def posY(p: scala.Int): scala.Int = p / GRID_W

  def bodyContains(body: IArray[scala.Int], p: scala.Int): Boolean =
    var i = 0
    var found = false
    while i < body.length do
      if body(i) == p then found = true
      i += 1
    found

  /** 100 random tries against the passed body, then the Zig fallback scan for
   *  the first free cell (same cell order: y-major, which is packed order). */
  def placeFood(body: IArray[scala.Int], r0: Long): FoodPick =
    var r = r0
    var found = -1
    var attempts = 0
    while found < 0 && attempts < 100 do
      r = nextRand(r)
      val fx = (r % GRID_W.toLong).toInt
      r = nextRand(r)
      val fy = (r % GRID_H.toLong).toInt
      val p = pack(fx, fy)
      if !bodyContains(body, p) then found = p
      attempts += 1
    if found < 0 then found = scanFree(body)
    FoodPick(found, r)

  def scanFree(body: IArray[scala.Int]): scala.Int =
    var p = 0
    var found = -1
    while found < 0 && p < MAX_LEN do
      if !bodyContains(body, p) then found = p
      p += 1
    found

  // ---- board setup ------------------------------------------------------------
  def initial(): SnakeState = freshBoard(SEED)

  /** a 3-cell snake at the board center heading right (the Zig start pose,
   *  centered on this grid), first food placed from `r`. */
  def freshBoard(r: Long): SnakeState =
    val cx = GRID_W / 2
    val cy = GRID_H / 2
    val body = IArray[scala.Int](pack(cx, cy), pack(cx - 1, cy), pack(cx - 2, cy))
    val pick = placeFood(body, r)
    SnakeState(body, DirRight(), DirRight(), pick.food, true, 0, 0.0, TICK_START, pick.rng)

  // ---- record withers (no `.copy` on sgola — see WataLogic) -------------------
  def withNextDir(s: SnakeState, nd: SnakeDir): SnakeState =
    SnakeState(s.body, s.dir, nd, s.food, s.alive, s.score, s.tickTimer, s.tickRate, s.rng)
  def withTimer(s: SnakeState, t: scala.Double): SnakeState =
    SnakeState(s.body, s.dir, s.nextDir, s.food, s.alive, s.score, t, s.tickRate, s.rng)

  // ---- input (press only) -----------------------------------------------------
  /** arrows steer (a reversal is ignored — the guard is against the APPLIED
   *  direction, as in the Zig code); dead board: OK restarts. The red key
   *  never reaches here — the shell routes it back to the wata applet. */
  def handleInput(s: SnakeState, k: Key, ks: KeyState): SnakeState =
    if !Shell.isPressed(ks) then s
    else if !s.alive then restartInput(s, k)
    else k match
      case _: KUp    => steer(s, DirUp())
      case _: KDown  => steer(s, DirDown())
      case _: KLeft  => steer(s, DirLeft())
      case _: KRight => steer(s, DirRight())
      case _         => s

  /** OK on a dead board starts the next game, CONTINUING the PRNG sequence
   *  (no reseed — see the header). */
  def restartInput(s: SnakeState, k: Key): SnakeState = k match
    case _: KEnter => freshBoard(s.rng)
    case _         => s

  def steer(s: SnakeState, nd: SnakeDir): SnakeState =
    if isOpposite(nd, s.dir) then s else withNextDir(s, nd)

  def isOpposite(a: SnakeDir, b: SnakeDir): Boolean = a match
    case _: DirUp    => isDown(b)
    case _: DirDown  => isUp(b)
    case _: DirLeft  => isRight(b)
    case _: DirRight => isLeft(b)

  def isUp(d: SnakeDir): Boolean = d match
    case _: DirUp => true
    case _        => false
  def isDown(d: SnakeDir): Boolean = d match
    case _: DirDown => true
    case _          => false
  def isLeft(d: SnakeDir): Boolean = d match
    case _: DirLeft => true
    case _          => false
  def isRight(d: SnakeDir): Boolean = d match
    case _: DirRight => true
    case _           => false

  def dx(d: SnakeDir): scala.Int = d match
    case _: DirLeft  => -1
    case _: DirRight => 1
    case _           => 0
  def dy(d: SnakeDir): scala.Int = d match
    case _: DirUp   => -1
    case _: DirDown => 1
    case _          => 0

  // ---- per-frame update -------------------------------------------------------
  /** accumulate the frame clock and step the game at `tickRate` (the Zig
   *  update loop: subtract-and-step while the timer covers a tick; a step
   *  that kills the snake leaves the loop draining the timer as a no-op).
   *  A dead board is frozen — no accumulation at all. */
  def update(s: SnakeState, dt: scala.Double): SnakeState =
    if !s.alive then s
    else
      var out = withTimer(s, s.tickTimer + dt)
      while out.tickTimer >= out.tickRate do
        out = step(withTimer(out, out.tickTimer - out.tickRate))
      out

  /** one game step: apply the buffered turn, move the head; walls and the
   *  snake's own body kill; food grows the snake, scores 10, speeds the game
   *  up 5ms (60ms floor), and places the next food. */
  def step(s: SnakeState): SnakeState =
    if !s.alive then s
    else
      val d = s.nextDir
      val hx = posX(s.body(0)) + dx(d)
      val hy = posY(s.body(0)) + dy(d)
      if hx < 0 || hx >= GRID_W || hy < 0 || hy >= GRID_H then died(s, d)
      else stepAt(s, d, pack(hx, hy))

  def stepAt(s: SnakeState, d: SnakeDir, nh: scala.Int): SnakeState =
    if bodyContains(s.body, nh) then died(s, d)
    else if nh == s.food then eat(s, d, nh)
    else move(s, d, nh)

  /** the Zig step sets `dir` from `next_dir` before it checks anything, so a
   *  fatal step still records the direction it died moving in. */
  def died(s: SnakeState, d: SnakeDir): SnakeState =
    SnakeState(s.body, d, s.nextDir, s.food, false, s.score, s.tickTimer, s.tickRate, s.rng)

  def move(s: SnakeState, d: SnakeDir, nh: scala.Int): SnakeState =
    val nb = IArray.tabulate(s.body.length)(i => shiftCell(s.body, nh, i))
    SnakeState(nb, d, s.nextDir, s.food, true, s.score, s.tickTimer, s.tickRate, s.rng)

  /** grow (unless the board is full, the Zig cap: the head is then simply
   *  replaced in place). Food is placed against the PRE-MOVE body — the Zig
   *  order, which draws before the new head cell exists. */
  def eat(s: SnakeState, d: SnakeDir, nh: scala.Int): SnakeState =
    val nb =
      if s.body.length < MAX_LEN then IArray.tabulate(s.body.length + 1)(i => shiftCell(s.body, nh, i))
      else IArray.tabulate(s.body.length)(i => keepCell(s.body, nh, i))
    val pick = placeFood(s.body, s.rng)
    var rate = s.tickRate
    if rate > TICK_MIN then rate = rate - TICK_DECR
    SnakeState(nb, d, s.nextDir, pick.food, true, s.score + 10, s.tickTimer, rate, pick.rng)

  /** cell `i` of the shifted body: the new head, then everything one back. */
  def shiftCell(body: IArray[scala.Int], nh: scala.Int, i: scala.Int): scala.Int =
    if i == 0 then nh else body(i - 1)
  /** cell `i` with only the head replaced (the full-board cap). */
  def keepCell(body: IArray[scala.Int], nh: scala.Int, i: scala.Int): scala.Int =
    if i == 0 then nh else body(i)

  // ---- render -----------------------------------------------------------------
  /** the Zig frame: black board, red food cell, green head + dark-green body
   *  with a 1px cell gap, zero-padded score on the bottom grid row, and the
   *  centered game-over overlay ("OK" is the green key's on-case label). */
  def render(s: SnakeState, px: go.Bytes): Unit =
    Draw.fillRect(px, 0, GRID_Y, Display.W, GRID_H * CELL_H, Color.black)
    Draw.fillRect(px, posX(s.food) * CELL_W, GRID_Y + posY(s.food) * CELL_H,
      CELL_W, CELL_H, Color.red)
    var i = 0
    while i < s.body.length do
      val c = if i == 0 then Color.green else Color.darkGreen
      Draw.fillRect(px, posX(s.body(i)) * CELL_W, GRID_Y + posY(s.body(i)) * CELL_H,
        CELL_W - 1, CELL_H - 1, c)
      i += 1
    Font.drawText(px, "SCORE:" + pad4(s.score), 0, Font.ROWS - 1, Color.green, false, 0)
    if !s.alive then
      Font.drawTextCentered(px, "GAME OVER", 6, Color.red, false, 0)
      Font.drawTextCentered(px, "OK to restart", 8, Color.midGray, false, 0)

  /** the Zig score format (`{d:0>4}`): zero-padded to at least four digits. */
  def pad4(n: scala.Int): String =
    var t = "" + n
    while t.length < 4 do t = "0" + t
    t
