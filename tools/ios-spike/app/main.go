// The iOS architecture spike (IOS-CLIENT-ASSEMBLY): one pure-Go binary that
// IS an iOS app — no Swift, no Objective-C source, no Xcode project, no
// gomobile. It answers three questions with a running process:
//
//  1. does purego.Dlopen of a system framework work inside an iOS app
//     process (UIKit, Foundation, libobjc)?
//  2. can a Go binary own UIApplicationMain and the UIKit runloop?
//  3. does objc -> Go dispatch survive — both a synthesized *delegate* class
//     (UIApplicationDelegate) and a synthesized *view subclass* whose
//     layoutSubviews UIKit calls, the iOS analog of nativeui's WataKeyView?
//
// Every proof prints one `spike: <fact>` line; the driver (spike.py) asserts
// on the set. The app also paints a known solid colour so a screenshot can
// confirm the pixels really came from Go-driven UIKit.
//
// The Go side reuses the repo's own runtime layer (go-pkgs/appleptt/objcrt)
// rather than a private copy: whether THAT compiles and runs under GOOS=ios
// is part of the question.

//go:build darwin

package main

import (
	"fmt"
	"os"
	"runtime"
	"sync/atomic"
	"time"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

func init() {
	// UIKit, like AppKit, owns the main thread. Same discipline as
	// go-pkgs/macshell: pin before anything else runs.
	runtime.LockOSThread()
}

// CoreGraphics geometry, laid out as the ABI expects (same shapes the
// generated appkit package uses).
type CGPoint struct{ X, Y float64 }
type CGSize struct{ Width, Height float64 }
type CGRect struct {
	Origin CGPoint
	Size   CGSize
}

var (
	selAlloc       = objc.RegisterName("alloc")
	selInit        = objc.RegisterName("init")
	selInitFrame   = objc.RegisterName("initWithFrame:")
	selBounds      = objc.RegisterName("bounds")
	selMainScreen  = objc.RegisterName("mainScreen")
	selSetRootVC   = objc.RegisterName("setRootViewController:")
	selMakeKeyVis  = objc.RegisterName("makeKeyAndVisible")
	selView        = objc.RegisterName("view")
	selSetBG       = objc.RegisterName("setBackgroundColor:")
	selAddSubview  = objc.RegisterName("addSubview:")
	selSetText     = objc.RegisterName("setText:")
	selSetTextColor = objc.RegisterName("setTextColor:")
	selColorRGBA   = objc.RegisterName("colorWithRed:green:blue:alpha:")
	selWhiteColor  = objc.RegisterName("whiteColor")
	selLayoutSubs  = objc.RegisterName("layoutSubviews")
	selTick        = objc.RegisterName("wataTick:")
	selButtonHit   = objc.RegisterName("wataButtonHit:")
	selDidLaunch   = objc.RegisterName("application:didFinishLaunchingWithOptions:")
	selTimer       = objc.RegisterName("scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:")
	selAddTarget   = objc.RegisterName("addTarget:action:forControlEvents:")
	selSendActions = objc.RegisterName("sendActionsForControlEvents:")
	selSetTitle    = objc.RegisterName("setTitle:forState:")
	selClass       = objc.RegisterName("class")
	selDescription = objc.RegisterName("description")
)

// UIControlEventTouchUpInside.
const touchUpInside = 1 << 6

var (
	uiApplicationMain func(argc int32, argv uintptr, principal objc.ID, delegate objc.ID) int32

	// Retained by Go so ARC-less ObjC does not free them: the window must
	// outlive didFinishLaunching.
	window objc.ID
	button objc.ID

	laidOut   atomic.Bool
	ticked    atomic.Bool
	buttonHit atomic.Bool
)

func say(format string, a ...any) {
	fmt.Printf("spike: "+format+"\n", a...)
	os.Stdout.Sync()
}

func main() {
	// Q1: framework loading. objc.GetClass sees only what is loaded, and a
	// pure-Go binary links nothing — so every class below depends on this.
	for _, fw := range []string{
		"/System/Library/Frameworks/UIKit.framework/UIKit",
		"/System/Library/Frameworks/Foundation.framework/Foundation",
	} {
		h, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			say("FAIL dlopen %s: %v", fw, err)
			os.Exit(1)
		}
		say("dlopen ok %s handle=%#x", fw, h)
	}
	uikit, _ := purego.Dlopen("/System/Library/Frameworks/UIKit.framework/UIKit",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY)

	if c := objc.GetClass("UIApplication"); c == 0 {
		say("FAIL objc.GetClass(UIApplication) is nil after dlopen")
		os.Exit(1)
	}
	say("getclass ok UIApplication UIWindow=%v UILabel=%v",
		objc.GetClass("UIWindow") != 0, objc.GetClass("UILabel") != 0)

	// Q3a: a synthesized app-delegate class. purego derives the ObjC type
	// encoding from the Go signature; both methods here take objects only
	// and return BOOL/void, so the derived encoding is the true one (the
	// rule bindgen.md pins for struct-carrying selectors does not bite).
	delegateClass, err := objc.RegisterClass("WataAppDelegate", objc.GetClass("NSObject"), nil, nil,
		[]objc.MethodDef{
			{Cmd: selDidLaunch, Fn: func(self objc.ID, _ objc.SEL, app objc.ID, opts objc.ID) bool {
				didFinishLaunching(app)
				return true
			}},
			{Cmd: selTick, Fn: func(self objc.ID, _ objc.SEL, timer objc.ID) { tick() }},
			{Cmd: selButtonHit, Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
				buttonHit.Store(true)
				say("callback control-action from %s", objcrt.GoString(sender.Send(selDescription)))
			}},
		})
	if err != nil {
		say("FAIL RegisterClass WataAppDelegate: %v", err)
		os.Exit(1)
	}
	say("registerclass ok WataAppDelegate=%#x", uintptr(delegateClass))

	// Q3b: a synthesized UIView subclass. This is the iOS analog of
	// nativeui.WataKeyView — UIKit itself calls layoutSubviews during the
	// layout pass, so a firing callback proves framework-driven dispatch
	// into Go on a class Go invented, not just a delegate we handed over.
	if _, err := objc.RegisterClass("WataSpikeView", objc.GetClass("UIView"), nil, nil,
		[]objc.MethodDef{
			{Cmd: selLayoutSubs, Fn: func(self objc.ID, _ objc.SEL) {
				if !laidOut.Swap(true) {
					say("callback layoutSubviews on WataSpikeView")
				}
			}},
		}); err != nil {
		say("FAIL RegisterClass WataSpikeView: %v", err)
		os.Exit(1)
	}

	// Watchdog: never wedge a CI run on a stuck runloop.
	go func() {
		time.Sleep(90 * time.Second)
		say("FAIL watchdog: runloop never reported")
		os.Exit(2)
	}()

	// Q2: hand the process to UIKit. UIApplicationMain does not return.
	purego.RegisterLibFunc(&uiApplicationMain, uikit, "UIApplicationMain")
	say("calling UIApplicationMain")
	os.Exit(int(uiApplicationMain(0, 0, 0, objcrt.NSString("WataAppDelegate"))))
}

