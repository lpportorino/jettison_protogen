(ns gate.store
  "Bounded machine-local receipt storage; atomic create-only publication cannot overwrite another run."
  (:require [gate.admission :as admission]
            [gate.cache :as cache]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption
            NoSuchFileException FileAlreadyExistsException]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]))

(def Directory [:string {:min 1 :max 4096}])
(def Lookup
  [:multi {:dispatch :status}
   [:hit [:map {:closed true} [:status [:= :hit]] [:receipt r/Receipt]]]
   [:miss [:map {:closed true} [:status [:= :miss]] [:reason [:enum :absent :invalid :io-error :conflict]]]]])
(def Publication [:map {:closed true} [:status [:enum :stored :existing]] [:receipt r/Receipt]])
(def Failure [:map {:closed true} [:code [:enum :cache-file-type :cache-io :cache-invalid-receipt :cache-conflict]]])
(def ^:private byte-limit 4194304)
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Do not expose filesystem paths, receipt values or platform-specific exception strings."
  [code]
  (throw (ex-info "Receipt storage refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2))] :nil])

(defn- read-receipt
  "Read at most 4 MiB plus a sentinel, preserving strict UTF-8 and finite EDN admission."
  [directory input-digest]
  (let [base (Path/of directory (make-array String 0))
        path (.resolve base (str input-digest ".edn"))]
    (when-not (Files/isDirectory base no-follow) (refuse! :cache-file-type))
    (let [attrs (Files/readAttributes path BasicFileAttributes no-follow)]
      (when (or (not (.isRegularFile attrs)) (> (.size attrs) byte-limit))
        (refuse! :cache-file-type)))
    (with-open [channel (Files/newByteChannel path (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
      (let [buffer (ByteBuffer/allocate (inc byte-limit))]
        (loop [] (when (and (.hasRemaining buffer) (not (neg? (.read channel buffer)))) (recur)))
        (when (> (.position buffer) byte-limit) (refuse! :cache-file-type))
        (.flip buffer)
        (let [text (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                                   (.onMalformedInput CodingErrorAction/REPORT)
                                   (.onUnmappableCharacter CodingErrorAction/REPORT)) buffer))
              receipt (admission/decode text :cache-receipt (assoc admission/default-limits :bytes byte-limit))]
          (when (or (not= input-digest (:key receipt))
                    (not= (:result receipt) (cache/result-identity (:coverage receipt) (:outputs receipt))))
            (refuse! :cache-invalid-receipt))
          receipt)))))
(m/=> read-receipt [:=> [:cat Directory c/Digest] r/Receipt])

(defn lookup
  "Malformed, missing or unreadable receipts are explicit misses, never successful cache evidence."
  [directory input-digest]
  (try (let [base (Path/of directory (make-array String 0))
             marker (.resolve base (str input-digest ".conflict"))]
         (cond
           (Files/exists marker no-follow) {:status :miss :reason :conflict}
           (not (Files/exists base no-follow)) {:status :miss :reason :absent}
           :else (let [receipt (read-receipt directory input-digest)]
                   (if (Files/exists marker no-follow) {:status :miss :reason :conflict}
                       {:status :hit :receipt receipt}))))
       (catch NoSuchFileException _ {:status :miss :reason :absent})
       (catch clojure.lang.ExceptionInfo _ {:status :miss :reason :invalid})
       (catch java.nio.charset.CharacterCodingException _ {:status :miss :reason :invalid})
       (catch java.io.IOException _ {:status :miss :reason :io-error})))
(m/=> lookup [:=> [:cat Directory c/Digest] Lookup])

(defn- quarantine!
  "A persistent key-local marker makes observed nondeterminism ineligible for later reuse."
  [directory input-digest]
  (let [marker (.resolve (Path/of directory (make-array String 0)) (str input-digest ".conflict"))]
    (try (Files/createFile marker (make-array FileAttribute 0))
         (catch FileAlreadyExistsException _ nil)))
  nil)
(m/=> quarantine! [:=> [:cat Directory c/Digest] :nil])

(defn publish!
  "Hard-link a finished temporary into its key slot; racing equal results reuse the first receipt.
   Conflicting results quarantine the key and preserve the original receipt. This assumes a trusted local cache directory."
  [directory receipt]
  (when (or (not (m/validate r/Receipt receipt))
            (not= (:result receipt) (cache/result-identity (:coverage receipt) (:outputs receipt))))
    (refuse! :cache-invalid-receipt))
  (try
    (let [base (Path/of directory (make-array String 0))
          _ (Files/createDirectories base (make-array FileAttribute 0))
          _ (when-not (Files/isDirectory base no-follow) (refuse! :cache-file-type))
          _ (when (Files/exists (.resolve base (str (:key receipt) ".conflict")) no-follow) (refuse! :cache-conflict))
          encoded (.getBytes (canonical/encode receipt byte-limit) StandardCharsets/UTF_8)
          temporary (Files/createTempFile base ".receipt-" ".tmp" (make-array FileAttribute 0))
          target (.resolve base (str (:key receipt) ".edn"))]
      (try
        (Files/write temporary encoded (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))
        (try
          (Files/createLink target temporary)
          {:status :stored :receipt receipt}
          (catch FileAlreadyExistsException _
            (let [existing (lookup directory (:key receipt))]
              (when (= :conflict (:reason existing)) (refuse! :cache-conflict))
              (when (not= :hit (:status existing)) (refuse! :cache-invalid-receipt))
              (when (or (not= (:gate receipt) (get-in existing [:receipt :gate]))
                        (not= (:result receipt) (get-in existing [:receipt :result])))
                (quarantine! directory (:key receipt))
                (refuse! :cache-conflict))
              {:status :existing :receipt (:receipt existing)})))
        (finally (Files/deleteIfExists temporary))))
    (catch java.io.IOException _ (refuse! :cache-io))))
(m/=> publish! [:=> [:cat Directory r/Receipt] Publication])
