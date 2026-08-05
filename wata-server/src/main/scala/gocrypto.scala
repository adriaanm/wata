package go

import language.experimental.saferExceptions

/** `go.hashpkg` / `go.sha256` / `go.hmac` / `go.b64std` — the APP-OWNED
 *  facades the password hasher (pbkdf2.scala) needs on top of the core `go.*`
 *  surface. Same mechanism as `go.subtle` / `go.iolimit`: a `@go.bind` object
 *  per Go import path, bodies `???`, the import path rides the tree.
 *
 *  Naming: the core facade already owns `go.crypto` (`crypto/rand`) and
 *  `go.encoding.base64` (URL encoding only), and a second `object crypto` /
 *  `object encoding` in `package go` would collide — so these bind their Go
 *  packages under distinct object names, exactly as the tui's `go.osx` does
 *  for `os`. */

/** `go.hashpkg` — the `hash` package, curated to the ONE interface a keyed
 *  digest is used through: `hash.Hash`. Declared as a TRAIT (Go interfaces are
 *  traits in this facade language — `go.io.Reader` is the core's precedent);
 *  no wata type implements it, it is only ever the opaque type a
 *  `sha256.New()` / `hmac.New(...)` value carries.
 *
 *  `Sum(b)` APPENDS the digest to `b` and returns the result, so it is called
 *  with an empty slice and never mutates a caller buffer. `Reset()` is what
 *  lets one HMAC object be reused across PBKDF2's iteration loop instead of
 *  allocating a fresh one per block. */
@go.bind("hash")
object hashpkg:
  trait Hash:
    @go.name("Write") def write(p: go.Bytes): scala.Int throws sgo.GoError
    @go.name("Sum") def sum(b: go.Bytes): go.Bytes
    @go.name("Reset") def reset(): Unit

/** `go.sha256` — `crypto/sha256`, curated to the constructor. Bound WITHOUT
 *  parens so it emits as the bare function VALUE `sha256.New`, which is what
 *  `hmac.New` takes (`func() hash.Hash`). */
@go.bind("crypto/sha256")
object sha256:
  @go.name("New") def New: () => go.hashpkg.Hash = ???
  /** the digest size in bytes — PBKDF2's `hLen`. */
  @go.name("Size") def Size: scala.Int = ???

/** `go.hmac` — `crypto/hmac`, curated to the constructor: a keyed HMAC over
 *  the given digest, itself a `hash.Hash`. */
@go.bind("crypto/hmac")
object hmac:
  @go.name("New") def New(h: () => go.hashpkg.Hash, key: go.Bytes): go.hashpkg.Hash = ???

/** `go.b64std` — `encoding/base64`'s STANDARD alphabet. The core facade binds
 *  only `URLEncoding` (ids and tokens want the URL alphabet); the stored
 *  password hash uses the standard one, which is what every other
 *  `pbkdf2-sha256$...` implementation writes. */
@go.bind("encoding/base64")
object b64std:
  @go.name("StdEncoding") val StdEncoding: go.encoding.base64.Encoding = ???
