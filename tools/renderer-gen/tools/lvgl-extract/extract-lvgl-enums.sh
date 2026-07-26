#!/usr/bin/env bash
#
# POC #1 — Authoritative LVGL enum extraction via clang.
#
# Parses the LVGL headers, groups every enum constant under its typedef name,
# and resolves the AUTHORITATIVE integer value of each by compiling a probe
# (the C compiler is the only correct oracle: it handles sequential
# enumerators, bitmask expressions like `A | B`, and macro-derived values).
#
# Emits an EDN table to stdout:
#   { "lv_align_t" [ {:name "LV_ALIGN_DEFAULT" :value 0} ... ]
#     "lv_flex_align_t" [ ... ] ... }
#
# Dependencies: clang + jq only. No libclang, no special container — runs on a
# bare host or inside the dev container identically. Finishes in well under a
# second on LVGL 9.x.
#
# This is the foundation layer for:
#   (a) the direct-cast PARITY GATE  — assert proto enum int == LVGL enum int
#       for the enums renderer.c casts straight to LVGL types (the set is
#       lvgl-codegen.construct.lift/direct-cast-typedefs — its one home; a
#       count copied to here would rot the next time that set grows);
#   (b) the COVERAGE MATRIX           — the full set of values visual tests cover;
#   (c) CODEGEN                       — proto / clojure / malli emission.
#
# Usage:
#   tools/lvgl-extract/extract-lvgl-enums.sh > lvgl-enums.edn
# Overridable via env: REPO, LVGL_DIR, CONF_DIR, OUT_DIR, CLANG.
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
LVGL_DIR="${LVGL_DIR:-$REPO/lvgl}"
CONF_DIR="${CONF_DIR:-$REPO}" # directory containing lv_conf.h
OUT_DIR="${OUT_DIR:-$(mktemp -d)}"
CLANG="${CLANG:-clang}" # used for the AST dump (clang-only feature)
CC="${CC:-cc}"          # used for the native probe; in the dev container set
# CC=gcc because `clang` there is WASI (wasm32) clang

inc=(-I "$LVGL_DIR" -I "$CONF_DIR" -DLV_CONF_INCLUDE_SIMPLE)

mkdir -p "$OUT_DIR"
printf '#include "lvgl.h"\n' >"$OUT_DIR/lvgl_include.c"

# 1. Dump the translation unit's Clang AST as JSON.
"$CLANG" -fsyntax-only -Xclang -ast-dump=json "${inc[@]}" \
  "$OUT_DIR/lvgl_include.c" >"$OUT_DIR/ast.json"

# 2. Group enum constants by typedef name. LVGL enums are anonymous
#    `typedef enum { ... } lv_x_t;`, so the name comes from the TypedefDecl that
#    immediately FOLLOWS the EnumDecl. Emits TSV: "<typedef>\t<CONSTANT>",
#    members contiguous and in declaration order.
jq -r '
  [.inner[] | select(.kind=="EnumDecl" or .kind=="TypedefDecl")] as $n
  | range(0; ($n|length)) as $i
  | $n[$i]
  | select(.kind=="EnumDecl" and ((.inner//[]) | any(.kind=="EnumConstantDecl")))
  | ([.inner[] | select(.kind=="EnumConstantDecl") | .name][0]) as $first
  | (($n[$i+1]
      | if .kind=="TypedefDecl" and ((.type.qualType // "") | startswith("enum "))
        then .name else "anon_" + $first end)) as $td
  | .inner[] | select(.kind=="EnumConstantDecl") | "\($td)\t\(.name)"
' "$OUT_DIR/ast.json" >"$OUT_DIR/groups.tsv"

# 3. Resolve authoritative integer values via a compiled probe.
{
  echo '#include "lvgl.h"'
  echo '#include <stdio.h>'
  echo 'int main(void){'
  cut -f2 "$OUT_DIR/groups.tsv" | sort -u | while read -r nm; do
    printf '  printf("%s %%lld\\n",(long long)%s);\n' "$nm" "$nm"
  done
  echo '  return 0; }'
} >"$OUT_DIR/probe.c"
"$CC" "${inc[@]}" "$OUT_DIR/probe.c" -o "$OUT_DIR/probe"
"$OUT_DIR/probe" >"$OUT_DIR/values.txt"

# 4. Join groups + values into an EDN map (preserving declaration order).
awk '
  NR==FNR { val[$1]=$2; next }
  FNR==1  { print "{" }
  $1 != cur { if (cur != "") print "  ]"; printf "  \"%s\"\n  [\n", $1; cur=$1 }
  { printf "   {:name \"%s\" :value %s}\n", $2, val[$2] }
  END { if (cur != "") print "  ]"; print "}" }
' "$OUT_DIR/values.txt" "$OUT_DIR/groups.tsv"

echo "extract-lvgl-enums: $(wc -l <"$OUT_DIR/values.txt") constants across \
$(cut -f1 "$OUT_DIR/groups.tsv" | sort -u | wc -l) enums" >&2
