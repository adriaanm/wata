// AudioToolbox's C AudioConverter API over purego — no cgo, no ObjC. This is
// the codec half of the package: the Opus encoder and decoder wata's wire
// shape needs.
//
// Why the C API and not AVAudioConverter:
// -[AVAudioConverter convertToBuffer:error:withInputFromBlock:] takes a block
// with a NON-void return (it returns AVAudioBuffer *), a shape the bindgen
// mapper refuses because the purego block trampoline cannot carry it.
// AudioConverterFillComplexBuffer's input proc is a plain C function pointer
// whose arguments are all pointers — exactly what purego.NewCallback handles —
// and the structs it needs (ASBD, AudioBufferList, packet descriptions) lay out
// identically in Go on arm64. Same codec underneath: both sit on the
// AudioCodec component. AudioToolbox is ObjC-free C, so this file is iOS-clean.
package macaudio

import (
	"errors"
	"fmt"
	"sync"
	"unsafe"

	"github.com/ebitengine/purego"
)

// AudioStreamBasicDescription — 40 bytes, same layout as C on arm64.
type asbd struct {
	SampleRate       float64
	FormatID         uint32
	FormatFlags      uint32
	BytesPerPacket   uint32
	FramesPerPacket  uint32
	BytesPerFrame    uint32
	ChannelsPerFrame uint32
	BitsPerChannel   uint32
	Reserved         uint32
}

// AudioBuffer / AudioBufferList — Go inserts the same 4-byte pad before the
// pointer that C does, so the layouts match (24 bytes for a 1-buffer list).
type audioBuffer struct {
	NumberChannels uint32
	DataByteSize   uint32
	Data           unsafe.Pointer
}

type audioBufferList struct {
	NumberBuffers uint32
	Buffers       [1]audioBuffer
}

// AudioStreamPacketDescription — 16 bytes.
type aspd struct {
	StartOffset            int64
	VariableFramesInPacket uint32
	DataByteSize           uint32
}

func fourcc(s string) uint32 {
	return uint32(s[0])<<24 | uint32(s[1])<<16 | uint32(s[2])<<8 | uint32(s[3])
}

var (
	kAudioFormatLinearPCM = fourcc("lpcm")
	kAudioFormatOpus      = fourcc("opus")

	kLinearPCMFormatFlagIsFloat         uint32 = 1 << 0
	kLinearPCMFormatFlagIsSignedInteger uint32 = 1 << 2
	kLinearPCMFormatFlagIsPacked        uint32 = 1 << 3

	kAudioFormatProperty_FormatInfo = fourcc("fmti")

	kAudioConverterEncodeBitRate                   = fourcc("brat")
	kAudioConverterPropertyMaximumOutputPacketSize = fourcc("xops")

	// Returned by our input proc when the caller's data for THIS call is
	// exhausted but the stream is not over. Returning 0 packets instead would
	// tell the converter end-of-stream and flush it, which for a per-packet
	// streaming encoder means the next call gets a dead converter.
	statusNoMoreData = int32(fourcc("nomd"))
)

var (
	audioConverterNew               func(inFormat, outFormat *asbd, out *uintptr) int32
	audioConverterDispose           func(conv uintptr) int32
	audioConverterSetProperty       func(conv uintptr, prop uint32, size uint32, data unsafe.Pointer) int32
	audioConverterGetProperty       func(conv uintptr, prop uint32, size *uint32, data unsafe.Pointer) int32
	audioConverterFillComplexBuffer func(conv uintptr, proc uintptr, userData uintptr,
		ioOutputDataPacketSize *uint32, outOutputData *audioBufferList, outPacketDescription *aspd) int32
	audioFormatGetProperty func(prop uint32, specSize uint32, spec unsafe.Pointer, ioSize *uint32, out unsafe.Pointer) int32
)

var (
	atbxOnce sync.Once
	atbxErr  error
)

