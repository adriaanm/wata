import language.experimental.saferExceptions

/** The APP-side capability impls for the iOS client: `HttpDo` over the
 *  net/http CLIENT facade and `Clock` over `go.time`, with wata-fb's iroh
 *  seam (plan 0062): a present iroh config (Enrol.irohPath — the sandbox
 *  file, or `WATA_IROH_CONFIG` for a harness) swaps the client for one
 *  whose connections are iroh streams to the configured peer. Same
 *  `go.net.http.Client` facade type, so nothing above the capability line
 *  knows. Transport failure of a single request is the same everywhere: a
 *  thrown `GoError` becomes `HttpResponse(0, "")` — the portable core never
 *  sees Go errors. */

class IosClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = IosCaps.sleepMs(ms)

class IosHttp(client: go.net.http.Client) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = IosCaps.send(req, client)

object IosCaps:

  def clock(): Clock = IosClock()

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

  def httpDo(): HttpDo =
    if Enrol.configured() then IosHttp(irohClient(Enrol.irohPath()))
    else IosHttp(go.httpc.newClient(timeoutMs()))

  /** a PLAIN-TCP `HttpDo`, whatever the session's transport — the enrolment
   *  announce needs it (wata-fb's same seam: a device showing the enrol
   *  screen is a device the iroh transport has just refused). */
  def plainHttp(timeoutMs: Long): HttpDo = IosHttp(go.httpc.newClient(timeoutMs))

  /** the iroh-backed client, or — on a failed init (bad config, a build
   *  without the staged lib) — a loud line, a LATCHED "transport
   *  unavailable" the boot screen names, and a client that can only fail.
   *  Downgrading silently to plain TCP would show "waiting for network"
   *  forever against a transport that was never coming up. */
  def irohClient(cfgPath: String): go.net.http.Client =
    var out = go.net.http.DefaultClient
    try out = go.irohnet.newHTTPClient(cfgPath)
    catch
      case e: sgo.GoError =>
        println("irohnet: client init failed: " + e.message)
        transportDownC.set(true)
    go.httpc.withTimeout(out, timeoutMs())

  private val transportDownC: sgo.Atomic[Boolean] = sgo.atomic(false)
  def transportUnavailable(): Boolean = transportDownC.get()

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

  /** "" body -> nil reader (a GET), else a strings.Reader over the
   *  (binary-safe) body String. */
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
