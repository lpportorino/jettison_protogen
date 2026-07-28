#!/usr/bin/env bash
# Cross-check the test's pure-Clojure header oracle against the PRODUCTION
# extractor (clang AST dump + a natively-compiled probe) for exactly the
# typedefs the factory requires. Two independent readings of the same headers;
# any disagreement means one of them is wrong.
#
# ROOT comes from $1 (default $PWD) and is VERIFIED -- a probe that derives its
# root from its own location runs against the wrong tree the moment it is
# copied, and then fails for a reason unrelated to what it is testing.
set -euo pipefail
ROOT="${1:-$PWD}"
[ -f "$ROOT/renderer.mk" ] || {
  echo "FATAL: $ROOT is not a protogen root" >&2
  exit 2
}
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

LVGL_DIR="$ROOT/renderer/lvgl" CONF_DIR="$ROOT/renderer" \
  CLANG="${CLANG:-${WASI_SDK:-/opt/wasi-sdk}/bin/clang}" CC="${CC:-gcc}" \
  OUT_DIR="$tmp/extract" \
  "$ROOT/tools/renderer-gen/tools/lvgl-extract/extract-lvgl-enums.sh" >"$tmp/lvgl-enums.edn"

cat >"$tmp/probe.clj" <<CLJ
(require '[clojure.edn :as edn] '[lvgl-codegen.construct.lift :as lift])
(require 'lvgl-codegen.wire-lut-test)
(let [authoritative (edn/read-string (slurp "$tmp/lvgl-enums.edn"))
      oracle @@#'lvgl-codegen.wire-lut-test/header-facts
      required (sort lift/required-typedefs)
      diffs (for [t required
                  :let [a (get authoritative t)
                        b (get oracle t)]
                  :when (not= a b)]
              [t {:extractor a :oracle b}])]
  (println "typedefs compared:" (count required))
  (println "constants compared:" (reduce + (map #(count (get oracle %)) required)))
  (println "typedefs in the extractor's own output:" (count authoritative))
  (println "disagreements:" (count diffs))
  (doseq [d diffs] (prn d))
  (System/exit (if (seq diffs) 1 0)))
CLJ

cd "$ROOT/tools/renderer-gen"
clojure -Sdeps '{:aliases {:probe {:extra-paths ["test"]}}}' -M:probe "$tmp/probe.clj"
