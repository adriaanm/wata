//go:build linux && arm

package audio

/*
#cgo CFLAGS: -I${SRCDIR}/vendor/opus/include -I${SRCDIR}/vendor/tinyalsa/include
#cgo LDFLAGS: -L${SRCDIR}/clib/arm -lopus -ltinyalsa -lm

#include <stdlib.h>
#include <sys/ioctl.h>
#include <opus.h>
#include <tinyalsa/pcm.h>
#include <tinyalsa/mixer.h>

// pcm_state() is exported by tinyalsa but not declared in its public header.
int pcm_state(struct pcm *pcm);

// SNDRV_PCM_IOCTL_DRAIN = _IO('A', 0x44) = 0x4144. ioctl is variadic, so wrap
// it for cgo (which cannot call variadic C directly).
static int drain_pcm(int fd) { return ioctl(fd, 0x4144, 0); }

// opus_encoder_ctl is variadic — wrap the two settings we use so cgo can call
// them (cgo cannot call C variadics directly).
static int enc_set_bitrate(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_BITRATE_REQUEST, v); }
static int enc_set_dtx(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_DTX_REQUEST, v); }
*/
import "C"

import (
	"errors"
	"fmt"
	"unsafe"
)

// ---- Opus ----------------------------------------------------------------

// Encoder wraps an OpusEncoder configured for 48kHz mono VoIP, 16kbps, DTX on
// (matches opus.zig Encoder.init).
type Encoder struct{ enc *C.OpusEncoder }

func NewEncoder() (*Encoder, error) {
	var cerr C.int
	enc := C.opus_encoder_create(C.opus_int32(SampleRate), C.int(Channels), C.OPUS_APPLICATION_VOIP, &cerr)
	if enc == nil || cerr != C.OPUS_OK {
		return nil, fmt.Errorf("opus_encoder_create failed: %d", int(cerr))
	}
	C.enc_set_bitrate(enc, C.opus_int32(16000))
	C.enc_set_dtx(enc, C.opus_int32(1))
	return &Encoder{enc: enc}, nil
}

// Encode one 960-sample S16_LE frame (pcm, PeriodBytes-agnostic: exactly
// FrameSamples*FrameSize bytes) into out; returns the encoded byte count.
func (e *Encoder) Encode(pcm []byte, out []byte) (int, error) {
	if len(pcm) < FrameSamples*FrameSize {
		return 0, errors.New("opus encode: short pcm frame")
	}
	n := C.opus_encode(e.enc,
		(*C.opus_int16)(unsafe.Pointer(&pcm[0])), C.int(FrameSamples),
		(*C.uchar)(unsafe.Pointer(&out[0])), C.opus_int32(len(out)))
	if n < 0 {
		return 0, fmt.Errorf("opus_encode failed: %d", int(n))
	}
	return int(n), nil
}

func (e *Encoder) Close() {
	if e.enc != nil {
		C.opus_encoder_destroy(e.enc)
		e.enc = nil
	}
}

// Decoder wraps an OpusDecoder (48kHz mono).
type Decoder struct{ dec *C.OpusDecoder }

func NewDecoder() (*Decoder, error) {
	var cerr C.int
	dec := C.opus_decoder_create(C.opus_int32(SampleRate), C.int(Channels), &cerr)
	if dec == nil || cerr != C.OPUS_OK {
		return nil, fmt.Errorf("opus_decoder_create failed: %d", int(cerr))
	}
	return &Decoder{dec: dec}, nil
}

// Decode an Opus packet into pcmOut; returns the decoded sample count. The
// frame_size argument to opus_decode is the OUTPUT CAPACITY per channel, not a
// fixed frame length — it must be >= the packet's decoded duration or the call
// returns OPUS_BUFFER_TOO_SMALL. We pass the actual buffer capacity (callers
// size it MaxDecodeSamples via DecodeFrame) so packets of any legal Opus
// duration decode; a 20ms device packet still yields exactly FrameSamples.
func (d *Decoder) Decode(data []byte, pcmOut []byte) (int, error) {
	var dp *C.uchar
	if len(data) > 0 {
		dp = (*C.uchar)(unsafe.Pointer(&data[0]))
	}
	capSamples := len(pcmOut) / (FrameSize * Channels)
	n := C.opus_decode(d.dec, dp, C.opus_int32(len(data)),
		(*C.opus_int16)(unsafe.Pointer(&pcmOut[0])), C.int(capSamples), 0)
	if n < 0 {
		return 0, fmt.Errorf("opus_decode failed: %d", int(n))
	}
	return int(n), nil
}

func (d *Decoder) Close() {
	if d.dec != nil {
		C.opus_decoder_destroy(d.dec)
		d.dec = nil
	}
}

// ---- tinyalsa PCM --------------------------------------------------------

