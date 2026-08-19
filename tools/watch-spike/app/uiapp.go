// The `uiapp` mode: does watchOS let a Go binary own UIApplicationMain and
// put a REAL UIKit window on the watch's screen?
//
// This is the question the class table (main.go) raised but could not
// settle. watchOS's UIKit headers mark UIView & co API_UNAVAILABLE(watchos)
// and its UIKit.tbd exports no classes, yet the runtime has all of them and
// UIApplicationMain resolves. If an app may actually drive them, then the
// watch client is wata-ios's retained stage with a different screen size,
// and nothing about the plan's "the stage cannot be a view tree" holds.
//
// The shape is iosshell's, deliberately: synthesize a delegate, take the
// launch callback, size a window off UIScreen, give it a root controller,
// make it key and visible, and splice in a container. The one thing added
// is a red-top/blue-bottom raster in a UIImageView — the same orientation
// pin wata-ios uses — so a screenshot proves pixels reached the panel
// rather than proving only that no call returned nil.
package main

import (
	"fmt"
	"unsafe"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

type cgPoint struct{ X, Y float64 }
type cgSize struct{ Width, Height float64 }
type cgRect struct {
	Origin cgPoint
	Size   cgSize
}

const uiDelegateClass = "WataWatchSpikeDelegate"

var uiApplicationMain func(argc int32, argv uintptr, principal, delegate objc.ID) int32

// nsString mints an autoreleased NSString. The spike does not depend on
// go-pkgs/appleptt (it is deliberately standalone), so this is the one
// helper it needs of its own.
func nsString(s string) objc.ID {
	b := append([]byte(s), 0)
	return objc.ID(objc.GetClass("NSString")).Send(
		objc.RegisterName("stringWithUTF8String:"),
		unsafe.Pointer(&b[0]))
}

func runUIApp() {
	fmt.Println("watchspike: uiapp mode — claiming UIApplicationMain")
	var uikitLib uintptr
	for _, fw := range []string{
		"/System/Library/Frameworks/Foundation.framework/Foundation",
		"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
		"/System/Library/Frameworks/UIKit.framework/UIKit",
	} {
		h, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			fmt.Printf("watchspike: uiapp dlopen FAILED %s %v\n", fw, err)
			fmt.Println("watchspike: FAIL")
			return
		}
		uikitLib = h // UIKit is last, so this ends holding it
	}
	purego.RegisterLibFunc(&uiApplicationMain, uikitLib, "UIApplicationMain")

	selDidLaunch := objc.RegisterName("application:didFinishLaunchingWithOptions:")
	methods := []objc.MethodDef{
		{Cmd: selDidLaunch, Fn: func(self objc.ID, _ objc.SEL, app, opts objc.ID) bool {
			didLaunch()
			return true
		}},
	}
	if _, err := objc.RegisterClass(uiDelegateClass, objc.GetClass("NSObject"),
		nil, nil, methods); err != nil {
		fmt.Printf("watchspike: uiapp RegisterClass FAILED %v\n", err)
		fmt.Println("watchspike: FAIL")
		return
	}
	fmt.Println("watchspike: uiapp delegate class registered")
	// Never returns if watchOS accepts it. If it DOES return, that is
	// itself the finding, so it is printed.
	rc := uiApplicationMain(0, 0, 0, nsString(uiDelegateClass))
	fmt.Printf("watchspike: uiapp UIApplicationMain RETURNED %d\n", rc)
	fmt.Println("watchspike: FAIL")
}

