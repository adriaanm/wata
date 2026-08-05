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
#cgo darwin LDFLAGS: -L${SRCDIR}/clib/darwin -lirohnet_ffi -framework Security -framework SystemConfiguration -framework CoreFoundation -framework CoreWLAN
#cgo linux,arm LDFLAGS: -L${SRCDIR}/clib/linux_arm -lirohnet_ffi -lunwind

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
extern void irohnet_server_close(uint64_t h);
extern int32_t irohnet_client_new(const char* secret_hex, const char* peer_id, const char* peer_addrs_csv,
                                  const char* relay, uint64_t* out_handle, char* err_out, size_t err_cap);
extern int32_t irohnet_client_dial(uint64_t h, int64_t timeout_ms, uint64_t* out_stream,
                                   char* err_out, size_t err_cap);
extern int32_t irohnet_client_last_refusal(uint64_t h, char* out, size_t cap);
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
	ret := int64(C.irohnet_stream_read(c.h, (*C.uchar)(unsafe.Pointer(&b[0])), C.size_t(len(b))))
	if ret >= 0 {
		return int(ret), nil
	}
	return 0, c.errForRet(ret)
}

func (c *Conn) Write(b []byte) (int, error) {
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
	C.irohnet_stream_set_deadlines(c.h, deadlineMs(t), deadlineMs(t))
	return nil
}

func (c *Conn) SetReadDeadline(t time.Time) error {
	C.irohnet_stream_set_deadlines(c.h, deadlineMs(t), -1)
	return nil
}

func (c *Conn) SetWriteDeadline(t time.Time) error {
	C.irohnet_stream_set_deadlines(c.h, -1, deadlineMs(t))
	return nil
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
// allowlist is enforced at accept, before any stream is surfaced. If
// cfg.AnnounceFile is set, the listener's id + dialable local addrs are
// written there.
func Listen(cfg *Config) (*Listener, error) {
	if len(cfg.Allowlist) == 0 {
		return nil, errors.New("irohnet: refusing to listen with an empty allowlist (use [\"*\"] to admit any peer)")
	}
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
	return l, nil
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
	l.closeOnce.Do(func() { C.irohnet_server_close(l.h) })
	return nil
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
}

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
	return &Conn{h: sh, local: Addr{ID: "client"}, remote: Addr{ID: d.peer}, dialer: d}, nil
}

// logDialError prints a dial failure once per distinct reason string. The
// HttpDo capability catches the resulting Go error and folds it into
// HttpResponse(0, "") (the portable core never sees a Go error), so this is
// the only place a refusal like "server refused: 401 not allowlisted"
// (go-pkgs/irohnet/rust/src/lib.rs, irohnet_client_dial) becomes visible —
// both wata-server's client use and wata-fb/wata-tui share this path. The
// Rust side already keeps a redial loop from repeating an application close
// on the wire; this dedupes what reaches the log on top of that.
func (d *Dialer) logDialError(reason string) {
	d.logMu.Lock()
	defer d.logMu.Unlock()
	if reason == d.lastLogged {
		return
	}
	d.lastLogged = reason
	fmt.Printf("irohnet: dial %s failed: %s\n", d.peer, reason)
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
	srv := &http.Server{Handler: handler}
	return srv.Serve(l)
}
