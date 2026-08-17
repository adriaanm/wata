# 0061 — wata-ios on the owner's iPhone: from simulator-green to daily use

Status: accepted (owner direction 2026-08-17)

## The problem

The iOS client (plan 0044) is simulator-green but has never run on a
phone, and the owner wants to *use* it — iterate toward the mac app's
feature parity on the real device. The device prerequisites all fell
today: the PTT hello passed its hardware gate on the owner's iPhone
("foon", iOS 27 beta, team YAURQZ84XZ), proving dev-signing, devicectl
install, and — decisively — that dlopen'd frameworks and synthesized
ObjC classes survive a signed device build. That last point was
`IOS-HELLO-ON-PHONE`'s sole open question, so the ios-spike device leg
is retired as subsumed: the real app on the phone answers everything
the spike would have, on the code that matters.

## The decision

Iterate on the shipped app, not on more hellos. Stages, each its own
commit and each usable on the phone before the next starts:

1. **Device build/sign/install leg** (`just ios-device`). The emitted
   `wata-ios` module compiled `GOOS=ios GOARCH=arm64` against the
   `iphoneos` sysroot (`tools/iosenv.py` already parameterizes this),
   bundled as `WataIos.app` (`net.wa-ta.ios`), signed with the hello's
   codesign recipe generalized out of `tools/bindgen/hello/build.py`,
   installed via `devicectl`. Owner's portal leg: App ID
   `net.wa-ta.ios` with **Push to Talk + Push Notifications** checked
   (audio will need both; checking now avoids a profile regen), an iOS
   App Development profile for it + foon. Acceptance: the app launches
   on the phone, paints the boot screen, and logs in against the
   wata-server on the Mac over home LAN.
2. **Fit the phone**: the safe-area inset fix (the stage renders under
   the notch — `ADULT-UX-NONHAPPY`'s concrete finding), plus whatever
   minimal touch-target sizing daily use demands.
3. **First-run login surface.** The mac's login sheet was dropped on
   iOS and a phone has no env vars; until this lands, stage 1 seeds
   credentials by writing the sandbox config from the harness. This is
   the iOS half of `ADULT-UX-NONHAPPY` starting with its most
   load-bearing piece.
4. **Real audio** — the actual product. `PTT-MIC-ROUNDTRIP` (AVAudioEngine
   onto the bindgen allowlist, a capture graph on the PTT-activated
   session), then the `AudioThread` seam's stub swapped for the real
   record/playback loop.

## Out of scope (deliberately)

- ~~The away-from-home transport story (iroh on iOS): at-home LAN HTTP to
  the Mac's server is enough to iterate; roaming is its own plan.~~
  Superseded same day by plan 0062 (owner ruling): the login surface
  choice exposed that LAN-HTTP login would fork the auth model, so iroh
  + enrollment come to iOS now, and stage 3's login sheet is replaced by
  the enroll surface.
- Keychain-backed secrets (sandbox `secrets.json` stays until the app
  holds real long-lived credentials worth it).
- APNs background receive, TestFlight/ad-hoc distribution to the
  family: after the owner's own daily use proves the client.

## Verification

Stage 1 by launch-and-look on the phone plus the existing simulator
gates staying green (`ios-interptest`, `ios-smoke` — the device leg
must not fork the build). Stages 2–4 each extend `ios-smoke` or
`mac-ui-tests`-style asserts where simulator-checkable, and the
phone-in-hand check where not.
