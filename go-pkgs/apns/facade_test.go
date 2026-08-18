package apns

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// writeP8 writes a fresh P-256 key out as a .p8 Auth Key file, the shape
// Configure reads (a PEM-wrapped PKCS#8 EC private key).
func writeP8(t *testing.T) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	path := filepath.Join(t.TempDir(), "AuthKey_TEST.p8")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	return path
}

// resetFacade puts the package-level state back, so one test's Configure does
// not arm the next one.
func resetFacade() {
	facadeMu.Lock()
	defer facadeMu.Unlock()
	facadeKey, facadeTeamID, facadeKeyID, facadeTopic = nil, "", "", ""
	facadeClients = map[string]*Client{}
}

func TestFacadeConfigureAndPush(t *testing.T) {
	defer resetFacade()
	resetFacade()

	if Configured() {
		t.Fatal("Configured() is true before any Configure")
	}
	// The unconfigured push must not reach the network at all.
	if _, err := Push("http://127.0.0.1:1", "tok", "t", "b", "!r", "$e", -1); err == nil {
		t.Fatal("Push without Configure returned no error")
	}

	var gotPath, gotAuth, gotTopic string
	var gotBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotAuth, gotTopic = r.URL.Path, r.Header.Get("authorization"), r.Header.Get("apns-topic")
		gotBody, _ = io.ReadAll(r.Body)
		if strings.HasSuffix(r.URL.Path, "/deadtoken") {
			w.WriteHeader(http.StatusGone)
			_, _ = w.Write([]byte(`{"reason":"Unregistered"}`))
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := Configure("TEAM1", "KEY1", "com.example.wata", writeP8(t)); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if !Configured() {
		t.Fatal("Configured() is false after a successful Configure")
	}

	status, err := Push(srv.URL, "livetoken", "Alice", "Voice message", "!room:localhost", "$ev:localhost", 3)
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	if status != 200 {
		t.Fatalf("status = %d, want 200", status)
	}
	if gotPath != "/3/device/livetoken" {
		t.Errorf("path = %q", gotPath)
	}
	if !strings.HasPrefix(gotAuth, "bearer ") {
		t.Errorf("authorization = %q", gotAuth)
	}
	if gotTopic != "com.example.wata" {
		t.Errorf("apns-topic = %q", gotTopic)
	}
	var payload Payload
	if err := json.Unmarshal(gotBody, &payload); err != nil {
		t.Fatalf("payload: %v (%s)", err, gotBody)
	}
	if payload.Aps.Alert.Title != "Alice" || payload.Aps.Alert.Body != "Voice message" {
		t.Errorf("alert = %+v", payload.Aps.Alert)
	}
	if payload.Aps.InterruptionLevel != "time-sensitive" {
		t.Errorf("interruption-level = %q", payload.Aps.InterruptionLevel)
	}
	if payload.Aps.Badge == nil || *payload.Aps.Badge != 3 {
		t.Errorf("badge = %v", payload.Aps.Badge)
	}
	if payload.RoomID != "!room:localhost" || payload.EventID != "$ev:localhost" {
		t.Errorf("room/event = %q/%q", payload.RoomID, payload.EventID)
	}

	// A negative badge leaves the count alone: the key must be absent.
	if _, err := Push(srv.URL, "livetoken", "Alice", "hi", "!r", "$e", -1); err != nil {
		t.Fatalf("Push: %v", err)
	}
	if strings.Contains(string(gotBody), "badge") {
		t.Errorf("badge present for a negative badge: %s", gotBody)
	}

	// 410 comes back as a status, not an error — the caller's whole job is to
	// notice it and delete the registration.
	status, err = Push(srv.URL, "deadtoken", "Alice", "hi", "!r", "$e", -1)
	if err != nil {
		t.Fatalf("Push (410): %v", err)
	}
	if status != http.StatusGone {
		t.Fatalf("status = %d, want 410", status)
	}
}

func TestFacadeConfigureRejectsBadKey(t *testing.T) {
	defer resetFacade()
	resetFacade()

	bad := filepath.Join(t.TempDir(), "not-a-key.p8")
	if err := os.WriteFile(bad, []byte("nonsense"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Configure("TEAM1", "KEY1", "com.example.wata", bad); err == nil {
		t.Fatal("Configure accepted a non-PEM file")
	}
	if err := Configure("TEAM1", "KEY1", "com.example.wata", filepath.Join(t.TempDir(), "missing.p8")); err == nil {
		t.Fatal("Configure accepted a missing file")
	}
	if err := Configure("", "KEY1", "com.example.wata", writeP8(t)); err == nil {
		t.Fatal("Configure accepted an empty team id")
	}
	if Configured() {
		t.Fatal("a failed Configure armed the pusher")
	}
}

func TestFacadeHostFor(t *testing.T) {
	if got := HostFor("sandbox"); got != SandboxHost {
		t.Errorf("HostFor(sandbox) = %q", got)
	}
	for _, env := range []string{"production", "", "nonsense"} {
		if got := HostFor(env); got != ProductionHost {
			t.Errorf("HostFor(%q) = %q, want production", env, got)
		}
	}
}
