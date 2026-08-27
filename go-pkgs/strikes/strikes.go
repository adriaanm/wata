// Package strikes rasterises the handset's type at boot: a STRIKE is one
// face at one pixel size as renderable data — per-glyph alpha-coverage
// bitmaps plus advance/bearing tables, printable ASCII (0x20..0x7E), the
// format plan 0077 stage 1 makes the painter's interface.
//
// Pure Go, no cgo: golang.org/x/image/font/opentype, an ORDINARY fetched Go
// dependency (the plan-0014 pattern go-pkgs/qr opened). Rasterisation is at
// font.HintingNone, and that is deliberate, NEVER HintingFull: x/image has no
// hinter — HintingFull only quantises advances to whole pixels, which at
// 11 px opens uneven word gaps while sharpening nothing (the plan-0077 probe,
// 2026-08-27). Glyphs are rendered once at an integer dot and placed at
// rounded pen positions; the pen itself accumulates FRACTIONAL advances
// (26.6 fixed point), so rounding error distributes per glyph instead of
// piling up per word.
//
// The strike table is owned here: a strike is named by a small integer id
// resolved by Strike(face, px, weight). Each entry rasterises lazily, once
// (sync.Once) — a face nobody looks up costs only its embedded ttf bytes,
// which is what lets both faces ship for the owner's on-panel A/B.
//
// Everything crossing the boundary is plain ints, strings and []byte — the
// shapes the Sgola facade binds. Per-glyph calls are ordinary Go calls from
// the emitted app, so there is no batching layer.
//
// The output is byte-deterministic across darwin/linux (integer fixed-point
// rasteriser, no platform text stack): Digest pins that, and it is what makes
// future golden frames drawn with these strikes portable across hosts.
package strikes

import (
	_ "embed"
	"fmt"
	"sync"

	"golang.org/x/image/font"
	"golang.org/x/image/font/opentype"
	"golang.org/x/image/math/fixed"
	"image"
	"image/draw"
)

//go:embed fonts/Inter-Bold.ttf
var interBold []byte

//go:embed fonts/Inter-Medium.ttf
var interMedium []byte

//go:embed fonts/AtkinsonHyperlegible-Bold.ttf
var atkinsonBold []byte

// Atkinson Hyperlegible ships no Medium cut; its "medium" strikes rasterise
// the Regular ttf (fonts/README).
//
//go:embed fonts/AtkinsonHyperlegible-Regular.ttf
var atkinsonRegular []byte

const (
	glyphLo = 0x20 // first printable ASCII
	glyphHi = 0x7e // last
	nGlyphs = glyphHi - glyphLo + 1
)

// glyph is one rasterised glyph: a w*h coverage bitmap (row-major, one byte
// per pixel, 0..255) drawn at (penX+left, baselineY-top), advancing the pen
// by adv64 (26.6 fixed point).
type glyph struct {
	w, h  int
	left  int // horizontal bearing: box left edge relative to the pen
	top   int // vertical bearing: box top edge ABOVE the baseline
	adv64 int
	cov   []byte
}

// strike is one table entry. face/px/weight are the lookup key; the rest
// fills in on first use.
type strike struct {
	face   string
	px     int
	weight string
	ttf    []byte

	once    sync.Once
	ok      bool
	ascent  int // pixels above the baseline (ceil)
	descent int // pixels below the baseline (ceil, positive)
	glyphs  [nGlyphs]glyph
}

// The table: every (face, px, weight) the role table can ask for, both faces
// so the A/B face switch is a config change, not a rebuild. Ids are the
// slice indices and are stable only within a process — nothing persists them.
var table = []*strike{
	{face: "inter", px: 30, weight: "bold", ttf: interBold},
	{face: "inter", px: 16, weight: "bold", ttf: interBold},
	{face: "inter", px: 16, weight: "medium", ttf: interMedium},
	{face: "inter", px: 13, weight: "medium", ttf: interMedium},
	{face: "atkinson", px: 30, weight: "bold", ttf: atkinsonBold},
	{face: "atkinson", px: 16, weight: "bold", ttf: atkinsonBold},
	{face: "atkinson", px: 16, weight: "medium", ttf: atkinsonRegular},
	{face: "atkinson", px: 13, weight: "medium", ttf: atkinsonRegular},
	// the full-bleed DISPLAY ladder's other rungs (38 is the resting card's
	// first choice, 24 the floor; 30 above is the middle rung) — appended so
	// the earlier ids keep their positions, and boot-lazy like everything
	// else, so a rung no name ever needs costs only these table rows.
	{face: "inter", px: 38, weight: "bold", ttf: interBold},
	{face: "inter", px: 24, weight: "bold", ttf: interBold},
	{face: "atkinson", px: 38, weight: "bold", ttf: atkinsonBold},
	{face: "atkinson", px: 24, weight: "bold", ttf: atkinsonBold},
}

// Strike resolves (face, px, weight) to a strike id, or -1 if the table has
// no such entry. Cheap (a scan of 8), callable per label per frame.
func Strike(face string, px int, weight string) int {
	for i, s := range table {
		if s.face == face && s.px == px && s.weight == weight {
			return i
		}
	}
	return -1
}

