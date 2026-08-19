// The `wkapp` mode: a watch app whose entry point, delegate, root
// controller and every pixel are Go, with no Swift and no storyboard.
//
// Four findings shaped it, each from a run rather than a reading, and each
// one is why a line here looks the way it does:
//
//   - watchOS will NOT let a binary claim UIApplicationMain (`uiapp` mode):
//     it blocks forever and the launch callback never fires. A watch app is
//     started by WatchKit's lifecycle, not UIKit's, and that is not
//     negotiable.
//   - WKApplicationMain with a bare delegate ABORTS, and watchOS says why
//     in its own log: the class "doesn't conform to the WKExtensionDelegate
//     protocol". Implementing the methods is not enough — the protocol is
//     checked by name, so it is attached explicitly (objc.RegisterClass
//     takes protocols, objc.GetProtocol resolves one at runtime).
//   - It then aborts again for want of an entry point: "No interface
//     description file Interface.plist … and extensionDelegate didn't
//     return a applicationRootInterfaceControllerClass." The second half of
//     that sentence is the way out — a delegate that ANSWERS with a root
//     controller class needs no Interface.plist, hence no storyboard, no
//     ibtool, and none of the WatchKit-storyboard deprecation.
//   - A UIWindow built by the app is not composited until it belongs to a
//     scene: minting one and calling makeKeyAndVisible leaves the screen
//     BLACK and isKeyWindow 0. watchOS does run a real UIWindowScene with a
//     real UIWindow in it, so joining that scene is what puts pixels on the
//     panel (isKeyWindow 1, and the screenshot shows the frame).
//
// What is left is the wata-fb model on a wrist: WatchKit owns the
// lifecycle, the app owns a UIView tree, and the frame is a raster the app
// paints. No WKInterface object is involved in showing it.
package main

import (
	"fmt"
	"unsafe"

	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

const (
	wkDelegateClass   = "WataWatchSpikeWKDelegate"
	wkControllerClass = "WataRootController"
)

var wkApplicationMain func(argc int32, argv uintptr, delegate objc.ID) int32

func runWKApp() {
	fmt.Println("watchspike: wkapp mode — WatchKit lifecycle, Go-owned controller")
	var wkLib uintptr
	for _, fw := range []string{
		"/System/Library/Frameworks/Foundation.framework/Foundation",
		"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
		"/System/Library/Frameworks/UIKit.framework/UIKit",
		"/System/Library/Frameworks/WatchKit.framework/WatchKit",
	} {
		h, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			fmt.Printf("watchspike: wkapp dlopen FAILED %s %v\n", fw, err)
			fmt.Println("watchspike: FAIL")
			return
		}
		wkLib = h // WatchKit is last
	}
	purego.RegisterLibFunc(&wkApplicationMain, wkLib, "WKApplicationMain")

	if !registerDelegate() || !registerController() {
		fmt.Println("watchspike: FAIL")
		return
	}
	rc := wkApplicationMain(0, 0, nsString(wkDelegateClass))
	fmt.Printf("watchspike: wkapp WKApplicationMain RETURNED %d\n", rc)
	fmt.Println("watchspike: FAIL")
}

// The app delegate. WatchKit checks protocol conformance by name before it
// will use the class at all, so WKExtensionDelegate is attached explicitly;
// a class that merely implements the methods is rejected.
func registerDelegate() bool {
	proto := objc.GetProtocol("WKExtensionDelegate")
	if proto == nil {
		fmt.Println("watchspike: wkapp WKExtensionDelegate protocol NOT FOUND")
		return false
	}
	fmt.Println("watchspike: wkapp WKExtensionDelegate protocol resolved")
	methods := []objc.MethodDef{
		{Cmd: objc.RegisterName("applicationDidFinishLaunching"),
			Fn: func(self objc.ID, _ objc.SEL) {
				fmt.Println("watchspike: wkapp applicationDidFinishLaunching")
			}},
		{Cmd: objc.RegisterName("applicationDidBecomeActive"),
			Fn: func(self objc.ID, _ objc.SEL) {
				fmt.Println("watchspike: wkapp applicationDidBecomeActive")
			}},
		// The storyboard-free entry point. watchOS's own error names this
		// as the alternative to an interface description file: a delegate
		// that answers with a root controller CLASS needs no Interface.plist
		// and therefore no storyboard, no ibtool and no deprecation.
		{Cmd: objc.RegisterName("applicationRootInterfaceControllerClass"),
			Fn: func(self objc.ID, _ objc.SEL) uintptr {
				c := objc.GetClass(wkControllerClass)
				fmt.Printf("watchspike: wkapp rootInterfaceControllerClass asked, "+
					"answering %s (%v)\n", wkControllerClass, c != 0)
				return uintptr(c)
			}},
	}
	if _, err := objc.RegisterClass(wkDelegateClass, objc.GetClass("NSObject"),
		[]*objc.Protocol{proto}, nil, methods); err != nil {
		fmt.Printf("watchspike: wkapp delegate RegisterClass FAILED %v\n", err)
		return false
	}
	fmt.Println("watchspike: wkapp delegate class registered")
	return true
}

