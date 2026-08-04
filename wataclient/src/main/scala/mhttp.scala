import language.experimental.saferExceptions

/** the Matrix HTTP CLIENT over the `HttpDo` capability. Every request goes
 *  through the injected `HttpDo.send`; the 429 retry sleeps via the `Clock`
 *  capability. Transport failure surfaces as `status == 0` (the app-side
 *  HttpDo maps a Go `error` to that — the core never sees Go errors).
 *
 *  Binary bodies (media upload/download) cross the capability as VERBATIM-byte
 *  Strings via `Bytes.rawString`/`Bytes.fromRawString` (a Go string IS immutable
 *  bytes; the JVM reference is ISO-8859-1, byte-exact both ways). */

/** the per-session request context: capabilities + base URL + access token.
 *  Immutable — logging in mints a new `Hs` carrying the new token. */
case class Hs(http: HttpDo, clock: Clock, base: String, token: String)

object MatrixHttp:

  def withToken(hs: Hs, token: String): Hs = Hs(hs.http, hs.clock, hs.base, token)

  /** one request through the capability, with a 429 retry loop (<= 3 retries,
   *  `retry_after_ms` else 1000ms). Any other status returns as-is — the
   *  CALLER decides what non-200 means. */
  def request(hs: Hs, method: String, path: String, contentType: String, body: String): HttpResponse =
    var resp = send1(hs, method, path, contentType, body)
    var attempt = 0
    while resp.status == 429 && attempt < 3 do
      hs.clock.sleepMs(retryAfterMs(resp.body))
      resp = send1(hs, method, path, contentType, body)
      attempt += 1
    resp

  def send1(hs: Hs, method: String, path: String, contentType: String, body: String): HttpResponse =
    var headers = Caps.noHeaders
    if hs.token != "" then
      headers = Caps.withHeader(headers, "Authorization", "Bearer " + hs.token)
    headers = Caps.withHeader(headers, "Content-Type", contentType)
    hs.http.send(HttpRequest(method, hs.base + path, headers, body))

  /** the 429 body's `retry_after_ms`, default 1000ms (also on unparseable
   *  JSON, or a non-positive value). */
  def retryAfterMs(body: String): Long =
    val v = WJson.longField(parseOrNull(body), "retry_after_ms", 1000L)
    if v > 0L then v else 1000L

  /** parse a response body, `JNull` when malformed (callers read fields with
   *  defaults). try-as-STATEMENT: a value-yielding try is outside the
   *  language subset. */
  def parseOrNull(body: String): Json =
    var out: Json = JNull()
    try
      val j = Json.parse(body)
      out = j
    catch case e: JsonError => out = JNull()
    out

  // ---- the API surface --------------------------------------------------------

  private def JSON = "application/json"

  /** POST /login. */
  def login(hs: Hs, user: String, password: String): HttpResponse =
    request(hs, "POST", "/_matrix/client/v3/login", JSON,
      Json.write(Matrix.loginBody(user, password)))

  /** GET /sync — long-polls up to `timeoutMs` server-side ("" since = initial). */
  def sync(hs: Hs, since: String, timeoutMs: Int): HttpResponse =
    request(hs, "GET", "/_matrix/client/v3/sync" + Matrix.syncQuery(since, timeoutMs), JSON, "")

  /** PUT profile displayname. */
  def setDisplayName(hs: Hs, userId: String, name: String): HttpResponse =
    var fs: List[(String, Json)] = Nil
    fs = ("displayname", JStr(name)) :: fs
    request(hs, "PUT", "/_matrix/client/v3/profile/" + userId + "/displayname", JSON,
      Json.write(JObj(fs)))

  /** PUT redact (reason "deleted"). */
  def redactEvent(hs: Hs, roomId: String, eventId: String, txnId: Int): HttpResponse =
    request(hs, "PUT",
      "/_matrix/client/v3/rooms/" + roomId + "/redact/" + eventId + "/" + Matrix.intStr(txnId),
      JSON, "{\"reason\":\"deleted\"}")

  /** POST m.read receipt. */
  def sendReadReceipt(hs: Hs, roomId: String, eventId: String): HttpResponse =
    request(hs, "POST",
      "/_matrix/client/v3/rooms/" + roomId + "/receipt/m.read/" + eventId, JSON, "{}")

  /** POST media upload (Ogg bytes cross as a raw-byte String). */
  def uploadMedia(hs: Hs, ogg: Bytes): HttpResponse =
    request(hs, "POST", "/_matrix/media/v3/upload?content_type=audio%2Fogg",
      "audio/ogg", ogg.rawString)

  /** GET media download; "" body + status 0 on a malformed mxc URL. */
  def downloadMedia(hs: Hs, mxcUrl: String): HttpResponse =
    val path = Matrix.mxcToDownloadUrl("", mxcUrl)
    if path == "" then HttpResponse(0, "")
    else request(hs, "GET", path, JSON, "")

  /** POST /join/{roomId} (invite auto-accept). */
  def joinRoom(hs: Hs, roomId: String): HttpResponse =
    request(hs, "POST", "/_matrix/client/v3/join/" + roomId, JSON, "{}")

  /** POST /createRoom — a DM (is_direct + trusted_private_chat). */
  def createRoom(hs: Hs, contactUserId: String): HttpResponse =
    var inv: List[Json] = Nil
    inv = JStr(contactUserId) :: inv
    var fs: List[(String, Json)] = Nil
    fs = ("visibility", JStr("private")) :: fs
    fs = ("preset", JStr("trusted_private_chat")) :: fs
    fs = ("invite", JArr(inv)) :: fs
    fs = ("is_direct", JBool(true)) :: fs
    request(hs, "POST", "/_matrix/client/v3/createRoom", JSON, Json.write(JObj(fs)))

  /** POST /createRoom with an alias localpart (the FAMILY room; public — the
   *  per-family wireguard boundary is the trust boundary). */
  def createRoomWithAlias(hs: Hs, aliasLocal: String, inviteUserId: String): HttpResponse =
    var inv: List[Json] = Nil
    inv = JStr(inviteUserId) :: inv
    var fs: List[(String, Json)] = Nil
    fs = ("visibility", JStr("public")) :: fs
    fs = ("preset", JStr("public_chat")) :: fs
    fs = ("invite", JArr(inv)) :: fs
    fs = ("room_alias_name", JStr(aliasLocal)) :: fs
    request(hs, "POST", "/_matrix/client/v3/createRoom", JSON, Json.write(JObj(fs)))

  /** GET account data (m.direct — may 404 when none yet, caller-handled). */
  def getAccountData(hs: Hs, userId: String, dtype: String): HttpResponse =
    request(hs, "GET", "/_matrix/client/v3/user/" + userId + "/account_data/" + dtype, JSON, "")

  /** PUT account data. */
  def setAccountData(hs: Hs, userId: String, dtype: String, body: String): HttpResponse =
    request(hs, "PUT", "/_matrix/client/v3/user/" + userId + "/account_data/" + dtype, JSON, body)

  /** GET /messages backward with NO from token — the newest `limit` events
   *  (the new-room tail backfill; wata-server's from is an event id, so a sync
   *  token would match nothing). */
  def getMessagesTail(hs: Hs, roomId: String, limit: Int): HttpResponse =
    request(hs, "GET",
      "/_matrix/client/v3/rooms/" + roomId + "/messages?dir=b&limit=" + Matrix.intStr(limit),
      JSON, "")

  /** GET /messages backward pagination (the limited-timeline backfill). */
  def getMessages(hs: Hs, roomId: String, from: String, limit: Int): HttpResponse =
    request(hs, "GET",
      "/_matrix/client/v3/rooms/" + roomId + "/messages?from=" + from + "&dir=b&limit=" + Matrix.intStr(limit),
      JSON, "")

  /** PUT a voice message (m.audio content, shaped by `Matrix.voiceContent`). */
  def sendVoiceMessage(hs: Hs, roomId: String, mxcUrl: String, durationMs: Long, size: Int, txnId: Int): HttpResponse =
    request(hs, "PUT",
      "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + Matrix.intStr(txnId),
      JSON, Json.write(Matrix.voiceContent(mxcUrl, durationMs, size)))

  // ---- response parse helpers ("" = absent throughout) -------------------------

  /** upload response -> `content_uri`, "" when absent. */
  def parseMxcUrl(body: String): String =
    WJson.strField(parseOrNull(body), "content_uri", "")

  /** createRoom response -> `room_id`, "" when absent. */
  def parseRoomId(body: String): String =
    WJson.strField(parseOrNull(body), "room_id", "")

  /** m.direct body -> the FIRST room listed for `contactId`, "" when none. */
  def findRoomForContact(body: String, contactId: String): String =
    firstString(WJson.objField(parseOrNull(body), contactId))

  def firstString(j: Json): String = j match
    case a: JArr => firstStringIn(a.items)
    case _       => ""

  def firstStringIn(items: List[Json]): String = items match
    case h :: t => WJson.strOr(h, "")
    case Nil  => ""

  /** parse the current m.direct object ("{}" when absent), append `roomId` to
   *  the contact's list (position-preserving upsert), serialize back. */
  def mdirectWithRoom(body: String, contactId: String, roomId: String): String =
    val j = parseOrNull(body)
    val fields = objFields(j)
    Json.write(JObj(upsertRoomList(fields, contactId, roomId, Nil)))

  def objFields(j: Json): List[(String, Json)] = j match
    case o: JObj => o.fields
    case _       => Nil

  def upsertRoomList(fs: List[(String, Json)], contactId: String, roomId: String,
                     acc: List[(String, Json)]): List[(String, Json)] = fs match
    case p :: t => upsertRoomStep(p, t, contactId, roomId, acc)
    case Nil  => ListOps.reverse((contactId, oneRoomArr(roomId)) :: acc)

  def upsertRoomStep(p: (String, Json), t: List[(String, Json)], contactId: String,
                     roomId: String, acc: List[(String, Json)]): List[(String, Json)] =
    val k: String = p._1
    if k == contactId then
      // cons BOUND first — a `::` in argument position does not lower
      // correctly here.
      val updated = (contactId, appendRoomArr(p._2, roomId)) :: acc
      revAppendFields(updated, t)
    else upsertRoomList(t, contactId, roomId, p :: acc)

  /** reverse `acc` onto `t` (the core List has no `:::`). */
  def revAppendFields(acc: List[(String, Json)], t: List[(String, Json)]): List[(String, Json)] = acc match
    case h :: r => revAppendStep(h, r, t)
    case Nil  => t

  def revAppendStep(h: (String, Json), r: List[(String, Json)],
                    t: List[(String, Json)]): List[(String, Json)] =
    revAppendFields(r, h :: t)

  def oneRoomArr(roomId: String): Json =
    var xs: List[Json] = Nil
    xs = JStr(roomId) :: xs
    JArr(xs)

  def appendRoomArr(j: Json, roomId: String): Json = j match
    case a: JArr => JArr(appendJson(a.items, JStr(roomId)))
    case _       => oneRoomArr(roomId)

  def appendJson(xs: List[Json], x: Json): List[Json] =
    ListOps.reverse(x :: ListOps.reverse(xs))
