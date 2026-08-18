package apns

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const p256FieldBytesTest = 32 // mirrors jwt.go's p256FieldBytes; test stays independent of it on purpose

func testKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return key
}

// capturedRequest is what the fake server saw, copied out before the body
// is consumed so the test can inspect it after the handler returns.
type capturedRequest struct {
	method     string
	path       string
	proto      int
	authHeader string
	topic      string
	pushType   string
	priority   string
	expiration string
	body       []byte
}

// fakeAPNs stands in for Apple: it records every request it sees and
// answers according to respond, keyed by device token.
type fakeAPNs struct {
	srv     *httptest.Server
	pubKey  *ecdsa.PublicKey
	reqs    atomic.Int64
	last    atomic.Value // capturedRequest
	respond func(deviceToken string) (status int, body string)
}

func newFakeAPNs(t *testing.T, key *ecdsa.PrivateKey, respond func(string) (int, string)) *fakeAPNs {
	t.Helper()
	f := &fakeAPNs{pubKey: &key.PublicKey, respond: respond}
	mux := http.NewServeMux()
	mux.HandleFunc("/3/device/", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		cap := capturedRequest{
			method:     r.Method,
			path:       r.URL.Path,
			proto:      r.ProtoMajor,
			authHeader: r.Header.Get("authorization"),
			topic:      r.Header.Get("apns-topic"),
			pushType:   r.Header.Get("apns-push-type"),
			priority:   r.Header.Get("apns-priority"),
			expiration: r.Header.Get("apns-expiration"),
			body:       body,
		}
		f.last.Store(cap)
		f.reqs.Add(1)

		deviceToken := strings.TrimPrefix(r.URL.Path, "/3/device/")
		status, respBody := f.respond(deviceToken)
		w.Header().Set("apns-id", "test-apns-id")
		w.WriteHeader(status)
		if respBody != "" {
			_, _ = w.Write([]byte(respBody))
		}
	})
	srv := httptest.NewUnstartedServer(mux)
	srv.EnableHTTP2 = true
	srv.StartTLS()
	f.srv = srv
	return f
}

func (f *fakeAPNs) close() { f.srv.Close() }

func (f *fakeAPNs) lastRequest(t *testing.T) capturedRequest {
	t.Helper()
	v := f.last.Load()
	if v == nil {
		t.Fatalf("no request captured yet")
	}
	return v.(capturedRequest)
}

func newClient(t *testing.T, f *fakeAPNs, key *ecdsa.PrivateKey, clock func() time.Time) *Client {
	t.Helper()
	c, err := New(Config{
		TeamID:     "TEAMID123",
		KeyID:      "KEYID456",
		Topic:      "com.example.wata",
		PrivateKey: key,
		Host:       f.srv.URL,
		HTTPClient: f.srv.Client(),
		Clock:      clock,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return c
}

func decodeJWT(t *testing.T, authHeader string) (hdr jwtHeader, claims jwtClaims, sigRaw []byte, signingInput string) {
	t.Helper()
	const prefix = "bearer "
	if !strings.HasPrefix(authHeader, prefix) {
		t.Fatalf("authorization header %q missing %q prefix", authHeader, prefix)
	}
	tok := strings.TrimPrefix(authHeader, prefix)
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatalf("JWT %q does not have 3 parts", tok)
	}
	hdrJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatalf("decode header: %v", err)
	}
	claimsJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatalf("decode claims: %v", err)
	}
	sigRaw, err = base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		t.Fatalf("decode signature: %v", err)
	}
	if err := json.Unmarshal(hdrJSON, &hdr); err != nil {
		t.Fatalf("unmarshal header: %v", err)
	}
	if err := json.Unmarshal(claimsJSON, &claims); err != nil {
		t.Fatalf("unmarshal claims: %v", err)
	}
	signingInput = parts[0] + "." + parts[1]
	return
}

// TestJWTHeaderAndClaims asserts the header and claims decode to what APNs
// requires, and iat reflects the injected clock (not wall time).
func TestJWTHeaderAndClaims(t *testing.T) {
	key := testKey(t)
	now := time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC)
	f := newFakeAPNs(t, key, func(string) (int, string) { return 200, "" })
	defer f.close()
	c := newClient(t, f, key, func() time.Time { return now })

	res, err := c.Send(context.Background(), "devicetoken1", AlertPayload("t", "b", "room1", "event1", nil), SendOptions{})
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	if !res.OK() {
		t.Fatalf("expected OK, got status %d reason %q", res.StatusCode, res.Reason)
	}

	req := f.lastRequest(t)
	hdr, claims, _, _ := decodeJWT(t, req.authHeader)
	if hdr.Alg != "ES256" {
		t.Errorf("alg = %q, want ES256", hdr.Alg)
	}
	if hdr.Kid != "KEYID456" {
		t.Errorf("kid = %q, want KEYID456", hdr.Kid)
	}
	if claims.Iss != "TEAMID123" {
		t.Errorf("iss = %q, want TEAMID123", claims.Iss)
	}
	if claims.Iat != now.Unix() {
		t.Errorf("iat = %d, want %d", claims.Iat, now.Unix())
	}
}

