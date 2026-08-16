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
  // the message currently playing, so the read receipt can be sent when the
  // audio FINISHES rather than when it starts. Both empty when nothing plays.
  playingRoom: String,
  playingId: String,
  sendError: Boolean,
  sendOk: Boolean,
  playError: Boolean,
  // was the play failure "there is nothing to play it through" rather than
  // "it could not be fetched"? Two causes, two sentences on screen.
  noAudio: Boolean,
  // the RECORDING failed — the microphone's fault, not the network's. Its
  // own flag because it draws its own sentence: blaming a TCC-denied mic on
  // the send path reads as "SEND FAILED" and points the user at the wrong
  // fix (plan 0045 slice 4).
  micError: Boolean,
  statusTimer: scala.Double,
  backHeld: Boolean,
  backHoldTime: scala.Double,
  okHeld: Boolean,
  okHoldTime: scala.Double,
  // the recording meter's level (0..32), the peak of the last captured 40ms
  // period — fed by `AeCaptureLevel`, reset to 0 on PTT press (plan 0042).
  captureLevel: scala.Int,
  // the event id the message cursor is EXPLICITLY holding, or "" while the
  // cursor sits on row 0 tracking the newest message. `msgSelected` stays the
  // index the renderer draws; this is what re-locates it when the list shifts
  // under the cursor — the list is newest-first, so every arrival would
  // otherwise slide the selection one row older (`clampMessages`).
  msgAnchorId: String,
  // user-initiated one-shots (delete/favorite) the action queue REFUSED, kept
  // in offer order and re-offered by `update` each frame until the queue takes
  // them (plan 0046): a full queue must not silently eat a delete. Session
  // intents only — a new session starts from `initial()`, so nothing here
  // outlives the session that meant it.
  pendingOneshots: List[Action]
)

