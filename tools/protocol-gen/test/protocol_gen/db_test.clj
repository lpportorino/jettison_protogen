(ns protocol-gen.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.db :as db]
            [protocol-gen.instrument :as instrument]))

(def ^:private minimal
  {:messages {"p.M" {:id "p.M" :name "M"
                     :fields [{:number 1 :name "a" :type :int32}]}}
   :enums {}})

(deftest a-well-formed-database-validates
  (is (= minimal (db/validate! minimal "<inline>"))))

(deftest a-malformed-database-throws-naming-its-path
  ;; thrown-with-msg? rather than thrown?: once instrumentation is armed a bare
  ;; thrown? cannot tell this function's own guard from malli refusing the
  ;; argument at the boundary, and would keep passing with the guard deleted.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Not a descriptor database"
       ((instrument/uninstrumented #'db/validate!) {:messages {}} "<inline>"))))

(deftest a-missing-database-file-is-a-hard-failure-not-an-empty-one
  ;; An empty database projects to an empty schema, which every downstream
  ;; check then passes over — the vacuous green gate-enforcement §3 refuses.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Descriptor database not found"
       (db/load-database "does/not/exist.edn"))))

(deftest load-round-trips-a-file
  (let [f (java.io.File/createTempFile "protocol-gen-db" ".edn")]
    (try
      (spit f (pr-str minimal))
      (is (= minimal (db/load-database (java.io.File/.getPath f))))
      (finally (io/delete-file f true)))))

(deftest references-resolve-by-id
  (is (true? (db/message-ref? minimal "p.M")))
  (is (false? (db/message-ref? minimal "p.Nope")))
  (is (false? (db/enum-ref? minimal "p.M"))))

(deftest fields-are-found-by-number-never-by-position
  (let [msg {:id "p.M" :name "M"
             :fields [{:number 7 :name "seven" :type :int32}
                      {:number 3 :name "three" :type :int32}]}]
    (is (= "seven" (:name (db/field-by-number msg 7))))
    (is (= "three" (:name (db/field-by-number msg 3))))
    (testing "position 0 holds number 7, so a positional lookup would disagree"
      (is (nil? (db/field-by-number msg 0)))
      (is (nil? (db/field-by-number msg 1))))))
