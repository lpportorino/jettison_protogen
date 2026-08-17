(ns protodoc.parse-test
  "Tests for JSON descriptor parsing."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [protodoc.parse :as parse]))

(def sample-descriptor
  {"file"
   [{"name" "jon_shared_cmd.proto"
     "package" "cmd"
     "messageType"
     [{"name" "Root"
       "field"
       [{"name" "protocol_version"
         "number" 1
         "label" "LABEL_OPTIONAL"
         "type" "TYPE_UINT32"
         "options" {"[buf.validate.field]" {"uint32" {"gt" 0 "lte" 2147483647}}}}
        {"name" "client_type"
         "number" 5
         "label" "LABEL_OPTIONAL"
         "type" "TYPE_ENUM"
         "typeName" ".ser.JonGuiDataClientType"
         "options" {"[buf.validate.field]" {"enum" {"definedOnly" true "notIn" [0]}}}}
        {"name" "day_camera"
         "number" 20
         "label" "LABEL_OPTIONAL"
         "type" "TYPE_MESSAGE"
         "typeName" ".cmd.DayCamera.Root"
         "oneofIndex" 0}]
       "oneofDecl"
       [{"name" "payload"
         "options" {"[buf.validate.oneof]" {"required" true}}}]}]
     "enumType"
     [{"name" "TestEnum"
       "value"
       [{"name" "UNSPECIFIED" "number" 0}
        {"name" "VALUE_1" "number" 1}]}]}]})

(deftest parse-descriptor-test
  (testing "Parses sample descriptor"
    (let [temp-file (java.io.File/createTempFile "test-descriptor" ".json")]
      (try
        (spit temp-file (json/write-str sample-descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          (is (map? db))
          (is (contains? db :messages))
          (is (contains? db :enums))
          (is (= 1 (count (:messages db))))
          (is (= 1 (count (:enums db)))))
        (finally
          (.delete temp-file))))))

(deftest parse-constraints-test
  (testing "Parses numeric constraints"
    (let [temp-file (java.io.File/createTempFile "test-desc" ".json")]
      (try
        (spit temp-file (json/write-str sample-descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "cmd.Root"])
              pv-field (first (filter #(= (:name %) "protocol_version") (:fields msg)))]
          (is (= {:gt 0 :lte 2147483647} (:constraints pv-field))))
        (finally
          (.delete temp-file)))))

  (testing "Parses enum constraints"
    (let [temp-file (java.io.File/createTempFile "test-desc" ".json")]
      (try
        (spit temp-file (json/write-str sample-descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "cmd.Root"])
              ct-field (first (filter #(= (:name %) "client_type") (:fields msg)))]
          (is (:defined-only (:constraints ct-field)))
          (is (= [0] (:not-in (:constraints ct-field)))))
        (finally
          (.delete temp-file))))))

(deftest parse-oneofs-test
  (testing "Parses oneof declarations"
    (let [temp-file (java.io.File/createTempFile "test-desc" ".json")]
      (try
        (spit temp-file (json/write-str sample-descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "cmd.Root"])
              oneofs (:oneofs msg)]
          (is (= 1 (count oneofs)))
          (is (= "payload" (:name (first oneofs))))
          (is (:required (first oneofs)))
          (is (= [20] (:fields (first oneofs)))))
        (finally
          (.delete temp-file))))))

(deftest parse-type-ref-test
  (testing "Normalizes type references"
    (let [temp-file (java.io.File/createTempFile "test-desc" ".json")]
      (try
        (spit temp-file (json/write-str sample-descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "cmd.Root"])
              ct-field (first (filter #(= (:name %) "client_type") (:fields msg)))]
          ;; Should strip leading dot
          (is (= "ser.JonGuiDataClientType" (:type-ref ct-field))))
        (finally
          (.delete temp-file))))))

(deftest build-search-index-test
  (testing "Builds search index from DB"
    (let [db {:messages {"cmd.Test" {:id "cmd.Test"
                                     :name "TestMessage"
                                     :package "cmd"
                                     :source "test.proto"
                                     :fields [{:number 1 :name "iris_value" :type :double}]}}
              :enums {}
              :search-index {}}
          indexed (parse/build-search-index db)]
      ;; "testmessage" (lowercased name) should be indexed
      (is (contains? (:search-index indexed) "testmessage"))
      (is (contains? (:search-index indexed) "iris"))
      (is (contains? (:search-index indexed) "cmd"))
      (is (some #(= "cmd.Test" %) (get-in indexed [:search-index "testmessage"]))))))

;; Edge case tests

(deftest parse-error-handling-test
  (testing "Non-existent file throws"
    (is (thrown? Exception
                 (parse/parse-descriptor-file "/nonexistent/path.json"))))

  (testing "Invalid JSON throws"
    (let [temp-file (java.io.File/createTempFile "bad-json" ".json")]
      (try
        (spit temp-file "{invalid json")
        (is (thrown? Exception
                     (parse/parse-descriptor-file (.getPath temp-file))))
        (finally
          (.delete temp-file)))))

  (testing "Empty JSON file returns empty DB"
    (let [temp-file (java.io.File/createTempFile "empty" ".json")]
      (try
        (spit temp-file "{}")
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          (is (= 0 (count (:messages db))))
          (is (= 0 (count (:enums db)))))
        (finally
          (.delete temp-file))))))

(deftest parse-nested-messages-test
  (testing "Deeply nested messages (2 levels)"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Level1"
                          "field" [{"name" "x" "number" 1 "type" "TYPE_BOOL"}]
                          "nestedType"
                          [{"name" "Level2"
                            "field" [{"name" "y" "number" 1 "type" "TYPE_BOOL"}]}]}]}]}
          temp-file (java.io.File/createTempFile "nested" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          (is (= 2 (count (:messages db))))
          (is (contains? (:messages db) "test.Level1"))
          (is (contains? (:messages db) "test.Level1.Level2")))
        (finally
          (.delete temp-file))))))

