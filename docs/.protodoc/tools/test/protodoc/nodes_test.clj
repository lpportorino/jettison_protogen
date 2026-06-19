(ns protodoc.nodes-test
  "Hermetic tests for the node codegen IR generator (in-memory db fixtures —
   no proto-db.edn / filesystem dependency)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; A minimal cmd oneof graph: Root.payload(day_camera) → DayCamera.Root.cmd(
;; set_clahe_level) → SetClaheLevel. Proves prost paths come from the ONEOF
;; FIELD names, not the message ids.
(def ^:private cmd-graph-db
  {:messages
   {"cmd.Root"
    {:id "cmd.Root" :name "Root"
     :fields [{:number 1 :name "protocol_version" :type :uint32}
              {:number 13 :name "day_camera" :type :message :type-ref "cmd.DayCamera.Root"}]}
    "cmd.DayCamera.Root"
    {:id "cmd.DayCamera.Root" :name "Root" :package "cmd.DayCamera"
     :fields [{:number 16 :name "set_clahe_level" :type :message
               :type-ref "cmd.DayCamera.SetClaheLevel"}
              {:number 17 :name "start" :type :message :type-ref "cmd.DayCamera.Start"}]}
    "cmd.DayCamera.SetClaheLevel" clahe-msg
    "cmd.DayCamera.Start" {:id "cmd.DayCamera.Start" :name "Start" :fields []}}})

(deftest pairs-enable-disable-toggles
  (let [db {:messages
            {"cmd.CV.RecognitionModeEnable"
             {:id "cmd.CV.RecognitionModeEnable" :name "RecognitionModeEnable"
              :fields [] :interaction {:ui-pattern :toggle}}
             "cmd.CV.RecognitionModeDisable"
             {:id "cmd.CV.RecognitionModeDisable" :name "RecognitionModeDisable"
              :fields [] :interaction {:ui-pattern :toggle}}
             ;; an Enable with no matching Disable → NOT a node (loud no-op)
             "cmd.CV.LonelyEnable"
             {:id "cmd.CV.LonelyEnable" :name "LonelyEnable"
              :fields [] :interaction {:ui-pattern :toggle}}}}
        nodes (nodes/derive-toggle-nodes db)]
    (is (= 1 (count nodes)) "only the paired toggle, not the lonely Enable")
    (is (= {:id "toggle.CV.RecognitionMode"
            :kind :toggle
            :title "Recognition Mode"
            :command-on "cmd.CV.RecognitionModeEnable"
            :command-off "cmd.CV.RecognitionModeDisable"}
           (first nodes))))
  (testing "Enable/Disable as a PREFIX pairs too (EnableX / DisableX)"
    (let [db {:messages
              {"cmd.System.EnableGeodesicMode"
               {:id "cmd.System.EnableGeodesicMode" :name "EnableGeodesicMode"
                :fields [] :interaction {:ui-pattern :toggle}}
               "cmd.System.DisableGeodesicMode"
               {:id "cmd.System.DisableGeodesicMode" :name "DisableGeodesicMode"
                :fields [] :interaction {:ui-pattern :toggle}}}}
          nodes (nodes/derive-toggle-nodes db)]
      (is (= 1 (count nodes)))
      (is (= "toggle.System.GeodesicMode" (:id (first nodes))))
      (is (= "cmd.System.EnableGeodesicMode" (:command-on (first nodes)))))))

(deftest pairs-plus-minus-steppers
  (let [db {:messages
            {"cmd.HeatCamera.FocusStepPlus"
             {:id "cmd.HeatCamera.FocusStepPlus" :name "FocusStepPlus"
              :fields [] :interaction {:ui-pattern :stepper}}
             "cmd.HeatCamera.FocusStepMinus"
             {:id "cmd.HeatCamera.FocusStepMinus" :name "FocusStepMinus"
              :fields [] :interaction {:ui-pattern :stepper}}
             ;; a lone Next with no Prev → not paired
             "cmd.HeatCamera.NextZoomTablePos"
             {:id "cmd.HeatCamera.NextZoomTablePos" :name "NextZoomTablePos"
              :fields [] :interaction {:ui-pattern :stepper}}}}
        nodes (nodes/derive-stepper-nodes db)]
    (is (= 1 (count nodes)) "only the +/- pair, not the lone Next")
    (is (= {:id "stepper.HeatCamera.FocusStep"
            :kind :stepper
            :title "Focus Step"
            :command-increment "cmd.HeatCamera.FocusStepPlus"
            :command-decrement "cmd.HeatCamera.FocusStepMinus"}
           (first nodes)))))

