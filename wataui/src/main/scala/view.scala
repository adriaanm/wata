/** THE VIEW ALGEBRA — a screen as data.
 *
 *  A view is a value: a pure function of the application state builds it, and
 *  a per-backend interpreter turns it into pixels (the framebuffer painter) or
 *  into retained widgets (a native toolkit). Nothing here knows about a
 *  framebuffer, a font table, a socket or a clock — this module links `core`
 *  and NOTHING else, which is exactly what lets every backend link it.
 *
 *  The vocabulary is the PAINTER's, not a layout engine's: five constructors,
 *  each one thing a backend already knows how to do. Coordinates are semantic
 *  positions, not device pixels — `VText` sits on a character grid (column,
 *  row) and `VGlyph`/`VRect`/`VImage` on a pixel grid; a backend that is not a
 *  160x128 panel scales both. Higher-level ideas (a selectable row list, a
 *  footer legend, a right-aligned mark column) are FUNCTIONS RETURNING VIEWS,
 *  not new constructors: sugar composes, and a backend only ever sees five
 *  cases.
 *
 *  Colors are RGB565 ints, the format the device panel takes; a backend on
 *  another display expands them. A color may carry an ALPHA (0..255) — the
 *  elements that take one say so; `Alpha.over` is what it means.
 */
sealed trait View derives CanEqual

/** a run of ASCII text at a character-grid cell. `text` is drawn byte by byte:
 *  a glyph past 0x7F belongs in `VGlyph`, never inside a string (it would
 *  UTF-8 encode into two bytes and two wrong glyphs). */
case class VText(col: Int, row: Int, text: String, color: Int) extends View

/** one glyph by code at a PIXEL position — the custom icons past 0x7F
 *  (battery, wifi, the cellular mast, the favorite star, the outbox and
 *  playback marks) and any single character that has to sit off the grid. */
case class VGlyph(x: Int, y: Int, glyph: Int, color: Int) extends View

/** a filled rectangle at a pixel position: a selection highlight, a recording
 *  bar, the QR block's white field. */
case class VRect(x: Int, y: Int, w: Int, h: Int, color: Int) extends View

/** a raw image blitted at a pixel position: `w * h` pixels, row-major, as
 *  RGB565 LITTLE-ENDIAN byte pairs (`w * h * 2` bytes) — the panel's own
 *  format, so the framebuffer arm is a row copy. The one image wata draws is
 *  the enrolment QR block, whose body scales the module bitmap into this
 *  buffer; a backend on another display re-expands the pairs. */
case class VImage(x: Int, y: Int, w: Int, h: Int, pixels: Bytes) extends View

/** TEXT PLACED IN PIXELS, at a type ROLE — plan 0070's rolodex cannot be
 *  drawn with `VText`, which is a run of cells on a character grid.
 *
 *  It carries a ROLE and not a point size, and that is the load-bearing
 *  choice. A size in an element would foreclose Dynamic Type (the system's
 *  preferred size is the renderer's to read, per device, per user) and force
 *  every body to know which panel it is on. A role — `TypeRole.DISPLAY`, the
 *  rolodex's full-bleed name; `NAME`, a card or row title; `CAPTION`, the
 *  secondary line; `STATUS`, the small print — keeps that knowledge in ONE
 *  place, the renderer's metrics.
 *
 *  `(x, y, w, h)` is the BOX the text is aligned within, not the text's own
 *  extent: a name optically centred in a card is the whole point of the
 *  design, and a body that had to measure a native font to centre it would be
 *  doing the renderer's job with the renderer's numbers missing. `weight` is
 *  `TypeWeight.*`; `alpha` is 0..255, `Alpha.OPAQUE` for ordinary text. */
case class VLabel(x: Int, y: Int, w: Int, h: Int, text: String,
  role: Int, weight: Int, align: Int, color: Int, alpha: Int) extends View

