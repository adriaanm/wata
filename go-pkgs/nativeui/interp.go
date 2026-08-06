// The retained interpreter: a wataui view tree as a live NSView tree, at an
// integer scale of the 160x128 stage (plan 0032).
//
// The element table, per constructor:
//
//	VText  -> NSTextField label (monospaced system font sized to the cell)
//	VGlyph -> NSTextField label over the glyph mapping (glyphs.go)
//	VRect  -> NSBox, custom/borderless, fillColor = the RGB565 color widened
//	VImage -> NSImageView over RGB565->RGBA widening + nearest-neighbour
//	          pre-scaling (pixels.go), so AppKit never interpolates
//	VGroup -> a plain container NSView; subview order IS paint order (AppKit
//	          draws later subviews over earlier — the same rule the algebra has)
//
// VRect is an NSBox rather than the layer-backed NSView the plan sketched:
// layer background colors speak CGColorRef, which bindgen refuses (a
// CoreFoundation struct pointer, not an ObjC class), while NSBox's fillColor
// is a plain NSColor — and NSBox draws through drawRect:, so offscreen
// renders (cacheDisplayInRect) see it without a presented layer tree.
//
// Geometry: coordinates are wataui's semantic positions. VText addresses the
// 26x15 character grid of the fb font (6x8 cells, text rows starting 1px
// down — display.scala's Font.drawText); everything else addresses stage
// pixels. Both scale by the integer Scale. AppKit's y axis points UP, so
// every frame is flipped against the stage height; group containers all
// span the full stage, which keeps child coordinates stage-absolute.
//
// Threading: every method that touches the native tree must run on the
// AppKit thread. The interpreter does not hop by itself — the frame loop
// owns the seam (dispatch.go) and submits whole Apply calls, keeping one
// frame one main-queue turn.

//go:build darwin

package nativeui

import (
	"bytes"
	"image"
	"image/png"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
)

// The stage the view algebra addresses: the device panel's geometry
// (display.scala's Display/Font constants).
const (
	StageW     = 160
	StageH     = 128
	GlyphW     = 6 // 5px glyph + 1px spacing
	GlyphH     = 8
	TextTopPad = 1 // text row 0 starts at y=1, below the 1px status line
)

// cellFontPts is the monospaced font size per unit of scale. The fb glyph
// cell is 6x8; no vector font matches both, and the row pitch is what must
// hold, so the size is chosen to keep the line height inside the 8px cell
// (SF Mono's ascent+descent is ~1.16em). The character advance (~0.6em) is
// then narrower than the 6px cell — columns still line up exactly, because
// every VText is framed from its own grid cell; only intra-string width
// shrinks. The semantic oracle for appearance stays the fb goldens.
const cellFontPts = 6.8

// node is one native view and, for groups, its children — the same shape as
// the plain mirror, so paths index both identically.
type node struct {
	view   View
	native appkit.NSView
	kids   []*node
}

// Stage owns the retained native tree and its plain mirror. Zero or one
// view tree is mounted at a time; the root container view is created once
// and handed to the window (chunk 2) or the offscreen renderer (tests).
type Stage struct {
	Scale  int
	root   appkit.NSView
	font   appkit.NSFont
	top    *node
	mirror View
}

// NewStage creates the scaled stage container (Scale*160 x Scale*128).
// AppKit thread only.
func NewStage(scale int) *Stage {
	if scale < 1 {
		scale = 1
	}
	s := &Stage{Scale: scale}
	s.root = appkit.GetNSViewClass().Alloc().InitWithFrame(
		appkit.CGRect{Origin: appkit.CGPoint{}, Size: appkit.CGSize{
			Width:  float64(StageW * scale),
			Height: float64(StageH * scale),
		}})
	s.font = appkit.GetNSFontClass().MonospacedSystemFontOfSizeWeight(
		cellFontPts*float64(scale), 0 /* NSFontWeightRegular */)
	return s
}

// Root is the stage's container view — what a window's contentView adopts,
// and what offscreen renders draw.
func (s *Stage) Root() appkit.NSView { return s.root }

// Mirror is the plain view tree the native tree currently shows — the
// "retained mirror" of wataui's applyAll contract. Nil before SetTree.
func (s *Stage) Mirror() View { return s.mirror }

