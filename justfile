# wata recipes — run `just` to list, `just <recipe>` to run one.
#
# Every recipe resolves the sgola toolchain the same way the scripts do: the
# pinned clone under .toolchain/sgola, unless SGOLA_HOME is set. So sgola can
# drive any of these against an in-development compiler:
#
#     SGOLA_HOME=/path/to/sgola just smoke

# list recipes
default:
    @just --list

# ── Toolchain ─────────────────────────────────────────────────────────────────

# clone/checkout the pinned sgola commit and build the toolchain
sync:
    tools/toolchain.py sync

# what's present, and does it match the pin
status:
    tools/toolchain.py status

# bump the pin to a new sgola commit (then run `just sync`)
pin COMMIT:
    tools/toolchain.py pin {{COMMIT}}

# ── IDE ───────────────────────────────────────────────────────────────────────

# Metals speaks BSP to `sgo bsp` — one build target per Sgola module. In VS
# Code, open wata.code-workspace (multi-root: one folder per module, one BSP
# session each). In a single-root editor, open a MODULE dir (wataclient/,
# wata-server/, wata-fb/), not the repo root.
#
# write the .bsp/sgo.json launch files Metals discovers, and bridge the dep
# TASTy paths the BSP shim expects (rerun after `just sync`)
ide:
    tools/ide-setup.py

# ── Build ─────────────────────────────────────────────────────────────────────

# build every app
build: build-server build-fb build-tui

# build the homeserver
build-server:
    cd wata-server && ../tools/sgo build

# build the device client (native; audio is stubbed off-device)
build-fb:
    cd wata-fb && ../tools/sgo build

# build the terminal client / admin REPL
build-tui:
    cd wata-tui && ../tools/sgo build

# run the homeserver, default port 8008
server PORT="8008":
    cd wata-server && ../tools/sgo run :{{PORT}}

# ── Test ──────────────────────────────────────────────────────────────────────

# Each script prints its own PASS; just stops at the first failure.
#
# the whole gate
ci: smoke persist admin-smoke cmd-smoke push-smoke ptt-smoke fb-smoke wataui-tests client-tests apns-tests integ golden fb-ui-tests bindgen-tests facade-check mac-build-check amd64-smoke tunnel-smoke objc-spike callback-spike interp-spike

# homeserver: selfcheck, live Matrix session, long-poll concurrency, -race
smoke:
    bash tools/wata-smoke.sh

# homeserver: kill -9 and replay the JSONL journal
persist:
    bash tools/wata-persist-smoke.sh

# homeserver: the admin surface — password hashing at rest, the admin gate,
# accounts CRUD against a live server, /admin
admin-smoke:
    tools/wata-admin-smoke.py

# homeserver: the device-command mailbox — admin gate, take-once long-poll
# delivery, latest-wins reports, in-memory-only across a restart
cmd-smoke:
    tools/wata-cmd-smoke.py

# homeserver: APNs pushes against a local fake Apple — registration, the
# per-message fan-out, 410-forgets-the-token, and silence with no APNs config
push-smoke:
    tools/wata-push-smoke.py

# homeserver: PushToTalk channel pushes — the ephemeral token's lifetime, the
# pushtotalk type at the .voip-ptt topic, and what a device holding both gets
ptt-smoke:
    tools/wata-ptt-smoke.py

# device client: native build+run, armv7 cross-cgo build
fb-smoke:
    bash tools/wata-fb-smoke.sh

# device client: byte-exact golden frame against tools/fb-golden.png
golden:
    bash tools/fb-golden.sh

# device UI: scripted runs of the real frame loop, golden frames per checkpoint
fb-ui-tests *ARGS:
    tools/fb-ui-tests.py {{ARGS}}

# does wata-mac grow while it sits idle? RSS + Go live heap + OS threads
# together, so the answer says WHERE (tools/mac-leak.py header)
mac-leak *ARGS:
    tools/mac-leak.py {{ARGS}}

# device UI: the real frame loop in this terminal, against a live server
fb-sim *ARGS:
    bash tools/fb-sim.sh {{ARGS}}

