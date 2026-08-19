# 0069 — wata on the watch, standalone (a sketch)

Status: proposed — **a sketch, not a commitment.** What it contains is the
constraint analysis and a first probe whose failure would end the idea
cheaply. Nothing here is scheduled. **Stage 0 passed** (result at the
bottom): a Go c-archive does link into a watchOS binary, so the idea is
not dead on arrival — but nothing beyond stage 0 is scheduled either.

`[WATCH-GO-LINK-PROBE]`

The goal: an Apple Watch is a wata handset on its own. The watch talks to
the homeserver over its own wifi or LTE, records and plays voice messages,
and needs no paired iPhone in the path. A parent's wrist instead of a
kid's BQ268.

## Four constraints, verified before any design

These were checked against the installed SDK and Apple's own material
rather than assumed, because three of the four are recent enough that
anything remembered about them is likely stale.

**1. Go can reach only the newest watches — and watchOS 27 makes that
moot.**
watchOS ran on `arm64_32` — 64-bit instructions, 32-bit pointers — which
Go has never supported and, on the evidence of the request to add it
(golang/go#60180, closed frozen), will not soon. watchOS 26 moves Series
9, Series 10 and Ultra 2 to **full `arm64`**; Series 8, SE 2 and the
original Ultra stay `arm64_32` forever. So this is possible at all only
because of a months-old platform change.

And the fragmentation it implies **disappears if we require watchOS 27**,
which drops the Series 8, SE 2 and original Ultra outright: every watch
that runs watchOS 27 (Series 9, 10, 11, Ultra 2, Ultra 3, SE 3) is an S9-
or S10-class device, i.e. arm64. So the ISA constraint is not a
fragmentation problem to design around, it is a minimum-OS line: watchOS
27, arm64 only, no `arm64_32` build ever.

**2. There is no `GOOS=watchos`** (`go tool dist list` has `ios` and
`darwin`, nothing else Apple). The hypothesis worth probing: Darwin is
Darwin, so `GOOS=ios GOARCH=arm64` against the **watchOS sysroot** with
`-mwatchos-version-min` may produce a valid watchOS Mach-O, because a cgo
build links externally through `clang`, and it is clang — not Go — that
stamps `LC_BUILD_VERSION`. `tools/iosenv.py`'s `go_env(sdk)` is exactly
the seam: one more entry in its `_MINFLAG` table is the whole change on
our side. Whether the result loads is an empirical question, and it is
stage 0.

**3. The watch has no runtime-constructible view hierarchy.** This is the
one that decides the architecture.
- `UIView` is `API_UNAVAILABLE(watchos)` — verified in the watchOS 26.2
  SDK's own header. watchOS's UIKit is the *drawing* subset only:
  `UIImage`, `UIColor`, `UIFont`, `UIBezierPath`, the text machinery
  (available since watchOS 2.0).
- SwiftUI is the supported UI, and it is Swift-only: no ObjC classes, so
  purego/objc — the mechanism every wata Apple client is built on — cannot
  touch it.
- WatchKit's `WKInterfaceLabel`/`Image`/`Group`/`Table` **are** real ObjC
  classes, exported by `WatchKit.tbd`, and `WKApplicationMain(argc, argv,
  delegateClassName)` is a plain C entry point exactly analogous to the
  `UIApplicationMain` our iOS shell owns. That is tempting and it is a
  trap: `WKInterface*` objects are **storyboard outlets**, not things you
  `alloc`/`init` and insert, and the whole storyboard lifecycle has been
  deprecated since watchOS 7.

  Consequence: **the stage cannot be a view tree.** It has to be one
  raster. Which is not a setback — it is what wata-fb already is. The
  device client rasterizes its entire UI into a framebuffer, and
  `pixels.scala`/`glyphs.scala` are already Sgola. The watch is a
  framebuffer with a nicer screen.

**4. There is no PushToTalk on watchOS, and Apple is going the other
way.** No `PushToTalk.framework` in the watchOS SDK, and the iOS 26.2
SDK's own headers say why rather than leaving it to inference: *every*
symbol in `PushToTalk.framework` carries
`API_UNAVAILABLE(macos, macCatalyst, tvos, watchos)`. So everything plan
0065 tier 3 built — the system pill, the ephemeral per-join channel
token, the framework owning the audio session, a push that wakes the app
into live audio — does not exist on the watch.

Nor is it arriving as the replacement for what Apple is removing:
**watchOS 27 deletes the built-in Walkie-Talkie app** (gone from the app
list and Control Center in the June 2026 beta, unannounced, after eight
releases without a meaningful update), and nothing in watchOS 27's
developer material offers a push-to-talk API in its place — the new
frameworks are Foundation Models, Vision and a menopause health API.
9to5Mac's read is that this leaves "a gap that some third-party app will
fill", which is a market observation and not an API.

The cheap definitive check, when Xcode 27 is installed:
`ls $(xcrun --sdk watchos --show-sdk-path)/System/Library/Frameworks |
grep -i pushtotalk`. Until then, treat PTT-on-watch as absent.

## The supported replacement: LiveCommunicationKit + a VoIP push

Walkie-Talkie's removal does leave a sanctioned path, and it is better
than the notification-and-tap fallback this sketch first assumed. Both
halves were verified in the installed watchOS 26.2 SDK:

- **`PKPushTypeVoIP` is available on watchOS 9.0+** (PushKit, and it is
  ObjC with real headers — so reachable from Sgola through purego exactly
  as our other bindings are). A standalone watch app can be woken by a
  VoIP push.
- **`LiveCommunicationKit.ConversationManager.reportNewIncomingConversation(uuid:update:)`
  is available on watchOS 10.4+.** That is the CallKit-shaped contract:
  report an incoming conversation, get system UI and a system-activated
  audio session. Only the telephony parts of the framework are
  `@available(watchOS, unavailable)` (`TelephonyConversationManager`,
  `CellularService`, `ConversationHistoryManager`) — the conversation core
  is not.

So the arrival story can be: VoIP push wakes the app → report the
conversation → the system gives us the audio session → the clip plays,
with a press-to-reply in the app. That is much closer to the experience
Apple is deleting than a banner would be.

Two caveats, both real:
- **LiveCommunicationKit is Swift-only** on watchOS — a `.swiftmodule`
  with no headers, whose only ObjC exports are two mangled internal
  classes. purego cannot reach it. This makes the Swift shell below
  **load-bearing for two independent reasons**, not just the screen.
- **It is built for conversations, not one-shot clips.** wata sends voice
  *messages*; presenting each as an incoming call-like conversation is a
  design and an App Review question, and Apple's answer for the
  message-shaped case on iOS is the PushToTalk framework that the watch
  does not have. So the notification-plus-haptic path stays the fallback,
  and probing LiveCommunicationKit is a stage of its own rather than an
  assumption baked into the architecture.

## The shape those constraints force

Sgola owns everything above the pixel; the platform layer is a window and
an event queue.

- **`wata-watch`** — the client core, `wataui`, and the raster, built as a
  Go **c-archive**, exposing a tiny C ABI: `wata_start(cfg)`,
  `wata_frame(w, h) -> RGBA`, `wata_event(kind, a, b)`, `wata_shutdown()`.
  Four functions, all scalars and one byte buffer.
- **`WataWatch.swift`** (~100 lines) — a SwiftUI `Image` refreshed from
  that buffer, with taps, long-press and the Digital Crown pushed in as
  events. This would be **the first Swift in the tree**, which is the
  owner's call to make, not mine. The argument for it: it is the same
  division of labour bindgen already runs on — a thin boundary layer
  written in the platform's own language, doing nothing but marshalling —
  and it is the only way onto the watch's screen that Apple supports.
  The argument against is real too: a second language at the edge is a
  second thing to keep honest.
- **Fallback if the c-archive cannot be linked but a whole binary can**:
  Go owns `main`, calls `WKApplicationMain` through purego, and the app
  carries a storyboard with exactly one full-screen `WKInterfaceImage`
  whose `setImage:` we drive per frame. No Swift at all. It rides a
  deprecated lifecycle, so it is a probe result, never a plan.

## What the product is on a wrist

- **Send**: press and hold the on-screen PTT target while the app is open;
  record, release, send. No system talk button exists to borrow.
- **Receive**: two candidate paths, in preference order — a VoIP push
  plus `reportNewIncomingConversation` (system UI, system-activated audio
  session, closest to a walkie-talkie), or an APNs alert with a haptic
  that the user taps to play. Neither gives background listening:
  `WKExtendedRuntimeSession` exists but its session types are self-care,
  mindfulness, physical therapy and smart alarm, and Apple says outright
  that using them for something else risks review rejection.
- **Server**: a standalone watch app has **its own APNs token at its own
  topic**  (and a VoIP push would be a third kind beside alert and
  `pushtotalk`) — `<bundle>.watchkitapp`, and sending to the bare bundle id
  answers `DeviceTokenNotForTopic`. That is a third topic beside the app's
  and `.voip-ptt`, and after plan 0068 it is a one-line change in
  `ApnsPush.topicFor` plus a topic-kind field on the registration. The
  fan-out, the 410 rule and the badge all already work.
- **Screen**: 160×128 is smaller than any watch (Series 10 is 416×496), so
  the raster is not a scale-up of the handset's layout — it is the same
  bodies re-laid-out: two contact rows and one big talk target.

## Staged probes, cheapest-falsifying-first

**Stage 0 — does a Go archive link into a watchOS binary at all?** No
watch, no simulator runtime, an afternoon.
- `go build -buildmode=c-archive` with `GOOS=ios GOARCH=arm64` against
  both the `watchos` and `watchsimulator` sysroots; read
  `LC_BUILD_VERSION` back with `otool -l` / `vtool -show`.
- Link it into a trivial Swift watch app with `xcodebuild`. If `ld`
  refuses on the platform, retry with `vtool -set-build-version watchos`
  over the archive members.
- **Gate**: if neither works, this plan is abandoned with the finding
  recorded, and the idea waits on a Go port. That is the point of doing it
  first.

**Stage 1 — a hello raster on the watch simulator.** Blocked on disk
today: no watchOS runtime is installed and the internal disk has ~5 GB
free (the iOS runtime needed 25–30 GB of headroom to add — see the
2026-08-09 entry in the top-level learnings log). Stage 0 needs the SDK
only, which is why it goes first.

**Stage 2 — the client core over the watch's own network.** Login, sync,
one message received, headless: the `interptest` argv pattern wata-ios
already uses, so the assertions are in-process and need no UI.

**Stage 2b — the arrival experience.** Does a VoIP push reach a
standalone watch app, and does `reportNewIncomingConversation` hand over
an audio session for a *message*-shaped event? Needs Swift, a real watch
and a real push, so it comes after stage 4 in practice even though it is
the most product-relevant probe. Failure here is not fatal: the fallback
is a notification with a haptic.

**Stage 3 — audio.** `AVFAudio` is in the watchOS SDK, and `macaudio` is
purego over AVFAudio/AudioToolbox, so the backend may port as-is. Opus
(`go-pkgs/audio`) is cgo, which is legal in a c-archive but is a separate
probe: cgo + watchOS sysroot + arm64.

**Stage 4 — a real watch.** The `tools/ios-device.py` analogue: bundle,
sign against a profile with the watch app's identity, install.

## Out of scope, explicitly

- **`arm64_32` watches.** Unreachable, permanently.
- **Background listening, a live channel, anything PushToTalk-shaped.**
  The framework is not there.
- **A companion-of-the-iPhone watch app.** That is a different product
  (and it would work today, over WatchConnectivity, with no Go on the
  watch at all). If standalone proves impossible, that fallback is worth
  its own plan rather than a quiet substitution in this one — "standalone"
  is the requirement, not a nice-to-have.

## What would kill this

Named up front, so nobody has to discover them at stage 3: stage 0
failing both ways (**it did not — see below**); App Review objecting to an app that is not a SwiftUI
lifecycle app (the raster-in-an-Image shape is unusual, though it is
ordinary SwiftUI from the outside); the disk, for anything needing a
simulator; and battery, which no amount of design fixes if a watch cannot
hold a sync loop.

## Stage 0 result (2026-08-19): **pass, and cleanly**

Ran on Xcode 26.2 (watchOS 26.2 sdk, no watchOS platform component and no
simulator runtime installed) with go1.26.5. The whole hypothesis in
constraint 2 held, and the fallback workarounds were never needed.

**What was run.** `tools/iosenv.py` grew `watchos` and `watchsimulator`
entries (`-mwatchos-version-min` / `-mwatchos-simulator-version-min`, min
`26.0`) — that is the entire change on our side, as predicted. Against
each sysroot, `go build -buildmode=c-archive` of a two-line package with
one `//export`ed function, `GOOS=ios GOARCH=arm64 CGO_ENABLED=1`. Then
`vtool -show-build` over the extracted archive members, a direct `clang`
link of a C `main`, and finally an `xcodebuild` build of a throwaway
SwiftUI watch app (generated with `xcodegen`) linking the archive through
a bridging header.

**What the platform stamp said.** Every member of both archives — not
just the clang-compiled cgo objects but `go.o`, the object the Go linker
itself emits — reads:

```
 platform WATCHOS            minos 26.0   sdk 26.2     (watchos sysroot)
 platform WATCHOSSIMULATOR   minos 26.0   sdk 26.2     (watchsimulator sysroot)
```

That is the hypothesis confirmed at its root: because a cgo build links
externally, `go.o` goes through clang too, and clang honours
`-mwatchos-version-min`. Go never gets a say in the platform byte.

**Which workaround was needed: none.** `ld` raised no platform objection
at any point, so neither `vtool -set-build-version watchos` nor an
explicit `-platform_version watchos` was tried in anger. The only link
failure was undefined `_CFBundle*` / `_CFRelease` / `_CFStringGetCString`
from `runtime/cgo`'s iOS `init_working_dir` — real iOS-shaped runtime
code riding in on `GOOS=ios`, and it resolves with `-framework
CoreFoundation`, which watchOS has.

**The end product.** `xcodebuild -sdk watchos26.2` and `-sdk
watchsimulator26.2` both built `WataWatch.app` green, unsigned. The
device-sdk binary is `Mach-O 64-bit executable arm64`, `platform
WATCHOS minos 26.0`, and carries both `_wata_probe` and ~3.3k Go runtime
symbols. A SwiftUI `@main struct: App` calling a Go function on the watch
compiles, links and is a well-formed watchOS executable.

**Two tooling facts worth carrying into stage 1**, both in
`~/g/bq268/apple-dev-tooling.md` in full: the watchOS sdk is on disk but
the *platform component* is not, so `xcodebuild -scheme` refuses with
"Found no destinations … watchOS 26.2 is not installed" while
`xcodebuild -target … -sdk <sdk>` builds fine — an SDK-only gate must use
`-target`. And `vtool -show` rejects an `.a` outright ("file is not
mach-o"); `ar x` first.

**Verdict.** The gate is passed: the ISA and toolchain question that
would have ended this plan is answered yes, for both device and
simulator, with no hacks. Two things are still unknown and neither was in
stage 0's scope — whether the resulting app *runs* (nothing was executed;
no runtime is installed, and a simulator binary run on the host aborts
with `DYLD_ROOT_PATH not set`, which only proves the stamp), and whether
cgo with a real C dependency (opus, stage 3) survives the same treatment.

**The PushToTalk re-check is still pending.** Xcode here is 26.2, not 27,
so `ls $(xcrun --sdk watchos --show-sdk-path)/System/Library/Frameworks |
grep -i pushtotalk` finding nothing says only what the plan already
states about the 26.2 sdk. Re-run it the day Xcode 27 lands.

**What stage 1 needs that it does not have**: a watchOS platform
component and simulator runtime, i.e. the disk headroom the 2026-08-09
learnings entry describes (the iOS runtime wanted 25–30 GB free; ~5 GB
free today). That is an owner decision, not a task to pick up.

## Stage 1 result (2026-08-19): the sketch's constraint 3 is WRONG, and the architecture changes

Owner rulings taken before this stage, both recorded here because the rest
of the plan now rests on them: **target watchOS 26**, not 27 (no verified
UX benefit has turned up in 27's SDK to justify the narrower install base),
and **no Swift** — the tree stays single-language, and Swift comes back on
the table only if a *material UX* wall is proven after every Sgola path is
exhausted. This stage was that exhausting, and it did not find the wall; it
found the opposite.

Everything below is a line a run printed. The harness is
`just watch-spike` (`tools/watch-spike/`, five stages), on a Series 10 46mm
simulator, watchOS 26.2.

### What is actually true about watchOS's UIKit

The sketch's constraint 3 said the watch "has no runtime-constructible view
hierarchy" and concluded "the stage cannot be a view tree." That was
inferred from the headers — `UIView` is `API_UNAVAILABLE(watchos)` and
`UIKit.tbd` exports no classes — and **the headers are not the runtime**.
Asked directly, the objc runtime on watchOS 26.2 answers `true` for
`UIWindow`, `UIView`, `UIViewController`, `UILabel`, `UIApplication`,
`UIScreen`, `UIImageView`, `UIColor`, `UIFont`, `UIBezierPath`, `UIImage`,
`UIGraphicsImageRenderer` and `CALayer` — 22 of the 23 names probed, the
miss being `WKHapticType`, which is an enum and never was a class.

They are not merely mapped, they work: `UIView` alloc/init returns an
object, `-layer` answers, `addSubview:` lands (`subviews` count 1), and a
raw RGBA buffer becomes a `UIImage` through
`CGDataProviderCreateWithData` + `CGImageCreate`.

### The three walls, and the way through each

**`UIApplicationMain` is exported on watchOS and calling it HANGS.** The
symbol resolves; the call never returns and the delegate's launch callback
never fires (two runs, 120s watchdog each). A watch app is started by
WatchKit's lifecycle, and that is not negotiable.

**`WKApplicationMain` with a synthesized delegate aborts, twice, and
watchOS names both causes in its own log** — which is the single most
useful debugging fact from this stage:

1. *"Info.plist key WKExtensionDelegateClassName has value
   "…", but that class doesn't conform to the WKExtensionDelegate
   protocol."* Implementing the methods is not enough; conformance is
   checked by name. `objc.GetProtocol("WKExtensionDelegate")` resolves at
   runtime and `objc.RegisterClass` takes protocols, so this is one
   argument. watchOS then says *"Created WKExtensionDelegate of class
   WataWatchSpikeWKDelegate"* — a Go-synthesized class accepted as the app
   delegate.
2. *"No interface description file Interface.plist … and extensionDelegate
   didn't return a applicationRootInterfaceControllerClass."* The second
   half is the way out: **a delegate that answers
   `applicationRootInterfaceControllerClass` with a class needs no
   `Interface.plist`, hence no storyboard, no ibtool, and none of the
   WatchKit-storyboard deprecation.** A storyboard route was built first
   and then deleted as strictly worse — for the record it does work
   (`ibtool --target-device watch` compiles the file with a deprecation
   *warning*, not an error), and a `WKInterfaceController` outlet is just
   an ivar the nib loader fills by KVC, so even that path needs no Swift.

**A UIWindow the app builds is never composited.** `makeKeyAndVisible` on
a fresh window leaves `isKeyWindow` 0 and the panel **BLACK** — the
screenshot is the proof, and without it the printed lines all read like
success. On iOS 13+ the scene owns the display, and watchOS does run a real
one: `connectedScenes` has 1, its class is `UIWindowScene`, and it already
holds a `UIWindow`. Joining that scene (`setWindowScene:`) flips
`isKeyWindow` to 1 and the frame appears.

### What is on the screen

`tools/watch-spike/out/wkapp.png`: a 416×496 raster (208×248 pt at scale 2,
Series 10 46mm) painted red on the top half and blue on the bottom — the
same row-orientation pin wata-ios uses — with a live `UILabel` over it. It
was produced by a single Go binary with **no Swift, no storyboard, no
Xcode project and no `WKInterface` object involved in showing it.**

### The architecture this forces, replacing the sketch's

The sketch proposed a Go c-archive under a SwiftUI shell, with a WatchKit
raster as fallback. Neither is needed:

- **the entry point** is `WKApplicationMain` from Go, with a delegate and
  root interface controller synthesized through purego/objc — the same
  mechanism every other wata Apple client already uses;
- **the screen** is a UIKit view tree the app builds at runtime, joined to
  the scene watchOS provides. So `wata-ios`'s retained stage is the
  starting point, not wata-fb's framebuffer — the watch is a small iPhone
  as far as the element table is concerned, and the raster path exists as
  well if a body wants it;
- **the root interface controller** is lifecycle scaffolding only; its
  `willActivate` is the cue that a screen exists. Nothing is drawn through
  it.

Constraint 3 in the body of this plan should be read as superseded by this
section. Constraints 1, 2 and 4 stand.

### Still unknown

The system time overlay ("12:41" in the screenshot) is drawn by watchOS
above app content and cannot be removed — a UX fact to design the top of
the screen around, not a defect. Untouched by this stage: input (the
gesture recognizers and `WKCrownSequencer` are all present and
undeprecated, but nothing has been wired), the client core over the
watch's own network, audio, and anything on real hardware.

## Stage 2 progress (2026-08-19): Sgola runs on the watch

Three things landed, each with a gate that re-takes it (`just watch-spike`,
`just watch-hello`, `just watch-smoke`).

**`go-pkgs/watchshell`** is iosshell's twin: WKApplicationMain, a
synthesized `WKExtensionDelegate` answering
`applicationRootInterfaceControllerClass` (so no storyboard ships), and a
window joined to watchOS's scene *and raised above WatchKit's own* — both
are needed, and a bisect (`watch-spike --only wkapp`, argv `adopt|own`)
showed adopting the existing scene window works equally well.
`go-pkgs/iosui` is reused **unchanged**: it is libdispatch, libobjc and
CoreGraphics, and nothing in it was ever iOS-specific.

**`wata-watch`** is a real sgo module — `mode app` over core + json +
wataclient + wataui, `godep`ing watchshell/iosui/appleptt — whose
`main.scala` builds a UIView tree through the same generated UIKit facade
wata-ios uses and paints it. `just watch-smoke` runs it on a Series 10
simulator and asserts `watch: ready 208x248`, `painted 3 views`,
`probe ff0000`. The screenshot shows it. That is Sgola, compiled to Go,
driving UIKit on a platform whose headers deny those classes exist there.

**Go's network stack is fine on watchOS** — TCP, HTTP and TLS 1.3 against
a real name, with a sane clock (`watch-spike --only net`). So the client
core has nothing to prove below the socket.

### Two measurement lessons, both of which produced false results first

- **An offscreen render probe is not evidence that anything is on screen.**
  `RenderViewRGBA` read back correct pixels through every black-panel
  configuration tried. Only a screenshot settles visibility.
- **A done marker can beat the compositor.** The whole run is ~0.6s, so a
  screenshot taken the instant the app printed its last proof caught an
  undrawn panel and read as total failure. `simrun.launch_and_expect` grew
  a `settle` (0.0 default; the watch harnesses pass 2.0). This is the same
  shape as the top-level learnings log's "a done marker can race its own
  stimulus", reached from the other direction.

### What stage 2 still owes

The client core itself — login, sync, one message received — is NOT done.
`wata-watch` links wataclient but does not yet run a session: that needs
the wata-ios bodies ported (`caps`, `config`, the screen bodies, the pump)
and is the same shape of work plan 0044 stage 4 was for the phone, not a
probe. Nothing found so far suggests it is blocked; the platform questions
are all answered.

### Sizing the rest, now that the unknowns are gone

- **Stage 2 (client core + the screen bodies)** — a port of wata-ios's
  sources, mechanical but not small.
- **Input** — untouched. `WKCrownSequencer` and the tap/long-press/swipe/pan
  gesture recognizers are all present and carry no deprecation, and
  long-press is exactly hold-to-talk. Whether a UIKit responder on our own
  window sees touches at all on watchOS is unprobed and should be the next
  cheap falsifying check.
- **Stage 3 (audio)** — `AVFAudio` is in the watch SDK and `macaudio` is
  purego over it; opus is cgo, a separate question.
- **Stage 4 (hardware)** — needs the owner's Series 10 and a signing
  identity.
