// The watch's input, on the SAME contract as the phone's touch keypad
// (go-pkgs/iosshell/keypad.go, ioskeys.scala's IosKeys): a queue of
// `code*4 + phase` with code 1..7 (UP DOWN LEFT RIGHT ENTER BACK PTT) and
// phase 0 release / 1 press. The applets already speak those keys, so the
// watch's very different gestures arrive as the model the shared logic
// expects and nothing above this file knows the difference.
//
// The phone draws seven buttons because a phone has no keys. A watch has no
// room for seven buttons and does have real input, so the mapping is the
// watch's own:
//
//	Digital Crown rotate   UP / DOWN   the watch's signature control; a list
//	                                   scrolls under the thumb, and it is the
//	                                   one input that needs no screen space
//	tap                    ENTER       open what is selected
//	swipe right            BACK        the platform's own back idiom
//	long press             PTT         press and release edges, which is
//	                                   exactly hold-to-talk
//	swipe up / down        UP / DOWN   for a wrist that would rather swipe
//
// WHY UIKit's recognizers AND NOT WatchKit's. Both exist here. WatchKit's
// WKTapGestureRecognizer & co. attach to a storyboard's objects, and the
// whole point of watchshell is that there is no storyboard; UIKit's attach
// to the view tree the app already builds. That this works at all rests on
// the window being hit-testable, which was probed before any of this was
// written (plan 0069's input section): our own scene-joined, level-raised
// window hit-tests to the app's container, not to WatchKit's hierarchy
// underneath.
//
// THE CROWN IS NOT A KEY, and turning it into one is a choice. Rotation
// arrives as a continuous rotationalDelta, so this accumulates and emits one
// UP/DOWN per detentPerKey of travel. Too small and a flick scrolls a list
// off its end; too large and the crown feels dead. The value below is a
// starting point to be tuned on a wrist, not a measured constant.
//
// Retention: the recognizers are retained by the view they attach to; the
// target and the crown delegate are owned (+alloc) and kept in package
// globals. Neither UIKit nor WatchKit retains a target or a delegate.

//go:build darwin

package watchshell

