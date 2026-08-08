/** the audio-thread MAILBOX PROTOCOL: the command/event vocabulary between
 *  the portable runtime and the device audio thread. The runtime's action
 *  loop pushes `AcPlay` after a media download, so the types live in the
 *  portable core; the thread that CONSUMES them (opus/tinyalsa) is the app
 *  device layer (`wata-fb/audiothread.scala`).
 *
 *  Command surface: start_recording / stop_recording / play / stop_playback /
 *  echo_test / quit. There is no volume command — volume rides inside the
 *  playback discipline via the pre-opened ctl.
 *
 *  DELIVERY SEMANTICS: both mailboxes are capacity-16 `Chan`s and every
 *  producer-side send is a `trySend` — drop-on-full. The consumer side: the
 *  thread main loop BLOCKS on `recv`; mid-record/mid-play stop-polls use
 *  `tryReceive`. */

// ---- commands (UI/runtime -> audio thread) -----------------------------------
sealed trait AudioCmd
case class AcRecordStart() extends AudioCmd
case class AcRecordStop() extends AudioCmd
/** play an Ogg/Opus buffer (the Matrix-receive path). */
case class AcPlay(ogg: Bytes) extends AudioCmd
case class AcStopPlayback() extends AudioCmd
/** record 2s + play back through the speaker (settings echo test). */
case class AcEchoTest() extends AudioCmd
/** exit the thread main loop; doubles as the poison pill — closing the
 *  channel would hand a nil interface to `match` (same pattern as
 *  `ActQuit` in the action loop). */
case class AcQuit() extends AudioCmd

// ---- events (audio thread -> UI) ----------------------------------------------
sealed trait AudioEvt
/** recording finished — Ogg/Opus bytes ready for upload. */
case class AeRecordingDone(ogg: Bytes, durationMs: Long) extends AudioEvt
case class AeRecordingError() extends AudioEvt
/** live capture level while recording: the peak absolute sample of one 40ms
 *  period, scaled 0..32. Posted once per period (25 Hz) from the record loop
 *  only — a dropped tick is harmless, and a loop that stops reading stops
 *  ticking, so the meter doubles as a liveness signal (plan 0042). */
case class AeCaptureLevel(level: Int) extends AudioEvt
case class AePlaybackDone() extends AudioEvt
case class AePlaybackError() extends AudioEvt
case class AeEchoRecording() extends AudioEvt
case class AeEchoPlaying() extends AudioEvt
case class AeEchoDone() extends AudioEvt
case class AeEchoError() extends AudioEvt