// TestJWTSignatureIsRawFixedWidth is the test for the classic bug: an
// ECDSA JWT signature must be R||S, 32 bytes each for P-256 (64 bytes
// total) — NOT the ASN.1 DER ecdsa.PrivateKey.Sign (the crypto.Signer
// method) would produce, which is variable-length and typically 70-72
// bytes with a leading 0x30 SEQUENCE tag.
func TestJWTSignatureIsRawFixedWidth(t *testing.T) {
	key := testKey(t)
	f := newFakeAPNs(t, key, func(string) (int, string) { return 200, "" })
	defer f.close()
	c := newClient(t, f, key, func() time.Time { return time.Unix(1000, 0) })

	if _, err := c.Send(context.Background(), "devicetoken1", AlertPayload("t", "b", "r", "e", nil), SendOptions{}); err != nil {
		t.Fatalf("Send: %v", err)
	}

	req := f.lastRequest(t)
	_, _, sig, signingInput := decodeJWT(t, req.authHeader)

	wantLen := 2 * p256FieldBytesTest
	if len(sig) != wantLen {
		t.Fatalf("signature is %d bytes, want exactly %d (R||S fixed-width, not ASN.1 DER)", len(sig), wantLen)
	}
	if sig[0] == 0x30 {
		t.Errorf("signature's first byte is 0x30 (an ASN.1 DER SEQUENCE tag) — looks like DER, not raw R||S")
	}

	r := new(big.Int).SetBytes(sig[:p256FieldBytesTest])
	s := new(big.Int).SetBytes(sig[p256FieldBytesTest:])
	digest := sha256.Sum256([]byte(signingInput))
	if !ecdsa.Verify(&key.PublicKey, digest[:], r, s) {
		t.Fatalf("signature does not verify against the signing input under the test public key")
	}
}

// TestRequestShape asserts the method, path and all five headers Apple's
// provider API requires.
func TestRequestShape(t *testing.T) {
	key := testKey(t)
	f := newFakeAPNs(t, key, func(string) (int, string) { return 200, "" })
	defer f.close()
	c := newClient(t, f, key, func() time.Time { return time.Unix(2000, 0) })

	if _, err := c.Send(context.Background(), "abc123devicetoken", AlertPayload("Bob", "hi", "room9", "event9", nil), SendOptions{}); err != nil {
		t.Fatalf("Send: %v", err)
	}

	req := f.lastRequest(t)
	if req.method != http.MethodPost {
		t.Errorf("method = %q, want POST", req.method)
	}
	if req.path != "/3/device/abc123devicetoken" {
		t.Errorf("path = %q, want /3/device/abc123devicetoken", req.path)
	}
	if req.proto != 2 {
		t.Errorf("ProtoMajor = %d, want 2 (HTTP/2) — the fake server did not negotiate h2", req.proto)
	}
	if !strings.HasPrefix(req.authHeader, "bearer ") {
		t.Errorf("authorization = %q, want a bearer token", req.authHeader)
	}
	if req.topic != "com.example.wata" {
		t.Errorf("apns-topic = %q, want com.example.wata", req.topic)
	}
	if req.pushType != "alert" {
		t.Errorf("apns-push-type = %q, want alert", req.pushType)
	}
	if req.priority != "10" {
		t.Errorf("apns-priority = %q, want 10", req.priority)
	}
	if req.expiration != "0" {
		t.Errorf("apns-expiration = %q, want 0", req.expiration)
	}

	var payload Payload
	if err := json.Unmarshal(req.body, &payload); err != nil {
		t.Fatalf("unmarshal request body: %v", err)
	}
	if payload.Aps.Alert.Title != "Bob" || payload.Aps.Alert.Body != "hi" {
		t.Errorf("alert = %+v, want title Bob body hi", payload.Aps.Alert)
	}
	if payload.RoomID != "room9" || payload.EventID != "event9" {
		t.Errorf("room/event = %q/%q, want room9/event9", payload.RoomID, payload.EventID)
	}
	if payload.Aps.InterruptionLevel != "time-sensitive" {
		t.Errorf("interruption-level = %q, want time-sensitive", payload.Aps.InterruptionLevel)
	}
}

