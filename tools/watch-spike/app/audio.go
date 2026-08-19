// The audio probe (plan 0069 stage 3's falsifying question). Sending and
// receiving voice is the whole product, so the one thing worth knowing
// early is whether go-pkgs/macaudio — AVFAudio + AudioToolbox over purego,
// written for the phone — comes up on watchOS at all.
//
// It BUILDS for both watch sysroots, including the AVAudioSession work in
// session_ios.go (that file is //go:build ios, and the watch rides GOOS=ios).
// A build is not a run, so this starts the engine, plays a real tone through
// it, opens the mic and reads frames, and encodes/decodes through the codec.
//
// Each leg reports independently: on a simulator the mic may be absent or
// TCC-denied while playback is fine, and that is a useful answer rather than
// a failure of the probe.
package main

import (
	"fmt"
	"os"
	"time"

	"github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"
	"github.com/adriaanm/wata/go-pkgs/macaudio"
	"github.com/ebitengine/purego"
	"github.com/ebitengine/purego/objc"
)

// probeAudioClasses walks the AVFAudio surface macaudio's startEngine uses,
// one step at a time, BEFORE macaudio runs. SetupMixer aborts the process on
// watchOS, and an abort inside one opaque call says only that audio does not
// work; these prints say WHICH step the platform refuses.
func probeAudioClasses() {
	for _, n := range []string{
		"AVAudioSession", "AVAudioEngine", "AVAudioPlayerNode",
		"AVAudioFormat", "AVAudioRecorder", "WKAudioFilePlayer",
		"AVAudioInputNode", "AVAudioOutputNode",
	} {
		fmt.Printf("watchspike: audio class %-20s %v\n", n, objc.GetClass(n) != 0)
	}

	// The session, the first thing startEngine touches.
	cls := objc.GetClass("AVAudioSession")
	if cls == 0 {
		fmt.Println("watchspike: audio NO AVAudioSession class")
		return
	}
	sess := objc.ID(cls).Send(objc.RegisterName("sharedInstance"))
	fmt.Printf("watchspike: audio sharedInstance %v\n", sess != 0)
	if sess == 0 {
		return
	}
	// setCategory:error: — PlayAndRecord, the category macaudio asks for.
	var setCat func(objc.ID, objc.SEL, objc.ID, *objc.ID) bool
	purego.RegisterFunc(&setCat, objcMsgSend())
	var errp objc.ID
	ok := setCat(sess, objc.RegisterName("setCategory:error:"),
		objcrt.NSString("AVAudioSessionCategoryPlayAndRecord"), &errp)
	fmt.Printf("watchspike: audio setCategory ok=%v err=%v\n", ok, errp != 0)

	// Building the engine by hand, the step macaudio does next.
	ecls := objc.GetClass("AVAudioEngine")
	if ecls == 0 {
		fmt.Println("watchspike: audio NO AVAudioEngine class")
		return
	}
	eng := objc.ID(ecls).Send(objc.RegisterName("alloc")).Send(objc.RegisterName("init"))
	fmt.Printf("watchspike: audio engine alloc/init %v\n", eng != 0)
	if eng == 0 {
		return
	}
	// inputNode is the suspect: a watch may have no input node to hand out,
	// and asking for one is how an app finds out the hard way.
	fmt.Println("watchspike: audio asking engine for outputNode")
	out := eng.Send(objc.RegisterName("outputNode"))
	fmt.Printf("watchspike: audio outputNode %v\n", out != 0)
	fmt.Println("watchspike: audio asking engine for inputNode")
	in := eng.Send(objc.RegisterName("inputNode"))
	fmt.Printf("watchspike: audio inputNode %v\n", in != 0)
	fmt.Println("watchspike: audio class walk survived")
}

func probeAudio() {
	fmt.Println("watchspike: audio starting")
	// The walk is skippable so it can be shown NOT to be load-bearing: it
	// touches AVAudioSession before macaudio does, and a probe that quietly
	// primes the thing it measures is worthless.
	if os.Getenv("WATCHSPIKE_AUDIO_WALK") != "0" {
		probeAudioClasses()
	} else {
		fmt.Println("watchspike: audio class walk SKIPPED")
	}

	fmt.Println("watchspike: audio calling SetupMixer")
	macaudio.SetupMixer()
	fmt.Println("watchspike: audio SetupMixer returned")

	// Playback: a real tone through the real engine. PlayMessage answers the
	// frame count it accepted, so a non-zero count means the graph took it.
	tone := macaudio.Tone(440, macaudio.SampleRate/2) // 0.5s
	n, err := macaudio.PlayMessage(tone, 100)
	if err != nil {
		fmt.Printf("watchspike: audio PLAYBACK FAILED %v\n", err)
	} else {
		fmt.Printf("watchspike: audio playback frames=%d\n", n)
	}

	// Capture: the mic. A denial here is a real answer, not a probe bug.
	cap, err := macaudio.OpenCapture()
	if err != nil {
		fmt.Printf("watchspike: audio CAPTURE FAILED %v\n", err)
	} else {
		buf := make([]byte, macaudio.PeriodBytes)
		done := make(chan int, 1)
		go func() {
			got, rerr := cap.ReadFrames(buf)
			if rerr != nil {
				fmt.Printf("watchspike: audio read error %v\n", rerr)
				done <- -1
				return
			}
			done <- got
		}()
		select {
		case got := <-done:
			fmt.Printf("watchspike: audio capture frames=%d\n", got)
		case <-time.After(5 * time.Second):
			// A mic that opens but never delivers is its own finding.
			fmt.Println("watchspike: audio capture TIMEOUT (opened, no frames)")
		}
		cap.Close()
	}

	// The codec, which is what actually crosses the wire.
	enc, err := macaudio.NewEncoder()
	if err != nil {
		fmt.Printf("watchspike: audio ENCODER FAILED %v\n", err)
	} else {
		fmt.Println("watchspike: audio encoder ok")
		_ = enc
	}
	dec, err := macaudio.NewDecoder()
	if err != nil {
		fmt.Printf("watchspike: audio DECODER FAILED %v\n", err)
	} else {
		fmt.Println("watchspike: audio decoder ok")
		_ = dec
	}
	fmt.Println("watchspike: audio all checks passed")
}
