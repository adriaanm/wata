package apns

// The narrow surface wata-server reaches from Sgola (go.apns, apns.scala).
//
// The Client API above is struct-shaped — a Config with an *ecdsa.PrivateKey,
// a Payload with a nested aps dictionary, a *Result — and none of that crosses
// the facade frontier: the dialect binds flat scalar signatures. So the server
// sees exactly three calls, over flat strings and ints:
//
//	Configure(teamID, keyID, topic, keyPath)   once at boot, from the operator's config
//	HostFor(env)                               "sandbox"/"production" -> the APNs host
//	Push(host, token, title, body, room, event, badge) -> the HTTP status
//
// The credentials live in a package-level configured state rather than being
// threaded through every call, the same shape irohnet uses for "this process's
// live listener": they are boot-time facts, one set per process, and a Sgola
// caller has no way to hold an *ecdsa.PrivateKey anyway.
//
// One Client is cached per host, because the JWT cache lives on the Client and
// re-minting a provider token per push is exactly what Apple rejects.

import (
	"context"
	"crypto/ecdsa"
	"fmt"
	"os"
	"sync"
)

var (
	facadeMu      sync.Mutex
	facadeKey     *ecdsa.PrivateKey
	facadeTeamID  string
	facadeKeyID   string
	facadeTopic   string
	facadeClients = map[string]*Client{}
)

// Configure reads the APNs Auth Key at keyPath (a .p8 file) and arms the
// package for Push. Calling it again replaces the credentials and drops the
// per-host Clients, so a re-read picks up a rotated key. An error leaves the
// previous state untouched — a bad path must not disarm a working pusher.
func Configure(teamID, keyID, topic, keyPath string) error {
	if teamID == "" || keyID == "" || topic == "" || keyPath == "" {
		return fmt.Errorf("apns: Configure needs teamID, keyID, topic and keyPath")
	}
	pem, err := os.ReadFile(keyPath)
	if err != nil {
		return fmt.Errorf("apns: read key %s: %w", keyPath, err)
	}
	key, err := ParsePrivateKey(pem)
	if err != nil {
		return err
	}
	facadeMu.Lock()
	defer facadeMu.Unlock()
	facadeKey, facadeTeamID, facadeKeyID, facadeTopic = key, teamID, keyID, topic
	facadeClients = map[string]*Client{}
	return nil
}

// Configured reports whether Configure has succeeded. With no APNs credentials
// the server does nothing at all — no pushes, no errors — so this is the gate
// the send path reads.
func Configured() bool {
	facadeMu.Lock()
	defer facadeMu.Unlock()
	return facadeKey != nil
}

// HostFor maps a registration's environment to the APNs host. A device build's
// token is only valid against the sandbox and an App Store build's only against
// production, so the environment travels with the registration rather than
// being one server-wide setting.
func HostFor(env string) string {
	if env == "sandbox" {
		return SandboxHost
	}
	return ProductionHost
}

// clientFor returns the cached Client for host, building one if needed.
func clientFor(host string) (*Client, error) {
	facadeMu.Lock()
	defer facadeMu.Unlock()
	if facadeKey == nil {
		return nil, fmt.Errorf("apns: not configured")
	}
	if c, ok := facadeClients[host]; ok {
		return c, nil
	}
	c, err := New(Config{
		TeamID:     facadeTeamID,
		KeyID:      facadeKeyID,
		Topic:      facadeTopic,
		PrivateKey: facadeKey,
		Host:       host,
	})
	if err != nil {
		return nil, err
	}
	facadeClients[host] = c
	return c, nil
}

// Push sends one time-sensitive alert to deviceToken through host and returns
// the HTTP status APNs answered with. 410 is the status the caller must act on
// — it means the token is dead and its registration has to go.
//
// A non-200 is NOT an error: the status is the answer. An error is returned
// only when no verdict was reached (not configured, dial failure). APNs' own
// `reason` for a rejection is printed rather than swallowed, since nothing
// else in the server would ever say why a push stopped working.
//
// badge < 0 leaves the app's badge count unchanged; 0 clears it.
func Push(host, deviceToken, title, body, roomID, eventID string, badge int) (int, error) {
	c, err := clientFor(host)
	if err != nil {
		return 0, err
	}
	var badgep *int
	if badge >= 0 {
		badgep = &badge
	}
	res, err := c.Send(context.Background(), deviceToken, AlertPayload(title, body, roomID, eventID, badgep), SendOptions{})
	if err != nil {
		return 0, err
	}
	if !res.OK() {
		fmt.Println("wata: apns push rejected, status " + fmt.Sprint(res.StatusCode) + " reason " + res.Reason)
	}
	return res.StatusCode, nil
}
