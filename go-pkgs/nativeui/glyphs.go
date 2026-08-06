// The glyph mapping: VGlyph codes into text AppKit can draw.
//
// The fb font (wata-fb/src/main/scala/display.scala, Font.glyphs) is a 5x8
// bitmap table indexed by code; codes past 0x7F are wata's hand-drawn icons,
// drawn only through drawChar, never inside a string. A native backend has
// no bitmap table, so each icon code maps to the Unicode character that
// carries the same meaning. The mapping covers every code a wata BODY
// actually emits (grep VGlyph + NetStatus.glyph); anything else renders as
// the placeholder — visibly wrong rather than silently blank, so a new icon
// code fails loudly here instead of vanishing.
//
// These are approximations by design: native fonts are not the fb font, and
// the semantic oracle for what a screen shows stays the fb goldens over the
// same bodies. ASCII 0x20..0x7E render as themselves.

package nativeui

// GlyphPlaceholder is what an unmapped glyph code renders as.
const GlyphPlaceholder = "□" // □ WHITE SQUARE

// glyphText maps the icon codes wata's bodies emit (display.scala's Font
// constants) to a one-character string.
var glyphText = map[int]string{
	0x80: "✓", // ICON_CHECK — sent/played check mark
	0x84: "≈", // ICON_WIFI3 — the wifi pipe mark (double wave)
	0x8C: "Ψ", // ICON_CELL — the cellular mast (Ψ)
	0x8D: "★", // ICON_STAR — the favorite star
	0x8E: "↑", // ICON_UNSENT — up arrow: waiting to go out
	0x8F: "⚠", // ICON_UNDELIV — warning: will never arrive
	0x90: "▶", // ICON_PLAY — playing right now
}

// GlyphString is the text a VGlyph code renders as on this backend.
func GlyphString(code int) string {
	if s, ok := glyphText[code]; ok {
		return s
	}
	if code >= 0x20 && code <= 0x7e {
		return string(rune(code))
	}
	return GlyphPlaceholder
}
