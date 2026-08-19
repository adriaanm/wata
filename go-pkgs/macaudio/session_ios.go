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
// SESSION OWNERSHIP AND PushToTalk (plans 0065 tier 3, 0066). A joined
// PushToTalk app does not own its audio session — at all, and not merely during
// an episode. The FRAMEWORK activates it, for a transmission or for an incoming
// message, and hands it over through channelManager:didActivateAudioSession:.
// The rule, its two hardware proofs and Apple's statement of it are in
// sessionpolicy.go, which decides; this file only obeys.
//
// WHAT THAT COSTS, stated plainly because it is surprising: between episodes a
// joined app has NO RUNNING ENGINE, and cannot get one. That is the normal
// state of a joined walkie-talkie, not a defect, and the log must not call it
// one. It also means every sound the app makes is an episode:
//
//   - a transmission the user starts (on-screen button or the system talk
//     button) goes through requestBeginTransmitting, and recording begins on
//     the handover that follows;
//   - PLAYBACK — including a message the user tapped, which has nothing to do
//     with PushToTalk from the user's point of view — asks the framework for an
//     episode too (setActiveRemoteParticipant, then play on the handover, then
//     clear it). beginPlaybackSession below is that ask, so nothing above this
//     package has to know: the audio thread requests a sound and gets one.
//
// While the framework owns the session (an episode is live):
//   - sessionActivate() sets NOTHING: no category, no setActive. The session
//     the framework handed over is already configured for the episode, and
//     both calls are exactly what must not race it.
//   - the engine is RESET — stopped and started — because it configures its IO
//     unit from the session at start, and a session whose category, mode or
//     sample rate changed underneath a running engine leaves that unit built
//     for the old one. That is silence, not an error.
//
// A session step that fails is LOGGED AND SURVIVED, never fatal: an app that
// cannot have a session right now must still be an app.
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
// When the framework gives it back (didDeactivateAudioSession) the app is a
// plain audio app again — as far as the JOIN allows: still joined means still
// category-only. Only leaving the channel restores self-activation. Every
// direction is best-effort and only logged; an audio session that cannot be
// reclaimed must not wedge the app, and the audio thread already reports
// per-command failures.
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

// How long a playback waits for the episode it asked for. The handover is one
// framework round trip — observed in the same instant as the request on
// hardware — so this bounds a callback that has already been requested, not the
// user's patience. A playback that times out here is reported to the audio
// thread, which surfaces it per command.
const playbackSessionWait = 3 * time.Second

// pttJoined is true while a PushToTalk channel is joined; pttOwns while the
// framework has an episode's session handed over. Guarded by pttMu, which also
// serializes the handoff calls against each other (they arrive on the
// framework's threads). pttEpoch counts ownership transitions, so a grace timer
// can tell "still the episode I armed for" from "the framework already handed
// back, or took it again".
var (
	pttMu     sync.Mutex
	pttJoined bool
	pttOwns   bool
	pttEpoch  uint64
)

// PTTChannelJoined records whether a channel is joined. Call it from
// channelManager:didJoinChannelWithUUID: and :didLeaveChannelWithUUID: —
// including the RESTORATION join, which is how an app that was killed comes
// back already joined before its audio thread has even started.
func PTTChannelJoined(joined bool) {
	pttMu.Lock()
	was := pttJoined
	pttJoined = joined
	pttMu.Unlock()
	if was == joined {
		return
	}
	if joined {
		log.Printf("macaudio: a PushToTalk channel is joined — the framework owns " +
			"the audio session from here, and every sound is an episode")
		// The engine may be running on a session the app activated for itself
		// before the join. It may not stay: a self-activated session is exactly
		// what "works in the foreground and fails in the background" (Apple
		// DTS), so the app gives it up here and runs the engine only on what the
		// framework hands over. Nothing is lost — the graph is still built.
		noteSessionChanged()
		stopForSession("a PushToTalk channel was joined")
		return
	}
	log.Printf("macaudio: the PushToTalk channel is gone — the audio session is " +
		"the app's own again")
	noteSessionChanged()
	reclaimSession()
}

// policyNow is what the app may do to the session right now (sessionpolicy.go).
func policyNow() sessionPolicy {
	pttMu.Lock()
	defer pttMu.Unlock()
	return policyFor(pttJoined, pttOwns)
}

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
	noteSessionChanged()
	startForSession()
	logSessionState("PushToTalk activated")
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
	if policyNow().needEpisode {
		// Still joined: the session that just went away was never ours to
		// replace, and the engine cannot legally run until the next handover.
		// Trying anyway is what printed `engine restart after a session change:
		// … 'what'` after every episode (device log 2026-08-19).
		stopForSession("the episode ended and the channel is still joined")
		return
	}
	reclaimSession()
}

