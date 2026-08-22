/** The watch's input vocabulary — the CONTRACT with go-pkgs/watchshell's
 *  input.go, which declares the same constants.
 *
 *  This replaces the handset key codes the watch used to speak (plan 0071's
 *  first step). A shell reports what the PERSON DID — navigate, choose, go
 *  back, talk — and the domain decides what that means; a wrist gesture no
 *  longer has to be described as an arrow key on a walkie-talkie that is not
 *  present.
 *
 *  `NAVIGATE` carries an AXIS and a signed MAGNITUDE rather than a
 *  direction. The magnitude is in cards: 1.0 is one crown detent, a swipe is
 *  worth more because a flick is harder than a nudge, and later a drag will
 *  report its speed at release. Plan 0070's scrolling is physical — impulse,
 *  friction, detent spring, end spring — and that model lives ONCE above
 *  this boundary, so what each device owes it is only "how hard was it
 *  pushed".
 *
 *  The integrator does not exist yet (plan 0071's step 2), so `Intents.steps`
 *  rounds a magnitude to whole cards and the pump moves that many rows. The
 *  magnitude itself is carried across the seam and logged, so a nudge and a
 *  flick are already distinguishable — retrofitting it into a settled
 *  interface later is the expensive version.
 *
 *  `AXIS_H` is RESERVED AND UNUSED: no gesture produces it and no body
 *  consumes it. It is here so the integrator runs per axis and layout can
 *  position from an (x, y) offset. */
object Intents:
  val NONE = 0
  val NAVIGATE = 1  // axis + signed magnitude in cards
  val CHOOSE = 2    // open what is centred
  val BACK = 3
  val TALK_DOWN = 4 // hold-to-talk edges — the send path hangs off these
  val TALK_UP = 5
  val WAKE = 6      // lifecycle: the wrist rose
  val SLEEP = 7     // lifecycle: the wrist dropped
  val RAW = 8       // escape hatch: a device-specific code, nothing on the watch

  val AXIS_V = 0
  val AXIS_H = 1 // reserved

  def axisName(axis: scala.Int): String = if axis == AXIS_H then "h" else "v"

  def name(kind: scala.Int): String =
    if kind == NAVIGATE then "navigate"
    else if kind == CHOOSE then "choose"
    else if kind == BACK then "back"
    else if kind == TALK_DOWN then "talk-down"
    else if kind == TALK_UP then "talk-up"
    else if kind == WAKE then "wake"
    else if kind == SLEEP then "sleep"
    else if kind == RAW then "raw"
    else "none"

  /** whole cards to move for a magnitude, until the integrator turns
   *  magnitudes into motion. Rounds away from zero, and never rounds a real
   *  push down to nothing: the smallest gesture the shell can report still
   *  moves one row. */
  def steps(amount: scala.Double): scala.Int =
    if amount > 0.0 then
      val n = (amount + 0.5).toInt
      if n < 1 then 1 else n
    else if amount < 0.0 then
      val n = (-amount + 0.5).toInt
      if n < 1 then -1 else -n
    else 0

  /** a magnitude with two decimals, so a nudge and a flick differ in the log
   *  (Sgola has no printf, and an Int cannot show the difference between 1.0
   *  and 1.4). */
  def fmt(amount: scala.Double): String =
    val neg = amount < 0.0
    val a = if neg then -amount else amount
    val whole = a.toInt
    val hundredths = ((a - whole.toDouble) * 100.0 + 0.5).toInt
    val h = if hundredths > 99 then 99 else hundredths
    val frac = if h < 10 then "0" + h else "" + h
    (if neg then "-" else "") + whole + "." + frac
