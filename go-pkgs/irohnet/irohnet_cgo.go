//go:build iroh && (darwin || (linux && arm))

// The real implementation: cgo over the vendored Rust staticlib (rust/,
// crate irohnet-ffi). Build the lib first: ./mklib.py for the host
// (clib/darwin/), ./mklib.py arm for the device cross-build
// (clib/linux_arm/, armv7-unknown-linux-musleabihf); clib/ is a build
// artifact, gitignored. Building with `-tags iroh` and no staged lib fails
// loudly at link time — the stub covers only builds without the tag.
//
// Blocking model: every C call that can block parks the calling goroutine on
// the lib's internal tokio runtime; Go's scheduler is unaffected (cgo calls
// release the P). Deadlines and Close interrupt blocked calls from another
// goroutine — see the package header for the exact net.Conn semantics.

package irohnet

/*
#cgo darwin,!ios LDFLAGS: -L${SRCDIR}/clib/darwin -lirohnet_ffi -framework Security -framework SystemConfiguration -framework CoreFoundation -framework CoreWLAN
#cgo ios LDFLAGS: -L${SRCDIR}/clib/ios_active -lirohnet_ffi -framework Security -framework SystemConfiguration -framework CoreFoundation -framework Network
#cgo linux,arm LDFLAGS: -L${SRCDIR}/clib/linux_arm -lirohnet_ffi -lunwind

// ios: GOOS=ios also carries the darwin tag, so the mac line is fenced
// !ios (CoreWLAN does not exist on iOS). clib/ios_active holds whichever
// of the device/simulator archives the harness staged via
// `./mklib.py activate ios|ios-sim` — cgo tags cannot tell those apart.

// -lunwind: Rust std's panic machinery references _Unwind_* — on the armv7
// musl cross-link `zig cc` (the device C toolchain) satisfies it with its
// bundled LLVM libunwind, statically. Darwin gets unwinding from libSystem.

#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>

// irohnet-ffi (rust/src/lib.rs). Stream I/O return codes:
//   >= 0 bytes, -1 EOF, -2 deadline, -3 closed locally, -4 stream error.
extern void irohnet_gen_key(char* secret_out, size_t secret_cap, char* id_out, size_t id_cap);
extern int32_t irohnet_id_of(const char* secret_hex, char* id_out, size_t id_cap);
extern int32_t irohnet_server_new(const char* secret_hex, const char* allowlist_csv, const char* relay,
                                  uint64_t* out_handle, char* err_out, size_t err_cap);
extern int32_t irohnet_server_id(uint64_t h, char* out, size_t cap);
extern int32_t irohnet_server_addrs(uint64_t h, char* out, size_t cap);
extern int32_t irohnet_server_accept(uint64_t h, uint64_t* out_stream, char* remote_out, size_t remote_cap,
                                     char* err_out, size_t err_cap);
extern int32_t irohnet_server_allow(uint64_t h, const char* node_id, char* err_out, size_t err_cap);
extern int32_t irohnet_server_disallow(uint64_t h, const char* node_id, char* err_out, size_t err_cap);
extern void irohnet_server_close(uint64_t h);
extern int32_t irohnet_client_new(const char* secret_hex, const char* peer_id, const char* peer_addrs_csv,
                                  const char* relay, uint64_t* out_handle, char* err_out, size_t err_cap);
extern int32_t irohnet_client_dial(uint64_t h, int64_t timeout_ms, uint64_t* out_stream,
                                   char* err_out, size_t err_cap);
extern int32_t irohnet_client_last_refusal(uint64_t h, char* out, size_t cap);
extern int64_t irohnet_client_rebuilds(uint64_t h);
extern void irohnet_client_close(uint64_t h);
extern void irohnet_stream_set_deadlines(uint64_t h, int64_t read_ms, int64_t write_ms);
extern int64_t irohnet_stream_read(uint64_t h, unsigned char* buf, size_t cap);
extern int64_t irohnet_stream_write(uint64_t h, const unsigned char* buf, size_t len);
extern void irohnet_stream_close(uint64_t h);
*/
import "C"

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"
)

