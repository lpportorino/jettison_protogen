(ns protodoc.roundtrip-test
  "End-to-end roundtrip tests: generate → edit → regenerate → verify preserved."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [protodoc.extract :as extract]
            [protodoc.render :as render]))

(deftest roundtrip-description-test
  (testing "Message description survives roundtrip"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :description "Original description with [[wikilink]]."
               :fields [{:number 1 :name "value" :type :double}]}
          ;; Render to markdown
          md (render/render-message msg)
          ;; Extract back
          temp-file (java.io.File/createTempFile "roundtrip" ".md")]
      (try
        (spit temp-file md)
        (let [extracted (extract/extract-from-file (.getPath temp-file))]
          (is (= (:description msg) (:description extracted))))
        (finally
          (.delete temp-file))))))

(deftest roundtrip-field-notes-test
  (testing "Field notes survive roundtrip"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1 :name "value" :type :double
                         :description "Field **one** description."}
                        {:number 2 :name "mode" :type :enum
                         :description "Field two with [[link]]."}]}
          md (render/render-message msg)
          temp-file (java.io.File/createTempFile "roundtrip" ".md")]
      (try
        (spit temp-file md)
        (let [extracted (extract/extract-from-file (.getPath temp-file))]
          (is (= "Field **one** description."
                 (get-in extracted [:field-notes 1 :description])))
          (is (= "Field two with [[link]]."
                 (get-in extracted [:field-notes 2 :description]))))
        (finally
          (.delete temp-file))))))

(deftest full-roundtrip-test
  (testing "Full database roundtrip"
    (let [original-db {:messages {"cmd.Test"
                                  {:id "cmd.Test"
                                   :name "Test"
                                   :package "cmd"
                                   :source "test.proto"
                                   :description "Test message description"
                                   :fields [{:number 1 :name "value" :type :double
                                             :description "Value field docs"}
                                            {:number 2 :name "mode" :type :enum
                                             :type-ref "ser.Mode"}]}}
                       :enums {}
                       :search-index {}}
          ;; Simulate regeneration with fresh IR (no user content)
          fresh-ir {:messages {"cmd.Test"
                               {:id "cmd.Test"
                                :name "Test"
                                :package "cmd"
                                :source "test.proto"
                                ;; Fresh IR has no descriptions
                                :fields [{:number 1 :name "value" :type :double}
                                         {:number 2 :name "mode" :type :enum
                                          :type-ref "ser.Mode"}
                                         ;; New field added in proto
                                         {:number 3 :name "new_field" :type :bool}]}}
                    :enums {}
                    :search-index {}}
          ;; Preserve user content from original
          preserved (extract/preserve-user-content fresh-ir original-db)
          msg (get-in preserved [:messages "cmd.Test"])]

      ;; Description should be preserved
      (is (= "Test message description" (:description msg)))
      ;; Field 1 docs preserved
      (is (= "Value field docs" (:description (first (:fields msg)))))
      ;; Field 2 has no docs (wasn't documented originally)
      (is (nil? (:description (second (:fields msg)))))
      ;; Field 3 is new, no docs
      (is (nil? (:description (nth (:fields msg) 2)))))))

;; Property-based roundtrip test
(deftest property-roundtrip-test
  (testing "Generated descriptions survive roundtrip"
    (let [result
          (tc/quick-check
           20
           (prop/for-all
            [desc (gen/such-that
                   #(and (seq %)
                         (not (str/includes? % "---"))  ; Avoid frontmatter conflicts
                         (not (str/starts-with? % "#")) ; Avoid header conflicts
                         (not (str/starts-with? % "|"))) ; Avoid table conflicts
                   gen/string-alphanumeric)]
            (let [msg {:id "test.Msg"
                       :name "Msg"
                       :package "test"
                       :source "test.proto"
                       :description desc
                       :fields [{:number 1 :name "f" :type :bool}]}
                  md (render/render-message msg)
                  temp-file (java.io.File/createTempFile "prop-test" ".md")]
              (try
                (spit temp-file md)
                (let [extracted (extract/extract-from-file (.getPath temp-file))]
                  (= desc (:description extracted)))
                (finally
                  (.delete temp-file))))))]
      (is (:pass? result)
          (str "Failed with: " (:shrunk result))))))

;; ============================================================================
;; Interaction Metadata Roundtrip Tests
;; ============================================================================

