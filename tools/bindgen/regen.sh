#!/usr/bin/env bash
# Regenerate the Apple bindings from the SDK headers, then check them.
#
# Needs Xcode (the version bindgen.json pins); everything else lives in
# bindgen.py, which dumps the AST, emits the Go, and runs gofmt + go vet +
# the ios/arm64 build over what it wrote.
#
#   tools/bindgen/regen.sh                    # every target
#   tools/bindgen/regen.sh --target appleptt  # one
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python3 tools/bindgen/bindgen.py "$@"
