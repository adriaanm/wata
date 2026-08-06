// Package nativeui is the retained AppKit backend for wataui (plan 0032):
// a Go mirror of the view algebra, and an interpreter that builds an NSView
// tree from a view tree and mutates it by applying the differ's patch script.
//
// This file is the algebra mirror. It exists so the interpreter is plain Go —
// testable with `go test` alone, no Sgola in the loop — while staying
// field-for-field faithful to the Sgola definitions:
//
//	wataui/src/main/scala/view.scala  (View, Keyed)
//	wataui/src/main/scala/diff.scala  (Patch, Patches.applyAll)
//
// The correspondence is exact and deliberate:
//
//   - Five constructors, same names, same field order.
//   - Coordinates are SEMANTIC positions, not device pixels: VText addresses
//     a character-grid cell (col, row) on the 26x15 grid; VGlyph, VRect and
//     VImage address pixels on the 160x128 stage. A backend that is not that
//     panel scales both (interp.go's Stage does, by an integer factor).
//   - Colors are RGB565 ints, the device panel's own format; this backend
//     widens them by bit replication (pixels.go).
//   - VImage pixels are w*h RGB565 LITTLE-ENDIAN byte pairs, row-major.
//   - Children paint in list order: a later child draws over an earlier one.
//   - A Keyed's non-empty key names the thing the child shows; "" means
//     "match me by position".
//
// The Sgola side is the source of truth. A field added or reordered there
// must be mirrored here in the same change.
package nativeui

// View is a screen (or part of one) as a value. Exactly five constructors,
// mirroring wataui's sealed trait.
type View interface{ isView() }

// VText is a run of ASCII text at a character-grid cell. Text is drawn byte
// by byte: a glyph past 0x7F belongs in VGlyph, never inside a string.
type VText struct {
	Col, Row int
	Text     string
	Color    int // RGB565
}

// VGlyph is one glyph by code at a PIXEL position — the custom icons past
// 0x7F and any single character that has to sit off the grid.
type VGlyph struct {
	X, Y  int
	Glyph int // font code 0..255; see glyphs.go for the AppKit mapping
	Color int // RGB565
}

// VRect is a filled rectangle at a pixel position.
type VRect struct {
	X, Y, W, H int
	Color      int // RGB565
}

// VImage is a raw image blitted at a pixel position: W*H pixels, row-major,
// as RGB565 little-endian byte pairs (W*H*2 bytes).
type VImage struct {
	X, Y, W, H int
	Pixels     []byte
}

// VGroup is an ordered composite. Children paint in list order.
type VGroup struct {
	Children []Keyed
}

// Keyed is a child and its identity within its group. "" means "no
// identity" — match by position.
type Keyed struct {
	Key  string
	View View
}

func (VText) isView()  {}
func (VGlyph) isView() {}
func (VRect) isView()  {}
func (VImage) isView() {}
func (VGroup) isView() {}

// Patch is one edit of a view tree, mirroring wataui's Patch. A path is the
// child indices from the root, outermost first; nil (empty) is the root. A
// script is ORDERED: each patch is written against the tree the patches
// before it produced.
type Patch interface{ isPatch() }

// PSet replaces the subtree at Path wholesale. The differ emits it at leaf
// granularity: a changed line of text patches that one text node.
type PSet struct {
	Path []int
	View View
}

// PInsert inserts Keyed as child Idx of the group at Path.
type PInsert struct {
	Path []int
	Idx  int
	Keyed Keyed
}

// PDelete removes child Idx of the group at Path.
type PDelete struct {
	Path []int
	Idx  int
}

func (PSet) isPatch()    {}
func (PInsert) isPatch() {}
func (PDelete) isPatch() {}

// EqView is structural equality over a whole tree (VImage compares pixel
// bytes by content — mirror of Views.eqView).
func EqView(a, b View) bool {
	switch x := a.(type) {
	case VText:
		y, ok := b.(VText)
		return ok && x == y
	case VGlyph:
		y, ok := b.(VGlyph)
		return ok && x == y
	case VRect:
		y, ok := b.(VRect)
		return ok && x == y
	case VImage:
		y, ok := b.(VImage)
		if !ok || x.X != y.X || x.Y != y.Y || x.W != y.W || x.H != y.H {
			return false
		}
		if len(x.Pixels) != len(y.Pixels) {
			return false
		}
		for i := range x.Pixels {
			if x.Pixels[i] != y.Pixels[i] {
				return false
			}
		}
		return true
	case VGroup:
		y, ok := b.(VGroup)
		if !ok || len(x.Children) != len(y.Children) {
			return false
		}
		for i := range x.Children {
			if x.Children[i].Key != y.Children[i].Key ||
				!EqView(x.Children[i].View, y.Children[i].View) {
				return false
			}
		}
		return true
	}
	return false
}

// ApplyAll applies a whole script, in order, to a plain view tree — the Go
// port of Patches.applyAll, the "retained mirror" semantics the native tree
// must match. applyAll(diff(a,b), a) == b is the invariant the differ is
// judged by; the interpreter's own tests judge the NSView tree against this.
func ApplyAll(ps []Patch, v View) View {
	for _, p := range ps {
		v = applyOne(p, v)
	}
	return v
}

func applyOne(p Patch, v View) View {
	switch q := p.(type) {
	case PSet:
		return replaceAt(v, q.Path, q.View)
	case PInsert:
		return editGroup(v, q.Path, true, q.Idx, q.Keyed)
	case PDelete:
		return editGroup(v, q.Path, false, q.Idx, Keyed{})
	}
	return v
}

// replaceAt puts nv where path points, keeping the key of the child it
// replaces — a PSet changes what a child shows, never who it is.
func replaceAt(v View, path []int, nv View) View {
	if len(path) == 0 {
		return nv
	}
	g, ok := v.(VGroup)
	if !ok {
		return v
	}
	h := path[0]
	if h < 0 || h >= len(g.Children) {
		return v
	}
	kids := append([]Keyed(nil), g.Children...)
	kids[h] = Keyed{kids[h].Key, replaceAt(kids[h].View, path[1:], nv)}
	return VGroup{kids}
}

// editGroup inserts into / deletes from the child list of the group path
// points at.
func editGroup(v View, path []int, insert bool, idx int, k Keyed) View {
	if len(path) > 0 {
		g, ok := v.(VGroup)
		if !ok {
			return v
		}
		h := path[0]
		if h < 0 || h >= len(g.Children) {
			return v
		}
		kids := append([]Keyed(nil), g.Children...)
		kids[h] = Keyed{kids[h].Key, editGroup(kids[h].View, path[1:], insert, idx, k)}
		return VGroup{kids}
	}
	g, ok := v.(VGroup)
	if !ok {
		return v
	}
	kids := append([]Keyed(nil), g.Children...)
	if insert {
		if idx < 0 {
			return v // mirror of Views.insertAt: a negative index never matches
		}
		if idx > len(kids) {
			idx = len(kids) // mirror of Views.insertAt: past-the-end appends
		}
		kids = append(kids[:idx], append([]Keyed{k}, kids[idx:]...)...)
	} else {
		if idx < 0 || idx >= len(kids) {
			return v
		}
		kids = append(kids[:idx], kids[idx+1:]...)
	}
	return VGroup{kids}
}
