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
| `watabind/sgo.deps` | names `wataclient` by explicit relative path (`wataclient ../../../wataclient`) — the nested-module form `sgo.deps` grew for exactly this layout |
| `watamobile/` | the hand-written Go shim that gobind actually binds: `Start`/`Watch(EventSink)`/`Report`/`Stop` over plan 0025's client handle — strings in, strings out |
| `swift/` | the Swift shell (+ its bridging header) that drives the bound macOS framework |
| `spike.py` | the four stages, end to end |
| `out/` | build products (gitignored): the two xcframeworks, `watashell`, `server.log` |

## The pipeline, in one paragraph

`sgo build` compiles `watabind` — core + json + wataclient + the spike's own
capability impls — into one whole-program Go module under
`watabind/.sgo/watacore/`, and — via `emitpackage watacore` in `sgo.build` —
also into the importable sibling package dir `watabind/.sgo/watacore-pkg/`
(`func main` becomes the exported `RunCLI()` there). The hand-written
`watamobile` package requires the `-pkg` module through a `replace` and
re-exports two functions in types gobind can carry. `gomobile bind` turns
`watamobile` into an xcframework for `ios,iossimulator` and another for
`macos`. `swiftc` builds a CLI against the macOS one, and the smoke boots a
`wata-server`, seeds a family room, and checks the lines the Swift process
prints — which are rendered inside Sgola, from a live `StateSnapshot`.

The shim drives the client through `ClientHandle` (plan 0025): it starts it,
pumps the handle's dirty-flag channel into an `EventSink` on a goroutine, reads
the snapshot when a flag says something moved, and stops it. The host owns the
loop, which is what a UIKit app needs.

## Rerunning after a source change

`sgo build` rewrites both emission dirs on every build. Running
`just phone-spike` from the top always does the right thing.