// SetTree builds the native tree from scratch, replacing whatever was
// mounted. AppKit thread only.
func (s *Stage) SetTree(v View) {
	if s.top != nil {
		s.top.native.RemoveFromSuperview()
	}
	s.top = s.build(v)
	s.root.AddSubview(s.top.native)
	s.mirror = v
}

// Apply mutates the native tree by the differ's script, in script order —
// the retained arm of Patches.applyAll. AppKit thread only.
func (s *Stage) Apply(ps []Patch) {
	for _, p := range ps {
		switch q := p.(type) {
		case PSet:
			s.set(q.Path, q.View)
		case PInsert:
			s.insert(q.Path, q.Idx, q.Keyed.View)
		case PDelete:
			s.delete(q.Path, q.Idx)
		}
		s.mirror = applyOne(p, s.mirror)
	}
}

// -- patch arms --------------------------------------------------------------

func (s *Stage) set(path []int, v View) {
	if len(path) == 0 {
		if s.top == nil {
			s.SetTree(v)
			return
		}
		if s.mutate(s.top, v) {
			return
		}
		fresh := s.build(v)
		s.root.ReplaceSubviewWith(s.top.native, fresh.native)
		s.top = fresh
		return
	}
	parent := s.nodeAt(path[:len(path)-1])
	idx := path[len(path)-1]
	if parent == nil || idx < 0 || idx >= len(parent.kids) {
		return // mirror semantics: a path into nothing is a no-op
	}
	child := parent.kids[idx]
	if s.mutate(child, v) {
		return
	}
	fresh := s.build(v)
	parent.native.ReplaceSubviewWith(child.native, fresh.native)
	parent.kids[idx] = fresh
}

func (s *Stage) insert(path []int, idx int, v View) {
	parent := s.nodeAt(path)
	if parent == nil || idx < 0 {
		return
	}
	if _, ok := parent.view.(VGroup); !ok {
		return
	}
	fresh := s.build(v)
	if idx >= len(parent.kids) {
		parent.native.AddSubview(fresh.native)
		parent.kids = append(parent.kids, fresh)
		return
	}
	// Below the current occupant of idx: earlier in subviews = painted first.
	parent.native.AddSubviewPositionedRelativeTo(
		fresh.native, appkit.NSWindowBelow, parent.kids[idx].native)
	parent.kids = append(parent.kids[:idx],
		append([]*node{fresh}, parent.kids[idx:]...)...)
}

func (s *Stage) delete(path []int, idx int) {
	parent := s.nodeAt(path)
	if parent == nil || idx < 0 || idx >= len(parent.kids) {
		return
	}
	parent.kids[idx].native.RemoveFromSuperview()
	parent.kids = append(parent.kids[:idx], parent.kids[idx+1:]...)
}

func (s *Stage) nodeAt(path []int) *node {
	n := s.top
	for _, i := range path {
		if n == nil || i < 0 || i >= len(n.kids) {
			return nil
		}
		n = n.kids[i]
	}
	return n
}

// -- building ----------------------------------------------------------------

func (s *Stage) build(v View) *node {
	n := &node{view: v}
	switch x := v.(type) {
	case VGroup:
		n.native = appkit.GetNSViewClass().Alloc().InitWithFrame(s.stageRect())
		for _, k := range x.Children {
			kid := s.build(k.View)
			n.native.AddSubview(kid.native)
			n.kids = append(n.kids, kid)
		}
	case VText:
		n.native = s.label(x.Text, s.textFrame(x), x.Color)
	case VGlyph:
		n.native = s.label(GlyphString(x.Glyph), s.frame(x.X, x.Y, GlyphW, GlyphH), x.Color)
	case VRect:
		// -init may return a different object than -alloc did; always adopt
		// the returned id.
		v := appkit.NSView{ID: appkit.GetNSBoxClass().Alloc().ID}.
			InitWithFrame(s.frame(x.X, x.Y, x.W, x.H))
		box := appkit.NSBox{ID: v.ID}
		box.SetBoxType(appkit.NSBoxCustom)
		box.SetTitlePosition(appkit.NSNoTitle)
		box.SetBorderWidth(0)
		box.SetFillColor(s.color(x.Color))
		n.native = v
	case VImage:
		v := appkit.NSView{ID: appkit.GetNSImageViewClass().Alloc().ID}.
			InitWithFrame(s.frame(x.X, x.Y, x.W, x.H))
		iv := appkit.NSImageView{ID: v.ID}
		iv.SetImageScaling(appkit.NSImageScaleAxesIndependently)
		iv.SetImage(s.image(x))
		n.native = v
	}
	return n
}

