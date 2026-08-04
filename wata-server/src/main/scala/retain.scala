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
 *  and a foreign/dangling mxc never matches the store. */
object Retain:
  /** the exempt-set seam: event ids the sweep never touches. Empty today; a
   *  future "favorite a message" marker slots its ids in here. */
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
    sweepEvents(h.roomId, h.timeline, cutoff)
    sweepRooms(t, cutoff)

  def sweepEvents(roomId: String, evs: List[Event], cutoff: scala.Long): Unit = evs match
    case h :: t => sweepEventsStep(roomId, h, t, cutoff)
    case Nil  => ()

  def sweepEventsStep(roomId: String, h: Event, t: List[Event], cutoff: scala.Long): Unit =
    if expired(h, cutoff) then sweepOne(roomId, h) else ()
    sweepEvents(roomId, t, cutoff)

  def expired(ev: Event, cutoff: scala.Long): Boolean =
    ev.etype == "m.room.message" && ev.ts < cutoff && !isExempt(ev.eventId)
      && refersStoredMedia(ev)

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
