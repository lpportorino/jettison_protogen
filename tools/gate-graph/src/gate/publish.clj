(ns gate.publish
  "Verified generated-file publication. Per-file atomic replacement, explicit partial batch outcomes."
  (:require [clojure.string :as str]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption StandardCopyOption CopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def Failure [:map {:closed true} [:code [:enum :output-publication-refused]]])

(defn- safe-path!
  "Create owned output parent directories, refusing symlinks and non-directory ancestors.
   Trusted workspace and exclusive caller output claims required; not an adversarial path sandbox."
  [root relative]
  (let [parts (str/split relative #"/")]
    (loop [path root remaining-parts (butlast parts)]
      (if-let [part (first remaining-parts)]
        (let [next-path (.resolve ^Path path ^String part)]
          (when (Files/notExists next-path no-follow) (Files/createDirectory next-path (make-array FileAttribute 0)))
          (when (or (Files/isSymbolicLink next-path) (not (Files/isDirectory next-path no-follow)))
            (throw (ex-info "Output publication refused" {:code :output-publication-refused})))
          (recur next-path (next remaining-parts)))
        (let [target (.resolve ^Path path ^String (last parts))]
          (when (or (Files/isSymbolicLink target)
                    (and (Files/exists target no-follow) (not (Files/isRegularFile target no-follow))))
            (throw (ex-info "Output publication refused" {:code :output-publication-refused})))
          target)))))
(m/=> safe-path! [:=> [:cat inputs/NativePath r/Path] inputs/NativePath])

(defn- copy-bounded!
  "Copy into a private temporary on the destination filesystem; never truncate an existing output."
  [source target file budget limits]
  (with-open [input (Files/newByteChannel source (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))
              output (Files/newByteChannel target (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))]
    (let [buffer (ByteBuffer/allocate 65536)]
      (loop []
        (let [n (.read input buffer)]
          (when-not (neg? n)
            (when (> (swap! budget + n) (:bytes limits))
              (throw (ex-info "Output publication refused" {:code :output-publication-refused})))
            (.flip buffer)
            (while (.hasRemaining buffer) (.write output buffer))
            (.clear buffer) (recur))))))
  (Files/setPosixFilePermissions target (PosixFilePermissions/fromString (if (:executable? file) "rwxr-xr-x" "rw-r--r--")))
  nil)
(m/=> copy-bounded! [:=> [:cat inputs/NativePath inputs/NativePath r/File inputs/Budget inputs/Limits] :nil])

(defn- replace!
  "Atomically install one fully staged file; unsupported atomic moves fail without a copy fallback."
  [temporary target]
  (Files/move ^Path temporary ^Path target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
  nil)
(m/=> replace! [:=> [:cat inputs/NativePath inputs/NativePath] :nil])

(defn install!
  "Stage every declared output, verify copied digests, then atomically replace each destination.
   Caller must hold exclusive output ownership through receipt admission. No unlisted file is removed.
   Batch replacement is not transactional: on interruption/I/O failure, :partial and :installed
   identify the exact completed subset. No receipt may be earned for partial/refused publication.
   Atomic moves must be supported; there is no unsafe copy/truncate fallback. Symlink ancestors,
   changed source bytes and unsupported destinations refuse. Source and destination are trusted local
   directories; external concurrent mutation and power-loss durability are outside this contract."
  [source-directory destination-directory gate evidence limits expected]
  (let [installed (atom []) staged (atom [])]
    (try
      (let [root (.toRealPath (Path/of destination-directory (make-array String 0)) (make-array LinkOption 0))
            source (.toRealPath (Path/of source-directory (make-array String 0)) (make-array LinkOption 0))
            budget (atom 0)]
        (when-not (= expected (inputs/outputs! source-directory gate evidence limits))
          (throw (ex-info "Output publication refused" {:code :output-publication-refused})))
        (doseq [file expected]
          (let [target (safe-path! root (:path file))
                temporary (Files/createTempFile (.getParent target) ".gate-output-" ".tmp" (make-array FileAttribute 0))]
            (swap! staged conj {:target target :temporary temporary :file file})
            (copy-bounded! (.resolve source ^String (:path file)) temporary file budget limits)
            (let [relative (str (.relativize root temporary))
                  actual (inputs/outputs! destination-directory (assoc gate :outputs [relative]) evidence limits)]
              (when-not (= [(assoc file :path relative)] actual)
                (throw (ex-info "Output publication refused" {:code :output-publication-refused}))))))
        (doseq [{:keys [target temporary file]} @staged]
          (safe-path! root (:path file))
          (replace! temporary target)
          (swap! installed conj (:path file)))
        (if (= expected (inputs/outputs! destination-directory gate evidence limits))
          {:status :installed :installed @installed :reason nil}
          {:status :partial :installed @installed :reason :output-verification}))
      (catch clojure.lang.ExceptionInfo _ {:status (if (seq @installed) :partial :refused) :installed @installed :reason :output-verification})
      (catch java.io.IOException _ {:status (if (seq @installed) :partial :refused) :installed @installed :reason :output-io})
      (catch SecurityException _ {:status (if (seq @installed) :partial :refused) :installed @installed :reason :output-io})
      (catch UnsupportedOperationException _ {:status (if (seq @installed) :partial :refused) :installed @installed :reason :output-io})
      (finally
        (doseq [{:keys [temporary]} @staged]
          (try (Files/deleteIfExists ^Path temporary) (catch java.io.IOException _ nil) (catch SecurityException _ nil)))))))
(m/=> install! [:=> [:cat inputs/Root inputs/Root r/Gate inputs/Evidence inputs/Limits r/Files] r/OutputPublication])
