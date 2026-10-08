(ns gate.archive-io-test
  "Actual-file archive publication, checked export exclusions and nonreplacement of prior evidence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.archive-io :as archive]
            [gate.archive-test :as fixture]
            [gate.canonical :as canonical]
            [gate.report-io :as reader]
            [gate.report-publish :as html]
            [gate.report-publish-test :as publisher])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn with-directory [f]
  (let [root (Files/createTempDirectory "archive-export-" (make-array FileAttribute 0))]
    (try (f root)
         (finally
           (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
             (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
               (Files/delete path)))))))
(defn child [root relative] (.resolve ^Path root ^String relative))
(defn exists? [root relative] (Files/exists (child root relative) (make-array java.nio.file.LinkOption 0)))

(deftest archive-publication-has-matching-edn-html-and-identity
  (with-directory
    (fn [root]
      (let [document (fixture/document) result (archive/publish! (str root) document publisher/viewer)
            html (slurp (str (child root "index.html")))]
        (is (= (:artifact document) (:artifact result)))
        (is (= (:digest publisher/viewer) (:viewer result)))
        (is (= document (reader/read-archive! (str (child root "run.edn")) admission/default-limits)))
        (is (= (:graph document) (reader/read-graph! (str (child root "graph.edn")) admission/default-limits)))
        (is (str/includes? html "Archive: passed"))
        (is (str/includes? html (:artifact document)))
        (is (= #{"graph.edn" "run.edn" "index.html"} (set (map #(.getName %) (.listFiles (.toFile root))))))))))

(deftest existing-capture-graph-is-reused-only-with-matching-content
  (with-directory
    (fn [root]
      (let [document (fixture/document) graph-file (child root "graph.edn")
            text (str (canonical/encode (:graph document) 1048576) "\n")]
        (spit (str graph-file) text)
        (archive/publish! (str root) document publisher/viewer)
        (is (= text (slurp (str graph-file)))))))
  (with-directory
    (fn [root]
      (let [document (fixture/document) text (canonical/encode (assoc-in (:graph document) [:nodes 0 :label] "other") 1048576)]
        (spit (str (child root "graph.edn")) text)
        (is (= {:code :archive-graph-binding} (fixture/failure #(archive/publish! (str root) document publisher/viewer))))
        (is (= text (slurp (str (child root "graph.edn")))))
        (is (not (exists? root "index.html")))
        (is (not (exists? root "run.edn")))))))

(deftest existing-archives-and-links-never-get-replaced
  (doseq [filename ["run.edn" "index.html"]]
    (with-directory
      (fn [root]
        (let [path (child root filename)]
          (spit (str path) "prior")
          (is (= {:code :archive-output-exists}
                 (fixture/failure #(archive/publish! (str root) (fixture/document) publisher/viewer))))
          (is (= "prior" (slurp (str path))))
          (Files/delete path)
          (Files/createSymbolicLink path (Path/of "missing" (make-array String 0)) (make-array FileAttribute 0))
          (is (= {:code :archive-output-exists}
                 (fixture/failure #(archive/publish! (str root) (fixture/document) publisher/viewer))))
          (is (Files/isSymbolicLink path)))))))

(deftest html-failure-does-not-create-a-completed-archive-record
  (with-directory
    (fn [root]
      (with-redefs [html/publish! (fn [& _] (throw (ex-info "Synthetic publication failure" {:code :html-io})))]
        (is (= {:code :html-io} (fixture/failure #(archive/publish! (str root) (fixture/document) publisher/viewer)))))
      (is (exists? root "graph.edn"))
      (is (not (exists? root "run.edn"))))))

(deftest export-requires-exclusion-in-current-and-observed-policies
  (doseq [[path policy] [["reports-source/run" fixture/policy]
                         ["reports/run" (assoc fixture/policy :excluded [])]
                         ["other/run" (assoc fixture/policy :excluded ["other"])]]]
    (with-directory
      (fn [root]
        (is (= {:code :archive-output-policy}
               (fixture/failure #(archive/export! (str root) path policy (fixture/document) publisher/viewer))))
        (is (not (exists? root path))))))
  (with-directory
    (fn [root]
      (let [document (fixture/document)]
        (archive/export! (str root) "reports/run" fixture/policy document publisher/viewer)
        (is (= document (reader/read-archive! (str (child root "reports/run/run.edn")) admission/default-limits)))
        (is (= {:code :archive-output-exists}
               (fixture/failure #(archive/export! (str root) "reports/run" fixture/policy document publisher/viewer))))))))

(deftest export-refuses-symlink-ancestors-and-invalid-native-paths
  (with-directory
    (fn [root]
      (Files/createDirectory (child root "elsewhere") (make-array FileAttribute 0))
      (Files/createSymbolicLink (child root "reports") (child root "elsewhere") (make-array FileAttribute 0))
      (is (= {:code :archive-output-policy}
             (fixture/failure #(archive/export! (str root) "reports/run" fixture/policy (fixture/document) publisher/viewer))))
      (is (not (exists? root "elsewhere/run")))))
  (is (= {:code :archive-output-policy}
         (fixture/failure #(archive/export! "\u0000" "reports/run" fixture/policy (fixture/document) publisher/viewer)))))
