(ns gate.api-test
  "Independent adoption/version examples and generated identity changes over synthetic public inventories."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.api :as api]
            [gate.api-contract :as ac]
            [gate.canonical :as canonical]
            [malli.core :as m]
            [malli.generator :as mg]))

(def a (apply str (repeat 64 "a")))
(def b (apply str (repeat 64 "b")))
(def tool {:id "fixture.query/page" :kind :operation :role :tool :invocation :function
           :platforms [:clj :cljs] :capabilities ["inspection"] :definition a :arguments a :result a})
(def helper (assoc tool :id "fixture.query/prepare!" :role :trusted))
(def material {:schema/version 1 :profile :source-closure-v1 :api-version 1 :source a
               :entries [tool helper]})
(defn- document [] (api/manifest material))
(defn- record []
  {:schema/version 1 :consumer-api-version 1 :acceptance-version 1 :manifest (:artifact (document))
   :surface a :exports ["fixture.toolbox/page"]
   :decisions [{:id (:id tool) :action :adopt :wrappers ["fixture.toolbox/page"]
                :reason "Toolbox delegates to the shared bounded page operation."}]})
(defn- request [] {:manifest (document) :acceptance (record) :previous nil :surface a
                   :exports ["fixture.toolbox/page"]})
(defn- code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error
                 (is (m/validate ac/Failure (ex-data error))) (:code (ex-data error)))))

