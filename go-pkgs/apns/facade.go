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
//	PushChannel(host, token, speaker, room, event)     -> the HTTP status
//
// The credentials live in a package-level configured state rather than being
// threaded through every call, the same shape irohnet uses for "this process's
// live listener": they are boot-time facts, one set per process, and a Sgola
// caller has no way to hold an *ecdsa.PrivateKey anyway.
//
// One Client is cached per (host, topic), because the JWT cache lives on the
// Client and re-minting a provider token per push is exactly what Apple
// rejects — and PushChannel talks to a different topic than Push does.

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

// clientFor returns the cached Client for (host, topic), building one if
// needed. topic empty means the configured bundle id.
func clientFor(host, topic string) (*Client, error) {
	facadeMu.Lock()
	defer facadeMu.Unlock()
	if facadeKey == nil {
		return nil, fmt.Errorf("apns: not configured")
	}
	if topic == "" {
		topic = facadeTopic
	}
	key := host + "\x00" + topic
	if c, ok := facadeClients[key]; ok {
		return c, nil
	}
	c, err := New(Config{
		TeamID:     facadeTeamID,
		KeyID:      facadeKeyID,
		Topic:      topic,
		PrivateKey: facadeKey,
		Host:       host,
	})
	if err != nil {
		return nil, err
	}
	facadeClients[key] = c
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
	c, err := clientFor(host, "")
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

// PushChannel sends one PushToTalk push to an EPHEMERAL channel token: the
// token the PushToTalk framework minted for the app's current channel join,
// which is dead the moment that channel is left. It differs from Push in
// every header that matters — apns-push-type is `pushtotalk` and the topic is
// the bundle id plus PTTTopicSuffix, a topic Push never talks to — and the
// payload names the active speaker rather than describing a banner, because
// the system hands this push to the framework instead of presenting it.
//
// Like Push, the status is the answer: 410 means the caller must delete the
// channel registration. So does 400 `BadDeviceToken`, which is what a token
// belonging to a channel the phone has since left answers with — the caller
// decides, this reports.
func PushChannel(host, deviceToken, speaker, roomID, eventID string) (int, error) {
	c, err := clientFor(host, pttTopic())
	if err != nil {
		return 0, err
	}
	res, err := c.Send(context.Background(), deviceToken, ChannelPayload(speaker, roomID, eventID),
		SendOptions{PushType: PushTypePushToTalk, Topic: pttTopic()})
	if err != nil {
		return 0, err
	}
	if !res.OK() {
		fmt.Println("wata: apns pushtotalk rejected, status " + fmt.Sprint(res.StatusCode) + " reason " + res.Reason)
	}
	return res.StatusCode, nil
}

// pttTopic is the configured bundle id plus the PushToTalk suffix. Empty when
// nothing is configured, which clientFor turns into an error before any
// request is built.
func pttTopic() string {
	facadeMu.Lock()
	defer facadeMu.Unlock()
	if facadeTopic == "" {
		return ""
	}
	return facadeTopic + PTTTopicSuffix
}
