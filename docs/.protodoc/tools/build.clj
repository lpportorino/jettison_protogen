(ns build
  "Uberjar build. NOT the path the docs legs take.

   `uber` below AOT-compiles protodoc.core on its way to a jar, so it reads
   like the home of the AOT that `make docs-generate` and friends rely on. It
   is not: reaching anything in this file needs io.github.clojure/tools.build,
   which the pinned toolchain image does not prefetch — `clojure -T:build`
   under `--network none` in that image fails to resolve its classpath, so a
   doc leg routed through here would download at run time. The docs legs use
   the `:aot`/`:aot-compile` aliases in deps.edn instead (clojure core's own
   `compile`, no dependency beyond what `:run` already resolves), driven by
   aot.sh. Change the compiled entry point and both places need it."
  (:require [clojure.tools.build.api :as b]))

(def lib 'protodoc/protodoc)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/protodoc.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[protodoc.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'protodoc.core}))