const errCap = 512

// timeoutError satisfies net.Error with Timeout() true — what net/http and
// io deadlines expect from a conn.
type timeoutError struct{}

func (timeoutError) Error() string   { return "irohnet: deadline exceeded" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

var errStream = errors.New("irohnet: stream reset or connection lost")

func retErr(ret int64) error {
	switch ret {
	case -1:
		return io.EOF
	case -2:
		return timeoutError{}
	case -3:
		return net.ErrClosed
	default:
		return errStream
	}
}

// GenKey generates a fresh node key; returns (secretHex, idHex).
func GenKey() (string, string, error) {
	sec := make([]byte, 128)
	id := make([]byte, 128)
	C.irohnet_gen_key((*C.char)(unsafe.Pointer(&sec[0])), C.size_t(len(sec)),
		(*C.char)(unsafe.Pointer(&id[0])), C.size_t(len(id)))
	return cstr(sec), cstr(id), nil
}

// IDOf derives the node id of an existing secret key (lowercase hex), so a
// device that minted its key on an earlier boot can still say who it is
// without touching the file again.
func IDOf(secretHex string) (string, error) {
	sec := C.CString(secretHex)
	defer C.free(unsafe.Pointer(sec))
	id := make([]byte, 128)
	if C.irohnet_id_of(sec, (*C.char)(unsafe.Pointer(&id[0])), 128) != 0 {
		return "", errors.New("irohnet: the configured secretKey is not a valid node key")
	}
	return cstr(id), nil
}

func cstr(b []byte) string {
	for i, c := range b {
		if c == 0 {
			return string(b[:i])
		}
	}
	return string(b)
}

// ---- conn -------------------------------------------------------------------

// Conn is one iroh bidirectional stream as a net.Conn. dialer is set only for
// client-side conns (Dialer.DialContext); it is what lets a stream I/O
// failure recover a peer-initiated refusal reason (see errForRet) — opening
// a stream is local-only, so a dial can succeed against a connection the
// peer is already closing, and the refusal only surfaces on the first
// read/write.
type Conn struct {
	h         C.uint64_t
	local     Addr
	remote    Addr
	closeOnce sync.Once
	dialer    *Dialer
	// opTimeout bounds each individual Read/Write (0 = unbounded). Set on
	// CLIENT conns only: the dial already has a 30s bound, and without a
	// post-dial one a peer that opened the stream and then went quiet leaves
	// the caller blocked forever — the same wedge the plain-TCP path fixes
	// with http.Client.Timeout. The accept side deliberately keeps 0: a
	// server's read between requests is legitimately idle for as long as the
	// client is quiet.
	opTimeout time.Duration
	// the deadlines a CALLER set explicitly (SetDeadline & friends), epoch
	// millis, 0 = none. They win over opTimeout whenever they are the
	// earlier bound, so this default never weakens what a caller asked for
	// (and never overrides the net.Conn contract the tests pin).
	dlMu    sync.Mutex
	readDl  int64
	writeDl int64
}

// opTimeoutMs is the per-read/write bound client conns carry: 30s, or
// WATA_HTTP_TIMEOUT_MS when set (the same knob the plain-TCP client reads, so
// a test can shrink both transports' deadlines together).
func opTimeoutMs() time.Duration {
	if v := os.Getenv("WATA_HTTP_TIMEOUT_MS"); v != "" {
		if ms, e := strconv.ParseInt(v, 10, 64); e == nil && ms > 0 {
			return time.Duration(ms) * time.Millisecond
		}
	}
	return 30 * time.Second
}

// armOp starts this operation's deadline. A fresh one per call, so a healthy
// long-poll (many short reads) is never cut off, while a single read against a
// peer that has gone silent fails within the bound and becomes a failed sync
// round the client's backoff already knows how to handle. An idle keep-alive
// conn's background read hits it too, which retires the conn from the
// transport's pool — cheaper than the wedge it prevents.
func (c *Conn) armOp() {
	if c.opTimeout <= 0 {
		return
	}
	own := time.Now().Add(c.opTimeout).UnixMilli()
	c.dlMu.Lock()
	r, w := earlier(c.readDl, own), earlier(c.writeDl, own)
	c.dlMu.Unlock()
	C.irohnet_stream_set_deadlines(c.h, C.int64_t(r), C.int64_t(w))
}

// earlier picks the binding deadline: a caller's explicit one when it is
// sooner (0 = the caller set none).
func earlier(user, own int64) int64 {
	if user > 0 && user < own {
		return user
	}
	return own
}

// errForRet turns a stream return code into an error, using the owning
// dialer's client handle (if any) to recover a peer refusal reason that
// outran the dial (irohnet_client_last_refusal, go-pkgs/irohnet/rust/src/lib.rs)
// instead of the generic "stream reset or connection lost".
func (c *Conn) errForRet(ret int64) error {
	if ret == -4 && c.dialer != nil {
		buf := make([]byte, errCap)
		if C.irohnet_client_last_refusal(c.dialer.h, (*C.char)(unsafe.Pointer(&buf[0])), errCap) == 0 {
			reason := cstr(buf)
			c.dialer.logDialError(reason)
			return fmt.Errorf("irohnet: dial %s: %s", c.dialer.peer, reason)
		}
	}
	return retErr(ret)
}

func (c *Conn) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	c.armOp()
	ret := int64(C.irohnet_stream_read(c.h, (*C.uchar)(unsafe.Pointer(&b[0])), C.size_t(len(b))))
	if ret >= 0 {
		return int(ret), nil
	}
	return 0, c.errForRet(ret)
}

