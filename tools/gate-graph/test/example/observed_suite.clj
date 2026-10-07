(ns example.observed-suite
  "Synthetic test runner behaviors, controlled through dynamic bindings without changing global vars."
  (:require [clojure.test :as test :refer [deftest is]]
            [gate.clojure-test :as observer]
            [malli.core :as m])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:dynamic *mode* :normal)
(def ^:dynamic *events* nil)
(def ^:dynamic *barrier* nil)

(test/use-fixtures :once (fn [f]
                           (when *events* (swap! *events* conj :once-start))
                           (when (= *mode* :fixture-error) (throw (ex-info "sensitive fixture error" {})))
                           (when (= *mode* :fixture-assertion) (is (= 1 1)))
                           (f)
                           (when *events* (swap! *events* conj :once-end))))
(test/use-fixtures :each (fn [f]
                           (when *events* (swap! *events* conj :each-start))
                           (f)
                           (when *events* (swap! *events* conj :each-end))))

(deftest second-test
  (when *barrier*
    (.countDown ^CountDownLatch *barrier*)
    (is (.await ^CountDownLatch *barrier* 2 TimeUnit/SECONDS)))
  (when-not (= *mode* :empty) (is (= 2 (+ 1 1)))))

(deftest first-test
  (when *barrier*
    (.countDown ^CountDownLatch *barrier*)
    (is (.await ^CountDownLatch *barrier* 2 TimeUnit/SECONDS)))
  (case *mode*
    :empty nil
    :failure (is (= "sensitive sentinel" "different"))
    :error (throw (ex-info "sensitive sentinel" {}))
    :joined @(future (is (= 3 (+ 1 2))))
    :nested-observer (let [result (binding [*mode* :normal]
                                    (observer/run! {:run "inner" :gate "inner-tests" :label "Nested observer"
                                                    :source-digest (apply str (repeat 64 "a"))
                                                    :max-tests 10 :max-assertions 100}
                                                   '[example.observed-suite]))]
                       (is (= :passed (:status result))))
    :nested (do (is (= 1 1)) (test/test-var #'second-test))
    (is (= 4 (+ 2 2)))))

(defn test-ns-hook
  "Preserve real clojure.test fixture execution while exercising selection, nesting and concurrency."
  []
  (case *mode*
    :omit (test/test-vars [#'first-test])
    :parallel (let [workers [(future (test/test-vars [#'first-test]))
                             (future (test/test-vars [#'second-test]))]]
                (doseq [worker workers] @worker))
    (test/test-vars [#'first-test #'second-test])))
(m/=> test-ns-hook [:=> [:cat] :nil])
