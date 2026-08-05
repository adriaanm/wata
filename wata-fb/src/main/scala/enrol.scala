import language.experimental.saferExceptions

/** ENROLMENT — the device half of plan 0014: this handset's own iroh identity,
 *  and the screen a parent points a phone at to admit it.
 *
 *  THE IDENTITY IS MINTED HERE. A handset is deployed with an iroh config that
 *  names the family's server and carries NO secret; `irohnet.EnsureKey` mints
 *  an ed25519 key into that file on first boot (0600, every other field
 *  preserved) and answers the public node id. Nothing secret ever crosses the
 *  gap, so enrolment is the approval of a public id rather than the delivery
 *  of a key — which is what let the audio-modem idea be dropped.
 *
 *  THE CONFIG FILE — `$WATA_IROH_CONFIG`, the same JSON `go-pkgs/irohnet`
 *  reads (its `Config`):
 *
 *  {{{
 *  {
 *    "peer":      "<the family server's node id>",   baked at deploy time
 *    "relay":     "n0",                              baked at deploy time
 *    "peerAddrs": ["192.168.1.4:52011"],             optional, LAN only
 *    "adminUrl":  "http://192.168.1.4:8008",         baked at deploy time
 *    "secretKey": "<minted on first boot>"           NEVER deployed
 *  }
 *  }}}
 *
 *  `adminUrl` is the base URL of the admin interface, and it is CONFIG: the
 *  device cannot derive an address a parent's phone can reach (it does not
 *  know the server's LAN address, let alone a domain). `WATA_ADMIN_URL`
 *  overrides the field, which is how a harness pins it.
 *
 *  THE QR encodes `<adminUrl>/admin#enroll/<nodeId>/<nonce>`, so the stock
 *  camera app lands the parent on the admin page with this device's row
 *  singled out — no phone client, no camera on the handset. The page announces
 *  the id itself when no pending row matches (plan 0014's ruling), so the
 *  handset needs zero server connectivity to enroll.
 *
 *  THE TYPED CODE under the QR — `<nonce>-<first 8 of the node id>` — is the
 *  fallback for a scratched or dim screen. It SELECTS a pending row; it cannot
 *  create one, because 8 hex characters are not an identity and a server that
 *  accepted a prefix could be told to allowlist something no device ever
 *  announced. That scopes the fallback to a device that could announce itself
 *  — see `announceOnce` and the plan's fallback section.
 *
 *  DETERMINISM: the nonce is derived from the clock, so `force` exists for the
 *  scripted driver — a goldened frame pins both the node id and the nonce. */
