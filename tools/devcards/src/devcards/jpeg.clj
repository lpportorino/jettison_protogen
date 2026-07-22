(ns devcards.jpeg
  "RGBA framebuffer → committed-quality JPEG (galleries + contact sheets).

   Design pinned by the JPEG POC (JPEG_POC.md): direct javax.imageio interop,
   NO added dependency (buy-before-build: the JDK encoder is the mature
   library). Two POC-proven correctness invariants:
   - FLATTEN BEFORE ENCODE: the renderer's straight-alpha RGBA carries
     GARBAGE color bytes under A=0 pixels; SrcOver onto opaque black
     (c*a/255) makes them deterministically black. Encoding un-flattened
     data bakes garbage into the gallery.
   - EXPLICIT quality: ImageWriteParam MODE_EXPLICIT — the default writer
     quality is not a contract.
   Headless is forced at ns-load so the encoder behaves identically under a
   display-less container. Encoded JPEGs are PRESENTATION artifacts only —
   goldens hash the RAW RGBA (encoder-independent), never these bytes."
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio IIOImage ImageIO ImageWriteParam ImageWriter)
           (javax.imageio.stream MemoryCacheImageOutputStream)))

(set! *warn-on-reflection* true)

;; BufferedImage/Graphics2D need no display, but force headless before any
;; AWT toolkit use so container runs cannot differ from desktop runs.
(System/setProperty "java.awt.headless" "true")

(def default-quality
  "Gallery encode quality. 0.85 is the POC verdict, CONFIRMED by the
   light-theme/text-heavy spot-check at gallery build (the measured
   0.85-vs-0.90 numbers + verdict live in the devcards.gallery ns
   docstring); the agreed 0.90 fallback was not needed."
  0.85)

(defn flatten->rgb
  "SrcOver-flatten straight-alpha RGBA bytes (w*h*4) onto opaque black into
   a TYPE_INT_RGB BufferedImage. out = round(c*a/255); A=0 → (0,0,0)
   regardless of the garbage color bytes underneath."
  ^BufferedImage [^bytes raw ^long w ^long h]
  (let [expected (* w h 4)]
    (when (not= (alength raw) expected)
      (throw (ex-info "framebuffer size mismatch"
                      {:expected expected :actual (alength raw) :w w :h h}))))
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)]
    (dotimes [y h]
      (dotimes [x w]
        (let [i (* (+ (* y w) x) 4)
              r (bit-and (aget raw i) 0xFF)
              g (bit-and (aget raw (+ i 1)) 0xFF)
              b (bit-and (aget raw (+ i 2)) 0xFF)
              a (bit-and (aget raw (+ i 3)) 0xFF)
              fr (quot (+ (* r a) 127) 255)
              fg (quot (+ (* g a) 127) 255)
              fb (quot (+ (* b a) 127) 255)]
          (.setRGB img x y (bit-or (bit-shift-left fr 16) (bit-shift-left fg 8) fb)))))
    img))

(defn encode
  "Encode a BufferedImage as JPEG bytes at `quality` (0.0-1.0, explicit
   compression mode)."
  ^bytes [^BufferedImage img quality]
  (let [writer ^ImageWriter (.next (ImageIO/getImageWritersByFormatName "jpeg"))
        param (.getDefaultWriteParam writer)
        baos (ByteArrayOutputStream.)]
    (.setCompressionMode param ImageWriteParam/MODE_EXPLICIT)
    (.setCompressionQuality param (float quality))
    (with-open [ios (MemoryCacheImageOutputStream. baos)]
      (.setOutput writer ios)
      (.write writer nil (IIOImage. img nil nil) param))
    (.dispose writer)
    (.toByteArray baos)))

(defn crop
  "Content crop for documentation previews: sub-image of `img` around the
   rect [x1 y1 x2 y2] (inclusive, dump_tree coord convention) grown by
   `margin` px and clamped to the image. The crop rect comes from the card's
   DUMPED coords (the wrapper card's bbox where the spec wraps the widget,
   else the widget bbox) — deterministic, no pixel scanning — and the margin
   absorbs outline/shadow bleed drawn outside the bbox. PRESENTATION only:
   goldens hash the FULL raw framebuffer, never cropped output."
  ^BufferedImage [^BufferedImage img [x1 y1 x2 y2] margin]
  (let [x (max 0 (- x1 margin))
        y (max 0 (- y1 margin))
        x' (min (dec (.getWidth img)) (+ x2 margin))
        y' (min (dec (.getHeight img)) (+ y2 margin))]
    (when (or (< x' x) (< y' y))
      (throw (ex-info "crop rect empty after clamp" {:rect [x1 y1 x2 y2] :margin margin})))
    (.getSubimage img x y (inc (- x' x)) (inc (- y' y)))))

(def default-crop-margin
  "Preview crop margin, px. Covers the measured worst-case out-of-bbox
   bleed (focus outline width+pad ~12px) with headroom."
  16)
