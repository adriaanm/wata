package go

import language.experimental.saferExceptions

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

  /** `irohnet.Allow(nodeId)` — admit a node id on THIS process's live listener,
   *  the enrolment approve path (plan 0021): the approved handset's next dial
   *  is accepted with no restart. The durable half of the approval is the
   *  config file's allowlist, which enroll.scala rewrites; this is only the
   *  live apply, so its failure (no iroh listener in this process, a stub
   *  build, a malformed id) is reported rather than fatal. */
  @go.name("Allow") def allow(nodeId: String): Unit throws sgo.GoError = ???

  /** `irohnet.Disallow(nodeId)` — the inverse, the enrolment revoke path
   *  (plan 0058): stop admitting the node on THIS process's live listener AND
   *  close the connections it already holds, so a revoked handset is out now,
   *  not at the next restart. The durable half is the config file's allowlist,
   *  which enroll.scala rewrites without the id; this is only the live apply,
   *  so its failure (no iroh listener in this process, a stub build, a
   *  malformed id) is reported rather than fatal. */
  @go.name("Disallow") def disallow(nodeId: String): Unit throws sgo.GoError = ???

  /** `irohnet.StripNodeID(handler)` — wrap a handler so any client-supplied
   *  `X-Wata-Node-Id` is deleted before dispatch: the TCP edge's half of the
   *  trusted-header contract (plan 0027). Untagged in the Go package, so it
   *  is real in every build, stub transport included. */
  @go.name("StripNodeID") def stripNodeId(h: go.net.http.Handler): go.net.http.Handler = ???
