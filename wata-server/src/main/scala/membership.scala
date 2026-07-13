/** M7 chunk 3 — room membership as the state machine it is.
 *
 *  store.ts models membership as a bare string read off `m.room.member` content
 *  and the join/invite rules are scattered across the handlers (rooms.ts). Here
 *  it is a SEALED FAMILY + an explicit `transition` table (LANGUAGE.md: types
 *  restrict; the ADT + state machine is the domain). Handlers ask `transition`
 *  and turn a `Denied` into the Matrix `{errcode,error}` envelope.
 *
 *  The transition table (from × action -> allowed / denied / if-public):
 *
 *              JOIN        INVITE      LEAVE     BAN
 *    none      if-public   allowed     allowed   allowed
 *    invite    allowed     allowed     allowed   allowed
 *    join      allowed     denied*     allowed   allowed
 *    leave     if-public   allowed     allowed   allowed
 *    ban       denied      denied*     allowed   allowed
 *
 *  TS FIDELITY (dormancy caveat, recorded in the chunk report):
 *   - JOIN matches rooms.ts `joinRoom` exactly: from `invite` -> join; from
 *     none/leave -> join ONLY if the room's `join_rule == public` (`if-public`);
 *     from `ban` -> forbidden. Already-joined is handled by the caller (idempotent
 *     `{room_id}` return), so JOIN-from-join is `allowed` here for completeness.
 *   - INVITE is SPEC-CORRECTED (`*`): the TS `handleInvite` overwrites the target
 *     member event with NO check on the target's current membership (re-inviting a
 *     joined or banned user silently rewrites their state — rot). The spec makes
 *     inviting a joined/banned user `M_FORBIDDEN`; we deny those. Well-behaved
 *     clients only invite users not in the room (none/leave -> invite), so this is
 *     invisible to the suite; if a test re-invites a joined user, the designer
 *     adjudicates (chunk 5).
 *   - LEAVE/BAN have no wired handlers in the TS (no leave/kick/ban endpoints);
 *     the table lists them for completeness (both always `allowed`).
 */
sealed trait Membership
case class MNone() extends Membership
case class MInvite() extends Membership
case class MJoin() extends Membership
case class MLeave() extends Membership
case class MBan() extends Membership

sealed trait MAction
case class AJoin() extends MAction
case class AInvite() extends MAction
case class ALeave() extends MAction
case class ABan() extends MAction

sealed trait Trans
case class Allowed() extends Trans
case class Denied() extends Trans
case class IfPublic() extends Trans

object Mem:
  def parse(s: String): Membership =
    if s == "join" then MJoin()
    else if s == "invite" then MInvite()
    else if s == "leave" then MLeave()
    else if s == "ban" then MBan()
    else MNone()

  def str(m: Membership): String = m match
    case _: MJoin   => "join"
    case _: MInvite => "invite"
    case _: MLeave  => "leave"
    case _: MBan    => "ban"
    case _: MNone   => "none"

  def transStr(t: Trans): String = t match
    case _: Allowed  => "allowed"
    case _: Denied   => "denied"
    case _: IfPublic => "if-public"

  def transition(from: Membership, act: MAction): Trans = act match
    case _: AJoin   => joinFrom(from)
    case _: AInvite => inviteFrom(from)
    case _: ALeave  => Allowed()
    case _: ABan    => Allowed()

  def joinFrom(from: Membership): Trans = from match
    case _: MInvite => Allowed()
    case _: MJoin   => Allowed()
    case _: MBan    => Denied()
    case _: MNone   => IfPublic()
    case _: MLeave  => IfPublic()

  def inviteFrom(from: Membership): Trans = from match
    case _: MJoin   => Denied()
    case _: MBan    => Denied()
    case _: MNone   => Allowed()
    case _: MInvite => Allowed()
    case _: MLeave  => Allowed()
