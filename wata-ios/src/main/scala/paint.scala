/** THE FRAMEBUFFER INTERPRETER — one of the backends `wataui`'s view algebra
 *  is written for, and the only one that exists today.
 *
 *  It walks a view tree and calls the SAME `Font`/`Draw` entry points the
 *  immediate-mode painter always called, in the same order, with the same
 *  arguments. That is the whole point: a screen ported from a `renderX(px,
 *  ctx)` to a `bodyX(...): View` + one `FbPaint.draw` must leave every golden
 *  frame byte-identical, and it does because nothing between the view and the
 *  pixels is new.
 *
 *  It does NOT use the differ. The frame loop clears the buffer and repaints
 *  the whole tree every frame at ~30fps, exactly as before — an edit script is
 *  for a backend that holds widgets, not for one that owns 40960 bytes.
 *
 *  Coordinates arrive as the algebra defines them: `VText` on the 26x15
 *  character grid (row 0 starts at y=1, below the 1px status line — the
 *  offset `Font.drawText` applies), everything else in panel pixels. */
object FbPaint:
  def draw(px: go.Bytes, v: View): Unit = v match
    case x: VText  => Font.drawText(px, x.text, x.col, x.row, x.color, false, 0)
    case x: VGlyph => Font.drawChar(px, x.glyph, x.x, x.y, x.color, false, 0)
    case x: VRect  => Draw.fillRect(px, x.x, x.y, x.w, x.h, x.color)
    case x: VImage => blit(px, x)
    case x: VGroup => drawChildren(px, x.children)

  /** children paint in list order, so a later one draws over an earlier one. */
  def drawChildren(px: go.Bytes, xs: List[Keyed]): Unit =
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          draw(px, h.view)
          cur = t
        case Nil => going = false

  /** an image's pixels are RGB565 LITTLE-ENDIAN pairs, row-major — the panel's
   *  own format, so this is a copy. It goes through `Draw.setPixel` rather
   *  than a raw index so an image hanging off the edge clips instead of
   *  corrupting the row below. */
  def blit(px: go.Bytes, img: VImage): Unit =
    var y = 0
    while y < img.h do
      var x = 0
      while x < img.w do
        val i = (y * img.w + x) * 2
        if i + 1 < img.pixels.length then
          Draw.setPixel(px, img.x + x, img.y + y, img.pixels(i) | (img.pixels(i + 1) << 8))
        x += 1
      y += 1

  /** the column a centered line starts at — the fb grid's own centering, the
   *  arithmetic `Font.drawTextCentered` does, lifted so a BODY can do it and
   *  hand the interpreter a plain positioned `VText`. Centering is layout, and
   *  layout is the body's job: a backend that is not a 26-column grid must be
   *  free to place the same text its own way. Counted in BYTES, like the
   *  painter — these strings are ASCII. */
  def centerCol(text: String): scala.Int =
    val nchars = go.bytes(text).length
    if nchars >= Font.COLS then 0 else (Font.COLS - nchars) / 2
