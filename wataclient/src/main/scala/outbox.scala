/** THE OUTBOX: a voice message that could not be sent is kept, not lost.
 *
 *  A recording is the one thing this client produces that cannot be recreated
 *  — the kid pressed the key and spoke; the bytes exist exactly once. So a
 *  send that fails on the network does not become a 2s flash and a hole in the
 *  conversation: it becomes a queue entry that survives the outage and the
 *  reboot, and is retried, IN ORDER, as soon as the client is proven able to
 *  reach the server again.
 *
 *  WHERE IT LIVES. The core may not touch a filesystem (capabilities.scala is
 *  the whole outside world it gets), so persistence is an injected capability:
 *  `OutboxStore`, a fixed set of `CAP` numbered SLOTS the app maps onto files
 *  (wata-fb writes `<config dir>/outbox/eN.msg`). Slots rather than named
 *  files because the subset has no directory listing — a bounded slot scan at
 *  start is the whole load protocol, and the ORDER comes from the sequence
 *  number inside each entry, not from the slot. A host build with nowhere to
 *  write gets `MemOutbox`, which keeps the same queue for the life of the
 *  process and says so once.
 *
 *  THE CLASSIFIED FAILURE POLICY (plan 0022, owner ruling 2026-08-05). Every
 *  send attempt lands in one of three classes:
 *
 *    DELIVERED (2xx)      the entry is gone; when the queue empties the
 *                         conversation's unsent marker clears.
 *    UNDELIVERABLE (4xx)  the server understood and refused. Retrying forever
 *                         would let one poisoned head block every message
 *                         behind it, so the entry is DROPPED — loudly: the
 *                         conversation keeps an "undelivered" marker until the
 *                         user opens it. 401 and 429 are NOT in this class:
 *                         they say something about our token or our rate, not
 *                         about the message.
 *    RETRY (transport, 5xx, 401, 429)
 *                         the entry stays at its place in the queue. A long
 *                         outage therefore never loses a message.
 *
 *  WHEN IT RETRIES. On each SUCCESSFUL sync round — the one moment
 *  connectivity is proven — the sync loop pokes the action loop, which drains
 *  the queue oldest-first and stops at the first RETRY-class failure (so
 *  ordering is preserved: a message never overtakes the one before it). All
 *  outbox mutation and all its disk IO happen on the ACTION loop; the sync
 *  loop only reads the counts, and the UI reads neither — it is told, through
 *  `EvOutbox`.
 *
 *  IDEMPOTENCY. An entry carries the transaction id its first attempt used and
 *  reuses it on every retry, so a send the server accepted but never got to
 *  answer is deduplicated by the server rather than delivered twice.
 *
 *  THE DM LEG. A send into a conversation with no room yet (a family-roster
 *  row nobody has messaged) is queued with the CONTACT id and an empty room
 *  id; the retry re-resolves the DM room against the server. So a queued send
 *  survives a restart even when the room it belongs in did not exist yet when
 *  the user spoke. */

import sgo.add  // the Atomic[Long] add extension (the sequence counter)

/** the persistence seam: `CAP` numbered slots of opaque text. Every method is
 *  best-effort — a store that cannot write returns false and the queue simply
 *  degrades to memory for this session. */
trait OutboxStore extends Shareable:
  /** the entry text in `slot`, or "" when the slot is free/unreadable. */
  def read(slot: scala.Int): String
  /** write `data` to `slot`; false = it did not land. */
  def write(slot: scala.Int, data: String): Boolean
  /** free `slot`. */
  def clear(slot: scala.Int): Unit
  /** false = memory only, nothing survives the process. */
  def persistent(): Boolean

/** the no-filesystem fallback: the same queue semantics, gone at process exit.
 *  Fieldless — the slots live in `MemSlots`' module cell, under the same
 *  single-client-per-process discipline as the rest of the runtime's state. */
