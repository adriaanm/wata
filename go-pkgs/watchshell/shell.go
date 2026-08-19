// The watchOS shell around the Sgola retained stage (plan 0069): the
// WatchKit entry point, the synthesized app delegate and root interface
// controller, the window, and the seam handing control back once a screen
// exists. This is iosshell productized for the watch — same package shape,
// same threading discipline, same `ready` trampoline — with the platform's
// three refusals designed around rather than fought.
//
// The refusals, each established by a run in tools/watch-spike and each
// the reason for a specific line below:
//
//   - **UIApplicationMain hangs.** The symbol is exported on watchOS and
//     calling it never returns and never delivers a launch callback: a
//     watch app is started by WatchKit's lifecycle. So the entry point is
//     WKApplicationMain, and it takes a delegate CLASS NAME, like
//     UIApplicationMain does.
//   - **The delegate must CONFORM to WKExtensionDelegate**, not merely
//     implement its methods — watchOS checks the protocol by name and
//     refuses the class otherwise. objc.GetProtocol resolves it at
//     runtime; RegisterClass takes it.
//   - **An app needs a root interface controller or a storyboard.** The
//     delegate answering `applicationRootInterfaceControllerClass` is
//     watchOS's own stated alternative to an Interface.plist, so this
//     package ships no storyboard and needs no ibtool. The controller is
//     lifecycle scaffolding only — nothing is drawn through it.
//
// And the one that is silent rather than loud:
//
//   - **A UIWindow is not composited until it belongs to a scene, and not
//     seen until it outranks WatchKit's own.** A freshly built window made
//     key and visible leaves isKeyWindow 0 and the panel BLACK while every
//     call answers non-nil. watchOS runs exactly one UIWindowScene already
//     holding one UIWindow, and a bisect (tools/watch-spike's `wkapp
//     adopt|own`) showed two routes that composite: add the view straight
//     to that existing window, or mint one, join the scene AND raise it
//     above with setWindowLevel:. This package takes the second, so the
//     whole view hierarchy is the app's own, as on the phone.
//
// One measurement habit follows from that and is worth more than the API
// details: **an offscreen render probe passing is not evidence that
// anything is on screen.** RenderViewRGBA read back correct pixels through
// every one of the black-panel configurations above. Only a screenshot
// settles it — and it must be taken a beat after the app's last printed
// proof, because on a device this fast the done marker beats the
// compositor (simrun.launch_and_expect's `settle`).
//
// Above that line the watch is an iPhone: UIKit's classes are all present
// and functional on watchOS despite their headers marking them
// API_UNAVAILABLE(watchos), so the element table, the differ and the
// bodies are wata-ios's, and go-pkgs/iosui is reused unchanged (it is
// libdispatch, libobjc and CoreGraphics — nothing in it is iOS-specific).
//
// Threading: the package init pins the main goroutine to the main OS
// thread; Start and RunApp must be called from it, first. Frame applies
// hop to the main queue through iosui.OnMain. Retention: everything UIKit
// must not free lives in package globals for the process's lifetime.

//go:build darwin

package watchshell

