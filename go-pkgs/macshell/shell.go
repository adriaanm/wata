// The macOS shell around the Sgola-side retained stage: the window (or the
// headless flag), the AppKit-thread seam for the CHROME, the raw key queue,
// and the TreeDump assertion surface. This is the package the wata-mac Sgola
// app binds (`go.macshell`); the surface is primitive-typed — strings and
// ints — plus the appkit handle for the stage root, which the Sgola
// interpreter (wata-mac/src/main/scala/interp.scala) now owns and builds.
//
// TWO MODES, and who owns which thread:
//
//   - WINDOWED. Start must run on the process's main thread (the package
//     init locks the main goroutine there, so the Sgola main simply calls it
//     first); it brings up NSApplication, the window and the first-responder
//     key view (nativeui.NewKeyView, raw keyCodes). The Sgola side then
//     creates its stage on the same main goroutine and hands the root over
//     (AdoptRoot), forks the pump, and calls RunApp = NSApplication.run,
//     which never returns. Frame applies hop to the MAIN QUEUE through
//     nativeui.OnMain — the pump publishes a frame, a `go.callback`
//     trampoline applies it on the AppKit thread, one frame per queue turn.
//
//   - HEADLESS (WATA_MAC_HEADLESS). No NSApplication, no window, no runloop,
//     and no second thread either: the main goroutine IS the locked main OS
//     thread, the Sgola stage lives on it, applies run inline (bracketed in
//     nativeui.PoolPush/PoolPop), and TreeDump walks the adopted root
//     synchronously — when the REPL's `wait` returns, the native tree IS the
//     state the printed patches produced.
//
// Every AppKit excursion is wrapped in an autorelease pool (the caller's
// job, per wata-mac.md).

//go:build darwin

package macshell

import (
	"errors"
	"fmt"
	"runtime"
	"strings"
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/appkit"
	"github.com/adriaanm/wata/go-pkgs/nativeui"
	"github.com/ebitengine/purego/objc"
)

func init() {
	// The package is linked ⇒ the app may bring up a window: pin the main
	// goroutine to the main OS thread before anything else runs, so the
	// Sgola main's Start/RunApp calls land where AppKit demands them.
	runtime.LockOSThread()
}

func poolPush() uintptr    { return nativeui.PoolPush() }
func poolPop(pool uintptr) { nativeui.PoolPop(pool) }

const keyQueueCap = 256

var (
	mu       sync.Mutex
	headless bool
	keys     chan int
	win      appkit.NSWindow
	keyView  appkit.NSView
	root     appkit.NSView // the Sgola stage's container, once adopted
	hasRoot  bool
)

var errNotStarted = errors.New("macshell: no stage root adopted yet")

// packKey packs a RAW macOS virtual key code and a phase (nativeui.Phase*)
// into one int: code*4 + phase. -1 = queue empty. Translation into wata's
// key model is the Sgola side's (mackeys.scala).
func packKey(code int, phase int) int { return code*4 + phase }

func pushKey(code int, phase int) {
	select {
	case keys <- packKey(code, phase):
	default: // a pump that stopped draining is already broken; drop, not block
	}
}

// StartHeadless arms headless mode: just the key queue and the flag — the
// Sgola side creates its stage on the calling goroutine (the locked main OS
// thread) and hands the root to AdoptRoot for TreeDump.
func StartHeadless() {
	mu.Lock()
	headless = true
	keys = make(chan int, keyQueueCap)
	mu.Unlock()
}

// Start brings up NSApplication, the window and the key view — no stage:
// the Sgola side builds one next and hands its root to AdoptRoot. MAIN
// THREAD ONLY (the init lock guarantees the Sgola main is there); call it
// before forking the pump, then call RunApp last.
func Start(scale int, title string) {
	if scale < 1 {
		scale = 1
	}
	mu.Lock()
	headless = false
	keys = make(chan int, keyQueueCap)
	mu.Unlock()
	pool := poolPush()
	defer poolPop(pool)

	app := appkit.GetNSApplicationClass().SharedApplication()
	app.SetActivationPolicy(appkit.NSApplicationActivationPolicyRegular)
	// before the window: an app with no menu bar has no ⌘Q, no About, and no
	// ⌘V in the login sheet (menu.go)
	installMenuBar(app.ID, title)
	// Ask for notification permission while the app is coming up rather than
	// at the first arrival: the prompt is then part of launching, not a dialog
	// that interrupts the moment a message lands. A no-op unbundled.
	RequestNotifyAuth()

	stageRect := appkit.CGRect{Size: appkit.CGSize{
		Width:  float64(nativeui.StageW * scale),
		Height: float64(nativeui.StageH * scale),
	}}
	style := appkit.NSWindowStyleMaskTitled | appkit.NSWindowStyleMaskClosable |
		appkit.NSWindowStyleMaskMiniaturizable
	w := appkit.NSWindow{ID: appkit.GetNSWindowClass().Alloc().ID}.
		InitWithContentRectStyleMaskBackingDefer(stageRect, style, appkit.NSBackingStoreBuffered, false)
	w.SetTitle(title)
	// The key view spans the stage and stays ON TOP of it (AdoptRoot splices
	// the stage root BELOW it); it draws nothing and owns first responder, so
	// every key lands in the queue as a raw code.
	kv := nativeui.NewKeyView(stageRect, pushKey)
	w.ContentView().AddSubview(kv)
	w.SetInitialFirstResponder(kv)
	w.Center()
	w.MakeKeyAndOrderFront(objc.ID(0))
	objc.Send[bool](w.ID, objc.RegisterName("makeFirstResponder:"), kv.ID)
	app.ActivateIgnoringOtherApps(true)

	mu.Lock()
	win = w
	keyView = kv
	mu.Unlock()
}

