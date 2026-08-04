package go

/** `go.iolimit` — an APP-OWNED facade for stdlib `io.LimitReader` (the core
 *  facade does not cover it). The edge wraps a request body in it before
 *  `io.ReadAll`, so a request can hand the server at most n bytes: reading
 *  `maxBodyBytes + 1` and seeing more than `maxBodyBytes` is the overflow
 *  signal (413), and the real Go `io.ReadAll` keeps handling the stream's
 *  read loop and data-with-EOF finals. */
@go.bind("io")
object iolimit:
  @go.name("LimitReader") def limitReader(r: go.io.Reader | Null, n: scala.Long): go.io.Reader = ???
