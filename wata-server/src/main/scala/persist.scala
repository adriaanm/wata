import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** Append-only JSONL persistence.
 *
 *  OFF BY DEFAULT: enabled only when the `WATA_LOG` env var names a log file
 *  (`WATA_LOG=/path/to/log.jsonl wata-server :8008`). The test suite runs
 *  STATELESS (no env var) — `Journal.on` gates every write, so a stateless run
 *  pays one boolean read per mutation and nothing else.
 *
 *  Design: every store mutation that must survive a restart appends ONE JSON
 *  line (the `json` module's writer) describing the CONCRETE post-generation
 *  record — never the generator inputs, so replay reinserts verbatim without
 *  re-running `crypto/rand` or the wall clock (ids, tokens, timestamps, seqs
 *  are faithful across the reboot). On boot the log is read and replayed in
 *  file order (== commit order, since each `rec` fires under the store write
 *  lock) BEFORE serving; then the append handle opens and `on` flips.
 *
 *  What is logged (enough to make a reboot useful AND faithful):
 *   - `device` / `rmDevice`  — a client's access token survives a restart;
 *                              `device` carries the node id the session was
 *                              minted through ("" for a password login; a
 *                              journal from before the field simply lacks it
 *                              and replays as ""), so revoke-by-node (plan
 *                              0058) survives a reboot too; and the mint
 *                              time `created_ms` (plan 0059 — absent replays
 *                              as 0, rendered "unknown"). The per-session
 *                              LAST-SEEN stamp is deliberately NOT here:
 *                              journaling it would make every authenticated
 *                              request a journal write (store.scala's
 *                              `lastSeenDev` doc has the tradeoff)
 *   - `profile`              — displayname / avatar
 *   - `acct`                 — account data (carries its seq)
 *   - `room` / `event` / `redact` — rooms, their state + timeline, redactions
 *   - `alias`                — directory
 *   - `media`                — blob METADATA only ({media_id, content_type,
 *                              size}); the bytes live in the blob file
 *                              (MediaFiles), written BEFORE this op. Replay
 *                              probes the file instead of decoding a payload;
 *                              a journal from before the file-backed store
 *                              carries the bytes base64url'd in a `data`
 *                              field, which replay MIGRATES by writing the
 *                              blob out to the media dir (idempotent — the
 *                              write truncates — since the journal is not
 *                              compacted)
 *   - `receipt`              — read receipts (carries its seq)
 *   - `txn`                  — per-device send idempotency
 *   - `dmpair`               — a canonical DM's pair -> room claim, so DM
 *                              identity survives a restart with no re-derivation
 *   - `bind` / `unbind`      — a device-account binding nodeId -> user (plan
 *                              0027); a re-bind of the same node overwrites,
 *                              so replay in commit order converges on the
 *                              latest binding. `unbind` (plan 0058) is
 *                              written only by an enrolment revocation
 *   - `nick`                 — a handset's admin-given nickname (plan 0060);
 *                              last write wins, an empty name clears
 *   - `pushreg` / `pushunreg` / `pushforget` — APNs push registrations (plan
 *                              0065); a registration that does not survive a
 *                              restart is a phone that goes silent until it
 *                              happens to re-register
 *   - `pttjoin` / `pttleave` — the PushToTalk channel's ephemeral push token
 *                              (plan 0065 tier 3); one live row per device,
 *                              and re-joining a channel needs the user to
 *                              foreground the app, so the row outlives a
 *                              server restart
 *
 *  NOT logged (transient by nature): long-poll waiters (in-flight goroutines).
 */
/** The journal's guarded state: the `Journal.on`/`path` fields behind a small
 *  SECOND `Mutex`, kept separate from `Store`'s cell so `Journal.rec`, called
 *  from INSIDE a `Store.withLock` span, acquires a DISTINCT mutex — no
 *  re-entrancy/deadlock. Write-once-at-boot state read on every mutation; a
 *  `Mutex` (not two atomics) keeps `on`/`path` reseated together in one
 *  transaction. */
class JournalState:
  var on: Boolean = false
  var path: String = ""

object Journal:
  private val cell: Mutex[JournalState] = mutex(new JournalState())

  /** the persistence gate, read at every store mutation site (off by default). */
  def enabled: Boolean = cell.withLock(js => js.on)

  /** the journal file's path, "" when persistence is off — the admin status
   *  panel reports its size. */
  def path(): String = cell.withLock(js => js.path)

  /** boot: read `WATA_LOG`; if set, replay the existing log then enable append.
   *  Called from `Server.serve` after `Store.init()` (which seeds the config
   *  users' default profiles — replay builds on top, overriding where it set). */
  def boot(): Unit =
    val p = go.sys.getenv("WATA_LOG")
    if p == "" then ()
    else bootWith(p)

  def bootWith(p: String): Unit =
    cell.withLock(js => js.path = p)
    replay(p)
    cell.withLock(js => js.on = true)
    println("Wata persistence ON, journal " + p)

  /** append one op line (only when enabled). The gate read, path read, and file
   *  append are ONE transaction under the small Journal lock — serializing the
   *  appends preserves the log's commit order (a desirable property), and the
   *  block returns `Unit`, so nothing leaks the guarded state. The JSON is
   *  rendered OUTSIDE the lock (it touches no guarded state). */
  def rec(op: Json): Unit =
    val line = Json.write(op) + "\n"
    cell.withLock(js => if js.on then go.sys.appendLine(js.path, line) else ())

  // ---- op builders (the CONCRETE record; one JObj per mutation) --------------

  def deviceOp(d: Device): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("device")) :: fs
    fs = ("device_id", JStr(d.deviceId)) :: fs
    fs = ("user_id", JStr(d.userId)) :: fs
    fs = ("token", JStr(d.accessToken)) :: fs
    fs = ("node_id", JStr(d.nodeId)) :: fs
    fs = ("created_ms", JInt(d.createdMs)) :: fs
    endObj(fs)

  def rmDeviceOp(deviceId: String, token: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("rmDevice")) :: fs
    fs = ("device_id", JStr(deviceId)) :: fs
    fs = ("token", JStr(token)) :: fs
    endObj(fs)

  def profileOp(userId: String, p: Profile): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("profile")) :: fs
    fs = ("user_id", JStr(userId)) :: fs
    fs = ("displayname", JStr(p.displayname)) :: fs
    fs = ("avatar_url", JStr(p.avatarUrl)) :: fs
    endObj(fs)

  def acctOp(a: AcctData): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("acct")) :: fs
    fs = ("user_id", JStr(a.userId)) :: fs
    fs = ("has_room", JBool(a.hasRoom)) :: fs
    fs = ("room_id", JStr(a.roomId)) :: fs
    fs = ("dtype", JStr(a.dtype)) :: fs
    fs = ("content", a.content) :: fs
    fs = ("seq", JInt(a.seq)) :: fs
    endObj(fs)

  def roomOp(roomId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("room")) :: fs
    fs = ("room_id", JStr(roomId)) :: fs
    endObj(fs)

  def eventOp(ev: Event): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("event")) :: fs
    fs = ("event_id", JStr(ev.eventId)) :: fs
    fs = ("type", JStr(ev.etype)) :: fs
    fs = ("sender", JStr(ev.sender)) :: fs
    fs = ("room_id", JStr(ev.roomId)) :: fs
    fs = ("ts", JInt(ev.ts)) :: fs
    fs = ("content", ev.content) :: fs
    fs = ("has_state_key", JBool(ev.hasStateKey)) :: fs
    fs = ("state_key", JStr(ev.stateKey)) :: fs
    fs = ("has_redacts", JBool(ev.hasRedacts)) :: fs
    fs = ("redacts", JStr(ev.redacts)) :: fs
    fs = ("unsigned", ev.unsigned) :: fs
    fs = ("seq", JInt(ev.seq)) :: fs
    endObj(fs)

  def redactOp(roomId: String, targetId: String, redId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("redact")) :: fs
    fs = ("room_id", JStr(roomId)) :: fs
    fs = ("target", JStr(targetId)) :: fs
    fs = ("red", JStr(redId)) :: fs
    endObj(fs)

  def aliasOp(alias: String, roomId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("alias")) :: fs
    fs = ("alias", JStr(alias)) :: fs
    fs = ("room_id", JStr(roomId)) :: fs
    endObj(fs)

  /** media METADATA; the bytes are already on disk (MediaFiles) when this op
   *  is appended. */
  def mediaOp(id: String, contentType: String, size: scala.Long): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("media")) :: fs
    fs = ("media_id", JStr(id)) :: fs
    fs = ("content_type", JStr(contentType)) :: fs
    fs = ("size", JInt(size)) :: fs
    endObj(fs)

  def receiptOp(rc: Receipt): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("receipt")) :: fs
    fs = ("room_id", JStr(rc.roomId)) :: fs
    fs = ("user_id", JStr(rc.userId)) :: fs
    fs = ("event_id", JStr(rc.eventId)) :: fs
    fs = ("ts", JInt(rc.ts)) :: fs
    fs = ("receipt_type", JStr(rc.receiptType)) :: fs
    fs = ("seq", JInt(rc.seq)) :: fs
    endObj(fs)

  /** a canonical DM pair claim: the SORTED pair and the room it claimed. */
  def dmPairOp(p: DmPair): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("dmpair")) :: fs
    fs = ("a", JStr(p.a)) :: fs
    fs = ("b", JStr(p.b)) :: fs
    fs = ("room_id", JStr(p.roomId)) :: fs
    endObj(fs)

  /** a device-account binding: the node id and the account's localpart. */
  def bindOp(nodeId: String, user: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("bind")) :: fs
    fs = ("node_id", JStr(nodeId)) :: fs
    fs = ("user", JStr(user)) :: fs
    endObj(fs)

  /** an enrolment revocation dropped the binding (plan 0058). */
  /** a handset nickname: the node id and its admin-given label (plan 0060);
   *  an empty name records a clear. Last write wins on replay. */
  def nickOp(nodeId: String, name: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("nick")) :: fs
    fs = ("node_id", JStr(nodeId)) :: fs
    fs = ("name", JStr(name)) :: fs
    endObj(fs)

  /** an APNs push registration (plan 0065): the concrete row. Replay applies
   *  them in commit order, and a re-registration overwrites both its (user,
   *  device) predecessor and any row holding the same token, so replay
   *  converges on exactly the live set. */
  def pushRegOp(reg: PushReg): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("pushreg")) :: fs
    fs = ("user_id", JStr(reg.userId)) :: fs
    fs = ("device_id", JStr(reg.deviceId)) :: fs
    fs = ("platform", JStr(reg.platform)) :: fs
    fs = ("token", JStr(reg.token)) :: fs
    fs = ("env", JStr(reg.env)) :: fs
    endObj(fs)

  /** the client unregistered its own session. */
  def pushUnregOp(deviceId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("pushunreg")) :: fs
    fs = ("device_id", JStr(deviceId)) :: fs
    endObj(fs)

  /** APNs answered 410 for the token: it is dead, and must stay dead across a
   *  restart. */
  def pushForgetOp(token: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("pushforget")) :: fs
    fs = ("token", JStr(token)) :: fs
    endObj(fs)

  /** a PushToTalk channel join (plan 0065 tier 3): the device's one live
   *  ephemeral channel token, replacing whatever it held. */
  def pttJoinOp(reg: ChannelReg): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("pttjoin")) :: fs
    fs = ("user_id", JStr(reg.userId)) :: fs
    fs = ("device_id", JStr(reg.deviceId)) :: fs
    fs = ("token", JStr(reg.token)) :: fs
    fs = ("env", JStr(reg.env)) :: fs
    endObj(fs)

  /** the channel was left, or APNs rejected its token: it is dead, and must
   *  stay dead across a restart. */
  def pttLeaveOp(deviceId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("pttleave")) :: fs
    fs = ("device_id", JStr(deviceId)) :: fs
    endObj(fs)

  def unbindOp(nodeId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("unbind")) :: fs
    fs = ("node_id", JStr(nodeId)) :: fs
    endObj(fs)

  def txnOp(key: String, eventId: String): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("op", JStr("txn")) :: fs
    fs = ("key", JStr(key)) :: fs
    fs = ("event_id", JStr(eventId)) :: fs
    endObj(fs)

  // ---- replay ----------------------------------------------------------------

  /** read the whole journal and apply each line (boot only; single-threaded, so
   *  the replay methods mutate the store WITHOUT locking). A missing file is not
   *  an error (first boot); a malformed line is skipped (best-effort). */
  def replay(p: String): Unit =
    var raw: go.Bytes = go.makeSlice[Byte](0)
    var ok = false
    try
      val v = go.sys.readFile(p)
      raw = v
      ok = true
      ()
    catch case e: sgo.GoError => ()
    if ok then replayLines(splitLines(go.string(raw), 0, "", Nil)) else ()

  /** split on '\n' into non-empty lines (STR-1 byte scan; the json module's own
   *  idiom — no String.split in the subset). */
  def splitLines(s: String, i: Int, cur: String, acc: List[String]): List[String] =
    if i >= s.length then finishLines(cur, acc)
    else splitStep(s, i, cur, acc)

  def splitStep(s: String, i: Int, cur: String, acc: List[String]): List[String] =
    val c = s.charAt(i)
    if c == '\n' then splitLines(s, i + 1, "", pushLine(cur, acc))
    else splitLines(s, i + 1, cur + charStr(c), acc)

  def finishLines(cur: String, acc: List[String]): List[String] =
    ListOps.reverse(pushLine(cur, acc))

  def pushLine(cur: String, acc: List[String]): List[String] =
    if cur == "" then acc else cur :: acc

  def charStr(c: Char): String = go.runeString(c.toInt)

  def replayLines(xs: List[String]): Unit = xs match
    case h :: t => replayLinesStep(h, t)
    case Nil  => ()

  def replayLinesStep(h: String, t: List[String]): Unit =
    replayLine(h)
    replayLines(t)

  def replayLine(line: String): Unit = Json.tryParse(line) match
    case Left(_)  => ()
    case Right(j) => dispatch(j, strField(j, "op", ""))

  def dispatch(j: Json, op: String): Unit =
    if op == "device" then Store.replayDevice(Device(strField(j, "device_id", ""), strField(j, "user_id", ""), strField(j, "token", ""), strField(j, "node_id", ""), longField(j, "created_ms")))
    else if op == "rmDevice" then Store.replayRmDevice(strField(j, "device_id", ""), strField(j, "token", ""))
    else if op == "profile" then Store.replayProfile(strField(j, "user_id", ""), Profile(strField(j, "displayname", ""), strField(j, "avatar_url", "")))
    else if op == "acct" then Store.replayAcct(acctOf(j))
    else if op == "room" then Store.replayRoom(strField(j, "room_id", ""))
    else if op == "event" then Store.replayEvent(eventOf(j))
    else if op == "redact" then Store.replayRedact(strField(j, "room_id", ""), strField(j, "target", ""), strField(j, "red", ""))
    else if op == "alias" then Store.replayAlias(strField(j, "alias", ""), strField(j, "room_id", ""))
    else if op == "media" then applyMedia(j)
    else if op == "receipt" then Store.replayReceipt(receiptOf(j))
    else if op == "txn" then Store.replayTxn(strField(j, "key", ""), strField(j, "event_id", ""))
    else if op == "dmpair" then Store.replayDmPair(DmPair(strField(j, "a", ""), strField(j, "b", ""), strField(j, "room_id", "")))
    else if op == "bind" then Bindings.replayBind(strField(j, "node_id", ""), strField(j, "user", ""))
    else if op == "unbind" then Bindings.replayUnbind(strField(j, "node_id", ""))
    else if op == "nick" then Nicknames.replay(strField(j, "node_id", ""), strField(j, "name", ""))
    else if op == "pushreg" then PushRegs.replayPut(pushRegOf(j))
    else if op == "pushunreg" then PushRegs.replayDropDevice(strField(j, "device_id", ""))
    else if op == "pushforget" then PushRegs.replayForget(strField(j, "token", ""))
    else if op == "pttjoin" then ChannelRegs.replayJoin(pttRegOf(j))
    else if op == "pttleave" then ChannelRegs.replayLeave(strField(j, "device_id", ""))
    else ()

  def acctOf(j: Json): AcctData =
    AcctData(strField(j, "user_id", ""), boolField(j, "has_room"), strField(j, "room_id", ""),
      strField(j, "dtype", ""), jsonField(j, "content"), longField(j, "seq"))

  def eventOf(j: Json): Event =
    Event(strField(j, "event_id", ""), strField(j, "type", ""), strField(j, "sender", ""),
      strField(j, "room_id", ""), longField(j, "ts"), jsonField(j, "content"),
      boolField(j, "has_state_key"), strField(j, "state_key", ""),
      boolField(j, "has_redacts"), strField(j, "redacts", ""), jsonField(j, "unsigned"),
      longField(j, "seq"))

  def pushRegOf(j: Json): PushReg =
    PushReg(strField(j, "user_id", ""), strField(j, "device_id", ""), strField(j, "platform", ""),
      strField(j, "token", ""), strField(j, "env", ""))

  def pttRegOf(j: Json): ChannelReg =
    ChannelReg(strField(j, "user_id", ""), strField(j, "device_id", ""),
      strField(j, "token", ""), strField(j, "env", ""))

  def receiptOf(j: Json): Receipt =
    Receipt(strField(j, "room_id", ""), strField(j, "user_id", ""), strField(j, "event_id", ""),
      longField(j, "ts"), strField(j, "receipt_type", ""), longField(j, "seq"))

  /** two generations of `media` op:
   *   - OLD (base64url `data` payload): MIGRATE — write the blob out to the
   *     media dir once per boot (the write truncates, so re-running over the
   *     same uncompacted journal converges) and keep metadata only. Without a
   *     media dir (`Journal.bootWith` outside `Server.serve`) the bytes stay
   *     in memory, the pre-file-store behavior.
   *   - SLIM (`{media_id, content_type, size}`): probe the blob file; a
   *     missing file logs and SKIPS the metadata insert, so fetches of the id
   *     404 — degraded, not fatal. */
  def applyMedia(j: Json): Unit = getField(j, "data") match
    case _: Some[Json] => migrateMedia(strField(j, "media_id", ""), strField(j, "content_type", ""), strField(j, "data", ""))
    case None => applySlimMedia(strField(j, "media_id", ""), strField(j, "content_type", ""))

  def migrateMedia(id: String, ct: String, enc: String): Unit =
    var raw: go.Bytes = go.makeSlice[Byte](0)
    try
      val v = go.encoding.base64.URLEncoding.decodeString(enc)
      raw = v
      ()
    catch case e: sgo.GoError => ()
    if MediaFiles.enabled then migrateMediaOut(id, ct, go.string(raw))
    else Store.replayMedia(id, go.string(raw), ct)

  def migrateMediaOut(id: String, ct: String, data: String): Unit =
    MediaFiles.write(id, data)
    Store.replayMediaMeta(id, ct)

  def applySlimMedia(id: String, ct: String): Unit =
    if MediaFiles.enabled && !MediaFiles.exists(id) then
      println("wata: media blob missing on replay, skipping " + id)
    else Store.replayMediaMeta(id, ct)

  // ---- field accessors (JInt / nested Json) ----------------------------------

  def longField(j: Json, k: String): scala.Long = getField(j, k) match
    case s: Some[Json] => longOr(s.value)
    case None => 0L

  def longOr(j: Json): scala.Long = j match
    case i: JInt => i.v
    case _       => 0L

  def jsonField(j: Json, k: String): Json = getField(j, k) match
    case s: Some[Json] => s.value
    case None => JNull()
