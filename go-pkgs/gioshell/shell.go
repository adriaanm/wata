// The two-loop plumbing, free of any Gio import so that every build — the
// armv7 device cross-build, the linux/amd64 server smoke, an ordinary `sgo
// build` — compiles this package without a window toolkit. Only shell_gio.go
// (behind the `gioshell` build tag) imports Gio; shell_stub.go stands in for
// it everywhere else.
//
// The surface below is what the Sgola facade binds, and it is deliberately
// primitive-typed: bytes, ints, bools. No Sgola value ever crosses into Go and
// no Go value ever crosses into Sgola beyond a []byte, which is the same
// discipline go-pkgs/audio's facade keeps.

package gioshell

import (
	"errors"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

// The key codes the facade and GioDevice agree on. They are wata-fb's `Key`
// cases, not evdev codes: the Sgola side turns a code back into a `KeyEvent`,
// so the wire between the two only has to be stable, not meaningful to Go.
const (
	KeyUp = iota
	KeyDown
	KeyLeft
	KeyRight
	KeyEnter
	KeyBack
	KeyPtt
	KeyDot1
	KeyDot2
	KeyF2
)

// NextKey packs a code and its up/down state into one int: code*2+1 pressed,
// code*2 released. -1 means the queue is empty, which is what makes the
// facade's poll non-blocking exactly as `Evdev.poll` is.
func packKey(code int, pressed bool) int {
	if pressed {
		return code*2 + 1
	}
	return code * 2
}

// keyQueueCap bounds the pending input; a UI that stops polling for a second
// at 30fps is already broken, and dropping is better than blocking the window
// loop.
const keyQueueCap = 256

type shellState struct {
	w, h      int // the source framebuffer geometry (160x128)
	scale     int // 0 = fit the window, else a forced integer magnification
	maxFrames int // >0: quit after this many presented frames (the scripted sanity)

	mu     sync.Mutex
	latest []byte // the most recent frame, owned by this package
	gen    uint64 // bumped per present; the window loop redraws on a change

	keys chan int

	green, red atomic.Bool
	quit       atomic.Bool
	presented  atomic.Int64 // frames handed over by the wata loop
	painted    atomic.Int64 // frames the window actually drew

	// set by the window loop once its window exists, so Present can wake it.
	invalidate atomic.Pointer[func()]

	done     chan struct{}
	doneOnce sync.Once
}

var st atomic.Pointer[shellState]

var errNotStarted = errors.New("gioshell: Start has not run")

func initState(w, h, scale, maxFrames int) *shellState {
	s := &shellState{
		w: w, h: h, scale: scale, maxFrames: maxFrames,
		latest: make([]byte, w*h*2),
		keys:   make(chan int, keyQueueCap),
		done:   make(chan struct{}),
	}
	st.Store(s)
	// The frame loop's teardown ends in Done(); that is the process's exit
	// edge, because app.Main never returns.
	go func() {
		<-s.done
		os.Exit(0)
	}()
	// A window that never appears is otherwise silent: Gio's window creation
	// is asynchronous, so a host that cannot give it one (no active display —
	// a locked or sleeping screen, a session with no window server) leaves the
	// client happily looping against nothing. Say so once.
	go func() {
		time.Sleep(windowWarnAfter)
		if s.painted.Load() == 0 {
			os.Stderr.WriteString("gioshell: no frame has been drawn — Gio could not open a window. " +
				"It needs a GUI session with an ACTIVE display (a sleeping or locked screen is enough " +
				"to stop it); the client itself is running.\n")
		}
	}()
	return s
}

// how long a window may take to draw its first frame before we say something.
const windowWarnAfter = 5 * time.Second

// Present takes a copy of the RGB565 frame and wakes the window. It copies
// rather than retains: the caller is wata-fb's single reusable pixel buffer,
// which the very next frame overwrites.
func Present(px []byte) {
	s := st.Load()
	if s == nil {
		return
	}
	s.mu.Lock()
	if len(s.latest) != len(px) {
		s.latest = make([]byte, len(px))
	}
	copy(s.latest, px)
	s.gen++
	s.mu.Unlock()
	if n := s.presented.Add(1); s.maxFrames > 0 && n >= int64(s.maxFrames) {
		s.quit.Store(true)
	}
	if f := s.invalidate.Load(); f != nil {
		(*f)()
	}
}

// frame hands the window loop the current frame and its generation.
func (s *shellState) frame(into []byte) ([]byte, uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(into) != len(s.latest) {
		into = make([]byte, len(s.latest))
	}
	copy(into, s.latest)
	return into, s.gen
}

// Leds mirrors the device's green/red connection LEDs into the shell chrome.
func Leds(green, red bool) {
	s := st.Load()
	if s == nil {
		return
	}
	s.green.Store(green)
	s.red.Store(red)
	if f := s.invalidate.Load(); f != nil {
		(*f)()
	}
}

// NextKey pops one pending key event, or -1 when there is none.
func NextKey() int {
	s := st.Load()
	if s == nil {
		return -1
	}
	select {
	case k := <-s.keys:
		return k
	default:
		return -1
	}
}

// push queues a key event, dropping it if the frame loop has fallen behind.
func (s *shellState) push(code int, pressed bool) {
	select {
	case s.keys <- packKey(code, pressed):
	default:
	}
}

// Quit reports that the window is gone (or the frame budget is spent), which
// is the frame loop's cue to tear down and call Done.
func Quit() bool {
	s := st.Load()
	return s != nil && s.quit.Load()
}

// Frames is how many frames have been presented — the scripted sanity check's
// evidence that a real frame reached the window.
func Frames() int { return int(mustState().presented.Load()) }

// Painted is how many frames the window DREW. Presented counts what the wata
// loop handed over; painted counts what Gio laid out and put on the screen, so
// an unattended run can tell "the client is looping" from "a window came up
// and the blit path ran".
func Painted() int { return int(mustState().painted.Load()) }

func mustState() *shellState {
	if s := st.Load(); s != nil {
		return s
	}
	return &shellState{}
}

// Done is called by the frame loop after its teardown; it ends the process,
// because app.Main owns the main goroutine and never gives it back.
func Done() {
	s := st.Load()
	if s == nil {
		os.Exit(0)
	}
	s.doneOnce.Do(func() { close(s.done) })
	// the exit goroutine is already running; block rather than race back into
	// a torn-down client.
	time.Sleep(5 * time.Second)
	os.Exit(0)
}

// waitDone gives the frame loop a moment to finish its teardown after the
// window closes, then exits regardless.
func (s *shellState) waitDone(d time.Duration) {
	select {
	case <-s.done:
	case <-time.After(d):
	}
}
