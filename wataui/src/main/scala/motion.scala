/** THE MOTION INTEGRATOR — plan 0070's physical scrolling, once, above the
 *  platform (plan 0071's boundary).
 *
 *  A rolodex is a physical metaphor, so the scroll position is not an index a
 *  shell moves: it is a simulated quantity. Input adds VELOCITY, friction
 *  decays it exponentially, a critically damped spring pulls into the nearest
 *  detent below a threshold speed, and a stiffer spring at each end gives and
 *  bounces back — which is how a kid learns the list has an end without being
 *  told. Two quick presses are twice the shove, so acceleration falls out for
 *  free.
 *
 *  Why it lives HERE, in the backend-free UI layer: a crown, an arrow key and
 *  a thumb should differ in what they CONTRIBUTE, not in how the thing behaves
 *  afterwards. A shell owes exactly two things — an impulse (`Navigate`'s
 *  signed magnitude, in items) and a frame clock (`step`'s `dt`) — and gets
 *  the same feel on every device. This module links `core` and nothing else,
 *  same as the rest of `wataui`.
 *
 *  ## Per axis, not on a scalar
 *
 *  Nothing moves sideways today and nothing should until there is a reason,
 *  but the horizontal axis is plumbed so a later per-card action strip or a
 *  scrub along a message is a body change rather than a re-architecture. It is
 *  integrated with the same code at a count of one item, so it is pinned at
 *  zero — RESERVED AND UNUSED, and demonstrably so rather than by assertion.
 *
 *  ## Why a fixed sub-step
 *
 *  The springs are integrated with explicit Euler, which at stiffness 340 goes
 *  UNSTABLE if it is handed a whole frame: a 33 ms step at k=340 has the
 *  position growing each step instead of converging, and a dropped frame turns
 *  a bounce into an explosion. So real elapsed time is ACCUMULATED and spent in
 *  fixed 1/240 s sub-steps, with the remainder carried in the state — the frame
 *  rate then changes how smooth the motion looks and never what it does.
 *
 *  ## The constants
 *
 *  Plan 0070's, tuned in a browser mockup and to be re-tuned against a crown
 *  and a keypad. They are consistent with each other in a way worth stating: an
 *  impulse of one detent (7 items/s) under friction alone coasts
 *  `7 * 0.14 = 0.98` items, so one crown detent is one card, and a flick worth
 *  three detents coasts about three.
 */
case class MotionAxis(
  // where the list is, in ITEMS — 1.0 is one card, fractional between two
  pos: Double,
  // items per second, positive toward the END of the list
  vel: Double,
  // elapsed time accepted but not yet spent in whole sub-steps
  acc: Double
) derives CanEqual

/** the whole model: both axes, how long since the last input (the settle), and
 *  how open the stack is (0 = one card full bleed, 1 = the stack). */
case class Motion(v: MotionAxis, h: MotionAxis, sinceInput: Double, open: Double)
  derives CanEqual