# plan 0038 probe: can Sgola reach the C ABI with no Go of ours? YES
objc-spike:
    # Builds AND runs its oracle (sgola 1c6d6ed): the C-string bracket
    # `go.cstring(s) { p => syscallN(…, p) }` closed the last gap, so the
    # binary does a real objc_msgSend round-trip and must print
    # `objc-spike: length = 5`. Run by ci, so ci asserts the oracle.
    # tools/objc-spike/REPORT.md owns the expectation.
    # The run's own exit is 0 even on the caught-error branch, so the grep is
    # the assertion: the exact oracle line, or the recipe fails.
    cd tools/objc-spike && ../../tools/sgo build && \
      ./.sgo/objc-spike/objc-spike | tee /dev/stderr | grep -qx 'objc-spike: length = 5'

# MAC-IDLE-LEAK arbiter: does Diff.diff retain the trees it walks? ANSWERED
# no — every arm flat/bounded (tools/diff-spike/REPORT.md owns the series and
# the verdict). Kept runnable so the answer can be re-taken after a repin.
# `just diff-spike` runs all five arms; `just diff-spike e big` runs one.
diff-spike *ARGS:
    cd tools/diff-spike && ../../tools/sgo build && \
      if [ -n "{{ARGS}}" ]; then ./.sgo/diff-spike/diff-spike {{ARGS}}; \
      else for a in a b c d e; do ./.sgo/diff-spike/diff-spike $a; done; fi

# plan 0038 gate: can a facade express AppKit geometry? YES (IOP-6)
interp-spike:
    # Builds AND runs its oracle (sgola 329656e, where FACADE-VALUE-STRUCT
    # landed): CGRect/CGPoint/CGSize as facade value structs, constructed in
    # Sgola, passed through initWithFrame: by value and read back from frame.
    # Run by ci, so ci asserts the oracle.
    # tools/interp-spike/REPORT.md owns the expectation.
    cd tools/interp-spike && ../../tools/sgo build && \
      ./.sgo/interp-spike/interp-spike | tee /dev/stderr | grep -qx 'interp-spike: PASS'

# plan 0038 leg 2: an ObjC method whose body is Sgola (waiting on go.callback)
callback-spike:
    # Builds AND runs its oracle (sgola cb15191, where go.callback landed):
    # an ObjC class synthesized at runtime dispatches a method whose IMP is a
    # Sgola literal registered via go.callback, and the msgSend must answer
    # exactly 42 — the first C-to-Sgola control transfer in the project.
    # Run by ci, so ci asserts the oracle.
    # tools/callback-spike/REPORT.md owns the expectation.
    # The run's own exit is 0 even on the caught-error branch, so the grep is
    # the assertion: the exact oracle line, or the recipe fails.
    cd tools/callback-spike && ../../tools/sgo build && \
      ./.sgo/callback-spike/callback-spike | tee /dev/stderr | grep -qx 'callback-spike: PASS'

# UI layer: portability/dependency tripwires, the differ's round-trip oracle
wataui-tests:
    bash tools/wataui-tests.sh

# the APNs pusher (plan 0065 tier 2): JWT header/claims/signature (raw
# R||S, not ASN.1 DER), request shape, token caching/refresh, and the
# 200/410/4xx outcomes — all against a local fake APNs server, no Apple
# credentials or device involved. Portable Go; in ci.
apns-tests:
    cd go-pkgs/apns && GOWORK=off go test ./...

# client core: portability tripwire, sync/fixture/ogg byte oracles
client-tests:
    bash tools/wataclient-tests.sh

# client core: 14 live client-server scenarios, fresh server each
integ:
    bash tools/wataclient-integ.sh

# Apple bindings (plan 0026): the generator's unit tests, over the committed
# clang-AST fixtures. No Xcode, no SDK, no device.
bindgen-tests:
    tools/bindgen/test_bindgen.py

# Apple bindings: regenerate go-pkgs/appleptt from the SDK headers, then gofmt,
# go vet and build it for ios/arm64. Needs Xcode; not in ci.
bindgen *FLAGS:
    tools/bindgen/regen.sh {{FLAGS}}

# iOS client (plan 0044): go vet + go build of the generated UIKit bindings
# for GOOS=ios/arm64 against the iphonesimulator sysroot. Needs Xcode; not in ci.
ios-build-check:
    tools/ios-build-check.py

# wata-ios on a physical iPhone (plan 0061): build for the iphoneos sysroot,
# bundle, sign (needs WATA_TEAM_ID + a profile), devicectl install.
# Stage-by-stage via `--only build|bundle|sign|install`. Needs Xcode; not in ci.
ios-device *FLAGS:
    tools/ios-device.py {{FLAGS}}

