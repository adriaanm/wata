import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*

/** Media retention: wata is EPHEMERAL BY DESIGN — a walkie-talkie, not an
 *  archive (plan 0012's product ruling). Voice media older than
 *  `WATA_MEDIA_RETAIN_DAYS` (default 7; `0` disables) is swept: the referring
 *  message is redacted server-side through the SAME store path a manual
 *  redaction takes, which reclaims the blob (metadata dropped, file deleted —
 *  `Store.reclaimMedia`) and journals an ordinary event + redact op pair, so
 *  a replay converges and clients render the same message-removed row a
 *  manual redaction produces.
 *
 *  Age is judged by the event's `origin_server_ts` — it survives replay
 *  verbatim, unlike a blob file's mtime, which a boot migration would reset.
 *  The sweep runs once at boot (`Server.serve`, before listening) and then
 *  daily on a spawned goroutine. Only `m.room.message` events whose `url`
 *  names a STORED media id are candidates: text messages are out of scope,
 *  already-redacted events have empty content (no `url`) and fall through,
 *  and a foreign/dangling mxc never matches the store.
 *
 *  FAVORITES are the exception (plan 0019): an event whose room state carries a
 *  non-empty `net.wata.favorite` slot under its id is skipped, so a marked
 *  message keeps its blob for as long as the marker stands. The check reads the
 *  room's own state (favorite.scala `isFavorited`) during the per-room walk
 *  rather than materializing one global id list — the sweep already holds the
 *  room. `exemptEventIds` stays as an additional, list-shaped seam for tests.
 */
object Retain:
  /** the exempt-set seam: extra event ids the sweep never touches, on top of
   *  the room's favorites. Empty in production. */
  def exemptEventIds: List[String] = Nil

  def isExempt(eventId: String): Boolean = inList(exemptEventIds, eventId)

  def inList(xs: List[String], x: String): Boolean = xs match
    case h :: t => inListStep(h, t, x)
    case Nil  => false

  def inListStep(h: String, t: List[String], x: String): Boolean =
    if h == x then true else inList(t, x)

  /** `WATA_MEDIA_RETAIN_DAYS`: unset/unparsable -> 7, `0` (or negative) ->
   *  disabled. */
  def retainDays(): scala.Int = parseDays(go.sys.getenv("WATA_MEDIA_RETAIN_DAYS"))

  def parseDays(s: String): scala.Int =
    if s == "" then 7
    else parseDaysNum(s)

  def parseDaysNum(s: String): scala.Int =
    var d = 7
    try
      val v = go.strconv.atoi(s)
      d = v.toInt
      ()
    catch case e: sgo.GoError => ()
    d

  def dayMs: scala.Long = 86400000L

  /** boot entry: sweep now, then daily (a spawned goroutine — unstructured is
   *  fine here, the loop lives for the process). */
  def boot(): Unit =
    val d = retainDays()
    if d > 0 then bootWith(d)
    else println("Wata media retention OFF")

  def bootWith(d: scala.Int): Unit =
    println("Wata media retention " + longStr(d.toLong) + " days")
    sweep(d)
    go.spawn(() => loop(d))

  def loop(d: scala.Int): Unit =
    while true do
      go.time.After(go.time.milliseconds(86400000)).recv()
      sweep(d)

  /** one pass: snapshot the rooms (outside any lock), collect the expired
   *  media messages, redact each. */
  def sweep(days: scala.Int): Unit =
    sweepRooms(Store.allRooms(), Store.nowMs() - d2ms(days))

  def d2ms(days: scala.Int): scala.Long = days.toLong * dayMs

  def sweepRooms(rs: List[Room], cutoff: scala.Long): Unit = rs match
    case h :: t => sweepRoomsStep(h, t, cutoff)
    case Nil  => ()

  def sweepRoomsStep(h: Room, t: List[Room], cutoff: scala.Long): Unit =
    sweepEvents(h, h.timeline, cutoff)
    sweepRooms(t, cutoff)

  def sweepEvents(room: Room, evs: List[Event], cutoff: scala.Long): Unit = evs match
    case h :: t => sweepEventsStep(room, h, t, cutoff)
    case Nil  => ()

  def sweepEventsStep(room: Room, h: Event, t: List[Event], cutoff: scala.Long): Unit =
    if expired(room, h, cutoff) then sweepOne(room.roomId, h) else ()
    sweepEvents(room, t, cutoff)

  /** the snapshot the sweep walks is the room record itself, so the favorite
   *  check is a state lookup on the room already in hand. */
  def expired(room: Room, ev: Event, cutoff: scala.Long): Boolean =
    ev.etype == "m.room.message" && ev.ts < cutoff && !isExempt(ev.eventId)
      && !Favorite.isFavorited(room, ev.eventId) && refersStoredMedia(ev)

  def refersStoredMedia(ev: Event): Boolean =
    mediaKnownOf(Store.mediaIdOfMxc(strField(ev.content, "url", "")))

  def mediaKnownOf(id: String): Boolean =
    if id == "" then false else Store.hasMedia(id)

  /** redact one expired message SERVER-SIDE, exactly the manual-redaction
   *  store sequence (rooms.scala `redact4`/`redact5` minus the HTTP edge):
   *  append an `m.room.redaction` event (journaled), redact the target (which
   *  reclaims the blob and journals the redact op), wake the room. The sender
   *  is the swept message's own sender — a self-redaction, which any power
   *  table permits. */
  def sweepOne(roomId: String, ev: Event): Unit =
    Store.addEvent(roomId, "m.room.redaction", ev.sender,
      obj2("redacts", JStr(ev.eventId), "reason", JStr("retention")),
      false, "", true, ev.eventId, JNull()) match
      case s: Some[Event] => sweepOneGo(roomId, ev.eventId, s.value)
      case None => ()

  def sweepOneGo(roomId: String, targetId: String, red: Event): Unit =
    Store.redactTarget(roomId, targetId, red)
    Store.notifyRoomMembers(roomId)
    println("wata: retention swept " + targetId)
