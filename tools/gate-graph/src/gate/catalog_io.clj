(ns gate.catalog-io
  "Bounded immediate local POSIX directory discovery without reading entry contents or following entry symlinks."
  (:require [gate.canonical :as canonical]
            [gate.catalog-contract :as cc]
            [gate.catalog-identity :as identity]
            [gate.report-io :as report-io]
            [malli.core :as m])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))
(set! *warn-on-reflection* true)
(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private hints
  {"run.edn" [:run-archive] "graph.edn" [:graph]
   "report.edn" [:process-batch-report :test-batch-report]
   "definitions.edn" [:gate-definitions] "batch.edn" [:coordinator-batch]
   "repository-before.edn" [:repository-observation] "repository-after.edn" [:repository-observation]
   "catalog.edn" [:artifact-catalog] "paired-performance.edn" [:paired-performance]})
(defn- refuse!
  "Return a closed refusal without native messages, private paths or rejected names."
  [code]
  (throw (ex-info "Directory catalog refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth cc/Failure 2))] :nil])
(defn- entry
  "Snapshot one entry's exact names and no-follow type; hints never authorize file admission."
  [^Path path]
  (let [name-text (str (.getFileName path)) path-text (str path)
        ^BasicFileAttributes attributes (Files/readAttributes path BasicFileAttributes no-follow)
        value {:name name-text :path path-text :type (cond (.isSymbolicLink attributes) :symlink
                                                           (.isRegularFile attributes) :file
                                                           (.isDirectory attributes) :directory :else :other)
               :status :unopened :hints (if (.isRegularFile attributes) (get hints name-text []) [])}]
    ;; POSIX names not representable exactly as Java UTF-8 strings refuse rather than relabel.
    (when-not (and (= path (Path/of path-text (make-array String 0))) (m/validate cc/Entry value))
      (refuse! :catalog-policy))
    value))
(m/=> entry [:=> [:cat [:fn #(instance? Path %)]] cc/Entry])

(defn open!
  "Acquire every immediate entry under hard entry/whole canonical metadata byte limits.
   Exceeding either limit refuses; no partial catalog is silently promoted to complete.
   Leaf directory symlinks refuse and entry symlinks remain unopened. Trusted ancestor
   directories are not a sandbox. No contents are read, no recursive traversal occurs,
   and the listing is a local snapshot across acquisition, not a race-free/current-filesystem proof."
  [directory limits]
  (when-not (and (m/validate report-io/PathName directory) (m/validate cc/Limits limits)
                 (= "/" java.io.File/separator)) (refuse! :catalog-policy))
  (try
    (let [root (.normalize (.toAbsolutePath (Path/of directory (make-array String 0))))
          _ (when-not (m/validate cc/Directory (str root)) (refuse! :catalog-policy))
          base {:schema/version 1 :profile :posix-directory-v1 :directory (str root) :entries []}
          envelope (assoc base :artifact (apply str (repeat 64 "0")))
          header-size (try (canonical/utf8-size (canonical/encode envelope (:bytes limits)))
                           (catch clojure.lang.ExceptionInfo error
                             (if (= :encoded-byte-limit (:code (ex-data error))) (refuse! :catalog-byte-limit) (throw error))))]
      (when-not (Files/isDirectory root no-follow) (refuse! :catalog-unreadable))
      (with-open [stream (Files/newDirectoryStream root)]
        (loop [remaining (iterator-seq (.iterator stream)) entries [] used header-size]
          (if-let [path (first remaining)]
            (do (when (>= (count entries) (:entries limits)) (refuse! :catalog-entry-limit))
                (let [item (entry path)
                      next-used (+ used (canonical/utf8-size (canonical/encode item 65536)) (if (seq entries) 1 0))]
                  (when (> next-used (:bytes limits)) (refuse! :catalog-byte-limit))
                  (recur (next remaining) (conj entries item) next-used)))
            (let [document (identity/seal (assoc base :entries (vec (sort-by :name entries))))]
              (canonical/encode document (:bytes limits))
              document)))))
    (catch java.io.IOException _ (refuse! :catalog-unreadable))
    (catch java.nio.file.DirectoryIteratorException _ (refuse! :catalog-unreadable))
    (catch IllegalArgumentException _ (refuse! :catalog-policy))
    (catch SecurityException _ (refuse! :catalog-unreadable))))
(m/=> open! [:=> [:cat report-io/PathName cc/Limits] cc/Document])