// mutate updates one native view's properties in place when the new view has
// the same constructor — the PSet fast path. Groups always answer false: a
// group PSet is a subtree replace (the differ only emits it on a
// constructor change).
func (s *Stage) mutate(n *node, v View) bool {
	switch old := n.view.(type) {
	case VText:
		x, ok := v.(VText)
		if !ok {
			return false
		}
		s.relabel(n.native, x.Text, s.textFrame(x), x.Color, old.Text, old.Color)
	case VGlyph:
		x, ok := v.(VGlyph)
		if !ok {
			return false
		}
		s.relabel(n.native, GlyphString(x.Glyph), s.frame(x.X, x.Y, GlyphW, GlyphH),
			x.Color, GlyphString(old.Glyph), old.Color)
	case VRect:
		x, ok := v.(VRect)
		if !ok {
			return false
		}
		box := appkit.NSBox{ID: n.native.ID}
		if x.Color != old.Color {
			box.SetFillColor(s.color(x.Color))
		}
		n.native.SetFrame(s.frame(x.X, x.Y, x.W, x.H))
	case VImage:
		x, ok := v.(VImage)
		if !ok {
			return false
		}
		iv := appkit.NSImageView{ID: n.native.ID}
		iv.SetImage(s.image(x))
		n.native.SetFrame(s.frame(x.X, x.Y, x.W, x.H))
	default:
		return false
	}
	n.view = v
	return true
}

func (s *Stage) relabel(native appkit.NSView, text string, frame appkit.CGRect,
	color int, oldText string, oldColor int) {
	field := appkit.NSTextField{ID: native.ID}
	if text != oldText {
		appkit.NSControl{ID: native.ID}.SetStringValue(text)
	}
	if color != oldColor {
		field.SetTextColor(s.color(color))
	}
	native.SetFrame(frame)
}

// -- elements ----------------------------------------------------------------

func (s *Stage) label(text string, frame appkit.CGRect, color int) appkit.NSView {
	field := appkit.GetNSTextFieldClass().LabelWithString(text)
	appkit.NSControl{ID: field.ID}.SetFont(s.font)
	field.SetTextColor(s.color(color))
	v := appkit.NSView{ID: field.ID}
	v.SetFrame(frame)
	return v
}

func (s *Stage) image(x VImage) appkit.NSImage {
	rgba := ScaleRGBANearest(ExpandRGB565(x.Pixels, x.W, x.H), x.W, x.H, s.Scale)
	img := &image.RGBA{Pix: rgba, Stride: x.W * s.Scale * 4,
		Rect: image.Rect(0, 0, x.W*s.Scale, x.H*s.Scale)}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		panic("nativeui: png encode: " + err.Error()) // cannot fail on a valid RGBA
	}
	data := appkit.NSData{ID: objcrt.NSData(buf.Bytes())}
	return appkit.GetNSImageClass().Alloc().InitWithData(data)
}

func (s *Stage) color(c int) appkit.NSColor {
	r, g, b := RGB565Components(c)
	return appkit.GetNSColorClass().ColorWithSRGBRedGreenBlueAlpha(r, g, b, 1)
}

// -- geometry ----------------------------------------------------------------

func (s *Stage) stageRect() appkit.CGRect {
	return appkit.CGRect{Size: appkit.CGSize{
		Width:  float64(StageW * s.Scale),
		Height: float64(StageH * s.Scale),
	}}
}

// frame maps a semantic stage rect (origin top-left, pixels) to a scaled
// AppKit frame (origin bottom-left).
func (s *Stage) frame(x, y, w, h int) appkit.CGRect {
	return appkit.CGRect{
		Origin: appkit.CGPoint{
			X: float64(x * s.Scale),
			Y: float64((StageH - y - h) * s.Scale),
		},
		Size: appkit.CGSize{Width: float64(w * s.Scale), Height: float64(h * s.Scale)},
	}
}

// textFrame is the grid cell run a VText occupies: x = col*6, y = 1 + row*8
// (display.scala's Font.drawText), one 6x8 cell per byte of text.
func (s *Stage) textFrame(t VText) appkit.CGRect {
	return s.frame(t.Col*GlyphW, TextTopPad+t.Row*GlyphH, len(t.Text)*GlyphW, GlyphH)
}
