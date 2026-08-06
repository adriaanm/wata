// The capture path: whatever CoreAudio gives the input tap becomes 48kHz mono
// S16_LE in a ring buffer, and ReadFrames hands out exactly one period at a
// time.
//
// The ring and ReadFrames are SHARED by the real and the fake backend — only
// the producer differs (an AVAudioEngine input tap, or a tone generator paced
// by a ticker). That is what "the fake replaces only the microphone" means
// mechanically: the blocking discipline the audio thread depends on is the same
// code in both.
package macaudio

import (
	"errors"
	"fmt"
	"log"
	"sync"
	"unsafe"

	av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"
)

// captureRingPeriods: 1.28s of cushion between the tap and the encoder. The
// encoder needs ~1ms per 40ms period, so this is slack for a scheduling
// hiccup, not a design margin.
const captureRingPeriods = 32

// Capture is an open microphone. ReadFrames BLOCKS until a whole period is
// available, which is the contract the audio thread's record loop is written
// against (it reads one period, then encodes both of its 960-sample halves).
//
// OVERRUN POLICY: drop OLDEST. When the ring is full the producer advances the
// read cursor over the stale audio rather than discarding the new samples. A
// live microphone's value is its freshness — a reader that fell behind wants to
// resynchronize with now, not to replay a backlog and stay behind forever. The
// dropped byte count is logged at Close so an overrun is never silent.
type Capture struct {
	mu     sync.Mutex
	cond   *sync.Cond
	ring   []byte
	r, n   int // read cursor, bytes available
	drops  int
	closed bool

	// real backend
	node   av.AVAudioNode
	tapped bool
	rs     *resampler // nil when the hardware already gives 48kHz

	// fake backend
	stop chan struct{}
	wg   sync.WaitGroup
}

func newCapture() *Capture {
	c := &Capture{ring: make([]byte, captureRingPeriods*PeriodBytes)}
	c.cond = sync.NewCond(&c.mu)
	return c
}

// push appends S16_LE bytes, dropping the oldest audio if the ring is full.
func (c *Capture) push(b []byte) {
	if len(b) == 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(b) > len(c.ring) { // absurdly large buffer: keep the newest ringful
		c.drops += len(b) - len(c.ring)
		b = b[len(b)-len(c.ring):]
	}
	if over := c.n + len(b) - len(c.ring); over > 0 {
		c.r = (c.r + over) % len(c.ring)
		c.n -= over
		c.drops += over
	}
	w := (c.r + c.n) % len(c.ring)
	for len(b) > 0 {
		m := copy(c.ring[w:], b)
		b = b[m:]
		w = (w + m) % len(c.ring)
		c.n += m
	}
	c.cond.Broadcast()
}

// ReadFrames blocks until one whole period (PeriodBytes) has been captured,
// copies it into buf and returns the frame count (always FramesPerPeriod).
// It waits on a condition variable — no polling, no busy loop.
func (c *Capture) ReadFrames(buf []byte) (int, error) {
	if len(buf) < PeriodBytes {
		return 0, fmt.Errorf("macaudio: ReadFrames wants a buffer of at least %d bytes, got %d", PeriodBytes, len(buf))
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for c.n < PeriodBytes && !c.closed {
		c.cond.Wait()
	}
	if c.n < PeriodBytes {
		return 0, errors.New("macaudio: ReadFrames on a closed capture")
	}
	m := copy(buf[:PeriodBytes], c.ring[c.r:])
	if m < PeriodBytes {
		copy(buf[m:PeriodBytes], c.ring)
	}
	c.r = (c.r + PeriodBytes) % len(c.ring)
	c.n -= PeriodBytes
	return FramesPerPeriod, nil
}

// Close stops the producer and wakes any blocked reader. Idempotent.
func (c *Capture) Close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	drops := c.drops
	c.cond.Broadcast()
	c.mu.Unlock()

	if c.stop != nil {
		close(c.stop)
		c.wg.Wait()
	}
	if c.tapped {
		inPool(func() { c.node.RemoveTapOnBus(0) })
		c.tapped = false
	}
	if c.rs != nil {
		c.rs.close()
		c.rs = nil
	}
	if drops > 0 {
		log.Printf("macaudio: capture dropped %d bytes (%d ms) — the reader fell behind the mic",
			drops, drops/(FrameSize*Channels)*1000/SampleRate)
	}
}