object Enrol:

  /** the iroh config file; its presence is what "this device speaks iroh"
   *  means everywhere below. */
  val ENV_IROH = "WATA_IROH_CONFIG"
  /** the admin base URL, overriding the config file's `adminUrl`. */
  val ENV_ADMIN_URL = "WATA_ADMIN_URL"

  /** the announce is a courtesy, not a dependency: a short bound, one attempt,
   *  and a failure is a line on stdout. Blocking the frame loop for longer
   *  than this would be worse than not announcing at all. */
  val ANNOUNCE_TIMEOUT_MS: Long = 1500L

  /** how many characters of the node id the typed code carries. Eight hex
   *  characters is 32 bits — plenty to pick one row out of the eight a server
   *  holds at a time, and short enough for a parent to read off a 160x128
   *  panel and type on a phone. */
  val PREFIX_LEN = 8

  // ---- the session's cells -----------------------------------------------------
  // Written by the UI goroutine on first use and read every frame; `sgo.Atomic`
  // rather than plain vars for the same reason `Ui`'s cells are.
  private val idC: sgo.Atomic[String] = sgo.atomic("")
  private val idTriedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val idErrC: sgo.Atomic[String] = sgo.atomic("")
  private val nonceC: sgo.Atomic[String] = sgo.atomic("")
  private val urlC: sgo.Atomic[String] = sgo.atomic("")
  private val urlTriedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  private val announcedC: sgo.Atomic[Boolean] = sgo.atomic(false)
  // the scripted driver's overrides: -1 = the real verdict, 0/1 = forced.
  private val forceRefusedC: sgo.Atomic[scala.Int] = sgo.atomic(-1)

  /** is this device configured to speak iroh at all? Everything enrolment
   *  offers — the settings row, the boot-state takeover — hangs off this;
   *  a plain-TCP deployment never sees any of it. */
  def configured(): Boolean = go.sys.getenv(ENV_IROH) != ""

  /** has the transport refused this node id outright — the loud
   *  `server refused: 401 not allowlisted` (go-pkgs/irohnet)? That is a
   *  permanent state no retry can fix and the ONE case where the device has
   *  something useful to show instead of "waiting for network": its QR. */
  def refused(): Boolean =
    val forced = forceRefusedC.get()
    if forced >= 0 then forced == 1
    else configured() && notAllowlisted(go.irohnet.lastRefusal())

  def notAllowlisted(reason: String): Boolean = reason.indexOf("not allowlisted") >= 0

  /** does the enrolment screen replace the boot screen this frame? */
  def required(): Boolean = configured() && refused()

  // ---- the identity ---------------------------------------------------------------

  /** this device's node id, minting the key on the first call. Minting is
   *  tried ONCE per session: a config we cannot write is not going to become
   *  writable between two frames, and retrying inside the render path would
   *  turn a broken deployment into a stuttering one. */
  def nodeId(): String =
    if !idTriedC.get() then mintId()
    idC.get()

  def mintId(): Unit =
    idTriedC.set(true)
    val p = go.sys.getenv(ENV_IROH)
    if p != "" then
      try idC.set(go.irohnet.ensureKey(p))
      catch case e: sgo.GoError => noteIdError(e.message)

  def noteIdError(msg: String): Unit =
    println("enrol: cannot mint this device's node key: " + msg)
    idErrC.set(msg)

  /** why there is no node id, or "" when there is one (or nothing was tried). */
  def idError(): String = idErrC.get()

  /** the session's correlator — short, printable, and NOT a credential: it
   *  says "this is the handset in my hand" to a parent comparing a screen with
   *  a page. Minted once per session from the clock, in the same alphabet the
   *  server's `validNonce` accepts. */
  def nonce(): String =
    // bind to a String local so `==` stays native: comparing an
    // `Atomic[String].get()` against a literal in place emits a call to an
    // undefined `equalsObject(Object, Object)` (sgola ticket ATOMIC-STR-EQ).
    val cur: String = nonceC.get()
    if cur == "" then nonceC.set(mintNonce(go.time.nowUnixMilli()))
    nonceC.get()

  /** four base-32 characters of the millisecond clock — the low bits, which
   *  are the ones that actually differ between two boots. */
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

  val NONCE_ALPHA = "0123456789ABCDEFGHJKLMNPQRSTUVWX"

  /** the first `PREFIX_LEN` characters of the node id — the half of the typed
   *  code that says WHICH device. */
  def prefix(): String =
    val id = nodeId()
    if id.length <= PREFIX_LEN then id else id.substring(0, PREFIX_LEN)

  /** what the parent types into the admin page: nonce and prefix, joined so
   *  the two halves cannot run together on a small screen. */
  def code(): String = nonce() + "-" + prefix()

  // ---- the admin URL -----------------------------------------------------------------

  /** the admin interface's base URL: `WATA_ADMIN_URL`, else the iroh config's
   *  `adminUrl`. Never derived — the device does not know an address a phone
   *  on the family's wifi can reach, and guessing one produces a QR that leads
   *  nowhere. Empty means the screen says so instead of encoding a dead link. */
  def adminUrl(): String =
    if !urlTriedC.get() then
      urlTriedC.set(true)
      urlC.set(trimSlash(resolveUrl()))
    urlC.get()

  def resolveUrl(): String =
    val env = go.sys.getenv(ENV_ADMIN_URL)
    if env != "" then env else WJson.strField(configJson(), "adminUrl", "")

  def configJson(): Json =
    var out: Json = JNull()
    val p = go.sys.getenv(ENV_IROH)
    if p != "" then
      try out = MatrixHttp.parseOrNull(go.string(go.sys.readFile(p)))
      catch case e: sgo.GoError => out = JNull()
    out

  /** a trailing `/` would double up against the `/admin` path. */
  def trimSlash(s: String): String =
    if s.length > 0 && s.substring(s.length - 1, s.length) == "/" then s.substring(0, s.length - 1)
    else s

  /** the URL the QR encodes — the admin page's enrolment fragment. */
  def url(): String =
    val base = adminUrl()
    val id = nodeId()
    if base == "" || id == "" then ""
    else base + "/admin#enroll/" + id + "/" + nonce()

  // ---- the best-effort announce -------------------------------------------------------

  /** tell the server this device exists, ONCE per session, over PLAIN TCP.
   *  Silent on failure: the parent's phone announces from the page when this
   *  cannot get through (plan 0014's ruling), so the only thing a failure
   *  costs is the typed-code fallback, which needs a pending row to select.
   *  Called when the enrolment screen first appears — a device is only ever
   *  waiting to be admitted while someone is looking at that screen. */
  def announceOnce(): Unit =
    if !announcedC.get() then
      announcedC.set(true)
      announce()

  def announce(): Unit =
    val base = adminUrl()
    val id = nodeId()
    if base != "" && id != "" then
      val hs = Hs(FbCaps.plainHttp(ANNOUNCE_TIMEOUT_MS), FbCaps.clock(), base, "")
      val resp = MatrixHttp.request(hs, "POST", "/_wata/v1/enroll", "application/json",
        "{\"nodeId\":\"" + id + "\",\"nonce\":\"" + nonce() + "\"}")
      println("enrol: announce " + base + " -> " + resp.status)

  // ---- the scripted driver's hooks -------------------------------------------------

  /** pin the identity a goldened frame draws (uitest only). The QR is a pure
   *  function of node id + nonce + admin URL, so pinning those three makes the
   *  frame byte-reproducible; without it every run would mint a fresh key. */
  def force(id: String, n: String): Unit =
    idC.set(id)
    idTriedC.set(true)
    idErrC.set("")
    nonceC.set(n)
    announcedC.set(true)   // a scripted run announces nothing

  /** force the not-allowlisted verdict (uitest only; -1 hands it back). */
  def forceRefused(tag: scala.Int): Unit = forceRefusedC.set(tag)

  /** a fresh session per run, like `Ui.resetCells`. */
  def reset(): Unit =
    idC.set("")
    idTriedC.set(false)
    idErrC.set("")
    nonceC.set("")
    urlC.set("")
    urlTriedC.set(false)
    announcedC.set(false)
    forceRefusedC.set(-1)

  // ---- the screen ------------------------------------------------------------------

  /** the grid row the typed code sits on, and the one under it — the two rows
   *  left below the QR block on the 15-row landscape grid. */
  val CODE_ROW = 13
  val HINT_ROW = 14
  /** the first pixel row of the QR block (under the header). */
  val TOP_Y = 10
  /** the quiet zone, in modules per side. The spec asks for four; two is what
   *  a 160x128 panel can afford, and it buys a whole pixel per module — which
   *  is the thing a phone camera actually needs. The screen around it is white
   *  for the full block, so the border reads as wider than it is. */
  val QUIET = 2

  /** the enrolment screen: header, QR, typed code, one line of instruction.
   *  `hint` is the caller's — the settings applet names the key that closes
   *  the screen, the boot state names the keys it already had. */
  def render(px: go.Bytes, hint: String): Unit =
    Font.drawText(px, "ENROLL", 0, 0, Color.cyan, false, 0)
    val u = url()
    if u == "" then renderUnconfigured(px)
    else
      val drawn = drawQr(px, u)
      if !drawn then Font.drawTextCentered(px, "QR failed", 6, Color.red, false, 0)
      Font.drawTextCentered(px, code(), CODE_ROW, Color.white, false, 0)
    Font.drawTextCentered(px, hint, HINT_ROW, Color.midGray, false, 0)

  /** nothing to encode: say WHICH half is missing, since the two have
   *  completely different fixes (a deployment that forgot `adminUrl` vs a
   *  config the device cannot mint a key into). */
  def renderUnconfigured(px: go.Bytes): Unit =
    if nodeId() == "" then
      Font.drawTextCentered(px, "no device key", 6, Color.red, false, 0)
      Font.drawTextCentered(px, WataLogic.clip(shortErr(), 26), 7, Color.midGray, false, 0)
    else
      Font.drawTextCentered(px, "no admin URL", 6, Color.red, false, 0)
      Font.drawTextCentered(px, "set adminUrl in", 7, Color.midGray, false, 0)
      Font.drawTextCentered(px, "the iroh config", 8, Color.midGray, false, 0)

  def shortErr(): String =
    if idError() == "" then "check the iroh config" else idError()

  /** encode and blit. False when the encoder refused the text (a payload too
   *  long for any version), which is a deployment error — an admin URL nobody
   *  can scan — and says so rather than drawing nothing. */
  def drawQr(px: go.Bytes, text: String): Boolean =
    var ok = false
    try
      val m = go.qr.matrix(text)
      blit(px, m, isqrt(m.length))
      ok = true
    catch case e: sgo.GoError => println("enrol: QR encode failed: " + e.message)
    ok

  /** the largest whole `n` with `n*n <= v` — the module grid's side, since the
   *  facade hands back one flat array. */
  def isqrt(v: scala.Int): scala.Int =
    var n = 0
    while (n + 1) * (n + 1) <= v do n = n + 1
    n

  /** white block, dark modules on top, centered horizontally. Integer scale
   *  only: a QR module drawn at a fractional size is a QR module a camera
   *  cannot decode. */
  def blit(px: go.Bytes, m: go.Bytes, side: scala.Int): Unit =
    if side > 0 then
      val full = side + 2 * QUIET
      val scale = scaleFor(full)
      val dim = full * scale
      val x0 = (Display.W - dim) / 2
      Draw.fillRect(px, x0, TOP_Y, dim, dim, Color.white)
      var y = 0
      while y < side do
        var x = 0
        while x < side do
          if m(y * side + x).toInt != 0 then
            Draw.fillRect(px, x0 + (x + QUIET) * scale, TOP_Y + (y + QUIET) * scale,
              scale, scale, Color.black)
          x = x + 1
        y = y + 1

  /** pixels per module: as many as fit between the header and the code line,
   *  at least one. 41 modules (the ~110-byte URL this screen encodes) plus the
   *  quiet zone lands on 2 — a 90x90 block, which is what the panel has room
   *  for; a shorter admin URL earns 3 on its own. */
  def scaleFor(full: scala.Int): scala.Int =
    val availH = 1 + CODE_ROW * Font.GLYPH_H - TOP_Y
    var s = Display.W / full
    if availH / full < s then s = availH / full
    if s < 1 then s = 1
    s
