import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** The device-command mailbox (plan 0020): a small dialect surface for
 *  commanding a user's handset from the admin side — wifi provisioning today,
 *  the same seam any future remote-admin op lands on.
 *
 *  {{{
 *  POST /_wata/v1/cmd/{userId}          {"op": …, …}              queue a command (ADMIN)
 *  GET  /_wata/v1/cmd/poll?wait=<s>                               the device: long-poll its queue
 *  POST /_wata/v1/cmd/report            {"op": …, "result": …}    the device reports back
 *  GET  /_wata/v1/cmd/{userId}/report?op=…                        read the latest report (ADMIN)
 *  }}}
 *
 *  **In-memory only, never journaled.** Commands are transient by nature, and
 *  `wifi_join` carries a PSK — the journal is append-only with no compaction,
 *  and a secret that outlives its use in a file is a defect. Nothing here
 *  calls `Journal`; a restart drops queues and reports, and the tui retries.
 *
 *  **Auth.** Queueing and report-reading are behind the ADMIN flag
 *  (`Admin.requireAdmin` — commanding a device, especially pushing wifi
 *  credentials, is an admin act). The device side authenticates as its own
 *  account, by either of two credentials:
 *
 *   - the trusted `X-Wata-Node-Id` header (go-pkgs/irohnet/nodeid.go): its
 *     presence proves the iroh accept gate verified the peer, and the
 *     BINDING (`Bindings.userFor`) turns that transport proof into the
 *     account whose queue is being polled — transport proof alone is not
 *     enough here, because the mailbox is addressed per account. An
 *     admitted-but-unbound node is 403. Every TCP edge strips inbound
 *     copies of the header, so a TCP request cannot reach this path at all,
 *     forged or otherwise — the header route is refused off-iroh by
 *     construction.
 *   - an ordinary bearer token. The device's poller runs inside a logged-in
 *     client session (device-login already exchanged the node id for a
 *     token), and the tui can play the device in a harness — both arrive
 *     with a token, over either transport. Plain LAN HTTP with a valid
 *     token is inside the trust boundary, as everywhere else on this server.
 *
 *  **Delivery is take-once.** A poll hands over every command queued for the
 *  account and clears the queue; there is no ack. A device that dies
 *  mid-command loses it, and the admin's report poll times out — the honest
 *  outcome for a transient channel (the tui re-queues).
 *
 *  **Reports are latest-wins per (user, op)**, each stamped with a monotonic
 *  `seq`. The seq is what makes the admin's wait skew-free: read the current
 *  seq (0 when none), queue the command, then poll until the seq moves — no
 *  clock comparison between two machines.
 *
 *  The long-poll reuses the store's waiter discipline (close-signalled
 *  channel, register-then-recheck; store.scala) under this module's OWN
 *  mutex and waiter list — a mailbox wake must not wake `/sync` pollers or
 *  vice versa.
 */
case class PendingCmd(userId: String, body: Json)
case class CmdReport(userId: String, op: String, result: Json, seq: scala.Long)

class CmdState:
  var pending: List[PendingCmd] = Nil
  var reports: List[CmdReport] = Nil
  var waiters: List[Waiter] = Nil
  var waiterSeq: scala.Long = 0L
  var reportSeq: scala.Long = 0L

