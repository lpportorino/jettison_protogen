(ns devcards.gallery
  "Per-widget contact sheets (T2.7) — composed from the SAME gated atomic
   frames the corpus battery hashes, rendered through the same pipeline
   machinery (devcards.host, fresh context per card, the pinned protocol,
   800x480 canvas). The sheet is the gallery unit: never a second fixture
   set, never re-authored cards.

   Three sheets per widget — the committed doc set:
   - vanilla        family 1, dark  (the stock-idempotent layer)
   - asgard-dark    family 0, dark  (the shipped look)
   - asgard-light   family 0, light

   Cells are the widget's atomic cards in spec order (state x size[/value]),
   each CROPPED TO CONTENT: the crop rect is the card's own dump_tree
   coords — the harness root's FIRST child, which is the WRAPPER node where
   the spec wraps the widget (slider/checkbox-small/spinner-small/
   buttonmatrix-small bleed wrappers) and the subject widget itself
   everywhere else — grown by jpeg/default-crop-margin. Each family renders
   its OWN dump: self-sizing widgets (switch, checkbox, dropdown) measure
   differently under different themes, so one family's geometry must never
   crop another's pixels. Crop is presentation-only; goldens stay
   full-canvas raw.

   The crop margin only absorbs bleed that was actually RENDERED. A card
   whose subject sits at the harness-root origin clips any outside-the-box
   decoration (a focus/edited outline ring, an edge-tick scale label) at
   the CANVAS edge — those pixels never exist, and no margin can restore
   them. Outline/ext-draw-bearing classes therefore carry a padded spec
   WRAPPER for standoff (slider, spinbox, the linear scales); the margin's
   job is bbox-external bleed on the open sides (measured worst case ~4px
   outline + AA — well inside jpeg/default-crop-margin).

   Row chunking: a new row starts at max-cells-per-row cells or when the
   row would exceed max-row-px, whichever comes first — 6-up stays the
   ceiling for readability, and the width cap keeps wide-cell classes
   (table/tabview large, the kitchen sinks) from composing into a
   several-thousand-px strip. Narrow cells are padded up to their label
   width first so labels never overlap adjacent cells.

   QUALITY VERDICT (the pinned 0.85-vs-0.90 conditional, measured on the
   text-heavy light-theme spot-check — the WIDGET_TEXTAREA asgard-light
   sheet, 12 text cells, 1352x238): q0.85 = 44,409 bytes, q0.90 = 50,664
   bytes (1.14x); decode-vs-original per-channel mean abs error 0.694
   (q0.85) vs 0.497 (q0.90) on 0..255, max channel error 67 vs 64
   (isolated glyph-edge ringing px — 0.90 does not eliminate it), pixels
   with any channel delta > 10: 1.13% vs 0.58%, PSNR 41.0 dB vs 43.0 dB.
   Both sit in the visually-transparent band (PSNR > 40 dB) and the error
   concentrates on antialiased glyph edges, not flat light-theme fields
   (banding would read as large flat-area MAE — absent at 0.7/255 mean).
   VERDICT: KEEP jpeg/default-quality 0.85; the 0.90 fallback stays
   unneeded."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.host :as host]
            [devcards.jpeg :as jpeg])
  (:import (java.awt Graphics2D)
           (java.awt.image BufferedImage)))

(set! *warn-on-reflection* true)

(def family-renders
  "The three committed gallery render sets, in doc-page order. :file-suffix
   names the artifact (<WIDGET>-<suffix>.jpg); :title heads the doc-page
   section. Closed — a fourth set is a deliberate doc-contract change."
  [{:key :vanilla
    :file-suffix "vanilla"
    :family 1
    :dark 1
    :title "vanilla (family 1, dark)"}
   {:key :asgard-dark
    :file-suffix "asgard-dark"
    :family 0
    :dark 1
    :title "asgard dark (family 0)"}
   {:key :asgard-light
    :file-suffix "asgard-light"
    :family 0
    :dark 0
    :title "asgard light (family 0)"}])

(def max-cells-per-row
  "Row-chunking cell ceiling (readability — the operator's ~6-up call)."
  6)

(def max-row-px
  "Row-chunking width cap, px. Keeps wide-cell classes (table/tabview
   large, kitchen sinks) from composing into an unreadably wide strip."
  2000)

(defn- crop-node
  "The card's crop subject from its parsed dump tree: the harness root's
   FIRST child (the spec wrapper where wrapped, else the widget). Throws
   when the tree is truncated or the node/coords are absent — a card whose
   geometry cannot be located is an error, never a full-canvas fallback."
  [card-id tree]
  (when (:truncated tree)
    (throw (ex-info "dump tree truncated — crop coords unjudgeable" {:card card-id})))
  (let [harness-root (first (:children tree))
        subject (first (:children harness-root))]
    (when-not (and (map? subject) (= 4 (count (:coords subject))))
      (throw (ex-info
              "crop node missing from dump tree"
              {:card card-id :root-type (:type tree) :harness-type (:type harness-root)})))
    subject))

