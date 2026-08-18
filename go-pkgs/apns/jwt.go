package apns

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"time"
)

// p256FieldBytes is the width of an ECDSA P-256 R or S value in bytes.
const p256FieldBytes = 32

type jwtHeader struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
}

type jwtClaims struct {
	Iss string `json:"iss"`
	Iat int64  `json:"iat"`
}

// mintToken builds an ES256-signed APNs provider token: header {alg:ES256,
// kid}, claims {iss, iat}, and a JWS signature.
//
// The signature MUST be the raw R||S concatenation (32 bytes each for
// P-256), not the ASN.1 DER encoding crypto.Signer.Sign would hand back —
// that is the classic mistake here, so this calls ecdsa.Sign directly and
// pads R and S itself rather than going through a Sign() that returns DER.
func mintToken(teamID, keyID string, key *ecdsa.PrivateKey, now time.Time) (string, error) {
	header, err := json.Marshal(jwtHeader{Alg: "ES256", Kid: keyID})
	if err != nil {
		return "", fmt.Errorf("marshal header: %w", err)
	}
	claims, err := json.Marshal(jwtClaims{Iss: teamID, Iat: now.Unix()})
	if err != nil {
		return "", fmt.Errorf("marshal claims: %w", err)
	}

	signingInput := b64(header) + "." + b64(claims)

	digest := sha256.Sum256([]byte(signingInput))
	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		return "", fmt.Errorf("sign: %w", err)
	}

	sig := make([]byte, 2*p256FieldBytes)
	r.FillBytes(sig[:p256FieldBytes])
	s.FillBytes(sig[p256FieldBytes:])

	return signingInput + "." + b64(sig), nil
}

func b64(b []byte) string {
	return base64.RawURLEncoding.EncodeToString(b)
}

// ParsePrivateKey parses the contents of an APNs Auth Key .p8 file — a PEM
// block wrapping a PKCS#8 EC private key — into the key Config.PrivateKey
// wants.
func ParsePrivateKey(p8PEM []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(p8PEM)
	if block == nil {
		return nil, fmt.Errorf("apns: no PEM block found")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: parse PKCS8 key: %w", err)
	}
	ecKey, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("apns: key is %T, not an ECDSA private key", key)
	}
	return ecKey, nil
}
