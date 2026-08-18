# 0063 — real audio on iOS: the mac's backend under the shared audio thread

Status: done — on-phone mic roundtrip verified 2026-08-18 (message
recorded on the iPhone, received and played on the BQ268). One hardware
fix was needed on top of the simulator-green build: on iOS the engine's
IO unit is configured lazily, so an engine started without its input
node ever instantiated runs output-only and the input node reports a
0 Hz format on every capture open ("input node reports no format").
macaudio's `prepareInput` (platform-split, no-op on macOS) touches the
input node before the engine's first start. Diagnosed via plan 0064's
persistent log — the simulator legs could not have caught it (they run
the fake backend).

## The problem

The iOS client is a full wata client except for the one thing wata is
for: PTT answers MIC FAILED because `audiostub.scala` honestly errors
every record and play command (plan 0044's deliberate stub). The
hardware risk is already retired — the PTT hello proved dlopen'd
frameworks, synthesized ObjC classes, and Go callbacks all survive
device signing — so what remains is wiring, and almost all of it
exists: `go-pkgs/macaudio` (plan 0033) was written iOS-clean by
construction (AudioToolbox is ObjC-free C; AVFAudio exists on iOS; no
cgo anywhere) and it already vets and builds for GOOS=ios against the
iphoneos sysroot, untouched. Its package doc names the one gap: iOS
requires an AVAudioSession activation that macOS does not have.

## The decision

Run **wata-fb's own audio thread over macaudio** on iOS — the exact
seam wata-mac uses: `audiothread.scala` symlinked in, an `audio.scala`
facade declaration-identical to wata-fb's (`just facade-check`
enforces it), and the backend chosen purely by the `@go.bind` path.
macaudio grows an iOS-only session shim rather than wata-ios growing
an audio backend.

Foreground-only, deliberately: the session category is activated when
the audio thread starts, and recording works while the app is open.
The PushToTalk framework (background transmit, the system PTT UI, the
locked-screen arc — what the hello actually exercised) is real product
work with its own UX questions; it gets its own plan once foreground
voice is field-proven. Same posture plan 0062 took toward APNs.

## What changes

- `go-pkgs/macaudio/session_ios.go` (build tag `ios`) +
  `session_other.go` (no-op): `SetupMixer` calls `sessionActivate()`
  first — AVAudioSession sharedInstance, category PlayAndRecord with
  DefaultToSpeaker|AllowBluetooth, `setActive:error:`, and a
  fire-and-forget record-permission request so the system prompt
  appears at first audio-thread start, not mid-PTT-press. A failed
  activation logs and continues: OpenCapture then fails per-command,
  which the audio thread already surfaces as MIC FAILED.
- `wata-ios/src/main/scala/audio.scala`: wata-mac's facade, same
  declarations (facade-check gains the third member).
- `wata-ios/src/main/scala/audiothread.scala`: symlink to wata-fb's,
  same as wata-mac. `audiostub.scala` is deleted.
- `wata-ios/sgo.build`: `godep …/go-pkgs/macaudio`.
- Simulator gates (`ios-smoke`, `ios-enroll-smoke`): run with
  `WATA_MAC_AUDIO=fake` (macaudio's tone-generator backend — the env
  name is the package's, kept for facade parity) so no mic-permission
  prompt blocks a harness; the gates now prove the REAL audio thread
  links, starts, and pumps on iOS.
- `tools/ios-device.py`: nothing — the mic usage string has been in
  the Info.plist since stage 1.

## Verification

The gates above green in the simulator (real thread + fake backend),
then the owner's mic roundtrip on the phone: hold PTT, speak, release,
the message plays on another client; a message sent from the mac plays
on the phone. MIC FAILED remains the correct surface for a denied
permission.

## Out of scope

- The PushToTalk framework / background transmit (own plan, after
  field use).
- Interruption handling (calls, Siri) beyond whatever the engine's
  own error surfacing already gives — field experience first.
- The startup chirp (`chirp=false` stays: a phone app that appeared
  when tapped has answered the question the chirp answers).