func pcmErr(pcm *C.struct_pcm) string {
	if pcm == nil {
		return ""
	}
	if s := C.pcm_get_error(pcm); s != nil {
		return C.GoString(s)
	}
	return ""
}

func baseConfig() C.struct_pcm_config {
	var cfg C.struct_pcm_config
	cfg.channels = C.uint(Channels)
	cfg.rate = C.uint(SampleRate)
	cfg.period_size = C.uint(FramesPerPeriod)
	cfg.period_count = 2
	cfg.format = C.PCM_FORMAT_S16_LE
	return cfg
}

// Playback is a PCM_OUT stream on hw:0,0. The default recipe is alsa.zig's
// (period 1920, 8-period ring, stop_threshold = ring size, 4-period chunked
// writes); OpenPlaybackTuned exposes the geometry so the pcm-recipe pacing can
// be validated against aplay's own (period 6000 / buffer 24000) config.
type Playback struct {
	pcm       *C.struct_pcm
	chunkByte int // pcm_writei chunk size in bytes
}

// OpenPlayback opens playback with the default alsa.zig recipe. startThreshold
// is frames-before-autostart (FramesPerPeriod = the alsa.zig assumption; a
// large value defers autostart for the explicit-Start hypothesis arm).
func OpenPlayback(startThreshold uint) (*Playback, error) {
	return OpenPlaybackTuned(FramesPerPeriod, PlaybackPeriods, WriteChunkPeriods, startThreshold)
}

// OpenPlaybackTuned opens playback with an explicit ring geometry. chunkPeriods
// is the pcm_writei chunk size (periods); startThreshold is in frames.
func OpenPlaybackTuned(periodSize, periodCount, chunkPeriods, startThreshold uint) (*Playback, error) {
	cfg := baseConfig()
	cfg.period_size = C.uint(periodSize)
	cfg.period_count = C.uint(periodCount)
	cfg.start_threshold = C.uint(startThreshold)
	cfg.stop_threshold = C.uint(periodSize * periodCount)
	pcm := C.pcm_open(0, 0, C.PCM_OUT, &cfg)
	if pcm == nil || C.pcm_is_ready(pcm) == 0 {
		msg := pcmErr(pcm)
		if pcm != nil {
			C.pcm_close(pcm)
		}
		return nil, fmt.Errorf("pcm_open(PCM_OUT) failed: %s", msg)
	}
	return &Playback{pcm: pcm, chunkByte: int(chunkPeriods * periodSize * Channels * FrameSize)}, nil
}

// WriteFrames writes whole-period PCM, chunked so the Q6 paces (alsa.zig
// writeFrames).
func (p *Playback) WriteFrames(buf []byte) error {
	chunk := p.chunkByte
	if chunk <= 0 {
		chunk = WriteChunkPeriods * PeriodBytes
	}
	for off := 0; off < len(buf); {
		end := off + chunk
		if end > len(buf) {
			end = len(buf)
		}
		slice := buf[off:end]
		frames := C.uint(len(slice) / (FrameSize * Channels))
		n := C.pcm_writei(p.pcm, unsafe.Pointer(&slice[0]), frames)
		if n < 0 {
			return fmt.Errorf("pcm_writei failed: %s", pcmErr(p.pcm))
		}
		if C.uint(n) != frames {
			// Loud on short writes — chunk-1 forensics: pre-start writes can be
			// silently swallowed on this driver (appl_ptr unchanged).
			return fmt.Errorf("pcm_writei short write: %d of %d frames", int(n), int(frames))
		}
		off = end
	}
	return nil
}

// Start forces the stream to RUNNING (pcm_start) — the explicit-start arm of
// the pcm_start hypothesis.
func (p *Playback) Start() error {
	if C.pcm_start(p.pcm) < 0 {
		return fmt.Errorf("pcm_start failed: %s", pcmErr(p.pcm))
	}
	return nil
}

// State returns the current PCM_STATE_* (see StateName).
func (p *Playback) State() int { return int(C.pcm_state(p.pcm)) }

// Drain blocks until the hardware has played all buffered samples
// (SNDRV_PCM_IOCTL_DRAIN; tinyalsa v2 has no pcm_drain).
func (p *Playback) Drain() {
	C.drain_pcm(C.pcm_get_file_descriptor(p.pcm))
}

func (p *Playback) Close() {
	if p.pcm != nil {
		C.pcm_close(p.pcm)
		p.pcm = nil
	}
}

// Capture is a PCM_IN stream on hw:0,0 (alsa.zig Capture): small buffer, low
// latency.
type Capture struct{ pcm *C.struct_pcm }