func (c *Conn) Write(b []byte) (int, error) {
	c.armOp()
	written := 0
	for written < len(b) {
		p := b[written:]
		ret := int64(C.irohnet_stream_write(c.h, (*C.uchar)(unsafe.Pointer(&p[0])), C.size_t(len(p))))
		if ret < 0 {
			return written, c.errForRet(ret)
		}
		written += int(ret)
	}
	return written, nil
}

func (c *Conn) Close() error {
	c.closeOnce.Do(func() { C.irohnet_stream_close(c.h) })
	return nil
}

func deadlineMs(t time.Time) C.int64_t {
	if t.IsZero() {
		return 0 // none
	}
	ms := t.UnixMilli()
	if ms <= 0 {
		ms = 1 // far past: any positive value already elapsed
	}
	return C.int64_t(ms)
}

func (c *Conn) SetDeadline(t time.Time) error {
	c.rememberDeadline(t, true, true)
	C.irohnet_stream_set_deadlines(c.h, deadlineMs(t), deadlineMs(t))
	return nil
}

func (c *Conn) SetReadDeadline(t time.Time) error {
	c.rememberDeadline(t, true, false)
	C.irohnet_stream_set_deadlines(c.h, deadlineMs(t), -1)
	return nil
}

func (c *Conn) SetWriteDeadline(t time.Time) error {
	c.rememberDeadline(t, false, true)
	C.irohnet_stream_set_deadlines(c.h, -1, deadlineMs(t))
	return nil
}

// rememberDeadline records what the caller asked for, so the next armOp
// (which re-arms the stream for its own bound) cannot silently relax it.
func (c *Conn) rememberDeadline(t time.Time, read, write bool) {
	ms := int64(0)
	if !t.IsZero() {
		ms = t.UnixMilli()
		if ms <= 0 {
			ms = 1 // far past: any positive value has already elapsed
		}
	}
	c.dlMu.Lock()
	if read {
		c.readDl = ms
	}
	if write {
		c.writeDl = ms
	}
	c.dlMu.Unlock()
}

func (c *Conn) LocalAddr() net.Addr  { return c.local }
func (c *Conn) RemoteAddr() net.Addr { return c.remote }

// ---- listener ---------------------------------------------------------------

// Listener is the iroh accept side as a net.Listener.
type Listener struct {
	h         C.uint64_t
	id        string
	addrs     []string
	closeOnce sync.Once
}

