/** This file is a separate compilation unit from shell.scala deliberately: a
 *  cross-unit module-val read (`Color.cyan`/`Font.ROWS`/`Display.W` from a
 *  unit that hadn't otherwise been referenced) used to emit invalid Go before
 *  the emitter's module-val pre-scan was fixed to look across all units; the
 *  applets stayed split out as the regression check for that fix. See
 *  shell.scala for the UI-half notes. */

/** The two gate applets: `wata` (contacts + conversation, PTT record/play)
 *  and `settings` (menu of device options). Device-layer app code.
 *
 *  The applet layer fronts an `Applet` TRAIT — each applet is a `final class`
 *  holding its own immutable state record behind the interface, dispatched
 *  dynamically from Shell. The pure transition functions below are plain
 *  function bags, `WataLogic`/`SettingsLogic`; the impl classes are thin
 *  3-method shells over them. The per-frame CONTEXT is ONE unified `FrameCtx`
 *  record passed as a PARAMETER, not held in the applet's own state — it
 *  changes every frame and the queues it carries are owned by the UI
 *  goroutine.
 *
 *  FONT: bitmap only, no vector/TrueType rendering — the wata applet renders
 *  through the 5x8 bitmap font exclusively (grid (col,row) coordinates, the
 *  Font/Draw layer in display.scala). */

// ---- view selector -------------------------------------------------------------
sealed trait WataView derives CanEqual
case class VContacts() extends WataView
case class VConversation() extends WataView

/** wata applet state (the UI-only fields; snapshot/queues are per-frame
 *  params). `pttHeld`/`pttHoldTime` drive the record overlay; send/play
 *  status flash for `statusTimer` seconds then clears. */
case class WataState(
  view: WataView,
  selected: scala.Int,
  scrollOffset: scala.Int,
  convContactIdx: scala.Int,
  msgSelected: scala.Int,
  msgScroll: scala.Int,
  pttHeld: Boolean,
  pttHoldTime: scala.Double,
  playing: Boolean,
  sendError: Boolean,
  sendOk: Boolean,
  playError: Boolean,
  statusTimer: scala.Double
)

