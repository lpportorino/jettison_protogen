(ns gate.test-graph
  "Portable judgment and graph projection of bounded per-test observations."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.graph :as graph]
            [gate.run-contract :as r]
            [malli.core :as m]))

(def Judgement
  [:map {:closed true} [:complete? :boolean] [:status [:enum :passed :failed :error]]
   [:problems [:vector {:max 8} r/TestProblem]] [:coverage [:maybe r/Coverage]]])
(def Failure [:map {:closed true} [:code [:= :invalid-test-observation]]])

(defn judge
  "Reconcile declared identities, observed invocations, assertion counts and acquisition problems.
   A suite passes only with complete membership, positive assertions and no failed/error reports.
   The coverage digest binds the independent test inventory, not a summary printed by the tests."
  [inventory executions unattributed problems]
  (let [expected (set (map :key inventory)) observed (set (map :key executions))
        assertions (reduce + (mapcat (comp vals :counts) executions))
        problems (cond-> (set problems)
                   (seq (remove observed expected)) (conj :missing-tests)
                   (seq (remove expected observed)) (conj :unexpected-tests)
                   (pos? (reduce + (vals unattributed))) (conj :unattributed-assertions)
                   (zero? assertions) (conj :empty-assertions))
        complete? (empty? problems)
        status (cond (or (not complete?) (some #(or (not (:returned? %)) (pos? (get-in % [:counts :error]))) executions)) :error
                     (some #(pos? (get-in % [:counts :fail])) executions) :failed
                     :else :passed)
        digest (canonical/sha256 (canonical/encode inventory 67108864))]
    {:problems (vec (sort problems)) :complete? complete? :status status
     :coverage (when (= :passed status) {:expected digest :observed digest :count (count executions)})}))
(m/=> judge [:=> [:cat r/TestInventory [:vector {:max 10000} r/TestExecution] r/TestCounts
                  [:vector {:max 8} r/TestProblem]] Judgement])

(defn- outcome
  "Distinguish test assertion failure, unexpected exception and normal return."
  [execution]
  (cond (or (not (:returned? execution)) (pos? (get-in execution [:counts :error]))) :error
        (pos? (get-in execution [:counts :fail])) :failed
        :else :passed))
(m/=> outcome [:=> [:cat r/TestExecution] [:enum :passed :failed :error]])

(defn- measurements
  "Record entry count and exclusive assertion ownership; retained counts after overflow are lower bounds."
  [node counts interval test? partial?]
  (mapv (fn [[quantity value]]
          (let [uncertain? (and partial? (not= :tests quantity))]
            {:id (str node "/" (name quantity)) :node node
             :resource "test-work" :source "test-observer" :quantity quantity :form :delta
             :accounting :exclusive :interval interval
             :status (if uncertain? :partial :measured) :value value
             :reason (when uncertain? :partial-coverage) :method :test-runner}))
        (cond-> [[:assertions (str (reduce + (vals counts)))]
                 [:assertions-passed (str (:pass counts))]
                 [:assertions-failed (str (:fail counts))]
                 [:assertions-errored (str (:error counts))]]
          test? (conj [:tests "1"]))))
(m/=> measurements [:=> [:cat c/Id r/TestCounts c/Interval :boolean :boolean] [:vector {:min 4 :max 5} c/Measurement]])

(defn project
  "Produce the same canonical graph consumed by drill, aggregate, diff and offline HTML tools.
   Test-var timing includes reporting and excludes surrounding fixtures. Missing test identities get
   explicit missing-evidence decisions. Complete capture is independent of a passing verdict. Counts
   have exclusive dynamic invocation ownership; overlapping intervals still require explicit pair
   evidence before aggregation. This projection supplies no CPU/resource or causal attribution.
   It validates record consistency before graph invariants; raw schema admission alone is insufficient."
  [observation]
  (let [{:keys [inventory executions duration-ns source-digest complete? status problems]} observation
        expected (into {} (map (juxt :key :label) inventory))
        judgement (judge inventory executions (:unattributed observation) problems)]
    (when (or (not= judgement (select-keys observation (keys judgement)))
              (not= (count inventory) (count expected))
              (some #(and (contains? expected (:key %)) (not= (get expected (:key %)) (:label %))) executions)
              (some (fn [[_ entries]] (not= (mapv :attempt entries) (vec (range 1 (inc (count entries))))))
                    (group-by :key executions)))
      (throw (ex-info "Test observation refused" {:code :invalid-test-observation})))
    (let [observed (set (map :key executions))
          nodes (mapv (fn [execution]
                        (merge (select-keys execution [:id :key :label :attempt :interval])
                               {:parent (or (:parent execution) "suite") :source "test-observer" :kind :test
                                :record :execution :outcome (outcome execution)})) executions)
          missing (mapv (fn [{test-key :key :keys [label]}]
                          {:id (str "missing/" (canonical/sha256 test-key)) :key test-key :label label :parent "suite"
                           :source "test-observer" :kind :test :record :decision :at-ns duration-ns
                           :outcome :refused :reason {:code :missing-evidence :detail "Declared test var was not observed"}})
                        (remove #(contains? observed (:key %)) inventory))
          problem-nodes (mapv (fn [problem]
                                {:id (str "problem/" (name problem)) :key (str "collector/" (name problem))
                                 :label (str "Test observation: " (name problem)) :parent "suite"
                                 :source "test-observer" :kind :boundary :record :decision :at-ns duration-ns
                                 :outcome :refused :reason {:code :missing-evidence :detail (name problem)}}) problems)
          partial? (boolean (some #{:assertion-limit} problems))
          unattributed (:unattributed observation)
          value {:schema/version 1
                 :run {:id (:run observation) :source-digest source-digest :complete? complete?
                       :clock {:id "clojure-test" :origin-ns "0"} :end-ns duration-ns :expected-keys [(:gate observation)]}
                 :sources [{:id "test-observer" :kind :capture :label "clojure.test dynamic invocation observer"
                            :digest (canonical/sha256 (canonical/encode observation 67108864))}
                           {:id "test-inventory" :kind :declaration :label "Pre-run loaded test vars"
                            :digest (canonical/sha256 (canonical/encode inventory 67108864))}]
                 :nodes (into [{:id "suite" :key (:gate observation) :label (:label observation) :parent nil
                                :source "test-observer" :kind :gate :record :execution :attempt 1 :outcome status
                                :interval {:start-ns "0" :end-ns duration-ns}}] (concat nodes missing problem-nodes))
                 :resources [{:id "test-work" :parent nil :source "test-observer" :kind :logical
                              :label "Observed test entries and assertion reports"}]
                 :edges []
                 :measurements (into (if (pos? (reduce + (vals unattributed)))
                                       (measurements "suite" unattributed {:start-ns "0" :end-ns duration-ns} false partial?) [])
                                     (mapcat #(measurements (:id %) (:counts %) (:interval %) true partial?)) executions)}]
      (canonical/normalize-graph (graph/require-valid! value)))))
(m/=> project [:=> [:cat r/TestObservation] c/Graph])
