import language.experimental.saferExceptions

/** The APP-side capability impls for the mac client: `HttpDo` over the
 *  net/http CLIENT facade and `Clock` over `go.time` — wata-tui's caps, with
 *  wata-fb's failure policy (plan 0034). Transport failure of a single
 *  request is the same everywhere: a thrown `GoError` becomes
 *  `HttpResponse(0, "")` — the portable core never sees Go errors. */

class MacClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = MacCaps.sleepMs(ms)

class MacHttp(client: go.net.http.Client) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = MacCaps.send(req, client)

object MacCaps:

  def clock(): Clock = MacClock()

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

  /** Transport selection (plan 0013, plan 0034): `WATA_IROH_CONFIG=<json>`
   *  swaps the underlying client for one whose connections are iroh streams
   *  to the configured peer — the same `go.net.http.Client` facade type, so
   *  `MacHttp` and everything above the capability line are untouched. Unset
   *  (the default) is the plain TCP client. This is the whole reason a parent
   *  AWAY from home can use the mac client at all: no port forwarding, no
   *  route to the Pi. */
  def httpDo(): HttpDo =
    val irohCfg = go.sys.getenv("WATA_IROH_CONFIG")
    if irohCfg == "" then MacHttp(go.net.http.DefaultClient)
    else MacHttp(irohClient(irohCfg))

  /** the iroh-backed client, or — on a failed init (bad config, a build
   *  without the `iroh` tag) — a loud line, a LATCHED "transport unavailable"
   *  state the boot screen names outright, and a client that can only fail
   *  (there is no os.Exit facade).
   *
   *  This follows wata-fb rather than wata-tui deliberately. The tui
   *  downgrades to the plain client and prints a line, which is fine for an
   *  operator reading scrollback; a GUI client that downgrades silently sits
   *  on "waiting for network" forever against a transport that was never
   *  going to come up. The mac runs wata-fb's screens, so it gets wata-fb's
   *  honest answer. */
  def irohClient(cfgPath: String): go.net.http.Client =
    var out = go.net.http.DefaultClient
    try out = go.irohnet.newHTTPClient(cfgPath)
    catch case e: sgo.GoError => noteTransportDown(e.message)
    out

  def noteTransportDown(msg: String): Unit =
    println("irohnet: client init failed: " + msg)
    transportDownC.set(true)

  // the transport's own verdict: set once, at client construction, when the
  // configured transport could not be brought up at all. Both drivers build
  // the client (`Pump.startAudioClient`) on the goroutine that then starts the
  // pump, so the write happens-before every frame's read.
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
