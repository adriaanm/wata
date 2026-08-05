import language.experimental.saferExceptions

/** The APP-side capability constructors: `HttpDo` over the net/http CLIENT
 *  facade, `Clock` over `go.time`. This is the seam's other half
 *  (capabilities.scala, in wataclient, defines the TRAITS; the app supplies
 *  the impls).
 *
 *  The capabilities are proper traits, so the app edge supplies a NAMED impl
 *  class per capability (`FbClock`/`FbHttp`) instead of a record of closures.
 *  Go structural typing makes each `*FbClock` / `*FbHttp` satisfy the
 *  interface — no `implements`, no vtable. The impls carry no fields yet (they
 *  reach the `go.*` facades directly).
 *
 *  Client facade surface used: `newRequest` (method/URL/body-reader),
 *  `Request.header.set` (headers in), `DefaultClient.Do`, `Response.statusCode`
 *  / `Response.body`, `io.readAll` (body out), and `go.io.ReadCloser` —
 *  `Response.body` is typed as a `ReadCloser` so the long-poll loop can
 *  `close()` each body (connection reuse).
 *
 *  Transport failure policy (see capabilities.scala): a thrown `GoError`
 *  becomes `HttpResponse(0, "")` — the portable core never sees Go errors. */
/** the app-edge `Clock` impl over `go.time` (a named class, no fields). */
class FbClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = FbCaps.sleepMs(ms)

/** the app-edge `HttpDo` impl over the net/http CLIENT facade. `HttpDo`
 *  extends `Shareable`, so this impl's shareability is checked at its
 *  definition site. It HOLDS the `go.net.http.Client` handle as a constructor
 *  val-param field. Admissible because the Client facade carries the
 *  `@sgo.curatedSafe` verdict ("Clients are safe for concurrent use by
 *  multiple goroutines"). */
class FbHttp(client: go.net.http.Client) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = FbCaps.send(req, client)

object FbCaps:

  def clock(): Clock = FbClock()

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind — the `timeout` combinator's own recipe). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

  /** Transport selection (plan 0013): `WATA_IROH_CONFIG=<json>` swaps the
   *  underlying client for one whose connections are iroh streams to the
   *  configured peer — same `go.net.http.Client` facade type, so `FbHttp`
   *  and everything above the capability line are untouched. Unset (the
   *  default, and what every harness uses) is the plain TCP client.
   *
   *  EVERY request is bounded by `timeoutMs` (plan 0022): unbounded was a
   *  wedge — one half-open connection and the sync round, or the action the
   *  UI is waiting on, never returns. The bound has to clear the Matrix
   *  long-poll, which the client caps itself (`ClientConfig.syncTimeoutMs`,
   *  seconds); 30s is the default, and `WATA_HTTP_TIMEOUT_MS` overrides it so
   *  a hung-server test does not have to wait out a real one. */
  def httpDo(): HttpDo =
    val irohCfg = go.sys.getenv("WATA_IROH_CONFIG")
    if irohCfg == "" then FbHttp(go.httpc.newClient(timeoutMs()))
    else FbHttp(irohClient(irohCfg))

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

  /** the iroh-backed client, or — on a failed init (bad config, stub build) —
   *  a loud line, a LATCHED "transport unavailable" state the boot screen
   *  names outright, and a client that can only fail (there is no os.Exit
   *  facade). Downgrading silently to the plain client left the device
   *  showing "waiting for network" forever against a transport that was never
   *  going to come up. */
  def irohClient(cfgPath: String): go.net.http.Client =
    var out = go.net.http.DefaultClient
    try out = go.irohnet.newHTTPClient(cfgPath)
    catch case e: sgo.GoError => noteTransportDown(e.message)
    go.httpc.withTimeout(out, timeoutMs())

  def noteTransportDown(msg: String): Unit =
    println("irohnet: client init failed: " + msg)
    transportDownC.set(true)

  // the transport's own verdict: set once, at client construction, when the
  // configured transport could not be brought up at all. Written by the
  // constructing goroutine before the loops start, read per frame.
  private val transportDownC: sgo.Atomic[Boolean] = sgo.atomic(false)

  /** is the configured transport unavailable — a permanent state no retry can
   *  fix (the UI says so rather than blaming the network)? */
  def transportUnavailable(): Boolean = transportDownC.get()

  def send(req: HttpRequest, client: go.net.http.Client): HttpResponse =
    var out = HttpResponse(0, "")
    try
      val r = go.net.http.newRequest(req.method, req.url, bodyReader(req))
      setHeaders(r, req.headers)
      val resp = client.Do(r)
      val raw = go.io.readAll(resp.body)
      resp.body.close()
      out = HttpResponse(resp.statusCode.toInt, go.string(raw))
    catch case e: sgo.GoError => out = HttpResponse(0, "")
    out

  /** "" body -> nil reader (a GET; the httpscenario
   *  `newRequest(_, _, null.asInstanceOf[go.io.Reader])` shape), else a
   *  strings.Reader over the (binary-safe) body String. */
  def bodyReader(req: HttpRequest): go.io.Reader =
    if req.body == "" then null.asInstanceOf[go.io.Reader]
    else go.strings.newReader(req.body)

  def setHeaders(r: go.net.http.Request, hs: List[(String, String)]): Unit =
    var cur = hs
    var going = true
    while going do
      cur match
        case p :: t =>
          setHeader1(r, p)
          cur = t
        case Nil => going = false

  def setHeader1(r: go.net.http.Request, p: (String, String)): Unit =
    val k: String = p._1
    val v: String = p._2
    r.header.set(k, v)
