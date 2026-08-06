// The codec legs of the package, judged NUMERICALLY (tone purity, packet
// counts, frame counts) and never by ear. These run against the REAL
// AudioToolbox opus codec — the fake backend replaces only mic and speaker —
// so they are the standing version of the audio spike's legs 1a and 1b.
package macaudio

import (
	"math"
	"os"
	"testing"
)

// oggPackets extracts Opus packets from an Ogg stream with correct lacing
// semantics (a 255 segment continues the packet, < 255 ends it), dropping
// OpusHead/OpusTags. TEST SCAFFOLDING ONLY: Ogg framing is portable and the
// production reader is wataclient's Scala Ogg.readFrames — this package
// deliberately owns no container code.
func oggPackets(d []byte) [][]byte {
	var packets [][]byte
	var pending []byte
	pos := 0
	for pos+27 <= len(d) && string(d[pos:pos+4]) == "OggS" {
		nseg := int(d[pos+26])
		segStart := pos + 27
		if segStart+nseg > len(d) {
			break
		}
		off := segStart + nseg
		for i := 0; i < nseg; i++ {
			sz := int(d[segStart+i])
			if off+sz > len(d) {
				return packets
			}
			pending = append(pending, d[off:off+sz]...)
			off += sz
			if sz < 255 {
				if len(pending) > 0 {
					packets = append(packets, pending)
				}
				pending = nil
			}
		}
		pos = off
	}
	if len(packets) >= 2 {
		return packets[2:]
	}
	return nil
}

func samples(pcm []byte) []int16 {
	out := make([]int16, len(pcm)/2)
	for i := range out {
		out[i] = int16(uint16(pcm[2*i]) | uint16(pcm[2*i+1])<<8)
	}
	return out
}

func rms(s []int16) float64 {
	if len(s) == 0 {
		return 0
	}
	var acc float64
	for _, v := range s {
		f := float64(v) / 32768
		acc += f * f
	}
	return math.Sqrt(acc / float64(len(s)))
}

// goertzel returns the fraction of the signal's power sitting at freq — the
// numeric "is this still a 440Hz tone" check.
func goertzel(s []int16, freq float64) float64 {
	if len(s) == 0 {
		return 0
	}
	w := 2 * math.Pi * freq / SampleRate
	coeff := 2 * math.Cos(w)
	var s0, s1, s2, total float64
	for _, v := range s {
		f := float64(v) / 32768
		total += f * f
		s0 = f + coeff*s1 - s2
		s2, s1 = s1, s0
	}
	power := s1*s1 + s2*s2 - coeff*s1*s2
	if total == 0 {
		return 0
	}
	return power * 2 / (total * float64(len(s)))
}

// settled drops the first 250ms, where codec priming and pre-skip live.
func settled(s []int16) []int16 {
	if len(s) > SampleRate/4 {
		return s[SampleRate/4:]
	}
	return s
}

// encodeAll runs a whole pcm buffer through the per-frame encoder, which is the
// call shape the audio thread uses (one packet per 960-sample frame).
func encodeAll(t *testing.T, pcm []byte) [][]byte {
	t.Helper()
	enc, err := NewEncoder()
	if err != nil {
		t.Fatalf("NewEncoder: %v", err)
	}
	defer enc.Close()
	var packets [][]byte
	frames := len(pcm) / (FrameSamples * FrameSize * Channels)
	for i := 0; i < frames; i++ {
		p, err := enc.EncodeFrameAt(pcm, i)
		if err != nil {
			t.Fatalf("EncodeFrameAt(%d): %v", i, err)
		}
		packets = append(packets, p)
	}
	return packets
}

func decodeAll(t *testing.T, packets [][]byte) []int16 {
	t.Helper()
	dec, err := NewDecoder()
	if err != nil {
		t.Fatalf("NewDecoder: %v", err)
	}
	defer dec.Close()
	var out []int16
	for i, p := range packets {
		pcm, err := dec.DecodeFrame(p)
		if err != nil {
			t.Fatalf("DecodeFrame(%d): %v", i, err)
		}
		out = append(out, samples(pcm)...)
	}
	return out
}

