(ns protodoc.render-test
  "Tests for markdown rendering."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [protodoc.render :as render]))

(def sample-message
  {:id "cmd.DayCamera.SetIris"
   :name "SetIris"
   :package "cmd.DayCamera"
   :source "jon_shared_cmd_day_camera.proto"
   :description "Sets iris position."
   :fields [{:number 1 :name "value" :type :double
             :constraints {:gte 0 :lte 1}
             :description "Normalized value 0-1"}]})

(def sample-enum
  {:id "ser.JonGuiDataClientType"
   :name "JonGuiDataClientType"
   :package "ser"
   :source "jon_shared_data_types.proto"
   :description "Client type enum."
   :values [{:number 0 :name "UNSPECIFIED"}
            {:number 1 :name "WEB" :description "Web client"}]})

(deftest render-message-test
  (testing "Renders message to markdown"
    (let [md (render/render-message sample-message)]
      ;; Check frontmatter
      (is (str/includes? md "id: cmd.DayCamera.SetIris"))
      (is (str/includes? md "type: message"))
      ;; Check content
      (is (str/includes? md "# SetIris"))
      (is (str/includes? md "Sets iris position."))
      ;; Check fields table
      (is (str/includes? md "| 1 | value | double |"))
      (is (str/includes? md ">= 0"))
      ;; Check field notes
      (is (str/includes? md "### value (#1)"))
      (is (str/includes? md "Normalized value 0-1")))))

(deftest render-enum-test
  (testing "Renders enum to markdown"
    (let [md (render/render-enum sample-enum)]
      ;; Check frontmatter
      (is (str/includes? md "id: ser.JonGuiDataClientType"))
      (is (str/includes? md "type: enum"))
      ;; Check content
      (is (str/includes? md "# JonGuiDataClientType"))
      (is (str/includes? md "Client type enum."))
      ;; Check values table
      (is (str/includes? md "| 0 | UNSPECIFIED |"))
      (is (str/includes? md "| 1 | WEB | Web client |")))))

(deftest render-message-with-wikilinks-test
  (testing "Renders type refs as wikilinks"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1
                         :name "camera_state"
                         :type :message
                         :type-ref "ser.JonGuiDataCameraDay"}]}
          md (render/render-message msg)]
      (is (str/includes? md "[[ser.JonGuiDataCameraDay]]")))))

(deftest render-index-test
  (testing "Renders index markdown"
    (let [db {:messages {"cmd.Test" {:id "cmd.Test"
                                     :name "Test"
                                     :package "cmd"
                                     :source "test.proto"
                                     :fields [{:number 1 :name "value" :type :double}]}
                         "cmd.Other" {:id "cmd.Other"
                                      :name "Other"
                                      :package "cmd"
                                      :source "test.proto"
                                      :fields []}}
              :enums {"ser.Enum1" {:id "ser.Enum1"
                                   :name "Enum1"
                                   :package "ser"
                                   :source "test.proto"
                                   :values []}}
              :search-index {}}
          md (render/render-index db)]
      ;; Check stats
      (is (str/includes? md "2 messages"))
      (is (str/includes? md "1 enums"))
      ;; Check message links
      (is (str/includes? md "[[cmd.Test|Test]]"))
      (is (str/includes? md "[[cmd.Other|Other]]"))
      ;; Check enum links
      (is (str/includes? md "[[ser.Enum1|Enum1]]")))))

(deftest render-repeated-field-test
  (testing "Marks repeated fields"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1
                         :name "items"
                         :type :string
                         :repeated true}]}
          md (render/render-message msg)]
      (is (str/includes? md "repeated string")))))

(deftest render-no-description-placeholder-test
  (testing "Shows placeholder for missing description"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1 :name "value" :type :double}]}
          md (render/render-message msg)]
      (is (str/includes? md "No description yet")))))

;; ============================================================================
;; Interaction Metadata Rendering Tests
;; ============================================================================

(def sample-message-with-interaction
  {:id "cmd.DayCamera.SetIris"
   :name "SetIris"
   :package "cmd.DayCamera"
   :source "jon_shared_cmd_day_camera.proto"
   :description "Sets iris position."
   :interaction {:category :actuator
                 :ui-pattern :slider-with-presets
                 :feedback :pending-timeout
                 :timeout-ms 2000
                 :purpose "Controls the physical iris aperture."
                 :related-state ["ser.JonGuiDataCameraDay"]
                 :related-commands ["cmd.DayCamera.SetAutoIris"]
                 :preconditions ["Camera must be started" "Auto-iris disabled"]
                 :notes "Expect state confirmation within ~500ms."}
   :fields [{:number 1 :name "value" :type :double
             :constraints {:gte 0 :lte 1}
             :description "Normalized value 0-1"
             :interaction {:semantic-type :normalized
                           :unit "%"
                           :precision 0
                           :display-format "{value * 100}%"
                           :presets [0 0.25 0.5 0.75 1.0]}}]})

(deftest render-message-interaction-test
  (testing "Renders message-level interaction metadata"
    (let [md (render/render-message sample-message-with-interaction)]
      ;; Check section header
      (is (str/includes? md "## Interaction"))
      ;; Check metadata bullets
      (is (str/includes? md "**Category:** :actuator"))
      (is (str/includes? md "**UI Pattern:** :slider-with-presets"))
      (is (str/includes? md "**Feedback:** :pending-timeout"))
      (is (str/includes? md "**Timeout:** 2000ms"))
      ;; Check subsections
      (is (str/includes? md "### Purpose"))
      (is (str/includes? md "physical iris aperture"))
      (is (str/includes? md "### Related State"))
      (is (str/includes? md "[[ser.JonGuiDataCameraDay]]"))
      (is (str/includes? md "### Related Commands"))
      (is (str/includes? md "[[cmd.DayCamera.SetAutoIris]]"))
      (is (str/includes? md "### Preconditions"))
      (is (str/includes? md "Camera must be started"))
      (is (str/includes? md "### Implementation Notes"))
      (is (str/includes? md "500ms")))))

(deftest render-field-interaction-test
  (testing "Renders field-level interaction metadata"
    (let [md (render/render-message sample-message-with-interaction)]
      ;; Check field metadata subsection
      (is (str/includes? md "#### Metadata"))
      (is (str/includes? md "**Semantic Type:** :normalized"))
      (is (str/includes? md "**Unit:** %"))
      (is (str/includes? md "**Precision:** 0"))
      (is (str/includes? md "**Display Format:** `{value * 100}%`"))
      (is (str/includes? md "**Presets:** 0, 0.25, 0.5, 0.75, 1.0")))))

(deftest render-no-interaction-test
  (testing "Does not render interaction section when empty"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1 :name "value" :type :double}]}
          md (render/render-message msg)]
      (is (not (str/includes? md "## Interaction")))
      (is (not (str/includes? md "#### Metadata"))))))

(deftest render-partial-interaction-test
  (testing "Renders only present interaction fields"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :interaction {:category :sensor
                             :ui-pattern :indicator}
               :fields [{:number 1 :name "value" :type :double}]}
          md (render/render-message msg)]
      ;; Should have interaction section
      (is (str/includes? md "## Interaction"))
      (is (str/includes? md "**Category:** :sensor"))
      (is (str/includes? md "**UI Pattern:** :indicator"))
      ;; Should NOT have empty subsections
      (is (not (str/includes? md "### Purpose")))
      (is (not (str/includes? md "### Related State")))
      (is (not (str/includes? md "### Preconditions"))))))