func didLaunch() {
	fmt.Println("watchspike: uiapp didFinishLaunching entered")
	selAlloc := objc.RegisterName("alloc")
	selInit := objc.RegisterName("init")

	scr := objc.ID(objc.GetClass("UIScreen")).Send(objc.RegisterName("mainScreen"))
	b := objc.Send[cgRect](scr, objc.RegisterName("bounds"))
	fmt.Printf("watchspike: uiapp screen %.0fx%.0f\n", b.Size.Width, b.Size.Height)

	w := objc.ID(objc.GetClass("UIWindow")).Send(selAlloc).
		Send(objc.RegisterName("initWithFrame:"), b)
	vc := objc.ID(objc.GetClass("UIViewController")).Send(selAlloc).Send(selInit)
	w.Send(objc.RegisterName("setRootViewController:"), vc)
	w.Send(objc.RegisterName("makeKeyAndVisible"))
	fmt.Println("watchspike: uiapp window key and visible")

	cv := vc.Send(objc.RegisterName("view"))
	// The raster: red top half, blue bottom half, the row-orientation pin.
	img := rasterImage(int(b.Size.Width), int(b.Size.Height))
	if img == 0 {
		fmt.Println("watchspike: uiapp raster FAILED")
		fmt.Println("watchspike: FAIL")
		return
	}
	iv := objc.ID(objc.GetClass("UIImageView")).Send(selAlloc).
		Send(objc.RegisterName("initWithFrame:"), b)
	iv.Send(objc.RegisterName("setImage:"), img)
	cv.Send(objc.RegisterName("addSubview:"), iv)

	lbl := objc.ID(objc.GetClass("UILabel")).Send(selAlloc).
		Send(objc.RegisterName("initWithFrame:"), cgRect{
			Origin: cgPoint{X: 0, Y: b.Size.Height/2 - 14},
			Size:   cgSize{Width: b.Size.Width, Height: 28},
		})
	lbl.Send(objc.RegisterName("setText:"), nsString("wata watch"))
	lbl.Send(objc.RegisterName("setTextAlignment:"), 1) // centered
	lbl.Send(objc.RegisterName("setTextColor:"),
		objc.ID(objc.GetClass("UIColor")).Send(objc.RegisterName("whiteColor")))
	cv.Send(objc.RegisterName("addSubview:"), lbl)

	subs := cv.Send(objc.RegisterName("subviews"))
	n := uint64(subs.Send(objc.RegisterName("count")))
	fmt.Printf("watchspike: uiapp root adopted, %d subviews\n", n)

	// Retention: nothing here may be collected once this returns.
	keep = append(keep, w, vc, iv, lbl, img)
	fmt.Println("watchspike: all checks passed")
}

var keep []objc.ID

// rasterImage builds a w x h UIImage, red top half / blue bottom half, out
// of a raw RGBA buffer — the shape wata-fb's framebuffer would arrive in.
func rasterImage(w, h int) objc.ID {
	if w <= 0 || h <= 0 {
		return 0
	}
	cg, err := purego.Dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY)
	if err != nil {
		return 0
	}
	var (
		newDeviceRGB func() uintptr
		provider     func(info uintptr, data []byte, size uint64, rel uintptr) uintptr
		imageCreate  func(w, h, bpc, bpp, bpr uint64, cs uintptr, bi uint32,
			p uintptr, decode uintptr, interp bool, intent uint32) uintptr
	)
	purego.RegisterLibFunc(&newDeviceRGB, cg, "CGColorSpaceCreateDeviceRGB")
	purego.RegisterLibFunc(&provider, cg, "CGDataProviderCreateWithData")
	purego.RegisterLibFunc(&imageCreate, cg, "CGImageCreate")

	px := make([]byte, w*h*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			i := (y*w + x) * 4
			if y < h/2 {
				px[i], px[i+3] = 0xff, 0xff // red
			} else {
				px[i+2], px[i+3] = 0xff, 0xff // blue
			}
		}
	}
	pixels = px // the provider does not copy; keep it alive
	cs := newDeviceRGB()
	p := provider(0, px, uint64(len(px)), 0)
	if cs == 0 || p == 0 {
		return 0
	}
	img := imageCreate(uint64(w), uint64(h), 8, 32, uint64(w*4), cs, 1, p, 0, false, 0)
	if img == 0 {
		return 0
	}
	return objc.ID(objc.GetClass("UIImage")).
		Send(objc.RegisterName("imageWithCGImage:"), img)
}

var pixels []byte
