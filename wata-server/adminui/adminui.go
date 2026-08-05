// Package adminui carries the admin web page as a compiled-in asset.
//
// The page is one hand-written, dependency-free HTML file living next to this
// source, embedded with go:embed so the server binary is still the only thing
// that has to be copied to a machine. wata-server reaches it through the
// `go.webembed` facade (webembed.scala) and serves it at GET /admin.
package adminui

import _ "embed"

//go:embed index.html
var indexHTML string

// IndexHTML returns the admin page's HTML source.
func IndexHTML() string { return indexHTML }
