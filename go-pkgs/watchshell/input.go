// The watch's input, in WATA's vocabulary rather than the handset's.
//
// This file used to queue `code*4 + phase` in the BQ268's key codes, because
// the shared applets speak those — so a wrist gesture had to be translated
// into an arrow key on a walkie-talkie that is not present. That is plan
// 0071's "the platform reaches too far up", and at the wrong device's
// vocabulary. What crosses the seam now is an INTENT: what the person did,
// in terms the domain owns.
//
//	Digital Crown rotate   Navigate(vertical, ±detents)   the watch's signature
//	                       control; the one input that needs no screen space
//	swipe up / down        Navigate(vertical, ∓flick)     a wrist that would
//	                       rather swipe than turn
//	tap                    Choose
//	swipe right            Back                           the platform's idiom
//	long press             TalkDown / TalkUp              hold-to-talk
//	activate / deactivate  Wake / Sleep                   WatchKit's lifecycle
//
// NAVIGATE CARRIES A SIGNED MAGNITUDE, not a direction, and that is the
// point of the shape rather than a decoration. Plan 0070's scrolling is
// physical — impulse, friction, detent spring, end spring — so a shell that
// can only say "down was pressed" cannot express a flick. Each device says
// how hard it was pushed and the physics lives once, above the boundary.
// Positive is toward the END of a list (down / right); negative is back
// toward its start. Units are DETENTS: 1.0 is one card, which is what a
// single crown click is worth.
//
// The integrator that turns those magnitudes into motion is plan 0071's step
// 2 and does not exist yet, so the app side rounds a magnitude to whole
// steps. The magnitude is still carried and still logged, so a nudge and a
// flick are already distinguishable at the seam — retrofitting it later into
// a settled interface is the expensive version.
//
// THE HORIZONTAL AXIS IS RESERVED AND UNUSED. Nothing here emits it and
// nothing above consumes it. It exists so the integrator runs per axis and
// layout positions items from an (x, y) offset, which makes a later per-card
// action strip a body change rather than a re-architecture. Do not spend a
// gesture on it without a reason.
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
// THE CROWN IS CONTINUOUS. Rotation arrives as a rotationalDelta, so this
// accumulates and reports one Navigate per BATCH of whole detents crossed —
// magnitude = the detents, so a steady turn of three clicks is one
// Navigate(±3) rather than three of ±1, and a nudge is ±1. detentPerCard is
// a starting point to be tuned on a wrist, not a measured constant.
//
// Retention: the recognizers are retained by the view they attach to; the
// target and the crown delegate are owned (+alloc) and kept in package
// globals. Neither UIKit nor WatchKit retains a target or a delegate.

//go:build darwin

package watchshell

