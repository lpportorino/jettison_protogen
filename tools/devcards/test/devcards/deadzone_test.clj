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

(deftest containment-is-measured-but-not-emitted-by-the-producer
  (let [tree
        (root-with
         [{:type "lv_obj"
           :uid 10
           :coords [20 20 299 159]
           :children
           [{:type "lv_button"
             :uid 11
             :disabled true
             :coords [40 40 239 119]
             :children []}]}])
        measured (containment-findings tree)
        armed (sibling-findings tree)]
    (testing "CONTROL: ARM 2 is mechanically present on the same tree"
      (is (= #{:disabled-covers-ancestor} (invariants-of measured)))
      (is (= "lv_button#11 INSIDE lv_obj#10"
             (:node (first measured)))))
    (testing "but the ARM-1 producer is silent, so ARM 2 cannot block"
      (is (empty? armed)))))

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
      (is (= #{:disabled-dead-zone} (invariants-of (:live res))))))
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
