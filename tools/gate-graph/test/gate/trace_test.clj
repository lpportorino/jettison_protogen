(ns gate.trace-test
  "Independent v1 journal examples exercise framing, provenance and work coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [gate.canonical :as canonical]
            [gate.graph :as graph]
            [gate.trace-contract :as t]
            [gate.trace-import :as importer]
            [gate.trace-io :as trace-io]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.generator :as mg])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def run-id "123456789abcdef0123456789abcdef0")
(def root-id "123456789abcdef0")
(def span-id "223456789abcdef0")
(def origin 9007199254740993)
(def revision (apply str (repeat 40 "a")))
(def inventory [{:name "compile" :key "gate/compile"}])
(def header
  {"schema" 1 "record" "run" "run_id" run-id "root_span_id" root-id
   "entrypoint" "check" "started_unix_ns" 1700000000000000000 "started_mono_ns" origin
   "vcs" {"revision" revision "dirty" false "dirty_digest" nil "submodules" {}
          "changeset" {"kind" "worktree" "base" revision "tip" nil "paths" [] "path_count" 0}}
   "toolchain" {"image" "synthetic-v1"}
   "host" {"os" "Linux" "kernel" "synthetic" "machine" "x86_64" "cpus" 8
           "cpu_model" nil "name" nil}})
(def start
  {"schema" 1 "record" "span-start" "trace_id" run-id "span_id" span-id
   "parent_span_id" root-id "name" "compile" "kind" "gate" "start_mono_ns" (+ origin 100)})
(def final-span
  (merge start {"record" "span" "end_mono_ns" (+ origin 400) "verdict" "success"
                "exit" {"status" "exited" "code" 0}
                "rusage" {"user_us" 7 "sys_us" 3 "maxrss_kib" 42 "minflt" 0 "majflt" 0
                          "inblock" 0 "oublock" 0 "nvcsw" 2 "nivcsw" 1}
                "signals_forwarded" {"SIGHUP" 0 "SIGTERM" 0}
                "attributes" {"process.pid" 123 "process.executable.name" "clojure"}}))
(def end-record
  {"schema" 1 "record" "run-end" "run_id" run-id
   "ended_unix_ns" 1700000000000000500 "ended_mono_ns" (+ origin 500)})

(defn line [record] (str (json/write-value-as-string record) "\n"))
(defn entry [path record] {:path path :record record :digest (canonical/sha256 (line record))})
(defn journal [] [(entry "run.json" header) (entry "end.json" end-record)
                  (entry (str "spans/" span-id ".start.json") start)
                  (entry (str "spans/" span-id ".span.json") final-span)])
