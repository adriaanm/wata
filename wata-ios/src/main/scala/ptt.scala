import language.experimental.saferExceptions

/** The PushToTalk channel (plan 0065 tier 3): the family room as the ONE
 *  system-wide PTT channel, the ephemeral push token that channel mints, and
 *  the system talk button (Dynamic Island, lock screen) driving the same
 *  record/send arc the on-screen PTT button drives.
 *
 *  The platform side is `go-pkgs/iosshell/ptt.go` — the channel manager the
 *  framework requires at launch, both delegates, and a queue of what they
 *  said. Nothing there decides anything; this is where the decisions are, on
 *  the pump's own goroutine, exactly as `PushReg` is for tier 2's alert
 *  token.
 *
 *  THE CHANNEL IS THE FAMILY. There is one PTT channel system-wide, so the
 *  family room is it: the channel's name is the family's and its UUID is
 *  derived from the family ROOM ID (iosshell derives it), which makes every
 *  launch, and every device of the same family, land on the same channel.
 *  A device with no family in its snapshot never joins.
 *
 *  JOINING NEEDS THE APP IN THE FOREGROUND. The framework refuses a
 *  background join (`appNotForeground`), which is the product rule too: no
 *  server can conscript a phone into a channel. So the join is attempted from
 *  the pump while the app is up and retried on a slow clock, and after a
 *  reboot or an explicit leave the user has to open the app once. The
 *  refusal is printed rather than worked around.
 *
 *  TWO TOKENS, TWO ENDPOINTS. The ephemeral channel token is minted per JOIN
 *  and is DEAD after the leave, so it lives in its own server-side table
 *  (`POST /_wata/v1/push/channel/{join,leave}`) and never in tier 2's row.
 *  This object posts every token the framework mints and posts the leave when
 *  the framework confirms one, so a channel that is gone stops being pushed
 *  to.
 *
 *  TRANSMIT IS THE FRAMEWORK'S EDGE, NOT THE BUTTON'S. While a channel is
 *  joined the on-screen PTT button no longer records directly: it REQUESTS a
 *  transmission (`Pump.applyKeys`), and recording starts only when the
 *  framework has both begun transmitting and handed over an activated audio
 *  session — the shell derives that pair into one `talk on` event. That is
 *  what keeps the system UI, the audio session and the app's own state in
 *  step, and it is the same path whichever button the user pressed. A
 *  transmission the SYSTEM started (the user has no wata screen in front of
 *  them) targets the family conversation; one the app's own button started
 *  keeps whatever conversation is open.
 *
 *  RECEIVING IS AN EPISODE THE APP OWNS FROM END TO END. A `pushtotalk` push
 *  shows no banner — it wakes the app so the app can play the message — and
 *  answering it with an active speaker is what activates the audio session and
 *  puts "<name> speaking" on the system UI. Both stay until the app clears the
 *  speaker again, so the arc here is: take the room and event the push named,
 *  wait for the sync to bring that event in, play it on the session the
 *  framework handed over, then end the episode. Three things shape it:
 *
 *  - **The app may be suspended or cold.** The push is delivered to the
 *    framework, not to a running pump: the pump can start frames seconds
 *    later, and a cold launch has to log in and sync first. So the request
 *    carries the push's own ARRIVAL time (`pttPlayAgeMs`) and the window is
 *    measured from that, not from the frame that drained it — a pump that
 *    starts five minutes later must not suddenly play a stale message.
 *  - **Nothing plays before THIS push's session handover.** Answering the push
 *    only ASKS for the audio session; it is activated later, on
 *    `didActivateAudioSession`, and that activation reconfigures the session
 *    under the running engine — which stops the engine and strands whatever
 *    was scheduled, with no error and no completion handler. So the play waits
 *    for the handover as well as for the event. "A session is active" is not
 *    the same question and answering it was the rapid-fire bug: a second
 *    message arriving while the first episode is still winding down finds a
 *    live session, plays on it, and is killed when that older episode's
 *    deactivation lands. The shell counts activations so the app can ask for
 *    the one its own push asked for (`pttPlayReady`).
 *  - **An episode may only end itself.** Every episode carries the id of the
 *    push that opened it, and `finish` names it; if a newer push has taken
 *    the speaker since, the shell drops the request. Otherwise a failed
 *    playback tears down the message that arrived after it.
 *  - **The fetch IS the sync.** The event may not be in the local timeline
 *    yet; when the sync brings it in it appears in the snapshot with its mxc
 *    url, which is what playing needs anyway. So the resolve is retried once
 *    per frame until `PLAY_WINDOW_MS` runs out, and arming it kicks the sync
 *    loop out of any backoff (`Runtime.retryNow`) — a suspended app's sockets
 *    were torn down while it slept.
 *  - **Every exit ends the episode.** Played, gave up, no message named, the
 *    channel left: each one clears the speaker, because an episode nobody
 *    ends never ends — on hardware 2026-08-18 five pushes activated the audio
 *    session and none of them ever released it. */
