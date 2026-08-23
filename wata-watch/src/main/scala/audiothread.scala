import language.experimental.saferExceptions

/** The AUDIO THREAD: background capture (PTT recording) and playback, driven
 *  by the portable `AudioCmd`/`AudioEvt` mailbox protocol
 *  (wataclient/audiocmd.scala) over capacity-16 `Chan`s. Every event send is
 *  `trySend` — drop-on-full; mid-record/mid-play stop-polls are `tryReceive`.
 *
 *  PLAYBACK is `go.audio.playMessage` — the verified discipline described in
 *  `go-pkgs/audio/audio.go` (patched tinyalsa, pre-opened vol ctl,
 *  start_threshold = ring, full-ring prime, 4-period chunks), not a naive
 *  one-period start — `aplay`'s own playback behavior is the reference this
 *  was tuned against, and this discipline is what fixes the stutter/replay
 *  bug the original device client shipped with.
 *
 *  SHAPE NOTE (the throws/try discipline this file settled into): the emitter
 *  val-binds a throwing call (a) as a DIRECT statement of a `try` block, or
 *  (b) at ANY depth inside a `throws`-declared function; a Unit-throwing call
 *  in statement position DROPS its error. So: loops with throwing calls live
 *  in `throws` functions with each call hoisted to a `val`; resource tiers
 *  close-and-rethrow in their catch (hand-rolled exit-edge duplication); the
 *  non-throws boundary catches once, PRINTS the cause (`audio: <surface>
 *  failed: <err>` — the catch is the only place the error text still
 *  exists; the event carries none), and turns failure into the error event.
 *
 *  DELIBERATE NON-PORTS of quirks in the original client this was ported
 *  from:
 *   - the original's record path encodes ONE 960-sample Opus frame per
 *     1920-frame period read, silently DROPPING the second half of every
 *     period (its own echo-test path did not have this bug). We encode both
 *     subframes.
 *   - the original's playback path TRUNCATES the tail to a whole period (up
 *     to 40ms lost); `playMessage` zero-pads instead.
 *   - quit-mid-record in the original leaves its main loop blocked until the
 *     queue is closed; here the session returns a quit code and the main
 *     loop exits directly (same observable behavior, no limbo).
 *
 *  Kept faithfully: `AcStopPlayback` is honored between DECODE steps only —
 *  once `playMessage` starts writing, the message plays out, matching the
 *  same uninterruptible-write shape the original client used. */
