"""The GOOS=ios cross-build environment, computed from Xcode.

One function, `go_env(sdk)`: the environment `go build`/`go vet` need to
target iOS — the same env gomobile's appleEnv sets (x/mobile/cmd/gomobile/
env.go) for the matching target, minus gomobile. Shared by the ios spike
(tools/ios-spike/spike.py) and `just ios-build-check`.
"""

import os
import subprocess

MIN_IOS = "17.0"

_MINFLAG = {
    "iphonesimulator": "-mios-simulator-version-min",
    "iphoneos": "-miphoneos-version-min",
}


def sdk_paths(sdk):
    """(sysroot, clang) for an Xcode SDK name."""
    def x(*a):
        return subprocess.run(["xcrun", "--sdk", sdk, *a],
                              capture_output=True, text=True, check=True).stdout.strip()
    return x("--show-sdk-path"), x("--find", "clang")


def go_env(sdk, min_ios=MIN_IOS):
    """os.environ plus everything a GOOS=ios/arm64 cgo build needs for `sdk`."""
    root, clang = sdk_paths(sdk)
    flags = f"-isysroot {root} {_MINFLAG[sdk]}={min_ios} -arch arm64"
    return dict(os.environ,
                GOWORK="off", GOOS="ios", GOARCH="arm64", CGO_ENABLED="1",
                CC=clang, CXX=clang + "++",
                CGO_CFLAGS=flags, CGO_CXXFLAGS=flags, CGO_LDFLAGS=flags)