object WataLogic:
  val FONT_ROWS_HEADER = 2 // header grid rows before the list (bitmap layout)
  val FOOTER_ROW = Font.ROWS - 1

  def initial(): WataState =
    WataState(VContacts(), 0, 0, 0, 0, 0, false, 0.0, false, "", "", false, false, false, false, false, 0.0,
      false, 0.0, false, 0.0, 0, "", Nil)

  /** visible list rows between header and footer (bitmap grid). */
  def visibleRows(): scala.Int = FOOTER_ROW - FONT_ROWS_HEADER

  // ---- record withers (no `.copy` on sgola — GoEmitter skips synthetic
  //      `copy`; the house style reconstructs the record explicitly) ----------
  def withView(s: WataState, v: WataView): WataState =
    WataState(v, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  def withSel(s: WataState, sel: scala.Int, off: scala.Int): WataState =
    WataState(s.view, sel, off, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  /** Opens at row 0, which is the NEWEST message now that the list comes back
   *  newest first — the one somebody just pressed the LED for. It used to be
   *  the oldest, so a busy conversation opened on a message from days ago and
   *  had to be scrolled to the bottom before anything could be played. */
  def enterConv(s: WataState, idx: scala.Int): WataState =
    WataState(VConversation(), s.selected, s.scrollOffset, idx, 0, 0,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, "", s.pendingOneshots)

  /** cursor move that keeps the current anchor — the per-frame reconcile's
   *  found-the-anchor path and every non-cursor wither go through here. */
  def withMsgSel(s: WataState, sel: scala.Int, scr: scala.Int): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, sel, scr,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  /** cursor move that also RE-DECIDES the anchor — what an explicit up/down
   *  and the vanished-anchor fallback use. */
  def withMsgAnchor(s: WataState, sel: scala.Int, scr: scala.Int, anchor: String): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, sel, scr,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, anchor, s.pendingOneshots)

  def withPtt(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      held, hold, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  /** the recording meter's level — the only field `AeCaptureLevel` moves. */
  def withCapLevel(s: WataState, level: scala.Int): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, level, s.msgAnchorId, s.pendingOneshots)

  /** `room`/`id` name what is playing, and are cleared when it stops — a
   *  playback target that outlives the playback would receipt the wrong
   *  message the next time audio ends. */
  def withPlaying(s: WataState, playing: Boolean, room: String, id: String): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, room, id, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer, s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  /** the full status-flash tuple (hold + timer + the four flash flags); the
   *  play-failure CAUSE rides along unchanged. */
  def withFlash(s: WataState, hold: scala.Double, timer: scala.Double,
                sendErr: Boolean, sendOk: Boolean, playErr: Boolean, micErr: Boolean): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, hold, s.playing, s.playingRoom, s.playingId, sendErr, sendOk, playErr, s.noAudio, micErr, timer, s.backHeld, s.backHoldTime,
      s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  /** a play that failed: the flash, its cause, and `playing` dropped — a
   *  playback indicator that outlives the playback is a lie. */
  def withPlayErr(s: WataState, playing: Boolean, playErr: Boolean, noAudio: Boolean,
                  timer: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, playing, "", "", s.sendError, s.sendOk, playErr, noAudio, s.micError, timer,
      s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  def withOk(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer,
      s.backHeld, s.backHoldTime, held, hold, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  def withBack(s: WataState, held: Boolean, hold: scala.Double): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer,
      held, hold, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, s.pendingOneshots)

  def withPendingOneshots(s: WataState, pending: List[Action]): WataState =
    WataState(s.view, s.selected, s.scrollOffset, s.convContactIdx, s.msgSelected, s.msgScroll,
      s.pttHeld, s.pttHoldTime, s.playing, s.playingRoom, s.playingId, s.sendError, s.sendOk, s.playError, s.noAudio, s.micError, s.statusTimer,
      s.backHeld, s.backHoldTime, s.okHeld, s.okHoldTime, s.captureLevel, s.msgAnchorId, pending)

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
      // level 0 until the first period's tick arrives — a stale level from
      // the previous recording must not open the meter wide.
      out = withCapLevel(withPtt(s, true, 0.0), 0)
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

  /** Open the selected conversation and clear its undelivered marker: the
   *  mark exists to tell the user a message of theirs never arrived, and
   *  opening the conversation is where they find that out.
   *
   *  Opening sends NO read receipt. On a text client, a message on screen has
   *  been read and a receipt is the best signal available; here the message IS
   *  audio, and seeing a row is not hearing it. The receipt goes out when the
   *  clip finishes playing (`onAudioEvent`), which is the signal this product
   *  actually has — and the sender's double-check then means "heard", not
   *  "was in the room when it arrived". */
  def enterConversation(s: WataState, ctx: FrameCtx): WataState =
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
    case _: KUp    => upMsg(s, ctx)
    case _: KF2    => deleteSelected(s, ctx)   // sim/script delete; no F2 key on the case
    case _           => s

  def downMsg(s: WataState, ctx: FrameCtx): WataState =
    val count = msgCount(ctx.snap, s.convContactIdx)
    if count > 0 && s.msgSelected < count - 1 then
      val sel = s.msgSelected + 1
      val vis = visibleRows()
      val scr = if sel >= s.msgScroll + vis then sel - vis + 1 else s.msgScroll
      withMsgAnchor(s, sel, scr, anchorAt(ctx.snap, s.convContactIdx, sel))
    else s

  def upMsg(s: WataState, ctx: FrameCtx): WataState =
    if s.msgSelected > 0 then
      val sel = s.msgSelected - 1
      val scr = if sel < s.msgScroll then sel else s.msgScroll
      withMsgAnchor(s, sel, scr, anchorAt(ctx.snap, s.convContactIdx, sel))
    else s

  /** the anchor a cursor at `sel` carries: the message's own event id, or ""
   *  on row 0 — the newest row is not anchored, it TRACKS newest, so a
   *  cursor left where `enterConv` put it keeps pointing at each arrival
   *  (the walkie-talkie default: the top row is the message to play next). */
  def anchorAt(snap: StateSnapshot, convIdx: scala.Int, sel: scala.Int): String =
    if sel <= 0 then ""
    else selectedMsg(snap, convIdx, sel) match
      case m: Some[VoiceMessage] => m.value.id
      case None => ""

  /** OK on a message: download-and-play, remembering WHICH message so the
   *  receipt can follow the audio rather than the keypress. */
  def playSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        Runtime.sendAction(ctx.client, ActPlay(m.value.mxcUrl))
        withPlaying(s, true, roomIdAt(ctx.snap, s.convContactIdx), m.value.id)
      case None => s

  def deleteSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        offerOneshot(s, ctx, ActRedact(roomIdAt(ctx.snap, s.convContactIdx), m.value.id))
      case None => s

  /** hold-OK: toggle the server's favorite marker on the selected message. The
   *  star it draws arrives through `/sync` as room state, so the row updates
   *  when the server has actually recorded it — nothing is optimistic here. */
  def favoriteSelected(s: WataState, ctx: FrameCtx): WataState =
    selectedMsg(ctx.snap, s.convContactIdx, s.msgSelected) match
      case m: Some[VoiceMessage] =>
        offerOneshot(s, ctx, ActFavorite(roomIdAt(ctx.snap, s.convContactIdx), m.value.id))
      case None => s

  // ---- pending one-shots (plan 0046) ---------------------------------------------
  // A delete or favorite is a user INTENT, and no later action supersedes it —
  // so unlike receipts and name pushes, a queue-refused one may not drop. The
  // intent is kept locally and delivery retries until the queue takes it (the
  // outbox's shape, session-scoped): local-first, the field being the design
  // case — cellular drops and wifi<->cellular switches stall the action loop
  // exactly when someone tidies a conversation.

  /** at most this many refused one-shots are held; past it a new one drops
   *  silently, which is the pre-plan-0046 behavior with a far narrower window
   *  (the queue itself is 64 deep and stuck for minutes before this fills). */
  val MAX_PENDING_ONESHOTS = 8

  /** offer a one-shot to the action queue; a refused one joins
   *  `pendingOneshots` (in offer order) unless the same intent is already
   *  waiting — a second delete of the same message adds nothing, and a second
   *  favorite keeps the single toggle the user's star will reflect. */
  def offerOneshot(s: WataState, ctx: FrameCtx, a: Action): WataState =
    if ClientHandle.sendAction(ctx.client, a) then s
    else if hasOneshot(s.pendingOneshots, a) then s
    else if lenActions(s.pendingOneshots) >= MAX_PENDING_ONESHOTS then s
    else withPendingOneshots(s, appendAction(s.pendingOneshots, a))

  /** re-offer the HEAD each frame until the queue accepts — one per frame is
   *  plenty (the queue drains request-by-request) and keeps the frame cheap. */
  def retryOneshots(s: WataState, ctx: FrameCtx): WataState = s.pendingOneshots match
    case h :: t =>
      if ClientHandle.sendAction(ctx.client, h) then withPendingOneshots(s, t) else s
    case Nil => s

  /** the same intent: one-shot kinds compare by their target, not by record
   *  equality — the kinds are closed here (only redact/favorite are offered). */
  def sameOneshot(a: Action, b: Action): Boolean = a match
    case x: ActRedact => b match
      case y: ActRedact => x.roomId == y.roomId && x.eventId == y.eventId
      case _ => false
    case x: ActFavorite => b match
      case y: ActFavorite => x.roomId == y.roomId && x.eventId == y.eventId
      case _ => false
    case _ => false

  def hasOneshot(l: List[Action], a: Action): Boolean = l match
    case h :: t => if sameOneshot(h, a) then true else hasOneshot(t, a)
    case Nil => false

  def lenActions(l: List[Action]): scala.Int = l match
    case h :: t => 1 + lenActions(t)
    case Nil => 0

  /** tail-append, keeping offer order (bounded by MAX_PENDING_ONESHOTS). */
  def appendAction(l: List[Action], a: Action): List[Action] = l match
    case h :: t => h :: appendAction(t, a)
    case Nil => a :: Nil

  // ---- receipts ------------------------------------------------------------------
  /** The ONE place a receipt is sent, called from `AePlaybackDone`. There is
   *  deliberately no "receipt the conversation's latest" path: a receipt here
   *  asserts the audio was heard, which only playback can know. */
  def pushReceipt(ctx: FrameCtx, roomId: String, eventId: String): Unit =
    if roomId != "" && eventId != "" then Runtime.sendAction(ctx.client, ActReceipt(roomId, eventId))

  // ---- per-frame update --------------------------------------------------------
  /** tick hold-time + status flash, then reconcile the cursors. Audio events
   *  arrive via `Shell.routeAudio` -> `onAudioEvent`, NOT a drain here — the
   *  shell owns the mailbox's single drain (plan 0009). */
  def update(s: WataState, dt: scala.Double, ctx: FrameCtx): WataState =
    clampSelection(tickTimers(retryOneshots(s, ctx), dt, ctx), ctx)

  /** Reconcile the cursors with the live snapshot. The lists change under the
   *  cursor without any input — an arrival puts a new row on top (the list is
   *  newest-first), a redaction drops one, a peer leaving drops a
   *  conversation — and a selection left past the end highlights nothing and
   *  plays nothing. So each frame re-locates the message cursor's ANCHOR and
   *  pulls both cursors back inside the list, dragging the scroll windows
   *  after them. */
  def clampSelection(s: WataState, ctx: FrameCtx): WataState =
    var out = clampContacts(s, convCount(ctx.snap))
    out = clampMessages(out, ctx)
    out

  def clampContacts(s: WataState, count: scala.Int): WataState =
    val sel = clampIdx(s.selected, count)
    withSel(s, sel, clampScroll(s.scrollOffset, sel))

  /** The message cursor holds an INDEX (what renders) but is anchored by the
   *  selected message's EVENT ID: an unanchored cursor ("" — row 0, where
   *  `enterConv` starts it) just clamps, so it keeps tracking the newest
   *  message as arrivals push rows down; an anchored one (moved by up/down)
   *  is re-located by id each frame, so an arrival shifts the index and the
   *  highlight stays on the SAME message. An anchor that vanished (its
   *  message was redacted) falls back to the nearest surviving index —
   *  clamped — and re-anchors there. */
  def clampMessages(s: WataState, ctx: FrameCtx): WataState =
    val count = msgCount(ctx.snap, s.convContactIdx)
    var out = s
    if s.msgAnchorId == "" then
      val sel = clampIdx(s.msgSelected, count)
      out = withMsgSel(s, sel, clampScroll(s.msgScroll, sel))
    else
      val at = msgIndexOf(ctx.snap, s.convContactIdx, s.msgAnchorId)
      if at >= 0 then out = withMsgSel(s, at, clampScroll(s.msgScroll, at))
      else
        val sel = clampIdx(s.msgSelected, count)
        out = withMsgAnchor(s, sel, clampScroll(s.msgScroll, sel),
          anchorAt(ctx.snap, s.convContactIdx, sel))
    out

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
    var out = withFlash(s, hold, s.statusTimer, s.sendError, s.sendOk, s.playError, s.micError)
    if s.statusTimer > 0.0 then
      val t = s.statusTimer - dt
      if t <= 0.0 then out = withFlash(s, hold, 0.0, false, false, false, false)
      else out = withFlash(s, hold, t, s.sendError, s.sendOk, s.playError, s.micError)
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
    case _: AeRecordingError => withFlash(s, s.pttHoldTime, 2.0, false, s.sendOk, s.playError, true)
    // the record loop's 25 Hz level tick — the recording meter's only feed.
    case l: AeCaptureLevel   => withCapLevel(s, l.level)
    // the clip played to the end, which is what "heard" means here: receipt
    // now, and only now. A playback that ERRORED sends nothing — a broken
    // speaker must not tell the sender their message got through.
    case _: AePlaybackDone   =>
      pushReceipt(ctx, s.playingRoom, s.playingId)
      withPlaying(s, false, "", "")
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
    var out = withFlash(s, s.pttHoldTime, 1.5, false, true, s.playError, s.micError)
    if isError then out = withFlash(s, s.pttHoldTime, 2.0, true, false, s.playError, s.micError)
    out

  /** the runtime's play failure: flash the cause and stop showing the play
   *  mark (a hung download resolves here through the request deadline). */
  def notifyPlayError(s: WataState, fetchFailed: Boolean): WataState =
    withPlayErr(s, false, true, !fetchFailed, 2.0)

  // ---- render (a `wataui` body — plan 0024) -------------------------------------
  /** ONE view per frame, painted by the framebuffer interpreter. Everything
   *  ambient the screens need is read HERE, at the call site: the session latch
   *  `NetStatus.everLive`, the app-edge `FbCaps.transportUnavailable` and the
   *  enrolment identity, since a body reads its arguments and nothing else. The
   *  one EFFECT on this path — announcing the device the first time the
   *  enrolment state appears — is here too, for the same reason. */
  def render(s: WataState, px: go.Bytes, ctx: FrameCtx): Unit =
    val everLive = NetStatus.everLive()
    FbPaint.draw(px, body(s, ctx.snap, ctx.net, ctx.connection, ctx.quitArmed,
      ctx.unsent, ctx.undelivered, everLive, FbCaps.transportUnavailable(),
      enrolSnap(ctx, everLive), Enrol.provisioning(), NetStatus.clockOk(),
      ctx.client.clock.nowUnixMillis()))

  /** the screen, then the two things that sit OVER it: the send/play status
   *  flash and the recording bar. They are children after the screen because
   *  children paint in list order, which is the same reason the old painter
   *  drew them last. */
  def body(s: WataState, snap: StateSnapshot, net: NetState, c: ConnectionState,
      quitArmed: Boolean, unsent: List[String], undelivered: List[String],
      everLive: Boolean, unavail: Boolean, enrol: Option[EnrolSnap], prov: Boolean,
      clockOk: Boolean, nowMs: Long): View =
    val screen: View = s.view match
      case _: VContacts =>
        bodyContacts(s, snap, net, c, quitArmed, unsent, undelivered, everLive, unavail, enrol,
          prov, clockOk)
      case _: VConversation => bodyConversation(s, snap, nowMs)
    var kids: List[Keyed] = Nil
    if s.pttHeld then kids = Keyed("rec", recordingView(s)) :: kids
    if s.statusTimer > 0.0 then kids = Keyed("flash", statusFlashView(s)) :: kids
    VGroup(Keyed("screen", screen) :: ListOps.reverse(kids))

  /** the enrolment screen's data, and the announce that goes with the frame it
   *  first appears on — `Some` exactly when the transport has refused this node
   *  id and the session has never been live. Reading it costs a QR encode, so
   *  it is read only when the screen is the one being drawn.
   *
   *  Announcing rides here rather than on a keypress because nobody presses
   *  anything on a handset that has never connected: the screen appearing IS
   *  the event. It is once per session, best-effort, and off the frame path
   *  (`Enrol.announceOnce` spawns it). */
  def enrolSnap(ctx: FrameCtx, everLive: Boolean): Option[EnrolSnap] =
    if everLive || !Enrol.required() then None
    else
      Enrol.announceOnce()
      Some(Enrol.snap(enrolBootHint(ctx)))

  /** four screens in one, in the order the session passes through them: the
   *  enrolment QR when the server has refused this handset outright, the boot
   *  screen until the link has been live once (`bodyBoot` — ONE definition, not
   *  a second copy of it), the connection line while the sync has not yet
   *  produced a self user or a conversation, then the list. */
  def bodyContacts(s: WataState, snap: StateSnapshot, net: NetState, c: ConnectionState,
      quitArmed: Boolean, unsent: List[String], undelivered: List[String],
      everLive: Boolean, unavail: Boolean, enrol: Option[EnrolSnap], prov: Boolean,
      clockOk: Boolean): View =
    enrol match
      case e: Some[EnrolSnap] => Enrol.body(e.value)
      case None               =>
        bodyLive(s, snap, net, c, quitArmed, unsent, undelivered, everLive, unavail, prov, clockOk)

  def bodyLive(s: WataState, snap: StateSnapshot, net: NetState, c: ConnectionState,
      quitArmed: Boolean, unsent: List[String], undelivered: List[String],
      everLive: Boolean, unavail: Boolean, prov: Boolean, clockOk: Boolean): View =
    if !everLive then bodyBoot(net, c, quitArmed, unavail, prov, clockOk)
    else if !snap.hasSelfUser && convCount(snap) == 0 then
      VText(1, 2, connectingMsg(c), Color.midGray)
    else
      val count = convCount(snap)
      // the footer is the key legend, except while Back is armed: the quit
      // confirmation is the only thing the next press does, so it takes the row
      var kids: List[Keyed] =
        Keyed("footer", VText(0, FOOTER_ROW, contactsFooter(quitArmed), Color.midGray)) :: Nil
      if count == 0 then
        kids = Keyed("empty", Views.group(
          VText(3, 4, "No contacts", Color.midGray) ::
            (VText(3, 5, "Waiting sync", Color.midGray) :: Nil))) :: kids
      else kids = Keyed("rows", contactRowsView(s, snap, unsent, undelivered, count)) :: kids
      VGroup(Keyed("title", VText(0, 0, "WATA", Color.cyan)) :: (Keyed("net", netView(net)) :: kids))

  def contactsFooter(quitArmed: Boolean): String =
    if quitArmed then "BACK again to exit" else "UP/DN sel OK open PTT talk"

  /** the visible window of contact rows, keyed on the conversation's identity —
   *  the same room-or-contact key the outbox marks are matched by. */
  def contactRowsView(s: WataState, snap: StateSnapshot, unsent: List[String],
      undelivered: List[String], count: scala.Int): View =
    val vis = visibleRows()
    val end = if count < s.scrollOffset + vis then count else s.scrollOffset + vis
    var acc: List[Keyed] = Nil
    var i = s.scrollOffset
    while i < end do
      val row = FONT_ROWS_HEADER + (i - s.scrollOffset)
      val selected = i == s.selected
      convAt(snap, i) match
        case c: Some[Conversation] =>
          val mark = outboxMark(unsent, undelivered, c.value)
          acc = Keyed(convKey(c.value), contactRowView(snap, c.value, mark, row, selected)) :: acc
        case None => ()
      i += 1
    VGroup(ListOps.reverse(acc))

  /** the row: the selection highlight first (children paint in list order),
   *  then the unplayed underline (plan 0041) — a yellow rule under any row
   *  whose count is up, persistent until played because it renders the COUNT,
   *  not the arrival edge; a count digit alone is a channel a kid never
   *  notices — the name — cyan for the family thread unless this row is the
   *  selected one, whose black-on-green has to stay legible — then the outbox
   *  mark in the last column and the unplayed badge, which slides two columns
   *  left to clear the mark when there is one. */
  def contactRowView(snap: StateSnapshot, conv: Conversation, mark: scala.Int,
      row: scala.Int, selected: Boolean): View =
    val y = 1 + row * Font.GLYPH_H
    val fg = if selected then Color.black else Color.green
    var kids: List[Keyed] = Nil
    if selected then kids = Keyed("hl", VRect(0, y, Display.W, Font.GLYPH_H, Color.green)) :: kids
    if conv.unplayedCount > 0 then
      kids = Keyed("unp", VRect(0, y + Font.GLYPH_H - 1, Display.W, 1, Color.yellow)) :: kids
    val nameColor = if isFamily(conv.convType) && !selected then Color.cyan else fg
    kids = Keyed("name", VText(0, row, clip(convName(snap, conv), 18), nameColor)) :: kids
    if mark > 0 then kids = Keyed("mark", outboxMarkView(mark, y)) :: kids
    if conv.unplayedCount > 0 then
      val badge = "" + conv.unplayedCount
      val shift = if mark == 0 then 0 else 2
      kids = Keyed("badge", VText(Font.COLS - badge.length - shift, row, badge, Color.yellow)) :: kids
    VGroup(ListOps.reverse(kids))

  /** 0 = nothing pending, 1 = something of ours is still queued, 2 = something
   *  of ours will never arrive. The louder one wins: a lost message is worth
   *  more of the row than a waiting one. */
  def outboxMark(unsent: List[String], undelivered: List[String], conv: Conversation): scala.Int =
    var out = 0
    if convKeyed(unsent, conv) then out = 1
    if convKeyed(undelivered, conv) then out = 2
    out

  /** a conversation's identity: its room, or its contact for a DM room that
   *  does not exist yet — the same pairing `convKeyed` matches outbox keys by,
   *  so a row and the marks meant for it always name the same thing. */
  def convKey(conv: Conversation): String =
    var out = ""
    if conv.hasContact then out = conv.contact.user.id
    if conv.roomId != "" then out = conv.roomId
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
   *  so a message going out never reflows the name. Custom glyphs (> 0x7F) are
   *  `VGlyph`s: inside a string they would UTF-8 encode into two wrong ones. */
  def outboxMarkView(mark: scala.Int, y: scala.Int): View =
    val g = if mark == 2 then Font.ICON_UNDELIV else Font.ICON_UNSENT
    val fg = if mark == 2 then Color.red else Color.yellow
    VGlyph((Font.COLS - 1) * Font.GLYPH_W, y, g, fg)

  /** the conversation screen: a pure function of the applet state and the
   *  frame's snapshot. Nothing here reads an atomic, a clock or the network. */
  def bodyConversation(s: WataState, snap: StateSnapshot, nowMs: Long): View =
    convAt(snap, s.convContactIdx) match
      case c: Some[Conversation] => convBodyView(s, c.value, snap, nowMs)
      case None                  => VText(3, 6, "No conversation", Color.midGray)

  def convBodyView(s: WataState, conv: Conversation, snap: StateSnapshot, nowMs: Long): View =
    // the same name the list row shows: the contact, the family name, or the
    // group's stamp name — "Chat" only when nothing knows better.
    var header = convName(snap, conv)
    if header == "" || header == "?" then header = "Chat"
    val n = msgCountList(conv.messages)
    var kids: List[Keyed] = Nil
    if n == 0 then
      kids = Keyed("empty", VText(3, 6, "No messages", Color.midGray)) ::
        (Keyed("footer", VText(0, FOOTER_ROW, "ESC back", Color.midGray)) :: Nil)
    else
      kids = Keyed("rows", msgRowsView(s, conv, n, selfIdOf(snap), nowMs)) ::
        (Keyed("footer", VText(0, FOOTER_ROW, "OK play hold=fav red=del", Color.midGray)) :: Nil)
    VGroup(Keyed("header", VText(0, 0, clip(header, 20), Color.cyan)) :: kids)

  /** the visible window of message rows, KEYED ON THE EVENT ID — the message's
   *  own identity, so a retained backend recognizes a row that scrolled rather
   *  than rewriting every row below it. A row index the list cannot answer for
   *  contributes nothing, highlight included. */
  def msgRowsView(s: WataState, conv: Conversation, n: scala.Int, selfId: String,
      nowMs: Long): View =
    val vis = visibleRows()
    val end = if n < s.msgScroll + vis then n else s.msgScroll + vis
    var acc: List[Keyed] = Nil
    var i = s.msgScroll
    while i < end do
      val row = FONT_ROWS_HEADER + (i - s.msgScroll)
      val selected = i == s.msgSelected
      msgAt(conv.messages, i) match
        case m: Some[VoiceMessage] =>
          val fg =
            if selected then Color.black
            else (if m.value.isPlayed then Color.midGray else Color.green)
          val own = selfId != "" && m.value.sender.id == selfId
          acc = Keyed(m.value.id, msgRowView(m.value, row, fg, selected, selected && s.playing, own, nowMs)) :: acc
        case None => ()
      i += 1
    VGroup(ListOps.reverse(acc))

  def selfIdOf(snap: StateSnapshot): String =
    if snap.hasSelfUser then snap.selfUser.id else ""

  /** the row, one fixed grid for every message so the columns align down the
   *  list (plan 0049): selection highlight FIRST — children paint in list
   *  order, so the filled rectangle has to precede the text it sits behind —
   *  then marks (cols 0-1), age (col 2), sender (col 6), the duration
   *  right-aligned ending at col 24, and a favorited row's STAR in the last
   *  column, so marking a message never shifts the text.
   *
   *  The mark area (cols 0-1) reads the SAME on every row (plan 0051):
   *  check one = the message is delivered (in the timeline — for an own row
   *  that says the server has it), check two = it has been HEARD by its
   *  audience — the peer for an own row (`playedByPeer`), yourself for a
   *  received one (`isPlayed`). The PLAY triangle holds the first slot while
   *  this row is the one being fetched and played, and it appears the
   *  instant OK is released, before the download has even started: pressing
   *  a key must show something, and a slow fetch is exactly when it matters.
   *  Two adjacent `ICON_CHECK` glyphs are the Zig reference's documented
   *  double-check convention (`font.zig`: "draw two 0x80 glyphs adjacent"),
   *  so no new glyph. Both mark columns are reserved on EVERY row, so all
   *  rows share one grid and a receipt arriving never reflows a row. All
   *  marks are custom glyphs (> 0x7F), so they are `VGlyph`s rather than
   *  characters inside a `VText`.
   *
   *  The sender is "me" on an own row — the reader knows their own name —
   *  and is clipped to the room left of the duration. */
  def msgRowView(m: VoiceMessage, row: scala.Int, fg: scala.Int, selected: Boolean,
      playing: Boolean, own: Boolean, nowMs: Long): View =
    val y = 1 + row * Font.GLYPH_H
    var kids: List[Keyed] = Nil
    if selected then kids = Keyed("hl", VRect(0, y, Display.W, Font.GLYPH_H, Color.green)) :: kids
    if playing then kids = Keyed("mark", VGlyph(0, y, Font.ICON_PLAY, fg)) :: kids
    else kids = Keyed("mark", VGlyph(0, y, Font.ICON_CHECK, fg)) :: kids
    if heardMark(m, own) then
      kids = Keyed("mark2", VGlyph(Font.GLYPH_W, y, Font.ICON_CHECK, fg)) :: kids
    kids = Keyed("age", VText(2, row, ageStr(nowMs, m.timestamp), fg)) :: kids
    val dur = durStr(m.durationMs)
    val durCol = 25 - dur.length
    kids = Keyed("dur", VText(durCol, row, dur, fg)) :: kids
    val name = if own then "me" else m.sender.displayName
    kids = Keyed("sender", VText(6, row, clip(name, durCol - 7), fg)) :: kids
    if m.isFavorite then
      kids = Keyed("star", VGlyph((Font.COLS - 1) * Font.GLYPH_W, y, Font.ICON_STAR, fg)) :: kids
    VGroup(ListOps.reverse(kids))

  /** the second check: the message reached its audience's ears — a peer's for
   *  an own row, yours for a received one. */
  def heardMark(m: VoiceMessage, own: Boolean): Boolean =
    if own then m.playedByPeer else m.isPlayed

  /** when a message arrived, as the row's 3-wide age column: "now" under a
   *  minute, then minutes/hours, then relative days capped at 99 (a calendar
   *  date would need month math for a column that answers "when"). A FUTURE
   *  timestamp is "now" — the handset boots at 1970 until the clock steps,
   *  and the scripted harness runs a virtual frame clock against real server
   *  timestamps; both belong in the newest bucket, and the clamp is what
   *  keeps the golden frames deterministic. */
  def ageStr(nowMs: Long, ts: Long): String =
    val diff = nowMs - ts
    if diff < 60000L then "now"
    else if diff < 3600000L then "" + (diff / 60000L).toInt + "m"
    else if diff < 86400000L then "" + (diff / 3600000L).toInt + "h"
    else
      var d = (diff / 86400000L).toInt
      if d > 99 then d = 99
      "" + d + "d"

  /** the send/play flash, while its timer runs. An empty group is what "no
   *  flash" looks like as data — the four states are exclusive and the losing
   *  ones draw nothing. MIC FAILED outranks the rest: it names the device
   *  that broke, where SEND FAILED would blame the network for it. */
  def statusFlashView(s: WataState): View =
    var kids: List[Keyed] = Nil
    if s.micError then kids = Keyed("msg", VText(3, 9, "MIC FAILED", Color.red)) :: kids
    else if s.sendError then kids = Keyed("msg", VText(3, 9, "SEND FAILED", Color.red)) :: kids
    else if s.playError then kids = Keyed("msg", VText(3, 9, playErrMsg(s), Color.red)) :: kids
    else if s.sendOk then kids = Keyed("msg", VText(8, 9, "SENT", Color.green)) :: kids
    VGroup(kids)

  /** the two play failures the user can act on differently: the network could
   *  not give us the message, or this device cannot play one. */
  def playErrMsg(s: WataState): String =
    if s.noAudio then "NO AUDIO" else "PLAY FAILED"

  /** the recording bar: a red band across the bottom with the live capture
   *  meter and the elapsed time over it — paint order back to front. The
   *  meter (plan 0042) is one bright rect whose width is the level: the
   *  elapsed time animates on the CLOCK and counts up identically over a dead
   *  microphone, so the meter is the one thing on this bar that proves sound
   *  is arriving — flat sliver = dead mic. */
  def recordingView(s: WataState): View =
    val barY = Display.H - 24
    val secs = s.pttHoldTime.toInt
    val tenths = (s.pttHoldTime * 10.0).toInt % 10
    val txt = "REC " + secs + "." + tenths + "s"
    VGroup(Keyed("bar", VRect(0, barY, Display.W, 24, Color.red)) ::
      (Keyed("lvl", VRect(4, barY + 9, levelWidth(s.captureLevel), 6, Color.green)) ::
        (Keyed("time", VText(FbPaint.centerCol(txt), (barY + 8) / Font.GLYPH_H, txt,
          Color.white)) :: Nil)))

  /** the meter's width in px: level 0..32 across the bar's inner span, min 1
   *  so silence is a visible sliver rather than absence. */
  def levelWidth(level: scala.Int): scala.Int =
    val w = level * (Display.W - 8) / 32
    if w < 1 then 1 else w

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
  /** The boot screen is the first applet to be a `wataui` BODY (plan 0024): a
   *  pure function to a view tree, painted by the framebuffer interpreter. It
   *  is reached through `bodyContacts`, which is where the ambient reads it
   *  needs (`NetStatus.everLive`, `FbCaps.transportUnavailable`) are hoisted
   *  to — a body reads its arguments and nothing else. */
  def bodyBoot(net: NetState, c: ConnectionState, quitArmed: Boolean, unavail: Boolean,
      prov: Boolean, clockOk: Boolean): View =
    val sub = bootSubMsg(net, c, unavail, prov, clockOk)
    // one calm line sits centered; a failure's two lines straddle that row
    val head = if sub == "" then 7 else 6
    val msg = bootMsg(net, c, unavail, prov, clockOk)
    val keys = bootKeys(quitArmed)
    var kids: List[Keyed] =
      Keyed("footer", VText(FbPaint.centerCol(keys), FOOTER_ROW, keys, Color.midGray)) :: Nil
    if sub != "" then
      kids = Keyed("sub", VText(FbPaint.centerCol(sub), 8, sub, Color.midGray)) :: kids
    kids = Keyed("head", VText(FbPaint.centerCol(msg), head, msg, bootColor(net, c, unavail, clockOk))) :: kids
    VGroup(Keyed("title", VText(0, 0, "WATA", Color.cyan)) :: (Keyed("net", netView(net)) :: kids))

  /** the headline: the transport being unavailable outright, then the states
   *  the device is STILL BOOTING through, then the two failure states, then
   *  the two calm waiting states ("starting up" until there is BOTH an
   *  interface and a client trying to use it; "waiting for network" once the
   *  pipe is there and the sync loop is connecting). Live never reaches here —
   *  the first live frame latches `everLive` and the ordinary UI takes over
   *  for the session.
   *
   *  `stillBooting` outranks the connection error, which is a REFINEMENT of
   *  plan 0022 rather than a reversal of it: that plan let a failure speak
   *  even before the first live link, because a client that never connected is
   *  exactly the case an `everLive` gate could never reach. That holds once
   *  the device could plausibly have connected. It does not hold in the first
   *  seconds of a power-on, where a dial fails because there is no network yet
   *  or no clock yet (`NetStatus.clockOk`) — blaming the server there teaches
   *  a kid that the radio is broken every morning, which is the thing the boot
   *  screen exists to avoid. */
  def bootMsg(net: NetState, c: ConnectionState, unavail: Boolean, prov: Boolean,
      clockOk: Boolean): String =
    if unavail then "transport unavailable"
    else if isAuthRejected(c) then "account rejected"
    else if stillBooting(net, clockOk) then "starting up..."
    else if isConnError(c) then "can't reach server"
    else if prov then "setting up..."
    else if NetStatus.hasInterface(net.pipe) && !NetStatus.isDown(net.health) then "waiting for network"
    else "starting up..."

  /** is the device in a state where a failed dial says NOTHING about the
   *  server — no interface holding an address, or no valid wall clock (at
   *  1970 every TLS handshake fails, so iroh's relay and address discovery
   *  cannot work whatever the network does)?
   *
   *  `PipeNone` is the DEVICE's honest "interfaces exist, none has an
   *  address". `PipeUnknown` — the host answer, and what every off-device
   *  build and golden sees — is deliberately NOT included: on a Mac pointed
   *  at a dead server the failure is the whole truth, and plan 0022's
   *  behaviour there stands unchanged. */
  def stillBooting(net: NetState, clockOk: Boolean): Boolean =
    NetStatus.isNoPipe(net.pipe) || !clockOk

  /** the second line: what to do about it. Empty for the calm states, which
   *  need no instruction — provisioning (plan 0027: this session's QR was
   *  just approved and the handset is trading its node id for a session)
   *  says so, because the parent is WATCHING this screen right after the
   *  approve click and "waiting for network" would read as a failure. */
  def bootSubMsg(net: NetState, c: ConnectionState, unavail: Boolean, prov: Boolean,
      clockOk: Boolean): String =
    if unavail then "check config"
    else if isAuthRejected(c) then "check server"
    else if stillBooting(net, clockOk) then ""
    else if isConnError(c) then "retrying..."
    else if prov then "handset approved"
    else ""

  /** red only where the headline blames something. A still-booting device is
   *  calm gray however its dials are going. */
  def bootColor(net: NetState, c: ConnectionState, unavail: Boolean, clockOk: Boolean): scala.Int =
    if unavail || isAuthRejected(c) then Color.red
    else if stillBooting(net, clockOk) then Color.midGray
    else if isConnError(c) then Color.red
    else Color.midGray

  /** the footer names the live keys — and, once Back is armed, replaces them
   *  with the confirmation, since that is the only thing the next press does. */
  def bootKeys(quitArmed: Boolean): String =
    if quitArmed then "BACK again to exit" else "OK retry  BACK exit"

  def isAuthRejected(c: ConnectionState): Boolean = c match
    case _: ConnAuthRejected => true
    case _                   => false

  def isConnError(c: ConnectionState): Boolean = c match
    case _: ConnError => true
    case _            => false

  /** THE ENROLMENT BOOT SCREEN's hint line. That screen replaces the boot
   *  screen when the transport has refused this node id outright (`server
   *  refused: 401 not allowlisted`): not a network problem, and no amount of
   *  waiting fixes it — the server does not know this handset yet, and the one
   *  useful thing the device can do is show the parent how to admit it. So the
   *  calm "waiting for network" line gives way to the QR and its typed code.
   *
   *  The hint still names the exit while the two-step quit is armed: this is a
   *  boot screen, and the confirmation has to be legible wherever it lands. */
  def enrolBootHint(ctx: FrameCtx): String =
    if ctx.quitArmed then "BACK again to exit" else "scan to add this device"

  /** the CONNECTIVITY element, right-aligned in the header — the slot the old
   *  `ok`/`..`/`ERR`/`off` indicator held, replacing it rather than sitting
   *  beside it (two indicators would have to agree). One pipe mark (the wifi
   *  or cellular glyph on the device, a plain `NET` off it, `OFF` when no
   *  interface has an address) plus `..` while the client is reconnecting,
   *  alternating on the blink phase. The mark is ANCHORED at the right edge
   *  and the dots blink in a fixed slot to its left — the blink must never
   *  reflow the mark (a mark that shuffles with the phase reads as glitch).
   *  The 1px status line derives from the same `NetState`
   *  (`ShellStatus.fromNet`), so the two always agree.
   *
   *  As a view: the mark, plus the `..` that alternates while the link is
   *  reconnecting. It is ONE
   *  definition because the boot screen, the contact list and the enrolment
   *  screen all show it — two implementations would eventually make
   *  disagreeing claims about the same connection. */
  def netView(net: NetState): View =
    val g = NetStatus.glyph(net.pipe)
    val text = if g >= 0 then "" else NetStatus.label(net.pipe)
    val dots = if NetStatus.showsDots(net) then ".." else ""
    val markCols = if g >= 0 then 1 else text.length
    val col = Font.COLS - markCols
    val fg = NetStatus.color(net)
    val mark: View =
      if g >= 0 then VGlyph(col * Font.GLYPH_W, 1, g, fg)
      else VText(col, 0, text, fg)
    var kids: List[Keyed] = Nil
    if dots != "" then kids = Keyed("dots", VText(col - 2, 0, dots, fg)) :: Nil
    VGroup(Keyed("mark", mark) :: kids)

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
    else if isGroup(conv.convType) then conv.name
    else if conv.hasContact then conv.contact.user.displayName
    else "?"

  def familyName(snap: StateSnapshot): String =
    var out = "Family"
    if snap.hasFamily then out = snap.family.name
    out

  def isFamily(t: ConversationType): Boolean = t match
    case _: FamilyConv => true
    case _: GroupConv  => false
    case _: DmConv     => false

  def isGroup(t: ConversationType): Boolean = t match
    case _: GroupConv  => true
    case _: FamilyConv => false
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

  /** the index the event id sits at in the conversation's list, or -1 —
   *  how the anchored message cursor re-finds its row after the list shifts. */
  def msgIndexOf(snap: StateSnapshot, convIdx: scala.Int, id: String): scala.Int =
    convAt(snap, convIdx) match
      case c: Some[Conversation] => msgIndexIn(c.value.messages, id)
      case None => -1

  def msgIndexIn(ms: List[VoiceMessage], id: String): scala.Int =
    var out = -1
    var i = 0
    var cur = ms
    var going = true
    while going do
      cur match
        case h :: t =>
          val hid: String = h.id
          if hid == id then
            out = i
            going = false
          else
            i += 1
            cur = t
        case Nil => going = false
    out

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
// developer settings applet — echo test, diagnostics, radios, power (the
// hidden panel behind the kid settings' development row, plan 0053)
// ============================================================================

sealed trait EchoState derives CanEqual
case class EchoIdle() extends EchoState
case class EchoRecording() extends EchoState
case class EchoPlaying() extends EchoState
case class EchoDone() extends EchoState
case class EchoErr() extends EchoState

/** every diagnostic the settings applet shows, read TOGETHER on `refreshDiag`'s
 *  countdown. All of them are AMBIENT reads — sysfs nodes, a modem call, the
 *  interface table, the environment — and a body reads its arguments and
 *  nothing else, so they are cached in the applet's state rather than read by
 *  the screen that draws them. One record because they share one cadence:
 *  reading half of them per frame and half every five seconds would be two
 *  policies for one idea.
 *
 *  `battery` is -1 where sysfs has no battery node (every dev host), which is
 *  how the Device Info line knows to leave the percentage out; the text fields
 *  answer `Diag.UNAVAILABLE` in the same situation. `enrol` is whether this
 *  handset has an identity to enroll — decided from the environment, so
 *  constant for a run, and the one field seeded at construction, because the
 *  MENU SHAPE depends on it and input reaches the menu before the first
 *  refresh does. */
case class DiagSnap(
  ip: String,
  cell: String,
  cellAddr: String,
  wifi: String,
  battery: scala.Int,
  uptime: String,
  mem: String,
  enrol: Boolean
)

/** `armed` is the action-row confirmation latch: OK on a power row or a
 *  toggle arms it, a second OK runs the action, any other key drops it.
 *  `diag` is the cached diagnostics, re-read by `refreshDiag` every
 *  `DIAG_REFRESH` frames (`diagLeft` counts down to the next read).
 *  `netLine1`/`netLine2` hold the last net test's
 *  verdicts ("" = never run this session) and `netRunning` is true while the
 *  goroutine running the probes has not answered yet and `actionMsg` the last toggle's
 *  failure text — an action that did not do what the row says it does has to
 *  say so rather than leave the row looking untouched. `enrolOpen` is the one
 *  row that opens a screen of its own (Enroll): while it is true the applet
 *  draws the enrolment QR full-frame and the only live key is Back. */
case class SettingsState(
  selected: scala.Int,
  brightness: scala.Int,
  echo: EchoState,
  screenTimeoutIdx: scala.Int,
  connected: Boolean,
  armed: Boolean,
  diag: DiagSnap,
  netLine1: String,
  netLine2: String,
  netRunning: Boolean,
  actionMsg: String,
  diagLeft: scala.Int,
  enrolOpen: Boolean,
  // the arrival-notification mode's row value (plan 0041, remapped by plan
  // 0047): true = chime on arrival, false = quiet. Auto-play is not offered
  // here — it returns with the future focus-modes work.
  // A mirror of FbConfig's notify-mode cell, held here so the menu body stays
  // a pure function of the applet state; `persisted` writes it back through
  // `FbConfig.saveNotifyMode` the moment it changes.
  notifyChime: Boolean
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
  // toggles, 10-12 the power actions, and last the arrival-notification
  // mode (plan 0041) — appended so no earlier row's position (or golden)
  // moves.
  //
  // There is deliberately NO display-name row: a person's name is the
  // account's, set by whoever administers the server (the admin interface,
  // plan 0021), not something a handset picks from a list of presets.
  //
  // ENROLL is a CONDITIONAL row (plan 0014): it exists only on a handset
  // configured to speak iroh, where a node id has to be admitted by a parent
  // before anything works. A plain-TCP deployment has nothing to enroll and
  // shows the same 13 rows it always did — which is also why the item
  // CONSTANTS below are stable ids rather than menu positions: `itemAt` maps
  // a position to an id, so inserting the row after Network shifts no id and
  // invalidates no golden of a non-iroh device.
  val N_BASE = 14
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
  val ENROLL = 13
  // the arrival-notification mode (plan 0041). A stable id like the others;
  // its POSITION is the last base row, below the power actions — appending
  // keeps every other row where its golden pinned it.
  val NOTIFY = 14

  /** how many rows the menu has this run — one more on a handset with an
   *  identity to enroll. */
  def nItems(s: SettingsState): scala.Int =
    var n = N_BASE
    if s.diag.enrol then n = N_BASE + 1
    n

  /** the item id shown at menu POSITION `i`. Enroll sits right after Network,
   *  where a parent looking for "how do I get this thing online" will find it
   *  — not after the reboot rows. Everything below it shifts down by one
   *  position while keeping its id. */
  def itemAt(s: SettingsState, i: scala.Int): scala.Int =
    if !s.diag.enrol then plainId(i)
    else if i <= DISCONNECT then i
    else if i == DISCONNECT + 1 then ENROLL
    else plainId(i - 1)

  /** position -> id with no Enroll row in the way: identity, except the last
   *  base position, which is the Notify row (its id sits past ENROLL's). */
  def plainId(i: scala.Int): scala.Int =
    var out = i
    if i == N_BASE - 1 then out = NOTIFY
    out

  /** the item id the selection is on. */
  def cur(s: SettingsState): scala.Int = itemAt(s, s.selected)

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
    SettingsState(0, 40, EchoIdle(), 1, true, false, noDiag(), "", "", false, "", 0, false,
      Notify.chimes(FbConfig.notifyMode()))

  /** the boot state: preferences come back from the config store, so a device
   *  keeps the backlight and timeout its owner set. The notify mode reads the
   *  config module's cell (primed before the shell is built) rather than a
   *  `FbPrefs` field — the record is constructed positionally by BOTH clients,
   *  so a field for one would have to appear on the other in the same move. */
  def restored(p: FbPrefs): SettingsState =
    SettingsState(0, p.brightness, EchoIdle(), p.timeoutIdx, true, false,
      noDiag(), "", "", false, "", 0, false, Notify.chimes(FbConfig.notifyMode()))

  /** nothing read yet — the first `refreshDiag` fills it in on the first frame
   *  (`diagLeft` starts at 0). `enrol` is the exception: it decides how many
   *  rows the menu has, and an input event can reach the menu before an update
   *  does, so it is read here. */
  def noDiag(): DiagSnap = DiagSnap("", "", "", "", -1, "", "", Enrol.configured())

  /** all eight diagnostics, read together. Off-device every one of them answers
   *  a constant ("n/a", -1, ""), which is what keeps the frames the goldens pin
   *  independent of when a refresh lands. */
  def readDiag(): DiagSnap =
    DiagSnap(Diag.wlanIp(), Diag.cellData(), Diag.cellAddr(), Diag.wifiState(),
      Led.readBatteryPercent(), Diag.uptime(), Diag.memAvail(), Enrol.configured())

  def getScreenTimeout(s: SettingsState): scala.Int = timeoutSecs(s.screenTimeoutIdx)
  def getBrightness(s: SettingsState): scala.Int = s.brightness

  // ---- record withers (no `.copy` on sgola — see WataApplet) ----------------
  def withSelected(s: SettingsState, sel: scala.Int): SettingsState =
    SettingsState(sel, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withBrightness(s: SettingsState, b: scala.Int): SettingsState =
    SettingsState(s.selected, b, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withEcho(s: SettingsState, e: EchoState): SettingsState =
    SettingsState(s.selected, s.brightness, e, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withTimeoutIdx(s: SettingsState, i: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, i, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withConnected(s: SettingsState, c: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, c,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withArmed(s: SettingsState, a: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      a, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withDiag(s: SettingsState, d: DiagSnap, left: scala.Int): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, d, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, left, s.enrolOpen, s.notifyChime)
  def withNetTest(s: SettingsState, l1: String, l2: String, running: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, l1, l2, running, s.actionMsg, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withEnrolOpen(s: SettingsState, o: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, o, s.notifyChime)
  def withActionMsg(s: SettingsState, m: String): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, m, s.diagLeft, s.enrolOpen, s.notifyChime)
  def withNotify(s: SettingsState, play: Boolean): SettingsState =
    SettingsState(s.selected, s.brightness, s.echo, s.screenTimeoutIdx, s.connected,
      s.armed, s.diag, s.netLine1, s.netLine2, s.netRunning, s.actionMsg, s.diagLeft, s.enrolOpen, play)

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
    else if s.enrolOpen then closeOnBack(s, k)
    else k match
      case _: KUp    => moveUp(disarmed(s))
      case _: KDown  => moveDown(disarmed(s))
      case _: KEnter => onEnter(s, ctx)
      case _: KLeft  => onLeft(disarmed(s))
      case _: KRight => onRight(disarmed(s))
      case _           => disarmed(s)

  /** the enrolment screen swallows every key but Back, which closes it. A QR
   *  a stray keypress can dismiss is a QR a parent has to go find again
   *  mid-scan; Back is the one way out and the screen says so. */
  def closeOnBack(s: SettingsState, k: Key): SettingsState =
    if Shell.isBackKey(k) then withEnrolOpen(s, false) else s

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
    if before.notifyChime != after.notifyChime then
      FbConfig.saveNotifyMode(modeOf(after.notifyChime))
    after

  /** the row's Boolean back as the shared model's mode. */
  def modeOf(chime: Boolean): NotifyMode =
    var out: NotifyMode = NotifyQuiet()
    if chime then out = NotifyChime()
    out

  def prefsChanged(a: SettingsState, b: SettingsState): Boolean =
    a.brightness != b.brightness || a.screenTimeoutIdx != b.screenTimeoutIdx

  def moveUp(s: SettingsState): SettingsState =
    var out = s
    if s.selected > 0 then out = withSelected(s, s.selected - 1)
    out

  def moveDown(s: SettingsState): SettingsState =
    var out = s
    if s.selected < nItems(s) - 1 then out = withSelected(s, s.selected + 1)
    out

  def onEnter(s: SettingsState, ctx: FrameCtx): SettingsState =
    val i = cur(s)
    if i == ECHO then startEcho(s, ctx)
    else if i == DISCONNECT then doDisconnect(s, ctx)
    else if i == NET_TEST then runNetTest(s)
    else if i == ENROLL then withEnrolOpen(s, true)
    else if isActionRow(i) then armOrRun(s)
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
    if isPowerRow(cur(s)) then runPower(cur(s))
    else if cur(s) == WIFI_TOGGLE then out = toggleWifi(s)
    else out = toggleData(s)
    out

  def runPower(i: scala.Int): Unit =
    if i == POWER_OFF then Diag.powerOff()
    else if i == REBOOT_BL then Diag.rebootBootloader()
    else Diag.rebootEdl()

  def toggleWifi(s: SettingsState): String =
    if isOn(s.diag.wifi) then Diag.wifiStop() else Diag.wifiStart()

  /** the data toggle acts once per OK and NEVER retries: this modem accepts a
   *  single data call per boot, so a hidden second attempt would spend it. */
  def toggleData(s: SettingsState): String =
    if dataOn(s) then Diag.dataStop() else Diag.dataStart()

  def isOn(t: String): Boolean = t == "ON"
  def dataOn(s: SettingsState): Boolean = s.diag.cell.startsWith("up")

  /** the data row's ON/OFF, derived from the same cellular text the info row
   *  shows ("n/a" off-device, so the toggle reads "n/a" too). */
  def dataState(s: SettingsState): String =
    var out = Diag.UNAVAILABLE
    if s.diag.cell.startsWith("up") then out = "ON"
    else if s.diag.cell.startsWith("off") then out = "OFF"
    out

  /** OK on the net-test row STARTS the probes on a goroutine and returns: they
   *  take a few seconds — four network round trips — and a frame is 33ms. The
   *  row says it is running until `collectNetTest` picks the verdicts up; a
   *  second OK while it runs does nothing, since one test is one test. The
   *  previous run's verdicts are dropped at the start, because they are not
   *  this run's answer. Off-device the probes run nothing and say "n/a". */
  def runNetTest(s: SettingsState): SettingsState =
    var out = s
    if !s.netRunning then
      Diag.startNetTest()
      out = withNetTest(s, "", "", true)
    out

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
    if cur(s) == BRIGHTNESS then brightnessDown(s)
    else if cur(s) == SCREEN_OFF then withTimeoutIdx(s, decMod(s.screenTimeoutIdx, N_TIMEOUTS))
    else if cur(s) == NOTIFY then withNotify(s, !s.notifyChime)
    else s

  def onRight(s: SettingsState): SettingsState =
    if cur(s) == BRIGHTNESS then brightnessUp(s)
    else if cur(s) == SCREEN_OFF then withTimeoutIdx(s, (s.screenTimeoutIdx + 1) % N_TIMEOUTS)
    else if cur(s) == NOTIFY then withNotify(s, !s.notifyChime)
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
    refreshDiag(collectNetTest(s))

  /** the net test's verdicts, the frame after the goroutine running them
   *  finished. `takeNetTest` answers once, so this is a no-op on every other
   *  frame. */
  def collectNetTest(s: SettingsState): SettingsState =
    Diag.takeNetTest() match
      case r: Some[NetTestResult] => withNetTest(s, r.value.line1, r.value.line2, false)
      case None                   => s

  /** re-read the IP / cellular info rows every `DIAG_REFRESH` frames (the
   *  first frame reads immediately — `diagLeft` starts at 0). Off-device
   *  both reads answer a constant "n/a", which keeps the frames the goldens
   *  pin independent of when a refresh lands. */
  def refreshDiag(s: SettingsState): SettingsState =
    if s.diagLeft > 0 then withDiag(s, s.diag, s.diagLeft - 1)
    else withDiag(s, readDiag(), DIAG_REFRESH)

  /** one echo event, routed here by `Shell.routeAudio`; the catch-all is
   *  unreachable (wata events route to the wata applet). */
  def onEcho(s: SettingsState, e: AudioEvt): SettingsState = e match
    case _: AeEchoRecording => withEcho(s, EchoRecording())
    case _: AeEchoPlaying   => withEcho(s, EchoPlaying())
    case _: AeEchoDone      => withEcho(s, EchoDone())
    case _: AeEchoError     => withEcho(s, EchoErr())
    case _                  => s // wata events (unreachable)

  // ---- render (a `wataui` body — plan 0024) ---------------------------------------
  /** the whole applet is ONE body. Everything ambient it needs was cached into
   *  `s.diag` by `refreshDiag` frames ago, so the only reads left here are the
   *  enrolment screen's — which is also where this applet's one render-path
   *  EFFECT lives (`announceOnce`, once per session, spawned). */
  def render(s: SettingsState, px: go.Bytes, ctx: FrameCtx): Unit =
    FbPaint.draw(px, body(s, enrolSnap(s)))

  def enrolSnap(s: SettingsState): Option[EnrolSnap] =
    if !s.enrolOpen then None
    else
      Enrol.announceOnce()
      Some(Enrol.snap("BACK to close"))

  /** two screens: the menu, and the enrolment QR that takes the whole frame
   *  while it is open — it is a QR code, and the menu behind it would only
   *  steal pixels from the modules. */
  def body(s: SettingsState, enrol: Option[EnrolSnap]): View =
    enrol match
      case e: Some[EnrolSnap] => Enrol.body(e.value)
      case None               => menuView(s)

  def menuView(s: SettingsState): View =
    val start = windowStart(s)
    VGroup(Keyed("title", VText(0, 0, "DEV SETTINGS", Color.cyan)) ::
      (Keyed("rows", rowsView(s, start)) ::
        (Keyed("cues", cuesView(s, start)) ::
          (Keyed("detail", detailView(s)) :: Nil))))

  /** the visible window of menu rows, KEYED ON THE ITEM ID — the stable id, not
   *  the position, so the conditional Enroll row appearing renames nothing
   *  below it. */
  def rowsView(s: SettingsState, start: scala.Int): View =
    var acc: List[Keyed] = Nil
    var i = start
    while i < start + VISIBLE do
      val id = itemAt(s, i)
      acc = Keyed("item" + id, itemView(s, id, 2 + (i - start) * 2, i == s.selected)) :: acc
      i += 1
    VGroup(ListOps.reverse(acc))

  /** a row: the selection highlight FIRST (children paint in list order), the
   *  label in column 0, and the row's value — a state, a setting, a reading —
   *  in the column that item keeps it in. A row with nothing to show on the
   *  right is label-only. */
  def itemView(s: SettingsState, i: scala.Int, row: scala.Int, sel: Boolean): View =
    val fg = if sel then Color.black else Color.green
    var kids: List[Keyed] = Nil
    if sel then
      kids = Keyed("hl", VRect(0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)) :: kids
    kids = Keyed("label", VText(0, row, itemLabel(i), fg)) :: kids
    val v = itemValue(s, i)
    if v != "" then kids = Keyed("value", VText(valueCol(i), row, v, valueColor(s, i, fg))) :: kids
    VGroup(ListOps.reverse(kids))

  def itemLabel(i: scala.Int): String =
    if i == ECHO then "Audio Echo"
    else if i == BRIGHTNESS then "Brightness"
    else if i == SCREEN_OFF then "Screen off"
    else if i == DISCONNECT then "Network"
    else if i == ENROLL then "Enroll"
    else if i == INFO then "Device Info"
    else if i == IP_ADDR then "IP"
    else if i == CELL_DATA then "Cell data"
    else if i == NET_TEST then "Net test"
    else if i == WIFI_TOGGLE then "Wifi"
    else if i == DATA_TOGGLE then "Data link"
    else if i == POWER_OFF then "Power off"
    else if i == REBOOT_BL then "Reboot to BL"
    else if i == NOTIFY then "Notify"
    else "Reboot to EDL"

  /** the row's right-hand value; "" for the rows that are a label alone (Device
   *  Info and the three power actions, whose whole content is in the detail
   *  block). The two long readings are clipped to what is left of the row. */
  def itemValue(s: SettingsState, i: scala.Int): String =
    if i == ECHO then echoStatus(s.echo)
    else if i == BRIGHTNESS then "" + s.brightness + "/40"
    else if i == SCREEN_OFF then timeoutLabel(s.screenTimeoutIdx)
    else if i == DISCONNECT then netLabel(s)
    else if i == ENROLL then "OK=QR"
    else if i == IP_ADDR then WataLogic.clip(s.diag.ip, 21)
    else if i == CELL_DATA then WataLogic.clip(s.diag.cell, 14)
    else if i == NET_TEST then netTestStatus(s)
    else if i == WIFI_TOGGLE then s.diag.wifi
    else if i == DATA_TOGGLE then dataState(s)
    else if i == NOTIFY then notifyLabel(s)
    else ""

  /** the Notify row's value: the persisted spellings, spoken as the row. */
  def notifyLabel(s: SettingsState): String =
    var out = "quiet"
    if s.notifyChime then out = "chime"
    out

  def netLabel(s: SettingsState): String =
    var out = "OFF"
    if s.connected then out = "ON"
    out

  /** where each row's value starts — the column its label leaves free. */
  def valueCol(i: scala.Int): scala.Int =
    if i == BRIGHTNESS then 14
    else if i == ECHO || i == SCREEN_OFF then 12
    else if i == IP_ADDR then 4
    else 11

  /** values take the row's color, except the two that ARE a verdict: a failed
   *  echo is red, and the network row's ON/OFF is green/red whether or not the
   *  row is selected. */
  def valueColor(s: SettingsState, i: scala.Int, fg: scala.Int): scala.Int =
    var out = fg
    if i == ECHO && isEchoErr(s.echo) then out = Color.red
    if i == DISCONNECT then
      out = Color.red
      if s.connected then out = Color.green
    out

  /** first visible item: 0 until the selection passes the window's last row,
   *  then whatever keeps it on the last row (derived, not stored — see
   *  `VISIBLE`). */
  def windowStart(s: SettingsState): scala.Int =
    var w = s.selected - (VISIBLE - 1)
    if w < 0 then w = 0
    w

  /** "^"/"v" in the last column of the first/last menu rows when the window
   *  has items above/below it. */
  def cuesView(s: SettingsState, start: scala.Int): View =
    var kids: List[Keyed] = Nil
    if start + VISIBLE < nItems(s) then
      kids = Keyed("down", VText(25, 2 + (VISIBLE - 1) * 2, "v", Color.midGray)) :: kids
    if start > 0 then kids = Keyed("up", VText(25, 2, "^", Color.midGray)) :: kids
    VGroup(kids)

  /** the selected item's detail, on the two grid rows left below the menu. */
  def detailView(s: SettingsState): View =
    val i = cur(s)
    if i == ENROLL then twoLines("OK shows the QR code", "a parent scans to add")
    else if i == INFO then lines(batteryLine(s), Color.midGray,
      "Mem:" + s.diag.mem + " wata-fb", Color.midGray)
    else if i == ECHO then twoLines("Records 2s, plays", "back thru speaker")
    else if i == DISCONNECT then twoLines(connectDetail(s), "")
    else if i == BRIGHTNESS then twoLines("</> adjust", "")
    else if i == SCREEN_OFF then twoLines("</> timeout", "Any key wakes")
    else if i == IP_ADDR then twoLines("wlan0 IPv4 address", "")
    else if i == CELL_DATA then twoLines("ppp0 link + signal", cellAddrLine(s))
    else if i == NET_TEST then netTestDetail(s)
    else if i == NOTIFY then twoLines("</> arriving messages", "play thru speaker/quiet")
    else actionDetail(s)

  /** the detail block's two rows, in the color everything but a warning uses. */
  def twoLines(l1: String, l2: String): View = lines(l1, Color.midGray, l2, Color.midGray)

  def lines(l1: String, c1: scala.Int, l2: String, c2: scala.Int): View =
    var kids: List[Keyed] = Nil
    if l2 != "" then kids = Keyed("d2", VText(0, DETAIL_ROW + 1, l2, c2)) :: kids
    if l1 != "" then kids = Keyed("d1", VText(0, DETAIL_ROW, l1, c1)) :: kids
    VGroup(kids)

  def connectDetail(s: SettingsState): String =
    if s.connected then "OK to disconnect" else "Restart to reconn"

  /** the ppp0 address, which the row itself has no room for next to the signal
   *  strength; nothing to draw when the link is down. */
  def cellAddrLine(s: SettingsState): String =
    if s.diag.cellAddr == "" then "" else "ppp0 " + s.diag.cellAddr

  /** before a run, what OK will do; during one, that it is going; after one,
   *  the four verdicts. */
  def netTestDetail(s: SettingsState): View =
    if s.netRunning then twoLines("pinging gw/DNS...", "takes a few seconds")
    else if s.netLine1 == "" then twoLines("OK pings gw/DNS", "takes a few seconds")
    else twoLines(s.netLine1, s.netLine2)

  /** the action rows' detail doubles as the confirmation prompt: unarmed it
   *  says what OK starts, armed it says what the NEXT OK does — in red, with
   *  the escape route on the second line. A run that reported something (a
   *  failed toggle, or the off-device no-op) shows that instead, in red,
   *  until the next keypress. */
  def actionDetail(s: SettingsState): View =
    if s.actionMsg != "" then
      lines(WataLogic.clip(s.actionMsg, 26), Color.red, "not retried", Color.midGray)
    else if s.armed then
      lines("OK again: " + actionVerb(s), Color.red, "other keys cancel", Color.midGray)
    else twoLines("OK arms " + actionVerb(s), "then OK again runs")

  /** what the armed OK would do — for a toggle that is the OPPOSITE of the
   *  state the row shows. */
  def actionVerb(s: SettingsState): String =
    val i = cur(s)
    if i == POWER_OFF then "power off"
    else if i == REBOOT_BL then "reboot to BL"
    else if i == REBOOT_EDL then "reboot to EDL"
    else if i == WIFI_TOGGLE then toggleVerb("wifi", isOn(s.diag.wifi))
    else toggleVerb("data", dataOn(s))

  def toggleVerb(what: String, on: Boolean): String =
    var out = what + " on"
    if on then out = what + " off"
    out

  /** Device Info's first line: battery + uptime. sysfs has no battery node
   *  off-device, and `readBatteryPercent` says so with -1 rather than by
   *  failing — the battery part is simply left out then, and uptime answers
   *  "n/a". */
  def batteryLine(s: SettingsState): String =
    var line = "Up:" + s.diag.uptime
    if s.diag.battery >= 0 then line = "Bat:" + s.diag.battery + "% " + line
    line

  /** has this session's net test produced verdicts (what a scripted run waits
   *  on — the probes answer on their own goroutine)? */
  def hasNetTestResult(s: SettingsState): Boolean = !s.netRunning && s.netLine1 != ""

  /** the net-test row's own value: what OK does, or that it has run (the
   *  verdicts themselves need the detail block's width). */
  def netTestStatus(s: SettingsState): String =
    if s.netRunning then "run.."
    else if s.netLine1 == "" then "OK=run"
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

// ============================================================================
// kid settings applet — the three-row panel in the dot rotation (plan 0053)
// ============================================================================

/** the kid settings applet's state. Three visible rows (notify, bright, data)
 *  plus a HIDDEN development row the selection can scroll onto; the bottom
 *  two grid rows always carry a help/status line for the selected row.
 *
 *  `brightness`/`timeoutIdx`/`notifyChime` are MIRRORS of the same persisted
 *  preferences the developer applet edits (`FbConfig.savePrefs` /
 *  `saveNotifyMode`); `Shell.syncPrefs` keeps the inactive applet's mirror
 *  equal to the active one's every frame, so the two panels never disagree.
 *  The kid panel has no timeout row — `timeoutIdx` rides along only so a
 *  brightness save can write the whole `FbPrefs` record back unchanged.
 *
 *  `dataTarget`/`settle` are the data row's tri-state gesture: OK cycles the
 *  TARGET on screen immediately, and the radios are touched only once the
 *  selection has sat still for `SETTLE_S` — this modem accepts a single data
 *  call per boot (`SettingsLogic.toggleData`), so cycling through `cell` on
 *  the way to `off` must not spend it. `diag`/`diagLeft` cache the same
 *  ambient reads the developer applet's rows use, on the same cadence, so
 *  the row shows what IS while the target shows what is asked for.
 *  `actionMsg` is the last apply's report ("" = nothing to say) — an apply
 *  that answered something (a failed toggle, the off-device "not on device")
 *  says so on the help rows until the next keypress. */
case class KidSettingsState(
  selected: scala.Int,
  brightness: scala.Int,
  timeoutIdx: scala.Int,
  notifyChime: Boolean,
  dataTarget: scala.Int,
  settle: scala.Double,
  diag: DiagSnap,
  diagLeft: scala.Int,
  actionMsg: String
)

/** the kid settings applet: a thin dynamic-dispatch shell over
 *  `KidSettingsLogic`, like every other applet wrapper here. */
final class KidSettingsApplet(val state: KidSettingsState) extends Applet:
  def handleInput(k: Key, ks: KeyState, ctx: FrameCtx): Applet =
    KidSettingsApplet(KidSettingsLogic.handleInput(state, k, ks, ctx))
  def update(dt: scala.Double, ctx: FrameCtx): Applet =
    KidSettingsApplet(KidSettingsLogic.update(state, dt, ctx))
  def render(px: go.Bytes, ctx: FrameCtx): Unit =
    KidSettingsLogic.render(state, px, ctx)

object KidSettingsLogic:
  // rows: the three a kid sees, plus the development row — drawn only while
  // selected, so the panel stays three rows until someone scrolls past the
  // bottom. OK on it is intercepted by the Shell (the door to the DEV applet).
  val NOTIFY = 0
  val BRIGHT = 1
  val DATA = 2
  val DEV_ROW = 3

  // the data row's tri-state targets, in OK's cycling order. `T_NONE` is
  // "no pending target" (and "current state unknown" from `currentIdx`, so
  // the first OK off a n/a reading lands on `off`).
  val T_NONE = -1
  val T_OFF = 0
  val T_WIFI = 1
  val T_CELL = 2

  /** seconds the data selection must sit untouched before it is applied. */
  val SETTLE_S = 1.0

  def initial(): KidSettingsState =
    KidSettingsState(0, 40, 1, Notify.chimes(FbConfig.notifyMode()),
      T_NONE, 0.0, SettingsLogic.noDiag(), 0, "")

  /** the boot state: the same stored preferences the developer applet
   *  restores, read into this panel's mirrors. */
  def restored(p: FbPrefs): KidSettingsState =
    KidSettingsState(0, p.brightness, p.timeoutIdx, Notify.chimes(FbConfig.notifyMode()),
      T_NONE, 0.0, SettingsLogic.noDiag(), 0, "")

  // ---- record withers (no `.copy` on sgola — see WataApplet) ----------------
  def withSelected(s: KidSettingsState, sel: scala.Int): KidSettingsState =
    KidSettingsState(sel, s.brightness, s.timeoutIdx, s.notifyChime,
      s.dataTarget, s.settle, s.diag, s.diagLeft, s.actionMsg)
  def withBrightness(s: KidSettingsState, b: scala.Int): KidSettingsState =
    KidSettingsState(s.selected, b, s.timeoutIdx, s.notifyChime,
      s.dataTarget, s.settle, s.diag, s.diagLeft, s.actionMsg)
  def withNotify(s: KidSettingsState, chime: Boolean): KidSettingsState =
    KidSettingsState(s.selected, s.brightness, s.timeoutIdx, chime,
      s.dataTarget, s.settle, s.diag, s.diagLeft, s.actionMsg)
  def withTarget(s: KidSettingsState, t: scala.Int, settle: scala.Double): KidSettingsState =
    KidSettingsState(s.selected, s.brightness, s.timeoutIdx, s.notifyChime,
      t, settle, s.diag, s.diagLeft, s.actionMsg)
  def withDiag(s: KidSettingsState, d: DiagSnap, left: scala.Int): KidSettingsState =
    KidSettingsState(s.selected, s.brightness, s.timeoutIdx, s.notifyChime,
      s.dataTarget, s.settle, d, left, s.actionMsg)
  def withActionMsg(s: KidSettingsState, m: String): KidSettingsState =
    KidSettingsState(s.selected, s.brightness, s.timeoutIdx, s.notifyChime,
      s.dataTarget, s.settle, s.diag, s.diagLeft, m)
  /** the other panel's preference edits, mirrored in (`Shell.syncPrefs`). */
  def mirrored(s: KidSettingsState, b: scala.Int, tIdx: scala.Int, chime: Boolean): KidSettingsState =
    KidSettingsState(s.selected, b, tIdx, chime,
      s.dataTarget, s.settle, s.diag, s.diagLeft, s.actionMsg)

  // ---- input (press-only) ---------------------------------------------------
  /** every key goes through `persisted` (the developer applet's rule: one
   *  place decides when the stored preferences are written), and every key
   *  RE-ARMS the data settle timer — the apply waits for a hand to leave the
   *  keypad, not merely for a second on the clock. */
  def handleInput(s: KidSettingsState, k: Key, ks: KeyState, ctx: FrameCtx): KidSettingsState =
    persisted(s, handleKey(s, k, ks))

  def handleKey(s: KidSettingsState, k: Key, ks: KeyState): KidSettingsState =
    if !Shell.isPressed(ks) then s
    else resettled(pressedKey(cleared(s), k))

  def pressedKey(s: KidSettingsState, k: Key): KidSettingsState = k match
    case _: KUp    => moveUp(s)
    case _: KDown  => moveDown(s)
    case _: KEnter => onEnter(s)
    case _: KLeft  => onLeft(s)
    case _: KRight => onRight(s)
    case _           => s

  /** the last apply's report belongs to the gesture that produced it — any
   *  keypress drops it. */
  def cleared(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.actionMsg != "" then out = withActionMsg(s, "")
    out

  /** a pending data target's settle clock restarts on EVERY keypress. */
  def resettled(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.dataTarget != T_NONE then out = withTarget(s, s.dataTarget, SETTLE_S)
    out

  def persisted(before: KidSettingsState, after: KidSettingsState): KidSettingsState =
    if before.brightness != after.brightness then
      FbConfig.savePrefs(FbPrefs(after.brightness, after.timeoutIdx))
    if before.notifyChime != after.notifyChime then
      FbConfig.saveNotifyMode(SettingsLogic.modeOf(after.notifyChime))
    after

  def moveUp(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.selected > 0 then out = withSelected(s, s.selected - 1)
    out

  /** DOWN past the data row lands on the hidden development row. */
  def moveDown(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.selected < DEV_ROW then out = withSelected(s, s.selected + 1)
    out

  /** OK: flip notify, cycle the data target. OK on the development row never
   *  reaches this applet — the Shell intercepts it as the door to the
   *  developer panel. */
  def onEnter(s: KidSettingsState): KidSettingsState =
    if s.selected == NOTIFY then withNotify(s, !s.notifyChime)
    else if s.selected == DATA then cycleData(s)
    else s

  def onLeft(s: KidSettingsState): KidSettingsState =
    if s.selected == NOTIFY then withNotify(s, !s.notifyChime)
    else if s.selected == BRIGHT then brightnessDown(s)
    else s

  def onRight(s: KidSettingsState): KidSettingsState =
    if s.selected == NOTIFY then withNotify(s, !s.notifyChime)
    else if s.selected == BRIGHT then brightnessUp(s)
    else s

  def brightnessDown(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.brightness > 0 then out = setBl(s, s.brightness - 5)
    out

  def brightnessUp(s: KidSettingsState): KidSettingsState =
    var out = s
    if s.brightness < 40 then out = setBl(s, s.brightness + 5)
    out

  /** the backlight takes effect live, the same sysfs write the developer
   *  row's `SettingsLogic.setBl` makes. */
  def setBl(s: KidSettingsState, b: scala.Int): KidSettingsState =
    Led.setBacklight(b)
    withBrightness(s, b)

  /** OK on the data row: cycle the TARGET (off -> wifi -> cell -> off),
   *  starting from what the radios currently read when nothing is pending.
   *  Only the screen changes here; `tickSettle` applies it later. */
  def cycleData(s: KidSettingsState): KidSettingsState =
    var base = s.dataTarget
    if base == T_NONE then base = currentIdx(s)
    withTarget(s, (base + 1) % 3, SETTLE_S)

  /** the tri-state the radios are IN, from the same readings the developer
   *  rows show: cell wins (data link up), then wifi, then a known off/off;
   *  `T_NONE` when neither radio answers (off-device both read "n/a"). */
  def currentIdx(s: KidSettingsState): scala.Int =
    if dataIsOn(s) then T_CELL
    else if wifiIsOn(s) then T_WIFI
    else if s.diag.wifi == "OFF" || s.diag.cell.startsWith("off") then T_OFF
    else T_NONE

  def dataIsOn(s: KidSettingsState): Boolean = s.diag.cell.startsWith("up")
  def wifiIsOn(s: KidSettingsState): Boolean = s.diag.wifi == "ON"

  def targetLabel(t: scala.Int): String =
    if t == T_OFF then "off"
    else if t == T_WIFI then "wifi"
    else if t == T_CELL then "cell"
    else Diag.UNAVAILABLE

  // ---- update ---------------------------------------------------------------
  /** tick the settle timer (the one piece of per-frame logic this panel
   *  owns), then the same diagnostics cadence the developer applet keeps. */
  def update(s: KidSettingsState, dt: scala.Double, ctx: FrameCtx): KidSettingsState =
    refreshDiag(tickSettle(s, dt))

  def tickSettle(s: KidSettingsState, dt: scala.Double): KidSettingsState =
    if s.dataTarget == T_NONE then s
    else if s.settle > dt then withTarget(s, s.dataTarget, s.settle - dt)
    else applyData(s)

  /** the settled target, applied: at most one call per radio and NEVER a
   *  retry (the modem's one-data-call-per-boot pin — see
   *  `SettingsLogic.toggleData`). Off-device every call is the guarded
   *  "not on device" no-op, so the sim walks the whole gesture. The
   *  diagnostics are re-read on the next frame (`diagLeft` 0) so the row
   *  shows what the radios now say rather than a stale reading. */
  def applyData(s: KidSettingsState): KidSettingsState =
    val msg = applyCalls(s, s.dataTarget)
    withDiag(withActionMsg(withTarget(s, T_NONE, 0.0), msg), s.diag, 0)

  /** the calls that move the radios from what they read to the target; the
   *  first non-empty report is kept (one line is what the help rows hold). */
  def applyCalls(s: KidSettingsState, t: scala.Int): String =
    var msg = ""
    if t == T_OFF then
      if dataIsOn(s) then msg = keepMsg(msg, Diag.dataStop())
      if wifiIsOn(s) then msg = keepMsg(msg, Diag.wifiStop())
    else if t == T_WIFI then
      if dataIsOn(s) then msg = keepMsg(msg, Diag.dataStop())
      if !wifiIsOn(s) then msg = keepMsg(msg, Diag.wifiStart())
    else
      if wifiIsOn(s) then msg = keepMsg(msg, Diag.wifiStop())
      if !dataIsOn(s) then msg = keepMsg(msg, Diag.dataStart())
    msg

  def keepMsg(have: String, next: String): String =
    var out = have
    if out == "" then out = next
    out

  def refreshDiag(s: KidSettingsState): KidSettingsState =
    if s.diagLeft > 0 then withDiag(s, s.diag, s.diagLeft - 1)
    else withDiag(s, SettingsLogic.readDiag(), SettingsLogic.DIAG_REFRESH)

  // ---- render (a `wataui` body, like the developer panel's) -----------------
  def render(s: KidSettingsState, px: go.Bytes, ctx: FrameCtx): Unit =
    FbPaint.draw(px, body(s))

  def body(s: KidSettingsState): View =
    VGroup(Keyed("title", VText(0, 0, "SETTINGS", Color.cyan)) ::
      (Keyed("rows", rowsView(s)) ::
        (Keyed("help", helpView(s)) :: Nil)))

  /** the three rows, at the two-row spacing the developer menu uses — plus
   *  the development row, drawn ONLY while selected, so the panel a kid
   *  scrolls through stays three rows deep. */
  def rowsView(s: KidSettingsState): View =
    var acc: List[Keyed] = Nil
    if s.selected == DEV_ROW then
      acc = Keyed("dev", rowView(s, DEV_ROW, 2 + DEV_ROW * 2, true)) :: acc
    var i = DATA
    while i >= 0 do
      acc = Keyed("row" + i, rowView(s, i, 2 + i * 2, i == s.selected)) :: acc
      i -= 1
    VGroup(acc)

  def rowView(s: KidSettingsState, i: scala.Int, row: scala.Int, sel: Boolean): View =
    val fg = if sel then Color.black else Color.green
    var kids: List[Keyed] = Nil
    if sel then
      kids = Keyed("hl", VRect(0, 1 + row * Font.GLYPH_H, Display.W, Font.GLYPH_H, Color.green)) :: kids
    kids = Keyed("label", VText(0, row, rowLabel(i), fg)) :: kids
    val v = rowValue(s, i)
    if v != "" then kids = Keyed("value", VText(11, row, v, rowValueColor(s, i, fg))) :: kids
    VGroup(ListOps.reverse(kids))

  def rowLabel(i: scala.Int): String =
    if i == NOTIFY then "Notify"
    else if i == BRIGHT then "Bright"
    else if i == DATA then "Data"
    else "development"

  def rowValue(s: KidSettingsState, i: scala.Int): String =
    if i == NOTIFY then notifyLabel(s)
    else if i == BRIGHT then "" + s.brightness + "/40"
    else if i == DATA then dataValue(s)
    else ""

  def notifyLabel(s: KidSettingsState): String =
    var out = "quiet"
    if s.notifyChime then out = "chime"
    out

  /** what the radios read — or, while a target is pending, the target with a
   *  `>` prefix (and yellow, `rowValueColor`), so asked-for is never dressed
   *  as done. */
  def dataValue(s: KidSettingsState): String =
    var out = targetLabel(currentIdx(s))
    if s.dataTarget != T_NONE then out = ">" + targetLabel(s.dataTarget)
    out

  def rowValueColor(s: KidSettingsState, i: scala.Int, fg: scala.Int): scala.Int =
    var out = fg
    if i == DATA && s.dataTarget != T_NONE then out = Color.yellow
    out

  /** the help/status block: the bottom two grid rows always describe the
   *  selected row (the `DETAIL_ROW` convention the developer panel keeps).
   *  A data apply that reported something shows that instead, in red. */
  def helpView(s: KidSettingsState): View =
    if s.actionMsg != "" then
      SettingsLogic.lines(WataLogic.clip(s.actionMsg, 26), Color.red, "not retried", Color.midGray)
    else if s.selected == NOTIFY then
      SettingsLogic.twoLines("OK: chime or stay quiet", "now: " + notifyLabel(s))
    else if s.selected == BRIGHT then
      SettingsLogic.twoLines("</> adjust backlight", "now: " + s.brightness + "/40")
    else if s.selected == DATA then
      SettingsLogic.lines("OK picks off/wifi/cell", Color.midGray,
        "cell works once per boot", Color.yellow)
    else
      SettingsLogic.twoLines("OK opens dev settings", "red comes back")
