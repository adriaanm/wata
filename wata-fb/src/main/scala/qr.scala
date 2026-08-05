package go

import language.experimental.saferExceptions

/** `go.qr` — the APP-OWNED facade for `go-pkgs/qr`, a thin adapter over
 *  `rsc.io/qr`, which it requires as an ORDINARY fetched Go dependency (plan
 *  0014's revised QR ruling; see go-pkgs/qr/README). The whole surface is one
 *  call: text in, the module grid
 *  out as one byte per module (1 = dark), row-major, `side*side` long — the
 *  device draws those modules into the RGB565 buffer itself (enrol.scala),
 *  because a PNG would be a detour on the way to a framebuffer.
 *
 *  The side length is not a second return value: the facade binds one result
 *  plus an error, and `side` is the integer square root of the length, which
 *  the caller recomputes. */
@go.bind("github.com/adriaanm/wata/go-pkgs/qr")
object qr:
  /** `qr.Matrix(text)` — `([]byte, error)`, level L. */
  @go.name("Matrix") def matrix(text: String): go.Bytes throws sgo.GoError = ???
