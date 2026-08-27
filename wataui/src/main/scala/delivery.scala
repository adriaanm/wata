/** THE DELIVERY DOTS — plan 0078's outbound state, as a pure rule.
 *
 *  Two check marks are a borrowed metaphor that must be taught and sit oddly
 *  beside a language made of bars; my rows carry two small colour-coded
 *  CIRCLES instead, in the right gutter (my bars grow from the right, so that
 *  gutter is their root). The pair's grammar: the FIRST slot is "the server
 *  has it", the SECOND is "the audience played it"; a pending slot is a dim
 *  RING, a completed one a GREEN disc, and a refused send collapses the pair
 *  to a single RED disc (owner ruling 2026-08-27 — colour-coded circles stand
 *  apart from the bar rectangles better than plan 0070's squares did):
 *
 *      QUEUED    ring + ring      the send is still mine — nothing has it
 *      SERVER    green + ring     the server has it (the row is in the
 *                                 timeline, which is what "has it" means)
 *      PLAYED    green + green    somebody played it (`playedByPeer`)
 *      REFUSED   one red disc     it will never arrive (the outbox's
 *                                 UNDELIVERABLE drop)
 *
 *  The states survive being 5 px wide on the handset — a pair of ticks does
 *  not. This REPLACES the check-glyph convention inside the thread; received
 *  rows carry no dots at all (their state is the bar's ink).
 *
 *  The rule is `(delivery state) -> (slot one, slot two)`, each slot one of
 *  the render states below; how big a dot is, what "dim" or "green" is in
 *  pixels, and where the gutter sits is the body's geometry, not this rule's.
 */
object Delivery:

  // ---- the delivery states a row can be in ----------------------------------
  /** not my row — no dots at all. */
  val NONE = 0
  /** queued locally; nothing beyond this device has it. */
  val QUEUED = 1
  /** the server has it (it is in the timeline). */
  val SERVER = 2
  /** somebody other than me has played it. */
  val PLAYED = 3
  /** the server refused it for good — it will never arrive. */
  val REFUSED = 4

  // ---- what one slot renders as ---------------------------------------------
  val SLOT_NONE = 0   // absent
  val SLOT_RING = 1   // pending: a dim outline circle
  val SLOT_GREEN = 2  // completed: a solid green disc
  val SLOT_RED = 3    // solid red disc — the never-arriving state

  def slotOne(state: scala.Int): scala.Int =
    if state == QUEUED then SLOT_RING
    else if state == SERVER then SLOT_GREEN
    else if state == PLAYED then SLOT_GREEN
    else if state == REFUSED then SLOT_RED
    else SLOT_NONE

  def slotTwo(state: scala.Int): scala.Int =
    if state == QUEUED then SLOT_RING
    else if state == SERVER then SLOT_RING
    else if state == PLAYED then SLOT_GREEN
    else SLOT_NONE // REFUSED is a single red disc; NONE has no dots

  def showState(state: scala.Int): String =
    if state == QUEUED then "queued"
    else if state == SERVER then "server"
    else if state == PLAYED then "played"
    else if state == REFUSED then "refused"
    else "none"

  def showSlot(sq: scala.Int): String =
    if sq == SLOT_RING then "ring"
    else if sq == SLOT_GREEN then "green"
    else if sq == SLOT_RED then "red"
    else "-"
