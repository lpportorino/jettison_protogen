(ns gate.viewer-asset
  "Load and verify the packaged browser asset before a consumer starts expensive work."
  (:require [clojure.java.io :as io]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.report :as report]
            [gate.viewer-contract :as vc]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction CharacterCodingException]))

(set! *warn-on-reflection* true)

(def Asset [:map {:closed true} [:javascript report/Bundle] [:digest c/Digest]])
(def Failure
  [:map {:closed true}
   [:code [:enum :viewer-unavailable :viewer-byte-limit :viewer-utf8
           :viewer-bundle-mismatch :viewer-source-mismatch :viewer-input-roster]]])
(def manifest-limits
  "The build manifest is small and shallow even when the report itself is large."
  {:bytes 65536 :depth 8 :values 2048 :collection 256 :string-units 256 :token-units 64})

(defn- refuse!
  "Retain a closed failure code without classpath locations or source contents."
  [code]
  (throw (ex-info "Packaged viewer refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2))] :nil])

(defn resource-text!
  "Read a trusted classpath resource with an actual-byte limit and strict UTF-8.
   Uses the same classloader resolution as the program; this is not a signature
   or a sandbox against a hostile classpath. Missing resources fail before a run."
  [path limit]
  (try
    (let [resource (or (io/resource path) (refuse! :viewer-unavailable))]
      (with-open [stream (io/input-stream resource)]
        (let [content (.readNBytes stream (int (inc limit)))]
          (when (> (alength content) limit) (refuse! :viewer-byte-limit))
          (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                          (.onMalformedInput CodingErrorAction/REPORT)
                          (.onUnmappableCharacter CodingErrorAction/REPORT))
                        (ByteBuffer/wrap content))))))
    (catch CharacterCodingException _ (refuse! :viewer-utf8))
    (catch java.io.IOException _ (refuse! :viewer-unavailable))))
(m/=> resource-text! [:=> [:cat [:string {:min 1 :max 256}] [:int {:min 1 :max 16777216}]] report/Bundle])

(defn manifest!
  "Read the packaged provenance through the bounded EDN parser and closed schema."
  []
  (admission/decode (resource-text! "gate/viewer/manifest.edn" 65536) :viewer-manifest manifest-limits))
(m/=> manifest! [:=> [:cat] vc/Manifest])

(defn load!
  "Verify release bytes and each recorded CLJC/CLJS source against the classpath.
   Build configuration/dependency hashes are provenance; the build freshness command
   checks those against the checkout. This does not attest loaded code or dependency
   jars, authenticate the publisher, or prove that JavaScript was compiled correctly."
  []
  (let [manifest (manifest!)
        paths (mapv :path (:inputs manifest))
        javascript (resource-text! "gate/viewer/main.js" 16777216)]
    (when (or (not= paths (vec (sort (distinct paths))))
              (not (some #{"gate/viewer.cljs"} paths)))
      (refuse! :viewer-input-roster))
    (when-not (= (:bundle manifest) (canonical/sha256 javascript))
      (refuse! :viewer-bundle-mismatch))
    (doseq [{:keys [path digest]} (:inputs manifest)]
      (when-not (= digest (canonical/sha256 (resource-text! path 1048576)))
        (refuse! :viewer-source-mismatch)))
    {:javascript javascript :digest (:bundle manifest)}))
(m/=> load! [:=> [:cat] Asset])
