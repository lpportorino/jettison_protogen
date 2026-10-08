#!/usr/bin/env bash
# Run inside the pinned toolchain: bash tools/uber.sh 'bash tools/gate-graph/browser/adoption-parity.sh'
set -euo pipefail
parity_module="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$parity_module"
parity_deps='{:aliases {:adoption-parity {:extra-paths ["test"]} :adoption-parity-cljs {:main-opts ["-m" "cljs.main"]}}}'
mkdir -p target/adoption-parity
clojure -Sdeps "$parity_deps" -M:adoption-parity -m gate.api-parity > target/adoption-parity/clj.edn-lines
# The last temporary alias replaces :shadow's main-opts, retaining its pinned dependency.
clojure -Sdeps "$parity_deps" -M:shadow:adoption-parity:adoption-parity-cljs \
  -co '{:target :nodejs :output-to "target/adoption-parity/main.js" :output-dir "target/adoption-parity/cljs-out" :optimizations :none}' \
  -c gate.api-parity > target/adoption-parity/compile.log 2>&1
node target/adoption-parity/main.js > target/adoption-parity/cljs.edn-lines
cmp target/adoption-parity/clj.edn-lines target/adoption-parity/cljs.edn-lines
clojure -Sdeps "$parity_deps" -M:shadow:adoption-parity:adoption-parity-cljs \
  -co '{:target :nodejs :output-to "target/adoption-parity/advanced.js" :output-dir "target/adoption-parity/advanced-out" :optimizations :advanced :infer-externs true}' \
  -c gate.api-parity > target/adoption-parity/advanced-compile.log 2>&1
node target/adoption-parity/advanced.js > target/adoption-parity/advanced.edn-lines
cmp target/adoption-parity/clj.edn-lines target/adoption-parity/advanced.edn-lines
tail -n 1 target/adoption-parity/clj.edn-lines
sha256sum target/adoption-parity/clj.edn-lines target/adoption-parity/cljs.edn-lines target/adoption-parity/advanced.edn-lines
printf '%s\n' 'CLJ/CLJS API corpus: exact canonical output matches unoptimized and advanced Node builds.'
