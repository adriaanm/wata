# Wata

Wata is a Matrix client-server + framebuffer device stack written in
**Sgola** (restricted Scala 3 compiled to Go). It was the M7/M8 proving
vertical for the sgola spike; with BUILD chunk E2b it moved OUT of the
sgola tree into this sibling repo, so it is now a **real multi-module
consumer** of published sgola libraries — the north-star build stories
exercised end to end (`go get` a Sgola dep, `sgo build`).

## Modules

| dir            | kind                | consumes                    |
|----------------|---------------------|-----------------------------|
| `wataclient/`  | published Sgola lib | `json` (via go.mod require) |
| `wata-server/` | app (Matrix server) | `json`                      |
| `wata-fb/`     | app (device/UI)     | `json`, `wataclient` + a cgo audio dep (`go-pkgs/audio`) |
| `wataclient-jvm/` | JVM-conformance seed (plain scalac; the byte oracles' JVM twin) |
| `go-pkgs/audio/` | plain Go cgo module (opus/tinyalsa; wata-fb's device audio) |

`json` is NOT in this repo — it is a **published sgola module**
(`sgola.spike/json`), required by go.mod and resolved through Go's module
cache (`pkg/mod`); its `sgola/src` compiles into the consumer's
whole-program link (source-in-link — the sgola BUILD-E2a mechanism).

## Building

Building a wata module needs the **sgola toolchain** (the `sgo` driver +
the JVM compile-time front end), reached via `$SGOLA_HOME` (the sgola
tree, in the spike; a released home in production — the go:embed v1
stand-in). With the toolchain and the module deps resolved into a Go
module cache:

```
cd wata-server        # or wata-fb
sgo build             # discovery-driven: go.mod + .scala tree + sgo.build/sgo.deps markers
sgo run               # build + run
```

Deps — Go AND Sgola — are `require` lines in each module's `go.mod`,
fetched by `go get`, resolved by MVS. There are **no sgola-tree relative
paths** in any wata manifest: this repo is `go get`-shaped.

`sgo.build` / `sgo.deps` are the spike's per-module build markers (mode,
emitname, in-link chain, cgo godep) — a temporary stand-in that folds into
go.mod's model as sgola's build story matures; schema in the sgola tree's
`core/sgo.build`.

## The scenario suite (`tools/ci.sh`)

`tools/ci.sh` runs the wata regression suite — the six scenarios that used
to live as sgola ci steps 11–16, preserved verbatim here (the code and its
fixtures live together now):

1. `wata-smoke.sh`        — store-ADT selfcheck + live Matrix session + long-poll concurrency + `-race`
2. `wata-persist-smoke.sh`— kill-9 + reboot-from-JSONL state survival
3. `wata-fb-smoke.sh`     — wata-fb native (audio stub) + armv7 cross-cgo build (zig-gated)
4. `wataclient-tests.sh`  — go.* tripwire, plugin emit, JVM conformance seed, sync/fixture/ogg/foreign byte oracles
5. `wataclient-integ.sh`  — 10 live client↔server scenarios, fresh server each, `-race` client
6. `fb-golden.sh`         — framebuffer golden-frame PNG byte-identity

The sgola gate (its ci) runs this same suite from a hermetic build of this
repo (its "wata proving consumer" step), so the compiler-regression value
of the old steps 11–16 is preserved while the fixtures live where the code
lives.

Run it with the toolchain + a resolved dep cache in the environment:

```
SGOLA_HOME=/path/to/sgola/spike tools/ci.sh
```

## Debt

Wata-app debt rides `WATA-TODO.md` (it moved here with the code).

## Shortcuts (this repo)

- **`$SGOLA_HOME` reaches the sgola toolchain** — the v1 packaging
  stand-in (decision 10's go:embed release home not yet built); a real
  release ships `sgo` + the compiler + the prelude self-contained.
- **`sgo.build` / `sgo.deps` markers** — the spike's per-module build
  knobs (not yet folded into go.mod).
- **The dep cache is populated hermetically** — the wata ci and the sgola
  proving-consumer step build a file-GOPROXY from the published `json`
  (and `wataclient`) trees; a developer with the real proxies configured
  gets the same via plain `go get`.
