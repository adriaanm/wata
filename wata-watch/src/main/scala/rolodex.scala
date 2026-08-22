import language.experimental.saferExceptions

/** THE ROLODEX — plan 0070's contact screen, on the wrist.
 *
 *  At rest the screen is ONE CONTACT, full bleed, in that person's own colour:
 *  their name as large as the panel allows and one line of state. Navigating
 *  shrinks the card to reveal it was always one of a vertical stack, and the
 *  stack scrolls under a fixed centre band. 450 ms after the last input the
 *  centre card grows back to full bleed.
 *
 *  Nothing here is an index and nothing is a frame count: the position is
 *  `wataui`'s `Motion` (a simulated quantity — impulse, friction, detent
 *  spring, end spring) and the zoom is that model's `open`, which is a
 *  continuous 0..1. So this body is a pure function of (cards, motion,
 *  panel), and every frame of the animation is one evaluation of it.
 *
 *  ## The geometry
 *
 *  One interpolation does the whole thing, which is why there is no separate
 *  "closed" and "open" layout to keep in agreement. Card `i`, at scroll
 *  position `p` and openness `o`:
 *
 *      full-bleed stacking:  y = (i - p) * H,        h = H
 *      the open stack:       y = centreY + (i-p)*rowH, h = rowH - gap
 *      what is drawn:        the two, lerped by `o`
 *
 *  At `o = 0` that is exactly one card filling the panel with its neighbours
 *  exactly one panel away — off screen, and correctly placed the instant a
 *  flick starts. At `o = 1` it is five rows under a fixed centre band. Nothing
 *  special-cases "the centre card": it is simply the one whose `i - p` is
 *  small.
 *
 *  ## What each card says
 *
 *  Colour, name, and the unheard count — the plan's "every visible card
 *  carries its colour, name and unheard count", which is what lets the roster
 *  disappear at rest without anything being lost.
 *
 *  The unheard count and the unheard BAR are ONE element rather than two: a
 *  yellow band across the top of the card, which is tall enough to hold "3
 *  unheard" when the card is full bleed and degrades continuously into a
 *  yellow rule along the top of a stack row. A second element that had to
 *  agree with the first about when to appear is how a design drifts.
 *
 *  Text is black on every card, because that is the palette's constraint
 *  (`Palette.INK`) rather than a decision this file makes. Type is by ROLE:
 *  the full-bleed name is `display`, a stack card's name is `name`, and the
 *  state line is `caption` — the renderer resolves each against this panel's
 *  metrics, which is the one place that knows how big the panel is.
 */
case class RoloCard(
  // the conversation's identity — the differ's key, so a card that scrolled is
  // recognised rather than rewritten
  key: String,
  name: String,
  color: scala.Int,
  // the one line of state under a full-bleed name
  state: String,
  unheard: scala.Int,
  // 0 = nothing of ours is pending, 1 = still queued, 2 = it will never
  // arrive. Plan 0070's delivery vocabulary is SQUARES, so this draws as one:
  // a small square in the card's trailing corner, yellow or red.
  mark: scala.Int
)