(deftest parse-filters-map-entries-test
  (testing "Map entry types are filtered from nested messages"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "WithMap"
                          "field" [{"name" "tags" "number" 1 "type" "TYPE_MESSAGE"
                                    "typeName" ".test.WithMap.TagsEntry"}]
                          "nestedType"
                          [{"name" "TagsEntry"
                            "options" {"mapEntry" true}
                            "field" [{"name" "key" "number" 1 "type" "TYPE_STRING"}
                                     {"name" "value" "number" 2 "type" "TYPE_STRING"}]}]}]}]}
          temp-file (java.io.File/createTempFile "map-entry" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          ;; Should only have WithMap, not TagsEntry
          (is (= 1 (count (:messages db))))
          (is (contains? (:messages db) "test.WithMap"))
          (is (not (contains? (:messages db) "test.WithMap.TagsEntry"))))
        (finally
          (.delete temp-file))))))

(deftest filter-project-files-test
  (testing "Processes jon_shared_* and opaque/* files, excludes others"
    (let [descriptor {"file"
                      [{"name" "jon_shared_cmd.proto"
                        "package" "cmd"
                        "messageType" [{"name" "Test1" "field" []}]}
                       {"name" "other_file.proto"
                        "package" "other"
                        "messageType" [{"name" "Test2" "field" []}]}
                       {"name" "jon_shared_data.proto"
                        "package" "ser"
                        "messageType" [{"name" "Test3" "field" []}]}
                       {"name" "opaque/object_detection.proto"
                        "package" "ser"
                        "messageType" [{"name" "ObjectDetection" "field" []}]}]}
          temp-file (java.io.File/createTempFile "filter" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          ;; Should have Test1, Test3, and ObjectDetection, not Test2
          (is (= 3 (count (:messages db))))
          (is (contains? (:messages db) "cmd.Test1"))
          (is (contains? (:messages db) "ser.Test3"))
          (is (contains? (:messages db) "ser.ObjectDetection"))
          (is (not (contains? (:messages db) "other.Test2"))))
        (finally
          (.delete temp-file))))))

