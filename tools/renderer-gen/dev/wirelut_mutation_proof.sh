#!/usr/bin/env bash
# Mutation proof for lvgl-codegen.wire-lut-test.
#
# Each case breaks ONE clause with a WRONG-BUT-LEGAL value -- never a
# syntactically invalid one, which would red the file without executing it --
# asserts the mutation LANDED, runs the suite, and then runs a named CONTROL in
# isolation so the red is attributable to the mutated clause and not to a
# neighbour that would have refused the same input anyway.
#
# ROOT is taken from $1 (default $PWD) and VERIFIED, because a script that
# computes its root from its own location silently retargets when copied.
#
# Usage (inside the pinned container, from the repo root):
#   bash .fork-scratch/wirelut/mutation-proof.sh
set -uo pipefail

ROOT="${1:-$PWD}"
[ -f "$ROOT/renderer.mk" ] && [ -d "$ROOT/tools/renderer-gen" ] || {
  echo "FATAL: $ROOT is not a protogen checkout root" >&2
  exit 2
}

LUTS="$ROOT/renderer/generated/ui_luts.h"
EMIT="$ROOT/tools/renderer-gen/src/lvgl_codegen/emit_proto.clj"
ENUMS="$ROOT/tools/renderer-gen/src/lvgl_codegen/generated/enums.clj"
MATRIX="$ROOT/renderer/coverage_matrix/run.sh"
BK="$ROOT/.fork-scratch/wirelut/.backup"
OUT="$ROOT/.fork-scratch/wirelut/mutation-runs"
FILES=("$LUTS" "$EMIT" "$ENUMS" "$MATRIX")

mkdir -p "$BK" "$OUT"
for f in "${FILES[@]}"; do cp "$f" "$BK/$(basename "$f")"; done

restore() {
  for f in "${FILES[@]}"; do cp "$BK/$(basename "$f")" "$f"; done
}
trap restore EXIT

run_suite() { (cd "$ROOT/tools/renderer-gen" && clojure -M:test "$@" 2>&1); }

# kaocha colours its output, so every reader here strips ANSI first -- a grep
# for a literal "FAIL in" silently matches nothing otherwise, which looks
# exactly like a mutation that did not fire.
plain() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }

totals() { plain "$1" | grep -E '^[0-9]+ tests, ' | tail -1; }

failures() { plain "$1" | sed -n '/^FAIL in\|^ERROR in/,/^Actual:$/p'; }

error_count() { plain "$1" | grep -c '^ERROR in'; }

# $1 label  $2 file  $3 sed-expr  $4 grep-pattern-that-must-now-exist
#   $5 test expected to go red  $6 control test that must stay green
mutate_case() {
  local label="$1" file="$2" expr="$3" landed="$4" red="$5" control="$6"
  echo
  echo "============================================================"
  echo "MUTATION: $label"
  echo "  file: ${file#"$ROOT"/}"
  restore
  sed -i "$expr" "$file"
  local n
  n=$(grep -c -- "$landed" "$file")
  echo "  landed: grep -c '$landed' -> $n"
  if [ "$n" -eq 0 ]; then
    echo "  FATAL: mutation did NOT land -- any result below is meaningless" >&2
    restore
    return 1
  fi
  echo "--- diff ---"
  diff -u "$BK/$(basename "$file")" "$file" | tail -n +3 | grep -E '^[-+][^-+]' || true
  run_suite >"$OUT/$label.full.txt"
  echo "--- suite totals (expect FAILures, and ZERO errors) ---"
  totals "$OUT/$label.full.txt"
  echo "  ERROR blocks: $(error_count "$OUT/$label.full.txt")"
  echo "--- the failure, and the clause it names (expected red: $red) ---"
  failures "$OUT/$label.full.txt" | head -40
  echo "--- CONTROL (run alone): $control ---"
  run_suite --focus "$control" >"$OUT/$label.control.txt"
  totals "$OUT/$label.control.txt"
  restore
}

echo "############ BASELINE (unmutated) ############"
run_suite >"$OUT/baseline.txt"
totals "$OUT/baseline.txt"

# M1 -- a wrong-but-LEGAL ui_luts.h cell: wire 3 (ROW_WRAP) points at
#       LV_FLEX_FLOW_ROW_REVERSE, another perfectly valid lv_flex_flow_t value.
mutate_case luts-wrong-but-legal "$LUTS" \
  's/^  LV_FLEX_FLOW_ROW_WRAP, /  LV_FLEX_FLOW_ROW_REVERSE, /' \
  'LV_FLEX_FLOW_ROW_REVERSE, /\* 3:' \
  lvgl-codegen.wire-lut-test/lut-families-resolve-through-the-table-to-the-header-value \
  lvgl-codegen.wire-lut-test/untabled-families-carry-the-header-value

