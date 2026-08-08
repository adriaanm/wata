/** the ARRIVAL-NOTIFICATION oracle: `Notify.step` driven over hand-built
 *  snapshots and rendered as a deterministic report.
 *  `tools/wataclient-notify.expected.txt` pins the output; ci diffs against
 *  it.
 *
 *  What the scenarios are here to pin, because each is a rule a client would
 *  otherwise re-derive (and get wrong differently):
 *   - the session PRIMES once, on the first snapshot with `caughtUp` true
 *     (the first fully processed /sync round, conversations or not), and
 *     announces nothing however much backlog it carries — a first sync split
 *     across snapshots stays silent through every piece, and a fresh
 *     account's empty caught-up picture primes too, so the first thing
 *     anyone ever says still announces (a DM room is created by its first
 *     message, and a room appearing after priming counts from zero);
 *   - the edge is the count RISING *and* the newest unplayed message
 *     CHANGING, so a count that stays put or falls (a message played) is
 *     silent, and so is a backfill-raised count (older history appended:
 *     badge moves, newest unchanged, no announcement);
 *   - the message named is the newest one that is unplayed and not ours,
 *     which is one of the ones the count moved for;
 *   - a DM names no place, the family thread names the family, a group names
 *     its stamp;
 *   - the badge is every conversation's unplayed messages added up.
 *  PORTABLE — zero `go` facade use. */
