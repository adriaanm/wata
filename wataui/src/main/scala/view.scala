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
 *  another display expands them.
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

/** an ordered composite. Children paint in list order, so a later child draws
 *  over an earlier one — the same rule the immediate-mode painter always had. */
case class VGroup(children: List[Keyed]) extends View

/** a child and its identity within its group. A non-empty `key` is a stable
 *  name for the thing the child shows (a room id, a contact id, a menu item
 *  id), which is what lets the differ recognize a moved row instead of
 *  rewriting every row after it. `""` means "no identity" — match me by
 *  position. */
case class Keyed(key: String, view: View) derives CanEqual

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

  /** structural equality over the whole tree. The generated `==` is not
   *  leaned on: `VImage` carries a `Bytes`, whose identity is not its
   *  contents. */
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
