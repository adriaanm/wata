//go:build darwin && iroh

// In-process proof of the net.Conn glue: a real net/http server on an iroh
// listener, a real http.Client over the iroh dialer, no TCP anywhere, relay
// "none" (no network beyond loopback UDP). Run:
//
//	./mklib.py && go test -tags iroh ./...

package irohnet

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// boot a server+client pair over loopback iroh; returns the client, the
// listener and the CLIENT's node id (caller closes both via t.Cleanup
// registrations here).
func pair(t *testing.T, allowClient bool) (*http.Client, *Listener, string) {
	t.Helper()
	srvSecret, _, e := GenKey()
	if e != nil {
		t.Fatal(e)
	}
	cliSecret, cliID, e := GenKey()
	if e != nil {
		t.Fatal(e)
	}
	allow := []string{cliID}
	if !allowClient {
		otherSecret, otherID, _ := GenKey()
		_ = otherSecret
		allow = []string{otherID}
	}
	dir := t.TempDir()
	ann := filepath.Join(dir, "announce.json")
	l, e := Listen(&Config{SecretKey: srvSecret, Relay: "none", Allowlist: allow, AnnounceFile: ann})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { l.Close() })

	raw, e := os.ReadFile(ann)
	if e != nil {
		t.Fatal(e)
	}
	var a struct {
		ID    string   `json:"id"`
		Addrs []string `json:"addrs"`
	}
	if e := json.Unmarshal(raw, &a); e != nil {
		t.Fatal(e)
	}
	// v4 loopback only: the test asserts the announce file is dialable as-is.
	var v4 []string
	for _, s := range a.Addrs {
		if !strings.HasPrefix(s, "[") {
			v4 = append(v4, s)
		}
	}
	d, e := NewDialer(&Config{SecretKey: cliSecret, Relay: "none", Peer: a.ID, PeerAddrs: v4})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { d.Close() })
	return &http.Client{Transport: &http.Transport{DialContext: d.DialContext}}, l, cliID
}

func TestHTTPOverIroh(t *testing.T) {
	client, l, _ := pair(t, true)
	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("X-Remote", r.RemoteAddr)
		w.Write([]byte("echo:" + string(body)))
	})
	go func() { http.Serve(l, mux) }()

	// several requests: exercises stream-per-conn + keep-alive reuse.
	for i := 0; i < 5; i++ {
		resp, e := client.Post("http://wata.iroh/echo", "text/plain", strings.NewReader("hi"))
		if e != nil {
			t.Fatalf("request %d: %v", i, e)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if string(body) != "echo:hi" {
			t.Fatalf("request %d: got %q", i, body)
		}
		if resp.Header.Get("X-Remote") == "" {
			t.Fatal("no remote node id on the server side")
		}
	}
}

// The trusted header end to end (plan 0027): a request over the iroh bridge
// reaches the handler carrying NodeIDHeader == the CLIENT's node id — the one
// the accept gate verified — even when the client sends a forged copy. This
// is what device-login authenticates on, so the assertion is exact equality
// with the key the client actually dialed with, not mere presence.
func TestTrustedNodeIDOverIroh(t *testing.T) {
	client, l, cliID := pair(t, true)
	mux := http.NewServeMux()
	mux.HandleFunc("/whoami", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(r.Header.Get(NodeIDHeader)))
	})
	// the same wrapping Serve installs (irohnet_cgo.go).
	go func() { http.Serve(l, trustedNodeID(mux)) }()

	req, _ := http.NewRequest("GET", "http://wata.iroh/whoami", nil)
	req.Header.Set(NodeIDHeader, "forged-by-the-peer")
	resp, e := client.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != cliID {
		t.Fatalf("handler saw %q, want the dialing client's node id %q", body, cliID)
	}
}

