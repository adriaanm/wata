/** ARRIVAL NOTIFICATION: what a client does when a voice message lands while
 *  nobody is looking at it.
 *
 *  This lives in the client core rather than in one client because both
 *  clients answer the same two questions and must answer them the same way:
 *  *which* arrival is worth announcing, and *how* — a walkie-talkie that plays
 *  the message as it lands, or a quiet announcement. The presentation is the
 *  client's (a macOS banner and a Dock badge; the handset's LED, speaker and
 *  screen state), the model is not.
 *
 *  THE ARRIVAL EDGE is the per-conversation unplayed count RISING. That count
 *  is already what the sync engine computes and already what the contact list
 *  badges, so notifying on its edge means the banner and the badge can never
 *  disagree with the screen — there is one number, not a second notification
 *  channel threaded through the sync engine to drift away from it.
 *
 *  PRIMING is once per session, not once per conversation. The first snapshot
 *  that carries any conversations at all records their counts and announces
 *  nothing — a client opening with a backlog must badge it, not banner every
 *  message in it. After that a conversation seen for the first time counts
 *  from zero, because a DM ROOM IS CREATED BY ITS FIRST MESSAGE: the room, the
 *  conversation and the message all appear in the same snapshot, and priming
 *  per-conversation would make the one arrival most worth announcing — the
 *  first thing anyone ever says to you — the one that is always silent. */

/** how an arriving message is presented. */
sealed trait NotifyMode
/** walkie-talkie: play it as it lands. */
case class NotifyPlayNow() extends NotifyMode
/** announce it and leave the audio to the user. */
case class NotifyQuiet() extends NotifyMode

/** one arrival worth announcing: who it is from, where it landed, and enough
 *  to play and receipt it without going back to the snapshot. `place` is ""
 *  for a DM — the sender IS the place — and the room's name otherwise. */
case class Arrival(
  roomId: String,
  eventId: String,
  mxcUrl: String,
  sender: String,
  place: String
)

/** the marks the edge is measured against: one unplayed count per room id, as
 *  of the last step, and whether this session has seen a snapshot with any
 *  conversations in it yet. */
case class NotifyState(primed: Boolean, marks: List[(String, Int)])

/** one step's answer: the marks to carry forward, and what arrived.
 *
 *  A case class rather than the `(NotifyState, List[Arrival])` tuple this
 *  wants to be, because sgola erases a REFERENCE-typed tuple component to Go's
 *  `any` and then omits the type assertion on a plain assignment
 *  (`var cur = r._2` emits `var cur List__Arrival; cur = r._2`, which does not
 *  compile; the same read as a call argument does get the assertion). Filed
 *  upstream as TUPLE-REF-COMPONENT-ASSIGN. A named result is clearer here
 *  anyway, so this stays even once the gap closes. */
case class NotifyStep(marks: NotifyState, arrivals: List[Arrival])

object Notify:

  /** the persisted spellings. Strings, because this is what a config file
   *  holds and what a client's chrome passes around. */
  val MODE_PLAY = "play"
  val MODE_QUIET = "quiet"

  def parseMode(s: String): NotifyMode =
    if s == MODE_PLAY then NotifyPlayNow() else NotifyQuiet()

  def spellMode(m: NotifyMode): String = m match
    case _: NotifyPlayNow => MODE_PLAY
    case _                => MODE_QUIET

  def playsNow(m: NotifyMode): Boolean = m match
    case _: NotifyPlayNow => true
    case _                => false

  def initial(): NotifyState = NotifyState(false, Nil)

  /** what a Dock badge / LED / count shows: every conversation's unplayed
   *  messages, added up. */
  def totalUnplayed(snap: StateSnapshot): Int =
    var n = 0
    var cur = snap.conversations
    var going = true
    while going do
      cur match
        case c: ::[Conversation] =>
          n += c.head.unplayedCount
          cur = c.tail
        case Nil => going = false
    n

  /** one step: the new marks, and the conversations whose unplayed count rose.
   *  Pure — the caller decides what an arrival means. */
  def step(st: NotifyState, snap: StateSnapshot): NotifyStep =
    var marks: List[(String, Int)] = Nil
    var out: List[Arrival] = Nil
    var cur = snap.conversations
    var going = true
    // Before the session is primed nothing is announced, however much is
    // there; after it, a room nobody has seen before counts from zero.
    val priming = !st.primed
    while going do
      cur match
        case c: ::[Conversation] =>
          val conv = c.head
          var was = markOf(st.marks, conv.roomId)
          if was < 0 then was = 0
          if !priming && conv.unplayedCount > was then
            newest(conv, selfId(snap)) match
              case m: Some[VoiceMessage] =>
                out = Arrival(conv.roomId, m.value.id, m.value.mxcUrl,
                  senderName(m.value), placeName(conv, snap)) :: out
              case None => ()
          marks = (conv.roomId, conv.unplayedCount) :: marks
          cur = c.tail
        case Nil => going = false
    NotifyStep(NotifyState(st.primed || snap.conversations != Nil, marks), reverse(out))

  /** the mark for a room, or -1 for "not seen before" — which the caller
   *  reads as zero once the session is primed. */
  def markOf(marks: List[(String, Int)], roomId: String): Int = marks match
    case p :: t => markStep(p, t, roomId)
    case Nil    => -1

  def markStep(p: (String, Int), t: List[(String, Int)], roomId: String): Int =
    val k: String = p._1 // bind to a String local so `==` stays native
    if k == roomId then p._2 else markOf(t, roomId)

  def selfId(snap: StateSnapshot): String =
    if snap.hasSelfUser then snap.selfUser.id else ""

  /** the newest message that is unplayed and not our own — the same
   *  predicate `unplayedOf` counts with, so the message named is one of the
   *  ones the count moved for. Messages are newest first. */
  def newest(conv: Conversation, self: String): Option[VoiceMessage] =
    var cur = conv.messages
    var out: Option[VoiceMessage] = None
    var going = true
    while going do
      cur match
        case c: ::[VoiceMessage] =>
          if !c.head.isPlayed && c.head.sender.id != self then
            out = Some(c.head)
            going = false
          else cur = c.tail
        case Nil => going = false
    out

  def senderName(m: VoiceMessage): String =
    Names.displayOr(m.sender.displayName, m.sender.id)

  /** where it landed, as a person reads it: "" for a DM (the sender's name
   *  already says where), the family's name for the family thread, the
   *  stamp's name for a group. */
  def placeName(conv: Conversation, snap: StateSnapshot): String = conv.convType match
    case _: DmConv     => ""
    case _: FamilyConv => if snap.hasFamily && snap.family.name != "" then snap.family.name else "Family"
    case _: GroupConv  => conv.name

  /** the banner's two lines. Kept here so both clients say the same thing. */
  def title(a: Arrival): String = a.sender

  def body(a: Arrival): String =
    if a.place == "" then "sent you a voice message"
    else "sent a voice message to " + a.place

  def reverse(xs: List[Arrival]): List[Arrival] =
    var cur = xs
    var out: List[Arrival] = Nil
    var going = true
    while going do
      cur match
        case c: ::[Arrival] =>
          out = c.head :: out
          cur = c.tail
        case Nil => going = false
    out
