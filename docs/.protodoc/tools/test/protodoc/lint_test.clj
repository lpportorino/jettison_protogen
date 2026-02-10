(ns protodoc.lint-test
  "Tests for proto documentation lint rules."
  (:require [clojure.test :refer [deftest testing is]]
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
  {:id id :name (last (clojure.string/split id #"\."))
   :package (clojure.string/join "." (butlast (clojure.string/split id #"\.")))
   :source "test.proto"
   :fields (or fields [])
   :description description
   :interaction interaction})

(defn- make-field
  "Create a minimal field."
  [name type & {:keys [description constraints interaction]}]
  (cond-> {:number 1 :name name :type type}
    description  (assoc :description description)
    constraints  (assoc :constraints constraints)
    interaction  (assoc :interaction interaction)))

(defn- make-enum
  "Create a minimal enum."
  [id & {:keys [description values]}]
  {:id id :name (last (clojure.string/split id #"\."))
   :package (clojure.string/join "." (butlast (clojure.string/split id #"\.")))
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
      (is (clojure.string/includes? (:message (first (:findings (lint/lint db :rules #{:enum-values-undocumented}))))
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
        (is (clojure.string/includes? msg ":ui-pattern"))
        (is (clojure.string/includes? msg ":feedback")))))

  (testing "Flags category without feedback only"
    (let [db (make-db :messages {"test.M" (make-msg "test.M"
                                                     :interaction {:category :actuator :ui-pattern :slider})})]
      (is (= 1 (count (:findings (lint/lint db :rules #{:interaction-incomplete})))))
      (is (clojure.string/includes?
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
      (is (empty? (:findings (lint/lint db :rules #{:invalid-references})))))))

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
                                                                         :interaction {:semantic-type :enum-label})])})]
      (let [result (lint/lint db)]
        (is (pos? (:error (:summary result))))
        (is (pos? (:warning (:summary result))))
        (is (pos? (:info (:summary result)))))))

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
      (is (clojure.string/includes? output "No issues found"))
      (is (clojure.string/includes? output "0 errors, 0 warnings, 0 info"))))

  (testing "Formats findings with all severities"
    (let [result {:findings [{:rule :invalid-references :severity :error
                              :id "test.M" :field nil :message "ref not found"}
                             {:rule :interaction-incomplete :severity :warning
                              :id "test.M" :field nil :message "missing :feedback"}
                             {:rule :description-vague :severity :info
                              :id "test.M" :field nil :message "contains TBD"}]
                  :summary {:error 1 :warning 1 :info 1}}
          output (lint/format-findings result)]
      (is (clojure.string/includes? output "ERRORS (1)"))
      (is (clojure.string/includes? output "WARNINGS (1)"))
      (is (clojure.string/includes? output "INFO (1)"))
      (is (clojure.string/includes? output "1 errors, 1 warnings, 1 info")))))