import (
	"log"
	"sync"

	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// the key codes — the Sgola contract, identical to iosshell's.
const (
	KeyNone  = 0
	KeyUp    = 1
	KeyDown  = 2
	KeyLeft  = 3
	KeyRight = 4
	KeyEnter = 5
	KeyBack  = 6
	KeyPtt   = 7
)

// UIGestureRecognizerState (UIGestureRecognizer.h): a long press reports
// Began when the finger has been down long enough and Ended/Cancelled when it
// lifts. Those two are the PTT edges.
const (
	gestureBegan     = 1
	gestureEnded     = 3
	gestureCancelled = 4
	gestureFailed    = 5
)

// UISwipeGestureRecognizerDirection.
const (
	swipeRight = 1 << 0
	swipeLeft  = 1 << 1
	swipeUp    = 1 << 2
	swipeDown  = 1 << 3
)

// How much crown travel makes one arrow key. Tune on hardware.
const detentPerKey = 0.35

const (
	inputTargetClass = "WataWatchInputTarget"
	crownDelegClass  = "WataWatchCrownDelegate"
)

var (
	keyMu    sync.Mutex
	keyQueue []int

	inputTarget objc.ID // owned; recognizers do not retain their target
	crownDeleg  objc.ID // owned; the sequencer does not retain its delegate
	recognizers []objc.ID
	crownAccum  float64

	selState        = objc.RegisterName("state")
	selAddGesture   = objc.RegisterName("addGestureRecognizer:")
	selInitTarget   = objc.RegisterName("initWithTarget:action:")
	selSetDirection = objc.RegisterName("setDirection:")
	selSetMinPress  = objc.RegisterName("setMinimumPressDuration:")
)

// msgSendPtr is the raw objc_msgSend. Needed for the few setters taking a
// DOUBLE: objc.ID.Send passes uintptr arguments, which puts a float in an
// integer register and sets garbage.
func msgSendPtr() uintptr {
	msgSendOnce.Do(func() {
		h, err := purego.Dlopen("/usr/lib/libobjc.A.dylib",
			purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("watchshell: dlopen libobjc: " + err.Error())
		}
		sym, err := purego.Dlsym(h, "objc_msgSend")
		if err != nil {
			panic("watchshell: dlsym objc_msgSend: " + err.Error())
		}
		msgSend = sym
	})
	return msgSend
}

var (
	msgSendOnce sync.Once
	msgSend     uintptr
)

// PushKey queues one key edge (phase 0 release / 1 press). The gestures' own
// path, and a harness's injection seam.
func PushKey(code, phase int) {
	keyMu.Lock()
	keyQueue = append(keyQueue, code*4+phase)
	keyMu.Unlock()
}

// NextKey pops the oldest queued `code*4 + phase`, or -1 — never blocks.
func NextKey() int {
	keyMu.Lock()
	defer keyMu.Unlock()
	if len(keyQueue) == 0 {
		return -1
	}
	k := keyQueue[0]
	keyQueue = keyQueue[1:]
	return k
}

// tap and swipe are one-shot: they fire once, so a press/release pair is
// synthesized. Only the long press has real edges.
func pressAndRelease(code int) {
	PushKey(code, 1)
	PushKey(code, 0)
}

func inputTargetID() objc.ID {
	if inputTarget != 0 {
		return inputTarget
	}
	cls, err := objc.RegisterClass(inputTargetClass, objc.GetClass("NSObject"),
		nil, nil, []objc.MethodDef{
			{Cmd: objc.RegisterName("wataTap:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pressAndRelease(KeyEnter)
				}},
			{Cmd: objc.RegisterName("wataBack:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pressAndRelease(KeyBack)
				}},
			{Cmd: objc.RegisterName("wataUp:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pressAndRelease(KeyUp)
				}},
			{Cmd: objc.RegisterName("wataDown:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pressAndRelease(KeyDown)
				}},
			// Hold-to-talk. A long press is the ONLY gesture here with
			// meaningful edges, and the whole send path hangs off them.
			{Cmd: objc.RegisterName("wataHold:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					switch int(g.Send(selState)) {
					case gestureBegan:
						PushKey(KeyPtt, 1)
					case gestureEnded, gestureCancelled, gestureFailed:
						PushKey(KeyPtt, 0)
					}
				}},
		})
	if err != nil {
		panic("watchshell: RegisterClass " + inputTargetClass + ": " + err.Error())
	}
	inputTarget = objc.ID(cls).Send(selAllocSel).Send(selInitSel)
	return inputTarget
}

func addRecognizer(v uikit.UIView, class, action string, configure func(objc.ID)) {
	cls := objc.GetClass(class)
	if cls == 0 {
		return // absent on this OS: the other gestures still work
	}
	g := objc.ID(cls).Send(selAllocSel).Send(selInitTarget,
		inputTargetID(), objc.RegisterName(action))
	if g == 0 {
		return
	}
	if configure != nil {
		configure(g)
	}
	v.ID.Send(selAddGesture, g)
	recognizers = append(recognizers, g)
}

// AddGestures attaches the watch's input to a view — normally the stage's
// container, so the whole panel is the target. Main thread, after `ready`.
func AddGestures(v uikit.UIView) {
	addRecognizer(v, "UITapGestureRecognizer", "wataTap:", nil)
	addRecognizer(v, "UILongPressGestureRecognizer", "wataHold:", func(g objc.ID) {
		// Shorter than UIKit's 0.5s default: this is a talk button, and a
		// walkie-talkie that needs half a second before it hears you feels
		// broken.
		var setDur func(objc.ID, objc.SEL, float64)
		purego.RegisterFunc(&setDur, msgSendPtr())
		setDur(g, selSetMinPress, 0.25)
	})
	addRecognizer(v, "UISwipeGestureRecognizer", "wataBack:", func(g objc.ID) {
		g.Send(selSetDirection, swipeRight)
	})
	addRecognizer(v, "UISwipeGestureRecognizer", "wataUp:", func(g objc.ID) {
		g.Send(selSetDirection, swipeUp)
	})
	addRecognizer(v, "UISwipeGestureRecognizer", "wataDown:", func(g objc.ID) {
		g.Send(selSetDirection, swipeDown)
	})
	startCrown()
}

