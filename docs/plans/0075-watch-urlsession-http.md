# 0075 — The watch's HttpDo over NSURLSession

Status: accepted

Implements `WATCH-URLSESSION-HTTP`, step 1 of plan 0074.

## The problem

watchOS denies BSD sockets to third-party apps (proven on the Series 10,
2026-08-23: every dial, LAN or public, over wifi or the Bluetooth proxy,
fails EHOSTUNREACH). Go's net stack is raw sockets, so the net/http-backed
`HttpDo` in `caps.scala` can never complete a request from the wrist —
the watch client cannot log in on hardware, which also blocks
`WATCH-AUDIO`'s receive half. Third-party networking is NSURLSession or
nothing; URLSession traffic also transparently rides the phone's
Bluetooth proxy, which sockets never would.

## The decision

An NSURLSession-backed `HttpDo`, implemented **in Sgola** — the FFI
frontier (docs/design/sgola-ffi.md) closes both crossings this needs:
`objc_msgSend` out through `go.purego.syscallN` with C strings in
`go.cstring` brackets (objc-spike), and delegate methods in as ObjC
methods whose IMPs are `go.callback`-registered literals
(callback-spike). No new Go package; the only Go under it is purego
itself, already injected by the compiler.

Shape (the macaudio delegate idiom, in dialect terms):

- One serial NSURLSession (`sessionWithConfiguration:delegate:delegateQueue:`
  with a nil queue), built once at boot against one long-lived
  `WataHttpDelegate : NSObject` synthesized with `class_addMethod`, its two
  IMPs registered at boot scope: `URLSession:dataTask:didReceiveData:`
  appends the chunk to a shared NSMutableData accumulator;
  `URLSession:task:didCompleteWithError:` records the error handle and
  signals completion. Shared cells are `sgo.Atomic`/`sgo.Chan` — the
  CONC-8 fork predicate at the registration site demands synchronizers,
  and the delegate callbacks arrive on the session's own thread.
- `send` is synchronous, like every other `HttpDo`: build an
  NSMutableURLRequest (method, headers, optional body), resume a data
  task, then `sgo.select2` between the done channel and `go.time.After`
  for the per-request deadline (30s default, `WATA_HTTP_TIMEOUT_MS`
  override — the same contract the socket impl documented).
- Status from `[task response]`'s `statusCode` guarded by
  `respondsWithSelector:`; transport failure prints the cause line
  (`http: <method> <url> failed: <cause>`) and folds to
  `HttpResponse(0, "")` exactly as before — status 0 stays all the core
  sees.

Two dialect constraints shaped the byte paths, recorded because they are
the interesting part:

- **Sgola cannot dereference.** Neither direction of raw bytes can cross
  directly: a `go.Uintptr` cannot be read into `go.Bytes`, and
  `go.bytes(s)` cannot be turned into an address. So both bodies travel
  by file: the request body is written to a sandbox temp file
  (`syscall.write`, FbConfig's proven pattern) and handed over as
  `[NSData dataWithContentsOfFile:]`; the accumulated response is written
  out with `[NSData writeToFile:atomically:]` and read back with
  `go.sys.readFile`. A failing request's `localizedDescription` rides
  the same route so the cause line names something real. Files are
  unlinked after each request.
- **No doubles across msgSend.** `SyscallN` passes integer registers
  only, so `setTimeoutInterval:` (a double) is unreachable; the deadline
  is enforced Sgola-side by the select above instead of
  NSURLSession's own timeout. Zero arguments are spelled by omission
  (SyscallN zero-fills), which covers the nil delegateQueue.

Autorelease discipline: every callback body and the send-side
construction run inside an `NSAutoreleasePool` alloc/drain bracket —
delegate callbacks land on threads with no pool, and per-request objects
must not accumulate on a wrist that syncs for months.

## What changes

- `wata-watch/src/main/scala/nsurlsession.scala` — new: the whole thing
  (ObjC runtime resolution, delegate synthesis, the session, `send`).
- `wata-watch/src/main/scala/caps.scala` — `httpDo()` and `plainHttp()`
  return the URLSession impl; the socket transports (`IosHttp` over
  net/http, `irohClient`) are deleted from this app — plan 0074 ruled
  the watch never speaks iroh, and sockets never dial. Enrol's iroh
  config seam itself stays (it still provisions credentials); its
  transport half is now inert here.
- `tools/watchrun.py`, `tools/watch-device.py` — `NSAppTransportSecurity`
  / `NSAllowsArbitraryLoads` in both Info.plist bundlers: dev servers are
  plain http on LAN addresses, which ATS otherwise refuses; localhost
  (watch-e2e) is exempt either way.

## Verification

- `just watch-interptest` — unchanged oracle (no HTTP), proves nothing
  else moved.
- `just watch-e2e` — the real gate: full Matrix login/sync/send/receive
  on the simulator, now over the URLSession transport instead of
  sockets. Same assertions, swapped underneath.
- Hardware (`just watch-wrist`) is the owner's leg: the same legs must
  go green from the wrist, where the socket wall made them structurally
  impossible.

## Out of scope

- iroh removal from the watch's enrol flow (its transport half goes
  inert here; the credential/config half still serves until plan 0074
  steps 3–5 reshape it).
- QUIC/iroh-behind-URLSession questions — rejected in plan 0074.
- Retiring `go-pkgs/httpc` everywhere (`RETIRE-HTTPC-SHIM`) — wata-fb
  and the iOS app still dial with sockets legitimately.
