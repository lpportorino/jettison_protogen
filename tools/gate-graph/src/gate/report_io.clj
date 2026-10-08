(ns gate.report-io
  "Byte-bounded local report loading for consumer toolboxes."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.archive-contract :as ac]
            [gate.contract :as c]
            [gate.repository-contract :as rc]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction CharacterCodingException]
           [java.nio.file Files Path LinkOption OpenOption]))

(set! *warn-on-reflection* true)

(def PathName [:string {:min 1 :max 4096}])
(def Failure
  [:map {:closed true}
   [:code [:enum :report-policy :report-unreadable :report-byte-limit :report-utf8]]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Return only a closed failure code; paths, bytes and native messages stay local."
  [code]
  (throw (ex-info "Report file refused" {:code code})))
(m/=> refuse! [:=> [:cat [:enum :report-policy :report-unreadable :report-byte-limit :report-utf8]] :nil])

(defn- read-text!
  "Read at most limit+1 bytes from a local regular file and decode strict UTF-8.
   File metadata is not the byte budget. Symlink leaves and nonregular files refuse;
   trusted parent directories are not a sandbox against concurrent replacement."
  [path limit]
  (try
    (let [file (Path/of path (make-array String 0))]
      (when-not (Files/isRegularFile file no-follow) (refuse! :report-unreadable))
      (with-open [stream (Files/newInputStream file (into-array OpenOption [LinkOption/NOFOLLOW_LINKS]))]
        (let [content (.readNBytes stream (int (inc limit)))]
          (when (> (alength content) limit) (refuse! :report-byte-limit))
          (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                          (.onMalformedInput CodingErrorAction/REPORT)
                          (.onUnmappableCharacter CodingErrorAction/REPORT))
                        (ByteBuffer/wrap content))))))
    (catch CharacterCodingException _ (refuse! :report-utf8))
    (catch java.io.IOException _ (refuse! :report-unreadable))
    (catch IllegalArgumentException _ (refuse! :report-unreadable))
    (catch SecurityException _ (refuse! :report-unreadable))))
(m/=> read-text! [:=> [:cat PathName [:int {:min 1 :max 134217728}]] admission/SourceText])

(defn- naming-file
  "Run one admission, adding the file's BASENAME to an admission refusal. Only the
   basename: the directory can be private, and refusals never echo private input."
  [path f]
  (try (f)
       (catch clojure.lang.ExceptionInfo error
         (throw (if (= admission/refusal-message (ex-message error))
                  (let [base (peek (str/split path #"[/\\\\]"))]
                    ;; Split on BOTH separators: a backslash path must not pass through whole
                    ;; on a POSIX host. Attach only what the contract admits, else nothing.
                    (ex-info (ex-message error)
                             (cond-> (ex-data error) (and base (re-matches #"[^/\\\\]{1,255}" base)) (assoc :file base))
                             error))
                  error)))))
(m/=> naming-file [:=> [:cat PathName fn?] some?])

(defn read-graph!
  "Load one local graph with explicit I/O, parser and graph-invariant limits.
   Accepts a trusted caller-selected path, never evaluates EDN or follows a leaf
   symlink. Reads actual bytes once before parsing. Returns the graph for bounded
   query/aggregation/diff preparation; callers should return pages to an LLM.
   File failures use Failure; parsing and graph errors retain their shared closed
   diagnostics. This establishes artifact validity, not truth of captured work."
  [path limits]
  (when-not (and (m/validate PathName path) (m/validate c/AdmissionLimits limits))
    (refuse! :report-policy))
  (naming-file path #(admission/decode (read-text! path (:bytes limits)) :graph limits)))
(m/=> read-graph! [:=> [:cat PathName c/AdmissionLimits] c/Graph])

(defn read-archive!
  "Read bounded strict UTF-8 archive EDN and check graph, provenance, verdict and content identity.
   A consistent archive does not independently prove producer honesty or complete CI enrollment."
  [path limits]
  (naming-file path #(admission/decode (read-text! path (:bytes limits)) :run-archive limits)))
(m/=> read-archive! [:=> [:cat PathName c/AdmissionLimits] ac/Document])

(defn read-repository!
  "Read a bounded full repository observation and verify hashes, policy and nested checkout bindings."
  [path limits]
  (naming-file path #(admission/decode (read-text! path (:bytes limits)) :repository-observation limits)))
(m/=> read-repository! [:=> [:cat PathName c/AdmissionLimits] rc/Observation])
