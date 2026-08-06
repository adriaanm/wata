import language.experimental.saferExceptions

/** The APP-side capability impls for the mac client: `HttpDo` over the
 *  net/http CLIENT facade and `Clock` over `go.time` — wata-tui's caps with
 *  the iroh branch removed (plan 0032: TCP first; `IROH-APPLE` is its own
 *  queue item). Transport failure policy is the same: a thrown `GoError`
 *  becomes `HttpResponse(0, "")` — the portable core never sees Go errors. */

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

  def httpDo(): HttpDo = MacHttp(go.net.http.DefaultClient)

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