object WataLogic:
  val FONT_ROWS_HEADER = 2 // header grid rows before the list (bitmap layout)
  val FOOTER_ROW = Font.ROWS - 1

  def initial(): WataState =
    WataState(VContacts(), 0, 0, 0, 0, 0, false, 0.0, false, false, false, false, 0.0)

  /** visible list rows between header and footer (bitmap grid). */
  def visibleRows(): scala.Int = FOOTER_ROW - FONT_ROWS_HEADER

  // ---- record withers (no `.copy` on sgola — GoEmitter skips synthetic
  //      `copy`; the house style reconstructs the record explicitly) ----------
  def withView(s: WataState, v: WataView): WataState =
    WataState(v, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  def withSel(s: WataState, sel: scala.Int, off: scala.Int): WataState =
    WataState(s.view, sel, off, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  def enterConv(s: WataState, idx: scala.Int): WataState =
    WataState(VConversation(), s.selected, s.scrollOffset, idx, 0, 0,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  def withMsgSel(s: WataState, sel: scala.Int, scr: scala.Int): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, sel, scr,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  def withPtt(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      held, hold, s.playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  def withPlaying(s: WataState, playing: Boolean): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, s.sendError, s.sendOk, s.playError, s.statusTimer)

  /** the full status-flash tuple (hold + timer + the three flash flags). */
  def withFlash(s: WataState, hold: scala.Double, timer: scala.Double,
                sendErr: Boolean, sendOk: Boolean, playErr: Boolean): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, hold, s.playing, sendErr, sendOk, playErr, timer)

  def withPlayErr(s: WataState, playing: Boolean, playErr: Boolean, timer: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, s.sendError, s.sendOk, playErr, timer)

  // ---- input (needs the snapshot + queues) -----------------------------------
  /** full input with per-frame context (snapshot + queues). Returns new state. */
  def handleInput(s: WataState, k: Key, ks: KeyState, ctx: FrameCtx): WataState =
    if isPtt(k) then pttInput(s, ks, ctx)
    else if !Shell.isPressed(ks) then s
    else s.view match
      case _: VContacts     => contactsInput(s, k, ctx)
      case _: VConversation => conversationInput(s, k, ctx)

  def isPtt(k: Key): Boolean = k match
    case KPtt() => true
    case _      => false

  /** PTT: press starts recording, release stops + (via the audio thread) sends.
   *  The effect (trySend) runs in statement position; the result rides a
   *  `var` (the same if-as-expression idiom used in led.scala). */
  def pttInput(s: WataState, ks: KeyState, ctx: FrameCtx): WataState = ks match
    case Pressed()  => pttPress(s, ctx)
    case Released() => pttRelease(s, ctx)
    case _          => s

  def pttPress(s: WataState, ctx: FrameCtx): WataState =
    var out = s
    if !s.pttHeld then
      ctx.audioCmds.trySend(AcRecordStart())
      out = withPtt(s, true, 0.0)
    out

  def pttRelease(s: WataState, ctx: FrameCtx): WataState =
    var out = s
    if s.pttHeld then
      ctx.audioCmds.trySend(AcRecordStop())
      out = withPtt(s, false, s.pttHoldTime)
    out

  def contactsInput(s: WataState, k: Key, ctx: FrameCtx): WataState =
    val count = convCount(ctx.snap)
    if count == 0 then s
    else k match
      case _: KDown => downSel(s, count)
      case _: KUp   => upSel(s)
      case _: KEnter => enterConversation(s, ctx)
      case _          => s

  def downSel(s: WataState, count: scala.Int): WataState =
    val sel = if s.selected < count - 1 then s.selected + 1 else s.selected
    val vis = visibleRows()
    val off = if sel >= s.scrollOffset + vis then sel - vis + 1 else s.scrollOffset
    withSel(s, sel, off)

  def upSel(s: WataState): WataState =
    val sel = if s.selected > 0 then s.selected - 1 else 0
    val off = if sel < s.scrollOffset then sel else s.scrollOffset
    withSel(s, sel, off)

  /** open the selected conversation + send a read receipt for its latest msg. */
  def enterConversation(s: WataState, ctx: FrameCtx): WataState =
    sendReceiptForConversation(ctx, s.selected)
    enterConv(s, s.selected)

  def conversationInput(s: WataState, k: Key, ctx: FrameCtx): WataState = k match
    case _: KBack  => withView(s, VContacts())
    case _: KDown  => downMsg(s, ctx)
    case _: KUp    => upMsg(s)
    case _: KEnter => playSelected(s, ctx)
    case _: KF2    => deleteSelected(s, ctx)
    case _           => s

  def downMsg(s: WataState, ctx: FrameCtx): WataState =
    val count = msgCount(ctx.snap, s.convContactIdx)
    if count > 0 && s.msgSelected < count - 1 then
      val sel = s.msgSelected + 1
      val vis = visibleRows()
      val scr = if sel >= s.msgScroll + vis then sel - vis + 1 else s.msgScroll
      withMsgSel(s, sel, scr)
    else s

  def upMsg(s: WataState): WataState =
    if s.msgSelected > 0 then
      val sel = s.msgSelected - 1
      val scr = if sel < s.msgScroll then sel else s.msgScroll
      withMsgSel(s, sel, scr)
    else s

  /** OK on a message: read receipt (if unplayed) + download-and-play. */
  def playSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        if !m.value.isPlayed then pushReceipt(ctx, roomIdAt(ctx.snap, s.convContactIdx), m.value.id)
        Runtime.sendAction(ctx.client, ActPlay(m.value.mxcUrl))
        withPlaying(s, true)
      case None => s

  def deleteSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        Runtime.sendAction(ctx.client, ActRedact(roomIdAt(ctx.snap, s.convContactIdx), m.value.id))
        s
      case None => s

  // ---- receipts ------------------------------------------------------------------
  def sendReceiptForConversation(ctx: FrameCtx, idx: scala.Int): Unit =
    convAt(ctx.snap, idx) match
      case c: Some[Conversation] => receiptLatest(ctx, c.value)
      case None => ()

  def receiptLatest(ctx: FrameCtx, conv: Conversation): Unit =
    lastMsg(conv.messages) match
      case m: Some[VoiceMessage] => pushReceipt(ctx, conv.roomId, m.value.id)
      case None => ()

  def pushReceipt(ctx: FrameCtx, roomId: String, eventId: String): Unit =
    if roomId != "" && eventId != "" then Runtime.sendAction(ctx.client, ActReceipt(roomId, eventId))

  // ---- per-frame update --------------------------------------------------------
  /** tick hold-time + status flash, then reconcile the cursors. Audio events
   *  arrive via `Shell.routeAudio` -> `onAudioEvent`, NOT a drain here — the
   *  shell owns the mailbox's single drain (plan 0009). */
  def update(s: WataState, dt: scala.Double, ctx: FrameCtx): WataState =
    clampSelection(tickTimers(s, dt), ctx)

  /** Reconcile the cursors with the live snapshot. The lists shrink under the
   *  cursor without any input — a redaction drops a message row, a peer
   *  leaving drops a conversation — and a selection left past the end
   *  highlights nothing and plays nothing. So each frame pulls both cursors
   *  back onto the last row and drags the scroll window after them. */
  def clampSelection(s: WataState, ctx: FrameCtx): WataState =
    var out = clampContacts(s, convCount(ctx.snap))
    out = clampMessages(out, msgCount(ctx.snap, out.convContactIdx))
    out

  def clampContacts(s: WataState, count: scala.Int): WataState =
    val sel = clampIdx(s.selected, count)
    withSel(s, sel, clampScroll(s.scrollOffset, sel))

  def clampMessages(s: WataState, count: scala.Int): WataState =
    val sel = clampIdx(s.msgSelected, count)
    withMsgSel(s, sel, clampScroll(s.msgScroll, sel))

  /** the last index, or 0 for an empty list (which renders no rows anyway). */
  def clampIdx(i: scala.Int, count: scala.Int): scala.Int =
    var out = i
    if count <= 0 then out = 0
    else if i > count - 1 then out = count - 1
    if out < 0 then out = 0
    out

  /** the scroll offset that keeps `sel` inside the visible window. */
  def clampScroll(off: scala.Int, sel: scala.Int): scala.Int =
    val vis = visibleRows()
    var out = off
    if sel < out then out = sel
    if sel >= out + vis then out = sel - vis + 1
    if out < 0 then out = 0
    out

  def tickTimers(s: WataState, dt: scala.Double): WataState =
    val hold = if s.pttHeld then s.pttHoldTime + dt else s.pttHoldTime
    var out = withFlash(s, hold, s.statusTimer, s.sendError, s.sendOk, s.playError)
    if s.statusTimer > 0.0 then
      val t = s.statusTimer - dt
      if t <= 0.0 then out = withFlash(s, hold, 0.0, false, false, false)
      else out = withFlash(s, hold, t, s.sendError, s.sendOk, s.playError)
    out

  /** one audio event, routed here by `Shell.routeAudio`: a finished recording
   *  is uploaded+sent; the catch-all is unreachable (echo events route to the
   *  settings applet). */
  def onAudioEvent(s: WataState, e: AudioEvt, ctx: FrameCtx): WataState = e match
    case d: AeRecordingDone =>
      uploadRecording(ctx, s, d.ogg, d.durationMs)
      s
    case _: AeRecordingError => withFlash(s, s.pttHoldTime, 2.0, true, s.sendOk, s.playError)
    case _: AePlaybackDone   => withPlaying(s, false)
    case _: AePlaybackError  => withPlayErr(s, false, true, 2.0)
    case _                   => s // echo events -> settings applet (unreachable)

  /** upload+send the recorded voice message to the current/selected
   *  conversation. roomId "" -> the runtime resolves/creates the DM for the
   *  contact. */
  def uploadRecording(ctx: FrameCtx, s: WataState, ogg: Bytes, durationMs: Long): Unit =
    val idx = convIdxForSend(s)
    convAt(ctx.snap, idx) match
      case c: Some[Conversation] =>
        val contactId = if c.value.hasContact then c.value.contact.user.id else ""
        Runtime.sendAction(ctx.client, ActSendVoice(c.value.roomId, contactId, ogg, durationMs))
      case None => ()

  def convIdxForSend(s: WataState): scala.Int = s.view match
    case _: VConversation => s.convContactIdx
    case _: VContacts     => s.selected

  // ---- send/play status feedback (from the runtime's UiEvents) ----------------
  def notifySend(s: WataState, isError: Boolean): WataState =
    var out = withFlash(s, s.pttHoldTime, 1.5, false, true, s.playError)
    if isError then out = withFlash(s, s.pttHoldTime, 2.0, true, false, s.playError)
    out

  def notifyPlayError(s: WataState): WataState = withFlash(s, s.pttHoldTime, 2.0, s.sendError, s.sendOk, true)

  // ---- render (bitmap-font only) -----------------------------------------------
  def render(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    s.view match
      case _: VContacts     => renderContacts(s, px, ctx)
      case _: VConversation => renderConversation(s, px, ctx)
    renderStatusFlash(s, px)
    if s.pttHeld then renderRecordingOverlay(s, px)

  def renderContacts(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    if !ctx.snap.hasSelfUser && convCount(ctx.snap) == 0 then renderConnecting(px, ctx.connection)
    else
      Font.drawText(px, "WATA", 0, 0, Color.cyan, false, 0)
      Font.drawText(px, connStr(ctx.connection), Font.COLS - 3, 0, connColor(ctx.connection), false, 0)
      val count = convCount(ctx.snap)
      if count == 0 then
        Font.drawText(px, "No contacts", 3, 4, Color.midGray, false, 0)
        Font.drawText(px, "Waiting sync", 3, 5, Color.midGray, false, 0)
      else renderContactRows(s, px, ctx, count)
      Font.drawText(px, "UP/DN sel OK open", 0, FOOTER_ROW, Color.midGray, false, 0)

  def renderContactRows(s: WataState, px: go.Bytes, ctx: FrameCtx, count: scala.Int): Unit =
    val vis = visibleRows()
    val end = if count < s.scrollOffset + vis then count else s.scrollOffset + vis
    var i = s.scrollOffset
    while i < end do
      val row = FONT_ROWS_HEADER + (i - s.scrollOffset)
      val selected = i == s.selected
      if selected then Draw.fillRect(px, 0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)
      val fg = if selected then Color.black else Color.green
      renderContactRow(px, ctx, i, row, fg, selected)
      i += 1

  def renderContactRow(px: go.Bytes, ctx: FrameCtx, i: scala.Int, row: scala.Int, fg: scala.Int, selected: Boolean): Unit =
    convAt(ctx.snap, i) match
      case c: Some[Conversation] =>
        val name = convName(ctx.snap, c.value)
        val nameColor = if isFamily(c.value.convType) && !selected then Color.cyan else fg
        Font.drawText(px, clip(name, 18), 0, row, nameColor, false, 0)
        if c.value.unplayedCount > 0 then
          val badge = "" + c.value.unplayedCount
          Font.drawText(px, badge, Font.COLS - badge.length, row, Color.yellow, false, 0)
      case None => ()

  def renderConversation(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    convAt(ctx.snap, s.convContactIdx) match
      case c: Some[Conversation] => renderConvBody(s, px, c.value)
      case None => Font.drawText(px, "No conversation", 3, 6, Color.midGray, false, 0)

  def renderConvBody(s: WataState, px: go.Bytes, conv: Conversation): Unit =
    val header = if conv.hasContact then conv.contact.user.displayName else "Chat"
    Font.drawText(px, clip(header, 20), 0, 0, Color.cyan, false, 0)
    val n = msgCountList(conv.messages)
    if n == 0 then
      Font.drawText(px, "No messages", 3, 6, Color.midGray, false, 0)
      Font.drawText(px, "ESC back", 0, FOOTER_ROW, Color.midGray, false, 0)
    else
      renderMsgRows(s, px, conv, n)
      Font.drawText(px, "OK play F2 del", 0, FOOTER_ROW, Color.midGray, false, 0)

  def renderMsgRows(s: WataState, px: go.Bytes, conv: Conversation, n: scala.Int): Unit =
    val vis = visibleRows()
    val end = if n < s.msgScroll + vis then n else s.msgScroll + vis
    var i = s.msgScroll
    while i < end do
      val row = FONT_ROWS_HEADER + (i - s.msgScroll)
      val selected = i == s.msgSelected
      msgAt(conv.messages, i) match
        case m: Some[VoiceMessage] =>
          if selected then Draw.fillRect(px, 0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)
          val fg = if selected then Color.black else (if m.value.isPlayed then Color.midGray else Color.green)
          renderMsgRow(px, m.value, row, fg)
        case None => ()
      i += 1

  def renderMsgRow(px: go.Bytes, m: VoiceMessage, row: scala.Int, fg: scala.Int): Unit =
    if m.isPlayed then Font.drawChar(px, Font.ICON_CHECK, 0, 1 + row * Font.GLYPH_H, fg, false, 0)
    val dur = durStr(m.durationMs)
    val col = if m.isPlayed then 1 else 0
    Font.drawText(px, dur, col, row, fg, false, 0)
    val sender = clip(m.sender.displayName, 8)
    Font.drawText(px, sender, col + dur.length + 1, row, fg, false, 0)

  def renderStatusFlash(s: WataState, px: go.Bytes): Unit =
    if s.statusTimer > 0.0 then
      if s.sendError then Font.drawText(px, "SEND FAILED", 3, 9, Color.red, false, 0)
      else if s.playError then Font.drawText(px, "PLAY FAILED", 3, 9, Color.red, false, 0)
      else if s.sendOk then Font.drawText(px, "SENT", 8, 9, Color.green, false, 0)

  def renderRecordingOverlay(s: WataState, px: go.Bytes): Unit =
    val barY = Display.H - 24
    Draw.fillRect(px, 0, barY, Display.W, 24, Color.red)
    val secs = s.pttHoldTime.toInt
    val tenths = (s.pttHoldTime * 10.0).toInt % 10
    val txt = "REC " + secs + "." + tenths + "s"
    Font.drawTextCentered(px, txt, (barY + 8) / Font.GLYPH_H, Color.white, false, 0)

  def renderConnecting(px: go.Bytes, c: ConnectionState): Unit =
    Font.drawText(px, connectingMsg(c), 1, 2, Color.midGray, false, 0)

  // ---- string / snapshot helpers --------------------------------------------
  def connStr(c: ConnectionState): String = c match
    case _: Syncing      => "ok"
    case _: Connected    => ".."
    case _: Connecting   => ".."
    case _: ConnError    => "ERR"
    case _: Disconnected => "off"

  def connColor(c: ConnectionState): scala.Int = c match
    case _: Syncing   => Color.green
    case _: ConnError => Color.red
    case _            => Color.midGray

  def connectingMsg(c: ConnectionState): String = c match
    case _: Disconnected => "Disconnected"
    case _: Connecting   => "Connecting..."
    case _: Connected    => "Logging in..."
    case _: Syncing      => "Syncing..."
    case _: ConnError    => "Connection error"

  def convName(snap: StateSnapshot, conv: Conversation): String =
    if isFamily(conv.convType) then familyName(snap)
    else if conv.hasContact then conv.contact.user.displayName
    else "?"

  def familyName(snap: StateSnapshot): String =
    var out = "Family"
    if snap.hasFamily then out = snap.family.name
    out

  def isFamily(t: ConversationType): Boolean = t match
    case _: FamilyConv => true
    case _: DmConv     => false

  /** duration mm:ss from ms. */
  def durStr(ms: Long): String =
    val secs = (ms / 1000L).toInt
    val m = secs / 60
    val sPart = secs % 60
    val pad = if sPart < 10 then "0" else ""
    "" + m + ":" + pad + sPart

  def clip(s: String, n: scala.Int): String =
    if s.length <= n then s else s.substring(0, n)

  // ---- list access (walks, the integ/devcli house pattern) -------------------
  def convCount(snap: StateSnapshot): scala.Int = lenConv(snap.conversations)

  def lenConv(cs: List[Conversation]): scala.Int =
    var n = 0
    var cur = cs
    var going = true
    while going do
      cur match
        case _ :: t => n += 1; cur = t
        case Nil  => going = false
    n

  def convAt(snap: StateSnapshot, i: scala.Int): Option[Conversation] = nthConv(snap.conversations, i)

  def nthConv(cs: List[Conversation], i: scala.Int): Option[Conversation] =
    if i < 0 then None
    else cs match
      case h :: t => nthConvStep(h, t, i)
      case Nil  => None

  def nthConvStep(h: Conversation, t: List[Conversation], i: scala.Int): Option[Conversation] =
    if i == 0 then Some(h) else nthConv(t, i - 1)

  def roomIdAt(snap: StateSnapshot, i: scala.Int): String = convAt(snap, i) match
    case c: Some[Conversation] => c.value.roomId
    case None => ""

  def msgCount(snap: StateSnapshot, convIdx: scala.Int): scala.Int = convAt(snap, convIdx) match
    case c: Some[Conversation] => msgCountList(c.value.messages)
    case None => 0

  def msgCountList(ms: List[VoiceMessage]): scala.Int =
    var n = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case _ :: t => n += 1; cur = t
        case Nil  => going = false
    n

  def selectedMsg(snap: StateSnapshot, convIdx: scala.Int, msgIdx: scala.Int): Option[VoiceMessage] =
    convAt(snap, convIdx) match
      case c: Some[Conversation] => msgAt(c.value.messages, msgIdx)
      case None => None

  def msgAt(ms: List[VoiceMessage], i: scala.Int): Option[VoiceMessage] =
    if i < 0 then None
    else ms match
      case h :: t => msgAtStep(h, t, i)
      case Nil  => None

  def msgAtStep(h: VoiceMessage, t: List[VoiceMessage], i: scala.Int): Option[VoiceMessage] =
    if i == 0 then Some(h) else msgAt(t, i - 1)

  def lastMsg(ms: List[VoiceMessage]): Option[VoiceMessage] = ms match
    case h :: t => lastMsgStep(h, t)
    case Nil  => None

  def lastMsgStep(h: VoiceMessage, t: List[VoiceMessage]): Option[VoiceMessage] = t match
    case h2 :: t2 => lastMsgStep(h2, t2)
    case Nil    => Some(h)

/** the UNIFIED per-frame context, one record for every applet: the live
 *  snapshot, the connection, the matrix client (action queue), and the audio
 *  thread's command/event mailboxes. The settings applet simply ignores the
 *  `connection` field it doesn't need. Built once per frame by the UI loop
 *  and passed through the `Applet` interface. */
case class FrameCtx(
  snap: StateSnapshot,
  connection: ConnectionState,
  client: MatrixClient,
  audioCmds: sgo.Chan[AudioCmd],
  audioEvts: sgo.Chan[AudioEvt]
)

/** the `Applet` interface: each applet is an IMMUTABLE object holding its OWN
 *  typed state record behind the interface; input/update transitions return
 *  a NEW applet (the wither style, one level up), so applets stay snapshots
 *  inside the UI's `Atomic[ShellState]` cell — no in-place mutation. Derives
 *  the `Shareable` marker: the ShellState field `IArray[Applet]` is then
 *  admissible, and each impl's fields are in turn obliged pure (they are:
 *  one pure state record each). Dynamic dispatch through this interface
 *  replaces a hand-rolled `if active == SETTINGS` two-arm check at each of
 *  Shell's three dispatch sites; adding an applet = one list append in
 *  `Shell.initial()`. */
trait Applet extends Shareable:
  def handleInput(k: Key, ks: KeyState, ctx: FrameCtx): Applet
  def update(dt: scala.Double, ctx: FrameCtx): Applet
  def render(px: go.Bytes, ctx: FrameCtx): Unit

/** the wata applet: a thin dynamic-dispatch shell over `WataLogic`'s pure
 *  transition functions (which stay the house immutable-record style). */
final class WataApplet(val state: WataState) extends Applet:
  def handleInput(k: Key, ks: KeyState, ctx: FrameCtx): Applet =
    WataApplet(WataLogic.handleInput(state, k, ks, ctx))
  def update(dt: scala.Double, ctx: FrameCtx): Applet =
    WataApplet(WataLogic.update(state, dt, ctx))
  def render(px: go.Bytes, ctx: FrameCtx): Unit =
    WataLogic.render(state, px, ctx)

// ============================================================================
// settings applet — bitmap font, echo test, brightness, info
// ============================================================================

sealed trait EchoState derives CanEqual
case class EchoIdle() extends EchoState
case class EchoRecording() extends EchoState
case class EchoPlaying() extends EchoState
case class EchoDone() extends EchoState
case class EchoErr() extends EchoState

case class SettingsState(
  selected: scala.Int,
  brightness: scala.Int,
  echo: EchoState,
  nameIdx: scala.Int,
  screenTimeoutIdx: scala.Int,
  connected: Boolean
)

/** the settings applet: a thin dynamic-dispatch shell over `SettingsLogic`
 *  (per-frame context is the shared `FrameCtx`; settings ignores
 *  `connection`). */
final class SettingsApplet(val state: SettingsState) extends Applet:
  def handleInput(k: Key, ks: KeyState, ctx: FrameCtx): Applet =
    SettingsApplet(SettingsLogic.handleInput(state, k, ks, ctx))
  def update(dt: scala.Double, ctx: FrameCtx): Applet =
    SettingsApplet(SettingsLogic.update(state, dt, ctx))
  def render(px: go.Bytes, ctx: FrameCtx): Unit =
    SettingsLogic.render(state, px, ctx)

object SettingsLogic:
  // menu items: 0 echo, 1 brightness, 2 screen_off, 3 display_name,
  // 4 disconnect, 5 info.
  val N_ITEMS = 6
  val ECHO = 0
  val BRIGHTNESS = 1
  val SCREEN_OFF = 2
  val DISPLAY_NAME = 3
  val DISCONNECT = 4
  val INFO = 5

  // screen timeouts (seconds; 0 = never) + labels
  def timeoutSecs(i: scala.Int): scala.Int =
    if i == 0 then 30 else if i == 1 then 60 else if i == 2 then 120 else if i == 3 then 300 else 0
  def timeoutLabel(i: scala.Int): String =
    if i == 0 then "30s" else if i == 1 then "1m" else if i == 2 then "2m" else if i == 3 then "5m" else "Never"
  val N_TIMEOUTS = 5

  def displayName(i: scala.Int): String =
    if i == 0 then "Alice" else if i == 1 then "Bob" else if i == 2 then "Charlie" else "Device"
  val N_NAMES = 4

  /** the menu occupies grid rows 2..12 (six items at two-row spacing), which
   *  leaves rows 13 and 14 — the last two of the 15-row landscape grid — for
   *  the selected item's detail text. Two lines is what there is room for. */
  val DETAIL_ROW = 13

  def initial(): SettingsState = SettingsState(0, 40, EchoIdle(), 0, 1, true)

  /** the boot state: preferences come back from the config store, so a device
   *  keeps the backlight and timeout its owner set. */
  def restored(p: FbPrefs): SettingsState =
    SettingsState(0, p.brightness, EchoIdle(), p.nameIdx, p.timeoutIdx, true)

  def getScreenTimeout(s: SettingsState): scala.Int = timeoutSecs(s.screenTimeoutIdx)
  def getBrightness(s: SettingsState): scala.Int = s.brightness

  // ---- record withers (no `.copy` on sgola — see WataApplet) ----------------
  def withSelected(s: SettingsState, sel: scala.Int): SettingsState =
    SettingsState(sel, s.brightness, s.echo, s.nameIdx, s.screenTimeoutIdx, s.connected)
  def withBrightness(s: SettingsState, b: scala.Int): SettingsState =
    SettingsState(s.selected, b, s.echo, s.nameIdx, s.screenTimeoutIdx, s.connected)
  def withEcho(s: SettingsState, e: EchoState): SettingsState =
    SettingsState(s.selected, s.brightness, e, s.nameIdx, s.screenTimeoutIdx, s.connected)
  def withNameIdx(s: SettingsState, i: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, i, s.screenTimeoutIdx, s.connected)
  def withTimeoutIdx(s: SettingsState, i: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.nameIdx, i, s.connected)
  def withConnected(s: SettingsState, c: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.nameIdx, s.screenTimeoutIdx, c)

  // ---- input (press-only) ------------------------------------------------------
  /** every key goes through `persisted`, so the three stored preferences are
   *  written back the moment one of them changes and there is exactly one
   *  place that decides when to write. */
  def handleInput(s: SettingsState, k: Key, ks: KeyState, ctx: FrameCtx): SettingsState =
    persisted(s, handleKey(s, k, ks, ctx))

  def handleKey(s: SettingsState, k: Key, ks: KeyState, ctx: FrameCtx): SettingsState =
    if !Shell.isPressed(ks) then s
    else k match
      case _: KUp    => moveUp(s)
      case _: KDown  => moveDown(s)
      case _: KEnter => onEnter(s, ctx)
      case _: KLeft  => onLeft(s)
      case _: KRight => onRight(s)
      case _           => s

  def persisted(before: SettingsState, after: SettingsState): SettingsState =
    if prefsChanged(before, after) then
      FbConfig.savePrefs(FbPrefs(after.brightness, after.screenTimeoutIdx, after.nameIdx))
    after

  def prefsChanged(a: SettingsState, b: SettingsState): Boolean =
    a.brightness != b.brightness || a.screenTimeoutIdx != b.screenTimeoutIdx ||
      a.nameIdx != b.nameIdx

  def moveUp(s: SettingsState): SettingsState =
    var out = s
    if s.selected > 0 then out = withSelected(s, s.selected - 1)
    out

  def moveDown(s: SettingsState): SettingsState =
    var out = s
    if s.selected < N_ITEMS - 1 then out = withSelected(s, s.selected + 1)
    out

  def onEnter(s: SettingsState, ctx: FrameCtx): SettingsState =
    if s.selected == ECHO then startEcho(s, ctx)
    else if s.selected == DISPLAY_NAME then pushName(s, ctx)
    else if s.selected == DISCONNECT then doDisconnect(s, ctx)
    else s

  def startEcho(s: SettingsState, ctx: FrameCtx): SettingsState =
    var out = s
    if isIdleEcho(s.echo) then
      ctx.audioCmds.trySend(AcEchoTest())
      out = withEcho(s, EchoRecording())
    out

  def isIdleEcho(e: EchoState): Boolean = e match
    case _: EchoIdle => true
    case _: EchoDone => true
    case _: EchoErr  => true
    case _             => false

  def pushName(s: SettingsState, ctx: FrameCtx): SettingsState =
    Runtime.sendAction(ctx.client, ActSetName(displayName(s.nameIdx)))
    s

  /** disconnect network only: stop sync loop + close actions. Reconnect
   *  requires an app restart (the runtime's threads don't respawn). */
  def doDisconnect(s: SettingsState, ctx: FrameCtx): SettingsState =
    var out = s
    if s.connected then
      Runtime.stopClient(ctx.client)
      out = withConnected(s, false)
    out

  def onLeft(s: SettingsState): SettingsState =
    if s.selected == BRIGHTNESS then brightnessDown(s)
    else if s.selected == DISPLAY_NAME then withNameIdx(s, decMod(s.nameIdx, N_NAMES))
    else if s.selected == SCREEN_OFF then withTimeoutIdx(s, decMod(s.screenTimeoutIdx, N_TIMEOUTS))
    else s

  def onRight(s: SettingsState): SettingsState =
    if s.selected == BRIGHTNESS then brightnessUp(s)
    else if s.selected == DISPLAY_NAME then withNameIdx(s, (s.nameIdx + 1) % N_NAMES)
    else if s.selected == SCREEN_OFF then withTimeoutIdx(s, (s.screenTimeoutIdx + 1) % N_TIMEOUTS)
    else s

  /** wrap-decrement (the `if x==0 then n-1 else x-1` idiom as a plain fn). */
  def decMod(x: scala.Int, n: scala.Int): scala.Int =
    var out = n - 1
    if x != 0 then out = x - 1
    out

  def brightnessDown(s: SettingsState): SettingsState =
    var out = s
    if s.brightness > 0 then out = setBl(s, s.brightness - 5)
    out

  def brightnessUp(s: SettingsState): SettingsState =
    var out = s
    if s.brightness < 40 then out = setBl(s, s.brightness + 5)
    out

  def setBl(s: SettingsState, b: scala.Int): SettingsState =
    Led.setBacklight(b)
    withBrightness(s, b)

  // ---- update ---------------------------------------------------------------------
  /** nothing per-frame here: echo events arrive via `Shell.routeAudio` ->
   *  `onEcho`, NOT a drain — the shell owns the mailbox's single drain
   *  (plan 0009). */
  def update(s: SettingsState, dt: scala.Double, ctx: FrameCtx): SettingsState = s

  /** one echo event, routed here by `Shell.routeAudio`; the catch-all is
   *  unreachable (wata events route to the wata applet). */
  def onEcho(s: SettingsState, e: AudioEvt): SettingsState = e match
    case _: AeEchoRecording => withEcho(s, EchoRecording())
    case _: AeEchoPlaying   => withEcho(s, EchoPlaying())
    case _: AeEchoDone      => withEcho(s, EchoDone())
    case _: AeEchoError     => withEcho(s, EchoErr())
    case _                  => s // wata events (unreachable)

  // ---- render --------------------------------------------------------------------
  def render(s: SettingsState, px: go.Bytes, ctx: FrameCtx): Unit =
    renderMenu(s, px)
    renderDetail(s, px, ctx)

  def renderMenu(s: SettingsState, px: go.Bytes): Unit =
    Font.drawText(px, "SETTINGS", 0, 0, Color.cyan, false, 0)
    var i = 0
    while i < N_ITEMS do
      val row = 2 + i * 2
      val sel = i == s.selected
      if sel then Draw.fillRect(px, 0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)
      val fg = if sel then Color.black else Color.green
      renderItem(s, px, i, row, fg)
      i += 1

  def renderItem(s: SettingsState, px: go.Bytes, i: scala.Int, row: scala.Int, fg: scala.Int): Unit =
    if i == ECHO then
      Font.drawText(px, "Audio Echo", 0, row, fg, false, 0)
      var statusFg = fg
      if isEchoErr(s.echo) then statusFg = Color.red
      Font.drawText(px, echoStatus(s.echo), 12, row, statusFg, false, 0)
    else if i == BRIGHTNESS then
      Font.drawText(px, "Brightness", 0, row, fg, false, 0)
      Font.drawText(px, "" + s.brightness + "/40", 14, row, fg, false, 0)
    else if i == SCREEN_OFF then
      Font.drawText(px, "Screen off", 0, row, fg, false, 0)
      Font.drawText(px, timeoutLabel(s.screenTimeoutIdx), 12, row, fg, false, 0)
    else if i == DISPLAY_NAME then
      Font.drawText(px, "Name", 0, row, fg, false, 0)
      Font.drawText(px, displayName(s.nameIdx), 7, row, Color.yellow, false, 0)
    else if i == DISCONNECT then
      Font.drawText(px, "Network", 0, row, fg, false, 0)
      var netTxt = "OFF"
      var netFg = Color.red
      if s.connected then
        netTxt = "ON"
        netFg = Color.green
      Font.drawText(px, netTxt, 11, row, netFg, false, 0)
    else
      Font.drawText(px, "Device Info", 0, row, fg, false, 0)

  /** the selected item's detail, on the two grid rows left below the menu. */
  def renderDetail(s: SettingsState, px: go.Bytes, ctx: FrameCtx): Unit =
    val row = DETAIL_ROW
    if s.selected == INFO then
      renderBattery(px, row)
      Font.drawText(px, "wata-fb sgola", 0, row + 1, Color.midGray, false, 0)
    else if s.selected == ECHO then
      Font.drawText(px, "Records 2s, plays", 0, row, Color.midGray, false, 0)
      Font.drawText(px, "back thru speaker", 0, row + 1, Color.midGray, false, 0)
    else if s.selected == DISPLAY_NAME then
      Font.drawText(px, "</> pick  OK set", 0, row, Color.midGray, false, 0)
      renderCurrentName(px, ctx, row + 1)
    else if s.selected == DISCONNECT then
      if s.connected then Font.drawText(px, "OK to disconnect", 0, row, Color.midGray, false, 0)
      else Font.drawText(px, "Restart to reconn", 0, row, Color.midGray, false, 0)
    else if s.selected == BRIGHTNESS then
      Font.drawText(px, "</> adjust", 0, row, Color.midGray, false, 0)
    else
      Font.drawText(px, "</> timeout", 0, row, Color.midGray, false, 0)
      Font.drawText(px, "Any key wakes", 0, row + 1, Color.midGray, false, 0)

  /** sysfs has no battery node off-device, and `readBatteryPercent` says so
   *  with -1 rather than by failing — the line is simply left out then. */
  def renderBattery(px: go.Bytes, row: scala.Int): Unit =
    val pct = Led.readBatteryPercent()
    if pct >= 0 then
      Font.drawText(px, "Battery: " + pct + "%", 0, row, Color.midGray, false, 0)

  def renderCurrentName(px: go.Bytes, ctx: FrameCtx, row: scala.Int): Unit =
    if ctx.snap.hasSelfUser then
      Font.drawText(px, "Now:", 0, row, Color.midGray, false, 0)
      Font.drawText(px, WataLogic.clip(ctx.snap.selfUser.displayName, 12), 5, row, Color.green, false, 0)

  def echoStatus(e: EchoState): String = e match
    case _: EchoIdle      => "OK=go"
    case _: EchoRecording => "OK=stop"
    case _: EchoPlaying   => "play.."
    case _: EchoDone      => "done!"
    case _: EchoErr       => "error"

  def isEchoErr(e: EchoState): Boolean = e match
    case _: EchoErr => true
    case _            => false