object DeviceCmd:
  private val cell: Mutex[CmdState] = mutex(new CmdState())

  /** the long-poll ceiling, seconds — a poller re-arms rather than parking a
   *  handler goroutine for arbitrary client-chosen times. */
  def MAX_WAIT_S: scala.Int = 60

  // ---- routing ---------------------------------------------------------------

  /** `GET /_wata/v1/cmd/poll` */
  def isPollPath(path: String): Boolean = path == "/_wata/v1/cmd/poll"
  /** `POST /_wata/v1/cmd/report` */
  def isReportPath(path: String): Boolean = path == "/_wata/v1/cmd/report"
  /** `POST /_wata/v1/cmd/{userId}` — dispatched AFTER the two literals. */
  def isQueuePath(path: String, n: scala.Int): Boolean =
    n == 4 && Router.seg(path, 0) == "_wata" && Router.seg(path, 1) == "v1" && Router.seg(path, 2) == "cmd"
  /** `GET /_wata/v1/cmd/{userId}/report` */
  def isReadReportPath(path: String, n: scala.Int): Boolean =
    n == 5 && Router.seg(path, 0) == "_wata" && Router.seg(path, 1) == "v1" &&
      Router.seg(path, 2) == "cmd" && Router.seg(path, 4) == "report"

  // ---- the device's credential ------------------------------------------------

  /** the device side's identity: the account whose queue/report this is.
   *  Header first (iroh-proven node id -> binding), else bearer token. */
  def deviceUser(r: go.net.http.Request): Either[MErr, String] =
    val nid = r.header.get(DeviceLogin.NODE_HEADER)
    if nid != "" then boundUser(nid)
    else tokenUser(r)

  /** an iroh-proven node id names an account only through its binding; an
   *  admitted-but-unbound node has no queue to poll. */
  def boundUser(nid: String): Either[MErr, String] = Bindings.userFor(nid) match
    case s: Some[String] => Right(Store.userIdOf(s.value))
    case None => Left(MErr(403, M_FORBIDDEN(), "No account is bound to this device"))

  def tokenUser(r: go.net.http.Request): Either[MErr, String] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] => Right(rr.right.userId)

  // ---- queue (ADMIN) ----------------------------------------------------------

  /** `POST /_wata/v1/cmd/{userId}` — queue the request body, whole, for that
   *  user's device. The body must be a JSON object with a non-empty `op`;
   *  everything else in it rides along untouched (the op's arguments). */
  def queue(r: go.net.http.Request, body: String): Either[MErr, Json] =
    Admin.requireAdmin(r) match
      case l: Left[MErr, Auth]  => Left(l.left)
      case _: Right[MErr, Auth] => queueFor(r.pathValue("userId"), body)

  def queueFor(target: String, body: String): Either[MErr, Json] =
    val lp = Router.cleanLocalpart(target)
    Store.userByLocalpart(lp) match
      case None => Left(MErr(404, M_NOT_FOUND(), "Unknown user"))
      case _: Some[UserCfg] => queueBody(Store.userIdOf(lp), body)

  def queueBody(userId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => queueParsed(userId, j)

  def queueParsed(userId: String, j: Json): Either[MErr, Json] =
    if !isObj(j) then Left(MErr(400, M_BAD_JSON(), "Command body is not a JSON object"))
    else if strField(j, "op", "") == "" then Left(MErr(400, M_BAD_JSON(), "Command has no op"))
    else queueOk(userId, j)

  def queueOk(userId: String, j: Json): Either[MErr, Json] =
    cell.withLock { st =>
      st.pending = PendingCmd(userId, j) :: st.pending   // newest-first; takePending re-orders
      val old = st.waiters
      st.waiters = Store.dropUser(old, userId, Nil)
      Store.closeUser(old, userId)
    }
    Right(obj1("queued", JBool(true)))

  // ---- poll (the device) ------------------------------------------------------

  /** `GET /_wata/v1/cmd/poll?wait=<s>` — take every pending command for the
   *  calling account; with `wait` and an empty queue, park until a queue
   *  lands or the timer fires (register-then-recheck, the store's no-lost-
   *  wake order), then take whatever is there. */
  def poll(r: go.net.http.Request): Either[MErr, Json] = deviceUser(r) match
    case l: Left[MErr, String]   => Left(l.left)
    case rr: Right[MErr, String] => Right(pollUser(rr.right, waitSecs(r)))

  def waitSecs(r: go.net.http.Request): scala.Int =
    val s = Sync.parseTimeout(r.uRL.query().get("wait"))
    if s < 0 then 0 else if s > MAX_WAIT_S then MAX_WAIT_S else s

  def pollUser(userId: String, waitS: scala.Int): Json =
    val got = takePending(userId)
    if lenCmds(got) > 0 || waitS == 0 then cmdsJson(got)
    else pollWait(userId, waitS)

  def pollWait(userId: String, waitS: scala.Int): Json =
    val w = registerWaiter(userId)
    val again = takePending(userId)
    if lenCmds(again) > 0 then
      removeWaiter(w.id)
      cmdsJson(again)
    else
      Store.waitForEvents(w, waitS * 1000)
      removeWaiter(w.id)
      cmdsJson(takePending(userId))

  /** pop this user's whole queue, oldest first (the stored list is
   *  newest-first; the prepend-walk un-reverses it). */
  def takePending(userId: String): List[PendingCmd] =
    cell.withLock { st =>
      val mine = ofUser(st.pending, userId, Nil)
      st.pending = notOfUser(st.pending, userId, Nil)
      mine
    }

  def ofUser(xs: List[PendingCmd], userId: String, acc: List[PendingCmd]): List[PendingCmd] = xs match
    case h :: t => ofUserStep(h, t, userId, acc)
    case Nil  => acc

  def ofUserStep(h: PendingCmd, t: List[PendingCmd], userId: String, acc: List[PendingCmd]): List[PendingCmd] =
    var acc2: List[PendingCmd] = acc
    if h.userId == userId then acc2 = h :: acc2
    ofUser(t, userId, acc2)

  def notOfUser(xs: List[PendingCmd], userId: String, acc: List[PendingCmd]): List[PendingCmd] = xs match
    case h :: t => notOfUserStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def notOfUserStep(h: PendingCmd, t: List[PendingCmd], userId: String, acc: List[PendingCmd]): List[PendingCmd] =
    var acc2: List[PendingCmd] = acc
    if h.userId == userId then acc2 = acc else acc2 = h :: acc2
    notOfUser(t, userId, acc2)

  def lenCmds(xs: List[PendingCmd]): scala.Int =
    var n = 0
    var cur = xs
    var going = true
    while going do
      cur match
        case _ :: t =>
          n = n + 1
          cur = t
        case Nil => going = false
    n

  def cmdsJson(xs: List[PendingCmd]): Json = obj1("cmds", JArr(cmdBodies(xs, Nil)))

  def cmdBodies(xs: List[PendingCmd], acc: List[Json]): List[Json] = xs match
    case h :: t => cmdBodies(t, h.body :: acc)
    case Nil  => ListOps.reverse(acc)

  // the mailbox's own waiters, the store's register/remove shapes verbatim
  // (a Chan cannot leave the withLock block; the handle is rebuilt outside).

  def registerWaiter(userId: String): Waiter =
    val ch = sgo.makeChan[Boolean]()
    val id = cell.withLock { st =>
      st.waiterSeq = st.waiterSeq + 1L
      st.waiters = Waiter(st.waiterSeq, userId, ch) :: st.waiters
      st.waiterSeq
    }
    Waiter(id, userId, ch)

  def removeWaiter(id: scala.Long): Unit =
    cell.withLock(st => st.waiters = Store.dropId(st.waiters, id, Nil))

  // ---- report (the device) ----------------------------------------------------

  /** `POST /_wata/v1/cmd/report` — store the calling account's `{op, result}`
   *  as the latest report for that op, stamped with a fresh seq. */
  def report(r: go.net.http.Request, body: String): Either[MErr, Json] = deviceUser(r) match
    case l: Left[MErr, String]   => Left(l.left)
    case rr: Right[MErr, String] => reportBody(rr.right, body)

  def reportBody(userId: String, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => reportParsed(userId, j)

  def reportParsed(userId: String, j: Json): Either[MErr, Json] =
    val op = strField(j, "op", "")
    if op == "" then Left(MErr(400, M_BAD_JSON(), "Report has no op"))
    else Right(obj1("seq", JInt(putReport(userId, op, objField(j, "result")))))

  def objField(j: Json, key: String): Json = getField(j, key) match
    case s: Some[Json] => s.value
    case None => emptyObj

  def putReport(userId: String, op: String, result: Json): scala.Long =
    cell.withLock { st =>
      st.reportSeq = st.reportSeq + 1L
      st.reports = CmdReport(userId, op, result, st.reportSeq) :: dropReport(st.reports, userId, op, Nil)
      st.reportSeq
    }

  def dropReport(xs: List[CmdReport], userId: String, op: String, acc: List[CmdReport]): List[CmdReport] = xs match
    case h :: t => dropReportStep(h, t, userId, op, acc)
    case Nil  => ListOps.reverse(acc)

  def dropReportStep(h: CmdReport, t: List[CmdReport], userId: String, op: String, acc: List[CmdReport]): List[CmdReport] =
    var acc2: List[CmdReport] = acc
    if h.userId == userId && h.op == op then acc2 = acc else acc2 = h :: acc2
    dropReport(t, userId, op, acc2)

  // ---- read report (ADMIN) ----------------------------------------------------

  /** `GET /_wata/v1/cmd/{userId}/report?op=…` — the latest report, or 404
   *  while none has arrived (the admin's poll target). */
  def readReport(r: go.net.http.Request): Either[MErr, Json] =
    Admin.requireAdmin(r) match
      case l: Left[MErr, Auth]  => Left(l.left)
      case _: Right[MErr, Auth] =>
        readFor(Store.userIdOf(Router.cleanLocalpart(r.pathValue("userId"))), r.uRL.query().get("op"))

  def readFor(userId: String, op: String): Either[MErr, Json] =
    if op == "" then Left(MErr(400, M_BAD_JSON(), "Missing op"))
    else findReport(cell.withLock(st => st.reports), userId, op)

  def findReport(xs: List[CmdReport], userId: String, op: String): Either[MErr, Json] = xs match
    case h :: t => findReportStep(h, t, userId, op)
    case Nil  => Left(MErr(404, M_NOT_FOUND(), "No report"))

  def findReportStep(h: CmdReport, t: List[CmdReport], userId: String, op: String): Either[MErr, Json] =
    if h.userId == userId && h.op == op then Right(reportJson(h))
    else findReport(t, userId, op)

  def reportJson(rep: CmdReport): Json =
    obj3("op", JStr(rep.op), "seq", JInt(rep.seq), "result", rep.result)
