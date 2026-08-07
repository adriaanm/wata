import language.experimental.saferExceptions

/** The startup bleep: a short walkie-talkie chirp the handset plays once its
 *  audio route is up, so a user holding a device with a ~40s boot can tell
 *  "ready" from "still booting" from "broken".
 *
 *  It is an Ogg/Opus file beside the binary (`/opt/wata/chirp.ogg`, built by
 *  `tools/make-chirp.py`), not bytes compiled into the source, so the asset
 *  stays reviewable data. It plays through the SAME path a voice message
 *  takes — `Ogg.readFrames` -> `audio.Decoder.decodeFrame` ->
 *  `audio.playMessage` — so there is no second audio path to keep working.
 *
 *  Everything here is best-effort: a missing or unreadable asset, a decode
 *  failure and a playback failure all print one line and leave the app
 *  running. A handset that cannot say hello is still a working handset.
 *
 *  `play()` is the whole surface, so a PTT or roger beep is a call site
 *  (send it from the audio thread, which owns the pcm device) rather than a
 *  redesign. */
object Chirp:

  /** overrides the asset path — one binary serves the device and a dev host,
   *  and the run-mode deploy puts the asset next to the binary in /dev/shm. */
  val ENV_PATH = "WATA_CHIRP"

  /** the device default: beside the installed binary. */
  val DefaultPath = "/opt/wata/chirp.ogg"

  def path(): String =
    var p = go.sys.getenv(ENV_PATH)
    if p == "" then p = DefaultPath
    p

  /** load, decode and play the chirp once. Never throws, never fatal. */
  def play(): Unit =
    val p = path()
    try
      val raw = go.sys.readFile(p)
      val n = playOgg(GoBytes.toPortable(raw))
      if n < 0 then println("chirp: " + p + ": played no frames")
    catch case e: sgo.GoError => println("chirp: " + p + ": " + e.message)

  /** decoder tier: closes the decoder on both edges (close-and-rethrow, the
   *  same hand-rolled exit-edge duplication the audio thread's tiers use). */
  def playOgg(ogg: Bytes): Int throws sgo.GoError =
    val dec = go.audio.newDecoder()
    var n = 0
    try
      val pcm = AudioThread.decodeAll(dec, ogg)
      val m = go.audio.playMessage(GoBytes.fromPortable(pcm), AudioThread.PlayVol)
      n = m
    catch
      case e: sgo.GoError =>
        dec.close()
        throw e
    dec.close()
    n
