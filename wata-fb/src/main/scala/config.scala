import language.experimental.saferExceptions

/** The on-disk config store: the credentials the device carries across
 *  restarts, so a boot-into-wata handheld comes up without anyone typing a
 *  homeserver.
 *
 *  `wataclient` owns the RECORD and its JSON (`Session` / `Sessions`) but
 *  deliberately no file IO — where the config lives is the app layer's call.
 *  This file is that call:
 *
 *    $WATA_FB_CONFIG   if set — one binary serves the device and a dev host
 *    /etc/wata/config.json   otherwise (the device default)
 *
 *  READ is best-effort in both directions: a missing file, an unreadable one
 *  and malformed JSON all resolve to the empty session, which
 *  `Sessions.isValid` rejects, which sends the sync loop down the password
 *  login path exactly as if nothing were stored. WRITE is best-effort too —
 *  the parent directory is created if it can be, the file is opened 0600, and
 *  a failure anywhere is silently a no-op. A device that cannot persist its
 *  session still runs; it just logs in again next boot.
 *
 *  `device_id` is written empty: `wataclient`'s `loginOrResume` publishes
 *  `AuthCreds(accessToken, userId)` and drops the login response's device id,
 *  and nothing reads the field back (`Sessions.isValid` wants a homeserver and
 *  a token). It is kept in the file because the Zig client's `config.json`
 *  has it.
 *
 *  The same file also carries the settings applet's own preferences, in three
 *  fields alongside the session. They are the device's, not the account's:
 *  someone who has set the backlight low and the screen timeout long should
 *  not have to set them again after a reboot. Session and preferences are
 *  written together — every write reads the whole store back first, so the
 *  half that did not change is preserved. */

/** the settings the settings applet owns and this store persists: panel
 *  brightness 0..40 and an index into the screen-timeout choices. Both are
 *  the DEVICE's, not the account's — a display name belongs to the account
 *  and is set through the admin interface (plan 0021), so nothing about a
 *  person is stored here. */
case class FbPrefs(brightness: scala.Int, timeoutIdx: scala.Int)

/** the device's `OutboxStore` (outbox.scala): one small file per queued voice
 *  message under `<config dir>/outbox/`, so a message recorded during an
 *  outage is still there after a reboot. `writable` is decided once, by
 *  probing the directory — a read-only rootfs then degrades to a memory queue
 *  for the session instead of pretending to persist. */
final class FbOutbox(val dir: String, val writable: Boolean) extends OutboxStore:
  def read(slot: scala.Int): String = FbConfig.readSlot(dir, slot)
  def write(slot: scala.Int, data: String): Boolean =
    var ok = false
    if writable then ok = FbConfig.writeSlot(dir, slot, data)
    ok
  def clear(slot: scala.Int): Unit = FbConfig.clearSlot(dir, slot)
  def persistent(): Boolean = writable

