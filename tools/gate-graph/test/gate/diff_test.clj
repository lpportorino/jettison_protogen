(ns gate.diff-test
  "Task comparison preserves absence, repetition, capture identity and bounded continuation semantics."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.canonical :as canonical]
            [gate.decimal :as decimal]
            [gate.diff :as diff]
            [gate.fixtures :as f]
            [gate.inspection-contract :as ic]
            [gate.query :as query]
            [malli.core :as m]))

(def request {:select {:op :tasks :changed-only? false}
              :budget {:visits 100 :rows 100 :bytes 65536}})

(defn context [before after]
  (diff/prepare (query/prepare before) (query/prepare after)))

(defn task-row [result task-key]
  (first (filter #(= task-key (get-in % [:identity :key])) (:rows result))))

(defn decision [outcome]
  (-> (f/decision)
      (assoc-in [:edges 0 :to :phase] :decision)
      (assoc-in [:nodes 2 :outcome] outcome)
      (assoc-in [:nodes 2 :reason]
                {:code (if (= :cached outcome) :cache-hit :unchanged) :detail "Synthetic decision"})))

(defn error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))

(deftest task-comparison-is-stable-under-capture-ids-clock-placement-and-input-order
  (let [before (f/example)
        rename-id #(when % (str "later/" %))
        after (-> before
                  (assoc-in [:run :id] "later")
                  (assoc-in [:run :clock :origin-ns] "99999999999999999")
                  (assoc-in [:run :end-ns] "110")
                  (update :nodes #(mapv (fn [node]
                                          (-> node (update :id rename-id) (update :parent rename-id)
                                              (update :interval (fn [interval]
                                                                  (into {} (map (fn [[k v]] [k (decimal/add v "10")])) interval)))))
                                        (reverse %)))
                  (update :edges #(mapv (fn [edge]
                                          (-> edge (update-in [:from :node] rename-id)
                                              (update-in [:to :node] rename-id))) %)))
        result (diff/page (context before after) request)]
    (is (not= (:before result) (:after result)))
    (is (every? #(= :unchanged (:change %)) (:rows result)))
    (is (every? #(not= (get-in % [:before :examples]) (get-in % [:after :examples])) (:rows result)))
    (is (= :task-observations (:scope result)))
    (is (= result (edn/read-string (canonical/encode result 65536))))))

(deftest elapsed-duration-changes-are-distinct-from-task-state
  (let [before (f/example)
        after (assoc-in before [:nodes 1 :interval :end-ns] "39")
        result (diff/page (context before after) request)
        compile-row (task-row result "compile")]
    (is (= :changed (:change compile-row)))
    (is (= [:durations] (:dimensions compile-row)))
    (is (= (get-in compile-row [:before :semantics]) (get-in compile-row [:after :semantics])))
    (is (not (contains? compile-row :regression?)))
    (is (= :unchanged (:change (task-row result "check"))))))

(deftest state-changes-have-no-invented-timing-change
  (let [after (assoc-in (f/example) [:nodes 1 :outcome] :failed)
        row (task-row (diff/page (context (f/example) after) request) "compile")]
    (is (= [:semantics] (:dimensions row)))
    (is (= 1 (get-in row [:before :outcomes :passed])))
    (is (= 1 (get-in row [:after :outcomes :failed])))
    (is (= (get-in row [:before :durations]) (get-in row [:after :durations])))))

(deftest cache-and-selection-decisions-remain-different-from-execution
  (let [execution-to-cache (task-row (diff/page (context (f/example) (decision :cached)) request) "check")
        cache-to-selection (task-row (diff/page (context (decision :cached) (decision :deselected)) request) "check")]
    (is (= [:semantics :durations] (:dimensions execution-to-cache)))
    (is (= 1 (get-in execution-to-cache [:after :outcomes :cached])))
    (is (= 0 (get-in execution-to-cache [:after :outcomes :passed])))
    (is (= [:semantics] (:dimensions cache-to-selection)))
    (is (= 1 (get-in cache-to-selection [:after :outcomes :deselected])))))

(deftest retries-and-repeated-observations-preserve-multiplicity
  (let [before (f/example)
        repeated (update before :nodes conj (assoc (get-in before [:nodes 1]) :id "another-compile"))
        retry (-> repeated (assoc-in [:nodes 1 :outcome] :failed) (assoc-in [:nodes 3 :attempt] 2))
        repeated-row (task-row (diff/page (context before repeated) request) "compile")
        retry-row (task-row (diff/page (context before retry) request) "compile")]
    (is (= 2 (get-in repeated-row [:after :observations])))
    (is (= [:semantics :durations] (:dimensions repeated-row)))
    (is (= 1 (get-in retry-row [:after :outcomes :failed])))
    (is (= 1 (get-in retry-row [:after :outcomes :passed])))
    (is (= ["another-compile" "compile"] (get-in retry-row [:after :examples])))))

(deftest identical-marginals-do-not-hide-swapped-state-duration-associations
  (let [before (-> (f/example)
                   (update :nodes conj (assoc (get-in (f/example) [:nodes 1]) :id "another-compile"))
                   (assoc-in [:nodes 1 :outcome] :failed) (assoc-in [:nodes 1 :interval :end-ns] "20"))
        after (-> before (assoc-in [:nodes 1 :interval :end-ns] "40")
                  (assoc-in [:nodes 3 :interval :end-ns] "20"))
        row (task-row (diff/page (context before after) request) "compile")]
    (is (= :changed (:change row)))
    (is (= [:association] (:dimensions row)))
    (is (= (get-in row [:before :semantics]) (get-in row [:after :semantics])))
    (is (= (get-in row [:before :durations]) (get-in row [:after :durations])))
    (is (not= (get-in row [:before :association]) (get-in row [:after :association])))))

(deftest labels-and-immediate-parent-semantics-are-observable
  (doseq [after [(assoc-in (f/example) [:nodes 1 :label] "Renamed task")
                 (assoc-in (f/example) [:nodes 0 :key] "other-chain")]]
    (let [row (task-row (diff/page (context (f/example) after) request) "compile")]
      (is (= [:semantics] (:dimensions row))))))

(deftest a-shared-key-does-not-collapse-distinct-node-kinds
  (let [after (-> (f/example) (assoc-in [:nodes 2 :key] "compile")
                  (assoc-in [:nodes 2 :kind] :test) (assoc-in [:run :expected-keys] ["compile"]))
        rows (:rows (diff/page (context (f/example) after) request))]
    (is (= 2 (count (filter #(= "compile" (get-in % [:identity :key])) rows))))
    (is (= :after-only (:change (first (filter #(= {:key "compile" :kind :test} (:identity %)) rows)))))))

(deftest expected-but-unobserved-gates-and-incomplete-captures-do-not-become-removals
  (let [before (f/example)
        after (-> before (assoc-in [:run :complete?] false)
                  (update :nodes #(vec (remove (fn [node] (= "check" (:id node))) %)))
                  (assoc :edges []))
        result (diff/page (context before after) request)
        row (task-row result "check")]
    (is (false? (:after-complete? result)))
    (is (true? (get-in row [:after :declared?])))
    (is (= 0 (get-in row [:after :observations])))
    (is (= 0 (get-in row [:after :outcomes :cached])))
    (is (= :changed (:change row)))
    (is (= [:semantics :durations] (:dimensions row)))))

(deftest declaration-with-no-observations-still-appears-in-an-empty-capture
  (let [capture (-> (f/example) (assoc-in [:run :complete?] false)
                    (assoc-in [:run :end-ns] nil) (assoc :nodes [] :edges []))
        extended (update-in capture [:run :expected-keys] conj "extra")
        result (diff/page (context capture extended) request)
        extra (task-row result "extra")]
    (is (= :after-only (:change extra)))
    (is (true? (get-in extra [:after :declared?])))
    (is (= 0 (get-in extra [:after :observations])))
    (is (= [] (get-in extra [:after :examples])))
    (is (false? (:before-complete? result)))
    (is (false? (:after-complete? result)))))

(deftest a-missing-task-on-one-side-retains-both-run-identities
  (let [after (-> (f/example) (assoc-in [:nodes 1 :key] "other-compile")
                  (assoc-in [:run :expected-keys] ["other-compile" "check"]))
        result (diff/page (context (f/example) after) request)]
    (is (= :before-only (:change (task-row result "compile"))))
    (is (= :after-only (:change (task-row result "other-compile"))))
    (is (nil? (:after (task-row result "compile"))))
    (is (true? (:before-complete? result)))
    (is (true? (:after-complete? result)))))

(deftest small-pages-enumerate-the-merged-inventory-with-no-duplicate-or-missing-key
  (let [after (-> (f/example) (assoc-in [:nodes 1 :key] "other-compile")
                  (assoc-in [:run :expected-keys] ["other-compile" "check"]))
        ctx (context (f/example) after)
        full (diff/page ctx request)
        input (assoc-in request [:budget :rows] 1)
        rows (loop [input input result [] remaining 8]
               (when (zero? remaining) (throw (ex-info "Pagination did not finish" {})))
               (let [page (diff/page ctx input) result (into result (:rows page))]
                 (if-let [cursor (:next page)]
                   (recur (assoc input :cursor cursor) result (dec remaining)) result)))]
    (is (= (:rows full) rows))
    (is (= ["chain" "check" "compile" "other-compile"] (mapv #(get-in % [:identity :key]) rows)))))

(deftest changed-only-filters-still-spend-visits-on-unchanged-keys
  (let [ctx (context (f/example) (f/example))
        input (-> request (assoc-in [:select :changed-only?] true) (assoc-in [:budget :visits] 1))
        result (diff/page ctx input)]
    (is (= [] (:rows result)))
    (is (= 1 (:visited result)))
    (is (= :visit-limit (:stop result)))
    (is (= 1 (get-in result [:next :before-offset])))
    (is (= 1 (get-in result [:next :after-offset])))))

(deftest comparison-byte-budget-includes-envelope-and-preserves-retry-position
  (let [ctx (context (f/example) (f/example))
        small (assoc-in request [:budget :bytes] 1024)
        result (diff/page ctx small)]
    (is (= :byte-limit (:stop result)))
    (is (= [] (:rows result)))
    (is (= 0 (get-in result [:next :before-offset])))
    (is (= 0 (get-in result [:next :after-offset])))
    (is (<= (canonical/utf8-size (canonical/encode result 65536)) 1024))
    (is (= "chain" (get-in (diff/page ctx (assoc request :cursor (:next result))) [:rows 0 :identity :key])))))

(deftest continuations-bind-both-artifacts-direction-selection-and-merge-frontier
  (let [before (f/example) after (assoc-in before [:nodes 1 :outcome] :failed)
        ctx (context before after) input (assoc-in request [:budget :rows] 1)
        cursor (:next (diff/page ctx input))
        changed (assoc-in after [:nodes 0 :label] "Different")]
    (doseq [[ctx input cursor]
            [[(context before changed) input cursor]
             [(context changed after) input cursor]
             [(context after before) input cursor]
             [ctx (assoc-in input [:select :changed-only?] true) cursor]
             [ctx input (assoc cursor :before-offset 2 :after-offset 1)]
             [ctx input (assoc cursor :before-offset 4 :after-offset 4)]]]
      (is (= :invalid-cursor (error-code #(diff/page ctx (assoc input :cursor cursor))))))))

(deftest comparison-contracts-are-closed-and-bounded
  (is (false? (m/validate ic/DiffSelection {:op :tasks :changed-only? false :eval '(+ 1 2)})))
  (is (false? (m/validate ic/DiffSelection {:op :tasks :changed-only? false :where {:resource "host"}})))
  (is (false? (m/validate ic/DiffRequest (assoc-in request [:budget :visits] 0))))
  (is (false? (m/validate ic/DiffPage (assoc (diff/page (context (f/example) (f/example)) request)
                                             :regression? false)))))

(deftest grouped-outcome-differences-agree-with-an-independent-frequency-oracle
  (let [property
        (prop/for-all [before-states (gen/vector (gen/elements [:passed :failed :error :cancelled]) 1 20)
                       after-states (gen/vector (gen/elements [:passed :failed :error :cancelled]) 1 20)]
                      (let [make-graph (fn [states]
                                         (assoc (f/example) :nodes
                                                (into [(get-in (f/example) [:nodes 0]) (get-in (f/example) [:nodes 2])]
                                                      (map-indexed (fn [i outcome]
                                                                     (assoc (get-in (f/example) [:nodes 1])
                                                                            :id (str "invocation-" i) :outcome outcome)) states))
                                                :edges []))
                            result (task-row (diff/page (context (make-graph before-states) (make-graph after-states)) request) "compile")
                            before-counts (frequencies before-states) after-counts (frequencies after-states)]
                        (and (= (count before-states) (get-in result [:before :observations]))
                             (= (count after-states) (get-in result [:after :observations]))
                             (= before-counts (into {} (filter (comp pos? val)) (get-in result [:before :outcomes])))
                             (= after-counts (into {} (filter (comp pos? val)) (get-in result [:after :outcomes])))
                             (= (= before-counts after-counts) (= :unchanged (:change result))))))
        result (check/quick-check 200 property :seed 141421)]
    (is (:pass? result) (pr-str result))))
