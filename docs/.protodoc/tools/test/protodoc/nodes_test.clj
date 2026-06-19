(ns protodoc.nodes-test
  "Hermetic tests for the node codegen IR generator (in-memory db fixtures —
   no proto-db.edn / filesystem dependency)."
  (:require [clojure.test :refer [deftest is testing]]
            [protodoc.nodes :as nodes]))

(def ^:private clahe-msg
  "A SetClaheLevel-shaped slider command leaf (the proven golden node)."
  {:id "cmd.DayCamera.SetClaheLevel"
   :name "SetClaheLevel"
   :fields [{:number 1 :name "value" :type :double
             :constraints {:lte 1 :gte 0}
             :interaction {:semantic-type :normalized
                           :presets [0.0 0.25 0.5 0.75 1.0]}}]
   :interaction {:ui-pattern :slider
                 :related-state ["ser.JonGuiDataCameraDay"]}})

(deftest name-derivation-helpers
  (is (= "camera_day" (nodes/camel->snake "CameraDay")))
  (is (= "clahe_level" (nodes/camel->snake "ClaheLevel")))
  (is (= ["Set" "Clahe" "Level"] (nodes/camel-words "SetClaheLevel")))
  (is (= "SetClaheLevel" (nodes/leaf "cmd.DayCamera.SetClaheLevel")))
  (is (= "JonGuiDataCameraDay" (nodes/leaf "ser.JonGuiDataCameraDay"))))

(deftest scale-for-semantic-type
  (testing ":normalized maps to per-mille; unmapped types return nil"
    (is (= 1000 (nodes/scale-for :normalized)))
    (is (nil? (nodes/scale-for :angle)))
    (is (nil? (nodes/scale-for :temperature)))))

(deftest classifies-slider-nodes
  (is (nodes/slider-node? clahe-msg))
  (testing "non-slider ui-pattern is rejected"
    (is (not (nodes/slider-node? (assoc-in clahe-msg [:interaction :ui-pattern] :toggle)))))
  (testing "non-normalized field is rejected (its scale isn't derivable yet)"
    (is (not (nodes/slider-node?
              (assoc-in clahe-msg [:fields 0 :interaction :semantic-type] :angle)))))
  (testing "multi-field command is rejected"
    (is (not (nodes/slider-node?
              (update clahe-msg :fields conj {:number 2 :name "x" :type :double}))))))

(deftest derives-clahe-slider-bindings
  (testing "the behavioral bindings match the hand-authored slice golden"
    (let [n (nodes/derive-slider-node clahe-msg)]
      (is (= :slider (:kind n)))
      (is (= "camera_day.clahe_level" (:state-field-path n)))
      (is (= 1000 (:scale n)))
      (is (= 0 (:min-value n)))
      (is (= 1000 (:max-value n)))
      (is (= [0 250 500 750 1000] (:presets n)))
      (is (= "cmd.DayCamera.SetClaheLevel" (:command-id n)))
      (is (= "Clahe Level" (:title n))))))

(deftest derives-from-exclusive-bounds
  (testing "{:gt :lt} bounds are accepted like {:gte :lte}"
    (let [n (nodes/derive-slider-node
             (assoc-in clahe-msg [:fields 0 :constraints] {:gt 0 :lt 1}))]
      (is (= 0 (:min-value n)))
      (is (= 1000 (:max-value n))))))

(deftest skips-undeerivable-slider-loudly
  (testing "a slider with no min/max bounds is skipped-with-reason, not emitted"
    (let [no-bounds (update-in clahe-msg [:fields 0] dissoc :constraints)
          {:keys [nodes skipped]} (nodes/derive-nodes {:messages {"x" no-bounds}})]
      (is (empty? nodes))
      (is (= 1 (count skipped)))
      (is (= "cmd.DayCamera.SetClaheLevel" (:id (first skipped))))
      (is (re-find #"min/max" (:reason (first skipped)))))))

(deftest derive-nodes-sorts-and-counts
  (let [db {:messages {"a" clahe-msg
                       "b" {:id "ser.Foo" :name "Foo" :fields []
                            :interaction {:ui-pattern :indicator}}}}
        {:keys [nodes non-slider-count]} (nodes/derive-nodes db)]
    (is (= 1 (count nodes)))
    (is (= 1 non-slider-count))))
