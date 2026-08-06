// Offscreen render sanity: a known tree drawn through AppKit's own pipeline
// (cacheDisplayInRect:toBitmapImageRep:, the machinery the struct-callback
// spike proved headless), asserting non-blank output and probe pixels — NOT
// byte goldens. Native fonts are not the fb font; the semantic oracle for
// what a screen shows stays the fb goldens over the same bodies.

//go:build darwin

package nativeui

import (
	"testing"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
)

// probe reads a rendered pixel at STAGE coordinates (top-left origin,
// semantic pixels), mapping through the rep's own pixel size so a backing
// scale other than 1 cannot skew the address.
func probe(rep appkit.NSBitmapImageRep, x, y, scale int) (r, g, b float64) {
	ir := appkit.NSImageRep{ID: rep.ID}
	px := ir.PixelsWide() * (x*scale + scale/2) / (StageW * scale)
	py := ir.PixelsHigh() * (y*scale + scale/2) / (StageH * scale)
	c := rep.ColorAtXY(px, py)
	return c.RedComponent(), c.GreenComponent(), c.BlueComponent()
}

func near(v, want float64) bool {
	d := v - want
	return d < 0.15 && d > -0.15
}

func TestOffscreenRenderProbes(t *testing.T) {
	onAppKit(t, func() {
		const scale = 4
		s := NewStage(scale)
		s.SetTree(VGroup{[]Keyed{
			{"bg", VRect{0, 0, StageW, StageH, 0x0000}},         // black stage
			{"red", VRect{8, 8, 40, 24, 0xf800}},                // top-left red
			{"blue", VRect{120, 100, 32, 20, 0x001f}},           // bottom-right blue
			{"title", VText{2, 6, "WATA", 0xffff}},              // white text
			{"qr", qrImage()},                                   // the checker block
		}})
		root := s.Root()
		bounds := root.Bounds()
		rep := root.BitmapImageRepForCachingDisplayInRect(bounds)
		if rep.ID == 0 {
			t.Fatal("no bitmap rep")
		}
		root.CacheDisplayInRectToBitmapImageRep(bounds, rep)

		// The red rect's center, in stage coordinates — also pins the y flip:
		// red sits near the TOP of the stage, blue near the bottom.
		if r, g, b := probe(rep, 28, 20, scale); !near(r, 1) || !near(g, 0) || !near(b, 0) {
			t.Errorf("red probe = (%v %v %v)", r, g, b)
		}
		if r, g, b := probe(rep, 136, 110, scale); !near(r, 0) || !near(g, 0) || !near(b, 1) {
			t.Errorf("blue probe = (%v %v %v)", r, g, b)
		}
		// Background between the rects is black.
		if r, g, b := probe(rep, 80, 60, scale); !near(r, 0) || !near(g, 0) || !near(b, 0) {
			t.Errorf("background probe = (%v %v %v)", r, g, b)
		}
		// The image blit: the checker's white top-left cell.
		if r, g, b := probe(rep, 100, 60, scale); !near(r, 1) || !near(g, 1) || !near(b, 1) {
			t.Errorf("image probe = (%v %v %v)", r, g, b)
		}
		// Non-blank in the text run: some pixel in the title's cell block
		// must not be background black (the label drew SOMETHING white-ish).
		found := false
		for y := 49; y < 57 && !found; y++ {
			for x := 12; x < 36 && !found; x++ {
				if r, g, b := probe(rep, x, y, scale); r+g+b > 1.2 {
					found = true
				}
			}
		}
		if !found {
			t.Error("the text label rendered nothing visible")
		}
	})
}
