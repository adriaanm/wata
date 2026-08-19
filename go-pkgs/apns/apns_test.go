package apns

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const p256FieldBytesTest = 32 // mirrors apns.go's p256FieldBytes; the test stays independent of it on purpose

// writeKeyFile mints a P-256 key and writes it as a .p8 file — a PEM block
// wrapping PKCS#8, exactly the shape Apple's developer portal hands out.
func writeKeyFile(t *testing.T) (string, *ecdsa.PublicKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatalf("marshal pkcs8: %v", err)
	}
	path := filepath.Join(t.TempDir(), "AuthKey_TEST.p8")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	return path, &key.PublicKey
}

// disarm returns the package to its unloaded state, so one test's key cannot
// arm another's.
func disarm() {
	mu.Lock()
	key = nil
	mu.Unlock()
}

// TestSignES256IsRawFixedWidth is the test for the classic bug: an ECDSA JWT
// signature must be R||S, 32 bytes each for P-256 (64 bytes total) — NOT the
// ASN.1 DER that ecdsa.PrivateKey.Sign (the crypto.Signer method) produces,
// which is variable-length and typically 70-72 bytes with a leading 0x30
// SEQUENCE tag. It also verifies the signature against the public key, which
// is the whole reason this stayed in Go.
func TestSignES256IsRawFixedWidth(t *testing.T) {
	disarm()
	defer disarm()
	path, pub := writeKeyFile(t)
	if err := LoadKey(path); err != nil {
		t.Fatalf("LoadKey: %v", err)
	}
	if !Loaded() {
		t.Fatalf("Loaded() is false after a successful LoadKey")
	}

	signingInput := "eyJhbGciOiJFUzI1NiIsImtpZCI6IktFWUlEIn0.eyJpc3MiOiJURUFNIiwiaWF0IjoxMDAwfQ"
	segment, err := SignES256(signingInput)
	if err != nil {
		t.Fatalf("SignES256: %v", err)
	}
	if strings.ContainsAny(segment, "+/=") {
		t.Errorf("signature segment %q is not base64url without padding", segment)
	}
	sig, err := base64.RawURLEncoding.DecodeString(segment)
	if err != nil {
		t.Fatalf("decode signature: %v", err)
	}

	if want := 2 * p256FieldBytesTest; len(sig) != want {
		t.Fatalf("signature is %d bytes, want exactly %d (R||S fixed-width, not ASN.1 DER)", len(sig), want)
	}
	if sig[0] == 0x30 {
		t.Errorf("signature's first byte is 0x30 (an ASN.1 DER SEQUENCE tag) — looks like DER, not raw R||S")
	}

	r := new(big.Int).SetBytes(sig[:p256FieldBytesTest])
	s := new(big.Int).SetBytes(sig[p256FieldBytesTest:])
	digest := sha256.Sum256([]byte(signingInput))
	if !ecdsa.Verify(pub, digest[:], r, s) {
		t.Fatalf("signature does not verify against the signing input under the test public key")
	}
}

// TestSignES256VariesPerCall guards against a cached or deterministic
// signature: ECDSA is randomized, and two signatures over the same input
// must differ while both verifying.
func TestSignES256VariesPerCall(t *testing.T) {
	disarm()
	defer disarm()
	path, _ := writeKeyFile(t)
	if err := LoadKey(path); err != nil {
		t.Fatalf("LoadKey: %v", err)
	}
	a, err := SignES256("a.b")
	if err != nil {
		t.Fatalf("SignES256: %v", err)
	}
	b, err := SignES256("a.b")
	if err != nil {
		t.Fatalf("SignES256: %v", err)
	}
	if a == b {
		t.Errorf("two signatures over the same input are identical — the nonce is not fresh")
	}
}

// TestSignWithoutKeyFails: the send path must get an error rather than a
// token signed with nothing.
func TestSignWithoutKeyFails(t *testing.T) {
	disarm()
	defer disarm()
	if Loaded() {
		t.Fatalf("Loaded() is true with no key")
	}
	if _, err := SignES256("a.b"); err == nil {
		t.Fatalf("SignES256 succeeded with no key loaded")
	}
}

// TestLoadKeyRejectsBadInput, and leaves a working key armed: a bad path or a
// file that is not a PKCS#8 EC key must not disarm a pusher that was working.
func TestLoadKeyRejectsBadInput(t *testing.T) {
	disarm()
	defer disarm()
	good, _ := writeKeyFile(t)
	if err := LoadKey(good); err != nil {
		t.Fatalf("LoadKey: %v", err)
	}

	if err := LoadKey(filepath.Join(t.TempDir(), "nope.p8")); err == nil {
		t.Errorf("LoadKey accepted a missing file")
	}

	junk := filepath.Join(t.TempDir(), "junk.p8")
	if err := os.WriteFile(junk, []byte("not a pem block"), 0o600); err != nil {
		t.Fatalf("write junk: %v", err)
	}
	if err := LoadKey(junk); err == nil {
		t.Errorf("LoadKey accepted a file with no PEM block")
	}

	rsaish := filepath.Join(t.TempDir(), "empty.p8")
	if err := os.WriteFile(rsaish, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte{1, 2, 3}}), 0o600); err != nil {
		t.Fatalf("write rsaish: %v", err)
	}
	if err := LoadKey(rsaish); err == nil {
		t.Errorf("LoadKey accepted a PEM block that is not a PKCS#8 key")
	}

	if !Loaded() {
		t.Errorf("a failed LoadKey disarmed the previously loaded key")
	}
	if _, err := SignES256("a.b"); err != nil {
		t.Errorf("signing broke after a failed LoadKey: %v", err)
	}
}
