// The watchOS spike (plan 0069 stage 1): what a Go binary can actually do
// on a watch, asked one printed line at a time.
//
// Stage 0 proved a Go archive LINKS into a watchOS binary. This asks the
// next three questions, cheapest first, and every answer is a line the
// harness asserts rather than a thing we concluded:
//
//  1. Does a plain Go executable run as a watch app at all? The Go runtime
//     starting and reaching main is the whole of it — everything below is
//     unreachable if this is not true.
//  2. Which ObjC classes EXIST at runtime? watchOS's UIKit headers mark
//     UIView & co API_UNAVAILABLE(watchos) and UIKit.tbd exports no classes
//     (it re-exports UIKitCore, which is not in the SDK) — but neither of
//     those is a statement about the runtime. SwiftUI on watchOS is itself
//     built over UIKitCore, so the classes are very likely THERE. If they
//     are, a wata-ios-shaped retained stage may port to the watch nearly
//     wholesale, which is a far better outcome for a single-language tree
//     than driving storyboard outlets. If they are not, the WatchKit path
//     below is the only one.
//  3. Is the WatchKit surface reachable the way our other Apple clients
//     reach ObjC — dlopen + the objc runtime? WKApplicationMain is an
//     exported C symbol and WKInterfaceImage/WKCrownSequencer/the gesture
//     recognizers are real ObjC classes, so this should hold; it is asked
//     anyway because the cost of asking is one line.
//
// Deliberately NOT done here: calling WKApplicationMain. It never returns,
// so it would end the probe's ability to report anything else; it gets its
// own stage once this one says what the runtime contains.
package main

import (
	"fmt"
	"os"
	"runtime"
	"strings"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

func init() {
	// Whatever we end up calling owns the main thread, so pin it the way
	// every other wata Apple shell does, before anything else runs.
	runtime.LockOSThread()
}

// The frameworks worth having loaded before asking what exists: a class
// only appears once the image defining it is mapped, so a "missing" class
// with its framework unloaded proves nothing.
var frameworks = []string{
	"/System/Library/Frameworks/Foundation.framework/Foundation",
	"/System/Library/Frameworks/UIKit.framework/UIKit",
	"/System/Library/Frameworks/WatchKit.framework/WatchKit",
	"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
}

// What the answer to question 2 turns on. The UIKit names are the ones a
// retained stage needs (wata-ios builds exactly these); the WatchKit names
// are the fallback path's, and the raster names are what either path needs
// to get pixels on screen at all.
var probeClasses = []string{
	// the retained-stage hope
	"UIWindow", "UIView", "UIViewController", "UILabel", "UIApplication",
	"UIScreen", "UIImageView", "UIColor", "UIFont", "UIBezierPath",
	// the raster, needed either way
	"UIImage", "UIGraphicsImageRenderer", "CALayer",
	// the sanctioned WatchKit path
	"WKApplication", "WKInterfaceController", "WKInterfaceImage",
	"WKInterfaceGroup", "WKCrownSequencer", "WKTapGestureRecognizer",
	"WKLongPressGestureRecognizer", "WKInterfaceDevice",
	// what an extended session / haptics would need later
	"WKExtendedRuntimeSession", "WKHapticType",
}

// set by the `audio` argv mode; read in wkapp's ready hop.
var wantAudio bool

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "uiapp":
			runUIApp()
			return
		case "wkapp":
			runWKApp()
			return
		case "audio":
			// the wkapp shell, plus the audio probe once the UI is up:
			// AVAudioSession wants a real running app, not a bare main.
			wantAudio = true
			runWKApp()
			return
		case "net":
			runNet()
			return
		}
	}
	fmt.Println("watchspike: go main entered")
	fmt.Printf("watchspike: runtime %s %s/%s cpus=%d\n",
		runtime.Version(), runtime.GOOS, runtime.GOARCH, runtime.NumCPU())
	fmt.Printf("watchspike: argv %v\n", os.Args)

	loaded := 0
	for _, fw := range frameworks {
		name := fw[strings.LastIndex(fw, "/")+1:]
		if _, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
			fmt.Printf("watchspike: dlopen %s FAILED %v\n", name, err)
			continue
		}
		loaded++
		fmt.Printf("watchspike: dlopen %s ok\n", name)
	}
	fmt.Printf("watchspike: frameworks %d/%d loaded\n", loaded, len(frameworks))

	present := 0
	for _, name := range probeClasses {
		c := objc.GetClass(name)
		if c != 0 {
			present++
		}
		fmt.Printf("watchspike: class %-28s %v\n", name, c != 0)
	}
	fmt.Printf("watchspike: classes %d/%d present\n", present, len(probeClasses))

	// The C entry point the no-Swift shell would call. Binding it proves the
	// symbol resolves through purego; it is deliberately NOT called.
	wk, err := purego.Dlopen("/System/Library/Frameworks/WatchKit.framework/WatchKit",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY)
	if err == nil {
		if sym, err := purego.Dlsym(wk, "WKApplicationMain"); err == nil && sym != 0 {
			fmt.Println("watchspike: WKApplicationMain resolved (not called)")
		} else {
			fmt.Printf("watchspike: WKApplicationMain UNRESOLVED %v\n", err)
		}
	}

	// A live ObjC round trip, so "the class exists" is backed by a call that
	// actually went through objc_msgSend on this platform.
	if d := objc.GetClass("WKInterfaceDevice"); d != 0 {
		dev := objc.ID(d).Send(objc.RegisterName("currentDevice"))
		if dev != 0 {
			bounds := dev.Send(objc.RegisterName("screenBounds"))
			_ = bounds
			scale := dev.Send(objc.RegisterName("screenScale"))
			fmt.Printf("watchspike: WKInterfaceDevice currentDevice ok scale-id=%v\n", scale != 0)
		}
	}

	probeEntryPoints()
	probeUIKitLive()

	fmt.Println("watchspike: all checks passed")
}