(deftest roundtrip-interaction-test
  (testing "Message-level interaction metadata survives roundtrip"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :description "Test message"
               :interaction {:category :actuator
                             :ui-pattern :slider-with-presets
                             :feedback :pending-timeout
                             :purpose "Controls the slider."
                             :related-state ["ser.State"]
                             :related-commands ["cmd.Other"]
                             :preconditions ["Must be enabled" "Auto mode off"]
                             :notes "Implementation notes here."}
               :fields [{:number 1 :name "value" :type :double}]}
          md (render/render-message msg)
          temp-file (java.io.File/createTempFile "roundtrip-interaction" ".md")]
      (try
        (spit temp-file md)
        (let [extracted (extract/extract-from-file (.getPath temp-file))
              interaction (:interaction extracted)]
          (is (= :actuator (:category interaction)))
          (is (= :slider-with-presets (:ui-pattern interaction)))
          (is (= :pending-timeout (:feedback interaction)))
          (is (str/includes? (:purpose interaction) "slider"))
          (is (= ["ser.State"] (:related-state interaction)))
          (is (= ["cmd.Other"] (:related-commands interaction)))
          (is (= ["Must be enabled" "Auto mode off"] (:preconditions interaction)))
          (is (str/includes? (:notes interaction) "Implementation")))
        (finally
          (.delete temp-file))))))

(deftest roundtrip-field-interaction-test
  (testing "Field-level interaction metadata survives roundtrip"
    (let [msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :fields [{:number 1 :name "value" :type :double
                         :description "The value field."
                         :interaction {:semantic-type :normalized
                                       :unit "%"
                                       :precision 0
                                       :display-format "{value * 100}%"
                                       :presets [0 0.5 1.0]}}]}
          md (render/render-message msg)
          temp-file (java.io.File/createTempFile "roundtrip-field" ".md")]
      (try
        (spit temp-file md)
        (let [extracted (extract/extract-from-file (.getPath temp-file))
              field-note (get-in extracted [:field-notes 1])
              field-interaction (:interaction field-note)]
          (is (str/includes? (:description field-note) "value field"))
          (is (= :normalized (:semantic-type field-interaction)))
          (is (= "%" (:unit field-interaction)))
          (is (= 0 (:precision field-interaction)))
          (is (= "{value * 100}%" (:display-format field-interaction)))
          (is (= [0.0 0.5 1.0] (:presets field-interaction))))
        (finally
          (.delete temp-file))))))

;; ============================================================================
;; HTML-escape accretion
;; ============================================================================
;;
;; The render side writes metadata values into markdown; the extract side reads
;; them back. If the writer escapes a character the reader does not unescape,
;; every regeneration cycle adds one more escape layer and the value diverges
;; monotonically — an apostrophe becomes &#39;, whose & becomes &amp;#39; on the
;; next cycle, then &amp;amp;#39;, forever. These three tests pin the contract:
;; (a) an escapable value survives one cycle unchanged, (b) a value already
;; carrying accumulated layers is HEALED back to its clean form, and (c) a
;; second cycle is a fixpoint.

(defn- render->extract
  "Render a message to markdown, then extract user content back out of it.
   One full regeneration cycle."
  [msg]
  (let [md (render/render-message msg)
        temp-file (java.io.File/createTempFile "escape-roundtrip" ".md")]
    (try
      (spit temp-file md)
      (extract/extract-from-file (.getPath temp-file))
      (finally
        (.delete temp-file)))))

(defn- html-escape-once
  "Apply one HTML-escape pass, ampersand first (a correct single-pass escaper).
   Used to synthesize the multi-layer corruption earlier cycles accumulated."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "'" "&#39;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(def ^:private escapable-display-format
  "{value ? 'Use Rotary' : 'Use Compass'} & <b> \"q\"")

(deftest roundtrip-escapable-metadata-test
  (testing "metadata values holding HTML-escapable chars survive a cycle unchanged"
    (let [unit "a & b"
          preconditions ["Operator's mode must be \"on\" & armed"]
          msg {:id "cmd.Test"
               :name "Test"
               :package "cmd"
               :source "test.proto"
               :interaction {:category :actuator
                             :preconditions preconditions}
               :fields [{:number 1 :name "value" :type :double
                         :description "The value field."
                         :interaction {:semantic-type :normalized
                                       :unit unit
                                       :display-format escapable-display-format}}]}
          extracted (render->extract msg)
          field-interaction (get-in extracted [:field-notes 1 :interaction])]
      (is (= escapable-display-format (:display-format field-interaction)))
      (is (= unit (:unit field-interaction)))
      (is (= preconditions (get-in extracted [:interaction :preconditions]))))))