object FbConfig:

  /** the env var that overrides the config path. */
  val ENV_PATH = "WATA_FB_CONFIG"
  /** the device default — the rootfs location the Zig client also uses. */
  val DEVICE_PATH = "/etc/wata/config.json"

  /** 0600 and 0755 as the decimal literals the `perm` argument has to be —
   *  see syscall.scala's header on why these are never computed. */
  val FILE_PERM = 384
  val DIR_PERM = 493

  def path(): String =
    var p = go.sys.getenv(ENV_PATH)
    if p == "" then p = DEVICE_PATH
    p

  def empty(): Session = Session("", "", "", "", "")

  // ---- read ------------------------------------------------------------------

  /** the stored session, or the empty one when there is nothing readable. */
  def load(): Session = Sessions.fromJson(readJson())

  /** the stored preferences, or the defaults (full brightness, 1m screen
   *  timeout) for anything absent. */
  def loadPrefs(): FbPrefs = prefsFrom(readJson())

  def prefsFrom(j: Json): FbPrefs =
    FbPrefs(WJson.longField(j, "brightness", 40L).toInt,
      WJson.longField(j, "screen_timeout_idx", 1L).toInt)

  /** the parsed config object; `JNull` for absent/unreadable/malformed. */
  def readJson(): Json =
    var out: Json = JNull()
    try
      val raw = go.sys.readFile(path())
      out = MatrixHttp.parseOrNull(go.string(raw))
    catch case e: sgo.GoError => out = JNull()
    out

  // ---- write -----------------------------------------------------------------

  /** persist the credentials a live session logged in with, keeping whatever
   *  preferences are already stored. */
  def saveSession(s: Session): Unit = writeStore(s, loadPrefs())

  /** the post-login write: the homeserver and username this run was configured
   *  with, plus the token and user id the sync loop actually got back. */
  def saveLogin(homeserver: String, username: String, creds: AuthCreds): Unit =
    saveSession(Session(homeserver, sessionUser(username, creds.userId),
      creds.accessToken, creds.userId, ""))

  /** the username the stored session carries. Device-login (plan 0027) is
   *  configured with NO username — the node key is the credential — so the
   *  localpart of the user id the server answered stands in; storing ""
   *  would disagree with every username-keyed read (`storedFor`'s resume
   *  match, any UI that names the account). A password login keeps the name
   *  it was configured with. */
  def sessionUser(username: String, userId: String): String =
    var out = username
    if out == "" then out = Names.localpart(userId)
    out

  /** persist the settings applet's preferences, keeping the stored session. */
  def savePrefs(p: FbPrefs): Unit = writeStore(load(), p)

  /** the one writer: session fields first (the Zig client's file order), then
   *  the preference fields. Both halves are always written, so a caller that
   *  changed one of them has to have read the other back — which `saveSession`
   *  and `savePrefs` each do. */
  def writeStore(s: Session, p: FbPrefs): Unit = writeText(Json.write(toJson(s, p)))

  def toJson(s: Session, p: FbPrefs): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("screen_timeout_idx", JInt(p.timeoutIdx.toLong)) :: fs
    fs = ("brightness", JInt(p.brightness.toLong)) :: fs
    fs = ("device_id", JStr(s.deviceId)) :: fs
    fs = ("user_id", JStr(s.userId)) :: fs
    fs = ("access_token", JStr(s.accessToken)) :: fs
    fs = ("username", JStr(s.username)) :: fs
    fs = ("homeserver", JStr(s.homeserver)) :: fs
    JObj(fs)

  /** open 0600 (creating the parent dir if it is missing), truncate, write. */
  def writeText(text: String): Unit =
    val p = path()
    mkdirParent(p)
    try
      val fd = go.syscall.open(p,
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      go.syscall.write(fd, go.bytes(text))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()

  /** best-effort `mkdir` of the containing directory — one level, since the
   *  device's `/etc` always exists and a dev host's override path is expected
   *  to point somewhere that does too. An existing directory errors and the
   *  error is dropped, which is the intended outcome. */
  def mkdirParent(p: String): Unit =
    val d = parentDir(p)
    if d != "" then go.syscall.mkdir(d, 493)

  /** everything before the last `/`, or "" when the path has no directory
   *  part. Hand-scanned: the subset has no `lastIndexOf`. */
  def parentDir(p: String): String =
    var cut = -1
    var i = 0
    while i < p.length do
      if p.substring(i, i + 1) == "/" then cut = i
      i = i + 1
    var out = ""
    if cut > 0 then out = p.substring(0, cut)
    out

  // ---- the outbox store ----------------------------------------------------------

  /** the env var that overrides the outbox directory. */
  val ENV_OUTBOX = "WATA_FB_OUTBOX"

  /** where queued sends live: a directory beside the config file, so one
   *  `$WATA_FB_CONFIG` names one device's whole persistent state and two
   *  scripted runs cannot see each other's queue. */
  def outboxDir(): String =
    var d = go.sys.getenv(ENV_OUTBOX)
    if d == "" then d = parentDir(path()) + "/outbox"
    d

  /** the outbox store this run uses. Creating the directory is best-effort
   *  like every other write here, and the PROBE is what decides: a directory
   *  we cannot write is reported non-persistent, which makes the queue
   *  memory-only for the session and prints once (outbox.scala) rather than
   *  silently losing messages at the next reboot. */
  def outbox(): FbOutbox = outboxAt(outboxDir())

  /** the same store over a named directory — what a driver that has to point
   *  two clients at ONE queue uses. */
  def outboxAt(d: String): FbOutbox =
    mkdirParent(d)
    go.syscall.mkdir(d, 493)   // literal: `perm` must land as an untyped constant
    FbOutbox(d, probeWritable(d))

  def probeWritable(dir: String): Boolean =
    var ok = false
    try
      val fd = go.syscall.open(dir + "/.probe",
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      go.syscall.close(fd)
      go.syscall.unlink(dir + "/.probe")
      ok = true
    catch case e: sgo.GoError => ok = false
    ok

  /** one slot's file. Numbered, not named: the subset has no directory
   *  listing, so the loader scans a fixed slot range (outbox.scala). */
  def slotPath(dir: String, slot: scala.Int): String = dir + "/e" + slot + ".msg"

  def readSlot(dir: String, slot: scala.Int): String =
    var out = ""
    try
      val raw = go.sys.readFile(slotPath(dir, slot))
      out = go.string(raw)
    catch case e: sgo.GoError => out = ""
    out

  def writeSlot(dir: String, slot: scala.Int, data: String): Boolean =
    var ok = false
    try
      val fd = go.syscall.open(slotPath(dir, slot),
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      go.syscall.write(fd, go.bytes(data))
      go.syscall.close(fd)
      ok = true
    catch case e: sgo.GoError => ok = false
    ok

  /** TRUNCATE then unlink: an unlink that fails must still not leave a
   *  delivered message readable, or the next boot would send it again. */
  def clearSlot(dir: String, slot: scala.Int): Unit =
    val ok = writeSlot(dir, slot, "")
    go.syscall.unlink(slotPath(dir, slot))

  // ---- the run's client config -------------------------------------------------

  /** the `ClientConfig` one UI run drives: an explicitly given base/user/pass
   *  wins, and "" or "-" (the scripted driver's "unset" spelling, since its
   *  arguments are positional) falls back to the store. The stored session
   *  rides along for `loginOrResume` to try first; it only honors it when its
   *  homeserver matches, so a base override naturally forces a fresh login. */
  def resolve(base: String, user: String, pass: String, timeoutMs: scala.Int): ClientConfig =
    val stored = load()
    val b = pick(base, pick(stored.homeserver, irohBase()))
    val u = pick(user, stored.username)
    ClientConfig(b, u, pick(pass, ""), timeoutMs, storedFor(b, u, stored))

  /** under the iroh transport the homeserver is a label, not an address —
   *  every request rides the tunnel regardless. A factory-clean device (no
   *  stored session, no arguments, only the iroh config) must still boot:
   *  it comes up transport-refused, enrols, and device-login writes the
   *  first real session. `http://wata.iroh` is the spelling tunnel-smoke's
   *  credential-free client drives with. */
  def irohBase(): String =
    var out = ""
    if go.sys.getenv(Enrol.ENV_IROH) != "" then out = "http://wata.iroh"
    out

  /** the stored session is only offered to the run it belongs to — same
   *  homeserver AND same username. Naming a different user explicitly is
   *  asking for a different account, and must go through a password login
   *  rather than resume on the last user's token. */
  def storedFor(base: String, user: String, s: Session): Session =
    var out = empty()
    if s.homeserver == base && s.username == user then out = s
    out

  /** `arg`, not `given` — the latter is a Scala 3 soft keyword and cannot name
   *  a parameter. */
  def pick(arg: String, dflt: String): String =
    var out = dflt
    if arg != "" && arg != "-" then out = arg
    out

  /** the credential argument at `i`, or "" past the end — the drivers all take
   *  their credentials positionally and all of them are now optional. A `--`
   *  flag (`sim`'s `--once`) reads as absent, so a flag may sit in a slot a
   *  credential would otherwise have occupied. */
  def argAt(args: Array[String], i: scala.Int): String =
    var out = ""
    if i < args.length && !args(i).startsWith("--") then out = args(i)
    out

  /** what to print when neither the arguments nor the store name a server. */
  def noServerMsg(cmd: String): String =
    cmd + ": no homeserver — pass <base> <user> <pass>, or store a session in " + path()