(deftest search-index-edge-cases-test
  (testing "Handles nil descriptions"
    (let [db {:messages {"test.Msg" {:id "test.Msg"
                                     :name "Test"
                                     :package "test"
                                     :source "test.proto"
                                     :description nil
                                     :fields []}}
              :enums {}
              :search-index {}}
          indexed (parse/build-search-index db)]
      (is (contains? (:search-index indexed) "test"))))

  (testing "Multiple messages indexed to same term"
    (let [db {:messages {"test.Msg1" {:id "test.Msg1"
                                      :name "TestMessage"
                                      :package "test"
                                      :source "test.proto"
                                      :fields []}
                         "test.Msg2" {:id "test.Msg2"
                                      :name "TestCommand"
                                      :package "test"
                                      :source "test.proto"
                                      :fields []}}
              :enums {}
              :search-index {}}
          indexed (parse/build-search-index db)]
      ;; Both should be indexed under "test"
      (is (= 2 (count (get-in indexed [:search-index "test"])))))))

;; ============================================================================
;; New Constraint Type Tests
;; ============================================================================

(deftest parse-string-in-constraint-test
  (testing "Parses string.in constraint (allowed string values)"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "LogLevel"
                          "field"
                          [{"name" "level"
                            "number" 1
                            "type" "TYPE_STRING"
                            "options" {"[buf.validate.field]" {"string" {"in" ["error" "warn" "info" "debug"]}}}}]}]}]}
          temp-file (java.io.File/createTempFile "string-in" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.LogLevel"])
              field (first (:fields msg))]
          (is (= ["error" "warn" "info" "debug"] (:in (:constraints field)))))
        (finally
          (.delete temp-file))))))

(deftest parse-string-email-constraint-test
  (testing "Parses string.email constraint (email validation)"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "User"
                          "field"
                          [{"name" "email"
                            "number" 1
                            "type" "TYPE_STRING"
                            "options" {"[buf.validate.field]" {"string" {"email" true}}}}]}]}]}
          temp-file (java.io.File/createTempFile "string-email" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.User"])
              field (first (:fields msg))]
          (is (true? (:email (:constraints field)))))
        (finally
          (.delete temp-file))))))

(deftest parse-repeated-min-items-constraint-test
  (testing "Parses repeated.minItems constraint (minimum array length)"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Container"
                          "field"
                          [{"name" "items"
                            "number" 1
                            "type" "TYPE_STRING"
                            "label" "LABEL_REPEATED"
                            "options" {"[buf.validate.field]" {"repeated" {"minItems" "1"}}}}]}]}]}
          temp-file (java.io.File/createTempFile "repeated-min" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.Container"])
              field (first (:fields msg))]
          (is (= 1 (:min-items (:constraints field)))))
        (finally
          (.delete temp-file))))))

(deftest parse-multiple-new-constraints-test
  (testing "Parses multiple new constraint types together"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Complex"
                          "field"
                          [{"name" "email"
                            "number" 1
                            "type" "TYPE_STRING"
                            "options" {"[buf.validate.field]" {"string" {"email" true "minLen" "5" "maxLen" "100"}}}}
                           {"name" "tags"
                            "number" 2
                            "type" "TYPE_STRING"
                            "label" "LABEL_REPEATED"
                            "options" {"[buf.validate.field]" {"repeated" {"minItems" "2"}}}}
                           {"name" "status"
                            "number" 3
                            "type" "TYPE_STRING"
                            "options" {"[buf.validate.field]" {"string" {"in" ["active" "inactive" "pending"]}}}}]}]}]}
          temp-file (java.io.File/createTempFile "multi-new" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.Complex"])
              email-field (first (filter #(= "email" (:name %)) (:fields msg)))
              tags-field (first (filter #(= "tags" (:name %)) (:fields msg)))
              status-field (first (filter #(= "status" (:name %)) (:fields msg)))]
          ;; Email field has email + min/max len
          (is (true? (:email (:constraints email-field))))
          (is (= 5 (:min-len (:constraints email-field))))
          (is (= 100 (:max-len (:constraints email-field))))
          ;; Tags field has min-items
          (is (= 2 (:min-items (:constraints tags-field))))
          ;; Status field has in
          (is (= ["active" "inactive" "pending"] (:in (:constraints status-field)))))
        (finally
          (.delete temp-file))))))

;; ============================================================================
;; Unknown Constraint Error Handling Tests
;; ============================================================================

(deftest unknown-constraint-throws-test
  (testing "Unknown constraint type throws ex-info with descriptive message"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Test"
                          "field"
                          [{"name" "value"
                            "number" 1
                            "type" "TYPE_STRING"
                            "options" {"[buf.validate.field]" {"string" {"unknownConstraint" "someValue"}}}}]}]}]}
          temp-file (java.io.File/createTempFile "unknown" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unknown buf.validate constraint: :string/unknownConstraint"
             (parse/parse-descriptor-file (.getPath temp-file))))
        (finally
          (.delete temp-file))))))

