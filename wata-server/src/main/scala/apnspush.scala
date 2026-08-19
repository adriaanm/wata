import language.experimental.saferExceptions
import JsonNav.*

/** The APNs provider client: the JWT, the request, and the verdict.
 *
 *  Apple's HTTP/2 provider API is one POST per push —
 *  `POST <host>/3/device/<token>` with a bearer provider token and four
 *  `apns-*` headers — and all of it is expressible here: the `net/http`
 *  facade issues requests with arbitrary headers, and Go negotiates HTTP/2
 *  over TLS on the client itself, so the protocol version is a property of
 *  the client rather than something the caller states. What is NOT expressible
 *  is signing with the operator's `.p8` key, and that is all `go.apns` is
 *  (apns.scala, go-pkgs/apns) — the same split the password hasher uses, where
 *  PBKDF2 is written here over bound HMAC primitives.
 *
 *  Everything that can be wrong about a push is therefore reachable from the
 *  server's own checks: the provider token's claims, the topic a PushToTalk
 *  push goes to, the payload's shape, and what a status code means.
 *  `SelfCheck` (server.scala) prints all four as pure values.
 *
 *  The credentials are armed once at boot (`configure`) and live in one
 *  guarded cell, because they are process-wide facts: an APNs key is
 *  team-owned, and a self-hoster brings one account or runs without pushes.
 *  The provider token minted from them is cached beside them — Apple rejects
 *  a token refreshed younger than 20 minutes and one older than 60
 *  (`InvalidProviderToken`), so `RefreshAfterSecs` sits inside both bounds and
 *  minting per push is exactly the mistake to avoid.
 */
class ApnsState:
  var teamId: String = ""
  var keyId: String = ""
  var topic: String = ""
  /** the cached provider token and the second it was minted at ("" = none). */
  var token: String = ""
  var mintedAt: scala.Long = 0L

