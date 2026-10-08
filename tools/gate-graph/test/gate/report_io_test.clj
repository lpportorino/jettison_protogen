(ns gate.report-io-test
  "Real-file admission checks for the consumer inspection boundary."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.fixtures :as fixtures]
            [gate.report-io :as report-io]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn with-file
  "Give one test an owned temporary file and remove it on every exit."
  [f]
  (let [file (Files/createTempFile "gate-report-" ".edn" (make-array FileAttribute 0))]
    (try (f file) (finally (Files/deleteIfExists file)))))

(defn write-bytes!
  "Write exact fixture bytes, including malformed UTF-8 when requested."
  [file content]
  (Files/write ^Path file ^bytes content (make-array OpenOption 0)))

(defn failure
  "Keep only structured refusal data, never use a broad exception match as proof."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest byte-budget-and-strict-utf8-bound-the-actual-read
  (with-file
    (fn [file]
      (let [graph (assoc-in (fixtures/example) [:nodes 0 :label] "λ")
            text (pr-str graph)
            content (.getBytes text StandardCharsets/UTF_8)
            limit (alength content)]
        (write-bytes! file content)
        (is (= graph (report-io/read-graph! (str file) (assoc admission/default-limits :bytes limit))))
        (is (= {:code :report-byte-limit}
               (failure #(report-io/read-graph! (str file) (assoc admission/default-limits :bytes (dec limit))))))
        (write-bytes! file (byte-array [(unchecked-byte 195) (unchecked-byte 40)]))
        (is (= {:code :report-utf8}
               (failure #(report-io/read-graph! (str file) admission/default-limits))))))))

(deftest graph-admission-is-not-bypassed-by-file-loading
  (with-file
    (fn [file]
      (spit (str file) (pr-str (assoc-in (fixtures/example) [:nodes 1 :parent] "absent")))
      (is (= :invalid-graph (:code (failure #(report-io/read-graph! (str file) admission/default-limits)))))
      (spit (str file) (str (canonical/encode (fixtures/example) 65536) " {}"))
      (is (= :trailing-input (:code (failure #(report-io/read-graph! (str file) admission/default-limits))))))))

(deftest missing-files-and-nonregular-inputs-have-closed-errors
  (with-file
    (fn [file]
      (Files/delete file)
      (is (= {:code :report-unreadable}
             (failure #(report-io/read-graph! (str file) admission/default-limits))))
      (is (= {:code :report-unreadable}
             (failure #(report-io/read-graph! (str (.getParent ^Path file)) admission/default-limits)))))))

(deftest symlink-leaves-refuse-without-reading-the-target
  (with-file
    (fn [target]
      (spit (str target) (pr-str (fixtures/example)))
      (with-file
        (fn [link]
          (Files/delete link)
          (Files/createSymbolicLink link target (make-array FileAttribute 0))
          (is (= {:code :report-unreadable}
                 (failure #(report-io/read-graph! (str link) admission/default-limits)))))))))

(deftest a-refusal-names-the-kind-it-expected-and-the-file-it-read
  ;; A reader handed the wrong document (a run REPORT where a run ARCHIVE is
  ;; required) must say which kind it wanted and which file it read, so the caller
  ;; can tell "wrong file" from "damaged file". Only the basename travels: the
  ;; directory can be private, and refusals never echo private input.
  (let [dir (Files/createTempDirectory "gate-refusal-" (make-array FileAttribute 0))
        file (.resolve ^Path dir "report.edn")]
    (try
      (spit (str file) (canonical/encode (fixtures/example) 65536))
      (let [result (failure #(report-io/read-archive! (str file) admission/default-limits))]
        (is (= :invalid-input-shape (:code result)))
        (is (= :run-archive (:expected-kind result)))
        (is (= "report.edn" (:file result)))
        (is (m/validate c/Failure result) "the refusal still satisfies the exported closed contract")
        (is (not (.contains (pr-str result) (str dir))) "the directory never travels"))
      (finally (Files/deleteIfExists file) (Files/deleteIfExists dir)))))

(deftest a-refusal-file-is-only-ever-a-basename
  ;; A caller can hand any string as a path; only a separator-free basename may
  ;; travel. A backslash path must not pass through whole on a POSIX host.
  (let [dir (Files/createTempDirectory "gate-refusal-" (make-array FileAttribute 0))
        file (.resolve ^Path dir "a\\b\\report.edn")]
    (try
      (spit (str file) (canonical/encode (fixtures/example) 65536))
      (let [result (failure #(report-io/read-archive! (str file) admission/default-limits))]
        (is (= :run-archive (:expected-kind result)))
        (is (= "report.edn" (:file result)) "both separators are stripped")
        (is (m/validate c/Failure result)))
      (finally (Files/deleteIfExists file) (Files/deleteIfExists dir)))))
