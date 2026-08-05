import language.experimental.saferExceptions

/** The app-edge capability impls for the phone spike: `HttpDo` over the
 *  net/http client facade, `Clock` over `go.time`. The same seam wata-fb and
 *  wata-tui supply (wataclient's capabilities.scala defines the traits),
 *  stripped to plain TCP — the spike answers a toolchain question, not a
 *  transport one.
 *
 *  Transport failure policy: a thrown `GoError` becomes `HttpResponse(0, "")`,
 *  so the portable core never sees a Go error.
 *
 *  The third capability the handle needs is `Spawner` (plan 0025): the
 *  goroutine `ClientHandle` runs its supervised scope on. */

class SpikeClock extends Clock:
  def nowUnixMillis(): Long = go.time.nowUnixMilli()
  def sleepMs(ms: Long): Unit = SpikeCaps.sleepMs(ms)

/** holds the `go.net.http.Client` handle; admissible under `Shareable`
 *  because the Client facade carries the `@sgo.curatedSafe` verdict. */
class SpikeHttp(client: go.net.http.Client) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = SpikeCaps.send(req, client)

/** the goroutine capability: one line of `go.spawn` over the core's own scope
 *  body. */
class SpikeSpawner extends Spawner:
  def runDetached(h: Handle): Unit = SpikeCaps.spawnScope(h)

object SpikeCaps:

  def clock(): Clock = SpikeClock()

  def httpDo(): HttpDo = SpikeHttp(go.net.http.DefaultClient)

  def spawner(): Spawner = SpikeSpawner()

  /** the spawn lives on the OBJECT: a lambda inside a CLASS method is lifted
   *  to a method but called as a top-level function, which does not compile
   *  (sgola ticket `CLASS-METHOD-LAMBDA-LIFT-MISMATCH`). */
  def spawnScope(h: Handle): Unit = go.spawn(() => ClientHandle.runScope(h))

  /** sleep via the timeout-channel facade (`time.After` + recv; there is no
   *  bare sleep bind). */
  def sleepMs(ms: Long): Unit =
    if ms > 0L then go.time.After(go.time.milliseconds(ms.toInt)).recv()
    ()

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

  /** "" body -> nil reader (a GET), else a strings.Reader over the body. */
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
