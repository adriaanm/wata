// Legs 2 and 3: AVAudioEngine render and capture through the generated
// bindings (go-pkgs/appleptt/avfaudio) — ObjC dispatch via purego, no cgo.
//
// The one hand-written glue point: AVAudioPCMBuffer's floatChannelData is
// `float * const *`, a raw pointer pair the bindgen mapper rightly refuses,
// so the two accessors below reach it with a raw selector send + unsafe.Slice.
package main

import (
	"fmt"
	"os"
	"sync"
	"time"
	"unsafe"

	av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"
	"github.com/ebitengine/purego/objc"
)

var selFloatChannelData = objc.RegisterName("floatChannelData")

// channel0 returns buffer channel 0 as a float32 slice of length n.
// Standard AVAudioFormat is float32 deinterleaved: floatChannelData is a
// pointer to an array of per-channel pointers.
func channel0(buf av.AVAudioPCMBuffer, n int) []float32 {
	chans := objc.Send[unsafe.Pointer](buf.ID, selFloatChannelData)
	if chans == nil || n == 0 {
		return nil
	}
	ch0 := *(**float32)(chans)
	return unsafe.Slice(ch0, n)
}

// legRender: decode the foreign fixture with leg 1's converter, then play it
// through AVAudioEngine -> playerNode. The assertable check is the engine
// running and the scheduled buffer being consumed to completion, not the ear.
func legRender(fixture string) {
	fmt.Println("── leg 2: render decoded PCM through AVAudioEngine (generated bindings)")
	raw, err := os.ReadFile(fixture)
	if err != nil {
		check(false, "fixture: %v", err)
		return
	}
	pcm, err := decodeOpus(oggPackets(raw), 2880)
	if err != nil {
		check(false, "decode: %v", err)
		return
	}

	eng := av.GetAVAudioEngineClass().Alloc().Init()
	player := av.GetAVAudioPlayerNodeClass().Alloc().Init()
	eng.AttachNode(av.AVAudioNode{ID: player.ID})
	fmt48 := av.GetAVAudioFormatClass().Alloc().InitStandardFormatWithSampleRateChannels(48000, 1)
	mixer := eng.MainMixerNode()
	eng.ConnectToFormat(av.AVAudioNode{ID: player.ID}, av.AVAudioNode{ID: mixer.ID}, fmt48)

	buf := av.GetAVAudioPCMBufferClass().Alloc().InitWithPCMFormatFrameCapacity(fmt48, uint32(len(pcm)))
	dst := channel0(buf, len(pcm))
	for i, v := range pcm {
		dst[i] = float32(v) / 32768
	}
	buf.SetFrameLength(uint32(len(pcm)))

	ok, startErr := eng.StartAndReturnError()
	if !ok {
		check(false, "engine start: %v", startErr)
		return
	}
	check(eng.Running(), "engine running after start")

	done := make(chan struct{})
	start := time.Now()
	player.ScheduleBufferCompletionHandler(buf, func() { close(done) })
	player.Play()
	select {
	case <-done:
		fmt.Printf("     %d frames (%.3fs) consumed in %.3fs wall\n",
			len(pcm), float64(len(pcm))/sampleRate, time.Since(start).Seconds())
		check(true, "scheduled buffer consumed to completion")
	case <-time.After(10 * time.Second):
		check(false, "completion handler never fired (10s)")
	}
	check(eng.Running(), "engine still running after playback")
	player.Stop()
	eng.Stop()
}

// legCapture: tap the engine's input node, gather PCM, then push it through
// leg 1's encoder and decoder. Needs a mic TCC grant for the invoking
// terminal — silence in means the grant is missing, and the leg says so
// rather than failing the pipeline mechanics.
func legCapture(secs int) {
	fmt.Println("── leg 3: mic capture -> opus -> decode (generated bindings + converter)")
	eng := av.GetAVAudioEngineClass().Alloc().Init()
	input := eng.InputNode()
	node := av.AVAudioNode{ID: input.ID}
	hw := node.OutputFormatForBus(0)
	rate := hw.SampleRate()
	fmt.Printf("     input hardware format: %g Hz, %d ch\n", rate, hw.ChannelCount())

	var mu sync.Mutex
	var captured []float32
	node.InstallTapOnBusBufferSizeFormatBlock(0, 4800, hw, func(b av.AVAudioPCMBuffer, _ av.AVAudioTime) {
		n := int(b.FrameLength())
		src := channel0(b, n)
		mu.Lock()
		captured = append(captured, src...)
		mu.Unlock()
	})
	ok, startErr := eng.StartAndReturnError()
	if !ok {
		check(false, "engine start (input): %v", startErr)
		return
	}
	time.Sleep(time.Duration(secs) * time.Second)
	node.RemoveTapOnBus(0)
	eng.Stop()

	mu.Lock()
	pcmF := captured
	mu.Unlock()
	fmt.Printf("     captured %d frames (%.2fs)\n", len(pcmF), float64(len(pcmF))/rate)
	check(len(pcmF) > int(rate)*secs/2, "tap delivered buffers (%d frames)", len(pcmF))

	pcm := make([]int16, len(pcmF))
	for i, f := range pcmF {
		v := f * 32767
		if v > 32767 {
			v = 32767
		}
		if v < -32768 {
			v = -32768
		}
		pcm[i] = int16(v)
	}
	inRMS := rms(pcm)
	fmt.Printf("     capture RMS %.5f\n", inRMS)
	if inRMS < 1e-4 {
		fmt.Println("     NOTE: captured silence — mic TCC grant likely missing for this terminal")
		fmt.Println("           (System Settings -> Privacy & Security -> Microphone), or the mic is muted.")
	}
	check(inRMS > 1e-5, "non-silent capture (RMS %.5f)", inRMS)

	packets, _, err := encodeOpusRate(pcmBytes(pcm), 16000, rate)
	if err != nil {
		check(false, "encode captured audio: %v", err)
		return
	}
	dec, err := decodeOpus(packets, 960)
	if err != nil {
		check(false, "decode captured audio: %v", err)
		return
	}
	outRMS := rms(dec)
	fmt.Printf("     %d packets; decoded %d frames @48k, RMS %.5f\n", len(packets), len(dec), outRMS)
	check(len(dec) > 0 && outRMS > inRMS/4, "captured audio survives the opus round trip")
}
