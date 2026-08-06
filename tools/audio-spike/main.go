// The Apple-audio derisk spike (AUDIO-APPLE-DERISK): prove the macOS/iOS
// audio path with NO cgo — AudioToolbox's built-in Opus codec through purego
// (leg 1), AVAudioEngine render through the generated bindings (leg 2), and
// mic capture (leg 3). REPORT.md is the deliverable; this driver is the
// evidence generator.
//
//	go run . [-only opus|foreign|render|capture] [-fixture path]
package main

import (
	"flag"
	"fmt"
	"math"
	"os"
)

const sampleRate = 48000

// sine synthesizes n samples of a 440Hz tone at ~ -6 dBFS.
func sine(n int) []int16 {
	out := make([]int16, n)
	for i := range out {
		out[i] = int16(16000 * math.Sin(2*math.Pi*440*float64(i)/sampleRate))
	}
	return out
}

func pcmBytes(s []int16) []byte {
	b := make([]byte, len(s)*2)
	for i, v := range s {
		b[2*i] = byte(v)
		b[2*i+1] = byte(uint16(v) >> 8)
	}
	return b
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

// goertzel returns the power fraction of the signal concentrated at freq —
// the numeric "is this still a 440Hz tone" check (no listening involved).
func goertzel(s []int16, freq float64) float64 {
	if len(s) == 0 {
		return 0
	}
	w := 2 * math.Pi * freq / sampleRate
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

func check(ok bool, format string, args ...any) {
	status := "ok  "
	if !ok {
		status = "FAIL"
		failed = true
	}
	fmt.Printf("%s %s\n", status, fmt.Sprintf(format, args...))
}

var failed bool

// legOpus: PCM -> AudioToolbox opus encoder -> packets -> decoder -> PCM,
// fidelity judged numerically (tone purity + level after codec round trip).
func legOpus() {
	fmt.Println("── leg 1a: opus round trip, no cgo (AudioConverter C API via purego)")
	const seconds = 2
	in := sine(seconds * sampleRate)
	packets, opusFmt, err := encodeOpus(pcmBytes(in), 16000)
	if err != nil {
		check(false, "encode: %v", err)
		return
	}
	total := 0
	minP, maxP := 1<<30, 0
	for _, p := range packets {
		total += len(p)
		if len(p) < minP {
			minP = len(p)
		}
		if len(p) > maxP {
			maxP = len(p)
		}
	}
	fpp := opusFmt.FramesPerPacket
	fmt.Printf("     encoder ASBD: %g Hz, %d ch, %d frames/packet\n",
		opusFmt.SampleRate, opusFmt.ChannelsPerFrame, fpp)
	fmt.Printf("     %d packets, %d..%d bytes, %d total (%.1f kbps)\n",
		len(packets), minP, maxP, total, float64(total)*8/seconds/1000)
	wantPackets := seconds * sampleRate / int(fpp)
	check(len(packets) >= wantPackets && len(packets) <= wantPackets+2,
		"packet count %d ~ %d (%d frames/packet over %ds)", len(packets), wantPackets, fpp, seconds)
	check(total > 1000, "non-trivial payload (%d bytes)", total)

	dec, err := decodeOpus(packets, fpp)
	if err != nil {
		check(false, "decode: %v", err)
		return
	}
	fmt.Printf("     decoded %d frames (%.3fs); input %d\n", len(dec), float64(len(dec))/sampleRate, len(in))
	check(abs(len(dec)-len(in)) <= int(fpp), "duration matches input within one packet")
	// skip the first 250ms: codec priming + pre-skip land there
	settled := dec
	if len(settled) > sampleRate/4 {
		settled = settled[sampleRate/4:]
	}
	r := rms(settled)
	g := goertzel(settled, 440)
	fmt.Printf("     settled RMS %.4f (input %.4f), 440Hz power fraction %.4f\n", r, rms(in), g)
	check(r > 0.2 && r < 0.5, "level preserved through the codec (RMS %.4f vs input %.4f)", r, rms(in))
	check(g > 0.9, "still a pure 440Hz tone after the round trip (fraction %.4f)", g)
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}

// legForeign: decode the repo's real cross-implementation fixture — 1.5s of
// 440Hz encoded by the TUI's wasm opus stack (60ms/2880-frame packets), the
// exact bytes ci step 14 oracles through the Scala Ogg reader.
func legForeign(fixture string) {
	fmt.Println("── leg 1b: decode the foreign fixture (tui-foreign.ogg)")
	raw, err := os.ReadFile(fixture)
	if err != nil {
		check(false, "read fixture: %v", err)
		return
	}
	packets := oggPackets(raw)
	fmt.Printf("     %d bytes ogg -> %d opus packets\n", len(raw), len(packets))
	check(len(packets) == 25, "25 packets as the container promises (got %d)", len(packets))
	dec, err := decodeOpus(packets, 2880)
	if err != nil {
		check(false, "decode: %v", err)
		return
	}
	fmt.Printf("     decoded %d frames (%.3fs)\n", len(dec), float64(len(dec))/sampleRate)
	check(abs(len(dec)-72000) <= 2880, "~72000 frames = the fixture's final granule (got %d)", len(dec))
	settled := dec
	if len(settled) > sampleRate/4 {
		settled = settled[sampleRate/4:]
	}
	r := rms(settled)
	g := goertzel(settled, 440)
	fmt.Printf("     settled RMS %.4f, 440Hz power fraction %.4f\n", r, g)
	check(r > 0.05, "non-silent output (RMS %.4f)", r)
	check(g > 0.9, "the fixture's 440Hz tone survives a foreign decode (fraction %.4f)", g)
}

func main() {
	only := flag.String("only", "", "run one leg: opus|foreign|render|capture")
	fixture := flag.String("fixture", "../../go-pkgs/audio/testdata/tui-foreign.ogg", "ogg fixture path")
	captureSecs := flag.Int("capture-secs", 3, "capture leg duration")
	flag.Parse()

	loadAudioToolbox()

	run := func(name string) bool { return *only == "" || *only == name }
	if run("opus") {
		legOpus()
	}
	if run("foreign") {
		legForeign(*fixture)
	}
	if run("render") {
		legRender(*fixture)
	}
	if *only == "capture" {
		legCapture(*captureSecs)
	} else if run("capture") {
		fmt.Println("── leg 3: capture — skipped unless invoked as `-only capture` (mic TCC prompt; see REPORT.md)")
	}

	if failed {
		fmt.Println("RESULT: FAIL")
		os.Exit(1)
	}
	fmt.Println("RESULT: OK")
}
