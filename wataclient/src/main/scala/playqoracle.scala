/** the WOKEN-PLAYBACK QUEUE oracle: `PlayQ` driven over a virtual clock by a
 *  scripted burst of arrivals, rendered as a deterministic transcript.
 *  `tools/wataclient-playq.expected.txt` pins the output; ci diffs against it.
 *
 *  The driver below is the caller's loop — deliver what arrived, charge the
 *  clock, notice a playback that stopped, serve the head, close the burst when
 *  there is nothing left — written once here so the composed behaviour is
 *  pinned rather than each function in isolation. wata-ios's `PttChan.playStep`
 *  is the same loop with a real audio session and a real snapshot in the two
 *  places this one has a script.
 *
 *  What the scenarios pin, each of them a rule that was got wrong on hardware
 *  or is one step from it:
 *   - a BURST of four plays all four, in arrival order, even though their
 *     playback together outlasts `PLAY_WINDOW_MS` several times over (the
 *     2026-08-18 defect: one played, three were silently dropped);
 *   - a message queued BEHIND another does not age — only the head ages, and
 *     only while nothing is playing, so a message that never resolves costs
 *     itself the window and costs the ones behind it nothing;
 *   - a message that arrives already past the window is dropped at once,
 *     because the age it arrives with is real waiting;
 *   - a session handover that never arrives ends the burst with "gave up",
 *     not with "played";
 *   - a FAILED playback is counted as failed, never as played;
 *   - the no-progress deadline is renewed by every offer, start and finish, so
 *     only a playback that reports neither an end nor a failure reaches it.
 *  PORTABLE — zero `go` facade use. */

/** one scripted arrival: the clock reading its push lands at, the event id, the
 *  age it arrives with, whether the client can ever resolve its media, how long
 *  its playback lasts, and whether that playback fails. */
case class PlayQArr(atMs: Long, event: String, ageMs: Long, resolves: Boolean,
                    playMs: Long, fails: Boolean)

