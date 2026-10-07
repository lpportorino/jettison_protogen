(ns gate.trace-io
  "Finite, strict JSON journal input; no keyword interning or implicit numeric rounding."
  (:require [clojure.string :as str]
            [gate.trace-contract :as t]
            [jsonista.core :as json]
            [malli.core :as m])
  (:import [com.fasterxml.jackson.core JsonFactory JsonParser$Feature StreamReadConstraints]
           [com.fasterxml.jackson.databind DeserializationFeature ObjectMapper]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]))

(def ^:private max-file-bytes 1048576)
(def ^:private max-total-bytes 134217728)
(def ^:private max-files 256002)
(def ^:private mapper
  (let [constraints (-> (StreamReadConstraints/builder) (.maxNestingDepth 32)
                        (.maxStringLength 4096) (.maxNameLength 4096) (.maxNumberLength 19) .build)
        factory (doto (JsonFactory.)
                  (.setStreamReadConstraints constraints)
                  (.enable JsonParser$Feature/STRICT_DUPLICATE_DETECTION))]
    (doto ^ObjectMapper (json/object-mapper {:factory factory})
      (.enable DeserializationFeature/FAIL_ON_TRAILING_TOKENS))))
(def ^:private record-schema (m/schema t/Record))

(defn refuse!
  "Throw a closed refusal; raw input, host paths and exception messages are not diagnostics."
  [code path issues]
  (let [failure {:code code :path path :issues issues}]
    (when-not (m/validate t/Failure failure)
      (throw (ex-info "Internal trace diagnostic violates contract" {:code :invalid-trace-diagnostic})))
    (throw (ex-info "Trace import refused" failure))))
(m/=> refuse! [:=> [:cat (second (nth t/Failure 2)) t/Path (second (nth t/Failure 4))] :nil])

(defn parse-record
  "Parse a single framed JSON record and report bounded schema paths on shape errors."
  [text path]
  (when-not (and (str/ends-with? text "\n")
                 (not (str/includes? (subs text 0 (dec (count text))) "\n")))
    (refuse! :trace-json path []))
  (let [record (try (json/read-value text mapper)
                    (catch Exception _ (refuse! :trace-json path [])))]
    (when-not (m/validate record-schema record)
      (let [schema (case (get record "record")
                     "run" t/Header "run-end" t/End "span-start" t/Start "span" t/Span t/Record)
            issues (mapv (fn [{:keys [in] error-type :type}]
                           {:in (vec (take 64 in))
                            :type (case error-type :malli.core/missing-key :missing-key
                                        :malli.core/extra-key :extra-key
                                        :malli.core/invalid-type :invalid-type :invalid-value)})
                         (take 32 (:errors (m/explain schema record))))]
        (refuse! :trace-shape path issues)))
    record))
(m/=> parse-record [:=> [:cat [:string {:max 1048576}] t/Path] t/Record])

(defn- paths
  "List only the two journal levels, spending the file budget before sorting entries."
  [root]
  (let [base (Path/of root (make-array String 0))
        links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (when-not (Files/isDirectory base links) (refuse! :trace-file-type "run" []))
    (with-open [entries (Files/newDirectoryStream base)]
      (doseq [entry entries
              :let [filename (str (.getFileName ^Path entry))]]
        (when-not (contains? #{"run.json" "end.json" "spans"} filename)
          (refuse! :trace-layout "run" []))))
    (let [span-dir (.resolve base "spans")]
      (when-not (Files/isDirectory span-dir links) (refuse! :trace-file-type "spans" []))
      (with-open [entries (Files/newDirectoryStream span-dir)]
        (loop [remaining (iterator-seq (.iterator entries))
               result (cond-> ["run.json"] (Files/exists (.resolve base "end.json") links) (conj "end.json"))]
          (if-let [entry (first remaining)]
            (let [filename (str (.getFileName ^Path entry))]
              (when (>= (count result) max-files) (refuse! :trace-file-limit "spans" []))
              (when (str/includes? filename ".attr.") (refuse! :trace-unsupported-annotation "spans" []))
              (when-not (re-matches #"[0-9a-f]{16}\.(?:start|span)\.json" filename)
                (refuse! :trace-layout "spans" []))
              (recur (rest remaining) (conj result (str "spans/" filename))))
            (vec (sort result))))))))
(m/=> paths [:=> [:cat t/Path] [:vector {:min 1 :max 256002} t/Path]])

(defn- read-entry
  "Check type and length, use no-follow open, and stop reads at the remaining byte budget."
  [root relative remaining]
  (let [path (.resolve (Path/of root (make-array String 0)) relative)
        links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        attrs (Files/readAttributes path BasicFileAttributes links)
        limit (min remaining max-file-bytes)]
    (when-not (.isRegularFile attrs) (refuse! :trace-file-type relative []))
    (when (> (.size attrs) limit) (refuse! :trace-byte-limit relative []))
    (with-open [channel (Files/newByteChannel path
                                              (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
      (let [buffer (ByteBuffer/allocate (inc limit))]
        (loop []
          (when (pos? (.remaining buffer))
            (let [read-count (.read channel buffer)]
              (when-not (neg? read-count) (recur)))))
        (let [size (.position buffer)]
          (when (> size limit) (refuse! :trace-byte-limit relative []))
          (.flip buffer)
          (let [digest (MessageDigest/getInstance "SHA-256")
                _ (.update digest (.asReadOnlyBuffer buffer))
                fingerprint (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest)))
                text (try
                       (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                                       (.onMalformedInput CodingErrorAction/REPORT)
                                       (.onUnmappableCharacter CodingErrorAction/REPORT)) buffer))
                       (catch Exception _ (refuse! :trace-json relative [])))]
            [size {:path relative :digest fingerprint :record (parse-record text relative)}]))))))
(m/=> read-entry [:=> [:cat t/Path t/Path [:int {:min 0 :max 134217728}]]
                  [:tuple [:int {:min 0 :max 1048576}] t/Entry]])

(defn load-journal
  "Load a quiescent journal under global file/byte limits; active writers must first be stopped."
  [root]
  (try
    (loop [files (seq (paths root)) remaining max-total-bytes entries []]
      (if-let [relative (first files)]
        (let [[size entry] (read-entry root relative remaining)]
          (recur (next files) (- remaining size) (conj entries entry)))
        entries))
    (catch java.io.IOException _ (refuse! :trace-io "run" []))))
(m/=> load-journal [:=> [:cat t/Path] t/Journal])
