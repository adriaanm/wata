import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** Device enrolment (plan 0021 milestone B; the server half of plan 0014).
 *
 *  A handset that has minted its own iroh node key cannot talk to the server
 *  yet — the listener's allowlist decides who is admitted, and an unknown node
 *  is closed at accept. Enrolment is how a node id crosses that line WITHOUT
 *  anyone editing a file over ssh:
 *
 *  {{{
 *  POST /_wata/v1/enroll                       {nodeId, nonce}   (no auth)
 *  GET  /_wata/v1/admin/enroll                                   (admin)
 *  POST /_wata/v1/admin/enroll/{nodeId}/approve                  (admin)
 *  POST /_wata/v1/admin/enroll/{nodeId}/deny                     (admin)
 *  }}}
 *
 *  **The announce is deliberately unauthenticated and deliberately inert.** A
 *  device that cannot reach the server over iroh has no token to present, so
 *  requiring one would be circular; what keeps that safe is that the announce
 *  writes NOTHING that grants access. It parks a (nodeId, nonce) pair in
 *  memory for a parent to look at. Only `approve` — behind the admin gate —
 *  turns a pending row into an allowlist entry.
 *
 *  The pending list is bounded on both axes, so the open endpoint cannot be
 *  turned into a memory sink: entries expire (`WATA_ENROLL_EXPIRY_MS`, default
 *  10 minutes) and the list is capped (`WATA_ENROLL_MAX`, default 8) with the
 *  OLDEST evicted, and a re-announce of the same node id refreshes its entry
 *  rather than adding one. A flood therefore rotates within the cap instead of
 *  growing. Nothing is journalled: a restart drops the pending set, which is
 *  the right outcome for a 10-minute approval window.
 *
 *  **Approval is durable first, live second.** `approve` appends the node id
 *  to the iroh config's `allowlist` (an atomic temp+rename over the file, the
 *  same discipline `Config` uses for `users.json`) and only then applies it to
 *  the running listener through `irohnet.Allow` (go-pkgs/irohnet, plan 0021's
 *  one new FFI). If the file write cannot be made to stick the approval fails
 *  with the row intact; if only the LIVE apply fails — a server with no iroh
 *  listener, e.g. a plain-TCP deployment — the approval still stands and the
 *  response says `"live": false`, since the file is what the next boot reads.
 *
 *  **"Already enrolled" is an answer, not an absence.** An announce of an id
 *  the allowlist already holds is 200 with `"allowlisted": true` and no pending
 *  row, and the admin listing carries the allowlisted ids beside the pending
 *  ones. A page that finds no row can therefore say which of the two things
 *  happened — this handset is done, or the announce expired — instead of
 *  reporting an expiry over a successful approval.
 *
 *  The QR contract (the device's side, plan 0014): the handset shows
 *  `http://<server>/admin#enroll/<nodeId>/<nonce>`, so a stock camera app
 *  lands the parent on the page with that row highlighted. The nonce is a
 *  human-visible correlator — "this is the handset in my hand", not a secret
 *  and not a credential; the approval decision is the parent's.
 */
case class Pending(nodeId: String, nonce: String, atMs: scala.Long)

class EnrollState:
  /** OLDEST FIRST: eviction drops the head, a new announce appends. */
  var pending: List[Pending] = Nil

object Enroll:
  private val cell: Mutex[EnrollState] = mutex(new EnrollState())

  /** how long an announce stays approvable, ms (`WATA_ENROLL_EXPIRY_MS`). */
  def expiryMs(): scala.Long = envInt("WATA_ENROLL_EXPIRY_MS", 600000).toLong

  /** how many announces are held at once (`WATA_ENROLL_MAX`); the oldest is
   *  evicted past it. */
  def maxPending(): scala.Int = envInt("WATA_ENROLL_MAX", 8)

  def envInt(name: String, dflt: scala.Int): scala.Int =
    val s = go.sys.getenv(name)
    if s == "" then dflt else parseIntOr(s, dflt)

  def parseIntOr(s: String, dflt: scala.Int): scala.Int =
    var n = dflt
    try
      val v = go.strconv.atoi(s)
      n = v.toInt
      ()
    catch case e: sgo.GoError => ()
    n

  /** the file the durable allowlist lives in: the iroh config
   *  (`WATA_IROH_CONFIG`) — the very file the listener is built from — unless
   *  `WATA_ENROLL_ALLOWLIST` names another. The override exists so the admin
   *  surface can be exercised (and a deployment can keep the allowlist beside
   *  a config it does not want rewritten) without the process also having to
   *  serve over iroh. */
  def allowlistPath(): String =
    val over = go.sys.getenv("WATA_ENROLL_ALLOWLIST")
    if over != "" then over else go.sys.getenv("WATA_IROH_CONFIG")

  // ---- the announce ------------------------------------------------------------

  /** `POST /_wata/v1/enroll` — unauthenticated. */
  def announce(body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => announce2(j)

  def announce2(j: Json): Either[MErr, Json] =
    val nodeId = strField(j, "nodeId", "")
    val nonce = strField(j, "nonce", "")
    if !validNodeId(nodeId) then Left(MErr(400, M_BAD_JSON(), "Invalid node id"))
    else if !validNonce(nonce) then Left(MErr(400, M_BAD_JSON(), "Invalid nonce"))
    else if allowlisted(nodeId) then enrolled(nodeId)
    else recorded(nodeId, nonce)

  def recorded(nodeId: String, nonce: String): Either[MErr, Json] =
    val now = Store.nowMs()
    cell.withLock(st => st.pending = capped(append(fresh(st.pending, now), Pending(nodeId, nonce, now), nodeId)))
    Right(obj3("node_id", JStr(nodeId), "pending", JBool(true), "allowlisted", JBool(false)))

  /** the announce of an id the allowlist ALREADY holds: 200, no row, and
   *  `allowlisted` says why there is none. Without that marker the caller sees
   *  the same empty pending list an EXPIRED announce leaves and has to guess —
   *  which is how the admin page came to report "the announce may have expired"
   *  over a device that had just been approved successfully. Nothing is
   *  recorded: a pending row is a request for a decision, and this decision is
   *  made. */
  def enrolled(nodeId: String): Either[MErr, Json] =
    Right(obj3("node_id", JStr(nodeId), "pending", JBool(false), "allowlisted", JBool(true)))

  /** is this id in the DURABLE allowlist — enrolled, with nothing left to
   *  approve? The file is the source of truth and `approve` writes it before it
   *  answers, so a page that re-checks straight after an approval sees it here.
   *  A `"*"` entry admits every peer, so it answers for any id. */
  def allowlisted(nodeId: String): Boolean =
    val p = allowlistPath()
    if p == "" then false else allowedIn(fileIds(p), nodeId)

  def allowedIn(xs: List[Json], nodeId: String): Boolean =
    hasStr(xs, nodeId) || hasStr(xs, "*")

  /** the allowlist file's entries, or Nil when there is no readable file. */
  def fileIds(p: String): List[Json] = Json.tryParse(Config.readAll(p)) match
    case Left(_)  => Nil
    case Right(j) => allowIds(j)

  /** the entries still inside the expiry window. */
  def fresh(xs: List[Pending], now: scala.Long): List[Pending] = xs match
    case h :: t => freshStep(h, t, now)
    case Nil  => Nil

  def freshStep(h: Pending, t: List[Pending], now: scala.Long): List[Pending] =
    var out: List[Pending] = fresh(t, now)
    if now - h.atMs < expiryMs() then out = h :: out else ()
    out

  /** append, dropping any earlier announce of the SAME node id — a device
   *  retrying its QR screen refreshes its row instead of filling the list. */
  def append(xs: List[Pending], p: Pending, nodeId: String): List[Pending] =
    var r: List[Pending] = ListOps.reverse(without(xs, nodeId, Nil))
    r = p :: r
    ListOps.reverse(r)

  def without(xs: List[Pending], nodeId: String, acc: List[Pending]): List[Pending] = xs match
    case h :: t => withoutStep(h, t, nodeId, acc)
    case Nil  => ListOps.reverse(acc)

  def withoutStep(h: Pending, t: List[Pending], nodeId: String, acc: List[Pending]): List[Pending] =
    var acc2: List[Pending] = acc
    if h.nodeId == nodeId then acc2 = acc else acc2 = h :: acc2
    without(t, nodeId, acc2)

  /** hold at most `maxPending`, dropping from the OLDEST end. */
  def capped(xs: List[Pending]): List[Pending] =
    if count(xs, 0) <= maxPending() then xs else capped(dropHead(xs))

  def dropHead(xs: List[Pending]): List[Pending] = xs match
    case _ :: t => t
    case Nil  => Nil

  def count(xs: List[Pending], n: scala.Int): scala.Int = xs match
    case _ :: t => count(t, n + 1)
    case Nil  => n

  // ---- shapes ------------------------------------------------------------------

  /** an iroh endpoint id in either form the endpoint layer parses: 64 lowercase
   *  hex characters (what `irohnet-keygen` prints and what an allowlist file
   *  holds today) or the 52-character z-base-32 spelling of the same 32 bytes.
   *  Shape is checked HERE because a junk id that reached the allowlist file
   *  would fail the listener's own parse at the next boot — i.e. one bad
   *  announce could stop the server from listening at all. */
  def validNodeId(s: String): Boolean =
    (s.length == 64 && allIn(hexAlpha, s, 0)) || (s.length == 52 && allIn(z32Alpha, s, 0))

  def hexAlpha: String = "0123456789abcdef"

  /** z-base-32 (iroh's `PublicKey` Display alphabet). */
  def z32Alpha: String = "ybndrfg8ejkmcpqxot1uwisza345h769"

  def allIn(alpha: String, s: String, i: scala.Int): Boolean =
    if i >= s.length then true
    else if inAlpha(alpha, s.charAt(i), 0) then allIn(alpha, s, i + 1)
    else false

  def inAlpha(alpha: String, c: Char, i: scala.Int): Boolean =
    if i >= alpha.length then false
    else if alpha.charAt(i) == c then true
    else inAlpha(alpha, c, i + 1)

  /** the correlator the QR carries: short, printable, no path or JSON
   *  surprises. */
  def validNonce(s: String): Boolean =
    s != "" && s.length <= 64 && allNonce(s, 0)

  def allNonce(s: String, i: scala.Int): Boolean =
    if i >= s.length then true
    else if nonceChar(s.charAt(i)) then allNonce(s, i + 1)
    else false

  def nonceChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'

  // ---- the admin surface -------------------------------------------------------

  /** `GET /_wata/v1/admin/enroll` — the pending rows, oldest first, expired
   *  ones pruned as they are read (there is no timer; the list is only ever
   *  observed through here and through `approve`), plus `allowlisted`: the ids
   *  the durable allowlist already holds.
   *
   *  The second field is what makes "there is no row for this handset"
   *  answerable. A page holding a node id (a scanned fragment, or the row it
   *  just approved) finds it here and says "already enrolled" instead of
   *  reporting an expiry; a TYPED code, which carries only a prefix, is matched
   *  against these ids the same way it is matched against pending rows. They
   *  are public keys behind the admin gate, so listing them tells an admin
   *  nothing they could not read out of the config file. */
  def list(): Json =
    val now = Store.nowMs()
    val xs = cell.withLock(st => pruneLocked(st, now))
    obj2("pending", JArr(rows(xs, now, Nil)), "allowlisted", JArr(allowlistIds()))

  def allowlistIds(): List[Json] =
    val p = allowlistPath()
    if p == "" then Nil else fileIds(p)

  def pruneLocked(st: EnrollState, now: scala.Long): List[Pending] =
    st.pending = fresh(st.pending, now)
    st.pending

  def rows(xs: List[Pending], now: scala.Long, acc: List[Json]): List[Json] = xs match
    case h :: t => rows(t, now, row(h, now) :: acc)
    case Nil  => ListOps.reverse(acc)

  def row(p: Pending, now: scala.Long): Json =
    var fs: List[(String, Json)] = startObj
    fs = ("node_id", JStr(p.nodeId)) :: fs
    fs = ("nonce", JStr(p.nonce)) :: fs
    fs = ("age_ms", JInt(now - p.atMs)) :: fs
    fs = ("expires_in_ms", JInt(expiryMs() - (now - p.atMs))) :: fs
    endObj(fs)

  /** `POST /_wata/v1/admin/enroll/{nodeId}/deny` — the row goes away and
   *  nothing else happens. A denied device may announce again (that is what
   *  makes deny the safe default for "I don't recognize this"). */
  def deny(nodeId: String): Either[MErr, Json] =
    if !take(nodeId) then Left(MErr(404, M_NOT_FOUND(), "No such pending enrolment"))
    else Right(emptyObj)

  /** remove a pending row; false when it was not there (expired or unknown). */
  def take(nodeId: String): Boolean =
    val now = Store.nowMs()
    cell.withLock(st => takeLocked(st, nodeId, now))

  def takeLocked(st: EnrollState, nodeId: String, now: scala.Long): Boolean =
    val live = fresh(st.pending, now)
    st.pending = without(live, nodeId, Nil)
    count(live, 0) != count(st.pending, 0)

  /** `POST /_wata/v1/admin/enroll/{nodeId}/approve` — the allowlist write. */
  def approve(nodeId: String): Either[MErr, Json] =
    if !isPending(nodeId) then Left(MErr(404, M_NOT_FOUND(), "No such pending enrolment"))
    else approve2(nodeId)

  def isPending(nodeId: String): Boolean =
    val now = Store.nowMs()
    cell.withLock(st => has(pruneLocked(st, now), nodeId))

  def has(xs: List[Pending], nodeId: String): Boolean = xs match
    case h :: t => hasStep(h, t, nodeId)
    case Nil  => false

  def hasStep(h: Pending, t: List[Pending], nodeId: String): Boolean =
    if h.nodeId == nodeId then true else has(t, nodeId)

  /** durable first: a file write that cannot be confirmed leaves the row
   *  pending and answers 500, because the alternative — a row that vanished
   *  while nothing was granted — is the one outcome nobody can debug. */
  def approve2(nodeId: String): Either[MErr, Json] =
    val note = writeAllow(nodeId)
    if note != "" then Left(MErr(500, M_UNKNOWN(), note))
    else approved(nodeId)

  def approved(nodeId: String): Either[MErr, Json] =
    val live = liveAllow(nodeId)
    take(nodeId)
    var fs: List[(String, Json)] = startObj
    fs = ("node_id", JStr(nodeId)) :: fs
    fs = ("allowlist_file", JStr(allowlistPath())) :: fs
    fs = ("live", JBool(live == "")) :: fs
    fs = ("note", JStr(live)) :: fs
    Right(endObj(fs))

  /** apply to the RUNNING listener. Returns "" on success, else why not — a
   *  process with no iroh listener (plain-TCP mode, or a build without the
   *  transport) reports that here and stays a successful approval, since the
   *  file is what the next boot reads. */
  def liveAllow(nodeId: String): String =
    var msg = ""
    try
      go.irohnet.allow(nodeId)
      ()
    catch case e: sgo.GoError => msg = e.message
    msg

  // ---- the durable allowlist ----------------------------------------------------

  /** add the id to the config's `allowlist` array and rewrite the file
   *  ATOMICALLY (temp in the same dir + rename, mode 0600 — the file also
   *  holds the node's secret key). Returns "" on success, else the reason.
   *  Success is CONFIRMED by re-reading the file: `os.WriteFile`/`os.Rename`
   *  drop their errors in this facade, so the read-back is what makes a
   *  failure visible instead of a silently unapplied approval. */
  def writeAllow(nodeId: String): String =
    val p = allowlistPath()
    if p == "" then "No allowlist file configured (WATA_IROH_CONFIG)"
    else writeAllow2(p, nodeId)

  def writeAllow2(p: String, nodeId: String): String = Json.tryParse(Config.readAll(p)) match
    case Left(_)  => "Allowlist file " + p + " is missing or not JSON"
    case Right(j) => writeAllow3(p, j, nodeId)

  def writeAllow3(p: String, j: Json, nodeId: String): String =
    val updated = jsonSet(j, "allowlist", JArr(withId(allowIds(j), nodeId)))
    writeAtomic(p, Json.write(updated) + "\n")
    if fileHasId(p, nodeId) then "" else "Could not write the allowlist file " + p

  /** the config's current allowlist entries (a missing or non-array field
   *  reads as empty — the listener treats an empty allowlist as "refuse
   *  everyone", so nothing is lost by normalizing it here). */
  def allowIds(j: Json): List[Json] = getField(j, "allowlist") match
    case s: Some[Json] => arrItems(s.value)
    case None => Nil

  def arrItems(j: Json): List[Json] = j match
    case a: JArr => a.items
    case _       => Nil

  /** append unless already there (approving twice is a no-op, not a duplicate
   *  entry). */
  def withId(xs: List[Json], nodeId: String): List[Json] =
    if hasStr(xs, nodeId) then xs else appendStr(xs, nodeId)

  def hasStr(xs: List[Json], s: String): Boolean = xs match
    case h :: t => hasStrStep(h, t, s)
    case Nil  => false

  def hasStrStep(h: Json, t: List[Json], s: String): Boolean =
    if strOr(h, "") == s then true else hasStr(t, s)

  def appendStr(xs: List[Json], s: String): List[Json] =
    var r: List[Json] = ListOps.reverse(xs)
    r = JStr(s) :: r
    ListOps.reverse(r)

  def writeAtomic(p: String, body: String): Unit =
    val tmp = p + ".tmp"
    go.osfile.writeFile(tmp, go.bytes(body), 384)
    go.osfile.rename(tmp, p)

  def fileHasId(p: String, nodeId: String): Boolean = Json.tryParse(Config.readAll(p)) match
    case Left(_)  => false
    case Right(j) => hasStr(allowIds(j), nodeId)