object PlayQOracle:

  /** the virtual frame, coarse enough to keep the transcript short and fine
   *  enough that no deadline is stepped over by more than a tenth of a
   *  second. */
  val STEP_MS: Long = 250L

  def arr(atMs: Long, event: String, playMs: Long): PlayQArr =
    PlayQArr(atMs, event, 0L, true, playMs, false)

  def report(): String =
    var out = "== playq: the constants ==\n"
    out = out + "  PLAY_WINDOW_MS=" + PlayQ.PLAY_WINDOW_MS + "\n"
    out = out + "  NO_PROGRESS_MS=" + PlayQ.NO_PROGRESS_MS + "\n"

    out = out + run("1 one message, resolved and played",
      arr(0L, "$a1", 3000L) :: Nil, 0L, 60000L)

    // THE regression: four pushes in a second, 8s of audio each. 32s of
    // playback against a 20s window — every one of them must play.
    out = out + run("2 a burst of four outlasting the window",
      arr(0L, "$b1", 8000L) ::
      arr(300L, "$b2", 8000L) ::
      arr(600L, "$b3", 8000L) ::
      arr(900L, "$b4", 8000L) :: Nil, 0L, 120000L)

    // The head never resolves and burns its own window; the one behind it did
    // not age while it waited, so it still plays live.
    out = out + run("3 the head never resolves, the next still plays",
      PlayQArr(0L, "$c1", 0L, false, 0L, false) ::
      arr(200L, "$c2", 2000L) :: Nil, 0L, 60000L)

    // The age a push arrives with is real waiting: a phone that was asleep.
    out = out + run("4 one arrives already past the window",
      PlayQArr(0L, "$d1", 25000L, true, 2000L, false) ::
      arr(0L, "$d2", 2000L) :: Nil, 0L, 60000L)

    // The framework never hands the audio session over. Nothing plays, and the
    // burst says so rather than claiming a play.
    out = out + run("5 the session handover never arrives",
      arr(0L, "$e1", 2000L) ::
      arr(400L, "$e2", 2000L) :: Nil, 999999L, 60000L)

    // The handover is late but arrives: the queue was holding, not aging out.
    out = out + run("6 a late handover, inside the window",
      arr(0L, "$f1", 2000L) ::
      arr(400L, "$f2", 2000L) :: Nil, 12000L, 60000L)

    // A playback that errors clears `playing` exactly as a clean one does; only
    // the caller knows which, and the tally must not flatter it.
    out = out + run("7 a failed playback is not a played one",
      PlayQArr(0L, "$g1", 0L, true, 2000L, true) ::
      arr(100L, "$g2", 2000L) :: Nil, 0L, 60000L)

    // A playback that never reports: the wedge-breaker is the only thing that
    // can end this burst.
    out = out + run("8 a playback that never ends hits the no-progress cap",
      arr(0L, "$h1", 9999999L) :: Nil, 0L, 400000L)
    out

  /** the caller's loop over a virtual clock. Ends on the first burst-closing
   *  event; `limitMs` is the transcript's own backstop, and a run that reaches
   *  it says LIMIT rather than ending quietly. */
  def run(label: String, script: List[PlayQArr], readyAtMs: Long,
          limitMs: Long): String =
    val b = new StringBuilder
    b.append(label)
    b.append("\n")
    var q = PlayQ.empty()
    var rest = script
    var now = 0L
    var live = false
    var playEnd = -1L
    var playEvent = ""
    var playFails = false
    var going = true
    while going do
      // 1. everything the framework delivered since the last frame.
      var more = true
      while more do
        rest match
          case c: ::[PlayQArr] =>
            if c.head.atMs <= now then
              val a = c.head
              q = PlayQ.offer(q, "!room", a.event, a.ageMs, now)
              live = true
              b.append(stamp(now) + " push " + a.event + " age=" + a.ageMs +
                "ms depth=" + PlayQ.depth(q) + "\n")
              rest = c.tail
            else more = false
          case Nil => more = false
      // 2. charge the head, then notice a playback that stopped.
      q = PlayQ.tick(q, now)
      if playEnd >= 0L && now >= playEnd then
        q = PlayQ.finished(q, now, playFails)
        if playFails then b.append(stamp(now) + " FAILED " + playEvent + "\n")
        else b.append(stamp(now) + " done " + playEvent + "\n")
        playEnd = -1L
      // 3. serve the head: drop it if it waited out its window, else play it
      //    once the session is ours and the media has resolved.
      if live && !q.playing then
        if PlayQ.headStale(q) then
          val ev = headEvent(q)
          q = PlayQ.dropHead(q, now)
          b.append(stamp(now) + " gave up on " + ev + "\n")
        else if now >= readyAtMs then
          PlayQ.head(q) match
            case s: Some[PlayQMsg] =>
              val a = arrOf(script, s.value.event)
              if a.resolves then
                q = PlayQ.start(q, now)
                playEnd = now + a.playMs
                playEvent = a.event
                playFails = a.fails
                b.append(stamp(now) + " playing " + a.event + PlayQ.behind(q) + "\n")
            case None => ()
      // 4. close the burst.
      if live && PlayQ.isIdle(q) then
        b.append(stamp(now) + " burst done (" + PlayQ.summary(q) + ")\n")
        going = false
      else if live && PlayQ.wedged(q, now) then
        b.append(stamp(now) + " burst wedged (" + PlayQ.summary(q) + ")\n")
        going = false
      else if now >= limitMs then
        b.append(stamp(now) + " LIMIT (" + PlayQ.summary(q) + ")\n")
        going = false
      now = now + STEP_MS
    b.toString

  def headEvent(q: PlayQState): String = PlayQ.head(q) match
    case s: Some[PlayQMsg] => s.value.event
    case None              => ""

  def arrOf(xs: List[PlayQArr], ev: String): PlayQArr = xs match
    case h :: t => arrStep(h, t, ev)
    case Nil    => PlayQArr(0L, "", 0L, false, 0L, false)

  def arrStep(h: PlayQArr, t: List[PlayQArr], ev: String): PlayQArr =
    val k: String = h.event // bind to a String local so `==` stays native
    if k == ev then h else arrOf(t, ev)

  /** the virtual clock, tenths of a second, right-aligned to three places so
   *  the transcript's columns line up past 100 seconds. */
  def stamp(ms: Long): String =
    val s = ms / 1000L
    val d = (ms % 1000L) / 100L
    var pad = ""
    if s < 10L then pad = "  " else if s < 100L then pad = " "
    "[" + pad + s + "." + d + "s]"
