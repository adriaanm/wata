// Package macaudio is the macOS audio backend for the wata client: Opus
// encode/decode through AudioToolbox's built-in codec and capture/playback
// through AVAudioEngine, all over purego — NO cgo anywhere.
//
// Its exported surface mirrors github.com/adriaanm/wata/go-pkgs/audio (the
// device's cgo opus + tinyalsa backend) member for member, because the two are
// bound by declaration-identical Sgola facades and driven by ONE audio thread
// (wata-fb/src/main/scala/audiothread.scala, symlinked into wata-mac). Anything
// that reads oddly here — PCM stream "states", an ASM volume scale, a
// tinyalsa-shaped period size — reads that way because the device defined the
// shape and the mac follows it.
//
// The constants describe the WIRE, not the hardware: whatever CoreAudio hands
// the capture tap is downmixed and resampled to 48kHz mono S16_LE before it
// reaches a caller.
//
// AudioToolbox is ObjC-free C and AVFAudio exists on iOS, so nothing here is
// macOS-specific by construction — but nothing has executed it on iOS, and iOS
// additionally needs an AVAudioSession activation this package does not do.
package macaudio

import (
	"fmt"
	"math"
	"sync"
)

const (
	SampleRate   = 48000 // Hz — the device's only rate, and opus's native one
	Channels     = 1     // mono
	FrameSize    = 2     // bytes per sample (S16_LE)
	FrameSamples = 960   // Opus 20ms frame at 48kHz (the ENCODE frame)
	MaxFrameByte = 4000  // Opus-recommended max encoded frame size

	// MaxDecodeSamples: the largest an Opus packet can decode to at 48kHz —
	// 120ms = 5760 samples. The DECODE buffer is sized for this, NOT
	// FrameSamples: a foreign encoder (the TUI muxes 60ms packets = 2880
	// samples at 48kHz decode) produces packets longer than our own 20ms frame,
	// and the device backend's history records what sizing for FrameSamples
	// cost — every TUI-sent message failed to play.
	MaxDecodeSamples = 5760

	// FramesPerPeriod: 1920 frames = 40ms, a multiple of the 960 Opus frame,
	// so one period is exactly the two subframes recordLoop encodes.
	FramesPerPeriod = 1920
	PeriodBytes     = FramesPerPeriod * Channels * FrameSize // 3840

	// PlaybackPeriods: 8 × 40ms of cushion. On the device this sizes the ALSA
	// ring; here it sizes the fake backend's pacing and keeps the constant true
	// to its facade documentation.
	PlaybackPeriods = 8
)

// PCM stream states. The device returns these from tinyalsa; on the mac only
// StateRunning and StateDisconnected are ever produced, but the whole table is
// here so StateName renders a device log line identically.
const (
	StateOpen         = 0x00
	StateSetup        = 0x01
	StatePrepared     = 0x02
	StateRunning      = 0x03
	StateXrun         = 0x04
	StateDraining     = 0x05
	StateSuspended    = 0x07
	StateDisconnected = 0x08
)

// Tone fills a fresh S16_LE mono buffer with `samples` samples of a `freqHz`
// sine wave at SampleRate, ~half amplitude. Portable Go, byte-identical to the
// device backend's Tone — the fake capture backend synthesizes from it, and the
// tests encode it.
func Tone(freqHz, samples int) []byte {
	buf := make([]byte, samples*FrameSize)
	for i := 0; i < samples; i++ {
		v := int16(16000 * math.Sin(2*math.Pi*float64(freqHz)*float64(i)/float64(SampleRate)))
		buf[i*2] = byte(v)
		buf[i*2+1] = byte(v >> 8)
	}
	return buf
}

// EncodeFrameAt encodes the frameIdx-th 960-sample S16_LE frame of pcm and
// returns an EXACT-SIZED encoded packet (no caller-side slice arithmetic — the
// sub-slicing happens here, on the Go side of the facade boundary). The audio
// thread calls it twice per captured period, for frames 0 and 1.
func (e *Encoder) EncodeFrameAt(pcm []byte, frameIdx int) ([]byte, error) {
	fb := FrameSamples * FrameSize * Channels
	off := frameIdx * fb
	if off < 0 || off+fb > len(pcm) {
		return nil, fmt.Errorf("macaudio: EncodeFrameAt: frame %d out of range (%d bytes)", frameIdx, len(pcm))
	}
	out := make([]byte, MaxFrameByte)
	n, err := e.Encode(pcm[off:off+fb], out)
	if err != nil {
		return nil, err
	}
	return out[:n], nil
}

// DecodeFrame decodes one Opus packet and returns the EXACT-SIZED pcm for the
// packet's actual duration. The scratch buffer is MaxDecodeSamples so a foreign
// encoder's longer frames decode intact; a 20ms packet still returns exactly
// 960 samples' worth.
func (d *Decoder) DecodeFrame(data []byte) ([]byte, error) {
	out := make([]byte, MaxDecodeSamples*FrameSize*Channels)
	n, err := d.Decode(data, out)
	if err != nil {
		return nil, err
	}
	return out[:n*FrameSize*Channels], nil
}

// StateName renders a PCM state value for logging.
func StateName(s int) string {
	switch s {
	case StateOpen:
		return "OPEN"
	case StateSetup:
		return "SETUP"
	case StatePrepared:
		return "PREPARED"
	case StateRunning:
		return "RUNNING"
	case StateXrun:
		return "XRUN"
	case StateDraining:
		return "DRAINING"
	case StateSuspended:
		return "SUSPENDED"
	case StateDisconnected:
		return "DISCONNECTED"
	default:
		return "UNKNOWN"
	}
}

// lastPlayStats is diagnostics-only (read via PlayStats after a PlayMessage).
var (
	statsMu       sync.Mutex
	lastPlayStats string
)

func setPlayStats(s string) {
	statsMu.Lock()
	lastPlayStats = s
	statsMu.Unlock()
}

// PlayStats renders the previous PlayMessage's pacing numbers. Diagnostics
// only, no behavior rides on it.
func PlayStats() string {
	statsMu.Lock()
	defer statsMu.Unlock()
	return lastPlayStats
}
