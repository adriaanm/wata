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

  def httpDo(): HttpDo = FbHttp(go.net.http.DefaultClient)

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

  /** "" body -> nil reader (a GET; the httpscenario `newRequest(_, _, null)`
   *  shape), else a strings.Reader over the (binary-safe) body String. */
  def bodyReader(req: HttpRequest): go.io.Reader =
    if req.body == "" then null
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
