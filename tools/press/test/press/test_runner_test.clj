(ns press.test-runner-test
  "Discovery guards use real files and verify newly added namespaces join the suite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [press.test-runner :as runner]))

(deftest discovers-all-shipped-modules
  (is (some #{'press.doc.image} runner/source-namespaces))
  (is (some #{'press.doc.image-test} runner/test-namespaces))
  (is (some #{'press.test-runner-test} runner/test-namespaces)))

(deftest refuses-vacuous-and-ambiguous-discovery
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "press-discovery-" (make-array java.nio.file.attribute.FileAttribute 0)))
        a (io/file dir "a.clj") b (io/file dir "b.clj")]
    (try
      (testing "empty source population"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty or duplicate" (runner/discover-namespaces dir))))
      (spit a "(ns independent.example)")
      (is (= ['independent.example] (runner/discover-namespaces dir)))
      (spit b "(ns independent.example)")
      (testing "duplicate namespaces cannot conceal a file"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty or duplicate" (runner/discover-namespaces dir))))
      (spit b "(def missing-ns true)")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing namespace" (runner/discover-namespaces dir)))
      (finally (.delete a) (.delete b) (.delete dir)))))
