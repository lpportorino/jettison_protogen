(ns gate.diagnostic
  "Compact JVM instrumentation errors for local gate APIs; never serialize rejected values or schemas."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def Namespaces
  "Additional exact consumer namespace names; a parent name does not enroll its descendants."
  [:vector {:max 128} [:and :symbol [:fn #(boolean (re-matches #"[A-Za-z][A-Za-z0-9._-]{0,255}" (str %)))]]])

(def Failure
  [:map {:closed true}
   [:code [:enum :contract-input :contract-output :contract-arity :contract-guard :contract-violation]]
   [:function [:string {:min 1 :max 256}]]
   [:issues [:vector {:max 16}
             [:map {:closed true}
              [:path [:vector {:max 32} [:or :keyword :int [:= "*"]]]]
              [:type [:enum :missing-key :extra-key :invalid-type :invalid-value]]]]]])

(defn install!
  "Instrument registered gate.* functions with closed, bounded serialized contract errors.
   Failures name the function and phase and up to 16 schema paths (32 components each).
   Dynamic string/map keys become '*'; raw values, complete schemas and causes are omitted.
   This adapts Malli's internal callback, not a public raw-data query interface. Its explanation
   traversal is governed by admitted input bounds; the output cap is not a general CPU budget.
   Invoke at a process/test entrypoint after loading the intended namespaces.
   The optional vector enrolls exact consumer namespaces alongside gate.*; schemas on
   consumer functions otherwise remain declarations only. It never matches a prefix."
  ([] (install! []))
  ([namespaces]
   (mi/instrument!
    {:filters [(fn [namespace-name _ _]
                 (or (str/starts-with? (str namespace-name) "gate.")
                     (contains? (set namespaces) namespace-name)))]
     :report
     (fn [event-type data]
       (let [[schema value] (case event-type
                              :malli.core/invalid-input [(:input data) (:args data)]
                              :malli.core/invalid-output [(:output data) (:value data)]
                              [nil nil])
             issues (when schema
                      (mapv (fn [{:keys [in] error-type :type}]
                              {:path (mapv #(if (or (keyword? %) (integer? %)) % "*") (take 32 in))
                               :type (case error-type :malli.core/missing-key :missing-key
                                           :malli.core/extra-key :extra-key
                                           :malli.core/invalid-type :invalid-type :invalid-value)})
                            (take 16 (:errors (m/explain schema value)))))
             function-name (str (:fn-name data))
             failure {:code (case event-type :malli.core/invalid-input :contract-input
                                  :malli.core/invalid-output :contract-output
                                  :malli.core/invalid-arity :contract-arity
                                  :malli.core/invalid-guard :contract-guard :contract-violation)
                      :function (subs function-name 0 (min 256 (count function-name)))
                      :issues (or issues [])}]
         (when-not (m/validate Failure failure)
           (throw (ex-info "Invalid contract diagnostic" {:code :contract-violation :function "unknown" :issues []})))
         (throw (ex-info "Gate function contract violated" failure))))})
   nil))
(m/=> install! [:function [:=> [:cat] :nil] [:=> [:cat Namespaces] :nil]])