func OpenCapture() (*Capture, error) {
	cfg := baseConfig()
	pcm := C.pcm_open(0, 0, C.PCM_IN, &cfg)
	if pcm == nil || C.pcm_is_ready(pcm) == 0 {
		msg := pcmErr(pcm)
		if pcm != nil {
			C.pcm_close(pcm)
		}
		return nil, fmt.Errorf("pcm_open(PCM_IN) failed: %s", msg)
	}
	return &Capture{pcm: pcm}, nil
}

// ReadFrames reads one period (FramesPerPeriod) into buf; returns frames read.
func (c *Capture) ReadFrames(buf []byte) (int, error) {
	n := C.pcm_readi(c.pcm, unsafe.Pointer(&buf[0]), C.uint(FramesPerPeriod))
	if n < 0 {
		return 0, fmt.Errorf("pcm_readi failed: %s", pcmErr(c.pcm))
	}
	return int(n), nil
}

func (c *Capture) Close() {
	if c.pcm != nil {
		C.pcm_close(c.pcm)
		c.pcm = nil
	}
}

// SetupMixer applies the one-time playback+capture route (alsa.zig setupMixer):
// PRI_MI2S_RX ← MultiMedia1, RX2/HPHR/Ext-Spk on, mic decimator on. Called once
// after boot; no per-mode switching (avoids ADSP churn).
func SetupMixer() {
	mixer := C.mixer_open(0)
	if mixer == nil {
		return
	}
	defer C.mixer_close(mixer)
	setInt(mixer, "PRI_MI2S_RX Audio Mixer MultiMedia1", 1)
	setEnum(mixer, "RX2 MIX1 INP1", "RX1")
	setEnum(mixer, "RDAC2 MUX", "RX2")
	setEnum(mixer, "HPHR", "Switch")
	setEnum(mixer, "Ext Spk Switch", "On")
	setInt(mixer, "RX2 Digital Volume", 96)
	setInt(mixer, "MultiMedia1 Mixer TERT_MI2S_TX", 1)
	setEnum(mixer, "DEC1 MUX", "ADC1")
	setInt(mixer, "ADC1 Volume", 6)
	setInt(mixer, "DEC1 Volume", 104)
}

// SetPlaybackVolume sets the Q6 ASM soft "Playback 0 Volume" (0..8192-ish);
// only takes effect on an OPEN, RUNNING substream (audio_experiments.md:
// applies after the first period is written). Returns the mixer set rc.
//
// NB mixer_open enumerates every control (~700 on this codec) and can take
// longer than the ring cushion — calling this mid-stream right after start
// caused a start-of-stream xrun (M8 chunk 1). Use VolCtl for mid-stream sets.
func SetPlaybackVolume(v int) int {
	vc, err := OpenVolCtl()
	if err != nil {
		return -1
	}
	defer vc.Close()
	return vc.Apply(v)
}

// VolCtl is a pre-opened handle on "Playback 0 Volume" so the expensive
// mixer_open happens BEFORE the stream starts and the mid-stream apply is one
// cheap ioctl (the ring cushion is only ~320ms).
type VolCtl struct {
	mixer *C.struct_mixer
	ctl   *C.struct_mixer_ctl
}

func OpenVolCtl() (*VolCtl, error) {
	mixer := C.mixer_open(0)
	if mixer == nil {
		return nil, errors.New("mixer_open(0) failed")
	}
	name := C.CString("Playback 0 Volume")
	defer C.free(unsafe.Pointer(name))
	ctl := C.mixer_get_ctl_by_name(mixer, name)
	if ctl == nil {
		C.mixer_close(mixer)
		return nil, errors.New("control 'Playback 0 Volume' not found")
	}
	return &VolCtl{mixer: mixer, ctl: ctl}, nil
}

// Apply sets the volume (call after the stream is RUNNING); returns the rc.
func (v *VolCtl) Apply(val int) int {
	return int(C.mixer_ctl_set_value(v.ctl, 0, C.int(val)))
}

func (v *VolCtl) Close() {
	if v.mixer != nil {
		C.mixer_close(v.mixer)
		v.mixer = nil
		v.ctl = nil
	}
}

func setInt(mixer *C.struct_mixer, name string, v int) {
	cn := C.CString(name)
	defer C.free(unsafe.Pointer(cn))
	ctl := C.mixer_get_ctl_by_name(mixer, cn)
	if ctl == nil {
		return
	}
	C.mixer_ctl_set_value(ctl, 0, C.int(v))
}

func setEnum(mixer *C.struct_mixer, name, value string) {
	cn := C.CString(name)
	defer C.free(unsafe.Pointer(cn))
	ctl := C.mixer_get_ctl_by_name(mixer, cn)
	if ctl == nil {
		return
	}
	cv := C.CString(value)
	defer C.free(unsafe.Pointer(cv))
	C.mixer_ctl_set_enum_by_string(ctl, cv)
}