(deftest derives-shift-steppers
  (let [db {:messages
            {"cmd.Root"
             {:id "cmd.Root" :name "Root"
              :fields [{:number 1 :name "system" :type :message
                        :type-ref "cmd.System.Root"}]}
             "cmd.System.Root"
             {:id "cmd.System.Root" :name "Root" :package "cmd.System"
              :fields [{:number 1 :name "step_day" :type :message
                        :type-ref "cmd.System.StepDay"}]}
             "cmd.System.StepDay"
             {:id "cmd.System.StepDay" :name "StepDay"
              :fields [{:name "offset" :type :int32}]
              :interaction {:ui-pattern :stepper}}
             ;; a single-int32 :stepper NOT wired into the oneof → not buildable → no node
             "cmd.System.Orphan"
             {:id "cmd.System.Orphan" :name "Orphan"
              :fields [{:name "value" :type :int32}]
              :interaction {:ui-pattern :stepper}}}}
        nodes (nodes/derive-shift-stepper-nodes db)]
    (is (= 1 (count nodes)) "only the oneof-reachable single-int32 stepper")
    (is (= {:id "cmd.System.StepDay"
            :kind :shift-stepper
            :title "Step Day"
            :command-id "cmd.System.StepDay"
            :step 1}
           (first nodes)))))

(deftest derives-bool-toggles
  (let [db {:messages
            {"cmd.Root"
             {:id "cmd.Root" :name "Root"
              :fields [{:number 1 :name "day_camera" :type :message
                        :type-ref "cmd.DayCamera.Root"}]}
             "cmd.DayCamera.Root"
             {:id "cmd.DayCamera.Root" :name "Root" :package "cmd.DayCamera"
              :fields [{:number 1 :name "set_auto_gain" :type :message
                        :type-ref "cmd.DayCamera.SetAutoGain"}]}
             "cmd.DayCamera.SetAutoGain"
             {:id "cmd.DayCamera.SetAutoGain" :name "SetAutoGain"
              :fields [{:name "value" :type :bool}]
              :interaction {:ui-pattern :toggle}}}}
        nodes (nodes/derive-bool-toggle-nodes db)]
    (is (= 1 (count nodes)) "the oneof-reachable single-bool toggle")
    (is (= {:id "cmd.DayCamera.SetAutoGain"
            :kind :bool-toggle
            :title "Auto Gain"
            :command-id "cmd.DayCamera.SetAutoGain"}
           (first nodes)))))

(deftest derives-enum-picker-options
  (let [db {:enums {"ser.FxMode"
                    {:values [{:number 0 :name "JON_FX_DEFAULT"}
                              {:number 1 :name "JON_FX_A"}
                              {:number 2 :name "JON_FX_B"}]}}
            :messages
            {"cmd.DayCamera.SetFxMode"
             {:id "cmd.DayCamera.SetFxMode" :name "SetFxMode"
              :fields [{:name "mode" :type :enum :type-ref "ser.FxMode"}]
              :interaction {:ui-pattern :enum-picker}}}}
        nodes (nodes/derive-enum-picker-nodes db)]
    (is (= 1 (count nodes)))
    (is (= {:id "cmd.DayCamera.SetFxMode"
            :kind :enum-picker
            :title "Fx Mode"
            :command-id "cmd.DayCamera.SetFxMode"
            :options [{:label "DEFAULT" :value 0}
                      {:label "A" :value 1}
                      {:label "B" :value 2}]}
           (first nodes))
        "options strip the common enum prefix; index→value preserved")))

(deftest cmd-builder-traverses-oneof-graph
  (testing "set-value arm-specs resolve prost paths from the oneof field names"
    (let [arms (nodes/set-value-command-arms cmd-graph-db)]
      (is (= 1 (count arms)) "only the single-double-field leaf, not parameterless Start")
      (is (= {:command-id "cmd.DayCamera.SetClaheLevel"
              :payload-variant "DayCamera"
              :module "day_camera"
              :cmd-variant "SetClaheLevel"
              :struct "SetClaheLevel"
              :field "value"}
             (first arms)))))
  (testing "action arm-specs pick the parameterless leaf (Start), no value field"
    (let [arms (nodes/action-command-arms cmd-graph-db)]
      (is (= 1 (count arms)))
      (is (= {:command-id "cmd.DayCamera.Start"
              :payload-variant "DayCamera"
              :module "day_camera"
              :cmd-variant "Start"
              :struct "Start"
              :field nil}
             (first arms)))))
  (testing "emitted Rust has both typed builders (no runtime reflection)"
    (let [{:keys [rust set-value-count action-count]} (nodes/cmd-builders-rust cmd-graph-db)]
      (is (= 1 set-value-count))
      (is (= 1 action-count))
      (is (str/includes? rust "pub fn build_set_value_command"))
      (is (str/includes? rust "pub fn build_action_command"))
      (is (str/includes?
           rust
           "cmd::day_camera::root::Cmd::SetClaheLevel(cmd::day_camera::SetClaheLevel { value: value })"))
      (is (str/includes?
           rust
           "cmd::day_camera::root::Cmd::Start(cmd::day_camera::Start {})")))))