(deftest unknown-constraint-error-data-test
  (testing "Unknown constraint ex-info contains useful error data"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Test"
                          "field"
                          [{"name" "value"
                            "number" 1
                            "type" "TYPE_DOUBLE"
                            "options" {"[buf.validate.field]" {"double" {"newConstraint" 42}}}}]}]}]}
          temp-file (java.io.File/createTempFile "unknown-data" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (try
          (parse/parse-descriptor-file (.getPath temp-file))
          (is false "Should have thrown ex-info")
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :unknown-constraint (:type data)))
              (is (= :double/newConstraint (:constraint data)))
              (is (= "double" (:type-key data)))
              (is (= "newConstraint" (:constraint-key data)))
              (is (= 42 (:value data))))))
        (finally
          (.delete temp-file))))))

(deftest parse-repeated-max-items-constraint-test
  (testing "Parses repeated.maxItems constraint (maximum array length)"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "DetectionList"
                          "field"
                          [{"name" "detections"
                            "number" 1
                            "type" "TYPE_MESSAGE"
                            "typeName" ".test.Detection"
                            "label" "LABEL_REPEATED"
                            "options" {"[buf.validate.field]" {"repeated" {"maxItems" "256"}}}}]}]}]}
          temp-file (java.io.File/createTempFile "repeated-max" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.DetectionList"])
              field (first (:fields msg))]
          (is (= 256 (:max-items (:constraints field)))))
        (finally
          (.delete temp-file))))))

(deftest parse-repeated-min-max-items-constraint-test
  (testing "Parses combined minItems + maxItems constraints"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "BoundedList"
                          "field"
                          [{"name" "items"
                            "number" 1
                            "type" "TYPE_STRING"
                            "label" "LABEL_REPEATED"
                            "options" {"[buf.validate.field]" {"repeated" {"minItems" "1" "maxItems" "100"}}}}]}]}]}
          temp-file (java.io.File/createTempFile "repeated-minmax" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              msg (get-in db [:messages "test.BoundedList"])
              field (first (:fields msg))]
          (is (= 1 (:min-items (:constraints field))))
          (is (= 100 (:max-items (:constraints field)))))
        (finally
          (.delete temp-file))))))

;; ============================================================================
;; Scalar type mapping — one keyword per WIRE ENCODING
;; ============================================================================
;;
;; DERIVED, NEVER LISTED. Both tests below read `parse/type-mapping`'s own keys
;; and compute what each one must produce, so neither can be satisfied by a map
;; that has been re-collapsed and neither rots when a descriptor type is added.
;; A hand-written expectation list would have to be edited in step with the map
;; it judges, which is the one edit an author making the map wrong would also
;; make.

(defn- descriptor-type-suffix
  "The proto spelling a descriptor type name carries — `TYPE_SFIXED32` ->
   `sfixed32`. It is the type's spelling in `.proto` source, which is what a
   consumer re-emitting from this database writes out."
  [descriptor-type-name]
  (str/lower-case (subs descriptor-type-name (count "TYPE_"))))

