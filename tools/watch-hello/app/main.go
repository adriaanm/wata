// The watch product hello (plan 0069 stage 1, productized): the spike's
// assertions re-taken through the PRODUCT packages — watchshell owns the
// WatchKit entry, the scene join and the window; iosui, unchanged from the
// phone, owns the frame hop, the casts, the raw-RGBA crossing and the
// render probe. This main is only composition, the way the Sgola side will
// compose them next. Every proof prints one `hello: <fact>` line; the
// harness (tools/watch-hello/hello.py) asserts on the set:
//
//	window visible    — watchshell brought the WatchKit lifecycle up, joined
//	                    watchOS's scene and ran the ready hop
//	root adopted      — AdoptRoot spliced a stage root into the container
//	label …           — cast facets + a facade read on a UILabel, on a
//	                    platform whose headers say UILabel does not exist
//	onmain round-trip — iosui.OnMain from a NON-main goroutine (the pump's
//	                    frame hop) landed back on the main queue
//	probe …           — RenderViewRGBA read back the exact colours UIKit was
//	                    told to paint: the bands pin the buffer's row
//	                    orientation, the image pixel pins the raw-RGBA-to-
//	                    UIImage crossing end to end
//
// The only thing that differs from the phone's hello is geometry: a watch
// container is ~200x240 points, so the bands are a fraction of the height
// rather than a fixed 80.
//
//go:build darwin

package main

import (
	"fmt"
	"os"
	"time"

	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/adriaanm/wata/go-pkgs/watchshell"
	"github.com/ebitengine/purego"
)

var (
	root  uikit.UIView
	label uikit.UIView
	imgV  uikit.UIView
	band  float64 // the coloured bands' height (points), a third of the screen
)

func say(format string, a ...any) {
	fmt.Printf("hello: "+format+"\n", a...)
	os.Stdout.Sync()
}

func main() {
	watchshell.Start()
	// Watchdog: never wedge a CI run on a stuck runloop.
	go func() {
		time.Sleep(80 * time.Second)
		say("FAIL watchdog: checks never completed")
		os.Exit(2)
	}()
	say("calling WKApplicationMain")
	watchshell.RunApp(purego.NewCallback(func() uintptr { ready(); return 0 }))
}

func rgb(r, g, b float64) uikit.UIColor {
	return uikit.GetUIColorClass().ColorWithRedGreenBlueAlpha(r, g, b, 1)
}

func newRect(f uikit.CGRect, c uikit.UIColor) uikit.UIView {
	v := uikit.GetUIViewClass().Alloc().InitWithFrame(f)
	v.SetBackgroundColor(c)
	return v
}

// ready runs on the main thread once watchshell's window is on the scene.
func ready() {
	b := watchshell.ContainerBounds()
	w, h := b.Size.Width, b.Size.Height
	say("window visible %.0fx%.0f", w, h)
	band = h / 3

	root = newRect(uikit.CGRect{Size: b.Size}, rgb(0.1, 0.1, 0.1))
	// Two bands pin the render probe's row orientation: red TOP, blue BOTTOM.
	root.AddSubview(newRect(uikit.CGRect{
		Size: uikit.CGSize{Width: w, Height: band}}, rgb(1, 0, 0)))
	root.AddSubview(newRect(uikit.CGRect{
		Origin: uikit.CGPoint{Y: h - band},
		Size:   uikit.CGSize{Width: w, Height: band}}, rgb(0, 0, 1)))

	// A label through the cast facets: alloc as UILabel, init through the
	// UIView facet, text/colour through the facade's own UILabel methods.
	label = iosui.AllocLabelAsView().InitWithFrame(uikit.CGRect{
		Origin: uikit.CGPoint{X: 8, Y: band + 8},
		Size:   uikit.CGSize{Width: w - 16, Height: 28}})
	iosui.AsLabel(label).SetText("wata watch hello")
	iosui.AsLabel(label).SetTextColor(uikit.GetUIColorClass().WhiteColor())
	root.AddSubview(label)

	// The raw-RGBA crossing: a 2x2 all-magenta image stretched over a 32x32
	// image view (scale-to-fill is UIImageView's default). This is the same
	// crossing a full-screen framebuffer would use.
	pix := make([]byte, 0, 16)
	for i := 0; i < 4; i++ {
		pix = append(pix, 0xff, 0x00, 0xff, 0xff)
	}
	img := iosui.ImageFromRGBA(pix, 2, 2)
	imgV = iosui.AllocImageViewAsView().InitWithFrame(uikit.CGRect{
		Origin: uikit.CGPoint{X: 8, Y: band + 44},
		Size:   uikit.CGSize{Width: 32, Height: 32}})
	iosui.AsImageView(imgV).SetImage(img)
	root.AddSubview(imgV)

	watchshell.AdoptRoot(root)
	say("root adopted, %d subviews", iosui.SubviewCount(root))
	say("label class=%s text=%q", iosui.ViewClassName(label),
		iosui.AsLabel(label).Text())

	// The frame hop, exactly as the pump will use it: from a NON-main
	// goroutine, enqueue a registered trampoline on the main queue.
	probeTramp := purego.NewCallback(func() uintptr { probe(); return 0 })
	go iosui.OnMain(probeTramp)
}

// probe runs on the main queue via iosui.OnMain and asserts the pixels.
func probe() {
	say("callback onmain round-trip")
	rgba, w, h := iosui.RenderViewRGBA(root)
	at := func(x, y int) int {
		i := 4 * (y*w + x)
		return int(rgba[i])<<16 | int(rgba[i+1])<<8 | int(rgba[i+2])
	}
	top := at(w/2, int(band)/2)
	bottom := at(w/2, h-int(band)/2)
	imgPix := iosui.RenderPixel(imgV, 16, 16)
	say("probe %dx%d top=%06x bottom=%06x image=%06x", w, h, top, bottom, imgPix)
	if top == 0xff0000 && bottom == 0x0000ff && imgPix == 0xff00ff {
		say("offscreen pixel probe PASS")
		say("all checks passed")
	} else {
		say("FAIL pixel probe: want top=ff0000 bottom=0000ff image=ff00ff")
	}
}
