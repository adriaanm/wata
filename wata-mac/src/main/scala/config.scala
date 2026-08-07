import language.experimental.saferExceptions

/** The mac client's persistent state — the three stores plan 0036 splits it
 *  into, by what each thing actually is.
 *
 *  THE KEYCHAIN holds the two secrets. The access token is written after
 *  every successful login and read first on every launch; the password is
 *  written once and read only after a token has been rejected. Both are
 *  generic-password items under service `wata`, account `<user>@<homeserver>`
 *  — so one user against a staging and a live server keeps two credentials,
 *  and two users on one Mac never collide.
 *
 *  Storing the PASSWORD, not just the token, is deliberate and the window
 *  forces it: wata-mac's screens are wata-fb's bodies, a keyboard-driven
 *  contact list with no text entry anywhere in it. There is nowhere to type a
 *  password, so a token that expires or is invalidated server-side would
 *  strand the app on its boot screen with no user-reachable fix. The recovery
 *  path has to be unattended. `WATA_MAC_NO_SAVE_PASSWORD` opts out for anyone
 *  who would rather re-run with `WATA_MAC_PASS` after an expiry.
 *
 *  THE CONFIG FILE holds the non-secret half — homeserver, username, user id
 *  — under `~/Library/Application Support/wata/`, 0600, and the preferences
 *  beside them: the settings applet's two, and the walkie-talkie toggle
 *  (`notify_mode`). No token is ever written here; that split is the whole
 *  point.
 *
 *  THE OUTBOX is numbered slot files in a directory beside the config file,
 *  the same shape wata-fb uses. Before this the mac ran on `MemOutbox`, so a
 *  recording made while the server was unreachable died with the window —
 *  and outbox.scala opens by saying a recording is the one thing this client
 *  produces that cannot be recreated.
 *
 *  This file is NOT a symlink to wata-fb's config.scala, unlike the screens:
 *  the device has no keychain, no `$HOME` and no user accounts, and this one
 *  has no `/etc/wata` and no rootfs that might be read-only. The object names
 *  match because the shared sources reach for them (`FbConfig.savePrefs`). */
