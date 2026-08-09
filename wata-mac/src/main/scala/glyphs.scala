/** The glyph mapping: VGlyph codes into text AppKit can draw.
 *
 *  The fb font (wata-fb/src/main/scala/display.scala, Font.glyphs) is a 5x8
 *  bitmap table indexed by code; codes past 0x7F are wata's hand-drawn icons,
 *  drawn only through drawChar, never inside a string. A native backend has
 *  no bitmap table, so each icon code maps to the Unicode character that
 *  carries the same meaning. The mapping covers every code a wata BODY
 *  actually emits (grep VGlyph + NetStatus.glyph); anything else renders as
 *  the placeholder — visibly wrong rather than silently blank, so a new icon
 *  code fails loudly here instead of vanishing.
 *
 *  These are approximations by design: native fonts are not the fb font, and
 *  the semantic oracle for what a screen shows stays the fb goldens over the
 *  same bodies. ASCII 0x20..0x7E render as themselves. */
object MacGlyphs:

  /** what an unmapped glyph code renders as. */
  val PLACEHOLDER = "□" // WHITE SQUARE

  /** the text a VGlyph code renders as on this backend. */
  def glyphString(code: Int): String =
    if code == 0x80 then "✓"      // ICON_CHECK — sent/played check mark
    else if code == 0x84 then "≈" // ICON_WIFI3 — the wifi pipe mark (double wave)
    else if code == 0x8C then "Ψ" // ICON_CELL — the cellular mast
    else if code == 0x8D then "★" // ICON_STAR — the favorite star
    else if code == 0x8E then "↑" // ICON_UNSENT — up arrow: waiting to go out
    else if code == 0x8F then "⚠" // ICON_UNDELIV — warning: will never arrive
    else if code == 0x90 then "▶" // ICON_PLAY — playing right now
    else if code >= 0x20 && code <= 0x7e then ascii(code)
    else PLACEHOLDER

  /** one printable ASCII code as a one-character string. */
  def ascii(code: Int): String =
    val b = new BytesBuilder
    b.addByte(code)
    b.result().utf8String
