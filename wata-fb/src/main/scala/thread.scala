/** THE DRAWN THREAD — plan 0078: the message view in the rolodex's language.
 *
 *  Inside a person the question is *which of these have I not heard*, and the
 *  answer is drawn, not listed: each message is a BAR whose length is its
 *  duration, in the speaker's colour, growing from the left if it is theirs
 *  and from the right in white if it is mine. Duration, direction, speaker
 *  and state are all shape — the family thread needs no names at all. Six
 *  rows fit the panel with no chrome; the header line and the recording bar /
 *  flashes are the shell's and stay exactly as they were.
 *
 *  ## The geometry (this panel's numbers)
 *
 *  One fixed STAMP column on the LEFT (`STAMP_W`), the bar field between it
 *  and the delivery gutter on the right (`GUTTER_W`), six ~21 px rows under
 *  a fixed pair of white nubs at the centre band. The stamp column is on the
 *  left because the right edge already belongs to the delivery squares (my
 *  rows' root — two vocabularies on one edge would collide exactly on the
 *  rows that carry both), and my bars — the only ones rooted at the right —
 *  are the minority in a family thread, so the left column is the
 *  least-crossed ground; bars never enter it (the field starts after it).
 *
 *  A bar is `max(MIN_W, duration * usable / CAP_S)` wide, capped at the
 *  field (`CAP_S` = 30 s — a walkie-talkie clip is seconds, not minutes;
 *  both constants are panel-tuned like the type sizes were). UNHEARD is full
 *  ink with a small yellow cap block at the growing end — the card's unheard
 *  band's rectangle vocabulary; PLAYED is the same bar at a third of the ink
 *  (`THIRD_ALPHA` over the black ground). My rows draw at full ink — their
 *  state is the squares', not the bar's. NOTHING here is dimmed by centre
 *  distance: the ink level IS data (played thirds vs unheard ink), so the
 *  rolodex's quiet-neighbour treatment would destroy information. The
 *  centre's whole marking is the nubs plus the stamp treatment (the centre
 *  row's stamp at full strength, a neighbour's quieter).
 *
 *  The stamps are `wataui`'s back-off (`Stamps`) at CAPTION, right-aligned
 *  against the field so each sits beside the bar it dates; collapsed rows
 *  leave the column empty (fixed column, nothing reflows). The row that is
 *  PLAYING shows its exact hh:mm in yellow instead — the one row whose
 *  "45m" is not the question being asked.
 *
 *  My rows carry the DELIVERY SQUARES (`Delivery`) in the right gutter:
 *  hollow+hollow queued, filled+hollow once the server has it, both filled
 *  once somebody played it, one red square when it will never arrive. A
 *  queued or refused send is not in the timeline, so those rows are
 *  SYNTHESIZED at the top from the outbox marker lists the frame already
 *  carries (one unsent key per queued entry; the undelivered flag) — their
 *  duration is unknown to the UI, so they draw at `MIN_W` with an empty
 *  stamp: "something of mine, not yet with the server". The favourite star
 *  renders past the bar's outer end (the side away from its growth); the
 *  gestures that set one keep today's handling.
 *
 *  The body is PURE and shareable — `body(rows, motion, w, h)` over a
 *  `ThreadRow` list `rows(...)` reads from one snapshot — the
 *  `Rolodex.body`/`Rolodex.cards` split repeated, so the watch's copy
 *  (`WATCH-DRAWN-THREAD`) is a geometry file, not a port. The scroll is the
 *  same motion integrator as the rolodex (one detent per row); this file
 *  only reads the `Motion` it is handed. */
case class ThreadRow(
  // the differ's key: the event id, or a synthetic outbox key
  key: String,
  durationMs: Long,
  own: Boolean,
  // the bar's colour: the speaker's palette hue, white for mine
  color: scala.Int,
  // received and not yet played by me — full ink + the yellow cap
  unheard: Boolean,
  // Delivery.* — NONE on received rows
  delivery: scala.Int,
  // the collapsed back-off label ("" = the column stays empty)
  stamp: String,
  // the exact hh:mm, drawn in yellow while `playing`
  exactStamp: String,
  playing: Boolean,
  favorite: Boolean
)

