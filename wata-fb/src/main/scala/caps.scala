import language.experimental.saferExceptions

/** M8 chunk 4 — the APP-side capability constructors: `HttpDo` over the
 *  net/http CLIENT facade, `Clock` over `go.time`. This is the seam's other
 *  half (capabilities.scala defines the TRAITS; the app supplies the impls).
 *
 *  M9 chunk 4t: the capabilities are now proper traits, so the app edge supplies
 *  a NAMED impl class per capability (`FbClock`/`FbHttp`) instead of a record of
 *  closures. Go structural typing makes each `*FbClock` / `*FbHttp` satisfy the
 *  interface — no `implements`, no vtable. The impls carry no fields yet (they
 *  reach the `go.*` facades directly); ch.6 curation admits the facade-handle
 *  fields (and the `Shareable` flip).
 *
 *  Client facade surface used (all M7 chunk 0 binds except ReadCloser):
 *  `newRequest` (method/URL/body-reader), `Request.header.set` (headers in),
 *  `DefaultClient.Do`, `Response.statusCode` / `Response.body`, `io.readAll`
 *  (body out), and the NEW `go.io.ReadCloser` — `Response.body` retyped from
 *  Reader so the long-poll loop can `close()` each body (connection reuse).
 *
 *  Transport failure policy (capabilities.scala doc): a thrown `GoError`
 *  becomes `HttpResponse(0, "")` — the portable core never sees Go errors. */
/** the app-edge `Clock` impl over `go.time` (M9 ch.4t: a named class, no
 *  fields). */
class FbClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = FbCaps.sleepMs(ms)

/** the app-edge `HttpDo` impl over the net/http CLIENT facade. M9 ch.6: HttpDo
 *  now extends `Shareable`, so this impl is checked at its definition site
 *  (CONC-7). It HOLDS the `go.net.http.Client` handle as a CONSTRUCTOR val-param
 *  field (the M10 ch.5 val-param path — a body-val initializer would be dropped
 *  by the open-iface-impl construction literal, now walled loud in the emitter).
 *  Admissible because the Client facade carries the `@sgo.curatedSafe` verdict
 *  ("Clients are safe for concurrent use by multiple goroutines"), the fourth
 *  disjunct of the crossing predicate. This proves the curated-facade-handle
 *  admission for real (the caps.scala doc promised exactly this). */
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