(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(defn changed [j index path value] (assoc-in j (into [index :record] path) value))
(defn with-directory [f]
  (let [root (Files/createTempDirectory "gate-trace-test-" (make-array FileAttribute 0))]
    (try (f root)
         (finally
           (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
             (doseq [path (reverse (sort (iterator-seq (.iterator paths))))]
               (Files/deleteIfExists path)))))))
(defn write-journal [root]
  (Files/createDirectory (.resolve ^Path root "spans") (make-array FileAttribute 0))
  (doseq [{:keys [path record]} (journal)] (spit (str (.resolve ^Path root path)) (line record))))

(deftest exact-projection-retains-work-coverage-and-inclusive-peaks
  (let [result (importer/project (journal) inventory)
        task (first (filter #(= span-id (:id %)) (:nodes result)))
        by-quantity (into {} (map (juxt :quantity identity) (:measurements result)))]
    (is (= [] (graph/findings result)))
    (is (= {:start-ns "100" :end-ns "400"} (:interval task)))
    (is (= (str origin) (get-in result [:run :clock :origin-ns])))
    (is (= "500" (get-in result [:run :end-ns])))
    (is (= "7000" (get-in by-quantity [:cpu-user-ns :value])))
    (is (= "3000" (get-in by-quantity [:cpu-system-ns :value])))
    (is (= "43008" (get-in by-quantity [:rss-peak-bytes :value])))
    (is (every? #(= :inclusive (:accounting %)) (:measurements result)))
    (is (every? #(= (:interval task) (:interval %)) (:measurements result)))
    (is (= :peak (get-in by-quantity [:rss-peak-bytes :form])))
    (is (= result (importer/project (vec (reverse (journal))) inventory)))))

(deftest unfinished-work-is-never-promoted-to-complete-by-an-end-stamp
  (doseq [j [(subvec (journal) 0 3) [(first (journal)) (nth (journal) 2)]]]
    (let [result (importer/project j inventory)]
      (is (false? (get-in result [:run :complete?])))
      (is (nil? (get-in result [:run :end-ns])))
      (is (every? #(= :running (:outcome %)) (:nodes result)))
      (is (empty? (:measurements result))))))

(deftest inventory-is-independent-of-observation
  (is (= :trace-inventory (:code (failure #(importer/project (journal) [{:name "other" :key "other"}])))))
  (is (= :trace-inventory (:code (failure #(importer/project (journal) (conj inventory (first inventory)))))))
  (let [error (failure #(importer/project (journal) (conj inventory {:name "missing" :key "missing"})))]
    (is (= :invalid-graph (:code error)))
    (is (some #(= :missing-expected-key (:code %)) (:findings error)))))

(deftest identity-framing-and-final-consistency-refusals-are-named
  (doseq [[j code] [[(conj (journal) (first (journal))) :trace-layout]
                    [(changed (journal) 1 ["run_id"] (apply str (repeat 32 "b"))) :trace-identity]
                    [(changed (journal) 2 ["trace_id"] (apply str (repeat 32 "b"))) :trace-identity]
                    [(changed (journal) 3 ["name"] "renamed") :trace-start-mismatch]
                    [(vec (concat (subvec (journal) 0 2) [(last (journal))])) :trace-start-mismatch]
                    [(changed (journal) 3 ["verdict"] "failure") :trace-exit]
                    [(changed (journal) 3 ["attributes" "process.pid"] nil) :trace-exit]
                    [(changed (journal) 3 ["rusage"] nil) :trace-exit]
                    [(changed (journal) 1 ["ended_mono_ns"] (+ origin 399)) :trace-clock]]]
    (let [error (failure #(importer/project j inventory))]
      (is (= code (:code error)))
      (is (m/validate t/Failure error)))))

(deftest spawn-failure-has-no-fabricated-work-measurements
  (let [span (assoc final-span "verdict" "error" "exit" {"status" "spawn-failed" "code" 127 "errno" "ENOENT"}
                    "rusage" nil "attributes" {"process.pid" nil "process.executable.name" "missing"})
        result (importer/project (assoc-in (journal) [3 :record] span) inventory)]
    (is (empty? (:measurements result)))
    (is (empty? (:resources result)))
    (is (= #{:failed :error} (set (map :outcome (:nodes result)))))))

(deftest bounded-diagnostics-have-schema-paths-without-raw-values
  (let [error (failure #(trace-io/parse-record (line (assoc start "start_mono_ns" "secret-value")) "record"))]
    (is (= :trace-shape (:code error)))
    (is (= [{:in ["start_mono_ns"] :type :invalid-value}] (:issues error)))
    (is (m/validate t/Failure error))
    (is (not (.contains (pr-str error) "secret-value")))))

(deftest malformed-json-never-gets-permissive-reader-behavior
  (doseq [text [(json/write-value-as-string start) (str (line start) (line start))
                "{\"record\":\"span\",\"record\":\"run\"}\n" "{} {}\n"
                "{\"x\":NaN}\n" "{\"x\":999999999999999999999999999}\n"
                (str (apply str (repeat 34 "[")) "0" (apply str (repeat 34 "]")) "\n")]]
    (is (= :trace-json (:code (failure #(trace-io/parse-record text "record")))))))

(deftest identifier-grammars-consume-the-entire-string
  (doseq [[schema value] [[t/TraceId run-id] [t/SpanId span-id] [t/Revision revision]
                          [t/Name "compile"] [t/Token "check"]]]
    (is (m/validate schema value))
    (is (not (m/validate schema (str value "\n"))))))

(deftest malli-generated-counters-survive-json-and-exact-unit-conversion
  (doseq [counter (mg/sample t/Counter {:seed 20261007 :size 80})]
    (let [span (assoc-in final-span ["rusage" "user_us"] counter)
          decoded (trace-io/parse-record (line span) "record")
          result (importer/project (assoc-in (journal) [3 :record] decoded) inventory)]
      (is (= counter (get-in decoded ["rusage" "user_us"])))
      (is (= (str (*' counter 1000))
             (:value (first (filter #(= :cpu-user-ns (:quantity %)) (:measurements result)))))))))

(deftest filesystem-loading-preserves-digests-and-refuses-links-or-invalid-bytes
  (with-directory
    (fn [root]
      (write-journal root)
      (is (= (sort-by :path (journal)) (trace-io/load-journal (str root))))
      (let [path (.resolve ^Path root "end.json")]
        (Files/delete path)
        (Files/createSymbolicLink path (.resolve ^Path root "run.json") (make-array FileAttribute 0))
        (is (= :trace-file-type (:code (failure #(trace-io/load-journal (str root))))))
        (Files/delete path)
        (Files/write path (byte-array [(unchecked-byte 0xc0) (unchecked-byte 0xaf) 10])
                     (make-array java.nio.file.OpenOption 0))
        (is (= :trace-json (:code (failure #(trace-io/load-journal (str root))))))))))

(deftest filesystem-limits-and-unknown-layout-refuse-before-projection
  (doseq [[filename contents code] [["end.json" (apply str (repeat 1048577 "x")) :trace-byte-limit]
                                    ["unexpected.json" "{}\n" :trace-layout]
                                    [(str "spans/" span-id ".attr.123456789abcdef0.json") "{}\n"
                                     :trace-unsupported-annotation]]]
    (testing filename
      (with-directory (fn [root]
                        (write-journal root)
                        (spit (str (.resolve ^Path root filename)) contents)
                        (is (= code (:code (failure #(trace-io/load-journal (str root)))))))))))