object Rolodex:
  /** how many rows the stack shows: the centre band and two neighbours each
   *  side. A property of the panel's shape, not of the watch — a taller panel
   *  gets taller rows, never more of them, because five is what "a glance"
   *  can hold. */
  val VISIBLE = 5
  /** the black gutter between stack rows, in semantic pixels. */
  val GAP = 2
  /** how far either side of the centre a card can be and still be worth
   *  building. At `VISIBLE = 5` two would do; three keeps a card that is
   *  sliding in from off-panel mid-flick. */
  val REACH = 3

  def rowH(h: scala.Int): scala.Int =
    val r = h / VISIBLE
    if r < 4 then 4 else r

  def centreY(h: scala.Int): scala.Int = (h - rowH(h)) / 2

  /** the side margin a stack row takes, so the rows read as cards rather than
   *  as a striped panel. Zero when the card is full bleed. */
  def padOpen(w: scala.Int): scala.Int =
    val p = w / 26
    if p < 2 then 2 else p

  def lerp(a: scala.Double, b: scala.Double, t: scala.Double): scala.Double =
    a + (b - a) * t

  def clamp01(x: scala.Double): scala.Double =
    if x < 0.0 then 0.0 else if x > 1.0 then 1.0 else x

  /** 0..255 from a 0..1 coverage. */
  def alphaOf(x: scala.Double): scala.Int = (clamp01(x) * 255.0 + 0.5).toInt

  // ---- the screen -----------------------------------------------------------

  /** the whole rolodex, as data. `cards` is the list in list order, `m` says
   *  where it is and how open it is, and `w`/`h` are the semantic panel. */
  def body(cards: List[RoloCard], count: scala.Int, m: Motion,
      w: scala.Int, h: scala.Int): View =
    val p = Motion.offset(m)
    val o = clamp01(Motion.openness(m))
    val centre = Motion.centre(m, count)
    var lo = centre - REACH
    if lo < 0 then lo = 0
    var hi = centre + REACH
    if hi > count - 1 then hi = count - 1
    var acc: List[Keyed] = Nil
    var i = lo
    while i <= hi do
      cardAt(cards, i) match
        case c: Some[RoloCard] =>
          cardView(c.value, i, p, o, w, h) match
            case v: Some[View] => acc = Keyed(c.value.key, v.value) :: acc
            case None          => ()
        case None => ()
      i += 1
    VGroup(ListOps.reverse(acc))

  /** one card, or None when it is entirely off the panel — culling here rather
   *  than letting the stage frame a view nobody can see keeps the patch script
   *  the size of what actually moved. */
  def cardView(c: RoloCard, i: scala.Int, p: scala.Double, o: scala.Double,
      w: scala.Int, h: scala.Int): Option[View] =
    val rh = rowH(h)
    val off = i.toDouble - p
    val yF = lerp(off * h.toDouble, centreY(h).toDouble + off * rh.toDouble, o)
    val hF = lerp(h.toDouble, (rh - GAP).toDouble, o)
    val y = roundI(yF)
    val ch = roundI(hF)
    if y + ch <= 0 || y >= h || ch < 2 then None
    else
      val x = roundI(o * padOpen(w).toDouble)
      val cw = w - 2 * x
      val radius = roundI(o * (rh / 3).toDouble)
      var kids: List[Keyed] = Nil
      kids = Keyed("card", VFill(x, y, cw, ch, radius, c.color, Alpha.OPAQUE)) :: kids
      // the unheard band, which becomes the unheard bar as the stack opens
      val bandH = if c.unheard > 0 then roundI(lerp((h / 6).toDouble, 3.0, o)) else 0
      if bandH >= 2 then
        kids = Keyed("band", VFill(x, y, cw, bandH, radius, Color.yellow, Alpha.OPAQUE)) :: kids
      val pad = 2 + roundI(o * padOpen(w).toDouble)
      // the name, optically centred in the card — a body cannot centre what it
      // cannot measure, so the element carries the BOX and the renderer places
      // the text in it.
      val nameH = roundI(lerp((h / 3).toDouble, (rh - GAP - 2).toDouble, o))
      val nameY = y + (ch - nameH) / 2
      val open = o >= 0.5
      val role = if open then TypeRole.NAME else TypeRole.DISPLAY
      val align = if open then TextAlign.LEADING else TextAlign.CENTER
      kids = Keyed("name", VLabel(x + pad, nameY, cw - 2 * pad, nameH, c.name,
        role, TypeWeight.BOLD, align, Palette.INK, Alpha.OPAQUE)) :: kids
      // the count, inside the band, only while the band is tall enough to
      // hold it. It fades out well before the band has finished shrinking, so
      // no frame shows clipped text.
      val ca = alphaOf((1.0 - o) * 2.0)
      if bandH >= 8 && ca > 8 then
        kids = Keyed("count", VLabel(x + pad, y + 1, cw - 2 * pad, bandH - 2,
          unheardText(c.unheard), TypeRole.CAPTION, TypeWeight.MEDIUM,
          TextAlign.CENTER, Palette.INK, ca)) :: kids
      // a message of ours that has not landed: one square in the trailing
      // corner, the same rectangle vocabulary as the unheard band, and the
      // one persistent surface for a send that failed (the SEND FAILED flash
      // is an edge and is gone two seconds later).
      if c.mark > 0 then
        val sq = roundI(lerp((h / 12).toDouble, (rh / 4).toDouble, o))
        if sq >= 2 then
          val mc = if c.mark == 2 then Color.red else Color.yellow
          kids = Keyed("mark", VFill(x + cw - pad - sq, y + ch - pad - sq, sq, sq,
            0, mc, Alpha.OPAQUE)) :: kids
      // the state line, which is a full-bleed affordance: in a stack row there
      // is no room for it and the roster is answering a different question.
      val stH = h / 10
      val stY = nameY + nameH + 2
      if ca > 8 && stH >= 6 && stY + stH <= y + ch && c.state != "" then
        kids = Keyed("state", VLabel(x + pad, stY, cw - 2 * pad, stH, c.state,
          TypeRole.CAPTION, TypeWeight.REGULAR, TextAlign.CENTER,
          Palette.INK, ca)) :: kids
      Some(VGroup(ListOps.reverse(kids)))

  def unheardText(n: scala.Int): String =
    if n == 1 then "1 unheard" else "" + n + " unheard"

  /** round-half-away-from-zero to a semantic pixel. A card sliding past the top
   *  of the panel has a negative y, and truncation there would make it jitter
   *  by a pixel as it crosses zero. */
  def roundI(x: scala.Double): scala.Int =
    if x >= 0.0 then (x + 0.5).toInt else -(((-x) + 0.5).toInt)

  // ---- from the snapshot ----------------------------------------------------

  /** the cards this snapshot holds, in list order. Everything a card shows is
   *  read here, so `body` is a pure function of plain values and a test can
   *  hand it three cards without building a Matrix snapshot. */
  def cards(snap: StateSnapshot, nowMs: Long, playingRoom: String,
      unsent: List[String], undelivered: List[String]): List[RoloCard] =
    var acc: List[RoloCard] = Nil
    var cur = snap.conversations
    var going = true
    while going do
      cur match
        case c :: t =>
          acc = cardOf(snap, c, nowMs, playingRoom, unsent, undelivered) :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def cardOf(snap: StateSnapshot, conv: Conversation, nowMs: Long,
      playingRoom: String, unsent: List[String],
      undelivered: List[String]): RoloCard =
    var name = WataLogic.convName(snap, conv)
    if name == "" || name == "?" then name = "Chat"
    RoloCard(WataLogic.convKey(conv), name,
      Palette.forConversation(conv.convType, conv.hasContact,
        conv.contact.user.id, conv.roomId),
      stateOf(conv, nowMs, playingRoom), conv.unplayedCount,
      WataLogic.outboxMark(unsent, undelivered, conv))

  /** the one line under a full-bleed name: what this conversation is doing, or
   *  when it last said anything. The message list is newest-first. */
  def stateOf(conv: Conversation, nowMs: Long, playingRoom: String): String =
    if playingRoom != "" && playingRoom == conv.roomId then "playing"
    else conv.messages match
      case h :: _ =>
        val a = WataLogic.ageStr(nowMs, h.timestamp)
        if a == "now" then "just now" else a + " ago"
      case Nil => "no messages"

  def cardAt(cs: List[RoloCard], i: scala.Int): Option[RoloCard] =
    var cur = cs
    var j = 0
    var out: Option[RoloCard] = None
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then
            out = Some(h)
            going = false
          else
            j += 1
            cur = t
        case Nil => going = false
    out

  def lenCards(cs: List[RoloCard]): scala.Int =
    var n = 0
    var cur = cs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n
