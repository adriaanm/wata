#!/usr/bin/env bash
# M8 chunk 3 — the wataclient library gate. Seven checks (6th = M8 chunk 5,
# 7th = M8 chunk 7 follow-up):
#   1. PORTABILITY TRIPWIRE: wataclient sources must have ZERO `go.*` references
#      (decision 1 — the reusable core is go-free; a Scala.js client shares it).
#   2. PLUGIN EMIT: wataclient compiles under the sgola plugin (emits valid Go to
#      .sgo/hello/wataclient) — the emitter accepts the whole portable port.
#   3. PORTABLE-CONFORMANCE SEED (decision 11c): the SAME Bytes/Ogg/CRC/oracle
#      sources, compiled WITHOUT the plugin to real JVM bytecode, run the byte
#      oracle on plain scalac and match the GOLDEN CRC-32 vectors / Ogg round-trip
#      / computed-Byte results. `report()` is identical code to the sgola path.
#   4. SYNC-ENGINE UNIT ORACLE: `wata-fb synctest` (the sgola-built driver over
#      the portable engine) prints the 15 ported sync_engine.zig tests; byte-
#      diffed against tools/wataclient-sync.expected.txt.
#   5. SYNC-ENGINE FIXTURE ORACLE: `wata-fb syncfix` feeds the checked-in /sync
#      fixtures (captured from the LIVE M7 wata-server by
#      tools/wataclient-fixtures.sh — provenance in README) and byte-diffs the
#      emitted events + state + snapshot against
#      tools/wataclient-fixtures.expected.txt.
#   6. SGOLA OGG/CRC BYTE ORACLE (M8 chunk 5 completion cycle, DATA-10):
#      `wata-fb oggtest` runs the SAME OggOracle.report() as the JVM seed (3)
#      on the Go-emitted side — the writer path (page CRC, round-trip exact,
#      multi-segment) is now ci-exercised where the by-value-builder bug lived.
#      Byte-diffed against tools/wataclient-ogg.expected.txt (== the JVM output;
#      re-pin designer-reviewed).
#   7. FOREIGN-CONTAINER FIXTURE (M8 chunk 7 follow-up): `wata-fb oggforeign`
#      runs the portable Ogg reader over the pinned TUI-shaped fixture
#      (go-pkgs/audio/testdata/tui-foreign.ogg — 60ms@16kHz packets, foreign
#      serial, EOS-carries-audio; the chunk-6 VOICEPLAY-FAIL container).
#      Byte-diffed against tools/wataclient-foreign.expected.txt. The DECODE
#      half of the same regression class is go-pkgs/audio's linux/arm Go test
#      over the SAME bytes (cross-compiled by ci step 13, run on-device).
#      Regenerate the fixture with spike/tools/tui-encode.mts (node+tsx,
#      generation-time only) + re-pin both — designer-reviewed.
#
#   tools/wataclient-tests.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                          # SGOLA_HOME (pinned clone by default), GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers (E2b)
SRC="$WATA/wataclient/src/main/scala"

echo "== wataclient-tests: 1/7 portability tripwire (zero go.* in $SRC) =="
# match a `go.` qualifier that is NOT part of `sgo.` (word-boundary before `go`).
HITS=$(grep -rnE '(^|[^A-Za-z0-9_])go\.' "$SRC" || true)
if [ -n "$HITS" ]; then
  echo "TRIPWIRE FAIL: wataclient references the go.* facade (must be portable):"
  echo "$HITS"
  exit 1
fi
echo "   ok — no go.* references"

echo "== wataclient-tests: 2/7 plugin emit + 3/7 JVM conformance seed =="
# BUILD chunk 5: the PLUGIN emit runs through the resident direct-dotc frontend
# (`sgo _compile wataclient` -> .sgo/hello/wataclient). The JVM CONFORMANCE SEED
# (wataclientJvm/run) stays on sbt: it compiles the SHARED sources to REAL JVM
# BYTECODE and RUNS the byte oracle on plain scalac — a dev-of-sgola conformance
# check, not a user build (decision 8 keeps sbt for exactly this kind of
# plugin-dev / conformance scaffolding). A cold `sbt` invocation is fine here.
"$SGO" _compile "$WATA/wataclient" 2>&1 | tail -5 || {
  echo "wataclient-tests FAIL: sgo _compile wataclient failed"; exit 1; }
