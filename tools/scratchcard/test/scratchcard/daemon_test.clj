(ns scratchcard.daemon-test
  "Pins the daemon's staleness detection.

  WHY THIS EXISTS, measured rather than imagined: a path-traversal fix was
  verified against a WARM daemon still running the pre-fix code. The traversal
  succeeded, and the only symptom was a security fix appearing not to work.
  A daemon designed never to restart has no other signal that the operator's
  edit is not in effect."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.daemon :as daemon]
   [scratchcard.scope :as scope])
  (:import
   (java.io File)))

(def ^:private repo-root
  (scope/discover-repo-root (System/getProperty "user.dir")))

(deftest source-sha-covers-the-code-the-daemon-actually-runs
  (testing "both roots are named — devcards supplies the host and the lanes"
    (is (some #(str/includes? % "scratchcard") daemon/source-roots))
    (is (some #(str/includes? % "devcards") daemon/source-roots)))
  (testing "every named root exists and holds Clojure — a dark root would make\n           the digest a function of nothing"
    (doseq [r daemon/source-roots]
      (let [d (io/file repo-root r)]
        (is (.isDirectory d) (str r " is not a directory"))
        (is (seq (filter #(and (.isFile ^File %)
                               (str/ends-with? (.getName ^File %) ".clj"))
                         (file-seq d)))
            (str r " holds no .clj files"))))))

(deftest source-sha-is-deterministic-and-content-addressed
  (let [a (daemon/source-sha repo-root)
        b (daemon/source-sha repo-root)]
    (is (re-matches #"[0-9a-f]{64}" a))
    (testing "stable across calls — a digest that varied with directory
              iteration order would make a daemon look stale to itself"
      (is (= a b)))))

(deftest an-unknown-baseline-is-reported-as-UNKNOWN-not-as-CLEAN
  ;; `booted-src-sha` is nil outside a running daemon. Reporting that as
  ;; `:stale? false` alone would be indistinguishable from a genuinely fresh
  ;; daemon — "I could not look" and "nothing to report" as the same answer,
  ;; which is what this repo refuses everywhere else.
  (let [s (daemon/staleness repo-root)]
    (is (false? (:known? s)))
    (is (false? (:stale? s)))))
