//go:build !(darwin && iroh)

// Pure-Go no-op stub for every build except darwin with the `iroh` tag
// (milestone 1 is Mac-only; milestone 2 adds the device cross-build). It lets
// every ordinary build — native sgo builds, the CGO_ENABLED=0 linux/amd64
// cross-build — compile this package with no cargo, no staticlib. Every entry
// point errors loudly rather than silently serving over the wrong transport.

package irohnet

import (
	"errors"
	"net/http"
)

var errIrohStub = errors.New("irohnet: stub build (the real transport is darwin + `-tags iroh` — run mklib.py, then build with the tag)")

// GenKey — stub; see the darwin+iroh build.
func GenKey() (string, string, error) { return "", "", errIrohStub }

// Listener — stub type so callers compile; Listen always errors.
type Listener struct{}

// Listen — stub; see the darwin+iroh build.
func Listen(cfg *Config) (*Listener, error) { return nil, errIrohStub }

// NewHTTPClient — stub; see the darwin+iroh build.
func NewHTTPClient(path string) (*http.Client, error) { return nil, errIrohStub }

// Serve — stub; see the darwin+iroh build.
func Serve(path string, handler http.Handler) error { return errIrohStub }
