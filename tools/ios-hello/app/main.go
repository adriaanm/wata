// The product hello (plan 0044 stage 2): the ios-spike's assertions
// re-taken through the PRODUCT packages — iosshell owns the UIKit entry and
// the window, iosui owns the frame hop, the casts, the raw-RGBA crossing and
// the render probe; this main is only composition, the way the Sgola side
// will compose them in stage 3. Every proof prints one `hello: <fact>` line;
// the harness (tools/ios-hello/hello.py) asserts on the set:
//
//   window visible    — iosshell brought UIKit up and ran the ready hop
//   root adopted      — AdoptRoot spliced a stage root into the container
//   label …           — cast facets + a facade read on a UILabel
//   onmain round-trip — iosui.OnMain from a NON-main goroutine (the pump's
//                       frame hop) landed back on the main queue
//   probe …           — RenderViewRGBA read back the exact colours UIKit
//                       was told to paint: the top/bottom bands pin the
//                       buffer's row orientation, the image pixel pins the
//                       raw-RGBA-to-UIImage crossing end to end
//
//go:build darwin

package main

import (
	"fmt"
	"os"
	"time"

	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/adriaanm/wata/go-pkgs/iosshell"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/ebitengine/purego"
)

const band = 80 // the coloured bands' height (points)

var (
	root  uikit.UIView
	label uikit.UIView
	imgV  uikit.UIView
)

func say(format string, a ...any) {
	fmt.Printf("hello: "+format+"\n", a...)
	os.Stdout.Sync()
}

func main() {
	iosshell.Start()
	// Watchdog: never wedge a CI run on a stuck runloop.
	go func() {
		time.Sleep(80 * time.Second)
		say("FAIL watchdog: checks never completed")
		os.Exit(2)
	}()
	say("calling UIApplicationMain")
	iosshell.RunApp(purego.NewCallback(func() uintptr { ready(); return 0 }))
}

func rgb(r, g, b float64) uikit.UIColor {
	return uikit.GetUIColorClass().ColorWithRedGreenBlueAlpha(r, g, b, 1)
}

func newRect(f uikit.CGRect, c uikit.UIColor) uikit.UIView {
	v := uikit.GetUIViewClass().Alloc().InitWithFrame(f)
	v.SetBackgroundColor(c)
	return v
}

// ready runs on the main thread once iosshell's window is key and visible.
func ready() {
	b := iosshell.ContainerBounds()
	w, h := b.Size.Width, b.Size.Height
	say("window visible %.0fx%.0f", w, h)

	root = newRect(uikit.CGRect{Size: b.Size}, rgb(0.1, 0.1, 0.1))
	// Two bands pin the render probe's row orientation: red TOP, blue BOTTOM.
	root.AddSubview(newRect(uikit.CGRect{Size: uikit.CGSize{Width: w, Height: band}}, rgb(1, 0, 0)))
	root.AddSubview(newRect(uikit.CGRect{
		Origin: uikit.CGPoint{Y: h - band},
		Size:   uikit.CGSize{Width: w, Height: band}}, rgb(0, 0, 1)))

	// A label through the cast facets: alloc as UILabel, init through the
	// UIView facet, text/colour through the facade's own UILabel methods.
	label = iosui.AllocLabelAsView().InitWithFrame(uikit.CGRect{
		Origin: uikit.CGPoint{X: 20, Y: 120},
		Size:   uikit.CGSize{Width: w - 40, Height: 44}})
	iosui.AsLabel(label).SetText("wata ios hello")
	iosui.AsLabel(label).SetTextColor(uikit.GetUIColorClass().WhiteColor())
	root.AddSubview(label)

	// The raw-RGBA crossing: a 2x2 all-magenta image, stretched over a
	// 40x40 image view (scale-to-fill is UIImageView's default).
	pix := make([]byte, 0, 16)
	for i := 0; i < 4; i++ {
		pix = append(pix, 0xff, 0x00, 0xff, 0xff)
	}
	img := iosui.ImageFromRGBA(pix, 2, 2)
	imgV = iosui.AllocImageViewAsView().InitWithFrame(uikit.CGRect{
		Origin: uikit.CGPoint{X: 20, Y: 200},
		Size:   uikit.CGSize{Width: 40, Height: 40}})
	iosui.AsImageView(imgV).SetImage(img)
	root.AddSubview(imgV)

	iosshell.AdoptRoot(root)
	say("root adopted, %d subviews", iosui.SubviewCount(root))
	say("label class=%s text=%q", iosui.ViewClassName(label), iosui.AsLabel(label).Text())

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
	top := at(w/2, band/2)
	bottom := at(w/2, h-band/2)
	imgPix := iosui.RenderPixel(imgV, 20, 20)
	say("probe %dx%d top=%06x bottom=%06x image=%06x", w, h, top, bottom, imgPix)
	if top == 0xff0000 && bottom == 0x0000ff && imgPix == 0xff00ff {
		say("offscreen pixel probe PASS")
		say("all checks passed")
	} else {
		say("FAIL pixel probe: want top=ff0000 bottom=0000ff image=ff00ff")
	}
}
