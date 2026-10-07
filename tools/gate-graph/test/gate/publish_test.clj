(ns gate.publish-test
  "Filesystem publication controls: preserve old files until verified staging, expose partial batches."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.publish :as publish]
            [gate.run-test :as fixture]
            [gate.trace-test :refer [with-directory]])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn setup [root]
  (let [source (.resolve ^Path root "source") destination (.resolve ^Path root "destination")]
    (files/put! source "out/result.edn" "a")
    (files/put! destination "out/result.edn" "old")
    [source destination]))
(defn install
  ([source destination] (install source destination fixture/gate fixture/outputs))
  ([source destination gate expected]
   (publish/install! (str source) (str destination) gate files/evidence inputs/default-limits expected)))
(defn temporaries [root]
  (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
    (vec (filter #(.startsWith (str (.getFileName ^Path %)) ".gate-output-") (iterator-seq (.iterator paths))))))

(deftest verified-files-replace-old-bytes-and-preserve-unlisted-files
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)]
        (files/put! destination "out/keep" "keep")
        (let [result (install source destination)]
          (is (= {:status :installed :installed ["out/result.edn"] :reason nil} result))
          (is (= "a" (slurp (str destination "/out/result.edn"))))
          (is (= "keep" (slurp (str destination "/out/keep"))))
          (is (= result (admission/decode (canonical/encode result 4096) :output-publication admission/default-limits)))
          (is (empty? (temporaries destination))))))))

(deftest stale-source-evidence-refuses-without-touching-old-output
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)]
        (files/put! source "out/result.edn" "b")
        (is (= {:status :refused :installed [] :reason :output-verification} (install source destination)))
        (is (= "old" (slurp (str destination "/out/result.edn"))))
        (is (empty? (temporaries destination)))))))

(deftest unavailable-source-observation-refuses-before-staging
  (with-directory
    (fn [root]
      (let [[source destination] (setup root) observe inputs/outputs!]
        (with-redefs [inputs/outputs! (fn [directory & args]
                                        (when-not (= directory (str source)) (apply observe directory args)))]
          (is (= :refused (:status (install source destination)))))
        (is (= "old" (slurp (str destination "/out/result.edn"))))))))

(deftest bytes-changing-after-source-observation-refuse-before-replacement
  (with-directory
    (fn [root]
      (let [[source destination] (setup root) observe inputs/outputs!]
        (with-redefs [inputs/outputs! (fn [directory & args]
                                        (let [result (apply observe directory args)]
                                          (when (= directory (str source)) (files/put! source "out/result.edn" "b"))
                                          result))]
          (is (= :refused (:status (install source destination)))))
        (is (= "old" (slurp (str destination "/out/result.edn"))))
        (is (empty? (temporaries destination)))))))

(deftest output-copy-budget-remains-enforced-after-observation
  (with-directory
    (fn [root]
      (let [[source destination] (setup root) observe inputs/outputs!]
        (with-redefs [inputs/outputs! (fn [directory & args]
                                        (let [result (apply observe directory args)]
                                          (when (= directory (str source)) (files/put! source "out/result.edn" "ab"))
                                         ;; Only the initial source observer is real here: isolate the copy's own budget guard.
                                          (if (= directory (str source)) result
                                              [(assoc (first fixture/outputs) :path (first (:outputs (first args))))])))]
          (is (= :refused (:status (publish/install! (str source) (str destination) fixture/gate files/evidence
                                                     (assoc inputs/default-limits :bytes 1) fixture/outputs)))))
        (is (= "old" (slurp (str destination "/out/result.edn"))))))))

(deftest later-staging-failure-preserves-all-existing-outputs
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)
            gate (assoc fixture/gate :outputs ["out/result.edn" "out/z.edn"])]
        (files/put! source "out/z.edn" "b")
        (Files/createDirectory (.resolve ^Path destination "out/z.edn") (make-array FileAttribute 0))
        (let [expected (inputs/outputs! (str source) gate files/evidence inputs/default-limits)]
          (is (= :refused (:status (install source destination gate expected)))))
        (is (= "old" (slurp (str destination "/out/result.edn"))))
        (is (empty? (temporaries destination)))))))

(deftest failed-second-move-reports-exact-installed-subset
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)
            gate (assoc fixture/gate :outputs ["out/result.edn" "out/z.edn"])
            replace-var (ns-resolve 'gate.publish 'replace!) replace-file @replace-var calls (atom 0)]
        (files/put! source "out/z.edn" "b")
        (files/put! destination "out/z.edn" "old-z")
        (let [expected (inputs/outputs! (str source) gate files/evidence inputs/default-limits)
              result (with-redefs-fn {replace-var (fn [temporary target]
                                                    (if (= 2 (swap! calls inc)) (throw (java.io.IOException. "fixture"))
                                                        (replace-file temporary target)))}
                       #(install source destination gate expected))]
          (is (= {:status :partial :installed ["out/result.edn"] :reason :output-io} result))
          (is (= "a" (slurp (str destination "/out/result.edn"))))
          (is (= "old-z" (slurp (str destination "/out/z.edn"))))
          (is (empty? (temporaries destination))))))))

(deftest final-output-verification-cannot-be-replaced-by-staging-evidence
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)
            replace-var (ns-resolve 'gate.publish 'replace!) replace-file @replace-var
            result (with-redefs-fn {replace-var (fn [temporary target]
                                                  (replace-file temporary target)
                                                  (spit (str target) "changed-after-install") nil)}
                     #(install source destination))]
        (is (= {:status :partial :installed ["out/result.edn"] :reason :output-verification} result))))))

(deftest destination-links-never-write-through-to-another-file
  (doseq [ancestor? [false true]]
    (with-directory
      (fn [root]
        (let [[source destination] (setup root) outside (.resolve ^Path root "outside")]
          (files/put! outside "result.edn" "protected")
          (Files/delete (.resolve ^Path destination "out/result.edn"))
          (when ancestor? (Files/delete (.resolve ^Path destination "out")))
          (Files/createSymbolicLink (.resolve ^Path destination (if ancestor? "out" "out/result.edn"))
                                    (if ancestor? outside (.resolve outside "result.edn")) (make-array FileAttribute 0))
          (is (= :refused (:status (install source destination))))
          (is (= "protected" (slurp (str outside "/result.edn")))))))))

(deftest executable-mode-and-new-output-parents-are-published
  (with-directory
    (fn [root]
      (let [[source destination] (setup root)]
        (Files/setPosixFilePermissions (.resolve ^Path source "out/result.edn") (PosixFilePermissions/fromString "rwxr-xr-x"))
        (Files/delete (.resolve ^Path destination "out/result.edn"))
        (Files/delete (.resolve ^Path destination "out"))
        (is (= :installed (:status (install source destination fixture/gate (assoc-in fixture/outputs [0 :executable?] true)))))
        (is (= (PosixFilePermissions/fromString "rwxr-xr-x")
               (Files/getPosixFilePermissions (.resolve ^Path destination "out/result.edn") (make-array LinkOption 0))))))))
