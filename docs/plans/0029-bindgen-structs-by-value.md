# 0029 — bindgen: C structs passed and returned by value

Status: accepted

## Problem

The generator refuses every declaration whose signature carries a C struct
by value — `NSRange`, `NSOperatingSystemVersion` today, and the entire
geometry vocabulary (`CGRect`, `CGPoint`, `CGSize`, `UIEdgeInsets`) that
M4's UIKit backend will need on nearly every view method. The refusal was
a placeholder, not a limitation of the ground under us: purego's
`RegisterFunc` classifies struct arguments and returns per AAPCS64 on
darwin/arm64 — registers for composites ≤ 16 bytes, the x8 indirect
result for larger returns, by-reference for larger arguments — and both
targets are arm64-only (macOS and iOS).

## Decision

**An allowlisted struct becomes a generated Go struct type.** Each
`bindgen.json` target gains a `structs` list. The loader dumps the record
declaration (a named record like `struct _NSRange`, or the anonymous
record clang prints immediately before its typedef, as with
`NSOperatingSystemVersion`) and carries name, fields and doc into the IR.
The emitter writes one Go struct per entry into `structs.go`, fields
mapped with the existing primitive table (`NSUInteger`→`uint`,
`NSInteger`→`int`, `CGFloat`→`float64`) and capitalized; a field may be
another allowlisted struct (`CGRect` nesting `CGPoint`/`CGSize`).

**No new dispatch machinery.** `objc.Send[T]` registers its typed call
through `purego.RegisterFunc` per invocation and `ID.Send`'s variadic
arguments are flattened into fixed register-classified arguments — both
paths already carry structs correctly on darwin/arm64. So a method with a
struct in its signature emits exactly like any other method; `objcrt` is
untouched and no per-signature msgSend registration is needed.
(`objc_msgSend_stret` exists only on amd64, which these bindings do not
target.)

**What stays refused**, per declaration with the existing reason
mechanism:

- a struct not on the allowlist — the reason now names the fix ("add it
  to structs") whenever clang's desugared spelling reveals a record;
- a struct with a bitfield, a union, an array field, or a field that is
  not a mappable primitive / nested allowlisted struct — the struct is
  refused once (its own line in REFUSALS.md) and every declaration using
  it points at that reason;
- a struct anywhere in a block signature (outgoing `objc.NewBlock` and
  the incoming-block trampolines ride on purego callbacks, which reject
  struct arguments);
- a struct parameter or return in a protocol callback, same ground.

## What changes (file-level)

- `tools/bindgen/bindgen.py` — `structs` in `Target`/IR, `collect_struct`
  in the loader, struct validation + `structs.go` in the emitter, the
  mapper's struct lookup and the sharper not-on-allowlist reason.
- `tools/bindgen/bindgen.json` — foundation grows
  `structs: [NSRange, NSOperatingSystemVersion]` and the
  `NSDataSearchOptions` enum (which un-refuses
  `rangeOfData:options:range:`, the register-returned NSRange proof).
- `tools/bindgen/fixtures/` — a `struct_by_value` fixture (mapped,
  nested, and each refused shape); `property.json`'s `CGRect` gains its
  real desugared spelling so the refusal reason is the actionable one.
- `tools/bindgen/test_bindgen.py` — mapping and stays-refused tests.
- `go-pkgs/appleptt/foundation/` — regenerated; the NSRange /
  NSOperatingSystemVersion lines come off REFUSALS.md; runtime tests for
  both return conventions.
- `docs/design/bindgen.md` — the mapping and the refusal list.

## Verification

- `just bindgen-tests` — fixtures byte-for-byte, refusal reasons pinned.
- `just bindgen` — regeneration, gofmt, `go vet`, ios/arm64 build.
- `just bindgen-runtime` — the ABI against the live runtime, both return
  conventions with field values pinned in both directions:
  `-[NSData rangeOfData:options:range:]` (16-byte NSRange in x0/x1, and
  NSRange arguments narrowing the search), and
  `NSProcessInfo.operatingSystemVersion` (24-byte struct via x8)
  cross-checked against `isOperatingSystemAtLeastVersion:` (a 24-byte
  by-value argument) answering true for the version just returned and
  false above it.
- `just ci` — the whole gate.

## Out of scope

Structs in block signatures and protocol callbacks (needs struct support
in purego's callback path); struct fields that are pointers or objects;
growing the UIKit allowlist itself (M4 names its own types; this plan
only removes the mapping obstacle).
