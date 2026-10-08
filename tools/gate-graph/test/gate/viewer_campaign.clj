(ns gate.viewer-campaign
  "Attributed packaged-viewer and create-only HTML faults with clean full-suite baselines."
  (:require [gate.admission-campaign :as campaign]
            [gate.process-campaign :as process]
            [malli.core :as m]))

(def asset-faults
  [{:id "bundle-hash-ignored" :anchor "(= (:bundle manifest) (canonical/sha256 javascript))"
    :replacement "true" :test "mismatched-bundle-and-sources-refuse"}
   {:id "source-hash-ignored" :anchor "(= digest (canonical/sha256 (resource-text! path 1048576)))"
    :replacement "true" :test "mismatched-bundle-and-sources-refuse"}
   {:id "input-roster-ignored" :anchor "(refuse! :viewer-input-roster)" :replacement "nil"
    :test "missing-duplicate-or-unsorted-viewer-inputs-refuse"}
   {:id "resource-budget-ignored" :anchor "(> (alength content) limit)" :replacement "false"
    :test "actual-byte-bound-and-strict-utf8-on-resources"}
   {:id "malformed-utf8-replaced" :anchor "(.onMalformedInput CodingErrorAction/REPORT)"
    :replacement "(.onMalformedInput CodingErrorAction/REPLACE)"
    :test "actual-byte-bound-and-strict-utf8-on-resources"}])

(def publication-faults
  [{:id "viewer-binding-ignored" :anchor "(= (:digest viewer) (canonical/sha256 (:javascript viewer)))"
    :replacement "true" :test "invalid-graph-or-viewer-cannot-create-html"}
   {:id "existing-html-overwritten" :anchor "(Files/createLink (.resolve base \"index.html\") temporary)"
    :replacement "(Files/copy temporary (.resolve base \"index.html\") (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))"
    :test "existing-evidence-including-symlinks-is-never-replaced"}
   {:id "graph-identity-not-normalized" :anchor "(canonical/normalize-graph value)" :replacement "value"
    :test "published-html-binds-the-exact-normalized-graph-and-viewer"}
   {:id "publication-byte-count-wrong" :anchor ":bytes (alength content)" :replacement ":bytes 1"
    :test "published-html-binds-the-exact-normalized-graph-and-viewer"}
   {:id "native-path-error-escapes"
    :anchor "(catch IllegalArgumentException _ (refuse! :html-policy))" :replacement ""
    :test "invalid-native-path-has-a-closed-policy-failure"}])

(def build-faults
  [{:id "declaration-bytes-decoded-before-hashing"
    :anchor "(.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance \"SHA-256\") ^bytes (read-bytes! path 1048576)))"
    :replacement "(canonical/sha256 (String. ^bytes (read-bytes! path 1048576) StandardCharsets/UTF_8))"
    :test "declaration-hashes-preserve-distinct-byte-inputs"}
   {:id "nested-browser-sources-omitted"
    :anchor "(Files/walk ^Path root (make-array FileVisitOption 0))"
    :replacement "(Files/list ^Path root)"
    :test "source-membership-includes-nested-browser-files"}
   {:id "build-byte-budget-ignored" :anchor "(> (alength content) limit)" :replacement "false"
    :test "build-file-acquisition-bounds-bytes-and-maps-io-errors"}
   {:id "source-links-silently-omitted"
    :anchor "(when (Files/isSymbolicLink path) (refuse! :viewer-build-input))" :replacement "nil"
    :test "source-discovery-refuses-links-empty-membership-and-overflow"}])

(def manifest-faults
  [{:id "resource-path-substring-accepted"
    :anchor "#\"^gate/(?:[A-Za-z_][A-Za-z0-9_]*/)*[A-Za-z_][A-Za-z0-9_]*\\.(?:cljc|cljs)$(?![\\s\\S])\""
    :replacement "#\"gate/[a-z_]+\\.(?:cljc|cljs)\""
    :test "manifest-input-paths-name-exact-public-resources"}])

(def faults
  (vec (mapcat (fn [[source namespace-name control selected]]
                 (map #(assoc % :source source :namespace namespace-name :control control) selected))
               [["src/gate/viewer_asset.clj" "gate.viewer-asset-test"
                 "packaged-viewer-is-fresh-and-contract-valid" asset-faults]
                ["src/gate/report_publish.clj" "gate.report-publish-test"
                 "generated-labels-roundtrip-without-changing-script-boundaries" publication-faults]
                ["src/gate/process.clj" "gate.process-test"
                 "missing-executable-relative-executable-and-jobserver-refuse" process/faults]
                ["src/gate/viewer_contract.cljc" "gate.viewer-asset-test"
                 "actual-byte-bound-and-strict-utf8-on-resources" manifest-faults]
                ["src/gate/viewer_build.clj" "gate.viewer-asset-test"
                 "actual-byte-bound-and-strict-utf8-on-resources" build-faults]])))

(defn -main
  "Use one frozen copy and full before/after baselines for the entire delivery scope.
   Each fault still runs in its own JVM with an independently passing control."
  [output-parent]
  (binding [campaign/*scope* :viewer-delivery campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