// loadAudioToolbox dlopens AudioToolbox once. Every entry point that reaches C
// calls it first, so a package used purely for its constants (or under the fake
// backend) never touches the framework.
func loadAudioToolbox() error {
	atbxOnce.Do(func() {
		lib, err := purego.Dlopen(
			"/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox",
			purego.RTLD_LAZY|purego.RTLD_GLOBAL)
		if err != nil {
			atbxErr = fmt.Errorf("macaudio: dlopen AudioToolbox: %w", err)
			return
		}
		purego.RegisterLibFunc(&audioConverterNew, lib, "AudioConverterNew")
		purego.RegisterLibFunc(&audioConverterDispose, lib, "AudioConverterDispose")
		purego.RegisterLibFunc(&audioConverterSetProperty, lib, "AudioConverterSetProperty")
		purego.RegisterLibFunc(&audioConverterGetProperty, lib, "AudioConverterGetProperty")
		purego.RegisterLibFunc(&audioConverterFillComplexBuffer, lib, "AudioConverterFillComplexBuffer")
		purego.RegisterLibFunc(&audioFormatGetProperty, lib, "AudioFormatGetProperty")
	})
	return atbxErr
}

func osstatus(s int32) string {
	b := []byte{byte(s >> 24), byte(s >> 16), byte(s >> 8), byte(s)}
	printable := true
	for _, c := range b {
		if c < 0x20 || c > 0x7e {
			printable = false
		}
	}
	if printable {
		return fmt.Sprintf("%d ('%s')", s, b)
	}
	return fmt.Sprintf("%d", s)
}

// pcm16ASBD — 48kHz mono signed-16 packed: wata's wire shape (SampleRate,
// Channels, FrameSize).
func pcm16ASBD() asbd {
	return asbd{
		SampleRate:       SampleRate,
		FormatID:         kAudioFormatLinearPCM,
		FormatFlags:      kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
		BytesPerPacket:   FrameSize * Channels,
		FramesPerPacket:  1,
		BytesPerFrame:    FrameSize * Channels,
		ChannelsPerFrame: Channels,
		BitsPerChannel:   FrameSize * 8,
	}
}

// floatASBD — deinterleaved-irrelevant mono float32 at rate: what an
// AVAudioEngine tap hands us after downmixing, and the capture resampler's
// input format.
func floatASBD(rate float64) asbd {
	return asbd{
		SampleRate:       rate,
		FormatID:         kAudioFormatLinearPCM,
		FormatFlags:      kLinearPCMFormatFlagIsFloat | kLinearPCMFormatFlagIsPacked,
		BytesPerPacket:   4,
		FramesPerPacket:  1,
		BytesPerFrame:    4,
		ChannelsPerFrame: 1,
		BitsPerChannel:   32,
	}
}

// opusASBD asks the framework what its own opus codec wants, then overrides the
// frames-per-packet: FormatInfo answers 120 (2.5ms), which would make ~800 tiny
// packets out of two seconds; wata's wire shape is a 20ms frame. Setting
// mFramesPerPacket on the OUTPUT description before AudioConverterNew is
// accepted and produces exactly that.
func opusASBD(framesPerPacket uint32) (asbd, error) {
	d := asbd{SampleRate: SampleRate, FormatID: kAudioFormatOpus, ChannelsPerFrame: Channels}
	size := uint32(unsafe.Sizeof(d))
	if st := audioFormatGetProperty(kAudioFormatProperty_FormatInfo, 0, nil, &size, unsafe.Pointer(&d)); st != 0 {
		return d, fmt.Errorf("macaudio: AudioFormatGetProperty(FormatInfo, opus): %s", osstatus(st))
	}
	d.FramesPerPacket = framesPerPacket
	return d, nil
}

// ── the input proc ───────────────────────────────────────────────────────────
//
// purego.NewCallback allocates a trampoline that is never released, so the
// package creates exactly ONE and routes every converter through it. The
// converter's userData carries a registry handle rather than a Go pointer, so
// no Go pointer is ever parked in C memory and two encoders on two goroutines
// cannot see each other's source.

