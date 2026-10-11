(ns gate.run-lens-test
  "Optional attention projections retain exact counts, routes and independently ranked measured intervals."
  (:require [clojure.test :refer [deftest is]]
            [gate.archive :as archive]
            [gate.archive-test :as archives]
            [gate.canonical :as canonical]
            [gate.fixtures :as fixtures]
            [gate.record-view :as view]
            [gate.run-lens :as lens]
            [gate.run-lens-contract :as lc]
            [malli.core :as m]))
(def budget {:top 5 :bytes 16384})
(defn saved [graph] (archive/create archives/scope graph archives/unavailable archives/unavailable))
(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))

(deftest attention-counts-tasks-and-gates-separately-and-every-row-drills
  (let [graph (-> (fixtures/example)
                  (assoc-in [:nodes 0 :outcome] :failed)
                  (assoc-in [:nodes 1 :outcome] :error)
                  (assoc-in [:nodes 2 :outcome] :cancelled))
        document (saved graph) result (lens/digest document {:top 2 :bytes 16384})
        context (view/prepare :run-archive document)]
    (is (m/validate lc/digest-result result))
    (is (= [3 2 1] ((juxt :observations :gates :omitted) (:attention result))))
    (is (= ["check" "compile"] (mapv :id (get-in result [:attention :rows]))))
    (doseq [row (concat (get-in result [:attention :rows]) (get-in result [:longest-gates :rows]))]
      (is (= (:id row) (get-in (:document context) (conj (:path row) :id))))
      (is (= (:path row) (get-in (view/navigate context {:path (:path row) :direction :self :bytes 65536}) [:location :path]))))
    (is (= (set (map vector (keys document))) (set (map :path (:sections result)))))
    (is (= (count (:graph document)) (:children (first (filter #(= [:graph] (:path %)) (:sections result))))))))

(deftest decisions-and-open-executions-have-attention-but-no-invented-duration
  (let [graph (-> (assoc (fixtures/decision) :edges []) (assoc-in [:nodes 2 :outcome] :blocked)
                  (assoc-in [:nodes 2 :reason] {:code :prerequisite-failed :detail "Synthetic prerequisite"})
                  (assoc-in [:nodes 1 :outcome] :running) (assoc-in [:nodes 1 :interval :end-ns] nil)
                  (assoc-in [:nodes 0 :outcome] :running) (assoc-in [:nodes 0 :interval :end-ns] nil)
                  (assoc-in [:run :complete?] false) (assoc-in [:run :end-ns] nil))
        result (lens/digest (saved graph) budget)]
    (is (= [3 2 0] ((juxt :observations :gates :omitted) (:attention result))))
    (is (= #{:running :blocked} (set (map :outcome (get-in result [:attention :rows])))))
    (is (= [0 1 0] ((juxt :closed :open :omitted) (:longest-gates result))))
    (is (= 1 (get-in result [:gates :decisions])))
    (is (nil? (get-in result [:run :elapsed-ns])))
    (is (= :failed (:status result)))))

(deftest duration-order-uses-exact-string-integers-and-stable-id-ties
  (let [graph (-> (fixtures/example) (assoc :edges [])
                  (assoc-in [:run :end-ns] "9007199254740993")
                  (assoc-in [:nodes 0 :interval :end-ns] "9007199254740993")
                  (assoc-in [:nodes 1 :interval] {:start-ns "0" :end-ns "9007199254740992"})
                  (assoc-in [:nodes 2 :interval] {:start-ns "0" :end-ns "9007199254740993"}))
        result (lens/digest (saved graph) budget)
        tied (lens/digest (saved (assoc-in graph [:nodes 1 :interval :end-ns] "9007199254740993")) budget)]
    (is (= ["check" "compile"] (mapv :id (get-in result [:longest-gates :rows]))))
    (let [inverted (lens/digest (saved (-> graph (assoc-in [:nodes 1 :interval :end-ns] "9007199254740993")
                                           (assoc-in [:nodes 2 :interval :end-ns] "9007199254740992"))) budget)]
      (is (= ["compile" "check"] (mapv :id (get-in inverted [:longest-gates :rows])))))
    (is (= ["9007199254740993" "9007199254740992"] (mapv :elapsed-ns (get-in result [:longest-gates :rows]))))
    (is (= ["check" "compile"] (mapv :id (get-in tied [:longest-gates :rows]))))
    (is (= "9007199254740993" (get-in result [:run :elapsed-ns])))
    (is (= "9007199254740993" (get-in result [:run :clock :origin-ns])))))

(deftest projection-byte-budget-includes-every-field-and-preserves-archive-seal
  (let [document (saved (fixtures/example)) result (lens/digest document budget)
        required (canonical/utf8-size (canonical/encode result 65536))]
    (is (= result (lens/digest document {:top 5 :bytes required})))
    (is (= :encoded-byte-limit (failure #(lens/digest document {:top 5 :bytes (dec required)}))))
    (is (= :encoded-byte-limit (failure #(lens/digest document {:top 5 :bytes 1024}))))
    (is (= :archive-identity (failure #(lens/digest (assoc-in document [:graph :nodes 0 :label] "tampered") budget))))
    (doseq [bad [{:top 0 :bytes 16384} {:top 101 :bytes 16384} {:top 5 :bytes 65537} {:top 5 :bytes 16384 :extra true}]]
      (is (not (m/validate lc/digest-budget bad))) (is (thrown? clojure.lang.ExceptionInfo (lens/digest document bad))))))

(deftest refused-decisions-remain-attention-and-do-not-become-executions
  (let [graph (-> (fixtures/decision) (assoc :edges [])
                  (assoc-in [:nodes 2 :outcome] :refused)
                  (assoc-in [:nodes 2 :reason] {:code :unsupported :detail "Synthetic unsupported input"}))
        document (saved graph) result (lens/digest document budget)]
    (is (= [1 1 0] ((juxt :observations :gates :omitted) (:attention result))))
    (is (= [:decision :refused] ((juxt :record :outcome) (first (get-in result [:attention :rows])))))
    (is (= "check" (get-in document (conj (:path (first (get-in result [:attention :rows]))) :id))))
    (is (= [1 0 0] ((juxt :closed :open :omitted) (:longest-gates result))))))
