(ns press.doc.image-test
  "Raster document images: local decoded bytes are embedded with bounded layout,
   invalid inputs refuse a build, and image changes invalidate a print plan."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [press.config :as config]
            [press.doc :as doc]
            [press.doc.fixture :as fx]
            [press.doc.image :as image]
            [press.doc.io :as dio])
  (:import [java.awt.image BufferedImage]
           [java.io RandomAccessFile]
           [java.nio.file Files]
           [java.util.zip CRC32]
           [javax.imageio ImageIO]))

(use-fixtures :each fx/cleanup)

(defn- image-data
  "A complete document placing raster block `opts` in its first section."
  [opts]
  (update-in (doc/example) [:body 0 :sections 0 :blocks] conj [:doc/image opts]))

(defn- raster!
  "Write a tiny decodable `format` raster to document `d` at `name`."
  [d filename raster-format w]
  (let [f (io/file d filename)]
    (io/make-parents f)
    (ImageIO/write (BufferedImage. w 6 BufferedImage/TYPE_INT_RGB) raster-format f)
    f))

(defn- out
  "The library build directory for document `d`."
  [d]
  (str (fx/build-root) "/" (fx/name-of d)))

(deftest decoded-raster-is-self-contained-accessible-and-bounded
  (doseq [[raster-format mime] [["png" "image/png"] ["jpg" "image/jpeg"]]]
    (let [opts {:src "media/sample.bin" :alt "A black test rectangle & border"
                :caption [[:b "Sample"] " at native proportions"] :width-percent 75 :max-height-mm 90}
          d (fx/doc-dir! (image-data opts))]
      (raster! d (:src opts) raster-format 12)
      (is (:ok? (doc/check d)))
      (when (:ok? (doc/check d))
        (let [html (slurp (:path (doc/build! d (out d))))]
          (is (str/includes? html (str "src=\"data:" mime ";base64,")) "MIME follows decoded bytes, not suffix")
          (is (str/includes? html "alt=\"A black test rectangle &amp; border\""))
          (is (str/includes? html "width=\"12\""))
          (is (str/includes? html "height=\"6\""))
          (is (str/includes? html "width:75%;max-height:90mm"))
          (is (str/includes? html "<figcaption><b>Sample</b> at native proportions</figcaption>"))
          (is (not (str/includes? html "src=\"media/sample.bin\""))))))))

(deftest missing-corrupt-and-nonlocal-images-are-attributed
  (doseq [[src rule] [["missing.png" :doc/no-image]
                      ["corrupt.png" :doc/bad-image]
                      ["https://example.com/photo.png" :doc/nonlocal-image]
                      ["//example.com/photo.png" :doc/nonlocal-image]
                      ["data:image/png;base64,abc" :doc/nonlocal-image]
                      ["/tmp/photo.png" :doc/nonlocal-image]
                      ["../../../../../../../../etc/passwd" :doc/nonlocal-image]]]
    (let [d (fx/doc-dir! (image-data {:src src :alt "Test raster"}))
          page (io/file (config/from-root (out d)) (str (fx/name-of d) ".html"))]
      (spit (io/file d "corrupt.png") "not an image")
      (io/make-parents page)
      (spit page "old page")
      (is (= [rule] (mapv :rule (:violations (doc/check d)))) src)
      (is (= :doc/refused (fx/refusal #(doc/build! d (out d)))) src)
      (is (not (.exists page)) "a refused build removes the old page"))))

(deftest image-schema-stays-closed-and-top-level
  (doseq [opts [{:src "x.png"} {:src "x.png" :alt " "}
                {:src "x.png" :alt "x" :width-percent 101}
                {:src "x.png" :alt "x" :max-height-mm 181}
                {:src "x.png" :alt "x" :onerror "run()"}]]
    (is (= [:doc/malformed] (mapv :rule (:violations (doc/check (fx/doc-dir! (image-data opts))))))))
  (doseq [block [[:doc/note :note [:doc/image {:src "x.png" :alt "x"}]]
                 [:doc/keep [:doc/image {:src "x.png" :alt "x"}]]]]
    (is (some #{:doc/malformed} (map :rule (:violations (doc/check
                                                         (fx/doc-dir! (update (doc/example) :problem conj block)))))))))

