# 0033 — audio for wata-mac: one audio thread, two backends

Status: done

## The problem

`wata-mac` runs the device's own screens and input, but PTT does nothing
and an arriving voice message cannot be played: the mac client has no
audio backend, and the pump never drains either mailbox — not the
`AudioEvt` queue (so `AeRecordingDone` would never become an upload) and
not the runtime's `UiEvent` queue (so the send/play flash states and the
outbox `unsent`/`undelivered` markers are permanently empty). Queue item
`MAC-AUDIO`, named as the next step by plan 0032.

`tools/audio-spike/REPORT.md` already answered the hard question: Opus
encode/decode (AudioToolbox `kAudioFormatOpus` over purego), render and
capture (generated `avfaudio` bindings) all work with **no cgo**, against
wata's exact wire shape and against a foreign-encoder fixture. What is
missing is a package, a facade, and the two drains.

## The decision

**Give macOS an audio backend whose Go API is the same API
`go-pkgs/audio` presents, so `wata-fb/audiothread.scala` is SHARED by
symlink** — exactly the way `wata-mac` already shares the screens.

The audio thread is not a trivial file: the record/play sessions, the
stop/quit polling, the Ogg framing, the close-and-rethrow tiers and the
`throws`-discipline shape are all behavior we want identical on both
clients, and it is the file most likely to drift if forked. The
device-facing part of it is narrow — nine functions and a handful of
constants — and every one has a macOS answer. So the seam is placed at
the Go package, not at the Scala thread:

| | device | mac |
|---|---|---|
| Go package | `go-pkgs/audio` (cgo: opus + tinyalsa, linux/arm) | `go-pkgs/macaudio` (purego: AudioToolbox + AVFAudio) |
| Sgola facade | `wata-fb/src/main/scala/audio.scala` | `wata-mac/src/main/scala/audio.scala` (same `go.audio` object, different `@go.bind` path) |
| the thread | `wata-fb/src/main/scala/audiothread.scala` | the SAME file, symlinked |

The two facade files are declaration-identical by construction, and a
gate check enforces it (below) — that check is the price of the symlink
and it is what keeps "one audio thread" true rather than aspirational.

**Rejected: a mac-specific audio thread.** It is less code today and a
guaranteed divergence tomorrow; the device's recording discipline (both
subframes encoded, the tail not truncated, stop honored between decode
steps) is hard-won behavior documented in that file's header, and a
second copy would relitigate it silently.

**Rejected: an `AudioBackend` interface in Sgola.** A trait over the two
backends would push the choice into the portable core, which is exactly
where app-tier knowledge does not belong (`audio.scala`'s header states
the rule). The binding annotation is already the polymorphism.

### What `go-pkgs/macaudio` is

Plain Go, no cgo, promoted from the spike's proven code (`atbx.go` is the
AudioConverter driver; `engine.go` the AVAudioEngine legs; `ogg.go` is
NOT promoted — Ogg framing is portable and already lives in wataclient).
Its exported surface mirrors `go-pkgs/audio`'s member for member:

- Constants: `SampleRate`, `Channels`, `FrameSize`, `FrameSamples`,
  `MaxFrameByte`, `FramesPerPeriod`, `PeriodBytes`, `PlaybackPeriods`,
  `StateRunning` — the same values; they describe the wire, not the
  hardware.
