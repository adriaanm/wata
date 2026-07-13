/** M8 chunk 6 — the audio-thread MAILBOX PROTOCOL, ported from fbclient
 *  `audio_thread.zig` (`Command`/`Event` unions). PORTABLE (zero `go`): the
 *  runtime's action loop pushes `AcPlay` after a media download, so the types
 *  live in the portable core; the thread that CONSUMES them (opus/tinyalsa) is
 *  the app device layer (`wata-fb/audiothread.scala`).
 *
 *  Surface matches the Zig `Command` union exactly (designer 2026-07-10: the
 *  brief's "SetVolume" was a drafting slip — the Zig union has none; volume
 *  rides inside the playback discipline via the pre-opened ctl):
 *    start_recording / stop_recording / play / stop_playback / echo_test / quit.
 *  Zig's `play.allocator` (ownership plumbing) dies — GC.
 *
 *  DELIVERY SEMANTICS (CONC-2, the pinned surface this chunk landed): both
 *  mailboxes are capacity-16 `Chan`s and every producer-side send is a
 *  `trySend` — drop-on-full, bit-for-bit the Zig `Mailbox.send -> false`
 *  contract (`_ = q.send(..)` discards the bool at every Zig call site). The
 *  consumer side: the thread main loop BLOCKS on `recv` (Zig futex receive);
 *  mid-record/mid-play stop-polls are `tryReceive` (Zig `tryReceive`). */

// ---- commands (UI/runtime -> audio thread) -----------------------------------
sealed trait AudioCmd
case class AcRecordStart() extends AudioCmd
case class AcRecordStop() extends AudioCmd
/** play an Ogg/Opus buffer (the Matrix-receive path). */
case class AcPlay(ogg: Bytes) extends AudioCmd
case class AcStopPlayback() extends AudioCmd
/** record 2s + play back through the speaker (settings echo test). */
case class AcEchoTest() extends AudioCmd
/** exit the thread main loop (the Zig `quit`; doubles as the poison pill —
 *  closing the channel would hand a nil interface to `match`, the chunk-4
 *  ActQuit precedent). */
case class AcQuit() extends AudioCmd

// ---- events (audio thread -> UI) ----------------------------------------------
sealed trait AudioEvt
/** recording finished — Ogg/Opus bytes ready for upload. */
case class AeRecordingDone(ogg: Bytes, durationMs: Long) extends AudioEvt
case class AeRecordingError() extends AudioEvt
case class AePlaybackDone() extends AudioEvt
case class AePlaybackError() extends AudioEvt
case class AeEchoRecording() extends AudioEvt
case class AeEchoPlaying() extends AudioEvt
case class AeEchoDone() extends AudioEvt
case class AeEchoError() extends AudioEvt