# pull wata-ios's on-device log (Documents/wata.log, plan 0064) over
# devicectl and print it. Device from --device/$WATA_DEVICE or the single
# attached iPhone; bundle id from --bundle-id/$WATA_BUNDLE_ID. Needs Xcode.
ios-log *FLAGS:
    tools/ios-log.py {{FLAGS}}

# Apple bindings: the runtime leg — the generated Foundation wrappers driven
# against this Mac's ObjC runtime (dispatch, blocks, NSError**, and a
# synthesized delegate class Foundation itself calls). Needs macOS; not in ci.
bindgen-runtime:
    cd go-pkgs/appleptt && GOWORK=off go test -tags objcruntime ./...

# the retained AppKit backend (plans 0032/0038): the Sgola interpreter's own
# suite (`wata-mac interptest` — the native hierarchy mirrors wataui's
# applyAll for build-from-scratch and patch scripts, in-place mutation vs
# replace, paint order, the offscreen render probes, the pure
# pixel/glyph/key tables), plus the remaining Go tests: nativeui's dispatch
# seam and raw-code key view, macshell's chrome (login/menu/devices).
# The binary exits 0 either way, so the grep IS the assertion.
# Needs macOS (like bindgen-runtime); not in ci.
nativeui-tests:
    cd go-pkgs/nativeui && GOWORK=off go test ./...
    cd go-pkgs/macshell && GOWORK=off go test ./...
    cd wata-mac && ../tools/sgo build && \
      ./.sgo/wata-mac/wata-mac interptest | tee /dev/stderr | grep -qx 'interptest: PASS'

# the macOS audio backend (plan 0033): go-pkgs/macaudio's unit tests — the
# AudioToolbox opus round trip and the foreign-encoder fixture judged by tone
# purity, and the capture/playback discipline under WATA_MAC_AUDIO=fake, so no
# mic grant and no speaker are involved. Needs macOS; not in ci.
macaudio-tests:
    cd go-pkgs/macaudio && GOWORK=off go test ./...

# wata-mac's screens ARE wata-fb's, by symlink — so an edit under wata-fb/src
# is an edit to two apps. Compile the second one, so stubs.scala's tripwire
# fires in ci and not only when someone runs the macOS-only mac-smoke. A SKIP
# with a reason off darwin (the app's godeps are AppKit/AudioToolbox). In ci.
mac-build-check:
    tools/mac-build-check.py

# the facades a SYMLINKED source binds must declare the same thing: wata-fb's
# and wata-mac's `go.audio` (plan 0033), compared declaration by declaration
# with comments and the @go.bind path ignored. Pure text — in ci.
facade-check:
    tools/facade-check.py

# Apple bindings: the struct-callback spike — ObjC methods whose C signatures
# carry structs (CGRect/NSRange/...) dispatched into Go callbacks, in both
# shapes the pin supports: register decomposition (what the emitter specifies)
# and v0.11's typed structs (what it could collapse to), driven by NSInvocation
# and a real AppKit drawRect:. Needs macOS arm64; the first run fetches
# modules. Not in ci.
bindgen-structcb:
    cd tools/bindgen/spikes/structcb/decomp && GOWORK=off go test ./...
    cd tools/bindgen/spikes/structcb/upstream && GOWORK=off go test ./...

# Apple bindings: build the PushToTalk hello (plan 0026's hardware gate) —
# hellopt/ as an ios/arm64 c-archive, linked with the ObjC shell into
# out/WataHello.app. Unsigned by default; --sign/--install are the owner's legs
# and need the restricted push-to-talk entitlement (tools/bindgen/hello/README).
ptt-hello *FLAGS:
    tools/bindgen/hello/build.py {{FLAGS}}

# terminal client: two scripted REPL sessions against a fresh server (bob
# sends a canned Ogg, alice snaps/plays/pokes). ~10s, so standalone, not in ci.
tui-smoke:
    tools/tui-smoke.py

# macOS client (plan 0032): build the wata-mac app
mac-build:
    cd wata-mac && ../tools/sgo build

# macOS client: the real window against a live server (WATA_MAC_USER/PASS/HS
# or args) — the owner's leg: look at it, keyboard only
mac *ARGS:
    cd wata-mac && ../tools/sgo run {{ARGS}}

# macOS client: headless end-to-end against a fresh server — native hierarchy
# asserted, a mid-session message patches exactly its rows, the key path opens
# the conversation. ~30s, standalone like tui-smoke; macOS only, not in ci.
mac-smoke:
    tools/mac-smoke.py