- `NewEncoder() (*Encoder, error)`, `(*Encoder) EncodeFrameAt(pcm []byte,
  frameIdx int) ([]byte, error)`, `Encode(pcm, out []byte) (int, error)`,
  `Close()`. One AudioConverter per encoder, `mFramesPerPacket = 960` set
  on the output ASBD before `AudioConverterNew` (spike friction #3), one
  packet out per call.
- `NewDecoder() (*Decoder, error)`, `(*Decoder) DecodeFrame(data []byte)
  ([]byte, error)`, `Close()`. The input ASBD's `mFramesPerPacket` is
  **not** knowable per packet from the wire, so the decoder sizes for the
  120ms maximum and reports the true frame count the converter returns —
  the same reasoning `go-pkgs/audio`'s `MaxDecodeSamples` comment
  records; the foreign 2880-frame fixture is the test that pins it.
- `OpenCapture() (*Capture, error)`, `(*Capture) ReadFrames(buf []byte)
  (int, error)`, `Close()`. An AVAudioEngine input tap writes into a ring
  buffer; `ReadFrames` BLOCKS until one 1920-frame period is available,
  which is the contract `recordLoop` is written against. The tap's
  hardware format is whatever the device gives (the spike saw 48kHz/2ch),
  so the capture path downmixes to mono and converts to S16_LE before the
  ring — a `Capture` always yields the device's format.
- `SetupMixer()` — on mac this is engine construction + `start`, and it
  keeps the device contract of being called exactly once at thread start.
- `PlayMessage(pcm []byte, vol int) (int, error)` — schedule the whole
  buffer on an `AVAudioPlayerNode` and block until it has been consumed,
  returning frames played. `vol` is the device's ASM scale (0..8192)
  mapped to the mixer's 0..1 gain; `vol <= 0` leaves the gain alone,
  matching the facade's documented meaning.
- `PlayStats() string`, `Tone(freqHz, samples int) []byte`,
  `StateName(s int) string` — diagnostics; `Tone` is portable Go, copied.

**The fake backend.** `WATA_MAC_AUDIO=fake` (read once at `SetupMixer`)
replaces only the two HARDWARE ends: capture yields a generated tone in
real time instead of a mic tap, and playback consumes the buffer against
a clock instead of a speaker. The codec, the framing, the mailbox
protocol and every UI state stay real. This is what makes an unattended
gate possible — a mic tap needs a TCC grant the CI-shaped run does not
have, and a speaker in a test suite is a nuisance. Anything else faking
audio would be testing the fake.

### The pump's two drains (`wata-mac/src/main/scala/main.scala`)

1. **`AudioEvt`, once per frame, before the applet tick** — the single
   drain plan 0009 mandates (two drains on one channel eat each other's
   events). `WataLogic.onAudioEvent(st.wata, e, ctx)` is the router;
   wata-mac has no settings applet driving the echo test, so echo events
   are dropped by the same predicate `Shell.isEchoEvt` names.
2. **`UiEvent`, once per frame** — `Runtime.pollEvent(h.client)`, with
   `EvSendComplete`/`EvSendFailed` → `WataLogic.notifySend`,
   `EvPlaybackError` → `WataLogic.notifyPlayError`, `EvOutbox` → the
   pump's `unsent`/`undelivered`, which then reach `FrameCtx` and
   `WataLogic.body` instead of today's hard-coded `Nil`. `EvConn` is
   already covered by `h.connection()`; the mac has no LEDs and no
   config store, so it needs nothing else from it.

Both drains live in `frame`, so the windowed and headless drivers get
them identically. The audio thread is forked into the client's scope the
way `wata-fb` does it, and the client is built with
`Runtime.makeWithAudio` (not `ClientHandle.start`'s headless
constructor) so the action loop's `AcPlay` reaches the thread —
`ClientHandle.startClient` takes the client the caller built, which is
exactly the seam plan 0025 left for this.

## What changes

- **new** `go-pkgs/macaudio/` — the package above, plus its Go tests.
- **new** `wata-mac/src/main/scala/audio.scala` — the `go.audio` facade
  bound to `macaudio`.
- **new symlink** `wata-mac/src/main/scala/audiothread.scala` →
  `../../../../wata-fb/src/main/scala/audiothread.scala`.
- `wata-mac/sgo.build` — a `godep` for `go-pkgs/macaudio` (and, because a
  godep's own `replace` lines do not reach the app, godeps for whatever
  local siblings it requires — the appleptt lesson from plan 0032).
- `wata-mac/src/main/scala/main.scala` — the client construction, the
  audio-thread fork, the two drains, `FrameCtx`'s real outbox lists.
- `tools/mac-smoke.py` — the audio leg (below).
- **new** `tools/facade-check.py` + `just facade-check` — the
  declaration-identity check between the two `audio.scala` files.
- `justfile` — `just macaudio-tests`, wired into `just nativeui-tests`'s
  neighbourhood (macOS-only, not in ci); `just facade-check` into `ci`
  (it is pure text, it runs anywhere).
- `docs/design/wata-mac.md` — an audio section; the "no audio yet" and
  "does not drain UiEvent" statements come out.

## How it is verified

- **`just macaudio-tests`** (macOS-only): encode 2s of tone → 101±2
  packets, decode back, Goertzel purity > 0.99 (the spike's own numbers,
  now a standing assertion); the repo fixture
  `go-pkgs/audio/testdata/tui-foreign.ogg` (a *foreign* encoder at a
  *foreign* frame size) decodes to its promised frame count and purity;
  fake capture delivers exactly `PeriodBytes` per `ReadFrames` and blocks
  rather than spinning; fake playback returns the frame count it was
  given. The real mic/speaker legs stay in `just audio-spike`, which is
  where an attended check belongs.
- **`just mac-smoke`** gains an audio leg, unattended under
  `WATA_MAC_AUDIO=fake`: alice holds PTT (`key space press`, `wait`,
  `key space release`), the native tree shows the recording overlay
  while held, and after release bob's tui session sees a voice message
  arrive — a real recording, really encoded, really uploaded, really
  synced. Then the reverse: bob sends the fixture, alice opens the
  conversation and presses OK, and the printed differ script shows the
  playing state and then the played badge — which is also the first
  end-to-end proof of the `UiEvent` drain.
- **`just facade-check`** — the two facades' declarations are identical.
- The owner's leg: `just mac` against a live server, actually talk to a
  handset and listen to what comes back.

## Out of scope

- iOS. The package is written to be iOS-clean (AudioToolbox is C;
  `avfaudio` exists there) but nothing here executes it —
  `IOS-CLIENT-ASSEMBLY` and `PTT-HELLO-HARDWARE` own that, and iOS adds
  an `AVAudioSession` activation this plan does not model.
- Voice processing / echo cancellation (`setVoiceProcessingEnabled:`).
  Same `AVAudioIONode` surface, wanted later, not needed to talk.
- The settings applet's echo test on mac: it compiles in, nothing drives
  it, and `AcEchoTest` therefore never reaches the thread.
- Device-picking UI. The system default input and output are what wata
  uses; a mac with the wrong default is a mac problem.
