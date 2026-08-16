(ns protocol-gen.instrument-test
  "The arming seam's own canary.

   An arming seam that instruments nothing prints exactly what a working one
   prints, so the suite that depends on it has to assert that arming REALLY
   happened rather than that `arm!` returned.

   TO BREAK IT DELIBERATELY and watch this go red: remove
   `:kaocha.hooks/post-load` (or `:plugins [:kaocha.plugin/hooks]`) from
   `tests.edn` — the first leaves the hook unwired, the second leaves the key
   inert config nothing reads."
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.db :as db]
            [protocol-gen.instrument :as instrument]))

(deftest the-source-namespace-list-is-derived-and-non-empty
  (let [nss (instrument/source-namespaces)]
    (is (seq nss) "no namespace under src — discovery broke, it is not that
                   there is nothing to arm")
    (is (contains? (set nss) 'protocol-gen.db))))

(deftest specs-are-registered-and-armed
  (let [vars (instrument/specced-vars)]
    (is (seq vars) "no var carries a registered m/=> schema")
    (testing "and the hook actually replaced them — a spec on an unwrapped var
              is one nothing can ever check"
      (is (thrown? clojure.lang.ExceptionInfo (db/message-ref? {:messages {}} 42))
          "message-ref? accepted a non-string id, so instrumentation is NOT armed"))))

(deftest replaced-count-can-tell-a-wrapped-var-from-an-unwrapped-one
  ;; The guard's own predicate, proven both directions on a synthetic pair
  ;; rather than on the live registry.
  (let [v #'db/known-types]
    (is (= 0 (instrument/replaced-count {v @v})))
    (is (= 1 (instrument/replaced-count {v ::something-else})))))