// startCrown focuses the Digital Crown and turns its rotation into arrows.
// The sequencer belongs to the interface controller, which is why shell.go
// keeps the instance.
func startCrown() {
	mu.Lock()
	c := controller
	mu.Unlock()
	if c == 0 {
		return
	}
	proto := objc.GetProtocol("WKCrownDelegate")
	if crownDeleg == 0 {
		var protos []*objc.Protocol
		if proto != nil {
			protos = []*objc.Protocol{proto}
		}
		cls, err := objc.RegisterClass(crownDelegClass, objc.GetClass("NSObject"),
			protos, nil, []objc.MethodDef{
				{Cmd: objc.RegisterName("crownDidRotate:rotationalDelta:"),
					Fn: func(self objc.ID, _ objc.SEL, seq objc.ID, delta float64) {
						crownRotated(delta)
					}},
			})
		if err != nil {
			panic("watchshell: RegisterClass " + crownDelegClass + ": " + err.Error())
		}
		crownDeleg = objc.ID(cls).Send(selAllocSel).Send(selInitSel)
	}
	seq := c.Send(objc.RegisterName("crownSequencer"))
	if seq == 0 {
		return
	}
	seq.Send(objc.RegisterName("setDelegate:"), crownDeleg)
	// Without focus the sequencer reports nothing, silently.
	seq.Send(objc.RegisterName("focus"))
}

// crownRotated accumulates continuous rotation into discrete arrow keys.
// Crossing the threshold consumes exactly one key's worth rather than
// resetting, so a steady turn emits an even stream instead of dropping the
// remainder of every detent.
func crownRotated(delta float64) {
	keyMu.Lock()
	crownAccum += delta
	var ups, downs int
	for crownAccum >= detentPerKey {
		crownAccum -= detentPerKey
		ups++
	}
	for crownAccum <= -detentPerKey {
		crownAccum += detentPerKey
		downs++
	}
	keyMu.Unlock()
	// Crown up is scrolling toward the TOP of a list, which is UP.
	for i := 0; i < ups; i++ {
		pressAndRelease(KeyUp)
	}
	for i := 0; i < downs; i++ {
		pressAndRelease(KeyDown)
	}
}

// TakeURL is a STUB: the watch app declares no URL scheme yet, so nothing
// ever queues one and this always answers "". It exists because enrol.scala
// is wata-ios's file unchanged, and its configure-link path is the phone's
// enrollment story (plan 0062). The watch is enrolled from the companion
// app instead (owner ruling 2026-08-19: one-off setup happens there, and the
// watch's independent job is only sending and receiving), so what enrol.scala
// still does here is read the config that enrollment LEFT — Enrol.configured()
// — while the link half stays inert.
//
// When the watch grows its own scheme, this becomes iosshell's real queue.
func TakeURL() string { return "" }

// OpenURL is a STUB, and the honest answer is that the watch cannot do this.
// The phone's enrollment bounces the user into Safari to tap "Add this
// phone" on the family admin page (plan 0062); watchOS has no browser to
// bounce into. The watch is enrolled from the companion app instead, so
// enrol.scala's setup arc never needs this — but it still CALLS it, being
// the phone's file unchanged, so the call has to land somewhere.
//
// It logs rather than doing nothing silently: if this ever prints, the watch
// has reached a setup path that cannot complete on the watch, and that is
// worth seeing rather than hunting.
func OpenURL(s string) {
	log.Printf("watchshell: no browser on the watch, ignoring open %s", s)
}
