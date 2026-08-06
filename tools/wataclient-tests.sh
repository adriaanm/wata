#!/usr/bin/env bash
# The wataclient library gate — five checks over the portable client core.
#
#   1. PORTABILITY TRIPWIRE: wataclient sources must have ZERO `go.*`
#      references. The core is portable by construction: it reaches hardware,
#      the network, and the clock only through the HttpDo/Clock
#      capability traits its consumer implements.
#   2. SYNC-ENGINE UNIT ORACLE: `wata-fb synctest` runs the 18 sync-engine
#      scenarios; byte-diffed against tools/wataclient-sync.expected.txt.
#   3. SYNC-ENGINE FIXTURE ORACLE: `wata-fb syncfix` replays the checked-in
#      /sync fixtures (captured from a live wata-server by
#      tools/wataclient-fixtures.sh) and byte-diffs the emitted events, state,
#      and snapshot against tools/wataclient-fixtures.expected.txt.
#   4. OGG/CRC BYTE ORACLE: `wata-fb oggtest` runs OggOracle.report() — CRC-32
#      golden vectors, an Ogg write/read round trip over a multi-segment frame,
#      Byte narrowing/widening, BytesBuilder.freeze semantics. Byte-diffed
#      against tools/wataclient-ogg.expected.txt.
#   5. FOREIGN-CONTAINER FIXTURE: `wata-fb oggforeign` runs the portable Ogg
#      reader over a pinned container written by someone else
#      (go-pkgs/audio/testdata/tui-foreign.ogg — 60ms@16kHz packets, foreign
#      serial, audio carried in the EOS page), so the reader is held to more
#      than the writer's own conventions. Byte-diffed against
#      tools/wataclient-foreign.expected.txt. The decode half of the same
#      class is go-pkgs/audio's linux/arm Go test over the same bytes.
#      Regenerate the fixture with tools/tui-encode.mts and re-pin both.
#
#   tools/wataclient-tests.sh
set -uo pipefail
cd "$(dirname "$0")/.."
WATA="$(pwd)"
. "$WATA/tools/sgo-env.sh"                        # SGOLA_HOME, GOTOOLCHAIN, SGO
. "$WATA/tools/emitdir.sh"                        # emit paths from the module markers
SRC="$WATA/wataclient/src/main/scala"

echo "== wataclient-tests: 1/5 portability tripwire (zero go.* in $SRC) =="
# match a `go.` qualifier that is NOT part of `sgo.` (word-boundary before `go`).
HITS=$(grep -rnE '(^|[^A-Za-z0-9_])go\.' "$SRC" || true)
if [ -n "$HITS" ]; then
  echo "TRIPWIRE FAIL: wataclient references the go.* facade (must be portable):"
  echo "$HITS"
  exit 1
fi
echo "   ok — no go.* references"

echo "== wataclient-tests: 2/5 sync-engine unit oracle (wata-fb synctest) =="
# The driver is wata-fb itself (it links core+json+wataclient). Hash-gated
# build, so usually a no-op.
( cd "$WATA/wata-fb" && "$SGO" build ) >/dev/null || { echo "wataclient-tests FAIL: wata-fb build"; exit 1; }
FB="$(emitdir wata-fb)/$(binname wata-fb)"
if ! diff <("$FB" synctest) tools/wataclient-sync.expected.txt; then
  echo "wataclient-tests FAIL: sync-engine unit oracle diverged from expected"
  exit 1
fi
echo "   ok — 19 sync-engine scenarios byte-match the pinned expectations"

echo "== wataclient-tests: 3/5 sync-engine fixture oracle (wata-fb syncfix) =="
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

echo "== wataclient-tests: 4/5 Ogg/CRC byte oracle (wata-fb oggtest) =="
if ! diff <("$FB" oggtest) tools/wataclient-ogg.expected.txt; then
  echo "wataclient-tests FAIL: Ogg oracle diverged from the pinned expected"
  exit 1
fi
echo "   ok — OggOracle.report() byte-matches the pinned expected"

echo "== wataclient-tests: 5/5 foreign-container fixture (wata-fb oggforeign) =="
FOREIGN="$WATA/go-pkgs/audio/testdata/tui-foreign.ogg"
[ -f "$FOREIGN" ] || { echo "wataclient-tests FAIL: missing pinned fixture $FOREIGN"; exit 1; }
if ! diff <("$FB" oggforeign "$FOREIGN") tools/wataclient-foreign.expected.txt; then
  echo "wataclient-tests FAIL: foreign-container report diverged from the pinned expected"
  exit 1
fi
echo "   ok — foreign fixture parses to the pinned packets/granule/EOS shape"

echo "wataclient-tests: PASS (tripwire + sync unit/fixture + ogg + foreign-container oracles)"
