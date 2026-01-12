(ns protodoc.parse-test
  "Tests for JSON descriptor parsing."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [protodoc.parse :as parse]
            [protodoc.schema :as schema]))

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

(deftest filter-jon-files-test
  (testing "Only processes jon_shared_* files"
    (let [descriptor {"file"
                      [{"name" "jon_shared_cmd.proto"
                        "package" "cmd"
                        "messageType" [{"name" "Test1" "field" []}]}
                       {"name" "other_file.proto"
                        "package" "other"
                        "messageType" [{"name" "Test2" "field" []}]}
                       {"name" "jon_shared_data.proto"
                        "package" "ser"
                        "messageType" [{"name" "Test3" "field" []}]}]}
          temp-file (java.io.File/createTempFile "filter" ".json")]
      (try
        (spit temp-file (json/write-str descriptor))
        (let [db (parse/parse-descriptor-file (.getPath temp-file))]
          ;; Should only have Test1 and Test3, not Test2
          (is (= 2 (count (:messages db))))
          (is (contains? (:messages db) "cmd.Test1"))
          (is (contains? (:messages db) "ser.Test3"))
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