object FbConfig:

  /** the env vars that override the two paths, and the keychain service. */
  val ENV_PATH = "WATA_MAC_CONFIG"
  val ENV_OUTBOX = "WATA_MAC_OUTBOX"
  val ENV_NO_SAVE_PASSWORD = "WATA_MAC_NO_SAVE_PASSWORD"
  /** set by a harness: never read or write the login keychain at all. A
   *  smoke that logs in with credentials it already has in its environment
   *  has no business leaving items in the developer's keychain — one per
   *  run, keyed by a random scratch port, that nothing ever cleans up. */
  val ENV_NO_KEYCHAIN = "WATA_MAC_NO_KEYCHAIN"
  val SERVICE = "wata"
  /** where a first run with nothing configured points. */
  val DEFAULT_HS = "http://127.0.0.1:8008"

  /** 0600 and 0755 as the decimal literals the `perm` argument has to be —
   *  see syscall.scala on why these are never computed. */
  val FILE_PERM = 384
  val DIR_PERM = 493

  // ---- where ---------------------------------------------------------------

  /** `~/Library/Application Support/wata` — the macOS location for an app's
   *  own state. A run with no `$HOME` (a stripped harness environment) gets
   *  "", which every reader below turns into "nothing stored" rather than a
   *  path relative to the working directory. */
  def stateDir(): String =
    val home = go.sys.getenv("HOME")
    var out = ""
    if home != "" then out = home + "/Library/Application Support/wata"
    out

  def path(): String =
    var p = go.sys.getenv(ENV_PATH)
    if p == "" then
      val d = stateDir()
      if d != "" then p = d + "/config.json"
    p

  def outboxDir(): String =
    var d = go.sys.getenv(ENV_OUTBOX)
    if d == "" then
      val p = path()
      if p != "" then d = parentDir(p) + "/outbox"
    d

  /** the keychain account: the identity a credential belongs to. Both halves
   *  matter — the same username against two homeservers is two accounts. */
  def account(homeserver: String, username: String): String =
    username + "@" + homeserver

  // ---- the secrets ---------------------------------------------------------

  def tokenAccount(hs: String, user: String): String = account(hs, user)
  def passAccount(hs: String, user: String): String = "password:" + account(hs, user)

  def keychainOff(): Boolean = go.sys.getenv(ENV_NO_KEYCHAIN) != ""

  /** the login sheet's "stay signed in" checkbox. Cleared, nothing is
   *  written — not the password and not the token — so the next launch asks
   *  again, which is what unchecking it means. Default true: the stores are
   *  the point, and a first run from the environment has no sheet to say
   *  otherwise. */
  private val rememberC: sgo.Atomic[Boolean] = sgo.atomic(true)
  def setRemember(on: Boolean): Unit = rememberC.set(on)
  def remembering(): Boolean = rememberC.get()

  def loadToken(hs: String, user: String): String =
    if user == "" || hs == "" || keychainOff() then ""
    else go.mackeychain.lookup(SERVICE, tokenAccount(hs, user))

  def loadPassword(hs: String, user: String): String =
    if user == "" || hs == "" || keychainOff() then ""
    else go.mackeychain.lookup(SERVICE, passAccount(hs, user))

  /** keep the token the sync loop is actually using. Reported, not silent: a
   *  keychain that refuses the write means every later launch asks for a
   *  password again, and the user should hear why once. */
  def saveToken(hs: String, user: String, token: String): Unit =
    if user != "" && hs != "" && token != "" && !keychainOff() && remembering() then
      val err = go.mackeychain.store(SERVICE, tokenAccount(hs, user), token)
      if err != "" then println("wata-mac: could not store the token: " + err)

  def savePassword(hs: String, user: String, pass: String): Unit =
    if user != "" && hs != "" && pass != "" && !keychainOff() && remembering() &&
      go.sys.getenv(ENV_NO_SAVE_PASSWORD) == "" then
      val err = go.mackeychain.store(SERVICE, passAccount(hs, user), pass)
      if err != "" then println("wata-mac: could not store the password: " + err)

  /** forget both secrets for one identity — what a `--forget` run does. */
  def forget(hs: String, user: String): Unit =
    drop(go.mackeychain.forget(SERVICE, tokenAccount(hs, user)))
    drop(go.mackeychain.forget(SERVICE, passAccount(hs, user)))

  def drop(s: String): Unit = ()

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

  /** the stored session WITH its token filled in from the keychain — the file
   *  never holds one, so a session read straight off disk would always fail
   *  `Sessions.isValid` and force a password login. */
  def load(): Session =
    val j = readJson()
    val hs = WJson.strField(j, "homeserver", "")
    val user = WJson.strField(j, "username", "")
    Session(hs, user, loadToken(hs, user), WJson.strField(j, "user_id", ""),
      WJson.strField(j, "device_id", ""))

  def loadPrefs(): FbPrefs = prefsFrom(readJson())

  // ---- the arrival-notification mode ---------------------------------------

  /** The walkie-talkie toggle (plan 0037 slice 4) lives here with the other
   *  preferences rather than in `FbPrefs`, whose two fields are the handset's
   *  brightness and screen timeout — the SHARED settings applet constructs
   *  that record positionally, so a third field would have to appear on the
   *  device too, for a control the device does not have yet.
   *
   *  The cell is the reader for every write path: `writeStore` is called by
   *  `saveLogin` and `savePrefs`, neither of which knows about the mode, and
   *  a writer that read the file each time would race with itself. `Main`
   *  primes it from the file before anything else runs. */
  private val modeC: sgo.Atomic[String] = sgo.atomic(Notify.MODE_QUIET)

  def loadNotifyMode(): NotifyMode =
    val m = Notify.parseMode(WJson.strField(readJson(), "notify_mode", Notify.MODE_QUIET))
    modeC.set(Notify.spellMode(m))
    m

  def notifyMode(): NotifyMode = Notify.parseMode(modeC.get())

  /** the user changed it in Settings: remember it for the next launch. */
  def saveNotifyMode(m: NotifyMode): Unit =
    modeC.set(Notify.spellMode(m))
    writeStore(load(), loadPrefs())

  def prefsFrom(j: Json): FbPrefs =
    FbPrefs(WJson.longField(j, "brightness", 40L).toInt,
      WJson.longField(j, "screen_timeout_idx", 1L).toInt)

  /** the post-login write: the file gets the identity, the keychain gets the
   *  token. Called once per session, by the pump, when the credentials the
   *  sync loop got back are real. */
  def saveLogin(homeserver: String, username: String, creds: AuthCreds): Unit =
    val user = sessionUser(username, creds.userId)
    saveToken(homeserver, user, creds.accessToken)
    if remembering() then
      writeStore(Session(homeserver, user, "", creds.userId, ""), loadPrefs())

  def sessionUser(username: String, userId: String): String =
    var out = username
    if out == "" then out = Names.localpart(userId)
    out

  def savePrefs(p: FbPrefs): Unit = writeStore(load(), p)

  /** the one writer. The session's token field is IGNORED here — writing it
   *  would put the secret back in the file this store exists to keep it out
   *  of — so the JSON carries no `access_token` key at all. */
  def writeStore(s: Session, p: FbPrefs): Unit = writeText(Json.write(toJson(s, p)))

  def toJson(s: Session, p: FbPrefs): Json =
    var fs: List[(String, Json)] = Nil
    fs = ("notify_mode", JStr(modeC.get())) :: fs
    fs = ("screen_timeout_idx", JInt(p.timeoutIdx.toLong)) :: fs
    fs = ("brightness", JInt(p.brightness.toLong)) :: fs
    fs = ("user_id", JStr(s.userId)) :: fs
    fs = ("username", JStr(s.username)) :: fs
    fs = ("homeserver", JStr(s.homeserver)) :: fs
    JObj(fs)

  /** open 0600 (creating the parent dirs), truncate, write. */
  def writeText(text: String): Unit =
    val p = path()
    if p != "" then
      mkdirAll(parentDir(p))
      try
        val fd = go.syscall.open(p,
          go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
        go.syscall.write(fd, go.bytes(text))
        go.syscall.close(fd)
      catch case e: sgo.GoError => ()

  /** `~/Library/Application Support` may exist while `wata` under it does
   *  not, and on a fresh account neither does — so this walks the path and
   *  mkdirs each component, dropping the errors an existing one raises. */
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
   *  user actually being used, so a stored password never leaks into a run
   *  aimed at a different account. */
  def resolve(hsIn: String, userIn: String, passIn: String): ClientConfig =
    val stored = load()
    var hs = hsIn
    // The default homeserver comes LAST, after the stored one. Applied
    // earlier (as an argument default) it silently wins over what the last
    // run recorded, and every keychain lookup then misses — the account is
    // keyed by homeserver, so a wrong one is indistinguishable from having
    // stored nothing at all.
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
   *  next pass asks rather than retrying what was just refused, and hand back
   *  a config that still remembers WHERE and WHO, which is what prefills the
   *  sheet. */
  def forgetAndReload(c: ClientConfig): ClientConfig =
    forget(c.homeserver, c.username)
    ClientConfig(c.homeserver, c.username, "", c.syncTimeoutMs, empty())
