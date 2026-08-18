package go

import language.experimental.saferExceptions

/** `go.apns` — the APP-OWNED facade for the APNs pusher package
 *  `github.com/adriaanm/wata/go-pkgs/apns` (plan 0065 tier 2). Same mechanism
 *  as `go.irohnet`: a `@go.bind` object, bodies `???`, the package rides the
 *  emitted app module as a plain Go dependency (a `godep` line in sgo.build).
 *
 *  The pusher exists in Go because APNs token auth needs an ES256 JWT and an
 *  HTTP/2 POST, neither expressible in the dialect. What crosses this frontier
 *  is deliberately smaller than what the Go package offers: no Config struct,
 *  no `*ecdsa.PrivateKey`, no Payload, no Result — three calls over flat
 *  strings and ints (go-pkgs/apns/facade.go states the same contract from the
 *  Go side). The credentials are package-level state over there, armed once at
 *  boot, exactly as irohnet's live listener is.
 */
@go.bind("github.com/adriaanm/wata/go-pkgs/apns")
object apns:
  /** `apns.Configure(teamID, keyID, topic, keyPath)` — read the operator's
   *  `.p8` Auth Key and arm the pusher. Throws when the file is missing or is
   *  not a PKCS#8 EC key; the caller reports and stays disarmed. */
  @go.name("Configure") def configure(teamID: String, keyID: String, topic: String, keyPath: String): Unit throws sgo.GoError = ???

  /** `apns.Configured()` — did `configure` succeed? False is the ordinary
   *  state of a self-hosted install with no Apple account, and the send path
   *  must then do nothing at all. */
  @go.name("Configured") def configured(): Boolean = ???

  /** `apns.HostFor(env)` — the APNs host for a registration's environment
   *  ("sandbox" for a development build's token, production otherwise). A
   *  token minted against one environment is rejected by the other, so the
   *  environment belongs to the REGISTRATION rather than to the server. */
  @go.name("HostFor") def hostFor(env: String): String = ???

  /** `apns.Push(host, token, title, body, roomId, eventId, badge)` — one
   *  time-sensitive alert; answers the HTTP status APNs gave (410 = the token
   *  is dead, delete its registration). A rejection is a STATUS, not a throw;
   *  a throw means no verdict was reached at all (unconfigured, dial failure).
   *  `badge` below 0 leaves the app's badge count alone. */
  @go.name("Push") def push(host: String, deviceToken: String, title: String, body: String,
                            roomId: String, eventId: String, badge: go.Int): go.Int throws sgo.GoError = ???

  /** `apns.PushChannel(host, token, speaker, roomId, eventId)` — one
   *  PushToTalk push to an EPHEMERAL channel token (plan 0065 tier 3). Same
   *  answer discipline as `push`: the HTTP status, a throw only when no
   *  verdict was reached. It differs from `push` in everything Apple looks at
   *  — `apns-push-type: pushtotalk`, the topic is the bundle id plus
   *  `.voip-ptt`, and the payload names the ACTIVE SPEAKER rather than a
   *  banner, since the system hands this push to the PushToTalk framework
   *  instead of presenting it. A 4xx (410 Gone, or 400 BadDeviceToken for a
   *  channel the phone has left) means the token is dead. */
  @go.name("PushChannel") def pushChannel(host: String, deviceToken: String, speaker: String,
                                          roomId: String, eventId: String): go.Int throws sgo.GoError = ???
