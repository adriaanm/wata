//go:build gioshell

// The draw path, end to end, without a screen: the same view the window lays
// out, rendered through Gio's headless GPU surface and read back pixel by
// pixel. pixels_test.go proves the conversion and the scaling; this proves
// that what Gio actually PUTS ON THE SURFACE is those exact bytes — no
// filtering, no colour management, no half-pixel offset between the frame and
// its destination rectangle.
//
// That is the golden-equivalence claim at the window level: the frames the
// fb-ui goldens pin reach a phone or desktop screen unchanged, magnified by an
// integer factor and nothing else.
//
// It needs a GPU context (Metal on macOS) but no display, so it runs wherever
// a Go test can allocate one; it skips loudly when it cannot.

package gioshell

import (
	"image"
	"testing"

	"gioui.org/f32"
	"gioui.org/font/gofont"
	"gioui.org/gpu/headless"
	"gioui.org/io/pointer"
	"gioui.org/layout"
	"gioui.org/op"
	"gioui.org/text"
	"gioui.org/unit"
	"gioui.org/widget/material"
)

func TestBlitRendersFrameExactly(t *testing.T) {
	const (
		srcW, srcH = 160, 128
		winW, winH = 700, 700
		scale      = 3
	)
	// a frame whose every pixel is a distinct function of its coordinates,
	// so a shifted or resampled blit cannot accidentally match.
	raw := make([]byte, srcW*srcH*2)
	for y := 0; y < srcH; y++ {
		for x := 0; x < srcW; x++ {
			put(raw, srcW, x, y, rgb565(x%32, (x*3+y)%64, y%32))
		}
	}
	s := &shellState{w: srcW, h: srcH, scale: scale, latest: raw,
		keys: make(chan int, 4), done: make(chan struct{})}

	th := material.NewTheme()
	th.Shaper = text.NewShaper(text.WithCollection(gofont.Collection()))
	v := &view{s: s, th: th, held: -1}

	win, err := headless.NewWindow(winW, winH)
	if err != nil {
		t.Skipf("no headless GPU context here: %v", err)
	}
	defer win.Release()

	ops := new(op.Ops)
	gtx := layout.Context{
		Ops:         ops,
		Metric:      unit.Metric{PxPerDp: 1, PxPerSp: 1},
		Constraints: layout.Exact(image.Pt(winW, winH)),
	}
	v.layout(gtx)
	if err := win.Frame(ops); err != nil {
		t.Fatalf("frame: %v", err)
	}
	shot := image.NewRGBA(image.Rect(0, 0, winW, winH))
	if err := win.Screenshot(shot); err != nil {
		t.Fatalf("screenshot: %v", err)
	}

	if v.dstScale != scale {
		t.Fatalf("blit scale %d, want %d", v.dstScale, scale)
	}
	if got := v.dst.Size(); got != image.Pt(srcW*scale, srcH*scale) {
		t.Fatalf("blit size %v, want %v", got, image.Pt(srcW*scale, srcH*scale))
	}
	want := Expand(raw, srcW, srcH, nil)
	for y := 0; y < srcH; y++ {
		for x := 0; x < srcW; x++ {
			wo := want.PixOffset(x, y)
			// the middle of the magnified block: the corners are where a
			// half-pixel error would hide, so check one of those too.
			for _, at := range []image.Point{
				{X: x*scale + scale/2, Y: y*scale + scale/2},
				{X: x * scale, Y: y * scale},
			} {
				p := at.Add(v.dst.Min)
				so := shot.PixOffset(p.X, p.Y)
				for c := 0; c < 3; c++ {
					if shot.Pix[so+c] != want.Pix[wo+c] {
						t.Fatalf("source (%d,%d) at window %v channel %d: got %d, want %d",
							x, y, p, c, shot.Pix[so+c], want.Pix[wo+c])
					}
				}
			}
		}
	}
}

// The chrome must not eat the panel: at a window too small for even a 1x
// frame the blit still lands (clipped), and at a generous one the frame is
// centred with the button row below it.
func TestLayoutGeometry(t *testing.T) {
	const srcW, srcH = 160, 128
	s := &shellState{w: srcW, h: srcH, latest: make([]byte, srcW*srcH*2),
		keys: make(chan int, 4), done: make(chan struct{})}
	th := material.NewTheme()
	th.Shaper = text.NewShaper(text.WithCollection(gofont.Collection()))

	for _, tc := range []struct{ w, h, wantScale int }{
		{100, 100, 1},   // smaller than the chrome itself: clamp to 1x
		{700, 700, 4},   // 4x both ways (520px of panel height, 128*4=512)
		{1400, 1200, 7}, // 8x by width, but the panel height caps it at 7x
	} {
		v := &view{s: s, th: th, held: -1}
		gtx := layout.Context{
			Ops:         new(op.Ops),
			Metric:      unit.Metric{PxPerDp: 1, PxPerSp: 1},
			Constraints: layout.Exact(image.Pt(tc.w, tc.h)),
		}
		v.layout(gtx)
		if v.dstScale != tc.wantScale {
			t.Errorf("%dx%d: scale %d, want %d", tc.w, tc.h, v.dstScale, tc.wantScale)
		}
		if len(v.zones) != 5 {
			t.Errorf("%dx%d: %d touch zones, want 5", tc.w, tc.h, len(v.zones))
		}
		// the PTT zone is the wide one, and it is the last
		if ptt := v.zones[len(v.zones)-1]; ptt.code != KeyPtt {
			t.Errorf("%dx%d: last zone is %d, want PTT", tc.w, tc.h, ptt.code)
		}
	}
}

// A press inside a zone sends that key down, and the matching release sends it
// up — press-and-hold, which is the whole of PTT.
func TestPointerHoldSendsBothEdges(t *testing.T) {
	s := &shellState{w: 160, h: 128, latest: make([]byte, 160*128*2),
		keys: make(chan int, 8), done: make(chan struct{})}
	st.Store(s)
	defer st.Store(nil)
	v := &view{s: s, held: -1, zones: []zone{
		{r: image.Rect(0, 0, 10, 10), code: KeyEnter},
		{r: image.Rect(0, 20, 100, 40), code: KeyPtt},
	}}
	v.pointer(ptrEvent(pressKind, 50, 30))
	v.pointer(ptrEvent(releaseKind, 50, 30))
	if got := NextKey(); got != KeyPtt*2+1 {
		t.Fatalf("first event %d, want PTT press", got)
	}
	if got := NextKey(); got != KeyPtt*2 {
		t.Fatalf("second event %d, want PTT release", got)
	}
	if got := NextKey(); got != -1 {
		t.Fatalf("queue not empty: %d", got)
	}
	// a press outside every zone holds nothing, and its release is silent
	v.pointer(ptrEvent(pressKind, 500, 500))
	v.pointer(ptrEvent(releaseKind, 500, 500))
	if got := NextKey(); got != -1 {
		t.Fatalf("a press on no zone produced %d", got)
	}
}

const (
	pressKind   = pointer.Press
	releaseKind = pointer.Release
)

func ptrEvent(kind pointer.Kind, x, y float32) pointer.Event {
	return pointer.Event{Kind: kind, Position: f32.Pt(x, y)}
}
