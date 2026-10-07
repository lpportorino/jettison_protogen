(ns gate.report-io
  "Byte-bounded local report loading for consumer toolboxes."
  (:require [gate.admission :as admission]
            [gate.contract :as c]
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
  (admission/decode (read-text! path (:bytes limits)) :graph limits))
(m/=> read-graph! [:=> [:cat PathName c/AdmissionLimits] c/Graph])
