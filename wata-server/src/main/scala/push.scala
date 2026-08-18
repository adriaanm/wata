import language.experimental.saferExceptions
import ListOps.*
import JsonNav.*
import sgo.{Mutex, mutex}

/** APNs push registrations and the message fan-out (plan 0065 tier 2).
 *
 *  A polling client only hears a message while it is running. iOS suspends a
 *  backgrounded app and tears down its sockets, so the phone in a pocket is
 *  deaf — reaching it is APNs or nothing. This module is the server half:
 *  where a device's push token is registered, and what happens to it when a
 *  message lands.
 *
 *  {{{
 *  POST /_wata/v1/push/register     {"platform": "ios", "token": "<hex>", "env": "sandbox"}
 *  POST /_wata/v1/push/unregister   {}
 *  }}}
 *
 *  Both authenticate as an ordinary session (`Router.requireAuth`) and act on
 *  THAT session's device — a push registration belongs to one app install on
 *  one handset, and the session is how the server already names that.
 *  Re-registration overwrites, because the APNs token is per-install and
 *  changes (a restore, a reinstall, occasionally on its own).
 *
 *  **Registrations are keyed twice.** The row is stored per (user, device),
 *  and registering also DROPS any other row carrying the same token: a token
 *  identifies one app install, and after a logout/login the same install
 *  arrives under a fresh device id. Without that drop the install would
 *  collect a push per stale row.
 *
 *  **Journaled** (`pushreg` / `pushunreg` / `pushforget` ops, persist.scala),
 *  like bindings. A registration lost on restart is a phone that goes silent
 *  until it happens to re-register, which for a walkie-talkie is the whole
 *  defect this tier exists to fix.
 *
 *  **When a push fires.** An `m.room.message` landing in a room pushes to
 *  every registered device of every joined member EXCEPT the sending session's
 *  own device. Deliberately NOT conditioned on whether that device is
 *  currently syncing: the check is racy, and iOS suppresses a banner the
 *  foreground app consumes anyway. The fan-out runs on a spawned goroutine —
 *  a send must not wait on Apple.
 *
 *  **410 Gone deletes the registration.** APNs answering 410 means the token
 *  is dead (the app was uninstalled); a pusher that ignores it re-sends to
 *  nobody forever.
 *
 *  **With no APNs credentials configured, nothing here does anything.** That
 *  is the ordinary state of a self-hosted install — APNs credentials are
 *  team-owned, so a self-hoster brings their own developer account or none at
 *  all. Registrations are still accepted and stored (a client may register
 *  before the operator configures a key); the fan-out simply returns.
 */
case class PushReg(userId: String, deviceId: String, platform: String, token: String, env: String)

class PushState:
  var items: List[PushReg] = Nil

