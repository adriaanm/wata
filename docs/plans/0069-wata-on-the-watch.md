# 0069 — wata on the watch, standalone (a sketch)

Status: proposed — **a sketch, not a commitment.** What it contains is the
constraint analysis and a first probe whose failure would end the idea
cheaply. Nothing here is scheduled.

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
failing both ways; App Review objecting to an app that is not a SwiftUI
lifecycle app (the raster-in-an-Image shape is unusual, though it is
ordinary SwiftUI from the outside); the disk, for anything needing a
simulator; and battery, which no amount of design fixes if a watch cannot
hold a sync loop.
