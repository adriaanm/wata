# emitdir.sh — the wata scenario scripts' bridge to the sgo build layout
# (moved from the sgola tree in BUILD chunk E2b, adapted to the wata repo).
#
# A wata app module builds through sgo's table pipeline with the toolchain +
# SHARED-LIBRARY emission (core/json/wataclient) under $SGOLA_HOME, but its OWN
# generated tree lands under ITS OWN dir at <module>/.sgo/<emitname> (BUILD E3
# item (b): a moved-out app emits under its own tree, the fresh-module rule; the
# E2b $SGOLA_HOME/.sgo coupling was the recorded shortcut, now retired). The
# emitname is declared in the module's own sgo.build — never re-encoded here.
# Source this file (with $WATA = the wata repo root and $SGOLA_HOME set) and call:
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
