/** M8 chunk 3 — Matrix Client-Server API SHAPING over the `json` module, ported
 *  from fbclient `json_types.zig` / `client.zig` request builders. Request +
 *  response RECORD shapes and their Json encode/decode; NO transport (that is the
 *  `HttpDo` capability, driven in chunk 4). PORTABLE — ZERO `go`.
 *
 *  Bodies are built with the json module's var-prepend idiom (a multi-cons `::`
 *  literal in arg position lowers to a broken value-IIFE — DOGFOOD); responses
 *  are read through `WJson`. */
// ---- response records (json_types.zig) — TOP LEVEL (debt 2: a case class
// nested in an object lifts to a `$`-mangled Go name, a designed loud error). ----
case class LoginResult(userId: String, accessToken: String, deviceId: String)
case class WhoamiResult(userId: String, deviceId: String)
case class SendResult(eventId: String)
case class UploadResult(contentUri: String)
case class RoomResult(roomId: String)

object Matrix:

  def parseLogin(j: Json): LoginResult =
    LoginResult(
      WJson.strField(j, "user_id", ""),
      WJson.strField(j, "access_token", ""),
      WJson.strField(j, "device_id", "")
    )

  def parseWhoami(j: Json): WhoamiResult =
    WhoamiResult(WJson.strField(j, "user_id", ""), WJson.strField(j, "device_id", ""))

  def parseSend(j: Json): SendResult = SendResult(WJson.strField(j, "event_id", ""))
  def parseUpload(j: Json): UploadResult = UploadResult(WJson.strField(j, "content_uri", ""))
  def parseRoom(j: Json): RoomResult = RoomResult(WJson.strField(j, "room_id", ""))

  // ---- request bodies -------------------------------------------------------

  /** `POST /login` body: `{type, identifier:{type,user}, password}`. */
  def loginBody(user: String, password: String): Json =
    var idf: List[(String, Json)] = Nil[(String, Json)]()
    idf = ("user", JStr(user)) :: idf
    idf = ("type", JStr("m.id.user")) :: idf
    val ident = JObj(idf)
    var fs: List[(String, Json)] = Nil[(String, Json)]()
    fs = ("password", JStr(password)) :: fs
    fs = ("identifier", ident) :: fs
    fs = ("type", JStr("m.login.password")) :: fs
    JObj(fs)

  /** an `m.audio` voice-message event content (fbclient `client.zig`
   *  sendVoiceMessage): `{msgtype:"m.audio", body, url, info:{duration, mimetype,
   *  size}}`. */
  def voiceContent(mxcUrl: String, durationMs: Long, size: Int): Json =
    var info: List[(String, Json)] = Nil[(String, Json)]()
    info = ("size", JInt(size.toLong)) :: info
    info = ("mimetype", JStr("audio/ogg")) :: info
    info = ("duration", JInt(durationMs)) :: info
    val infoObj = JObj(info)
    var fs: List[(String, Json)] = Nil[(String, Json)]()
    fs = ("info", infoObj) :: fs
    fs = ("url", JStr(mxcUrl)) :: fs
    fs = ("body", JStr("Voice message")) :: fs
    fs = ("msgtype", JStr("m.audio")) :: fs
    JObj(fs)

  /** an `m.read` receipt has an empty JSON body `{}`. */
  def emptyBody(): Json = JObj(Nil[(String, Json)]())

  // ---- URL / query shaping --------------------------------------------------

  /** derive the media download URL from an `mxc://server/mediaId` URL:
   *  `<homeserver>/_matrix/media/v3/download/<server>/<mediaId>`. "" if malformed. */
  def mxcToDownloadUrl(homeserver: String, mxc: String): String =
    if !mxc.startsWith("mxc://") then ""
    else finishMxc(homeserver, mxc.substring(6))

  def finishMxc(homeserver: String, rest: String): String =
    val slash = rest.indexOf("/")
    if slash < 0 then ""
    else homeserver + "/_matrix/media/v3/download/" + rest.substring(0, slash) + "/" + rest.substring(slash + 1)

  /** the `/sync` query string. An empty `since` -> an initial sync (no `since`
   *  param); `timeoutMs == 0` -> a non-blocking sync. */
  def syncQuery(since: String, timeoutMs: Int): String =
    if since == "" then "?timeout=" + intStr(timeoutMs)
    else "?since=" + since + "&timeout=" + intStr(timeoutMs)

  /** a small non-negative int as decimal (STR-1: no `Long`; timeouts fit `Int`).
   *  Portable (no `strconv` facade): build digits then reverse. */
  def intStr(n: Int): String =
    if n == 0 then "0"
    else
      val b = new StringBuilder
      var x = n
      while x > 0 do
        b.append(digit(x % 10))
        x = x / 10
      reverseStr(b.toString)

  def digit(d: Int): Char = "0123456789".charAt(d)

  def reverseStr(s: String): String =
    val b = new StringBuilder
    var i = s.length - 1
    while i >= 0 do
      b.append(s.charAt(i))
      i = i - 1
    b.toString
