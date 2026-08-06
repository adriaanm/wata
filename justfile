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
ci: smoke persist admin-smoke cmd-smoke fb-smoke wataui-tests client-tests integ golden fb-ui-tests bindgen-tests facade-check amd64-smoke tunnel-smoke

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

# device client: native build+run, armv7 cross-cgo build
fb-smoke:
    bash tools/wata-fb-smoke.sh

# device client: byte-exact golden frame against tools/fb-golden.png
golden:
    bash tools/fb-golden.sh

# device UI: scripted runs of the real frame loop, golden frames per checkpoint
fb-ui-tests *ARGS:
    tools/fb-ui-tests.py {{ARGS}}

# device UI: the real frame loop in this terminal, against a live server
fb-sim *ARGS:
    bash tools/fb-sim.sh {{ARGS}}

# UI layer: portability/dependency tripwires, the differ's round-trip oracle
wataui-tests:
    bash tools/wataui-tests.sh

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

# Apple bindings: the runtime leg — the generated Foundation wrappers driven
# against this Mac's ObjC runtime (dispatch, blocks, NSError**, and a
# synthesized delegate class Foundation itself calls). Needs macOS; not in ci.
bindgen-runtime:
    cd go-pkgs/appleptt && GOWORK=off go test -tags objcruntime ./...

# the retained AppKit backend (plan 0032): go-pkgs/nativeui's unit tests —
# the native hierarchy mirrors wataui's applyAll for build-from-scratch and
# patch scripts, the offscreen render probes, the dispatch seam, the key view,
# all headless — plus go-pkgs/macshell's wire-grammar tests.
# Needs macOS (like bindgen-runtime); not in ci.
nativeui-tests:
    cd go-pkgs/nativeui && GOWORK=off go test ./...
    cd go-pkgs/macshell && GOWORK=off go test ./...

# the macOS audio backend (plan 0033): go-pkgs/macaudio's unit tests — the
# AudioToolbox opus round trip and the foreign-encoder fixture judged by tone
# purity, and the capture/playback discipline under WATA_MAC_AUDIO=fake, so no
# mic grant and no speaker are involved. Needs macOS; not in ci.
macaudio-tests:
    cd go-pkgs/macaudio && GOWORK=off go test ./...

# the facades a SYMLINKED source binds must declare the same thing: wata-fb's
# and wata-mac's `go.audio` (plan 0033), compared declaration by declaration
# with comments and the @go.bind path ignored. Pure text — in ci.
facade-check:
    tools/facade-check.py

# Apple bindings: the struct-callback spike — ObjC methods whose C signatures
# carry structs (CGRect/NSRange/...) dispatched into Go callbacks, both on the
# pinned purego (register decomposition) and on the v0.11 alpha (typed structs),
# driven by NSInvocation and a real AppKit drawRect:. Needs macOS arm64; the
# first run fetches modules. Not in ci.
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

# ── Deploy ────────────────────────────────────────────────────────────────────

# cross-build armv7 and deploy the device client
fb-deploy *FLAGS:
    bash tools/fb-deploy.sh {{FLAGS}}

# macOS service (plan 0015, [SRV-PACKAGE]): package wata-server, no sudo
server-package *FLAGS:
    python3 tools/server-service.py package {{FLAGS}}

# install the newest staged release at /usr/local/wata (needs sudo; `just server-package` first)
server-install *FLAGS:
    python3 tools/server-service.py install {{FLAGS}}

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

