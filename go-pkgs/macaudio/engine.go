// The AVAudioEngine half of the package: the one engine the client owns, the
// speaker path, and the ObjC lifetime rules both obey.
//
// THREADING. Everything here is called from the audio thread — an sgo.fork,
// i.e. an ordinary goroutine that is NOT the AppKit main thread. AVAudioEngine
// does not require the main thread and no runloop is needed, so nothing hops;
// the package never locks an OS thread (macshell already owns that seam for
// AppKit). Completion handlers arrive on a CoreAudio thread and only close a
// channel.
//
// LIFETIME (docs/design/wata-mac.md, "Threading and lifetime facts").
// Autorelease pools are the caller's job: every excursion into ObjC below runs
// inside a pool of its own, so an object we KEEP across pools must be owned or
// retained. The engine, the player node and the 48kHz format come from
// -alloc/-init (owned); the main mixer node comes from a property and is
// retained explicitly. `-init` may return a different object than `-alloc`, so
// every construction adopts the returned id.
package macaudio

import (
	"errors"
	"fmt"
	"log"
	"os"
	"sync"
	"time"
	"unsafe"

	av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

var (
	selFloatChannelData = objc.RegisterName("floatChannelData")
	selRetain           = objc.RegisterName("retain")
	selRelease          = objc.RegisterName("release")
	selSetVolume        = objc.RegisterName("setVolume:")
)

// ── autorelease pools ───────────────────────────────────────────────────────

var (
	poolPush func() uintptr
	poolPop  func(p uintptr)
	poolOnce sync.Once
)

func poolInit() {
	poolOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libobjc.A.dylib", purego.RTLD_GLOBAL|purego.RTLD_LAZY)
		if err != nil {
			panic("macaudio: dlopen libobjc: " + err.Error())
		}
		purego.RegisterLibFunc(&poolPush, lib, "objc_autoreleasePoolPush")
		purego.RegisterLibFunc(&poolPop, lib, "objc_autoreleasePoolPop")
	})
}

// inPool runs f inside a fresh autorelease pool.
func inPool(f func()) {
	poolInit()
	p := poolPush()
	defer poolPop(p)
	f()
}

// ── the engine ──────────────────────────────────────────────────────────────

type engine struct {
	eng    av.AVAudioEngine
	player av.AVAudioPlayerNode
	mixer  av.AVAudioMixerNode
	fmt48  av.AVAudioFormat
}

var (
	setupOnce sync.Once
	setupDone bool
	fakeMode  bool

	// engMu guards the engine and the remembered build failure. The audio
	// thread builds it; on iOS a PushToTalk session transition can arrive on a
	// framework thread and try again (see ensureEngine).
	engMu    sync.Mutex
	setupErr error
	eng      *engine

	// The buffer handed to the player by the previous PlayMessage, released at
	// the START of the next one. scheduleBuffer:completionHandler: fires when
	// the player has CONSUMED the buffer, which the docs allow to be slightly
	// before the last frames leave the mix, so releasing it inline would race
	// the renderer; one message of grace costs at most a few hundred KB and is
	// simpler than a timer.
	pending objc.ID
)

// The audio session generation. On iOS the AVAudioSession can be reconfigured
// underneath a running engine by something that is not this package —
// PushToTalk activating its own session for an episode, and handing it back
// afterwards. That stops the engine and strands whatever the player node had
// scheduled: its completion handler never fires (the same failure the reset in
// PlayMessage exists for, arriving from the other direction). So every session
// transition closes the current watch channel, and a blocked playback learns
// about it immediately instead of waiting out its timeout.
//
// Nothing outside session_ios.go ever calls noteSessionChanged, so on macOS the
// channel is created once and never closed.
var (
	sessMu    sync.Mutex
	sessWatch = make(chan struct{})
)

// sessionWatch is a channel closed by the NEXT session transition. Take it
// before scheduling anything and select on it while waiting.
func sessionWatch() <-chan struct{} {
	sessMu.Lock()
	defer sessMu.Unlock()
	return sessWatch
}

