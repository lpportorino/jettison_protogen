(ns devcards.gallery
  "Per-CARD gallery images (T2.7) — composed from the SAME gated atomic
   frames the corpus battery hashes, rendered through the same pipeline
   machinery (devcards.host, fresh context per card, the pinned protocol,
   800x480 canvas). One card is the gallery unit: never a second fixture
   set, never re-authored cards.

   ONE IMAGE PER (card, family), NO LABEL BAKED IN. The doc set was three
   packed contact SHEETS per widget — many cells crammed into one JPEG with
   a label drawn under each. That packing put a short cell's label under the
   TALLEST cell in its row (labels floated far from their card), and left
   large dead gutters under short cells. Both are gone by construction: each
   card renders to its own tight crop, and the caption lives in the README
   grid as real markdown text that cannot drift from its image. The browser
   packs the grid; no packing algorithm here can get it wrong. Kitchen-sink
   cards are whole-screen compositions, so their per-card image already IS a
   composite screen — same rule, no special case.

   Three families per card — the committed doc set:
   - vanilla        family 1, dark  (the stock-idempotent layer)
   - asgard-dark    family 0, dark  (the shipped look)
   - asgard-light   family 0, light

   Each card is CROPPED TO CONTENT: the crop rect is the card's own dump_tree
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
  (:import (java.awt.image BufferedImage)))

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

(defn state-slug
  "A filename-safe token for a card's state label (`cell-label` output): the
   slashes separating state/size/value become underscores. Deterministic and
   collision-free within a unit (card ids, hence their tails, are unique)."
  ^String [^String label]
  (str/replace label "/" "_"))