object Thread:
  /** rows on the panel: six ~21 px rows is the density this screen exists
   *  for (the stack's 42 px rows would show three). */
  val VISIBLE = 6
  /** the fixed stamp column (left), sized for "20 Aug" at CAPTION 13. */
  val STAMP_W = 36
  /** the delivery gutter (right): two 5 px squares, their gaps, and the
   *  right nub's ground (the nub is 4 px at the panel edge — the squares
   *  stop short of it, or the centre row's second square would merge with
   *  the band mark). */
  val GUTTER_W = 16
  /** the length mapping: linear in duration, floored and capped. */
  val CAP_S = 30
  val MIN_W = 8
  /** bar shape. */
  val BAR_H = 11
  val BAR_RADIUS = 3
  /** the unheard cap block at the growing end, inside the bar's tip. */
  val CAP_W = 4
  /** played ink: the same bar at a third (alpha over the black ground). */
  val THIRD_ALPHA = 85
  /** a neighbour row's stamp ink; the centre row's is full strength. */
  val QUIET_STAMP_ALPHA = 150
  /** delivery square side. */
  val SQ = 5
  /** how far either side of the centre a row is worth building. */
  val REACH = 4

  def rowH(h: scala.Int): scala.Int =
    val r = h / VISIBLE
    if r < 4 then 4 else r

  def centreY(h: scala.Int): scala.Int = (h - rowH(h)) / 2

  /** the bar field's edges. */
  def fieldX0(w: scala.Int): scala.Int = STAMP_W + 2
  def fieldX1(w: scala.Int): scala.Int = w - GUTTER_W

  /** a bar's length for `ms` of audio. */
  def barW(ms: Long, w: scala.Int): scala.Int =
    val usable = fieldX1(w) - fieldX0(w)
    var out = ((ms * usable.toLong) / (CAP_S.toLong * 1000L)).toInt
    if out < MIN_W then out = MIN_W
    if out > usable then out = usable
    out

  // ---- the screen -----------------------------------------------------------

  def body(rows: List[ThreadRow], count: scala.Int, m: Motion,
      w: scala.Int, h: scala.Int): View =
    val p = Motion.offset(m)
    val centre = Motion.centre(m, count)
    var lo = centre - REACH
    if lo < 0 then lo = 0
    var hi = centre + REACH
    if hi > count - 1 then hi = count - 1
    var acc: List[Keyed] = Nil
    var i = lo
    while i <= hi do
      rowAt(rows, i) match
        case r: Some[ThreadRow] =>
          rowView(r.value, i, p, i == centre, w, h) match
            case v: Some[View] => acc = Keyed(r.value.key, v.value) :: acc
            case None          => ()
        case None => ()
      i += 1
    // the fixed centre band's nubs, last — they belong to the panel, and they
    // are what the rows move under (the rolodex's marking, always on: the
    // thread has no closed state).
    val nw = nubW(w)
    val nh = rowH(h) / 2
    val ny = centreY(h) + (rowH(h) - nh) / 2
    acc = Keyed("nub-r", VFill(w - nw, ny, nw, nh, nw / 2, Color.white, 255)) :: acc
    acc = Keyed("nub-l", VFill(0, ny, nw, nh, nw / 2, Color.white, 255)) :: acc
    VGroup(ListOps.reverse(acc))

  def nubW(w: scala.Int): scala.Int =
    val n = w / 40
    if n < 3 then 3 else n

  /** one row, or None when it is entirely off the panel. */
  def rowView(r: ThreadRow, i: scala.Int, p: scala.Double, isCentre: Boolean,
      w: scala.Int, h: scala.Int): Option[View] =
    val rh = rowH(h)
    val y = centreY(h) + roundI((i.toDouble - p) * rh.toDouble)
    if y + rh <= 0 || y >= h then None
    else
      val bw = barW(r.durationMs, w)
      val bx = if r.own then fieldX1(w) - bw else fieldX0(w)
      val by = y + (rh - BAR_H) / 2
      val ink = if r.own || r.unheard then 255 else THIRD_ALPHA
      var kids: List[Keyed] = Nil
      kids = Keyed("bar", VFill(bx, by, bw, BAR_H, BAR_RADIUS, r.color, ink)) :: kids
      // the unheard cap: a yellow block INSIDE the bar's growing tip — the
      // card's unheard-band vocabulary at row scale. Theirs grow rightward,
      // so the tip is the bar's right end.
      if r.unheard then
        val cx = if r.own then bx else bx + bw - CAP_W
        kids = Keyed("cap", VFill(cx, by, CAP_W, BAR_H, 0, Color.yellow, 255)) :: kids
      // the favourite star, past the bar's OUTER end (away from its growth)
      if r.favorite then
        val sx = if r.own then bx - Font.GLYPH_W - 3 else bx + bw + 3
        kids = Keyed("star", VGlyph(sx, y + (rh - Font.GLYPH_H) / 2,
          Font.ICON_STAR, Color.yellow)) :: kids
      // the delivery squares, my rows only, rooted in the right gutter
      if r.own && r.delivery != Delivery.NONE then
        val sy = y + (rh - SQ) / 2
        kids = squareView("sq1", Delivery.squareOne(r.delivery), w - 15, sy, kids)
        kids = squareView("sq2", Delivery.squareTwo(r.delivery), w - 9, sy, kids)
      // the stamp column: the playing row's exact time in yellow at full
      // strength; otherwise the back-off label — full strength beside the
      // centre row (its half of the centre marking), quieter elsewhere.
      val st = if r.playing then r.exactStamp else r.stamp
      if st != "" then
        val sc = if r.playing then Color.yellow else Color.white
        val sa = if r.playing || isCentre then 255 else QUIET_STAMP_ALPHA
        kids = Keyed("stamp", VLabel(2, y, STAMP_W - 4, rh, st, TypeRole.CAPTION,
          TypeWeight.MEDIUM, TextAlign.TRAILING, sc, sa)) :: kids
      Some(VGroup(ListOps.reverse(kids)))

  /** one delivery square, by its render state (Delivery.SQ_*). A hollow
   *  square is the fill plus a black core — the vocabulary has no stroke. */
  def squareView(key: String, sq: scala.Int, x: scala.Int, y: scala.Int,
      kids: List[Keyed]): List[Keyed] =
    var out = kids
    if sq == Delivery.SQ_FILLED then
      out = Keyed(key, VFill(x, y, SQ, SQ, 0, Color.white, 255)) :: out
    else if sq == Delivery.SQ_RED then
      out = Keyed(key, VFill(x, y, SQ, SQ, 0, Color.red, 255)) :: out
    else if sq == Delivery.SQ_HOLLOW then
      out = Keyed(key, VFill(x, y, SQ, SQ, 0, Color.white, 255)) :: out
      out = Keyed(key + "c", VFill(x + 1, y + 1, SQ - 2, SQ - 2, 0,
        Color.black, 255)) :: out
    out

  def roundI(x: scala.Double): scala.Int =
    if x >= 0.0 then (x + 0.5).toInt else -(((-x) + 0.5).toInt)

  // ---- from the snapshot ----------------------------------------------------

  /** how many rows the outbox contributes at the top of this conversation's
   *  thread: one per queued send, plus one while the refused marker is up.
   *  This is the offset between a row index and a timeline index — the
   *  cursor/action plumbing uses it through `WataLogic`. */
  def synthCount(conv: Conversation, unsent: List[String],
      undelivered: List[String]): scala.Int =
    var n = WataLogic.outboxSending(unsent, conv)
    if WataLogic.convKeyed(undelivered, conv) then n = n + 1
    n

  /** the whole thread, newest first: the outbox's synthetic rows, then the
   *  timeline. Everything a row shows is read here, so `body` is a pure
   *  function of plain values.
   *
   *  Speaker colours come from the SAME roster assignment the rolodex draws
   *  with (`Palette.forRoster` over the whole conversation list) — a DM's
   *  subject is its contact, so the same person is the same colour on their
   *  card and on every bar of theirs in any thread. A sender with no card
   *  (left the roster) falls back to their id's own preference. */
  def rows(snap: StateSnapshot, conv: Conversation, selfId: String, nowMs: Long,
      playingId: String, unsent: List[String],
      undelivered: List[String]): List[ThreadRow] =
    val pal = rosterPairs(snap)
    var acc: List[ThreadRow] = Nil
    // synthetic outbox rows, newest first: still-queued sends, then the
    // refused marker (the dropped send was older than anything still queued)
    val nq = WataLogic.outboxSending(unsent, conv)
    var qi = 0
    while qi < nq do
      acc = ThreadRow("queued-" + qi, 0L, true, Color.white, false,
        Delivery.QUEUED, "", "", false, false) :: acc
      qi += 1
    if WataLogic.convKeyed(undelivered, conv) then
      acc = ThreadRow("refused", 0L, true, Color.white, false,
        Delivery.REFUSED, "", "", false, false) :: acc
    var cur = conv.messages
    var going = true
    while going do
      cur match
        case m :: t =>
          acc = timelineRow(m, selfId, nowMs, playingId, pal) :: acc
          cur = t
        case Nil => going = false
    withStamps(ListOps.reverse(acc))

  def timelineRow(m: VoiceMessage, selfId: String, nowMs: Long,
      playingId: String, pal: List[HuePair]): ThreadRow =
    val own = selfId != "" && m.sender.id == selfId
    val col = if own then Color.white else colorOf(pal, m.sender.id)
    val unheard = !own && !m.isPlayed
    val delivery =
      if !own then Delivery.NONE
      else if m.playedByPeer then Delivery.PLAYED
      else Delivery.SERVER
    // the exact stamp only when the clock can vouch for it: a FUTURE
    // timestamp (the 1970-boot handset before the clock steps, and the
    // scripted harness's virtual clock against real server stamps) falls
    // back to the back-off label — the same clamp the age column has, and
    // what keeps a playing-row frame a golden.
    val exact =
      if nowMs >= m.timestamp then Stamps.exact(m.timestamp)
      else Stamps.label(nowMs, m.timestamp)
    ThreadRow(m.id, m.durationMs, own, col, unheard, delivery,
      Stamps.label(nowMs, m.timestamp), exact,
      playingId != "" && m.id == playingId, m.isFavorite)

  /** apply the collapse over the built list (render order, newest first):
   *  consecutive identical labels keep only the first. */
  def withStamps(rs: List[ThreadRow]): List[ThreadRow] =
    val collapsed = Stamps.collapse(stampsOf(rs))
    var acc: List[ThreadRow] = Nil
    var cur = rs
    var lab = collapsed
    var going = true
    while going do
      cur match
        case r :: t =>
          lab match
            case l :: lt =>
              acc = withStamp(r, l) :: acc
              lab = lt
            case Nil => acc = r :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def withStamp(r: ThreadRow, stamp: String): ThreadRow =
    ThreadRow(r.key, r.durationMs, r.own, r.color, r.unheard, r.delivery,
      stamp, r.exactStamp, r.playing, r.favorite)

  def stampsOf(rs: List[ThreadRow]): List[String] =
    var acc: List[String] = Nil
    var cur = rs
    var going = true
    while going do
      cur match
        case r :: t =>
          acc = r.stamp :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  // ---- the roster's colours -------------------------------------------------

  /** (subject id, hue) for every conversation — the rolodex's assignment,
   *  restated as pairs a sender id can be looked up in. */
  def rosterPairs(snap: StateSnapshot): List[HuePair] =
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
    val ordered = ListOps.reverse(subs)
    var cols = Palette.forRoster(ordered)
    var acc: List[HuePair] = Nil
    var ids = ordered
    var going = true
    while going do
      ids match
        case i :: t =>
          cols match
            case col :: ct =>
              acc = HuePair(i, col) :: acc
              cols = ct
            case Nil => ()
          ids = t
        case Nil => going = false
    ListOps.reverse(acc)

  def colorOf(pal: List[HuePair], id: String): scala.Int =
    var out = -1
    var cur = pal
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.id == id then
            out = h.color
            going = false
          else cur = t
        case Nil => going = false
    if out < 0 then Palette.forUser(id) else out

  def rowAt(rs: List[ThreadRow], i: scala.Int): Option[ThreadRow] =
    var cur = rs
    var j = 0
    var out: Option[ThreadRow] = None
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

  def lenRows(rs: List[ThreadRow]): scala.Int =
    var n = 0
    var cur = rs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

/** one roster subject and its assigned hue. */
case class HuePair(id: String, color: scala.Int)