// at returns the rasterised entry for id, or nil for a bad id or a strike
// whose rasterisation failed (embedded ttfs make the latter a build defect;
// the fb-smoke selfcheck is what catches it loudly).
func at(id int) *strike {
	if id < 0 || id >= len(table) {
		return nil
	}
	s := table[id]
	s.once.Do(s.rasterise)
	if !s.ok {
		return nil
	}
	return s
}

func (s *strike) rasterise() {
	f, err := opentype.Parse(s.ttf)
	if err != nil {
		return
	}
	// HintingNone — see the package comment; HintingFull is a trap here.
	face, err := opentype.NewFace(f, &opentype.FaceOptions{
		Size: float64(s.px), DPI: 72, Hinting: font.HintingNone,
	})
	if err != nil {
		return
	}
	defer face.Close()
	met := face.Metrics()
	s.ascent = met.Ascent.Ceil()
	s.descent = met.Descent.Ceil()
	// An integer dot far from the origin keeps every glyph box positive; the
	// bearings below are relative to it.
	dot := fixed.P(100, 100)
	for c := glyphLo; c <= glyphHi; c++ {
		dr, mask, maskp, adv, ok := face.Glyph(dot, rune(c))
		g := &s.glyphs[c-glyphLo]
		if !ok {
			continue
		}
		w, h := dr.Dx(), dr.Dy()
		g.w, g.h = w, h
		g.left = dr.Min.X - 100
		g.top = 100 - dr.Min.Y
		g.adv64 = int(adv)
		if w > 0 && h > 0 {
			// copy the mask into a tight one-byte-per-pixel buffer
			al := image.NewAlpha(image.Rect(0, 0, w, h))
			draw.DrawMask(al, al.Bounds(), image.NewUniform(image.White),
				image.Point{}, mask, maskp, draw.Src)
			cov := make([]byte, 0, w*h)
			for y := 0; y < h; y++ {
				cov = append(cov, al.Pix[y*al.Stride:y*al.Stride+w]...)
			}
			g.cov = cov
		}
	}
	s.ok = true
}

// gl returns the glyph record for ch in strike id. A ch outside printable
// ASCII reads as a space (these strings are ASCII by house convention; a
// stray byte must not crash a frame).
func gl(id, ch int) *glyph {
	s := at(id)
	if s == nil {
		return nil
	}
	if ch < glyphLo || ch > glyphHi {
		ch = glyphLo
	}
	return &s.glyphs[ch-glyphLo]
}

// Ascent is the strike's pixels above the baseline; Descent below (positive).
// Ascent+Descent is the line box a vertical centring uses.
func Ascent(id int) int {
	if s := at(id); s != nil {
		return s.ascent
	}
	return 0
}

func Descent(id int) int {
	if s := at(id); s != nil {
		return s.descent
	}
	return 0
}

// MeasureText is the text's advance width in pixels: the fractional advances
// summed in 26.6, rounded once at the end — the number TextAlign aligns by.
func MeasureText(id int, text string) int {
	s := at(id)
	if s == nil {
		return 0
	}
	sum := 0
	for i := 0; i < len(text); i++ {
		sum += gl(id, int(text[i])).adv64
	}
	return (sum + 32) >> 6
}

// Advance64 is ch's advance in 26.6 fixed point — the painter's pen
// accumulates these and rounds per glyph.
func Advance64(id, ch int) int {
	if g := gl(id, ch); g != nil {
		return g.adv64
	}
	return 0
}

// GlyphW/GlyphH are ch's coverage-box size; GlyphLeft its left bearing from
// the pen; GlyphTop its top edge's height above the baseline.
func GlyphW(id, ch int) int {
	if g := gl(id, ch); g != nil {
		return g.w
	}
	return 0
}

func GlyphH(id, ch int) int {
	if g := gl(id, ch); g != nil {
		return g.h
	}
	return 0
}

func GlyphLeft(id, ch int) int {
	if g := gl(id, ch); g != nil {
		return g.left
	}
	return 0
}

func GlyphTop(id, ch int) int {
	if g := gl(id, ch); g != nil {
		return g.top
	}
	return 0
}

// Cover is ch's coverage bitmap: GlyphW*GlyphH bytes, row-major, 0..255.
// The INTERNAL buffer, not a copy — callers read it, per frame, and must not
// write into it.
func Cover(id, ch int) []byte {
	if g := gl(id, ch); g != nil {
		return g.cov
	}
	return nil
}

// Digest is an FNV-1a 64 over the strike's metrics and every glyph's
// metrics+coverage, as 16 hex digits — the byte-determinism witness the
// fb-smoke selfcheck pins. "" for a bad id or a failed rasterisation.
func Digest(id int) string {
	s := at(id)
	if s == nil {
		return ""
	}
	h := uint64(14695981039346656037)
	mix := func(v int) {
		for i := 0; i < 4; i++ {
			h ^= uint64(byte(v >> (8 * i)))
			h *= 1099511628211
		}
	}
	mix(s.ascent)
	mix(s.descent)
	for i := range s.glyphs {
		g := &s.glyphs[i]
		mix(g.w)
		mix(g.h)
		mix(g.left)
		mix(g.top)
		mix(g.adv64)
		for _, b := range g.cov {
			h ^= uint64(b)
			h *= 1099511628211
		}
	}
	return fmt.Sprintf("%016x", h)
}
