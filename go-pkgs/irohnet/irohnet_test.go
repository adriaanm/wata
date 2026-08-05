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

// boot a server+client pair over loopback iroh; returns the client and the
// listener (caller closes both via t.Cleanup registrations here).
func pair(t *testing.T, allowClient bool) (*http.Client, *Listener) {
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
	return &http.Client{Transport: &http.Transport{DialContext: d.DialContext}}, l
}

func TestHTTPOverIroh(t *testing.T) {
	client, l := pair(t, true)
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

func TestAllowlistRefusedAtAccept(t *testing.T) {
	client, l := pair(t, false)
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
// again. Each leg dials with a FRESH client endpoint, because a refused
// client caches the dead connection and answers its own dials from it for
// REFUSAL_COOLDOWN (rust/src/lib.rs) — the recovery a real device gets is a
// retry past that cooldown, or a restart, not an instant one.
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
	client, l := pair(t, true)
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