type packetSource struct {
	// decoder side: one opus packet, handed over whole with its description.
	packet []byte
	// encoder / resampler side: raw PCM, consumed in bytesPerFrame units.
	pcm           []byte
	pcmPos        int
	bytesPerFrame int
	served        bool // decoder: the packet has been handed over
	desc          aspd // scratch description handed back to the converter
}

var (
	srcMu   sync.Mutex
	srcs    = map[uintptr]*packetSource{}
	nextSrc uintptr
)

func registerSource() uintptr {
	srcMu.Lock()
	defer srcMu.Unlock()
	nextSrc++
	h := nextSrc
	srcs[h] = &packetSource{}
	return h
}

func lookupSource(h uintptr) *packetSource {
	srcMu.Lock()
	defer srcMu.Unlock()
	return srcs[h]
}

func releaseSource(h uintptr) {
	srcMu.Lock()
	defer srcMu.Unlock()
	delete(srcs, h)
}

var inputProc = purego.NewCallback(func(conv uintptr, ioNumberDataPackets *uint32, ioData *audioBufferList, outDataPacketDescription **aspd, userData uintptr) uintptr {
	s := lookupSource(userData)
	if s == nil {
		*ioNumberDataPackets = 0
		return uintptr(uint32(statusNoMoreData))
	}
	if s.packet != nil {
		if s.served {
			*ioNumberDataPackets = 0
			return uintptr(uint32(statusNoMoreData))
		}
		s.served = true
		p := s.packet
		*ioNumberDataPackets = 1
		ioData.NumberBuffers = 1
		ioData.Buffers[0].NumberChannels = Channels
		ioData.Buffers[0].DataByteSize = uint32(len(p))
		ioData.Buffers[0].Data = unsafe.Pointer(&p[0])
		if outDataPacketDescription != nil {
			s.desc = aspd{StartOffset: 0, DataByteSize: uint32(len(p))}
			*outDataPacketDescription = &s.desc
		}
		return 0
	}
	remain := (len(s.pcm) - s.pcmPos) / s.bytesPerFrame
	if remain == 0 {
		*ioNumberDataPackets = 0
		return uintptr(uint32(statusNoMoreData))
	}
	n := int(*ioNumberDataPackets)
	if n > remain {
		n = remain
	}
	*ioNumberDataPackets = uint32(n)
	ioData.NumberBuffers = 1
	ioData.Buffers[0].NumberChannels = Channels
	ioData.Buffers[0].DataByteSize = uint32(n * s.bytesPerFrame)
	ioData.Buffers[0].Data = unsafe.Pointer(&s.pcm[s.pcmPos])
	s.pcmPos += n * s.bytesPerFrame
	return 0
})

// ── Encoder ─────────────────────────────────────────────────────────────────

// Encoder is one AudioConverter driving AudioToolbox's built-in Opus encoder at
// 48kHz mono, producing one FrameSamples-frame (20ms) packet per Encode call —
// the same one-in-one-out contract the device's libopus encoder has.
//
// Not safe for concurrent use: one Encoder belongs to one recording session
// (which is what the audio thread does with it).
type Encoder struct {
	conv      uintptr
	src       uintptr
	maxPacket uint32
	closed    bool
}

// EncodeBitRate is what the encoder asks AudioToolbox for. The codec treats it
// as advisory — the spike measured ~23.6 kbps for a 16000 request — which is
// fine for wata (the phone/mac uplink is not the constrained hop).
const EncodeBitRate = 16000

