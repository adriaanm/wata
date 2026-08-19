# 0068 — the APNs pusher moves into Sgola, minus the key handling

Status: proposed

`[APNS-TO-SGOLA]`

`go-pkgs/apns` is 441 lines of hand-written Go carrying the whole push
protocol: the JWT, the header set, the POST, and what a status code
means. Its own header says why it is Go — "APNs token auth needs an ES256
JWT and an HTTP/2 POST, neither expressible in the dialect". Half of that
is no longer true, and the other half is smaller than it looks.

## The problem

**The HTTP/2 claim is false today.** Sgola already issues arbitrary HTTP
requests through the bound `net/http` facade: `newRequest`,
`Request.header.set` for any header, `Client.Do`, `Response.statusCode`,
`Response.body` + `io.readAll` — that is exactly what every wata client's
`HttpDo` impl does (`wata-fb/src/main/scala/caps.scala`), and Go's
`net/http` negotiates HTTP/2 over TLS with no extra setup, so the
negotiation is a property of the client we already hold rather than
something the caller expresses.

**The ES256 claim is true but narrow.** What genuinely needs Go is
reading the operator's `.p8` (PEM + PKCS#8 → an `*ecdsa.PrivateKey`) and
signing with it. Everything around it — the JOSE header, the claims, the
base64url segments, when to mint a fresh token — is ordinary logic.

And the precedent for exactly this split is already in the tree:
`pwhash.scala`/`pbkdf2.scala` implement PBKDF2 **in Sgola** over
per-package binds (`go.sha256`, `go.hmac`, `go.hashpkg`,
`go.encoding.base64`) declared in `gocrypto.scala`. The algorithm is
dialect code; only the primitives are Go. APNs is the same shape.

## The decision

**Keep in Go, in `go-pkgs/apns` (~80 lines):** the key. `LoadKey(path)`
parses the PEM/PKCS#8 `.p8` and holds the `*ecdsa.PrivateKey`;
`SignES256(input)` returns the base64url JWS signature for a signing
input. Both are technology-boundary primitives in this repo's own sense
(`docs/design/sgola-ffi.md`'s first category), and neither decides
anything.

**Move to Sgola, in `wata-server`:** the pusher.
- the JOSE header `{alg:ES256,kid}` and claims `{iss,iat}`, JSON-built
  and base64url-encoded with the facades already bound;
- the mint-and-cache policy (Apple rejects a token refreshed younger
  than 20 minutes or older than 60; 45 minutes sits inside both);
- the header set per push type: `apns-topic` (bundle id, or bundle id +
  `.voip-ptt` for a `pushtotalk` push — a different topic for the same
  bundle), `apns-push-type`, `apns-priority`, `apns-expiration`;
- the POST and the verdict: 200 is accepted, **410 means the token is
  dead and the caller must forget it**, every other status surfaces
  APNs' own `reason` rather than swallowing it.

**Why this is worth doing at all**, beyond the goal of shrinking the Go
surface: every bug this code can have lives in the part that moves. The
JWT's claims, the topic suffix, and the 410 rule are decisions, and this
repo's own division of labour puts decisions in the dialect and leaves Go
holding the primitives.

## What changes

- `go-pkgs/apns/` — reduced to key loading and signing. `apns.go`'s
  Client, `Send`, `bearerToken`, `SendOptions`, `Result` and both facade
  push functions go; `apns_test.go` keeps the signing test (that a
  signature verifies against the public key) and loses the request-shape
  and JWT-claims tests, which move with the code they describe.
- `wata-server/src/main/scala/apns.scala` — the facade shrinks to the two
  primitives.
- `wata-server/src/main/scala/apnspush.scala` (new) — the pusher.
- `wata-server/src/main/scala/push.scala` — calls it instead of
  `go.apns.push` / `go.apns.pushChannel`. The call sites keep their
  shape; what they call is dialect code now.

## How it is verified

The oracle already exists and is in `ci`, which is what makes this port
safe to make at all: `push-smoke` (per-message fan-out, the
410-forgets-the-token rule, silence with no APNs configured) and
`ptt-smoke` (the `pushtotalk` type at the `.voip-ptt` topic, and what a
channel-holding device gets) both run the real server against a fake APNs
server that captures every request's headers and payload.

Two additions, because the port moves code those smokes do not currently
look closely at:
- **`tools/pushkit.py` asserts the JWT.** The fake server already
  captures the `authorization` header and ignores its contents. It will
  decode the three segments and check `alg`/`kid` in the header and
  `iss`/`iat` in the claims, so the assertions the Go unit tests made
  survive the code moving — at the level that matters, which is what
  Apple would actually see.
- **The refresh policy gets a Sgola-side check** in the same style as
  `pbkdf2`'s: one mint, a clock step inside the window (same token), a
  step past it (a new token). It is pure given an injected clock.

`apns-tests` stays in ci for the signing primitive.

## Out of scope

- Any change to what wata pushes, when, or to whom. This is a port: the
  smokes must pass unchanged apart from the strengthened JWT assertions.
- The `apns-id` response header, which the Go client read and only
  logged. `HttpResponse` in the client capability carries status and body
  only; the pusher here uses the `net/http` facade directly, so reading
  it back is possible — but nothing consumes it, so it is not ported
  until something does.
- The other Go in this repo. The pass this plan comes out of found the
  rest genuinely boundary-bound (cgo, reflection-driven `objc.Send`, the
  platform entry points that OWN the process); `docs/design/sgola-ffi.md`
  records that verdict.
