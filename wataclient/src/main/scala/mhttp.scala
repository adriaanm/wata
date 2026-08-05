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
   *  `retry_after_ms` clamped to [1s, 60s], else 1000ms). Any other status
   *  returns as-is — the CALLER decides what non-200 means. */
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
   *  JSON, or a non-positive value), clamped to 60000ms — the sync loop's own
   *  backoff ceiling (`runtime.scala`). The value is server-supplied; without
   *  the clamp a malicious or buggy homeserver could stall the caller's
   *  supervised scope for an arbitrary time per retry. */
  def retryAfterMs(body: String): Long =
    var v = WJson.longField(parseOrNull(body), "retry_after_ms", 1000L)
    if v <= 0L then v = 1000L
    if v > 60000L then v = 60000L
    v

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

  /** POST /_wata/v1/device-login — NO credentials (plan 0027): over the iroh
   *  transport the connection's proven node id is the credential, and the
   *  server answers the same shape as /login for the account bound to it at
   *  enrolment approval. Anywhere peer identity cannot be proven (any TCP
   *  path) the server answers 403 unconditionally; an admitted node whose
   *  binding has not landed yet is 404 — both are just failed logins to the
   *  session loop, which keeps retrying. */
  def deviceLogin(hs: Hs): HttpResponse =
    request(hs, "POST", "/_wata/v1/device-login", JSON, "{}")

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

  /** POST /_wata/v1/dm/{userId} — the wata dialect's DM resolution: ONE
   *  idempotent call that answers with THE room for this pair, minting it and
   *  joining both sides if it does not exist yet. Repeat calls, and calls from
   *  the two sides at once, all return the same room, so there is nothing here
   *  to reconcile — which is the whole point of the endpoint. */
  def dmRoom(hs: Hs, contactUserId: String): HttpResponse =
    request(hs, "POST", "/_wata/v1/dm/" + contactUserId, JSON, "{}")

  /** POST /_wata/v1/favorite/{roomId}/{eventId} — the wata dialect's favorite
   *  TOGGLE: the server writes (or clears) the `net.wata.favorite` state event
   *  that keeps the message past media retention, and answers
   *  `{"favorite": true|false}` with the resulting state. Any joined member of
   *  the room may call it; the marker is global, not per-user. */
  def setFavorite(hs: Hs, roomId: String, eventId: String): HttpResponse =
    request(hs, "POST", "/_wata/v1/favorite/" + roomId + "/" + eventId, JSON, "{}")

  /** the favorite endpoint's answer -> the resulting state (false when absent). */
  def parseFavorite(body: String): Boolean =
    WJson.boolField(parseOrNull(body), "favorite")

  /** POST /createRoom in the DM shape a STOCK Matrix client sends (`is_direct`
   *  + one invitee). The client core never calls this — `dmRoom` is its path —
   *  but the live oracle does, to hold the server to answering a stock client
   *  with the same canonical room. */
  def createRoomStockDm(hs: Hs, contactUserId: String): HttpResponse =
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

  /** GET /messages backward pagination (the limited-timeline backfill); `from`
   *  is the room's `prev_batch`, an opaque `s<seq>` position token. */
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

  /** createRoom / DM-endpoint response -> `room_id`, "" when absent. */
  def parseRoomId(body: String): String =
    WJson.strField(parseOrNull(body), "room_id", "")

  def objFields(j: Json): List[(String, Json)] = j match
    case o: JObj => o.fields
    case _       => Nil
