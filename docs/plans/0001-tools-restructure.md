# 0001 `[TOOLS-RESTRUCTURE]` — Restructure `tools/` around what wata tests, not where it came from

**Status:** abandoned — superseded by
[0002](0002-decouple-from-sgola.md).

This plan assumed wata would keep hosting the compiler-regression
assertions and hand the scripts to sgola as a transfer. The actual
decision is narrower: the two repos share exactly one interface,
`tools/wata-smoke.sh`, and everything sgola-facing is deleted here rather
than handed over. Its `tools/test.py` proposal is also dropped — with the
sgola-only legs gone there is nothing left for a runner layer to
organize, so the justfile names the scripts directly. Kept for the
inventory of what each script does, which 0002 builds on.

## Problem

`tools/` is organized by its origin rather than its purpose. `ci.sh` is a
nine-step gate whose steps are numbered and described by their lineage in
another project's CI, and three of those steps exist only to regress the
*compiler*, not wata. Every script header opens with a milestone tag. The
result is that nobody can tell, from the directory, how to run wata's
tests or which failures are wata's problem.

Two audiences are tangled together:

- **Wata's own tests** — does the homeserver serve Matrix correctly, does
  state survive a restart, does the device client draw the right frame,
  does the client core round-trip a sync.
- **Compiler regressions** — is the emitted Go byte-identical across a
  source-in-link build, is the published payload idempotent, did a new
  unsafe-escape hatch appear, does the plugin still accept these sources.

The second set belongs to sgola, which consumes this repo as a proving
vertical and will re-incorporate those assertions on its side.

## Decision

Split `tools/` by audience. Wata keeps a single, purpose-named test entry
point; the compiler-regression scripts are handed to sgola and removed
here.

### Wata's suite — `tools/test.py <group>`, default all

| group    | covers                                                              | from                                                        |
|----------|---------------------------------------------------------------------|-------------------------------------------------------------|
| `server` | selfcheck, live Matrix session, long-poll concurrency, `-race`; kill-9 restart replay | `wata-smoke.sh`, `wata-persist-smoke.sh`                     |
| `client` | portability tripwire, sync/fixture/ogg/foreign byte oracles, 10 live client↔server scenarios | behavioral legs of `wataclient-tests.sh`, `wataclient-integ.sh` |
| `fb`     | native build + run, armv7 cross-cgo build, golden-frame PNG          | `wata-fb-smoke.sh`, `fb-golden.sh`                           |
| `deploy` | linux/amd64 server smoke (the always-on box is a real target)        | `linux-amd64-smoke.sh`                                       |

`tools/ci.sh` becomes a thin alias for `tools/test.py` so existing muscle
memory and any caller keep working.

### Kept as-is, purpose unchanged

`fb-deploy.sh` (device deploy), `wataclient-fixtures.sh` (fixture
regeneration), `wata-bench.sh` + `wata-bench/` + `wata-throughput/` +
`wata-conc/` (benchmarks), `emitdir.sh` (shared helper), `tui-encode.mts`,
and the `*.expected.txt` oracles.

`wata-tests.sh` is kept and promoted: it runs the **original TypeScript
wata's jest integration suite** against the Sgola-built server. That is
the conformance oracle for replacing `~/g/bq268/wata`, so it matters more
now, not less. It stays out of `test.py` because it needs an external
repo (`$WATA_TS_REPO`) with `node_modules` installed.

### Handed to sgola, removed here

| script                | why it is sgola's                                                     |
|-----------------------|-----------------------------------------------------------------------|
| `wata-sil.sh`         | emitted-Go byte-identity + publish idempotence — a compiler property    |
| `crossing-residue.sh` | audits unsafe-escape hatches against a compiler-side expectation        |
| plugin-emit and JVM-conformance legs of `wataclient-tests.sh` | "does the compiler accept these sources" and "does the JVM twin agree" |

These are **not deleted until sgola has them**. Sequencing below.

## Changes

- add `tools/test.py` (the four groups; Python per the repo rule)
- rewrite `tools/ci.sh` as an alias
- `tools/wataclient-tests.sh` → keep only the behavioral checks
- rewrite every remaining script header to say what it tests
- delete `tools/wata-sil.sh`, `tools/crossing-residue.sh` **only after**
  sgola confirms it has them
- `wataclient-jvm/` stays (sgola's conformance seed reads it) but its role
  is documented in `docs/design/wataclient.md`

## Verification

`tools/test.py` green on all four groups before and after, with the same
assertions firing. Each group is compared against the current script's
output, not just its exit code. `tools/wata-tests.sh` green against
`$WATA_TS_REPO`.

Baseline recorded 2026-07-25 with the pinned toolchain: `wata-smoke.sh`
PASS (selfcheck + live session + 8/8 long-poll wake + `-race` clean),
`wata-server` and `wata-fb` both build clean.

## Out of scope

- Changing any test's assertions or coverage. This is a reorganization;
  behavior changes get their own plan.
- `[MODULE-PATHS]` The module-path cleanup (`sgola.spike/wata-server` →
  `github.com/adriaanm/wata/wata-server`, `sgola.example/audio`) — real,
  but it moves go.mod and the committed payload together. Its own plan.
- Wata's own replacement for the jest oracle. Wanted eventually; the
  external suite is the honest oracle until then.

## Open question

Does sgola want these scripts as-is, or will it re-derive the assertions
against its own corpus? That answer sets whether the removal here is a
hand-off or a plain delete.
