/** M8 chunk 3 — the platform capability SEAM (decision 1). The portable core
 *  touches the outside world only through these three capabilities, injected at
 *  the app edge (LANGUAGE.md §capabilities).
 *
 *  M9 chunk 4t: capabilities are proper TRAITS (open, non-generic, abstract
 *  methods only — the emitter's non-generic-trait lowering, added this chunk).
 *  Each becomes a Go interface carrying its real method set; the app supplies a
 *  named IMPL class per capability (FbCaps) that PROVES its fields where it is
 *  defined — CONC-7's impl-site story. The M8 record-of-closures shape was
 *  EMITTER-FORCED (there was no lowering for non-generic traits then), not
 *  chosen; its cost surfaced at the M9 ch.4a dry-run — a function-typed field is
 *  unjudgeable by the local crossing predicate by design. A trait whose impl
 *  curates its facade-handle fields is the cleaner route (Adriaan, ch.4a
 *  boundary). The impls do NOT yet extend `Shareable` — that flip (an impl
 *  holding net/http facade handles passing CONC-7) waits on ch.6's
 *  curated-facade disjunct.
 *
 *  Transport/time/rand are UNUSED by chunk 3's pure sync engine (sync-response
 *  Json in -> events out) — they are DEFINED here (the seam) and CONSUMED in
 *  chunk 4's runtime layer.
 */

// ---- HTTP transport (request record => response record) ----------------------
case class HttpRequest(method: String, url: String, headers: List[(String, String)], body: String)
case class HttpResponse(status: Int, body: String)

/** the transport capability: perform a request, get a response. The app builds
 *  an impl over the net/http client facade (chunk 4); network failure is
 *  surfaced as a non-2xx `status` (the core never sees a Go `error`). */
trait HttpDo extends Shareable:
  def send(req: HttpRequest): HttpResponse

/** the wall clock (epoch ms, `origin_server_ts`-shaped) + `sleepMs` (M8
 *  chunk 4: the sync loop's backoff and the wait-helpers' poll interval need a
 *  sleep, and the portable core may not touch the time facade — the app supplies
 *  it like the clock read; sync_thread.zig's `sleepMs(io, ms)` seam). */
trait Clock extends Shareable:
  def nowUnixMillis(): Long
  def sleepMs(ms: Long): Unit

/** randomness for transaction ids / device ids. No corpus consumer today
 *  (transaction ids ride the `Atomic[Int]` counter); migrated for uniformity —
 *  an impl would live at the app edge (FbCaps) over the rand facade, like the
 *  `Clock` impl. */
trait Rand extends Shareable:
  def nextU32(): Int

/** header-list builders (the chunk-4 request-shaping surface; ALSO the
 *  construction sites that record the `List[(String, String)]` instantiation —
 *  a bare case-class FIELD type alone requests no template stamp, see the
 *  chunk-3 DOGFOOD note). */
object Caps:
  def noHeaders: List[(String, String)] = Nil[(String, String)]()
  def withHeader(hs: List[(String, String)], name: String, value: String): List[(String, String)] =
    (name, value) :: hs
