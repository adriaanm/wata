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
package macaudio

import (
	"fmt"
	"log"

	av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"
	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// The permission block, package-held so the Go side outlives the ask (the
// framework copies the ObjC side itself; this keeps the trampoline alive).
var recordPermBlock objc.Block

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

func sessionActivate() error {
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
		// fails per-command, the next one works).
		recordPermBlock = objc.NewBlock(func(_ objc.Block, granted bool) {
			log.Printf("macaudio: record permission granted=%v", granted)
		})
		s.Send(selRequestRecordPerm, recordPermBlock)
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