/** a filled rectangle with a CORNER RADIUS and an ALPHA — `VRect` with the two
 *  properties the design's depth is made of. The cards are rounded when the
 *  stack is open, and half the depth is black at 50-80% over a hue.
 *
 *  `radius` is in the same pixel space as `w`/`h` and is clamped to half the
 *  shorter side (so a radius past that is a stadium, never a defect);
 *  `radius == 0` is a plain rectangle and `alpha == Alpha.OPAQUE` a plain
 *  fill, which is exactly `VRect` — kept as a separate constructor because
 *  every body written to `VRect` (and every golden it produces) stays as it
 *  is. */
case class VFill(x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int,
  alpha: Int) extends View

/** an ordered composite. Children paint in list order, so a later child draws
 *  over an earlier one — the same rule the immediate-mode painter always had. */
case class VGroup(children: List[Keyed]) extends View

/** a child and its identity within its group. A non-empty `key` is a stable
 *  name for the thing the child shows (a room id, a contact id, a menu item
 *  id), which is what lets the differ recognize a moved row instead of
 *  rewriting every row after it. `""` means "no identity" — match me by
 *  position. */
case class Keyed(key: String, view: View) derives CanEqual

/** THE TYPE ROLES — what a piece of text IS, which is all a body knows and all
 *  a renderer needs to pick a size.
 *
 *  Four, and the set is chosen from what plan 0070's screens actually say:
 *  DISPLAY is the one full-bleed name a rolodex card shows and nothing else
 *  ever is; NAME titles a card or a row; CAPTION is the secondary line under
 *  it; STATUS is the small print (the header, the legend, a count). A renderer
 *  resolves each against its metrics — on Apple against the system's preferred
 *  size, which is what Dynamic Type and VoiceOver need; on the handset against
 *  the baked strikes it ships. */
object TypeRole:
  val DISPLAY = 0
  val NAME = 1
  val CAPTION = 2
  val STATUS = 3

  def show(r: Int): String =
    if r == DISPLAY then "display"
    else if r == CAPTION then "caption"
    else if r == STATUS then "status"
    else "name"

/** the weights the vocabulary admits. Three, because a renderer with one
 *  strike per size can honour at most a couple and a design that needs five is
 *  a design the handset cannot draw. */
object TypeWeight:
  val REGULAR = 0
  val MEDIUM = 1
  val BOLD = 2

  def show(w: Int): String =
    if w == MEDIUM then "medium" else if w == BOLD then "bold" else "regular"

/** where the text sits in its box. LEADING/TRAILING rather than left/right:
 *  the box is the unit, and the renderer decides which edge that is. */
object TextAlign:
  val LEADING = 0
  val CENTER = 1
  val TRAILING = 2

  def show(a: Int): String =
    if a == CENTER then "center" else if a == TRAILING then "trailing" else "leading"

/** ALPHA — a colour's coverage, 0 (invisible) to 255 (opaque).
 *
 *  Kept as an int rather than a fraction so two views compare exactly (the
 *  differ's field comparison is the whole reason) and so a framebuffer's blend
 *  is integer arithmetic. `over` is what alpha MEANS in this algebra: source
 *  over destination in RGB565, which every backend must agree on — the
 *  framebuffer does it per pixel, a toolkit hands the same numbers to a
 *  compositor. */
object Alpha:
  val OPAQUE = 255
  val CLEAR = 0

  def clamp(a: Int): Int = if a < 0 then 0 else if a > 255 then 255 else a

  /** 0.0 .. 1.0, for a toolkit whose colours take a fraction. */
  def fraction(a: Int): Double = clamp(a).toDouble / 255.0

  /** `src` at coverage `a` composited over `dst`, both RGB565. */
  def over(dst: Int, src: Int, a0: Int): Int =
    val a = clamp(a0)
    var out = dst
    if a >= 255 then out = src
    else if a > 0 then
      val ia = 255 - a
      val r = (((src >> 11) & 0x1f) * a + ((dst >> 11) & 0x1f) * ia) / 255
      val g = (((src >> 5) & 0x3f) * a + ((dst >> 5) & 0x3f) * ia) / 255
      val bl = ((src & 0x1f) * a + (dst & 0x1f) * ia) / 255
      out = (r << 11) | (g << 5) | bl
    out

/** the view-level list helpers the algebra needs. Keyed-specific rather than
 *  generic on purpose: these are the only lists in the module, and the
 *  concrete shape is the one the emitter has no room to guess at. */
