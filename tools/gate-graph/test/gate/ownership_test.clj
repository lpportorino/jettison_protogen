(ns gate.ownership-test
  "Thread and separate-JVM exclusion, distinct-root concurrency and recovery from partial claims."
  (:require [clojure.test :refer [deftest is]]
            [gate.ownership :as ownership]
            [gate.process :as process]
            [gate.trace-test :refer [with-directory failure]])
  (:import [java.nio.file Files Path LinkOption]))

(deftest ownership-roots-are-sorted-overlap-safe-and-reserved
  (is (= ["a" "z"] (ownership/roots ["z/b" "a/b/c" "a/d" "a"])))
  (is (= :reserved-output (:reason (failure #(ownership/roots [".gate-output-locks/key"]))))))

(deftest same-root-contends-and-distinct-root-remains-independent
  (with-directory
    (fn [root]
      (let [lease (ownership/acquire! (str root) ["out/a"] 1000 (atom false))]
        (try
          (is (= :timed-out (:reason @(future (failure #(ownership/acquire! (str root) ["out/b"] 100 (atom false)))))))
          (is (= :acquired @(future (let [other (ownership/acquire! (str root) ["other/b"] 1000 (atom false))]
                                      (try :acquired (finally (ownership/release! other)))))))
          (finally (ownership/release! lease)))
        (let [again (ownership/acquire! (str root) ["out/b"] 1000 (atom false))]
          (is (some? again)) (ownership/release! again) (ownership/release! again))))))

(deftest partial-claim-failure-releases-earlier-roots
  (with-directory
    (fn [root]
      (let [lease (ownership/acquire! (str root) ["z/file"] 1000 (atom false))]
        (try
          (is (= :timed-out (:reason @(future (failure #(ownership/acquire! (str root) ["a/file" "z/file"] 100 (atom false)))))))
          (let [a (ownership/acquire! (str root) ["a/other"] 1000 (atom false))]
            (is (some? a)) (ownership/release! a))
          (finally (ownership/release! lease)))))))

(deftest cancelled-wait-does-not-retain-a-jvm-mutex
  (with-directory
    (fn [root]
      (is (= :cancelled (:reason (failure #(ownership/acquire! (str root) ["out/a"] 1000 (atom true))))))
      (let [lease (ownership/acquire! (str root) ["out/a"] 1000 (atom false))]
        (is (some? lease)) (ownership/release! lease)))))

(deftest independent-jvm-cannot-acquire-a-held-output-root
  (with-directory
    (fn [root]
      (let [lease (ownership/acquire! (str root) ["out/a"] 1000 (atom false))
            request {:directory (System/getProperty "user.dir") :cwd "."
                     :command [(str (System/getProperty "java.home") "/bin/java") "-cp" (System/getProperty "java.class.path")
                               "clojure.main" "-m" "gate.ownership-worker" (str root) "out/b"]
                     :environment {} :stdin nil :log-directory (str root) :log "worker.log"
                     :limits (assoc process/default-limits :timeout-ms 20000)}]
        (try
          (is (= :io (:reason (failure #(ownership/acquire! (str root) ["out/nested"] 100 (atom false))))))
          (let [observed (process/run! request (atom false))]
            (is (= :exited (:status observed)))
            (is (not= 0 (:exit observed)))
            (is (Files/exists (.resolve ^Path root "worker-started") (make-array LinkOption 0)))
            (is (not (Files/exists (.resolve ^Path root "worker-acquired") (make-array LinkOption 0))))
            (is (.contains (slurp (str root "/worker.log")) "Output ownership unavailable")))
          (finally (ownership/release! lease)))
        (let [observed (process/run! (assoc request :log "worker-after.log") (atom false))]
          (is (= 0 (:exit observed)))
          (is (Files/exists (.resolve ^Path root "worker-acquired") (make-array LinkOption 0))))))))