// noteSessionChanged wakes everything waiting on the current generation and
// arms the next.
func noteSessionChanged() {
	sessMu.Lock()
	close(sessWatch)
	sessWatch = make(chan struct{})
	sessMu.Unlock()
}

// SetupMixer prepares the audio hardware. It is called EXACTLY ONCE, at audio
// thread start — that is the device contract (per-recording route switching
// crashed the BQ268's ADSP) and this package keeps it, building the engine and
// starting it here.
//
// It returns nothing, so a failure cannot be reported to its caller. Instead
// the failure is remembered and LOGGED, and every later entry point that needs
// the hardware (OpenCapture, PlayMessage) returns it as an error — the audio
// thread's own error tiers then turn it into AeRecordingError/AePlaybackError.
// Calling OpenCapture or PlayMessage without SetupMixer is likewise an error,
// never a nil dereference.
//
// WATA_MAC_AUDIO=fake, read once here, replaces the two HARDWARE ends only:
// the mic becomes a generated tone and the speaker becomes a clock. The codec,
// the buffer sizes and every byte of framing stay real.
func SetupMixer() {
	setupOnce.Do(func() {
		setupDone = true
		fakeMode = os.Getenv("WATA_MAC_AUDIO") == "fake"
		if fakeMode {
			log.Printf("macaudio: WATA_MAC_AUDIO=fake — synthetic mic, clock speaker, real codec")
			return
		}
		engMu.Lock()
		setupErr = startEngine()
		engMu.Unlock()
		if setupErr != nil {
			log.Printf("macaudio: SetupMixer failed, audio is unavailable: %v", setupErr)
		}
	})
}

// ensureEngine builds the engine if a previous attempt left none. iOS only in
// practice: a launch whose session activation was refused because a PushToTalk
// channel was already restored used to end here permanently, with every later
// play answering the remembered error. A session transition is a new set of
// conditions and worth one more attempt.
func ensureEngine() {
	engMu.Lock()
	defer engMu.Unlock()
	if !setupDone || fakeMode || eng != nil {
		return
	}
	if setupErr = startEngine(); setupErr != nil {
		log.Printf("macaudio: the engine still cannot be built: %v", setupErr)
		return
	}
	log.Printf("macaudio: the engine was built on a retry — audio is available again")
}

// engineOrNil is the engine, or nil, without the diagnosis audioEngine gives.
func engineOrNil() *engine {
	engMu.Lock()
	defer engMu.Unlock()
	return eng
}

func startEngine() error {
	if err := loadAudioToolbox(); err != nil {
		return err
	}
	// iOS only (no-op elsewhere): the AVAudioSession category decides what the
	// IO unit is configured with, so it is applied before the engine exists.
	//
	// A FAILURE HERE IS NOT FATAL, and that is the whole lesson of the
	// 2026-08-18 device log: a PushToTalk app joined to a channel is REFUSED
	// its own setActive: ('ent?', missing entitlement), because activation is
	// the framework's. Returning that error built no engine and made every
	// later PlayMessage and OpenCapture answer with it — an app with no audio
	// at all, including for messages the user tapped. The engine's own start,
	// below, is the real test of whether audio works; it activates the session
	// on the app's behalf when the app may not. See session_ios.go.
	if err := sessionActivate(); err != nil {
		log.Printf("macaudio: the audio session was not fully configured, "+
			"building the engine anyway: %v", err)
	}
	var err error
	inPool(func() {
		e := av.GetAVAudioEngineClass().Alloc().Init() // -init: adopt the result
		if e.ID == 0 {
			err = errors.New("macaudio: AVAudioEngine alloc/init returned nil")
			return
		}
		player := av.GetAVAudioPlayerNodeClass().Alloc().Init()
		if player.ID == 0 {
			err = errors.New("macaudio: AVAudioPlayerNode alloc/init returned nil")
			return
		}
		f48 := av.GetAVAudioFormatClass().Alloc().InitStandardFormatWithSampleRateChannels(SampleRate, Channels)
		if f48.ID == 0 {
			err = errors.New("macaudio: AVAudioFormat alloc/init returned nil")
			return
		}
		e.AttachNode(av.AVAudioNode{ID: player.ID})
		mixer := e.MainMixerNode() // engine property: autoreleased-only
		mixer.ID.Send(selRetain)   // we keep it across pools
		e.ConnectToFormat(av.AVAudioNode{ID: player.ID}, av.AVAudioNode{ID: mixer.ID}, f48)
		// iOS only (no-op on macOS): the input node must exist before the
		// first start or the IO unit comes up output-only. See session_ios.go.
		prepareInput(e)
		ok, startErr := e.StartAndReturnError()
		if !ok {
			err = fmt.Errorf("macaudio: AVAudioEngine start: %w", startErr)
			return
		}
		eng = &engine{eng: e, player: player, mixer: mixer, fmt48: f48}
	})
	if err == nil {
		// The success side of the story. Until this line existed the log only
		// ever mentioned the engine when it FAILED, so "audio is available"
		// was never a thing the device log said.
		log.Printf("macaudio: the audio engine is running")
	}
	return err
}

