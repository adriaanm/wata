# Wata

A Matrix homeserver and a framebuffer device client, written in **Sgola**
(restricted Scala 3 compiled to readable Go source; no JVM at runtime).
Wata is a plain downstream consumer of the sgola compiler — nothing in
this repo is about the compiler's own development.

## Layout

| dir                | what it is                                                              |
|--------------------|-------------------------------------------------------------------------|
| `wata-server/`     | app — the Matrix client-server homeserver                                |
| `wata-fb/`         | app — the device client (framebuffer display, input, audio, LEDs)        |
| `wataclient/`      | library — the portable Matrix client core; published, linked by `wata-fb` |
| `wataclient-jvm/`  | a JVM (plain scalac) twin of the client, used to cross-check behavior     |
| `go-pkgs/audio/`   | plain Go cgo module — opus + tinyalsa, `wata-fb`'s device audio           |
| `tools/`           | build, test, and deploy scripts                                          |
| `docs/`            | design and plan docs (index below)                                       |

Deps between modules are ordinary go.mod `require` lines. `json` comes
from sgola; `wataclient` is published out of this repo. Neither has a
fetchable remote yet, so both are served from a local file-GOPROXY that
`tools/toolchain.py` builds.

## Toolchain

Building needs the sgola compiler, pinned by `tools/toolchain-pin.txt` and
cloned to `.toolchain/sgola` (gitignored). `~/g/sgola`, if present, is an
active development tree — never build against it; a toolchain change must
be a commit here.

```
tools/toolchain.py sync      # clone/checkout the pin, build the toolchain
tools/toolchain.py status    # what's present, does it match the pin
tools/toolchain.py pin <sha> # bump (then sync)

cd wata-server && ../tools/sgo build     # or run, emit, ...
```

`tools/sgo` is the pinned driver plus the right environment; use it rather
than a bare `sgo`. Prerequisites: `go`, JDK, `sbt`, `git`. The pinned Go
version is read out of the toolchain — emitted Go is a pinned-toolchain
product, so a different `go` reformats it and byte-comparisons drift.

### Building against a different sgola

A preset `$SGOLA_HOME` overrides the pin, so sgola can drive wata as a
proving consumer — real code against an in-development compiler:

```
SGOLA_HOME=/path/to/sgola tools/wata-smoke.sh
```

Nothing needs installing in wata first. Every script sources
`tools/sgo-env.sh`, which resolves the home, applies the Go pin, and builds
that home's `sgo` driver if it is absent.

### How the deps resolve

`json` and `wataclient` need **no module proxy and no populated module
cache**. `sgo` resolves an in-link library (the names in `sgo.deps`) by
searching the declaring module's parent dir first, then the toolchain home
— so `wataclient` is found as a sibling in this repo and `json` as the
author module in the sgola tree, and both compile from those sources.

The go.mod `require` lines for them declare the dependency and drive
sgola's *source-in-link* check, which recompiles a dep from a published
artifact in `pkg/mod` to prove the emitted Go is byte-identical either way.
That check is sgola's and it sets up its own hermetic proxy. Wata's build
does not need one.

**`wataclient/sgola/` is a generated, committed publish payload.** Never
hand-edit it; regenerate with `cd wataclient && ../tools/sgo emit .` after
changing `wataclient/src/`.

## How we work here

Non-trivial work gets a **plan doc** in `docs/plans/`, committed *before*
the code, and reviewed as a plan. The point is that the reasoning survives
the session it happened in.

- **Name**: `docs/plans/NNNN-short-slug.md`, NNNN monotonic.
- **Contents**: the problem, the decision and why, what changes
  (file-level), how it will be verified, and what is explicitly out of
  scope. Keep it short — a plan that needs 20 pages is two plans.
- **Status line** at the top: `proposed` / `accepted` / `done` /
  `abandoned`. Abandoned plans stay in the tree with a line saying why;
  that record is the point.
- Skip the plan doc only for genuinely mechanical work (a typo, a version
  bump). If you are unsure, it needs one.

Design docs in `docs/design/` describe **how a subproject is built today**,
not how it should be. When a change lands that invalidates one, update it
in the same commit.

### Open work — `TODO.jsonl`

`TODO.jsonl` is an **ephemeral work queue**, one JSON object per line,
roughly priority-ordered. It holds only open items; it is not a record of
anything. The durable content lives in the docs.

```json
{"key":"FB-PNG-BLOCK","title":"one-line description","doc":"docs/design/wata-fb.md","inProgress":false}
```

- **`key`** — unique and greppable. The full body is tagged `` `[KEY]` ``
  in `doc`; grep it there rather than reading the whole doc.
- **`doc`** — a design doc, or a plan doc for work that has one.
- **`inProgress`** — true while someone is on it.

**Lifecycle.** Finishing an item, or deciding it is out of scope, means
**deleting its line** and reflecting the outcome in its `doc` — the fix
folded into the description of how the thing now works, or the decision
recorded with its reasoning. A line that just disappears has lost the only
thing worth keeping.

Writing style for all docs and comments: say what the code does and why.
No project-history vocabulary, no milestone tags, no references to how the
code got here — git holds that.

Scripts over ~10 lines are Python, not bash.

## Docs

| doc | what it covers |
|-----|----------------|
| [docs/design/wata-server.md](docs/design/wata-server.md) | homeserver: routing, store, sync, persistence |
| [docs/design/wataclient.md](docs/design/wataclient.md) | client core: sync engine, domain model, transport, audio |
| [docs/design/wata-fb.md](docs/design/wata-fb.md) | device client: display, input, audio, cross-build, deploy |
| [TODO.jsonl](TODO.jsonl) | the open-work queue (protocol above) |
| [WATA-TODO.md](WATA-TODO.md) | known debt |
| [docs/plans/](docs/plans/) | plan docs, newest last |