object PttChan:

  /** how long before a failed channel registration is tried again. */
  val RETRY_MS: Long = 10000L
  /** how long before another join is attempted (a refusal is normal: the app
   *  is backgrounded, or the manager has not arrived yet). */
  val JOIN_RETRY_MS: Long = 30000L

  /** the newest ephemeral token the framework minted, "" until a join. */
  private val tokenC: sgo.Atomic[String] = sgo.atomic("")
  /** the token the server has acknowledged — "" re-arms the POST. */
  private val doneC: sgo.Atomic[String] = sgo.atomic("")
  /** a POST is out; only one at a time. */
  private val inFlightC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the earliest clock reading at which another join POST may go out. */
  private val nextAtC: sgo.Atomic[Long] = sgo.atomic(0L)
  /** a leave POST is owed (the framework confirmed a leave). */
  private val leaveC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the earliest clock reading at which another join may be requested. */
  private val joinAtC: sgo.Atomic[Long] = sgo.atomic(0L)
  /** the last `pttJoined()` reading — what routes the on-screen button
   *  without a facade call per key event. */
  private val routingC: sgo.Atomic[Boolean] = sgo.atomic(false)

  /** how long after the framework delivered a `pushtotalk` push the message it
   *  names is still worth playing live, measured from the push's own arrival.
   *  It has to cover a COLD launch — the app was not running, so login and the
   *  first sync are inside it — and it must not be so long that a message
   *  plays out of nowhere after the user has put the phone away. Past it the
   *  message is an ordinary unplayed arrival: the conversation row and the
   *  badge are its surface, and the alert push is the one that asks for a tap. */
  val PLAY_WINDOW_MS: Long = 20000L
  /** a hard cap on one receive episode, only ever reached when the playback
   *  neither finishes nor fails — the audio thread reports both, so this is a
   *  wedge-breaker rather than a limit on how long a message may be. Without
   *  it a playback that never reports would leave the speaker on the system UI
   *  and the audio session in the framework's hands for the life of the app. */
  val EPISODE_MAX_MS: Long = 300000L

  /** the room and event a `pushtotalk` push named and the app has not resolved
   *  yet; "" once it has been played or given up on. */
  private val playRoomC: sgo.Atomic[String] = sgo.atomic("")
  private val playEventC: sgo.Atomic[String] = sgo.atomic("")
  /** the clock reading past which that message is no longer worth playing. */
  private val playUntilC: sgo.Atomic[Long] = sgo.atomic(0L)
  /** a speaker is on the system UI and the framework's session is the app's to
   *  play on, until the app says the episode is over. */
  private val speakingC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the clock reading at which the episode is ended regardless. */
  private val episodeUntilC: sgo.Atomic[Long] = sgo.atomic(0L)
  /** the playback this episode started reported a failure. */
  private val failedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** which episode the app is serving — the id of the push that opened it.
   *  Only that episode may end itself (`finish`), so a message whose playback
   *  fails cannot tear down the message that arrived after it. */
  private val episodeC: sgo.Atomic[Int] = sgo.atomic(0)

  /** is the PushToTalk arc armed at all? `WATA_IOS_PTT=0` turns it off, which
   *  is the only way to run this build as a plain audio app — a joined app
   *  does not own its audio session. */
  def enabled(): Boolean = go.sys.getenv("WATA_IOS_PTT") != "0"

  /** a session started (or restarted onto another server): the new server
   *  knows nothing about this device's channel. */
  def newSession(): Unit =
    doneC.set("")
    nextAtC.set(0L)
    joinAtC.set(0L)
    // a message this client cannot resolve any more: whatever episode the old
    // session was in ends here rather than outliving it.
    if speakingC.get() then finish("the session restarted")

  /** is the PTT button routed through the framework? True exactly while a
   *  channel is joined. */
  def routing(): Boolean = routingC.get()

  /** once per frame: drain what the framework said, keep the channel joined,
   *  keep the server's ephemeral registration current, and carry the receive
   *  episode a push woke the app for. */
  def step(h: Handle, st0: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    var st = st0
    var going = true
    while going do
      val e = go.iosshell.takePttEvent()
      if e == "" then going = false
      else
        println("ptt: " + e)
        st = onEvent(st, ctx, e)
    routingC.set(go.iosshell.pttJoined())
    val issued = go.iosshell.takePttToken()
    if issued != "" then
      tokenC.set(issued)
      doneC.set("")
      nextAtC.set(0L)
    postStep(h.client, nowMs)
    joinStep(ctx, nowMs)
    playStep(h, st, ctx, nowMs)

  /** one line from the shell's queue. Only three of them are decisions; the
   *  rest are printed and dropped. */
  def onEvent(st: PumpSt, ctx: FrameCtx, e: String): PumpSt =
    if e.startsWith("talk on") then talkOn(st, ctx, e.indexOf("system") >= 0)
    else if e == "talk off" then key(st, ctx, Released())
    else if e == "left" then
      // the ephemeral token died with the channel: tell the server, and drop
      // it here so nothing re-posts it.
      tokenC.set("")
      doneC.set("")
      leaveC.set(true)
      if speakingC.get() then finish("the channel was left")
      st
    else st

  /** a transmission is live and the session is activated: press PTT. A
   *  SYSTEM-started one opens the family conversation first — that is the
   *  channel the system talk button belongs to, and the recording goes to
   *  whichever conversation the applet is on. */
  def talkOn(st: PumpSt, ctx: FrameCtx, fromSystem: Boolean): PumpSt =
    var out = st
    if fromSystem then out = Pump.openRoom(out, ctx, ctx.snap.family.id)
    key(out, ctx, Pressed())

  def key(st: PumpSt, ctx: FrameCtx, ks: KeyState): PumpSt =
    Pump.withWata(st, WataLogic.handleInput(st.wata, KPtt(), ks, ctx))

  // ---- keeping the channel joined ---------------------------------------------

  /** ask the framework to join the family channel, at most every
   *  JOIN_RETRY_MS. `pttJoin` answers "" when the request went out and the
   *  reason otherwise (no manager yet, backgrounded, already joined). */
  def joinStep(ctx: FrameCtx, nowMs: Long): Unit =
    if !routingC.get() && ctx.snap.hasFamily && ctx.snap.family.id != "" &&
      NetStatus.everLive() && nowMs >= joinAtC.get() then
      joinAtC.set(nowMs + JOIN_RETRY_MS)
      val why = go.iosshell.pttJoin(channelName(ctx), ctx.snap.family.id)
      if why != "" then println("ptt: not joining: " + why)

  /** what the system UI calls this channel. */
  def channelName(ctx: FrameCtx): String =
    if ctx.snap.family.name != "" then ctx.snap.family.name else "wata"

  // ---- the receive half: the message the push woke the app for -----------------

  /** once per frame: take what the pushes named, try to resolve and play the
   *  one in hand, and end the episode when there is nothing left to do. */
  def playStep(h: Handle, st0: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    var going = true
    while going do
      val p = go.iosshell.takePttPlay()
      if p == "" then going = false
      else
        println("ptt: " + p)
        arm(h, go.iosshell.pttPlayRoom(), go.iosshell.pttPlayEvent(),
          go.iosshell.pttPlayAgeMs().toLong, nowMs)
    val st = resolve(h, st0, ctx, nowMs)
    endStep(st, nowMs)
    st

  /** a push named a message. The LAST one wins: only one thing can play, and
   *  an older push's message is an ordinary unplayed arrival the conversation
   *  row already shows. The deadline is measured from the push's arrival, so a
   *  pump that only starts running now inherits the time already spent. */
  def arm(h: Handle, room: String, event: String, ageMs: Long, nowMs: Long): Unit =
    // the newest push owns the episode from here: an older one that is still
    // in flight can no longer end anything (`finish` names its own id).
    episodeC.set(go.iosshell.pttPlayEpisode())
    failedC.set(false)
    speakingC.set(true)
    episodeUntilC.set(nowMs + EPISODE_MAX_MS)
    if room == "" || event == "" then finish("the push named no message")
    else
      playRoomC.set(room)
      playEventC.set(event)
      playUntilC.set(nowMs + PLAY_WINDOW_MS - ageMs)
      // the app was almost certainly suspended, so its sync connection is gone
      // and the retry loop may be part-way through a backoff sleep. Nothing
      // the client could be doing is more urgent than this one event.
      Runtime.retryNow(h.client)

  /** TWO things have to be true before a woken message may play, and the
   *  second one is not obvious.
   *
   *  The message must be in the snapshot with its mxc url — the sync putting
   *  it there IS the fetch, retried every frame.
   *
   *  And the FRAMEWORK MUST HAVE HANDED THE AUDIO SESSION OVER. Answering the
   *  push with an active speaker only ASKS for the session; the activation
   *  arrives later, on `channelManager:didActivateAudioSession:`. Playing
   *  before it lands does not merely play on the wrong session — the
   *  activation reconfigures the session under the running engine, which stops
   *  the engine and strands the buffer the player node had scheduled, and its
   *  completion handler never fires. Device log 2026-08-18: `ptt: playing`,
   *  then `PushToTalk owns the audio session`, then
   *  `playback of 61440 frames never completed`, and nothing audible.
   *
   *  Both waits share one deadline. A handover that never arrives is a
   *  diagnosable give-up, not a fallback onto a session that is about to be
   *  taken away — and the alert push still delivers the message. */
  def resolve(h: Handle, st: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    val room = playRoomC.get()
    if room == "" then st
    else if nowMs >= playUntilC.get() then
      finish("gave up on " + playEventC.get() + " (" + whyStuck() + ")")
      st
    else if !go.iosshell.pttPlayReady() then st
    else
      val url = mxcOf(ctx.snap.conversations, room, playEventC.get())
      if url == "" then st
      else play(h, st, ctx, room, playEventC.get(), url)

  /** which of the two waits ran out — the difference matters and costs one
   *  line to say. */
  def whyStuck(): String =
    if !go.iosshell.pttPlayReady() then "no audio session for this push"
    else "not synced in time"

  /** play it, on the session the framework activated for exactly this, and
   *  open its conversation — the user woken by a walkie-talkie should find
   *  the thread it came from on screen. The applet's own play path
   *  (`withPlaying`) is what marks it playing, so the arrival announcement in
   *  the same frame does not play it a second time. */
  def play(h: Handle, st: PumpSt, ctx: FrameCtx, room: String, event: String,
           url: String): PumpSt =
    playRoomC.set("")
    var out = Pump.openRoom(st, ctx, room)
    Runtime.sendAction(h.client, ActPlay(url))
    out = Pump.withWata(out, WataLogic.withPlaying(out.wata, true, room, event))
    println("ptt: playing " + event)
    out

  /** the episode is over once the playback the app started has stopped — the
   *  audio thread reports both the end and the failure, and both clear
   *  `playing`. The cap catches a playback that reports neither.
   *
   *  The line says which of the two it was. A `speaker done (played)` printed
   *  over a playback that errored is exactly the green that overstates what
   *  ran, and this log is the only surface the receive half has. */
  def endStep(st: PumpSt, nowMs: Long): Unit =
    if !speakingC.get() then ()
    else if playRoomC.get() == "" && !st.wata.playing then
      if failedC.get() then finish("playback FAILED") else finish("played")
    else if nowMs >= episodeUntilC.get() then finish("the episode outran its cap")

  /** the runtime or the audio thread reported a failed playback. Called from
   *  the pump's two drains, because neither the fetch failure (`EvPlaybackError`)
   *  nor the audio-thread failure (`AePlaybackError`) is distinguishable from a
   *  clean finish by the applet state they leave behind — both just clear
   *  `playing`. */
  def notePlayFailed(): Unit = if speakingC.get() then failedC.set(true)

  /** end the episode: clear the speaker, which takes it off the system UI and
   *  releases the audio session back to the app. Every exit comes through
   *  here. */
  def finish(why: String): Unit =
    val ep = episodeC.get()
    println("ptt: speaker done #" + ep + " (" + why + ")")
    playRoomC.set("")
    playEventC.set("")
    speakingC.set(false)
    failedC.set(false)
    // named, so an episode that is no longer current ends nothing: the shell
    // drops the call when a newer push owns the speaker.
    go.iosshell.pttSpeakerStopped(ep)

  def mxcOf(xs: List[Conversation], roomId: String, eventId: String): String = xs match
    case h :: t => mxcOfStep(h, t, roomId, eventId)
    case Nil    => ""

  def mxcOfStep(h: Conversation, t: List[Conversation], roomId: String,
                eventId: String): String =
    if h.roomId == roomId then mxcIn(h.messages, eventId)
    else mxcOf(t, roomId, eventId)

  def mxcIn(xs: List[VoiceMessage], eventId: String): String = xs match
    case h :: t => mxcInStep(h, t, eventId)
    case Nil    => ""

  def mxcInStep(h: VoiceMessage, t: List[VoiceMessage], eventId: String): String =
    if h.id == eventId then h.mxcUrl else mxcIn(t, eventId)

  // ---- the server's ephemeral registration -------------------------------------

  def postStep(c: MatrixClient, nowMs: Long): Unit =
    if leaveC.get() && !inFlightC.get() && Runtime.lastAuth.accessToken != "" then
      leaveC.set(false)
      inFlightC.set(true)
      sgo.spawn(() => PttChan.postLeave(c))
    else
      val tok = tokenC.get()
      if due(tok, nowMs) then
        inFlightC.set(true)
        nextAtC.set(nowMs + RETRY_MS)
        sgo.spawn(() => PttChan.postJoin(c, tok))

  def due(tok: String, nowMs: Long): Boolean =
    tok != "" && tok != doneC.get() && !inFlightC.get() &&
      nowMs >= nextAtC.get() && Runtime.lastAuth.accessToken != ""

  def postJoin(c: MatrixClient, tok: String): Unit =
    // the token is hex and the env is ours, so neither needs escaping.
    val body = "{\"token\":\"" + tok + "\",\"env\":\"" + PushReg.env() + "\"}"
    val resp = post(c, "/_wata/v1/push/channel/join", body)
    if resp.status == 200 then
      doneC.set(tok)
      println("ptt: channel registered with the server")
    else println("ptt: channel register failed status=" + resp.status)
    inFlightC.set(false)

  def postLeave(c: MatrixClient): Unit =
    val resp = post(c, "/_wata/v1/push/channel/leave", "{}")
    if resp.status == 200 then println("ptt: channel unregistered")
    else println("ptt: channel unregister failed status=" + resp.status)
    inFlightC.set(false)

  def post(c: MatrixClient, path: String, body: String): HttpResponse =
    val hs = Hs(c.http, c.clock, c.cfg.homeserver, Runtime.lastAuth.accessToken)
    MatrixHttp.request(hs, "POST", path, "application/json", body)