# E2b: the JVM CONFORMANCE SEED runs on PLAIN dotc (the step-18 oracle pattern;
# sbt's wataclientJvm project retired with the move) — the SHARED sources (core
# bytes/prelude/list under $SGOLA_HOME + wataclient ogg/oracle here) compile to
# REAL JVM bytecode, no plugin, and Conformance runs the byte oracle on scalac.
#
# STDLIB-HOME migration (sgola GRADUATION-BRIEF ruling 16/17, 2026-07-20):
# core's List/Option/Either/ListOps now live at their REAL scala.* FQNs
# (list.scala declares `package scala.collection.immutable`, etc — see
# scala_prelude.scala/scala_aliases.scala/scala_compiletime.scala), so this
# leg must compile THOSE files too (the old bytes/prelude/list-only set left
# `Option`/`ListOps` unresolved: "Not found: ListOps" + friends). The
# designer ruling asked to re-point the classpath at minlib (the sgola-owned
# `scala.*` source tree, $SGOLA_HOME/minlib) instead of the real stdlib jars.
# Verified NOT viable as the sole classpath here: minlib deliberately never
# reaches GenBCode (`-Ystop-after:inlining`, TASTy-only — it doesn't carry
# backend runtime vocabulary), so backend-compiling it standalone crashes the
# compiler ("does not have a member method apply" on a mid-pipeline megaphase).
# A real, executable `scala.*` runtime (MatchError, Product, boxing, …) is
# unavoidable for something that actually RUNS on a JVM. The honest-post-
# migration fix that still runs: keep the real stdlib jars as the backing
# runtime for everything core/minlib don't own, but compile core's real-FQN
# sources FRESH into this unit (shadowing the jars' `List`/`Option`/`Either`/
# `ListOps`) and put OUR classes FIRST on the runtime classpath — otherwise
# the JVM verifier rejects our `Nil` linked against a same-named-but-
# differently-shaped classpath `List` (VerifyError, reproduced then fixed
# here). No `???` fired.
SCALA_V="$(sed -n 's/.*scala3Version *= *"\([0-9.]*\)".*/\1/p' "$SGOLA_HOME/build.sbt" | head -1)"
CACHE="$HOME/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2"
CP="$CACHE/org/scala-lang/scala3-compiler_3/$SCALA_V/scala3-compiler_3-$SCALA_V.jar"
CP="$CP:$CACHE/org/scala-lang/scala3-library_3/$SCALA_V/scala3-library_3-$SCALA_V.jar"
CP="$CP:$CACHE/org/scala-lang/scala-library/$SCALA_V/scala-library-$SCALA_V.jar"
CP="$CP:$CACHE/org/scala-lang/scala3-interfaces/$SCALA_V/scala3-interfaces-$SCALA_V.jar"
CP="$CP:$CACHE/org/scala-lang/tasty-core_3/$SCALA_V/tasty-core_3-$SCALA_V.jar"
CP="$CP:$CACHE/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar"
CP="$CP:$CACHE/org/scala-sbt/compiler-interface/1.11.0/compiler-interface-1.11.0.jar"
for j in ${CP//:/ }; do [ -f "$j" ] || { echo "wataclient-tests FAIL: not in Coursier cache: $j"; exit 1; }; done
JVMWORK="$(mktemp -d)"
java -cp "$CP" dotty.tools.dotc.Main -classpath "$CP" -d "$JVMWORK" \
  "$SGOLA_HOME/core/src/main/scala/bytes.scala" \
  "$SGOLA_HOME/core/src/main/scala/prelude.scala" \
  "$SGOLA_HOME/core/src/main/scala/list.scala" \
  "$SGOLA_HOME/core/src/main/scala/scala_prelude.scala" \
  "$SGOLA_HOME/core/src/main/scala/scala_aliases.scala" \
  "$SGOLA_HOME/core/src/main/scala/scala_compiletime.scala" \
  "$SRC/ogg.scala" "$SRC/oracle.scala" \
  "$WATA/wataclient-jvm/src/main/scala/Conformance.scala" 2>&1 | tail -5
[ -f "$JVMWORK/Conformance.class" ] || { rm -rf "$JVMWORK"; \
  echo "wataclient-tests FAIL: JVM conformance seed did not compile (plain dotc)"; exit 1; }
# $JVMWORK FIRST: our freshly-compiled scala.{List,Option,Either,ListOps,...}
# must shadow the real jars' same-named classes, or the JVM verifier rejects
# the mixed-shape linkage (see the note above).
java -cp "$JVMWORK:$CP" Conformance | tail -40
STATUS=${PIPESTATUS[0]}
rm -rf "$JVMWORK"
if [ "$STATUS" -ne 0 ]; then
  echo "wataclient-tests FAIL: Conformance (JVM conformance seed, plain dotc) failed"
  exit 1
fi

# assert the plugin emission landed and is syntactically valid Go, with no leaked
# Bytes/BytesBuilder struct (they must be backend-mapped to []byte).
WC_EMIT="$SGOLA_HOME/.sgo/hello/wataclient"
if [ ! -f "$WC_EMIT/ogg.go" ]; then
  echo "wataclient-tests FAIL: $WC_EMIT/ogg.go not emitted"
  exit 1
fi
for f in "$WC_EMIT"/*.go; do
  if ! gofmt -e "$f" >/dev/null 2>/tmp/wc_gofmt_err; then
    echo "wataclient-tests FAIL: emitted Go does not parse: $f"; cat /tmp/wc_gofmt_err; exit 1
  fi
done
if grep -rnE 'type (Bytes|BytesBuilder) (struct|=)' "$WC_EMIT"/*.go >/dev/null 2>&1; then
  echo "wataclient-tests FAIL: Bytes/BytesBuilder leaked as an emitted type (must map to []byte)"
  exit 1
fi
echo "   ok — wataclient emitted ($WC_EMIT), parses clean, no Bytes struct leak"

echo "== wataclient-tests: 4/7 sync-engine unit oracle (wata-fb synctest) =="
# The sgola-built driver (wata-fb links core+json+wataclient). Hash-gated build;
# usually a no-op after ci step 13 already built it.
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "wataclient-tests FAIL: wata-fb build"; exit 1; }
FB="$(emitdir wata-fb)/$(binname wata-fb)"
if ! diff <("$FB" synctest) tools/wataclient-sync.expected.txt; then
  echo "wataclient-tests FAIL: sync-engine unit oracle diverged from expected"
  exit 1
fi
echo "   ok — 15 ported sync_engine.zig tests byte-match the pinned expectations"

echo "== wataclient-tests: 5/7 sync-engine fixture oracle (wata-fb syncfix) =="
# FIX stays repo-RELATIVE: the fixture paths echo into the oracle transcript
# (the pinned expectation), so the spelling is part of the byte contract.
FIX="wataclient/test-fixtures"
for f in alice__01-initial alice__02-incr alice__03-incr bob__01-initial; do
  [ -f "$FIX/$f.json" ] || { echo "wataclient-tests FAIL: missing fixture $FIX/$f.json"; exit 1; }
done
if ! diff <("$FB" syncfix \
    "@alice:localhost=$FIX/alice__01-initial.json" \
    "@alice:localhost=$FIX/alice__02-incr.json" \
    "@alice:localhost=$FIX/alice__03-incr.json" \
    "@bob:localhost=$FIX/bob__01-initial.json") tools/wataclient-fixtures.expected.txt; then
  echo "wataclient-tests FAIL: fixture oracle diverged from expected"
  exit 1
fi
echo "   ok — live-server fixtures replay to the pinned events/state/snapshot"

echo "== wataclient-tests: 6/7 sgola Ogg/CRC byte oracle (wata-fb oggtest) =="
if ! diff <("$FB" oggtest) tools/wataclient-ogg.expected.txt; then
  echo "wataclient-tests FAIL: sgola Ogg oracle diverged from the pinned expected"
  echo "  (must be byte-identical to the JVM conformance output — the portable contract)"
  exit 1
fi
echo "   ok — Go-emitted OggOracle.report() byte-matches the pinned expected (== JVM)"

echo "== wataclient-tests: 7/7 foreign-container fixture (wata-fb oggforeign) =="
FOREIGN="$WATA/go-pkgs/audio/testdata/tui-foreign.ogg"
[ -f "$FOREIGN" ] || { echo "wataclient-tests FAIL: missing pinned fixture $FOREIGN"; exit 1; }
if ! diff <("$FB" oggforeign "$FOREIGN") tools/wataclient-foreign.expected.txt; then
  echo "wataclient-tests FAIL: foreign-container report diverged from the pinned expected"
  echo "  (the TUI-shaped container the chunk-6 VOICEPLAY-FAIL root cause hid in;"
  echo "   decode half = go-pkgs/audio foreign_decode_test.go, cross-compiled in step 13)"
  exit 1
fi
echo "   ok — TUI-shaped fixture parses to the pinned packets/granule/EOS shape"

echo "wataclient-tests: PASS (tripwire + emit + JVM conformance + unit + fixture + sgola-ogg + foreign-container oracles)"
