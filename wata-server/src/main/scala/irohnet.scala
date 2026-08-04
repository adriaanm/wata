package go

/** `go.irohnet` — the APP-OWNED facade for the embedded iroh transport
 *  package `github.com/adriaanm/wata/go-pkgs/irohnet` (plan 0013). Lives in
 *  the wata-server module, not in core, exactly like wata-fb's `go.audio`:
 *  the binding is `@go.bind`-annotation-driven and the package rides the
 *  emitted app module as a plain Go dependency (require + local replace,
 *  written by the sgo go.mod stage).
 *
 *  The server-side surface is ONE call: serve the given handler over an iroh
 *  listener built from a JSON config file (node key, allowlist, relay mode —
 *  see the package doc in go-pkgs/irohnet/irohnet.go). No TCP port exists in
 *  that mode. Returns only on a terminal error, like `ListenAndServe`.
 *
 *  The real transport is compiled in only on darwin with the `iroh` Go build
 *  tag (the tunnel-smoke harness builds with it); every other build gets the
 *  package's loud-error stub, so this facade adds no cargo/rust requirement
 *  to ordinary builds. */
@go.bind("github.com/adriaanm/wata/go-pkgs/irohnet")
object irohnet:
  /** `irohnet.Serve(configPath, handler)` — `error`-returning, blocking. */
  @go.name("Serve") def serve(cfgPath: String, handler: go.net.http.Handler): sgo.GoError = ???