object ApnsPush:
  /** Apple's two hosts. A registration's environment picks one; a test points
   *  `WATA_APNS_HOST` at its own fake instead. */
  val ProductionHost: String = "https://api.push.apple.com"
  val SandboxHost: String = "https://api.sandbox.push.apple.com"

  /** what a PushToTalk push's `apns-topic` adds to the bundle id. It is a
   *  DIFFERENT topic from the app's own — the same bundle serves both, and a
   *  `pushtotalk` push to the bare bundle id is rejected. */
  val PttTopicSuffix: String = ".voip-ptt"

  /** how long one provider token is reused, seconds (45 minutes). */
  val RefreshAfterSecs: scala.Long = 2700L

  private val state: sgo.Mutex[ApnsState] = sgo.mutex(new ApnsState())

  // ---- arming ------------------------------------------------------------------

  /** read the operator's `.p8` Auth Key and arm the pusher. Throws when the
   *  file is missing or is not a PKCS#8 EC key, leaving any previous key
   *  armed — a bad path must not disarm a working pusher. */
  def configure(teamId: String, keyId: String, topic: String, keyPath: String): Unit throws sgo.GoError =
    if teamId == "" || keyId == "" || topic == "" || keyPath == "" then
      throw go.errors.New("apns: configure needs teamId, keyId, topic and keyPath")
    else
      go.apns.loadKey(keyPath)
      state.withLock(s => arm(s, teamId, keyId, topic))

  /** arming REPLACES the cached provider token: it carries the old key's id and
   *  team in its claims, and a rotated key must not go on signing as the old
   *  one for the rest of the refresh window. */
  def arm(s: ApnsState, teamId: String, keyId: String, topic: String): Unit =
    s.teamId = teamId
    s.keyId = keyId
    s.topic = topic
    s.token = ""
    s.mintedAt = 0L

  /** is a pusher armed? False on every install without Apple credentials, and
   *  the send path then does nothing at all. */
  def configured(): Boolean =
    go.apns.loaded() && state.withLock(s => s.topic != "")

  /** the APNs host for a registration's environment ("sandbox" for a
   *  development build's token, production otherwise). A token minted against
   *  one environment is rejected by the other, so the environment belongs to
   *  the REGISTRATION rather than to the server. */
  def hostFor(env: String): String =
    if env == "sandbox" then SandboxHost else ProductionHost

  // ---- the provider token ------------------------------------------------------

  /** must a token minted at `mintedAt` be replaced to serve at `nowSecs`?
   *  Pure — the refresh policy is a decision, and `SelfCheck` prints it. */
  def stale(token: String, mintedAt: scala.Long, nowSecs: scala.Long): Boolean =
    token == "" || nowSecs - mintedAt >= RefreshAfterSecs

  /** the cached provider token, minting a fresh one when the cache is empty or
   *  past the refresh window.
   *
   *  The signing happens OUTSIDE the lock (a facade call that can throw cannot
   *  run inside `withLock`), so two goroutines racing the same expiry can each
   *  mint; both tokens are valid and the last one stored wins. That is the
   *  cost of not holding a lock across a network-free but throwing call, and
   *  it is bounded by the refresh window. */
  def bearer(nowSecs: scala.Long): String throws sgo.GoError =
    val cached = state.withLock(s => fresh(s, nowSecs))
    if cached != "" then cached
    else
      val who = state.withLock(s => (s.teamId, s.keyId))
      val minted = mint(who._1, who._2, nowSecs)
      state.withLock(s => store(s, minted, nowSecs))
      minted

  def fresh(s: ApnsState, nowSecs: scala.Long): String =
    if stale(s.token, s.mintedAt, nowSecs) then "" else s.token

  def store(s: ApnsState, token: String, nowSecs: scala.Long): Unit =
    s.token = token
    s.mintedAt = nowSecs

  /** an ES256 provider token: header `{alg,kid}`, claims `{iss,iat}`, and the
   *  JWS signature over the two of them. */
  def mint(teamId: String, keyId: String, nowSecs: scala.Long): String throws sgo.GoError =
    val signingInput = signingInputFor(teamId, keyId, nowSecs)
    signingInput + "." + go.apns.signES256(signingInput)

  /** the first two segments — pure, so the claims are checkable without a key. */
  def signingInputFor(teamId: String, keyId: String, nowSecs: scala.Long): String =
    // LIST-APPLY-TUPLE-ELEM-PTR: the fields are consed rather than written
    // `List(a, b)` — a varargs List of TUPLE literals emits a slice of
    // pointers into a parameter typed as a slice of values, and the Go build
    // fails. `::` is unaffected.
    b64url(Json.write(JObj(("alg", JStr("ES256")) :: ("kid", JStr(keyId)) :: Nil))) + "." +
      b64url(Json.write(JObj(("iss", JStr(teamId)) :: ("iat", JInt(nowSecs)) :: Nil)))

  /** base64url without padding — what a JWT segment is. */
  def b64url(s: String): String = go.b64url.RawURLEncoding.encodeToString(go.bytes(s))

  // ---- the payloads ------------------------------------------------------------

  /** the time-sensitive message-arrived notification: a title/body alert with
   *  the default sound, allowed to break through Focus and a mute switch (a
   *  walkie-talkie message warrants that and a routine notification does not),
   *  carrying the room and event id a tap needs to open the right conversation
   *  without a round trip first.
   *
   *  `badge` below 0 omits the key, leaving the app's count unchanged; 0
   *  clears it. Empty strings are omitted, as Go's `omitempty` did. */
  def alertPayload(title: String, body: String, roomId: String, eventId: String, badge: scala.Int): Json =
    JObj(withIds(("aps", JObj(alertAps(title, body, badge))) :: Nil, roomId, eventId))

  def alertAps(title: String, body: String, badge: scala.Int): List[(String, Json)] =
    var fs: List[(String, Json)] = ("alert", JObj(alertText(title, body))) :: ("sound", JStr("default")) :: Nil
    if badge >= 0 then fs = concatFields(fs, ("badge", JInt(badge.toLong)) :: Nil)
    concatFields(fs, ("interruption-level", JStr("time-sensitive")) :: Nil)

  def alertText(title: String, body: String): List[(String, Json)] =
    var fs: List[(String, Json)] = Nil
    if title != "" then fs = ("title", JStr(title)) :: Nil
    if body != "" then fs = concatFields(fs, ("body", JStr(body)) :: Nil)
    fs

  /** a PushToTalk push's whole body, which shares nothing with an alert's:
   *  there is no `aps` dictionary, because the system never presents this push
   *  — it hands it to the PushToTalk framework, which wakes the app and asks
   *  it to report the active speaker back. `activeSpeaker` is the key the
   *  framework requires; the room and event ids ride along as wata's own, so
   *  the woken app knows which clip to fetch. */
  def channelPayload(speaker: String, roomId: String, eventId: String): Json =
    JObj(withIds(("activeSpeaker", JStr(speaker)) :: Nil, roomId, eventId))

  def withIds(fs: List[(String, Json)], roomId: String, eventId: String): List[(String, Json)] =
    var out = fs
    if roomId != "" then out = concatFields(out, ("room_id", JStr(roomId)) :: Nil)
    if eventId != "" then out = concatFields(out, ("event_id", JStr(eventId)) :: Nil)
    out

  def concatFields(a: List[(String, Json)], b: List[(String, Json)]): List[(String, Json)] = a match
    case h :: t => h :: concatFields(t, b)
    case Nil    => b

  // ---- the requests ------------------------------------------------------------

  /** one time-sensitive alert to a device token; answers the HTTP status APNs
   *  gave. A rejection is a STATUS, not a throw — a throw means no verdict was
   *  reached at all (unarmed, dial failure). */
  def push(host: String, deviceToken: String, title: String, body: String,
           roomId: String, eventId: String, badge: scala.Int): scala.Int throws sgo.GoError =
    send(host, deviceToken, topicOf(false), "alert",
         Json.write(alertPayload(title, body, roomId, eventId, badge)))

  /** one PushToTalk push to an EPHEMERAL channel token: the token the
   *  framework minted for the app's current channel join, dead the moment that
   *  channel is left. It differs from `push` in everything Apple looks at —
   *  the push type, the topic, and a payload that names the active speaker
   *  rather than describing a banner. */
  def pushChannel(host: String, deviceToken: String, speaker: String,
                  roomId: String, eventId: String): scala.Int throws sgo.GoError =
    send(host, deviceToken, topicOf(true), "pushtotalk",
         Json.write(channelPayload(speaker, roomId, eventId)))

  /** the `apns-topic` for a push: the bundle id, plus the PushToTalk suffix for
   *  a `pushtotalk` push. */
  def topicOf(ptt: Boolean): String = topicFor(state.withLock(s => s.topic), ptt)

  /** pure, so both topics are printable without arming anything. */
  def topicFor(bundle: String, ptt: Boolean): String =
    if ptt then bundle + PttTopicSuffix else bundle

  /** the POST, with the five request properties Apple's provider API defines:
   *  the method and `/3/device/<token>` path, the bearer provider token, the
   *  topic, the push type, the priority (10 = deliver immediately), and the
   *  expiration (0 = do not store this for later, attempt delivery once).
   *
   *  A non-200 is logged with APNs' own `reason` rather than swallowed, since
   *  nothing else in the server would ever say why pushes stopped working. */
  def send(host: String, deviceToken: String, topic: String, pushType: String, payload: String): scala.Int throws sgo.GoError =
    if !configured() then throw go.errors.New("apns: not configured")
    else
      val tok = bearer(nowSecs())
      val req = go.net.http.newRequest("POST", host + "/3/device/" + deviceToken, go.strings.newReader(payload))
      req.header.set("authorization", "bearer " + tok)
      req.header.set("apns-topic", topic)
      req.header.set("apns-push-type", pushType)
      req.header.set("apns-priority", "10")
      req.header.set("apns-expiration", "0")
      val resp = go.net.http.DefaultClient.Do(req)
      val raw = go.io.readAll(resp.body)
      resp.body.close()
      val status = resp.statusCode.toInt
      if status != 200 then
        println("wata: apns " + pushType + " rejected, status " + longStr(status.toLong) +
                " reason " + reasonOf(go.string(raw)))
      else ()
      status

  /** APNs' own rejection reason, from the JSON body it answers a non-200 with
   *  (`{"reason":"BadDeviceToken"}`). "" when there is none to report. */
  def reasonOf(body: String): String = Json.tryParse(body) match
    case Right(j) => strField(j, "reason", "")
    case Left(_)  => ""

  def nowSecs(): scala.Long = go.time.nowUnixMilli() / 1000L
