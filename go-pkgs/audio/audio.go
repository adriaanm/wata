// Package audio is the hand-written cgo facade target for the Sgola wata
// fbclient's audio path: Opus encode/decode + tinyalsa PCM playback/capture,
// tuned for the BQ268 (MSM8909, Q6 ADSP, hw:0,0, 48kHz mono S16_LE).
//
// It is an ORDINARY Go package (M8 decision 2): hand-written cgo today, a
// facade bind target later (M4 mechanism, zero new IOP). The real
// implementation is cgo over vendored opus 1.5.2 + tinyalsa 2.0.0 and builds
// only on linux/arm (build the static libs first with ./mklibs.sh). On every
// other platform — notably the darwin host that runs `sgo ci` — a pure-Go
// no-op stub takes its place so the host build never needs the device or the
// C toolchain.
//
// Constants mirror wata's opus.zig + alsa.zig surface exactly.
package audio

import (
	"fmt"
	"math"
	"time"
)

const (
	SampleRate   = 48000 // Hz — the only rate the Q6 DSP accepts
	Channels     = 1     // mono
	FrameSize    = 2     // bytes per sample (S16_LE)
	FrameSamples = 960   // Opus 20ms frame at 48kHz (the device ENCODE frame)
	MaxFrameByte = 4000  // Opus-recommended max encoded frame size

	// MaxDecodeSamples: the largest an Opus packet can decode to at 48kHz —
	// 120ms = 5760 samples/channel. The DECODE buffer is sized for this, NOT
	// FrameSamples: a foreign encoder (the TUI muxes 60ms@16kHz packets =
	// 2880 samples at 48kHz decode; xiph tools default to 20ms) produces
	// packets whose decoded length exceeds our own 20ms frame. Passing
	// frame_size=FrameSamples to opus_decode made it return OPUS_BUFFER_TOO_SMALL
	// (-2) on any packet longer than 20ms — the M8 chunk-6 "VOICEPLAY FAIL on
	// TUI-sent audio" root cause (2026-07-10). Sizing for the max frame lets the
	// decoder report the true sample count and keeps 20ms device packets exact.
	MaxDecodeSamples = 5760

	// FramesPerPeriod: 1920 frames = 40ms, a multiple of the 960 Opus frame.
	FramesPerPeriod = 1920
	PeriodBytes     = FramesPerPeriod * Channels * FrameSize // 3840

	// PlaybackPeriods: 8 × 40ms = 320ms ring cushion (alsa.zig PLAYBACK_PERIODS).
	PlaybackPeriods = 8

	// WriteChunkPeriods: max periods per pcm_writei. The Q6 rejects a large
	// one-shot write with ETIMEDOUT, so writes are chunked to 4 periods =
	// 160ms and each is allowed to drain (alsa.zig WRITE_CHUNK_PERIODS).
	WriteChunkPeriods = 4
)

// PCM stream states (tinyalsa PCM_STATE_*), returned by Playback.State.
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
// sine wave at SampleRate, ~half amplitude. Pure Go (no cgo), so it is present
// in BOTH the real cgo build and the host stub — the wata-fb skeleton (M8 chunk
// 2) feeds it to the Opus encoder to exercise the cross-cgo path without ever
// touching the speaker (non-audio-output verification).
func Tone(freqHz, samples int) []byte {
	buf := make([]byte, samples*FrameSize)
	for i := 0; i < samples; i++ {
		v := int16(16000 * math.Sin(2*math.Pi*float64(freqHz)*float64(i)/float64(SampleRate)))
		buf[i*2] = byte(v)
		buf[i*2+1] = byte(v >> 8)
	}
	return buf
}

// ---- M8 chunk 6: build-tag-shared wrappers over the per-build types --------
// (audio.go compiles in BOTH the linux/arm cgo build and the host stub build;
// methods on *Encoder/*Decoder and calls into Open*/WriteFrames resolve to
// whichever implementation the build tags picked, so these are written once.)

// EncodeFrameAt encodes the frameIdx-th 960-sample S16_LE frame of pcm and
// returns an EXACT-SIZED encoded packet (no caller-side slice arithmetic —
// the sub-slicing happens here, on the Go side of the facade boundary).
func (e *Encoder) EncodeFrameAt(pcm []byte, frameIdx int) ([]byte, error) {
	fb := FrameSamples * FrameSize * Channels
	off := frameIdx * fb
	if off < 0 || off+fb > len(pcm) {
		return nil, fmt.Errorf("EncodeFrameAt: frame %d out of range (%d bytes)", frameIdx, len(pcm))
	}
	out := make([]byte, MaxFrameByte)
	n, err := e.Encode(pcm[off:off+fb], out)
	if err != nil {
		return nil, err
	}
	return out[:n], nil
}

