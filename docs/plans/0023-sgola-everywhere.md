# 0023 — sgola everywhere: the phone path

Status: accepted (owner greenlight 2026-08-05 — "we have to do this")

## Problem

`[PHONE-CLIENT]` The parent's client is the last missing piece of the
family loop, and plan 0008's answer (a hand-written Swift PushToTalk
app) buys it at the price of a second implementation language and a
codebase sgola never touches. This is a fun project, deliberately: the
owner wants the crazy path — sgola across the board, phones included.
The stack makes that less crazy than it sounds: sgola's output is
ordinary Go, Go runs on both phone platforms as an AOT library
(gomobile; Tailscale ships it in both stores), Gio proves Go can own
the screen there, and DarwinKit proves Go bindings for Apple
frameworks can be *generated* rather than hand-written.

## Decision

One Sgola codebase reaches the phone in stages, each independently
useful, each a real consumer proof for the compiler:

**M1 — the gomobile spike.** `wataclient` (unchanged) through
`gomobile bind` into an xcframework; a throwaway ten-line Swift shell
drives login + sync in the iOS simulator against the Mac server over
TCP. Kills the central risk (does sgola-emitted Go survive gomobile's
toolchain and type constraints at the bind surface?) in an afternoon.
Friction is a deliverable: anything the emitted Go trips over becomes
a sgola inbox ticket, like the rsc.io/qr dep proof before it. It lives in
`tools/phone-spike/` (`just phone-spike`); its README says how to rerun it
and REPORT.md is the answer — the binds hold, and the two tickets are
`NO-LIB-EMIT-FOR-RUNTIME-LIBS` and `INLINK-DEP-SEARCH-PARENT-ONLY`.

**M2 — the blit shell: the phone is a bigger BQ268.** A Gio app that
runs the real `wata-fb` frame loop (via the `UiDevice` seam) and
blits the 160×128 buffer as a scaled texture, touch zones mapped to
the five keys. The entire existing UI — goldens and all — ships on a
phone with no new UI code. Silly on purpose, useful for real: a
parent has a working client while the rest matures. Android falls out
of the same Gio build.

**M3 — the bindings generator.** A DarwinKit-style codegen pass over
Apple's machine-readable framework metadata, scoped to what wata
needs: `PTChannelManager` (+ delegate, synthesized at runtime via the
ObjC runtime), `AVAudioEngine`/`AVAudioSession`,
`UNUserNotificationCenter`. Output: readable Go over `objc_msgSend`,
no Swift in the repo. Proven by a PTT hello (system PTT UI up, mic
round-trip) before anything integrates.

**M4 — the DSL.** The delicious part: a declarative UI layer in
restricted Scala — views as ADTs, `body` as a pure function of state,
a differ reconciling against a retained backend tree. **Backend 1 is
the framebuffer**: wata-fb adopts it applet by applet with the golden
harness as the oracle (byte-identical frames = the refactor is real),
zero Apple dependency, the whole design provable in-repo. **Backend 2
is UIKit** through M3's generated bindings — the same view ADTs
rendered as native controls. Sealed families + pure bodies are
exactly what the dialect is good at; whatever the differ shakes out
of the compiler is the point.

Server push (APNs/FCM wake — wata-server work) rides with M3's
integration, not before; the PushToTalk framework's channel model
defines what the server must send.

## What changes (file-level, per milestone)

- M1: `tools/phone-spike/` (bind wrapper package + the Swift shell +
  a driver script); no product-tree changes.
- M2: `go-pkgs/gioshell/` (plain Go, ordinary fetched Gio dep — the
  external-deps door is open), a `UiDevice` impl, `tools/` packaging.
- M3: `tools/bindgen/` (the generator), `go-pkgs/appleptt/` etc.
  (generated, committed, regeneration scripted).
- M4: `wataui/` (a new Sgola library module: the view ADTs, the
  differ, the fb backend), wata-fb adopting it; the UIKit backend
  lands with M3's bindings.

## Verification

- M1: scripted — bind succeeds, shell logs in, one message syncs;
  the report is the deliverable (plus any sgola tickets).
- M2: the existing fb-ui goldens ARE the oracle (same frames through
  the blit path); a manual on-phone smoke.
- M3: PTT hello on hardware; generated code `go vet`-clean.
- M4: golden-per-applet equivalence during fb adoption; the UIKit
  backend gets its own screenshot harness when it exists.
- Every milestone: full `just ci` + conformance stay green.

## Out of scope

The watch (plan 0008 keeps it), App Store distribution polish,
Android beyond what Gio gives free, multi-family/hosted, and
replacing plan 0008 — it remains the reference for what first-class
iOS PTT means; this plan is the sgola-native road there.
