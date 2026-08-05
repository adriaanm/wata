import language.experimental.saferExceptions

/** The BIND SURFACE of the phone spike (plan 0023 M1): the whole client core
 *  reduced to one blocking call that takes strings and returns a string.
 *
 *  Deliberately synchronous. `Runtime.start` forks the sync and action loops
 *  into an `sgo.supervised` scope, and the scope IS the join point — so a
 *  surface that returned while the loops ran would have to hand the scope's
 *  lifetime to the caller. gomobile's bound calls arrive on whatever thread
 *  the host picks, so the spike keeps the scope entirely inside one call: log
 *  in, wait for the first snapshot, render it, wind down. A real phone client
 *  gets a handle type and an event pump; this answers the toolchain question.
 *
 *  The rendered report is line-oriented and deterministic apart from the ids
 *  the server mints, which is what makes it checkable from a shell. */
object Bind:

  /** log in, run sync until the first self-bearing snapshot, render it. */
  def probe(homeserver: String, user: String, pass: String, timeoutMs: Long): String =
    val cfg = ClientConfig(homeserver, user, pass, 1000, Session("", "", "", "", ""))
    val c = Runtime.make(cfg, SpikeCaps.httpDo(), SpikeCaps.clock())
    sgo.supervised {
      Runtime.start(c)
      var out = "error unreachable-or-rejected"
      if Runtime.waitForConnection(c, Syncing(), timeoutMs) then
        if Runtime.waitForSnapshot(c, (s: StateSnapshot) => s.hasSelfUser, timeoutMs) then
          out = report(Runtime.lastSnap)
        else out = "error no-snapshot"
      Runtime.stopClient(c)
      out
    }

  /** a build-identity line, so a bound framework can be shown to be OURS
   *  without a server anywhere near it. */
  def hello(): String = "watabind sgola-emitted go, wataclient linked"

  def report(s: StateSnapshot): String =
    val b = new StringBuilder
    b.append("self ")
    b.append(s.selfUser.id)
    b.append(" ")
    b.append(Names.displayOr(s.selfUser.displayName, s.selfUser.id))
    b.append('\n')
    b.append("contacts ")
    b.append(len(s.contacts, 0))
    b.append('\n')
    var cur = s.contacts
    var going = true
    while going do
      cur match
        case h :: t =>
          b.append("contact ")
          b.append(h.user.id)
          b.append('\n')
          cur = t
        case Nil => going = false
    b.append("conversations ")
    b.append(convLen(s.conversations, 0))
    b.append('\n')
    b.append(convLines(s.conversations))
    b.append("family ")
    b.append(if s.hasFamily then s.family.name else "-")
    b.append('\n')
    b.toString

  def len(cs: List[Contact], acc: Int): Int = cs match
    case _ :: t => len(t, acc + 1)
    case Nil    => acc

  def convLen(cs: List[Conversation], acc: Int): Int = cs match
    case _ :: t => convLen(t, acc + 1)
    case Nil    => acc

  def msgLen(ms: List[VoiceMessage], acc: Int): Int = ms match
    case _ :: t => msgLen(t, acc + 1)
    case Nil    => acc

  def convLines(cs: List[Conversation]): String =
    val b = new StringBuilder
    var cur = cs
    var going = true
    while going do
      cur match
        case h :: t =>
          b.append(convLine(h))
          cur = t
        case Nil => going = false
    b.toString

  def convLine(c: Conversation): String =
    val b = new StringBuilder
    b.append("conv ")
    b.append(kindOf(c.convType))
    b.append(" with=")
    b.append(if c.hasContact then c.contact.user.id else "-")
    b.append(" messages=")
    b.append(msgLen(c.messages, 0))
    b.append(" unplayed=")
    b.append(c.unplayedCount)
    b.append('\n')
    b.toString

  def kindOf(t: ConversationType): String = t match
    case _: FamilyConv => "family"
    case _: DmConv     => "dm"
