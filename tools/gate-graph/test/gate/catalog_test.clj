(ns gate.catalog-test
  "Complete immediate metadata discovery, exact name admission and bounded shared traversal."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.catalog-contract :as cc]
            [gate.catalog-identity :as identity]
            [gate.catalog-io :as catalog]
            [gate.record-view :as view]
            [gate.record-view-test :as traversal]
            [gate.report-io :as report]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))
(def limits {:entries 100 :bytes 1048576})
(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))
(defn with-directory [f]
  (let [directory (Files/createTempDirectory "gate-catalog-" (make-array FileAttribute 0))]
    (try (f directory)
         (finally (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
                    (doseq [path (reverse (sort (iterator-seq (.iterator paths))))] (Files/deleteIfExists path)))))))
(defn write-file [^Path directory name-text]
  (let [path (.resolve directory ^String name-text)] (spit (str path) "not admitted EDN") path))
(defn fixture [^Path directory]
  (doseq [name-text ["z-unknown.bin" "report.edn" "run.edn" "space : tree.txt" "line\nbreak.txt"]] (write-file directory name-text))
  (let [child (Files/createDirectory (.resolve directory "nested") (make-array FileAttribute 0))]
    (write-file child "hidden-child.txt"))
  (Files/createSymbolicLink (.resolve directory "broken-link") (.resolve directory "absent") (make-array FileAttribute 0)))

