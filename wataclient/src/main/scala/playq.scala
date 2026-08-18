/** THE WOKEN-PLAYBACK QUEUE: a client is woken (by a push, by an arrival) with
 *  one or more messages to play live, and this decides which one plays next,
 *  when one has waited too long to be worth playing live at all, and when the
 *  whole burst is over.
 *
 *  It lives in the client core because it is pure scheduling over a clock and
 *  a list — no audio, no platform, no session — and because it is the part of
 *  a live-playback arc that is easiest to get wrong and hardest to observe on
 *  a device. Its first consumer is wata-ios's PushToTalk receive half
 *  (`PttChan`), where the queue's shape is forced by the platform: the system
 *  raises ONE speaker and activates ONE audio session for a burst of pushes,
 *  and every message in that burst has to play on it, in order, one at a time.
 *
 *  THE BURST IS THE UNIT, NOT THE MESSAGE. Four voice messages in a row is
 *  what a walkie-talkie does, not an edge case. So a message that arrives
 *  while another is playing is APPENDED; nothing is ever displaced by
 *  something newer. On hardware 2026-08-18 the last-one-wins version of this
 *  played the first of four and silently dropped the rest.
 *
 *  A MESSAGE'S WINDOW COUNTS ONLY ITS OWN WAITING. `PLAY_WINDOW_MS` exists to
 *  stop a message playing out of nowhere long after it was sent — a phone that
 *  was asleep, a client that cannot resolve the media, a session handover that
 *  never arrives. It must NOT expire a message because the messages AHEAD of
 *  it were still sounding: four messages whose playback together outlasts the
 *  window must all play. So the window is charged against a message only while
 *  it is at the HEAD of the queue with nothing playing (plus the age it
 *  arrived with, which is real waiting the client did not observe), and a
 *  message queued behind a playing one ages not at all.
 *
 *  `NO_PROGRESS_MS` is a wedge-breaker, not a limit on how long a burst may
 *  be: it is renewed by every offer, every start and every finish, so a
 *  draining queue keeps resetting it and only a playback that neither ends nor
 *  fails can reach it. */

/** one message the client was woken to play. `waitedMs` is how long it has
 *  spent waiting for its own turn to come — seeded with the age it arrived
 *  with, and grown only while it is the head of the queue and nothing is
 *  playing. */
case class PlayQMsg(room: String, event: String, waitedMs: Long)

/** the queue and the burst it is draining: `pending` in arrival order (head
 *  plays next), `playing` while a message this queue started is still
 *  sounding, `at` the clock reading the head was last charged at, `wedgeAt`
 *  the no-progress deadline, and the tallies the burst's closing line
 *  reports. */
case class PlayQState(
  pending: List[PlayQMsg],
  playing: Boolean,
  at: Long,
  wedgeAt: Long,
  played: Int,
  failed: Int,
  gaveUp: Int
)