final class MemOutbox extends OutboxStore:
  def read(slot: scala.Int): String = MemSlots.get(slot)
  def write(slot: scala.Int, data: String): Boolean =
    MemSlots.put(slot, data)
    true
  def clear(slot: scala.Int): Unit = MemSlots.put(slot, "")
  def persistent(): Boolean = false

/** one memory slot's contents. */
case class MemSlot(slot: scala.Int, data: String)

object MemSlots:
  private val slotsC: sgo.Atomic[List[MemSlot]] = sgo.atomic(Nil)

  def reset(): Unit = slotsC.set(Nil)

  def get(slot: scala.Int): String = find(slotsC.get(), slot)

  def find(xs: List[MemSlot], slot: scala.Int): String = xs match
    case h :: t => if h.slot == slot then h.data else find(t, slot)
    case Nil    => ""

  def put(slot: scala.Int, data: String): Unit =
    slotsC.set(MemSlot(slot, data) :: without(slotsC.get(), slot, Nil))

  def without(xs: List[MemSlot], slot: scala.Int, acc: List[MemSlot]): List[MemSlot] = xs match
    case h :: t => without(t, slot, keepUnless(h, slot, acc))
    case Nil    => ListOps.reverse(acc)

  def keepUnless(h: MemSlot, slot: scala.Int, acc: List[MemSlot]): List[MemSlot] =
    if h.slot == slot then acc else h :: acc

/** one queued voice message. `seq` orders the queue (and survives in the
 *  file, since slots carry no order); `txn` is the transaction id every
 *  attempt reuses; `ts` is when the user spoke. */
case class OutboxEntry(seq: Long, slot: scala.Int, roomId: String, contactId: String,
                       durationMs: Long, ts: Long, txn: scala.Int, ogg: Bytes)

