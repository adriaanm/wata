// Package httpc builds the plain-TCP *http.Client the wata clients use.
//
// It exists for one field: net/http.Client.Timeout. The bound net/http facade
// exposes the Client type but not its fields, and http.DefaultClient has NO
// timeout at all — a half-open connection (a server that accepted and then
// went away, the classic wifi-roam or NAT-drop case) leaves a request blocked
// forever, which upstream means a sync round or an action that never
// completes and a device wedged on its last good frame.
//
// The deadline is per REQUEST (dial + all redirects + reading the body), so
// it must comfortably exceed the client's longest legitimate request: the
// Matrix long-poll, which the client caps itself with the `timeout` sync
// parameter (seconds, not minutes).
package httpc

import (
	"net/http"
	"time"
)

// New returns a client whose every request is bounded by timeoutMs. A
// non-positive timeout means none, i.e. http.DefaultClient's behaviour.
func New(timeoutMs int64) *http.Client {
	if timeoutMs <= 0 {
		return &http.Client{}
	}
	return &http.Client{Timeout: time.Duration(timeoutMs) * time.Millisecond}
}

// WithTimeout returns a copy of c bounded by timeoutMs, keeping its transport
// (the iroh client's, for instance, whose dial is an iroh stream open). A nil
// client, or a non-positive timeout, answers New(timeoutMs).
func WithTimeout(c *http.Client, timeoutMs int64) *http.Client {
	if c == nil {
		return New(timeoutMs)
	}
	out := *c
	if timeoutMs > 0 {
		out.Timeout = time.Duration(timeoutMs) * time.Millisecond
	}
	return &out
}