// TestTokenCaching: two sends inside the refresh window reuse one JWT; a
// send past the window mints a new one. Driven entirely by the injectable
// clock — no sleeping.
func TestTokenCaching(t *testing.T) {
	key := testKey(t)
	f := newFakeAPNs(t, key, func(string) (int, string) { return 200, "" })
	defer f.close()

	base := time.Date(2026, 8, 18, 9, 0, 0, 0, time.UTC)
	now := base
	c := newClient(t, f, key, func() time.Time { return now })

	send := func() string {
		if _, err := c.Send(context.Background(), "tok", AlertPayload("t", "b", "r", "e", nil), SendOptions{}); err != nil {
			t.Fatalf("Send: %v", err)
		}
		return f.lastRequest(t).authHeader
	}

	first := send()

	now = base.Add(10 * time.Minute)
	second := send()
	if second != first {
		t.Errorf("send at +10m minted a new token; want the cached one reused (window is 45m)")
	}

	now = base.Add(46 * time.Minute)
	third := send()
	if third == first {
		t.Errorf("send at +46m reused the old token; want a fresh one minted past the refresh window")
	}

	_, claims, _, _ := decodeJWT(t, third)
	if claims.Iat != now.Unix() {
		t.Errorf("refreshed token iat = %d, want %d", claims.Iat, now.Unix())
	}
}

// TestOutcomes: 200 -> OK, 410 -> Gone (the "forget this token" outcome),
// and a 400 with a reason body -> that reason surfaced.
func TestOutcomes(t *testing.T) {
	key := testKey(t)
	f := newFakeAPNs(t, key, func(deviceToken string) (int, string) {
		switch deviceToken {
		case "dead-token":
			return http.StatusGone, `{"reason":"Unregistered"}`
		case "bad-token":
			return http.StatusBadRequest, `{"reason":"BadDeviceToken"}`
		default:
			return http.StatusOK, ""
		}
	})
	defer f.close()
	c := newClient(t, f, key, func() time.Time { return time.Unix(3000, 0) })

	okRes, err := c.Send(context.Background(), "live-token", AlertPayload("t", "b", "r", "e", nil), SendOptions{})
	if err != nil {
		t.Fatalf("Send (ok): %v", err)
	}
	if !okRes.OK() || okRes.Gone {
		t.Errorf("live-token: OK()=%v Gone=%v, want OK true, Gone false", okRes.OK(), okRes.Gone)
	}

	goneRes, err := c.Send(context.Background(), "dead-token", AlertPayload("t", "b", "r", "e", nil), SendOptions{})
	if err != nil {
		t.Fatalf("Send (gone): %v", err)
	}
	if !goneRes.Gone {
		t.Errorf("dead-token: Gone = false, want true (the caller's signal to delete the registration)")
	}
	if goneRes.OK() {
		t.Errorf("dead-token: OK() = true, want false")
	}
	if goneRes.Reason != "Unregistered" {
		t.Errorf("dead-token: Reason = %q, want Unregistered", goneRes.Reason)
	}

	badRes, err := c.Send(context.Background(), "bad-token", AlertPayload("t", "b", "r", "e", nil), SendOptions{})
	if err != nil {
		t.Fatalf("Send (bad): %v", err)
	}
	if badRes.Gone {
		t.Errorf("bad-token: Gone = true, want false (400, not 410)")
	}
	if badRes.Reason != "BadDeviceToken" {
		t.Errorf("bad-token: Reason = %q, want BadDeviceToken (never swallow APNs' own reason)", badRes.Reason)
	}
}

// TestAlertPayloadOmitsEmptyBadge asserts the payload struct is honest
// about which fields are optional: a nil badge must not appear in the JSON
// at all (vs. serializing as 0, which means something different to APNs —
// "set the badge to zero").
func TestAlertPayloadOmitsEmptyBadge(t *testing.T) {
	p := AlertPayload("t", "b", "room", "event", nil)
	out, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if strings.Contains(string(out), "badge") {
		t.Errorf("payload with nil badge contains a badge key: %s", out)
	}

	badge := 3
	p2 := AlertPayload("t", "b", "room", "event", &badge)
	out2, err := json.Marshal(p2)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(out2), `"badge":3`) {
		t.Errorf("payload with badge=3 missing badge key: %s", out2)
	}
}