# M2 -- a wrong-but-LEGAL hand-written pair: :flex-row-wrap emits the
#       ROW_REVERSE member. Legal proto, clean compile, wrong flow.
mutate_case emit-proto-wrong-but-legal "$EMIT" \
  's/:flex-row-wrap :FLEX_FLOW_ROW_WRAP$/:flex-row-wrap :FLEX_FLOW_ROW_REVERSE/' \
  ':flex-row-wrap :FLEX_FLOW_ROW_REVERSE' \
  lvgl-codegen.wire-lut-test/layout-map-resolves-to-the-header-value \
  lvgl-codegen.wire-lut-test/lut-families-resolve-through-the-table-to-the-header-value

# M3 -- a wrong-but-LEGAL direct-cast wire int: :center takes 8, which is
#       LV_ALIGN_RIGHT_MID -- a real lv_align_t value, so nothing type-checks it.
mutate_case enums-wrong-but-legal "$ENUMS" \
  's/^   :center 9$/   :center 8/' \
  '^   :center 8$' \
  lvgl-codegen.wire-lut-test/untabled-families-carry-the-header-value \
  lvgl-codegen.wire-lut-test/keyword-member-maps-name-the-header-constant

# M4 -- TOTALITY, keyword side: a :layout keyword the authoring schema does not
#       admit. The value loop iterates the schema vocabulary and never sees it,
#       so only the totality clause can fire.
mutate_case totality-extra-layout-keyword "$EMIT" \
  's/^  {:flex-row :FLEX_FLOW_ROW$/  {:flex-diagonal :FLEX_FLOW_ROW\n   :flex-row :FLEX_FLOW_ROW/' \
  ':flex-diagonal :FLEX_FLOW_ROW' \
  lvgl-codegen.wire-lut-test/layout-map-is-total-over-the-authoring-vocabulary \
  lvgl-codegen.wire-lut-test/layout-map-resolves-to-the-header-value

# M5 -- TOTALITY, header side: an authoring keyword with no LVGL enumerator
#       behind it. Again invisible to the per-member value loop, which iterates
#       the HEADER's members.
mutate_case totality-phantom-align-keyword "$ENUMS" \
  's/^   :center 9$/   :center 9\n   :phantom 9/' \
  '^   :phantom 9$' \
  lvgl-codegen.wire-lut-test/untabled-families-carry-the-header-value \
  lvgl-codegen.wire-lut-test/lut-families-resolve-through-the-table-to-the-header-value

# M6 -- the coverage matrix's REFERENCE side: :flex-col's LVGL value becomes 5,
#       which is LV_FLEX_FLOW_COLUMN_WRAP -- a real value of the same enum.
mutate_case matrix-reference-wrong-but-legal "$MATRIX" \
  's/^FLEX_VALS=(0 1 4 8 12 5 9 13)$/FLEX_VALS=(0 5 4 8 12 5 9 13)/' \
  '^FLEX_VALS=(0 5 4 8 12 5 9 13)$' \
  lvgl-codegen.wire-lut-test/coverage-matrix-reference-values-match-the-vendored-headers \
  lvgl-codegen.wire-lut-test/lut-families-resolve-through-the-table-to-the-header-value

# M7 -- a hand-carried grid constant off by one: LV_COORD_MAX-100 is LV_GRID_FR(0),
#       a perfectly real LVGL coordinate, so nothing downstream can type-check it.
mutate_case grid-constant-wrong-but-legal "$EMIT" \
  's/^(def \^:private lv-grid-content (- lv-coord-max 101))$/(def ^:private lv-grid-content (- lv-coord-max 100))/' \
  'lv-grid-content (- lv-coord-max 100)' \
  lvgl-codegen.wire-lut-test/grid-track-constants-match-the-vendored-macros \
  lvgl-codegen.wire-lut-test/layout-map-resolves-to-the-header-value

echo
echo "############ RESTORED -- final control run ############"
restore
run_suite >"$OUT/restored.txt"
totals "$OUT/restored.txt"
for f in "${FILES[@]}"; do
  cmp -s "$BK/$(basename "$f")" "$f" && echo "restored OK: ${f#"$ROOT"/}" ||
    echo "FATAL: NOT restored: ${f#"$ROOT"/}"
done
