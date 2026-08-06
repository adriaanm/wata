# Apple-audio derisk report — Opus, render, capture with no cgo

Queue item AUDIO-APPLE-DERISK under plan
[0023](../../docs/plans/0023-sgola-everywhere.md)'s M3, run 2026-08-06 on
macOS arm64 (Xcode 26.2 SDK bindings), Go 1.26.5 host, purego v0.10.2.
Rerun: `just audio-spike` (unattended); `just audio-spike -only capture`
(the mic leg, needs the terminal's mic TCC grant).

## The answer

**Yes to all three legs, with no cgo anywhere.** AudioToolbox's built-in
Opus codec (`kAudioFormatOpus`, macOS 10.13+/iOS 11+) encodes and decodes
wata's exact wire shape — 48kHz mono, 960-frame (20ms) packets — through
the C AudioConverter API driven by purego; AVAudioEngine renders and
captures through the bindgen-generated `go-pkgs/appleptt/avfaudio`
package. Every check is numeric, none by ear:

- **Leg 1a, round trip**: a 2s 440Hz tone encodes to 101 packets of
  22..74 bytes (5906 total) and decodes back at RMS 0.3554 vs input
  0.3453 with **0.9918** of the power still at 440Hz (Goertzel).
- **Leg 1b, the interop check that matters**: the repo's real fixture
  `go-pkgs/audio/testdata/tui-foreign.ogg` — encoded by the TUI's
  *wasm* opus stack, 60ms/2880-frame packets, i.e. a foreign encoder
  AND a foreign frame size — demuxes to its promised 25 packets and
  decodes to 71880/72000 frames, RMS 0.3453, **0.9997** at 440Hz.
- **Leg 2, render**: the decoded fixture plays through
  AVAudioEngine -> AVAudioPlayerNode via the generated bindings; the
  scheduled buffer is consumed to completion in 1.533s wall for 1.498s
  of audio (real-time render), engine running before and after.
- **Leg 3, capture**: the input-node tap delivered 139200 frames in a
  3s run (hardware 48kHz/2ch), RMS 0.016 (live room), and the captured
  audio survived encode -> decode through leg 1's converter. It ran
  unattended here because this terminal already held the mic TCC grant;
  on a machine without it the leg reports the silence and names the
  grant instead of wedging.

**Consequence, stated explicitly: the cgo-under-gomobile question from
`tools/phone-spike/REPORT.md` ("M3's audio path will be the first
cgo-under-gomobile question") dissolves.** The phone/mac clients need no
cgo opus — `go-pkgs/audio`'s cgo opus+tinyalsa stays what it already is,
the *device-only* (linux/arm) backend. The Apple audio architecture is
purego + generated bindings all the way down.

## What landed where

- `tools/audio-spike/` — this driver: `atbx.go` (AudioConverter over
  purego), `engine.go` (AVAudioEngine legs), `ogg.go` (packet demux),
  `main.go` (checks). `just audio-spike`.
- `go-pkgs/appleptt/avfaudio/` — new generated package: `AVAudioEngine`,
  `AVAudioNode`, `AVAudioPlayerNode`, `AVAudioFormat`, `AVAudioPCMBuffer`
  as classes; `AVAudioCommonFormat`; input/output/mixer nodes,
  `AVAudioTime` etc. opaque; macOS SDK, `frameworks: ["AVFAudio"]`.
- Two generator fixes the spike forced, both with fixture coverage
  (`frameworks.json`, `property.json`):
  1. **Framework loading**: the allowlist's `frameworks` field was
     parsed but never emitted, so every generated class outside
     Foundation resolved to nil (`objc.GetClass` sees only loaded
     frameworks, and a pure-Go binary links none). A target naming
     `frameworks` now emits `frameworks.go`, a `Dlopen` init.
  2. **Custom getter selectors**: `@property (getter=isRunning)` was
     bound to selector `running` — an "unrecognized selector" NSException
     on first touch. `Prop` now carries the AST's `getter`/`setter`
     names. This also fixed two latent wrong selectors in the existing
     `foundation` package (`isiOSAppOnMac`, `isiOSAppOnVision`).

## AVAudioConverter vs AudioConverter — why the codec leg is C

`-[AVAudioConverter convertToBuffer:error:withInputFromBlock:]` takes a
block that *returns* `AVAudioBuffer *`. A non-void block return is a shape
the bindgen mapper refuses (the purego callback trampoline cannot carry
it), so the ObjC route would have needed hand-written trampoline glue
anyway. `AudioConverterFillComplexBuffer`'s input proc is a plain C
function pointer whose arguments are all pointers — exactly what
`purego.NewCallback` handles — and the structs it needs (ASBD,
AudioBufferList, packet descriptions) lay out identically in Go on arm64.
Same codec underneath (both sit on the AudioCodec component). ~250 lines
of hand-written Go, no generator involvement, works on iOS unchanged
(AudioToolbox is ObjC-free C).

## Friction log (everything that did not work first try)

1. **`objc.GetClass("AVAudioFormat")` returned nil** — no framework
   loading in generated packages; fixed in the generator (above).
2. **`-[AVAudioEngine running]` unrecognized selector** — custom getter
   names ignored; fixed in the generator (above).
3. **The opus encoder defaults to 120 frames/packet (2.5ms)**:
   `AudioFormatGetProperty(FormatInfo)` fills the ASBD with
   `mFramesPerPacket = 120`, which yields 803 tiny packets for 2s and
   32.9 kbps of mostly packet overhead. Setting `mFramesPerPacket = 960`
   on the output ASBD before `AudioConverterNew` is accepted and gives
   the 20ms wire shape. Decode of the fixture's foreign 2880-frame
   packets likewise needed `mFramesPerPacket = 2880` on the *input* ASBD.
4. **Bitrate is advisory**: `kAudioConverterEncodeBitRate = 16000` was
   accepted but the tone encoded at ~23.6 kbps. No DTX knob surfaced.
   Fine for wata (uplink from phones is not the constrained hop), worth
   knowing if byte budgets ever matter.
5. **Codec latency shows as extra frames, not trimmed**: the round trip
   returns input+840 frames (priming + pre-skip are not swallowed by the
   converter at this API level) and the fixture decodes 120 frames short
   of its granule total. A player that cares can skip the first pre-skip
   samples; for PTT voice it is inaudible.
6. **`floatChannelData` is refused by the mapper** (`float * const *`,
   correctly): one 6-line hand-written accessor (raw selector send +
   `unsafe.Slice`) covers both render fill and tap read. If PCM buffers
   become a hot path in the real client, an `objcrt` helper is the home
   for it.
7. **vet flags `unsafe.Pointer(uintptr-expr)`** in the channel-data
   deref; `objc.Send[unsafe.Pointer]` instead of `[uintptr]` keeps the
   chain vet-clean.

## Numbers

| thing | value |
|-------|-------|
| encode, 2s mono @16k requested | 101 packets, 5906 B, ~23.6 kbps |
| packet sizes (960-frame, tone) | 22..74 B |
| round-trip tone purity | 0.9918 (Goertzel 440Hz power fraction) |
| foreign fixture decode | 25/25 packets, 71880/72000 frames, purity 0.9997 |
| render wall time | 1.533s for 1.498s of audio |
| capture (3s, 48kHz/2ch hw) | 139200 frames tapped, opus round trip OK |
| spike build | plain `go build`, no cgo, no Xcode at build time |

## Open questions (iOS-only, noted not chased)

- The same AudioToolbox + AVFAudio surface exists on iOS 11+, and the
  spike's code is iOS-clean (C API + generated bindings), but nothing has
  *executed* it there; the PTT hello on hardware (PTT-HELLO-HARDWARE) is
  the natural first executor. iOS adds `AVAudioSession`
  category/activation before the engine starts — one generated-binding
  call, not a design change.
- Voice-processing modes (`setVoiceProcessingEnabled:`, echo
  cancellation) were not exercised; they are on the same `AVAudioIONode`
  surface when wanted.

## Recommendation

**Pure bindings, no cgo fallback needed.** macOS/iOS audio architecture
for M3+: AudioConverter (C, purego — promoted from this spike into a real
package when the client needs it) for opus encode/decode; generated
`avfaudio` bindings for engine, render and capture; `go-pkgs/audio`
remains untouched as the linux/arm device backend. The only fallback
trigger would be an iOS-specific codec regression, and leg 1b's
foreign-encoder proof makes that unlikely to be a wata-side problem.
