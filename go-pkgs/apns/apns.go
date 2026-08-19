// Package apns holds the one part of APNs token auth that cannot be written
// in the dialect: the operator's ES256 signing key.
//
// Everything else about a push — the JOSE header and claims, when to mint a
// fresh provider token, the per-push-type header set, the POST, and what a
// status code means — is ordinary logic and lives in wata-server
// (apnspush.scala). This package is the technology boundary alone: read a
// `.p8` Auth Key (PEM-wrapped PKCS#8 EC) and sign a JWT signing input with
// it. It decides nothing.
//
// The split follows the tree's own division of labour — the password hasher
// writes PBKDF2 in Sgola over bound `crypto/hmac` primitives — and it is what
// makes the pusher's decisions reachable by the server's own checks rather
// than only by Go tests.
package apns

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"os"
	"sync"
)

// p256FieldBytes is the width of an ECDSA P-256 R or S value in bytes.
const p256FieldBytes = 32

// The armed key. It is package-level state rather than a value threaded
// through every call, the same shape irohnet uses for "this process's live
// listener": one operator key per process, read at boot, and a Sgola caller
// has no way to hold an *ecdsa.PrivateKey anyway.
var (
	mu  sync.Mutex
	key *ecdsa.PrivateKey
)

// LoadKey reads the APNs Auth Key at path (a .p8 file) and arms the package
// for SignES256. Calling it again replaces the key, so a re-read picks up a
// rotated one. An error leaves the previous key untouched — a bad path must
// not disarm a working pusher.
func LoadKey(path string) error {
	p8, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("apns: read key %s: %w", path, err)
	}
	k, err := parsePrivateKey(p8)
	if err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	key = k
	return nil
}

// Loaded reports whether a key is armed. With no APNs key the server does
// nothing at all — no pushes, no errors — so this is the gate the send path
// reads.
func Loaded() bool {
	mu.Lock()
	defer mu.Unlock()
	return key != nil
}

// SignES256 returns the JWS signature for a JWT signing input
// ("<b64url header>.<b64url claims>"), base64url-encoded without padding —
// the third segment of the provider token, ready to be appended.
//
// The signature MUST be the raw R||S concatenation (32 bytes each for P-256),
// not the ASN.1 DER encoding crypto.Signer.Sign would hand back — that is the
// classic mistake here, so this calls ecdsa.Sign directly and pads R and S
// itself rather than going through a Sign() that returns DER.
func SignES256(signingInput string) (string, error) {
	mu.Lock()
	k := key
	mu.Unlock()
	if k == nil {
		return "", fmt.Errorf("apns: no key loaded")
	}
	digest := sha256.Sum256([]byte(signingInput))
	r, s, err := ecdsa.Sign(rand.Reader, k, digest[:])
	if err != nil {
		return "", fmt.Errorf("apns: sign: %w", err)
	}
	sig := make([]byte, 2*p256FieldBytes)
	r.FillBytes(sig[:p256FieldBytes])
	s.FillBytes(sig[p256FieldBytes:])
	return base64.RawURLEncoding.EncodeToString(sig), nil
}

// parsePrivateKey parses the contents of an APNs Auth Key .p8 file — a PEM
// block wrapping a PKCS#8 EC private key.
func parsePrivateKey(p8PEM []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(p8PEM)
	if block == nil {
		return nil, fmt.Errorf("apns: no PEM block found")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: parse PKCS8 key: %w", err)
	}
	ecKey, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("apns: key is %T, not an ECDSA private key", parsed)
	}
	return ecKey, nil
}
