(ns gate.extension-campaign
  "Selected faults at each extension trust boundary, judged by the shared attributed campaign."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "duplicate-registration" :source "src/gate/extension.cljc"
    :anchor "(when (contains? out registration-key) (ec/refuse! :extension-duplicate))"
    :replacement "(when false (ec/refuse! :extension-duplicate))"
    :test "duplicate-registrations-refuse"}
   {:id "compiled-validator-ignored" :source "src/gate/extension.cljc"
    :anchor ":validate (m/validator (schema/compile-schema contract))"
    :replacement ":validate (constantly true)"
    :test "prepared-contexts-remain-independent"}
   {:id "payload-admission-skipped" :source "src/gate/extension.cljc"
    :anchor "(when-not ((:validate entry) (:payload observation)) (ec/refuse! :extension-payload))"
    :replacement "(when false (ec/refuse! :extension-payload))"
    :test "exact-version-and-payload-admission"}
   {:id "registration-count-unbounded" :source "src/gate/extension.cljc"
    :anchor "(and (vector? registrations) (<= (count registrations) (:registrations ec/limits)))"
    :replacement "(vector? registrations)"
    :test "schema-shape-and-expanded-work-have-hard-limits"}
   {:id "lone-surrogate-admitted" :source "src/gate/portable.cljc"
    :anchor "(<= 56320 u 57343) false"
    :replacement "(<= 56320 u 57343) true"
    :test "bounded-codec-does-not-admit-unknown-data"}
   {:id "sequence-counted-before-refusal" :source "src/gate/portable.cljc"
    :anchor "(when (or (> depth 64) (>= visited 16777216))"
    :replacement "(when (or (> depth 64) (>= visited 16777216) (and (coll? item) (> (count item) 1000000)))"
    :test "unbounded-sequences-are-refused-without-being-realized"}
   {:id "core-binding-skipped" :source "src/gate/extension.cljc"
    :anchor "(when-not (= (:artifact core) (:core/artifact sidecar)) (ec/refuse! :extension-binding))"
    :replacement "(when false (ec/refuse! :extension-binding))"
    :test "contract-and-core-bindings-refuse-tampering"}
   {:id "sidecar-identity-skipped" :source "src/gate/extension.cljc"
    :anchor "(when-not (= expected sidecar) (ec/refuse! :extension-identity))"
    :replacement "(when false (ec/refuse! :extension-identity))"
    :test "contract-and-core-bindings-refuse-tampering"}
   {:id "partial-publication-accepted" :source "src/gate/extension_io.clj"
    :anchor "(catch java.io.IOException _ (ec/refuse! :extension-output))"
    :replacement "(catch java.io.IOException _ nil)"
    :test "partial-publication-and-malformed-file-never-return-an-accepted-pair"}
   {:id "projection-result-unvalidated" :source "src/gate/extension.cljc"
    :anchor "(when-not (m/validate ec/Projection projection) (ec/refuse! :extension-projection))"
    :replacement "(when false (ec/refuse! :extension-projection))"
    :test "projection-failures-do-not-disappear"}
   {:id "open-map-admitted" :source "src/gate/extension_schema.cljc"
    :anchor "(when-not (true? (:closed properties)) (ec/refuse! :extension-schema))"
    :replacement "(when false (ec/refuse! :extension-schema))"
    :test "schemas-refuse-unsafe-or-unresolved-forms"}
   {:id "unknown-keyword-interned" :source "src/gate/admission.cljc"
    :anchor "(refuse! :unknown-keyword offset)"
    :replacement "(keyword (subs token 1))"
    :test "bounded-codec-does-not-admit-unknown-data"}
   {:id "core-encode-admission-skipped" :source "src/gate/canonical.cljc"
    :anchor "(when-not (m/validate encodable-schema value)"
    :replacement "(when false"
    :test "bounded-codec-does-not-admit-unknown-data"}
   {:id "continuation-unbound" :source "src/gate/extension_view.cljc"
    :anchor "(or (> start total) (not= (:cursor request) (cursor pair selection start)))"
    :replacement "(> start total)"
    :test "bounded-pages-preserve-frontier-and-pair-identity"}])

(defn -main
  "Run the extension campaign with the module's test namespace and an independent positive control."
  [output-parent]
  (binding [campaign/*scope* :report-extensions
            campaign/*test-namespace* "gate.extension-test"
            campaign/*control* "positive-observations-preserve-every-core-failure-state"
            campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