// TestToneRoundTrip: 2s of 440Hz -> 100 packets of 40..80 bytes -> decode ->
// the tone is still a tone. The spike measured 101 packets of 22..74 bytes
// driving the encoder in BULK; encoding frame by frame — the shape the audio
// thread uses — gives exactly one packet per 960-sample frame, so 100 of them,
// and none of the tiny sub-20-byte packets bulk mode emitted while priming.
// Measured here: 47..74 bytes; the asserted window is 40..80 so a codec
// revision does not fail the suite for being a few bytes cheaper.
func TestToneRoundTrip(t *testing.T) {
	const seconds = 2
	pcm := Tone(440, seconds*SampleRate)
	packets := encodeAll(t, pcm)

	if want := seconds * SampleRate / FrameSamples; len(packets) != want {
		t.Fatalf("packet count %d, want %d (one per 20ms frame)", len(packets), want)
	}
	total, minP, maxP := 0, 1<<30, 0
	for _, p := range packets {
		total += len(p)
		minP = min(minP, len(p))
		maxP = max(maxP, len(p))
	}
	t.Logf("%d packets, %d..%d bytes, %d total (%.1f kbps)",
		len(packets), minP, maxP, total, float64(total)*8/seconds/1000)
	if minP < 40 || maxP > 80 {
		t.Errorf("packet sizes %d..%d outside the 40..80 window (measured 47..74)", minP, maxP)
	}

	dec := decodeAll(t, packets)
	if got, want := len(dec), seconds*SampleRate; got < want-FrameSamples || got > want+FrameSamples {
		t.Errorf("decoded %d samples, want %d ± one frame", got, want)
	}
	s := settled(dec)
	r, g := rms(s), goertzel(s, 440)
	t.Logf("settled RMS %.4f (input %.4f), 440Hz power fraction %.4f", r, rms(settled(samples(pcm))), g)
	if r < 0.2 || r > 0.5 {
		t.Errorf("level not preserved: RMS %.4f", r)
	}
	if g < 0.99 {
		t.Errorf("440Hz purity %.4f after the round trip, want > 0.99", g)
	}
}

// TestForeignFixtureDecodes is the interop check that matters: the repo's real
// fixture was produced by the TUI's wasm opus stack at a FOREIGN frame size
// (60ms / 2880 samples), so it proves the decoder reads the packet's own TOC
// rather than assuming wata's 20ms shape. Nothing in this package parses Ogg
// (that is wataclient's job) — the demux here is test scaffolding.
func TestForeignFixtureDecodes(t *testing.T) {
	raw, err := os.ReadFile("../audio/testdata/tui-foreign.ogg")
	if err != nil {
		t.Fatalf("fixture: %v", err)
	}
	packets := oggPackets(raw)
	if len(packets) != 25 {
		t.Fatalf("demuxed %d packets, the fixture promises 25", len(packets))
	}
	if n, err := opusPacketFrames(packets[0]); err != nil || n != 2880 {
		t.Fatalf("TOC frame count %d (err %v), want 2880", n, err)
	}
	dec := decodeAll(t, packets)
	t.Logf("decoded %d samples (%.3fs)", len(dec), float64(len(dec))/SampleRate)
	// 72000 is the fixture's final granule; the codec's pre-skip is not
	// swallowed at this API level, so allow one packet of slack.
	if d := len(dec) - 72000; d > 2880 || d < -2880 {
		t.Errorf("decoded %d samples, want 72000 ± one 2880-sample packet", len(dec))
	}
	s := settled(dec)
	r, g := rms(s), goertzel(s, 440)
	t.Logf("settled RMS %.4f, 440Hz power fraction %.4f", r, g)
	if r < 0.05 {
		t.Errorf("silent decode (RMS %.4f)", r)
	}
	if g < 0.99 {
		t.Errorf("440Hz purity %.4f on the foreign fixture, want > 0.99", g)
	}
}

// TestEncodeFrameAtPair is the exact call shape recordLoop makes: one
// PeriodBytes buffer, both of its 960-sample subframes encoded, neither
// dropped.
func TestEncodeFrameAtPair(t *testing.T) {
	enc, err := NewEncoder()
	if err != nil {
		t.Fatalf("NewEncoder: %v", err)
	}
	defer enc.Close()
	buf := Tone(440, FramesPerPeriod)
	if len(buf) != PeriodBytes {
		t.Fatalf("Tone(FramesPerPeriod) is %d bytes, PeriodBytes is %d", len(buf), PeriodBytes)
	}
	f0, err := enc.EncodeFrameAt(buf, 0)
	if err != nil {
		t.Fatalf("EncodeFrameAt(0): %v", err)
	}
	f1, err := enc.EncodeFrameAt(buf, 1)
	if err != nil {
		t.Fatalf("EncodeFrameAt(1): %v", err)
	}
	if len(f0) == 0 || len(f1) == 0 {
		t.Fatalf("empty packet(s): %d, %d bytes", len(f0), len(f1))
	}
	for i, p := range [][]byte{f0, f1} {
		n, err := opusPacketFrames(p)
		if err != nil || n != FrameSamples {
			t.Errorf("subframe %d: TOC says %d samples (err %v), want %d", i, n, err, FrameSamples)
		}
	}
	if _, err := enc.EncodeFrameAt(buf, 2); err == nil {
		t.Error("EncodeFrameAt(buf, 2) should be out of range for one period")
	}
}
