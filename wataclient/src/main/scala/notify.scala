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
 *  THE ARRIVAL EDGE is the per-conversation unplayed count RISING *and* the
 *  newest unplayed message CHANGING. The count is already what the sync
 *  engine computes and already what the contact list badges, so notifying on
 *  its edge means the banner and the badge can never disagree with the
 *  screen — there is one number, not a second notification channel threaded
 *  through the sync engine to drift away from it. The newest-id half exists
 *  because the runtime's backfill walk appends OLDER unplayed messages long
 *  after a room's first snapshot: that raises the count with no live event
 *  behind it, and the newest message stays the newest — so a backfill-raised
 *  count moves the badge and announces nothing. A real arrival is by
 *  definition a new newest.
 *
 *  PRIMING is once per session, not once per conversation, and it latches on
 *  the first snapshot with `caughtUp` true — the first fully processed
 *  `/sync` round — whether or not any conversations exist yet. Priming on
 *  the caught-up round rather than on first-non-empty means a first sync
 *  that reaches the client in more than one snapshot primes on the complete
 *  picture, not a partial one whose tail would read as arrivals. A fresh
 *  account primes on an EMPTY picture, so the first thing anyone ever says
 *  still announces. After priming, a conversation seen for the first time
 *  counts from zero, because a DM ROOM IS CREATED BY ITS FIRST MESSAGE: the
 *  room, the conversation and the message all appear in the same snapshot,
 *  and priming per-conversation would make the one arrival most worth
 *  announcing — the first thing anyone ever says to you — always silent. */

/** how an arriving message is presented. */
sealed trait NotifyMode
/** walkie-talkie: play it as it lands. Not reachable from the device UI —
 *  the future focus-modes work reintroduces it deliberately (plan 0047). */
case class NotifyPlayNow() extends NotifyMode
/** the device default: a short chime, then the quiet channels (plan 0047). */
case class NotifyChime() extends NotifyMode
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

/** one room's mark as of the last step: its unplayed count and the event id
 *  of its newest unplayed message not our own ("" when there is none) — the
 *  same message `newest` selects for announcing, so the id the edge compares
 *  is the id an announcement would name. */
case class NotifyMark(roomId: String, count: Int, newestId: String)

/** the marks the edge is measured against, and whether this session has seen
 *  a caught-up snapshot yet. */
case class NotifyState(primed: Boolean, marks: List[NotifyMark])

/** one step's answer: the marks to carry forward, and what arrived.
 *
 *  A case class rather than the `(NotifyState, List[Arrival])` tuple this was
 *  first written as. The tuple form hit a real compiler bug — a reference-typed
 *  component erased to Go's `any` with the type assertion missing at the read —
 *  which is FIXED upstream (`TUPLE-REF-COMPONENT-ASSIGN`; it was the
 *  cross-module case, this module constructing and `wata-mac` reading). The
 *  named result stays because it reads better than a bare pair, not because
 *  anything still forces it. */
case class NotifyStep(marks: NotifyState, arrivals: List[Arrival])

object Notify:

  /** the persisted spellings. Strings, because this is what a config file
   *  holds and what a client's chrome passes around. */
  val MODE_PLAY = "play"
  val MODE_CHIME = "chime"
  val MODE_QUIET = "quiet"

  def parseMode(s: String): NotifyMode =
    if s == MODE_PLAY then NotifyPlayNow()
    else if s == MODE_CHIME then NotifyChime()
    else NotifyQuiet()

  def spellMode(m: NotifyMode): String = m match
    case _: NotifyPlayNow => MODE_PLAY
    case _: NotifyChime   => MODE_CHIME
    case _                => MODE_QUIET

  def playsNow(m: NotifyMode): Boolean = m match
    case _: NotifyPlayNow => true
    case _                => false

  def chimes(m: NotifyMode): Boolean = m match
    case _: NotifyChime => true
    case _              => false

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

  /** one step: the new marks, and the conversations that saw a real arrival —
   *  the unplayed count ROSE and the newest unplayed message CHANGED. Either
   *  guard alone has a hole: the count alone reads backfilled history as
   *  arrivals, the newest-id alone reads a split first sync's tail as one.
   *  Pure — the caller decides what an arrival means. */
  def step(st: NotifyState, snap: StateSnapshot): NotifyStep =
    var marks: List[NotifyMark] = Nil
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
          val wasNewest = markNewestOf(st.marks, conv.roomId)
          var newestId = ""
          newest(conv, selfId(snap)) match
            case m: Some[VoiceMessage] =>
              newestId = m.value.id
              if !priming && conv.unplayedCount > was && newestId != wasNewest then
                out = Arrival(conv.roomId, m.value.id, m.value.mxcUrl,
                  senderName(m.value), placeName(conv, snap)) :: out
            case None => ()
          marks = NotifyMark(conv.roomId, conv.unplayedCount, newestId) :: marks
          cur = c.tail
        case Nil => going = false
    NotifyStep(NotifyState(st.primed || snap.caughtUp, marks), reverse(out))

  /** the mark's count for a room, or -1 for "not seen before" — which the
   *  caller reads as zero once the session is primed. */
  def markOf(marks: List[NotifyMark], roomId: String): Int = marks match
    case p :: t => markStep(p, t, roomId)
    case Nil    => -1

  def markStep(p: NotifyMark, t: List[NotifyMark], roomId: String): Int =
    val k: String = p.roomId // bind to a String local so `==` stays native
    if k == roomId then p.count else markOf(t, roomId)

  /** the mark's newest unplayed id for a room, "" for "not seen before" or
   *  "none was unplayed" — either way, any real newest differs from it. */
  def markNewestOf(marks: List[NotifyMark], roomId: String): String = marks match
    case p :: t => markNewestStep(p, t, roomId)
    case Nil    => ""

  def markNewestStep(p: NotifyMark, t: List[NotifyMark], roomId: String): String =
    val k: String = p.roomId
    if k == roomId then p.newestId else markNewestOf(t, roomId)

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