// OpenCapture starts recording from the system default input. Under the fake
// backend it starts the tone generator instead; everything downstream — the
// ring, the blocking read, the period size — is identical.
func OpenCapture() (*Capture, error) {
	if !setupDone {
		return nil, errors.New("macaudio: SetupMixer has not run (the audio thread calls it once at start)")
	}
	if fakeMode {
		return openFakeCapture(), nil
	}
	e, err := audioEngine()
	if err != nil {
		return nil, err
	}
	c := newCapture()
	var openErr error
	inPool(func() {
		input := e.eng.InputNode()
		node := av.AVAudioNode{ID: input.ID}
		hw := node.OutputFormatForBus(0)
		rate := hw.SampleRate()
		chans := int(hw.ChannelCount())
		if rate == 0 || chans == 0 {
			openErr = errors.New("macaudio: input node reports no format (no input device?)")
			return
		}
		if rate != SampleRate {
			rs, err := newResampler(rate)
			if err != nil {
				openErr = err
				return
			}
			c.rs = rs
		}
		log.Printf("macaudio: capture input %g Hz, %d ch (resample: %v)", rate, chans, c.rs != nil)

		// The tap runs on a CoreAudio thread with no pool of its own; give it
		// one. It downmixes to mono, converts to S16_LE and pushes — bounded
		// work, no allocation the GC has to chase per sample.
		node.InstallTapOnBusBufferSizeFormatBlock(0, FramesPerPeriod, hw, func(b av.AVAudioPCMBuffer, _ av.AVAudioTime) {
			inPool(func() {
				n := int(b.FrameLength())
				// Stride is 1 for the standard deinterleaved format and the
				// channel count for an interleaved one; the buffer knows which
				// it got, so ask it rather than assuming.
				stride := int(b.Stride())
				data := channelData(b, n, chans, stride)
				if len(data) == 0 {
					return
				}
				mono := downmix(data, n, chans, stride)
				if c.rs != nil {
					pcm, err := c.rs.convert(mono)
					if err != nil {
						log.Printf("macaudio: capture resample: %v", err)
						return
					}
					c.push(pcm)
					return
				}
				c.push(toS16(mono))
			})
		})
		c.node = node
		c.tapped = true

		// A tap installed on the input node of an ALREADY RUNNING engine never
		// fires: the IO unit was configured without an input when SetupMixer
		// started it, and installing the tap is a graph configuration change
		// the engine only picks up on the next start. Restarting is what makes
		// the mic real — measured, not theoretical (without it ReadFrames
		// blocks forever). Recording and playback never overlap in the audio
		// thread, so no scheduled buffer is lost here.
		e.eng.Stop()
		if ok, startErr := e.eng.StartAndReturnError(); !ok {
			node.RemoveTapOnBus(0)
			c.tapped = false
			openErr = fmt.Errorf("macaudio: engine restart for capture: %w", startErr)
		}
	})
	if openErr != nil {
		return nil, openErr
	}
	return c, nil
}

// downmix averages the channels the hardware gave us into one mono buffer.
func downmix(data [][]float32, frames, chans, stride int) []float32 {
	out := make([]float32, frames)
	if stride > 1 { // interleaved: one buffer, stride samples per frame
		src := data[0]
		for i := 0; i < frames; i++ {
			var acc float32
			for ch := 0; ch < chans; ch++ {
				acc += src[i*stride+ch]
			}
			out[i] = acc / float32(chans)
		}
		return out
	}
	if len(data) == 1 {
		copy(out, data[0][:frames])
		return out
	}
	for i := 0; i < frames; i++ {
		var acc float32
		for ch := range data {
			acc += data[ch][i]
		}
		out[i] = acc / float32(len(data))
	}
	return out
}

// toS16 clamps and converts mono float32 to S16_LE bytes.
func toS16(in []float32) []byte {
	out := make([]byte, len(in)*FrameSize)
	for i, f := range in {
		v := f * 32767
		if v > 32767 {
			v = 32767
		}
		if v < -32768 {
			v = -32768
		}
		s := int16(v)
		out[2*i] = byte(s)
		out[2*i+1] = byte(uint16(s) >> 8)
	}
	return out
}

// ── the sample-rate converter ───────────────────────────────────────────────

// resampler is an AudioConverter from mono float32 at the hardware rate to the
// wire's 48kHz mono S16_LE. It exists only when the input device is not already
// at 48kHz (a USB mic at 44.1kHz, say); it keeps its filter state across tap
// buffers, so one Capture owns exactly one.
//
// UNPROVEN: no non-48kHz input device has been available to run this path. The
// built-in mic and the fake backend are both 48kHz, so every test and every
// session so far has taken the branch above it. iOS is where it will first
// matter — the input hardware rate is not guaranteed there.
type resampler struct {
	conv   uintptr
	src    uintptr
	inRate float64
}

func newResampler(inRate float64) (*resampler, error) {
	if err := loadAudioToolbox(); err != nil {
		return nil, err
	}
	in := floatASBD(inRate)
	out := pcm16ASBD()
	var conv uintptr
	if st := audioConverterNew(&in, &out, &conv); st != 0 {
		return nil, fmt.Errorf("macaudio: AudioConverterNew(%gHz float -> 48kHz s16): %s", inRate, osstatus(st))
	}
	return &resampler{conv: conv, src: registerSource(), inRate: inRate}, nil
}

func (r *resampler) convert(in []float32) ([]byte, error) {
	if len(in) == 0 {
		return nil, nil
	}
	s := lookupSource(r.src)
	s.pcm = unsafe.Slice((*byte)(unsafe.Pointer(&in[0])), len(in)*4)
	s.pcmPos, s.bytesPerFrame, s.packet, s.served = 0, 4, nil, false

	// The converter's own filter delay means the frame count out is not
	// exactly rate-scaled; oversize the destination and trust the returned
	// count.
	want := int(float64(len(in))*SampleRate/r.inRate) + 64
	out := make([]byte, want*FrameSize*Channels)
	list := audioBufferList{NumberBuffers: 1}
	list.Buffers[0] = audioBuffer{NumberChannels: Channels, DataByteSize: uint32(len(out)), Data: unsafe.Pointer(&out[0])}
	n := uint32(want)
	st := audioConverterFillComplexBuffer(r.conv, inputProc, r.src, &n, &list, nil)
	if st != 0 && st != statusNoMoreData {
		return nil, fmt.Errorf("macaudio: FillComplexBuffer(resample): %s", osstatus(st))
	}
	return out[:int(n)*FrameSize*Channels], nil
}

func (r *resampler) close() {
	audioConverterDispose(r.conv)
	releaseSource(r.src)
}