// AdoptRoot takes the Sgola stage's container view: windowed it goes into
// the window's content view BELOW the key view (later subview = above);
// both modes remember it as TreeDump's walk root. Call on the thread that
// owns the stage (the main goroutine, in both modes, before the pump forks).
func AdoptRoot(v appkit.NSView) {
	mu.Lock()
	hl := headless
	w := win
	kv := keyView
	root = v
	hasRoot = true
	mu.Unlock()
	if hl {
		return
	}
	pool := poolPush()
	defer poolPop(pool)
	w.ContentView().AddSubviewPositionedRelativeTo(v, appkit.NSWindowBelow, kv)
}

// RunApp is NSApplication.run: main thread only, never returns.
func RunApp() {
	appkit.GetNSApplicationClass().SharedApplication().Run()
}

// Terminate ends the windowed app ([NSApp terminate:]); it does not return.
func Terminate() {
	appkit.GetNSApplicationClass().SharedApplication().Terminate(objc.ID(0))
}

// onStageSync runs f where AppKit work is safe and WAITS for it — the
// CHROME's seam (login sheet, Settings, Devices). Headless the caller IS
// the stage's thread (the locked main goroutine), so f runs inline;
// windowed it hops to the main queue and blocks until the queue turn ran —
// so windowed, never call it from the main thread itself.
func onStageSync(f func()) {
	mu.Lock()
	hl := headless
	mu.Unlock()
	if hl {
		pool := poolPush()
		f()
		poolPop(pool)
		return
	}
	done := make(chan struct{})
	nativeui.MainQueue().Async(func() {
		pool := poolPush()
		f()
		poolPop(pool)
		close(done)
	})
	<-done
}

// onMainAsync enqueues chrome work on the main queue without waiting —
// windowed-only fire-and-forget (the Dock badge). Headless callers return
// before reaching it.
func onMainAsync(f func()) {
	nativeui.MainQueue().Async(func() {
		pool := poolPush()
		f()
		poolPop(pool)
	})
}

// NextKey pops one pending key event (rawCode*4+phase), or -1 — never blocks.
func NextKey() int {
	mu.Lock()
	k := keys
	mu.Unlock()
	if k == nil {
		return -1
	}
	select {
	case v := <-k:
		return v
	default:
		return -1
	}
}

// PushKeyCode injects a RAW macOS virtual key code into the same queue the
// key view feeds — the headless smoke's way of exercising the key path end
// to end (the Sgola drain runs the same translation table either way).
func PushKeyCode(code int, phase int) {
	mu.Lock()
	q := keys
	mu.Unlock()
	if q != nil {
		pushKey(code, phase)
	}
}

// TreeDump walks the live native hierarchy from the adopted root — class,
// frame (AppKit coordinates, bottom-left origin), and a text field's string —
// one line per view, children indented, in subview (= paint) order. Headless
// only: it is the smoke's assertion surface, and headless the caller IS the
// stage's thread, so the walk is inline and coherent with the applies before
// it.
func TreeDump() (string, error) {
	mu.Lock()
	ok := hasRoot && headless
	r := root
	mu.Unlock()
	if !ok {
		return "", errNotStarted
	}
	pool := poolPush()
	defer poolPop(pool)
	var b strings.Builder
	dumpView(&b, r, 0)
	return b.String(), nil
}

func dumpView(b *strings.Builder, v appkit.NSView, depth int) {
	f := v.Frame()
	cls := nativeui.ViewClassName(v)
	fmt.Fprintf(b, "%s%s %d %d %d %d", strings.Repeat("  ", depth), cls,
		int(f.Origin.X), int(f.Origin.Y), int(f.Size.Width), int(f.Size.Height))
	if cls == "NSTextField" {
		fmt.Fprintf(b, " %q", nativeui.LabelText(v))
	}
	b.WriteString("\n")
	// Descend only into plain NSViews — the stage, the groups, the key view's
	// siblings. Leaf elements (NSBox, NSTextField, NSImageView) own private
	// subviews of their own that are AppKit internals, not the mirror's.
	if cls != "NSView" {
		return
	}
	n := nativeui.SubviewCount(v)
	for i := 0; i < n; i++ {
		dumpView(b, nativeui.SubviewAt(v, i), depth+1)
	}
}