# macOS client: the failure-scenario suite (plan 0045) — wrong password,
# unreachable, hung server, mid-session loss, send failure, denied mic; judged
# on tree dumps + the title seam. ~1min, standalone; macOS only, not in ci.
mac-ui-tests *ARGS:
    tools/mac-ui-tests.py {{ARGS}}

# macOS client as a real .app (plan 0037): Info.plist, icon, ad-hoc signature.
# A bundle is what makes notifications, the microphone and a stable Keychain
# identity possible at all — and what a non-technical user double-clicks.
mac-app *FLAGS:
    tools/mac-app.py {{FLAGS}}

# redraw tools/wata.iconset from tools/mac-icon.py (~40s; the PNGs are committed)
mac-icon:
    tools/mac-icon.py

# macOS client notifications (plan 0037 slice 4): headless, one fresh server,
# bob sending — the DECISION per arrival (banner / suppressed while frontmost /
# played in walkie-talkie mode) plus the badge adding up and clearing, and the
# mode surviving a restart. macOS only, not in ci.
mac-notify-smoke:
    tools/mac-notify-smoke.py

# macOS client admin surface (plan 0037 slice 5): headless, one fresh server
# and a fake handset on the command mailbox — the Devices window's real
# controls driven with no mouse, asserting the REQUESTS (a scan aimed at the
# picked handset, a join carrying the ssid and the exact password, wifi off
# with its window, an approve that binds an account) and that the password
# reaches the handset and NOTHING else. macOS only, not in ci.
mac-devices-smoke:
    tools/mac-devices-smoke.py

# macOS client credentials (plan 0036): three headless runs — with a password
# in the environment, with NOTHING in it, and with the stored token no longer
# valid. Touches the login keychain; macOS only, not in ci.
mac-creds-smoke:
    tools/mac-creds-smoke.py

# macOS client over embedded iroh (plan 0034): the same app, built with
# `-tags iroh` over its emitted tree — the transport the client exists for, a
# parent away from home. Needs cargo (mklib.py stages the Rust staticlib);
# `just mac-build` stays cargo-free.
mac-iroh-build:
    tools/mac-iroh-build.py

# macOS client over iroh, end to end: one wata-server with NO TCP port, the
# headless mac dialing it over iroh (contact list, then bob's message
# arriving), and the negative — a transport that cannot come up must show
# `transport unavailable`, not `waiting for network`. Needs cargo; macOS only,
# not in ci.
mac-iroh-smoke:
    tools/mac-iroh-smoke.py

# terminal client: a REPL against a live server (WATA_TUI_USER/PASS/HS or args)
tui *ARGS:
    cd wata-tui && ../tools/sgo run {{ARGS}}

# throughput and concurrency benchmarks
bench:
    bash tools/wata-bench.sh

# These are committed baselines. Review the diff; don't rubber-stamp it.
#
# regenerate the wataclient fixture oracles
fixtures:
    bash tools/wataclient-fixtures.sh

# re-encode the startup chirp from the owner's source recording (--check diffs
# the committed asset against a fresh encode without writing)
make-chirp *ARGS:
    tools/make-chirp.py {{ARGS}}

# ── Deploy ────────────────────────────────────────────────────────────────────

# cross-build armv7 and deploy the device client
fb-deploy *FLAGS:
    bash tools/fb-deploy.sh {{FLAGS}}

# did the handset's startup chirp make a sound? restarts the app on the device
# and listens on this Mac's mic (--cold-boot reboots instead; --no-restart is
# the negative control)
chirp-check *ARGS:
    tools/chirp-check.py {{ARGS}}

# the device panel as a PNG, over ssh — what the handset is showing right now
fb-shot *ARGS:
    python3 tools/fb-shot.py {{ARGS}}

# macOS service (plan 0015, [SRV-PACKAGE]): package wata-server, no sudo
server-package *FLAGS:
    python3 tools/server-service.py package {{FLAGS}}

# package the current tree (iroh flavor unless FLAGS says otherwise), then install it at
# /usr/local/wata. Run WITHOUT sudo: packaging must stay unprivileged (a root build would
# root-own the emit tree and caches); only the install step prompts for sudo.
server-install *FLAGS="--iroh": (server-package FLAGS)
    sudo python3 tools/server-service.py install

# bootout + remove the daemon files (needs sudo; add --purge to also drop data)
server-uninstall *FLAGS:
    python3 tools/server-service.py uninstall {{FLAGS}}

