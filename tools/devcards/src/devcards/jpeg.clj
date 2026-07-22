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
  (:import (java.awt Color Font Rectangle RenderingHints TexturePaint)
           (java.awt.image BufferedImage)
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

(def ^:private checker-cell
  "Contact-sheet gutter checkerboard cell size, px."
  8)

(def ^:private checker-tile
  "The gutter checkerboard as a 2x2-cell tile, pre-generated ONCE and shared
   (a thread-safe delay) so every parallel sheet-compose thread READS the same
   bitmap instead of regenerating it. Two neutral grays bound both dark-theme
   and light-theme card surfaces against the gutter."
  (delay
    (let [s checker-cell
          n (* 2 s)
          tile (BufferedImage. n n BufferedImage/TYPE_INT_RGB)
          g (.createGraphics tile)]
      (.setColor g (Color. 0x99 0x99 0x99))
      (.fillRect g 0 0 n n)
      (.setColor g (Color. 0x66 0x66 0x66))
      (.fillRect g s 0 s s)
      (.fillRect g 0 s s s)
      (.dispose g)
      tile)))

(defn checker-paint
  "A TexturePaint tiling the shared gutter checkerboard from the origin — the
   contact-sheet / row-stack / cell-pad background fill, so each card's extent
   is legible against the gutter instead of near-black (which a dark card
   surface blends into)."
  ^TexturePaint []
  (TexturePaint. @checker-tile (Rectangle. 0 0 (* 2 checker-cell) (* 2 checker-cell))))

(def sheet-gutter
  "Contact-sheet cell gutter, px. Public so sheet composers (the gallery's
   row chunker) can account for the exact spacing without re-typing it."
  8)

(def label-font-spec
  "The contact-sheet label font as [name style size] data — ONE home for the
   font fact, so a caller measuring label widths (the gallery's
   pad-cell-to-label step) uses the same metrics the sheet draws with."
  ["SansSerif" Font/PLAIN 14])

(defn label-font
  "The contact-sheet label Font (from `label-font-spec`)."
  ^Font []
  (let [[nm style size] label-font-spec] (Font. ^String nm (int style) (int size))))

(def label-color
  "The contact-sheet label colour. The gutter is a mid-gray checkerboard
   (`checker-tile`, 0x99/0x66), against which plain white sat at low contrast
   on the light squares and read as part of the background. A high-chroma
   amber pops on both checker tones AND is distinct from the mostly-blue
   widget content, so a label never reads as part of the card it names."
  (Color. 0xFF 0xC4 0x00))

(def ^:private label-halo
  "Near-black halo drawn under the label. The checkerboard alternates tone
   every `checker-cell` px, so a label wide enough to span several cells
   crosses BOTH tones — no single flat colour is legible across all of it.
   The 1px halo pins contrast regardless of which tone a glyph lands on."
  (Color. 0x10 0x10 0x10))

(defn- draw-label!
  "Draw `label` at (lx, ly): halo first (8 single-px offsets), then the
   colour on top. Pure raster work — deterministic for a given input."
  [^java.awt.Graphics2D g ^String label ^long lx ^long ly]
  (.setColor g label-halo)
  (doseq [dx [-1 0 1]
          dy [-1 0 1]
          :when (not (and (zero? (long dx)) (zero? (long dy))))]
    (.drawString g label (int (+ lx (long dx))) (int (+ ly (long dy)))))
  (.setColor g label-color)
  (.drawString g label (int lx) (int ly)))

(defn contact-sheet
  "Compose a labeled N-up contact sheet from cells
   [{:image BufferedImage :label String} ...]: checkerboard background, uniform
   gutters, a haloed `label-color` label centered beneath each cell. Cells may
   differ in size; rows are single (the caller chunks multi-row sheets)."
  ^BufferedImage [cells]
  (when (empty? cells) (throw (ex-info "contact sheet needs at least one cell" {})))
  (let [gutter sheet-gutter
        label-h 22
        cell-w (fn [{:keys [^BufferedImage image]}] (.getWidth image))
        cell-h (fn [{:keys [^BufferedImage image]}] (.getHeight image))
        max-h (apply max (map cell-h cells))
        sheet-w (+ gutter (reduce + (map #(+ (cell-w %) gutter) cells)))
        sheet-h (+ gutter max-h label-h gutter)
        img (BufferedImage. sheet-w sheet-h BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (.setRenderingHint g
                       RenderingHints/KEY_TEXT_ANTIALIASING
                       RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
    (.setPaint g (checker-paint))
    (.fillRect g 0 0 sheet-w sheet-h)
    (.setFont g (label-font))
    (let [fm (.getFontMetrics g)]
      (loop [x gutter
             cells (seq cells)]
        (when cells
          (let [{:keys [^BufferedImage image ^String label]} (first cells)
                w (.getWidth image)]
            (.drawImage g image (int x) (int gutter) nil)
            (draw-label! g
                         label
                         (+ x (quot (- w (.stringWidth fm label)) 2))
                         (+ gutter max-h (.getAscent fm) 2))
            (recur (+ x w gutter) (next cells))))))
    (.dispose g)
    img))