object AudioThread:

  /** ASM "Playback 0 Volume" — the measured/verified value for this hardware. */
  val PlayVol = 8192

  /** session result codes: 0 = completed, 2 = quit arrived mid-session. */
  val CodeDone = 0
  val CodeQuit = 2

  /** The thread body: block on the command mailbox, dispatch. Run as a `fork`
   *  in the app's supervised scope — the blocking `recv` is scope-aware, so a
   *  sibling failure cancels it.
   *
   *  `chirp` plays the startup bleep (chirp.scala) once the mixer is up and
   *  before the first command: the routes must exist for it to be audible,
   *  and this thread owns the pcm device, so playing it anywhere else would
   *  race the mixer setup. The app passes true; the diagnostic drivers
   *  (selftest, devcli) pass false — they are judged by the sounds they make
   *  on purpose. */
  def mainLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], chirp: Boolean): Unit =
    // both mixer routes ONCE at startup — per-recording switching crashed the
    // ADSP on this hardware.
    go.audio.setupMixer()
    if chirp then Chirp.play()
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
    case _: AcChime        => Chirp.play(); true
    case _: AcQuit         => false

  // ---- command polls during record/play ----------------------------------------

  /** 0 = continue, 1 = stop-recording, 2 = quit; other commands ignored. */
  def recCtl(cmds: sgo.Chan[AudioCmd]): Int =
    cmds.tryReceive() match
      case s: Some[AudioCmd] => recCtlOf(s.value)
      case None => 0

  def recCtlOf(c: AudioCmd): Int = c match
    case _: AcRecordStop => 1
    case _: AcQuit       => 2
    case _               => 0

  /** 0 = continue, 1 = stop-playback, 2 = quit; other commands ignored. */
  def playCtl(cmds: sgo.Chan[AudioCmd]): Int =
    cmds.tryReceive() match
      case s: Some[AudioCmd] => playCtlOf(s.value)
      case None => 0

  def playCtlOf(c: AudioCmd): Int = c match
    case _: AcStopPlayback => 1
    case _: AcQuit         => 2
    case _                 => 0

  // ---- recording (capture -> opus -> Ogg -> AeRecordingDone) ------------------

  /** the non-throws boundary: any GoError anywhere in the session becomes
   *  `AeRecordingError`. Returns keep-running (false = quit mid-record). */
  def doRecord(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt]): Boolean =
    var code = 0
    try
      val c = recordSession(cmds, evts)
      code = c
    catch
      case e: sgo.GoError =>
        println("audio: record failed: " + e.getMessage)
        code = -1
    if code == -1 then
      evts.trySend(AeRecordingError())
      ()
    code != CodeQuit

  /** capture tier: opens the pcm, closes it on BOTH edges (close-and-rethrow,
   *  a hand-rolled exit-edge duplication). */
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
   *  subframes encoded (see the module doc's "deliberate non-ports" note),
   *  frames accumulated portable-side for `Ogg.writeStream`. Emits
   *  `AeRecordingDone` itself on a normal stop; quit discards the
   *  recording. */
  def recordLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt],
                 cap: go.audio.Capture, enc: go.audio.Encoder): Int throws sgo.GoError =
    var frames: List[Bytes] = Nil
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
        // the recording meter's tick (plan 0042): the level is computed here,
        // between the read and the encode, because this is where the PCM
        // already is — no Go change, no facade change.
        evts.trySend(AeCaptureLevel(periodLevel(buf, n)))
        val f0 = enc.encodeFrameAt(buf, 0)
        val f1 = enc.encodeFrameAt(buf, 1)
        frames = GoBytes.toPortable(f0) :: frames
        frames = GoBytes.toPortable(f1) :: frames
        total = total + n.toLong
    if code == CodeDone then finishRecording(evts, frames, total)
    code

  /** the peak absolute sample of one captured period, scaled to 0..32 for the
   *  recording meter (`level = peak * 32 / 32767`; 32767 -> 32, 0 -> 0). `n`
   *  is FRAMES read; S16_LE mono, so the first `n * 2` bytes of `buf` are
   *  valid, little-endian, sign carried by the high byte. */
  def periodLevel(buf: go.Bytes, n: Int): Int =
    var peak = 0
    val valid = n * 2
    var i = 0
    while i + 1 < valid do
      val lo = buf(i).toInt & 0xff
      val hi = buf(i + 1).toInt // sign-extends: Byte -> Int
      val s = (hi << 8) | lo
      val a = if s < 0 then -s else s
      if a > peak then peak = a
      i = i + 2
    peak * 32 / 32767

  def finishRecording(evts: sgo.Chan[AudioEvt], frames: List[Bytes], totalSamples: Long): Unit =
    val ogg = Ogg.writeStream(ListOps.reverse(frames))
    val durMs = totalSamples * 1000L / 48000L
    // The capture's assertable surface: `send: complete` needs the network,
    // so without this line a wrist recording that uploads slowly (or never)
    // is indistinguishable in a pulled log from a mic that captured nothing.
    println("audio: recorded ms=" + durMs + " ogg=" + ogg.length)
    evts.trySend(AeRecordingDone(ogg, durMs))
    ()

  // ---- playback (Ogg -> opus decode -> playMessage) ---------------------------

  /** the non-throws boundary; returns keep-running (false = quit mid-play).
   *
   *  A DECODE FAILURE IS RETRIED ONCE, with a fresh decoder. The bytes are
   *  fully downloaded before they get here (`Runtime.execPlay` reads the whole
   *  media response and only then hands it over), so a decode that fails is
   *  not a truncated file — it is the codec refusing. On iOS that has been
   *  seen as `FillComplexBuffer(decode): '!con'` on a converter created
   *  moments earlier, during rapid-fire PushToTalk episodes, which is what a
   *  contended or torn-down codec resource looks like. A second attempt costs
   *  one decode pass of a message we are otherwise about to drop, and it makes
   *  the log say which kind of failure this is: healed on the retry means
   *  transient, failed twice means the media or the format is genuinely bad. */
  def doPlay(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], ogg: Bytes): Boolean =
    var code = tryPlay(cmds, evts, ogg, false)
    if code == -1 then code = tryPlay(cmds, evts, ogg, true)
    if code == -1 then
      evts.trySend(AePlaybackError())
      ()
    code != CodeQuit

  /** one decode+play attempt; -1 = the decoder tier threw. */
  def tryPlay(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], ogg: Bytes,
              isRetry: Boolean): Int =
    var code = 0
    try
      val c = playSession(cmds, evts, ogg)
      code = c
    catch
      case e: sgo.GoError =>
        println("audio: play failed" + (if isRetry then " on the retry too: " else ", retrying: ") +
          e.getMessage)
        code = -1
    code

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

  /** decode all frames (polling for stop/quit between frames), then hand the
   *  whole pcm to the verified-discipline playMessage. A stop mid-decode
   *  still plays the decoded prefix; quit plays nothing. */
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
          case Nil => going = false
    if code == CodeDone then playPcm(evts, pcmB.result())
    code

  /** playback boundary: `playMessage` failure becomes `AePlaybackError`. */
  def playPcm(evts: sgo.Chan[AudioEvt], pcm: Bytes): Unit =
    var ok = true
    try
      val n = go.audio.playMessage(GoBytes.fromPortable(pcm), PlayVol)
      ok = n >= 0
    catch
      case e: sgo.GoError =>
        println("audio: playback failed: " + e.getMessage)
        ok = false
    if ok then
      // The playback's assertable surface: the receipt AePlaybackDone
      // triggers is invisible in a log pulled off a wrist, and failures
      // already print — a run with neither line means playback never ran.
      println("audio: playback done pcm=" + pcm.length)
      evts.trySend(AePlaybackDone())
      ()
    else
      evts.trySend(AePlaybackError())
      ()

  // ---- echo test (record 2s -> Ogg -> decode -> play) --------------------------

  /** fixed 2s record then immediate playback; no command polling. */
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
    catch
      case e: sgo.GoError =>
        println("audio: echo record failed: " + e.getMessage)
        out = Bytes.empty
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

  /** 2 seconds = 96000 samples of periods, both subframes encoded. */
  def echoLoop(cap: go.audio.Capture, enc: go.audio.Encoder): Bytes throws sgo.GoError =
    var frames: List[Bytes] = Nil
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
    catch
      case e: sgo.GoError =>
        println("audio: echo play failed: " + e.getMessage)
        ok = false
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
        case Nil => going = false
    b.result()
