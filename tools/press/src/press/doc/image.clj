(ns press.doc.image
  "Local PNG and JPEG inputs for paged documents. Sources are relative to the
   document directory and must resolve inside the configured repository root.
   Decoded dimensions and embedded MIME come from the file bytes, never its name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [press.config :as config]
            [press.doc.spec :as spec])
  (:import [java.io File ByteArrayInputStream]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util Base64 Locale]
           [java.util.zip CRC32]
           [javax.imageio ImageIO ImageReader]
           [javax.imageio.event IIOReadWarningListener]
           [javax.imageio.stream MemoryCacheImageInputStream]))

(set! *warn-on-reflection* true)

(def image-spec
  "One validated raster: self-contained source and decoded pixel dimensions."
  [:map {:closed true} [:uri spec/ne-string] [:width [:int {:min 1}]] [:height [:int {:min 1}]]])

(def images-spec
  "Document-relative source to validated raster, or nil for a refused input."
  [:map-of spec/ne-string [:maybe image-spec]])

(def max-bytes
  "Maximum raster file size, enforced before allocating or hashing raster bytes."
  (* 20 1024 1024))

(defn- png-integrity!
  "Refuse incomplete PNG chunks, invalid CRCs, missing image data or a missing
   terminal IEND. ImageIO tolerates some such malformed streams."
  [raster-bytes]
  (let [^bytes data raster-bytes n (alength data) buffer (ByteBuffer/wrap data)]
    (when-not (and (>= n 8) (= [137 80 78 71 13 10 26 10]
                               (mapv #(bit-and 255 (aget data %)) (range 8))))
      (throw (ex-info "invalid PNG signature" {:doc/refusal :doc/bad-image})))
    (loop [offset 8 seen-data? false]
      (when (> (+ offset 12) n)
        (throw (ex-info "PNG is missing its terminal chunk" {:doc/refusal :doc/bad-image})))
      (let [length (bit-and 0xffffffff (.getInt buffer (int offset)))
            end (+ offset 12 length)]
        (when (> end n)
          (throw (ex-info "truncated PNG chunk" {:doc/refusal :doc/bad-image})))
        (let [kind (String. data (int (+ offset 4)) 4 StandardCharsets/US_ASCII)
              crc (doto (CRC32.) (.update data (int (+ offset 4)) (int (+ length 4))))
              stored (bit-and 0xffffffff (.getInt buffer (int (+ offset 8 length))))]
          (when (or (not= stored (.getValue crc))
                    (not (re-matches #"[A-Za-z]{4}" kind))
                    (and (= offset 8) (not (and (= kind "IHDR") (= length 13))))
                    (and (> offset 8) (= kind "IHDR")))
            (throw (ex-info "invalid PNG header, chunk type or CRC" {:doc/refusal :doc/bad-image})))
          (if (= kind "IEND")
            (when-not (and (zero? length) (= end n) seen-data?)
              (throw (ex-info "invalid PNG terminal chunk" {:doc/refusal :doc/bad-image})))
            (recur end (or seen-data? (= kind "IDAT")))))))))

(m/=> png-integrity! [:=> [:cat [:fn bytes?]] :nil])

(defn sources
  "Every raster source a schema-valid document places, in reading order."
  [doc]
  (vec (for [node (tree-seq coll? seq doc)
             :when (and (vector? node) (= :doc/image (first node)))]
         (:src (second node)))))

(m/=> sources [:=> [:cat spec/edn-map] [:vector spec/ne-string]])

(defn local-file
  "Resolve `src` relative to document `d`; refuse :doc/nonlocal-image for
   absolute paths, URI schemes, or canonical paths outside the configured root.
   A relative path may point at another public asset inside that root."
  ^File [^File d src]
  (let [f (io/file src)]
    (when (or (.isAbsolute f) (str/starts-with? src "//")
              (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" src))
      (throw (ex-info (str "image " (pr-str src) " must be a document-relative local path")
                      {:doc/refusal :doc/nonlocal-image})))
    (let [resolved (.getCanonicalFile (io/file d src))
          root (.toPath (.getCanonicalFile (io/file (config/root))))]
      (when-not (.startsWith (.toPath resolved) root)
        (throw (ex-info (str "image " (pr-str src) " resolves outside the configured root")
                        {:doc/refusal :doc/nonlocal-image})))
      resolved)))

(m/=> local-file [:=> [:cat [:fn #(instance? File %)] spec/ne-string] [:fn #(instance? File %)]])

(defn- decode
  "Decode PNG/JPEG `bytes` after checking metadata against a 16-million-pixel
   budget. Return embedded data with its actual MIME; refuse unsupported,
   truncated or undecodable bytes as :doc/bad-image."
  [raster-bytes]
  (with-open [stream (MemoryCacheImageInputStream. (ByteArrayInputStream. ^bytes raster-bytes))]
    (let [readers (ImageIO/getImageReaders stream)]
      (when-not (.hasNext readers)
        (throw (ex-info "no raster decoder recognizes the bytes" {:doc/refusal :doc/bad-image})))
      (let [^ImageReader reader (.next readers)
            warnings (atom [])]
        (try
          (.addIIOReadWarningListener reader
                                      (reify IIOReadWarningListener
                                        (warningOccurred [_ _ warning] (swap! warnings conj warning))))
          (.setInput reader stream true true)
          (let [raster-format (.toLowerCase ^String (.getFormatName reader) Locale/ROOT)
                mime ({"png" "image/png" "jpeg" "image/jpeg"} raster-format)
                w (.getWidth reader 0)
                h (.getHeight reader 0)]
            (when (= raster-format "png") (png-integrity! raster-bytes))
            (when-not (and mime (pos? w) (pos? h) (<= (* (long w) h) 16000000))
              (throw (ex-info "only PNG/JPEG up to 16 million pixels are supported" {:doc/refusal :doc/bad-image})))
            (when-not (.read reader 0)
              (throw (ex-info "raster decoder produced no image" {:doc/refusal :doc/bad-image})))
            (when (seq @warnings)
              (throw (ex-info (str "raster decoder warning: " (str/join "; " @warnings))
                              {:doc/refusal :doc/bad-image})))
            {:uri (str "data:" mime ";base64," (.encodeToString (Base64/getEncoder) ^bytes raster-bytes))
             :width w :height h})
          (finally (.dispose reader)))))))

(m/=> decode [:=> [:cat [:fn bytes?]] image-spec])

(defn read-image
  "Validate and inline raster `src` from document `d`, or return one attributed
   violation. Missing files use :doc/no-image, path refusals :doc/nonlocal-image,
   unreadable/undecodable/unsupported or over-20-MiB files :doc/bad-image."
  [^File d src]
  (try
    (let [f (local-file d src)]
      (when-not (.isFile f)
        (throw (ex-info "no such raster file" {:doc/refusal :doc/no-image})))
      (when (> (.length f) max-bytes)
        (throw (ex-info "raster exceeds 20 MiB" {:doc/refusal :doc/bad-image})))
      (let [raster-bytes (with-open [stream (io/input-stream f)] (.readNBytes stream (int (inc max-bytes))))]
        (when (> (alength ^bytes raster-bytes) max-bytes)
          (throw (ex-info "raster exceeds 20 MiB" {:doc/refusal :doc/bad-image})))
        {:image (decode raster-bytes)}))
    (catch Exception e
      {:violation {:rule (or (:doc/refusal (ex-data e)) :doc/bad-image)
                   :at src :message (str "image " (pr-str src) " — " (ex-message e))}})))

(m/=> read-image [:=> [:cat [:fn #(instance? File %)] spec/ne-string]
                  [:or [:map {:closed true} [:image image-spec]]
                   [:map {:closed true} [:violation spec/violation]]]])

(defn inspect
  "Decode each distinct raster input once and collect its attributed violations."
  [^File d doc]
  (let [entries (mapv (fn [src] [src (read-image d src)]) (distinct (sources doc)))]
    {:images (into {} (map (fn [[src result]] [src (:image result)])) entries)
     :violations (vec (keep (comp :violation second) entries))}))

(m/=> inspect [:=> [:cat [:fn #(instance? File %)] spec/edn-map]
               [:map {:closed true} [:images images-spec] [:violations [:vector spec/violation]]]])
