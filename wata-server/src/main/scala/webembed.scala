package go

/** `go.webembed` — the APP-OWNED `@go.bind` facade for `wata-server/adminui`,
 *  a plain Go package that carries the admin page as a `go:embed`ed asset
 *  (plan 0021). Same shape as `go.irohnet`: the package rides the emitted app
 *  module as an ordinary Go dependency (require + local replace, written by
 *  the sgo go.mod stage from `wata-server/sgo.build`'s `godep` line).
 *
 *  The page lives NEXT TO that Go source (`adminui/index.html`) because
 *  `go:embed` cannot reach outside its own package directory — which is also
 *  why the asset is a Go package at all rather than a file this module reads
 *  at runtime: the server binary stays the only thing a deployment copies. */
@go.bind("github.com/adriaanm/wata/wata-server/adminui")
object webembed:
  /** `adminui.IndexHTML()` — the admin page's HTML source. */
  @go.name("IndexHTML") def indexHTML(): String = ???
