#!/usr/bin/env bash
#
# Setter extraction (the factory's pass-5 input) — parses the LVGL AST for
# two-argument setters:
#   lv_<owner>_set_<prop>(<owner ptr>, <value>)     e.g. lv_label_set_long_mode
#   lv_style_set_<prop>(lv_style_t *, <value>)
# and emits an EDN vector to stdout:
#   [ {:c-name "lv_label_set_long_mode" :owner "label" :prop "long_mode"
#      :value-c-type "lv_label_long_mode_t" :style-prop? false} ... ]
#
# Multi-value setters (3+ args, e.g. lv_obj_set_grid_align's column+row pair)
# are deliberately NOT emitted yet — each extra value param needs its own
# prop mapping; a later refinement when a consumer needs them.
#
# Reuses $OUT_DIR/ast.json when the enum extractor already dumped it (share
# OUT_DIR to pay the clang parse once).
#
# Usage:
#   pocs/01-clang-enum-extraction/extract-lvgl-setters.sh > lvgl-setters.edn
# Overridable via env: REPO, LVGL_DIR, CONF_DIR, OUT_DIR, CLANG.
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
LVGL_DIR="${LVGL_DIR:-$REPO/lvgl}"
CONF_DIR="${CONF_DIR:-$REPO}"
OUT_DIR="${OUT_DIR:-$(mktemp -d)}"
CLANG="${CLANG:-clang}"

inc=(-I "$LVGL_DIR" -I "$CONF_DIR" -DLV_CONF_INCLUDE_SIMPLE)

mkdir -p "$OUT_DIR"
if [ ! -f "$OUT_DIR/ast.json" ]; then
  printf '#include "lvgl.h"\n' >"$OUT_DIR/lvgl_include.c"
  "$CLANG" -fsyntax-only -Xclang -ast-dump=json "${inc[@]}" \
    "$OUT_DIR/lvgl_include.c" >"$OUT_DIR/ast.json"
fi

{
  echo '['
  jq -r '
    .inner[]
    | select(.kind=="FunctionDecl")
    | select(.name // "" | test("^lv_[a-z0-9_]+_set_[a-z0-9_]+$"))
    | . as $f
    | [.inner[]? | select(.kind=="ParmVarDecl") | .type.qualType] as $params
    | select(($params|length)==2)
    | ($f.name | capture("^lv_(?<owner>.+?)_set_(?<prop>.+)$")) as $m
    | " {:c-name \"\($f.name)\" :owner \"\($m.owner)\" :prop \"\($m.prop)\""
      + " :value-c-type \"\($params[1])\""
      + " :style-prop? \(if $m.owner == "style" then "true" else "false" end)}"
  ' "$OUT_DIR/ast.json" | sort -u
  echo ']'
}

echo "extract-lvgl-setters: done" >&2
