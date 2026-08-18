// Package apns sends push notifications through Apple's HTTP/2 provider API.
//
// It exists because APNs token auth needs an ES256 JWT and an HTTP/2 POST,
// neither expressible in the dialect — so this is plain Go behind the same
// kind of thin facade go-pkgs/irohnet and go-pkgs/audio already use. It needs
// no external dependency: crypto/ecdsa signs the JWT and Go's net/http
// negotiates HTTP/2 over TLS on its own.
//
// The host is a Config field, not a compile-time constant, so a local fake
// APNs server can stand in for Apple in tests: everything here — the JWT,
// the request shape, the 410-means-forget-this-token rule — is verifiable
// without a real key, a phone, or the developer portal.
package apns

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Well-known APNs hosts. A test passes its own httptest server URL instead.
const (
	ProductionHost = "https://api.push.apple.com"
	SandboxHost    = "https://api.sandbox.push.apple.com"
)

// tokenRefreshAfter bounds how long a minted JWT is reused. Apple rejects a
// refreshed token that is younger than 20 minutes and a token that is older
// than 60 minutes (InvalidProviderToken); 45 minutes sits comfortably inside
// that window on both sides.
const tokenRefreshAfter = 45 * time.Minute

// Config configures a Client. TeamID, KeyID and PrivateKey come from the
// Apple developer portal's APNs Auth Key (a .p8 file — parse it with
// ParsePrivateKey). Topic is the apns-topic header, normally the app's
// bundle id. Host selects which server to talk to; use ProductionHost or
// SandboxHost, or a test server's URL.
type Config struct {
	TeamID     string
	KeyID      string
	Topic      string
	PrivateKey *ecdsa.PrivateKey
	Host       string

	// HTTPClient is used for the POST. If nil, http.DefaultClient is used;
	// net/http negotiates HTTP/2 over TLS on its own, so no extra setup is
	// needed to talk to a real APNs host. A test pointed at a plaintext
	// httptest server must supply one configured for h2c, or run the fake
	// server over TLS.
	HTTPClient *http.Client

	// Clock returns the current time. If nil, time.Now is used. Overriding
	// it is how a test drives JWT refresh without sleeping.
	Clock func() time.Time
}

// Client sends pushes for one Config, caching and refreshing its JWT as
// needed. It is safe for concurrent use.
type Client struct {
	cfg Config

	mu       sync.Mutex
	token    string
	mintedAt time.Time
}

// New returns a Client for cfg. It does not itself contact APNs.
func New(cfg Config) (*Client, error) {
	if cfg.TeamID == "" || cfg.KeyID == "" || cfg.Topic == "" {
		return nil, fmt.Errorf("apns: Config needs TeamID, KeyID and Topic")
	}
	if cfg.PrivateKey == nil {
		return nil, fmt.Errorf("apns: Config needs a PrivateKey")
	}
	if cfg.Host == "" {
		return nil, fmt.Errorf("apns: Config needs a Host")
	}
	return &Client{cfg: cfg}, nil
}

func (c *Client) now() time.Time {
	if c.cfg.Clock != nil {
		return c.cfg.Clock()
	}
	return time.Now()
}

func (c *Client) httpClient() *http.Client {
	if c.cfg.HTTPClient != nil {
		return c.cfg.HTTPClient
	}
	return http.DefaultClient
}

// bearerToken returns the cached JWT, minting a fresh one if none exists yet
// or the cached one has passed tokenRefreshAfter.
func (c *Client) bearerToken() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := c.now()
	if c.token == "" || now.Sub(c.mintedAt) >= tokenRefreshAfter {
		tok, err := mintToken(c.cfg.TeamID, c.cfg.KeyID, c.cfg.PrivateKey, now)
		if err != nil {
			return "", fmt.Errorf("apns: mint token: %w", err)
		}
		c.token = tok
		c.mintedAt = now
	}
	return c.token, nil
}

// PushType selects the apns-push-type header. Alert is the tier-2 case: a
// visible, time-sensitive notification.
type PushType string

const (
	PushTypeAlert PushType = "alert"
)

// SendOptions overrides the per-request headers. The zero value sends an
// alert push at normal priority that APNs may hold and coalesce, expiring
// immediately if the device is unreachable.
type SendOptions struct {
	PushType   PushType      // default PushTypeAlert
	Priority   int           // default 10 (send immediately); Apple also accepts 5 and 1
	Expiration time.Duration // default 0 (do not store for later delivery)
}

func (o SendOptions) pushType() PushType {
	if o.PushType == "" {
		return PushTypeAlert
	}
	return o.PushType
}

func (o SendOptions) priority() int {
	if o.Priority == 0 {
		return 10
	}
	return o.Priority
}

// Result is what APNs said about one push. A non-200 StatusCode is not
// itself an error — Send returns an error only for a failure that never got
// a verdict from APNs (dial failure, malformed payload, and so on).
type Result struct {
	StatusCode int
	ApnsID     string // the apns-id response header, when present

	// Gone is true on 410: APNs is telling us the device token is dead. The
	// caller's whole job in that case is to delete the registration — never
	// retry, never keep sending to it.
	Gone bool

	// Reason is APNs' own JSON "reason" field, present on non-200 responses.
	// It is never swallowed; a caller logging a failed push should log this.
	Reason string
}

// OK reports whether APNs accepted the push (HTTP 200).
func (r *Result) OK() bool {
	return r.StatusCode == http.StatusOK
}

// Send POSTs payload to deviceToken. The request path, method and headers
// follow Apple's HTTP/2 provider API exactly, so a local fake server can
// assert them byte for byte.
func (c *Client) Send(ctx context.Context, deviceToken string, payload Payload, opts SendOptions) (*Result, error) {
	tok, err := c.bearerToken()
	if err != nil {
		return nil, err
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("apns: marshal payload: %w", err)
	}

	url := c.cfg.Host + "/3/device/" + deviceToken
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("apns: build request: %w", err)
	}
	req.Header.Set("authorization", "bearer "+tok)
	req.Header.Set("apns-topic", c.cfg.Topic)
	req.Header.Set("apns-push-type", string(opts.pushType()))
	req.Header.Set("apns-priority", strconv.Itoa(opts.priority()))
	req.Header.Set("apns-expiration", strconv.FormatInt(expirationHeader(opts.Expiration, c.now()), 10))

	resp, err := c.httpClient().Do(req)
	if err != nil {
		return nil, fmt.Errorf("apns: send: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("apns: read response: %w", err)
	}

	result := &Result{
		StatusCode: resp.StatusCode,
		ApnsID:     resp.Header.Get("apns-id"),
	}
	if resp.StatusCode == http.StatusGone {
		result.Gone = true
	}
	if resp.StatusCode != http.StatusOK && len(respBody) > 0 {
		var reason struct {
			Reason string `json:"reason"`
		}
		if err := json.Unmarshal(respBody, &reason); err == nil {
			result.Reason = reason.Reason
		}
	}
	return result, nil
}

// expirationHeader turns a duration into the apns-expiration header value: a
// Unix timestamp after which APNs should stop trying, or 0 to mean "do not
// store this notification, attempt delivery once."
func expirationHeader(d time.Duration, now time.Time) int64 {
	if d <= 0 {
		return 0
	}
	return now.Add(d).Unix()
}
