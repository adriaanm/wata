import language.experimental.saferExceptions

/** the enrolment screen's data, read once per frame (wataui's purity rule):
 *  the typed-fallback code and the footer hint. No QR modules — see below. */
case class EnrolSnap(code: String, hint: String)

/** ENROLMENT, the iOS way (plan 0062) — wata-fb's contract unchanged (the
 *  identity is minted on the device, enrolment is the approval of a public
 *  id) with the handset's QR replaced by the one thing a phone can do that
 *  a handset cannot: OPEN the admin page itself. Two arcs share this object:
 *
 *  SETUP (no iroh config yet — a fresh install): main.scala's setup wait
 *  opens the family admin page in Safari (`openSetupOnce`), whose "Add this
 *  phone" link is a `wata://configure?...` URL carrying the server's public
 *  card (peer node id, relay, direct addrs, plus the page's own origin as
 *  the admin URL — the address the phone provably reached it on).
 *  `handleConfigure` claims the bounce-back: the config is written into the
 *  sandbox beside config.json (any minted secretKey preserved) and the
 *  session restarts onto the iroh transport.
 *
 *  ENROLL (configured, transport refusing `not allowlisted`): the shared
 *  boot flow routes here exactly as on the handset (applets.scala's
 *  `enrolSnap`), `announceOnce` posts the public id over plain TCP, and
 *  `openOnce` bounces to `<adminUrl>/admin#enroll/<id>/<nonce>` — the same
 *  fragment the handset's QR encodes, opened by the enrollee itself. The
 *  page announces and highlights, the owner approves, the transport clears
 *  on its own redial, and device-login follows (plan 0027). The screen
 *  appearing IS the event for both the announce and the bounce; neither
 *  waits on a keypress, and both are once per session. */
