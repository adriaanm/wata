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
//     the framework handed over is already configured for the episode, and
//     both calls are exactly what must not race it.
//   - the engine is RESET — stopped and started — because it configures its IO
//     unit from the session at start, and a session whose category, mode or
//     sample rate changed underneath a running engine leaves that unit built
//     for the old one. That is silence, not an error.
//
// A SESSION CHANGE ALSO KILLS WHATEVER IS PLAYING, and it does it silently:
// the engine stops, the player node keeps a schedule nothing will consume, and
// scheduleBuffer:completionHandler: never fires. Device log, 2026-08-18:
//
//	ptt: playing $fbQ…                                   <- the app scheduled it
//	macaudio: PushToTalk owns the audio session           <- and THEN this
//	audio: playback failed: playback of 61440 frames never completed
//
// Two things follow, and both are here. Every transition calls
// noteSessionChanged (engine.go), so a blocked PlayMessage learns at once
// instead of waiting out dur+5s. And the ORDERING is the client's to get
// right: wata-ios does not play a woken message until the framework has handed
// the session over (wata-ios/ptt.scala). Playing first and being interrupted
// is not a race this file can win on its own.
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
	"errors"
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
	selCategory             = objc.RegisterName("category")
	selMode                 = objc.RegisterName("mode")
	selSampleRate           = objc.RegisterName("sampleRate")
	selOutputChannels       = objc.RegisterName("outputNumberOfChannels")
	selInputChannels        = objc.RegisterName("inputNumberOfChannels")
)

// AVAudioSessionCategoryOptions bits (AVAudioSessionTypes.h).
const (
	optAllowBluetooth   = 0x4
	optDefaultToSpeaker = 0x8
)

// How long the app waits for didDeactivateAudioSession after it has told the
// framework the episode is over. It bounds one async acknowledgement the app
// has already requested — not how long audio lasts.
//
// It was 2s, and the device log of 2026-08-18 shows 2s is INSIDE the
// framework's own teardown: the backstop fired, its setActive lost to a
// session PushToTalk was still tearing down ("Session activation failed"), and
// didDeactivateAudioSession arrived immediately afterwards. So the grace has
// to sit outside that teardown, which is the one thing it must not race.
// Waiting longer costs nothing — the real callback still does the reclaim the
// moment it lands, and this only decides how long a framework that never calls
// back can hold the flag.
const pttHandbackGrace = 10 * time.Second

// How many times, and how far apart, a reclaim is retried. The failure it
// exists for is transient by construction (the framework is mid-teardown), and
// an app left with no active session plays nothing at all.
const (
	reclaimTries = 4
	reclaimGap   = 500 * time.Millisecond
)

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
	logSessionState("PushToTalk activated")
	noteSessionChanged()
	resetForSession()
}

// PTTSessionDeactivated: the episode is over and the session is the app's
// problem again. Call it from channelManager:didDeactivateAudioSession: — or,
// when that never comes, from the grace timer PTTEpisodeEnded arms.
// The reclaim runs even when the flag was ALREADY down — the backstop may have
// dropped it and then failed to activate, and this callback is the moment the
// framework is finally out of the way. An early return there is how the app
// ends up with no active session at all.
func PTTSessionDeactivated() {
	pttMu.Lock()
	was := pttOwns
	pttOwns = false
	pttEpoch++
	pttMu.Unlock()
	if was {
		log.Printf("macaudio: PushToTalk released the audio session")
	}
	noteSessionChanged()
	reclaimSession()
}

