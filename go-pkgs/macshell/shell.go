// The macOS shell around nativeui's retained stage: the window (or the
// headless stage), the AppKit-thread seam, the key queue, and the wire
// decode. This is the package the wata-mac Sgola app binds (`go.macshell`);
// the surface is primitive-typed — strings and ints — the same discipline
// go-pkgs/audio and go-pkgs/gioshell keep.
//
// TWO MODES, one Apply path:
//
//   - WINDOWED. Start must run on the process's main thread (the package
//     init locks the main goroutine there, so the Sgola main simply calls it
//     first); it brings up NSApplication, the window, the stage and the
//     first-responder key view (nativeui.NewKeyView). RunApp is
//     NSApplication.run and never returns; the frame pump runs on a forked
//     goroutine and Apply submits each frame's mutation to the MAIN QUEUE
//     asynchronously (nativeui.MainQueue), one frame per queue turn — the
//     threading rule wata-mac.md pins.
//
//   - HEADLESS (WATA_MAC_HEADLESS). No NSApplication, no window, no runloop:
//     a dedicated locked OS thread owns the stage (chunk 1 proved offscreen
//     AppKit work is safe there), Apply runs on it SYNCHRONOUSLY, and
//     TreeDump walks the live NSView hierarchy after it — which is what
//     makes the mac smoke's assertions coherent: when `wait` returns, the
//     native tree IS the state the printed patches produced.
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
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/nativeui"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

func init() {
	// The package is linked ⇒ the app may bring up a window: pin the main
	// goroutine to the main OS thread before anything else runs, so the
	// Sgola main's Start/RunApp calls land where AppKit demands them.
	runtime.LockOSThread()
}

var poolPush func() uintptr
var poolPop func(p uintptr)
var poolOnce sync.Once

func poolInit() {
	poolOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("macshell: dlopen libobjc: " + err.Error())
		}
		purego.RegisterLibFunc(&poolPush, lib, "objc_autoreleasePoolPush")
		purego.RegisterLibFunc(&poolPop, lib, "objc_autoreleasePoolPop")
	})
}

const keyQueueCap = 256

var (
	mu       sync.Mutex
	stage    *nativeui.Stage
	headless bool
	work     chan func() // headless: the dedicated AppKit thread's inbox
	keys     chan int
	win      appkit.NSWindow
)

var errNotStarted = errors.New("macshell: Start/StartHeadless has not run")

// packKey packs a nativeui.Key and a phase (nativeui.Phase*) into one int:
// key*4 + phase. -1 = queue empty. The Sgola side rebuilds its KeyEvent.
func packKey(k nativeui.Key, phase int) int { return int(k)*4 + phase }

func pushKey(k nativeui.Key, phase int) {
	select {
	case keys <- packKey(k, phase):
	default: // a pump that stopped draining is already broken; drop, not block
	}
}

// StartHeadless creates the stage on a dedicated locked OS thread. No window,
// no runloop; Apply and TreeDump run synchronously on that thread.
func StartHeadless(scale int) {
	poolInit()
	mu.Lock()
	headless = true
	keys = make(chan int, keyQueueCap)
	work = make(chan func(), 16)
	mu.Unlock()
	go func() {
		runtime.LockOSThread()
		for f := range work {
			pool := poolPush()
			f()
			poolPop(pool)
		}
	}()
	onStage(func() { stage = nativeui.NewStage(scale) })
}

// Start brings up NSApplication, the window, the stage and the key view.
// MAIN THREAD ONLY (the init lock guarantees the Sgola main is there); call
// it before forking the pump, then call RunApp last.
func Start(scale int, title string) {
	poolInit()
	mu.Lock()
	headless = false
	keys = make(chan int, keyQueueCap)
	mu.Unlock()
	pool := poolPush()
	defer poolPop(pool)

	app := appkit.GetNSApplicationClass().SharedApplication()
	app.SetActivationPolicy(appkit.NSApplicationActivationPolicyRegular)

	s := nativeui.NewStage(scale)
	stageRect := appkit.CGRect{Size: appkit.CGSize{
		Width:  float64(nativeui.StageW * s.Scale),
		Height: float64(nativeui.StageH * s.Scale),
	}}
	style := appkit.NSWindowStyleMaskTitled | appkit.NSWindowStyleMaskClosable |
		appkit.NSWindowStyleMaskMiniaturizable
	w := appkit.NSWindow{ID: appkit.GetNSWindowClass().Alloc().ID}.
		InitWithContentRectStyleMaskBackingDefer(stageRect, style, appkit.NSBackingStoreBuffered, false)
	w.SetTitle(title)
	w.ContentView().AddSubview(s.Root())
	// The key view spans the stage ON TOP of it (later subview = above);
	// it draws nothing and owns first responder, so every key lands in the
	// translation table and the queue.
	kv := nativeui.NewKeyView(stageRect, pushKey)
	w.ContentView().AddSubview(kv)
	w.SetInitialFirstResponder(kv)
	w.Center()
	w.MakeKeyAndOrderFront(objc.ID(0))
	objc.Send[bool](w.ID, objc.RegisterName("makeFirstResponder:"), kv.ID)
	app.ActivateIgnoringOtherApps(true)

	mu.Lock()
	stage = s
	win = w
	mu.Unlock()
}

