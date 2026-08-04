package go

import language.experimental.saferExceptions

/** `go.irohnet` — the APP-OWNED facade for the embedded iroh transport
 *  package `github.com/adriaanm/wata/go-pkgs/irohnet` (plan 0013), the same
 *  `@go.bind` pattern as `go.audio`. The client-side surface is ONE call:
 *  build an `*http.Client` whose connections are iroh bidirectional streams
 *  to the peer named in a JSON config file (peer node id, optional direct
 *  addrs, relay mode — see go-pkgs/irohnet/irohnet.go). The result is the
 *  ordinary net/http Client facade type, so `FbHttp` carries it unchanged —
 *  nothing above the capability line knows the transport changed.
 *
 *  The real transport is compiled in only on darwin with the `iroh` Go build
 *  tag (the tunnel-smoke harness builds with it); every other build gets the
 *  package's loud-error stub. */
@go.bind("github.com/adriaanm/wata/go-pkgs/irohnet")
object irohnet:
  /** `irohnet.NewHTTPClient(configPath)` — `(*http.Client, error)`. */
  @go.name("NewHTTPClient") def newHTTPClient(cfgPath: String): go.net.http.Client throws sgo.GoError = ???
