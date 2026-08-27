import language.experimental.saferExceptions

/** THE ROLODEX — plan 0070's contact screen, on the handset (plan 0077
 *  stage 3). The watch's `rolodex.scala` restated for a LANDSCAPE panel;
 *  the design language is identical, the geometry fractions are this
 *  panel's own (they are the only thing the two files differ in — see the
 *  duplication note in plan 0077: unifying them waits for the iOS client).
 *
 *  At rest the screen is ONE CONTACT, full bleed, in that person's own colour:
 *  their name as large as the panel allows, one line of state, nothing else.
 *  Navigating shrinks the card to reveal it was always one of a vertical
 *  stack, and the stack scrolls under a fixed centre band. 450 ms after the
 *  last input the centre card grows back to full bleed.
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
 *  flick starts. At `o = 1` it is THREE rows under a fixed centre band:
 *  160×128 is wider than tall, and three 42 px rows is what gives the open
 *  stack bigger absolute type than the watch's five — which is the point of
 *  landing this design on the handset at all. The other fractions are
 *  re-derived from this panel's height rather than copied (the watch's were
 *  portrait choices): the full-bleed name box is h/3 (the DISPLAY strike
 *  sits in 42 px), the unheard band h/6 (holds a 13 px caption), and the
 *  state line h/6 — the NAME strike's line box is 21 px, and h/6 is exactly
 *  that on 128 px (h/8 = 16 clips five pixels of it).
 *
 *  ## Which card the talk button reaches
 *
 *  An open stack of same-sized rows says nothing about which one a held talk
 *  button will send to, and that is the one thing this screen must never leave
 *  ambiguous. So the CENTRE card — `Motion.centre`, the very value the frame
 *  pump writes into `selected` and `pttPress` reads back, not an
 *  approximation of it — is drawn differently from its neighbours, three ways
 *  at once so no single one of them has to carry it on a small dim panel:
 *
 *   - the neighbours are **inset**, horizontally by another `padOpen` and
 *     vertically by `QUIET_INSET`, so the centre card is visibly the widest and
 *     tallest row on the panel;
 *   - the neighbours are **dimmed** to `QUIET_ALPHA` over the black panel,
 *     which is what makes the centre card the brightest thing on screen even
 *     when the colours differ wildly in luminance;
 *   - the centre card keeps the `name` type role and bold weight while a
 *     neighbour drops to `caption`.
 *
 *  And the band the cards move under is DRAWN — a pair of white nubs at the
 *  panel's edges, fixed in panel space, fading in with `o`. That is what makes
 *  the treatment survive mid-scroll: the emphasis flips at the half-card mark,
 *  exactly where `Motion.centre` flips, and the nubs say where the flip
 *  happens rather than leaving a partly-aligned card to be judged by eye.
 *
 *  All three effects are scaled by `o`, so the closed card is untouched and the
 *  stack opens INTO the emphasis rather than snapping into it.
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
 *  state line is `name` at medium weight — at rest one contact owns the whole
 *  panel, so the supporting line has breathing room and takes real size
 *  (caption read as illegible on the physical panel, owner 2026-08-27); it
 *  only ever draws at/near full bleed, fading out with openness well before
 *  the stack rows exist, so no row form has to hold it. The renderer resolves
 *  each role against this panel's strikes (`FbTypeRoles`), which is the one
 *  place that knows how big the panel is.
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
  /** how many rows the stack shows: the centre band and one neighbour each
   *  side. A property of the panel's shape — on 128 px of height, three
   *  ~42 px rows is what keeps the open stack's type BIGGER than the
   *  watch's, not smaller; five rows here would be 25 px slivers. */
  val VISIBLE = 3
  /** the black gutter between stack rows, in panel pixels. */
  val GAP = 2
  /** how far either side of the centre a card can be and still be worth
   *  building. At `VISIBLE = 3` one would do; two keeps a card that is
   *  sliding in from off-panel mid-flick. */
  val REACH = 2

  def rowH(h: scala.Int): scala.Int =
    val r = h / VISIBLE
    if r < 4 then 4 else r

  def centreY(h: scala.Int): scala.Int = (h - rowH(h)) / 2

  /** the side margin a stack row takes, so the rows read as cards rather than
   *  as a striped panel. Zero when the card is full bleed. */
  def padOpen(w: scala.Int): scala.Int =
    val p = w / 26
    if p < 2 then 2 else p

  /** how far a card that is NOT the centre one is inset, top and bottom. It is
   *  small on purpose: this is the differential that reads at a glance without
   *  making the stack look like it lost a row. */
  val QUIET_INSET = 3

  /** what a neighbour's colour is worth against the black panel. Not lower:
   *  the ink on these cards is BLACK, so dimming the card dims the contrast the
   *  name is read through — at half strength a neighbour's name is nearly
   *  unreadable, and the roster is the whole reason the stack opened. This is
   *  the point where the centre card is still plainly the bright one and every
   *  neighbour still carries its name at better than 5:1. */
  val QUIET_ALPHA = 0.65

  /** the fixed centre band's marks: a nub at each panel edge, half a row tall,
   *  which is what the cards move UNDER. */
  def nubW(w: scala.Int): scala.Int =
    val n = w / 40
    if n < 3 then 3 else n

  def lerp(a: scala.Double, b: scala.Double, t: scala.Double): scala.Double =
    a + (b - a) * t

  def clamp01(x: scala.Double): scala.Double =
    if x < 0.0 then 0.0 else if x > 1.0 then 1.0 else x

  /** 0..255 from a 0..1 coverage. */
  def alphaOf(x: scala.Double): scala.Int = (clamp01(x) * 255.0 + 0.5).toInt

  // ---- the screen -----------------------------------------------------------

  /** the whole rolodex, as data. `cards` is the list in list order, `m` says
   *  where it is and how open it is, and `w`/`h` are the panel. */
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
          cardView(c.value, i, p, o, i == centre, w, h) match
            case v: Some[View] => acc = Keyed(c.value.key, v.value) :: acc
            case None          => ()
        case None => ()
      i += 1
    // the fixed centre band, LAST so it is on top of whatever is sliding under
    // it. It belongs to the panel and not to a card: that is what makes it a
    // band rather than a highlight, and it is the only thing on this screen
    // that does not move.
    val na = alphaOf(o)
    if na > 8 then
      val nw = nubW(w)
      val nh = rowH(h) / 2
      val ny = centreY(h) + (rowH(h) - nh) / 2
      val r = nw / 2
      acc = Keyed("nub-r", VFill(w - nw, ny, nw, nh, r, Color.white, na)) :: acc
      acc = Keyed("nub-l", VFill(0, ny, nw, nh, r, Color.white, na)) :: acc
    VGroup(ListOps.reverse(acc))

  /** one card, or None when it is entirely off the panel — culling here rather
   *  than letting the painter walk a view nobody can see keeps the tree the
   *  size of what actually shows (at rest it is a single card). */
  def cardView(c: RoloCard, i: scala.Int, p: scala.Double, o: scala.Double,
      isCentre: Boolean, w: scala.Int, h: scala.Int): Option[View] =
    val rh = rowH(h)
    // how much this card is QUIETED: zero for the centre one at any zoom, and
    // zero for everyone while the stack is closed, growing to one as it opens.
    val quiet = if isCentre then 0.0 else o
    val inY = roundI(quiet * QUIET_INSET.toDouble)
    val off = i.toDouble - p
    val yF = lerp(off * h.toDouble, centreY(h).toDouble + off * rh.toDouble, o)
    val hF = lerp(h.toDouble, (rh - GAP).toDouble, o)
    val y = roundI(yF) + inY
    val ch = roundI(hF) - 2 * inY
    if y + ch <= 0 || y >= h || ch < 2 then None
    else
      val x = roundI((o + quiet) * padOpen(w).toDouble)
      val cw = w - 2 * x
      val radius = roundI(o * (rh / 3).toDouble)
      // a neighbour's colour, at QUIET_ALPHA over the black panel. This is what
      // makes the centre card the brightest thing on the screen whatever the
      // two hues are, which a size differential alone cannot promise.
      val ca0 = alphaOf(1.0 - quiet * (1.0 - QUIET_ALPHA))
      var kids: List[Keyed] = Nil
      kids = Keyed("card", VFill(x, y, cw, ch, radius, c.color, ca0)) :: kids
      // the unheard band, which becomes the unheard bar as the stack opens
      val bandH = if c.unheard > 0 then roundI(lerp((h / 6).toDouble, 3.0, o)) else 0
      if bandH >= 2 then
        kids = Keyed("band", VFill(x, y, cw, bandH, radius, Color.yellow, ca0)) :: kids
      val pad = 2 + roundI(o * padOpen(w).toDouble)
      // the name, optically centred in the card — a body cannot centre what it
      // cannot measure, so the element carries the BOX and the renderer places
      // the text in it.
      val nameH = roundI(lerp((h / 3).toDouble, (rh - GAP - 2).toDouble, o)) - 2 * inY
      val nameY = y + (ch - nameH) / 2
      val open = o >= 0.5
      val quietly = open && !isCentre
      val role =
        if !open then TypeRole.DISPLAY
        else if isCentre then TypeRole.NAME
        else TypeRole.CAPTION
      val weight = if quietly then TypeWeight.MEDIUM else TypeWeight.BOLD
      val align = if open then TextAlign.LEADING else TextAlign.CENTER
      kids = Keyed("name", VLabel(x + pad, nameY, cw - 2 * pad, nameH, c.name,
        role, weight, align, Palette.INK, ca0)) :: kids
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
            0, mc, ca0)) :: kids
      // the state line, which is a full-bleed affordance: in a stack row there
      // is no room for it and the roster is answering a different question. At
      // rest one contact owns the whole panel, so the supporting line takes
      // the NAME role at real size rather than a caption (which read as
      // illegible on the physical panel — owner, 2026-08-27). The strip is
      // h/6: the NAME strike's line box is 21 px, exactly h/6 on 128 px (h/8
      // clips its descenders). Its fade is its OWN, faster than the count's:
      // gone by o = 1/3, safely before the shrinking card stops fitting the
      // strip (the fit gate below culls at o ~ 0.47 — on the count's fade
      // that cull would land at full alpha and pop).
      val sa = alphaOf(1.0 - o * 3.0)
      val stH = h / 6
      val stY = nameY + nameH + 2
      if sa > 8 && stH >= 6 && stY + stH <= y + ch && c.state != "" then
        kids = Keyed("state", VLabel(x + pad, stY, cw - 2 * pad, stH, c.state,
          TypeRole.NAME, TypeWeight.MEDIUM, TextAlign.CENTER,
          Palette.INK, sa)) :: kids
      Some(VGroup(ListOps.reverse(kids)))

  def unheardText(n: scala.Int): String =
    if n == 1 then "1 unheard" else "" + n + " unheard"

  /** round-half-away-from-zero to a panel pixel. A card sliding past the top
   *  of the panel has a negative y, and truncation there would make it jitter
   *  by a pixel as it crosses zero. */
  def roundI(x: scala.Double): scala.Int =
    if x >= 0.0 then (x + 0.5).toInt else -(((-x) + 0.5).toInt)

  // ---- from the snapshot ----------------------------------------------------

  /** the cards this snapshot holds, in list order. Everything a card shows is
   *  read here, so `body` is a pure function of plain values and a test can
   *  hand it three cards without building a Matrix snapshot.
   *
   *  The colours are taken for the WHOLE roster at once (`Palette.forRoster`)
   *  rather than one conversation at a time: eight hues and a per-id hash makes
   *  two of five contacts the same colour about four times in five, and a card
   *  that is not a distinguishable colour is a card that says nothing. */
  def cards(snap: StateSnapshot, nowMs: Long, playingRoom: String,
      unsent: List[String], undelivered: List[String]): List[RoloCard] =
    var subs: List[String] = Nil
    var cs = snap.conversations
    var g0 = true
    while g0 do
      cs match
        case c :: t =>
          subs = Palette.subjectOf(c.convType, c.hasContact,
            c.contact.user.id, c.roomId) :: subs
          cs = t
        case Nil => g0 = false
    var cols = Palette.forRoster(ListOps.reverse(subs))
    var acc: List[RoloCard] = Nil
    var cur = snap.conversations
    var going = true
    while going do
      cur match
        case c :: t =>
          cols match
            case col :: ct =>
              acc = cardOf(snap, c, col, nowMs, playingRoom, unsent, undelivered) :: acc
              cols = ct
            case Nil => ()
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def cardOf(snap: StateSnapshot, conv: Conversation, color: scala.Int,
      nowMs: Long, playingRoom: String, unsent: List[String],
      undelivered: List[String]): RoloCard =
    var name = WataLogic.convName(snap, conv)
    if name == "" || name == "?" then name = "Chat"
    RoloCard(WataLogic.convKey(conv), name, color,
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