func TestAllowlistRefusedAtAccept(t *testing.T) {
	client, l, _ := pair(t, false)
	go func() { http.Serve(l, http.NewServeMux()) }()
	client.Timeout = 5 * time.Second
	_, e := client.Get("http://wata.iroh/")
	if e == nil {
		t.Fatal("request from a non-allowlisted node succeeded")
	}
	// [IROH-REFUSAL-LOUD], plan 0013 M5: the reason must survive, not read
	// as a generic closed connection.
	if !strings.Contains(e.Error(), "not allowlisted") {
		t.Fatalf("refusal was not loud: %v", e)
	}
	// A second request against the same client hits the cached (now dead)
	// connection; it must fail fast with the same reason rather than
	// redialing into a fresh handshake against a peer that will only ever
	// refuse it again.
	_, e2 := client.Get("http://wata.iroh/")
	if e2 == nil || !strings.Contains(e2.Error(), "not allowlisted") {
		t.Fatalf("second request: refusal was not loud: %v", e2)
	}
}

// The live allowlist (plan 0021 milestone B): a node the listener was built
// without is refused, Allow admits it with NO restart, Disallow refuses it
// again. Each leg dials with a FRESH client endpoint so the legs read as
// separate decisions; a refused client caches the dead connection and answers
// its own dials from it for REFUSAL_COOLDOWN (rust/src/lib.rs), so recovery on
// the SAME dialer is a retry past that cooldown — which is what
// TestRefusedClientRedialsAfterAllow pins.
func TestLiveAllowlistAdd(t *testing.T) {
	srvSecret, _, _ := GenKey()
	_, otherID, _ := GenKey()
	cliSecret, cliID, _ := GenKey()
	dir := t.TempDir()
	ann := filepath.Join(dir, "announce.json")
	l, e := Listen(&Config{SecretKey: srvSecret, Relay: "none", Allowlist: []string{otherID}, AnnounceFile: ann})
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() { http.Serve(l, okMux()) }()

	peerID, peerAddrs := announced(t, ann)
	get := func() error {
		d, e := NewDialer(&Config{SecretKey: cliSecret, Relay: "none", Peer: peerID, PeerAddrs: peerAddrs})
		if e != nil {
			t.Fatal(e)
		}
		defer d.Close()
		c := &http.Client{Transport: &http.Transport{DialContext: d.DialContext}, Timeout: 10 * time.Second}
		resp, e := c.Get("http://wata.iroh/")
		if e != nil {
			return e
		}
		resp.Body.Close()
		return nil
	}

	if e := get(); e == nil || !strings.Contains(e.Error(), "not allowlisted") {
		t.Fatalf("want a loud refusal before the approval, got %v", e)
	}
	if e := l.Allow("not-a-node-id"); e == nil {
		t.Fatal("a malformed node id was accepted into the allowlist")
	}
	if e := l.Allow(cliID); e != nil {
		t.Fatal(e)
	}
	if e := get(); e != nil {
		t.Fatalf("request after Allow (no restart): %v", e)
	}
	if e := l.Allow(cliID); e != nil {
		t.Fatalf("Allow is not idempotent: %v", e)
	}
	if e := l.Disallow(cliID); e != nil {
		t.Fatal(e)
	}
	if e := get(); e == nil || !strings.Contains(e.Error(), "not allowlisted") {
		t.Fatalf("want a loud refusal after Disallow, got %v", e)
	}
}