import (
	"runtime"
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/adriaanm/wata/go-pkgs/iosui"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

func init() {
	// The package is linked ⇒ the app owns WKApplicationMain: pin the main
	// goroutine to the main OS thread before anything else runs.
	runtime.LockOSThread()
}

const (
	delegateClass   = "WataWatchDelegate"
	controllerClass = "WataWatchRootController"
)

var (
	mu        sync.Mutex
	window    uikit.UIWindow
	rootVC    objc.ID
	container uikit.UIView
	root      uikit.UIView
	hasRoot   bool
	insets    uiEdgeInsets
	readyFn    uintptr
	readyRun   bool
	controller objc.ID // the live WKInterfaceController (the crown's owner)

	wkApplicationMain func(argc int32, argv uintptr, delegate objc.ID) int32

	selAllocSel       = objc.RegisterName("alloc")
	selInitSel        = objc.RegisterName("init")
	selView           = objc.RegisterName("view")
	selSafeAreaInsets = objc.RegisterName("safeAreaInsets")
)

// uiEdgeInsets mirrors UIEdgeInsets — safeAreaInsets' return. Not in the
// generated bindings (UIEdgeInsets is not on the allowlist); a raw struct
// return here is the same shell-glue category as selView above.
type uiEdgeInsets struct {
	Top, Left, Bottom, Right float64
}

// Start loads the frameworks, synthesizes the delegate and root controller
// classes, and binds WKApplicationMain — no UI yet: watchOS only allows
// that once a controller activates. MAIN THREAD ONLY, before anything
// else; call RunApp next.
func Start() {
	var wkLib uintptr
	for _, fw := range []string{
		"/System/Library/Frameworks/Foundation.framework/Foundation",
		"/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
		"/System/Library/Frameworks/UIKit.framework/UIKit",
		"/System/Library/Frameworks/WatchKit.framework/WatchKit",
	} {
		h, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("watchshell: dlopen " + fw + ": " + err.Error())
		}
		wkLib = h // WatchKit is last
	}
	purego.RegisterLibFunc(&wkApplicationMain, wkLib, "WKApplicationMain")

	proto := objc.GetProtocol("WKExtensionDelegate")
	if proto == nil {
		panic("watchshell: WKExtensionDelegate protocol not found")
	}
	delegateMethods := []objc.MethodDef{
		{Cmd: objc.RegisterName("applicationDidFinishLaunching"),
			Fn: func(self objc.ID, _ objc.SEL) {}},
		// The storyboard-free entry point: answering with a class is what
		// lets this app ship without an Interface.plist.
		{Cmd: objc.RegisterName("applicationRootInterfaceControllerClass"),
			Fn: func(self objc.ID, _ objc.SEL) uintptr {
				return uintptr(objc.GetClass(controllerClass))
			}},
	}
	if _, err := objc.RegisterClass(delegateClass, objc.GetClass("NSObject"),
		[]*objc.Protocol{proto}, nil, delegateMethods); err != nil {
		panic("watchshell: RegisterClass " + delegateClass + ": " + err.Error())
	}

	wkController := objc.GetClass("WKInterfaceController")
	if wkController == 0 {
		panic("watchshell: WKInterfaceController not found")
	}
	controllerMethods := []objc.MethodDef{
		// Activation, not launch, is when a screen exists. It fires again on
		// every return to the app, so the build is idempotent.
		{Cmd: objc.RegisterName("willActivate"),
			Fn: func(self objc.ID, _ objc.SEL) {
				// Keep the instance: the Digital Crown's sequencer hangs off
				// the interface controller, not off any view, so input.go has
				// no other way to reach it.
				mu.Lock()
				controller = self
				mu.Unlock()
				didActivate()
			}},
	}
	if _, err := objc.RegisterClass(controllerClass, wkController, nil, nil,
		controllerMethods); err != nil {
		panic("watchshell: RegisterClass " + controllerClass + ": " + err.Error())
	}
}

// RunApp hands the process to WatchKit: WKApplicationMain with the
// synthesized delegate. It never returns. Once a screen exists, `ready` —
// a registered callback trampoline, iosui.OnMain's convention — runs on
// the main thread; everything the app builds starts there.
func RunApp(ready uintptr) {
	mu.Lock()
	readyFn = ready
	mu.Unlock()
	wkApplicationMain(0, 0, objcrt.NSString(delegateClass))
}

