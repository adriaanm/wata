package go

import language.experimental.saferExceptions

/** `go.apns` — the APP-OWNED facade for `github.com/adriaanm/wata/go-pkgs/apns`,
 *  which is now the operator's ES256 signing key and nothing else. Same
 *  mechanism as `go.irohnet`: a `@go.bind` object, bodies `???`, the package
 *  rides the emitted app module as a plain Go dependency (a `godep` line in
 *  sgo.build).
 *
 *  What is Go here is the technology boundary alone — parse a PEM-wrapped
 *  PKCS#8 EC key, and sign with it. Everything a push consists of (the JWT's
 *  header and claims, the refresh window, the headers, the POST, and what a
 *  status means) is dialect code in apnspush.scala.
 *
 *  The key is package-level state over there, armed once at boot, exactly as
 *  irohnet's live listener is: one operator key per process, and a Sgola caller
 *  has no way to hold an `*ecdsa.PrivateKey` anyway.
 */
@go.bind("github.com/adriaanm/wata/go-pkgs/apns")
object apns:
  /** `apns.LoadKey(path)` — read the operator's `.p8` Auth Key and arm the
   *  signer. Throws when the file is missing or is not a PKCS#8 EC key,
   *  leaving any previously loaded key armed. */
  @go.name("LoadKey") def loadKey(path: String): Unit throws sgo.GoError = ???

  /** `apns.Loaded()` — is a key armed? False is the ordinary state of a
   *  self-hosted install with no Apple account. */
  @go.name("Loaded") def loaded(): Boolean = ???

  /** `apns.SignES256(signingInput)` — the JWS signature for a provider token's
   *  `<header>.<claims>`, base64url without padding: the third segment, ready
   *  to append. Raw R||S (64 bytes for P-256), never ASN.1 DER — the classic
   *  mistake, and the reason this is a Go primitive with a Go test rather than
   *  a call to a generic signer. Throws when no key is armed. */
  @go.name("SignES256") def signES256(signingInput: String): String throws sgo.GoError = ???

/** `go.b64url` — `encoding/base64`'s RAW URL alphabet (no padding), which is
 *  what a JWT segment is. The core facade binds only `URLEncoding` (padded)
 *  and `go.b64std` (gocrypto.scala) the standard alphabet the stored password
 *  hash uses; a third object binds the same Go package under its own name for
 *  the same reason those two do — `package go` can hold only one
 *  `object encoding`. */
@go.bind("encoding/base64")
object b64url:
  @go.name("RawURLEncoding") val RawURLEncoding: go.encoding.base64.Encoding = ???
