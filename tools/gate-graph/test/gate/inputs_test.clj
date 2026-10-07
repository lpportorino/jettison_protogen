(ns gate.inputs-test
  "Real filesystem observations, content/membership pokes and conservative unsupported-input refusals."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.cache :as cache]
            [gate.canonical :as canonical]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [gate.run-test :as fixture]
            [gate.trace-test :refer [with-directory]]
            [malli.core :as m])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def evidence {:environment {"MODE" nil} :toolchains {"image" fixture/digest-a}})
(defn put! [^Path root relative text]
  (let [file (.resolve root ^String relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (spit (str file) text)
    file))
(defn observe [root gate] (inputs/observe! (str root) gate evidence inputs/default-limits))
(defn key-of [snapshot] (cache/input-key fixture/gate snapshot []))

(deftest actual-file-bytes-and-membership-have-a-canonical-key
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (let [snapshot (observe root fixture/gate)]
        (is (m/validate r/Snapshot snapshot))
        (is (= fixture/snapshot snapshot))
        (is (= (key-of fixture/snapshot) (key-of snapshot)))))))

(deftest content-is-rehashed-even-when-size-and-mtime-are-restored
  (with-directory
    (fn [root]
      (let [file (put! root "src/a.clj" "a")
            stamp (Files/getLastModifiedTime file (make-array LinkOption 0))
            before (observe root fixture/gate)]
        (spit (str file) "b")
        (Files/setLastModifiedTime file stamp)
        (let [after (observe root fixture/gate)]
          (is (:complete? after))
          (is (= fixture/digest-b (get-in after [:files 0 :digest])))
          (is (not= (key-of before) (key-of after))))))))

(deftest creation-deletion-rename-and-executable-mode-change-keys
  (with-directory
    (fn [root]
      (let [file (put! root "src/a.clj" "a")
            before (observe root fixture/gate)]
        (put! root "src/b.clj" "b")
        (is (not= (key-of before) (key-of (observe root fixture/gate))))
        (Files/delete (.resolve ^Path root "src/b.clj"))
        (is (= (key-of before) (key-of (observe root fixture/gate))))
        (Files/setPosixFilePermissions file (PosixFilePermissions/fromString "rwxr-xr-x"))
        (is (not= (key-of before) (key-of (observe root fixture/gate))))
        (Files/setPosixFilePermissions file (PosixFilePermissions/fromString "rw-r--r--"))
        (is (= (key-of before) (key-of (observe root fixture/gate))))
        (Files/move file (.resolve ^Path root "src/renamed.clj") (make-array java.nio.file.CopyOption 0))
        (is (not= (key-of before) (key-of (observe root fixture/gate))))))))

(deftest globs-exclusions-and-unrelated-files-have-explicit-semantics
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (put! root "src/nested/b.clj" "b")
      (put! root "src/a.txt" "ignored extension")
      (put! root "src/generated/c.clj" "generated")
      (let [gate (assoc fixture/gate :inputs [{:id "source" :kind :glob :path "src/**.clj"
                                               :required? true :exclude ["src/generated"]}])
            before (observe root gate)]
        (is (:complete? before))
        (is (= ["src/a.clj" "src/nested/b.clj"] (get-in before [:membership 0 :paths])))
        (put! root "elsewhere/new.clj" "unrelated")
        (put! root "src/generated/c.clj" "changed excluded bytes")
        (is (= before (observe root gate)))))))

(deftest optional-absence-remains-membership-and-required-absence-refuses
  (with-directory
    (fn [root]
      (let [gate (assoc fixture/gate :inputs [{:id "optional" :kind :file :path "optional" :required? false}])
            before (observe root gate)]
        (is (:complete? before))
        (is (= [{:selector "optional" :paths []}] (:membership before)))
        (is (= :missing-input (:reason (observe root (assoc-in gate [:inputs 0 :required?] true)))))
        (put! root "optional" "now present")
        (is (not= (cache/input-key gate before []) (cache/input-key gate (observe root gate) [])))))))

(deftest missing-toolchain-or-environment-is-not-complete-evidence
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (doseq [[given reason] [[(assoc evidence :toolchains {}) :unknown-toolchain]
                              [(assoc evidence :environment {}) :unsupported-input]]]
        (let [snapshot (inputs/observe! (str root) fixture/gate given inputs/default-limits)]
          (is (false? (:complete? snapshot)))
          (is (= reason (:reason snapshot)))
          (is (nil? (key-of snapshot))))))))