(deftest type-mapping-preserves-every-encoding-test
  (testing "each descriptor type maps to the keyword naming its own proto type"
    ;; A shared keyword is a COLLAPSED WIRE ENCODING: zigzag, fixed-width and
    ;; varint two's-complement decode the same bytes to different values, so a
    ;; database that cannot tell them apart cannot be re-emitted truthfully.
    (doseq [[descriptor-name mapped] parse/type-mapping]
      (is (= (keyword (descriptor-type-suffix descriptor-name)) mapped)
          (str descriptor-name " must map to :" (descriptor-type-suffix descriptor-name)))))

  (testing "no two descriptor types share a keyword"
    (is (= (count parse/type-mapping)
           (count (set (vals parse/type-mapping))))
        (str "collapsed keywords: "
             (->> (group-by val parse/type-mapping)
                  (filter (fn [[_ entries]] (> (count entries) 1)))
                  (mapv (fn [[k entries]] [k (mapv key entries)])))))))

(deftest parse-preserves-every-encoding-end-to-end-test
  (testing "a field of every descriptor type reaches the database with its own type"
    ;; Built FROM the mapping's keys, so the descriptor under test grows with
    ;; the mapping rather than having to be edited alongside it.
    (let [names (sort (keys parse/type-mapping))
          descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "EveryType"
                          "field"
                          (vec (map-indexed
                                (fn [i descriptor-name]
                                  {"name" (descriptor-type-suffix descriptor-name)
                                   "number" (inc i)
                                   "type" descriptor-name
                                   "typeName" ".test.Referenced"})
                                names))}]}]}
          temp-file (java.io.File/createTempFile "every-type" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              fields (:fields (get-in db [:messages "test.EveryType"]))
              by-name (into {} (map (juxt :name :type)) fields)]
          (is (= (count names) (count fields)))
          (doseq [descriptor-name names]
            (is (= (keyword (descriptor-type-suffix descriptor-name))
                   (get by-name (descriptor-type-suffix descriptor-name)))
                (str descriptor-name " lost its encoding on the way into the database")))
          (is (= (count fields) (count (set (map :type fields))))
              "two fields of different descriptor types share one database type"))
        (finally
          (.delete temp-file))))))

(deftest parse-group-keeps-its-type-ref-test
  (testing "a proto2 group is preserved distinctly and keeps its reference"
    ;; A group is a MESSAGE-shaped construct with a DIFFERENT encoding —
    ;; START_GROUP/END_GROUP tags rather than a length prefix — so folding it
    ;; onto :message is the same defect as folding sint32 onto int32. It is
    ;; preserved with its :type-ref so a consumer can resolve it and then refuse
    ;; it knowingly, rather than emit a length-delimited field in its place.
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Legacy"
                          "field" [{"name" "leg" "number" 1 "type" "TYPE_GROUP"
                                    "typeName" ".test.Legacy.Leg"}]}]}]}
          temp-file (java.io.File/createTempFile "group" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))
              field (first (:fields (get-in db [:messages "test.Legacy"])))]
          (is (= :group (:type field)))
          (is (= "test.Legacy.Leg" (:type-ref field))))
        (finally
          (.delete temp-file))))))

(deftest unknown-constraint-suggests-fix-test
  (testing "Unknown constraint error message suggests how to add handler"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "Test"
                          "field"
                          [{"name" "count"
                            "number" 1
                            "type" "TYPE_UINT32"
                            "options" {"[buf.validate.field]" {"uint32" {"customRule" 100}}}}]}]}]}
          temp-file (java.io.File/createTempFile "suggest" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (try
          (parse/parse-descriptor-file (.getPath temp-file))
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (let [msg (ex-message e)]
              ;; Should contain suggestions for fixing
              (is (re-find #"To add support:" msg))
              (is (re-find #"Add :uint32/customRule to constraint-registry" msg))
              (is (re-find #"defmethod parse-constraint :uint32/customRule" msg))
              (is (re-find #"Update Constraints schema" msg))
              (is (re-find #"Update format-constraints" msg)))))
        (finally
          (.delete temp-file))))))
