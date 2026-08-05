package go

import language.experimental.saferExceptions

/** `go.httpc` — the APP-OWNED facade for `go-pkgs/httpc`, the same `@go.bind`
 *  pattern as `go.irohnet`/`go.audio`. One thing lives there: an
 *  `*http.Client` carrying a per-request `Timeout`, which the bound net/http
 *  facade cannot express (it binds the Client type, not its fields, and
 *  `DefaultClient` has no timeout at all). Both results are the ordinary
 *  `go.net.http.Client` facade type, so nothing above the capability line
 *  changes. */
@go.bind("github.com/adriaanm/wata/go-pkgs/httpc")
object httpc:
  /** `httpc.New(timeoutMs)` — a plain-TCP client bounded per request. */
  @go.name("New") def newClient(timeoutMs: Long): go.net.http.Client = ???
  /** `httpc.WithTimeout(c, timeoutMs)` — the same bound over an existing
   *  client's transport (the iroh one). */
  @go.name("WithTimeout") def withTimeout(c: go.net.http.Client, timeoutMs: Long): go.net.http.Client = ???
