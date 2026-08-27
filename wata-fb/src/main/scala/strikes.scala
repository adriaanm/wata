package go

/** `go.strikes` — the APP-OWNED facade for `go-pkgs/strikes`, the pure-Go
 *  boot-time type rasteriser (plan 0077 stage 1; the same `@go.bind` pattern
 *  as `go.qr`/`go.httpc`). A STRIKE is one face at one pixel size: coverage
 *  bitmaps plus advance/bearing tables for printable ASCII, rasterised lazily
 *  once per strike Go-side (`x/image/font/opentype` at HintingNone — the
 *  package comment there owns the why). A strike is named by a small int id
 *  from the table the Go side owns; `FbTypeRoles` is the only caller of
 *  `strike`, and the painter's glyph loop does the per-glyph calls — ordinary
 *  Go calls in the emitted app, so there is nothing to batch. */
@go.bind("github.com/adriaanm/wata/go-pkgs/strikes")
object strikes:
  /** `strikes.Strike(face, px, weight)` — the strike id, or -1 for a
   *  combination the table does not carry. face "atkinson",
   *  weight "bold"|"medium". */
  @go.name("Strike") def strike(face: String, px: scala.Int, weight: String): scala.Int = ???
  /** pixels above / below the baseline — the line box a centring uses. */
  @go.name("Ascent") def ascent(id: scala.Int): scala.Int = ???
  @go.name("Descent") def descent(id: scala.Int): scala.Int = ???
  /** the text's advance width in pixels (fractional advances summed, rounded
   *  once) — what `TextAlign` aligns by. */
  @go.name("MeasureText") def measureText(id: scala.Int, text: String): scala.Int = ???
  /** ch's advance in 26.6 fixed point — the pen accumulates these and rounds
   *  per glyph, so word gaps stay even. Phase-independent. */
  @go.name("Advance64") def advance64(id: scala.Int, ch: scala.Int): scala.Int = ???
  /** ch's coverage box (w*h), left bearing from the pen, and top edge above
   *  the baseline — all AT x-phase `phase` (0..3, quarter-pixel raster
   *  origins; the painter picks the phase nearest the pen's fraction). */
  @go.name("GlyphW") def glyphW(id: scala.Int, ch: scala.Int, phase: scala.Int): scala.Int = ???
  @go.name("GlyphH") def glyphH(id: scala.Int, ch: scala.Int, phase: scala.Int): scala.Int = ???
  @go.name("GlyphLeft") def glyphLeft(id: scala.Int, ch: scala.Int, phase: scala.Int): scala.Int = ???
  @go.name("GlyphTop") def glyphTop(id: scala.Int, ch: scala.Int, phase: scala.Int): scala.Int = ???
  /** ch's coverage bitmap at x-phase `phase`: glyphW*glyphH bytes, row-major,
   *  0..255. The Go-side buffer, NOT a copy — read-only by contract. */
  @go.name("Cover") def cover(id: scala.Int, ch: scala.Int, phase: scala.Int): go.Bytes = ???
  /** 16 hex digits over the strike's metrics + coverage — the fb-smoke
   *  selfcheck's byte-determinism witness. */
  @go.name("Digest") def digest(id: scala.Int): String = ???