// The BOOTSTRAP state (plan 0014): a fresh install has approved nobody, so
// its allowlist is empty — and an empty allowlist LISTENS and refuses every
// peer, because the only way out of that state is an enrolment approval
// landing on a server that is already up. The refusal is the ordinary
// not-allowlisted one, and Allow admits the first node live, no restart.
func TestEmptyAllowlistBootstrap(t *testing.T) {
	srvSecret, _, _ := GenKey()
	cliSecret, cliID, _ := GenKey()
	dir := t.TempDir()
	ann := filepath.Join(dir, "announce.json")
	l, e := Listen(&Config{SecretKey: srvSecret, Relay: "none", Allowlist: []string{}, AnnounceFile: ann})
	if e != nil {
		t.Fatalf("an empty allowlist must listen (the fresh-install bootstrap state): %v", e)
	}
	defer l.Close()
	go func() { http.Serve(l, okMux()) }()

	peerID, peerAddrs := announced(t, ann)
	get := func() error {
		d, e := NewDialer(&Config{SecretKey: cliSecret, Relay: "none", Peer: peerID, PeerAddrs: peerAddrs})
		if e != nil {
			t.Fatal(e)
		}
		defer d.Close()
		c := &http.Client{Transport: &http.Transport{DialContext: d.DialContext}, Timeout: 10 * time.Second}
		resp, e := c.Get("http://wata.iroh/")
		if e != nil {
			return e
		}
		resp.Body.Close()
		return nil
	}

	if e := get(); e == nil || !strings.Contains(e.Error(), "not allowlisted") {
		t.Fatalf("want the ordinary loud refusal from the bootstrap server, got %v", e)
	}
	if e := l.Allow(cliID); e != nil {
		t.Fatal(e)
	}
	if e := get(); e != nil {
		t.Fatalf("first node allowed into a previously-empty allowlist must be admitted, no restart: %v", e)
	}
}

// A REFUSAL IS LOUD BUT NEVER TERMINAL ([FB-REDIAL-AFTER-REFUSAL], plan 0014).
// The same dialer that was refused has to get in the moment its id is
// allowlisted — no new dialer, no restarted process — because that is the whole
// promise of enrolment: a parent approves the handset in front of them and it
// connects.
//
// The trap this pins is that dialling HARDER must not make it worse. A refused
// client answers dials from its cached dead connection for REFUSAL_COOLDOWN
// before attempting another handshake; while every one of those fast local
// failures re-stamped the cooldown, a client retrying faster than it (the sync
// loop plus a parent pressing OK) slid the window forever and never attempted
// one — the hardware symptom, a device latched on "not allowlisted" until it
// was restarted. So this dials FASTER than the cooldown throughout.
func TestRefusedClientRedialsAfterAllow(t *testing.T) {
	client, l, cliID := pair(t, false)
	go func() { http.Serve(l, okMux()) }()
	client.Timeout = 10 * time.Second

	if _, e := client.Get("http://wata.iroh/"); e == nil || !strings.Contains(e.Error(), "not allowlisted") {
		t.Fatalf("want a loud refusal before the approval, got %v", e)
	}
	if LastRefusal() == "" {
		t.Fatal("the refusal did not reach the app edge (LastRefusal is empty)")
	}
	if e := l.Allow(cliID); e != nil {
		t.Fatal(e)
	}
	deadline := time.Now().Add(45 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		resp, e := client.Get("http://wata.iroh/")
		if e == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			last = nil
			break
		}
		last = e
		time.Sleep(200 * time.Millisecond) // faster than REFUSAL_COOLDOWN, on purpose
	}
	if last != nil {
		t.Fatalf("the approved node never got in on the same dialer: %v", last)
	}
	// and the app edge's latch clears, or the handset keeps showing its QR
	// over a working link.
	if r := LastRefusal(); r != "" {
		t.Fatalf("the refusal survived a successful dial: %q", r)
	}
}

func okMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	return mux
}

// the announce file as (node id, dialable v4 addrs).
func announced(t *testing.T, path string) (string, []string) {
	t.Helper()
	raw, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	var a struct {
		ID    string   `json:"id"`
		Addrs []string `json:"addrs"`
	}
	if e := json.Unmarshal(raw, &a); e != nil {
		t.Fatal(e)
	}
	var v4 []string
	for _, s := range a.Addrs {
		if !strings.HasPrefix(s, "[") {
			v4 = append(v4, s)
		}
	}
	return a.ID, v4
}