(deftest image-bytes-and-membership-participate-in-freshness
  (let [d (fx/doc-dir! (image-data {:src "media/sample.png" :alt "Test raster"}))]
    (raster! d "media/sample.png" "png" 12)
    (when (:ok? (doc/check d))
      (doc/build! d (out d))
      (is (= :no-throw (fx/refusal #(doc/pdf d))))
      (is (some #(= "image:media/sample.png" (first %)) (dio/stamp-inputs (io/file d) [])))
      (raster! d "media/sample.png" "png" 13)
      (is (= :doc/stale-page (fx/refusal #(doc/pdf d))))
      (doc/build! d (out d))
      (is (= :no-throw (fx/refusal #(doc/pdf d))))
      (Files/delete (.toPath (io/file d "media/sample.png")))
      (is (= :doc/stale-page (fx/refusal #(doc/pdf d)))))))

(deftest canonical-image-paths-cannot-escape-through-a-link
  (let [d (fx/doc-dir! (image-data {:src "link/passwd" :alt "Test raster"}))]
    (Files/createSymbolicLink (.toPath (io/file d "link")) (.toPath (io/file "/etc"))
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= [:doc/nonlocal-image] (mapv :rule (:violations (doc/check d)))))
    ;; Remove the symlink before fixture cleanup, which walks directories.
    (Files/delete (.toPath (io/file d "link")))))

(deftest unsupported-truncated-and-oversized-images-are-refused
  (doseq [kind [:unsupported :truncated :oversized]]
    (let [d (fx/doc-dir! (image-data {:src "sample.bin" :alt "Test raster"}))
          f (raster! d "sample.bin" (if (= kind :unsupported) "gif" "jpg") 12)]
      (case kind
        :unsupported nil
        :truncated (with-open [raf (RandomAccessFile. f "rw")]
                     (.setLength raf (- (.length raf) 10)))
        :oversized (with-open [raf (RandomAccessFile. f "rw")]
                     (.setLength raf (inc (* 20 1024 1024)))))
      (is (= [:doc/bad-image] (mapv :rule (:violations (doc/check d)))) (name kind)))))

(deftest incomplete-or-corrupt-png-chunks-are-refused
  (doseq [kind [:missing-iend :trailing-bytes :bad-crc :chunk-length]]
    (let [d (fx/doc-dir! (image-data {:src "sample.png" :alt "Test raster"}))
          f (raster! d "sample.png" "png" 12)]
      (with-open [raf (RandomAccessFile. f "rw")]
        (case kind
          :missing-iend (.setLength raf (- (.length raf) 12))
          :trailing-bytes (do (.seek raf (.length raf)) (.write raf 1))
          :bad-crc (do (.seek raf 29) (.write raf 0))
          :chunk-length (do (.seek raf 8) (.writeInt raf Integer/MAX_VALUE))))
      (is (= :doc/bad-image (get-in (image/read-image (io/file d) "sample.png") [:violation :rule])) (name kind)))))

(deftest oversized-raster-is-refused-before-stamp-hashing
  (let [d (fx/doc-dir! (image-data {:src "huge.bin" :alt "An oversized raster"}))
        f (io/file d "huge.bin")]
    (with-open [raf (RandomAccessFile. f "rw")]
      (.setLength raf (+ 2147483648 1)))
    (is (= [:doc/bad-image] (mapv :rule (:violations (doc/check d)))))
    (is (= :doc/refused (fx/refusal #(doc/build! d (out d)))))
    (is (some #{["image:huge.bin" "oversized"]} (dio/stamp-inputs (io/file d) [])))))

(deftest compressed-image-pixel-budget-precedes-decoding
  (let [d (fx/doc-dir! (image-data {:src "huge-pixels.png" :alt "An oversized pixel canvas"}))
        f (raster! d "huge-pixels.png" "png" 12)]
    ;; Change only the metadata and its CRC: no large raster allocation is
    ;; needed to establish that the pixel budget refuses before decoding.
    (with-open [raf (RandomAccessFile. f "rw")]
      (.seek raf 16)
      (.writeInt raf 5000)
      (.writeInt raf 5000)
      (.seek raf 12)
      (let [header (byte-array 17)]
        (.readFully raf header)
        (.writeInt raf (unchecked-int (.getValue (doto (CRC32.) (.update header)))))))
    (let [violation (:violation (image/read-image (io/file d) "huge-pixels.png"))]
      (is (= :doc/bad-image (:rule violation)))
      (is (str/includes? (:message violation) "16 million pixels")))))

(deftest the-cover-accepts-local-images-and-default-layout
  (let [data (assoc (doc/example) :cover [[:doc/image {:src "cover.png" :alt "A test cover"}]])
        d (fx/doc-dir! data)]
    (raster! d "cover.png" "png" 12)
    (let [html (slurp (:path (doc/build! d (out d))))]
      (is (str/includes? html "width:100%;max-height:120mm"))
      (is (str/includes? html "alt=\"A test cover\""))
      (is (< (str/index-of html "class=\"raster\"") (str/index-of html "class=\"meta\""))))))
