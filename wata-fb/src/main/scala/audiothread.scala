import language.experimental.saferExceptions

/** M8 chunk 6 — the AUDIO THREAD, ported from fbclient `audio_thread.zig`:
 *  background capture (PTT recording) and playback, driven by the portable
 *  `AudioCmd`/`AudioEvt` mailbox protocol (wataclient/audiocmd.scala) over
 *  capacity-16 `Chan`s. Every event send is `trySend` — drop-on-full, the Zig
 *  `Mailbox.send -> bool`-discard contract; mid-record/mid-play stop-polls are
 *  `tryReceive` (the Zig `tryReceive`) — this file is the canonical consumer
 *  the CONC-2 surface additions were pinned FOR.
 *
 *  PLAYBACK is `go.audio.playMessage` = THE chunk-1 verified discipline
 *  (patched tinyalsa, pre-opened vol ctl, start_threshold = ring, full-ring
 *  prime, 4-period chunks) — NOT alsa.zig's one-period start (decision 3;
 *  aplay parity is the reference, the Zig client's playback bug dies with it).
 *
 *  SHAPE NOTE (the throws/try discipline this port settled into): the emitter
 *  val-binds a throwing call (a) as a DIRECT statement of a `try` block, or
 *  (b) at ANY depth inside a `throws`-declared function; a Unit-throwing call
 *  in statement position DROPS its error (M4 ch.7 write precedent). So: loops
 *  with throwing calls live in `throws` functions with each call hoisted to a
 *  `val`; resource tiers close-and-rethrow in their catch (hand-rolled
 *  exit-edge duplication — exactly ERR-5's pending lowering); the non-throws
 *  boundary catches once and turns failure into the error event.
 *
 *  DELIBERATE ZIG-BUG NON-PORTS (recorded in the chunk report):
 *   - Zig `doRecord` encodes ONE 960-sample Opus frame per 1920-frame period
 *     read, silently DROPPING the second half of every period (its own
 *     `doEchoTest` encodes both halves). We encode both subframes.
 *   - Zig `doPlayback` TRUNCATES the tail to a whole period (up to 40ms lost);
 *     `playMessage` zero-pads instead.
 *   - quit-mid-record in Zig returns from `doRecord` leaving the main loop
 *     blocked until the queue is closed; here the session returns a quit code
 *     and the main loop exits directly (same observable behavior, no limbo).
 *
 *  Matching Zig granularity, kept: `AcStopPlayback` is honored between DECODE
 *  steps only — once `playMessage` starts writing, the message plays out
 *  (Zig's single `writeFrames` sequence is equally uninterruptible). */