object PushRegs:
  private val cell: Mutex[PushState] = mutex(new PushState())

  /** register (or re-register) the calling session's token. Journaled. */
  def put(reg: PushReg): Unit =
    putLocal(reg)
    Journal.rec(Journal.pushRegOp(reg))

  def replayPut(reg: PushReg): Unit = putLocal(reg)

  /** the row replaces both its (user, device) predecessor and any row holding
   *  the same token — see the token-identifies-one-install note above. */
  def putLocal(reg: PushReg): Unit =
    cell.withLock(st => st.items = reg :: withoutToken(withoutDevice(st.items, reg.deviceId, Nil), reg.token, Nil))

  /** drop the calling session's registration (the client's explicit
   *  unregister — a sign-out, or notifications turned off). Journaled. */
  def dropDevice(deviceId: String): Unit =
    dropDeviceLocal(deviceId)
    Journal.rec(Journal.pushUnregOp(deviceId))

  def replayDropDevice(deviceId: String): Unit = dropDeviceLocal(deviceId)

  def dropDeviceLocal(deviceId: String): Unit =
    cell.withLock(st => st.items = withoutDevice(st.items, deviceId, Nil))

  /** forget a token APNs answered 410 for. Journaled — a dead token that came
   *  back after a restart would be pushed to forever. */
  def forgetToken(token: String): Unit =
    forgetLocal(token)
    Journal.rec(Journal.pushForgetOp(token))

  def replayForget(token: String): Unit = forgetLocal(token)

  def forgetLocal(token: String): Unit =
    cell.withLock(st => st.items = withoutToken(st.items, token, Nil))

  def withoutDevice(xs: List[PushReg], deviceId: String, acc: List[PushReg]): List[PushReg] = xs match
    case h :: t => withoutDeviceStep(h, t, deviceId, acc)
    case Nil  => ListOps.reverse(acc)

  def withoutDeviceStep(h: PushReg, t: List[PushReg], deviceId: String, acc: List[PushReg]): List[PushReg] =
    var acc2: List[PushReg] = acc
    if h.deviceId == deviceId then acc2 = acc else acc2 = h :: acc2
    withoutDevice(t, deviceId, acc2)

  def withoutToken(xs: List[PushReg], token: String, acc: List[PushReg]): List[PushReg] = xs match
    case h :: t => withoutTokenStep(h, t, token, acc)
    case Nil  => ListOps.reverse(acc)

  def withoutTokenStep(h: PushReg, t: List[PushReg], token: String, acc: List[PushReg]): List[PushReg] =
    var acc2: List[PushReg] = acc
    if h.token == token then acc2 = acc else acc2 = h :: acc2
    withoutToken(t, token, acc2)

  /** every registration — the fan-out filters it, and the admin status panel
   *  can count it. */
  def all(): List[PushReg] = cell.withLock(st => st.items)

  /** this user's registrations. */
  def ofUser(userId: String): List[PushReg] = collectUser(all(), userId, Nil)

  def collectUser(xs: List[PushReg], userId: String, acc: List[PushReg]): List[PushReg] = xs match
    case h :: t => collectUserStep(h, t, userId, acc)
    case Nil  => ListOps.reverse(acc)

  def collectUserStep(h: PushReg, t: List[PushReg], userId: String, acc: List[PushReg]): List[PushReg] =
    var acc2: List[PushReg] = acc
    if h.userId == userId then acc2 = h :: acc2
    collectUser(t, userId, acc2)

/** The operator's APNs credentials, read at boot from the environment.
 *
 *  {{{
 *  WATA_APNS_KEY=/etc/wata/AuthKey_ABC123.p8   the .p8 Auth Key — its presence arms the pusher
 *  WATA_APNS_KEY_ID=ABC123                     the key's id
 *  WATA_APNS_TEAM_ID=TEAM123                   the Apple developer team
 *  WATA_APNS_BUNDLE_ID=com.example.wata        the app's bundle id (the apns-topic header)
 *  WATA_APNS_ENV=sandbox|production            the default for a registration that names none
 *  WATA_APNS_HOST=https://…                    override the APNs host (a fake, a proxy)
 *  }}}
 *
 *  These are the OPERATOR's own credentials: APNs keys are team-owned with no
 *  delegation primitive, so a self-hoster brings their own developer account
 *  and bundle id, or runs without pushes. Nothing here is a baked-in constant,
 *  and `WATA_APNS_KEY` unset is the normal case — the server behaves exactly
 *  as it did before this module existed.
 */
object PushCfg:
  /** boot: arm the pusher if a key file is configured. A configured-but-broken
   *  key is reported and left disarmed — a homeserver a handset depends on
   *  comes up and says what it needs rather than refusing to boot. */
  def boot(): Unit =
    val key = go.sys.getenv("WATA_APNS_KEY")
    if key == "" then () else arm(key)

  def arm(key: String): Unit =
    val topic = go.sys.getenv("WATA_APNS_BUNDLE_ID")
    try
      go.apns.configure(go.sys.getenv("WATA_APNS_TEAM_ID"), go.sys.getenv("WATA_APNS_KEY_ID"), topic, key)
      println("Wata push notifications ON, APNs topic " + topic)
      ()
    catch case e: sgo.GoError => println("wata: APNs push disabled: " + e.message)

  /** is a pusher armed? False on every install without Apple credentials. */
  def enabled: Boolean = go.apns.configured()

  /** the host one registration's pushes go to: the explicit override if set,
   *  else Apple's sandbox or production host for the registration's own
   *  environment (a development build's token is valid against exactly one of
   *  them). */
  def hostFor(env: String): String =
    val over = go.sys.getenv("WATA_APNS_HOST")
    if over != "" then over else go.apns.hostFor(envOr(env))

  def envOr(env: String): String =
    if env != "" then env else go.sys.getenv("WATA_APNS_ENV")

