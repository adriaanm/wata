import language.experimental.saferExceptions

/** The APP-side capability impls for the tui: `HttpDo` over the net/http
 *  CLIENT facade, `Clock` over `go.time` — the same seam wata-fb supplies
 *  (capabilities.scala in wataclient defines the traits). There is no audio
 *  capability: the tui hands downloaded Ogg bytes to an external player
 *  process instead of driving hardware, so the client runs headless
 *  (`Runtime.make`, not `makeWithAudio`).
 *
 *  Transport failure policy: a thrown `GoError` becomes `HttpResponse(0, "")`
 *  — the portable core never sees Go errors. */

/** the app-edge `Clock` impl over `go.time` (a named class, no fields). */
class TuiClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = TuiCaps.sleepMs(ms)

/** the app-edge `HttpDo` impl. `HttpDo` extends `Shareable`, so this impl's
 *  shareability is checked here: it holds the `go.net.http.Client` handle,
 *  admissible because the Client facade carries the `@sgo.curatedSafe`
 *  verdict. */
class TuiHttp(client: go.net.http.Client) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = TuiCaps.send(req, client)

object TuiCaps:

  def clock(): Clock = TuiClock()

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

  /** Transport selection (plan 0013): `WATA_IROH_CONFIG=<json>` swaps the
   *  underlying client for one whose connections are iroh streams to the
   *  configured peer — same `go.net.http.Client` facade type, so nothing above
   *  the capability line changes. Unset (the default) is the plain
   *  DefaultClient. This is what makes the tui a second iroh client for free. */
  def httpDo(): HttpDo =
    val irohCfg = go.sys.getenv("WATA_IROH_CONFIG")
    if irohCfg == "" then TuiHttp(go.net.http.DefaultClient)
    else TuiHttp(irohClient(irohCfg))

  /** the iroh-backed client, or — on a failed init (bad config, stub build) —
   *  a loud line + the DefaultClient, whose requests to the placeholder iroh
   *  base URL then fail visibly (there is no os.Exit facade). */
  def irohClient(cfgPath: String): go.net.http.Client =
    var out = go.net.http.DefaultClient
    try out = go.irohnet.newHTTPClient(cfgPath)
    catch case e: sgo.GoError =>
      println("irohnet: client init failed: " + e.message)
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
