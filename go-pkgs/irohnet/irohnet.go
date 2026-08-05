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
// The REAL implementation builds with the `iroh` build tag on the wired
// targets — darwin (staticlib: ./mklib.py) and linux/arm, the BQ268 device
// cross-build (staticlib: ./mklib.py arm); everywhere else a pure-Go stub
// errors loudly, so ordinary builds (including the linux/amd64
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
//   - A dial refused by the peer (e.g. the server's allowlist gate closing
//     with 401 "not allowlisted") is loud, not a GAP: the Rust layer
//     (irohnet_client_dial) matches iroh's ConnectionError::ApplicationClosed
//     on both the cached-connection and fresh-connect paths and formats
//     "server refused: <code> <reason>"; Dialer.DialContext logs that once
//     per distinct reason (irohnet_cgo.go, logDialError) before the error
//     reaches the HttpDo capability, which still folds it into
//     HttpResponse(0, "") — the portable core never sees a Go error, but the
//     reason is on stdout/stderr for whoever runs the process.
//   - A refusal is loud but never TERMINAL. The refused connection is cached
//     and answers dials locally for REFUSAL_COOLDOWN (rust/src/lib.rs), then
//     one fresh handshake is attempted — so a node approved mid-session gets
//     in on its next retries, without a new dialer or a restarted process
//     (TestRefusedClientRedialsAfterAllow). The first dial that succeeds
//     clears LastRefusal, which is what takes the device's enrolment QR down.
package irohnet

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
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
	// AdminURL (device) is the base URL of the admin interface the
	// enrolment QR encodes ("http://192.168.1.4:8008" today, a domain
	// later). Nothing in this package uses it — it rides here because the
	// device's whole iroh provisioning is one file, and wata-fb reads the
	// field itself (WATA_ADMIN_URL overrides). It is CONFIG: the device
	// cannot derive the address a parent's phone can reach.
	AdminURL string `json:"adminUrl"`
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

// EnsureKey gives the config at path a node key of its OWN and reports the
// resulting node id — the device-minted identity of plan 0014 milestone 1.
//
// A handset is flashed with a config that names the family's server and NO
// secret: the key is minted here, on the device, on first boot, and never
// travels. Enrolment is then an approval of a public id rather than a delivery
// of a secret, which is the whole point of that plan's reframing.
//
// Idempotent: a config that already carries a secretKey is left alone and only
// its id is derived. The rewrite preserves every other field (it edits the
// parsed JSON object rather than re-marshalling Config, so an adminUrl, a
// peerAddrs list or a field this package does not know survives), goes through
// a temp file + rename in the same directory, and lands 0600 — the file holds
// the node's secret.
func EnsureKey(path string) (string, error) {
	raw, e := os.ReadFile(path)
	if e != nil {
		return "", fmt.Errorf("irohnet: config: %w", e)
	}
	var obj map[string]json.RawMessage
	if e := json.Unmarshal(raw, &obj); e != nil {
		return "", fmt.Errorf("irohnet: config %s: %w", path, e)
	}
	var secret string
	if v, ok := obj["secretKey"]; ok {
		_ = json.Unmarshal(v, &secret)
	}
	if secret != "" {
		return IDOf(secret)
	}
	secret, id, e := GenKey()
	if e != nil {
		return "", e
	}
	enc, e := json.Marshal(secret)
	if e != nil {
		return "", e
	}
	obj["secretKey"] = enc
	body, e := json.MarshalIndent(obj, "", "  ")
	if e != nil {
		return "", e
	}
	tmp := path + ".tmp"
	if e := os.WriteFile(tmp, append(body, '\n'), 0o600); e != nil {
		return "", fmt.Errorf("irohnet: minting a key into %s: %w", path, e)
	}
	if e := os.Rename(tmp, path); e != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("irohnet: minting a key into %s: %w", path, e)
	}
	return id, nil
}

// refusal is the most recent reason a dial FAILED, recorded package-wide by
// Dialer.logDialError alongside the line it prints. A refusal by the peer
// formats as "server refused: <code> <reason>" (irohnet_client_dial), so
// "not allowlisted" is a substring test away; anything else here is an
// ordinary transport failure. The distinction is the transport's own and never
// reaches the portable client core (the HttpDo capability folds every Go error
// into HttpResponse(0, "")), so this is the only way the app edge can tell a
// device that is NOT ENROLLED from one whose network is merely down — the
// difference between showing a QR code and showing "waiting for network".
var (
	refusalMu sync.Mutex
	refusal   string
)

func noteRefusal(reason string) {
	refusalMu.Lock()
	defer refusalMu.Unlock()
	refusal = reason
}

// clearRefusal drops the standing reason once a dial gets through. A refusal
// is loud but never terminal: the enrolment flow's promise is "approve, and it
// connects", so the moment a dial succeeds the QR screen that reads this must
// yield to the ordinary boot flow. A refusal that outran the dial re-records
// itself on the first read/write of the stream (Conn.errForRet).
func clearRefusal() {
	refusalMu.Lock()
	defer refusalMu.Unlock()
	refusal = ""
}

// LastRefusal is the most recent dial-failure reason seen by any dialer in
// this process, or "" if none — cleared by the first dial that succeeds. It is
// the app edge's only signal for "this node is not enrolled" (the portable
// core folds every transport error into HttpResponse(0, "")), so it has to
// answer the CURRENT state, not the session's history.
func LastRefusal() string {
	refusalMu.Lock()
	defer refusalMu.Unlock()
	return refusal
}

// Addr is the net.Addr both sides report: network "iroh", the node id as the
// string form.
type Addr struct{ ID string }

func (a Addr) Network() string { return "iroh" }
func (a Addr) String() string  { return a.ID }

// announce writes the listener's identity for a harness to pick up:
// {"id": "<hex>", "addrs": ["127.0.0.1:52011", "192.168.1.4:52011", ...]}.
// The endpoint's LOCAL sockets usually bind an unspecified host; that is
// expanded to every dialable form — loopback first (the same-machine tunnel
// smoke) and then each non-loopback unicast interface address (a LAN peer's
// dialable form; the on-device smoke filters for these). Written via rename
// so a polling reader never sees a partial file.
func announce(path, id string, addrs []string) error {
	dialable := make([]string, 0, len(addrs))
	for _, a := range addrs {
		host, port, e := net.SplitHostPort(a)
		if e != nil {
			continue
		}
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsUnspecified() {
			dialable = append(dialable, a)
			continue
		}
		v6 := strings.Contains(host, ":")
		if v6 {
			dialable = append(dialable, net.JoinHostPort("::1", port))
		} else {
			dialable = append(dialable, net.JoinHostPort("127.0.0.1", port))
		}
		for _, ifip := range interfaceIPs(v6) {
			dialable = append(dialable, net.JoinHostPort(ifip, port))
		}
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

// interfaceIPs lists the host's non-loopback unicast addresses of one family
// (v6 selects IPv6, else IPv4), link-local excluded — the addresses a LAN
// peer can dial. Best-effort: enumeration failure is an empty list.
func interfaceIPs(v6 bool) []string {
	var out []string
	ifaddrs, e := net.InterfaceAddrs()
	if e != nil {
		return out
	}
	for _, ia := range ifaddrs {
		ipn, ok := ia.(*net.IPNet)
		if !ok {
			continue
		}
		ip := ipn.IP
		if ip.IsLoopback() || ip.IsLinkLocalUnicast() || !ip.IsGlobalUnicast() {
			continue
		}
		if (ip.To4() == nil) == v6 {
			out = append(out, ip.String())
		}
	}
	return out
}