(deftest deterministic-identity-and-explicit-tool-adoption
  (is (= (document) (api/manifest (update material :entries #(vec (reverse %))))))
  (is (nil? (api/verify! (document))))
  (is (= {:status :accepted :manifest (:artifact (document))
          :consumer-api-version 1 :acceptance-version 1
          :adopted [(:id tool)] :deferred []} (api/check! (request))))
  (let [deferred (-> (request)
                     (assoc :exports [])
                     (assoc-in [:acceptance :exports] [])
                     (assoc-in [:acceptance :decisions 0]
                               {:id (:id tool) :action :defer :wrappers [] :reason "Not exposed in this consumer yet."}))]
    (is (= [(:id tool)] (:deferred (api/check! deferred)))))
  (doseq [value [(document) (record) (request) (api/check! (request))]]
    (is (= value (admission/decode (canonical/encode value 65536) :value admission/default-limits)))))

(deftest manifests-refuse-duplicate-and-forged-inventories
  (doseq [bad [(update material :entries conj tool)
               (assoc-in material [:entries 0 :platforms] [:clj :clj])
               (assoc-in material [:entries 0 :capabilities] ["inspection" "inspection"])]]
    (is (= :api-duplicates (code #(api/manifest bad)))))
  (is (= :api-identity (code #(api/verify! (assoc (document) :artifact b)))))
  (is (= :api-identity (code #(api/verify! (assoc (document) :source b)))))
  (is (= :api-order (code #(api/verify! (update (document) :entries (comp vec reverse)))))))

(deftest every-public-declaration-change-needs-upstream-version
  (doseq [edit [#(assoc-in % [:entries 0 :arguments] b)
                #(assoc-in % [:entries 0 :result] b)
                #(assoc-in % [:entries 0 :role] :trusted)
                #(assoc-in % [:entries 0 :platforms] [:clj])
                #(update % :entries conj (assoc tool :id "fixture.query/new-tool?"))
                #(update % :entries pop)]]
    (let [changed (edit material)]
      (is (= :api-version-required (code #(api/verify-upgrade! (document) (api/manifest changed)))))
      (is (nil? (api/verify-upgrade! (document) (api/manifest (assoc changed :api-version 2)))))))
  (is (= :api-version-regressed
         (code #(api/verify-upgrade! (api/manifest (assoc material :api-version 2)) (document)))))
  (is (nil? (api/verify-upgrade! (document) (api/manifest (assoc material :source b))))))

(deftest missing-tool-decisions-and-invented-wrappers-refuse
  (is (= :api-decisions (code #(api/check! (assoc-in (request) [:acceptance :decisions] [])))))
  (is (= :api-decisions
         (code #(api/check! (update-in (request) [:acceptance :decisions]
                                       conj {:id (:id helper) :action :defer :wrappers [] :reason "Trusted helper."})))))
  (is (= :api-wrapper-coverage
         (code #(api/check! (assoc-in (request) [:acceptance :decisions 0 :wrappers] ["fixture.toolbox/absent"]))))))

(deftest independently-observed-evidence-cannot-be-acknowledged-away
  (doseq [[path value expected] [[[:acceptance :manifest] b :api-unacknowledged-manifest]
                                 [[:surface] b :api-surface-drift]
                                 [[:exports] [] :api-export-drift]]]
    (is (= expected (code #(api/check! (assoc-in (request) path value))))))
  (is (= :api-duplicates (code #(api/check! (update (request) :exports conj "fixture.toolbox/page")))))
  (is (= :api-duplicates
         (code #(api/check! (update-in (request) [:acceptance :decisions] conj (first (:decisions (record))))))))
  (is (= :api-initial-version (code #(api/check! (assoc-in (request) [:acceptance :acceptance-version] 2))))))

(deftest consumer-acknowledgement-and-export-version-are-independent
  (let [previous (record)
        changed (-> (request) (assoc :previous previous :surface b) (assoc-in [:acceptance :surface] b))]
    (is (= :api-ack-required (code #(api/check! changed))))
    (is (= :accepted (:status (api/check! (assoc-in changed [:acceptance :acceptance-version] 2)))))
    (is (= :accepted (:status (api/check! (assoc-in changed [:acceptance :consumer-api-version] 2)))))
    (is (= :api-ack-required
           (code #(api/check! (-> (request) (assoc :previous previous)
                                  (assoc-in [:acceptance :decisions 0 :reason] "Updated review decision."))))))
    (let [renamed (-> changed
                      (assoc :exports ["fixture.toolbox/drill"])
                      (assoc-in [:acceptance :exports] ["fixture.toolbox/drill"])
                      (assoc-in [:acceptance :decisions 0 :wrappers] ["fixture.toolbox/drill"])
                      (assoc-in [:acceptance :acceptance-version] 2))]
      (is (= :api-export-version-required (code #(api/check! renamed))))
      (is (= :accepted (:status (api/check! (assoc-in renamed [:acceptance :consumer-api-version] 2))))))
    (is (= :api-version-regressed
           (code #(api/check! (assoc (request) :previous (assoc previous :acceptance-version 2))))))))

(deftest malformed-contracts-remain-closed-and-bounded
  (doseq [bad [(assoc (document) :private-path "/ignored")
               (assoc-in (document) [:entries 0 :surprise] true)
               (assoc (document) :api-version 0)
               (assoc (document) :entries [])]]
    (is (false? (m/validate ac/Manifest bad))))
  (doseq [bad [(assoc (record) :extra true)
               (assoc-in (record) [:decisions 0 :reason] "   ")
               (assoc-in (record) [:decisions 0 :action] :ignore)
               (assoc-in (record) [:decisions 0 :wrappers] [])]]
    (is (false? (m/validate ac/Acceptance bad))))
  (doseq [export-name ["fixture.query/run!" "fixture.query/overlaps?" "fixture.contract/Manifest"]]
    (is (m/validate ac/Name export-name))))

(deftest decision-change-requires-ack-even-with-unchanged-wrapper-inventory
  (let [second-tool (assoc tool :id "fixture.query/search")
        doc (api/manifest (update material :entries conj second-tool))
        previous (-> (record) (assoc :manifest (:artifact doc))
                     (update :decisions conj {:id (:id second-tool) :action :defer :wrappers []
                                              :reason "Not yet exposed by the existing dispatcher."}))
        current (assoc-in previous [:decisions 1]
                          {:id (:id second-tool) :action :adopt :wrappers ["fixture.toolbox/page"]
                           :reason "The same dispatcher now supports search."})
        req (assoc (request) :manifest doc :acceptance current :previous previous)]
    (is (= :api-ack-required (code #(api/check! req))))
    (is (= [(:id tool) (:id second-tool)]
           (:adopted (api/check! (assoc-in req [:acceptance :acceptance-version] 2)))))))

(deftest legal-large-inventory-budget-errors-remain-closed
  (let [capabilities (mapv #(str (apply str (repeat 156 "a")) %) (range 32))
        entries (mapv (fn [i] {:id (str "fixture.values/v" i) :kind :value :platforms [:clj]
                               :definition a :capabilities capabilities}) (range 4096))
        large (assoc material :entries entries)
        candidate (assoc large :artifact a)]
    (is (m/validate ac/Material large))
    (doseq [operation [#(api/manifest large) #(api/verify! candidate)
                       #(api/check! (assoc (request) :manifest candidate))]]
      (is (= :api-budget (code operation))))))

(deftest generated-source-changes-always-require-consumer-ack
  (let [result (tc/quick-check
                100
                (prop/for-all [suffix (mg/generator [:string {:min 1 :max 64}])]
                              (let [digest (canonical/sha256 (str "independent-source:" suffix))
                                    next-manifest (api/manifest (assoc material :source digest))
                                    changed (-> (request) (assoc :manifest next-manifest :previous (record))
                                                (assoc-in [:acceptance :manifest] (:artifact next-manifest)))
                                    refused (try (api/check! changed) false
                                                 (catch clojure.lang.ExceptionInfo error
                                                   (= :api-ack-required (:code (ex-data error)))))]
                                (and (not= (:artifact (document)) (:artifact next-manifest)) refused
                                     (= :accepted (:status (api/check! (assoc-in changed [:acceptance :acceptance-version] 2)))))))
                :seed 420812)]
    (is (:pass? result) (pr-str result))))
