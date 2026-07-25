import language.experimental.saferExceptions

/** `wata-fb --selftest echo|play|all`: spawn the REAL production audio thread
 *  and drive it through its public command mailbox — exactly how the app
 *  does — so a passing selftest means the production audio path works end
 *  to end.
 *
 *  Stages:
 *   echo: record 2s -> encode -> decode -> play (primary gate: hear your voice).
 *   play: synthesize a 440Hz Ogg/Opus buffer with the PRODUCTION encoder + Ogg
 *         writer, then decode-and-play it (the Matrix-receive path; the tone is
 *         easy to verify with a sound meter).
 *
 *  Prints `SELFTEST PASS` / `SELFTEST FAIL <stage>` (no os.Exit facade — the
 *  driving script greps for the PASS/FAIL line). */
object Selftest:

  def run(stageArg: String): Unit =
    val stage = if stageArg == "" then "all" else stageArg
    if stage != "all" && stage != "echo" && stage != "play" then
      println("selftest: want --selftest echo|play|all")
    else
      val clock = FbCaps.clock()
      var rc = 0
      sgo.supervised {
        val cmds = sgo.makeChan[AudioCmd](16)
        val evts = sgo.makeChan[AudioEvt](16)
        sgo.fork(AudioThread.mainLoop(cmds, evts))
        if stage == "all" || stage == "echo" then
          println("=== echo_test - record 2s, play back (SPEAK, then LISTEN) ===")
          cmds.send(AcEchoTest())
          if waitFor(clock, evts, 4, 25000L) then println("  echo_test completed")
          else rc = 1
        if (stage == "all" || stage == "play") && rc == 0 then
          println("=== play Ogg/Opus 440Hz tone (LISTEN for a beep) ===")
          var ogg = Bytes.empty
          try
            val o = synthOggTone(1500)
            ogg = o
          catch case e: sgo.GoError => rc = 1
          if rc == 0 then
            println("  synthesized " + ogg.size + " bytes of Ogg/Opus")
            cmds.send(AcPlay(ogg))
            if waitFor(clock, evts, 1, 15000L) then
              println("  playback_done; " + go.audio.playStats())
            else rc = 1
        cmds.send(AcQuit())
      }
      if rc == 0 then println("SELFTEST PASS")
      else println("SELFTEST FAIL")

  /** synthesize `durationMs` of 440Hz as Ogg/Opus via the PRODUCTION encoder +
   *  Ogg writer (byte-identical to an app recording). */
  def synthOggTone(durationMs: Int): Bytes throws sgo.GoError =
    val enc = go.audio.newEncoder()
    val nFrames = 48000 * durationMs / 1000 / 960
    val pcm = go.audio.tone(440, nFrames * 960)
    var frames: List[Bytes] = Nil
    var i = 0
    while i < nFrames do
      val f = enc.encodeFrameAt(pcm, i) // hoisted: a throwing call must be val-bound
      frames = GoBytes.toPortable(f) :: frames
      i += 1
    enc.close()
    Ogg.writeStream(ListOps.reverse(frames))

  // ---- event waiting ------------------------------------------------------------

  /** integer tags for AudioEvt (target/error comparison without an eq surface):
   *  1 playback_done, 2 playback_error, 3 recording_done, 4 echo_done,
   *  5 echo_recording, 6 echo_playing, 7 echo_error, 8 recording_error. */
  def evtTag(e: AudioEvt): Int = e match
    case _: AePlaybackDone   => 1
    case _: AePlaybackError  => 2
    case _: AeRecordingDone  => 3
    case _: AeEchoDone       => 4
    case _: AeEchoRecording  => 5
    case _: AeEchoPlaying    => 6
    case _: AeEchoError      => 7
    case _: AeRecordingError => 8

  def evtName(e: AudioEvt): String = e match
    case _: AePlaybackDone   => "playback_done"
    case _: AePlaybackError  => "playback_error"
    case _: AeRecordingDone  => "recording_done"
    case _: AeEchoDone       => "echo_done"
    case _: AeEchoRecording  => "echo_recording"
    case _: AeEchoPlaying    => "echo_playing"
    case _: AeEchoError      => "echo_error"
    case _: AeRecordingError => "recording_error"

  def isErrTag(t: Int): Boolean = t == 2 || t == 7 || t == 8

  /** poll the event mailbox until `target` (true) / an error tag or the
   *  deadline (false), echoing every event. */
  def waitFor(clock: Clock, evts: sgo.Chan[AudioEvt], target: Int, timeoutMs: Long): Boolean =
    val deadline = clock.nowUnixMillis() + timeoutMs
    var res = false
    var run = true
    while run do
      if clock.nowUnixMillis() >= deadline then run = false
      else
        evts.tryReceive() match
          case s: Some[AudioEvt] =>
            println("  [event] " + evtName(s.value))
            val t = evtTag(s.value)
            if t == target then
              res = true
              run = false
            else if isErrTag(t) then run = false
          case None => clock.sleepMs(10L)
    res
