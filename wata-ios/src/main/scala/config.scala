import language.experimental.saferExceptions

/** The iOS client's persistent state — wata-mac's config.scala (plan 0036's
 *  three stores) with the keychain seam swapped for a file: everything lives
 *  in the app's own SANDBOX (`$HOME` inside an iOS app is the app's data
 *  container, writable and private to the app), so the simulator client
 *  needs no entitlements and no Security.framework binding yet.
 *
 *  THE SECRETS FILE (`secrets.json`, 0600, beside the config) holds the
 *  access token and the password, keyed `<kind>:<user>@<homeserver>` like
 *  the mac's keychain accounts. On a real phone these belong in the iOS
 *  keychain — that crossing is deliberate later work (the signed-device
 *  legs), not something the simulator stage fakes; the sandbox file is the
 *  honest simulator-grade store and this comment is its expiry note.
 *
 *  THE CONFIG FILE holds the non-secret half — homeserver, username, user
 *  id — plus the preferences (the settings applet's two, and the
 *  walkie-talkie toggle `notify_mode`). No token is ever written here.
 *
 *  THE OUTBOX is numbered slot files in a directory beside the config file,
 *  the same shape wata-fb uses.
 *
 *  The object is named `FbConfig` because the COPIED shared sources reach
 *  for it by that name (`FbConfig.savePrefs`). */