import (
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/adriaanm/wata/go-pkgs/appleptt/uikit"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// The intent kinds — the Sgola contract (watchui.scala's `go.watchshell`,
// decoded by intents.scala's `Intents`). Keep the two in step.
const (
	IntentNone     = 0
	IntentNavigate = 1
	IntentChoose   = 2
	IntentBack     = 3
	IntentTalkDown = 4
	IntentTalkUp   = 5
	IntentWake     = 6
	IntentSleep    = 7
	// IntentRaw is the escape hatch for anything a device needs that wata's
	// vocabulary does not name — the handset's diag and exit menus are the
	// motivating case. Its Code is that device's own key code; nothing on the
	// watch emits one today.
	IntentRaw = 8
)

// Navigate's axes. Vertical is the only one any gesture produces.
const (
	AxisVertical   = 0
	AxisHorizontal = 1
)

// UIGestureRecognizerState (UIGestureRecognizer.h): a long press reports
// Began when the finger has been down long enough and Ended/Cancelled when it
// lifts. Those two are the talk edges.
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

// How much crown travel is worth one card. Tune on hardware.
const detentPerCard = 0.35

// What a swipe is worth. A UISwipeGestureRecognizer reports THAT a flick
// happened and never how fast — it has no velocity, unlike a pan — so a
// swipe's magnitude is a constant until this moves to a pan recognizer and
// reads `velocityInView:` at release. Three cards is "harder than a nudge",
// which is the distinction the magnitude exists to carry.
const swipeFlick = 3.0

const (
	inputTargetClass = "WataWatchInputTarget"
	crownDelegClass  = "WataWatchCrownDelegate"
)

// intent is one thing the person did. Axis and Amount mean something only
// for Navigate; Code only for Raw.
type intent struct {
	Kind   int
	Axis   int
	Amount float64
	Code   int
}

var (
	keyMu       sync.Mutex
	intentQueue []intent
	current     intent // the last one NextIntent popped (see its comment)

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

// PushIntent queues one intent. The gestures' own path, and a harness's
// injection seam.
func PushIntent(kind, axis int, amount float64, code int) {
	keyMu.Lock()
	intentQueue = append(intentQueue, intent{Kind: kind, Axis: axis, Amount: amount, Code: code})
	keyMu.Unlock()
}

func pushNavigate(axis int, amount float64) { PushIntent(IntentNavigate, axis, amount, 0) }
func pushSimple(kind int)                   { PushIntent(kind, AxisVertical, 0, 0) }

// NextIntent pops the oldest intent and answers its KIND, or -1 when the
// queue is empty — never blocks. The rest of the record is then readable
// through IntentAxis / IntentAmount / IntentCode until the next pop.
//
// It is split that way because the Sgola seam passes scalars, not structs,
// and a Navigate has to carry a float64 magnitude that must not be rounded
// or packed on the way across (the whole point of plan 0071's intents). The
// contract that makes it safe is ONE consumer: the frame pump drains this
// queue and nothing else calls it.
func NextIntent() int {
	keyMu.Lock()
	defer keyMu.Unlock()
	if len(intentQueue) == 0 {
		current = intent{}
		return -1
	}
	current = intentQueue[0]
	intentQueue = intentQueue[1:]
	return current.Kind
}

// IntentAxis is the popped Navigate's axis (AxisVertical / AxisHorizontal).
func IntentAxis() int { keyMu.Lock(); defer keyMu.Unlock(); return current.Axis }

// IntentAmount is the popped Navigate's signed magnitude, in cards.
func IntentAmount() float64 { keyMu.Lock(); defer keyMu.Unlock(); return current.Amount }

// IntentCode is the popped Raw's device-specific code.
func IntentCode() int { keyMu.Lock(); defer keyMu.Unlock(); return current.Code }

func inputTargetID() objc.ID {
	if inputTarget != 0 {
		return inputTarget
	}
	cls, err := objc.RegisterClass(inputTargetClass, objc.GetClass("NSObject"),
		nil, nil, []objc.MethodDef{
			{Cmd: objc.RegisterName("wataTap:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pushSimple(IntentChoose)
				}},
			{Cmd: objc.RegisterName("wataBack:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pushSimple(IntentBack)
				}},
			// A swipe UP moves toward the START of the list, so its magnitude
			// is negative — the sign is the direction, and the size is how
			// hard it was pushed.
			{Cmd: objc.RegisterName("wataUp:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pushNavigate(AxisVertical, -swipeFlick)
				}},
			{Cmd: objc.RegisterName("wataDown:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					pushNavigate(AxisVertical, swipeFlick)
				}},
			// Hold-to-talk. A long press is the ONLY gesture here with
			// meaningful edges, and the whole send path hangs off them.
			{Cmd: objc.RegisterName("wataHold:"),
				Fn: func(self objc.ID, _ objc.SEL, g objc.ID) {
					switch int(g.Send(selState)) {
					case gestureBegan:
						pushSimple(IntentTalkDown)
					case gestureEnded, gestureCancelled, gestureFailed:
						pushSimple(IntentTalkUp)
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

// refocusCrown re-asserts crown focus on activation (shell.go's
// willActivate). startCrown is idempotent — the delegate class and instance
// are created once, and re-setting the delegate plus `focus` is exactly what
// a re-activation needs. Before the first AddGestures this focuses a
// sequencer nothing listens to yet, which is harmless.
func refocusCrown() { startCrown() }

// startCrown focuses the Digital Crown and turns its rotation into Navigate.
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

// crownRotated accumulates continuous rotation into whole cards and reports
// them as ONE Navigate carrying how many. Crossing the threshold consumes
// exactly that many detents rather than resetting, so a steady turn keeps
// its remainder instead of dropping it.
//
// Crown up (a positive rotationalDelta) scrolls toward the TOP of a list,
// which is a NEGATIVE amount under the "positive is toward the end" rule.
func crownRotated(delta float64) {
	keyMu.Lock()
	crownAccum += delta
	cards := 0
	for crownAccum >= detentPerCard {
		crownAccum -= detentPerCard
		cards++
	}
	for crownAccum <= -detentPerCard {
		crownAccum += detentPerCard
		cards--
	}
	keyMu.Unlock()
	if cards != 0 {
		pushNavigate(AxisVertical, -float64(cards))
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

// ScriptIntents is a HARNESS SEAM, not a product path: it replays a scripted
// gesture sequence so a gate can hold the talk button without a finger. The
// watch simulator has no way to synthesize a long press on a UIKit
// recognizer — `simctl` can tap a coordinate but not hold one — so without
// this the SEND half of a walkie-talkie is ungateable, and only the receive
// half would ever be proven.
//
// Format, from $WATA_WATCH_SCRIPT_INTENTS: comma-separated
// `what@atMs[+holdMs]`, where `what` names an intent —
//
//	talk        TalkDown at atMs, TalkUp holdMs later (the hold-to-talk one)
//	choose      Choose
//	back        Back
//	up / down   Navigate(vertical, ∓1) — one card, a nudge
//	up:2.5      the same with an explicit magnitude, for a flick
//	wake/sleep  the lifecycle pair
//	raw:N       Raw(N)
//
// So `talk@6000+1500` presses the talk button six seconds in and releases it
// 1.5s later — a hold of the kind a wrist would make. `holdMs` means
// anything only for `talk`; every other intent is a single edge.
//
// It pushes onto the SAME queue the gestures use, so what the pump sees is
// indistinguishable from a real gesture. That is the point and also the
// limit: this proves the send arc above the recognizer, and proves nothing
// about whether a real long press is delivered (WATCH-INPUT-DELIVERY).
func ScriptIntents(spec string) {
	if spec == "" {
		return
	}
	for _, item := range strings.Split(spec, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		whatStr, rest, ok := strings.Cut(item, "@")
		if !ok {
			log.Printf("watchshell: bad intent script %q, want what@atMs[+holdMs]", item)
			continue
		}
		atStr, holdStr, hasHold := strings.Cut(rest, "+")
		at, err1 := strconv.Atoi(strings.TrimSpace(atStr))
		hold := 0
		var err2 error
		if hasHold {
			hold, err2 = strconv.Atoi(strings.TrimSpace(holdStr))
		}
		what := strings.TrimSpace(whatStr)
		kind, axis, amount, code, ok := parseScriptWhat(what)
		if err1 != nil || err2 != nil || !ok {
			log.Printf("watchshell: bad intent script %q", item)
			continue
		}
		go func(what string, kind, axis int, amount float64, code, at, hold int) {
			time.Sleep(time.Duration(at) * time.Millisecond)
			log.Printf("watchshell: scripted intent %s", what)
			PushIntent(kind, axis, amount, code)
			if kind == IntentTalkDown {
				time.Sleep(time.Duration(hold) * time.Millisecond)
				log.Printf("watchshell: scripted intent talk release")
				PushIntent(IntentTalkUp, AxisVertical, 0, 0)
			}
		}(what, kind, axis, amount, code, at, hold)
	}
}

// parseScriptWhat decodes one script token's intent name, with the optional
// `:arg` that gives a Navigate its magnitude or a Raw its code.
func parseScriptWhat(what string) (kind, axis int, amount float64, code int, ok bool) {
	name, arg, hasArg := strings.Cut(what, ":")
	switch name {
	case "talk":
		return IntentTalkDown, AxisVertical, 0, 0, true
	case "choose":
		return IntentChoose, AxisVertical, 0, 0, true
	case "back":
		return IntentBack, AxisVertical, 0, 0, true
	case "wake":
		return IntentWake, AxisVertical, 0, 0, true
	case "sleep":
		return IntentSleep, AxisVertical, 0, 0, true
	case "up", "down":
		mag := 1.0
		if hasArg {
			v, err := strconv.ParseFloat(arg, 64)
			if err != nil {
				return 0, 0, 0, 0, false
			}
			mag = v
		}
		if name == "up" {
			mag = -mag
		}
		return IntentNavigate, AxisVertical, mag, 0, true
	case "raw":
		n, err := strconv.Atoi(arg)
		if !hasArg || err != nil {
			return 0, 0, 0, 0, false
		}
		return IntentRaw, AxisVertical, 0, n, true
	}
	return 0, 0, 0, 0, false
}
