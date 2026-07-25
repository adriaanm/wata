# Wata

A Matrix homeserver and a framebuffer device client, written in **Sgola**
(restricted Scala 3 compiled to readable Go source; no JVM at runtime).
Wata is a plain downstream consumer of the sgola compiler — nothing in
this repo is about the compiler's own development, and nothing here
asserts anything about the compiler.

**The two repos share exactly one interface:**

```
SGOLA_HOME=/path/to/sgola just smoke
```

That answers sgola's only question — does the compiler still build and
pass tests on a non-trivial Sgola codebase? Anything that would only
serve the compiler's own gate does not belong here.

## Layout

| dir                | what it is                                                              |
|--------------------|-------------------------------------------------------------------------|
| `wata-server/`     | app — the Matrix client-server homeserver                                |
| `wata-fb/`         | app — the device client (framebuffer display, input, audio, LEDs)        |
| `wataclient/`      | library — the portable Matrix client core, linked by `wata-fb`            |
| `go-pkgs/audio/`   | plain Go cgo module — opus + tinyalsa, `wata-fb`'s device audio           |
| `tools/`           | build, test, and deploy scripts                                          |
| `docs/`            | design and plan docs (index below)                                       |

Every module path is `github.com/adriaanm/wata/...`. Deps between modules
are ordinary go.mod `require` lines; `json` comes from sgola and has no
fetchable remote yet, so `sgo` compiles it from the toolchain tree.

## Toolchain

Building needs the sgola compiler, pinned by `tools/toolchain-pin.txt` and
cloned to `.toolchain/sgola` (gitignored). `~/g/sgola`, if present, is an
active development tree — never build against it; a toolchain change must
be a commit here.

```
just            # every recipe this repo has
just sync       # clone/checkout the pin, build the toolchain
just status     # what's present, does it match the pin
just pin <sha>  # bump (then `just sync`)
just build      # both apps;  also build-server, build-fb, server
just ci         # the whole gate: smoke, persist, fb-smoke, client-tests,
                #   integ, golden, amd64-smoke — each runnable on its own
```

`tools/sgo` is the pinned driver plus the right environment; use it rather
than a bare `sgo`. Prerequisites: `go`, JDK, `sbt`, `git`. The pinned Go
version is read out of the toolchain — emitted Go is a pinned-toolchain
product, so a different `go` reformats it and byte-comparisons drift.

### Building against a different sgola

A preset `$SGOLA_HOME` overrides the pin, so sgola can drive wata as a
proving consumer — real code against an in-development compiler:

```
SGOLA_HOME=/path/to/sgola just smoke
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
The build works with `GOPROXY`, `GOMODCACHE`, `GOFLAGS`, and `GOPRIVATE`
all unset.

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

**Finishing a logical task means committing it.** A plan carried out, or a
`TODO.jsonl` item that had no plan of its own, ends in a commit — code,
doc updates, and queue edits together — once its verification is green.
Don't leave finished work sitting in the working tree waiting to be asked
about; an uncommitted tree is work nobody else can see or bisect.

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

**Repeated tasks are code, and are treated as such.** A command you run
twice belongs in the `justfile` — named, documented in one line, and
reviewed like anything else here. `just` lists what this repo can do; a
command that lives only in someone's shell history is undiscoverable and
drifts silently. Recipes stay thin: the logic goes in `tools/`, the
justfile just gives it a name.

## Docs

| doc | what it covers |
|-----|----------------|
| [docs/design/wata-server.md](docs/design/wata-server.md) | homeserver: routing, store, sync, persistence |
| [docs/design/wataclient.md](docs/design/wataclient.md) | client core: sync engine, domain model, transport, audio |
| [docs/design/wata-fb.md](docs/design/wata-fb.md) | device client: display, input, audio, cross-build, deploy |
| [justfile](justfile) | every repeatable operation, one recipe each (`just` to list) |
| [TODO.jsonl](TODO.jsonl) | the open-work queue (protocol above) |
| [WATA-TODO.md](WATA-TODO.md) | known debt |
| [docs/plans/](docs/plans/) | plan docs, newest last |