(defn render-cell!
  "Render ONE card under one family set and crop it to content. `paths` =
   {:wasm :assets}; `canvas` = {:w :h}; `fam` = a family-renders entry.
   Fresh context per render (the hermetic builder law). Returns the cropped
   RGB BufferedImage."
  ^BufferedImage [paths canvas ^bytes pb {:keys [family dark]} card-id]
  (let [h (host/start!
           {:wasm (:wasm paths) :assets (:assets paths) :w (:w canvas) :h (:h canvas)})]
    (try (when (pos? (long family)) (host/set-theme-family! h family))
         (let [fb (host/render-card! h {:pb pb :bp 0 :dark dark})
               tree (json/read-str (host/dump-tree! h) :key-fn keyword)]
           (jpeg/crop (jpeg/flatten->rgb fb (:w canvas) (:h canvas))
                      (:coords (crop-node card-id tree))
                      jpeg/default-crop-margin))
         (finally (host/close! h)))))

(defn cell-label
  "The sheet-cell label: the card id's tail past the class segment —
   state/size[/value] for atomic cards, the sink slug for kitchen sinks."
  ^String [^String card-id]
  (let [parts (str/split card-id #"/")]
    (when (< (count parts) 2)
      (throw (ex-info "card id has no class/tail split" {:id card-id})))
    (str/join "/" (rest parts))))

(defn- label-px-width
  "Measured pixel width of `label` under the contact-sheet label font (one
   font home: jpeg/label-font)."
  ^long [^String label]
  (let [img (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB)
        g ^Graphics2D (.createGraphics img)]
    (try (.setFont g (jpeg/label-font))
         (long (.stringWidth (.getFontMetrics g) label))
         (finally (.dispose g)))))

(defn- pad-cell
  "Widen a cell image to at least its label's width (centered on the checker
   gutter) so a long label under a narrow crop never overlaps its neighbors. A
   cell already wide enough passes through untouched."
  [{:keys [^BufferedImage image ^String label] :as cell}]
  (let [min-w (+ (label-px-width label) 4)
        w (.getWidth image)]
    (if (>= w min-w)
      cell
      (let [padded (BufferedImage. min-w (.getHeight image) BufferedImage/TYPE_INT_RGB)
            g ^Graphics2D (.createGraphics padded)]
        (.setPaint g (jpeg/checker-paint))
        (.fillRect g 0 0 min-w (.getHeight image))
        (.drawImage g image (int (quot (- min-w w) 2)) 0 nil)
        (.dispose g)
        (assoc cell :image padded)))))

(defn chunk-cells
  "Chunk cells into sheet rows: a row closes at max-cells-per-row cells or
   when adding the next cell would push the row past max-row-px (a single
   over-wide cell still gets its own row — never an empty one)."
  [cells]
  (let [row-w (fn [row]
                (+ jpeg/sheet-gutter
                   (reduce +
                           (map (fn [{:keys [^BufferedImage image]}]
                                  (+ (.getWidth image) jpeg/sheet-gutter))
                                row))))]
    (reduce (fn [rows cell]
              (let [row (peek rows)]
                (if (and row
                         (< (count row) max-cells-per-row)
                         (<= (+ (long (row-w row))
                                (.getWidth ^BufferedImage (:image cell))
                                jpeg/sheet-gutter)
                             max-row-px))
                  (conj (pop rows) (conj row cell))
                  (conj rows [cell]))))
            []
            cells)))

(defn- stack-rows
  "Stack row images (each a jpeg/contact-sheet strip) vertically onto one
   checker-gutter canvas, left-aligned — the multi-row sheet."
  ^BufferedImage [rows]
  (when (empty? rows) (throw (ex-info "sheet needs at least one row" {})))
  (let [w (apply max (map (fn [^BufferedImage r] (.getWidth r)) rows))
        h (reduce + (map (fn [^BufferedImage r] (.getHeight r)) rows))
        out (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g ^Graphics2D (.createGraphics out)]
    (.setPaint g (jpeg/checker-paint))
    (.fillRect g 0 0 w h)
    (reduce (fn [^long y ^BufferedImage row]
              (.drawImage g row 0 (int y) nil)
              (+ y (.getHeight row)))
            0
            rows)
    (.dispose g)
    out))

(defn sheet
  "Compose labeled cells [{:image :label} ...] into ONE multi-row contact
   sheet (pad narrow cells, chunk rows, stack)."
  ^BufferedImage [cells]
  (when (empty? cells) (throw (ex-info "contact sheet over zero cells" {})))
  (stack-rows (mapv jpeg/contact-sheet (chunk-cells (mapv pad-cell cells)))))

(defn family-sheet!
  "Render + compose ONE family's contact sheet over `entries` (built corpus
   entries [{:id :bytes ...}], spec order). A class with zero renderable
   entries is an ERROR — an empty sheet would document nothing and look
   published."
  ^BufferedImage [paths canvas entries fam]
  (when (empty? entries)
    (throw (ex-info "widget class has ZERO renderable cards" {:family (:key fam)})))
  (sheet (mapv (fn [{:keys [id ^bytes bytes]}]
                 {:image (render-cell! paths canvas bytes fam (str id))
                  :label (cell-label (str id))})
               entries)))