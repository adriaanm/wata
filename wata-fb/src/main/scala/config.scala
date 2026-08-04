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
 *  has it. */
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

  /** the parsed config object; `JNull` for absent/unreadable/malformed. */
  def readJson(): Json =
    var out: Json = JNull()
    try
      val raw = go.sys.readFile(path())
      out = MatrixHttp.parseOrNull(go.string(raw))
    catch case e: sgo.GoError => out = JNull()
    out

  // ---- write -----------------------------------------------------------------

  /** persist the credentials a live session logged in with. */
  def saveSession(s: Session): Unit = writeText(Sessions.write(s))

  /** the post-login write: the homeserver and username this run was configured
   *  with, plus the token and user id the sync loop actually got back. */
  def saveLogin(homeserver: String, username: String, creds: AuthCreds): Unit =
    saveSession(Session(homeserver, username, creds.accessToken, creds.userId, ""))

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

  // ---- the run's client config -------------------------------------------------

  /** the `ClientConfig` one UI run drives: an explicitly given base/user/pass
   *  wins, and "" or "-" (the scripted driver's "unset" spelling, since its
   *  arguments are positional) falls back to the store. The stored session
   *  rides along for `loginOrResume` to try first; it only honors it when its
   *  homeserver matches, so a base override naturally forces a fresh login. */
  def resolve(base: String, user: String, pass: String, timeoutMs: scala.Int): ClientConfig =
    val stored = load()
    val b = pick(base, stored.homeserver)
    val u = pick(user, stored.username)
    ClientConfig(b, u, pick(pass, ""), timeoutMs, storedFor(b, u, stored))

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
