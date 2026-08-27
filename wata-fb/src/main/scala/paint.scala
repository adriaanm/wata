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
    case x: VFill  => Draw.fillRoundRect(px, x.x, x.y, x.w, x.h, x.radius, x.color, x.alpha)
    case x: VLabel => drawLabel(px, x)
    case x: VGroup => drawChildren(px, x.children)

  /** a `VLabel` on the framebuffer: role-aware since plan 0077 stage 1.
   *
   *  The ROLE picks a strike through `FbTypeRoles` — rasterised type at the
   *  size the role means, blended as coverage x label alpha x colour over
   *  whatever is under it (a card colour, usually). The label's box is a hard
   *  CLIP: a run wider than its box truncates at the edge, mid-glyph if it
   *  must — a label may never overflow itself (the watch learned this).
   *  `TextAlign` is honoured against the MEASURED width, and the line is
   *  vertically centred by the strike's ascent+descent.
   *
   *  `STATUS` — and any role the strike table cannot serve — resolves to no
   *  strike and keeps the 5x8 grid-font path below verbatim, so that gap is
   *  stated here rather than hidden: small print still draws exactly as it
   *  did before the strikes existed (including its overflow-to-the-right,
   *  which the goldens pin). */
  def drawLabel(px: go.Bytes, l: VLabel): Unit =
    // DISPLAY resolves per text through the fit-down ladder, against the
    // label's own box width — the same call the rolodex body sized the box
    // with, so the drawn rung and the box that holds it cannot disagree.
    val s =
      if l.role == TypeRole.DISPLAY then FbTypeRoles.displayStrikeFor(l.text, l.w)
      else FbTypeRoles.strikeFor(l.role, l.weight)
    if s < 0 then drawLabelGrid(px, l)
    else
      val tw = go.strikes.measureText(s, l.text)
      var x0 = l.x
      if l.align == TextAlign.CENTER then x0 = l.x + (l.w - tw) / 2
      else if l.align == TextAlign.TRAILING then x0 = l.x + l.w - tw
      val asc = go.strikes.ascent(s)
      val base = l.y + (l.h - (asc + go.strikes.descent(s))) / 2 + asc
      val bs = go.bytes(l.text)
      // the pen runs in 26.6 fixed point so the FRACTIONAL advances the
      // rasteriser keeps (HintingNone — see go-pkgs/strikes) accumulate; each
      // glyph lands at the pen's FLOOR pixel in the x-PHASE nearest the pen's
      // fraction (the strikes carry four quarter-pixel rasters per glyph —
      // owner-approved subpixel positioning, 2026-08-27), so the residual
      // quantisation per glyph is ±1/8 px and word gaps read optically even.
      var pen = x0 * 64
      var i = 0
      while i < bs.length do
        val ch = bs(i).toInt & 0xff
        // nearest quarter: q in 0..4, where 4 carries into the next pixel
        val fl = floor64(pen)
        val q = (pen - fl * 64 + 8) / 16
        drawStrikeChar(px, s, ch, (q & 3).toInt, fl + (q >> 2), base, l)
        pen = pen + go.strikes.advance64(s, ch)
        i = i + 1

  /** 26.6 floor toward negative infinity (a centre/trailing-aligned overlong
   *  run can put the pen left of zero; truncation there would jitter). */
  def floor64(v: scala.Int): scala.Int =
    var out = v / 64
    if v < 0 && out * 64 != v then out = out - 1
    out

  /** one strike glyph at pen floor `penX`, x-phase `phase`, baseline `base`,
   *  clipped to the label's box and blended per coverage pixel. Correct for
   *  arbitrary ink over arbitrary ground — black over a card colour is
   *  merely the common case. */
  def drawStrikeChar(px: go.Bytes, s: scala.Int, ch: scala.Int, phase: scala.Int,
      penX: scala.Int, base: scala.Int, l: VLabel): Unit =
    val gw = go.strikes.glyphW(s, ch, phase)
    val gh = go.strikes.glyphH(s, ch, phase)
    if gw > 0 && gh > 0 then
      val gx = penX + go.strikes.glyphLeft(s, ch, phase)
      val gy = base - go.strikes.glyphTop(s, ch, phase)
      val cov = go.strikes.cover(s, ch, phase)
      var row = 0
      while row < gh do
        val y = gy + row
        if y >= l.y && y < l.y + l.h then
          var col = 0
          while col < gw do
            val x = gx + col
            if x >= l.x && x < l.x + l.w then
              val c = cov(row * gw + col).toInt & 0xff
              if c > 0 then Draw.blendPixel(px, x, y, l.color, c * l.alpha / 255)
            col = col + 1
        row = row + 1

  /** the strike-less fallback: the 5x8 grid font placed in the box — exactly
   *  the pre-strike `VLabel` arm. The box and alignment are honoured (text
   *  placed in PIXELS, free of the character grid) and the alpha blended per
   *  lit pixel; a run wider than its box overflows to the right, the same
   *  thing `Font.drawText` does at the panel's edge. */
  def drawLabelGrid(px: go.Bytes, l: VLabel): Unit =
    val bs = go.bytes(l.text)
    val tw = bs.length * Font.GLYPH_W
    var x0 = l.x
    if l.align == TextAlign.CENTER then x0 = l.x + (l.w - tw) / 2
    else if l.align == TextAlign.TRAILING then x0 = l.x + l.w - tw
    val y0 = l.y + (l.h - Font.GLYPH_H) / 2
    var i = 0
    while i < bs.length do
      Font.drawCharAlpha(px, bs(i).toInt & 0xff, x0 + i * Font.GLYPH_W, y0, l.color, l.alpha)
      i = i + 1

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
