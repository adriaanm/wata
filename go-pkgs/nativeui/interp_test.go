// The retained invariant, checked on the real toolkit: after SetTree, and
// after Apply of a patch script, the NATIVE hierarchy (classes, order,
// frames, label strings) mirrors the plain tree ApplyAll produces.
//
// These tests drive AppKit headlessly — no NSApplication, no runloop, no
// window. Off-runloop view construction and mutation is safe for offscreen
// work (the struct-callback spike proved it up to cacheDisplayInRect); each
// test locks its OS thread and wraps itself in an autorelease pool so
// AppKit's temporaries drain deterministically.

//go:build darwin

package nativeui

import (
	"fmt"
	"runtime"
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

var poolPush func() uintptr
var poolPop func(p uintptr)

func init() {
	lib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
	if err != nil {
		panic(err)
	}
	purego.RegisterLibFunc(&poolPush, lib, "objc_autoreleasePoolPush")
	purego.RegisterLibFunc(&poolPop, lib, "objc_autoreleasePoolPop")
}

// onAppKit runs f on a locked OS thread inside an autorelease pool.
func onAppKit(t *testing.T, f func()) {
	t.Helper()
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	pool := poolPush()
	defer poolPop(pool)
	f()
}

// className asks the ObjC runtime what an object is.
func className(id objc.ID) string {
	return objc.Send[string](
		objc.ID(id.Class()).Send(objc.RegisterName("description")),
		objc.RegisterName("UTF8String"))
}

// wantClass is the native class each constructor must build — the element
// table, pinned.
func wantClass(v View) string {
	switch v.(type) {
	case VGroup:
		return "NSView"
	case VText, VGlyph:
		return "NSTextField"
	case VRect:
		return "NSBox"
	case VImage:
		return "NSImageView"
	}
	return "?"
}

// expectFrame recomputes the scaled, flipped frame independently of the
// interpreter, pinning the geometry convention: semantic top-left origin,
// AppKit bottom-left, text on the 6x8 grid one pixel down.
func expectFrame(v View, scale int) (x, y, w, h float64, ok bool) {
	var sx, sy, sw, sh int
	switch t := v.(type) {
	case VText:
		sx, sy, sw, sh = t.Col*6, 1+t.Row*8, len(t.Text)*6, 8
	case VGlyph:
		sx, sy, sw, sh = t.X, t.Y, 6, 8
	case VRect:
		sx, sy, sw, sh = t.X, t.Y, t.W, t.H
	case VImage:
		sx, sy, sw, sh = t.X, t.Y, t.W, t.H
	default:
		return 0, 0, 0, 0, false
	}
	return float64(sx * scale), float64((128 - sy - sh) * scale),
		float64(sw * scale), float64(sh * scale), true
}

// assertMirrors walks the native hierarchy and the plain tree side by side.
func assertMirrors(t *testing.T, path string, native appkit.NSView, v View, scale int) {
	t.Helper()
	if got, want := className(native.ID), wantClass(v); got != want {
		t.Fatalf("%s: native class %s, want %s", path, got, want)
	}
	if x, y, w, h, ok := expectFrame(v, scale); ok {
		f := native.Frame()
		if f.Origin.X != x || f.Origin.Y != y || f.Size.Width != w || f.Size.Height != h {
			t.Fatalf("%s: frame (%v %v %v %v), want (%v %v %v %v)", path,
				f.Origin.X, f.Origin.Y, f.Size.Width, f.Size.Height, x, y, w, h)
		}
	}
	switch x := v.(type) {
	case VText:
		if got := (appkit.NSControl{ID: native.ID}).StringValue(); got != x.Text {
			t.Fatalf("%s: label %q, want %q", path, got, x.Text)
		}
	case VGlyph:
		if got := (appkit.NSControl{ID: native.ID}).StringValue(); got != GlyphString(x.Glyph) {
			t.Fatalf("%s: glyph label %q, want %q", path, got, GlyphString(x.Glyph))
		}
	case VGroup:
		subs := native.Subviews()
		if int(subs.Count()) != len(x.Children) {
			t.Fatalf("%s: %d subviews, want %d", path, subs.Count(), len(x.Children))
		}
		for i, k := range x.Children {
			child := appkit.NSView{ID: subs.ObjectAtIndex(uint(i))}
			assertMirrors(t, fmt.Sprintf("%s/%d", path, i), child, k.View, scale)
		}
	}
}

// mounted is the stage's single mounted subview (the tree's root).
func mounted(t *testing.T, s *Stage) appkit.NSView {
	t.Helper()
	subs := s.Root().Subviews()
	if subs.Count() != 1 {
		t.Fatalf("stage should hold exactly one mounted tree, has %d", subs.Count())
	}
	return appkit.NSView{ID: subs.ObjectAtIndex(0)}
}

func qrImage() VImage {
	// an 8x8 two-tone block, RGB565 LE: white/black checker
	px := make([]byte, 8*8*2)
	for i := 0; i < 64; i++ {
		if (i/8+i%8)%2 == 0 {
			px[i*2], px[i*2+1] = 0xff, 0xff
		}
	}
	return VImage{100, 60, 8, 8, px}
}

func TestBuildFromScratchMirrorsTheTree(t *testing.T) {
	onAppKit(t, func() {
		v := VGroup{append(fixture().(VGroup).Children, Keyed{"qr", qrImage()})}
		s := NewStage(4)
		s.SetTree(v)
		assertMirrors(t, "root", mounted(t, s), v, 4)
		if !EqView(s.Mirror(), v) {
			t.Fatal("mirror diverged from the set tree")
		}
	})
}

func TestPatchSequenceMirrorsApplyAll(t *testing.T) {
	onAppKit(t, func() {
		s := NewStage(4)
		s.SetTree(fixture())
		s.Apply(script())
		want := ApplyAll(script(), fixture())
		assertMirrors(t, "root", mounted(t, s), want, 4)
		if !EqView(s.Mirror(), want) {
			t.Fatal("mirror diverged from applyAll")
		}
	})
}

func TestPSetSameKindMutatesInPlace(t *testing.T) {
	onAppKit(t, func() {
		s := NewStage(2)
		s.SetTree(VGroup{[]Keyed{{"", VText{0, 0, "one", 0xffff}}}})
		before := mounted(t, s).Subviews().ObjectAtIndex(0)
		s.Apply([]Patch{PSet{[]int{0}, VText{2, 3, "two", 0xf800}}})
		after := mounted(t, s).Subviews().ObjectAtIndex(0)
		if before != after {
			t.Fatal("a same-constructor PSet must mutate the native view, not replace it")
		}
		want := VText{2, 3, "two", 0xf800}
		assertMirrors(t, "root", mounted(t, s), VGroup{[]Keyed{{"", want}}}, 2)
	})
}

func TestPSetKindChangeReplacesTheView(t *testing.T) {
	onAppKit(t, func() {
		s := NewStage(2)
		s.SetTree(VGroup{[]Keyed{
			{"a", VText{0, 0, "gone", 0xffff}},
			{"b", VRect{0, 100, 10, 10, 0x001f}},
		}})
		s.Apply([]Patch{PSet{[]int{0}, VRect{4, 8, 20, 16, 0x07e0}}})
		want := VGroup{[]Keyed{
			{"a", VRect{4, 8, 20, 16, 0x07e0}},
			{"b", VRect{0, 100, 10, 10, 0x001f}},
		}}
		assertMirrors(t, "root", mounted(t, s), want, 2)
	})
}

func TestRootPSetReplacesTheWholeTree(t *testing.T) {
	onAppKit(t, func() {
		s := NewStage(2)
		s.SetTree(fixture())
		flat := VText{0, 0, "ALL", 0x07ff}
		s.Apply([]Patch{PSet{nil, flat}})
		assertMirrors(t, "root", mounted(t, s), flat, 2)
		if !EqView(s.Mirror(), flat) {
			t.Fatal("mirror diverged after a root PSet")
		}
	})
}

func TestInsertOrderIsPaintOrder(t *testing.T) {
	onAppKit(t, func() {
		s := NewStage(2)
		s.SetTree(VGroup{[]Keyed{
			{"b", VText{0, 2, "b", 0xffff}},
		}})
		// insert before, after, and in the middle; the subview walk in
		// assertMirrors pins that native order tracks child order.
		s.Apply([]Patch{
			PInsert{nil, 0, Keyed{"a", VText{0, 0, "a", 0xffff}}},
			PInsert{nil, 2, Keyed{"d", VText{0, 6, "d", 0xffff}}},
			PInsert{nil, 2, Keyed{"c", VText{0, 4, "c", 0xffff}}},
		})
		want := VGroup{[]Keyed{
			{"a", VText{0, 0, "a", 0xffff}},
			{"b", VText{0, 2, "b", 0xffff}},
			{"c", VText{0, 4, "c", 0xffff}},
			{"d", VText{0, 6, "d", 0xffff}},
		}}
		assertMirrors(t, "root", mounted(t, s), want, 2)
	})
}