(deftest file-byte-entry-and-depth-budgets-refuse-instead-of-truncating
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "abcdef")
      (put! root "src/deep/b.clj" "b")
      (doseq [limits [(assoc inputs/default-limits :bytes 1) (assoc inputs/default-limits :files 1)
                      (assoc inputs/default-limits :entries 1) (assoc inputs/default-limits :depth 1)]]
        (let [snapshot (inputs/observe! (str root) fixture/gate evidence limits)]
          (is (= :budget-exhausted (:reason snapshot)))
          (is (false? (:complete? snapshot)))
          (is (nil? (key-of snapshot))))))))

(deftest symlinks-and-wrong-file-kinds-cannot-silently-disappear-from-the-key
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (Files/createSymbolicLink (.resolve ^Path root "src/link") (.resolve ^Path root "src/a.clj") (make-array FileAttribute 0))
      (is (= :unsupported-input (:reason (observe root fixture/gate))))
      (is (= :unsupported-input
             (:reason (observe root (assoc fixture/gate :inputs [{:id "link" :kind :file :path "src/link" :required? true}])))))
      (is (= :unsupported-input
             (:reason (observe root (assoc fixture/gate :inputs [{:id "directory" :kind :file :path "src" :required? true}]))))))))

(deftest malformed-glob-refuses-without-echoing-the-pattern
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (let [snapshot (observe root (assoc fixture/gate :inputs [{:id "bad" :kind :glob :path "src/[" :required? true}]))]
        (is (= :unsupported-input (:reason snapshot)))
        (is (m/validate r/Snapshot snapshot))))))

(deftest overlapping-selectors-hash-each-byte-once-and-keep-both-memberships
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "abc")
      (let [gate (update fixture/gate :inputs conj {:id "file" :kind :file :path "src/a.clj" :required? true})
            snapshot (inputs/observe! (str root) gate evidence (assoc inputs/default-limits :bytes 3 :files 1))]
        (is (:complete? snapshot))
        (is (= 2 (count (:membership snapshot))))
        (is (= 1 (count (:files snapshot))))
        (is (= (canonical/sha256 "abc") (get-in snapshot [:files 0 :digest])))))))

(deftest reordering-explicit-exclusions-does-not-overhash
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (let [gate (assoc-in fixture/gate [:inputs 0 :exclude] ["src/generated" "src/temp"])
            reordered (update-in gate [:inputs 0 :exclude] (comp vec reverse))]
        (is (= (cache/input-key gate (observe root gate) [])
               (cache/input-key reordered (observe root reordered) [])))
        (is (= (key-of (observe root fixture/gate))
               (cache/input-key (assoc-in fixture/gate [:inputs 0 :exclude] []) (observe root fixture/gate) [])))))))

(deftest membership-is-checked-again-after-reading-file-bytes
  (with-directory
    (fn [root]
      (put! root "src/a.clj" "a")
      (let [hash-var (ns-resolve 'gate.inputs 'hash-file) original @hash-var
            snapshot (with-redefs-fn {hash-var (fn [& arguments]
                                                 (let [result (apply original arguments)]
                                                   (put! root "src/appeared.clj" "b") result))}
                       #(observe root fixture/gate))]
        (is (= :unstable-input (:reason snapshot)))
        (is (nil? (key-of snapshot)))))))

(deftest generated-filesystem-membership-agrees-with-independent-path-oracle
  (let [names ["src/a.clj" "src/nested/b.clj" "src/note.txt" "src/generated/c.clj" "elsewhere/d.clj" "src/e.clj"]
        gate (assoc fixture/gate :inputs [{:id "source" :kind :glob :path "src/**.clj"
                                           :exclude ["src/generated"] :required? false}])
        property (prop/for-all [present (gen/vector gen/boolean 6)]
                               (with-directory
                                 (fn [root]
                                   (doseq [[i path included?] (map vector (range) names present) :when included?]
                                     (put! root path (str "value-" i)))
                                   (let [expected (->> (map vector names present)
                                                       (keep (fn [[path included?]]
                                                               (when (and included? (str/starts-with? path "src/")
                                                                          (str/ends-with? path ".clj")
                                                                          (not (str/starts-with? path "src/generated/"))) path)))
                                                       sort vec)
                                         snapshot (observe root gate)]
                                     (and (:complete? snapshot)
                                          (= expected (get-in snapshot [:membership 0 :paths]))
                                          (= expected (mapv :path (:files snapshot))))))))
        result (tc/quick-check 100 property :seed 20261009)]
    (is (:pass? result) (pr-str result))))
