(ns gate.snapshot
  "Materialize declared bytes into a new owned tree before read-only container execution."
  (:require [clojure.string :as str]
            [gate.cache :as cache]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute FileTime PosixFilePermissions]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def Failure [:map {:closed true} [:code [:enum :snapshot-incomplete :snapshot-changed :snapshot-budget :snapshot-io :snapshot-layout]]])
(def OutputRoots [:vector {:max 64} r/Path])
(def Result [:map {:closed true} [:directory inputs/Root] [:snapshot r/Snapshot]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private epoch (FileTime/fromMillis 0))

(defn- refuse!
  "Refuse with a compact public code, never native errors or private source paths."
  [code]
  (throw (ex-info "Snapshot refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2))] :nil])

(defn- beneath?
  "Compare root-relative components, not ambiguous textual prefixes."
  [path root]
  (or (= path root) (str/starts-with? path (str root "/"))))
(m/=> beneath? [:=> [:cat r/Path r/Path] :boolean])

(defn validate-layout!
  "Require disjoint output roots, exactly one owning root per output and no hidden input bytes.
   A root may contain multiple declared outputs. Undeclared files inside it are never published."
  [gate snapshot output-roots]
  (when (or (some (fn [[i root]] (some #(beneath? root %) (concat (take i output-roots) (drop (inc i) output-roots))))
                  (map-indexed vector output-roots))
            (some (fn [output] (not= 1 (count (filter #(and (not= output %) (beneath? output %)) output-roots)))) (:outputs gate))
            (some (fn [root] (not-any? #(beneath? % root) (:outputs gate))) output-roots)
            (some (fn [{:keys [path]}] (some #(or (beneath? path %) (beneath? % path)) output-roots)) (:files snapshot)))
    (refuse! :snapshot-layout)))
(m/=> validate-layout! [:=> [:cat r/Gate r/Snapshot OutputRoots] :nil])

(defn- copy-file!
  "Stream one observed file into a create-only destination, checking bytes and execution mode.
   Cumulative copy bytes are bounded even if a live source grows after observation."
  [source destination file limits copied]
  (let [{:keys [path digest executable?]} file
        from (.resolve ^Path source ^String path) to (.resolve ^Path destination ^String path)
        sha (MessageDigest/getInstance "SHA-256")]
    (Files/createDirectories (.getParent to) (make-array FileAttribute 0))
    (with-open [input (Files/newByteChannel from (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))
                output (Files/newByteChannel to (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))]
      (let [buffer (ByteBuffer/allocate 65536)]
        (loop []
          (let [n (.read input buffer)]
            (when-not (neg? n)
              (when (> (swap! copied + n) (:bytes limits)) (refuse! :snapshot-budget))
              (.flip buffer)
              (.update sha (.asReadOnlyBuffer buffer))
              (while (.hasRemaining buffer) (.write output buffer))
              (.clear buffer) (recur))))))
    (when (or (not= digest (.formatHex (HexFormat/of) (.digest sha)))
              (not= executable? (inputs/executable-mode? from))) (refuse! :snapshot-changed))
    (Files/setPosixFilePermissions to (PosixFilePermissions/fromString (if executable? "r-xr-xr-x" "r--r--r--")))
    (Files/setLastModifiedTime to epoch)
    nil))
(m/=> copy-file! [:=> [:cat inputs/NativePath inputs/NativePath r/File inputs/Limits inputs/Budget] :nil])

(defn materialize!
  "Copy a complete declared snapshot into a new directory under an existing owned parent.
   Compare actual copied bytes with the initial snapshot, normalize file modes/mtime, and
   verify the resulting membership/digests. Empty output mount points are created separately.
   This freezes the bytes executed by a later read-only mount, not the original worktree.
   Another trusted host actor must not mutate the owned destination. POSIX regular files only.
   On refusal a partial directory may remain for caller-owned cleanup; no usable result is returned."
  [directory destination gate evidence limits output-roots]
  (try
    (let [before (inputs/observe! directory gate evidence limits)]
      (when-not (:complete? before) (refuse! :snapshot-incomplete))
      ;; Validate completeness/uniqueness without needing dependency results.
      (cache/input-key (assoc gate :dependencies []) before [])
      (validate-layout! gate before output-roots)
      (let [root (Path/of destination (make-array String 0))
            source (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))]
        (Files/createDirectory root (make-array FileAttribute 0))
        (let [copied (atom 0)] (doseq [file (:files before)] (copy-file! source root file limits copied)))
        (doseq [path output-roots] (Files/createDirectories (.resolve root ^String path) (make-array FileAttribute 0)))
        (when-not (= "." (:cwd gate))
          (Files/createDirectories (.resolve root ^String (:cwd gate)) (make-array FileAttribute 0)))
        (let [after (inputs/observe! destination gate evidence limits)]
          (when-not (= before after) (refuse! :snapshot-changed))
          ;; Directory metadata is also normalized after all children/mount points exist.
          (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
            (doseq [^Path path (iterator-seq (.iterator paths)) :when (Files/isDirectory path no-follow)]
              (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rwxr-xr-x"))
              (Files/setLastModifiedTime path epoch)))
          {:directory destination :snapshot after})))
    (catch java.io.IOException _ (refuse! :snapshot-io))
    (catch SecurityException _ (refuse! :snapshot-io))
    (catch UnsupportedOperationException _ (refuse! :snapshot-io))))
(m/=> materialize! [:=> [:cat inputs/Root inputs/Root r/Gate inputs/Evidence inputs/Limits OutputRoots] Result])