object Outbox:

  /** how many messages the queue holds. At the cap the OLDEST is dropped —
   *  and that drop is as loud as an undeliverable one, because it is one. */
  val CAP: scala.Int = 16

  /** oldest first — the delivery order. Touched by the action loop only
   *  (the sync loop reads `pending`; the UI is told through `EvOutbox`). */
  private val entriesC: sgo.Atomic[List[OutboxEntry]] = sgo.atomic(Nil)
  /** conversation keys with a message that will never be delivered. */
  private val droppedC: sgo.Atomic[List[String]] = sgo.atomic(Nil)
  private val seqC: sgo.Atomic[Long] = sgo.atomic(0L)
  /** has the "memory only" degradation been printed this session? */
  private val warnedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** is a retry poke already queued (one in flight is enough)? */
  private val armedC: sgo.Atomic[Boolean] = sgo.atomic(false)

  // ---- lifecycle ---------------------------------------------------------------

  /** load the queue from the store — the restart path. Slots are scanned in
   *  order and the entries sorted by their sequence number, which is what
   *  makes a restart mid-outage deliver in the order the user spoke. */
  def reset(store: OutboxStore): Unit =
    entriesC.set(Nil)
    droppedC.set(Nil)
    warnedC.set(false)
    armedC.set(false)
    seqC.set(0L)
    loadSlots(store)

  def loadSlots(store: OutboxStore): Unit =
    var acc: List[OutboxEntry] = Nil
    var i = 0
    while i < CAP do
      acc = loadOne(store, i, acc)
      i = i + 1
    entriesC.set(sortBySeq(acc))
    seqC.set(maxSeq(entriesC.get()) + 1L)

  def loadOne(store: OutboxStore, slot: scala.Int, acc: List[OutboxEntry]): List[OutboxEntry] =
    val raw = store.read(slot)
    var out = acc
    if raw != "" then
      val e = decode(slot, raw)
      if e.seq >= 0L then out = e :: acc
    out

  /** insertion sort over at most `CAP` entries. */
  def sortBySeq(xs: List[OutboxEntry]): List[OutboxEntry] =
    var out: List[OutboxEntry] = Nil
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          out = insertBySeq(out, h, Nil)
          cur = t
        case Nil => going = false
    out

  def insertBySeq(xs: List[OutboxEntry], e: OutboxEntry, acc: List[OutboxEntry]): List[OutboxEntry] = xs match
    case h :: t =>
      if e.seq < h.seq then appendRev(acc, e :: h :: t)
      else insertBySeq(t, e, h :: acc)
    case Nil => appendRev(acc, e :: Nil)

  /** `reverse(acc) ++ tail` — the subset has no `++`. */
  def appendRev(acc: List[OutboxEntry], tail: List[OutboxEntry]): List[OutboxEntry] =
    var out = tail
    var cur = acc
    var going = true
    while going do
      cur match
        case h :: t =>
          out = h :: out
          cur = t
        case Nil => going = false
    out

  def maxSeq(xs: List[OutboxEntry]): Long =
    var out = -1L
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.seq > out then out = h.seq
          cur = t
        case Nil => going = false
    out

  // ---- what the UI is told -------------------------------------------------------

  /** how many messages are waiting (the sync loop's read: no poke without
   *  work). */
  def pending(): scala.Int = count(entriesC.get())

  def count(xs: List[OutboxEntry]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  /** the conversation key one entry belongs to: the room when it is known,
   *  else the contact whose DM room the retry will resolve. */
  def keyOf(roomId: String, contactId: String): String =
    if roomId != "" then roomId else contactId

  /** conversation keys with something still queued. */
  def unsentKeys(): List[String] = keysOf(entriesC.get(), Nil)

  def keysOf(xs: List[OutboxEntry], acc: List[String]): List[String] = xs match
    case h :: t => keysOf(t, addKey(acc, keyOf(h.roomId, h.contactId)))
    case Nil    => ListOps.reverse(acc)

  def addKey(acc: List[String], k: String): List[String] =
    if k == "" || hasKey(acc, k) then acc else k :: acc

  def hasKey(xs: List[String], k: String): Boolean = xs match
    case h :: t => if h == k then true else hasKey(t, k)
    case Nil    => false

  /** conversation keys with a message that was dropped undelivered. */
  def droppedKeys(): List[String] = droppedC.get()

  /** the event the UI draws its markers from — published on every change, so
   *  the marker is live even while the client cannot sync at all (no round,
   *  no snapshot: an offline send would otherwise leave no trace on screen). */
  def event(): EvOutbox = EvOutbox(unsentKeys(), droppedKeys())

  def publish(c: MatrixClient): Unit =
    val ok = c.events.trySend(event())
    ()

  // ---- enqueue --------------------------------------------------------------------

  /** queue a send that failed for a RETRY-class reason. At the cap the oldest
   *  entry is dropped and marked undelivered — a full queue is data loss and
   *  says so. */
  def enqueue(c: MatrixClient, roomId: String, contactId: String, ogg: Bytes,
              durationMs: Long, txn: scala.Int): Unit =
    warnIfVolatile(c)
    if pending() >= CAP then dropOldest(c)
    val e = OutboxEntry(seqC.add(1L), freeSlot(entriesC.get()), roomId, contactId,
      durationMs, c.clock.nowUnixMillis(), txn, ogg)
    entriesC.set(appendRev(revOf(entriesC.get()), e :: Nil))
    store(c, e)
    publish(c)

  def revOf(xs: List[OutboxEntry]): List[OutboxEntry] = ListOps.reverse(xs)

  def store(c: MatrixClient, e: OutboxEntry): Unit =
    val ok = c.outbox.write(e.slot, encode(e))
    ()

  /** a device that cannot persist still queues — for this session only. Said
   *  once, because it is a property of the device, not of the message. */
  def warnIfVolatile(c: MatrixClient): Unit =
    if !c.outbox.persistent() && !warnedC.getAndSet(true) then
      println("outbox: no persistent store — queued messages live only in this session")

  def freeSlot(xs: List[OutboxEntry]): scala.Int =
    var i = 0
    var out = 0
    var going = true
    while going do
      if i >= CAP then going = false
      else if slotFree(xs, i) then
        out = i
        going = false
      else i = i + 1
    out

  def slotFree(xs: List[OutboxEntry], slot: scala.Int): Boolean = xs match
    case h :: t => if h.slot == slot then false else slotFree(t, slot)
    case Nil    => true

  /** the head goes, loudly. */
  def dropOldest(c: MatrixClient): Unit = entriesC.get() match
    case h :: t =>
      entriesC.set(t)
      c.outbox.clear(h.slot)
      markDropped(c, keyOf(h.roomId, h.contactId))
      println("outbox: full — dropped the oldest queued message")
    case Nil => ()

  /** record a conversation as having lost a message, and tell the UI. */
  def markDropped(c: MatrixClient, key: String): Unit =
    if key != "" then droppedC.set(addKey(droppedC.get(), key))
    publish(c)

  /** the user opened the conversation: the undelivered marker has been seen. */
  def ack(c: MatrixClient, key: String): Unit =
    droppedC.set(dropKey(droppedC.get(), key, Nil))
    publish(c)

  def dropKey(xs: List[String], k: String, acc: List[String]): List[String] = xs match
    case h :: t => dropKey(t, k, keepKeyUnless(h, k, acc))
    case Nil    => ListOps.reverse(acc)

  def keepKeyUnless(h: String, k: String, acc: List[String]): List[String] =
    if h == k then acc else h :: acc

  // ---- the retry poke ------------------------------------------------------------

  /** the sync loop's end-of-round poke: one retry action in flight at a time,
   *  non-blocking (a full action queue simply means the next round pokes
   *  again). */
  def kick(c: MatrixClient): Unit =
    if pending() > 0 && !armedC.getAndSet(true) then
      if !c.actions.trySend(ActRetryOutbox()) then armedC.set(false)

  /** drain the queue, oldest first, stopping at the first RETRY-class failure
   *  so nothing overtakes the message in front of it. Runs on the action loop
   *  (its HTTP and its disk writes are that loop's, never the sync loop's and
   *  never the UI's). */
  def deliver(c: MatrixClient, hs: Hs): Unit =
    armedC.set(false)
    var changed = false
    var going = true
    while going do
      entriesC.get() match
        case h :: t =>
          val cls = attempt(hs, h)
          if cls == RETRY then going = false
          else
            entriesC.set(t)
            c.outbox.clear(h.slot)
            if cls == UNDELIVERABLE then
              droppedC.set(addKey(droppedC.get(), keyOf(h.roomId, h.contactId)))
              println("outbox: server refused a queued message — dropped")
            changed = true
        case Nil => going = false
    if changed then publish(c)

  /** one queued entry's attempt — the DM room is re-resolved when the entry
   *  never had one. */
  def attempt(hs: Hs, e: OutboxEntry): scala.Int =
    sendOnce(hs, e.roomId, e.contactId, e.ogg, e.durationMs, e.txn)

  // ---- the classified send --------------------------------------------------------

  val DELIVERED: scala.Int = 0
  val UNDELIVERABLE: scala.Int = 1
  val RETRY: scala.Int = 2

  /** resolve (if needed) + upload + send, as one classified attempt. This is
   *  the ONE send path: a live send and a queued retry differ only in where
   *  the bytes came from. */
  def sendOnce(hs: Hs, roomId0: String, contactId: String, ogg: Bytes,
               durationMs: Long, txn: scala.Int): scala.Int =
    var roomId = roomId0
    var cls = DELIVERED
    if roomId == "" then
      if contactId == "" then cls = UNDELIVERABLE   // nowhere to send it, ever
      else
        val resp = MatrixHttp.dmRoom(hs, contactId)
        if resp.status == 200 then roomId = MatrixHttp.parseRoomId(resp.body)
        if roomId == "" then cls = resolveFail(resp.status)
    if cls == DELIVERED then cls = uploadAndSend(hs, roomId, ogg, durationMs, txn)
    cls

  /** a DM endpoint that answered 200 with no room is broken in a way retrying
   *  will not fix; anything else classifies normally. */
  def resolveFail(status: scala.Int): scala.Int =
    if status == 200 then UNDELIVERABLE else classify(status)

  def uploadAndSend(hs: Hs, roomId: String, ogg: Bytes, durationMs: Long, txn: scala.Int): scala.Int =
    val up = MatrixHttp.uploadMedia(hs, ogg)
    var cls = classify(up.status)
    if cls == DELIVERED then
      val mxc = MatrixHttp.parseMxcUrl(up.body)
      if mxc == "" then cls = UNDELIVERABLE
      else cls = classify(MatrixHttp.sendVoiceMessage(hs, roomId, mxc, durationMs, ogg.size, txn).status)
    cls

  /** the failure policy in one function: 2xx delivered; 4xx refused, EXCEPT
   *  401 (our token, which the sync loop is already fixing) and 429 (our rate,
   *  which waiting fixes); everything else — a transport failure (status 0),
   *  a 5xx, a timeout — is worth retrying. */
  def classify(status: scala.Int): scala.Int =
    if status == 200 then DELIVERED
    else if status == 401 || status == 429 then RETRY
    else if status >= 400 && status < 500 then UNDELIVERABLE
    else RETRY

  // ---- the entry format -----------------------------------------------------------
  //
  // Six newline-terminated header fields, then the Ogg bytes VERBATIM (they
  // are never re-encoded — what the user recorded is what is delivered). The
  // header is read by scanning the first six newlines, so the payload may
  // contain anything.

  def encode(e: OutboxEntry): String =
    "" + e.seq + "\n" + e.roomId + "\n" + e.contactId + "\n" + e.durationMs +
      "\n" + e.txn + "\n" + e.ts + "\n" + e.ogg.rawString

  /** parse a slot; `seq < 0` = unreadable, which the loader skips. */
  def decode(slot: scala.Int, raw: String): OutboxEntry =
    val f0 = cut(raw)
    val f1 = cut(f0.rest)
    val f2 = cut(f1.rest)
    val f3 = cut(f2.rest)
    val f4 = cut(f3.rest)
    val f5 = cut(f4.rest)
    var seq = -1L
    if f0.found && f1.found && f2.found && f3.found && f4.found && f5.found then
      seq = parseLong(f0.head)
    OutboxEntry(seq, slot, f1.head, f2.head, parseLong(f3.head), parseLong(f5.head),
      parseLong(f4.head).toInt, Bytes.fromRawString(f5.rest))

  /** one header field and what follows it. */
  def cut(s: String): Cut =
    val at = s.indexOf("\n")
    if at < 0 then Cut(false, "", "")
    else Cut(true, s.substring(0, at), s.substring(at + 1))

  def parseLong(s: String): Long =
    var out = 0L
    var ok = s.length > 0
    var i = 0
    while i < s.length do
      val d = digitOf(s.substring(i, i + 1))
      if d < 0 then ok = false
      else out = out * 10L + d.toLong
      i = i + 1
    var res = -1L
    if ok then res = out
    res

  def digitOf(ch: String): scala.Int =
    if ch == "0" then 0
    else if ch == "1" then 1
    else if ch == "2" then 2
    else if ch == "3" then 3
    else if ch == "4" then 4
    else if ch == "5" then 5
    else if ch == "6" then 6
    else if ch == "7" then 7
    else if ch == "8" then 8
    else if ch == "9" then 9
    else -1

/** a header field split off the front of an entry. */
case class Cut(found: Boolean, head: String, rest: String)