object Views:
  /** a group of unkeyed children, in paint order. */
  def group(children: List[View]): View =
    var out: List[Keyed] = Nil
    var cur = children
    var going = true
    while going do
      cur match
        case h :: t =>
          out = Keyed("", h) :: out
          cur = t
        case Nil => going = false
    VGroup(ListOps.reverse(out))

  def len(xs: List[Keyed]): Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n += 1
          cur = t
        case Nil => going = false
    n

  /** the `i`th child; an out-of-range index answers an empty placeholder
   *  rather than throwing — every caller here has already bounds-checked, and
   *  a total function keeps the walk free of exception plumbing. */
  def nth(xs: List[Keyed], i: Int): Keyed =
    var cur = xs
    var j = 0
    var out = Keyed("", VGroup(Nil))
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then
            out = h
            going = false
          else
            j += 1
            cur = t
        case Nil => going = false
    out

  def insertAt(xs: List[Keyed], i: Int, k: Keyed): List[Keyed] =
    var acc: List[Keyed] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then acc = h :: (k :: acc)
          else acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    if i >= j then acc = k :: acc
    ListOps.reverse(acc)

  def removeAt(xs: List[Keyed], i: Int): List[Keyed] =
    var acc: List[Keyed] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j != i then acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def setAt(xs: List[Keyed], i: Int, k: Keyed): List[Keyed] =
    var acc: List[Keyed] = Nil
    var cur = xs
    var j = 0
    var going = true
    while going do
      cur match
        case h :: t =>
          if j == i then acc = k :: acc else acc = h :: acc
          j += 1
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  /** structural equality over the whole tree — the differ's explicit,
   *  auditable comparator. The generated `==` now agrees (Bytes maps to
   *  content equality, `bytes.Equal`; verified by diff-spike's eq arm);
   *  this stays as the hand-written spelling the differ is read against,
   *  not as a workaround. */
  def eqView(a: View, b: View): Boolean =
    var out = false
    a match
      case x: VText => b match
        case y: VText => out = x.col == y.col && x.row == y.row && x.text == y.text && x.color == y.color
        case _        => out = false
      case x: VGlyph => b match
        case y: VGlyph => out = x.x == y.x && x.y == y.y && x.glyph == y.glyph && x.color == y.color
        case _         => out = false
      case x: VRect => b match
        case y: VRect => out = x.x == y.x && x.y == y.y && x.w == y.w && x.h == y.h && x.color == y.color
        case _        => out = false
      case x: VImage => b match
        case y: VImage =>
          out = x.x == y.x && x.y == y.y && x.w == y.w && x.h == y.h && eqBytes(x.pixels, y.pixels)
        case _ => out = false
      case x: VLabel => b match
        case y: VLabel =>
          out = x.x == y.x && x.y == y.y && x.w == y.w && x.h == y.h &&
            x.text == y.text && x.role == y.role && x.weight == y.weight &&
            x.align == y.align && x.color == y.color && x.alpha == y.alpha
        case _ => out = false
      case x: VFill => b match
        case y: VFill =>
          out = x.x == y.x && x.y == y.y && x.w == y.w && x.h == y.h &&
            x.radius == y.radius && x.color == y.color && x.alpha == y.alpha
        case _ => out = false
      case x: VGroup => b match
        case y: VGroup => out = eqChildren(x.children, y.children)
        case _         => out = false
    out

  def eqChildren(a: List[Keyed], b: List[Keyed]): Boolean =
    var xs = a
    var ys = b
    var out = true
    var going = true
    while going do
      xs match
        case xh :: xt => ys match
          case yh :: yt =>
            if xh.key != yh.key || !eqView(xh.view, yh.view) then
              out = false
              going = false
            else
              xs = xt
              ys = yt
          case Nil =>
            out = false
            going = false
        case Nil =>
          ys match
            case _ :: _ => out = false
            case Nil    => ()
          going = false
    out

  def eqBytes(a: Bytes, b: Bytes): Boolean =
    var out = a.length == b.length
    if out then
      var i = 0
      while i < a.length do
        if a(i) != b(i) then out = false
        i += 1
    out
