(ns protodoc.lint-test
  "Tests for proto documentation lint rules."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [protodoc.lint :as lint]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn- make-db
  "Create a minimal DB with given messages and enums."
  [& {:keys [messages enums] :or {messages {} enums {}}}]
  {:messages messages :enums enums :search-index {}})

(defn- make-msg
  "Create a minimal message."
  [id & {:keys [fields description interaction]}]
  {:id id :name (last (str/split id #"\."))
   :package (str/join "." (butlast (str/split id #"\.")))
   :source "test.proto"
   :fields (or fields [])
   :description description
   :interaction interaction})

(defn- make-field
  "Create a minimal field."
  [nm ty & {:keys [description constraints interaction]}]
  (cond-> {:number 1 :name nm :type ty}
    description  (assoc :description description)
    constraints  (assoc :constraints constraints)
    interaction  (assoc :interaction interaction)))

(defn- make-enum
  "Create a minimal enum."
  [id & {:keys [description values]}]
  {:id id :name (last (str/split id #"\."))
   :package (str/join "." (butlast (str/split id #"\.")))
   :source "test.proto"
   :description description
   :values (or values [])})

(defn- finding-rules [result]
  (set (map :rule (:findings result))))

;; ============================================================================
;; enum-values-undocumented
;; ============================================================================

(deftest enum-values-undocumented-test
  (testing "Flags enums with described parent but undocumented values"
    (let [db (make-db :enums {"test.E" (make-enum "test.E"
                                                  :description "An enum"
                                                  :values [{:number 0 :name "A"}
                                                           {:number 1 :name "B"}])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:enum-values-undocumented})))))
      (is (= :warning (:severity (first (:findings (lint/lint db :rules #{:enum-values-undocumented}))))))))

  (testing "Treats '-' as undocumented"
    (let [db (make-db :enums {"test.E" (make-enum "test.E"
                                                  :description "An enum"
                                                  :values [{:number 0 :name "A" :description "-"}
                                                           {:number 1 :name "B" :description "Fine"}])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:enum-values-undocumented})))))
      (is (str/includes? (:message (first (:findings (lint/lint db :rules #{:enum-values-undocumented}))))
                         "1/2"))))

  (testing "Skips enums without top-level description"
    (let [db (make-db :enums {"test.E" (make-enum "test.E"
                                                  :values [{:number 0 :name "A"}])})]
      (is (empty? (:findings (lint/lint db :rules #{:enum-values-undocumented}))))))

  (testing "Clean when all values documented"
    (let [db (make-db :enums {"test.E" (make-enum "test.E"
                                                  :description "An enum"
                                                  :values [{:number 0 :name "A" :description "Desc A"}
                                                           {:number 1 :name "B" :description "Desc B"}])})]
      (is (empty? (:findings (lint/lint db :rules #{:enum-values-undocumented})))))))

;; ============================================================================
;; field-metadata-without-description
;; ============================================================================

(deftest field-metadata-without-description-test
  (testing "Flags fields with interaction but no description"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double
                                                                         :interaction {:semantic-type :normalized})])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:field-metadata-without-description})))))))

  (testing "Clean when field has both interaction and description"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double
                                                                         :description "A value"
                                                                         :interaction {:semantic-type :normalized})])})]
      (is (empty? (:findings (lint/lint db :rules #{:field-metadata-without-description}))))))

  (testing "Clean when field has no interaction"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double)])})]
      (is (empty? (:findings (lint/lint db :rules #{:field-metadata-without-description})))))))

;; ============================================================================
;; semantic-type-mismatch
;; ============================================================================

(deftest semantic-type-mismatch-test
  (testing "Flags bool with :enum-label"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :bool
                                                                         :interaction {:semantic-type :enum-label})])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:semantic-type-mismatch})))))
      (is (= :error (:severity (first (:findings (lint/lint db :rules #{:semantic-type-mismatch}))))))))

  (testing "Flags string with :normalized"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :string
                                                                         :interaction {:semantic-type :normalized})])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:semantic-type-mismatch})))))))

  (testing "Clean when double + :normalized"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double
                                                                         :interaction {:semantic-type :normalized})])})]
      (is (empty? (:findings (lint/lint db :rules #{:semantic-type-mismatch}))))))

  (testing "Clean when :raw (compatible with all types)"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :bool
                                                                         :interaction {:semantic-type :raw})])})]
      (is (empty? (:findings (lint/lint db :rules #{:semantic-type-mismatch}))))))

  (testing "Clean when no semantic-type"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :bool)])})]
      (is (empty? (:findings (lint/lint db :rules #{:semantic-type-mismatch})))))))

;; ============================================================================
;; interaction-incomplete
;; ============================================================================

(deftest interaction-incomplete-test
  (testing "Flags category without ui-pattern and feedback"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:category :actuator})})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:interaction-incomplete})))))
      (let [msg (:message (first (:findings (lint/lint db :rules #{:interaction-incomplete}))))]
        (is (str/includes? msg ":ui-pattern"))
        (is (str/includes? msg ":feedback")))))

  (testing "Flags category without feedback only"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:category :actuator :ui-pattern :slider})})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:interaction-incomplete})))))
      (is (str/includes?
           (:message (first (:findings (lint/lint db :rules #{:interaction-incomplete}))))
           ":feedback"))))

  (testing "Clean when full interaction metadata"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:category :actuator
                                                                  :ui-pattern :slider
                                                                  :feedback :fire-and-forget})})]
      (is (empty? (:findings (lint/lint db :rules #{:interaction-incomplete}))))))

  (testing "Clean when no interaction"
    (let [db (make-db :messages {"test.M" (make-msg "test.M")})]
      (is (empty? (:findings (lint/lint db :rules #{:interaction-incomplete})))))))

;; ============================================================================
;; invalid-references
;; ============================================================================

(deftest invalid-references-test
  (testing "Flags related-state pointing to non-existent ID"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-state ["ser.NonExistent"]})})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:invalid-references})))))
      (is (= :error (:severity (first (:findings (lint/lint db :rules #{:invalid-references}))))))))

  (testing "Flags related-commands pointing to non-existent ID"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-commands ["cmd.Missing"]})})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:invalid-references})))))))

  (testing "Clean when references point to existing messages"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-state ["test.Other"]})
                                 "test.Other" (make-msg "test.Other")})]
      (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))))

  (testing "Clean when references point to existing enums"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-state ["test.E"]})}
                      :enums {"test.E" (make-enum "test.E")})]
      (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))))

  (testing "Clean when no interaction"
    (let [db (make-db :messages {"test.M" (make-msg "test.M")})]
      (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))))

  (testing "Resolves only the MESSAGE half of a field-qualified related-state"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-state ["test.Other#anything"]})
                                 "test.Other" (make-msg "test.Other")})]
      (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))))

  (testing "Flags a field-qualified reference whose MESSAGE is missing"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :interaction {:related-state ["test.Gone#value"]})})
          findings (:findings (lint/lint db :rules #{:invalid-references}))]
      (is (= 1 (count findings)))
      ;; The reported string is the WHOLE reference, not the split half — a
      ;; reader who greps the docs for the message the finding names must find it.
      (is (str/includes? (:message (first findings)) "'test.Gone#value'")))))

