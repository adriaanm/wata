# The PushToTalk hello

Plan [0026](../../../docs/plans/0026-bindgen.md)'s hardware gate: Apple's
PushToTalk framework driven from Go through the generated bindings, with no
Swift anywhere. The system PTT UI comes up, the app joins a channel, the
hold-to-talk button transmits, and the system hands the app an activated audio
session — all logged on screen.

```
ios/main.m      the shell: a window, four controls, a log view. No framework
                calls — it only forwards taps into the archive.
hellopt/        the Go side: PTChannelManager, both delegates and the channel
                descriptor, from go-pkgs/appleptt. This is the whole point.
build.py        archive -> app -> sign -> install
```

## What is proven without a phone

`tools/bindgen/hello/build.py` (or `just ptt-hello`) runs unattended on a Mac
with Xcode and needs no device, no signing identity and no entitlement:

- `archive` compiles `hellopt/` for `GOOS=ios GOARCH=arm64` as a C archive.
  The generated bindings, purego and the delegate trampolines all compile for
  the device.
- `app` links that archive with the ObjC shell against the real iOS SDK and
  lays out `out/WataHello.app` (~2.5 MB, arm64, linked against
  `PushToTalk.framework`).

Running it is the owner's leg, below.

## What the owner has to do

1. **Get the entitlement.** `com.apple.developer.push-to-talk` is
   *restricted*: Apple grants it per team, on request, at
   <https://developer.apple.com/contact/request/push-to-talk/>. Until that is
   granted, no profile can carry it and the app will not launch — there is no
   way around this and no simulator substitute (PushToTalk does not function
   in the simulator).

2. **Make an App ID and a profile.** In the developer portal, create the App
   ID `com.adriaanm.watahello` (override with `WATA_BUNDLE_ID`) with the
   Push to Talk capability, then a development provisioning profile for it and
   the target device. Download it to
   `tools/bindgen/hello/WataHello.mobileprovision` (or point `WATA_PROFILE` at
   it). Team `YAURQZ84XZ` (override with `WATA_TEAM_ID`).

3. **Build, sign, install** with the phone attached:

   ```
   just ptt-hello --install
   # or, stage by stage:
   tools/bindgen/hello/build.py                # archive + app, unsigned
   tools/bindgen/hello/build.py --only sign    # embed the profile, codesign
   tools/bindgen/hello/build.py --only install # devicectl install
   ```

   `--only install` needs the device's identifier: run
   `xcrun devicectl list devices` and pass it as `WATA_DEVICE`.

   If `devicectl` is awkward, dragging `out/WataHello.app` onto the device in
   Xcode's Devices window does the same thing.

4. **Run it.** On the phone:
   - the app boots a channel manager at launch (the framework requires that);
   - **Join** → iOS shows the system push-to-talk banner for "wata hello";
   - **HOLD TO TALK** → the banner turns to transmitting, the log shows
     `transmitting` and `audio session activated: <AVAudioSession …>`;
   - releasing shows `stopped transmitting`;
   - **Leave** ends it.

   The log lines are what to report back: they are the delegate callbacks
   arriving in Go.

## What this hello does NOT do

- **No audio is captured or played.** The framework activates an
  `AVAudioSession` for the transmission and the log proves the handoff; wiring
  a capture graph onto it is the next step (`go-pkgs/audio` plus
  `AVAudioEngine` bindings, which the allowlist does not yet name).
- **No network.** Two phones do not hear each other; a real channel needs the
  server's APNs push leg, which rides with the wataclient integration.
- **No wataclient.** The hello links the bindings and nothing else, on purpose:
  if it fails, the bindings failed.
