# bindgen — generated Go over the Objective-C runtime

How wata calls Apple frameworks: `tools/bindgen` reads the SDK headers through
clang's JSON AST and emits Go wrappers over `objc_msgSend`, dispatched by
[purego](https://github.com/ebitengine/purego). No Swift in the repo, no
hand-written binding per method, no cgo in the bindings themselves.

Decisions and their reasoning: [plan 0026](../plans/0026-bindgen.md).

| where | what |
|-------|------|
| `tools/bindgen/bindgen.py` | the generator: AST → IR → Go |
| `tools/bindgen/bindgen.json` | the allowlist, one entry per output package |
| `tools/bindgen/regen.sh` | `just bindgen` — regenerate and check |
| `tools/bindgen/fixtures/` | AST excerpts + expected Go, one per decl shape |
| `tools/bindgen/test_bindgen.py` | `just bindgen-tests`, in `just ci` |
| `tools/bindgen/hello/` | the PushToTalk hello (`just ptt-hello`) |
| `go-pkgs/appleptt/` | generated output + `objcrt`, the hand-written runtime |

## The pipeline

**`load`** builds an umbrella `.m` importing the frameworks a target names,
runs `clang -Xclang -ast-dump=json` once per allowlisted name with
`-Xclang -ast-dump-filter=<name>`, and walks the matched nodes into a small
JSON-able IR. The filter is not an optimization detail: an unfiltered dump of
Foundation + UIKit + PushToTalk is ~116 MB of JSON.

**`emit`** turns the IR into Go. It touches no SDK and no clang, which is why
the unit tests can run it in `just ci` on committed fixtures.

Three things the AST makes harder than it looks, all handled in `load`:

- **A class's API is spread over its categories.** `NSData`'s creation methods
  are in `@interface NSData (NSDataCreation)`. Worse, when a category's *own*
  name does not match the dump filter, clang prints its methods as loose
  top-level nodes; `mangledName` (`+[NSData dataWithBytes:length:]`) is what
  attributes them. Members are merged from the interface, matching categories
  and loose nodes, then deduplicated by (instance, selector).
- **There are no comment nodes.** clang's JSON dump carries no doc comments at
  any `-fparse-all-comments` setting, so they are read back out of the header
  by byte offset — walk back from the declaration over whitespace, collect the
  `///` run or the `/** */` block. clang's offsets are **1-based**, and `loc.file`
  is printed only when it changes, so files are stamped onto nodes by walking
  the dump in order.
- **Class properties** (`@property (class, …)`, e.g. `+[NSProcessInfo processInfo]`)
  carry `"class": true` and become methods on the class-object type.

## What the emitted Go looks like

```go
type PTChannelManager struct{ objc.ID }        // an instance
type PTChannelManagerClass struct{ objc.Class } // its class object
func GetPTChannelManagerClass() PTChannelManagerClass
func (c PTChannelManagerClass) Alloc() PTChannelManager

func (o PTChannelManager) RequestJoinChannelWithUUIDDescriptor(
	channelUUID NSUUID, descriptor PTChannelDescriptor) {
	o.ID.Send(selRequestJoinChannelWithUUIDDescriptor, channelUUID.ID, descriptor.ID)
}
```

A selector becomes a Go name by capitalizing each keyword and concatenating;
selectors are registered once in a package-level `var` block in `selectors.go`;
one file per class or protocol, plus `enums.go`. Ordering is sorted everywhere,
so regeneration is a reviewable diff. Instance methods hang off the value type,
class methods off the class type, and a name collision between two selectors is
a refusal rather than a silent rename.

**Type mapping.** `NSString *`↔`string`, `NSError *`↔`error`, a *trailing*
`NSError **` becomes a second `error` return, `BOOL`→`bool`, `NSInteger`→`int`,
`instancetype`→the class, `id`/`id<P>`→`objc.ID`, an allowlisted class→its
wrapper, `NS_ENUM`/`NS_OPTIONS`→a named Go integer type with its constants, and
blocks→Go funcs in both directions (`objc.NewBlock` on the way in, a Go call on
the way out).

**Protocols are a struct of func fields**, not a Go interface:

```go
type PTChannelManagerDelegate struct {
	ChannelManagerDidJoinChannelWithUUIDReason func(PTChannelManager, NSUUID, PTChannelJoinReason)
	…
}
func NewPTChannelManagerDelegate(d PTChannelManagerDelegate) objc.ID
```

`New…` registers a fresh ObjC class conforming to the protocol and installs an
IMP per non-nil field. A nil field is simply not installed, which *is* ObjC's
optional-method semantics — `-respondsToSelector:` answers NO — and an
interface could not express that. It also lets one unmappable callback be
refused without taking the whole protocol down with it.

**Opaque classes.** A class named in `opaque` gets a wrapper type with no
methods, so signatures mentioning it map. `classes` versus `opaque` is the
knob that keeps the allowlist small without refusing half the methods.

## Refusals

Anything the mapper cannot express is refused **per declaration, with a
reason**, and written to `REFUSALS.md` beside the generated code. Refusing is
not failing: the class still binds, the other methods still work. The refusal
list is the worklist — each line is either a mapping the generator should learn
or an allowlist entry that should go away. Shapes refused today: struct
parameters and returns (`NSRange`, `NSOperatingSystemVersion`), raw pointer
pairs (`void *` + length), enums not on the allowlist, classes not on the
allowlist, nested blocks, a non-trailing `NSError **`, and blocks in a protocol
callback (the block would outlive the call).

`NS_UNAVAILABLE` and deprecated declarations are skipped silently: the SDK is
saying "not callable", which is not a gap.

## objcrt — the hand-written runtime

Generated code contains no logic; it maps selectors onto `go-pkgs/appleptt/objcrt`:
string/error/data bridging, `ErrOut` (the `NSError **` slot, which holds a
`uintptr` so no Go pointer is ever visible to the callee), and `NewDelegate`.
A bridging bug is fixed there, once, rather than in thousands of generated lines.

Two rules the bridging assumes, both ordinary ObjC contracts:

- **Blocks are copied by whoever stores them.** A generated wrapper releases
  its block when the call returns; an API that keeps a completion handler
  copies it first (all of Apple's do), so the block survives.
- **Synthesized delegate classes and instances live forever.** The ObjC runtime
  cannot unregister a class, and delegates are usually held weakly, so
  `objcrt` keeps every instance alive. Delegates are meant to be created a
  bounded number of times — one per channel manager — never per event.

## How it is verified

| leg | command | needs |
|-----|---------|-------|
| generator unit tests | `just bindgen-tests` (in `just ci`) | nothing |
| regenerate + gofmt + `go vet` + ios/arm64 build | `just bindgen` | Xcode |
| the ObjC runtime, for real | `just bindgen-runtime` | macOS |
| the PushToTalk hello | `just ptt-hello` | Xcode (+ a phone to run it) |

The unit tests compare emitted Go **byte for byte** against
`fixtures/<shape>.expected/`, one fixture per shape the mapper has to get right
(object returns, `NSError **`, blocks, protocols, enums, properties,
categories), plus the refusal reasons and the doc extractor.

The runtime tests are the answer to "does any of this actually dispatch". They
are Mac-gated behind the `objcruntime` build tag and generated from the *macOS*
Foundation SDK — a second target in the same allowlist — because PushToTalk
exists only on iOS hardware while the machinery under it is identical. They
prove, against the live runtime: class properties and object/string/int/float
returns, a string round trip through a setter, an object round trip through an
initializer, bytes through `NSData`, a real `NSError **` failure with its
domain and localized description, a Go closure invoked as a block by
`NSNotificationCenter`, a Go function invoked by selector from Foundation, and
a generated `NSXMLParserDelegate` whose five callbacks `NSXMLParser` drives in
order with bridged arguments.

## The allowlist today

`appleptt` (iPhoneSimulator SDK, Foundation + UIKit + AVFAudio + PushToTalk):
`PTChannelManager`, `PTChannelDescriptor`, `PTPushResult`, `PTParticipant`,
`NSUUID`; the `PTChannelManagerDelegate` and `PTChannelRestorationDelegate`
protocols; seven PT enums; `NSData`, `NSDictionary`, `UIImage`,
`AVAudioSession` opaque.

`foundation` (macOS SDK): `NSProcessInfo`, `NSNotification`,
`NSNotificationCenter`, `NSXMLParser`, `NSUUID`, `NSData`; the
`NSXMLParserDelegate` protocol. It exists to make the runtime leg testable
without a phone, and it is a real second consumer of the generator.

Growing either one is a reviewed diff of `bindgen.json` plus regenerated
output. `AVAudioEngine` and the UIKit views M4's backend will want are the next
entries; neither is in yet.

## The landscape this design sits in

Constraints that shaped the design, and the boundaries it lives inside:

- **The allowlist is the viability argument.** General ObjC bindings are a
  maintained-by-a-team product (Xamarin's iOS bindings; DarwinKit for
  macOS/AppKit). This generator binds only what wata calls, regenerated
  against the SDK each build runs on, with every gap an explicit refusal —
  the slice of that problem one project can carry. The mechanism itself is
  well-proven ground (Rust's objc2, PyObjC): the ObjC runtime's openness,
  not the binding approach, is what everything rests on.
- **The ObjC-visible subset is the hard boundary.** Everything reachable
  here is reachable because the framework ships ObjC headers and dispatches
  through `objc_msgSend`. Swift-only API (SwiftUI, some newer frameworks)
  is invisible to this pipeline; if one ever becomes a must-have, the exit
  is a Swift shim compiled with Xcode — a leaf artifact, but a toolchain
  reintroduction. PushToTalk, AVFoundation, and UIKit are ObjC-visible.
- **Main-thread-only API needs a generated trampoline, not a convention.**
  UIKit and parts of AVFoundation must be called on the main thread; Go's
  scheduler migrates goroutines across OS threads freely. When the
  allowlist grows main-thread-annotated declarations, the wrapper must
  carry the dispatch hop itself — a caller-side rule would rot.
- **ObjC exceptions do not cross the boundary.** An NSException unwinding
  into Go frames is undefined behavior, and catching one needs an ObjC
  compiler. The stance: any NSException is a bug in our calling code
  (wrong thread, bad argument); crash loudly, fix the caller.
- **purego couples to the Go runtime by design** (`go:linkname` into the
  cgocall path), so a Go version bump can break it until purego catches
  up. Fenced here: the Go version is a pinned-toolchain product, so the
  pair moves in lockstep, never by surprise.
