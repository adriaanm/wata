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
 *  status flash for `statusTimer` seconds then clears; `backHeld`/
 *  `backHoldTime` track the red key in a conversation (tap = back on
 *  release, hold past `BACK_HOLD_DELETE` = delete the selected message), and
 *  `okHeld`/`okHoldTime` do the same for OK (tap = play on release, hold past
 *  `OK_HOLD_FAVORITE` = toggle the selected message's favorite). */
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
  // was the play failure "there is nothing to play it through" rather than
  // "it could not be fetched"? Two causes, two sentences on screen.
  noAudio: Boolean,
  statusTimer: scala.Double,
  backHeld: Boolean,
  backHoldTime: scala.Double,
  okHeld: Boolean,
  okHoldTime: scala.Double
)

object WataLogic:
  val FONT_ROWS_HEADER = 2 // header grid rows before the list (bitmap layout)
  val FOOTER_ROW = Font.ROWS - 1

  def initial(): WataState =
    WataState(VContacts(), 0, 0, 0, 0, 0, false, 0.0, false, false, false, false, false, 0.0,
      false, 0.0, false, 0.0)

  /** visible list rows between header and footer (bitmap grid). */
  def visibleRows(): scala.Int = FOOTER_ROW - FONT_ROWS_HEADER

  // ---- record withers (no `.copy` on sgola — GoEmitter skips synthetic
  //      `copy`; the house style reconstructs the record explicitly) ----------
  def withView(s: WataState, v: WataView): WataState =
    WataState(v, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def withSel(s: WataState, sel: scala.Int, off: scala.Int): WataState =
    WataState(s.view, sel, off, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def enterConv(s: WataState, idx: scala.Int): WataState =
    WataState(VConversation(), s.selected, s.scrollOffset, idx, 0, 0,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def withMsgSel(s: WataState, sel: scala.Int, scr: scala.Int): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, sel, scr,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def withPtt(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      held, hold, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def withPlaying(s: WataState, playing: Boolean): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  /** the full status-flash tuple (hold + timer + the three flash flags); the
   *  play-failure CAUSE rides along unchanged. */
  def withFlash(s: WataState, hold: scala.Double, timer: scala.Double,
                sendErr: Boolean, sendOk: Boolean, playErr: Boolean): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, hold, s.playing, sendErr, sendOk, playErr, s.noAudio, timer, s.backHeld, s.backHoldTime,
      s.okHeld, s.okHoldTime)

  /** a play that failed: the flash, its cause, and `playing` dropped — a
   *  playback indicator that outlives the playback is a lie. */
  def withPlayErr(s: WataState, playing: Boolean, playErr: Boolean, noAudio: Boolean,
                  timer: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, s.sendError, s.sendOk, playErr, noAudio, timer,
      s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime)

  def withOk(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer,
      s.backHeld, s.backHoldTime, held, hold)

  def withBack(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.sendError, s.sendOk, s.playError, s.noAudio, s.statusTimer,
      held, hold, s.okHeld, s.okHoldTime)

  // ---- input (needs the snapshot + queues) -----------------------------------
  /** full input with per-frame context (snapshot + queues). Returns new state. */
  def handleInput(s: WataState, k: Key, ks: KeyState, ctx: FrameCtx): WataState =
    if isPtt(k) then pttInput(s, ks, ctx)
    else if isBack(k) && isConvView(s) then backInput(s, ks)
    else if isEnter(k) && isConvView(s) then okInput(s, ks, ctx)
    else if !Shell.isPressed(ks) then s
    else s.view match
      case _: VContacts     => contactsInput(s, k, ctx)
      case _: VConversation => conversationInput(s, k, ctx)

  def isPtt(k: Key): Boolean = k match
    case KPtt() => true
    case _      => false

  def isBack(k: Key): Boolean = k match
    case KBack() => true
    case _       => false

  def isEnter(k: Key): Boolean = k match
    case KEnter() => true
    case _        => false

  def isConvView(s: WataState): Boolean = s.view match
    case _: VConversation => true
    case _                => false

  /** red in a conversation: tap = back on release; a hold is timed by
   *  `tickTimers`, which fires the delete and drops the held flag, so the
   *  eventual release then does nothing. */
  def backInput(s: WataState, ks: KeyState): WataState = ks match
    case Pressed()  => withBack(s, true, 0.0)
    case Released() => if s.backHeld then withBack(withView(s, VContacts()), false, 0.0) else s
    case _          => s

  /** OK in a conversation, the same hold grammar the red key uses: a TAP plays
   *  the selected message (on release), a HOLD past `OK_HOLD_FAVORITE` toggles
   *  its favorite — `tickTimers` fires that and drops the held flag, so the
   *  eventual release does nothing. */
  def okInput(s: WataState, ks: KeyState, ctx: FrameCtx): WataState = ks match
    case Pressed()  => withOk(s, true, 0.0)
    case Released() => okRelease(s, ctx)
    case _          => s

  def okRelease(s: WataState, ctx: FrameCtx): WataState =
    var out = s
    if s.okHeld then out = playSelected(withOk(s, false, 0.0), ctx)
    out

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

  /** With no conversations to open there is nothing for OK to do — except on
   *  the screen the device actually sits on when it cannot connect, where OK
   *  is RETRY NOW: it pokes the client's login/backoff sleep so the next
   *  attempt happens immediately instead of at the end of a 60s ceiling. */
  def contactsInput(s: WataState, k: Key, ctx: FrameCtx): WataState =
    val count = convCount(ctx.snap)
    if count == 0 then retryOnOk(s, k, ctx)
    else k match
      case _: KDown => downSel(s, count)
      case _: KUp   => upSel(s)
      case _: KEnter => enterConversation(s, ctx)
      case _          => s

  def retryOnOk(s: WataState, k: Key, ctx: FrameCtx): WataState =
    if isEnter(k) then Runtime.retryNow(ctx.client)
    s

  def downSel(s: WataState, count: scala.Int): WataState =
    val sel = if s.selected < count - 1 then s.selected + 1 else s.selected
    val vis = visibleRows()
    val off = if sel >= s.scrollOffset + vis then sel - vis + 1 else s.scrollOffset
    withSel(s, sel, off)

  def upSel(s: WataState): WataState =
    val sel = if s.selected > 0 then s.selected - 1 else 0
    val off = if sel < s.scrollOffset then sel else s.scrollOffset
    withSel(s, sel, off)

  /** open the selected conversation + send a read receipt for its latest msg,
   *  and clear its undelivered marker: the mark exists to tell the user a
   *  message of theirs never arrived, and opening the conversation is where
   *  they find that out. */
  def enterConversation(s: WataState, ctx: FrameCtx): WataState =
    sendReceiptForConversation(ctx, s.selected)
    ackOutbox(ctx, s.selected)
    enterConv(s, s.selected)

  def ackOutbox(ctx: FrameCtx, idx: scala.Int): Unit =
    convAt(ctx.snap, idx) match
      case c: Some[Conversation] => ackKeys(ctx, c.value)
      case None => ()

  /** both spellings of the conversation's key — a queued send made before the
   *  DM room existed is filed under the contact. */
  def ackKeys(ctx: FrameCtx, conv: Conversation): Unit =
    if conv.roomId != "" then Runtime.sendAction(ctx.client, ActAckOutbox(conv.roomId))
    if conv.hasContact then Runtime.sendAction(ctx.client, ActAckOutbox(conv.contact.user.id))

  /** OK is absent here on purpose: in the conversation view it is a HOLD
   *  gesture (`okInput`), routed before the press-only dispatch. */
  def conversationInput(s: WataState, k: Key, ctx: FrameCtx): WataState = k match
    case _: KDown  => downMsg(s, ctx)
    case _: KUp    => upMsg(s)
    case _: KF2    => deleteSelected(s, ctx)   // sim/script delete; no F2 key on the case
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

  /** hold-OK: toggle the server's favorite marker on the selected message. The
   *  star it draws arrives through `/sync` as room state, so the row updates
   *  when the server has actually recorded it — nothing is optimistic here. */
  def favoriteSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        Runtime.sendAction(ctx.client, ActFavorite(roomIdAt(ctx.snap, s.convContactIdx), m.value.id))
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
    clampSelection(tickTimers(s, dt, ctx), ctx)

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

  val BACK_HOLD_DELETE: scala.Double = 0.8
  /** seconds of OK before the gesture becomes "favorite" instead of "play" —
   *  the same hold budget the red key's delete uses. */
  val OK_HOLD_FAVORITE: scala.Double = 0.8

  def tickTimers(s: WataState, dt: scala.Double, ctx: FrameCtx): WataState =
    val hold = if s.pttHeld then s.pttHoldTime + dt else s.pttHoldTime
    var out = withFlash(s, hold, s.statusTimer, s.sendError, s.sendOk, s.playError)
    if s.statusTimer > 0.0 then
      val t = s.statusTimer - dt
      if t <= 0.0 then out = withFlash(s, hold, 0.0, false, false, false)
      else out = withFlash(s, hold, t, s.sendError, s.sendOk, s.playError)
    if out.backHeld then
      val bt = out.backHoldTime + dt
      if bt >= BACK_HOLD_DELETE then out = withBack(deleteSelected(out, ctx), false, 0.0)
      else out = withBack(out, true, bt)
    if out.okHeld then
      val ot = out.okHoldTime + dt
      if ot >= OK_HOLD_FAVORITE then out = withOk(favoriteSelected(out, ctx), false, 0.0)
      else out = withOk(out, true, ot)
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
    // the audio thread failing is the "nothing to play it through" cause by
    // construction: the bytes were already in hand.
    case _: AePlaybackError  => withPlayErr(s, false, true, true, 2.0)
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

  /** the runtime's play failure: flash the cause and stop showing the play
   *  mark (a hung download resolves here through the request deadline). */
  def notifyPlayError(s: WataState, fetchFailed: Boolean): WataState =
    withPlayErr(s, false, true, !fetchFailed, 2.0)

  // ---- render (bitmap-font only) -----------------------------------------------
  def render(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    s.view match
      case _: VContacts     => renderContacts(s, px, ctx)
      case _: VConversation => renderConversation(s, px, ctx)
    renderStatusFlash(s, px)
    if s.pttHeld then renderRecordingOverlay(s, px)

  def renderContacts(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    if !NetStatus.everLive() then renderBoot(px, ctx)
    else if !ctx.snap.hasSelfUser && convCount(ctx.snap) == 0 then renderConnecting(px, ctx.connection)
    else
      Font.drawText(px, "WATA", 0, 0, Color.cyan, false, 0)
      renderNet(px, ctx.net)
      val count = convCount(ctx.snap)
      if count == 0 then
        Font.drawText(px, "No contacts", 3, 4, Color.midGray, false, 0)
        Font.drawText(px, "Waiting sync", 3, 5, Color.midGray, false, 0)
      else renderContactRows(s, px, ctx, count)
      // the footer is the key legend, except while Back is armed: the quit
      // confirmation is the only thing the next press does, so it takes the row
      Font.drawText(px, contactsFooter(ctx), 0, FOOTER_ROW, Color.midGray, false, 0)

  def contactsFooter(ctx: FrameCtx): String =
    if ctx.quitArmed then "BACK again to exit" else "UP/DN sel OK open"

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
        val mark = outboxMark(ctx, c.value)
        renderOutboxMark(px, mark, row)
        if c.value.unplayedCount > 0 then
          val badge = "" + c.value.unplayedCount
          val shift = if mark == 0 then 0 else 2
          Font.drawText(px, badge, Font.COLS - badge.length - shift, row, Color.yellow, false, 0)
      case None => ()

  /** 0 = nothing pending, 1 = something of ours is still queued, 2 = something
   *  of ours will never arrive. The louder one wins: a lost message is worth
   *  more of the row than a waiting one. */
  def outboxMark(ctx: FrameCtx, conv: Conversation): scala.Int =
    var out = 0
    if convKeyed(ctx.unsent, conv) then out = 1
    if convKeyed(ctx.undelivered, conv) then out = 2
    out

  /** does one of the keys name this conversation — by room, or by contact for
   *  a DM room that did not exist when the send was queued? */
  def convKeyed(keys: List[String], conv: Conversation): Boolean =
    var out = false
    if conv.roomId != "" && hasKey(keys, conv.roomId) then out = true
    if conv.hasContact && hasKey(keys, conv.contact.user.id) then out = true
    out

  def hasKey(keys: List[String], k: String): Boolean =
    var cur = keys
    var out = false
    var going = true
    while going do
      cur match
        case h :: t =>
          if h == k then
            out = true
            going = false
          else cur = t
        case Nil => going = false
    out

  /** the mark sits in the last column, right-aligned like the favorite star,
   *  so a message going out never reflows the name. Custom glyphs (> 0x7F)
   *  can only be drawn with `drawChar`. */
  def renderOutboxMark(px: go.Bytes, mark: scala.Int, row: scala.Int): Unit =
    if mark > 0 then
      val g = if mark == 2 then Font.ICON_UNDELIV else Font.ICON_UNSENT
      val fg = if mark == 2 then Color.red else Color.yellow
      Font.drawChar(px, g, (Font.COLS - 1) * Font.GLYPH_W, 1 + row * Font.GLYPH_H, fg, false, 0)

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
      Font.drawText(px, "OK play hold=fav red=del", 0, FOOTER_ROW, Color.midGray, false, 0)

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
          renderMsgRow(px, m.value, row, fg, selected && s.playing)
        case None => ()
      i += 1

  /** the row: a mark in column 0 — the PLAY triangle while this row is the one
   *  being fetched and played, else the played check — then duration, sender,
   *  and a favorited row's STAR in the last column, right-aligned so marking a
   *  message never shifts the text. The play mark appears the instant OK is
   *  released, before the download has even started: pressing a key must show
   *  something, and a slow fetch is exactly when it matters. Both are custom
   *  glyphs (> 0x7F), so they go through `drawChar` rather than inside a
   *  `drawText` string. */
  def renderMsgRow(px: go.Bytes, m: VoiceMessage, row: scala.Int, fg: scala.Int, playing: Boolean): Unit =
    if playing then Font.drawChar(px, Font.ICON_PLAY, 0, 1 + row * Font.GLYPH_H, fg, false, 0)
    else if m.isPlayed then Font.drawChar(px, Font.ICON_CHECK, 0, 1 + row * Font.GLYPH_H, fg, false, 0)
    val dur = durStr(m.durationMs)
    val col = if m.isPlayed || playing then 1 else 0
    Font.drawText(px, dur, col, row, fg, false, 0)
    val sender = clip(m.sender.displayName, 8)
    Font.drawText(px, sender, col + dur.length + 1, row, fg, false, 0)
    if m.isFavorite then
      Font.drawChar(px, Font.ICON_STAR, (Font.COLS - 1) * Font.GLYPH_W, 1 + row * Font.GLYPH_H,
        fg, false, 0)

  def renderStatusFlash(s: WataState, px: go.Bytes): Unit =
    if s.statusTimer > 0.0 then
      if s.sendError then Font.drawText(px, "SEND FAILED", 3, 9, Color.red, false, 0)
      else if s.playError then Font.drawText(px, playErrMsg(s), 3, 9, Color.red, false, 0)
      else if s.sendOk then Font.drawText(px, "SENT", 8, 9, Color.green, false, 0)

  /** the two play failures the user can act on differently: the network could
   *  not give us the message, or this device cannot play one. */
  def playErrMsg(s: WataState): String =
    if s.noAudio then "NO AUDIO" else "PLAY FAILED"

  def renderRecordingOverlay(s: WataState, px: go.Bytes): Unit =
    val barY = Display.H - 24
    Draw.fillRect(px, 0, barY, Display.W, 24, Color.red)
    val secs = s.pttHoldTime.toInt
    val tenths = (s.pttHoldTime * 10.0).toInt % 10
    val txt = "REC " + secs + "." + tenths + "s"
    Font.drawTextCentered(px, txt, (barY + 8) / Font.GLYPH_H, Color.white, false, 0)

  /** THE BOOT SCREEN — what the applet shows from session start until the link
   *  has been live once (`NetStatus.everLive`). The device boots into wata
   *  before the network and the modem are up, so the first seconds of every
   *  power-on are a state the client cannot distinguish from a failure; naming
   *  it an error there would teach a kid that the radio is broken every morning.
   *  So while nothing has actually failed the body says what is happening, in
   *  one calm centered line.
   *
   *  Once something HAS failed it says so, latch or not (plan 0022): a client
   *  that never got its first connection is exactly the case the old
   *  `everLive`-gated error text could never reach, and "waiting for network"
   *  under a live wifi glyph is a lie the device sat on for hours. A transport
   *  failure and a REJECTED login are different sentences, because they need
   *  different actions — one is "the server is not reachable", the other is
   *  "this account is not accepted, look at the server".
   *
   *  Both live keys are named on the footer, because this screen is where a
   *  stuck user presses things: OK retries now (resetting the client's
   *  backoff), Back is the two-step exit.
   *
   *  Static on purpose: the header's `..` is already the one moving thing on
   *  the screen while the client is reconnecting, and a second animation under
   *  it would be noise rather than information. (Were this to animate, it would
   *  have to ride `NetState.blink`, whose phase resets on a health change —
   *  the discipline that keeps a scripted frame reproducible.) */
  def renderBoot(px: go.Bytes, ctx: FrameCtx): Unit =
    Font.drawText(px, "WATA", 0, 0, Color.cyan, false, 0)
    renderNet(px, ctx.net)
    val sub = bootSubMsg(ctx.net, ctx.connection)
    // one calm line sits centered; a failure's two lines straddle that row
    val head = if sub == "" then 7 else 6
    Font.drawTextCentered(px, bootMsg(ctx.net, ctx.connection), head, bootColor(ctx.connection), false, 0)
    if sub != "" then Font.drawTextCentered(px, sub, 8, Color.midGray, false, 0)
    Font.drawTextCentered(px, bootKeys(ctx), FOOTER_ROW, Color.midGray, false, 0)

  /** the headline: the transport being unavailable outright, then the two
   *  failure states, then the two calm waiting states ("starting up" until
   *  there is BOTH an interface and a client trying to use it; "waiting for
   *  network" once the pipe is there and the sync loop is connecting). Live
   *  never reaches here — the first live frame latches `everLive` and the
   *  ordinary UI takes over for the session. */
  def bootMsg(net: NetState, c: ConnectionState): String =
    if FbCaps.transportUnavailable() then "transport unavailable"
    else if isAuthRejected(c) then "account rejected"
    else if isConnError(c) then "can't reach server"
    else if NetStatus.hasInterface(net.pipe) && !NetStatus.isDown(net.health) then "waiting for network"
    else "starting up..."

  /** the second line: what to do about it. Empty for the calm states, which
   *  need no instruction. */
  def bootSubMsg(net: NetState, c: ConnectionState): String =
    if FbCaps.transportUnavailable() then "check config"
    else if isAuthRejected(c) then "check server"
    else if isConnError(c) then "retrying..."
    else ""

  def bootColor(c: ConnectionState): scala.Int =
    if FbCaps.transportUnavailable() || isAuthRejected(c) || isConnError(c) then Color.red
    else Color.midGray

  /** the footer names the live keys — and, once Back is armed, replaces them
   *  with the confirmation, since that is the only thing the next press does. */
  def bootKeys(ctx: FrameCtx): String =
    if ctx.quitArmed then "BACK again to exit" else "OK retry  BACK exit"

  def isAuthRejected(c: ConnectionState): Boolean = c match
    case _: ConnAuthRejected => true
    case _                   => false

  def isConnError(c: ConnectionState): Boolean = c match
    case _: ConnError => true
    case _            => false

  def renderConnecting(px: go.Bytes, c: ConnectionState): Unit =
    Font.drawText(px, connectingMsg(c), 1, 2, Color.midGray, false, 0)

  /** the CONNECTIVITY element, right-aligned in the header — the slot the old
   *  `ok`/`..`/`ERR`/`off` indicator held, replacing it rather than sitting
   *  beside it (two indicators would have to agree). One pipe mark (the wifi
   *  or cellular glyph on the device, a plain `NET` off it, `OFF` when no
   *  interface has an address) plus `..` while the client is reconnecting,
   *  alternating on the blink phase. The mark is ANCHORED at the right edge
   *  and the dots blink in a fixed slot to its left — the blink must never
   *  reflow the mark (a mark that shuffles with the phase reads as glitch).
   *  The 1px status line derives from the same `NetState`
   *  (`ShellStatus.fromNet`), so the two always agree. */
  def renderNet(px: go.Bytes, net: NetState): Unit =
    val g = NetStatus.glyph(net.pipe)
    val text = if g >= 0 then "" else NetStatus.label(net.pipe)
    val dots = if NetStatus.showsDots(net) then ".." else ""
    val markCols = if g >= 0 then 1 else text.length
    val col = Font.COLS - markCols
    val fg = NetStatus.color(net)
    if g >= 0 then Font.drawChar(px, g, col * Font.GLYPH_W, 1, fg, false, 0)
    else Font.drawText(px, text, col, 0, fg, false, 0)
    if dots != "" then Font.drawText(px, dots, col - 2, 0, fg, false, 0)

  // ---- string / snapshot helpers --------------------------------------------
  def connectingMsg(c: ConnectionState): String = c match
    case _: Disconnected => "Disconnected"
    case _: Connecting   => "Connecting..."
    case _: Connected    => "Logging in..."
    case _: Syncing      => "Syncing..."
    case _: ConnError    => "Connection error"
    case _: ConnAuthRejected => "Account rejected"

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
 *  snapshot, the connection, the frame's computed connectivity (netstatus.scala
 *  — the header element and the status line both read it, so they cannot
 *  disagree), the matrix client (action queue), and the audio thread's
 *  command/event mailboxes. The settings applet simply ignores the fields it
 *  doesn't need. Built once per frame by the UI loop and passed through the
 *  `Applet` interface. */
case class FrameCtx(
  snap: StateSnapshot,
  connection: ConnectionState,
  net: NetState,
  client: MatrixClient,
  audioCmds: sgo.Chan[AudioCmd],
  audioEvts: sgo.Chan[AudioEvt],
  // the outbox markers this frame draws (plan 0022): the conversation keys —
  // a room id, or a contact id for a DM room that does not exist yet — with a
  // send still queued, and the ones that lost a message for good. They arrive
  // as `EvOutbox` on the ordinary event path and are carried here like the
  // snapshot; the render path never reads the outbox itself.
  unsent: List[String],
  undelivered: List[String],
  // is the two-step quit armed (ui.scala owns the window)? The boot screen is
  // where the confirmation has to be legible — it is the screen a stuck user
  // is pressing keys on.
  quitArmed: Boolean
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

/** `armed` is the action-row confirmation latch: OK on a power row or a
 *  toggle arms it, a second OK runs the action, any other key drops it.
 *  `ipText`/`cellText`/`wifiText` are the diagnostics rows' cached values,
 *  re-read by `refreshDiag` every `DIAG_REFRESH` frames (`diagLeft` counts
 *  down to the next read). `netLine1`/`netLine2` hold the last net test's
 *  verdicts ("" = never run this session) and `actionMsg` the last toggle's
 *  failure text — an action that did not do what the row says it does has to
 *  say so rather than leave the row looking untouched. */
case class SettingsState(
  selected: scala.Int,
  brightness: scala.Int,
  echo: EchoState,
  screenTimeoutIdx: scala.Int,
  connected: Boolean,
  armed: Boolean,
  ipText: String,
  cellText: String,
  wifiText: String,
  netLine1: String,
  netLine2: String,
  actionMsg: String,
  diagLeft: scala.Int
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
  // menu items: 0 echo, 1 brightness, 2 screen_off, 3 disconnect, 4 info,
  // then the diagnostics absorbed from system-menu (plan 0003, phase 5):
  // 5 ip and 6 cell data (info), 7 net test, 8 wifi and 9 cellular-data
  // toggles, 10-12 the power actions.
  //
  // There is deliberately NO display-name row: a person's name is the
  // account's, set by whoever administers the server (the admin interface,
  // plan 0021), not something a handset picks from a list of presets.
  val N_ITEMS = 13
  val ECHO = 0
  val BRIGHTNESS = 1
  val SCREEN_OFF = 2
  val DISCONNECT = 3
  val INFO = 4
  val IP_ADDR = 5
  val CELL_DATA = 6
  val NET_TEST = 7
  val WIFI_TOGGLE = 8
  val DATA_TOGGLE = 9
  val POWER_OFF = 10
  val REBOOT_BL = 11
  val REBOOT_EDL = 12

  /** menu rows visible at once — the menu is a scrolling window now that the
   *  item count outgrew the grid (six two-row items + the two detail rows).
   *  The window start is DERIVED from `selected` (no scroll state): 0 until
   *  the selection passes the last visible row, then just enough to keep it
   *  on-screen — deterministic, which is what the goldens pin. */
  val VISIBLE = 6

  /** frames between diagnostics re-reads (~5s at 30fps — system-menu's own
   *  refresh cadence; an interface lookup per frame would be needless). */
  val DIAG_REFRESH = 150

  // screen timeouts (seconds; 0 = never) + labels
  def timeoutSecs(i: scala.Int): scala.Int =
    if i == 0 then 30 else if i == 1 then 60 else if i == 2 then 120 else if i == 3 then 300 else 0
  def timeoutLabel(i: scala.Int): String =
    if i == 0 then "30s" else if i == 1 then "1m" else if i == 2 then "2m" else if i == 3 then "5m" else "Never"
  val N_TIMEOUTS = 5

  /** the menu occupies grid rows 2..12 (six items at two-row spacing), which
   *  leaves rows 13 and 14 — the last two of the 15-row landscape grid — for
   *  the selected item's detail text. Two lines is what there is room for. */
  val DETAIL_ROW = 13

  def initial(): SettingsState =
    SettingsState(0, 40, EchoIdle(), 1, true, false, "", "", "", "", "", "", 0)

  /** the boot state: preferences come back from the config store, so a device
   *  keeps the backlight and timeout its owner set. */
  def restored(p: FbPrefs): SettingsState =
    SettingsState(0, p.brightness, EchoIdle(), p.timeoutIdx, true, false,
      "", "", "", "", "", "", 0)

  def getScreenTimeout(s: SettingsState): scala.Int = timeoutSecs(s.screenTimeoutIdx)
  def getBrightness(s: SettingsState): scala.Int = s.brightness

  // ---- record withers (no `.copy` on sgola — see WataApplet) ----------------
  def withSelected(s: SettingsState, sel: scala.Int): SettingsState =
    SettingsState(sel, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withBrightness(s: SettingsState, b: scala.Int): SettingsState =
    SettingsState(s.selected, b, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withEcho(s: SettingsState, e: EchoState): SettingsState =
    SettingsState(s.selected, s.brightness, e, s.screenTimeoutIdx, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withTimeoutIdx(s: SettingsState, i: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, i, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withConnected(s: SettingsState, c: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, c,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withArmed(s: SettingsState, a: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      a, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, s.actionMsg, s.diagLeft)
  def withDiag(s: SettingsState, ip: String, cell: String, wifi: String, left: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, ip, cell, wifi, s.netLine1, s.netLine2, s.actionMsg, left)
  def withNetTest(s: SettingsState, l1: String, l2: String): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, l1, l2, s.actionMsg, s.diagLeft)
  def withActionMsg(s: SettingsState, m: String): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.ipText, s.cellText, s.wifiText, s.netLine1, s.netLine2, m, s.diagLeft)

  // ---- input (press-only) ------------------------------------------------------
  /** every key goes through `persisted`, so the three stored preferences are
   *  written back the moment one of them changes and there is exactly one
   *  place that decides when to write. */
  def handleInput(s: SettingsState, k: Key, ks: KeyState, ctx: FrameCtx): SettingsState =
    persisted(s, handleKey(s, k, ks, ctx))

  /** every key except OK drops an armed power action first — arming is a
   *  two-OK-in-a-row gesture, nothing else keeps it alive. */
  def handleKey(s: SettingsState, k: Key, ks: KeyState, ctx: FrameCtx): SettingsState =
    if !Shell.isPressed(ks) then s
    else k match
      case _: KUp    => moveUp(disarmed(s))
      case _: KDown  => moveDown(disarmed(s))
      case _: KEnter => onEnter(s, ctx)
      case _: KLeft  => onLeft(disarmed(s))
      case _: KRight => onRight(disarmed(s))
      case _           => disarmed(s)

  /** any key but OK drops the confirm latch AND the last action's message —
   *  the message belongs to the gesture that produced it, not to the row. */
  def disarmed(s: SettingsState): SettingsState =
    var out = s
    if s.armed then out = withArmed(out, false)
    if out.actionMsg != "" then out = withActionMsg(out, "")
    out

  def persisted(before: SettingsState, after: SettingsState): SettingsState =
    if prefsChanged(before, after) then
      FbConfig.savePrefs(FbPrefs(after.brightness, after.screenTimeoutIdx))
    after

  def prefsChanged(a: SettingsState, b: SettingsState): Boolean =
    a.brightness != b.brightness || a.screenTimeoutIdx != b.screenTimeoutIdx

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
    else if s.selected == DISCONNECT then doDisconnect(s, ctx)
    else if s.selected == NET_TEST then runNetTest(s)
    else if isActionRow(s.selected) then armOrRun(s)
    else s

  def isPowerRow(i: scala.Int): Boolean =
    i == POWER_OFF || i == REBOOT_BL || i == REBOOT_EDL

  /** the rows OK must be pressed twice on: the three power actions and the
   *  two radio toggles. Cutting a kid's wifi (or spending the boot's one
   *  cellular data call) by a stray keypress is the same accident a reboot
   *  is, so they share the confirm latch. */
  def isActionRow(i: scala.Int): Boolean =
    isPowerRow(i) || i == WIFI_TOGGLE || i == DATA_TOGGLE

  /** first OK arms (the detail rows turn into the confirm hint); the second
   *  runs the action and reports what it said. `Diag`'s `onDevice()` gate is
   *  the device guard: off the hardware the run is a no-op that answers "not
   *  on device", so the sim can walk the whole gesture. */
  def armOrRun(s: SettingsState): SettingsState =
    var out = withArmed(s, true)
    if s.armed then out = withActionMsg(withArmed(s, false), runAction(s))
    out

  /** "" when the action reported nothing to say (the power rows never come
   *  back at all on the hardware). */
  def runAction(s: SettingsState): String =
    var out = ""
    if isPowerRow(s.selected) then runPower(s.selected)
    else if s.selected == WIFI_TOGGLE then out = toggleWifi(s)
    else out = toggleData(s)
    out

  def runPower(i: scala.Int): Unit =
    if i == POWER_OFF then Diag.powerOff()
    else if i == REBOOT_BL then Diag.rebootBootloader()
    else Diag.rebootEdl()

  def toggleWifi(s: SettingsState): String =
    if isOn(s.wifiText) then Diag.wifiStop() else Diag.wifiStart()

  /** the data toggle acts once per OK and NEVER retries: this modem accepts a
   *  single data call per boot, so a hidden second attempt would spend it. */
  def toggleData(s: SettingsState): String =
    if dataOn(s) then Diag.dataStop() else Diag.dataStart()

  def isOn(t: String): Boolean = t == "ON"
  def dataOn(s: SettingsState): Boolean = s.cellText.startsWith("up")

  /** the data row's ON/OFF, derived from the same cellular text the info row
   *  shows ("n/a" off-device, so the toggle reads "n/a" too). */
  def dataState(s: SettingsState): String =
    var out = Diag.UNAVAILABLE
    if s.cellText.startsWith("up") then out = "ON"
    else if s.cellText.startsWith("off") then out = "OFF"
    out

  /** the net test is synchronous — a few seconds of pings block the frame
   *  loop, exactly as system-menu's own net test blocks its menu. Off-device
   *  it runs nothing and says "n/a". */
  def runNetTest(s: SettingsState): SettingsState =
    val r = Diag.netTest()
    withNetTest(s, r.line1, r.line2)

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
    else if s.selected == SCREEN_OFF then withTimeoutIdx(s, decMod(s.screenTimeoutIdx, N_TIMEOUTS))
    else s

  def onRight(s: SettingsState): SettingsState =
    if s.selected == BRIGHTNESS then brightnessUp(s)
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
  /** the diagnostics refresh is the only per-frame work; echo events arrive
   *  via `Shell.routeAudio` -> `onEcho`, NOT a drain — the shell owns the
   *  mailbox's single drain (plan 0009). */
  def update(s: SettingsState, dt: scala.Double, ctx: FrameCtx): SettingsState =
    refreshDiag(s)

  /** re-read the IP / cellular info rows every `DIAG_REFRESH` frames (the
   *  first frame reads immediately — `diagLeft` starts at 0). Off-device
   *  both reads answer a constant "n/a", which keeps the frames the goldens
   *  pin independent of when a refresh lands. */
  def refreshDiag(s: SettingsState): SettingsState =
    if s.diagLeft > 0 then withDiag(s, s.ipText, s.cellText, s.wifiText, s.diagLeft - 1)
    else withDiag(s, Diag.wlanIp(), Diag.cellData(), Diag.wifiState(), DIAG_REFRESH)

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
    val start = windowStart(s)
    var i = start
    while i < start + VISIBLE do
      val row = 2 + (i - start) * 2
      val sel = i == s.selected
      if sel then Draw.fillRect(px, 0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)
      val fg = if sel then Color.black else Color.green
      renderItem(s, px, i, row, fg)
      i += 1
    renderScrollCues(px, start)

  /** first visible item: 0 until the selection passes the window's last row,
   *  then whatever keeps it on the last row (derived, not stored — see
   *  `VISIBLE`). */
  def windowStart(s: SettingsState): scala.Int =
    var w = s.selected - (VISIBLE - 1)
    if w < 0 then w = 0
    w

  /** "^"/"v" in the last column of the first/last menu rows when the window
   *  has items above/below it. */
  def renderScrollCues(px: go.Bytes, start: scala.Int): Unit =
    if start > 0 then Font.drawText(px, "^", 25, 2, Color.midGray, false, 0)
    if start + VISIBLE < N_ITEMS then
      Font.drawText(px, "v", 25, 2 + (VISIBLE - 1) * 2, Color.midGray, false, 0)

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
    else if i == DISCONNECT then
      Font.drawText(px, "Network", 0, row, fg, false, 0)
      var netTxt = "OFF"
      var netFg = Color.red
      if s.connected then
        netTxt = "ON"
        netFg = Color.green
      Font.drawText(px, netTxt, 11, row, netFg, false, 0)
    else if i == INFO then
      Font.drawText(px, "Device Info", 0, row, fg, false, 0)
    else if i == IP_ADDR then
      Font.drawText(px, "IP", 0, row, fg, false, 0)
      Font.drawText(px, WataLogic.clip(s.ipText, 21), 4, row, fg, false, 0)
    else if i == CELL_DATA then
      Font.drawText(px, "Cell data", 0, row, fg, false, 0)
      Font.drawText(px, WataLogic.clip(s.cellText, 14), 11, row, fg, false, 0)
    else if i == NET_TEST then
      Font.drawText(px, "Net test", 0, row, fg, false, 0)
      Font.drawText(px, netTestStatus(s), 11, row, fg, false, 0)
    else if i == WIFI_TOGGLE then
      Font.drawText(px, "Wifi", 0, row, fg, false, 0)
      Font.drawText(px, s.wifiText, 11, row, fg, false, 0)
    else if i == DATA_TOGGLE then
      Font.drawText(px, "Data link", 0, row, fg, false, 0)
      Font.drawText(px, dataState(s), 11, row, fg, false, 0)
    else if i == POWER_OFF then
      Font.drawText(px, "Power off", 0, row, fg, false, 0)
    else if i == REBOOT_BL then
      Font.drawText(px, "Reboot to BL", 0, row, fg, false, 0)
    else
      Font.drawText(px, "Reboot to EDL", 0, row, fg, false, 0)

  /** the selected item's detail, on the two grid rows left below the menu. */
  def renderDetail(s: SettingsState, px: go.Bytes, ctx: FrameCtx): Unit =
    val row = DETAIL_ROW
    if s.selected == INFO then
      renderBattery(px, row)
      Font.drawText(px, "Mem:" + Diag.memAvail() + " wata-fb", 0, row + 1, Color.midGray, false, 0)
    else if s.selected == ECHO then
      Font.drawText(px, "Records 2s, plays", 0, row, Color.midGray, false, 0)
      Font.drawText(px, "back thru speaker", 0, row + 1, Color.midGray, false, 0)
    else if s.selected == DISCONNECT then
      if s.connected then Font.drawText(px, "OK to disconnect", 0, row, Color.midGray, false, 0)
      else Font.drawText(px, "Restart to reconn", 0, row, Color.midGray, false, 0)
    else if s.selected == BRIGHTNESS then
      Font.drawText(px, "</> adjust", 0, row, Color.midGray, false, 0)
    else if s.selected == SCREEN_OFF then
      Font.drawText(px, "</> timeout", 0, row, Color.midGray, false, 0)
      Font.drawText(px, "Any key wakes", 0, row + 1, Color.midGray, false, 0)
    else if s.selected == IP_ADDR then
      Font.drawText(px, "wlan0 IPv4 address", 0, row, Color.midGray, false, 0)
    else if s.selected == CELL_DATA then
      Font.drawText(px, "ppp0 link + signal", 0, row, Color.midGray, false, 0)
      renderCellAddr(px, row + 1)
    else if s.selected == NET_TEST then
      renderNetTestDetail(s, px, row)
    else
      renderActionDetail(s, px, row)

  /** the ppp0 address, which the row itself has no room for next to the
   *  signal strength; nothing to draw when the link is down. */
  def renderCellAddr(px: go.Bytes, row: scala.Int): Unit =
    val addr = Diag.cellAddr()
    if addr != "" then Font.drawText(px, "ppp0 " + addr, 0, row, Color.midGray, false, 0)

  /** before a run, what OK will do; after one, the four verdicts. */
  def renderNetTestDetail(s: SettingsState, px: go.Bytes, row: scala.Int): Unit =
    if s.netLine1 == "" then
      Font.drawText(px, "OK pings gw/DNS", 0, row, Color.midGray, false, 0)
      Font.drawText(px, "takes a few seconds", 0, row + 1, Color.midGray, false, 0)
    else
      Font.drawText(px, s.netLine1, 0, row, Color.midGray, false, 0)
      Font.drawText(px, s.netLine2, 0, row + 1, Color.midGray, false, 0)

  /** the action rows' detail doubles as the confirmation prompt: unarmed it
   *  says what OK starts, armed it says what the NEXT OK does — in red, with
   *  the escape route on the second line. A run that reported something (a
   *  failed toggle, or the off-device no-op) shows that instead, in red,
   *  until the next keypress. */
  def renderActionDetail(s: SettingsState, px: go.Bytes, row: scala.Int): Unit =
    if s.actionMsg != "" then
      Font.drawText(px, WataLogic.clip(s.actionMsg, 26), 0, row, Color.red, false, 0)
      Font.drawText(px, "not retried", 0, row + 1, Color.midGray, false, 0)
    else if s.armed then
      Font.drawText(px, "OK again: " + actionVerb(s), 0, row, Color.red, false, 0)
      Font.drawText(px, "other keys cancel", 0, row + 1, Color.midGray, false, 0)
    else
      Font.drawText(px, "OK arms " + actionVerb(s), 0, row, Color.midGray, false, 0)
      Font.drawText(px, "then OK again runs", 0, row + 1, Color.midGray, false, 0)

  /** what the armed OK would do — for a toggle that is the OPPOSITE of the
   *  state the row shows. */
  def actionVerb(s: SettingsState): String =
    val i = s.selected
    if i == POWER_OFF then "power off"
    else if i == REBOOT_BL then "reboot to BL"
    else if i == REBOOT_EDL then "reboot to EDL"
    else if i == WIFI_TOGGLE then toggleVerb("wifi", isOn(s.wifiText))
    else toggleVerb("data", dataOn(s))

  def toggleVerb(what: String, on: Boolean): String =
    var out = what + " on"
    if on then out = what + " off"
    out

  /** Device Info's two lines: battery + uptime, then free memory next to what
   *  is running. sysfs has no battery node off-device, and
   *  `readBatteryPercent` says so with -1 rather than by failing — the
   *  battery part is simply left out then, and uptime/memory answer "n/a". */
  def renderBattery(px: go.Bytes, row: scala.Int): Unit =
    val pct = Led.readBatteryPercent()
    var line = "Up:" + Diag.uptime()
    if pct >= 0 then line = "Bat:" + pct + "% " + line
    Font.drawText(px, line, 0, row, Color.midGray, false, 0)

  /** the net-test row's own value: what OK does, or that it has run (the
   *  verdicts themselves need the detail block's width). */
  def netTestStatus(s: SettingsState): String =
    if s.netLine1 == "" then "OK=run"
    else if s.netLine1 == Diag.UNAVAILABLE then Diag.UNAVAILABLE
    else "done"

  def echoStatus(e: EchoState): String = e match
    case _: EchoIdle      => "OK=go"
    case _: EchoRecording => "OK=stop"
    case _: EchoPlaying   => "play.."
    case _: EchoDone      => "done!"
    case _: EchoErr       => "error"

  def isEchoErr(e: EchoState): Boolean = e match
    case _: EchoErr => true
    case _            => false
