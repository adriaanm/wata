/** the platform capability SEAM. The portable core touches the outside world
 *  only through these two capabilities, injected at the app edge.
 *
 *  Capabilities are proper TRAITS (open, non-generic, abstract methods only).
 *  Each becomes a Go interface carrying its real method set; the app supplies
 *  a named IMPL class per capability (e.g. `FbCaps`) that proves its fields
 *  crossable where it is defined. A trait whose impl curates its own
 *  facade-handle fields keeps a function-typed field out of the picture,
 *  which would otherwise be unjudgeable by the concurrency-safety checker
 *  used here.
 *
 *  Transport/time are UNUSED by the pure sync engine (sync-response Json
 *  in -> events out) — they are DEFINED here (the seam) and CONSUMED in the
 *  runtime layer. There is deliberately NO randomness capability: the one
 *  candidate consumer, transaction ids, rides an `Atomic[Int]` counter
 *  (`Runtime.txnCounterC`) — deterministic and sufficient for a
 *  single-client process — and nothing else in the core consumes
 *  randomness.
 */

// ---- HTTP transport (request record => response record) ----------------------
case class HttpRequest(method: String, url: String, headers: List[(String, String)], body: String)
case class HttpResponse(status: Int, body: String)

/** the transport capability: perform a request, get a response. The app
 *  builds an impl over the net/http client facade; network failure is
 *  surfaced as a non-2xx `status` (the core never sees a Go `error`). */
trait HttpDo extends Shareable:
  def send(req: HttpRequest): HttpResponse

/** the wall clock (epoch ms, `origin_server_ts`-shaped) + `sleepMs`: the sync
 *  loop's backoff and the wait-helpers' poll interval need a sleep, and the
 *  portable core may not touch the time facade directly — the app supplies
 *  it, just like the clock read. */
trait Clock extends Shareable:
  def nowUnixMillis(): Long
  def sleepMs(ms: Long): Unit

/** header-list builders (the request-shaping surface; also the construction
 *  sites that record the `List[(String, String)]` instantiation — a bare
 *  case-class FIELD type alone requests no template stamp). */
object Caps:
  def noHeaders: List[(String, String)] = Nil
  def withHeader(hs: List[(String, String)], name: String, value: String): List[(String, String)] =
    (name, value) :: hs