// audioEngine is the guard every hardware entry point goes through.
func audioEngine() (*engine, error) {
	if !setupDone {
		return nil, errors.New("macaudio: SetupMixer has not run (the audio thread calls it once at start)")
	}
	// One more attempt before answering with a stale failure: the conditions
	// that defeated the build (a PushToTalk channel joined before the audio
	// thread started) may be gone by now.
	ensureEngine()
	engMu.Lock()
	e, err := eng, setupErr
	engMu.Unlock()
	if e != nil {
		return e, nil
	}
	if err != nil {
		return nil, err
	}
	return nil, errors.New("macaudio: no engine (fake backend is active)")
}

// ── PCM buffer channel access ───────────────────────────────────────────────
//
// AVAudioPCMBuffer's floatChannelData is `float * const *`, a raw pointer pair
// the bindgen mapper rightly refuses, so these two reach it with a raw selector
// send. objc.Send[unsafe.Pointer] rather than [uintptr] keeps the deref
// chain clear of vet's unsafe.Pointer(uintptr-expr) rule.

// channelData returns one slice per channel of a standard (deinterleaved)
// float buffer, or a single interleaved slice of frames*stride when the format
// is interleaved.
func channelData(buf av.AVAudioPCMBuffer, frames, chans, stride int) [][]float32 {
	p := objc.Send[unsafe.Pointer](buf.ID, selFloatChannelData)
	if p == nil || frames == 0 || chans == 0 {
		return nil
	}
	ptrs := unsafe.Slice((**float32)(p), chans)
	if stride > 1 {
		// interleaved: one pointer, frames*stride samples behind it
		return [][]float32{unsafe.Slice(ptrs[0], frames*stride)}
	}
	out := make([][]float32, chans)
	for i := range out {
		out[i] = unsafe.Slice(ptrs[i], frames)
	}
	return out
}

// ── playback ────────────────────────────────────────────────────────────────

