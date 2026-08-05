// The trusted-header wrappers (nodeid.go), on plain net/http — no iroh, no
// staticlib, so these run on every `go test` including stub builds. The
// end-to-end proof that the injected value IS the handshake-verified node id
// lives in irohnet_test.go (TestTrustedNodeIDOverIroh, `-tags iroh`).

package irohnet

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// capture answers 200 and records what the wrapped handler saw in the header.
func capture(saw *string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*saw = r.Header.Get(NodeIDHeader)
	})
}

// A TCP-path request must never reach the handler with the header, however
// hard the client tries to forge one — the strip half of the contract.
func TestStripNodeIDDropsForgedHeader(t *testing.T) {
	var saw string
	srv := httptest.NewServer(StripNodeID(capture(&saw)))
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/_wata/v1/device-login", nil)
	req.Header.Set(NodeIDHeader, "attacker-supplied-node-id")
	resp, e := http.DefaultClient.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	resp.Body.Close()
	if saw != "" {
		t.Fatalf("a forged %s survived the TCP strip: %q", NodeIDHeader, saw)
	}
}

// The iroh bridge wrapper replaces any inbound copy with the connection's
// RemoteAddr — a forged value never wins, and the injected one is exactly
// what the transport authenticated.
func TestTrustedNodeIDInjectsAndOverridesForgery(t *testing.T) {
	var saw string
	inner := trustedNodeID(capture(&saw))
	w := httptest.NewRecorder()
	r := httptest.NewRequest("POST", "/x", nil)
	r.RemoteAddr = "aa11bb22" // stands in for the accept gate's verified id
	r.Header.Set(NodeIDHeader, "forged")
	inner.ServeHTTP(w, r)
	if saw != "aa11bb22" {
		t.Fatalf("handler saw %q, want the connection's id", saw)
	}
}