object NotifyOracle:

  def msg(id: String, sender: String, display: String, played: Boolean): VoiceMessage =
    VoiceMessage(id, User(sender, display), "mxc://wata/" + id, 1000L, 0L, played, false, false)

  def conv(roomId: String, ct: ConversationType, name: String,
           ms: List[VoiceMessage], unplayed: Int): Conversation =
    Conversation(roomId, ct, false, Contact(User("", "")), ms, unplayed, name)

  /** caught-up by default: most scenarios are about what happens after the
   *  first /sync round; the priming scenarios pass `caughtUp` explicitly. */
  def snap(convs: List[Conversation], famName: String): StateSnapshot =
    snapCU(convs, famName, true)

  def snapCU(convs: List[Conversation], famName: String, caughtUp: Boolean): StateSnapshot =
    StateSnapshot(Syncing(), true, User("@alice:localhost", "Alice"), Nil, convs,
      famName != "", Family("!fam", famName, Nil), caughtUp)

  /** one line per arrival, in the order `step` reports them. */
  def showArrivals(as: List[Arrival]): String =
    val b = new StringBuilder
    var cur = as
    var going = true
    while going do
      cur match
        case c: ::[Arrival] =>
          val a = c.head
          b.append("  arrival room=")
          b.append(a.roomId)
          b.append(" event=")
          b.append(a.eventId)
          b.append(" mxc=")
          b.append(a.mxcUrl)
          b.append(" title=\"")
          b.append(Notify.title(a))
          b.append("\" body=\"")
          b.append(Notify.body(a))
          b.append("\"\n")
          cur = c.tail
        case Nil => going = false
    if as == Nil then "  (none)\n" else b.toString

  /** one step, rendered with the badge count it would drive: the report
   *  fragment and the marks to carry forward. A builder never crosses a def
   *  boundary here (DATA-10), so the fragment comes back as a String. */
  def show(label: String, st: NotifyState, s: StateSnapshot): (String, NotifyState) =
    val r = Notify.step(st, s)
    val b = new StringBuilder
    b.append(label)
    b.append("\n")
    b.append(showArrivals(r.arrivals))
    b.append("  badge=")
    b.append(Notify.totalUnplayed(s))
    b.append("\n")
    (b.toString, r.marks)

  /** the family thread as it stands after scenario 5: two unplayed from bob,
   *  one of ours, one played. Repeated verbatim in every later step so the
   *  only thing that moves is the conversation under test. */
  def famAfter5(): Conversation =
    conv("!fam", FamilyConv(), "",
      msg("$e3", "@alice:localhost", "Alice", false) ::
      msg("$e4", "@bob:localhost", "Bob", false) ::
      msg("$e2", "@bob:localhost", "Bob", false) ::
      msg("$e1", "@bob:localhost", "Bob", true) :: Nil, 2)

  def report(): String =
    var out = "== notify: the mode enum ==\n"
    out = out + "  parse(play)=" + Notify.spellMode(Notify.parseMode(Notify.MODE_PLAY)) + "\n"
    out = out + "  parse(quiet)=" + Notify.spellMode(Notify.parseMode(Notify.MODE_QUIET)) + "\n"
    // Anything unrecognised must land on the quiet side: a config file written
    // by a newer client must not make this one start playing audio by itself.
    out = out + "  parse(nonsense)=" + Notify.spellMode(Notify.parseMode("nonsense")) + "\n"
    out = out + "  playsNow(play)=" + boolStr(Notify.playsNow(NotifyPlayNow())) + "\n"
    out = out + "  playsNow(quiet)=" + boolStr(Notify.playsNow(NotifyQuiet())) + "\n"
    out = out + "== notify: the arrival edge ==\n"

    val bobOld = msg("$e1", "@bob:localhost", "Bob", false)
    val bobNew = msg("$e2", "@bob:localhost", "Bob", false)
    val bob3 = msg("$e4", "@bob:localhost", "Bob", false)
    val mine = msg("$e3", "@alice:localhost", "Alice", false)
    val dm1 = msg("$d1", "@bob:localhost", "Bob", false)
    val dm2 = msg("$d2", "@bob:localhost", "Bob", false)

    var st = Notify.initial()
    // 1. first sight of a conversation that ALREADY has a backlog: badge it,
    //    announce nothing.
    var r = show("1 first sight, two unplayed", st,
      snap(conv("!fam", FamilyConv(), "", bobNew :: bobOld :: Nil, 2) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 2. nothing moved.
    r = show("2 unchanged", st,
      snap(conv("!fam", FamilyConv(), "", bobNew :: bobOld :: Nil, 2) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 3. one more lands: the newest unplayed message that is not ours is named.
    r = show("3 one arrives in the family thread", st,
      snap(conv("!fam", FamilyConv(), "", bob3 :: bobNew :: bobOld :: Nil, 3) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 4. our own send raises nothing — `unplayedCount` excludes it, and the
    //    message search skips it too.
    r = show("4 our own send", st,
      snap(conv("!fam", FamilyConv(), "", mine :: bob3 :: bobNew :: bobOld :: Nil, 3) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 5. the count falls (a message played): silent.
    r = show("5 one played", st, snap(famAfter5() :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 6. a DM room appears mid-session, created by its first message: the
    //    session is long primed, so this DOES announce — it is the first thing
    //    anyone has ever said to us, and per-conversation priming would make
    //    exactly that arrival silent. A DM names no place.
    r = show("6 a DM room appears with its first message", st,
      snap(famAfter5() :: conv("!dm", DmConv(), "", dm1 :: Nil, 1) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 7. two conversations move at once, one of each kind — a group names its
    //    stamp. Reported in snapshot order.
    r = show("7 a DM moves and a group appears", st,
      snap(famAfter5() ::
        conv("!dm", DmConv(), "", dm2 :: dm1 :: Nil, 2) ::
        conv("!grp", GroupConv(), "Kids",
          msg("$g1", "@carol:localhost", "", false) :: Nil, 1) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 8. from a sender with no display name, which falls back to the
    //    localpart.
    r = show("8 the group's next message", st,
      snap(famAfter5() ::
        conv("!dm", DmConv(), "", dm2 :: dm1 :: Nil, 2) ::
        conv("!grp", GroupConv(), "Kids",
          msg("$g2", "@carol:localhost", "", false) ::
          msg("$g1", "@carol:localhost", "", false) :: Nil, 2) :: Nil, "Moors"))
    out = out + r._1; st = r._2
    // 9. a family thread with no family name still names a place — and the
    //    priming step is what a session's very first snapshot looks like.
    var st2 = Notify.initial()
    r = show("9 no family name: priming", st2,
      snap(conv("!fam", FamilyConv(), "", Nil, 0) :: Nil, ""))
    out = out + r._1; st2 = r._2
    r = show("9b no family name: arrival", st2,
      snap(conv("!fam", FamilyConv(), "", bobNew :: Nil, 1) :: Nil, ""))
    out = out + r._1; st2 = r._2
    out = out + "== notify: priming and backfill ==\n"

    val bobOlder = msg("$e0", "@bob:localhost", "Bob", false)

    // 10. a first sync split across two snapshots: the partial piece is not
    //     caught up, so it does not prime, and the complete piece is the one
    //     that does — no announcement either step, however much of the
    //     backlog each carries. Badge tracks each snapshot as it stands.
    var st3 = Notify.initial()
    r = show("10 split backlog: partial, not caught up", st3,
      snapCU(conv("!fam", FamilyConv(), "", bobOld :: Nil, 1) :: Nil, "Moors", false))
    out = out + r._1; st3 = r._2
    r = show("10b split backlog: complete, caught up", st3,
      snapCU(conv("!fam", FamilyConv(), "", bobNew :: bobOld :: Nil, 2) :: Nil, "Moors", true))
    out = out + r._1; st3 = r._2
    // 11. backfill appends OLDER unplayed history after priming: the count
    //     rises but the newest unplayed message is still the newest, so the
    //     badge moves and nothing announces.
    r = show("11 backfill raises the count, newest unchanged", st3,
      snapCU(conv("!fam", FamilyConv(), "", bobNew :: bobOld :: bobOlder :: Nil, 3) :: Nil, "Moors", true))
    out = out + r._1; st3 = r._2
    // 11b. and a live arrival right after the backfill still announces: the
    //      count rose and the newest changed.
    r = show("11b a live arrival after the backfill", st3,
      snapCU(conv("!fam", FamilyConv(), "", bob3 :: bobNew :: bobOld :: bobOlder :: Nil, 4) :: Nil, "Moors", true))
    out = out + r._1; st3 = r._2
    // 12. a fresh account: the caught-up first round carries NO conversations
    //     and still primes, so the first thing anyone ever says announces.
    var st4 = Notify.initial()
    r = show("12 fresh account: empty and caught up", st4,
      snapCU(Nil, "", true))
    out = out + r._1; st4 = r._2
    r = show("12b the first message ever", st4,
      snap(conv("!dm", DmConv(), "", dm1 :: Nil, 1) :: Nil, ""))
    out = out + r._1; st4 = r._2
    out

  def boolStr(x: Boolean): String = if x then "true" else "false"