object AudioThread:

  /** ASM "Playback 0 Volume" — the audio_experiments.md verified value. */
  val PlayVol = 8192

  /** session result codes: 0 = completed, 2 = quit arrived mid-session. */
  val CodeDone = 0
  val CodeQuit = 2

  /** The thread body: block on the command mailbox, dispatch (Zig
   *  `audioThreadMain`). Run as a `fork` in the app's supervised scope — the
   *  blocking `recv` is scope-aware, so a sibling failure cancels it. */
  def mainLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt]): Unit =
    // both mixer routes ONCE at startup — per-recording switching crashed the
    // ADSP (Zig 5a64830; audio_experiments.md ADSP-churn warning).
    go.audio.setupMixer()
    var run = true
    while run do
      run = dispatch(cmds, evts, cmds.recv())

  /** false only for AcQuit (or quit-mid-session) — everything else loops. */
  def dispatch(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], cmd: AudioCmd): Boolean = cmd match
    case _: AcRecordStart  => doRecord(cmds, evts)
    case _: AcRecordStop   => true // handled inside doRecord
    case p: AcPlay         => doPlay(cmds, evts, p.ogg)
    case _: AcStopPlayback => true // handled inside doPlay
    case _: AcEchoTest     => doEcho(evts)
    case _: AcQuit         => false

  // ---- command polls during record/play (Zig cmd_queue.tryReceive()) ---------

  /** 0 = continue, 1 = stop-recording, 2 = quit; other commands ignored (Zig). */
  def recCtl(cmds: sgo.Chan[AudioCmd]): Int =
    cmds.tryReceive() match
      case s: Some[AudioCmd] => recCtlOf(s.v)
      case _: None[AudioCmd] => 0

  def recCtlOf(c: AudioCmd): Int = c match
    case _: AcRecordStop => 1
    case _: AcQuit       => 2
    case _               => 0

  /** 0 = continue, 1 = stop-playback, 2 = quit; other commands ignored (Zig). */
  def playCtl(cmds: sgo.Chan[AudioCmd]): Int =
    cmds.tryReceive() match
      case s: Some[AudioCmd] => playCtlOf(s.v)
      case _: None[AudioCmd] => 0

  def playCtlOf(c: AudioCmd): Int = c match
    case _: AcStopPlayback => 1
    case _: AcQuit         => 2
    case _                 => 0

  // ---- recording (Zig doRecord; capture -> opus -> Ogg -> AeRecordingDone) ----

  /** the non-throws boundary: any GoError anywhere in the session becomes
   *  `AeRecordingError`. Returns keep-running (false = quit mid-record). */
  def doRecord(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt]): Boolean =
    var code = 0
    try
      val c = recordSession(cmds, evts)
      code = c
    catch case e: sgo.GoError => code = -1
    if code == -1 then
      evts.trySend(AeRecordingError())
      ()
    code != CodeQuit

  /** capture tier: opens the pcm, closes it on BOTH edges (close-and-rethrow —
   *  the hand-rolled ERR-5 exit-edge duplication). */
  def recordSession(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt]): Int throws sgo.GoError =
    val cap = go.audio.openCapture()
    var code = 0
    try
      val c = recordEnc(cmds, evts, cap)
      code = c
    catch
      case e: sgo.GoError =>
        cap.close()
        throw e
    cap.close()
    code

  /** encoder tier: same close-and-rethrow shape. */
  def recordEnc(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt],
                cap: go.audio.Capture): Int throws sgo.GoError =
    val enc = go.audio.newEncoder()
    var code = 0
    try
      val c = recordLoop(cmds, evts, cap, enc)
      code = c
    catch
      case e: sgo.GoError =>
        enc.close()
        throw e
    enc.close()
    code

  /** the capture loop: one 1920-frame period per read, BOTH 960-sample
   *  subframes encoded (the Zig half-period-drop non-port), frames accumulated
   *  portable-side for `Ogg.writeStream`. Emits `AeRecordingDone` itself on a
   *  normal stop; quit discards the recording (Zig `writer.deinit`). */
  def recordLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt],
                 cap: go.audio.Capture, enc: go.audio.Encoder): Int throws sgo.GoError =
    var frames: List[Bytes] = Nil[Bytes]()
    var total = 0L
    var code = 0
    val buf = go.makeSlice[Byte](go.audio.PeriodBytes)
    var going = true
    while going do
      val ctl = recCtl(cmds)
      if ctl == 1 then going = false
      else if ctl == 2 then
        code = CodeQuit
        going = false
      else
        val n = cap.readFrames(buf)
        val f0 = enc.encodeFrameAt(buf, 0)
        val f1 = enc.encodeFrameAt(buf, 1)
        frames = GoBytes.toPortable(f0) :: frames
        frames = GoBytes.toPortable(f1) :: frames
        total = total + n.toLong
    if code == CodeDone then finishRecording(evts, frames, total)
    code

  def finishRecording(evts: sgo.Chan[AudioEvt], frames: List[Bytes], totalSamples: Long): Unit =
    val ogg = Ogg.writeStream(ListOps.reverse(frames))
    val durMs = totalSamples * 1000L / 48000L
    evts.trySend(AeRecordingDone(ogg, durMs))
    ()

  // ---- playback (Zig doPlayback; Ogg -> opus decode -> playMessage) -----------

  /** the non-throws boundary; returns keep-running (false = quit mid-play). */
  def doPlay(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], ogg: Bytes): Boolean =
    var code = 0
    try
      val c = playSession(cmds, evts, ogg)
      code = c
    catch case e: sgo.GoError => code = -1
    if code == -1 then
      evts.trySend(AePlaybackError())
      ()
    code != CodeQuit

  /** decoder tier: close-and-rethrow. */
  def playSession(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt],
                  ogg: Bytes): Int throws sgo.GoError =
    val dec = go.audio.newDecoder()
    var code = 0
    try
      val c = playDec(cmds, evts, ogg, dec)
      code = c
    catch
      case e: sgo.GoError =>
        dec.close()
        throw e
    dec.close()
    code

  /** decode all frames (polling for stop/quit between frames, the Zig decode
   *  loop), then hand the whole pcm to the verified-discipline playMessage.
   *  A stop mid-decode still plays the decoded prefix (Zig `.stop_playback =>
   *  break` then writes what it has); quit plays nothing. */
  def playDec(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt],
              ogg: Bytes, dec: go.audio.Decoder): Int throws sgo.GoError =
    val pcmB = new BytesBuilder
    var code = 0
    var cur = Ogg.readFrames(ogg)
    var going = true
    while going do
      val ctl = playCtl(cmds)
      if ctl == 1 then going = false
      else if ctl == 2 then
        code = CodeQuit
        going = false
      else
        cur match
          case h :: t =>
            val pcm = dec.decodeFrame(GoBytes.fromPortable(h))
            pcmB.addBytes(GoBytes.toPortable(pcm))
            cur = t
          case Nil() => going = false
    if code == CodeDone then playPcm(evts, pcmB.result())
    code

  /** playback boundary: `playMessage` failure becomes `AePlaybackError`. */
  def playPcm(evts: sgo.Chan[AudioEvt], pcm: Bytes): Unit =
    var ok = true
    try
      val n = go.audio.playMessage(GoBytes.fromPortable(pcm), PlayVol)
      ok = n >= 0
    catch case e: sgo.GoError => ok = false
    if ok then
      evts.trySend(AePlaybackDone())
      ()
    else
      evts.trySend(AePlaybackError())
      ()

  // ---- echo test (Zig doEchoTest: record 2s -> Ogg -> decode -> play) ---------

  /** fixed 2s record then immediate playback; no command polling (Zig). */
  def doEcho(evts: sgo.Chan[AudioEvt]): Boolean =
    evts.trySend(AeEchoRecording())
    val ogg = echoCapture()
    if ogg.size == 0 then
      evts.trySend(AeEchoError())
      ()
    else
      evts.trySend(AeEchoPlaying())
      echoPlay(evts, ogg)
    true

  /** non-throws boundary of the echo RECORD half; empty Bytes = failure. */
  def echoCapture(): Bytes =
    var out = Bytes.empty
    try
      val b = echoSession()
      out = b
    catch case e: sgo.GoError => out = Bytes.empty
    out

  def echoSession(): Bytes throws sgo.GoError =
    val cap = go.audio.openCapture()
    var out = Bytes.empty
    try
      val b = echoEnc(cap)
      out = b
    catch
      case e: sgo.GoError =>
        cap.close()
        throw e
    cap.close()
    out

  def echoEnc(cap: go.audio.Capture): Bytes throws sgo.GoError =
    val enc = go.audio.newEncoder()
    var out = Bytes.empty
    try
      val b = echoLoop(cap, enc)
      out = b
    catch
      case e: sgo.GoError =>
        enc.close()
        throw e
    enc.close()
    out

  /** 2 seconds = 96000 samples of periods, both subframes encoded (Zig
   *  doEchoTest's own shape). */
  def echoLoop(cap: go.audio.Capture, enc: go.audio.Encoder): Bytes throws sgo.GoError =
    var frames: List[Bytes] = Nil[Bytes]()
    var total = 0L
    val buf = go.makeSlice[Byte](go.audio.PeriodBytes)
    while total < 96000L do
      val n = cap.readFrames(buf)
      val f0 = enc.encodeFrameAt(buf, 0)
      val f1 = enc.encodeFrameAt(buf, 1)
      frames = GoBytes.toPortable(f0) :: frames
      frames = GoBytes.toPortable(f1) :: frames
      total = total + n.toLong
    Ogg.writeStream(ListOps.reverse(frames))

  /** non-throws boundary of the echo PLAY half. */
  def echoPlay(evts: sgo.Chan[AudioEvt], ogg: Bytes): Unit =
    var ok = true
    try
      val n = echoPlaySession(ogg)
      ok = n >= 0
    catch case e: sgo.GoError => ok = false
    if ok then
      evts.trySend(AeEchoDone())
      ()
    else
      evts.trySend(AeEchoError())
      ()

  def echoPlaySession(ogg: Bytes): Int throws sgo.GoError =
    val dec = go.audio.newDecoder()
    var n = 0
    try
      val pcm = decodeAll(dec, ogg)
      val m = go.audio.playMessage(GoBytes.fromPortable(pcm), PlayVol)
      n = m
    catch
      case e: sgo.GoError =>
        dec.close()
        throw e
    dec.close()
    n

  /** decode every Ogg frame into one contiguous pcm (no polling — echo path). */
  def decodeAll(dec: go.audio.Decoder, ogg: Bytes): Bytes throws sgo.GoError =
    val b = new BytesBuilder
    var cur = Ogg.readFrames(ogg)
    var going = true
    while going do
      cur match
        case h :: t =>
          val pcm = dec.decodeFrame(GoBytes.fromPortable(h))
          b.addBytes(GoBytes.toPortable(pcm))
          cur = t
        case Nil() => going = false
    b.result()