// PlayMessage plays a whole decoded S16_LE mono buffer and BLOCKS until it has
// been consumed, returning the frames played.
//
// pcm is zero-padded to a whole period, matching the device backend (which
// pads rather than truncating up to 40ms of tail audio). vol is the device's
// ASM scale, 0..8192, mapped onto the player node's 0..1 gain; vol <= 0 leaves
// the gain alone. The gain is set on the PLAYER node rather than the main
// mixer's output so it scales this message only and never the capture path.
func PlayMessage(pcm []byte, vol int) (int, error) {
	if rem := len(pcm) % PeriodBytes; rem != 0 {
		// COPY rather than append: the caller's slice may have spare capacity,
		// and appending would write the padding zeros into its backing array.
		padded := make([]byte, len(pcm)+PeriodBytes-rem)
		copy(padded, pcm)
		pcm = padded
	}
	if len(pcm) == 0 {
		return 0, errors.New("macaudio: PlayMessage: empty pcm")
	}
	frames := len(pcm) / (FrameSize * Channels)
	if !setupDone {
		return 0, errors.New("macaudio: SetupMixer has not run (the audio thread calls it once at start)")
	}
	if fakeMode {
		return fakePlay(frames)
	}
	e, err := audioEngine()
	if err != nil {
		return 0, err
	}

	t0 := time.Now()
	done := make(chan struct{})
	// Taken BEFORE the buffer is scheduled: a session change between here and
	// the select below closes it, and this playback is dead either way.
	sess := sessionWatch()
	var scheduleErr error
	inPool(func() {
		if pending != 0 {
			pending.Send(selRelease)
			pending = 0
		}
		buf := av.GetAVAudioPCMBufferClass().Alloc().InitWithPCMFormatFrameCapacity(e.fmt48, uint32(frames))
		if buf.ID == 0 {
			scheduleErr = errors.New("macaudio: AVAudioPCMBuffer alloc/init returned nil")
			return
		}
		dst := channelData(buf, frames, Channels, 1)
		if dst == nil {
			buf.ID.Send(selRelease)
			scheduleErr = errors.New("macaudio: AVAudioPCMBuffer has no float channel data")
			return
		}
		ch := dst[0]
		for i := 0; i < frames; i++ {
			ch[i] = float32(int16(uint16(pcm[2*i])|uint16(pcm[2*i+1])<<8)) / 32768
		}
		buf.SetFrameLength(uint32(frames))
		// Reset the player before scheduling. A capture session restarts the
		// engine under it (see OpenCapture), which leaves the node in a state
		// where a freshly scheduled buffer is never consumed — the completion
		// handler simply never fires. -stop clears its (empty) schedule and its
		// sample time, and the next -play renders again. Measured: without
		// this, the FIRST playback after any recording times out.
		e.player.Stop()
		if vol > 0 {
			e.player.ID.Send(selSetVolume, gainFor(vol))
		}
		e.player.ScheduleBufferCompletionHandler(buf, func() { close(done) })
		e.player.Play()
		pending = buf.ID // released at the next PlayMessage (see `pending`)
	})
	if scheduleErr != nil {
		setPlayStats("schedule-failed: " + scheduleErr.Error())
		return 0, scheduleErr
	}

	dur := time.Duration(frames) * time.Second / SampleRate
	select {
	case <-done:
	case <-sess:
		// The audio session was reconfigured under the running engine (on iOS
		// that is PushToTalk taking it, or handing it back). The engine stops,
		// the player node keeps a schedule nothing will consume, and the
		// completion handler NEVER fires — so waiting out the timeout below
		// would spend dur+5s to learn what is already known.
		setPlayStats(fmt.Sprintf("frames=%d SESSION-CHANGED after %.1fs", frames, time.Since(t0).Seconds()))
		return 0, fmt.Errorf("macaudio: the audio session changed under the playback of %d frames", frames)
	case <-time.After(dur + 5*time.Second):
		setPlayStats(fmt.Sprintf("frames=%d TIMEOUT after %.1fs", frames, time.Since(t0).Seconds()))
		return 0, fmt.Errorf("macaudio: playback of %d frames never completed", frames)
	}
	setPlayStats(fmt.Sprintf("primeState=%s frames=%d wall=%.0fms audio=%.0fms",
		StateName(StateRunning), frames, time.Since(t0).Seconds()*1000, dur.Seconds()*1000))
	return frames, nil
}

// gainFor maps the device's ASM volume scale (0..8192) onto the 0..1 linear
// gain an AVAudioMixing node takes.
func gainFor(vol int) float32 {
	g := float32(vol) / 8192
	if g > 1 {
		g = 1
	}
	return g
}