;; ============================================================================
;; unresolved-state-field
;;
;; THE ATTRIBUTION CASES ARE THE POINT, not the count. `:invalid-references`
;; would refuse several of the same inputs, so a red proves nothing about THIS
;; clause unless the neighbour is asserted silent on the same db — and still
;; refusing on its own. Both directions are below, and every case asserts the
;; named finding rather than a non-zero total.
;; ============================================================================

(deftest unresolved-state-field-test
  (let [target (make-msg "ser.Target" :fields [(make-field "real_field" :double)])]

    (testing "Flags a field the target message does not declare"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["ser.Target#nope"]})
                                   "ser.Target" target})
            findings (:findings (lint/lint db :rules #{:unresolved-state-field}))]
        (is (= 1 (count findings)))
        (is (= :unresolved-state-field (:rule (first findings))))
        (is (= :error (:severity (first findings))))
        (is (= "test.M" (:id (first findings))))
        (is (str/includes? (:message (first findings)) "ser.Target#nope"))
        (is (str/includes? (:message (first findings)) "declares no field 'nope'"))))

    (testing "ATTRIBUTION — the neighbour clause is SILENT on that same db"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["ser.Target#nope"]})
                                   "ser.Target" target})]
        (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))
        (is (= #{:unresolved-state-field} (finding-rules (lint/lint db))))))

    (testing "ATTRIBUTION — an unknown MESSAGE is the neighbour's, and reported ONCE"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["ser.Absent#nope"]})})]
        (is (empty? (:findings (lint/lint db :rules #{:unresolved-state-field}))))
        (is (= #{:invalid-references} (finding-rules (lint/lint db))))
        (is (= 1 (count (:findings (lint/lint db)))))))

    (testing "Clean when the named field exists"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["ser.Target#real_field"]})
                                   "ser.Target" target})]
        (is (empty? (:findings (lint/lint db :rules #{:unresolved-state-field}))))))

    (testing "Clean for a whole-message reference — the grain stays legal"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["ser.Target"]})
                                   "ser.Target" target})]
        (is (empty? (:findings (lint/lint db :rules #{:unresolved-state-field}))))))

    (testing "Flags a field suffix on an ENUM target, which has no fields"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-state ["test.E#value"]})}
                        :enums {"test.E" (make-enum "test.E")})
            findings (:findings (lint/lint db :rules #{:unresolved-state-field}))]
        (is (= 1 (count findings)))
        (is (= :unresolved-state-field (:rule (first findings))))
        (is (str/includes? (:message (first findings)) "is an enum and has none"))
        ;; The id resolves, so the neighbour is satisfied and stays quiet.
        (is (empty? (:findings (lint/lint db :rules #{:invalid-references}))))))

    (testing "Clean when there is no related-state at all"
      (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                      :interaction {:related-commands ["test.M"]})})]
        (is (empty? (:findings (lint/lint db :rules #{:unresolved-state-field}))))))))

;; ============================================================================
;; description-vague
;; ============================================================================

(deftest description-vague-test
  (testing "Flags TBD"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "TBD - needs docs")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Flags unclear"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "This is unclear")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Flags TODO"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "TODO fill this")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Flags ??"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "What does this do??")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Flags unknown"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "Purpose is unknown")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Also checks enum descriptions"
    (let [db (make-db :enums {"test.E" (make-enum "test.E" :description "TBD")})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:description-vague})))))))

  (testing "Clean for normal descriptions"
    (let [db (make-db :messages {"test.M" (make-msg "test.M" :description "Controls the iris aperture.")})]
      (is (empty? (:findings (lint/lint db :rules #{:description-vague}))))))

  (testing "Clean for nil description"
    (let [db (make-db :messages {"test.M" (make-msg "test.M")})]
      (is (empty? (:findings (lint/lint db :rules #{:description-vague})))))))

;; ============================================================================
;; constrained-fields-undocumented
;; ============================================================================

(deftest constrained-fields-undocumented-test
  (testing "Flags constrained fields without description"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double
                                                                         :constraints {:gte 0 :lte 1})])})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:constrained-fields-undocumented})))))))

  (testing "Clean when constrained field has description"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double
                                                                         :description "A value"
                                                                         :constraints {:gte 0 :lte 1})])})]
      (is (empty? (:findings (lint/lint db :rules #{:constrained-fields-undocumented}))))))

  (testing "Clean when field has no constraints"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "value" :double)])})]
      (is (empty? (:findings (lint/lint db :rules #{:constrained-fields-undocumented})))))))

;; ============================================================================
;; percent-display-precision
;; ============================================================================

(defn- pct-db
  "One-message DB whose single field carries the given field-level interaction."
  [interaction]
  (make-db :messages {"test.M" (make-msg "test.M"
                                         :fields [(make-field "value" :double
                                                              :description "Normalized value"
                                                              :interaction interaction)])}))

(defn- pct-findings [interaction]
  (:findings (lint/lint (pct-db interaction) :rules #{:percent-display-precision})))

(deftest percent-display-precision-test
  (testing "Flags a scaling display-format carrying a non-zero precision"
    (let [findings (pct-findings {:semantic-type :normalized
                                  :unit "%"
                                  :precision 2
                                  :display-format "{value * 100}%"})]
      (is (= 1 (count findings)))
      (is (= :error (:severity (first findings))))
      (is (= "value" (:field (first findings))))
      (is (str/includes? (:message (first findings)) "scales by 100"))
      (is (str/includes? (:message (first findings)) "found 2"))))

  (testing "Flags a scaling display-format declaring no precision at all"
    (let [findings (pct-findings {:semantic-type :normalized
                                  :display-format "{value * 100}%"})]
      (is (= 1 (count findings)))
      (is (str/includes? (:message (first findings)) "none declared"))))

  (testing "Clean at precision 0 — the documented canonical shape"
    (is (empty? (pct-findings {:semantic-type :normalized
                               :unit "%"
                               :precision 0
                               :display-format "{value * 100}%"}))))

  (testing "Tolerates the whitespace the authored string is not normalised for"
    (is (= 1 (count (pct-findings {:precision 3 :display-format "{value*100}%"}))))
    (is (= 1 (count (pct-findings {:precision 3 :display-format "{value * 100.0}%"})))))

  (testing "A DIFFERENT multiplier is not this rule's business"
    (is (empty? (pct-findings {:precision 3 :display-format "{value * 1000} ms"})))
    (is (empty? (pct-findings {:precision 3 :display-format "{value * 10}%"}))))

  (testing "Clean when the field declares no display-format"
    (is (empty? (pct-findings {:semantic-type :normalized :precision 2})))
    (is (empty? (pct-findings {:semantic-type :normalized :precision 2
                               :display-format "Speed: {value}"}))))

  (testing "Clean when the field carries no interaction metadata"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :fields [(make-field "speed" :double
                                                                         :description "Movement speed")])})]
      (is (empty? (:findings (lint/lint db :rules #{:percent-display-precision}))))))

  (testing "The finding names the message and the field, not just the message"
    (let [db (make-db :messages
                      {"test.Clean" (make-msg "test.Clean"
                                              :fields [(make-field "ok" :double
                                                                   :description "d"
                                                                   :interaction {:precision 0
                                                                                 :display-format "{value * 100}%"})])
                       "test.Drifted" (make-msg "test.Drifted"
                                                :fields [(make-field "bad" :double
                                                                     :description "d"
                                                                     :interaction {:precision 2
                                                                                   :display-format "{value * 100}%"})])})
          findings (:findings (lint/lint db :rules #{:percent-display-precision}))]
      (is (= 1 (count findings)))
      (is (= "test.Drifted" (:id (first findings))))
      (is (= "bad" (:field (first findings)))))))

;; ============================================================================
;; Orchestrator: filtering and summary
;; ============================================================================

(deftest lint-orchestrator-test
  (testing "Rule filtering includes only specified rules"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :description "TBD"
                                                    :fields [(make-field "v" :double :constraints {:gte 0})])})]
      (is (= #{:description-vague}
             (finding-rules (lint/lint db :rules #{:description-vague}))))
      (is (= #{:constrained-fields-undocumented}
             (finding-rules (lint/lint db :rules #{:constrained-fields-undocumented}))))))

  (testing "Rule exclusion removes specified rules"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :description "TBD"
                                                    :fields [(make-field "v" :double :constraints {:gte 0})])})]
      (is (not (contains? (finding-rules (lint/lint db :exclude #{:description-vague}))
                          :description-vague)))))

  (testing "Severity filtering"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :description "TBD"
                                                    :interaction {:related-state ["ser.Missing"]}
                                                    :fields [(make-field "v" :double :constraints {:gte 0})])})]
      ;; Only errors
      (let [result (lint/lint db :severity #{:error})]
        (is (every? #(= :error (:severity %)) (:findings result))))
      ;; Only warnings
      (let [result (lint/lint db :severity #{:warning})]
        (is (every? #(= :warning (:severity %)) (:findings result))))))

  (testing "Summary counts are correct"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                    :description "TBD"
                                                    :interaction {:category :actuator
                                                                  :related-state ["ser.Missing"]}
                                                    :fields [(make-field "v" :bool
                                                                         :interaction {:semantic-type :enum-label})])})
          result (lint/lint db)]
      (is (pos? (:error (:summary result))))
      (is (pos? (:warning (:summary result))))
      (is (pos? (:info (:summary result))))))

  (testing "Empty DB returns no findings"
    (let [result (lint/lint (make-db))]
      (is (empty? (:findings result)))
      (is (= {:error 0 :warning 0 :info 0} (:summary result))))))

;; ============================================================================
;; format-findings
;; ============================================================================

(deftest format-findings-test
  (testing "Formats empty results"
    (let [output (lint/format-findings {:findings [] :summary {:error 0 :warning 0 :info 0}})]
      (is (str/includes? output "No issues found"))
      (is (str/includes? output "0 errors, 0 warnings, 0 info"))))

  (testing "Formats findings with all severities"
    (let [result {:findings [{:rule :invalid-references :severity :error
                              :id "test.M" :field nil :message "ref not found"}
                             {:rule :interaction-incomplete :severity :warning
                              :id "test.M" :field nil :message "missing :feedback"}
                             {:rule :description-vague :severity :info
                              :id "test.M" :field nil :message "contains TBD"}]
                  :summary {:error 1 :warning 1 :info 1}}
          output (lint/format-findings result)]
      (is (str/includes? output "ERRORS (1)"))
      (is (str/includes? output "WARNINGS (1)"))
      (is (str/includes? output "INFO (1)"))
      (is (str/includes? output "1 errors, 1 warnings, 1 info")))))

;; ============================================================================
;; unit-contradicts-display-format  (rule A)
;; ============================================================================

(defn- ua-db
  "One-message DB whose single field carries the given field-level interaction."
  [interaction]
  (make-db :messages {"test.M" (make-msg "test.M"
                                         :fields [(make-field "value" :double
                                                              :description "Angle"
                                                              :interaction interaction)])}))

(defn- ua-findings [interaction]
  (:findings (lint/lint (ua-db interaction) :rules #{:unit-contradicts-display-format})))

(deftest unit-contradicts-display-format-test
  (testing "Flags a field whose unit and its own display-format spell one quantity two ways"
    (let [findings (ua-findings {:unit "degrees" :display-format "{value}°"})]
      (is (= 1 (count findings)))
      (is (= :unit-contradicts-display-format (:rule (first findings))))
      (is (= :error (:severity (first findings))))
      (is (= "value" (:field (first findings))))
      (is (str/includes? (:message (first findings)) "degrees"))
      (is (str/includes? (:message (first findings)) "°"))))

  (testing "Clean when the two keys agree"
    (is (empty? (ua-findings {:unit "°" :display-format "{value}°"}))))

  (testing "Clean when the suffix is separated by whitespace but names the same unit"
    (is (empty? (ua-findings {:unit "m" :display-format "{value} m"}))))

  (testing "Clean for the canonical percent shape — the suffix IS the unit"
    (is (empty? (ua-findings {:unit "%" :display-format "{value * 100}%"}))))

  ;; THE FALSE-POSITIVE GUARD. A display-format carrying no placeholder is a
  ;; standing LABEL, not a value template — it prints no value, so it declares no
  ;; unit. Without the guard the whole label is taken as a spelling and the field
  ;; is flagged for a contradiction it does not have. This case is why the rule
  ;; can claim zero false positives.
  (testing "Clean when the display-format is a label rather than a value template"
    (is (empty? (ua-findings {:unit "normalized" :display-format "Shift value (±0.01)"}))))

  (testing "Clean when the template prints no trailing literal at all"
    (is (empty? (ua-findings {:unit "ns" :display-format "{value}"}))))

  (testing "Silent when the field declares only one of the two keys"
    (is (empty? (ua-findings {:unit "degrees"})))
    (is (empty? (ua-findings {:display-format "{value}°"})))))

;; ============================================================================
;; unit-disagrees-across-identical-descriptions  (rule B)
;; ============================================================================

(defn- ub-db
  "DB of one message whose fields are [name unit display-format description]."
  [specs]
  (make-db :messages
           {"test.M" (make-msg "test.M"
                               :fields (vec (for [[n u f d] specs]
                                              (make-field n :double
                                                          :description d
                                                          :interaction (cond-> {}
                                                                         u (assoc :unit u)
                                                                         f (assoc :display-format f))))))}))

(defn- ub-findings [specs]
  (:findings (lint/lint (ub-db specs)
                        :rules #{:unit-disagrees-across-identical-descriptions})))

(def ^:private angle-desc "Azimuth angle in degrees")

(deftest unit-disagrees-across-identical-descriptions-test
  (testing "Flags the minority when templates on BOTH sides of the split agree"
    (let [findings (ub-findings [["a" "°"       "{value}°" angle-desc]
                                 ["b" "degrees" "{value}°" angle-desc]
                                 ["c" "degrees" nil        angle-desc]])]
      (is (= 2 (count findings)))
      (is (every? #(= :unit-disagrees-across-identical-descriptions (:rule %)) findings))
      (is (= #{"b" "c"} (set (map :field findings))))
      (is (str/includes? (:message (first findings)) "degrees"))
      (is (str/includes? (:message (first findings)) "°"))))

  ;; THE REFUSAL THAT MATTERS. When the group's ONLY template sits on the
  ;; disputed field, that field arbitrates in its own favour and the rule would
  ;; assert the minority spelling as the answer. The live instance is a lone
  ;; microsecond field among nanosecond siblings carrying the only template — a
  ;; genuine quantity dispute this rule cannot settle and must not pretend to.
  (testing "REFUSES when the only template belongs to the disputed field"
    (is (empty? (ub-findings [["a" "nanoseconds" nil         "State snapshot timestamp"]
                              ["b" "nanoseconds" nil         "State snapshot timestamp"]
                              ["c" "us"          "{value} μs" "State snapshot timestamp"]]))))

  ;; A GENERIC description groups genuinely different quantities. This is the
  ;; case that refuted the rule's original key, and both units here are CORRECT.
  (testing "REFUSES a group carrying no templates at all"
    (is (empty? (ub-findings [["a" "minutes" nil "Step offset value"]
                              ["b" "months"  nil "Step offset value"]]))))

  (testing "REFUSES when the templates disagree in kind"
    (is (empty? (ub-findings [["a" "normalized" "Shift value (±0.01)" "Signed offset value"]
                              ["b" "%"          "{value * 100}%"      "Signed offset value"]]))))

  (testing "Silent when the group agrees on one unit"
    (is (empty? (ub-findings [["a" "°" "{value}°" angle-desc]
                              ["b" "°" nil        angle-desc]]))))

  (testing "Silent across DIFFERENT descriptions even when the units disagree"
    (is (empty? (ub-findings [["a" "degrees" "{value}°" "Azimuth angle"]
                              ["b" "°"       "{value}°" "Elevation angle"]])))))