// RunApp is NSApplication.run: main thread only, never returns.
func RunApp() {
	appkit.GetNSApplicationClass().SharedApplication().Run()
}

// Terminate ends the windowed app ([NSApp terminate:]); it does not return.
func Terminate() {
	appkit.GetNSApplicationClass().SharedApplication().Terminate(objc.ID(0))
}

// onStage runs f on whatever thread owns the stage: the headless AppKit
// thread (synchronously), or the main queue (asynchronously — one frame per
// queue turn, drained under NSApplication.run).
func onStage(f func()) {
	mu.Lock()
	hl := headless
	w := work
	mu.Unlock()
	if hl {
		done := make(chan struct{})
		w <- func() { f(); close(done) }
		<-done
		return
	}
	nativeui.MainQueue().Async(func() {
		pool := poolPush()
		f()
		poolPop(pool)
	})
}

// Apply decodes one wire message (wire.go) and applies it to the stage —
// SetTree for a whole tree, Apply for a script, in script order.
func Apply(wire string) error {
	msg, err := DecodeMsg(wire)
	if err != nil {
		return err
	}
	mu.Lock()
	s := stage
	mu.Unlock()
	if s == nil {
		return errNotStarted
	}
	onStage(func() {
		if msg.IsTree {
			s.SetTree(msg.Tree)
		} else {
			s.Apply(msg.Patches)
		}
	})
	return nil
}

// NextKey pops one pending key event (key*4+phase), or -1 — never blocks.
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

// PushKeyCode injects a macOS virtual key code through the SAME translation
// table the key view uses (nativeui.TranslateKeyCode) — the headless smoke's
// way of exercising the key path end to end. Unknown codes are swallowed
// exactly as the key view swallows them.
func PushKeyCode(code int, phase int) {
	k := nativeui.TranslateKeyCode(uint16(code))
	if k == nativeui.KeyNone {
		return
	}
	mu.Lock()
	q := keys
	mu.Unlock()
	if q != nil {
		pushKey(k, phase)
	}
}

// TreeDump walks the live native hierarchy — class, frame (AppKit
// coordinates, bottom-left origin), and a text field's string — one line per
// view, children indented, in subview (= paint) order. Headless only: it is
// the smoke's assertion surface, read on the stage's own thread.
func TreeDump() (string, error) {
	mu.Lock()
	s := stage
	hl := headless
	mu.Unlock()
	if s == nil || !hl {
		return "", errNotStarted
	}
	var out string
	done := make(chan struct{})
	work <- func() {
		pool := poolPush()
		var b strings.Builder
		dumpView(&b, s.Root(), 0)
		out = b.String()
		poolPop(pool)
		close(done)
	}
	<-done
	return out, nil
}

func dumpView(b *strings.Builder, v appkit.NSView, depth int) {
	f := v.Frame()
	cls := viewClassName(v.ID)
	fmt.Fprintf(b, "%s%s %d %d %d %d", strings.Repeat("  ", depth), cls,
		int(f.Origin.X), int(f.Origin.Y), int(f.Size.Width), int(f.Size.Height))
	if cls == "NSTextField" {
		fmt.Fprintf(b, " %q", appkit.NSControl{ID: v.ID}.StringValue())
	}
	b.WriteString("\n")
	// Descend only into plain NSViews — the stage, the groups, the key view's
	// siblings. Leaf elements (NSBox, NSTextField, NSImageView) own private
	// subviews of their own that are AppKit internals, not the mirror's.
	if cls != "NSView" {
		return
	}
	subs := v.Subviews()
	n := int(subs.Count())
	for i := 0; i < n; i++ {
		dumpView(b, appkit.NSView{ID: subs.ObjectAtIndex(uint(i))}, depth+1)
	}
}

// viewClassName asks the ObjC runtime what an object is (the class object's
// description is its name).
func viewClassName(id objc.ID) string {
	return objcrt.GoString(objc.ID(id.Class()).Send(objc.RegisterName("description")))
}