func newColor(r, g, b float64) objc.ID {
	return objc.ID(objc.GetClass("UIColor")).Send(selColorRGBA, r, g, b, 1.0)
}

func didFinishLaunching(app objc.ID) {
	say("callback didFinishLaunching app=%s", objcrt.GoString(app.Send(selDescription)))

	screen := objc.ID(objc.GetClass("UIScreen")).Send(selMainScreen)
	b := objc.Send[CGRect](screen, selBounds)
	say("screen bounds %.0fx%.0f", b.Size.Width, b.Size.Height)

	window = objc.ID(objc.GetClass("UIWindow")).Send(selAlloc).Send(selInitFrame, b)
	vc := objc.ID(objc.GetClass("UIViewController")).Send(selAlloc).Send(selInit)
	root := vc.Send(selView)
	// A distinctive solid colour: the screenshot check reads it back.
	root.Send(selSetBG, newColor(0, 0, 1))

	spikeView := objc.ID(objc.GetClass("WataSpikeView")).Send(selAlloc).
		Send(selInitFrame, CGRect{CGPoint{0, 0}, CGSize{b.Size.Width, b.Size.Height}})
	spikeView.Send(selSetBG, newColor(0, 0, 1))
	root.Send(selAddSubview, spikeView)

	label := objc.ID(objc.GetClass("UILabel")).Send(selAlloc).
		Send(selInitFrame, CGRect{CGPoint{20, 120}, CGSize{b.Size.Width - 40, 44}})
	label.Send(selSetText, objcrt.NSString("wata ios spike: Go drives UIKit"))
	label.Send(selSetTextColor, objc.ID(objc.GetClass("UIColor")).Send(selWhiteColor))
	spikeView.Send(selAddSubview, label)

	button = objc.ID(objc.GetClass("UIButton")).Send(selAlloc).
		Send(selInitFrame, CGRect{CGPoint{20, 200}, CGSize{200, 44}})
	button.Send(selSetTitle, objcrt.NSString("ptt"), 0)
	button.Send(selAddTarget, delegateOf(), selButtonHit, uintptr(touchUpInside))
	spikeView.Send(selAddSubview, button)

	window.Send(selSetRootVC, vc)
	window.Send(selMakeKeyVis)
	say("window visible")

	// A runloop-scheduled callback into Go: NSTimer on the main runloop.
	objc.ID(objc.GetClass("NSTimer")).Send(selTimer, 0.5, delegateOf(), selTick, objc.ID(0), false)
}

// delegateOf returns the live UIApplication delegate — the instance UIKit
// created from the synthesized class.
func delegateOf() objc.ID {
	app := objc.ID(objc.GetClass("UIApplication")).Send(objc.RegisterName("sharedApplication"))
	return app.Send(objc.RegisterName("delegate"))
}

func tick() {
	ticked.Store(true)
	say("callback nstimer on the main runloop")
	// Drive the control machinery: UIKit's target-action dispatch calls
	// back into the Go method registered above.
	button.Send(selSendActions, uintptr(touchUpInside))
	if laidOut.Load() && ticked.Load() && buttonHit.Load() {
		say("all checks passed")
	} else {
		say("FAIL missing callbacks layout=%v tick=%v button=%v",
			laidOut.Load(), ticked.Load(), buttonHit.Load())
	}
}
