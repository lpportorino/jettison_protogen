(ns protodoc.extract-test
  "Tests for markdown extraction (roundtrip preservation)."
  (:require [clojure.test :refer [deftest testing is]]
            [protodoc.extract :as extract]))

(def sample-markdown
  "---
id: cmd.DayCamera.SetIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera iris/aperture position. Controls light intake.

See also: [[JonGuiDataCameraDay]] (irisPos field)

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |

## Field Notes

### value (#1)

Iris aperture position as normalized value (0.0 = closed, 1.0 = fully open).

**UI:** Slider in `jonIrisPalette` with presets

### unused (#2)

This is an unused field note.
")

(deftest extract-from-file-test
  (testing "Extracts content from markdown file"
    (let [temp-file (java.io.File/createTempFile "test-doc" ".md")]
      (try
        (spit temp-file sample-markdown)
        (let [result (extract/extract-from-file (.getPath temp-file))]
          (is (= "cmd.DayCamera.SetIris" (:id result)))
          (is (string? (:description result)))
          (is (clojure.string/includes? (:description result) "Sets the day camera"))
          (is (clojure.string/includes? (:description result) "[[JonGuiDataCameraDay]]"))
          (is (map? (:field-notes result)))
          (is (contains? (:field-notes result) 1))
          (is (clojure.string/includes? (get-in result [:field-notes 1 :description]) "normalized value")))
        (finally
          (.delete temp-file))))))

(deftest extract-preserves-raw-markdown-test
  (testing "Preserves wikilinks and formatting"
    (let [temp-file (java.io.File/createTempFile "test-doc" ".md")]
      (try
        (spit temp-file sample-markdown)
        (let [result (extract/extract-from-file (.getPath temp-file))]
          ;; Wikilinks should be preserved
          (is (clojure.string/includes? (:description result) "[[JonGuiDataCameraDay]]"))
          ;; Bold markers should be preserved
          (is (clojure.string/includes? (get-in result [:field-notes 1 :description]) "**UI:**")))
        (finally
          (.delete temp-file))))))

(deftest extract-handles-missing-description-test
  (testing "Handles files without description section"
    (let [md "---
id: test.NoDesc
proto: test.proto
package: test
type: message
---

# NoDesc

## Fields

| # | Field | Type |
|---|-------|------|
| 1 | value | int |
"
          temp-file (java.io.File/createTempFile "test-no-desc" ".md")]
      (try
        (spit temp-file md)
        (let [result (extract/extract-from-file (.getPath temp-file))]
          (is (= "test.NoDesc" (:id result)))
          (is (nil? (:description result))))
        (finally
          (.delete temp-file))))))

(deftest merge-user-content-test
  (testing "Merges user content into IR"
    (let [db {:messages {"cmd.Test" {:id "cmd.Test"
                                     :name "Test"
                                     :package "cmd"
                                     :source "test.proto"
                                     :fields [{:number 1 :name "value" :type :double}
                                              {:number 2 :name "mode" :type :enum}]}}
              :enums {}
              :search-index {}}
          user-content {"cmd.Test" {:description "User-written description"
                                    :field-notes {1 "Field 1 docs"}}}
          merged (extract/merge-user-content db user-content)
          msg (get-in merged [:messages "cmd.Test"])]
      (is (= "User-written description" (:description msg)))
      (is (= "Field 1 docs" (:description (first (:fields msg)))))
      (is (nil? (:description (second (:fields msg))))))))

(deftest preserve-user-content-test
  (testing "Preserves user content when syncing IR"
    (let [old-db {:messages {"cmd.Test" {:id "cmd.Test"
                                         :name "Test"
                                         :package "cmd"
                                         :source "test.proto"
                                         :description "Old description"
                                         :fields [{:number 1 :name "value" :type :double
                                                   :description "Old field desc"}]}}
                  :enums {}
                  :search-index {}}
          new-db {:messages {"cmd.Test" {:id "cmd.Test"
                                         :name "Test"
                                         :package "cmd"
                                         :source "test.proto"
                                         ;; New IR has no descriptions
                                         :fields [{:number 1 :name "value" :type :double}
                                                  {:number 2 :name "new_field" :type :bool}]}}
                  :enums {}
                  :search-index {}}
          preserved (extract/preserve-user-content new-db old-db)
          msg (get-in preserved [:messages "cmd.Test"])]
      ;; Message description should be preserved
      (is (= "Old description" (:description msg)))
      ;; Field 1 description should be preserved
      (is (= "Old field desc" (:description (first (:fields msg)))))
      ;; New field 2 should have no description
      (is (nil? (:description (second (:fields msg))))))))

;; ============================================================================
;; Interaction Metadata Extraction Tests
;; ============================================================================

(def sample-markdown-with-interaction
  "---
id: cmd.DayCamera.SetIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Sets the day camera iris/aperture position.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :pending-timeout

### Purpose

Controls the physical iris aperture of the day camera.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Related Commands

- [[cmd.DayCamera.SetAutoIris]]

### Preconditions

- Camera must be started
- Auto-iris must be disabled

### Implementation Notes

Expect state confirmation within ~500ms.

## Field Notes

### value (#1)

Normalized iris position (0.0 = closed, 1.0 = fully open).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Display Format:** `{value * 100}%`
- **Presets:** 0, 0.25, 0.5, 0.75, 1.0
")

(deftest extract-interaction-metadata-test
  (testing "Extracts message-level interaction metadata"
    (let [temp-file (java.io.File/createTempFile "test-interaction" ".md")]
      (try
        (spit temp-file sample-markdown-with-interaction)
        (let [result (extract/extract-from-file (.getPath temp-file))
              interaction (:interaction result)]
          (is (some? interaction))
          (is (= :actuator (:category interaction)))
          (is (= :slider-with-presets (:ui-pattern interaction)))
          (is (= :pending-timeout (:feedback interaction)))
          (is (clojure.string/includes? (:purpose interaction) "physical iris aperture"))
          (is (= ["ser.JonGuiDataCameraDay"] (:related-state interaction)))
          (is (= ["cmd.DayCamera.SetAutoIris"] (:related-commands interaction)))
          (is (= ["Camera must be started" "Auto-iris must be disabled"]
                 (:preconditions interaction)))
          (is (clojure.string/includes? (:notes interaction) "500ms")))
        (finally
          (.delete temp-file))))))

(deftest extract-field-interaction-metadata-test
  (testing "Extracts field-level interaction metadata"
    (let [temp-file (java.io.File/createTempFile "test-field-interaction" ".md")]
      (try
        (spit temp-file sample-markdown-with-interaction)
        (let [result (extract/extract-from-file (.getPath temp-file))
              field-note (get-in result [:field-notes 1])]
          (is (some? field-note))
          (is (clojure.string/includes? (:description field-note) "Normalized iris position"))
          (let [field-interaction (:interaction field-note)]
            (is (some? field-interaction))
            (is (= :normalized (:semantic-type field-interaction)))
            (is (= "%" (:unit field-interaction)))
            (is (= "{value * 100}%" (:display-format field-interaction)))
            (is (= [0.0 0.25 0.5 0.75 1.0] (:presets field-interaction)))))
        (finally
          (.delete temp-file))))))

(deftest merge-interaction-content-test
  (testing "Merges interaction metadata into IR"
    (let [db {:messages {"cmd.Test" {:id "cmd.Test"
                                     :name "Test"
                                     :package "cmd"
                                     :source "test.proto"
                                     :fields [{:number 1 :name "value" :type :double}]}}
              :enums {}
              :search-index {}}
          user-content {"cmd.Test" {:description "User description"
                                    :interaction {:category :actuator
                                                  :ui-pattern :slider}
                                    :field-notes {1 {:description "Field desc"
                                                     :interaction {:semantic-type :normalized
                                                                   :unit "%"}}}}}
          merged (extract/merge-user-content db user-content)
          msg (get-in merged [:messages "cmd.Test"])]
      ;; Message-level
      (is (= "User description" (:description msg)))
      (is (= :actuator (get-in msg [:interaction :category])))
      (is (= :slider (get-in msg [:interaction :ui-pattern])))
      ;; Field-level
      (is (= "Field desc" (:description (first (:fields msg)))))
      (is (= :normalized (get-in (first (:fields msg)) [:interaction :semantic-type])))
      (is (= "%" (get-in (first (:fields msg)) [:interaction :unit]))))))

(deftest preserve-interaction-content-test
  (testing "Preserves interaction metadata when syncing IR"
    (let [old-db {:messages {"cmd.Test" {:id "cmd.Test"
                                         :name "Test"
                                         :package "cmd"
                                         :source "test.proto"
                                         :description "Old description"
                                         :interaction {:category :actuator
                                                       :ui-pattern :slider-with-presets}
                                         :fields [{:number 1 :name "value" :type :double
                                                   :description "Old field desc"
                                                   :interaction {:semantic-type :normalized}}]}}
                  :enums {}
                  :search-index {}}
          new-db {:messages {"cmd.Test" {:id "cmd.Test"
                                         :name "Test"
                                         :package "cmd"
                                         :source "test.proto"
                                         :fields [{:number 1 :name "value" :type :double}
                                                  {:number 2 :name "new_field" :type :bool}]}}
                  :enums {}
                  :search-index {}}
          preserved (extract/preserve-user-content new-db old-db)
          msg (get-in preserved [:messages "cmd.Test"])]
      ;; Message-level interaction should be preserved
      (is (= :actuator (get-in msg [:interaction :category])))
      (is (= :slider-with-presets (get-in msg [:interaction :ui-pattern])))
      ;; Field 1 interaction should be preserved
      (is (= :normalized (get-in (first (:fields msg)) [:interaction :semantic-type])))
      ;; New field 2 should have no interaction
      (is (nil? (get-in (second (:fields msg)) [:interaction]))))))
