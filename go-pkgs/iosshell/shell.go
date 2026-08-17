// The iOS shell around the coming Sgola retained stage (plan 0044): the
// UIKit entry point, the synthesized app delegate, the window and the root
// container view, and the seam handing control back once UIKit is up. This
// is macshell productized for UIKit — same package shape, same threading
// discipline — with one structural inversion UIKit forces:
//
//   AppKit lets Start build the window BEFORE the runloop, so macshell's
//   Sgola main goes Start → build stage → AdoptRoot → RunApp. UIKit builds
//   the UI inside application:didFinishLaunchingWithOptions:, which fires
//   INSIDE UIApplicationMain — and UIApplicationMain never returns. So here
//   the main goes Start → RunApp(ready), and everything after (stage
//   creation, AdoptRoot, forking the pump) happens in the `ready` callback,
//   which the shell invokes on the main thread once the window is key and
//   visible. `ready` is a registered trampoline address (a `go.callback`
//   the Sgola side mints, or purego.NewCallback from Go) — the same
//   convention as iosui.OnMain, so the Sgola side needs nothing new.
//
// Threading: the package init pins the main goroutine to the main OS
// thread; Start and RunApp must be called from it, first. Frame applies hop
// to the MAIN QUEUE through iosui.OnMain — the pump publishes a frame, a
// registered trampoline applies it on the UIKit thread, one frame per queue
// turn. Every UIKit excursion is bracketed in an autorelease pool.
//
// Retention: iOS has no ARC on our side of the FFI, so everything UIKit
// must not free lives in package globals for the process's lifetime — the
// window, the root view controller, the container, the adopted root (the
// spike's friction #5). There is no headless mode and no mac chrome (menu
// bar, notifications): the simulator harness always runs windowed, and its
// assertion surface is the app's own printed lines plus the iosui render
// probe.

//go:build darwin

package iosshell

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
	// The package is linked ⇒ the app owns UIApplicationMain: pin the main
	// goroutine to the main OS thread before anything else runs.
	runtime.LockOSThread()
}

const delegateClass = "WataAppDelegate"

var (
	mu        sync.Mutex
	window    uikit.UIWindow
	rootVC    objc.ID // plain UIViewController; opaque in the bindings
	container uikit.UIView
	root      uikit.UIView
	hasRoot   bool
	readyFn   uintptr

	uiApplicationMain func(argc int32, argv uintptr, principal objc.ID, delegate objc.ID) int32

	selDidLaunch      = objc.RegisterName("application:didFinishLaunchingWithOptions:")
	selAllocSel       = objc.RegisterName("alloc")
	selInitSel        = objc.RegisterName("init")
	selView           = objc.RegisterName("view")
	selSafeAreaInsets = objc.RegisterName("safeAreaInsets")

	selOpenURLOpts       = objc.RegisterName("application:openURL:options:")
	selAbsoluteString    = objc.RegisterName("absoluteString")
	selURLWithString     = objc.RegisterName("URLWithString:")
	selSharedApplication = objc.RegisterName("sharedApplication")
	selOpenURLOutbound   = objc.RegisterName("openURL:options:completionHandler:")
	selDictionary        = objc.RegisterName("dictionary")

	// URLs delivered to application:openURL:options: (the wata:// scheme,
	// Info.plist's CFBundleURLTypes), oldest first. The app polls TakeURL
	// once per frame — same shape as the keypad queue.
	urlQueue []string
)

// uiEdgeInsets mirrors UIEdgeInsets — the safeAreaInsets property's return.
// Not in the generated bindings (no UIEdgeInsets on the allowlist); a raw
// struct return here is the same shell-glue category as selView above.
type uiEdgeInsets struct {
	Top, Left, Bottom, Right float64
}

