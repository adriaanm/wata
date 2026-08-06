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

**Structs by value.** A C struct named in the target's `structs` list becomes a
Go struct in `structs.go` — fields in layout order, mapped with the primitive
table (`NSUInteger`→`uint`, `CGFloat`→`float64`), nesting allowed between
allowlisted structs — and is used directly in method and property signatures.
No dispatch machinery is behind it: `objc.Send[T]` routes through
`purego.RegisterFunc`, which classifies struct arguments and returns per
AAPCS64 on arm64 (registers ≤ 16 bytes, the x8 indirect result above, larger
arguments by reference), and `ID.Send`'s variadic arguments are flattened into
fixed register-classified arguments — both proven against the live runtime in
both return conventions (plan 0029). arm64-only, deliberately: amd64 would
need the `objc_msgSend_stret` split. The loader resolves a name to its record
whether the header says `typedef struct _NSRange {…} NSRange` or names an
anonymous record only by its typedef, and the mapper recognizes both the
typedef and the desugared record spelling at use sites.

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

**A block the callback receives** (a completion handler the framework hands
the app — the PushToTalk incoming-push path) maps to a per-signature handle
type emitted into `blocks.go` — `IncomingBlockVoid`,
`IncomingBlockFloat64String`, … named from the Go parameter types and shared
by every callback with that signature. The trampoline copies the block
(`objcrt.CopyBlock`, before user code runs), so the handle is callable after
the callback returns, from any goroutine; `Call` is typed with the usual
conversions per argument, `Release` frees the copy — exactly once, see
`BlockHandle` below. Only void-returning blocks are mapped; a non-void
return, a nested block, or an unmappable block parameter stays refused.

**Opaque classes.** A class named in `opaque` gets a wrapper type with no
methods, so signatures mentioning it map. `classes` versus `opaque` is the
knob that keeps the allowlist small without refusing half the methods.

