(ns gate.test-artifact-test
  "Independent producer controls for source stability, output placement, admission and create-only writes."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.clojure-test :as observer]
            [gate.run-contract :as r]
            [gate.test-artifact :as artifact]
            [gate.test-graph :as projection]
            [malli.core :as m])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def digest-a (apply str (repeat 64 "a")))
(def digest-b (apply str (repeat 64 "b")))
(def options {:run "artifact-run" :gate "gate/producer" :label "Synthetic producer"
              :max-tests 10 :max-assertions 100})

(defn sample
  "Return synthetic captured work to exercise publication independently of the real test observer."
  [policy _ _]
  (let [inventory [{:key "test/example" :label "Synthetic test"}]
        executions [{:id "test-0" :key "test/example" :label "Synthetic test" :parent nil :attempt 1
                     :interval {:start-ns "0" :end-ns "1"} :counts {:pass 1 :fail 0 :error 0} :returned? true}]
        unattributed {:pass 0 :fail 0 :error 0}]
    (merge (select-keys policy [:run :gate :label :source-digest])
           {:schema/version 1 :inventory inventory :executions executions :unattributed unattributed :duration-ns "1"}
           (projection/judge inventory executions unattributed []))))
(m/=> sample [:=> [:cat observer/Options observer/Namespaces observer/Runner] r/TestObservation])

(defn with-directory
  "Own a disposable producer fixture and delete only its tree, without following symbolic links."
  [f]
  (let [directory (Files/createTempDirectory "test-artifact-control-" (make-array FileAttribute 0))]
    (try (f directory)
         (finally
           (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
             (doseq [path (reverse (sort-by str (iterator-seq (.iterator paths))))]
               (Files/deleteIfExists ^Path path))))))
  nil)
(m/=> with-directory [:=> [:cat [:=> [:cat [:fn #(instance? Path %)]] [:fn (constantly true)]]] :nil])

(defn failure
  "Return only structured producer refusals, allowing unexpected native failures to surface."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(m/=> failure [:=> [:cat [:=> [:cat] [:fn (constantly true)]]] [:maybe artifact/Failure]])

(defn publish
  "Publish synthetic work under one owned fixture; actual module commands use the real observer."
  [directory]
  (artifact/run! options '[example.observed-suite] [(str directory)] (str directory "-report") (fn [] nil)))
(m/=> publish [:=> [:cat [:fn #(instance? Path %)]] r/TestObservation])

(deftest source-change-preserves-executions-and-refuses-coverage
  (with-directory
    (fn [directory]
      (let [source (Files/createDirectory (.resolve ^Path directory "source") (make-array FileAttribute 0))
            calls (atom 0)]
        (with-redefs [observer/run-with! sample
                      artifact/source-digest (fn [_] (if (= 1 (swap! calls inc)) digest-a digest-b))]
          (let [result (publish source)
                graph (admission/decode (slurp (str source "-report/graph.edn")) :graph admission/default-limits)]
            (is (= :error (:status result)))
            (is (nil? (:coverage result)))
            (is (= 1 (count (:executions result))))
            (is (some #{:source-changed} (:problems result)))
            (is (false? (get-in graph [:run :complete?])))))))))

(deftest stable-source-publishes-both-records-and-existing-run-refuses
  (with-directory
    (fn [directory]
      (let [source (Files/createDirectory (.resolve ^Path directory "source") (make-array FileAttribute 0))]
        (with-redefs [observer/run-with! sample artifact/source-digest (constantly digest-a)]
          (let [result (publish source)
                text (slurp (str source "-report/tests.edn"))
                graph (admission/decode (slurp (str source "-report/graph.edn")) :graph admission/default-limits)]
            (is (= :passed (:status result)))
            (is (= result (admission/decode text :test-observation admission/default-limits)))
            (is (= 2 (count (:nodes graph))))
            (is (= :report-io (:code (failure #(publish source)))))
            (is (= text (slurp (str source "-report/tests.edn"))))))))))

(deftest source-directory-and-symlink-alias-cannot-contain-report-output
  (with-directory
    (fn [directory]
      (let [source (Files/createDirectory (.resolve ^Path directory "source") (make-array FileAttribute 0))
            source-alias (.resolve ^Path directory "alias")
            direct (.resolve source "direct-report")
            aliased (.resolve source-alias "alias-report")]
        (Files/createSymbolicLink source-alias (.toRealPath source (make-array LinkOption 0)) (make-array FileAttribute 0))
        (is (= :report-path (:code (failure #(#'artifact/output-directory! [(str source)] (str direct))))))
        (is (= :report-path (:code (failure #(#'artifact/output-directory! [(str source)] (str aliased))))))
        (is (not (Files/exists direct (make-array LinkOption 0))))
        (is (not (Files/exists (.resolve source "alias-report") (make-array LinkOption 0))))))))

(deftest record-publication-requires-admission-parity-and-create-only-files
  (with-directory
    (fn [directory]
      (let [record (sample (assoc options :source-digest digest-a) '[example.observed-suite] (fn [] nil))]
        (#'artifact/write-record! (str directory) "tests.edn" record :test-observation)
        (let [text (slurp (str directory "/tests.edn"))]
          (is (thrown? java.nio.file.FileAlreadyExistsException
                       (#'artifact/write-record! (str directory) "tests.edn" (assoc record :run "changed-run") :test-observation)))
          (is (= text (slurp (str directory "/tests.edn")))))
        (with-redefs [admission/decode (fn [_ _ _] nil)]
          (is (= :report-roundtrip
                 (:code (failure #(#'artifact/write-record! (str directory) "graph.edn" (projection/project record) :graph))))))
        (is (not (Files/exists (.resolve ^Path directory "graph.edn") (make-array LinkOption 0))))))))
