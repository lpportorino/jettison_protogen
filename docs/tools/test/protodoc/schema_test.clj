(ns protodoc.schema-test
  "Tests for Malli schema validation and generators."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [protodoc.schema :as schema]))

(deftest field-schema-test
  (testing "Valid field validates"
    (is (schema/valid? schema/Field
                       {:number 1
                        :name "value"
                        :type :double})))

  (testing "Field with all optional fields validates"
    (is (schema/valid? schema/Field
                       {:number 5
                        :name "client_type"
                        :type :enum
                        :type-ref "ser.JonGuiDataClientType"
                        :repeated false
                        :constraints {:defined-only true :not-in [0]}
                        :description "Client type identifier"})))

  (testing "Invalid field type fails"
    (is (not (schema/valid? schema/Field
                            {:number 1
                             :name "value"
                             :type :invalid-type})))))

(deftest constraints-schema-test
  (testing "Numeric constraints"
    (is (schema/valid? schema/Constraints
                       {:gt 0 :lte 100})))

  (testing "Enum constraints"
    (is (schema/valid? schema/Constraints
                       {:defined-only true :not-in [0 1 2]})))

  (testing "String constraints"
    (is (schema/valid? schema/Constraints
                       {:min-len 1 :max-len 255 :pattern "^[a-z]+$"}))))

(deftest message-schema-test
  (testing "Valid message validates"
    (is (schema/valid? schema/Message
                       {:id "cmd.DayCamera.SetIris"
                        :name "SetIris"
                        :package "cmd.DayCamera"
                        :source "jon_shared_cmd_day_camera.proto"
                        :fields [{:number 1 :name "value" :type :double}]})))

  (testing "Message with oneofs validates"
    (is (schema/valid? schema/Message
                       {:id "cmd.Root"
                        :name "Root"
                        :package "cmd"
                        :source "jon_shared_cmd.proto"
                        :fields [{:number 1 :name "protocol_version" :type :uint32}
                                 {:number 20 :name "day_camera" :type :message}]
                        :oneofs [{:name "payload" :required true :fields [20]}]}))))

(deftest enum-schema-test
  (testing "Valid enum validates"
    (is (schema/valid? schema/ProtoEnum
                       {:id "ser.JonGuiDataClientType"
                        :name "JonGuiDataClientType"
                        :package "ser"
                        :source "jon_shared_data_types.proto"
                        :values [{:number 0 :name "UNSPECIFIED"}
                                 {:number 1 :name "WEB"}
                                 {:number 2 :name "MOBILE"}]}))))

(deftest proto-db-schema-test
  (testing "Valid DB validates"
    (is (schema/valid? schema/ProtoDb
                       {:messages {}
                        :enums {}
                        :search-index {}})))

  (testing "DB with data validates"
    (is (schema/valid? schema/ProtoDb
                       {:messages {"cmd.Test"
                                   {:id "cmd.Test"
                                    :name "Test"
                                    :package "cmd"
                                    :source "test.proto"
                                    :fields [{:number 1 :name "value" :type :uint32}]}}
                        :enums {"ser.TestEnum"
                                {:id "ser.TestEnum"
                                 :name "TestEnum"
                                 :package "ser"
                                 :source "test.proto"
                                 :values [{:number 0 :name "UNSPECIFIED"}]}}
                        :search-index {"test" ["cmd.Test" "ser.TestEnum"]}}))))

;; Property-based tests

(deftest generated-fields-are-valid-test
  (testing "Generated fields are always valid"
    (let [result (tc/quick-check
                  100
                  (prop/for-all [field (mg/generator schema/Field-gen)]
                    (schema/valid? schema/Field field)))]
      (is (:pass? result)
          (str "Failed: " (:shrunk result))))))

(deftest generated-messages-are-valid-test
  (testing "Generated messages are always valid"
    (let [result (tc/quick-check
                  50
                  (prop/for-all [msg (mg/generator schema/Message-gen)]
                    (schema/valid? schema/Message msg)))]
      (is (:pass? result)
          (str "Failed: " (:shrunk result))))))

(deftest field-numbers-positive-test
  (testing "Field numbers are always positive"
    (let [result (tc/quick-check
                  100
                  (prop/for-all [msg (mg/generator schema/Message-gen)]
                    (every? pos? (map :number (:fields msg)))))]
      (is (:pass? result)
          (str "Failed: " (:shrunk result))))))

;; Edge case tests

