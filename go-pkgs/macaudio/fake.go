// The fake backend, selected by WATA_MAC_AUDIO=fake at SetupMixer.
//
// It replaces the two HARDWARE ends and nothing else: the microphone becomes a
// tone generator paced by a real clock, and the speaker becomes a clock. The
// codec, the ring buffer, the blocking read discipline, the period sizes and
// every byte of Ogg framing above this package stay real — a fake that also
// faked those would only be testing itself.
//
// Why it exists: an unattended run (the test suite, mac-smoke, anything
// CI-shaped) has no microphone TCC grant and no business making noise, but the
// audio thread it is exercising must still record, encode, upload, sync,
// decode and play for the exercise to mean anything.
package macaudio

import (
	"fmt"
	"math"
	"time"
)

// FakeToneHz is the pitch of the synthetic microphone. 440Hz because the
// numeric checks around it (Goertzel purity, the repo's fixture) are all
// written at 440.
const FakeToneHz = 440

// openFakeCapture starts a goroutine that pushes one period of continuous
// 440Hz tone into the shared ring every period-duration, so a reader blocks and
// resumes on exactly the cadence a real microphone imposes.
func openFakeCapture() *Capture {
	c := newCapture()
	c.stop = make(chan struct{})
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		period := time.Duration(FramesPerPeriod) * time.Second / SampleRate
		tick := time.NewTicker(period)
		defer tick.Stop()
		phase := 0 // sample index, kept across periods so the tone is continuous
		// Prime one period immediately: a real mic's first buffer arrives
		// within a period too, and this keeps a test's first ReadFrames from
		// waiting a whole tick for nothing.
		c.push(fakePeriod(&phase))
		for {
			select {
			case <-c.stop:
				return
			case <-tick.C:
				c.push(fakePeriod(&phase))
			}
		}
	}()
	return c
}

// fakePeriod renders one period of the tone, advancing the phase counter.
func fakePeriod(phase *int) []byte {
	out := make([]byte, PeriodBytes)
	for i := 0; i < FramesPerPeriod; i++ {
		v := int16(16000 * math.Sin(2*math.Pi*FakeToneHz*float64(*phase+i)/SampleRate))
		out[2*i] = byte(v)
		out[2*i+1] = byte(uint16(v) >> 8)
	}
	*phase += FramesPerPeriod
	return out
}

// fakePlay consumes the buffer against a clock: it blocks for the audio's own
// duration, exactly as a speaker would, and reports the frames played.
func fakePlay(frames int) (int, error) {
	dur := time.Duration(frames) * time.Second / SampleRate
	t0 := time.Now()
	time.Sleep(dur)
	setPlayStats(fmt.Sprintf("fake frames=%d wall=%.0fms audio=%.0fms",
		frames, time.Since(t0).Seconds()*1000, dur.Seconds()*1000))
	return frames, nil
}
