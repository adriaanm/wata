package go

import language.experimental.saferExceptions

/** `go.audio` — the APP-OWNED facade (M8 chunk 2) for the cgo opus + tinyalsa
 *  package `sgola.example/audio` (spike/go-pkgs/audio). Lives in the wata-fb
 *  module, NOT in core: the plugin/core carry zero app-tier knowledge — the
 *  binding is entirely `@go.bind`-annotation-driven (the import path rides the
 *  tree; the emitter never learns the name "audio"). The package itself rides
 *  the emitted app module as a plain Go dependency (require + local `replace`
 *  in go.mod, written by the sgo go.mod stage).
 *
 *  Bound surface (chunk 2 — the wata-fb skeleton's minimum): the constants, the
 *  `Encoder` (create/encode/close), the pure-Go `Tone` generator, and the PCM
 *  state names. `Decoder`, `Playback`/`Capture`/`VolCtl` (the tuned handles —
 *  `OpenPlaybackTuned`, `State`, …) are DEFERRED to chunk 6 (the device audio
 *  thread), which is where they are first exercised. */
@go.bind("sgola.example/audio")
object audio:
  // --- constants (Go `const`; mirror opus.zig + alsa.zig) -----------------
  def SampleRate: scala.Int      = ??? // 48000 Hz — the only rate the Q6 accepts
  def Channels: scala.Int        = ??? // 1 (mono)
  def FrameSize: scala.Int       = ??? // 2 bytes/sample (S16_LE)
  def FrameSamples: scala.Int    = ??? // 960 — Opus 20ms frame at 48kHz
  def MaxFrameByte: scala.Int    = ??? // 4000 — Opus max encoded frame
  def FramesPerPeriod: scala.Int = ??? // 1920 (40ms period)
  def PlaybackPeriods: scala.Int = ??? // 8 (320ms ring cushion)
  def StateRunning: scala.Int    = ??? // 0x03 — PCM_STATE_RUNNING
  def PeriodBytes: scala.Int     = ??? // 3840 — one 40ms period (S16_LE mono)

  // --- opus encoder (48kHz mono VoIP, 16kbps, DTX on) ---------------------
  /** Opaque cgo handle for `*audio.Encoder`. */
  final class Encoder private[go] ():
    /** Encode one 960-sample S16_LE frame (`pcm`) into `out`; returns the
     *  encoded byte count. `(int, error)` — rides the throws val-bind lowering. */
    @go.name("Encode") def encode(pcm: go.Bytes, out: go.Bytes): scala.Int throws sgo.GoError = ???
    /** M8 chunk 6: encode the `frameIdx`-th 960-sample frame of `pcm`, returning
     *  an EXACT-SIZED packet — sub-slicing happens Go-side (the go.Slice
     *  sub-slice gap never surfaces in Scala). */
    @go.name("EncodeFrameAt") def encodeFrameAt(pcm: go.Bytes, frameIdx: scala.Int): go.Bytes throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `audio.NewEncoder()` — `(*Encoder, error)`. */
  def newEncoder(): go.audio.Encoder throws sgo.GoError = ???

  // --- opus decoder (M8 chunk 6: the play path) ----------------------------
  /** Opaque cgo handle for `*audio.Decoder` (48kHz mono). */
  final class Decoder private[go] ():
    /** decode one Opus packet -> EXACT-SIZED pcm (1920 bytes for a full frame). */
    @go.name("DecodeFrame") def decodeFrame(data: go.Bytes): go.Bytes throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `audio.NewDecoder()` — `(*Decoder, error)`. */
  def newDecoder(): go.audio.Decoder throws sgo.GoError = ???

  // --- tinyalsa capture (M8 chunk 6: the record path) ----------------------
  /** Opaque cgo handle for `*audio.Capture` (PCM_IN hw:0,0, small ring). */
  final class Capture private[go] ():
    /** read one period (1920 frames = 40ms) into `buf` (>= PeriodBytes bytes);
     *  returns frames read. */
    @go.name("ReadFrames") def readFrames(buf: go.Bytes): scala.Int throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `audio.OpenCapture()` — `(*Capture, error)`. */
  def openCapture(): go.audio.Capture throws sgo.GoError = ???

  // --- mixer + the verified playback discipline (M8 chunk 6) ---------------
  /** one-time playback+capture mixer routes (alsa.zig setupMixer; per-recording
   *  switching caused ADSP crashes — call ONCE at audio-thread start). */
  def setupMixer(): Unit = ???

  /** play a whole decoded S16_LE mono buffer with THE chunk-1 verified
   *  discipline (pre-opened vol ctl, start_threshold=ring, full-ring prime,
   *  explicit start only for shorter-than-ring messages, ASM volume post-start,
   *  4-period chunks, drain). `vol <= 0` skips the ASM volume set. Returns
   *  frames played — `(int, error)` so callers VAL-BIND it (a Unit-throwing
   *  statement drops the error, the M4 chunk 7 write precedent). */
  def playMessage(pcm: go.Bytes, vol: scala.Int): scala.Int throws sgo.GoError = ???

  /** the previous playMessage's pacing numbers (diagnostics line). */
  def playStats(): String = ???

  /** `audio.Tone(freqHz, samples)` — a pure-Go S16_LE mono tone buffer (no
   *  speaker); the skeleton feeds it to the encoder to prove the cgo path. */
  def tone(freqHz: scala.Int, samples: scala.Int): go.Bytes = ???

  /** `audio.StateName(s)` — a PCM_STATE_* value rendered for logging. */
  def stateName(s: scala.Int): String = ???