object Enrol:

  /** overrides the derived sandbox path — how a harness pins the config. */
  val ENV_IROH = "WATA_IROH_CONFIG"
  /** overrides the admin base URL (wata-fb's same knob). */
  val ENV_ADMIN_URL = "WATA_ADMIN_URL"
  /** the Bonjour/mDNS name the server's install publishes; an iPhone
   *  resolves `.local` natively, which is exactly why the QR contract chose
   *  it (plan 0014). */
  val DEFAULT_ADMIN_URL = "http://wata.local:8008"
  val PREFIX_LEN = 8
  val ANNOUNCE_TIMEOUT_MS = 1500L
  val NONCE_ALPHA = "0123456789ABCDEFGHJKLMNPQRSTUVWX"

  private val idC: sgo.Atomic[String] = sgo.atomic("")
  private val idTriedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val idErrC: sgo.Atomic[String] = sgo.atomic("")
  private val nonceC: sgo.Atomic[String] = sgo.atomic("")
  private val announcedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val openedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val setupOpenedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val announceDoneC: sgo.Atomic[Boolean] = sgo.atomic(false)
  /** latched by the first real refusal this session — see `provisioning`. */
  private val everRefusedC: sgo.Atomic[Boolean] = sgo.atomic(false)

  // ---- where the config lives -----------------------------------------------------

  def irohPath(): String =
    val over = go.sys.getenv(ENV_IROH)
    if over != "" then over
    else
      val p = FbConfig.path()
      if p == "" then "" else FbConfig.parentDir(p) + "/iroh.json"

  def readCfg(): Json =
    val p = irohPath()
    if p == "" then JNull()
    else
      try MatrixHttp.parseOrNull(go.string(go.sys.readFile(p)))
      catch case e: sgo.GoError => JNull()

  /** is this device configured to speak iroh — a config with a peer exists.
   *  Everything enrolment offers hangs off this; a fresh install (no file)
   *  is the SETUP arc's territory instead. */
  def configured(): Boolean = WJson.strField(readCfg(), "peer", "") != ""

  // ---- the transport verdict (wata-fb's, verbatim) ---------------------------------

  /** has the transport refused this node id outright — the loud
   *  `server refused: 401 not allowlisted`? A state, not a verdict: the
   *  transport redials on the sync loop's cadence and clears the reason on
   *  the first dial that gets through, so an approval landing mid-session
   *  takes the enrol screen down without a restart. */
  def refused(): Boolean =
    val v = configured() && notAllowlisted(go.irohnet.lastRefusal())
    if v then everRefusedC.set(true) else ()
    v

  def notAllowlisted(reason: String): Boolean = reason.indexOf("not allowlisted") >= 0

  /** the admitted-but-not-yet-live arc after an approval (plan 0027): was
   *  refused this session, no longer is — the boot screen names it. */
  def provisioning(): Boolean = everRefusedC.get() && !refused()

  /** does the enrolment screen replace the boot screen this frame? */
  def required(): Boolean = configured() && refused()

  // ---- the identity (wata-fb's, path-parameterized) --------------------------------

  /** this device's node id, minting the key on the first call — tried ONCE
   *  per session (a config we cannot write will not become writable between
   *  two frames). */
  def nodeId(): String =
    if !idTriedC.get() then mintId()
    idC.get()

  /** the id lands BEFORE the tried flag: a concurrent nodeId() that observes
   *  tried=true must find the id already there, or it reads "" and (say) an
   *  announce silently skips — a race that actually fired between the frame
   *  thread and the spawned announce. */
  def mintId(): Unit =
    val p = irohPath()
    if p != "" then
      try idC.set(go.irohnet.ensureKey(p))
      catch
        case e: sgo.GoError =>
          println("enrol: cannot mint this device's node key: " + e.message)
          idErrC.set(e.message)
    idTriedC.set(true)

  def idError(): String = idErrC.get()

  def nonce(): String =
    if nonceC.get() == "" then nonceC.set(mintNonce(go.time.nowUnixMilli()))
    nonceC.get()

  /** four base-32 characters of the millisecond clock — the low bits. */
  def mintNonce(ms: Long): String =
    var v = ms
    if v < 0L then v = -v
    var out = ""
    var i = 0
    while i < 4 do
      val d = (v % 32L).toInt
      out = NONCE_ALPHA.substring(d, d + 1) + out
      v = v / 32L
      i = i + 1
    out

  def prefix(): String =
    val id = nodeId()
    if id.length <= PREFIX_LEN then id else id.substring(0, PREFIX_LEN)

  /** the typed fallback the admin page's code box matches. */
  def code(): String = nonce() + "-" + prefix()

  // ---- the admin URL ---------------------------------------------------------------

  def adminUrl(): String =
    val over = go.sys.getenv(ENV_ADMIN_URL)
    if over != "" then over
    else
      val cfg = WJson.strField(readCfg(), "adminUrl", "")
      if cfg != "" then cfg else DEFAULT_ADMIN_URL

  // ---- the announce + the Safari bounce --------------------------------------------

  /** post the public id over plain TCP, once per session, off the frame
   *  path — best-effort: a failed announce costs nothing, the page
   *  announces for us when the fragment link opens. */
  def announceOnce(): Unit =
    if !announcedC.get() then
      announcedC.set(true)
      // the id resolves HERE, on the calling thread, so the spawned POST can
      // never race the first mint (see mintId)
      val id = nodeId()
      sgo.spawn(() => announce(id))

  def announce(id: String): Unit =
    val base = adminUrl()
    if base != "" && id != "" then
      val hs = Hs(IosCaps.plainHttp(ANNOUNCE_TIMEOUT_MS), IosCaps.clock(), base, "")
      val resp = MatrixHttp.request(hs, "POST", "/_wata/v1/enroll", "application/json",
        "{\"nodeId\":\"" + id + "\",\"nonce\":\"" + nonce() + "\"}")
      println("enrol: announce " + base + " -> " + resp.status)
    announceDoneC.set(true)

  /** bounce to the admin page with this device's fragment — the QR contract's
   *  URL, opened by the enrollee itself. Once per session; the shell hops to
   *  the main thread itself and a failure to open costs only the bounce (the
   *  screen still shows the typed code).
   *
   *  It WAITS (off the frame path) for the announce to finish first: opening
   *  Safari backgrounds this app and iOS suspends it moments later, freezing
   *  the announce goroutine mid-POST — the pending row would never park, and
   *  the approve page would have nothing to approve. */
  def openOnce(): Unit =
    if !openedC.get() then
      openedC.set(true)
      val id = nodeId()
      if id != "" then
        sgo.spawn(() => openAfterAnnounce(id))

  def openAfterAnnounce(id: String): Unit =
    // generously past the POST's own timeout: the announce goroutine can be
    // held up behind the session's iroh client init before it even sends
    var waitMs = 10000L
    while !announceDoneC.get() && waitMs > 0L do
      IosCaps.sleepMs(50L)
      waitMs = waitMs - 50L
    println("enrol: opening " + adminUrl() + "/admin#enroll/…")
    go.watchshell.openURL(adminUrl() + "/admin#enroll/" + id + "/" + nonce())

  /** the SETUP arc's bounce: the bare admin page, where "Add this phone"
   *  lives. Once per session, from main.scala's setup wait. */
  def openSetupOnce(): Unit =
    if !setupOpenedC.get() then
      setupOpenedC.set(true)
      println("enrol: setup — opening " + adminUrl() + "/admin")
      go.watchshell.openURL(adminUrl() + "/admin")

  // ---- the configure bounce-back ---------------------------------------------------

  /** claim a `wata://configure?peer=..&relay=..&admin=..&addrs=..` URL: write
   *  the sandbox iroh config (preserving a minted secretKey and any field
   *  this code does not know) and report whether a restart onto the iroh
   *  transport is warranted. Any other URL answers false. */
  def handleConfigure(u: String): Boolean =
    if !u.startsWith("wata://configure") then false
    else
      val q = queryOf(u)
      val peer = param(q, "peer")
      if peer == "" then
        println("enrol: configure link without a peer — ignored")
        false
      else
        val relay0 = param(q, "relay")
        val relay = if relay0 == "" then "n0" else relay0
        writeCfg(peer, relay, param(q, "admin"), param(q, "addrs"))
        println("enrol: configured — server " + prefixOf(peer) + " relay " + relay)
        true

  def prefixOf(id: String): String =
    if id.length <= PREFIX_LEN then id else id.substring(0, PREFIX_LEN)

  def writeCfg(peer: String, relay: String, admin: String, addrsCsv: String): Unit =
    var fs: List[(String, Json)] = Nil
    readCfg() match
      case o: JObj =>
        var cur = o.fields
        var going = true
        while going do
          cur match
            case f :: t =>
              if f._1 != "peer" && f._1 != "relay" && f._1 != "adminUrl" && f._1 != "peerAddrs" then
                fs = (f._1, f._2) :: fs
              cur = t
            case Nil => going = false
      case _ => ()
    if addrsCsv != "" then fs = ("peerAddrs", JArr(csvJson(addrsCsv))) :: fs
    if admin != "" then fs = ("adminUrl", JStr(admin)) :: fs
    fs = ("relay", JStr(relay)) :: fs
    fs = ("peer", JStr(peer)) :: fs
    val p = irohPath()
    if p != "" then
      FbConfig.mkdirAll(FbConfig.parentDir(p))
      FbConfig.writeFile(p, Json.write(JObj(fs)))

  def csvJson(s: String): List[Json] =
    if s == "" then Nil
    else
      val i = s.indexOf(",")
      if i < 0 then JStr(s) :: Nil
      else JStr(s.substring(0, i)) :: csvJson(s.substring(i + 1))

  /** everything after the first `?`, "" when there is none. */
  def queryOf(u: String): String =
    val i = u.indexOf("?")
    if i < 0 then "" else u.substring(i + 1)

  /** the (percent-decoded) value of `key` in a query string, "" if absent. */
  def param(q: String, key: String): String =
    var rest = q
    var out = ""
    var going = true
    while going do
      val amp = rest.indexOf("&")
      val pair = if amp < 0 then rest else rest.substring(0, amp)
      val eq = pair.indexOf("=")
      if eq > 0 && pair.substring(0, eq) == key then
        out = pctDecode(pair.substring(eq + 1))
        going = false
      else if amp < 0 then going = false
      else rest = rest.substring(amp + 1)
    out

  /** application/x-www-form-urlencoded decoding for the ASCII range URLs
   *  live in (`%3A` etc., `+` for space) — a malformed escape passes
   *  through literally. Non-ASCII decodes to the glyph placeholder, which
   *  no field this link carries can contain. */
  def pctDecode(s: String): String =
    var out = ""
    var i = 0
    while i < s.length do
      val ch = s.substring(i, i + 1)
      if ch == "+" then
        out = out + " "
        i = i + 1
      else if ch == "%" && i + 3 <= s.length
          && hexVal(s.substring(i + 1, i + 2)) >= 0
          && hexVal(s.substring(i + 2, i + 3)) >= 0 then
        val h = hexVal(s.substring(i + 1, i + 2)) * 16 + hexVal(s.substring(i + 2, i + 3))
        out = out + IosGlyphs.ascii(h)
        i = i + 3
      else
        out = out + ch
        i = i + 1
    out

  def hexVal(ch: String): scala.Int =
    val lower = "0123456789abcdef".indexOf(ch)
    if lower >= 0 then lower
    else
      val upper = "0123456789ABCDEF".indexOf(ch)
      if upper >= 10 then upper else -1

  // ---- the screen ------------------------------------------------------------------

  /** the frame that shows the screen is the frame that bounces to Safari —
   *  same rationale as the announce riding `enrolSnap` (nobody presses
   *  anything on a device that has never connected). */
  def snap(hint: String): EnrolSnap =
    openOnce()
    EnrolSnap(code(), hint)

  def body(e: EnrolSnap): View =
    val l1 = "add this phone"
    val l2 = "approve it on the family"
    val l3 = "admin page (now in Safari)"
    val l4 = if e.code.length > 1 then "code " + e.code else "no device key"
    VGroup(
      Keyed("title", VText(0, 0, "WATA", Color.cyan)) ::
      Keyed("head", VText(FbPaint.centerCol(l1), 4, l1, Color.white)) ::
      Keyed("sub1", VText(FbPaint.centerCol(l2), 6, l2, Color.midGray)) ::
      Keyed("sub2", VText(FbPaint.centerCol(l3), 7, l3, Color.midGray)) ::
      Keyed("code", VText(FbPaint.centerCol(l4), 9, l4, Color.green)) ::
      Keyed("footer", VText(FbPaint.centerCol(e.hint), Font.ROWS - 1, e.hint, Color.midGray)) :: Nil)