object FbConfig:

  /** the env vars that override the paths. */
  val ENV_PATH = "WATA_IOS_CONFIG"
  val ENV_OUTBOX = "WATA_IOS_OUTBOX"
  /** where a first run with nothing configured points. */
  val DEFAULT_HS = "http://127.0.0.1:8008"

  /** 0600 and 0755 as the decimal literals the `perm` argument has to be —
   *  see syscall.scala on why these are never computed. */
  val FILE_PERM = 384
  val DIR_PERM = 493

  // ---- where ---------------------------------------------------------------

  /** `$HOME/Library/Application Support/wata` — inside the app sandbox. A
   *  run with no `$HOME` gets "", which every reader below turns into
   *  "nothing stored" rather than a path relative to the working dir. */
  def stateDir(): String =
    val home = go.sys.getenv("HOME")
    var out = ""
    if home != "" then out = home + "/Library/Application Support/wata"
    out

  /** the persistent log (plan 0064): `$HOME/Documents/wata.log` — Documents
   *  exists in every iOS sandbox and is what `just ios-log` copies off the
   *  phone. "" with no `$HOME` (no sandbox — skip the tee). */
  def logPath(): String =
    val home = go.sys.getenv("HOME")
    var out = ""
    if home != "" then out = home + "/Documents/wata.log"
    out

  def path(): String =
    var p = go.sys.getenv(ENV_PATH)
    if p == "" then
      val d = stateDir()
      if d != "" then p = d + "/config.json"
    p

  def secretsPath(): String =
    val p = path()
    var out = ""
    if p != "" then out = parentDir(p) + "/secrets.json"
    out

  def outboxDir(): String =
    var d = go.sys.getenv(ENV_OUTBOX)
    if d == "" then
      val p = path()
      if p != "" then d = parentDir(p) + "/outbox"
    d

  /** the secrets key: the identity a credential belongs to. Both halves
   *  matter — the same username against two homeservers is two accounts. */
  def account(homeserver: String, username: String): String =
    username + "@" + homeserver

  // ---- the secrets ---------------------------------------------------------

  def tokenAccount(hs: String, user: String): String = "token:" + account(hs, user)
  def passAccount(hs: String, user: String): String = "password:" + account(hs, user)

  def readSecrets(): Json =
    var out: Json = JNull()
    val p = secretsPath()
    if p != "" then
      try out = MatrixHttp.parseOrNull(go.string(go.sys.readFile(p)))
      catch case e: sgo.GoError => out = JNull()
    out

  def writeSecrets(j: Json): Unit =
    val p = secretsPath()
    if p != "" then
      mkdirAll(parentDir(p))
      writeFile(p, Json.write(j))

  def secretField(key: String): String = WJson.strField(readSecrets(), key, "")

  /** set (or clear, with "") one key in the secrets object. */
  def putSecret(key: String, value: String): Unit =
    var fs: List[(String, Json)] = Nil
    readSecrets() match
      case o: JObj =>
        var cur = o.fields
        var going = true
        while going do
          cur match
            case f :: t =>
              if f._1 != key then fs = (f._1, f._2) :: fs
              cur = t
            case Nil => going = false
      case _ => ()
    if value != "" then fs = (key, JStr(value)) :: fs
    writeSecrets(JObj(fs))

  def loadToken(hs: String, user: String): String =
    if user == "" || hs == "" then "" else secretField(tokenAccount(hs, user))

  def loadPassword(hs: String, user: String): String =
    if user == "" || hs == "" then "" else secretField(passAccount(hs, user))

  def saveToken(hs: String, user: String, token: String): Unit =
    if user != "" && hs != "" && token != "" then putSecret(tokenAccount(hs, user), token)

  def savePassword(hs: String, user: String, pass: String): Unit =
    if user != "" && hs != "" && pass != "" then putSecret(passAccount(hs, user), pass)

  /** forget both secrets for one identity — the rejected arc's half of
   *  `forgetAndReload`. */
  def forget(hs: String, user: String): Unit =
    putSecret(tokenAccount(hs, user), "")
    putSecret(passAccount(hs, user), "")

  // ---- the config file -----------------------------------------------------

  def empty(): Session = Session("", "", "", "", "")

  /** the parsed config object; `JNull` for absent/unreadable/malformed. */
  def readJson(): Json =
    var out: Json = JNull()
    val p = path()
    if p != "" then
      try
        val raw = go.sys.readFile(p)
        out = MatrixHttp.parseOrNull(go.string(raw))
      catch case e: sgo.GoError => out = JNull()
    out

  /** the stored session WITH its token filled in from the secrets file — the
   *  config file never holds one, so a session read straight off disk would
   *  always fail `Sessions.isValid` and force a password login. */
  def load(): Session =
    val j = readJson()
    val hs = WJson.strField(j, "homeserver", "")
    val user = WJson.strField(j, "username", "")
    Session(hs, user, loadToken(hs, user), WJson.strField(j, "user_id", ""),
      WJson.strField(j, "device_id", ""))

  def loadPrefs(): FbPrefs = prefsFrom(readJson())

  // ---- the arrival-notification mode ---------------------------------------

  /** the walkie-talkie toggle, in the config file with the other preferences
   *  (wata-mac's reasoning verbatim: `FbPrefs` is the SHARED settings
   *  applet's positional record, so no third field). The cell is the reader
   *  for every write path; `Main` primes it before anything else runs. */
  private val modeC: sgo.Atomic[String] = sgo.atomic(Notify.MODE_QUIET)

  def loadNotifyMode(): NotifyMode =
    val m = Notify.parseMode(WJson.strField(readJson(), "notify_mode", Notify.MODE_QUIET))
    modeC.set(Notify.spellMode(m))
    m

  def notifyMode(): NotifyMode = Notify.parseMode(modeC.get())

  def saveNotifyMode(m: NotifyMode): Unit =
    modeC.set(Notify.spellMode(m))
    writeStore(load(), loadPrefs())

  def prefsFrom(j: Json): FbPrefs =
    FbPrefs(WJson.longField(j, "brightness", 40L).toInt,
      WJson.longField(j, "screen_timeout_idx", 1L).toInt)

  /** the post-login write: the file gets the identity, the secrets file the
   *  token. Called once per session, by the pump, when the credentials the
   *  sync loop got back are real. */
  def saveLogin(homeserver: String, username: String, creds: AuthCreds): Unit =
    val user = sessionUser(username, creds.userId)
    saveToken(homeserver, user, creds.accessToken)
    writeStore(Session(homeserver, user, "", creds.userId, ""), loadPrefs())

  def sessionUser(username: String, userId: String): String =
    var out = username
    if out == "" then out = Names.localpart(userId)
    out

  def savePrefs(p: FbPrefs): Unit = writeStore(load(), p)

  /** the one writer. The session's token field is IGNORED here — writing it
   *  would put the secret back in the file this store keeps it out of. */
  def writeStore(s: Session, p: FbPrefs): Unit =
    val text = Json.write(toJson(s, p))
    val pth = path()
    if pth != "" then
      mkdirAll(parentDir(pth))
      writeFile(pth, text)

  def toJson(s: Session, p: FbPrefs): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("notify_mode", JStr(modeC.get())) :: fs
    fs = ("screen_timeout_idx", JInt(p.timeoutIdx.toLong)) :: fs
    fs = ("brightness", JInt(p.brightness.toLong)) :: fs
    fs = ("user_id", JStr(s.userId)) :: fs
    fs = ("username", JStr(s.username)) :: fs
    fs = ("homeserver", JStr(s.homeserver)) :: fs
    JObj(fs)

  /** open 0600, truncate, write; errors dropped (best-effort persistence). */
  def writeFile(p: String, text: String): Unit =
    try
      val fd = go.syscall.open(p,
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      go.syscall.write(fd, go.bytes(text))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()

  /** walk the path and mkdir each component, dropping the errors existing
   *  ones raise (the sandbox's `Application Support` may not exist yet). */
  def mkdirAll(dir: String): Unit =
    var i = 1
    while i < dir.length do
      if dir.substring(i, i + 1) == "/" then go.syscall.mkdir(dir.substring(0, i), 493)
      i = i + 1
    go.syscall.mkdir(dir, 493)

  /** everything before the last `/`, or "" when there is no directory part.
   *  Hand-scanned: the subset has no `lastIndexOf`. */
  def parentDir(p: String): String =
    var cut = -1
    var i = 0
    while i < p.length do
      if p.substring(i, i + 1) == "/" then cut = i
      i = i + 1
    var out = ""
    if cut > 0 then out = p.substring(0, cut)
    out

  // ---- the outbox store ----------------------------------------------------

  def outbox(): FbOutbox = outboxAt(outboxDir())

  def outboxAt(d: String): FbOutbox =
    if d == "" then FbOutbox("", false)
    else
      mkdirAll(d)
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

  def slotPath(dir: String, slot: scala.Int): String = dir + "/e" + slot + ".msg"

  def readSlot(dir: String, slot: scala.Int): String =
    var out = ""
    try out = go.string(go.sys.readFile(slotPath(dir, slot)))
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

  def clearSlot(dir: String, slot: scala.Int): Unit =
    try go.syscall.unlink(slotPath(dir, slot))
    catch case e: sgo.GoError => ()

  // ---- resolving one run's identity ----------------------------------------

  /** what this run logs in as: an explicit argument or environment variable
   *  wins, then the stored identity. The password is looked up ONLY for the
   *  user actually being used. The default homeserver comes LAST, after the
   *  stored one (wata-mac's ordering note: applied earlier it silently wins
   *  over what the last run recorded). */
  def resolve(hsIn: String, userIn: String, passIn: String): ClientConfig =
    val stored = load()
    var hs = hsIn
    if hs == "" then hs = stored.homeserver
    if hs == "" then hs = DEFAULT_HS
    var user = userIn
    if user == "" then user = stored.username
    var pass = passIn
    if pass == "" then pass = loadPassword(hs, user)
    var session = stored
    if stored.homeserver != hs || stored.username != user then session = empty()
    ClientConfig(hs, user, pass, 1000, session)

  /** the identity failed against the server: drop both its secrets so the
   *  next launch asks rather than retrying what was just refused. */
  def forgetAndReload(c: ClientConfig): ClientConfig =
    forget(c.homeserver, c.username)
    ClientConfig(c.homeserver, c.username, "", c.syncTimeoutMs, empty())