// NewEncoder builds the pcm16 -> opus converter.
func NewEncoder() (*Encoder, error) {
	if err := loadAudioToolbox(); err != nil {
		return nil, err
	}
	in := pcm16ASBD()
	out, err := opusASBD(FrameSamples)
	if err != nil {
		return nil, err
	}
	var conv uintptr
	if st := audioConverterNew(&in, &out, &conv); st != 0 {
		return nil, fmt.Errorf("macaudio: AudioConverterNew(pcm->opus): %s", osstatus(st))
	}
	br := uint32(EncodeBitRate)
	if st := audioConverterSetProperty(conv, kAudioConverterEncodeBitRate, 4, unsafe.Pointer(&br)); st != 0 {
		audioConverterDispose(conv)
		return nil, fmt.Errorf("macaudio: set EncodeBitRate: %s", osstatus(st))
	}
	var maxPacket uint32
	sz := uint32(4)
	if st := audioConverterGetProperty(conv, kAudioConverterPropertyMaximumOutputPacketSize, &sz, unsafe.Pointer(&maxPacket)); st != 0 {
		audioConverterDispose(conv)
		return nil, fmt.Errorf("macaudio: get MaximumOutputPacketSize: %s", osstatus(st))
	}
	return &Encoder{conv: conv, src: registerSource(), maxPacket: maxPacket}, nil
}

// Encode encodes exactly one FrameSamples-sample S16_LE frame of pcm into out
// and returns the encoded byte count.
func (e *Encoder) Encode(pcm []byte, out []byte) (int, error) {
	if e == nil || e.closed {
		return 0, errors.New("macaudio: Encode on a closed encoder")
	}
	want := FrameSamples * FrameSize * Channels
	if len(pcm) != want {
		return 0, fmt.Errorf("macaudio: Encode wants exactly %d bytes (%d samples), got %d", want, FrameSamples, len(pcm))
	}
	if uint32(len(out)) < e.maxPacket {
		return 0, fmt.Errorf("macaudio: Encode output buffer %d < max packet %d", len(out), e.maxPacket)
	}
	s := lookupSource(e.src)
	s.pcm, s.pcmPos, s.bytesPerFrame, s.packet, s.served = pcm, 0, FrameSize*Channels, nil, false

	var desc aspd
	list := audioBufferList{NumberBuffers: 1}
	list.Buffers[0] = audioBuffer{NumberChannels: Channels, DataByteSize: uint32(len(out)), Data: unsafe.Pointer(&out[0])}
	numPackets := uint32(1)
	st := audioConverterFillComplexBuffer(e.conv, inputProc, e.src, &numPackets, &list, &desc)
	// statusNoMoreData is our own starvation signal: the converter asked for
	// more input after consuming the frame we gave it. It still hands back the
	// packet it produced, and its state stays intact for the next call — which
	// is exactly the streaming behavior a per-frame encoder needs.
	if st != 0 && st != statusNoMoreData {
		return 0, fmt.Errorf("macaudio: FillComplexBuffer(encode): %s", osstatus(st))
	}
	if numPackets == 0 {
		return 0, errors.New("macaudio: encoder produced no packet for a full frame")
	}
	if desc.StartOffset != 0 {
		copy(out, out[desc.StartOffset:uint32(desc.StartOffset)+desc.DataByteSize])
	}
	return int(desc.DataByteSize), nil
}

// Close disposes the converter. Idempotent.
func (e *Encoder) Close() {
	if e == nil || e.closed {
		return
	}
	e.closed = true
	audioConverterDispose(e.conv)
	releaseSource(e.src)
}

// ── Decoder ─────────────────────────────────────────────────────────────────

// Decoder turns opus packets back into 48kHz mono S16_LE.
//
// The packet duration is NOT a property of the stream we are handed — a foreign
// encoder sends whatever frame size it likes (the TUI sends 60ms) — and the
// input ASBD's mFramesPerPacket must state it, so the decoder reads it out of
// each packet's TOC byte and keeps ONE converter per distinct duration seen.
// A stream of uniform packets therefore builds exactly one converter and keeps
// its inter-packet codec state, the same way the device's libopus decoder does.
type Decoder struct {
	convs  map[uint32]uintptr // frames-per-packet -> converter
	src    uintptr
	closed bool
}

// NewDecoder builds an empty converter set; the first packet decides the shape.
func NewDecoder() (*Decoder, error) {
	if err := loadAudioToolbox(); err != nil {
		return nil, err
	}
	return &Decoder{convs: map[uint32]uintptr{}, src: registerSource()}, nil
}

