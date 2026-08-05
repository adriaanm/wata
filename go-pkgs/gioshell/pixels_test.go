// The blit pipeline's oracle: the window may letterbox, centre and magnify the
// frame, but it may not touch a single pixel VALUE. These tests pin exactly
// that — Expand is the documented RGB565 widening, and ScaleNearest turns each
// source pixel into an s*s block of the identical bytes and nothing else.

package gioshell

import (
	"image"
	"testing"
)

// rgb565 packs r,g,b (already 5/6/5 bits) the way wata-fb's Color.rgb does.
func rgb565(r5, g6, b5 int) uint16 { return uint16(r5<<11 | g6<<5 | b5) }

// put writes one little-endian RGB565 pixel into a w-wide buffer.
func put(buf []byte, w, x, y int, c uint16) {
	i := (y*w + x) * 2
	buf[i] = byte(c)
	buf[i+1] = byte(c >> 8)
}

func TestExpandChannels(t *testing.T) {
	// black, white, and the three primaries at full saturation: bit
	// replication must reach 0x00 and 0xff exactly, or every golden colour
	// drifts.
	cases := []struct {
		c    uint16
		want [3]byte
	}{
		{rgb565(0, 0, 0), [3]byte{0x00, 0x00, 0x00}},
		{rgb565(31, 63, 31), [3]byte{0xff, 0xff, 0xff}},
		{rgb565(31, 0, 0), [3]byte{0xff, 0x00, 0x00}},
		{rgb565(0, 63, 0), [3]byte{0x00, 0xff, 0x00}},
		{rgb565(0, 0, 31), [3]byte{0x00, 0x00, 0xff}},
		{rgb565(1, 1, 1), [3]byte{0x08, 0x04, 0x08}},
	}
	buf := make([]byte, len(cases)*2)
	for i, tc := range cases {
		put(buf, len(cases), i, 0, tc.c)
	}
	img := Expand(buf, len(cases), 1, nil)
	for i, tc := range cases {
		o := img.PixOffset(i, 0)
		got := [3]byte{img.Pix[o], img.Pix[o+1], img.Pix[o+2]}
		if got != tc.want {
			t.Errorf("pixel %d (%#04x): got %v, want %v", i, tc.c, got, tc.want)
		}
		if img.Pix[o+3] != 0xff {
			t.Errorf("pixel %d: alpha %d, want 255", i, img.Pix[o+3])
		}
	}
}

func TestScaleNearestBlocks(t *testing.T) {
	// a 3x2 gradient scaled 3x: every source pixel must appear as a 3x3
	// block of its own bytes.
	const w, h, s = 3, 2, 3
	src := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			o := src.PixOffset(x, y)
			src.Pix[o+0] = byte(10 * (y*w + x + 1))
			src.Pix[o+1] = byte(20 * (y*w + x + 1))
			src.Pix[o+2] = byte(30 * (y*w + x + 1))
			src.Pix[o+3] = 0xff
		}
	}
	out := ScaleNearest(src, s, nil)
	if out.Bounds() != image.Rect(0, 0, w*s, h*s) {
		t.Fatalf("bounds %v, want %v", out.Bounds(), image.Rect(0, 0, w*s, h*s))
	}
	for y := 0; y < h*s; y++ {
		for x := 0; x < w*s; x++ {
			so := src.PixOffset(x/s, y/s)
			do := out.PixOffset(x, y)
			for c := 0; c < 4; c++ {
				if out.Pix[do+c] != src.Pix[so+c] {
					t.Fatalf("(%d,%d) channel %d: got %d, want %d (source (%d,%d))",
						x, y, c, out.Pix[do+c], src.Pix[so+c], x/s, y/s)
				}
			}
		}
	}
}

// The whole pipeline at the panel's real geometry: a 160x128 frame whose every
// pixel is a distinct function of its coordinates, pushed through
// Expand+ScaleNearest, must come out as exact s*s blocks of the expanded
// source value. This is the golden-equivalence claim — the frame CONTENT that
// the fb-ui goldens pin survives the window path untouched.
func TestPanelFrameRoundTrip(t *testing.T) {
	const w, h, s = 160, 128, 4
	buf := make([]byte, w*h*2)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			put(buf, w, x, y, rgb565(x%32, (x+y)%64, y%32))
		}
	}
	exp := Expand(buf, w, h, nil)
	out := ScaleNearest(exp, s, nil)
	if out.Bounds() != image.Rect(0, 0, w*s, h*s) {
		t.Fatalf("bounds %v", out.Bounds())
	}
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			c := rgb565(x%32, (x+y)%64, y%32)
			r5, g6, b5 := int(c>>11)&0x1f, int(c>>5)&0x3f, int(c)&0x1f
			want := [4]byte{
				byte(r5<<3 | r5>>2), byte(g6<<2 | g6>>4), byte(b5<<3 | b5>>2), 0xff,
			}
			for dy := 0; dy < s; dy++ {
				for dx := 0; dx < s; dx++ {
					o := out.PixOffset(x*s+dx, y*s+dy)
					got := [4]byte{out.Pix[o], out.Pix[o+1], out.Pix[o+2], out.Pix[o+3]}
					if got != want {
						t.Fatalf("source (%d,%d) block (%d,%d): got %v, want %v",
							x, y, dx, dy, got, want)
					}
				}
			}
		}
	}
}

// Buffer reuse must not change the result: the window loop hands the same
// scratch images back every frame.
func TestReuseBuffers(t *testing.T) {
	const w, h, s = 8, 4, 2
	a := make([]byte, w*h*2)
	b := make([]byte, w*h*2)
	for i := range a {
		a[i] = byte(i)
		b[i] = byte(255 - i)
	}
	exp := Expand(a, w, h, nil)
	sc := ScaleNearest(exp, s, nil)
	exp2 := Expand(b, w, h, exp)
	sc2 := ScaleNearest(exp2, s, sc)
	if exp2 != exp || sc2 != sc {
		t.Fatal("right-sized buffers must be reused, not reallocated")
	}
	fresh := ScaleNearest(Expand(b, w, h, nil), s, nil)
	for i := range fresh.Pix {
		if sc2.Pix[i] != fresh.Pix[i] {
			t.Fatalf("reuse changed byte %d: %d vs %d", i, sc2.Pix[i], fresh.Pix[i])
		}
	}
}

// A frame smaller than the buffer it is handed must still land at the
// requested geometry (the fit guard), and an s of 0 or less must not produce a
// degenerate image.
func TestFitAndScaleGuards(t *testing.T) {
	img := Expand(make([]byte, 4*2*2), 4, 2, image.NewRGBA(image.Rect(0, 0, 9, 9)))
	if img.Bounds() != image.Rect(0, 0, 4, 2) {
		t.Fatalf("bounds %v, want 4x2", img.Bounds())
	}
	if got := ScaleNearest(img, 0, nil).Bounds(); got != image.Rect(0, 0, 4, 2) {
		t.Fatalf("scale 0 bounds %v, want 4x2", got)
	}
}
