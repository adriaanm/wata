import language.experimental.saferExceptions

/** The watch-side capability impls: `Clock` over `go.time`, and `HttpDo`
 *  over **NSURLSession** (`nsurlsession.scala`) — plan 0075. watchOS denies
 *  BSD sockets to third-party apps, so the net/http client this file used to
 *  build (and the iroh-stream variant behind Enrol's seam, plan 0062) could
 *  never complete a request from the wrist; both socket transports are gone
 *  from this app. Plan 0074 ruled the watch never speaks iroh anyway — its
 *  transport is Matrix C-S HTTPS over URLSession, which also rides the
 *  phone's Bluetooth proxy for free. Enrol's CONFIG half still serves (it
 *  provisions credentials); only its transport half is inert here.
 *
 *  Transport failure of a single request is the same contract as ever: the
 *  capability folds it into `HttpResponse(0, "")`, naming the cause on the
 *  log at the seam, and the portable core never sees an error. */

class IosClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = IosCaps.sleepMs(ms)

object IosCaps:

  def clock(): Clock = IosClock()

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

  def httpDo(): HttpDo = WristHttp(timeoutMs())

  /** the same transport at a caller-chosen deadline — the enrolment
   *  announce's shorter fuse (wata-fb's seam shape). There is one transport
   *  on this platform; "plain" only means the deadline is not the sync
   *  long-poll's. */
  def plainHttp(timeoutMs: Long): HttpDo = WristHttp(timeoutMs)

  /** always false today: there is no second transport left to lose. The
   *  boot screen still asks (the shared bodies read it), so the answer
   *  stays rather than the question being forked per platform. */
  def transportUnavailable(): Boolean = false

  /** per-request deadline (wata-fb's shape — plan 0045 slice 1). Without one
   *  a hung server freezes the state machine in Connecting/Syncing forever.
   *  30s clears the server's ~25s sync long-poll; `WATA_HTTP_TIMEOUT_MS`
   *  overrides so a hung-server test does not wait out a real one. */
  val DEFAULT_TIMEOUT_MS: Long = 30000L

  def timeoutMs(): Long =
    val raw = go.sys.getenv("WATA_HTTP_TIMEOUT_MS")
    var out = DEFAULT_TIMEOUT_MS
    if raw != "" then out = parseMs(raw, DEFAULT_TIMEOUT_MS)
    out

  /** decimal milliseconds, falling back on anything unparseable. */
  def parseMs(s: String, dflt: Long): Long =
    var out = 0L
    var ok = s.length > 0
    var i = 0
    while i < s.length do
      val d = digitOf(s.substring(i, i + 1))
      if d < 0 then ok = false else out = out * 10L + d.toLong
      i = i + 1
    var res = dflt
    if ok then res = out
    res

  def digitOf(ch: String): scala.Int =
    var i = 0
    var out = -1
    while i < 10 do
      if ch == "" + i then out = i
      i = i + 1
    out


