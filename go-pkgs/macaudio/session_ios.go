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
//
// THE HAND-BACK CANNOT BE WAITED FOR FOREVER. Observed on hardware
// 2026-08-18: five incoming `pushtotalk` pushes produced five
// didActivateAudioSession callbacks and NOT ONE didDeactivateAudioSession, so
// the flag below latched for the life of the process and every later
// sessionActivate() silently did nothing. So ownership is scoped to an
// EPISODE — one transmission, or one incoming message — and the app declares
// the episode over itself (PTTEpisodeEnded) at the moment it has told the
// framework so: it stopped transmitting, it cleared the active remote
// participant, or the channel is gone.
//
// PTTEpisodeEnded does not drop ownership on the spot, because the framework
// deactivates its session asynchronously after that and reclaiming underneath
// that teardown is the same race the yield exists to avoid. It arms a GRACE
// period instead and reclaims only if the callback has still not arrived.
// pttHandbackGrace is therefore a bound on one asynchronous acknowledgement
// the app has already requested — not a guess at how long audio lasts — and
// the completion handlers it waits on normally fire in milliseconds.
package macaudio

import (
	"fmt"
	"log"
	"sync"
	"time"

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

// How long the app waits for didDeactivateAudioSession after it has told the
// framework the episode is over. See the header: this bounds one async
// acknowledgement, so it is generous by three orders of magnitude rather than
// tuned.
const pttHandbackGrace = 2 * time.Second

// pttOwns is set for as long as the PushToTalk framework owns the audio
// session. Guarded by pttMu, which also serializes the handoff calls against
// each other (they arrive on the framework's threads). pttEpoch counts
// ownership transitions, so a grace timer can tell "still the episode I armed
// for" from "the framework already handed back, or took it again".
var (
	pttMu    sync.Mutex
	pttOwns  bool
	pttEpoch uint64
)

// PTTSessionActivated: the PushToTalk framework has handed the app an
// ACTIVATED session — for a transmission, or for an incoming message it woke
// the app to play. Call it from channelManager:didActivateAudioSession:
// before anything records or plays.
func PTTSessionActivated() {
	pttMu.Lock()
	pttOwns = true
	pttEpoch++
	pttMu.Unlock()
	log.Printf("macaudio: PushToTalk owns the audio session")
	startIfStopped()
}

// PTTSessionDeactivated: the episode is over and the session is the app's
// problem again. Call it from channelManager:didDeactivateAudioSession: — or,
// when that never comes, from the grace timer PTTEpisodeEnded arms.
func PTTSessionDeactivated() {
	pttMu.Lock()
	was := pttOwns
	pttOwns = false
	pttEpoch++
	pttMu.Unlock()
	if !was {
		return
	}
	log.Printf("macaudio: PushToTalk released the audio session")
	if err := sessionActivate(); err != nil {
		log.Printf("macaudio: reclaiming the audio session failed: %v", err)
		return
	}
	startIfStopped()
}

// PTTEpisodeEnded: the app has told the framework this episode is finished
// (it stopped transmitting, it cleared the active remote participant, or the
// channel is gone) and the hand-back is now owed. Ownership is NOT dropped
// here — the framework tears its session down asynchronously and reclaiming
// underneath that is the race the yield exists to avoid — but it stops being
// open-ended: if didDeactivateAudioSession has still not arrived after
// pttHandbackGrace, the app reclaims the session itself.
//
// Idempotent and cheap to over-call: a second call inside the same episode
// arms a second timer that finds the epoch unchanged and does the same
// nothing, and a call while the app does not own the session returns at once.
func PTTEpisodeEnded(reason string) {
	pttMu.Lock()
	owns, epoch := pttOwns, pttEpoch
	pttMu.Unlock()
	if !owns {
		return
	}
	go func() {
		time.Sleep(pttHandbackGrace)
		pttMu.Lock()
		stale := pttOwns && pttEpoch == epoch
		pttMu.Unlock()
		if !stale {
			return
		}
		log.Printf("macaudio: PushToTalk never handed the session back after %s "+
			"(%v) — reclaiming it", reason, pttHandbackGrace)
		PTTSessionDeactivated()
	}()
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
