import language.experimental.saferExceptions
import JsonNav.*
import sgo.{Mutex, mutex}

/** Fail-on-demand for the media edge — TEST SURFACE, absent in production.
 *
 *  {{{
 *  WATA_TEST_HOOKS=1 wata-server :8008
 *  POST /_wata/v1/test/fail  {"count": N}
 *  }}}
 *
 *  arms ONE shared counter: the next N media operations (upload or download,
 *  whichever arrive first) answer 500 `M_UNKNOWN` instead of running. That is
 *  what lets a UI test render the `SEND FAILED` / `PLAY FAILED` flashes
 *  against a real server: arm, provoke, watch the flash, and the counter
 *  disarms itself as it is consumed. Re-arming replaces the count (it does
 *  not add), and `{"count": 0}` disarms explicitly.
 *
 *  The route is REGISTERED only when `WATA_TEST_HOOKS=1` (`registerRoutes`,
 *  server.scala) and the dispatch predicate is gated the same way, so without
 *  the env var the path falls through to the Matrix 404 catch-all exactly
 *  like any unknown path — the production surface is unchanged. The endpoint
 *  is deliberately unauthenticated: it exists only inside a test harness's
 *  private server, and the harness's out-of-band arm (before any login) needs
 *  no token dance.
 *
 *  The counter lives behind its own small `Mutex` (like Config's): media
 *  requests decrement it from per-request goroutines.
 */
class HookState:
  var mediaFails: scala.Int = 0

object TestHooks:
  private val cell: Mutex[HookState] = mutex(new HookState())

  /** the gate, read per use — costs one getenv and keeps boot order out of it. */
  def enabled: Boolean = go.sys.getenv("WATA_TEST_HOOKS") == "1"

  /** `POST /_wata/v1/test/fail {"count": N}` -> `{}`. */
  def route(body: String): Either[MErr, Json] = Json.tryParse(body) match
    case Left(_)  => Left(MErr(400, M_BAD_JSON(), "Invalid JSON"))
    case Right(j) => arm(j)

  def arm(j: Json): Either[MErr, Json] =
    val n = longOrDflt(j, "count", 0L).toInt
    if n < 0 then Left(MErr(400, M_BAD_JSON(), "count must be >= 0"))
    else
      cell.withLock(st => st.mediaFails = n)
      Right(emptyObj)

  /** consume one armed failure; true = the current media op must answer 500.
   *  Always false when the hooks are disabled (the counter cannot even be
   *  armed then, but the media path should not depend on that at a distance). */
  def failMedia(): Boolean =
    if !enabled then false else cell.withLock(st => take(st))

  def take(st: HookState): Boolean =
    var out = false
    if st.mediaFails > 0 then
      st.mediaFails = st.mediaFails - 1
      out = true
    out

  /** the injected failure, one shape for both media edges. */
  def mediaErr: MErr = MErr(500, M_UNKNOWN(), "Injected media failure (test hook)")
