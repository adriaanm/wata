// The trusted node-id header (plan 0027): how a request handler learns WHICH
// authenticated iroh peer a request came from.
//
// The accept gate verifies the remote node id before any stream is surfaced
// (rust/src/lib.rs, irohnet_server_new), and each accepted stream's net.Conn
// reports that id as its RemoteAddr. net/http copies it into
// Request.RemoteAddr, and the iroh listener bridge (Serve, irohnet_cgo.go)
// turns it into the header NodeIDHeader — after deleting any copy the client
// sent, so the value can only ever be the handshake-proven one.
//
// The contract has two halves, and BOTH are load-bearing:
//   - the iroh bridge strips any inbound NodeIDHeader and injects the
//     connection's authenticated remote id;
//   - every TCP listener serves its handler through StripNodeID, so a request
//     that arrived where peer identity cannot be proven NEVER carries the
//     header, forged or otherwise.
// A handler that sees the header may therefore treat it as proof of key
// possession — that is what /_wata/v1/device-login authenticates on, and what
// plan 0020's command mailbox is meant to authenticate on next.
//
// This file carries no build tag: the strip half must exist in stub builds
// too (a plain-TCP wata-server still has to sanitize its edge).

package irohnet

import "net/http"

// NodeIDHeader carries the authenticated remote iroh node id (lowercase hex)
// on requests that arrived over the iroh listener. Injected only by the
// listener bridge; stripped from every inbound request on both transports.
const NodeIDHeader = "X-Wata-Node-Id"

// StripNodeID wraps a handler so any client-supplied NodeIDHeader is deleted
// before dispatch — the TCP edge's half of the trusted-header contract. Every
// listener that cannot prove its peer's identity must serve through this.
func StripNodeID(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Header.Del(NodeIDHeader)
		h.ServeHTTP(w, r)
	})
}

// trustedNodeID wraps a handler for the IROH listener bridge: delete any
// inbound NodeIDHeader, then set it to the connection's authenticated remote
// id. On that listener net/http fills Request.RemoteAddr from the accepted
// Conn's RemoteAddr — the node id the accept gate verified — and a TCP-style
// host:port can never appear there, because this wrapper is only ever
// installed on the iroh http.Server (Serve).
func trustedNodeID(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Header.Del(NodeIDHeader)
		if r.RemoteAddr != "" {
			r.Header.Set(NodeIDHeader, r.RemoteAddr)
		}
		h.ServeHTTP(w, r)
	})
}