func (d *Decoder) converter(framesPerPacket uint32) (uintptr, error) {
	if c, ok := d.convs[framesPerPacket]; ok {
		return c, nil
	}
	in := asbd{
		SampleRate:       SampleRate,
		FormatID:         kAudioFormatOpus,
		ChannelsPerFrame: Channels,
		FramesPerPacket:  framesPerPacket,
	}
	out := pcm16ASBD()
	var conv uintptr
	if st := audioConverterNew(&in, &out, &conv); st != 0 {
		return 0, fmt.Errorf("macaudio: AudioConverterNew(opus->pcm, %d frames/packet): %s", framesPerPacket, osstatus(st))
	}
	d.convs[framesPerPacket] = conv
	return conv, nil
}

// Decode decodes one opus packet into out (S16_LE) and returns the SAMPLE
// count. out must hold MaxDecodeSamples samples: a foreign packet can be up to
// 120ms long, which is six of our own frames.
func (d *Decoder) Decode(data []byte, out []byte) (int, error) {
	if d == nil || d.closed {
		return 0, errors.New("macaudio: Decode on a closed decoder")
	}
	if len(data) == 0 {
		return 0, errors.New("macaudio: Decode of an empty packet")
	}
	frames, err := opusPacketFrames(data)
	if err != nil {
		return 0, err
	}
	if frames > MaxDecodeSamples {
		return 0, fmt.Errorf("macaudio: packet claims %d samples, over the %d 120ms maximum", frames, MaxDecodeSamples)
	}
	if len(out) < frames*FrameSize*Channels {
		return 0, fmt.Errorf("macaudio: Decode output buffer %d < %d bytes needed", len(out), frames*FrameSize*Channels)
	}
	conv, err := d.converter(uint32(frames))
	if err != nil {
		return 0, err
	}
	s := lookupSource(d.src)
	s.packet, s.served, s.pcm, s.pcmPos = data, false, nil, 0

	list := audioBufferList{NumberBuffers: 1}
	list.Buffers[0] = audioBuffer{NumberChannels: Channels, DataByteSize: uint32(len(out)), Data: unsafe.Pointer(&out[0])}
	numFrames := uint32(frames)
	st := audioConverterFillComplexBuffer(conv, inputProc, d.src, &numFrames, &list, nil)
	if st != 0 && st != statusNoMoreData {
		return 0, fmt.Errorf("macaudio: FillComplexBuffer(decode): %s", osstatus(st))
	}
	return int(numFrames), nil
}

// Close disposes every converter the decoder built. Idempotent.
func (d *Decoder) Close() {
	if d == nil || d.closed {
		return
	}
	d.closed = true
	for _, c := range d.convs {
		audioConverterDispose(c)
	}
	d.convs = nil
	releaseSource(d.src)
}

// opusPacketFrames reads a packet's duration in 48kHz samples out of its TOC
// byte (RFC 6716 §3.1): the config selects the frame duration, the code selects
// how many frames the packet carries. This is what lets one Decoder handle both
// our 20ms packets and a foreign encoder's 60ms ones without being told.
func opusPacketFrames(p []byte) (int, error) {
	toc := p[0]
	config := int(toc >> 3)
	var frameSamples int
	switch {
	case config < 12: // SILK: 10, 20, 40, 60 ms
		frameSamples = []int{480, 960, 1920, 2880}[config&3]
	case config < 16: // hybrid: 10, 20 ms
		frameSamples = []int{480, 960}[config&1]
	default: // CELT: 2.5, 5, 10, 20 ms
		frameSamples = []int{120, 240, 480, 960}[config&3]
	}
	count := 1
	switch toc & 3 {
	case 0:
		count = 1
	case 1, 2:
		count = 2
	case 3:
		if len(p) < 2 {
			return 0, errors.New("macaudio: truncated code-3 opus packet")
		}
		count = int(p[1] & 0x3f)
		if count == 0 {
			return 0, errors.New("macaudio: code-3 opus packet claims zero frames")
		}
	}
	return frameSamples * count, nil
}
