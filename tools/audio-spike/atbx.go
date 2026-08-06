// AudioToolbox's C AudioConverter API over purego — no cgo, no ObjC.
//
// The codec leg deliberately uses the C function-pointer API rather than
// AVAudioConverter: AVAudioConverter's convertToBuffer:error:withInputFromBlock:
// takes a block with a NON-void return (the input block returns AVAudioBuffer*),
// a shape the bindgen mapper refuses (purego callbacks cannot return objects
// through the block trampoline), while AudioConverterFillComplexBuffer's input
// proc is a plain C function pointer whose arguments are all pointers — exactly
// what purego.NewCallback supports. Same codec underneath (both sit on
// AudioCodec components).
package main

import (
	"fmt"
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

	kLinearPCMFormatFlagIsSignedInteger uint32 = 1 << 2
	kLinearPCMFormatFlagIsPacked        uint32 = 1 << 3

	kAudioFormatProperty_FormatInfo = fourcc("fmti")

	kAudioConverterEncodeBitRate                   = fourcc("brat")
	kAudioConverterPropertyMaximumOutputPacketSize = fourcc("xops")
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

func loadAudioToolbox() {
	lib, err := purego.Dlopen(
		"/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox",
		purego.RTLD_LAZY|purego.RTLD_GLOBAL)
	if err != nil {
		panic(fmt.Sprintf("dlopen AudioToolbox: %v", err))
	}
	purego.RegisterLibFunc(&audioConverterNew, lib, "AudioConverterNew")
	purego.RegisterLibFunc(&audioConverterDispose, lib, "AudioConverterDispose")
	purego.RegisterLibFunc(&audioConverterSetProperty, lib, "AudioConverterSetProperty")
	purego.RegisterLibFunc(&audioConverterGetProperty, lib, "AudioConverterGetProperty")
	purego.RegisterLibFunc(&audioConverterFillComplexBuffer, lib, "AudioConverterFillComplexBuffer")
	purego.RegisterLibFunc(&audioFormatGetProperty, lib, "AudioFormatGetProperty")
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

// pcm16ASBD — 48kHz mono signed-16 packed, the wire shape go-pkgs/audio uses.
func pcm16ASBD() asbd {
	return asbd{
		SampleRate:       48000,
		FormatID:         kAudioFormatLinearPCM,
		FormatFlags:      kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
		BytesPerPacket:   2,
		FramesPerPacket:  1,
		BytesPerFrame:    2,
		ChannelsPerFrame: 1,
		BitsPerChannel:   16,
	}
}

// opusASBD — filled out by AudioFormatGetProperty(FormatInfo) so the framework
// itself states what its opus codec wants (frames per packet in particular).
func opusASBD() (asbd, error) {
	d := asbd{SampleRate: 48000, FormatID: kAudioFormatOpus, ChannelsPerFrame: 1}
	size := uint32(unsafe.Sizeof(d))
	if st := audioFormatGetProperty(kAudioFormatProperty_FormatInfo, 0, nil, &size, unsafe.Pointer(&d)); st != 0 {
		return d, fmt.Errorf("AudioFormatGetProperty(FormatInfo, opus): %s", osstatus(st))
	}
	return d, nil
}

// ── the input proc ───────────────────────────────────────────────────────────
//
// One shared C-callback trampoline (purego.NewCallback is a limited resource:
// callbacks are never released, so create exactly one and route through a
// package var). FillComplexBuffer calls it synchronously on the calling
// goroutine's thread, so a plain package-level source is race-free here.

type packetSource struct {
	packets        [][]byte // for the decoder: one opus packet per entry
	pcm            []byte   // for the encoder: raw pcm16 bytes
	pcmPos         int
	bytesPerPacket int  // encoder: bytes per PCM "packet" (= per frame)
	desc           aspd // scratch packet description handed back to the converter
}

var src *packetSource

var inputProc = purego.NewCallback(func(conv uintptr, ioNumberDataPackets *uint32, ioData *audioBufferList, outDataPacketDescription **aspd, userData uintptr) uintptr {
	if src.packets != nil {
		// decoder side: hand over exactly one opus packet with its description
		if len(src.packets) == 0 {
			*ioNumberDataPackets = 0
			return 0
		}
		p := src.packets[0]
		src.packets = src.packets[1:]
		*ioNumberDataPackets = 1
		ioData.NumberBuffers = 1
		ioData.Buffers[0].NumberChannels = 1
		ioData.Buffers[0].DataByteSize = uint32(len(p))
		ioData.Buffers[0].Data = unsafe.Pointer(&p[0])
		if outDataPacketDescription != nil {
			src.desc = aspd{StartOffset: 0, DataByteSize: uint32(len(p))}
			*outDataPacketDescription = &src.desc
		}
		return 0
	}
	// encoder side: hand over as many PCM frames as requested (or remain)
	remain := (len(src.pcm) - src.pcmPos) / src.bytesPerPacket
	if remain == 0 {
		*ioNumberDataPackets = 0
		return 0
	}
	n := int(*ioNumberDataPackets)
	if n > remain {
		n = remain
	}
	*ioNumberDataPackets = uint32(n)
	ioData.NumberBuffers = 1
	ioData.Buffers[0].NumberChannels = 1
	ioData.Buffers[0].DataByteSize = uint32(n * src.bytesPerPacket)
	ioData.Buffers[0].Data = unsafe.Pointer(&src.pcm[src.pcmPos])
	src.pcmPos += n * src.bytesPerPacket
	return 0
})

// encodeOpus runs pcm16 through the AudioToolbox opus encoder; returns the
// packets and the ASBD the converter settled on.
func encodeOpus(pcm []byte, bitrate uint32) ([][]byte, asbd, error) {
	return encodeOpusRate(pcm, bitrate, 48000)
}

// encodeOpusRate is encodeOpus with a caller-set input sample rate: the
// converter's own SRC brings a non-48k capture rate to opus's 48k.
func encodeOpusRate(pcm []byte, bitrate uint32, inRate float64) ([][]byte, asbd, error) {
	in := pcm16ASBD()
	in.SampleRate = inRate
	out, err := opusASBD()
	if err != nil {
		return nil, out, err
	}
	// FormatInfo defaults to 120 frames/packet (2.5ms); wata's wire shape is
	// 20ms frames — ask for 960 and let AudioConverterNew arbitrate.
	out.FramesPerPacket = 960
	var conv uintptr
	if st := audioConverterNew(&in, &out, &conv); st != 0 {
		return nil, out, fmt.Errorf("AudioConverterNew(pcm->opus): %s", osstatus(st))
	}
	defer audioConverterDispose(conv)
	if st := audioConverterSetProperty(conv, kAudioConverterEncodeBitRate, 4, unsafe.Pointer(&bitrate)); st != 0 {
		return nil, out, fmt.Errorf("set EncodeBitRate: %s", osstatus(st))
	}
	var maxPacket uint32
	sz := uint32(4)
	if st := audioConverterGetProperty(conv, kAudioConverterPropertyMaximumOutputPacketSize, &sz, unsafe.Pointer(&maxPacket)); st != 0 {
		return nil, out, fmt.Errorf("get MaximumOutputPacketSize: %s", osstatus(st))
	}
	src = &packetSource{pcm: pcm, bytesPerPacket: int(in.BytesPerPacket)}
	defer func() { src = nil }()

	var packets [][]byte
	buf := make([]byte, maxPacket)
	for {
		var desc aspd
		list := audioBufferList{NumberBuffers: 1}
		list.Buffers[0] = audioBuffer{NumberChannels: 1, DataByteSize: uint32(len(buf)), Data: unsafe.Pointer(&buf[0])}
		numPackets := uint32(1)
		st := audioConverterFillComplexBuffer(conv, inputProc, 0, &numPackets, &list, &desc)
		if st != 0 {
			return nil, out, fmt.Errorf("FillComplexBuffer(encode) after %d packets: %s", len(packets), osstatus(st))
		}
		if numPackets == 0 {
			break
		}
		p := make([]byte, desc.DataByteSize)
		copy(p, buf[desc.StartOffset:uint32(desc.StartOffset)+desc.DataByteSize])
		packets = append(packets, p)
	}
	return packets, out, nil
}

// decodeOpus runs opus packets through the AudioToolbox decoder back to pcm16.
// framesPerPacket tells the converter the packet duration (the fixture's
// foreign packets are 2880 frames; our own are 960).
func decodeOpus(packets [][]byte, framesPerPacket uint32) ([]int16, error) {
	in := asbd{
		SampleRate:       48000,
		FormatID:         kAudioFormatOpus,
		ChannelsPerFrame: 1,
		FramesPerPacket:  framesPerPacket,
	}
	out := pcm16ASBD()
	var conv uintptr
	if st := audioConverterNew(&in, &out, &conv); st != 0 {
		return nil, fmt.Errorf("AudioConverterNew(opus->pcm): %s", osstatus(st))
	}
	defer audioConverterDispose(conv)

	src = &packetSource{packets: packets}
	defer func() { src = nil }()

	var pcm []int16
	const chunkFrames = 4096
	buf := make([]int16, chunkFrames)
	for {
		list := audioBufferList{NumberBuffers: 1}
		list.Buffers[0] = audioBuffer{NumberChannels: 1, DataByteSize: uint32(len(buf) * 2), Data: unsafe.Pointer(&buf[0])}
		numFrames := uint32(chunkFrames)
		st := audioConverterFillComplexBuffer(conv, inputProc, 0, &numFrames, &list, nil)
		if st != 0 {
			return nil, fmt.Errorf("FillComplexBuffer(decode) after %d frames: %s", len(pcm), osstatus(st))
		}
		if numFrames == 0 {
			break
		}
		pcm = append(pcm, buf[:numFrames]...)
	}
	return pcm, nil
}
