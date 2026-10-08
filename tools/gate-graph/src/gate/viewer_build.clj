(ns gate.viewer-build
  "Rebuild or check the packaged public viewer from the module working directory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.viewer-asset :as asset]
            [gate.viewer-contract :as vc]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path LinkOption FileVisitOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(set! *warn-on-reflection* true)

(def Snapshot
  [:map {:closed true} [:inputs [:vector {:min 1 :max 256} vc/Input]]
   [:dependencies c/Digest] [:configuration c/Digest]])
(def Failure
  [:map {:closed true}
   [:code [:enum :viewer-build-input :viewer-build-failed :viewer-build-changed :viewer-build-stale]]])

(defn- refuse!
  "Keep build failures machine-readable without embedding compiler logs or paths."
  [code]
  (throw (ex-info "Viewer build refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2))] :nil])

(defn- read-bytes!
  "Read at most limit+1 bytes; the sentinel detects excess before unbounded allocation."
  [path limit]
  (try
    (with-open [stream (io/input-stream path)]
      (let [content (.readNBytes stream (int (inc limit)))]
        (when (> (alength content) limit) (refuse! :viewer-build-input))
        content))
    (catch java.io.IOException _ (refuse! :viewer-build-input))
    (catch SecurityException _ (refuse! :viewer-build-input))))
(m/=> read-bytes!
      [:=> [:cat [:enum "deps.edn" "shadow-cljs.edn" "target/viewer/main.js"]
            [:int {:min 1 :max 16777216}]] [:fn bytes?]])

(defn- input-hash
  "Hash exact declaration bytes; decoding must not collapse distinct invalid UTF-8."
  [path]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") ^bytes (read-bytes! path 1048576))))
(m/=> input-hash [:=> [:cat [:enum "deps.edn" "shadow-cljs.edn"]] c/Digest])

(defn- source-paths!
  "Discover nested browser sources without following links or silently dropping invalid names.
   A 257th source refuses the 256-entry manifest budget. The trusted checkout scan
   has no general filesystem traversal deadline; this is an authoring operation."
  [root]
  (try
    (with-open [stream (Files/walk ^Path root (make-array FileVisitOption 0))]
      (let [paths (->> (iterator-seq (.iterator stream))
                       (keep (fn [^Path path]
                               (when (Files/isSymbolicLink path) (refuse! :viewer-build-input))
                               (when (and (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                                          (re-find #"\.(?:cljc|cljs)$" (str (.getFileName path))))
                                 (str "gate/" (str/join "/" (map str (iterator-seq (.iterator (.relativize ^Path root path)))))))))
                       (take 257) sort vec)]
        (when-not (m/validate [:vector {:min 1 :max 256} vc/ResourcePath] paths)
          (refuse! :viewer-build-input))
        paths))
    (catch java.io.IOException _ (refuse! :viewer-build-input))
    (catch java.io.UncheckedIOException _ (refuse! :viewer-build-input))
    (catch SecurityException _ (refuse! :viewer-build-input))))
(m/=> source-paths! [:=> [:cat [:fn #(instance? Path %)]] [:vector {:min 1 :max 256} vc/ResourcePath]])

(defn snapshot
  "Bind every module CLJC/CLJS source plus dependency and compiler declarations.
   Conservative source membership catches newly introduced files in freshness checks.
   This records declared pins, not a lockfile or an attestation of compiler jars."
  []
  (let [paths (source-paths! (.toPath (io/file "src/gate")))]
    {:inputs (mapv (fn [path] {:path path :digest (canonical/sha256 (asset/resource-text! path 1048576))}) paths)
     :dependencies (input-hash "deps.edn") :configuration (input-hash "shadow-cljs.edn")}))
(m/=> snapshot [:=> [:cat] Snapshot])

(defn check!
  "Verify packaged bytes, source membership, build configuration and dependency declarations.
   Run from the module root; a passing check does not replace an offline browser test."
  []
  (let [manifest (asset/manifest!)]
    (when-not (= (snapshot) (select-keys manifest [:inputs :dependencies :configuration]))
      (refuse! :viewer-build-stale))
    (asset/load!)
    manifest))
(m/=> check! [:=> [:cat] vc/Manifest])

(defn build!
  "Compile the pinned release and package only JavaScript and public provenance.
   Requires Clojure/shadow-cljs during authoring; consumers need neither Node nor
   a compiler invocation to render. Source maps and compiler caches stay ignored.
   Inputs must stay immutable for the build; before/after equality is not ABA proof."
  []
  (let [before (snapshot)
        command ["clojure" "-M:shadow" "release" "viewer"]
        compiler (.start (doto (ProcessBuilder. ^java.util.List command)
                           (.inheritIO)))]
    (when-not (zero? (.waitFor compiler)) (refuse! :viewer-build-failed))
    (when-not (= before (snapshot)) (refuse! :viewer-build-changed))
    (let [bundle (read-bytes! "target/viewer/main.js" 16777216)
          _ (when (zero? (alength ^bytes bundle)) (refuse! :viewer-build-input))
          manifest (assoc before :schema/version 1 :bundle (canonical/sha256 (String. ^bytes bundle StandardCharsets/UTF_8)))
          target (io/file "resources/gate/viewer")]
      (io/make-parents (io/file target "main.js"))
      (with-open [output (io/output-stream (io/file target "main.js"))] (.write output ^bytes bundle))
      (spit (io/file target "manifest.edn") (str (canonical/encode manifest 65536) "\n"))
      ;; A first build can create resources after the JVM fixed its classpath.
      ;; A fresh process runs check! (also enrolled in the ordinary module suite).
      manifest)))
(m/=> build! [:=> [:cat] vc/Manifest])

(defn -main
  "Build by default, or check freshness with the single argument 'check'."
  [& [operation]]
  (when-not (contains? #{nil "build" "check"} operation) (refuse! :viewer-build-input))
  (let [manifest (if (= "check" operation) (check!) (build!))]
    (prn {:bundle (:bundle manifest) :inputs (count (:inputs manifest))}))
  nil)
(m/=> -main [:=> [:cat [:? :string]] :nil])
