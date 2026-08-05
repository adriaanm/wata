# 0026 — bindgen: generated Go over the ObjC runtime (plan 0023 M3)

Status: done, except the on-hardware hello (the owner's leg: Apple must
grant the restricted push-to-talk entitlement before the app can run at all)

## Problem

M3 needs `PTChannelManager` (+ its delegate), `AVAudioEngine` /
`AVAudioSession`, and `UNUserNotificationCenter` callable from Go, with
no Swift in the repo and no hand-written binding per method. DarwinKit
proves the approach (generate readable Go over `objc_msgSend`) but
carries ~all of AppKit and its own runtime layer; wata needs three
frameworks' worth of surface and wants to own the generator (it is the
same muscle as sgola's readable-Go emission — codegen is this project's
native tool).

## Decisions

**Dispatch through purego/objc, not cgo.** `github.com/ebitengine/purego`
(+ its `objc` package) reaches the ObjC runtime — `objc_msgSend`, class
lookup, class REGISTRATION — with no cgo at all. That matters twice:
gomobile builds get no second C toolchain in the loop, and the M1
finding "cgo is unanswered" stays answered-by-avoidance for everything
except audio DSP we already own in go-pkgs/audio. Fallback if purego
disappoints on arm64 varargs/struct returns: a single generated cgo
shim file, same generated Go on top. The generator's emitter is the
only thing that would change.

**Metadata source: clang's JSON AST over the SDK's own headers**
(`clang -Xclang -ast-dump=json -fsyntax-only` on a small umbrella .m
importing the three frameworks, with the simulator SDK sysroot). No
hand-maintained IDL, no scraping Apple docs: the SDK on disk is the
authority, versioned by Xcode. The generator consumes the AST dump,
filters by an explicit allowlist (classes + protocols named in a
`bindgen.json`), and emits:

- one Go type per class (`type PTChannelManager struct { objc.ID }`),
  methods as `objc.Send` wrappers with Go-native params (NSString ↔
  string, NSError** → error return, blocks → Go funcs via purego's
  block support);
- one Go interface per delegate protocol, plus a runtime-synthesized
  ObjC class (`objc.RegisterClass`) whose IMPs trampoline into the Go
  interface — the DarwinKit delegate pattern, scoped to the protocols
  we allowlist;
- readable output: godoc from the header comments where present,
  deterministic ordering, `go vet`-clean, committed under
  `go-pkgs/appleptt/` (etc.), regeneration scripted
  (`tools/bindgen/regen.sh` pinned to the Xcode version).

**Scope is the allowlist, and it starts brutally small**: the ~6 types
PTT hello needs (`PTChannelManager`, `PTChannelDescriptor`,
`PTPushResult`, the `PTChannelManagerDelegate` +
`PTChannelRestorationDelegate` protocols, `AVAudioSession` basics).
Growing the allowlist is a normal reviewed diff of `bindgen.json` +
regenerated output. The generator refuses (loudly, per-decl) anything
it cannot map — refusals are the worklist, exactly the sgola
restriction-wall model.

## Proof

The M3 milestone gate from plan 0023 stands: a PTT hello on hardware —
system PTT UI appears, mic round-trip records — driven from Go through
the generated bindings, before anything integrates with wataclient.
Generator unit tests run on the committed AST fixtures (a checked-in
JSON snippet per tricky decl shape), so `just ci` exercises the
generator without Xcode; regeneration + the hello are Mac-gated legs.

**What landed** (design: `docs/design/bindgen.md`). Everything below the
phone is proven here: 23 generator unit tests over committed AST
fixtures in `just ci`; regeneration checked by gofmt, `go vet` and a
GOOS=ios GOARCH=arm64 build; and — the leg the plan did not anticipate —
a second allowlist target generated from the *macOS* Foundation SDK, so
the dispatch, the bridging, the blocks and the delegate trampolines are
exercised against a live ObjC runtime (`just bindgen-runtime`) with no
device involved. The hello app builds unattended into a 2.5 MB arm64
bundle linked against PushToTalk.framework (`just ptt-hello`).

**What did not**: running it. `com.apple.developer.push-to-talk` is a
restricted entitlement Apple grants per team on request, and PushToTalk
does not function in the simulator, so the system PTT UI and the mic
round-trip wait on that grant plus a phone. `tools/bindgen/hello/README.md`
has the request link and the exact owner steps. Audio capture is
deliberately not in the hello: the framework hands over an activated
AVAudioSession and the hello logs the handoff, but `AVAudioEngine` is not
on the allowlist yet.

## What changes (file-level)

- `tools/bindgen/` — the generator (Python, like the repo's other
  tools; the AST walk is I/O + filtering, not perf-bound), its
  `bindgen.json`, `regen.sh`, fixtures + tests.
- `go-pkgs/appleptt/` — generated output + the purego require.
- `docs/design/bindgen.md` once it lands.

## Out of scope

AppKit/UIKit view bindings (M4's UIKit backend will name what it needs
and the allowlist grows then), Android/JNI (Gio covers the phone shell
until a real need appears), block-heavy async APIs beyond what the six
types force, and any attempt at full-framework coverage.
