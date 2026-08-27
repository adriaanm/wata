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
// 2026-08-27).
//
// SUBPIXEL X-PHASES (owner-approved, 2026-08-27): each glyph is rasterised
// at FOUR fractional x origins — 0, ¼, ½, ¾ px (the rasteriser takes a
// fractional dot) — and the painter's pen keeps the fractional advance sum,
// picking the phase nearest the pen's fraction per glyph. Word spacing is
// then optically even at every size, instead of each gap rounding to a
// whole pixel. The pen accumulates FRACTIONAL advances (26.6 fixed point)
// as before, so rounding error never piles up per word; what the phases add
// is that the residual quantisation per glyph drops from ±½ px to ±⅛ px.
//
// The strike table is owned here: a strike is named by a small integer id
// resolved by Strike(face, px, weight). Each entry rasterises lazily, once
// (sync.Once) — an entry nobody looks up costs only its table row. The face
// is settled: Atkinson Hyperlegible (owner A/B verdict 2026-08-27, plan
// 0077; Inter was the other candidate and is retired).
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

//go:embed fonts/AtkinsonHyperlegible-Bold.ttf
var atkinsonBold []byte

const (
	glyphLo = 0x20 // first printable ASCII
	glyphHi = 0x7e // last
	nGlyphs = glyphHi - glyphLo + 1
)

// nPhases is the subpixel x-phase count: a glyph is rasterised at origins
// 0, ¼, ½ and ¾ of a pixel (26.6: 0, 16, 32, 48).
const nPhases = 4

// phased is one glyph AT ONE X-PHASE: a w*h coverage bitmap (row-major, one
// byte per pixel, 0..255) drawn at (penX+left, baselineY-top). The bearings
// are phase-specific — shifting the origin by ¼ px can move the box edge.
type phased struct {
	w, h int
	left int // horizontal bearing: box left edge relative to the pen
	top  int // vertical bearing: box top edge ABOVE the baseline
	cov  []byte
}

// glyph is one rasterised glyph: its four x-phases plus the advance the pen
// moves by (26.6 fixed point; phase-independent — the outline is the same,
// only its raster origin shifts).
type glyph struct {
	adv64 int
	ph    [nPhases]phased
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

// The table: every (face, px, weight) the role table can ask for. One face —
// Atkinson Hyperlegible, the owner's on-panel verdict (2026-08-27; Inter and
// the runtime face switch retired with it, plan 0077). Ids are the slice
// indices and are stable only within a process — nothing persists them.
var table = []*strike{
	{face: "atkinson", px: 30, weight: "bold", ttf: atkinsonBold},
	{face: "atkinson", px: 16, weight: "bold", ttf: atkinsonBold},
	// 13 bold: the small roles. Classic Atkinson ships no Medium cut, so the
	// old "medium" entries silently rasterised Regular — which read too thin
	// on the panel (owner, 2026-08-27); FbTypeRoles resolves every small
	// role to Bold now and the Regular rows are gone with their ttf.
	{face: "atkinson", px: 13, weight: "bold", ttf: atkinsonBold},
	// the full-bleed DISPLAY ladder's other rungs (38 is the resting card's
	// first choice, 24 the floor; 30 above is the middle rung) — boot-lazy
	// like everything else, so a rung no name ever needs costs only these
	// table rows.
	{face: "atkinson", px: 38, weight: "bold", ttf: atkinsonBold},
	{face: "atkinson", px: 24, weight: "bold", ttf: atkinsonBold},
}

// Strike resolves (face, px, weight) to a strike id, or -1 if the table has
// no such entry. Cheap (a scan of 6), callable per label per frame.
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
	// A dot far from the origin keeps every glyph box positive; the bearings
	// below are relative to its integer part. Each phase shifts the dot by a
	// quarter pixel (16 in 26.6) — the same outline, four raster origins.
	for c := glyphLo; c <= glyphHi; c++ {
		g := &s.glyphs[c-glyphLo]
		for p := 0; p < nPhases; p++ {
			dot := fixed.Point26_6{X: fixed.I(100) + fixed.Int26_6(p*16), Y: fixed.I(100)}
			dr, mask, maskp, adv, ok := face.Glyph(dot, rune(c))
			if !ok {
				continue
			}
			if p == 0 {
				g.adv64 = int(adv)
			}
			ph := &g.ph[p]
			w, h := dr.Dx(), dr.Dy()
			ph.w, ph.h = w, h
			ph.left = dr.Min.X - 100
			ph.top = 100 - dr.Min.Y
			if w > 0 && h > 0 {
				// copy the mask into a tight one-byte-per-pixel buffer
				al := image.NewAlpha(image.Rect(0, 0, w, h))
				draw.DrawMask(al, al.Bounds(), image.NewUniform(image.White),
					image.Point{}, mask, maskp, draw.Src)
				cov := make([]byte, 0, w*h)
				for y := 0; y < h; y++ {
					cov = append(cov, al.Pix[y*al.Stride:y*al.Stride+w]...)
				}
				ph.cov = cov
			}
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

// phOf returns ch's raster at x-phase `phase` (clamped into 0..3), or nil.
func phOf(id, ch, phase int) *phased {
	g := gl(id, ch)
	if g == nil {
		return nil
	}
	return &g.ph[phase&(nPhases-1)]
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

// GlyphW/GlyphH are ch's coverage-box size AT x-phase `phase` (0..3 —
// quarter-pixel raster origins); GlyphLeft its left bearing from the pen;
// GlyphTop its top edge's height above the baseline. The bearings carry the
// phase's sub-shift, so a caller draws at the pen's FLOOR pixel and the
// quarter-pixel placement is already in the raster.
func GlyphW(id, ch, phase int) int {
	if p := phOf(id, ch, phase); p != nil {
		return p.w
	}
	return 0
}

func GlyphH(id, ch, phase int) int {
	if p := phOf(id, ch, phase); p != nil {
		return p.h
	}
	return 0
}

func GlyphLeft(id, ch, phase int) int {
	if p := phOf(id, ch, phase); p != nil {
		return p.left
	}
	return 0
}

func GlyphTop(id, ch, phase int) int {
	if p := phOf(id, ch, phase); p != nil {
		return p.top
	}
	return 0
}

// Cover is ch's coverage bitmap at x-phase `phase`: GlyphW*GlyphH bytes,
// row-major, 0..255. The INTERNAL buffer, not a copy — callers read it, per
// frame, and must not write into it.
func Cover(id, ch, phase int) []byte {
	if p := phOf(id, ch, phase); p != nil {
		return p.cov
	}
	return nil
}

// Digest is an FNV-1a 64 over the strike's metrics and every glyph's
// metrics+coverage — ALL FOUR x-phases, so a phase silently collapsing onto
// phase 0 moves the digest — as 16 hex digits: the byte-determinism witness
// the fb-smoke selfcheck pins. "" for a bad id or a failed rasterisation.
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
		mix(g.adv64)
		for p := 0; p < nPhases; p++ {
			ph := &g.ph[p]
			mix(ph.w)
			mix(ph.h)
			mix(ph.left)
			mix(ph.top)
			for _, b := range ph.cov {
				h ^= uint64(b)
				h *= 1099511628211
			}
		}
	}
	return fmt.Sprintf("%016x", h)
}