object PlayQ:

  /** how long a woken message is still worth playing LIVE, counted from its
   *  arrival but only over the time it spends waiting for its own turn (see
   *  the module header). Past it the message is an ordinary unplayed arrival:
   *  the conversation row and the badge are its surface. */
  val PLAY_WINDOW_MS: Long = 20000L

  /** how long the burst may make NO progress before the caller tears it down.
   *  Renewed by every offer, start and finish, so a draining queue never
   *  reaches it; what reaches it is a playback that reports neither an end nor
   *  a failure, which would otherwise hold a speaker on screen and an audio
   *  session out of the app's hands for the life of the process. */
  val NO_PROGRESS_MS: Long = 300000L

  def empty(): PlayQState = PlayQState(Nil, false, 0L, 0L, 0, 0, 0)

  /** nothing left to play and nothing sounding: the burst is over. */
  def isIdle(q: PlayQState): Boolean = !q.playing && depth(q) == 0

  def depth(q: PlayQState): Int = count(q.pending)

  def count(xs: List[PlayQMsg]): Int = xs match
    case h :: t => 1 + count(t)
    case Nil    => 0

  def head(q: PlayQState): Option[PlayQMsg] = q.pending match
    case h :: t => Some(h)
    case Nil    => None

  /** the head has waited out its window: it is no longer worth playing live.
   *  The caller drops it and moves to the next — one stale message does not
   *  end the burst. */
  def headStale(q: PlayQState): Boolean = q.pending match
    case h :: t => h.waitedMs >= PLAY_WINDOW_MS
    case Nil    => false

  /** once per frame, before anything is decided: charge the head with the time
   *  since the last reading. Only the head, and only while nothing is playing
   *  — the module header says why. */
  def tick(q: PlayQState, nowMs: Long): PlayQState =
    var dt = 0L
    if q.at > 0L && nowMs > q.at then dt = nowMs - q.at
    var xs = q.pending
    if !q.playing && dt > 0L then xs = chargeHead(q.pending, dt)
    PlayQState(xs, q.playing, nowMs, q.wedgeAt, q.played, q.failed, q.gaveUp)

  def chargeHead(xs: List[PlayQMsg], dt: Long): List[PlayQMsg] = xs match
    case h :: t => PlayQMsg(h.room, h.event, h.waitedMs + dt) :: t
    case Nil    => Nil

  /** a push named a message: it joins the BACK of the queue, seeded with the
   *  age it arrived with, and the burst's no-progress deadline is renewed. */
  def offer(q0: PlayQState, room: String, event: String, ageMs: Long,
            nowMs: Long): PlayQState =
    val q = tick(q0, nowMs)
    var age = ageMs
    if age < 0L then age = 0L
    PlayQState(append(q.pending, PlayQMsg(room, event, age)), q.playing,
      nowMs, nowMs + NO_PROGRESS_MS, q.played, q.failed, q.gaveUp)

  def append(xs: List[PlayQMsg], x: PlayQMsg): List[PlayQMsg] = xs match
    case h :: t => h :: append(t, x)
    case Nil    => x :: Nil

  /** the head is out of time: forget it, and count it. */
  def dropHead(q: PlayQState, nowMs: Long): PlayQState = q.pending match
    case h :: t =>
      PlayQState(t, q.playing, nowMs, nowMs + NO_PROGRESS_MS,
        q.played, q.failed, q.gaveUp + 1)
    case Nil => q

  /** the head is playing now: it leaves the queue, and nothing else may start
   *  until it stops. */
  def start(q: PlayQState, nowMs: Long): PlayQState = q.pending match
    case h :: t =>
      PlayQState(t, true, nowMs, nowMs + NO_PROGRESS_MS,
        q.played, q.failed, q.gaveUp)
    case Nil => q

  /** the playback stopped — played through, or failed. Both clear `playing`;
   *  only the caller can tell them apart, so it says which. */
  def finished(q: PlayQState, nowMs: Long, failed: Boolean): PlayQState =
    var played = q.played
    var bad = q.failed
    if failed then bad = bad + 1 else played = played + 1
    PlayQState(q.pending, false, nowMs, nowMs + NO_PROGRESS_MS,
      played, bad, q.gaveUp)

  /** the burst has made no progress for NO_PROGRESS_MS. */
  def wedged(q: PlayQState, nowMs: Long): Boolean =
    q.wedgeAt > 0L && nowMs >= q.wedgeAt

  /** what the burst did, for the one line that closes it. It never says
   *  "played" for a playback that errored: a green line that overstates what
   *  ran is worse than a red one, and this log is the only surface a device's
   *  receive half has. */
  def summary(q: PlayQState): String =
    var s = ""
    if q.played > 0 then s = join(s, "played " + q.played)
    if q.failed > 0 then s = join(s, q.failed + " FAILED")
    if q.gaveUp > 0 then s = join(s, "gave up on " + q.gaveUp)
    if s == "" then "nothing played" else s

  def join(s: String, part: String): String =
    if s == "" then part else s + ", " + part

  /** how the queue reads while a message starts: "" when it is the only one. */
  def behind(q: PlayQState): String =
    val n = depth(q)
    if n == 0 then "" else " (" + n + " more queued)"