// Which platform entry points a no-Swift shell could actually call. The
// class table says UIKit's classes EXIST; that is not the same as watchOS
// letting an app own a UIApplication, so both C entry points are asked for
// by name. Neither is called.
func probeEntryPoints() {
	for _, e := range []struct{ fw, sym string }{
		{"/System/Library/Frameworks/UIKit.framework/UIKit", "UIApplicationMain"},
		{"/System/Library/Frameworks/WatchKit.framework/WatchKit", "WKApplicationMain"},
		{"/System/Library/Frameworks/WatchKit.framework/WatchKit", "WKExtensionMain"},
	} {
		h, err := purego.Dlopen(e.fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			fmt.Printf("watchspike: entry %-18s dlopen-failed\n", e.sym)
			continue
		}
		sym, err := purego.Dlsym(h, e.sym)
		fmt.Printf("watchspike: entry %-18s %v\n", e.sym, err == nil && sym != 0)
	}
}

// Do the UIKit classes WORK, or are they merely mapped? Everything here is
// object work with no window and no runloop — allocating a view, sizing it,
// reaching its layer, building an image from raw pixels — because that is
// exactly what a retained stage does between frames. A class that is
// present but refuses to initialize would fail here rather than in stage 2.
func probeUIKitLive() {
	pool := objc.ID(objc.GetClass("NSAutoreleasePool")).
		Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
	defer pool.Send(objc.RegisterName("release"))

	selAlloc := objc.RegisterName("alloc")
	selInit := objc.RegisterName("init")

	// A live UIView, and its CALayer. The stage's whole element table is
	// views; if alloc/init works the port is mechanical.
	v := objc.ID(objc.GetClass("UIView")).Send(selAlloc).Send(selInit)
	fmt.Printf("watchspike: UIView alloc/init %v\n", v != 0)
	if v != 0 {
		layer := v.Send(objc.RegisterName("layer"))
		fmt.Printf("watchspike: UIView layer %v\n", layer != 0)
		sub := objc.ID(objc.GetClass("UILabel")).Send(selAlloc).Send(selInit)
		v.Send(objc.RegisterName("addSubview:"), sub)
		subs := v.Send(objc.RegisterName("subviews"))
		// Send returns the raw register, so an NSUInteger return reads
		// straight off the ID.
		n := uint64(subs.Send(objc.RegisterName("count")))
		fmt.Printf("watchspike: UIView addSubview -> %d subviews\n", n)
	}

	// The screen the watch reports, through UIKit rather than WatchKit.
	if s := objc.ID(objc.GetClass("UIScreen")).
		Send(objc.RegisterName("mainScreen")); s != 0 {
		fmt.Println("watchspike: UIScreen mainScreen ok")
	} else {
		fmt.Println("watchspike: UIScreen mainScreen NIL")
	}

	// A UIWindow — the object watchOS is least likely to hand an app.
	w := objc.ID(objc.GetClass("UIWindow")).Send(selAlloc).Send(selInit)
	fmt.Printf("watchspike: UIWindow alloc/init %v\n", w != 0)

	// The raster path either architecture needs: raw RGBA -> UIImage, via
	// CGDataProvider/CGImage. This is what wata-fb's framebuffer becomes.
	fmt.Printf("watchspike: UIImage from pixels %v\n", imageFromPixels())
}

// UIImage out of a 2x2 RGBA buffer through CoreGraphics, the way a
// framebuffer would arrive. Returns whether a non-nil UIImage came back.
func imageFromPixels() bool {
	cg, err := purego.Dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY)
	if err != nil {
		return false
	}
	var (
		newDeviceRGB     func() uintptr
		dataProviderData func(info uintptr, data []byte, size uint64, rel uintptr) uintptr
		imageCreate      func(w, h, bpc, bpp, bpr uint64, cs uintptr, bi uint32,
			provider uintptr, decode uintptr, interp bool, intent uint32) uintptr
	)
	purego.RegisterLibFunc(&newDeviceRGB, cg, "CGColorSpaceCreateDeviceRGB")
	purego.RegisterLibFunc(&dataProviderData, cg, "CGDataProviderCreateWithData")
	purego.RegisterLibFunc(&imageCreate, cg, "CGImageCreate")

	const w, h = 2, 2
	px := make([]byte, w*h*4)
	for i := range px {
		px[i] = 0xff
	}
	cs := newDeviceRGB()
	prov := dataProviderData(0, px, uint64(len(px)), 0)
	if cs == 0 || prov == 0 {
		return false
	}
	// kCGImageAlphaPremultipliedLast | kCGBitmapByteOrderDefault
	img := imageCreate(w, h, 8, 32, w*4, cs, 1, prov, 0, false, 0)
	if img == 0 {
		return false
	}
	ui := objc.ID(objc.GetClass("UIImage")).
		Send(objc.RegisterName("imageWithCGImage:"), img)
	return ui != 0
}