// Listen binds the server endpoint from cfg and starts accepting; the
// allowlist is enforced at accept, before any stream is surfaced. An EMPTY
// allowlist listens and refuses every peer — the bootstrap state of a fresh
// install, from which enrolment approvals admit nodes live (Allow) — while
// the single entry "*" admits any peer. If cfg.AnnounceFile is set, the
// listener's id + dialable local addrs are written there.
func Listen(cfg *Config) (*Listener, error) {
	secret := C.CString(cfg.SecretKey)
	allow := C.CString(strings.Join(cfg.Allowlist, ","))
	relay := C.CString(cfg.Relay)
	defer C.free(unsafe.Pointer(secret))
	defer C.free(unsafe.Pointer(allow))
	defer C.free(unsafe.Pointer(relay))
	var h C.uint64_t
	errBuf := make([]byte, errCap)
	if C.irohnet_server_new(secret, allow, relay, &h,
		(*C.char)(unsafe.Pointer(&errBuf[0])), errCap) != 0 {
		return nil, fmt.Errorf("irohnet: listen: %s", cstr(errBuf))
	}
	idBuf := make([]byte, 128)
	C.irohnet_server_id(h, (*C.char)(unsafe.Pointer(&idBuf[0])), 128)
	addrBuf := make([]byte, 1024)
	C.irohnet_server_addrs(h, (*C.char)(unsafe.Pointer(&addrBuf[0])), 1024)
	l := &Listener{h: h, id: cstr(idBuf)}
	for _, a := range strings.Split(cstr(addrBuf), ",") {
		if a != "" {
			l.addrs = append(l.addrs, a)
		}
	}
	if cfg.AnnounceFile != "" {
		if e := announce(cfg.AnnounceFile, l.id, l.addrs); e != nil {
			l.Close()
			return nil, fmt.Errorf("irohnet: announce: %w", e)
		}
	}
	setActive(l)
	return l, nil
}

// active is the listener this process is serving on, so the package-level
// Allow/Disallow can reach it without threading a handle through the app: a
// wata-server serves exactly one iroh listener (Serve builds it internally),
// and its admin enrolment endpoints (plan 0021) need to mutate that
// listener's allowlist from an HTTP handler.
var (
	activeMu sync.Mutex
	active   *Listener
)

func setActive(l *Listener) {
	activeMu.Lock()
	defer activeMu.Unlock()
	active = l
}

func activeListener() (*Listener, error) {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == nil {
		return nil, errors.New("irohnet: no iroh listener in this process")
	}
	return active, nil
}

// Allow adds a node id to this process's live listener allowlist — the
// enrolment approval path. The durable copy of the decision is the config
// file's allowlist (the caller owns that write); this is what makes the
// approval take effect without a restart.
func Allow(nodeID string) error {
	l, e := activeListener()
	if e != nil {
		return e
	}
	return l.Allow(nodeID)
}

// Disallow removes a node id from this process's live listener allowlist and
// closes the connections it already holds.
func Disallow(nodeID string) error {
	l, e := activeListener()
	if e != nil {
		return e
	}
	return l.Disallow(nodeID)
}

// Allow admits a node id from now on (idempotent). An id the endpoint layer
// cannot parse is an error, not a silently ignored entry.
func (l *Listener) Allow(nodeID string) error {
	return l.gateOp(nodeID, false)
}

// Disallow stops admitting a node id and closes its live connections
// (idempotent).
func (l *Listener) Disallow(nodeID string) error {
	return l.gateOp(nodeID, true)
}

func (l *Listener) gateOp(nodeID string, remove bool) error {
	id := C.CString(nodeID)
	defer C.free(unsafe.Pointer(id))
	errBuf := make([]byte, errCap)
	errPtr := (*C.char)(unsafe.Pointer(&errBuf[0]))
	var ret C.int32_t
	if remove {
		ret = C.irohnet_server_disallow(l.h, id, errPtr, errCap)
	} else {
		ret = C.irohnet_server_allow(l.h, id, errPtr, errCap)
	}
	if ret != 0 {
		return fmt.Errorf("irohnet: allowlist %s: %s", nodeID, cstr(errBuf))
	}
	return nil
}

