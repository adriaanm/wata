# emitdir.sh — the test scripts' bridge to the sgo build layout.
#
# An app module's generated Go tree lands under <module>/.sgo/<emitname>, where
# emitname is declared in the module's own sgo.build — never re-encoded here, so
# renaming it is a one-line edit in one place. Source this file (with $WATA = the
# repo root, after tools/sgo-env.sh) and call:
#
#   emitdir <module-dir-name>   # echoes the app's absolute emission dir
#   binname <module-dir-name>   # echoes the app's built-binary basename
#
# LOUD if the module (or its marker) is missing.

: "${SGO:=$SGOLA_HOME/sgo/sgo}"

_marker() { # _marker <module> <key> -> value (empty if absent)
  local f="$WATA/$1/sgo.build"
  [ -f "$f" ] || { echo "emitdir: no sgo.build for module '$1' under $WATA" >&2; return 1; }
  awk -v k="$2" '$1==k {print $2; exit}' "$f"
}

emitdir() {
  local em
  em="$(_marker "$1" emitname)" || return 1
  [ -n "$em" ] || { echo "emitdir: module '$1' declares no emitname" >&2; return 1; }
  printf '%s\n' "$WATA/$1/.sgo/$em"
}

binname() {
  local bn
  bn="$(_marker "$1" binname)" || return 1
  printf '%s\n' "${bn:-$1}"
}
