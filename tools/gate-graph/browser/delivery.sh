#!/usr/bin/env bash
# Verify source/resource JAR delivery without source directories on the classpath.
set -euo pipefail
delivery_module="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$delivery_module"
mkdir -p target
jar --create --file target/viewer-delivery.jar -C src . -C resources .
delivery_deps='{:paths ["target/viewer-delivery.jar" "test"]}'
clojure -Sdeps "$delivery_deps" -M -e '(require (quote clojure.java.io) (quote gate.viewer-asset)) (assert (= "jar" (.getProtocol (clojure.java.io/resource "gate/viewer.cljs")))) (println (select-keys (gate.viewer-asset/load!) [:digest]))'
clojure -Sdeps "$delivery_deps" -M -m gate.view.fixtures target/packaged-fixtures
printf '%s\n' 'Packaged JAR source verification and HTML generation passed.'
