# Sourced by wata's scripts to establish the sgola build environment.
#
#   . "$WATA/tools/sgo-env.sh"
#
# Sets SGOLA_HOME (defaulting to the pinned clone), GOTOOLCHAIN, GOWORK, and
# SGO, building the `sgo` driver if it is not there yet. After sourcing this, a
# script can just use "$SGO".
#
# A preset $SGOLA_HOME wins, so sgola can drive wata as a proving consumer
# against an in-development compiler with no other setup:
#
#   SGOLA_HOME=/path/to/sgola tools/wata-smoke.sh

_sgo_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The toolchain: an explicit SGOLA_HOME, else the pinned clone.
if [ -z "${SGOLA_HOME:-}" ]; then
  SGOLA_HOME="$(dirname "$_sgo_env_dir")/.toolchain/sgola"
  if [ ! -d "$SGOLA_HOME" ]; then
    echo "no sgola toolchain at $SGOLA_HOME — run tools/toolchain.py sync" >&2
    echo "(or set SGOLA_HOME to an sgola checkout)" >&2
    exit 1
  fi
fi
export SGOLA_HOME

# Emitted Go is a pinned-toolchain product: gofmt output is not stable across
# Go releases, so a different `go` silently reformats it and every
# byte-comparison drifts. Read the pin out of the toolchain itself.
_sgo_go_pin="$(sed -n 's/^SGOLA_GO_PIN="\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p' "$SGOLA_HOME/tools/ci.sh" 2>/dev/null | head -1)"
[ -n "$_sgo_go_pin" ] && export GOTOOLCHAIN="$_sgo_go_pin"

# sgola's own go.work must not capture wata's modules.
export GOWORK=off

# The driver. json and wataclient resolve as ordinary in-tree author modules
# (sgo searches the declaring module's parent dir, then the toolchain home), so
# nothing else needs setting up — no module proxy, no populated module cache.
SGO="${SGO:-$SGOLA_HOME/sgo/sgo}"
if [ ! -x "$SGO" ]; then
  echo "sgo-env: building the sgo driver…" >&2
  ( cd "$SGOLA_HOME/sgo" && go build -o sgo . ) \
    || { echo "sgo-env: failed to build the sgo driver at $SGOLA_HOME/sgo" >&2; exit 1; }
fi
export SGO
unset _sgo_env_dir _sgo_go_pin