object Push:
  /** `POST /_wata/v1/push/register` */
  def isRegisterPath(path: String): Boolean = path == "/_wata/v1/push/register"
  /** `POST /_wata/v1/push/unregister` */
  def isUnregisterPath(path: String): Boolean = path == "/_wata/v1/push/unregister"

  // ---- registration ------------------------------------------------------------

  def register(r: go.net.http.Request, body: String): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] => registerBody(rr.right, body)

  def registerBody(auth: Auth, body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => registerParsed(auth, j)

  def registerParsed(auth: Auth, j: Json): Either[MErr, Json] =
    val token = strField(j, "token", "")
    if token == "" then Left(MErr(400, M_BAD_JSON(), "Registration has no token"))
    else registerOk(auth, j, token)

  def registerOk(auth: Auth, j: Json, token: String): Either[MErr, Json] =
    PushRegs.put(PushReg(auth.userId, auth.deviceId, platformOf(j), token, strField(j, "env", "")))
    Right(obj1("registered", JBool(true)))

  /** the only platform this tier speaks; a registration that names none is
   *  taken as iOS rather than refused, since APNs is the only pusher there is. */
  def platformOf(j: Json): String =
    val p = strField(j, "platform", "")
    if p == "" then "ios" else p

  def unregister(r: go.net.http.Request): Either[MErr, Json] = Router.requireAuth(r) match
    case l: Left[MErr, Auth]   => Left(l.left)
    case rr: Right[MErr, Auth] =>
      PushRegs.dropDevice(rr.right.deviceId)
      Right(obj1("registered", JBool(false)))

  // ---- the fan-out --------------------------------------------------------------

  /** a message landed: push it to every joined member's registered devices
   *  except the sending session's own. Called from the send path; returns at
   *  once, the work runs on a spawned goroutine (Apple is not on the critical
   *  path of a walkie-talkie send). */
  def messageLanded(roomId: String, ev: Event, senderDeviceId: String): Unit =
    if !PushCfg.enabled || ev.etype != "m.room.message" then ()
    else go.spawn(() => fanOut(roomId, ev, senderDeviceId))

  def fanOut(roomId: String, ev: Event, senderDeviceId: String): Unit =
    pushEach(targets(joinedMembers(roomId), senderDeviceId, Nil), roomId, ev, senderName(ev.sender), bodyText(ev))

  /** every join-or-invite member of the room, from its state — the same walk,
   *  and the same population, `Store.notifyRoomMembers` wakes. Invited counts:
   *  a canonical DM leaves the peer holding an invite until they ask for the
   *  room (dm.scala), and their FIRST message is exactly the one they most
   *  need to hear about. */
  def joinedMembers(roomId: String): List[String] = Store.getRoom(roomId) match
    case s: Some[Room] => membersIn(s.value.state, Nil)
    case None => Nil

  def membersIn(state: List[(String, Event)], acc: List[String]): List[String] = state match
    case p :: t => membersInStep(p, t, acc)
    case Nil  => ListOps.reverse(acc)

  def membersInStep(p: (String, Event), t: List[(String, Event)], acc: List[String]): List[String] =
    val ev: Event = p._2
    var acc2: List[String] = acc
    if ev.etype == "m.room.member" && Store.isJoinOrInvite(strField(ev.content, "membership", "")) then
      acc2 = ev.stateKey :: acc2
    membersIn(t, acc2)

  /** the registrations to push to: every member's, minus the sending session's
   *  own device (the sender's OTHER devices still get one — a message sent
   *  from the mac should still reach the pocket). */
  def targets(members: List[String], senderDeviceId: String, acc: List[PushReg]): List[PushReg] = members match
    case h :: t => targetsStep(h, t, senderDeviceId, acc)
    case Nil  => acc

  def targetsStep(h: String, t: List[String], senderDeviceId: String, acc: List[PushReg]): List[PushReg] =
    targets(t, senderDeviceId, addRegs(PushRegs.ofUser(h), senderDeviceId, acc))

  def addRegs(xs: List[PushReg], senderDeviceId: String, acc: List[PushReg]): List[PushReg] = xs match
    case h :: t => addRegsStep(h, t, senderDeviceId, acc)
    case Nil  => acc

  def addRegsStep(h: PushReg, t: List[PushReg], senderDeviceId: String, acc: List[PushReg]): List[PushReg] =
    var acc2: List[PushReg] = acc
    if h.deviceId != senderDeviceId then acc2 = h :: acc2
    addRegs(t, senderDeviceId, acc2)

  def pushEach(xs: List[PushReg], roomId: String, ev: Event, title: String, body: String): Unit = xs match
    case h :: t => pushEachStep(h, t, roomId, ev, title, body)
    case Nil  => ()

  def pushEachStep(h: PushReg, t: List[PushReg], roomId: String, ev: Event, title: String, body: String): Unit =
    pushOne(h, roomId, ev, title, body)
    pushEach(t, roomId, ev, title, body)

  /** one push. A 410 deletes the registration; any other failure is logged and
   *  dropped — a message that failed to notify is not a message that failed to
   *  send, and the client's own sync still delivers it. */
  def pushOne(reg: PushReg, roomId: String, ev: Event, title: String, body: String): Unit =
    var status = 0
    try
      val v = go.apns.push(PushCfg.hostFor(reg.env), reg.token, title, body, roomId, ev.eventId,
                           go.Int.of(unplayedFor(reg.userId)))
      status = v.toInt
      ()
    catch case e: sgo.GoError => println("wata: push failed for " + reg.userId + ": " + e.message)
    if status == 410 then forget(reg) else ()

  def forget(reg: PushReg): Unit =
    println("wata: APNs reports the token gone, dropping the registration for " + reg.userId)
    PushRegs.forgetToken(reg.token)

  // ---- what the banner says -------------------------------------------------------

  /** the sender's display name, falling back to the mxid. */
  def senderName(userId: String): String = Store.getProfile(userId) match
    case s: Some[Profile] => nameOr(s.value.displayname, userId)
    case None => userId

  def nameOr(name: String, userId: String): String = if name == "" then userId else name

  /** a wata message is a voice clip; a text one (the tui, a test) shows its
   *  text. Neither shape is ever empty on the banner. */
  def bodyText(ev: Event): String =
    if strField(ev.content, "msgtype", "") == "m.audio" then "Voice message"
    else textOr(strField(ev.content, "body", ""))

  def textOr(s: String): String = if s == "" then "New message" else s

  // ---- the badge -------------------------------------------------------------------

  /** the app icon's badge: how many messages this user has not played yet,
   *  across every room they are joined to. A receipt per message is what
   *  "played" means here (plan 0050), so the count is the messages from
   *  someone else that carry no receipt of theirs. Family-sized rooms with
   *  swept timelines, walked once per push. */
  def unplayedFor(userId: String): scala.Int =
    countRooms(Store.roomsForUser(userId, "join"), userId, 0) +
      countRooms(Store.roomsForUser(userId, "invite"), userId, 0)

  def countRooms(rooms: List[Room], userId: String, acc: scala.Int): scala.Int = rooms match
    case h :: t => countRooms(t, userId, acc + countRoom(h, userId))
    case Nil  => acc

  def countRoom(room: Room, userId: String): scala.Int =
    countEvents(room.timeline, userId, Store.receiptsForRoom(room.roomId), 0)

  def countEvents(xs: List[Event], userId: String, rcs: List[Receipt], acc: scala.Int): scala.Int = xs match
    case h :: t => countEventsStep(h, t, userId, rcs, acc)
    case Nil  => acc

  def countEventsStep(h: Event, t: List[Event], userId: String, rcs: List[Receipt], acc: scala.Int): scala.Int =
    var acc2: scala.Int = acc
    if isUnplayed(h, userId, rcs) then acc2 = acc + 1
    countEvents(t, userId, rcs, acc2)

  def isUnplayed(ev: Event, userId: String, rcs: List[Receipt]): Boolean =
    ev.etype == "m.room.message" && ev.sender != userId && !hasReceipt(rcs, userId, ev.eventId)

  def hasReceipt(rcs: List[Receipt], userId: String, eventId: String): Boolean = rcs match
    case h :: t => hasReceiptStep(h, t, userId, eventId)
    case Nil  => false

  def hasReceiptStep(h: Receipt, t: List[Receipt], userId: String, eventId: String): Boolean =
    if h.userId == userId && h.eventId == eventId then true else hasReceipt(t, userId, eventId)
