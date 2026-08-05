/** the FIXTURE-ORACLE describer: feeds captured /sync responses (from a real
 *  wata-server instance) through the engine and renders emitted events +
 *  accumulated state + the built snapshot as deterministic text. The
 *  app-side driver (`wata-fb syncfix`) reads the fixture FILES and calls
 *  `fixtureReport`; the expected output is pinned in
 *  `tools/wataclient-fixtures.expected.txt`. PORTABLE — zero `go` facade use.
 *
 *  Every helper RETURNS a String (a StringBuilder must never cross a def
 *  boundary — a builder parameter lowers to an untyped `any` here, which
 *  silently breaks appends made through it). */
object SyncDescribe:

  def boolStr(x: Boolean): String = if x then "true" else "false"

  /** one fixture = (selfUserId, name, body). A change of selfUserId RESETS the
   *  engine (each user's sync stream is its own processor, as in the client). */
  def fixtureReport(fixtures: List[(String, String, String)]): String =
    val b = new StringBuilder
    var currentSelf = ""
    var cur = fixtures
    var going = true
    while going do
      cur match
        case c: ::[(String, String, String)] =>
          val selfUid: String = c.head._1
          if selfUid != currentSelf then
            SyncEngine.reset()
            SyncEngine.setSelfUser(selfUid)
            currentSelf = selfUid
            b.append("==== user ")
            b.append(selfUid)
            b.append('\n')
          b.append(runFixture(c.head._2, c.head._3))
          cur = c.tail
        case Nil => going = false
    b.toString

  def runFixture(name: String, body: String): String =
    val b = new StringBuilder
    b.append("== fixture ")
    b.append(name)
    b.append('\n')
    Json.tryParse(body) match
      case r: Right[String, Json] => b.append(runParsed(r.right))
      case l: Left[String, Json]  =>
        b.append("PARSE ERROR ")
        b.append(l.left)
        b.append('\n')
    b.toString

  def runParsed(j: Json): String =
    val evs = SyncEngine.process(j)
    val b = new StringBuilder
    b.append(describeEvents(evs))
    b.append("next_batch ")
    b.append(SyncEngine.nextBatch)
    b.append('\n')
    b.append(describeState())
    b.append(describeSnapshot())
    b.toString

  // ---- events -----------------------------------------------------------------

  def describeEvents(evs: List[SyncEvent]): String =
    val b = new StringBuilder
    var cur = evs
    var going = true
    while going do
      cur match
        case c: ::[SyncEvent] =>
          b.append(describeEvent(c.head))
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeEvent(e: SyncEvent): String = e match
    case ru: RoomUpdated        => "ev room_updated " + ru.roomId + "\n"
    case te: TimelineEventE     => "ev timeline_event " + te.roomId + " " + te.eventId + "\n"
    case mc: MembershipChanged  => "ev membership_changed " + mc.roomId + " " + mc.userId + " " + mc.membership + "\n"
    case ru: ReceiptUpdated     => "ev receipt_updated " + ru.roomId + " " + ru.eventId + "\n"

  // ---- accumulated state --------------------------------------------------------

  def describeState(): String =
    val b = new StringBuilder
    var cur = SyncEngine.allRooms
    var going = true
    while going do
      cur match
        case c: ::[RoomState] =>
          b.append(describeRoom(c.head))
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeRoom(r: RoomState): String =
    val b = new StringBuilder
    b.append("room ")
    b.append(r.roomId)
    b.append(" name=\"")
    b.append(r.name)
    b.append('"')
    b.append(" alias=\"")
    if r.hasAlias then b.append(r.alias)
    b.append('"')
    b.append(" dm=")
    b.append(dmStr(r.dmMembers))
    b.append(" fam=")
    b.append(boolStr(r.isFamily))
    b.append(" group=\"")
    b.append(r.groupName)
    b.append('"')
    b.append(" joined=")
    b.append(SyncEngine.joinedMemberCount(r))
    b.append('\n')
    b.append(describeMembers(r.members))
    b.append(describeVoices(r.voiceMessages))
    b.append(describeReceipts(r.receipts))
    b.toString

  /** the `net.wata.dm` pair, or "no" — the room's classification, verbatim. */
  def dmStr(members: List[String]): String = members match
    case _ :: _ => joinStr(members)
    case Nil  => "no"

  def joinStr(xs: List[String]): String =
    val b = new StringBuilder
    var first = true
    var cur = xs
    var going = true
    while going do
      cur match
        case c: ::[String] =>
          if !first then b.append(',')
          b.append(c.head)
          first = false
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeMembers(ms: List[MemberInfo]): String =
    val b = new StringBuilder
    var cur = ms
    var going = true
    while going do
      cur match
        case c: ::[MemberInfo] =>
          b.append("  member ")
          b.append(c.head.userId)
          b.append(" \"")
          b.append(c.head.displayName)
          b.append("\" ")
          b.append(c.head.membership)
          b.append('\n')
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeVoices(vs: List[VoiceMessageRaw]): String =
    val b = new StringBuilder
    var cur = vs
    var going = true
    while going do
      cur match
        case c: ::[VoiceMessageRaw] =>
          b.append("  voice ")
          b.append(c.head.eventId)
          b.append(" from ")
          b.append(c.head.sender)
          b.append(" url ")
          b.append(c.head.mxcUrl)
          b.append(" durMs ")
          b.append(c.head.durationMs.toInt)
          b.append('\n')
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeReceipts(res: List[ReceiptEntry]): String =
    val b = new StringBuilder
    var cur = res
    var going = true
    while going do
      cur match
        case c: ::[ReceiptEntry] =>
          b.append("  receipt ")
          b.append(c.head.eventId)
          b.append(" by")
          b.append(strListStr(c.head.userIds))
          b.append('\n')
          cur = c.tail
        case Nil => going = false
    b.toString

  def strListStr(xs: List[String]): String =
    val b = new StringBuilder
    var cur = xs
    var going = true
    while going do
      cur match
        case c: ::[String] =>
          b.append(' ')
          b.append(c.head)
          cur = c.tail
        case Nil => going = false
    b.toString

  // ---- snapshot -------------------------------------------------------------------

  def describeSnapshot(): String =
    val ss = SyncEngine.buildSnapshot()
    val b = new StringBuilder
    b.append("snapshot self=\"")
    if ss.hasSelfUser then b.append(ss.selfUser.displayName)
    b.append('"')
    b.append('\n')
    b.append(describeContacts(ss.contacts))
    b.append(describeConvs(ss.conversations))
    if ss.hasFamily then
      b.append("family ")
      b.append(ss.family.id)
      b.append(" \"")
      b.append(ss.family.name)
      b.append("\" members=")
      b.append(contactIdsStr(ss.family.members))
      b.append('\n')
    b.toString

  def describeContacts(cs: List[Contact]): String =
    val b = new StringBuilder
    var cur = cs
    var going = true
    while going do
      cur match
        case c: ::[Contact] =>
          b.append("contact ")
          b.append(c.head.user.id)
          b.append(" \"")
          b.append(c.head.user.displayName)
          b.append('"')
          b.append('\n')
          cur = c.tail
        case Nil => going = false
    b.toString

  def contactIdsStr(cs: List[Contact]): String =
    val b = new StringBuilder
    var first = true
    var cur = cs
    var going = true
    while going do
      cur match
        case c: ::[Contact] =>
          if !first then b.append(',')
          b.append(c.head.user.id)
          first = false
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeConvs(cs: List[Conversation]): String =
    val b = new StringBuilder
    var cur = cs
    var going = true
    while going do
      cur match
        case c: ::[Conversation] =>
          b.append(describeConv(c.head))
          cur = c.tail
        case Nil => going = false
    b.toString

  def describeConv(c: Conversation): String =
    val b = new StringBuilder
    b.append("conv \"")
    b.append(c.roomId)
    b.append("\" ")
    b.append(convTypeName(c.convType))
    b.append(" contact=\"")
    if c.hasContact then b.append(c.contact.user.id)
    b.append('"')
    b.append(" name=\"")
    b.append(c.name)
    b.append('"')
    b.append(" unplayed=")
    b.append(c.unplayedCount)
    b.append('\n')
    b.append(describeConvMessages(c.messages))
    b.toString

  def convTypeName(t: ConversationType): String = t match
    case _: DmConv     => "dm"
    case _: FamilyConv => "family"
    case _: GroupConv  => "group"

  def describeConvMessages(ms: List[VoiceMessage]): String =
    val b = new StringBuilder
    var cur = ms
    var going = true
    while going do
      cur match
        case c: ::[VoiceMessage] =>
          b.append("  msg ")
          b.append(c.head.id)
          b.append(" from \"")
          b.append(c.head.sender.displayName)
          b.append("\" durMs ")
          b.append(c.head.durationMs.toInt)
          b.append(" played=")
          b.append(boolStr(c.head.isPlayed))
          b.append(" fav=")
          b.append(boolStr(c.head.isFavorite))
          b.append('\n')
          cur = c.tail
        case Nil => going = false
    b.toString
