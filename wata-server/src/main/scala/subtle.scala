package go

/** `go.subtle` — an APP-OWNED facade for stdlib `crypto/subtle` (the core
 *  facade does not cover it; same mechanism as `go.iolimit`).
 *  `constantTimeCompare` answers 1 when the two byte slices are equal and 0
 *  otherwise, in time that depends only on their length — the password and
 *  access-token checks go through it so a comparison cannot leak how much of
 *  a guess matched. (Slices of unequal length return 0 immediately; a
 *  secret's LENGTH is not protected, which is the standard contract.) */
@go.bind("crypto/subtle")
object subtle:
  @go.name("ConstantTimeCompare") def constantTimeCompare(x: go.Bytes, y: go.Bytes): scala.Int = ???
