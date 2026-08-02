(ns scratchcard.png-test
  "Pins the render encoder.

  The assertion that matters is LOSSLESSNESS: a byte read back must equal the
  byte written. If that ever stops holding, every run-to-run pixel diff this
  tool produces becomes a comparison of compression artefacts, and it would
  fail silently — the images would still look right."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.png :as png])
  (:import
   (java.awt.image BufferedImage)
   (java.io ByteArrayInputStream File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (javax.imageio ImageIO)))

(defn- rgba
  "A w*h*4 straight-alpha framebuffer from a fn of [x y] -> [r g b a]."
  ^bytes [w h f]
  (let [out (byte-array (* w h 4))]
    (dotimes [y h]
      (dotimes [x w]
        (let [[r g b a] (f x y)
              i (* 4 (+ x (* y w)))]
          (aset-byte out i (unchecked-byte r))
          (aset-byte out (+ i 1) (unchecked-byte g))
          (aset-byte out (+ i 2) (unchecked-byte b))
          (aset-byte out (+ i 3) (unchecked-byte a)))))
    out))

(defn- decode
  ^BufferedImage [^bytes bs]
  (ImageIO/read (ByteArrayInputStream. bs)))

(deftest encodes-a-real-png-of-the-right-size
  (let [w 8 h 4
        img (decode (png/encode (rgba w h (fn [_ _] [255 0 0 255])) w h))]
    (is (some? img) "output did not decode as an image")
    (is (= w (.getWidth img)))
    (is (= h (.getHeight img)))))

(deftest opaque-pixels-survive-byte-exactly
  ;; LOSSLESS is the whole reason this is PNG and not JPEG. A lossy encoder
  ;; passes every shape assertion above and fails this one.
  (let [w 4 h 2
        colours [[255 0 0] [0 255 0] [0 0 255] [17 34 51]]
        fb (rgba w h (fn [x _] (conj (nth colours x) 255)))
        img (decode (png/encode fb w h))]
    (dotimes [y h]
      (dotimes [x w]
        (let [px (.getRGB img x y)
              [r g b] (nth colours x)]
          (is (= r (bit-and (bit-shift-right px 16) 0xff)))
          (is (= g (bit-and (bit-shift-right px 8) 0xff)))
          (is (= b (bit-and px 0xff))))))))

(deftest transparent-pixels-flatten-onto-black
  ;; The framebuffer carries GARBAGE colour bytes under A=0. Flattening is
  ;; what makes the output deterministic; without it two byte-identical
  ;; renders could encode differently.
  (let [w 2 h 1
        fb (rgba w h (fn [x _] (if (zero? x) [200 100 50 0] [200 100 50 255])))
        img (decode (png/encode fb w h))]
    (is (= 0 (bit-and (.getRGB img 0 0) 0xffffff))
        "A=0 must flatten to black regardless of its colour bytes")
    (is (= 0xC86432 (bit-and (.getRGB img 1 0) 0xffffff)))))

(deftest half-alpha-composites-the-same-way-the-gallery-does
  ;; Borrowed from devcards.jpeg/flatten->rgb: out = round(c*a/255).
  (let [fb (rgba 1 1 (fn [_ _] [255 255 255 128]))
        img (decode (png/encode fb 1 1))
        v (bit-and (.getRGB img 0 0) 0xff)]
    (is (= 128 v))))

(deftest write-is-atomic-and-leaves-no-part-file
  (let [dir (.toFile (Files/createTempDirectory "scratchcard-png-test"
                                                (into-array FileAttribute [])))
        target (io/file dir "sub" "frame.png")
        f (png/write! target (rgba 4 4 (fn [_ _] [1 2 3 255])) 4 4)]
    (is (.isFile ^File f))
    (is (pos? (.length ^File f)))
    (testing "the .part sibling is gone — a reader never sees a torn file"
      (is (not (.exists (io/file (str (.getPath ^File f) ".part"))))))
    (testing "parents are created"
      (is (.isDirectory (io/file dir "sub"))))
    (testing "and the written bytes decode to the right image"
      (let [img (ImageIO/read f)]
        (is (= 4 (.getWidth img)))
        (is (= 0x010203 (bit-and (.getRGB img 0 0) 0xffffff)))))))

(deftest a-png-writer-is-actually-registered
  ;; NON-VACUITY: `ImageIO/write` returns false when no writer exists, and a
  ;; caller ignoring that would produce zero-byte files that look like render
  ;; failures. png/encode throws instead — pin that a writer IS present, so
  ;; this suite is testing a live encoder rather than an exception path.
  (is (pos? (alength (ImageIO/getWriterFormatNames))))
  (is (some #(= "png" %) (map #(.toLowerCase ^String %)
                              (ImageIO/getWriterFormatNames)))))
