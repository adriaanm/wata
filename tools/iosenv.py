"""The GOOS=ios cross-build environment, computed from Xcode.

One function, `go_env(sdk)`: the environment `go build`/`go vet` need to
target iOS — the same env gomobile's appleEnv sets (x/mobile/cmd/gomobile/
env.go) for the matching target, minus gomobile. Shared by the ios spike
(tools/ios-spike/spike.py) and `just ios-build-check`.
"""

import os
import subprocess

MIN_IOS = "17.0"

# watchOS 26 is the first release running full arm64 (Series 9/10/Ultra 2);
# every earlier watch is arm64_32, which Go does not target. So the watch
# floor is not a compatibility choice, it is the first version Go can reach.
MIN_WATCHOS = "26.0"

_MINFLAG = {
    "iphonesimulator": "-mios-simulator-version-min",
    "iphoneos": "-miphoneos-version-min",
    "watchsimulator": "-mwatchos-simulator-version-min",
    "watchos": "-mwatchos-version-min",
}

_MINVERSION = {
    "iphonesimulator": MIN_IOS,
    "iphoneos": MIN_IOS,
    "watchsimulator": MIN_WATCHOS,
    "watchos": MIN_WATCHOS,
}


def sdk_paths(sdk):
    """(sysroot, clang) for an Xcode SDK name."""
    def x(*a):
        return subprocess.run(["xcrun", "--sdk", sdk, *a],
                              capture_output=True, text=True, check=True).stdout.strip()
    return x("--show-sdk-path"), x("--find", "clang")


def go_env(sdk, min_ios=None):
    """os.environ plus everything a GOOS=ios/arm64 cgo build needs for `sdk`.

    The watchOS sdks ride the same GOOS=ios: Go has no watchos target and
    will not get one soon (golang/go#60180, closed frozen), but a cgo build
    links externally through clang, and it is clang's -mwatchos-version-min
    that stamps LC_BUILD_VERSION.
    """
    root, clang = sdk_paths(sdk)
    minv = min_ios or _MINVERSION[sdk]
    flags = f"-isysroot {root} {_MINFLAG[sdk]}={minv} -arch arm64"
    return dict(os.environ,
                GOWORK="off", GOOS="ios", GOARCH="arm64", CGO_ENABLED="1",
                CC=clang, CXX=clang + "++",
                CGO_CFLAGS=flags, CGO_CXXFLAGS=flags, CGO_LDFLAGS=flags)
