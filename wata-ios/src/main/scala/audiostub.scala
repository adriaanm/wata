import language.experimental.saferExceptions

/** The audio thread, STUBBED OFF (plan 0044: the real iOS audio stack is the
 *  PTT leg's, hardware-gated). Same seam as wata-fb's audiothread.scala —
 *  `AudioThread.mainLoop` consuming the client's `AudioCmd` channel and
 *  answering on the `AudioEvt` channel — so the pump and the applets are
 *  unchanged; only the answers differ:
 *
 *    AcRecordStart -> AeRecordingError   (no microphone: the applet cancels
 *                                         the overlay and flashes MIC FAILED
 *                                         — honestly broken, not silently
 *                                         swallowed)
 *    AcPlay        -> AePlaybackError    (no speaker: the play error flash)
 *    everything else is drained and dropped; AcQuit ends the loop.
 *
 *  Draining matters even for the drops: the runtime's action loop sends into
 *  `audioCmds`, and a channel nobody reads would wedge it. */
object AudioThread:
  def mainLoop(cmds: sgo.Chan[AudioCmd], evts: sgo.Chan[AudioEvt], chirp: Boolean): Unit =
    var going = true
    while going do
      cmds.recv() match
        case _: AcQuit        => going = false
        case _: AcRecordStart => drop(evts.trySend(AeRecordingError()))
        case _: AcPlay        => drop(evts.trySend(AePlaybackError()))
        case _                => ()

  def drop(b: Boolean): Unit = ()