(deftest roundtrip-heals-accumulated-escaping-test
  (testing "a value carrying many accumulated escape layers collapses to the clean form"
    (let [corrupted (nth (iterate html-escape-once escapable-display-format) 5)
          md (str "---\n"
                  "id: cmd.Test\n"
                  "proto: test.proto\n"
                  "package: cmd\n"
                  "type: message\n"
                  "---\n"
                  "\n"
                  "# Test\n"
                  "\n"
                  "## Field Notes\n"
                  "\n"
                  "### value (#1)\n"
                  "\n"
                  "#### Metadata\n"
                  "\n"
                  "- **Semantic Type:** :normalized\n"
                  "- **Display Format:** `" corrupted "`\n")
          temp-file (java.io.File/createTempFile "escape-heal" ".md")]
      (try
        (spit temp-file md)
        ;; Guard: the fixture really is corrupted, so a pass cannot be vacuous.
        (is (str/includes? corrupted "&amp;amp;"))
        (let [extracted (extract/extract-from-file (.getPath temp-file))]
          (is (= escapable-display-format
                 (get-in extracted [:field-notes 1 :interaction :display-format]))))
        (finally
          (.delete temp-file))))))

(deftest roundtrip-escape-idempotence-test
  (testing "a second render->extract cycle is a fixpoint"
    (let [msg0 {:id "cmd.Test"
                :name "Test"
                :package "cmd"
                :source "test.proto"
                :interaction {:category :actuator
                              :preconditions ["Operator's mode must be \"on\" & armed"]}
                :fields [{:number 1 :name "value" :type :double
                          :description "The value field."
                          :interaction {:semantic-type :normalized
                                        :unit "a & b"
                                        :precision 0
                                        :display-format escapable-display-format
                                        :presets [0 0.5 1.0]}}]}
          pass1 (render->extract msg0)
          msg1 (-> msg0
                   (assoc-in [:fields 0 :interaction]
                             (get-in pass1 [:field-notes 1 :interaction]))
                   (assoc-in [:interaction :preconditions]
                             (get-in pass1 [:interaction :preconditions])))
          pass2 (render->extract msg1)]
      ;; Non-vacuity guard: both assertions below are ZERO-DELTA, so their PASS
      ;; value equals their nothing-ran value — if extract-from-file ever
      ;; returned {}, every get-in would be nil, nil would equal nil, and the
      ;; test would stay green over a dead round-trip. Pin that pass1 actually
      ;; carries the escapable value first, so the fixpoint claim below can
      ;; only pass by the round-trip genuinely preserving it.
      (is (= escapable-display-format
             (get-in pass1 [:field-notes 1 :interaction :display-format]))
          "pass1 must round-trip the escapable value before a fixpoint means anything")
      (is (= (get-in pass1 [:field-notes 1 :interaction])
             (get-in pass2 [:field-notes 1 :interaction])))
      (is (= (get-in pass1 [:interaction :preconditions])
             (get-in pass2 [:interaction :preconditions]))))))

(deftest full-roundtrip-with-interaction-test
  (testing "Full database roundtrip with interaction metadata"
    (let [original-db {:messages {"cmd.Test"
                                  {:id "cmd.Test"
                                   :name "Test"
                                   :package "cmd"
                                   :source "test.proto"
                                   :description "Test message"
                                   :interaction {:category :actuator
                                                 :ui-pattern :slider}
                                   :fields [{:number 1 :name "value" :type :double
                                             :description "Value field"
                                             :interaction {:semantic-type :normalized}}]}}
                       :enums {}
                       :search-index {}}
          ;; Fresh IR with no user content
          fresh-ir {:messages {"cmd.Test"
                               {:id "cmd.Test"
                                :name "Test"
                                :package "cmd"
                                :source "test.proto"
                                :fields [{:number 1 :name "value" :type :double}
                                         {:number 2 :name "new_field" :type :bool}]}}
                    :enums {}
                    :search-index {}}
          preserved (extract/preserve-user-content fresh-ir original-db)
          msg (get-in preserved [:messages "cmd.Test"])]

      ;; Message-level interaction should be preserved
      (is (= :actuator (get-in msg [:interaction :category])))
      (is (= :slider (get-in msg [:interaction :ui-pattern])))
      ;; Field 1 interaction should be preserved
      (is (= :normalized (get-in (first (:fields msg)) [:interaction :semantic-type])))
      ;; New field 2 should have no interaction
      (is (nil? (:interaction (second (:fields msg))))))))
