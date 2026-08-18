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
 *  keeps whatever conversation is open. */
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

  /** a session started (or restarted onto another server): the new server
   *  knows nothing about this device's channel. */
  def newSession(): Unit =
    doneC.set("")
    nextAtC.set(0L)
    joinAtC.set(0L)

  /** is the PTT button routed through the framework? True exactly while a
   *  channel is joined. */
  def routing(): Boolean = routingC.get()

  /** once per frame: drain what the framework said, keep the channel joined,
   *  and keep the server's ephemeral registration current. */
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
    st

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