// DecodeFrame decodes one Opus packet and returns the EXACT-SIZED pcm for the
// packet's actual duration (n*FrameSize*Channels bytes). The scratch buffer is
// MaxDecodeSamples so a foreign encoder's longer frames decode without
// OPUS_BUFFER_TOO_SMALL (the TUI's 60ms@16kHz -> 2880 samples at 48kHz; see
// MaxDecodeSamples). A device-recorded 20ms packet still returns exactly 960.
func (d *Decoder) DecodeFrame(data []byte) ([]byte, error) {
	out := make([]byte, MaxDecodeSamples*FrameSize*Channels)
	n, err := d.Decode(data, out)
	if err != nil {
		return nil, err
	}
	return out[:n*FrameSize*Channels], nil
}

// lastPlayStats is diagnostics-only (read via PlayStats after a PlayMessage).
var lastPlayStats string

// PlayStats renders the previous PlayMessage's pacing numbers (writes, max
// write-call stall, max app-side gap, prime-time state) — the soak/selftest
// evidence line. Diagnostics only, no behavior rides on it.
func PlayStats() string { return lastPlayStats }

// PlayMessage plays a whole decoded S16_LE mono pcm buffer with THE verified
// playback discipline (M8 chunk 1 gate, audio_experiments.md 2026-07-10 —
// NOT alsa.zig's one-period start):
//  1. pre-open the volume ctl (mixer_open walks ~700 controls; never mid-stream),
//  2. start_threshold = ring, stop_threshold = ring,
//  3. prime the FULL ring in chunk-size writes (kernel auto-starts on the last),
//  4. explicit Start() only if the whole message is SHORTER than the ring
//     (threshold never reached — safe on the patched tinyalsa),
//  5. ASM volume post-start via the pre-opened ctl,
//  6. then 4-period chunked writes at pace; drain; close.
//
// pcm is zero-PADDED to a whole period (deliberate departure from Zig
// doPlayback, which TRUNCATES up to 40ms of tail audio; recorded M8 chunk 6).
// vol <= 0 skips the volume set. Returns the frames played — (int, error), so
// the sgola side can val-bind it (the ERR-1 throws lowering; a Unit-throwing
// statement would DROP the error per the M4 chunk 7 write precedent).
func PlayMessage(pcm []byte, vol int) (int, error) {
	const ringFrames = FramesPerPeriod * PlaybackPeriods
	const ringBytes = ringFrames * Channels * FrameSize
	const chunkBytes = WriteChunkPeriods * PeriodBytes

	// pad to a whole period
	if rem := len(pcm) % PeriodBytes; rem != 0 {
		pcm = append(pcm, make([]byte, PeriodBytes-rem)...)
	}
	if len(pcm) == 0 {
		return 0, fmt.Errorf("PlayMessage: empty pcm")
	}

	var vc *VolCtl
	if vol > 0 {
		if v, err := OpenVolCtl(); err == nil {
			vc = v
			defer vc.Close()
		}
	}

	pb, err := OpenPlaybackTuned(FramesPerPeriod, PlaybackPeriods, WriteChunkPeriods, ringFrames)
	if err != nil {
		return 0, err
	}
	defer pb.Close()

	// prime up to the full ring (WriteFrames chunks internally; the kernel
	// auto-starts when the threshold is met on the last prime write)
	prime := len(pcm)
	if prime > ringBytes {
		prime = ringBytes
	}
	t0 := time.Now()
	if err := pb.WriteFrames(pcm[:prime]); err != nil {
		lastPlayStats = fmt.Sprintf("prime-failed after %.0fms: %v", time.Since(t0).Seconds()*1000, err)
		return 0, err
	}
	primeState := StateName(pb.State())
	if pb.State() != StateRunning {
		// short message: the buffer never reaches start_threshold = ring
		if err := pb.Start(); err != nil {
			lastPlayStats = fmt.Sprintf("primeState=%s start-failed: %v", primeState, err)
			return 0, err
		}
	}
	if vc != nil {
		vc.Apply(vol)
	}

	// paced body: one 4-period chunk per WriteFrames call so per-call timing
	// IS per-chunk timing (the soak's stall metric).
	writes, maxWriteMs, maxGapMs := 0, 0.0, 0.0
	lastEnd := time.Now()
	for off := prime; off < len(pcm); {
		end := off + chunkBytes
		if end > len(pcm) {
			end = len(pcm)
		}
		gapMs := time.Since(lastEnd).Seconds() * 1000
		if gapMs > maxGapMs {
			maxGapMs = gapMs
		}
		w0 := time.Now()
		err := pb.WriteFrames(pcm[off:end])
		dt := time.Since(w0).Seconds() * 1000
		lastEnd = time.Now()
		if dt > maxWriteMs {
			maxWriteMs = dt
		}
		writes++
		if err != nil {
			lastPlayStats = fmt.Sprintf("primeState=%s writes=%d maxWrite=%.0fms maxGap=%.0fms FAILED: %v",
				primeState, writes, maxWriteMs, maxGapMs, err)
			return 0, err
		}
		off = end
	}
	pb.Drain()
	frames := len(pcm) / (FrameSize * Channels)
	lastPlayStats = fmt.Sprintf("primeState=%s frames=%d writes=%d maxWrite=%.0fms maxGap=%.0fms",
		primeState, frames, writes, maxWriteMs, maxGapMs)
	return frames, nil
}

// StateName renders a PCM_STATE_* value for logging.
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
