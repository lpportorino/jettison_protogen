#!/usr/bin/env bash
# Two measurements this fork's report rests on, both of which are easy to get
# wrong by argument alone.
#
# A. Does `construct-bindings` ALREADY red on a wrong-but-legal ui_luts.h edit?
#    The donation brief asserted such an edit "passes the byte comparison".
#    Measure it instead of believing it. (The target regenerates in place on a
#    mismatch, so it repairs the file itself; this script verifies that too.)
#
# B. What does adding `lvgl-codegen.wire-lut-test` do to the namespace closure
#    `renderer.mk`'s clj-schema-test comment enumerates, and does anything in it
#    now resolve a class out of `target/proto-classes` (the documented hazard)?
set -uo pipefail
ROOT="${1:-$PWD}"
[ -f "$ROOT/renderer.mk" ] || {
  echo "FATAL: $ROOT is not a protogen root" >&2
  exit 2
}
LUTS="$ROOT/renderer/generated/ui_luts.h"
BK="$(mktemp -d)"
trap 'cp "$BK/ui_luts.h" "$LUTS"; rm -rf "$BK"' EXIT
cp "$LUTS" "$BK/ui_luts.h"

echo "###### A. construct-bindings vs a wrong-but-legal ui_luts.h cell ######"
sed -i 's/^  LV_FLEX_FLOW_ROW_WRAP, /  LV_FLEX_FLOW_ROW_REVERSE, /' "$LUTS"
echo "landed: $(grep -c 'LV_FLEX_FLOW_ROW_REVERSE, /\* 3:' "$LUTS")"
(cd "$ROOT" && make -f renderer.mk construct-bindings)
echo "construct-bindings exit: $?"
echo "file repaired by the target itself: $(cmp -s "$BK/ui_luts.h" "$LUTS" && echo yes || echo no)"
cp "$BK/ui_luts.h" "$LUTS"

echo
echo "###### B. the clj-schema-test namespace closure ######"
cat >"$BK/closure.clj" <<'CLJ'
(require 'lvgl-codegen.lvgl-parity-test
         'lvgl-codegen.schema-test
         'lvgl-codegen.theme-tokens-test
         'lvgl-codegen.theme-style-groups-test
         'lvgl-codegen.wire-lut-test)
(let [libs (sort (loaded-libs))
      ours (filter #(re-find #"^(lvgl-codegen|renderer-gen|uigen|asgard)" (str %)) libs)
      pronto (filter #(re-find #"pronto" (str %)) libs)]
  (println "project namespaces loaded:" (count ours))
  (doseq [n ours] (println "  " n))
  (println "pronto namespaces loaded:" (count pronto) (vec pronto))
  (println "ByteString comes from:"
           (-> com.google.protobuf.ByteString .getProtectionDomain .getCodeSource .getLocation str)))
CLJ
(cd "$ROOT/tools/renderer-gen" &&
  clojure -Sdeps '{:aliases {:probe {:extra-paths ["test"]}}}' -M:probe "$BK/closure.clj")