func TestDeadlineInterruptsBlockedRead(t *testing.T) {
	// raw conn pair: dial, then read with no server data and a deadline.
	srvSecret, _, _ := GenKey()
	c2Secret, c2ID, _ := GenKey()
	dir := t.TempDir()
	ann := filepath.Join(dir, "a.json")
	l2, e := Listen(&Config{SecretKey: srvSecret, Relay: "none", Allowlist: []string{c2ID}, AnnounceFile: ann})
	if e != nil {
		t.Fatal(e)
	}
	defer l2.Close()
	raw, _ := os.ReadFile(ann)
	var a struct {
		ID    string   `json:"id"`
		Addrs []string `json:"addrs"`
	}
	json.Unmarshal(raw, &a)
	var v4 []string
	for _, s := range a.Addrs {
		if !strings.HasPrefix(s, "[") {
			v4 = append(v4, s)
		}
	}
	d, e := NewDialer(&Config{SecretKey: c2Secret, Relay: "none", Peer: a.ID, PeerAddrs: v4})
	if e != nil {
		t.Fatal(e)
	}
	defer d.Close()
	conn, e := d.DialContext(t.Context(), "iroh", "ignored")
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	// the stream must exist server-side too: write a byte, accept it there.
	if _, e := conn.Write([]byte("x")); e != nil {
		t.Fatal(e)
	}
	srvConn, e := l2.Accept()
	if e != nil {
		t.Fatal(e)
	}
	defer srvConn.Close()

	// deadline set BEFORE the read: returns timeout promptly.
	conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	start := time.Now()
	buf := make([]byte, 16)
	_, e = conn.Read(buf)
	var nerr net.Error
	if e == nil || !isTimeout(e, &nerr) {
		t.Fatalf("want timeout, got %v", e)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("timeout not honored promptly")
	}

	// deadline moved into the past DURING a blocked read: must interrupt.
	conn.SetReadDeadline(time.Time{})
	done := make(chan error, 1)
	go func() {
		_, re := conn.Read(buf)
		done <- re
	}()
	time.Sleep(200 * time.Millisecond)
	conn.SetReadDeadline(time.Now().Add(-time.Second))
	select {
	case re := <-done:
		if !isTimeout(re, &nerr) {
			t.Fatalf("want timeout from interrupted read, got %v", re)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("blocked read not interrupted by past deadline")
	}

	// Close from another goroutine unblocks a pending read.
	conn.SetReadDeadline(time.Time{})
	done2 := make(chan error, 1)
	go func() {
		_, re := conn.Read(buf)
		done2 <- re
	}()
	time.Sleep(200 * time.Millisecond)
	conn.Close()
	select {
	case re := <-done2:
		if re == nil {
			t.Fatal("read after close returned no error")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("blocked read not unblocked by Close")
	}
}

func isTimeout(e error, nerr *net.Error) bool {
	if e == nil {
		return false
	}
	ne, ok := e.(net.Error)
	if !ok {
		return false
	}
	*nerr = ne
	return ne.Timeout()
}

func TestEOFOnPeerClose(t *testing.T) {
	client, l, _ := pair(t, true)
	_ = client
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	go func() { http.Serve(l, mux) }()
	resp, e := client.Get("http://wata.iroh/")
	if e != nil {
		t.Fatal(e)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "ok" {
		t.Fatalf("got %q", body)
	}
}

// A peer that ACCEPTS and never answers must not hang a client request
// forever: client conns carry a post-dial per-operation deadline
// (WATA_HTTP_TIMEOUT_MS, 30s by default), so the request fails as a timeout
// and the caller's backoff can do its job. Plan 0022.
func TestHungPeerHitsPostDialDeadline(t *testing.T) {
	t.Setenv("WATA_HTTP_TIMEOUT_MS", "500")
	client, l, _ := pair(t, true)
	// accept the stream and then sit on it — no response, ever.
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				return
			}
			defer c.Close()
			select {}
		}
	}()
	start := time.Now()
	_, e := client.Get("http://wata.iroh/")
	if e == nil {
		t.Fatal("want a timeout against a peer that never answers")
	}
	if time.Since(start) > 5*time.Second {
		t.Fatalf("deadline not honored promptly: %v", time.Since(start))
	}
}
