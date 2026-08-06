package go

import language.experimental.saferExceptions

/** `go.audio` — the APP-OWNED facade for the purego AudioToolbox + AVFAudio
 *  package `github.com/adriaanm/wata/go-pkgs/macaudio` (plan 0033). Its
 *  DECLARATIONS are identical to `wata-fb/src/main/scala/audio.scala`'s, which
 *  is what lets `audiothread.scala` be ONE file symlinked into both apps: the
 *  seam between the two backends is the `@go.bind` path on this object and
 *  nothing else. `just facade-check` enforces that identity — perturb a
 *  signature here and the gate says so, naming both files.
 *
 *  The comments differ deliberately: they describe the macOS realization
 *  (AVAudioEngine, AudioConverter) where wata-fb's describe tinyalsa and the
 *  Q6 ADSP. What must not differ is any declaration. */
@go.bind("github.com/adriaanm/wata/go-pkgs/macaudio")
object audio:
  // --- constants (Go `const`) ------------------------------------------------
  def SampleRate: scala.Int      = ??? // 48000 Hz — the wire's rate, and opus's
  def Channels: scala.Int        = ??? // 1 (mono)
  def FrameSize: scala.Int       = ??? // 2 bytes/sample (S16_LE)
  def FrameSamples: scala.Int    = ??? // 960 — Opus 20ms frame at 48kHz
  def MaxFrameByte: scala.Int    = ??? // 4000 — Opus max encoded frame
  def FramesPerPeriod: scala.Int = ??? // 1920 (40ms period)
  def PlaybackPeriods: scala.Int = ??? // 8 (320ms ring cushion)
  def StateRunning: scala.Int    = ??? // 0x03 — the device's PCM_STATE_RUNNING
  def PeriodBytes: scala.Int     = ??? // 3840 — one 40ms period (S16_LE mono)

  // --- opus encoder (48kHz mono VoIP, 16kbps) ------------------------------
  /** Opaque handle for `*macaudio.Encoder` (one AudioConverter). */
  final class Encoder private[go] ():
    /** Encode one 960-sample S16_LE frame (`pcm`) into `out`; returns the
     *  encoded byte count. `(int, error)` — rides the throws val-bind lowering. */
    @go.name("Encode") def encode(pcm: go.Bytes, out: go.Bytes): scala.Int throws sgo.GoError = ???
    /** encode the `frameIdx`-th 960-sample frame of `pcm`, returning an
     *  EXACT-SIZED packet — sub-slicing happens Go-side. */
    @go.name("EncodeFrameAt") def encodeFrameAt(pcm: go.Bytes, frameIdx: scala.Int): go.Bytes throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `macaudio.NewEncoder()` — `(*Encoder, error)`. */
  def newEncoder(): go.audio.Encoder throws sgo.GoError = ???

  // --- opus decoder (the play path) -----------------------------------------
  /** Opaque handle for `*macaudio.Decoder` (48kHz mono). */
  final class Decoder private[go] ():
    /** decode one Opus packet -> EXACT-SIZED pcm (1920 bytes for a full frame). */
    @go.name("DecodeFrame") def decodeFrame(data: go.Bytes): go.Bytes throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `macaudio.NewDecoder()` — `(*Decoder, error)`. */
  def newDecoder(): go.audio.Decoder throws sgo.GoError = ???

  // --- capture (the record path) ---------------------------------------------
  /** Opaque handle for `*macaudio.Capture`: an AVAudioEngine input tap (or,
   *  under `WATA_MAC_AUDIO=fake`, a clock-paced tone) feeding a ring. */
  final class Capture private[go] ():
    /** read one period (1920 frames = 40ms) into `buf` (>= PeriodBytes bytes);
     *  returns frames read. BLOCKS until the period is there. */
    @go.name("ReadFrames") def readFrames(buf: go.Bytes): scala.Int throws sgo.GoError = ???
    @go.name("Close") def close(): Unit = ???

  /** `macaudio.OpenCapture()` — `(*Capture, error)`. */
  def openCapture(): go.audio.Capture throws sgo.GoError = ???

  // --- mixer + the playback discipline ---------------------------------------
  /** build and start the shared AVAudioEngine — called ONCE at audio-thread
   *  start, the same contract the device's mixer routes keep. */
  def setupMixer(): Unit = ???

  /** play a whole decoded S16_LE mono buffer and block until it has been
   *  consumed, returning frames played. `vol` is the device's ASM scale
   *  (0..8192) mapped onto the mixer's 0..1 gain; `vol <= 0` leaves the gain
   *  alone. `(int, error)` so callers VAL-BIND it (a Unit-throwing statement
   *  would drop the error). */
  def playMessage(pcm: go.Bytes, vol: scala.Int): scala.Int throws sgo.GoError = ???

  /** the previous playMessage's pacing numbers (diagnostics line). */
  def playStats(): String = ???

  /** `macaudio.Tone(freqHz, samples)` — a pure-Go S16_LE mono tone buffer (no
   *  speaker); the skeleton feeds it to the encoder to prove the codec path. */
  def tone(freqHz: scala.Int, samples: scala.Int): go.Bytes = ???

  /** `macaudio.StateName(s)` — a PCM_STATE_* value rendered for logging. */
  def stateName(s: scala.Int): String = ???
