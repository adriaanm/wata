//go:build !(iroh && (darwin || (linux && arm)))

// Pure-Go no-op stub for every build without the `iroh` tag (and for tagged
// builds on targets with no staged staticlib: darwin and linux/arm — the
// BQ268 device — are the wired targets). It lets every ordinary build —
// native sgo builds, the CGO_ENABLED=0 linux/amd64 cross-build — compile
// this package with no cargo, no staticlib. Every entry point errors loudly
// rather than silently serving over the wrong transport.

package irohnet

import (
	"errors"
	"net/http"
)

var errIrohStub = errors.New("irohnet: stub build (the real transport needs `-tags iroh` on darwin or linux/arm — run mklib.py [arm], then build with the tag)")

// GenKey — stub; see the darwin+iroh build. EnsureKey (irohnet.go, shared)
// rides this, so a device build without the real transport reports that it
// cannot mint an identity rather than writing a fake one.
func GenKey() (string, string, error) { return "", "", errIrohStub }

// IDOf — stub; see the darwin+iroh build.
func IDOf(secretHex string) (string, error) { return "", errIrohStub }

// Listener — stub type so callers compile; Listen always errors.
type Listener struct{}

// Listen — stub; see the darwin+iroh build.
func Listen(cfg *Config) (*Listener, error) { return nil, errIrohStub }

// Allow — stub; see the darwin+iroh build. The enrolment approve path calls
// this to apply an allowlist addition to the live listener; without the real
// transport there is no listener, and the durable config-file write (the
// server's own job) is the whole of the approval.
func Allow(nodeID string) error { return errIrohStub }

// Disallow — stub; see the darwin+iroh build.
func Disallow(nodeID string) error { return errIrohStub }

// NewHTTPClient — stub; see the darwin+iroh build.
func NewHTTPClient(path string) (*http.Client, error) { return nil, errIrohStub }

// Serve — stub; see the darwin+iroh build.
func Serve(path string, handler http.Handler) error { return errIrohStub }
