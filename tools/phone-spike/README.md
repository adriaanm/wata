# phone-spike — sgola-emitted Go through `gomobile bind`

Plan [0023](../../docs/plans/0023-sgola-everywhere.md) milestone 1. One
question: **does sgola-emitted Go survive gomobile's toolchain and type
constraints at the bind surface?** It does. `REPORT.md` is the answer and the
friction list; this file is how to rerun it.

```
just phone-spike                 # emit, bind, shell, smoke
just phone-spike --only bind     # one stage (emit | bind | shell | smoke)
```

Needs Xcode, the pinned sgola toolchain (`just sync`), and:

```
go install golang.org/x/mobile/cmd/gomobile@latest \
           golang.org/x/mobile/cmd/gobind@latest
```

Nothing here is product code and nothing outside `tools/phone-spike/` changed
for it. It is not in `just ci` — it needs Xcode and a several-minute bind.

## What is here

| path | what it is |
|------|------------|
| `watabind/` | a Sgola module linking `wataclient` whole-program; its `Bind` object is the surface the phone reaches |
| `wataclient` | a symlink to the repo-root library — `sgo`'s in-link dep search only looks in the declaring module's parent dir |
| `aslib.py` | rewrites the emitted `package main` into an importable `package watacore` |
| `watamobile/` | the hand-written Go shim that gobind actually binds: strings in, strings out |
| `swift/` | the Swift shell (+ its bridging header) that drives the bound macOS framework |
| `spike.py` | the four stages, end to end |
| `out/` | build products (gitignored): the two xcframeworks, `watashell`, `server.log` |

## The pipeline, in one paragraph

`sgo build` compiles `watabind` — core + json + wataclient + the spike's own
capability impls — into one whole-program Go module under
`watabind/.sgo/watacore/`. That module is `package main`, which Go cannot
import, so `aslib.py` rewrites the package clause (and `func main` to
`func RunCLI`, which keeps the CLI runnable and the `os` import used). The
hand-written `watamobile` package requires that module through a `replace` and
re-exports two functions in types gobind can carry. `gomobile bind` turns
`watamobile` into an xcframework for `ios,iossimulator` and another for
`macos`. `swiftc` builds a CLI against the macOS one, and the smoke boots a
`wata-server`, seeds a family room, and checks the lines the Swift process
prints — which are rendered inside Sgola, from a live `StateSnapshot`.

## Rerunning after a source change

`sgo build` rewrites the emission on every build, so `aslib.py` has to run
again — that is why `emit` is one stage. Running `just phone-spike` from the
top always does the right thing.
