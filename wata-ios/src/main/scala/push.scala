import language.experimental.saferExceptions

/** The client half of APNs (plan 0065 tier 2): the device token this install
 *  was issued, posted to the homeserver so a message landing for it can wake
 *  the phone.
 *
 *  The platform side is `go-pkgs/iosshell/push.go` — permission,
 *  `registerForRemoteNotifications`, and the delegate callbacks that queue
 *  what iOS hands back. Nothing there decides anything; this is where the
 *  decisions are, on the pump's own goroutine.
 *
 *  {{{
 *  POST /_wata/v1/push/register  {"platform":"ios","token":"<hex>","env":"sandbox"}
 *  }}}
 *
 *  authenticated as this session, which is how the server names this device
 *  (wata-server's push.scala). Three facts shape the loop:
 *
 *  - **iOS re-issues the token** (a restore, a reinstall, occasionally on its
 *    own), so every value the system hands over is posted, not just the
 *    first. `takeDeviceToken` is a queue for exactly that reason.
 *  - **The POST can fail** — a phone that came up before its server did is
 *    the ordinary case — so it is RETRIED until the server takes it, on a
 *    fixed interval rather than a backoff: a registration that never lands is
 *    a walkie-talkie that never rings, and the request is one small POST.
 *  - **A new session re-registers.** Logging in again mints a fresh device
 *    id, and the row is stored per (user, device); the server drops the old
 *    row by token, but only when the new one arrives.
 *
 *  The POST runs on a spawned goroutine: the pump's frame is 33ms and a
 *  homeserver round trip is not.
 *
 *  `env` decides which of Apple's two hosts the server pushes to — a token
 *  minted under a development build is valid against the sandbox host only,
 *  an App Store one against production only. There is no runtime API that
 *  answers which, short of parsing the embedded provisioning profile, so it
 *  is `WATA_IOS_APNS_ENV` with **sandbox** as the default: every build this
 *  repo produces (hand-bundled simulator, sideloaded development) is a
 *  sandbox build, and an App Store build is a packaging decision that can
 *  carry the variable. */
object PushReg:

  /** how long before a failed registration is tried again. */
  val RETRY_MS: Long = 10000L

  /** the newest token iOS has issued, "" until one arrives. */
  private val tokenC: sgo.Atomic[String] = sgo.atomic("")
  /** the token the server has acknowledged — "" re-arms the POST. */
  private val doneC: sgo.Atomic[String] = sgo.atomic("")
  /** a POST is out; only one at a time. */
  private val inFlightC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** the earliest clock reading at which another POST may go out. */
  private val nextAtC: sgo.Atomic[Long] = sgo.atomic(0L)

  def env(): String =
    val e = go.sys.getenv("WATA_IOS_APNS_ENV")
    if e == "" then "sandbox" else e

  /** a session started (or restarted onto another server): whatever the last
   *  one registered says nothing about this one. */
  def newSession(): Unit =
    doneC.set("")
    nextAtC.set(0L)

  /** once per frame: pick up a newly issued token and keep trying to post it
   *  until the server takes it. Returns at once — the request is spawned. */
  def step(c: MatrixClient, nowMs: Long): Unit =
    val issued = go.iosshell.takeDeviceToken()
    if issued != "" then
      tokenC.set(issued)
      doneC.set("")
      nextAtC.set(0L)
      println("push: device token (" + issued.length + " hex chars)")
    val tok = tokenC.get()
    if due(tok, nowMs) then
      inFlightC.set(true)
      nextAtC.set(nowMs + RETRY_MS)
      sgo.spawn(() => PushReg.post(c, tok))

  def due(tok: String, nowMs: Long): Boolean =
    tok != "" && tok != doneC.get() && !inFlightC.get() &&
      nowMs >= nextAtC.get() && Runtime.lastAuth.accessToken != ""

  def post(c: MatrixClient, tok: String): Unit =
    val hs = Hs(c.http, c.clock, c.cfg.homeserver, Runtime.lastAuth.accessToken)
    // the token is hex and the env is ours, so neither needs escaping.
    val body = "{\"platform\":\"ios\",\"token\":\"" + tok + "\",\"env\":\"" + env() + "\"}"
    val resp = MatrixHttp.request(hs, "POST", "/_wata/v1/push/register",
      "application/json", body)
    if resp.status == 200 then
      doneC.set(tok)
      println("push: registered with the server")
    else println("push: register failed status=" + resp.status)
    inFlightC.set(false)
