"""Driving a hand-rolled watch `.app` in the watchOS simulator.

simrun.py's device machinery generalizes — the custom device set, the
reuse-or-create rule, the console-pty launch and its retry — so this module
imports all of it and adds only what the watch does differently:

  - **the runtime and device type** (a Series 10 46mm on the newest watchOS
    runtime, matching the hardware this targets),
  - **the bundle**, which is a different Info.plist: a watch app declares
    `WKApplication` (the watchOS 7+ single-target shape, not the old
    app-plus-extension pair) and `WKWatchOnly` (standalone — no companion
    iPhone app exists, which is the whole point), rides `UIDeviceFamily` 4,
    and names WatchSimulator/watchsimulator as its platform.

A watch app's bundle id is its own: the plan's `<bundle>.watchkitapp`
convention is about APNs topics for a companion-paired app, and a
watch-only app is simply its own identifier — but the suffix is kept
because the server's topic rule is written against it and a standalone
app's token is still not the phone app's.
"""

import pathlib
import plistlib
import shutil
import subprocess
import sys

import simrun

MIN_WATCHOS = "26.0"
DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-10-46mm"
DEVICE_NAME = "wata-watch"  # the one watch device every harness shares


def latest_watchos_runtime(sc):
    """The newest available watchOS runtime's identifier, or exit."""
    import json
    runtimes = json.loads(subprocess.run(sc + ["list", "runtimes", "-j"],
                                         capture_output=True, text=True,
                                         check=True).stdout)["runtimes"]
    watch = [r for r in runtimes if r.get("isAvailable")
             and r["identifier"].startswith(
                 "com.apple.CoreSimulator.SimRuntime.watchOS-")]
    if not watch:
        sys.exit("watchrun: no watchOS simulator runtime installed — "
                 "`xcodebuild -downloadPlatform watchOS` (~4 GB)")
    return watch[-1]["identifier"]


def ensure_device(sc, name=DEVICE_NAME, device_type=DEVICE_TYPE):
    """The udid of the named watch device, creating it only if absent."""
    import json
    listing = json.loads(subprocess.run(sc + ["list", "devices", "-j"],
                                        capture_output=True, text=True,
                                        check=True).stdout)["devices"]
    for devs in listing.values():
        for d in devs:
            if d.get("name") == name and d.get("isAvailable"):
                print(f"watchrun: reusing device {name} {d['udid']}")
                return d["udid"]
    rt = latest_watchos_runtime(sc)
    dev = subprocess.run(sc + ["create", name, device_type, rt],
                         capture_output=True, text=True)
    if dev.returncode != 0:
        sys.exit("watchrun: simctl create failed\n" + dev.stderr)
    udid = dev.stdout.strip()
    print(f"watchrun: created device {name} {udid} on {rt}")
    return udid


def bundle(app_path, binary, bundle_id, min_watchos=MIN_WATCHOS,
           delegate_class=None):
    """A watch `.app` by hand: no Xcode project, no Swift, no storyboard,
    no team ID."""
    app_path = pathlib.Path(app_path)
    binary = pathlib.Path(binary)
    shutil.rmtree(app_path, ignore_errors=True)
    app_path.mkdir(parents=True)
    shutil.copy2(binary, app_path / binary.name)
    info = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleExecutable": binary.name,
        "CFBundleIdentifier": bundle_id,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": binary.name,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleVersion": "1",
        "CFBundleSupportedPlatforms": ["WatchSimulator"],
        "DTPlatformName": "watchsimulator",
        "MinimumOSVersion": min_watchos,
        # 4 is the watch. The two WK keys are what make this a watchOS 7+
        # single-target app that runs with no paired iPhone app at all.
        "UIDeviceFamily": [4],
        "WKApplication": True,
        "WKWatchOnly": True,
        # Touching the microphone WITHOUT this key is not an error the app can
        # see or handle: the system aborts the process, and the crash report
        # names neither the key nor the API — the main thread is sitting in its
        # runloop and no frame points at audio. macaudio's AVAudioSession setup
        # asks for record permission as it starts the engine, so the abort
        # lands on SetupMixer, nowhere near an obvious mic call.
        "NSMicrophoneUsageDescription":
            "wata records voice messages to send to your family.",
    }
    if delegate_class is not None:
        # WatchKit reads the delegate under the EXTENSION key even for a
        # watchOS 7+ single-target app, and even when the name is passed as
        # WKApplicationMain's argument — its own error message names this
        # key. Set both so neither route is the one that is missing.
        info["WKExtensionDelegateClassName"] = delegate_class
        info["WKApplicationDelegateClassName"] = delegate_class
    with open(app_path / "Info.plist", "wb") as f:
        plistlib.dump(info, f)
    simrun.run(["codesign", "--force", "--sign", "-", "--timestamp=none",
                str(app_path)])
    return app_path


# The pieces that are identical to the phone's, re-exported so a harness
# imports one module.
simctl = simrun.simctl
devset = simrun.devset
run = simrun.run
launch_and_expect = simrun.launch_and_expect
launch_expect_verdict = simrun.launch_expect_verdict
shutdown = getattr(simrun, "shutdown", None)
