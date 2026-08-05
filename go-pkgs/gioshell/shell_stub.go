//go:build !gioshell

// The window-free build: everything without the `gioshell` tag. wata-fb is
// cross-built for the armv7 device and for linux/amd64, neither of which has
// (or wants) a window toolkit, and `just ci` builds it many times over — so
// Gio is opt-in exactly the way go-pkgs/irohnet's real transport is. Start
// errors loudly rather than silently doing nothing; `wata-fb gio` on an
// untagged binary therefore says how to get a real one.

package gioshell

import "errors"

var errStub = errors.New("gioshell: stub build — rebuild the emitted Go with `-tags gioshell` (see just phone-blit)")

// Start — stub; see the `gioshell` build.
func Start(w, h, scale, maxFrames int) error { return errStub }

// Main — stub; see the `gioshell` build.
func Main() { _ = errNotStarted }
