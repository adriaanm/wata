//go:build ios

// The AVAudioSession activation — the one thing iOS needs that macOS does not
// have (macOS has no AVAudioSession at all; the shared engine just runs).
// Raw selectors, same shell-glue category as engine.go's: AVAudioSession is
// identity-only in the generated bindings and its methods exist only on iOS,
// where the bindgen's macOS-driven runtime verification cannot reach them.
//
// Called from startEngine before the engine is built: the session's category
// decides what the IO unit is configured with, so it must be set first.
// PlayAndRecord + DefaultToSpeaker (a phone held in the hand routes to the
// receiver otherwise — walkie-talkie audio belongs on the speaker) +
// AllowBluetooth (a headset, if paired).
//
// The record-permission ask is fired here, once, and only logged: the system
// prompt then appears at first audio-thread start rather than mid-PTT-press.
// A press before the grant fails per-command (MIC FAILED), which is the
// audio thread's honest surface for it; the next press works.
//
// SESSION OWNERSHIP AND PushToTalk (plan 0065 tier 3). While a PTT
// transmission is in progress the FRAMEWORK owns the audio session: it
// configures and activates one and hands it to the app through
// channelManager:didActivateAudioSession:, and an app that activates its own
// session underneath that is what plan 0008 records as incompatible. So this
// file has an owner flag — PTTSessionActivated/PTTSessionDeactivated, called
// synchronously from those two delegate callbacks (go-pkgs/iosshell/ptt.go),
// never through the pump: the engine can be started on any thread and the
// yield has to be true the instant the framework says so.
//
// While the framework owns it:
//   - sessionActivate() sets NOTHING: no category, no setActive. The session
//     the framework handed over is already configured for the transmission,
//     and both calls are exactly what must not race it.
//   - the engine is STARTED if it is not running, because a transmission that
//     woke the app finds no engine yet and the capture tap needs one.
//
// When the framework gives it back (didDeactivateAudioSession), the app is a
// plain foreground audio app again: our own category is set and activated and
// a stopped engine restarted, so playing a received message after a
// transmission works. Both directions are best-effort and only logged — an
// audio session that cannot be reclaimed must not wedge the app, and the
// audio thread already reports per-command failures.
package macaudio

import (
	"fmt"
	"log"
	"sync"

	av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// The permission block, package-held so the Go side outlives the ask (the
// framework copies the ObjC side itself; this keeps the trampoline alive).
var (
	recordPermBlock objc.Block
	permOnce        sync.Once
)

var (
	selSharedInstance       = objc.RegisterName("sharedInstance")
	selSetCategoryOptsError = objc.RegisterName("setCategory:withOptions:error:")
	selSetActiveError       = objc.RegisterName("setActive:error:")
	selRequestRecordPerm    = objc.RegisterName("requestRecordPermission:")
)

// AVAudioSessionCategoryOptions bits (AVAudioSessionTypes.h).
const (
	optAllowBluetooth   = 0x4
	optDefaultToSpeaker = 0x8
)

// pttOwns is set for as long as the PushToTalk framework owns the audio
// session. Guarded by pttMu, which also serializes the two handoff calls
// against each other (they arrive on the framework's threads).
var (
	pttMu   sync.Mutex
	pttOwns bool
)

// PTTSessionActivated: the PushToTalk framework has handed the app an
// ACTIVATED session for a transmission. Call it from
// channelManager:didActivateAudioSession: before anything records.
func PTTSessionActivated() {
	pttMu.Lock()
	pttOwns = true
	pttMu.Unlock()
	log.Printf("macaudio: PushToTalk owns the audio session")
	startIfStopped()
}

// PTTSessionDeactivated: the transmission is over and the session is the
// app's problem again. Call it from channelManager:didDeactivateAudioSession:.
func PTTSessionDeactivated() {
	pttMu.Lock()
	pttOwns = false
	pttMu.Unlock()
	log.Printf("macaudio: PushToTalk released the audio session")
	if err := sessionActivate(); err != nil {
		log.Printf("macaudio: reclaiming the audio session failed: %v", err)
		return
	}
	startIfStopped()
}

// pttOwned answers whether the framework holds the session right now.
func pttOwned() bool {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttOwns
}

// startIfStopped restarts the shared engine when it exists and is not
// running — the session under it changed, which is what stops it.
func startIfStopped() {
	e := eng
	if e == nil {
		return
	}
	inPool(func() {
		if e.eng.Running() {
			return
		}
		if ok, err := e.eng.StartAndReturnError(); !ok {
			log.Printf("macaudio: engine restart after a session handoff: %v", err)
		}
	})
}

func sessionActivate() error {
	if pttOwned() {
		// The framework's session is live and configured; setting a category
		// or activating on top of it is the incompatibility itself.
		log.Printf("macaudio: PushToTalk owns the session — not activating our own")
		return nil
	}
	if _, err := purego.Dlopen(
		"/System/Library/Frameworks/AVFAudio.framework/AVFAudio",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		return fmt.Errorf("macaudio: dlopen AVFAudio: %w", err)
	}
	var out error
	inPool(func() {
		// The category constant's VALUE is its own name, and setCategory:
		// compares by string value (it must — apps hand it constants from
		// arbitrary framework copies), so a plain NSString stands in for the
		// dlsym'd extern and keeps this file unsafe-free.
		category := objcrt.NSString("AVAudioSessionCategoryPlayAndRecord")
		s := objc.ID(objc.GetClass("AVAudioSession")).Send(selSharedInstance)
		if s == 0 {
			out = fmt.Errorf("macaudio: AVAudioSession sharedInstance returned nil")
			return
		}
		var catErr objcrt.ErrOut
		if !objc.Send[bool](s, selSetCategoryOptsError, category,
			uintptr(optAllowBluetooth|optDefaultToSpeaker), catErr.Ptr()) {
			out = fmt.Errorf("macaudio: setCategory PlayAndRecord: %w", catErr.Err())
			return
		}
		var actErr objcrt.ErrOut
		if !objc.Send[bool](s, selSetActiveError, true, actErr.Ptr()) {
			out = fmt.Errorf("macaudio: setActive: %w", actErr.Err())
			return
		}
		// Fire-and-forget: the answer is only logged (a press before the grant
		// fails per-command, the next one works). ONCE: sessionActivate runs
		// again whenever PushToTalk hands the session back, and each ask
		// would mint another block the framework then holds.
		permOnce.Do(func() {
			recordPermBlock = objc.NewBlock(func(_ objc.Block, granted bool) {
				log.Printf("macaudio: record permission granted=%v", granted)
			})
			s.Send(selRequestRecordPerm, recordPermBlock)
		})
	})
	return out
}

// prepareInput touches the engine's input node BEFORE the engine's first
// start. On iOS the engine configures its IO unit lazily: started without
// the input node ever having been instantiated, it runs output-only, and the
// input node then reports a 0 Hz format — OpenCapture's "no format" failure
// on every PTT press, because its format check reads the running engine's
// state before the tap-install restart could reconfigure it. Accessing the
// node here makes the first start bring the unit up with input enabled, so
// the hardware format is real from the first capture open.
func prepareInput(e av.AVAudioEngine) {
	e.InputNode()
}