(deftest field-edge-cases-test
  (testing "Field with zero number fails"
    (is (not (schema/valid? schema/Field {:number 0 :name "x" :type :uint32}))))

  (testing "Field with negative number fails"
    (is (not (schema/valid? schema/Field {:number -1 :name "x" :type :uint32}))))

  (testing "Field with missing name fails"
    (is (not (schema/valid? schema/Field {:number 1 :type :uint32}))))

  (testing "Field with nil type fails"
    (is (not (schema/valid? schema/Field {:number 1 :name "x" :type nil})))))

(deftest constraints-edge-cases-test
  (testing "Empty constraints map is valid"
    (is (schema/valid? schema/Constraints {})))

  (testing "Example constraint validates"
    (is (schema/valid? schema/Constraints {:example [1.0 2.0 3.0]})))

  (testing "Combined constraints validate"
    (is (schema/valid? schema/Constraints {:gte 0 :lte 100 :example [50 75]}))))

(deftest message-edge-cases-test
  (testing "Message with empty fields is valid"
    (is (schema/valid? schema/Message
                       {:id "cmd.Empty"
                        :name "Empty"
                        :package "cmd"
                        :source "test.proto"
                        :fields []})))

  (testing "Message with empty package is valid"
    (is (schema/valid? schema/Message
                       {:id "Root"
                        :name "Root"
                        :package ""
                        :source "test.proto"
                        :fields [{:number 1 :name "x" :type :bool}]})))

  (testing "Message with missing id fails"
    (is (not (schema/valid? schema/Message
                            {:name "Test"
                             :package "cmd"
                             :source "test.proto"
                             :fields []})))))

(deftest enum-edge-cases-test
  (testing "Enum with negative numbers is valid"
    (is (schema/valid? schema/ProtoEnum
                       {:id "test.NegEnum"
                        :name "NegEnum"
                        :package "test"
                        :source "test.proto"
                        :values [{:number -1 :name "NEG_ONE"}
                                 {:number 0 :name "ZERO"}]})))

  (testing "Enum with empty values is valid"
    (is (schema/valid? schema/ProtoEnum
                       {:id "test.Empty"
                        :name "Empty"
                        :package "test"
                        :source "test.proto"
                        :values []}))))

(deftest validate-throws-test
  (testing "validate! throws on invalid data"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Schema validation failed"
         (schema/validate! schema/Field {:number 0 :name "x" :type :uint32}))))

  (testing "validate! includes error details in ex-data"
    (try
      (schema/validate! schema/Field {:number -1 :name "x" :type :unknown})
      (is false "Should have thrown")
      (catch Exception e
        (is (contains? (ex-data e) :errors))
        (is (contains? (ex-data e) :data))))))

;; Additional property-based tests

(deftest generated-enums-are-valid-test
  (testing "Generated enums are always valid"
    (let [result (tc/quick-check
                  50
                  (prop/for-all [enum (mg/generator schema/ProtoEnum-gen)]
                    (schema/valid? schema/ProtoEnum enum)))]
      (is (:pass? result)
          (str "Failed: " (:shrunk result))))))

(deftest generated-constraints-are-valid-test
  (testing "Generated constraints are always valid"
    (let [result (tc/quick-check
                  100
                  (prop/for-all [constraints (mg/generator schema/Constraints)]
                    (schema/valid? schema/Constraints constraints)))]
      (is (:pass? result)
          (str "Failed: " (:shrunk result))))))

;; ============================================================================
;; Interaction Metadata Schema Tests
;; ============================================================================

(deftest message-category-schema-test
  (testing "Valid message categories"
    (is (schema/valid? schema/MessageCategory :sensor))
    (is (schema/valid? schema/MessageCategory :actuator))
    (is (schema/valid? schema/MessageCategory :settings))
    (is (schema/valid? schema/MessageCategory :status))
    (is (schema/valid? schema/MessageCategory :lifecycle))
    (is (schema/valid? schema/MessageCategory :diagnostic)))

  (testing "Invalid message category fails"
    (is (not (schema/valid? schema/MessageCategory :invalid)))
    (is (not (schema/valid? schema/MessageCategory "actuator")))))