// The root interface controller the delegate answers with. It exists for
// the lifecycle only — WatchKit needs SOMETHING to root the app on, and its
// activation is the app's cue that a screen exists. Nothing is drawn
// through it; the pixels go through the scene's UIWindow.
func registerController() bool {
	super := objc.GetClass("WKInterfaceController")
	if super == 0 {
		fmt.Println("watchspike: wkapp WKInterfaceController NOT FOUND")
		return false
	}
	methods := []objc.MethodDef{
		{Cmd: objc.RegisterName("awakeWithContext:"),
			Fn: func(self objc.ID, _ objc.SEL, ctx objc.ID) {
				fmt.Println("watchspike: wkapp controller awakeWithContext")
				controllerSelf = self
			}},
		{Cmd: objc.RegisterName("willActivate"),
			Fn: func(self objc.ID, _ objc.SEL) {
				fmt.Println("watchspike: wkapp controller willActivate")
				controllerSelf = self
				paint()
			}},
	}
	if _, err := objc.RegisterClass(wkControllerClass, super, nil, nil,
		methods); err != nil {
		fmt.Printf("watchspike: wkapp controller RegisterClass FAILED %v\n", err)
		return false
	}
	fmt.Println("watchspike: wkapp controller class registered")
	return true
}

var (
	controllerSelf objc.ID
	painted        bool
)

