import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** Device-account bindings + device-login (plan 0027).
 *
 *  Approving an enrolment can BIND an account to the approved node id
 *  (enroll.scala carries the approve-side write); this module owns the map
 *  `nodeId -> user localpart` and the endpoint that turns a proven transport
 *  identity into a session:
 *
 *  {{{
 *  POST /_wata/v1/device-login        {}   (no credentials; iroh only)
 *  }}}
 *
 *  **The credential is the connection.** The iroh handshake proves possession
 *  of the device's secret key, and the admin approved that exact key while
 *  looking at the physical handset. The transport bridge injects the verified
 *  remote node id as the header `X-Wata-Node-Id` — after stripping any
 *  inbound copy — and every TCP listener serves through the matching strip
 *  (go-pkgs/irohnet/nodeid.go), so a request that carries the header CANNOT
 *  have forged it. Where the header is absent (any TCP-path request, however
 *  it was decorated) the answer is 403 unconditionally: peer identity cannot
 *  be proven there, so there is nothing to log in as.
 *
 *  An admitted node with no binding is 404 — enrolled into the transport but
 *  not yet given an account (an approve without a user selection); the
 *  handset keeps retrying on its login cadence and gets in the moment a
 *  binding lands. A bound account that has since been removed is 403.
 *
 *  A success mints a fresh device exactly like a password login and answers
 *  the same shape (`Router.loginOk`), so the client's session handling does
 *  not know which door it came through.
 *
 *  **Bindings are journaled** (`bind` op, persist.scala), replay-safe: the
 *  op carries the concrete pair, a re-bind of the same node id overwrites,
 *  and replay applies them in commit order — so the last binding wins after
 *  a reboot exactly as it did live. With persistence off they are in-memory,
 *  like every access token.
 */
case class Binding(nodeId: String, user: String)

class BindState:
  var items: List[Binding] = Nil

object Bindings:
  private val cell: Mutex[BindState] = mutex(new BindState())

  /** bind `nodeId` to account `user` (localpart), replacing any earlier
   *  binding of the same node — a handset moved to another account follows
   *  its latest approval. Journaled. */
  def bind(nodeId: String, user: String): Unit =
    put(nodeId, user)
    Journal.rec(Journal.bindOp(nodeId, user))

  /** the replay path: same reseat, no re-journaling. */
  def replayBind(nodeId: String, user: String): Unit = put(nodeId, user)

  /** drop `nodeId`'s binding, if any. Journaled (`unbind` op). Written ONLY by
   *  an enrolment revocation (enroll.scala) — never by account removal: a
   *  binding is a NAME and deliberately outlives its account (plan 0058's
   *  owner ruling — deletion is shallow, recreating the name is the undo), and
   *  an explicit rebind overwrites through `bind` rather than through here. */
  def unbind(nodeId: String): Unit =
    drop(nodeId)
    Journal.rec(Journal.unbindOp(nodeId))

  def replayUnbind(nodeId: String): Unit = drop(nodeId)

  def drop(nodeId: String): Unit =
    cell.withLock(st => st.items = withoutNode(st.items, nodeId, Nil))

  def put(nodeId: String, user: String): Unit =
    cell.withLock(st => st.items = Binding(nodeId, user) :: withoutNode(st.items, nodeId, Nil))

  def withoutNode(xs: List[Binding], nodeId: String, acc: List[Binding]): List[Binding] = xs match
    case h :: t => withoutStep(h, t, nodeId, acc)
    case Nil  => ListOps.reverse(acc)

  def withoutStep(h: Binding, t: List[Binding], nodeId: String, acc: List[Binding]): List[Binding] =
    var acc2: List[Binding] = acc
    if h.nodeId == nodeId then acc2 = acc else acc2 = h :: acc2
    withoutNode(t, nodeId, acc2)

  /** the bound account's localpart, if any. */
  def userFor(nodeId: String): Option[String] =
    findNode(cell.withLock(st => st.items), nodeId)

  def findNode(xs: List[Binding], nodeId: String): Option[String] = xs match
    case h :: t => findStep(h, t, nodeId)
    case Nil  => None

  def findStep(h: Binding, t: List[Binding], nodeId: String): Option[String] =
    if h.nodeId == nodeId then Some(h.user) else findNode(t, nodeId)

  /** every binding — the admin enroll listing renders these beside the
   *  allowlisted ids so an enrolled handset's row can name its account. */
  def all(): List[Binding] = cell.withLock(st => st.items)

object DeviceLogin:
  /** the trusted header the iroh bridge injects (go-pkgs/irohnet/nodeid.go).
   *  Its presence IS the transport check: both edges strip inbound copies,
   *  and only the iroh bridge sets it. */
  def NODE_HEADER: String = "X-Wata-Node-Id"

  /** `POST /_wata/v1/device-login`. */
  def route(r: go.net.http.Request): Either[MErr, Json] =
    val nid = r.header.get(NODE_HEADER)
    if nid == "" then Left(MErr(403, M_FORBIDDEN(), "Device login is served only over the iroh transport"))
    else byNode(nid)

  def byNode(nodeId: String): Either[MErr, Json] = Bindings.userFor(nodeId) match
    case s: Some[String] => byUser(s.value, nodeId)
    case None => Left(MErr(404, M_NOT_FOUND(), "No account is bound to this device"))

  /** a binding to an account that was since removed answers 403, not a
   *  session for a ghost. The binding itself is left in place: re-creating
   *  the account restores the handset without another enrolment. A success
   *  records the proven node id on the minted row (plan 0058), so an
   *  enrolment revocation can kill exactly the sessions this node minted. */
  def byUser(lp: String, nodeId: String): Either[MErr, Json] = Store.userByLocalpart(lp) match
    case _: Some[UserCfg] => Router.loginOkNode(lp, nodeId)
    case None => Left(MErr(403, M_FORBIDDEN(), "The bound account no longer exists"))
