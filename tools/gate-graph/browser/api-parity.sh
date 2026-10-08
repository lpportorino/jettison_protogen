#!/usr/bin/env bash
# Run inside the pinned toolchain: bash tools/uber.sh 'bash tools/gate-graph/browser/api-parity.sh'
set -euo pipefail
parity_module="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$parity_module"
parity_deps='{:aliases {:api-parity {:extra-paths ["test"]} :api-parity-cljs {:main-opts ["-m" "cljs.main"]}}}'
mkdir -p target/api-parity
clojure -Sdeps "$parity_deps" -M:api-parity -m gate.view.parity > target/api-parity/clj.edn-lines
# The last temporary alias replaces :shadow's main-opts, retaining its pinned dependency.
clojure -Sdeps "$parity_deps" -M:shadow:api-parity:api-parity-cljs \
  -co '{:target :nodejs :output-to "target/api-parity/main.js" :output-dir "target/api-parity/cljs-out" :optimizations :none}' \
  -c gate.view.parity > target/api-parity/compile.log 2>&1
node target/api-parity/main.js > target/api-parity/cljs.edn-lines
cmp target/api-parity/clj.edn-lines target/api-parity/cljs.edn-lines
clojure -Sdeps "$parity_deps" -M:shadow:api-parity:api-parity-cljs \
  -co '{:target :nodejs :output-to "target/api-parity/advanced.js" :output-dir "target/api-parity/advanced-out" :optimizations :advanced :infer-externs true}' \
  -c gate.view.parity > target/api-parity/advanced-compile.log 2>&1
node target/api-parity/advanced.js > target/api-parity/advanced.edn-lines
cmp target/api-parity/clj.edn-lines target/api-parity/advanced.edn-lines
tail -n 1 target/api-parity/clj.edn-lines
sha256sum target/api-parity/clj.edn-lines target/api-parity/cljs.edn-lines target/api-parity/advanced.edn-lines
printf '%s\n' 'CLJ/CLJS API corpus: exact canonical output matches unoptimized and advanced Node builds.'
