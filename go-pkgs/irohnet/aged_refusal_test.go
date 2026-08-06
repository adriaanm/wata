//go:build darwin && iroh

// The AGED-refusal regression (field, 2026-08-06): a handset sat refused for
// 15+ minutes, the admin then approved its node id — the server's live gate
// verifiably opened — and the client still never got in until an app restart.
// tunnel-smoke's enrolment leg approves within seconds of the first refusal
// and passes, so what it never exercised is a client whose refusals have AGED:
// state that matures over minutes of refused redials (the cached dead
// connection, the refusal latch, iroh endpoint internals) serving a stale
// verdict past the approval.
//
// This test runs that arc honestly: one dialer + one http.Client (the exact
// transport the apps use) redialing a refusing server for IROHNET_AGED_AGE,
// then Allow, then the same client must recover within a bounded window.
//
// What the honest runs proved (2026-08-06, logs in the fix commit): 16-minute
// aged runs — relay none at 2s cadence (480 real refused handshakes), relay n0
// at 45s cadence, and the full wata-fb + wata-server enrolment arc — ALL
// recover within milliseconds-to-1s of the approval. So under a stable network
// nothing above the endpoint goes stale, every past-cooldown redial is a real
// fresh handshake (IROHNET_DEBUG=1 shows each one), and the field wedge has to
// live in the endpoint's network-facing state across the device's mid-refusal
// network move — which one host cannot age. The fix for that class is the
// aged-failure endpoint REBUILD (rust/src/lib.rs, maybe_rebuild_endpoint), and
// THIS test gates its mechanism: with the horizon compressed below the aging
// span, the client must rebuild its endpoint mid-run AND still be admitted
// promptly after Allow. The red run is the same test with the rebuild disabled
// (IROHNET_REBUILD_HORIZON_MS=0, the pre-fix behavior): the rebuild assertion
// fails there.
//
// Knobs (all env, so the smoke and a long-form manual run share one test):
//
//	IROHNET_AGED_AGE           how long to accumulate refusals (default 60s)
//	IROHNET_AGED_CADENCE       gap between refused requests (default 2s)
//	IROHNET_AGED_RELAY         "none" (default) or "n0" (real relay + network)
//	IROHNET_AGED_NO_ADDRS      "1" drops the direct peer addrs, so an "n0" run
//	                           dials by node id through discovery + relay — the
//	                           field topology. NB: not runnable from every
//	                           network (this repo's dev host cannot resolve the
//	                           n0 DNS TXT records; the run fails with "No
//	                           addressing information available")
//	IROHNET_REBUILD_HORIZON_MS preset: honored as-is (0 = rebuild disabled —
//	                           the red run); unset: the test compresses it to
//	                           a third of the age
//
// Run the honest field-duration repro with:
//
//	IROHNET_AGED_AGE=15m go test -tags iroh -run AgedRefusal -timeout 30m -v ./
package irohnet

import (
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func envDur(name string, def time.Duration) time.Duration {
	if v := os.Getenv(name); v != "" {
		if d, e := time.ParseDuration(v); e == nil {
			return d
		}
	}
	return def
}

func TestAgedRefusalThenAllow(t *testing.T) {
	age := envDur("IROHNET_AGED_AGE", 60*time.Second)
	cadence := envDur("IROHNET_AGED_CADENCE", 2*time.Second)
	relay := os.Getenv("IROHNET_AGED_RELAY")
	if relay == "" {
		relay = "none"
	}
	// the rebuild horizon must be BELOW the aging span for the mechanism to be
	// exercised; a preset value (including "0" — disabled, the red run) wins.
	if os.Getenv("IROHNET_REBUILD_HORIZON_MS") == "" {
		t.Setenv("IROHNET_REBUILD_HORIZON_MS",
			strconv.FormatInt((age/3).Milliseconds(), 10))
	}

	srvSecret, _, _ := GenKey()
	cliSecret, cliID, _ := GenKey()
	dir := t.TempDir()
	ann := filepath.Join(dir, "announce.json")
	l, e := Listen(&Config{SecretKey: srvSecret, Relay: relay, Allowlist: []string{}, AnnounceFile: ann})
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() { http.Serve(l, okMux()) }()

	peerID, peerAddrs := announced(t, ann)
	if os.Getenv("IROHNET_AGED_NO_ADDRS") == "1" {
		peerAddrs = nil
	}
	d, e := NewDialer(&Config{SecretKey: cliSecret, Relay: relay, Peer: peerID, PeerAddrs: peerAddrs})
	if e != nil {
		t.Fatal(e)
	}
	defer d.Close()
	client := &http.Client{Transport: &http.Transport{DialContext: d.DialContext}, Timeout: 20 * time.Second}

	get := func() error {
		resp, e := client.Get("http://wata.iroh/")
		if e != nil {
			return e
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return nil
	}

	// ---- age: accumulate refusals for the configured wall-clock span -------
	start := time.Now()
	dials, refusals := 0, 0
	var lastErr error
	for time.Since(start) < age {
		e := get()
		dials++
		if e == nil {
			t.Fatalf("dial %d admitted before Allow — the gate is not refusing", dials)
		}
		lastErr = e
		if strings.Contains(e.Error(), "not allowlisted") {
			refusals++
		}
		time.Sleep(cadence)
	}
	t.Logf("aged %v: %d dials, %d loud refusals, %d endpoint rebuilds, last err: %v",
		age, dials, refusals, d.Rebuilds(), lastErr)
	if refusals == 0 {
		t.Fatalf("no loud refusal ever seen while aging; last err: %v", lastErr)
	}
	// the aged-failure heal fired: a failure run older than the horizon
	// rebuilt the endpoint (the field's app-restart, in process). Disabled
	// (horizon 0 — the pre-fix behavior) this is the assertion that fails.
	if d.Rebuilds() < 1 {
		t.Fatalf("aged %v past the rebuild horizon and the endpoint was never rebuilt", age)
	}

	// ---- the approval lands on the live gate --------------------------------
	if e := l.Allow(cliID); e != nil {
		t.Fatal(e)
	}
	allowed := time.Now()

	// ---- the same aged client must get in, same dialer, no restart ---------
	deadline := allowed.Add(45 * time.Second)
	for {
		e := get()
		if e == nil {
			t.Logf("recovered %v after Allow", time.Since(allowed).Round(time.Millisecond))
			break
		}
		lastErr = e
		if time.Now().After(deadline) {
			t.Fatalf("aged client (refused for %v) never admitted after Allow: %v", age, lastErr)
		}
		time.Sleep(cadence)
	}
	if r := LastRefusal(); r != "" {
		t.Fatalf("the refusal latch survived a successful request: %q", r)
	}
}