// reclaimSession takes the session back for the app — the LEFT-the-channel case
// and nothing else. Retried, because the first attempt can land while
// PushToTalk is still tearing its own session down.
//
// It ALWAYS resets the engine, even when every attempt failed: an engine is
// what plays audio, and a session activation is only what makes it loud.
func reclaimSession() {
	var err error
	for i := 0; i < reclaimTries; i++ {
		if i > 0 {
			time.Sleep(reclaimGap)
		}
		if err = sessionActivate(); err == nil {
			break
		}
		log.Printf("macaudio: reclaiming the audio session failed (try %d/%d): %s",
			i+1, reclaimTries, errDetail(err))
	}
	if err != nil {
		log.Printf("macaudio: the audio session was not reclaimed; the engine is "+
			"reset anyway: %s", errDetail(err))
	}
	// AFTER the reset, not before. Logged before it, this line reported the
	// window between "category set" and "engine started" — which while a
	// channel is joined has no active session at all, and so printed inCh=0
	// and read as a broken microphone (device log, 2026-08-18).
	startForSession()
	logSessionState("reclaimed by wata")
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

// startForSession rebuilds the engine's relationship with the audio session
// that is now current. It is deliberately a full STOP and start rather than
// "start it if it stopped": the engine configures its IO unit from the session
// at start, and a session whose category, mode or sample rate changed
// underneath a RUNNING engine leaves that unit configured for the old one —
// which is silence rather than an error. Stopping first is also what clears
// the player node, whose scheduled buffer the session change stranded (the
// same reset PlayMessage does for the capture case, and for the same reason:
// the completion handler never fires).
//
// Only ever called where the policy allows a start — on a handover, or once the
// channel is gone. Called while joined and idle it would be refused with
// 'what', which is what it used to do after every episode.
func startForSession() {
	// A launch that could not start (joined by restoration before the audio
	// thread ran) may have no engine at all. A session transition is exactly
	// when it is worth another try.
	ensureEngine()
	e := engineOrNil()
	if e == nil {
		return
	}
	inPool(func() {
		e.player.Stop()
		if e.eng.Running() {
			e.eng.Stop()
		}
		if ok, err := e.eng.StartAndReturnError(); !ok {
			log.Printf("macaudio: engine start on the framework's session failed: %s",
				errDetail(err))
		}
	})
}

// stopForSession gives the hardware up: the app is joined and idle, so it may
// not run an engine at all until the framework hands a session over. The graph
// survives — only the IO is stopped — so the next handover is a start rather
// than a rebuild.
//
// The player is stopped first for the same reason as everywhere else: a buffer
// scheduled on a node that stops is never consumed and its completion handler
// never fires. Whatever was playing is already dead; PlayMessage learns it from
// the session watch rather than from a timeout.
func stopForSession(why string) {
	e := engineOrNil()
	if e == nil {
		return
	}
	inPool(func() {
		e.player.Stop()
		if e.eng.Running() {
			e.eng.Stop()
			log.Printf("macaudio: the engine is stopped — %s; it runs again when "+
				"PushToTalk hands a session over", why)
		}
	})
}

// beginPlaybackSession makes it legal to play right now, and returns the
// undo. While a channel is joined the app has no session of its own, so a
// sound — even one the user asked for by tapping a message, which has nothing
// to do with PushToTalk as far as they are concerned — means asking the
// framework for an episode: raise the speaker, wait for the handover that
// activates and starts the engine, play, lower the speaker.
//
// It is a no-op in the two cases that are not that: unjoined (the app owns its
// session) and mid-episode (the framework already handed one over — a push-woken
// message, or a transmission — and that episode's owner ends it, not us).
func beginPlaybackSession() (func(), error) {
	if !policyNow().needEpisode {
		return func() {}, nil
	}
	if PTTBeginEpisode == nil {
		return nil, errors.New("macaudio: a PushToTalk channel is joined, so playback " +
			"needs an episode, and no channel manager registered one (iosshell)")
	}
	ep := PTTBeginEpisode("playing a message")
	if ep == 0 {
		return nil, errors.New("macaudio: a PushToTalk channel is joined and no episode " +
			"could be opened for playback")
	}
	// A negative id means an episode was already live and is somebody else's to
	// end — a push-woken burst still draining, or a transmission. Play on it and
	// leave the speaker alone.
	end := func() {}
	if ep > 0 {
		end = func() {
			if PTTEndEpisode != nil {
				PTTEndEpisode(ep)
			}
		}
	}
	if err := waitForHandover(playbackSessionWait); err != nil {
		end()
		return nil, err
	}
	return end, nil
}

// waitForHandover blocks until the framework has handed an activated session
// over, or the wait runs out. It watches the session generation rather than
// polling: PTTSessionActivated closes that channel, so the wake-up is the
// callback itself.
func waitForHandover(limit time.Duration) error {
	deadline := time.Now().Add(limit)
	for {
		// Taken before the check, so an activation between the two is not lost.
		next := sessionWatch()
		pttMu.Lock()
		owns := pttOwns
		pttMu.Unlock()
		if owns {
			return nil
		}
		left := time.Until(deadline)
		if left <= 0 {
			return fmt.Errorf("macaudio: PushToTalk did not hand a session over "+
				"within %v, so there is nothing to play on", limit)
		}
		select {
		case <-next:
		case <-time.After(left):
		}
	}
}

// logSessionState prints what the session actually IS. The framework's session
// for an episode, the framework's for a transmission, and the app's own are all
// different, so a category, a mode and a sample rate in the log is what turns
// "no audio" into a diagnosis. Best-effort: a missing selector prints nothing
// rather than failing anything.
//
// inRate is the ENGINE's input node format, and it is the number that predicts
// recording — a 0 there is plan 0063's failure, an IO unit brought up
// output-only, and OpenCapture refuses on it. The session's own inCh is
// reported too but reads 0 whenever the session is not ACTIVE, which while a
// channel is joined is most of the time (the app may not activate it; the
// engine's start does). So inCh=0 on its own says nothing; inCh=0 with
// inRate=0 says recording is broken.
//
// WHEN it is called matters as much as what it prints: after an engine reset,
// never between setting a category and starting the engine, or it describes a
// state that lasted a millisecond.
func logSessionState(what string) {
	inRate := 0.0
	if e := engineOrNil(); e != nil {
		inPool(func() {
			// the same reach OpenCapture makes, and the same value it
			// refuses on (capture.go).
			node := av.AVAudioNode{ID: e.eng.InputNode().ID}
			inRate = node.OutputFormatForBus(0).SampleRate()
		})
	}
	inPool(func() {
		s := objc.ID(objc.GetClass("AVAudioSession")).Send(selSharedInstance)
		if s == 0 {
			return
		}
		log.Printf("macaudio: session %s: category=%s mode=%s rate=%.0f "+
			"outCh=%d inCh=%d engineInRate=%.0f",
			what,
			objcrt.GoString(s.Send(selCategory)),
			objcrt.GoString(s.Send(selMode)),
			objc.Send[float64](s, selSampleRate),
			objc.Send[int](s, selOutputChannels),
			objc.Send[int](s, selInputChannels),
			inRate)
	})
}

// sessionActivate applies as much of the app's own session configuration as
// the current rule allows. It NEVER reports "the session is the framework's"
// as an error: that is the normal state of a joined walkie-talkie, and the
// caller's job is to build an engine either way.
func sessionActivate() error {
	p := policyNow()
	// The record-permission ask does NOT depend on the policy: it activates
	// nothing, and it is the reason the system prompt appears at audio-thread
	// start rather than mid-press. Gated on the category the way it used to be,
	// a launch that found a channel joined never asked at all.
	if err := loadAVFAudio(); err != nil {
		return err
	}
	askRecordPermission()
	if !p.setCategory {
		// Joined: the session is the framework's, whether or not an episode is
		// live. Anything set here is either refused outright ('ent?') or
		// corrupts the framework's own audio path in the background (Apple DTS).
		log.Printf("macaudio: the audio session is PushToTalk's — leaving it alone")
		return nil
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
		// Reached only when nothing else owns the session — the policy has
		// already ruled out every joined case, where this call is refused with
		// 'ent?' (AVAudioSessionErrorCodeMissingEntitlement).
		var actErr objcrt.ErrOut
		if !objc.Send[bool](s, selSetActiveError, true, actErr.Ptr()) {
			out = fmt.Errorf("macaudio: setActive: %s", errDetail(actErr.Err()))
			return
		}
	})
	return out
}

// loadAVFAudio makes AVAudioSession reachable. dlopen is idempotent and cheap,
// so every entry point that sends a selector to the class calls it rather than
// assuming an earlier caller did.
func loadAVFAudio() error {
	if _, err := purego.Dlopen(
		"/System/Library/Frameworks/AVFAudio.framework/AVFAudio",
		purego.RTLD_GLOBAL|purego.RTLD_LAZY); err != nil {
		return fmt.Errorf("macaudio: dlopen AVFAudio: %w", err)
	}
	return nil
}

// askRecordPermission fires the microphone ask, once per process. Fire-and-
// forget: the answer is only logged (a press before the grant fails per
// command, the next one works). ONCE, because every session transition runs
// sessionActivate again and each ask would mint another block the framework
// then holds onto.
func askRecordPermission() {
	permOnce.Do(func() {
		inPool(func() {
			s := objc.ID(objc.GetClass("AVAudioSession")).Send(selSharedInstance)
			if s == 0 {
				return
			}
			recordPermBlock = objc.NewBlock(func(_ objc.Block, granted bool) {
				log.Printf("macaudio: record permission granted=%v", granted)
			})
			s.Send(selRequestRecordPerm, recordPermBlock)
		})
	})
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