// paint puts one rasterized frame on the panel: find the scene watchOS
// runs, add a UIImageView carrying the frame to the window already in it,
// and join a window of our own to the same scene. This is the per-frame
// path a real client would drive, done once.
func paint() {
	if painted {
		return
	}
	painted = true

	dev := objc.ID(objc.GetClass("WKInterfaceDevice")).
		Send(objc.RegisterName("currentDevice"))
	b := objc.Send[cgRect](dev, objc.RegisterName("screenBounds"))
	scale := objc.Send[float64](dev, objc.RegisterName("screenScale"))
	fmt.Printf("watchspike: wkapp screen %.0fx%.0f scale=%.1f\n",
		b.Size.Width, b.Size.Height, scale)

	pw := int(b.Size.Width * scale)
	ph := int(b.Size.Height * scale)

	// The question this whole spike exists to answer: with WatchKit driving
	// the lifecycle, can the app put its OWN UIKit tree on the screen? If
	// yes, wata's raster goes straight into a UIImageView and the watch
	// client is wata-fb's painter with a nicer panel.
	scr := objc.ID(objc.GetClass("UIScreen")).Send(objc.RegisterName("mainScreen"))
	sb := objc.Send[cgRect](scr, objc.RegisterName("bounds"))
	fmt.Printf("watchspike: wkapp UIScreen %.0fx%.0f\n", sb.Size.Width, sb.Size.Height)

	// A UIWindow with no windowScene is never composited — on iOS 13+ the
	// scene owns the display. So find what WatchKit already has: the shared
	// application, its connected scenes, and any window already on screen.
	// Adopting the existing window is strictly better than minting one, and
	// it is what decides whether this path can show anything at all.
	app := objc.ID(objc.GetClass("UIApplication")).
		Send(objc.RegisterName("sharedApplication"))
	fmt.Printf("watchspike: wkapp UIApplication shared %v\n", app != 0)
	var scene, existing objc.ID
	if app != 0 {
		scenes := app.Send(objc.RegisterName("connectedScenes"))
		nsc := uint64(scenes.Send(objc.RegisterName("count")))
		fmt.Printf("watchspike: wkapp connectedScenes %d\n", nsc)
		if nsc > 0 {
			scene = scenes.Send(objc.RegisterName("anyObject"))
			cn := objcClassName(scene)
			fmt.Printf("watchspike: wkapp scene class %s\n", cn)
			if scene.Send(objc.RegisterName("respondsToSelector:"),
				objc.RegisterName("windows")) != 0 {
				ws := scene.Send(objc.RegisterName("windows"))
				nw := uint64(ws.Send(objc.RegisterName("count")))
				fmt.Printf("watchspike: wkapp scene windows %d\n", nw)
				if nw > 0 {
					existing = ws.Send(objc.RegisterName("objectAtIndex:"), 0)
					fmt.Printf("watchspike: wkapp existing window class %s\n",
						objcClassName(existing))
				}
			}
		}
	}

	// Route A: paint into the window watchOS already put on the screen.
	if existing != 0 {
		ui := rasterImage(pw, ph)
		ivw := objc.ID(objc.GetClass("UIImageView")).
			Send(objc.RegisterName("alloc")).
			Send(objc.RegisterName("initWithFrame:"), sb)
		ivw.Send(objc.RegisterName("setImage:"), ui)
		existing.Send(objc.RegisterName("addSubview:"), ivw)
		existing.Send(objc.RegisterName("bringSubviewToFront:"), ivw)
		n := uint64(existing.Send(objc.RegisterName("subviews")).
			Send(objc.RegisterName("count")))
		fmt.Printf("watchspike: wkapp adopted existing window, subviews=%d\n", n)
		keep = append(keep, existing, ivw, ui)
	}

	w := objc.ID(objc.GetClass("UIWindow")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), sb)
	if w == 0 {
		fmt.Println("watchspike: wkapp UIWindow init NIL")
		fmt.Println("watchspike: FAIL")
		return
	}
	// Route B: our own window, joined to the scene so it can composite.
	if scene != 0 && w.Send(objc.RegisterName("respondsToSelector:"),
		objc.RegisterName("setWindowScene:")) != 0 {
		w.Send(objc.RegisterName("setWindowScene:"), scene)
		fmt.Println("watchspike: wkapp own window joined to scene")
	}
	vc := objc.ID(objc.GetClass("UIViewController")).
		Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
	w.Send(objc.RegisterName("setRootViewController:"), vc)
	w.Send(objc.RegisterName("setWindowLevel:"), float64(100))
	w.Send(objc.RegisterName("makeKeyAndVisible"))

	cv := vc.Send(objc.RegisterName("view"))
	ui := rasterImage(pw, ph)
	if ui == 0 {
		fmt.Println("watchspike: wkapp raster FAILED")
		fmt.Println("watchspike: FAIL")
		return
	}
	ivw := objc.ID(objc.GetClass("UIImageView")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), sb)
	ivw.Send(objc.RegisterName("setImage:"), ui)
	cv.Send(objc.RegisterName("addSubview:"), ivw)

	lbl := objc.ID(objc.GetClass("UILabel")).Send(objc.RegisterName("alloc")).
		Send(objc.RegisterName("initWithFrame:"), cgRect{
			Origin: cgPoint{X: 0, Y: sb.Size.Height/2 - 14},
			Size:   cgSize{Width: sb.Size.Width, Height: 28},
		})
	lbl.Send(objc.RegisterName("setText:"), nsString("wata watch"))
	lbl.Send(objc.RegisterName("setTextAlignment:"), 1)
	lbl.Send(objc.RegisterName("setTextColor:"),
		objc.ID(objc.GetClass("UIColor")).Send(objc.RegisterName("whiteColor")))
	cv.Send(objc.RegisterName("addSubview:"), lbl)

	n := uint64(cv.Send(objc.RegisterName("subviews")).
		Send(objc.RegisterName("count")))
	isKey := uint64(w.Send(objc.RegisterName("isKeyWindow")))
	hidden := uint64(w.Send(objc.RegisterName("isHidden")))
	fmt.Printf("watchspike: wkapp uikit window subviews=%d key=%d hidden=%d\n",
		n, isKey, hidden)
	fmt.Printf("watchspike: wkapp frame pushed %dx%d px\n", pw, ph)

	keep = append(keep, w, vc, ivw, lbl, ui)
	fmt.Println("watchspike: all checks passed")
}

// objcClassName answers an object's class name — the cheapest way to say
// what watchOS actually handed us when the type is not in any header.
func objcClassName(id objc.ID) string {
	if id == 0 {
		return "<nil>"
	}
	cls := id.Send(objc.RegisterName("class"))
	s := cls.Send(objc.RegisterName("description"))
	return nsStringGo(s)
}

// nsStringGo reads an NSString back into Go via -UTF8String.
func nsStringGo(s objc.ID) string {
	if s == 0 {
		return "<nil>"
	}
	p := s.Send(objc.RegisterName("UTF8String"))
	if p == 0 {
		return "<nil>"
	}
	var b []byte
	for i := 0; ; i++ {
		c := *(*byte)(unsafe.Pointer(uintptr(p) + uintptr(i)))
		if c == 0 {
			break
		}
		b = append(b, c)
	}
	return string(b)
}
