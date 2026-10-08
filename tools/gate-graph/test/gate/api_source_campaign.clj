(ns gate.api-source-campaign
  "Selected passive inventory faults with independently asserted rosters and passing neighboring controls."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "cljs-selected-as-clj"
    :anchor ":features #{platform}" :replacement ":features #{:clj}"
    :test "selects-platform-without-implicit-jvm-feature"}
   {:id "literal-keyword-ends-source"
    :anchor "(identical? end form)"
    :replacement "(or (= :edamame.core/eof form) (identical? form end))"
    :test "source-keyword-cannot-masquerade-as-end-of-stream"}
   {:id "source-budget-counts-code-units"
    :anchor "(canonical/utf8-size source)" :replacement "(count source)"
    :test "counts-utf8-bytes-forms-and-parser-nodes"}
   {:id "source-form-budget-ignored"
    :anchor "(>= forms (:forms limits))" :replacement "false"
    :test "counts-utf8-bytes-forms-and-parser-nodes"}
   {:id "parser-node-budget-ignored"
    :anchor "(> (vswap! nodes inc) max-nodes)" :replacement "(do (vswap! nodes inc) false)"
    :test "counts-utf8-bytes-forms-and-parser-nodes"}
   {:id "declaration-privacy-dropped"
    :anchor "(boolean (:private attributes))" :replacement "false"
    :test "generated-declaration-rosters-have-independent-exact-oracles"}
   {:id "defn-minus-overrides-explicit-public-attribute"
    :anchor "(boolean (:private attributes))"
    :replacement "(boolean (or (= \"defn-\" head) (:private attributes)))"
    :test "explicit-attributes-override-defn-minus-privacy"}
   {:id "unbound-declarations-accepted"
    :anchor "(when (seq (:declared context)) (refuse! :api-source-unbound nil))" :replacement "nil"
    :test "declarations-reconcile-and-duplicates-refuse"}
   {:id "duplicate-declarations-overwritten"
    :anchor "(when (contains? (:definitions context) local) (refuse! :api-source-duplicate form))"
    :replacement "nil" :test "declarations-reconcile-and-duplicates-refuse"}
   {:id "orphan-registration-accepted"
    :anchor "(every? #(contains? names (:id %)) registrations)" :replacement "true"
    :test "declarations-reconcile-and-duplicates-refuse"}])

(defn -main
  "Assess finite source observation faults in disposable frozen copies, with full before/after suites."
  [output-parent]
  (binding [campaign/*scope* :api-source campaign/*mutation-source* "src/gate/api_source.clj"
            campaign/*test-namespace* "gate.api-source-test"
            campaign/*control* "exact-source-evidence-binds-ordered-arity-contracts"
            campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
