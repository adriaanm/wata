import language.experimental.saferExceptions

/** The ON-DEVICE SHELL DRIVERS ("scripted actions, no UI"): the sync runtime
 *  + the audio thread wired together against a live wata-server. Every
 *  subcommand prints a greppable PASS/FAIL line, matching `integ.scala`.
 *
 *    wata-fb login     <base> <user> <pass>                 provision/log in a user
 *    wata-fb voicesend <base> <user> <pass> <contact> <sec> record -> upload ->
 *                                                           m.audio (the PTT send half)
 *    wata-fb voiceplay <base> <user> <pass>                 sync -> newest voice msg ->
 *                                                           download -> decode -> SPEAKER
 *    wata-fb audiosoak <base> <user> <pass> <peer> <sec>    the GC-load re-check:
 *                                                           record/send/sync/download/play
 *                                                           cycles with the sync loop LIVE
 *                                                           (run under GODEBUG=gctrace=1)
 */
object DevCli:

  def run(args: Array[String]): Unit =
    if args(0) == "login" && args.length >= 4 then login(args)
    else if args(0) == "voicesend" && args.length >= 6 then voicesend(args)
    else if args(0) == "voiceplay" && args.length >= 4 then voiceplay(args)
    else if args(0) == "audiosoak" && args.length >= 6 then audiosoak(args)
    else println("devcli: bad args (see devcli.scala header)")

  def cfg(base: String, user: String, pass: String): ClientConfig =
    ClientConfig(base, user, pass, 5000, Session("", "", "", "", ""))

  // ---- login: provision a user on the trusted server (auto-created at login) ---

  def login(args: Array[String]): Unit =
    val c = Runtime.make(cfg(args(1), args(2), args(3)), FbCaps.httpDo(), FbCaps.clock())
    // result rides OUT of the scope as supervised's value (only Int vars have a
    // captured-ref lowering — the same pattern integ.scala's phaseCfg uses).
    val ok = sgo.supervised {
      Runtime.start(c)
      val r = Runtime.waitForConnection(c, Syncing(), 15000L)
      Runtime.stopClient(c)
      r
    }
    if ok then println("LOGIN PASS " + args(2)) else println("LOGIN FAIL " + args(2))

  // ---- voicesend: the PTT send half, record -> upload -> m.audio ---------------

  def voicesend(args: Array[String]): Unit =
    val secs = atoiOr(args(5), 2)
    val contact = args(4)
    val clock = FbCaps.clock()
    val c = Runtime.makeWithAudio(cfg(args(1), args(2), args(3)), FbCaps.httpDo(), clock)
    val ok = sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      Runtime.start(c)
      var r = false
      if Runtime.waitForConnection(c, Syncing(), 15000L) then
        println("voicesend: recording " + secs + "s - SPEAK NOW")
        c.audioCmds.send(AcRecordStart())
        clock.sleepMs(secs.toLong * 1000L)
        c.audioCmds.send(AcRecordStop())
        val ogg = waitRecording(clock, evts, 10000L)
        if ogg.size > 0 then
          println("voicesend: recorded " + ogg.size + " bytes, " + recDur + "ms; uploading to DM with " + contact)
          Runtime.sendAction(c, ActSendVoice("", contact, ogg, recDur))
          r = waitSend(clock, c, 20000L)
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
      r
    }
    if ok then println("VOICESEND PASS") else println("VOICESEND FAIL")

  // ---- voiceplay: sync -> newest voice message -> download -> speaker ----------

  def voiceplay(args: Array[String]): Unit =
    val clock = FbCaps.clock()
    val c = Runtime.makeWithAudio(cfg(args(1), args(2), args(3)), FbCaps.httpDo(), clock)
    val ok = sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      Runtime.start(c)
      var r = false
      if Runtime.waitForConnection(c, Syncing(), 15000L)
          && Runtime.waitForSnapshot(c, s => anyMsg(s), 20000L) then
        val mxc = firstMxc(Runtime.lastSnap.conversations)
        println("voiceplay: playing " + mxc + " - LISTEN")
        Runtime.sendAction(c, ActPlay(mxc))
        r = waitPlay(clock, evts, 30000L)
        if r then println("voiceplay: " + go.audio.playStats())
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
      r
    }
    if ok then println("VOICEPLAY PASS") else println("VOICEPLAY FAIL")

  // ---- audiosoak: the REAL record/play workload under the live sync loop -------
  // (a synthetic tone-playback soak is not representative; this is the true
  //  workload: capture -> opus -> Ogg -> upload -> /sync echo -> download ->
  //  decode -> verified-discipline playback, repeated, with the long-poll sync
  //  loop running throughout. GC numbers ride GODEBUG=gctrace=1.)

  def audiosoak(args: Array[String]): Unit =
    val soakSecs = atoiOr(args(5), 300)
    val peer = args(4)
    val clock = FbCaps.clock()
    val c = Runtime.makeWithAudio(cfg(args(1), args(2), args(3)), FbCaps.httpDo(), clock)
    var cycles = 0
    var playOk = 0
    var playFail = 0
    var sendFail = 0
    var recFail = 0
    sgo.supervised {
      val evts = sgo.makeChan[AudioEvt](16)
      // hoist the command Chan out of the fork — the body needs only the
      // channel (a synchronizer), not the whole client record.
      val audioCmds = c.audioCmds
      sgo.fork(AudioThread.mainLoop(audioCmds, evts, false))
      Runtime.start(c)
      if Runtime.waitForConnection(c, Syncing(), 15000L) then
        val deadline = clock.nowUnixMillis() + soakSecs.toLong * 1000L
        var run = true
        while run do
          if clock.nowUnixMillis() >= deadline then run = false
          else
            cycles += 1
            // 1. record 2s from the real mic
            c.audioCmds.send(AcRecordStart())
            clock.sleepMs(2000L)
            c.audioCmds.send(AcRecordStop())
            val ogg = waitRecording(clock, evts, 10000L)
            if ogg.size == 0 then recFail += 1
            else
              // 2. upload + m.audio into the DM with the peer
              Runtime.sendAction(c, ActSendVoice("", peer, ogg, recDur))
              if !waitSend(clock, c, 20000L) then sendFail += 1
              else
                // 3. the message echoes back through the live sync loop
                if Runtime.waitForSnapshot(c, s => anyMsg(s), 20000L) then
                  // 4. download -> decode -> verified-discipline playback
                  Runtime.sendAction(c, ActPlay(firstMxc(Runtime.lastSnap.conversations)))
                  if waitPlay(clock, evts, 30000L) then
                    playOk += 1
                    println("soak cycle " + cycles + ": " + go.audio.playStats() + "; rss " + rssLine())
                  else playFail += 1
                else sendFail += 1
      c.audioCmds.send(AcQuit())
      Runtime.stopClient(c)
    }
    println("SOAK RESULT cycles=" + cycles + " playOk=" + playOk + " playFail=" + playFail
      + " sendFail=" + sendFail + " recFail=" + recFail + " rss=" + rssLine())
    if playFail == 0 && recFail == 0 && playOk > 0 then println("AUDIOSOAK PASS")
    else println("AUDIOSOAK FAIL")

  /** VmRSS line from /proc/self/status ("" off-linux — diagnostics only). */
  def rssLine(): String =
    var out = ""
    try
      val raw = go.sys.readFile("/proc/self/status")
      out = pickRss(go.string(raw))
    catch case e: sgo.GoError => out = ""
    out

  def pickRss(s: String): String =
    val i = s.indexOf("VmRSS")
    if i < 0 then ""
    else
      val rest = s.substring(i, s.length)
      val j = rest.indexOf("\n")
      if j < 0 then rest else rest.substring(0, j)

  // ---- event/action waits (module out-cells for the recording payload) --------

  // `val`-held Atomic cells — recDur rides the native atomic word; the
  // recorded Ogg swaps immutable `Bytes` snapshots through the cell.
  private val recDurC: sgo.Atomic[Long] = sgo.atomic(0L)
  def recDur: Long = recDurC.get()

  private val recOggC: sgo.Atomic[Bytes] = sgo.atomic(Bytes.empty)
  def stashRec(d: AeRecordingDone): Int =
    recOggC.set(d.ogg)
    recDurC.set(d.durationMs)
    1

  /** 0 continue, 1 done (recOgg/recDur stashed), 2 error. The recording
   *  meter's `AeCaptureLevel` ticks ride this same mailbox at 25 Hz — never
   *  terminal, explicitly 0. */
  def recEvtOf(e: AudioEvt): Int = e match
    case d: AeRecordingDone  => stashRec(d)
    case _: AeRecordingError => 2
    case _: AeCaptureLevel   => 0
    case _                   => 0

  /** wait for the recording to finish; empty Bytes = failure. */
  def waitRecording(clock: Clock, evts: sgo.Chan[AudioEvt], timeoutMs: Long): Bytes =
    val deadline = clock.nowUnixMillis() + timeoutMs
    recOggC.set(Bytes.empty)
    var run = true
    while run do
      if clock.nowUnixMillis() >= deadline then run = false
      else
        evts.tryReceive() match
          case s: Some[AudioEvt] =>
            val t = recEvtOf(s.value)
            if t == 1 then run = false
            else if t == 2 then run = false
          case None => clock.sleepMs(10L)
    recOggC.get()

  /** 0 continue, 1 done, 2 error (playback tags only). */
  def playEvtOf(e: AudioEvt): Int = e match
    case _: AePlaybackDone  => 1
    case _: AePlaybackError => 2
    case _                  => 0

  def waitPlay(clock: Clock, evts: sgo.Chan[AudioEvt], timeoutMs: Long): Boolean =
    val deadline = clock.nowUnixMillis() + timeoutMs
    var res = false
    var run = true
    while run do
      if clock.nowUnixMillis() >= deadline then run = false
      else
        evts.tryReceive() match
          case s: Some[AudioEvt] =>
            val t = playEvtOf(s.value)
            if t == 1 then
              res = true
              run = false
            else if t == 2 then run = false
          case None => clock.sleepMs(10L)
    res

  /** 0 continue, 1 complete, 2 failed (send tags only). */
  def sendEvtOf(e: UiEvent): Int = e match
    case _: EvSendComplete => 1
    case _: EvSendFailed   => 2
    case _                 => 0

  def waitSend(clock: Clock, c: MatrixClient, timeoutMs: Long): Boolean =
    val deadline = clock.nowUnixMillis() + timeoutMs
    var res = false
    var run = true
    while run do
      if clock.nowUnixMillis() >= deadline then run = false
      else
        Runtime.pollEvent(c) match
          case s: Some[UiEvent] =>
            val t = sendEvtOf(s.value)
            if t == 1 then
              res = true
              run = false
            else if t == 2 then run = false
          case None => clock.sleepMs(20L)
    res

  // ---- snapshot inspection ------------------------------------------------------

  def anyMsg(s: StateSnapshot): Boolean = firstMxc(s.conversations) != ""

  /** the NEWEST message's mxc across conversations — the head of the first
   *  conversation that has any, messages coming back newest first. */
  def firstMxc(cs: List[Conversation]): String = cs match
    case h :: t => firstMxcStep(h, t)
    case Nil  => ""

  def firstMxcStep(h: Conversation, t: List[Conversation]): String =
    val m = newestMxcOf(h.messages)
    if m != "" then m else firstMxc(t)

  def newestMxcOf(ms: List[VoiceMessage]): String = ms match
    case h :: t => h.mxcUrl
    case Nil  => ""

  // ---- misc ----------------------------------------------------------------------

  def atoiOr(s: String, dflt: Int): Int =
    var out = dflt
    try
      val v = go.strconv.parseInt(s, 10, 64)
      out = v.toInt
    catch case e: sgo.GoError => out = dflt
    out
