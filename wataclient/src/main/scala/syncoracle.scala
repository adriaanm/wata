/** the sync-engine UNIT ORACLE: 15 scripted scenarios rendered as a
 *  deterministic report. Each block resets the engine, drives it through
 *  `process()` with a hand-built sync response, and prints the resulting
 *  events/state/snapshot values. `tools/wataclient-sync.expected.txt` pins
 *  the output; ci diffs against it.
 *
 *  Scenario 15 ("state persists across processes") probes that engine state
 *  survives across an intervening empty sync round, i.e. that a later,
 *  unrelated sync doesn't clobber earlier state. PORTABLE — zero `go` facade
 *  use (Json.tryParse only). */
object SyncOracle:

  def parse(s: String): Json = Json.tryParse(s) match
    case r: Right[String, Json] => r.right
    case l: Left[String, Json]  => JNull()

  def boolStr(x: Boolean): String = if x then "true" else "false"

  def eventName(e: SyncEvent): String = e match
    case _: RoomUpdated        => "room_updated"
    case _: TimelineEventE     => "timeline_event"
    case _: MembershipChanged  => "membership_changed"
    case _: ReceiptUpdated     => "receipt_updated"

  def eventCount(evs: List[SyncEvent]): Int =
    var n = 0
    var cur = evs
    var going = true
    while going do
      cur match
        case c: ::[SyncEvent] => n += 1; cur = c.tail
        case Nil            => going = false
    n

  def hasEvent(evs: List[SyncEvent], name: String): Boolean =
    var found = false
    var cur = evs
    var going = true
    while going do
      cur match
        case c: ::[SyncEvent] =>
          if eventName(c.head) == name then found = true
          cur = c.tail
        case Nil => going = false
    found

  /** the event names in emission order, dot-separated (order assertions). */
  def eventNames(evs: List[SyncEvent]): String =
    val b = new StringBuilder
    var first = true
    var cur = evs
    var going = true
    while going do
      cur match
        case c: ::[SyncEvent] =>
          if !first then b.append('.')
          b.append(eventName(c.head))
          first = false
          cur = c.tail
        case Nil => going = false
    b.toString

  def convCount(ss: StateSnapshot): Int =
    var n = 0
    var cur = ss.conversations
    var going = true
    while going do
      cur match
        case c: ::[Conversation] => n += 1; cur = c.tail
        case Nil               => going = false
    n

  def contactCount(ss: StateSnapshot): Int =
    var n = 0
    var cur = ss.contacts
    var going = true
    while going do
      cur match
        case c: ::[Contact] => n += 1; cur = c.tail
        case Nil          => going = false
    n

  def nthConv(ss: StateSnapshot, n: Int): Conversation =
    var result = Conversation("", DmConv(), false, Contact(User("", "")), Nil, 0)
    var i = 0
    var cur = ss.conversations
    var going = true
    while going do
      cur match
        case c: ::[Conversation] =>
          if i == n then result = c.head
          i += 1
          cur = c.tail
        case Nil => going = false
    result

  def firstMessage(c: Conversation): VoiceMessage =
    c.messages match
      case m :: _ => m
      case Nil  => VoiceMessage("", User("", ""), "", 0L, 0L, false)

  def msgCount(c: Conversation): Int =
    var n = 0
    var cur = c.messages
    var going = true
    while going do
      cur match
        case cc: ::[VoiceMessage] => n += 1; cur = cc.tail
        case Nil                => going = false
    n

  def convTypeName(t: ConversationType): String = t match
    case _: DmConv     => "dm"
    case _: FamilyConv => "family"

  def famMemberCount(f: Family): Int =
    var n = 0
    var cur = f.members
    var going = true
    while going do
      cur match
        case c: ::[Contact] => n += 1; cur = c.tail
        case Nil          => going = false
    n

  def firstFamMember(f: Family): Contact = f.members match
    case m :: _ => m
    case Nil  => Contact(User("", ""))

  /** find the conversation whose contact is `uid` ("" roomId etc. read off it). */
  def convOfContact(ss: StateSnapshot, uid: String): Conversation =
    var result = Conversation("<none>", DmConv(), false, Contact(User("", "")), Nil, 0)
    var cur = ss.conversations
    var going = true
    while going do
      cur match
        case c: ::[Conversation] =>
          val k: String = c.head.contact.user.id
          if c.head.hasContact && k == uid then result = c.head
          cur = c.tail
        case Nil => going = false
    result

  def report(): String =
    val b = new StringBuilder

    // -- 1. process empty sync response ------------------------------------------
    SyncEngine.reset()
    val evs1 = SyncEngine.process(parse("{\"next_batch\":\"batch_1\"}"))
    b.append("t01 empty: events "); b.append(eventCount(evs1))
    b.append(" next_batch "); b.append(SyncEngine.nextBatch)
    b.append(" rooms "); b.append(SyncEngine.roomCount); b.append('\n')

    // -- 2. joined room and member -----------------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js2 = "{\"next_batch\":\"batch_2\",\"rooms\":{\"join\":{\"!room1:test\":{" +
      "\"state\":{\"events\":[" +
      "{\"type\":\"m.room.name\",\"state_key\":\"\",\"content\":{\"name\":\"Test Room\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\",\"sender\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}" +
      "]}}}}}"
    val evs2 = SyncEngine.process(parse(js2))
    b.append("t02 join+member: events "); b.append(eventCount(evs2))
    b.append(" order "); b.append(eventNames(evs2))
    b.append(" rooms "); b.append(SyncEngine.roomCount); b.append('\n')
    val room2 = SyncEngine.roomOr("!room1:test", SyncEngine.emptyRoom("?"))
    b.append("t02 room: name "); b.append(room2.name)
    b.append(" joined "); b.append(SyncEngine.joinedMemberCount(room2)); b.append('\n')
    val bob2 = SyncEngine.findMember(room2.members, "@bob:test") match
      case s: Some[MemberInfo] => s.value
      case None => MemberInfo("", "", "")
    b.append("t02 bob: display "); b.append(bob2.displayName)
    b.append(" membership "); b.append(bob2.membership); b.append('\n')

    // -- 3. voice message ----------------------------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js3 = "{\"next_batch\":\"batch_3\",\"rooms\":{\"join\":{\"!room1:test\":{" +
      "\"timeline\":{\"events\":[" +
      "{\"type\":\"m.room.message\",\"event_id\":\"$msg1\",\"sender\":\"@bob:test\"," +
      "\"origin_server_ts\":1700000000000," +
      "\"content\":{\"msgtype\":\"m.audio\",\"url\":\"mxc://test/audio1\",\"body\":\"voice\"," +
      "\"info\":{\"duration\":3500,\"mimetype\":\"audio/ogg\"}}}" +
      "]}}}}}"
    val evs3 = SyncEngine.process(parse(js3))
    b.append("t03 voice: order "); b.append(eventNames(evs3)); b.append('\n')
    val room3 = SyncEngine.roomOr("!room1:test", SyncEngine.emptyRoom("?"))
    val vm3 = room3.voiceMessages match
      case v :: _ => v
      case Nil  => VoiceMessageRaw("", "", "", 0L, 0L)
    b.append("t03 vm: id "); b.append(vm3.eventId)
    b.append(" sender "); b.append(vm3.sender)
    b.append(" url "); b.append(vm3.mxcUrl)
    b.append(" durMs "); b.append(vm3.durationMs.toInt)
    b.append(" tsOk "); b.append(boolStr(vm3.timestamp == 1700000000000L)); b.append('\n')

    // -- 4. net.wata.dm classifies the room; is_direct classifies nothing -------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js4 = "{\"next_batch\":\"batch_4\",\"rooms\":{\"join\":{" +
      "\"!dm1:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}}," +
      "\"!plain:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\",\"is_direct\":true}}]}}}}}"
    SyncEngine.process(parse(js4))
    val dm4 = SyncEngine.roomOr("!dm1:test", SyncEngine.emptyRoom("?"))
    val plain4 = SyncEngine.roomOr("!plain:test", SyncEngine.emptyRoom("?"))
    b.append("t04 classify: dm "); b.append(SyncDescribe.dmStr(dm4.dmMembers))
    b.append(" is_direct-only "); b.append(SyncDescribe.dmStr(plain4.dmMembers))
    b.append(" peer "); b.append(SyncEngine.dmPeerOf(dm4)); b.append('\n')

    // -- 5. buildSnapshot: a classified room IS the conversation ----------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js5 = "{\"next_batch\":\"b5\"," +
      "\"rooms\":{\"join\":{\"!dm1:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}}}}}"
    SyncEngine.process(parse(js5))
    val ss5 = SyncEngine.buildSnapshot()
    b.append("t05 snapshot: contacts "); b.append(contactCount(ss5))
    val c5 = ss5.contacts match
      case h :: _ => h
      case Nil  => Contact(User("", ""))
    b.append(" first "); b.append(c5.user.displayName)
    b.append(" convs "); b.append(convCount(ss5))
    b.append(" room "); b.append(nthConv(ss5, 0).roomId); b.append('\n')

    // -- 6. timeline event dedup ------------------------------------------------------
    SyncEngine.reset()
    val js6 = "{\"next_batch\":\"batch_5\",\"rooms\":{\"join\":{\"!room1:test\":{" +
      "\"timeline\":{\"events\":[" +
      "{\"type\":\"m.room.message\",\"event_id\":\"$msg1\",\"sender\":\"@bob:test\"," +
      "\"origin_server_ts\":1700000000000," +
      "\"content\":{\"msgtype\":\"m.audio\",\"url\":\"mxc://test/audio1\"," +
      "\"info\":{\"duration\":1000}}}]}}}}}"
    val j6 = parse(js6)
    SyncEngine.process(j6)
    SyncEngine.process(j6)                         // same response again
    val room6 = SyncEngine.roomOr("!room1:test", SyncEngine.emptyRoom("?"))
    var vmCount6 = 0
    var cur6 = room6.voiceMessages
    var going6 = true
    while going6 do
      cur6 match
        case c: ::[VoiceMessageRaw] => vmCount6 += 1; cur6 = c.tail
        case Nil                  => going6 = false
    b.append("t06 dedup: voice "); b.append(vmCount6); b.append('\n')

    // -- 7. a peer's FIRST classified room wins (a loser of the pair map) --------------
    // The server only ever maps one room per pair, but a room it lost the claim
    // to stays joined and stamped, so the client still has to pick one.
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js7 = "{\"next_batch\":\"b7\",\"rooms\":{\"join\":{" +
      "\"!first:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}}," +
      "\"!second:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}]}}}}}"
    SyncEngine.process(parse(js7))
    val ss7 = SyncEngine.buildSnapshot()
    b.append("t07 first-wins: convs "); b.append(convCount(ss7))
    b.append(" room "); b.append(nthConv(ss7, 0).roomId); b.append('\n')

    // -- 8. a stamp that does not name US is somebody else's DM ------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js8 = "{\"next_batch\":\"b8\",\"rooms\":{\"join\":{\"!theirs:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@bob:test\",\"@charlie:test\"]}}]}}}}}"
    SyncEngine.process(parse(js8))
    val ss8 = SyncEngine.buildSnapshot()
    b.append("t08 not-ours: convs "); b.append(convCount(ss8))
    b.append(" contacts "); b.append(contactCount(ss8)); b.append('\n')

    // -- 9. family room by #family: canonical alias -------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js9 = "{\"next_batch\":\"b9\",\"rooms\":{\"join\":{\"!fam:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"m.room.canonical_alias\",\"state_key\":\"\",\"content\":{\"alias\":\"#family:test\"}}," +
      "{\"type\":\"m.room.name\",\"state_key\":\"\",\"content\":{\"name\":\"Family\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@alice:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Alice\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}" +
      "]}}}}}"
    SyncEngine.process(parse(js9))
    val ss9 = SyncEngine.buildSnapshot()
    b.append("t09 family: has "); b.append(boolStr(ss9.hasFamily))
    b.append(" name "); b.append(ss9.family.name)
    b.append(" members "); b.append(famMemberCount(ss9.family))
    b.append(" first "); b.append(firstFamMember(ss9.family).user.displayName); b.append('\n')
    b.append("t09 convs: n "); b.append(convCount(ss9))
    b.append(" c0 "); b.append(convTypeName(nthConv(ss9, 0).convType))
    b.append(" c1 "); b.append(convTypeName(nthConv(ss9, 1).convType))
    b.append(" c1room \""); b.append(nthConv(ss9, 1).roomId); b.append('"'); b.append('\n')

    // -- 10. roomless family members get empty-roomId conversations ----------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js10 = "{\"next_batch\":\"b10\"," +
      "\"rooms\":{\"join\":{" +
      "\"!fam:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"m.room.canonical_alias\",\"state_key\":\"\",\"content\":{\"alias\":\"#family:test\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@alice:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Alice\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@charlie:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Charlie\"}}" +
      "]}}," +
      "\"!dm_bob:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}" +
      "]}}}}}"
    SyncEngine.process(parse(js10))
    val ss10 = SyncEngine.buildSnapshot()
    val charlie10 = convOfContact(ss10, "@charlie:test")
    b.append("t10 roomless: convs "); b.append(convCount(ss10))
    b.append(" c0 "); b.append(convTypeName(nthConv(ss10, 0).convType))
    b.append(" charlie room \""); b.append(charlie10.roomId)
    b.append("\" type "); b.append(convTypeName(charlie10.convType))
    b.append(" msgs "); b.append(msgCount(charlie10)); b.append('\n')

    // -- 11. self is never a contact of its own -------------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js11 = "{\"next_batch\":\"b11\",\"rooms\":{\"join\":{\"!fam:test\":{\"state\":{\"events\":[" +
      "{\"type\":\"m.room.canonical_alias\",\"state_key\":\"\",\"content\":{\"alias\":\"#family:test\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@alice:test\",\"content\":{\"membership\":\"join\",\"displayname\":\"Alice\"}}" +
      "]}}}}}"
    SyncEngine.process(parse(js11))
    val ss11 = SyncEngine.buildSnapshot()
    b.append("t11 self-skip: contacts "); b.append(contactCount(ss11))
    b.append(" family-members "); b.append(famMemberCount(ss11.family))
    b.append(" convs "); b.append(convCount(ss11)); b.append('\n')

    // -- 12. is_played tracks read receipts ------------------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js12 = "{\"next_batch\":\"b12\"," +
      "\"rooms\":{\"join\":{\"!dm1:test\":{" +
      "\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}," +
      "\"timeline\":{\"events\":[{\"type\":\"m.room.message\",\"event_id\":\"$msg1\",\"sender\":\"@bob:test\"," +
      "\"origin_server_ts\":1700000000000," +
      "\"content\":{\"msgtype\":\"m.audio\",\"url\":\"mxc://test/audio1\",\"info\":{\"duration\":2000}}}]}," +
      "\"ephemeral\":{\"events\":[{\"type\":\"m.receipt\",\"content\":{" +
      "\"$msg1\":{\"m.read\":{\"@alice:test\":{\"ts\":1700000001000}}}}}]}}}}}"
    SyncEngine.process(parse(js12))
    val ss12 = SyncEngine.buildSnapshot()
    val conv12 = nthConv(ss12, 0)
    b.append("t12 played: convs "); b.append(convCount(ss12))
    b.append(" msgs "); b.append(msgCount(conv12))
    b.append(" is_played "); b.append(boolStr(firstMessage(conv12).isPlayed))
    b.append(" unplayed "); b.append(conv12.unplayedCount); b.append('\n')

    // -- 13. unplayed count reflects messages without receipts ------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js13 = "{\"next_batch\":\"b13\"," +
      "\"rooms\":{\"join\":{\"!dm1:test\":{" +
      "\"state\":{\"events\":[" +
      "{\"type\":\"net.wata.dm\",\"state_key\":\"\",\"content\":{\"members\":[\"@alice:test\",\"@bob:test\"]}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}," +
      "\"timeline\":{\"events\":[" +
      "{\"type\":\"m.room.message\",\"event_id\":\"$msg1\",\"sender\":\"@bob:test\"," +
      "\"origin_server_ts\":1700000000000,\"content\":{\"msgtype\":\"m.audio\",\"url\":\"mxc://test/a1\",\"info\":{\"duration\":1000}}}," +
      "{\"type\":\"m.room.message\",\"event_id\":\"$msg2\",\"sender\":\"@bob:test\"," +
      "\"origin_server_ts\":1700000001000,\"content\":{\"msgtype\":\"m.audio\",\"url\":\"mxc://test/a2\",\"info\":{\"duration\":2000}}}" +
      "]}}}}}"
    SyncEngine.process(parse(js13))
    val ss13 = SyncEngine.buildSnapshot()
    val conv13 = nthConv(ss13, 0)
    b.append("t13 unplayed: n "); b.append(conv13.unplayedCount)
    b.append(" m0 "); b.append(boolStr(firstMessage(conv13).isPlayed)); b.append('\n')

    // -- 14. redaction removes the voice message ----------------------------------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    SyncEngine.process(parse(js13))               // two voice messages
    val js14 = "{\"next_batch\":\"b14\",\"rooms\":{\"join\":{\"!dm1:test\":{" +
      "\"timeline\":{\"events\":[{\"type\":\"m.room.redaction\",\"event_id\":\"$red1\"," +
      "\"sender\":\"@bob:test\",\"redacts\":\"$msg1\"}]}}}}}"
    SyncEngine.process(parse(js14))
    val ss14 = SyncEngine.buildSnapshot()
    val conv14 = nthConv(ss14, 0)
    b.append("t14 redact: msgs "); b.append(msgCount(conv14))
    b.append(" first "); b.append(firstMessage(conv14).id); b.append('\n')

    // -- 15. state persists across processes (a later, unrelated sync round) --------------
    SyncEngine.reset()
    SyncEngine.setSelfUser("@alice:test")
    val js15 = "{\"next_batch\":\"batch_survive\",\"rooms\":{\"join\":{\"!room1:test\":{" +
      "\"state\":{\"events\":[" +
      "{\"type\":\"m.room.name\",\"state_key\":\"\",\"content\":{\"name\":\"Survived\"}}," +
      "{\"type\":\"m.room.member\",\"state_key\":\"@bob:test\",\"sender\":\"@bob:test\"," +
      "\"content\":{\"membership\":\"join\",\"displayname\":\"Bob\"}}]}}}}}"
    SyncEngine.process(parse(js15))
    SyncEngine.process(parse("{\"next_batch\":\"batch_after\"}"))  // a later, empty sync
    val room15 = SyncEngine.roomOr("!room1:test", SyncEngine.emptyRoom("?"))
    val bob15 = SyncEngine.findMember(room15.members, "@bob:test") match
      case s: Some[MemberInfo] => s.value
      case None => MemberInfo("", "", "")
    b.append("t15 persist: batch "); b.append(SyncEngine.nextBatch)
    b.append(" name "); b.append(room15.name)
    b.append(" bob "); b.append(bob15.displayName); b.append('\n')

    b.toString