// ID is the listener's node id (hex) — what peers provision.
func (l *Listener) ID() string { return l.id }

func (l *Listener) Accept() (net.Conn, error) {
	var sh C.uint64_t
	remoteBuf := make([]byte, 128)
	errBuf := make([]byte, errCap)
	ret := C.irohnet_server_accept(l.h, &sh,
		(*C.char)(unsafe.Pointer(&remoteBuf[0])), 128,
		(*C.char)(unsafe.Pointer(&errBuf[0])), errCap)
	if ret != 0 {
		return nil, net.ErrClosed
	}
	return &Conn{h: sh, local: Addr{ID: l.id}, remote: Addr{ID: cstr(remoteBuf)}}, nil
}

func (l *Listener) Close() error {
	l.closeOnce.Do(func() {
		C.irohnet_server_close(l.h)
		clearActive(l)
	})
	return nil
}

func clearActive(l *Listener) {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active == l {
		active = nil
	}
}

func (l *Listener) Addr() net.Addr { return Addr{ID: l.id} }

// ---- dialer + client --------------------------------------------------------

// Dialer opens iroh streams to the one peer in its config.
type Dialer struct {
	h         C.uint64_t
	peer      string
	closeOnce sync.Once

	logMu      sync.Mutex
	lastLogged string // last dial-error reason printed; suppresses repeats
	// how many failures the current reason has swallowed since it was last
	// printed, and when it was last printed — the heartbeat below.
	sameCount int
	lastAt    time.Time
}

// How long a dial failure that keeps repeating verbatim stays silent before it
// is printed again, with the count of what was swallowed. Log-once-per-reason
// alone makes a client stuck for an hour look identical to one that failed
// twice and recovered, which is exactly what a handset that never came up
// after boot looked like in its log (plan 0035).
const dialLogRepeat = 60 * time.Second

// NewDialer binds a client endpoint from cfg (Peer required; PeerAddrs
// required when Relay is "none").
func NewDialer(cfg *Config) (*Dialer, error) {
	if cfg.Peer == "" {
		return nil, errors.New("irohnet: client config needs a peer node id")
	}
	secret := C.CString(cfg.SecretKey)
	peer := C.CString(cfg.Peer)
	addrs := C.CString(strings.Join(cfg.PeerAddrs, ","))
	relay := C.CString(cfg.Relay)
	defer C.free(unsafe.Pointer(secret))
	defer C.free(unsafe.Pointer(peer))
	defer C.free(unsafe.Pointer(addrs))
	defer C.free(unsafe.Pointer(relay))
	var h C.uint64_t
	errBuf := make([]byte, errCap)
	if C.irohnet_client_new(secret, peer, addrs, relay, &h,
		(*C.char)(unsafe.Pointer(&errBuf[0])), errCap) != 0 {
		return nil, fmt.Errorf("irohnet: client: %s", cstr(errBuf))
	}
	return &Dialer{h: h, peer: cfg.Peer}, nil
}

// DialContext opens one stream to the configured peer (network/addr are
// ignored — the peer is fixed by config). ctx bounds the dial as a timeout;
// see the package header for the cancellation gap.
func (d *Dialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	timeoutMs := int64(30_000)
	if dl, ok := ctx.Deadline(); ok {
		left := time.Until(dl).Milliseconds()
		if left <= 0 {
			return nil, timeoutError{}
		}
		timeoutMs = left
	}
	var sh C.uint64_t
	errBuf := make([]byte, errCap)
	if C.irohnet_client_dial(d.h, C.int64_t(timeoutMs), &sh,
		(*C.char)(unsafe.Pointer(&errBuf[0])), errCap) != 0 {
		reason := cstr(errBuf)
		d.logDialError(reason)
		return nil, fmt.Errorf("irohnet: dial %s: %s", d.peer, reason)
	}
	d.dialWorked()
	return &Conn{h: sh, local: Addr{ID: "client"}, remote: Addr{ID: d.peer}, dialer: d,
		opTimeout: opTimeoutMs()}, nil
}

