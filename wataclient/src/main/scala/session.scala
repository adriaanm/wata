/** M8 chunk 3 — the session/config record (decision 8), shape-compatible with
 *  fbclient `config.zig`'s `Session` so a device carries credentials across the
 *  Zig->Sgola swap: `{homeserver, username, access_token, user_id, device_id}`.
 *  PURE record + Json (de)serialization over the `json` module — NO file IO here
 *  (that is the app/config layer, chunk 4/7). ZERO `go`. */
case class Session(
  homeserver: String,
  username: String,
  accessToken: String,
  userId: String,
  deviceId: String
)

object Sessions:

  /** encode a `Session` to its `config.json` object (field order matches
   *  config.zig's `saveSession` writer). */
  def toJson(s: Session): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("device_id", JStr(s.deviceId)) :: fs
    fs = ("user_id", JStr(s.userId)) :: fs
    fs = ("access_token", JStr(s.accessToken)) :: fs
    fs = ("username", JStr(s.username)) :: fs
    fs = ("homeserver", JStr(s.homeserver)) :: fs
    JObj(fs)

  /** the compact `config.json` text. */
  def write(s: Session): String = Json.write(toJson(s))

  /** decode a parsed config object into a `Session` (missing fields -> ""). */
  def fromJson(j: Json): Session =
    Session(
      WJson.strField(j, "homeserver", ""),
      WJson.strField(j, "username", ""),
      WJson.strField(j, "access_token", ""),
      WJson.strField(j, "user_id", ""),
      WJson.strField(j, "device_id", "")
    )

  /** a session is usable only with a homeserver AND an access token. */
  def isValid(s: Session): Boolean = s.homeserver != "" && s.accessToken != ""