object Motion:
  /** items per second added by one item of `Navigate` magnitude — plan 0070's
   *  "7 cards/s per detent". */
  val IMPULSE_PER_ITEM = 7.0
  /** friction's time constant: velocity decays by `1/e` in this long. */
  val FRICTION_TAU = 0.14
  /** the detent spring, and its CRITICAL damping `2*sqrt(k)`. Written out
   *  rather than computed: this dialect has no `math.sqrt` and a spring's
   *  damping is a design constant, not a runtime one. */
  val DETENT_K = 180.0
  val DETENT_C = 26.8328 // 2*sqrt(180)
  /** the end spring — stiffer, so the list's end gives and pushes back rather
   *  than absorbing the flick. */
  val WALL_K = 340.0
  val WALL_C = 36.8782 // 2*sqrt(340)
  /** seconds after the last input before the stack closes over what is
   *  centred. */
  val SETTLE_S = 0.45

  /** above this speed the detent spring is OFF, so a flick coasts past cards
   *  instead of being fought for each one; below it the spring takes over and
   *  lands on the nearest.
   *
   *  NOT in plan 0070 — the plan says "below a threshold speed" and never
   *  which — and it is the one constant here that is DERIVED rather than
   *  chosen, because it decides whether one crown detent moves one card.
   *
   *  Under friction alone an impulse of `v0` coasts `v0 * FRICTION_TAU`, and it
   *  is captured by the spring having covered the fraction `1 - s/v0` of that.
   *  One detent is `v0 = 7`, so it coasts `0.98 * (1 - s/7)` before the spring
   *  takes it — and the spring pulls toward the NEAREST detent, so anything
   *  short of half a card is pulled straight back to where it started. That is
   *  a hard floor at `s = 3.43`, and the first value tried here was 3.5: one
   *  detent coasted 0.49 cards and the crown did nothing at all, which
   *  `motionSettlesOnADetent` caught. 2.0 coasts 0.70 — past the line with room
   *  for the constants to be re-tuned around it.
   */
  val SNAP_SPEED = 2.0

  /** the integration sub-step (the mockup's). */
  val SUB_DT = 1.0 / 240.0
  /** the longest real interval one `step` will simulate. A stalled frame (a
   *  launch hitch, a debugger) must not be paid back as a burst of motion the
   *  user never asked for. */
  val MAX_DT = 0.25

  /** how fast the stack opens and closes. Also not in the plan: the plan gives
   *  the settle DELAY (450 ms) and says nothing about the zoom's own duration.
   */
  val OPEN_TAU = 0.10

  /** what counts as stopped. Both are tight enough to be invisible and loose
   *  enough that the pump goes quiet rather than chasing the last 1e-9. */
  val REST_VEL = 0.05  // items/s
  val REST_POS = 0.004 // items
  val OPEN_EPS = 0.01

  def axisAtRest(): MotionAxis = MotionAxis(0.0, 0.0, 0.0)

  /** the top of the list, closed, settled long ago. */
  def initial(): Motion =
    Motion(axisAtRest(), axisAtRest(), SETTLE_S + 1.0, 0.0)

  def absD(x: Double): Double = if x < 0.0 then -x else x

  /** the nearest detent to `x` — round-half-away-from-zero, spelled out
   *  because this dialect has no `Math.round`. */
  def nearest(x: Double): Double =
    if x >= 0.0 then (x + 0.5).toInt.toDouble
    else -(((-x) + 0.5).toInt.toDouble)

  /** the last valid detent for a list of `count` items (an empty list has one
   *  detent, at zero, so a body never divides by nothing). */
  def lastIndex(count: Int): Double =
    if count <= 1 then 0.0 else (count - 1).toDouble

  // ---- input ----------------------------------------------------------------

  /** a shell's contribution: `amount` in ITEMS, positive toward the end of the
   *  list. Adds to whatever velocity is already there, which is what makes two
   *  quick presses twice the shove and needs no acceleration curve of its own.
   *  It also restarts the settle, so the stack stays open while someone is
   *  still moving. */
  def impulse(m: Motion, axis: Int, amount: Double): Motion =
    if axis == MotionAxes.H then
      Motion(m.v, addVel(m.h, amount), 0.0, m.open)
    else Motion(addVel(m.v, amount), m.h, 0.0, m.open)

  def addVel(a: MotionAxis, amount: Double): MotionAxis =
    MotionAxis(a.pos, a.vel + amount * IMPULSE_PER_ITEM, a.acc)

  /** put the list AT an item with no motion — what a body does when the
   *  underlying list changed out from under the cursor (a conversation
   *  vanished), so the physics never has to be taught about the model. */
  def placeAt(m: Motion, index: Int): Motion =
    Motion(MotionAxis(index.toDouble, 0.0, m.v.acc), m.h, m.sinceInput, m.open)

  // ---- integration ----------------------------------------------------------

  /** advance the model by `dt` real seconds. `countV` is how many items the
   *  vertical list holds; the horizontal axis is reserved, so it is integrated
   *  against a single item and stays pinned at zero. */
  def step(m: Motion, dt0: Double, countV: Int): Motion =
    var dt = dt0
    if dt < 0.0 then dt = 0.0
    if dt > MAX_DT then dt = MAX_DT
    val nv = stepAxis(m.v, dt, countV)
    val nh = stepAxis(m.h, dt, 1)
    val since = m.sinceInput + dt
    // the stack is open while anything is moving, and for SETTLE_S after the
    // last input — then it closes over whoever is centred.
    val target = if since < SETTLE_S || moving(nv) || moving(nh) then 1.0 else 0.0
    Motion(nv, nh, since, approach(m.open, target, dt, OPEN_TAU))

  /** one axis, in fixed sub-steps against accumulated real time. */
  def stepAxis(a: MotionAxis, dt: Double, count: Int): MotionAxis =
    val last = lastIndex(count)
    var pos = a.pos
    var vel = a.vel
    var acc = a.acc + dt
    while acc >= SUB_DT do
      // friction, always: an exponential decay integrated explicitly, which at
      // this sub-step is within a fraction of a percent of `exp(-h/tau)`.
      vel = vel - vel * (SUB_DT / FRICTION_TAU)
      if pos < 0.0 then
        // past the top: the end spring, which gives and bounces back
        vel = vel + ((0.0 - pos) * WALL_K - vel * WALL_C) * SUB_DT
      else if pos > last then
        vel = vel + ((last - pos) * WALL_K - vel * WALL_C) * SUB_DT
      else if absD(vel) < SNAP_SPEED then
        // slow enough to be captured: the detent spring, critically damped
        vel = vel + ((nearest(pos) - pos) * DETENT_K - vel * DETENT_C) * SUB_DT
      pos = pos + vel * SUB_DT
      acc = acc - SUB_DT
    // Snap to rest EXACTLY once the spring has all but arrived, so the frame
    // clock can go quiet: a body that is 1e-9 off a detent forever would keep
    // the pump painting identical frames.
    if absD(vel) < REST_VEL then
      val n = nearest(pos)
      if n >= 0.0 && n <= last && absD(pos - n) < REST_POS then
        pos = n
        vel = 0.0
    MotionAxis(pos, vel, acc)

  /** move `cur` a fraction of the way to `target`, snapping when it is close —
   *  the zoom's own easing, and the same reason as the rest snap above. */
  def approach(cur: Double, target: Double, dt: Double, tau: Double): Double =
    var k = dt / tau
    if k > 1.0 then k = 1.0
    if k < 0.0 then k = 0.0
    var out = cur + (target - cur) * k
    if absD(target - out) < OPEN_EPS then out = target
    out

  // ---- what a body and a pump read -----------------------------------------

  def moving(a: MotionAxis): Boolean =
    absD(a.vel) > REST_VEL || absD(a.pos - nearest(a.pos)) > REST_POS

  /** "there is motion, keep painting" — plan 0071's signal to the surface. A
   *  frame can differ from the last one because of time alone, and this is the
   *  only thing that says so. */
  def live(m: Motion): Boolean =
    moving(m.v) || moving(m.h) || m.sinceInput < SETTLE_S ||
      (m.open > OPEN_EPS && m.open < 1.0 - OPEN_EPS)

  /** the item under the centre band — what a held talk button talks to, at any
   *  zoom. Clamped into the list, because the position may be outside it while
   *  the end spring is giving. */
  def centre(m: Motion, count: Int): Int =
    var i = nearest(m.v.pos).toInt
    if i < 0 then i = 0
    if count > 0 && i > count - 1 then i = count - 1
    if i < 0 then i = 0
    i

  /** the fractional scroll position a layout offsets items from. */
  def offset(m: Motion): Double = m.v.pos

  /** 0 = one card full bleed, 1 = the stack open. */
  def openness(m: Motion): Double = m.open

/** the axes a `Navigate` names. The same two constants each shell declares;
 *  `H` is reserved and no gesture produces it. */
object MotionAxes:
  val V = 0
  val H = 1