(deftest complete-catalog-preserves-unknowns-links-directories-and-exact-names
  (with-directory
    (fn [directory]
      (fixture directory)
      (let [document (catalog/open! (str directory) limits) entries (:entries document)
            by-name (into {} (map (juxt :name clojure.core/identity) entries))
            context (view/prepare :artifact-catalog document)]
        (is (= 7 (count entries)))
        (is (= ["broken-link" "line\nbreak.txt" "nested" "report.edn" "run.edn" "space : tree.txt" "z-unknown.bin"] (mapv :name entries)))
        (is (= :symlink (:type (by-name "broken-link"))))
        (is (= :directory (:type (by-name "nested"))))
        (is (= [:process-batch-report :test-batch-report] (:hints (by-name "report.edn"))))
        (is (= [:run-archive] (:hints (by-name "run.edn"))))
        (is (= [] (:hints (by-name "z-unknown.bin")) (:hints (by-name "broken-link")) (:hints (by-name "nested"))))
        (is (every? #(= :unopened (:status %)) entries))
        (is (every? #(= (str (.resolve ^Path directory ^String (:name %))) (:path %)) entries))
        (is (= (traversal/oracle document) (traversal/traversed context {:visits 1 :rows 1 :bytes 4096})))
        (is (not-any? #(= "hidden-child.txt" (:name %)) entries))
        (is (= ["hidden-child.txt"] (mapv :name (:entries (catalog/open! (str (.resolve ^Path directory "nested")) limits)))))
        (is (= :catalog-unreadable (failure #(catalog/open! (str (.resolve ^Path directory "broken-link")) limits))))))))

(deftest catalog-hints-never-admit-content-and-file-edits-do-not-change-metadata-identity
  (with-directory
    (fn [directory]
      (let [file (write-file directory "run.edn") first-catalog (catalog/open! (str directory) limits)]
        (is (thrown? clojure.lang.ExceptionInfo (report/read-record! (str file) :run-archive admission/default-limits)))
        (spit (str file) "different arbitrary contents")
        (is (= (:artifact first-catalog) (:artifact (catalog/open! (str directory) limits))))
        (Files/delete file)
        (Files/createDirectory file (make-array FileAttribute 0))
        (let [changed (catalog/open! (str directory) limits)]
          (is (not= (:artifact first-catalog) (:artifact changed)))
          (is (= [] (get-in changed [:entries 0 :hints])))
          (is (= :directory (get-in changed [:entries 0 :type]))))))))

(deftest listing-cursors-bind-the-metadata-snapshot-not-current-filesystem
  (with-directory
    (fn [directory]
      (write-file directory "a") (write-file directory "b")
      (let [before (view/prepare :artifact-catalog (catalog/open! (str directory) limits))
            request {:select {:op :children :path [:entries]} :budget {:visits 1 :rows 1 :bytes 4096}}
            page (view/page before request)]
        (write-file directory "c")
        (let [after (view/prepare :artifact-catalog (catalog/open! (str directory) limits))]
          (is (= :invalid-record-cursor (failure #(view/page after (assoc request :cursor (:next page))))))
          (is (= [:entries 1] (get-in (view/page before (assoc request :cursor (:next page))) [:rows 0 :path])))
          (is (= 2 (:children (view/page before request))))
          (is (= 3 (:children (view/page after request)))))))))

(deftest hard-input-limits-refuse-complete-inventories-instead-of-dropping-entries
  (with-directory
    (fn [directory]
      (doseq [index (range 12)] (write-file directory (str index "-" (apply str (repeat 100 "x")))))
      (let [document (catalog/open! (str directory) limits)
            required (canonical/utf8-size (canonical/encode document 1048576))]
        (is (> required 1024))
        (is (= document (catalog/open! (str directory) (assoc limits :entries 12 :bytes required))))
        (is (= :catalog-byte-limit (failure #(catalog/open! (str directory) (assoc limits :bytes (dec required))))))
        (is (= :catalog-entry-limit (failure #(catalog/open! (str directory) (assoc limits :entries 11))))))
      (doseq [bad [(assoc limits :entries 0) (assoc limits :bytes 1023) (assoc limits :unexpected true)]]
        (is (not (m/validate cc/Limits bad)))
        (is (thrown? clojure.lang.ExceptionInfo (catalog/open! (str directory) bad)))))))

(deftest empty-catalog-and-explicit-known-record-read-remain-fully-traversable
  (with-directory
    (fn [directory]
      (let [document (catalog/open! (str directory) limits)
            context (view/prepare :artifact-catalog document)
            file (.resolve ^Path directory "catalog.edn")]
        (is (= [] (:entries document)))
        (is (= (traversal/oracle document) (traversal/traversed context traversal/budget)))
        (spit (str file) (canonical/encode document 65536))
        (is (= document (report/read-record! (str file) :artifact-catalog admission/default-limits)))
        (is (not= (:artifact document) (:artifact (catalog/open! (str directory) limits))))))))

(deftest catalog-seal-rejects-tampered-paths-duplicate-unsorted-names-and-identity
  (let [material {:schema/version 1 :profile :posix-directory-v1 :directory "/library"
                  :entries [{:name "a" :path "/library/a" :type :file :status :unopened :hints []}
                            {:name "b" :path "/library/b" :type :directory :status :unopened :hints []}]}
        document (identity/seal material)]
    (is (= document (identity/require-valid! document)))
    (doseq [altered [(assoc-in document [:entries 0 :path] "/library/not-a")
                     (assoc document :entries (vec (reverse (:entries document))))
                     (assoc document :entries [(first (:entries document)) (first (:entries document))])]]
      (is (= :catalog-shape (failure #(view/prepare :artifact-catalog altered)))))
    (is (= :catalog-identity (failure #(view/prepare :artifact-catalog (assoc document :artifact (canonical/sha256 "other"))))))))

(deftest unrepresentable-native-filename-refuses-instead-of-relabeling
  (with-directory
    (fn [directory]
      ;; The official POSIX image contains Python; this creates an actual invalid UTF-8 filename.
      (let [process (.start (ProcessBuilder. ^java.util.List ["python3" "-c"
                                                              "import os,sys; p=os.fsencode(sys.argv[1])+b'/invalid-\\xff'; fd=os.open(p,os.O_CREAT|os.O_WRONLY,0o600); os.close(fd)"
                                                              (str directory)]))]
        (is (= 0 (.waitFor process)))
        (is (= :catalog-policy (failure #(catalog/open! (str directory) limits))))))))

(deftest representable-native-unicode-names-roundtrip-under-explicit-utf8-locale
  (with-directory
    (fn [directory]
      (let [program "(require '[gate.catalog-io :as catalog]) (let [entries (:entries (catalog/open! (System/getenv \"GATE_CATALOG_DIRECTORY\") {:entries 10 :bytes 65536}))] (when-not (= [\"tree \\uD83C\\uDF33.txt\"] (mapv :name entries)) (System/exit 1)))"
            create (.start (ProcessBuilder. ^java.util.List ["python3" "-c"
                                                             "import os,sys; p=os.fsencode(sys.argv[1])+b'/tree '+bytes.fromhex('f09f8cb3')+b'.txt'; fd=os.open(p,os.O_CREAT|os.O_WRONLY,0o600); os.close(fd)"
                                                             (str directory)]))
            builder (ProcessBuilder. ^java.util.List ["java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" program])]
        (is (= 0 (.waitFor create)))
        (.put (.environment builder) "GATE_CATALOG_DIRECTORY" (str directory))
        (.put (.environment builder) "LC_ALL" "C.UTF-8")
        (.put (.environment builder) "LANG" "C.UTF-8")
        (let [process (.start builder) exit (.waitFor process)]
          (is (= 0 exit) (str (slurp (.getInputStream process)) (slurp (.getErrorStream process)))))))))
