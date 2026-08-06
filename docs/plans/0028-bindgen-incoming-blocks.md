# 0028 — bindgen: blocks received in protocol callbacks

Status: accepted

## Problem

The generator refuses any protocol-callback parameter that is a block:
"the block would outlive the call". That refusal gates the PushToTalk
incoming-push path — `PTChannelManagerDelegate`'s
`incomingServiceUpdatePushForChannelManager:…withCompletionHandler:`
hands the app a completion block (`void (^)(void)`) that must be called
*after* the delegate method returns, once the app has decided what to do
with the push. Owner-ruled 2026-08-06: build the mapping.

The lifetime problem is real: the block pointer the trampoline receives
is only valid for the duration of the call unless it is copied. And
purego's `objc.Block.Invoke` cannot call a framework-created block — it
looks the closure up in purego's own cache, which only holds blocks Go
created. Calling an incoming block means going through the block
layout's own invoke pointer with the correct C ABI.

## Decision

**objcrt grows an incoming-block handle.** `objcrt.CopyBlock(b)` runs at
trampoline entry and `_Block_copy`s the block; the returned
`*BlockHandle` is safe to use from any goroutine:

- `Invoke(args ...any)` calls through the block's invoke pointer (offset
  16 of the block layout: isa 8, flags 4, reserved 4), with the block
  pointer itself as argument 0 — the ObjC block calling convention —
  followed by the declared arguments. The call is made through
  `purego.RegisterFunc` over a reflect-built function type, so floats
  ride in FP registers and the ABI is right for every mappable
  parameter kind.
- `Release()` is `_Block_release`, exactly once. A second `Release`
  panics; `Invoke` after `Release` panics (that would be a
  use-after-free). `Invoke` may be called more than once while the
  handle is live — some ObjC blocks are legitimately repeat-callable —
  and an `RWMutex` makes Invoke/Release safe to race.
- `CopyBlock(0)` returns nil (a nullable block the framework did not
  pass); calling through a nil handle panics.

**The generator maps the block parameter to a named handle type.** For a
callback block signature whose parameters are all mappable and whose
return is void, the struct-of-func-fields field gets a per-signature
wrapper type emitted into `blocks.go` — `IncomingBlockVoid`,
`IncomingBlockFloat64String`, … (named from the Go parameter types,
deduplicated package-wide) — with a typed `Call` (the existing
`to_objc` conversions apply per argument) and `Release`. The trampoline
constructs it via `objcrt.CopyBlock` before user code runs.

**What stays refused**, with the per-declaration reason mechanism:
blocks with a non-void return ("incoming block with a non-void return"
— completion handlers return void; a synchronous-answer block can be
mapped when something needs it), nested blocks (the existing "nested
block" refusal — the same mapping does not cover a block that itself
carries a block parameter), block parameters that are themselves
unmappable (struct-by-value, un-allowlisted classes), and a callback
*returning* a block.

## What changes (file-level)

- `go-pkgs/appleptt/objcrt/objcrt.go` — `BlockHandle`, `CopyBlock`,
  `Invoke`, `Release`; plus a Mac-gated runtime test
  (`blocks_darwin_test.go`, tag `objcruntime`) pinning the ABI (floats,
  ints, object args), cross-goroutine invocation, and the panic
  semantics.
- `tools/bindgen/bindgen.py` — the incoming-block mapping in
  `emit_protocol`, the handle-type emitter (`blocks.go`), the new
  refusal reasons.
- `tools/bindgen/fixtures/protocol.*` + `test_bindgen.py` — the mapped
  case (void and with-arguments blocks), the still-refused cases.
- `tools/bindgen/bindgen.json` — foundation target grows `NSURLSession`,
  `NSURLSessionDataDelegate`, `NSURLSessionResponseDisposition`, opaque
  `NSURLSessionConfiguration`/`NSURLSessionDataTask`/`NSURLResponse`:
  the runtime proof that a real framework hands a block into a
  synthesized delegate and the app calls it later.
- `go-pkgs/appleptt/`, `go-pkgs/appleptt/foundation/` — regenerated; the
  PTT incoming-push refusal comes off `REFUSALS.md`.
- `docs/design/bindgen.md` — the mapping, the handle contract, the
  refusal list.

## Verification

- `just bindgen-tests` — fixtures cover the mapped shapes byte-for-byte
  and the refusal reasons.
- `just bindgen` — regeneration, gofmt, `go vet`, ios/arm64 build.
- `just bindgen-runtime` — the handle against the live runtime: a
  hand-rolled block invoked through the handle (argument values pinned
  exactly, including a float), and `NSURLSession` handing
  `didReceiveResponse:completionHandler:`'s block to a generated
  delegate; the Go side stores the handle, returns, calls it from
  another goroutine with `NSURLSessionResponseDispositionAllow`, and
  `didReceiveData:` observably delivers the payload. Double-release and
  call-after-release panic behavior pinned.
- `just ci` — the whole gate.

## Out of scope

Running the PTT path on a phone (needs the restricted entitlement —
`PTT-HELLO-HARDWARE`); non-void incoming blocks; nested blocks; any
change to outgoing-block mapping.