// Start loads UIKit and Foundation, synthesizes the app delegate class, and
// binds UIApplicationMain — no UI yet: UIKit only allows that inside its own
// launch callback. MAIN THREAD ONLY, before anything else; call RunApp next.
func Start() {
	var uikitLib uintptr
	for _, fw := range []string{
		"/System/Library/Frameworks/UIKit.framework/UIKit",
		"/System/Library/Frameworks/Foundation.framework/Foundation",
	} {
		h, err := purego.Dlopen(fw, purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("iosshell: dlopen " + fw + ": " + err.Error())
		}
		if uikitLib == 0 {
			uikitLib = h
		}
	}
	purego.RegisterLibFunc(&uiApplicationMain, uikitLib, "UIApplicationMain")
	if _, err := objc.RegisterClass(delegateClass, objc.GetClass("NSObject"), nil, nil,
		[]objc.MethodDef{
			{Cmd: selDidLaunch, Fn: func(self objc.ID, _ objc.SEL, app objc.ID, opts objc.ID) bool {
				didFinishLaunching()
				return true
			}},
			// The wata:// scheme's inbound edge. iOS calls this for a launch
			// URL too (after didFinishLaunching returns true), so one queue
			// covers cold and warm opens.
			{Cmd: selOpenURLOpts, Fn: func(self objc.ID, _ objc.SEL, app, url, opts objc.ID) bool {
				s := objcrt.GoString(url.Send(selAbsoluteString))
				mu.Lock()
				urlQueue = append(urlQueue, s)
				mu.Unlock()
				return true
			}},
		}); err != nil {
		panic("iosshell: RegisterClass " + delegateClass + ": " + err.Error())
	}
}

// RunApp hands the process to UIKit: UIApplicationMain with the synthesized
// delegate. It never returns. Once the window is up, `ready` — a registered
// callback trampoline, iosui.OnMain's convention — runs on the main thread;
// everything the app builds (the stage, AdoptRoot, the pump) starts there.
func RunApp(ready uintptr) {
	mu.Lock()
	readyFn = ready
	mu.Unlock()
	uiApplicationMain(0, 0, 0, objcrt.NSString(delegateClass))
}

// didFinishLaunching is the delegate's launch callback: window sized from
// the main screen, a plain view controller whose view is the CONTAINER the
// stage root gets spliced into, make key and visible, then hand control to
// the registered ready trampoline.
func didFinishLaunching() {
	pool := iosui.PoolPush()
	b := uikit.GetUIScreenClass().MainScreen().Bounds()
	w := uikit.GetUIWindowClass().Alloc().InitWithFrame(b)
	// UIViewController is identity-only in the bindings (nothing of it is on
	// the allowlist beyond the handle), so alloc/init/view go through raw
	// selectors — shell glue, same category as macshell's makeFirstResponder.
	vc := objc.ID(objc.GetClass("UIViewController")).Send(selAllocSel).Send(selInitSel)
	cv := uikit.UIView{ID: vc.Send(selView)}
	w.SetRootViewController(uikit.UIViewController{ID: vc})
	w.MakeKeyAndVisible()
	// The container is NOT the controller's view but a subview capped to
	// the window's safe area, so nothing the app sizes from
	// ContainerBounds — the stage or the keypad — renders under the
	// notch/status bar or the home indicator. The window's insets are
	// valid once it is key and attached to a screen; a device with no
	// insets reads zero and the container spans the full view.
	ins := objc.Send[uiEdgeInsets](w.ID, selSafeAreaInsets)
	sub := uikit.GetUIViewClass().Alloc().InitWithFrame(uikit.CGRect{
		Origin: uikit.CGPoint{X: ins.Left, Y: ins.Top},
		Size: uikit.CGSize{
			Width:  b.Size.Width - ins.Left - ins.Right,
			Height: b.Size.Height - ins.Top - ins.Bottom,
		},
	})
	cv.AddSubview(sub)
	mu.Lock()
	window = w
	rootVC = vc
	container = sub
	ready := readyFn
	mu.Unlock()
	iosui.PoolPop(pool)
	if ready != 0 {
		pool := iosui.PoolPush()
		purego.SyscallN(ready)
		iosui.PoolPop(pool)
	}
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

// TakeURL pops the oldest URL handed to the app (the wata:// scheme), or ""
// when none is pending. Any-thread safe; the pump polls it once per frame.
func TakeURL() string {
	mu.Lock()
	defer mu.Unlock()
	if len(urlQueue) == 0 {
		return ""
	}
	s := urlQueue[0]
	urlQueue = urlQueue[1:]
	return s
}

// OpenURL hands a URL to the system to open in its owning app (Safari for
// http). MAIN THREAD ONLY — call through iosui.OnMain, or from the pump's
// frame callback. Fire-and-forget: the completion handler is nil.
func OpenURL(s string) {
	pool := iosui.PoolPush()
	defer iosui.PoolPop(pool)
	url := objc.ID(objc.GetClass("NSURL")).Send(selURLWithString, objcrt.NSString(s))
	if url == 0 {
		return
	}
	app := objc.ID(objc.GetClass("UIApplication")).Send(selSharedApplication)
	opts := objc.ID(objc.GetClass("NSDictionary")).Send(selDictionary)
	app.Send(selOpenURLOutbound, url, opts, 0)
}
