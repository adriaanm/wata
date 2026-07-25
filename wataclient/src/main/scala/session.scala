/** the session/config record: `{homeserver, username, access_token, user_id,
 *  device_id}`, the credentials a device carries across restarts. A pure
 *  record + Json (de)serialization — no file IO here (that lives in the app
 *  layer, which decides where the config is stored). */
case class Session(
  homeserver: String,
  username: String,
  accessToken: String,
  userId: String,
  deviceId: String
)

object Sessions:

  /** encode a `Session` to its `config.json` object. */
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
