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

# The payload embeds source text and TASTy, so it goes stale the moment a
# comment under wataclient/src/ moves.
#
# regenerate wataclient/sgola/, the committed publish payload
emit-payload:
    tools/sgo emit {{justfile_directory()}}/wataclient

# ── Test ──────────────────────────────────────────────────────────────────────

# the whole gate
ci:
    bash tools/ci.sh

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

# client core: portability tripwire, emit, JVM oracle, sync/fixture oracles
client-tests:
    bash tools/wataclient-tests.sh

# client core: 10 live client-server scenarios, fresh server each
integ:
    bash tools/wataclient-integ.sh

# Needs $WATA_TS_REPO (default ~/g/bq268/wata) with node_modules installed.
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
