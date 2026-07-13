//go:build linux && arm

// M8 chunk 7 follow-up — the FOREIGN-CONTAINER decode guard: the real opus
// decode path over a pinned TUI-shaped Ogg/Opus fixture, so the chunk-6
// "VOICEPLAY FAIL on TUI-sent audio" regression class (OPUS_BUFFER_TOO_SMALL:
// DecodeFrame sized its buffer at our own 20ms frame and rejected any longer
// foreign packet) is guarded by a test, not by a live TUI message.
//
// BUILD DISCIPLINE (why linux && arm): the real opus is cgo on linux/arm only;
// every other platform gets the loud no-op stub (audio_stub.go), so this test
// CANNOT run host-side. It rides the existing arm cross discipline instead:
// ci step 13 (tools/wata-fb-smoke.sh) cross-COMPILES it (`go test -c`,
// zig-gated with the same SKIP-sans-zig hermeticity) — the device is never a
// ci dependency. Run it for real on the BQ268:
//
//	scp out-fb/audio-arm.test root@bq268:/dev/shm/ && \
//	  ssh root@bq268 'mount -o remount,exec /dev/shm && /dev/shm/audio-arm.test -test.v'
//
// The container-parsing half of the same regression class DOES run at ci,
// host-side, over the SAME fixture bytes: wataclient-tests check 7/7
// (`wata-fb oggforeign`, ci step 14).
//
// FIXTURE: testdata/tui-foreign.ogg — 1.5s of 440Hz encoded by the TUI's own
// stack (@evan/wasm opus at 16kHz, 960-sample = 60ms frames -> TOC config 11;
// one packet per page, random serial, EOS page carries audio). 25 packets,
// each decoding to 2880 samples at 48kHz; final granule 72000. Regenerate
// (designer-reviewed re-pin; needs node+tsx, NEVER at ci time):
//
//	cd ~/g/bq268/wata && node --import tsx/esm \
//	  <spike>/tools/tui-encode.mts <spike>/go-pkgs/audio/testdata/tui-foreign.ogg
//
// (then re-pin tools/wataclient-foreign.expected.txt from `wata-fb oggforeign`).
package audio

import (
	_ "embed"
	"testing"
)

//go:embed testdata/tui-foreign.ogg
var tuiForeign []byte

// oggPackets extracts Opus packets from an Ogg stream with correct lacing
// semantics (segments of 255 continue the packet; < 255 ends it), dropping
// the first two packets (OpusHead/OpusTags) — the TS demuxer's contract.
// Test-local on purpose: the production reader is the portable Scala
// Ogg.readFrames (oracled by ci step 14 over these same bytes).
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
	return packets
}

// TestForeignContainerDecode: every TUI packet must decode cleanly (the
// regression returned OPUS_BUFFER_TOO_SMALL here) with the exact per-packet
// and total sample counts the container promises.
func TestForeignContainerDecode(t *testing.T) {
	all := oggPackets(tuiForeign)
	if len(all) < 3 {
		t.Fatalf("fixture parse: got %d packets, want >= 3 (head+tags+audio)", len(all))
	}
	if string(all[0][:8]) != "OpusHead" || string(all[1][:8]) != "OpusTags" {
		t.Fatalf("fixture parse: first two packets are not OpusHead/OpusTags")
	}
	pkts := all[2:]
	if len(pkts) != 25 {
		t.Fatalf("fixture parse: got %d audio packets, want 25", len(pkts))
	}

	dec, err := NewDecoder()
	if err != nil {
		t.Fatalf("NewDecoder: %v", err)
	}
	defer dec.Close()

	total := 0
	for i, p := range pkts {
		if cfg := p[0] >> 3; cfg != 11 {
			t.Fatalf("packet %d: TOC config %d, want 11 (SILK-WB 60ms — the foreign shape)", i, cfg)
		}
		pcm, err := dec.DecodeFrame(p)
		if err != nil {
			t.Fatalf("packet %d: DecodeFrame failed: %v (the OPUS_BUFFER_TOO_SMALL regression?)", i, err)
		}
		n := len(pcm) / (FrameSize * Channels)
		if n != 2880 {
			t.Fatalf("packet %d: decoded %d samples, want 2880 (60ms at 48kHz)", i, n)
		}
		total += n
	}
	if total != 72000 {
		t.Fatalf("total decoded %d samples, want 72000 (the fixture's final granule)", total)
	}
}

// TestDevicePacketStillExact: our own 20ms/960-sample encode path must still
// decode to exactly FrameSamples — the MaxDecodeSamples buffer must not change
// the device round-trip contract.
func TestDevicePacketStillExact(t *testing.T) {
	enc, err := NewEncoder()
	if err != nil {
		t.Fatalf("NewEncoder: %v", err)
	}
	defer enc.Close()
	pkt, err := enc.EncodeFrameAt(Tone(440, FrameSamples), 0)
	if err != nil {
		t.Fatalf("EncodeFrameAt: %v", err)
	}
	dec, err := NewDecoder()
	if err != nil {
		t.Fatalf("NewDecoder: %v", err)
	}
	defer dec.Close()
	pcm, err := dec.DecodeFrame(pkt)
	if err != nil {
		t.Fatalf("DecodeFrame: %v", err)
	}
	if n := len(pcm) / (FrameSize * Channels); n != FrameSamples {
		t.Fatalf("device packet decoded %d samples, want exactly %d", n, FrameSamples)
	}
}