**Framework loading.** `objc.GetClass` sees only frameworks already loaded
into the process, and a pure-Go binary links none — a package whose classes
live outside Foundation (which `objcrt`'s init loads) resolves every class
to nil until its framework is loaded. A target naming `frameworks` emits
`frameworks.go`, a package init that `Dlopen`s each
`/System/Library/Frameworks/<name>.framework/<name>`. Opt-in per target;
idempotent and cheap at runtime.

**Property selectors honor `getter=`/`setter=`.** `@property (getter=
isRunning) BOOL running` dispatches through `isRunning`, not `running` —
sending the property name is an unrecognized-selector NSException on first
touch. The loader carries the AST's custom getter/setter names; the Go
method name still comes from the property name (`Running()`), only the
selector changes.

**Superclass members are not inherited.** A class binds only what its own
interface and categories declare — `AVAudioPlayerNode` does not get
`AVAudioNode`'s `installTapOnBus:…`. Allowlist the superclass as a full
class and convert at the call site (`AVAudioNode{ID: player.ID}`); wrapper
types are all `struct{ objc.ID }`, so the conversion is free.

## Refusals

Anything the mapper cannot express is refused **per declaration, with a
reason**, and written to `REFUSALS.md` beside the generated code. Refusing is
not failing: the class still binds, the other methods still work. The refusal
list is the worklist — each line is either a mapping the generator should learn
or an allowlist entry that should go away. Shapes refused today: structs not
on the allowlist ("add it to structs" — the message names the fix), structs
with a bitfield, union or array field (refused once, per struct; every
declaration using one points at that reason), a struct anywhere in a block
signature or a protocol callback (purego callbacks cannot carry structs), raw
pointer pairs (`void *` + length), enums not on the allowlist, classes not on
the allowlist, nested blocks, a non-trailing `NSError **`, an incoming block
with a non-void return, and a callback returning a block.

`NS_UNAVAILABLE` and deprecated declarations are skipped silently: the SDK is
saying "not callable", which is not a gap.

## objcrt — the hand-written runtime

Generated code contains no logic; it maps selectors onto `go-pkgs/appleptt/objcrt`:
string/error/data bridging, `ErrOut` (the `NSError **` slot, which holds a
`uintptr` so no Go pointer is ever visible to the callee), `NewDelegate`, and
`BlockHandle` (a block received in a callback, below). A bridging bug is fixed
there, once, rather than in thousands of generated lines.

**BlockHandle — a block received in a callback.** The block pointer a delegate
trampoline gets is only valid for the duration of the call; `objcrt.CopyBlock`
runs at trampoline entry and `_Block_copy`s it, so the handle outlives the
callback and is safe from any goroutine. `Invoke` calls through the block
layout's own invoke pointer (offset 16: isa 8 + flags 4 + reserved 4), passing
the block pointer itself as argument 0 — the ObjC block convention — via
`purego.RegisterFunc`, so floats ride in FP registers. (purego's
`objc.Block.Invoke` cannot do this: it looks the closure up in purego's own
cache, which only holds Go-created blocks.) `Release` is `_Block_release`,
exactly once: a second `Release` panics, `Invoke` after `Release` panics —
either would otherwise be a use-after-free — while repeat `Invoke` on a live
handle is allowed (how often a block may be called is the framework's
contract, not this type's). `CopyBlock(0)` returns a nil handle, which panics
if called through.

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
`NSNotificationCenter`, a Go function invoked by selector from Foundation, a
generated `NSXMLParserDelegate` whose five callbacks `NSXMLParser` drives in
order with bridged arguments, and the incoming-block path both ways: the
`BlockHandle` ABI itself (a hand-rolled block whose float/int/object
arguments arrive intact through the invoke pointer, cross-goroutine, panics
pinned — in `objcrt`), and `NSURLSession` handing
`didReceiveResponse:completionHandler:`'s completion block to a generated
delegate, where the Go side stores the handle, returns, calls it later from
another goroutine, and `didReceiveData:` observably delivers the payload —
the exact shape of the PushToTalk incoming-push path — and structs by value in
both AAPCS64 return conventions: `-[NSData rangeOfData:options:range:]` (a
16-byte NSRange in x0/x1, with NSRange arguments observably narrowing the
search) and `NSProcessInfo.operatingSystemVersion` (24 bytes through the x8
indirect result, cross-checked against `isOperatingSystemAtLeastVersion:`
taking the same struct by value), field values pinned in both directions.

## The allowlist today

`appleptt` (iPhoneSimulator SDK, Foundation + UIKit + AVFAudio + PushToTalk):
`PTChannelManager`, `PTChannelDescriptor`, `PTPushResult`, `PTParticipant`,
`NSUUID`; the `PTChannelManagerDelegate` and `PTChannelRestorationDelegate`
protocols; seven PT enums; `NSData`, `NSDictionary`, `UIImage`,
`AVAudioSession` opaque.

`foundation` (macOS SDK): `NSProcessInfo`, `NSNotification`,
`NSNotificationCenter`, `NSXMLParser`, `NSUUID`, `NSData`, `NSURLSession`;
the `NSXMLParserDelegate` and `NSURLSessionDataDelegate` protocols (the
latter exists to drive the incoming-block handle against a live framework);
the `NSRange` and `NSOperatingSystemVersion` structs (which drive the
by-value ABI against a live framework).
It exists to make the runtime leg testable without a phone, and it is a real
second consumer of the generator.

`avfaudio` (macOS SDK, `frameworks: ["AVFAudio"]`): `AVAudioEngine`,
`AVAudioNode`, `AVAudioPlayerNode`, `AVAudioFormat`, `AVAudioPCMBuffer`;
`AVAudioCommonFormat`; the input/output/mixer nodes, `AVAudioTime` and
friends opaque. Proven against the live engine — render and mic capture —
by the audio spike (`tools/audio-spike/REPORT.md`), which also settled the
codec question: Opus rides AudioToolbox's C AudioConverter API over plain
purego (`AVAudioConverter`'s input block *returns* an object, a refused
shape), so the codec needs no generated bindings at all.

Growing any target is a reviewed diff of `bindgen.json` plus regenerated
output. The UIKit views M4's backend will want are the next entries.

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
