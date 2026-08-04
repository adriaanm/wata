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

# build both apps
build: build-server build-fb

# build the homeserver
build-server:
    cd wata-server && ../tools/sgo build

# build the device client (native; audio is stubbed off-device)
build-fb:
    cd wata-fb && ../tools/sgo build

# run the homeserver, default port 8008
server PORT="8008":
    cd wata-server && ../tools/sgo run :{{PORT}}

# ── Test ──────────────────────────────────────────────────────────────────────

# Each script prints its own PASS; just stops at the first failure.
#
# the whole gate
ci: smoke persist fb-smoke client-tests integ golden fb-ui-tests amd64-smoke

# homeserver: selfcheck, live Matrix session, long-poll concurrency, -race
smoke:
    bash tools/wata-smoke.sh

# homeserver: kill -9 and replay the JSONL journal
persist:
    bash tools/wata-persist-smoke.sh

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

# client core: portability tripwire, sync/fixture/ogg byte oracles
client-tests:
    bash tools/wataclient-tests.sh

# client core: 14 live client-server scenarios, fresh server each
integ:
    bash tools/wataclient-integ.sh

# Needs node_modules installed at $WATA_TS_REPO (default: this repo — the TS
# reference tree is in-tree since the graft).
#
# conformance oracle: the original TypeScript wata's jest suite, this server
conformance:
    bash tools/wata-tests.sh

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

# linux/amd64 server smoke — the always-on box is a real target
amd64-smoke:
    bash tools/linux-amd64-smoke.sh
# --- iroh-tunnel (connectivity spike; see docs/planning/connectivity-iroh.md) ---

# Server side: run next to the homeserver. Prints a stable NodeID to provision
# into clients, then tunnels accepted iroh connections to the local homeserver.
tunnel-listen *FLAGS:
    cd src/iroh-tunnel && cargo run --release -- listen --to 127.0.0.1:8008 {{FLAGS}}

# Client side: listen on 127.0.0.1:8009 and tunnel to the given homeserver NodeID.
# Point the client's homeserver URL at http://127.0.0.1:8009. Usage:
#   just tunnel-connect <NodeID>
tunnel-connect node *FLAGS:
    cd src/iroh-tunnel && cargo run --release -- connect --node {{node}} --local 127.0.0.1:8009 {{FLAGS}}

# Build the tunnel binary (release).
tunnel-build:
    cd src/iroh-tunnel && cargo build --release

