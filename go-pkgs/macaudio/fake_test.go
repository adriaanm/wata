// The hardware legs, run under the fake backend: no microphone grant, no
// speaker, nothing attended. What is exercised is the code the audio thread
// actually depends on — the blocking period-sized read, the ring's overrun
// policy, the frame count playback reports — with only the mic and the speaker
// themselves replaced.
package macaudio

import (
	"os"
	"testing"
	"time"
)

// The whole suite runs with the fake backend selected, because SetupMixer reads
// WATA_MAC_AUDIO exactly once: a test that wanted the real engine would have to
// be a different process, which is exactly what `just audio-spike` is for.
func TestMain(m *testing.M) {
	os.Setenv("WATA_MAC_AUDIO", "fake")
	SetupMixer()
	os.Exit(m.Run())
}

const periodDur = time.Duration(FramesPerPeriod) * time.Second / SampleRate // 40ms

// TestFakeCaptureBlocksAndDeliversWholePeriods: every ReadFrames returns
// exactly one period, never short, and the reads are PACED by the generator —
// the periods cannot arrive faster than real time, which is what "blocks" means
// here (a busy-spinning or short-returning read would finish instantly).
func TestFakeCaptureBlocksAndDeliversWholePeriods(t *testing.T) {
	mic, err := OpenCapture()
	if err != nil {
		t.Fatalf("OpenCapture: %v", err)
	}
	defer mic.Close()

	// 16 periods = 640ms, enough that dropping the codec's first 250ms still
	// leaves a third of a second to judge the tone by.
	const reads = 16
	buf := make([]byte, PeriodBytes)
	t0 := time.Now()
	var pcm []byte
	for i := 0; i < reads; i++ {
		n, err := mic.ReadFrames(buf)
		if err != nil {
			t.Fatalf("ReadFrames(%d): %v", i, err)
		}
		if n != FramesPerPeriod {
			t.Fatalf("ReadFrames(%d) = %d frames, want %d", i, n, FramesPerPeriod)
		}
		pcm = append(pcm, buf...)
	}
	elapsed := time.Since(t0)
	t.Logf("%d periods in %v (one period = %v)", reads, elapsed.Round(time.Millisecond), periodDur)
	// The generator primes one period; allow one more of slack for the ticker.
	if min := (reads - 2) * periodDur; elapsed < min {
		t.Errorf("%d reads took %v, faster than the %v a paced mic can deliver — the read is not blocking", reads, elapsed, min)
	}
	if len(pcm) != reads*PeriodBytes {
		t.Fatalf("captured %d bytes, want %d", len(pcm), reads*PeriodBytes)
	}

	// The captured audio survives the real codec: this is a fake microphone,
	// not a fake pipeline.
	dec := decodeAll(t, encodeAll(t, pcm))
	s := settled(dec)
	r, g := rms(s), goertzel(s, FakeToneHz)
	t.Logf("captured tone after encode/decode: RMS %.4f, %dHz power fraction %.4f", r, FakeToneHz, g)
	if r < 0.2 {
		t.Errorf("captured audio lost its level (RMS %.4f)", r)
	}
	if g < 0.99 {
		t.Errorf("captured tone purity %.4f after the round trip, want > 0.99", g)
	}
}

// TestFakePlaybackReturnsFrameCount: playback reports what it played and takes
// the audio's own time doing it.
func TestFakePlaybackReturnsFrameCount(t *testing.T) {
	const frames = 4 * FramesPerPeriod // 160ms, a whole number of periods
	pcm := Tone(440, frames)
	t0 := time.Now()
	n, err := PlayMessage(pcm, 8192)
	if err != nil {
		t.Fatalf("PlayMessage: %v", err)
	}
	if n != frames {
		t.Errorf("PlayMessage returned %d frames, want %d", n, frames)
	}
	if el := time.Since(t0); el < 120*time.Millisecond {
		t.Errorf("160ms of audio consumed in %v — playback is not blocking", el)
	}
	t.Logf("stats: %s", PlayStats())
}

// TestPlayMessagePadsToWholePeriod: a partial period is zero-padded, not
// truncated — the device backend's deliberate departure from the original
// client, which lost up to 40ms of tail audio.
func TestPlayMessagePadsToWholePeriod(t *testing.T) {
	frames := FramesPerPeriod + 7
	n, err := PlayMessage(Tone(440, frames), 0)
	if err != nil {
		t.Fatalf("PlayMessage: %v", err)
	}
	if n != 2*FramesPerPeriod {
		t.Errorf("PlayMessage returned %d frames, want %d (padded up from %d)", n, 2*FramesPerPeriod, frames)
	}
	if _, err := PlayMessage(nil, 0); err == nil {
		t.Error("PlayMessage(nil) should be an error")
	}
}

// TestReadFramesRejectsShortBuffer: the contract is a buffer of at least one
// period, and a smaller one is an error rather than a short read.
func TestReadFramesRejectsShortBuffer(t *testing.T) {
	mic, err := OpenCapture()
	if err != nil {
		t.Fatalf("OpenCapture: %v", err)
	}
	defer mic.Close()
	if _, err := mic.ReadFrames(make([]byte, PeriodBytes-1)); err == nil {
		t.Error("ReadFrames with a short buffer should fail")
	}
}

// TestCloseWakesABlockedReader: the audio thread closes the capture from its
// own goroutine when recording stops, and a reader parked on the condition
// variable must come back with an error instead of hanging.
func TestCloseWakesABlockedReader(t *testing.T) {
	c := newCapture() // no producer at all: the reader can only block
	done := make(chan error, 1)
	go func() {
		_, err := c.ReadFrames(make([]byte, PeriodBytes))
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	c.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Error("ReadFrames on a closed, empty capture should fail")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Close did not wake the blocked reader")
	}
}

// TestRingDropsOldestOnOverrun pins the documented overrun policy: a reader
// that falls behind resynchronizes with the newest audio rather than replaying
// a backlog. The ring holds captureRingPeriods; the producer here pushes one
// more than that and the reader must see the SECOND period, not the first.
func TestRingDropsOldestOnOverrun(t *testing.T) {
	c := newCapture()
	defer c.Close()
	mark := func(i int) []byte {
		b := make([]byte, PeriodBytes)
		for j := range b {
			b[j] = byte(i)
		}
		return b
	}
	for i := 1; i <= captureRingPeriods+1; i++ {
		c.push(mark(i))
	}
	buf := make([]byte, PeriodBytes)
	if _, err := c.ReadFrames(buf); err != nil {
		t.Fatalf("ReadFrames: %v", err)
	}
	if buf[0] != 2 {
		t.Errorf("after an overrun the reader saw period %d, want 2 (oldest dropped)", buf[0])
	}
	if c.drops != PeriodBytes {
		t.Errorf("drop accounting %d bytes, want %d", c.drops, PeriodBytes)
	}
}
