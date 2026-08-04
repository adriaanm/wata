// Package irohnet is the hand-written cgo facade target for wata's embedded
// iroh transport (plan 0013): both apps ARE iroh endpoints — no sidecar
// process, no TCP port. The package wraps a vendored Rust staticlib
// (rust/, crate irohnet-ffi, iroh pinned in rust/Cargo.lock) behind honest
// net.Listener / net.Conn glue:
//
//   - Listen(cfg) — a net.Listener whose Accept yields one net.Conn per
//     incoming iroh bidirectional stream, with the node-id allowlist checked
//     at accept (in Rust, before any stream is surfaced).
//   - NewHTTPClient(path) — an *http.Client whose transport dials the
//     configured peer over iroh (one bidi stream per pooled connection).
//   - Serve(path, handler) — config-driven http.Server.Serve over Listen.
//
// The REAL implementation builds only on darwin with the `iroh` build tag
// (milestone 1 — build the staticlib first with ./mklib.py); everywhere else
// a pure-Go stub errors loudly, so ordinary builds (including the linux/amd64
// CGO_ENABLED=0 cross-build) never need cargo or the lib.
//
// net.Conn semantics, precisely (the honest-gaps ledger):
//   - Read/Write/Close and all three deadline setters work as net.Conn
//     documents them, including interrupting a BLOCKED Read/Write when a
//     deadline is moved into the past (net/http relies on that). Deadline
//     errors satisfy net.Error with Timeout() == true.
//   - Close finishes the send side (the peer reads EOF) and stops the
//     receive side; it unblocks pending I/O (net.ErrClosed).
//   - GAP: no CloseRead/CloseWrite half-close methods (net/http does not
//     need them; net.Conn does not require them).
//   - GAP: stream errors are collapsed to one generic error — the QUIC
//     error detail stays in the server log, not in the returned error.
//   - GAP: DialContext honors ctx as a timeout only; mid-dial cancellation
//     does not interrupt the in-flight C call (it is abandoned server-side
//     at the QUIC layer instead).
package irohnet

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
)

// Config is the per-node JSON config (the enrollment file format of plan
// 0013; `just iroh-enroll` is a follow-up). One schema for both sides; each
// side reads the fields it needs.
type Config struct {
	// SecretKey is the node's ed25519 secret key, lowercase hex (64 chars).
	// Empty means an ephemeral key (fine for a client; a server wants a
	// stable one, or its id — what peers provision — changes every boot).
	SecretKey string `json:"secretKey"`
	// Relay selects connectivity: "n0" (default — n0's relays + address
	// lookup; real network use) or "none" (direct addresses only; the
	// no-network tunnel smoke).
	Relay string `json:"relay"`
	// Allowlist (server) holds the peer node ids admitted at accept.
	// The single entry "*" admits any peer; an empty list refuses all.
	Allowlist []string `json:"allowlist"`
	// Peer (client) is the server's node id (hex or z32).
	Peer string `json:"peer"`
	// PeerAddrs (client) optionally lists the server's direct socket
	// addresses ("127.0.0.1:52011"); required when Relay is "none".
	PeerAddrs []string `json:"peerAddrs"`
	// AnnounceFile (server) — when set, the listener writes
	// {"id":..., "addrs":[...]} there once bound (the smoke harness reads
	// it to provision the client).
	AnnounceFile string `json:"announceFile"`
}

// LoadConfig reads and parses the JSON config at path.
func LoadConfig(path string) (*Config, error) {
	raw, e := os.ReadFile(path)
	if e != nil {
		return nil, fmt.Errorf("irohnet: config: %w", e)
	}
	var c Config
	if e := json.Unmarshal(raw, &c); e != nil {
		return nil, fmt.Errorf("irohnet: config %s: %w", path, e)
	}
	if c.Relay == "" {
		c.Relay = "n0"
	}
	return &c, nil
}

// Addr is the net.Addr both sides report: network "iroh", the node id as the
// string form.
type Addr struct{ ID string }

func (a Addr) Network() string { return "iroh" }
func (a Addr) String() string  { return a.ID }

// announce writes the listener's identity for a harness to pick up:
// {"id": "<hex>", "addrs": ["127.0.0.1:52011", ...]} — unspecified hosts
// rewritten to loopback (the addrs are the endpoint's LOCAL sockets; for the
// same-machine smoke that is the dialable form). Written via rename so a
// polling reader never sees a partial file.
func announce(path, id string, addrs []string) error {
	dialable := make([]string, 0, len(addrs))
	for _, a := range addrs {
		host, port, e := net.SplitHostPort(a)
		if e != nil {
			continue
		}
		ip := net.ParseIP(host)
		if ip != nil && ip.IsUnspecified() {
			if strings.Contains(host, ":") {
				host = "::1"
			} else {
				host = "127.0.0.1"
			}
		}
		dialable = append(dialable, net.JoinHostPort(host, port))
	}
	blob, e := json.Marshal(map[string]any{"id": id, "addrs": dialable})
	if e != nil {
		return e
	}
	tmp := path + ".tmp"
	if e := os.WriteFile(tmp, blob, 0o644); e != nil {
		return e
	}
	return os.Rename(tmp, path)
}
