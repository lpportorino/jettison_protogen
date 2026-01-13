(ns protodoc.core-test
  "Tests for CLI and integration workflows."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [protodoc.core :as core]
            [protodoc.schema :as schema]
            [clojure.java.io :as io]))

(deftest coverage-error-handling-test
  (testing "Throws when DB not found"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Database not found"
         (core/coverage {:db-path "/nonexistent/db.edn"}))))

  (testing "Handles empty DB without division by zero"
    (let [temp-file (java.io.File/createTempFile "empty-db" ".edn")]
      (try
        (spit temp-file (pr-str {:messages {} :enums {} :search-index {}}))
        ;; Should not throw - captures output (note: format uses %3d padding)
        (let [output (with-out-str
                       (core/coverage {:db-path (.getPath temp-file)}))]
          (is (clojure.string/includes? output "  0 /   0")))
        (finally
          (.delete temp-file))))))

(deftest validate-function-test
  (testing "Returns valid result when DB is valid"
    (let [temp-file (java.io.File/createTempFile "valid-db" ".edn")
          db {:messages {} :enums {} :search-index {}}]
      (try
        (spit temp-file (pr-str db))
        (let [result (core/validate {:db-path (.getPath temp-file)})]
          (is (:valid result))
          (is (= db (:db result))))
        (finally
          (.delete temp-file)))))

  (testing "Returns error when DB not found"
    (let [result (core/validate {:db-path "/nonexistent/path.edn"})]
      (is (not (:valid result)))
      (is (= :not-found (:error result)))))

  (testing "Returns error when DB has wrong structure"
    (let [temp-file (java.io.File/createTempFile "invalid-db" ".edn")]
      (try
        (spit temp-file "{:not-a-valid :db}")
        (let [result (core/validate {:db-path (.getPath temp-file)})]
          (is (not (:valid result)))
          (is (= :invalid (:error result)))
          (is (some? (:errors result))))
        (finally
          (.delete temp-file))))))

(deftest parse-args-test
  (testing "Parses command"
    (let [result (#'core/parse-args ["generate"])]
      (is (= "generate" (:command result)))))

  (testing "Parses options"
    (let [result (#'core/parse-args ["generate" "--descriptor" "path.json" "--db-path" "db.edn"])]
      (is (= "generate" (:command result)))
      (is (= "path.json" (get-in result [:options :descriptor])))
      (is (= "db.edn" (get-in result [:options :db-path])))))

  (testing "Handles no arguments"
    (let [result (#'core/parse-args [])]
      (is (nil? (:command result)))
      (is (= {} (:options result))))))

(deftest generate-integration-test
  (testing "Full generate from descriptor to markdown"
    (let [descriptor {"file"
                      [{"name" "jon_shared_test.proto"
                        "package" "test"
                        "messageType"
                        [{"name" "TestMsg"
                          "field"
                          [{"name" "value"
                            "number" 1
                            "label" "LABEL_OPTIONAL"
                            "type" "TYPE_UINT32"
                            "jsonName" "value"}]}]
                        "enumType"
                        [{"name" "TestEnum"
                          "value"
                          [{"name" "UNSPECIFIED" "number" 0}
                           {"name" "VALUE_1" "number" 1}]}]}]}
          temp-desc (java.io.File/createTempFile "desc" ".json")
          temp-db (java.io.File/createTempFile "proto-db" ".edn")
          temp-out (java.io.File/createTempFile "output" "dir")]
      (.delete temp-out)
      (.mkdir temp-out)
      (try
        (spit temp-desc (json/write-str descriptor))
        (core/generate {:descriptor (.getPath temp-desc)
                        :output-dir (.getPath temp-out)
                        :db-path (.getPath temp-db)})
        ;; Verify DB was created
        (is (.exists temp-db))
        (let [db (read-string (slurp temp-db))]
          (is (schema/valid? schema/ProtoDb db))
          (is (= 1 (count (:messages db))))
          (is (= 1 (count (:enums db)))))
        ;; Verify markdown was created
        (is (.exists (io/file temp-out "proto/test.TestMsg.md")))
        (is (.exists (io/file temp-out "proto/test.TestEnum.md")))
        (is (.exists (io/file temp-out "index.md")))
        (finally
          (.delete temp-desc)
          (.delete temp-db)
          ;; Clean up output directory
          (doseq [f (reverse (file-seq temp-out))]
            (.delete f)))))))

(deftest sync-ir-integration-test
  (testing "Preserves user content when syncing"
    (let [old-db {:messages {"test.Msg" {:id "test.Msg"
                                         :name "Msg"
                                         :package "test"
                                         :source "jon_shared_test.proto"
                                         :description "User docs"
                                         :fields [{:number 1 :name "old_field" :type :bool}]}}
                  :enums {}
                  :search-index {}}
          new-descriptor {"file"
                          [{"name" "jon_shared_test.proto"
                            "package" "test"
                            "messageType"
                            [{"name" "Msg"
                              "field"
                              [{"name" "new_field"
                                "number" 1
                                "label" "LABEL_OPTIONAL"
                                "type" "TYPE_BOOL"
                                "jsonName" "newField"}]}]}]}
          temp-desc (java.io.File/createTempFile "desc" ".json")
          temp-db (java.io.File/createTempFile "proto-db" ".edn")]
      (try
        (spit temp-db (pr-str old-db))
        (spit temp-desc (json/write-str new-descriptor))
        (let [result (core/sync-ir {:descriptor (.getPath temp-desc)
                                    :db-path (.getPath temp-db)})]
          ;; User description should be preserved
          (is (= "User docs" (get-in result [:messages "test.Msg" :description])))
          ;; Field name should be updated
          (is (= "new_field" (get-in result [:messages "test.Msg" :fields 0 :name]))))
        (finally
          (.delete temp-desc)
          (.delete temp-db))))))
