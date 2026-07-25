# 0002 `[DECOUPLE]` — One interface to sgola: `tools/wata-smoke.sh`

**Status:** proposed

## Problem

This repo is organized partly around sgola's needs. Three test scripts,
two legs of a fourth, a 95-file committed artifact, a whole module, and
the module paths themselves exist to serve assertions about the
*compiler*, not about wata. They reach into `$SGOLA_HOME` internals —
`core/src/main/scala/*.scala` by filename, `.sgo/hello/crossings-*.txt`,
the Coursier cache layout, `build.sbt`'s Scala version — so a change in
sgola's internal layout breaks wata's tests without wata changing.

That coupling also runs the other way: sgola's gate used to depend on
wata. Both directions are now unwanted.

## Decision

**Wata tests wata.** The two repos share exactly one interface:

```
SGOLA_HOME=/path/to/sgola tools/wata-smoke.sh
```

That answers sgola's only question — *does the compiler still build and
pass tests on a non-trivial Sgola codebase?* Nothing else here is
sgola's, and nothing here asserts anything about the compiler.

Everything that only serves that second audience is **deleted**, not
handed over. Sgola re-derives whatever it wants against its own corpus;
this repo does not carry it in the meantime.

### Deleted

| what | why it is not wata's |
|------|----------------------|
| `tools/wata-sil.sh` | source-in-link byte-identity — an assertion about the emitter |
| `tools/crossing-residue.sh` | reads `$SGOLA_HOME/.sgo/hello/crossings-*.txt`; audits the compiler's unsafe-escape hatches |
| `wataclient-tests.sh` legs 2/7, 3/7 | "the plugin accepts these sources" and "plain scalac agrees" — both are compiler claims |
| `wataclient-jvm/` | the JVM twin; leg 3/7 was its only consumer |
| `wataclient/sgola/` + the `publish` marker + `wata-fb`'s `wataclient` require | the published payload exists for source-in-link consumption; see below |
| `tools/ci.sh` | a numbered gate whose numbering describes another project's CI |

**On the payload.** `wataclient/sgola/{meta,tasty,src}` is a 95-file
generated artifact that embeds source text and TASTy, so it goes stale on
any comment edit under `wataclient/src/`, silently. Nothing in this repo
consumes it: `sgo` resolves `wataclient` as a source sibling
(`findInLinkDir` skips payload-only dirs), and the emitted
`wata-fb/.sgo/fb/go.mod` never references it. Verified by deleting all
three and cold-building: both apps build, and `smoke`, `fb-smoke`, and
`golden` pass. Its only consumer was `wata-sil.sh`.

If `wataclient` is ever published to a fetchable remote, `publish` goes
back in `sgo.build` and `sgo emit` regenerates the tree — this deletes an
artifact, not a capability.

**On the JVM twin.** Leg 3/7 compiles wata's `ogg.scala`/`oracle.scala`
to real JVM bytecode under plain dotc and runs the byte oracle, which
sounds like a wata check. It isn't: the same oracle already runs natively
in legs 6/7, so the leg's unique claim is *scalac and sgo agree* — about
the compiler. `wataclient-jvm/src/main/scala/Conformance.scala` is worth
offering to sgola as a seed, but it is not kept here pending that.

### Renamed

Module paths carry sgola's namespace. They become wata's:

| from | to |
|------|----|
| `sgola.spike/wata-server` | `github.com/adriaanm/wata/wata-server` |
| `sgola.spike/wata-fb` | `github.com/adriaanm/wata/wata-fb` |
| `sgola.example/audio` | `github.com/adriaanm/wata/go-pkgs/audio` |

`wata-fb`'s `emitname fb` and the `crosskey` markers exist for the
shared-tree layout and the crossing checker respectively; `emitname`
becomes `wata-fb` and `crosskey` goes with `crossing-residue.sh`.

### The entry point

`just` is the naming layer, `tools/` holds the logic. `just ci` becomes
recipe dependencies over the remaining scripts:

```
ci: smoke persist fb-smoke client-tests integ golden amd64-smoke
```

No `tools/ci.sh` and no `tools/test.py`. Each script prints its own
`PASS`; `just` stops at the first failure. The cost is losing `ci.sh`'s
end-of-run summary, which is worth the layer it removes.

## Changes

- delete the five scripts/dirs and the payload listed above
- `wataclient-tests.sh`: drop legs 2/7 and 3/7, renumber to 5, drop the
  `$SGOLA_HOME`-internals reach (Coursier paths, `build.sbt` parsing)
- delete `tools/ci.sh`; `ci` recipe becomes dependencies
- rewrite `go.mod` module lines and the `sgo.build` markers for the
  renames; regenerate the emitted trees
- rewrite every remaining script header to say what it tests
- `CLAUDE.md`: state the one-interface contract; drop the dep-resolution
  section's source-in-link paragraph, which no longer describes anything
  here
- `docs/design/wataclient.md`: drop the JVM-twin role; record why the
  payload is gone and what re-enabling it takes

## Verification

- `just ci` green before and after, same assertions firing per script.
- The contract, from a clean clone with no `.toolchain`:
  `SGOLA_HOME=<a separate sgola checkout> just smoke` green.
- `git grep -iE 'sgola\.(spike|example)|SGOLA_HOME' -- . ':!tools/sgo-env.sh' ':!tools/toolchain*'`
  returns nothing outside the toolchain-resolution layer.
- Both apps build with `GOPROXY`, `GOMODCACHE`, `GOFLAGS`, `GOPRIVATE`
  unset — no proxy, no populated module cache.

## Out of scope

- Any change to what the remaining tests assert. This removes audiences,
  it does not change coverage of wata itself.
- Wata's own replacement for the jest conformance oracle
  (`tools/wata-tests.sh` against `$WATA_TS_REPO`). Still the honest
  oracle for replacing the TypeScript wata; it gets its own plan.
- The in-repo tinyalsa fork, which is a deliberate carry
  (`docs/design/wata-fb.md`).

## Risks

- **Coverage genuinely leaves.** Payload idempotence and emitted-Go
  byte-identity stop being checked anywhere in this repo. That is the
  point — they are claims about the compiler — but if sgola does not pick
  them up, nobody is making them. Worth telling sgola explicitly rather
  than assuming.
- **The renames touch generated trees.** `go.mod`, `sgo.build`, and the
  emitted `.sgo/` output move together; a partial application leaves a
  build that resolves by accident. Do it as one commit with a full cold
  rebuild.
