(ns devcards.deadzone-test
  "Pure contract tests for the ordered dead-zone rule. The battery's
   real-fixture/real-wasm proof lives in the `deadzone-canary-prebuilt`
   target; these pin the reducer and registry seams cheaply."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.deadzone :as deadzone]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]))

(def ^:private table
  {:types {"lv_obj" {:interactive? true :role :structural}
           "lv_button" {:interactive? true :role :interactive}
           "lv_label" {:interactive? false :role :text}}})

(defn- root-with
  [children]
  {:type "lv_obj" :coords [0 0 399 199] :children children})

(defn- button
  [uid disabled]
  (cond-> {:type "lv_button"
           :uid uid
           :coords (if (= uid 1) [0 0 199 79] [160 0 359 79])
           :children []}
    disabled (assoc :disabled true)))

(def ^:private disabled-over-enabled
  (root-with [(button 1 false) (button 2 true)]))

(def ^:private enabled-over-disabled
  (root-with [(button 1 true) (button 2 false)]))

(defn- sibling-findings
  [tree]
  (deadzone/sibling-findings
   {:card-id "c"
    :nodes (invariants/annotate-tree tree)
    :classes table
    :thresholds {:gap-px 0}}))

(defn- containment-findings
  [tree]
  (deadzone/containment-findings
   {:card-id "c"
    :nodes (invariants/annotate-tree tree)
    :classes table
    :thresholds {:gap-px 0}}))

(defn- invariants-of
  [findings-]
  (set (map :invariant findings-)))

(deftest reverse-child-order-decides-the-disabled-winner
  (testing "ARM 1 hazard: the later disabled sibling is reached first"
    (let [fs (sibling-findings disabled-over-enabled)]
      (is (= #{:disabled-dead-zone} (invariants-of fs)))
      (is (= "lv_button#2 OVER lv_button#1" (:node (first fs))))))
  (testing "ARM 1 inverse: the later enabled sibling wins, so disabled
            underneath is not the ordered defect"
    (let [control (sibling-findings disabled-over-enabled)
          fs (sibling-findings enabled-over-disabled)]
      (is (seq control)
          "CONTROL: the same input class can produce the ARM-1 finding")
      (is (empty? fs)))))

(def ^:private disabled-child-over-enabled-ancestor
  (root-with
   [{:type "lv_obj"
     :uid 10
     :coords [20 20 299 159]
     :children
     [{:type "lv_button"
       :uid 11
       :disabled true
       :coords [40 40 239 119]
       :children []}]}]))

(deftest containment-finds-disabled-child-over-enabled-ancestor
  (let [tree
        disabled-child-over-enabled-ancestor
        measured (containment-findings tree)]
    (testing "the related pair is ordered without asking winner for a path
              divergence: LVGL searches the child before its ancestor"
      (is (= #{:disabled-covers-ancestor} (invariants-of measured)))
      (is (= "lv_button#11 INSIDE lv_obj#10"
             (:node (first measured)))))))

(deftest pointer-transparent-scaffolding-removes-the-containment-hazard
  (let [hazard disabled-child-over-enabled-ancestor
        constructed
        (-> hazard
            (assoc :clickable false)
            (assoc-in [:children 0 :clickable] false))]
    (testing "CONTROL: the same geometry is hazardous while the wrapper takes
              the pointer"
      (is (= #{:disabled-covers-ancestor}
             (invariants-of (containment-findings hazard)))))
    (testing "clearing CLICKABLE on both the screen and its scaffolding wrapper
              leaves no enabled pointer-taking ancestor for the child to cover"
      (is (empty? (containment-findings constructed))))))

(deftest overflow-visible-does-not-hide-a-farther-covered-ancestor
  (let [tree
        (root-with
         [{:type "lv_obj"
           :uid 9
           :coords [0 0 399 199]
           :children
           [{:type "lv_obj"
             :uid 10
             :coords [0 0 299 159]
             :children
             [{:type "lv_obj"
               :uid 12
               :coords [20 20 99 99]
               :descend_gate [0 0 199 159]
               :children
               [{:type "lv_button"
                 :uid 11
                 :disabled true
                 :coords [120 40 179 79]
                 :children []}]}]}]}])
        fs (containment-findings tree)]
    (testing "the nearest clickable ancestor has no shared hit pixel, but its
              OVERFLOW_VISIBLE descent gate makes the child reachable"
      (is (not-any? #(= "lv_button#11 INSIDE lv_obj#12" (:node %)) fs)))
    (testing "selection continues outward to the nearest enabled ancestor that
              the disabled child actually covers"
      (is (some #(= "lv_button#11 INSIDE lv_obj#10" (:node %)) fs)))))

(deftest armed-containment-clause-canary
  (let [tree disabled-child-over-enabled-ancestor
        registry
        (:live
         (findings/card-findings
          {:card-id "c"
           :tree tree
           :classes table
           :thresholds {:deadzone/gap-px 0}
           :producers [deadzone/producer]}))
        atomic (lanes/atomic-findings "c" :judged tree)
        composition
        (lanes/composition-findings
         "c"
         tree
         {:dark {:commands [] :reports [] :events []}
          :light {:commands [] :reports [] :events []}})
        armed? (fn [fs]
                 (contains? (invariants-of fs)
                            :disabled-covers-ancestor))]
    (testing "the reducer, registry, and both exact lane entry points arm ARM 2"
      (is (= [true true true]
             (mapv armed? [registry atomic composition]))))
    (testing "CONTROL: enabling the same child removes this disabled-state
              clause without changing its containment geometry"
      (is (empty?
           (containment-findings
            (assoc-in tree [:children 0 :children 0 :disabled] false)))))))

(deftest winner-refuses-an-ancestor-related-pair
  (let [[ancestor child]
        (invariants/annotate-tree
         {:type "lv_obj"
          :coords [0 0 99 99]
          :children
          [{:type "lv_button" :coords [10 10 89 89] :children []}]})]
    (is (thrown? Exception (deadzone/winner ancestor child)))))

(deftest an-unclassified-node-is-a-finding-not-a-skip
  (let [unknown
        (root-with
         [(button 1 false)
          {:type "lv_mystery"
           :uid 2
           :coords [160 0 359 79]
           :disabled true
           :children []}])
        fs (sibling-findings unknown)]
    (is (= #{:unclassified-type} (invariants-of fs)))
    (testing "CONTROL: classifying the same geometry makes the ordered
              hazard judgeable and non-empty"
      (let [classes
            {:types
             (assoc (:types table)
                    "lv_mystery"
                    {:interactive? true :role :interactive})}
            judged
            (deadzone/sibling-findings
             {:card-id "c"
              :nodes (invariants/annotate-tree unknown)
              :classes classes
              :thresholds {:gap-px 0}})]
        (is (= #{:disabled-dead-zone} (invariants-of judged)))))))

(deftest producer-runs-through-the-registry-and-the-live-lanes
  (testing "the reusable producer resolves its namespaced threshold"
    (let [res
          (findings/card-findings
           {:card-id "c"
            :tree disabled-over-enabled
            :classes table
            :thresholds {:deadzone/gap-px 0}
            :producers [deadzone/producer]})]
      (is (contains? (invariants-of (:live res))
                     :disabled-dead-zone))))
  (testing "the exact atomic entry point carries ARM 1"
    (is (contains?
         (invariants-of
          (lanes/atomic-findings "c" :judged disabled-over-enabled))
         :disabled-dead-zone)))
  (testing "the exact composition entry point carries ARM 1 too"
    (is (contains?
         (invariants-of
          (lanes/composition-findings
           "c"
           disabled-over-enabled
           {:dark {:commands [] :reports [] :events []}
            :light {:commands [] :reports [] :events []}}))
         :disabled-dead-zone))))