# layout/daemon/journal state
server-status *FLAGS:
    python3 tools/server-service.py status {{FLAGS}}

# launchctl kickstart the daemon (needs sudo) — after an etc/wata.env edit
server-restart *FLAGS:
    python3 tools/server-service.py restart {{FLAGS}}

# no-sudo gate: package, install into a temp root, boot, serve, check the journal
server-selftest:
    python3 tools/server-service.py selftest

# linux/amd64 server smoke — the always-on box is a real target
amd64-smoke:
    bash tools/linux-amd64-smoke.sh

# embedded iroh (plan 0013): server + client session over iroh streams, two
# processes, one machine, no real network and no TCP port. Needs cargo.
tunnel-smoke:
    tools/tunnel-smoke.py

# on-device iroh LAN smoke (plan 0013): armv7-musl cross-build, then the BQ268
# dials this machine's wata-server over embedded iroh (direct LAN addrs, no
# relay). Needs cargo + rustup + zig + the device (ssh host bq268); not in ci.
iroh-lan-smoke:
    tools/iroh-lan-smoke.py

# on-device iroh ROAM smoke (plan 0013 milestone 3): wifi down over the USB
# serial console, the BQ268 dials this machine via the n0 relay over CELLULAR
# by node id alone, then wifi is restored. Needs the serial console
# (BQ268_SERIAL) besides everything iroh-lan-smoke needs; not in ci.
iroh-roam-smoke:
    tools/iroh-roam-smoke.py

# the Gio blit shell (plan 0023 M2): the REAL wata-fb frame loop in a macOS
# window — the 160x128 frame blitted as a scaled nearest-neighbour texture,
# five touch buttons for the five keys. Boots its own scratch server; add
# `--frames N` for the unattended sanity run. Needs a `-tags gioshell` build,
# which it does itself. Not in ci (it opens a window).
phone-blit *FLAGS:
    tools/phone-blit.py {{FLAGS}}

# the phone spike (plan 0023 M1): sgola-emitted wataclient through `gomobile
# bind` into iOS + macOS xcframeworks, then a Swift shell logs in and syncs
# against a scratch wata-server. Needs Xcode + gomobile/gobind; not in ci.
#   just phone-spike --only bind      # one stage
phone-spike *FLAGS:
    tools/phone-spike/spike.py {{FLAGS}}

# the Apple-audio derisk spike (AUDIO-APPLE-DERISK): AudioToolbox's built-in
# Opus codec + AVAudioEngine render, all through purego/generated bindings —
# no cgo. Unattended by default; `just audio-spike -only capture` is the
# owner's mic leg (needs the terminal's mic TCC grant). Report:
# tools/audio-spike/REPORT.md. macOS-only; not in ci (it plays audio).
audio-spike *FLAGS:
    cd tools/audio-spike && go run . {{FLAGS}}

# the iOS architecture spike (IOS-CLIENT-ASSEMBLY): one pure-Go binary driving
# UIKit through purego — no Swift, no ObjC source, no Xcode project, no
# gomobile — built, bundled by hand and run in the iOS simulator. Report:
# tools/ios-spike/REPORT.md. Needs Xcode + an iOS simulator runtime; not in ci.
#   just ios-spike --only run         # one stage
ios-spike *FLAGS:
    tools/ios-spike/spike.py {{FLAGS}}

# the watchOS architecture spike (plan 0069 stage 1): one pure-Go binary that
# IS a standalone watch app — WatchKit lifecycle, a delegate and root
# controller synthesized from Go, and a rasterized frame on the panel through
# the scene's UIWindow. No Swift, no storyboard, no Xcode project. Stages:
# build, bundle, run (what the objc runtime contains), uiapp (the NEGATIVE
# result: watchOS refuses UIApplicationMain), wkapp (the working shell).
# Needs Xcode + a watchOS simulator runtime; not in ci.
#   just watch-spike --only wkapp     # one stage
watch-spike *FLAGS:
    tools/watch-spike/spike.py {{FLAGS}}

# the plan-0069 stage-1 product gate: the watch spike's proofs re-taken
# through the product packages (go-pkgs/watchshell + go-pkgs/iosui, the
# latter unchanged from the phone) — build, hand-bundle, run on the shared
# Series 10 device, assert the printed proofs incl. the offscreen render
# probe, and screenshot the real panel. Needs Xcode + a watchOS simulator
# runtime; not in ci.
#   just watch-hello --only run       # one stage
watch-hello *FLAGS:
    tools/watch-hello/hello.py {{FLAGS}}