(deftest ui-pattern-schema-test
  (testing "Valid atomic UI patterns"
    (is (schema/valid? schema/UIPattern :toggle))
    (is (schema/valid? schema/UIPattern :action-button))
    (is (schema/valid? schema/UIPattern :slider))
    (is (schema/valid? schema/UIPattern :stepper))
    (is (schema/valid? schema/UIPattern :indicator)))

  (testing "Valid molecular UI patterns"
    (is (schema/valid? schema/UIPattern :slider-with-steppers))
    (is (schema/valid? schema/UIPattern :press-accelerating)))

  (testing "Valid composite UI patterns"
    (is (schema/valid? schema/UIPattern :slider-with-presets))
    (is (schema/valid? schema/UIPattern :directional-mover))
    (is (schema/valid? schema/UIPattern :tabbed-config))
    (is (schema/valid? schema/UIPattern :state-machine-menu)))

  (testing "Invalid UI pattern fails"
    (is (not (schema/valid? schema/UIPattern :custom-pattern)))))

(deftest feedback-type-schema-test
  (testing "Valid feedback types"
    (is (schema/valid? schema/FeedbackType :fire-and-forget))
    (is (schema/valid? schema/FeedbackType :pending-timeout))
    (is (schema/valid? schema/FeedbackType :poll-confirm))
    (is (schema/valid? schema/FeedbackType :optimistic-visual))
    (is (schema/valid? schema/FeedbackType :dual-feedback)))

  (testing "Invalid feedback type fails"
    (is (not (schema/valid? schema/FeedbackType :instant)))))

(deftest semantic-type-schema-test
  (testing "Valid numeric semantic types"
    (is (schema/valid? schema/SemanticType :normalized))
    (is (schema/valid? schema/SemanticType :angle))
    (is (schema/valid? schema/SemanticType :percentage))
    (is (schema/valid? schema/SemanticType :coordinate-geo))
    (is (schema/valid? schema/SemanticType :temperature))
    (is (schema/valid? schema/SemanticType :voltage)))

  (testing "Valid display semantic types"
    (is (schema/valid? schema/SemanticType :cardinal))
    (is (schema/valid? schema/SemanticType :enum-label))
    (is (schema/valid? schema/SemanticType :toggle-state))
    (is (schema/valid? schema/SemanticType :raw)))

  (testing "Invalid semantic type fails"
    (is (not (schema/valid? schema/SemanticType :unknown-type)))))

(deftest field-meta-schema-test
  (testing "Valid field metadata"
    (is (schema/valid? schema/FieldMeta
                       {:semantic-type :normalized
                        :unit "%"
                        :precision 2
                        :display-format "{value * 100}%"
                        :presets [0 0.25 0.5 0.75 1.0]})))

  (testing "Field metadata with string presets"
    (is (schema/valid? schema/FieldMeta
                       {:presets [0 "auto" 0.5 1.0]})))

  (testing "Empty field metadata is valid"
    (is (schema/valid? schema/FieldMeta {})))

  (testing "Invalid precision fails"
    (is (not (schema/valid? schema/FieldMeta {:precision -1})))))

(deftest interaction-meta-schema-test
  (testing "Valid interaction metadata"
    (is (schema/valid? schema/InteractionMeta
                       {:purpose "Controls iris aperture"
                        :category :actuator
                        :ui-pattern :slider-with-presets
                        :feedback :pending-timeout
                        :timeout-ms 2000
                        :related-state ["ser.JonGuiDataCameraDay"]
                        :related-commands ["cmd.DayCamera.SetAutoIris"]
                        :preconditions ["Camera must be started"]
                        :notes "Implementation guidance"})))

  (testing "Empty interaction metadata is valid"
    (is (schema/valid? schema/InteractionMeta {})))

  (testing "Invalid timeout fails"
    (is (not (schema/valid? schema/InteractionMeta {:timeout-ms 0}))))

  (testing "Invalid category fails"
    (is (not (schema/valid? schema/InteractionMeta {:category :invalid})))))

(deftest message-with-interaction-test
  (testing "Message with interaction metadata validates"
    (is (schema/valid? schema/Message
                       {:id "cmd.DayCamera.SetIris"
                        :name "SetIris"
                        :package "cmd.DayCamera"
                        :source "jon_shared_cmd_day_camera.proto"
                        :fields [{:number 1
                                  :name "value"
                                  :type :double
                                  :interaction {:semantic-type :normalized
                                                :unit "%"
                                                :presets [0 0.5 1.0]}}]
                        :interaction {:category :actuator
                                      :ui-pattern :slider-with-presets
                                      :feedback :pending-timeout}}))))

(deftest field-with-interaction-test
  (testing "Field with interaction metadata validates"
    (is (schema/valid? schema/Field
                       {:number 1
                        :name "value"
                        :type :double
                        :interaction {:semantic-type :normalized
                                      :unit "%"
                                      :precision 0
                                      :display-format "{value * 100}%"}}))))
