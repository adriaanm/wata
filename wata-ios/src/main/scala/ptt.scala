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
 *  RECEIVING IS AN EPISODE THE APP OWNS FROM END TO END, AND AN EPISODE IS A
 *  BURST, NOT A MESSAGE. A `pushtotalk` push shows no banner — it wakes the app
 *  so the app can play the message — and answering it with an active speaker is
 *  what activates the audio session and puts "<name> speaking" on the system
 *  UI. Both stay until the app clears the speaker again. So the arc here is:
 *  take every room and event the pushes name, queue them, play them in arrival
 *  order on the one session the framework handed over, and clear the speaker
 *  when there is nothing left. `PlayQ` (wataclient) is the queue and the
 *  deadlines; this object is its platform half. Five things shape it:
 *
 *  - **Four messages in a row is the ordinary case.** A walkie-talkie burst is
 *    not an edge case, and the speaker stays up across the whole of it: pushes
 *    APPEND, nothing is displaced by something newer. On hardware 2026-08-18 a
 *    last-one-wins version of this played one of four and dropped three.
 *  - **The app may be suspended or cold.** The push is delivered to the
 *    framework, not to a running pump: the pump can start frames seconds
 *    later, and a cold launch has to log in and sync first. So the request
 *    carries the push's own ARRIVAL time (`pttPlayAgeMs`) and each message's
 *    window is seeded with it — a pump that starts five minutes later must not
 *    suddenly play a stale message. But the window then counts only the time
 *    that message spends AT THE HEAD of the queue waiting: a burst whose
 *    playback outlasts the window must still play in full (`PlayQ`).
 *  - **Nothing plays before THIS EPISODE's session handover.** Answering a
 *    push only ASKS for the audio session; it is activated later, on
 *    `didActivateAudioSession`, and that activation reconfigures the session
 *    under the running engine — which stops the engine and strands whatever
 *    was scheduled, with no error and no completion handler. So the first play
 *    waits for the handover. "A session is active" is not the same question,
 *    and answering it was the original rapid-fire bug: a message arriving
 *    while an older episode is winding down finds a live session, plays on it,
 *    and is killed when that older episode's deactivation lands. The shell
 *    counts activations against the EPISODE (`pttPlayReady`), so a message
 *    joining a live episode is ready at once — same speaker, same session, no
 *    boundary crossed — while a push arriving after the speaker came down
 *    opens a new episode and waits for its own fresh activation.
 *  - **An episode may only end itself.** The episode id is the shell's raised
 *    speaker, shared by every push of the burst, and `finish` names it; if the
 *    speaker has come down and been raised again since, the shell drops the
 *    request. Otherwise a failed playback tears down the burst that succeeded
 *    it.
 *  - **The fetch IS the sync.** The event may not be in the local timeline
 *    yet; when the sync brings it in it appears in the snapshot with its mxc
 *    url, which is what playing needs anyway. So the head is retried once per
 *    frame until its window runs out, and queuing kicks the sync loop out of
 *    any backoff (`Runtime.retryNow`) — a suspended app's sockets were torn
 *    down while it slept.
 *  - **Every exit ends the episode.** Drained, given up on, no message named,
 *    the channel left: each one clears the speaker, because an episode nobody
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
  /** the service state the system UI was last told, "" before the first. */
  private val serviceC: sgo.Atomic[String] = sgo.atomic("")

  /** the messages the pushes named, in arrival order, with the deadlines that
   *  decide when one is no longer worth playing live and when the burst has
   *  wedged. The scheduling is portable and pinned by an oracle
   *  (`wata-fb playqtest`); what is iOS's is everything around it. */
  private val queueC: sgo.Atomic[PlayQState] = sgo.atomic(PlayQ.empty())
  /** a speaker is on the system UI and the framework's session is the app's to
   *  play on, until the app says the episode is over. */
  private val speakingC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the playback the app started reported a failure. */
  private val failedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** which episode the app is serving — the shell's id for the raised speaker,
   *  shared by every push of the burst. Only that episode may end itself
   *  (`finish`), so a burst that is over cannot tear down the one after it. */
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
    serviceStep(ctx)
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

  /** keep the system UI's view of our service honest, one call per change. The
   *  framework's default is `ready` and it never stops being that on its own,
   *  so a phone whose sync is erroring would show a healthy channel and a talk
   *  button that goes nowhere. Only what the user SEES depends on this; the
   *  audio path does not. */
  def serviceStep(ctx: FrameCtx): Unit =
    if routingC.get() then
      val state = serviceState(ctx.connection)
      if state != serviceC.get() then
        serviceC.set(state)
        go.iosshell.pttServiceStatus(state)

  /** the session's connection state as the three words the framework has.
   *  `connected` and `syncing` are both a working service; anything else means
   *  a press right now would not reach anyone. */
  def serviceState(c: ConnectionState): String = c match
    case _: Connected    => "ready"
    case _: Syncing      => "ready"
    case _: Connecting   => "connecting"
    case _               => "unavailable"

  /** what the system UI calls this channel. */
  def channelName(ctx: FrameCtx): String =
    if ctx.snap.family.name != "" then ctx.snap.family.name else "wata"

  // ---- the receive half: the message the push woke the app for -----------------

  /** once per frame: queue what the pushes named, notice a playback that has
   *  stopped, serve the head of the queue, and end the episode when the queue
   *  has drained. */
  def playStep(h: Handle, st0: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    var going = true
    while going do
      val p = go.iosshell.takePttPlay()
      if p == "" then going = false
      else
        println("ptt: " + p)
        offer(h, go.iosshell.pttPlayRoom(), go.iosshell.pttPlayEvent(),
          go.iosshell.pttPlayEpisode(), go.iosshell.pttPlayAgeMs().toLong, nowMs)
    queueC.set(PlayQ.tick(queueC.get(), nowMs))
    endedStep(st0, nowMs)
    val st = serveStep(h, st0, ctx, nowMs)
    endStep(st, nowMs)
    st

  /** a push named a message: it joins the back of the queue, and raises the
   *  speaker if this is the burst's first. Nothing is displaced — an older
   *  message is not less worth hearing because a newer one landed while it was
   *  still playing. */
  def offer(h: Handle, room: String, event: String, episode: Int, ageMs: Long,
            nowMs: Long): Unit =
    if room == "" || event == "" then noMessage(episode)
    else
      if !speakingC.get() then
        // the burst's first push owns the episode: the shell raised the
        // speaker for it, and `finish` names this id when the burst is done.
        episodeC.set(episode)
        failedC.set(false)
        speakingC.set(true)
      queueC.set(PlayQ.offer(queueC.get(), room, event, ageMs, nowMs))
      // the app was almost certainly suspended, so its sync connection is gone
      // and the retry loop may be part-way through a backoff sleep. Nothing
      // the client could be doing is more urgent than this one event.
      Runtime.retryNow(h.client)

  /** a push with no room or event in it. The shell has already raised a
   *  speaker for it, so one has to come down — but only if this push is the
   *  whole episode. Joining a burst that is playing, it is simply nothing to
   *  play, and ending that burst here would cut off the message in the air. */
  def noMessage(episode: Int): Unit =
    if speakingC.get() then println("ptt: a push named no message")
    else
      episodeC.set(episode)
      speakingC.set(true)
      finish("the push named no message")

  /** the playback the app started has stopped — the audio thread reports both
   *  the end and the failure, and both clear `playing`, so only the failure
   *  flag tells them apart. Counted before the head is served, so the next
   *  message starts in the same frame the previous one ended. */
  def endedStep(st: PumpSt, nowMs: Long): Unit =
    if queueC.get().playing && !st.wata.playing then
      queueC.set(PlayQ.finished(queueC.get(), nowMs, failedC.get()))
      failedC.set(false)

  /** the head of the queue, if nothing is playing: drop it if it has waited
   *  out its window, else play it once TWO things are true — and the second
   *  one is not obvious.
   *
   *  The message must be in the snapshot with its mxc url — the sync putting
   *  it there IS the fetch, retried every frame.
   *
   *  And the FRAMEWORK MUST HAVE HANDED THIS EPISODE'S AUDIO SESSION OVER.
   *  Answering a push with an active speaker only ASKS for the session; the
   *  activation arrives later, on `channelManager:didActivateAudioSession:`.
   *  Playing before it lands does not merely play on the wrong session — the
   *  activation reconfigures the session under the running engine, which stops
   *  the engine and strands the buffer the player node had scheduled, and its
   *  completion handler never fires. Device log 2026-08-18: `ptt: playing`,
   *  then `PushToTalk owns the audio session`, then
   *  `playback of 61440 frames never completed`, and nothing audible.
   *
   *  Both waits are charged to the head's own window. A handover that never
   *  arrives is a diagnosable give-up, not a fallback onto a session that is
   *  about to be taken away — and the alert push still delivers the message. */
  def serveStep(h: Handle, st: PumpSt, ctx: FrameCtx, nowMs: Long): PumpSt =
    val q = queueC.get()
    if q.playing then st
    else PlayQ.head(q) match
      case s: Some[PlayQMsg] => serve(h, st, ctx, s.value, nowMs)
      case None              => st

  def serve(h: Handle, st: PumpSt, ctx: FrameCtx, m: PlayQMsg,
            nowMs: Long): PumpSt =
    if PlayQ.headStale(queueC.get()) then
      // one message out of time does not end the burst: the next one gets its
      // own turn, with its own window, on the next frame.
      println("ptt: gave up on " + m.event + " (" + whyStuck() + ")")
      queueC.set(PlayQ.dropHead(queueC.get(), nowMs))
      st
    else if !go.iosshell.pttPlayReady() then st
    else
      val url = mxcOf(ctx.snap.conversations, m.room, m.event)
      if url == "" then st
      else play(h, st, ctx, m, url, nowMs)

  /** which of the two waits ran out — the difference matters and costs one
   *  line to say. */
  def whyStuck(): String =
    if !go.iosshell.pttPlayReady() then "no audio session for this episode"
    else "not synced in time"

  /** play it, on the session the framework activated for this episode, and
   *  open its conversation — the user woken by a walkie-talkie should find
   *  the thread it came from on screen. The applet's own play path
   *  (`withPlaying`) is what marks it playing, so the arrival announcement in
   *  the same frame does not play it a second time. */
  def play(h: Handle, st: PumpSt, ctx: FrameCtx, m: PlayQMsg, url: String,
           nowMs: Long): PumpSt =
    queueC.set(PlayQ.start(queueC.get(), nowMs))
    var out = Pump.openRoom(st, ctx, m.room)
    Runtime.sendAction(h.client, ActPlay(url))
    out = Pump.withWata(out, WataLogic.withPlaying(out.wata, true, m.room, m.event))
    println("ptt: playing " + m.event + PlayQ.behind(queueC.get()))
    out

  /** the episode is over once the queue has drained and nothing is sounding.
   *  The no-progress cap catches a playback that reports neither an end nor a
   *  failure; it is renewed by every message, so a long burst never reaches
   *  it.
   *
   *  The closing line says what the burst actually did. A `speaker done
   *  (played)` printed over a playback that errored is exactly the green that
   *  overstates what ran, and this log is the only surface the receive half
   *  has. */
  def endStep(st: PumpSt, nowMs: Long): Unit =
    if !speakingC.get() then ()
    else
      val q = queueC.get()
      // `pttPlayPending` closes the one-frame race the drain leaves open: a
      // push that landed after it belongs to THIS episode, and ending the
      // episode would leave its message with no speaker and no session.
      if PlayQ.isIdle(q) && !go.iosshell.pttPlayPending() then
        finish(PlayQ.summary(q))
      else if PlayQ.wedged(q, nowMs) then
        finish("the episode outran its cap: " + PlayQ.summary(q))

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
    queueC.set(PlayQ.empty())
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