# the plan-0044 stage-2 gate: the spike's proofs re-taken through the product
# packages (go-pkgs/iosshell + go-pkgs/iosui) — build, hand-bundle, run on the
# shared simulator device, assert the printed proofs incl. the offscreen render
# probe. Needs Xcode + an iOS simulator runtime; not in ci.
#   just ios-hello --only run         # one stage
ios-hello *FLAGS:
    tools/ios-hello/hello.py {{FLAGS}}

# the plan-0044 stage-3 gate: `wata-ios interptest` in the simulator — sgo
# emits the app, the emitted module cross-builds for the simulator, and the
# retained UIKit interpreter's suite runs on the shared device (verdict = the
# printed `interptest: PASS`, the wata-mac discipline). Needs Xcode + an iOS
# simulator runtime; not in ci.
#   just ios-interptest --only run    # one stage
ios-interptest *FLAGS:
    tools/ios-interptest.py {{FLAGS}}

# the plan-0044 stage-4 gate: wata-ios in the simulator against a live
# wata-server — the boot screen paints before the session connects, alice
# logs in, the contact list paints, and a message bob sends mid-session over
# plain HTTP surfaces (`notify:`) inside the latency budget (verdict = the
# app's printed lines, judged by tools/ios-smoke.py). Needs Xcode + an iOS
# simulator runtime; not in ci.
ios-smoke:
    tools/ios-smoke.py

# the plan-0062 gate: the whole phone-enrol flow in the simulator — fresh
# install, wata://configure from the admin page's card, plain-TCP announce,
# approve, passwordless device-login over iroh, contacts painted, and an
# inbound message surfacing over the iroh sync inside the latency budget
# (the same leg ios-smoke runs over plain HTTP, so the pair times both). Needs
# Xcode + a simulator runtime + the ios-sim irohnet archive; not in ci.
ios-enroll-smoke:
    tools/ios-enroll-smoke.py

# the plan-0065 tier-2 client gate: `xcrun simctl push` hands a realistic wata
# APNs payload to the running app (no APNs connection, no Apple credentials),
# and it must be presented and read — plus the registration attempt reported
# either way. Needs Xcode + a simulator runtime; not in ci.
ios-push-smoke:
    tools/ios-push-smoke.py


# the plan-0069 stage gate: `wata-watch interptest` on the watch simulator —
# the SAME suite ios-interptest runs (interptest.scala copied over, one PTT
# case dropped), so a green run says the retained stage, wataui's differ and
# the render probes behave on watchOS exactly as on iOS.
# Needs Xcode + a watchOS simulator runtime; not in ci.
#   just watch-interptest --only run  # one stage
watch-interptest *FLAGS:
    tools/watch-interptest.py {{FLAGS}}

# plan 0069's hardware leg: build, sign and install wata-watch on a real Apple
# Watch (ios-device's twin). Needs a watchOS App Development profile — an iOS
# profile cannot sign a watch app — and the watch in Developer Mode, paired to
# Xcode. The sign stage prints the portal steps when either is missing.
#   just watch-device --only build    # one stage
watch-device *FLAGS:
    tools/watch-device.py {{FLAGS}}

# plan 0069's wrist session: the scriptable half of the hardware legs
# (WATCH-INPUT-DELIVERY, WATCH-AUDIO). Stages, owner's fingers between them:
#   just watch-wrist serve    # LAN wata-server, foreground
#   just watch-wrist launch   # start the installed app against it
#   just watch-wrist send     # bob sends a voice message
#   just watch-wrist log      # pull the app's sandbox log off the watch
#   just watch-wrist check    # server-side family message count
watch-wrist *FLAGS:
    tools/watch-wrist.py {{FLAGS}}

# plan 0070: photograph the rolodex on the watch simulator — at rest, mid-scroll
# with the stack open, and settled on another contact. A LOOKING tool, not a
# gate (watch-interptest's rolodex cases are the oracle); not in ci.
watch-rolodex *FLAGS:
    tools/watch-rolodex.py {{FLAGS}}

# plan 0069's stage-2 gate: wata-watch on the watch simulator against a live
# wata-server — login, sync, and bob's voice message ARRIVING mid-session
# (ios-smoke's twin). The whole client, not a probe.
# Needs Xcode + a watchOS simulator runtime; not in ci.
watch-e2e *FLAGS:
    tools/watch-e2e.py {{FLAGS}}
