/** THE DELIVERY SQUARES — plan 0078's outbound state, as a pure rule.
 *
 *  Two check marks are a borrowed metaphor that must be taught and sit oddly
 *  beside a language made of bars; my rows carry two small SQUARES instead,
 *  in the right gutter (my bars grow from the right, so that gutter is their
 *  root), filling as the message travels:
 *
 *      QUEUED    both hollow      the send is still mine — nothing has it
 *      SERVER    one filled       the server has it (the row is in the
 *                                 timeline, which is what "has it" means)
 *      PLAYED    both filled      somebody played it (`playedByPeer`)
 *      REFUSED   one red square   it will never arrive (the outbox's
 *                                 UNDELIVERABLE drop)
 *
 *  Same rectangle vocabulary as the unheard cap on the other side, and it
 *  survives being 5 px wide on the handset — a pair of ticks does not. This
 *  REPLACES the check-glyph convention inside the thread; received rows carry
 *  no squares at all (their state is the bar's ink).
 *
 *  The rule is `(delivery state) -> (square one, square two)`, each square
 *  one of the render states below; how big a square is and where the gutter
 *  sits is the body's geometry, not this rule's.
 */
object Delivery:

  // ---- the delivery states a row can be in ----------------------------------
  /** not my row — no squares at all. */
  val NONE = 0
  /** queued locally; nothing beyond this device has it. */
  val QUEUED = 1
  /** the server has it (it is in the timeline). */
  val SERVER = 2
  /** somebody other than me has played it. */
  val PLAYED = 3
  /** the server refused it for good — it will never arrive. */
  val REFUSED = 4

  // ---- what one square renders as -------------------------------------------
  val SQ_NONE = 0    // absent
  val SQ_HOLLOW = 1  // outline only
  val SQ_FILLED = 2  // solid, in the row's ink
  val SQ_RED = 3     // solid red — the never-arriving state

  def squareOne(state: scala.Int): scala.Int =
    if state == QUEUED then SQ_HOLLOW
    else if state == SERVER then SQ_FILLED
    else if state == PLAYED then SQ_FILLED
    else if state == REFUSED then SQ_RED
    else SQ_NONE

  def squareTwo(state: scala.Int): scala.Int =
    if state == QUEUED then SQ_HOLLOW
    else if state == SERVER then SQ_HOLLOW
    else if state == PLAYED then SQ_FILLED
    else SQ_NONE // REFUSED is a single red square; NONE has no squares

  def showState(state: scala.Int): String =
    if state == QUEUED then "queued"
    else if state == SERVER then "server"
    else if state == PLAYED then "played"
    else if state == REFUSED then "refused"
    else "none"

  def showSquare(sq: scala.Int): String =
    if sq == SQ_HOLLOW then "hollow"
    else if sq == SQ_FILLED then "filled"
    else if sq == SQ_RED then "red"
    else "-"