// didActivate builds the window the first time a controller activates:
// join watchOS's scene (without which nothing is composited), a plain view
// controller whose view is the CONTAINER the stage root is spliced into,
// then hand control to the ready trampoline.
func didActivate() {
	mu.Lock()
	if readyRun {
		mu.Unlock()
		return
	}
	readyRun = true
	ready := readyFn
	mu.Unlock()

	pool := iosui.PoolPush()
	b := uikit.GetUIScreenClass().MainScreen().Bounds()

	// The app's own window, joined to watchOS's scene AND raised above the
	// window WatchKit already has there. Every part of that sentence is
	// load-bearing: a window with no scene is never composited at all, and
	// a window merely joined to the scene sits at the same level as
	// WatchKit's and loses to it. Either way the panel stays BLACK while
	// isKeyWindow, isHidden and an offscreen render probe all report
	// success — so an offscreen probe is never evidence that anything
	// reached the screen; only a screenshot is.
	w := uikit.GetUIWindowClass().Alloc().InitWithFrame(b)
	if scene := currentScene(); scene != 0 {
		w.ID.Send(objc.RegisterName("setWindowScene:"), scene)
	}
	w.ID.Send(objc.RegisterName("setWindowLevel:"), float64(100))
	vc := objc.ID(objc.GetClass("UIViewController")).
		Send(selAllocSel).Send(selInitSel)
	cv := uikit.UIView{ID: vc.Send(selView)}
	w.SetRootViewController(uikit.UIViewController{ID: vc})
	w.MakeKeyAndVisible()

	// The container spans the WHOLE panel. A watch's safe-area insets are
	// enormous relative to its screen — 89 of 248 points on a Series 10,
	// because WatchKit reserves the time overlay's band and a bottom margin
	// — and wata's screens are a rasterized layout that wants the panel,
	// not a form inset inside it. SafeArea() returns the insets so a body
	// can keep text clear of the overlay by choice.
	sub := uikit.GetUIViewClass().Alloc().InitWithFrame(
		uikit.CGRect{Size: b.Size})
	cv.AddSubview(sub)

	mu.Lock()
	window = w
	rootVC = vc
	container = sub
	insets = objc.Send[uiEdgeInsets](w.ID, selSafeAreaInsets)
	mu.Unlock()
	iosui.PoolPop(pool)

	if ready != 0 {
		pool := iosui.PoolPush()
		purego.SyscallN(ready)
		iosui.PoolPop(pool)
	}
}

// sceneWindow answers the UIWindow watchOS already put on the scene, or a
// zero handle. There is exactly one scene and it holds exactly one window.
func sceneWindow() uikit.UIWindow {
	scene := currentScene()
	if scene == 0 {
		return uikit.UIWindow{}
	}
	if scene.Send(objc.RegisterName("respondsToSelector:"),
		objc.RegisterName("windows")) == 0 {
		return uikit.UIWindow{}
	}
	ws := scene.Send(objc.RegisterName("windows"))
	if ws == 0 || uint64(ws.Send(objc.RegisterName("count"))) == 0 {
		return uikit.UIWindow{}
	}
	return uikit.UIWindow{ID: ws.Send(objc.RegisterName("objectAtIndex:"), 0)}
}

// SafeArea answers the window's safe-area insets in points. The container
// spans the whole panel, so a body that wants to keep text clear of the
// system time overlay watchOS draws above app content asks for these.
func SafeArea() (top, left, bottom, right float64) {
	mu.Lock()
	defer mu.Unlock()
	return insets.Top, insets.Left, insets.Bottom, insets.Right
}

// currentScene answers the UIWindowScene watchOS runs, or 0. There is
// exactly one; `anyObject` on the connectedScenes set is the whole search.
func currentScene() objc.ID {
	app := objc.ID(objc.GetClass("UIApplication")).
		Send(objc.RegisterName("sharedApplication"))
	if app == 0 {
		return 0
	}
	scenes := app.Send(objc.RegisterName("connectedScenes"))
	if scenes == 0 || uint64(scenes.Send(objc.RegisterName("count"))) == 0 {
		return 0
	}
	return scenes.Send(objc.RegisterName("anyObject"))
}

// ContainerBounds is the root container's bounds (points, zero origin) —
// what the app sizes its stage from. Valid once `ready` has run.
func ContainerBounds() uikit.CGRect {
	mu.Lock()
	cv := container
	mu.Unlock()
	pool := iosui.PoolPush()
	defer iosui.PoolPop(pool)
	return cv.Bounds()
}

// AdoptRoot splices the app's root view into the window's container and
// remembers it (the retention rule). Call from the main thread — inside
// `ready`, or through iosui.OnMain.
func AdoptRoot(v uikit.UIView) {
	mu.Lock()
	cv := container
	root = v
	hasRoot = true
	mu.Unlock()
	pool := iosui.PoolPush()
	defer iosui.PoolPop(pool)
	cv.AddSubview(v)
}

// Root answers the adopted root view (and whether one exists yet) — the
// probe surface the harness renders and asserts on.
func Root() (uikit.UIView, bool) {
	mu.Lock()
	defer mu.Unlock()
	return root, hasRoot
}
