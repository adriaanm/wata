// Package watamobile is the gomobile BIND SURFACE of the phone spike
// (plan 0023 M1): a hand-written Go shim over the sgola-emitted wataclient
// core, exposing only what gobind can carry across the ObjC/Java boundary.
//
// gobind's supported types are: signed integers, float32/64, bool, string,
// []byte, and interfaces/structs built from those. The emitted core's surface
// is nothing of the sort — `MatrixClient` holds channels, `StateSnapshot`
// holds sgola `List` cons cells, and every ADT case is a pointer-shaped
// struct behind a marker interface. So the boundary is strings, and the
// rendering happens in Sgola (Bind.report), where the domain types live.
//
// Nothing here is an API proposal. A real phone client gets a handle type and
// an event pump; this proves the toolchain.
package watamobile

import (
	watacore "github.com/adriaanm/wata/tools/phone-spike/watacore"
)

// Hello returns a build-identity line from the sgola-emitted package. It needs
// no server, so it is the smallest possible "the bound framework is really
// running our Go" check.
func Hello() string {
	return watacore.Bind_hello()
}

// Probe logs in to the homeserver, runs the sync loop until the first snapshot
// carrying the self user, and returns that snapshot rendered as lines
// (self / contacts / conversations / family). It BLOCKS for up to
// timeoutMillis; call it off the UI thread.
func Probe(homeserver, user, password string, timeoutMillis int) string {
	return watacore.Bind_probe(homeserver, user, password, int64(timeoutMillis))
}
