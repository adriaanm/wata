// The main-thread dispatch seam: libdispatch over purego.
//
// AppKit is main-thread-only once a window is up, and Go's scheduler
// migrates goroutines across OS threads freely — so the apply step (and
// only the apply step: bodies and the diff stay on the frame goroutine, per
// wataui's purity rule) hops to the main queue here. dispatch_async_f is
// the C-pointer flavor of dispatch_async: a plain function pointer plus a
// context word, exactly the shape purego's NewCallback likes best — no
// blocks, no ObjC.
//
// The context word is an index into a Go-side registry, never a Go pointer:
// nothing of Go's memory ever crosses into libdispatch.
//
// The seam is queue-generic on purpose. MainQueue drains only while the
// main thread runs a runloop (NSApplication.run, or dispatch_main) — a
// headless test would wait forever on it — so tests prove the seam on a
// private serial queue, which libdispatch drains on its own worker threads.
// The code path is identical.

//go:build darwin

package nativeui

import (
	"sync"

	"github.com/ebitengine/purego"
)

// Queue is a libdispatch queue handle.
type Queue struct{ q uintptr }

var dispatchOnce sync.Once
var dispatchAsyncF func(q uintptr, ctx uintptr, work uintptr)
var dispatchQueueCreate func(label string, attr uintptr) uintptr
var mainQueue uintptr
var workCallback uintptr

var pendingMu sync.Mutex
var pendingSeq uintptr
var pending = map[uintptr]func(){}

func dispatchInit() {
	dispatchOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libSystem.B.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("nativeui: dlopen libSystem: " + err.Error())
		}
		purego.RegisterLibFunc(&dispatchAsyncF, lib, "dispatch_async_f")
		purego.RegisterLibFunc(&dispatchQueueCreate, lib, "dispatch_queue_create")
		// dispatch_get_main_queue() is a macro for &_dispatch_main_q; the
		// symbol is the queue object itself, so its address is the handle.
		mainQueue, err = purego.Dlsym(lib, "_dispatch_main_q")
		if err != nil {
			panic("nativeui: dlsym _dispatch_main_q: " + err.Error())
		}
		workCallback = purego.NewCallback(func(ctx uintptr) uintptr {
			pendingMu.Lock()
			f := pending[ctx]
			delete(pending, ctx)
			pendingMu.Unlock()
			if f != nil {
				f()
			}
			return 0
		})
	})
}

// MainQueue is the process's main dispatch queue. Work lands there only
// while the main thread runs a runloop (NSApplication.run).
func MainQueue() Queue {
	dispatchInit()
	return Queue{mainQueue}
}

// NewSerialQueue creates a private serial queue (attr NULL), drained by
// libdispatch's own worker threads — no runloop needed.
func NewSerialQueue(label string) Queue {
	dispatchInit()
	return Queue{dispatchQueueCreate(label, 0)}
}

// Async enqueues f on the queue and returns immediately. Submissions from
// one goroutine run in submission order (the queues here are serial).
func (q Queue) Async(f func()) {
	dispatchInit()
	pendingMu.Lock()
	pendingSeq++
	id := pendingSeq
	pending[id] = f
	pendingMu.Unlock()
	dispatchAsyncF(q.q, id, workCallback)
}