// dialWorked retires the standing refusal (LastRefusal) and re-arms the
// log-once dedupe, so a LATER refusal on the same dialer is printed again
// rather than swallowed as a repeat.
func (d *Dialer) dialWorked() {
	d.logMu.Lock()
	d.lastLogged = ""
	d.sameCount = 0
	d.logMu.Unlock()
	clearRefusal()
}

// logDialError prints a dial failure once per distinct reason string. The
// HttpDo capability catches the resulting Go error and folds it into
// HttpResponse(0, "") (the portable core never sees a Go error), so this is
// the only place a refusal like "server refused: 401 not allowlisted"
// (go-pkgs/irohnet/rust/src/lib.rs, irohnet_client_dial) becomes visible —
// both wata-server's client use and wata-fb/wata-tui share this path. The
// Rust side already keeps a redial loop from repeating an application close
// on the wire; this dedupes what reaches the log on top of that.
//
// A reason that keeps repeating is not silent forever: every dialLogRepeat it
// prints once more with the number of attempts it swallowed, so a client stuck
// on one failure is visible as stuck rather than as quiet.
func (d *Dialer) logDialError(reason string) {
	d.logMu.Lock()
	defer d.logMu.Unlock()
	now := time.Now()
	if reason == d.lastLogged {
		d.sameCount++
		if now.Sub(d.lastAt) < dialLogRepeat {
			return
		}
		fmt.Printf("irohnet: dial %s still failing after %d more attempts: %s\n",
			d.peer, d.sameCount, reason)
		d.sameCount = 0
		d.lastAt = now
		return
	}
	d.lastLogged = reason
	d.sameCount = 0
	d.lastAt = now
	noteRefusal(reason)
	fmt.Printf("irohnet: dial %s failed: %s\n", d.peer, reason)
}

// Rebuilds reports how many times this dialer's endpoint was rebuilt by the
// aged-failure heal (rust/src/lib.rs, maybe_rebuild_endpoint): a client whose
// dials have failed for longer than IROHNET_REBUILD_HORIZON_MS (default 5
// minutes; 0 disables) swaps in a fresh endpoint — same key, same peer, new
// sockets and discovery/relay state — before its next handshake. The
// aged-refusal gate asserts on this counter.
func (d *Dialer) Rebuilds() int64 {
	return int64(C.irohnet_client_rebuilds(d.h))
}

// Close shuts the client endpoint down (open conns die with it).
func (d *Dialer) Close() error {
	d.closeOnce.Do(func() { C.irohnet_client_close(d.h) })
	return nil
}

// NewHTTPClient builds an *http.Client whose connections are iroh streams to
// the peer in the config at path. The URL host is ignored by the dial; use a
// placeholder base URL such as "http://wata.iroh". No overall client timeout
// (the Matrix long-poll needs none), like the plain-HTTP client it replaces.
func NewHTTPClient(path string) (*http.Client, error) {
	cfg, e := LoadConfig(path)
	if e != nil {
		return nil, e
	}
	d, e := NewDialer(cfg)
	if e != nil {
		return nil, e
	}
	tr := &http.Transport{
		DialContext:     d.DialContext,
		MaxIdleConns:    4,
		IdleConnTimeout: 90 * time.Second,
	}
	return &http.Client{Transport: tr}, nil
}

// Serve runs handler over an iroh listener built from the config at path —
// the whole server side: no TCP port exists. Blocks like ListenAndServe.
// Requests reach the handler with NodeIDHeader set to the connection's
// authenticated remote node id (any inbound copy stripped first — nodeid.go).
func Serve(path string, handler http.Handler) error {
	cfg, e := LoadConfig(path)
	if e != nil {
		return e
	}
	l, e := Listen(cfg)
	if e != nil {
		return e
	}
	fmt.Printf("irohnet: node %s\n", l.ID())
	for _, a := range l.addrs {
		fmt.Printf("irohnet: addr %s\n", a)
	}
	srv := &http.Server{Handler: trustedNodeID(handler)}
	return srv.Serve(l)
}