// reclaimSession takes the session back for the app: our own category, active,
// and an engine reset so its IO unit is built against the session that is
// actually current. Retried, because the first attempt can land while
// PushToTalk is still tearing its own session down.
func reclaimSession() {
	var err error
	for i := 0; i < reclaimTries; i++ {
		if i > 0 {
			time.Sleep(reclaimGap)
		}
		if err = sessionActivate(); err == nil {
			logSessionState("reclaimed by wata")
			resetForSession()
			return
		}
		log.Printf("macaudio: reclaiming the audio session failed (try %d/%d): %v",
			i+1, reclaimTries, err)
	}
	log.Printf("macaudio: the audio session could not be reclaimed; audio is "+
		"unavailable until the next session change: %v", err)
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

// errDetail spells an NSError out with its DOMAIN and CODE, and with the code
// as a four-char code when it reads as one. Every AVAudioSession error is
// documented by its four-char code ('!act' is busy, '!pla' cannot start
// playing, '!pri' insufficient priority) while its localizedDescription is the
// same "Session activation failed" for several of them — so the description
// alone, which is all NSError.Error() prints, does not identify the failure.
func errDetail(err error) string {
	if err == nil {
		return "<nil>"
	}
	var ns *objcrt.NSError
	if !errors.As(err, &ns) {
		return err.Error()
	}
	out := fmt.Sprintf("%s (%s %d", ns.Description, ns.Domain, ns.Code)
	if s := fourCC(ns.Code); s != "" {
		out += " '" + s + "'"
	}
	return out + ")"
}

// fourCC renders a code as its four printable ASCII bytes, or "" when it is
// not one.
func fourCC(code int) string {
	if code <= 0 || code > 0x7fffffff {
		return ""
	}
	b := []byte{byte(code >> 24), byte(code >> 16), byte(code >> 8), byte(code)}
	for _, c := range b {
		if c < 0x20 || c > 0x7e {
			return ""
		}
	}
	return string(b)
}

// pttOwned answers whether the framework holds the session right now.
func pttOwned() bool {
	pttMu.Lock()
	defer pttMu.Unlock()
	return pttOwns
}

// resetForSession rebuilds the engine's relationship with the audio session
// that is now current. It is deliberately a full STOP and start rather than
// "start it if it stopped": the engine configures its IO unit from the session
// at start, and a session whose category, mode or sample rate changed
// underneath a RUNNING engine leaves that unit configured for the old one —
// which is silence rather than an error. Stopping first is also what clears
// the player node, whose scheduled buffer the session change stranded (the
// same reset PlayMessage does for the capture case, and for the same reason:
// the completion handler never fires).
func resetForSession() {
	e := eng
	if e == nil {
		return
	}
	inPool(func() {
		e.player.Stop()
		if e.eng.Running() {
			e.eng.Stop()
		}
		if ok, err := e.eng.StartAndReturnError(); !ok {
			log.Printf("macaudio: engine restart after a session change: %v", err)
		}
	})
}

// logSessionState prints what the session actually IS at a handoff. The two
// directions are not symmetric — the framework configures a session for a
// transmission and another for an incoming message, and neither is the app's
// own PlayAndRecord+DefaultToSpeaker — so a category, a mode and a sample rate
// in the log is what turns "no audio" into a diagnosis. Best-effort: a missing
// selector prints nothing rather than failing anything.
func logSessionState(what string) {
	inPool(func() {
		s := objc.ID(objc.GetClass("AVAudioSession")).Send(selSharedInstance)
		if s == 0 {
			return
		}
		log.Printf("macaudio: session %s: category=%s mode=%s rate=%.0f "+
			"outCh=%d inCh=%d",
			what,
			objcrt.GoString(s.Send(selCategory)),
			objcrt.GoString(s.Send(selMode)),
			objc.Send[float64](s, selSampleRate),
			objc.Send[int](s, selOutputChannels),
			objc.Send[int](s, selInputChannels))
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
			out = fmt.Errorf("macaudio: setCategory PlayAndRecord: %s", errDetail(catErr.Err()))
			return
		}
		var actErr objcrt.ErrOut
		if !objc.Send[bool](s, selSetActiveError, true, actErr.Ptr()) {
			out = fmt.Errorf("macaudio: setActive: %s", errDetail(actErr.Err()))
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
