(ns disabled-pair-probe
  "Empirical probe: what (glyph, fill) pair does a DISABLED widget actually
   RENDER, and what is its contrast?

   Exists because the whole-widget-opa question cannot be settled from the
   token manifest. `tools/devcards/dev/palette-audit.py` measures the AUTHORED
   pair — the two tokens the theme names. A whole-widget `lv_style_set_opa`
   folds into
   `layer->opa` (lv_refr.c `lv_obj_refr`) and a whole-widget `recolor` folds
   into `layer->recolor` (same function), and BOTH re-composite the glyph AND
   the fill against whatever sits behind the widget. So the pair the operator
   sees is not the pair the tokens declare, and only the framebuffer knows
   which one it is.

   That gap is the entire subject of the opa ban: the ban exists to make the
   effective pair EQUAL the authored pair, so that a token-level contrast gate
   can mean something. This probe is how you check the ban landed — run it
   before and after, and check that the printed `fill` and `glyph` hexes are
   TOKEN VALUES rather than composites. Under a fade they are not: the
   disabled dropdown reported #282837 / #56566A, two colours in no token
   table; after, #1E1E2E / #9A9BB6, which WERE surface-2 and disabled-fg
   exactly WHEN THIS WAS MEASURED. That identity is the result — the `ratio`
   is a consequence of it.

   THE SECOND HEX IS A RETIRED TRANSCRIPTION, kept past-tense rather than
   refreshed. `217ecfda` dropped the foreground ladder to three rungs and
   moved `disabled-fg`, which now resolves through `:fg-dim` to `#A7A8C3`
   (`edn/tokens.edn`). Re-running this probe today reports that value, not
   `#9A9BB6`. The IDENTITY claim — that the pair is exactly (surface-2,
   disabled-fg) rather than two composites under a fade — is what this
   paragraph exists to establish, and it survives the tone moving; only the
   literal did not. Read the token home for the current value and never this
   sentence, which is a record of one measurement.

   METHOD, and its one real assumption. Inside the widget's dump `:coords`
   box, take the modal colour as the FILL and the colour maximising |ΔY| from
   that fill (subject to a pixel-count floor, so anti-aliased fringes cannot
   win) as the GLYPH CORE. This is sound for a filled, text-bearing control —
   one dominant fill, one ink — and it is NOT sound for a widget whose box is
   mostly something else (an arc's track, a chart).

   IT REPORTS THE MOST EXTREME INK IN THE BOX, WHICH IS NOT ALWAYS THE TONE
   YOU ARE ASKING ABOUT. Observed: a disabled roller reports #FFFFFF, the
   white option text on the cyan SELECTED band, not the disabled-fg tone on
   the unselected rows — the selected band is simply further from the fill.
   So a widget with more than one ink needs its rows read as 'the fade is
   gone' rather than as 'this is the disabled pair'. Read the pixel counts
   too: a glyph core holding a handful of pixels is a fringe, not ink.

   Read-only: renders, measures, prints. Writes nothing, gates nothing, and
   is not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:disabled-pair
     clojure -M:bindings:disabled-pair roller      ; substring filter"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

;; The classes the opa ban is about, plus the two that had already declined
;; opa and kept a whole-widget RECOLOR instead (textarea, spinbox). Those two
;; were included as intended CONTROLS — "the ban should not move these" — and
;; they moved the most, which is how the recolor arm of the hazard was found.
;; Keep them in the default set: they are the cheapest evidence that the two
;; mechanisms are one problem.
(def ^:private default-filter
  ["lv_dropdown/disabled" "lv_dropdown/default"
   "lv_roller/disabled" "lv_roller/default"
   "lv_tabview/disabled" "lv_tabview/default"
   "lv_table/disabled" "lv_table/default"
   "lv_textarea/disabled" "lv_textarea/default"
   "lv_spinbox/disabled" "lv_spinbox/default"
   "lv_button/disabled" "lv_button/default"])

;; ── WCAG 2.x relative luminance + contrast. Identical arithmetic to
;; tools/devcards/dev/palette-audit.py, so the two are comparable digit for
;; digit. MIL-STD-1472H 5.2.2.7 states the same quantity and binds the
;; THRESHOLD (6:1 shall) — see the plan's PDL-1 section.
(defn- lin ^double [^long c]
  (let [c (/ (double c) 255.0)]
    (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- luminance ^double [[r g b]]
  (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b))))

(defn- contrast ^double [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn- hexof [[r g b]] (format "#%02X%02X%02X" r g b))

(defn- box-histogram
  "{[r g b] -> count} over the pixels inside `coords`, SrcOver-flattened onto
   black exactly as devcards.jpeg does, so the numbers describe what the
   gallery sheet shows rather than a straight-alpha intermediate."
  [^bytes raw w [x0 y0 x1 y1]]
  (persistent!
   (reduce
    (fn [acc [x y]]
      (let [i (* 4 (+ (* (long y) (long w)) (long x)))
            a (bit-and (aget raw (+ i 3)) 0xFF)
            px [(quot (* (bit-and (aget raw i) 0xFF) a) 255)
                (quot (* (bit-and (aget raw (+ i 1)) 0xFF) a) 255)
                (quot (* (bit-and (aget raw (+ i 2)) 0xFF) a) 255)]]
        (assoc! acc px (inc (long (get acc px 0))))))
    (transient {})
    (for [y (range y0 (inc y1)) x (range x0 (inc x1))] [x y]))))

(def ^:private ink-floor
  "Minimum pixel count for a colour to count as INK rather than an AA fringe.
   Deliberately small — a 12px glyph run has few full-strength core pixels —
   but non-zero, because a single fringe pixel at an extreme luminance would
   otherwise be reported as the text colour and inflate every ratio."
  8)

(defn- effective-pair
  "[fill glyph fill-n glyph-n] from a box histogram, or nil if the box has
   fewer than two colours clearing the ink floor (a solid box has no pair)."
  [hist]
  (let [[fill fill-n] (apply max-key val hist)
        yf (luminance fill)
        cands (filter (fn [[c n]] (and (>= (long n) ink-floor) (not= c fill))) hist)]
    (when (seq cands)
      (let [[glyph glyph-n] (apply max-key
                                   (fn [[c _]] (Math/abs (- (luminance c) yf)))
                                   cands)]
        [fill glyph fill-n glyph-n]))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- target-node
  "The node whose class the card id names — `lv_dropdown/disabled/small/mid`
   -> the first `lv_dropdown` in the tree. Falls back to the largest node so
   the probe still says something for a class the dump names differently."
  [tree card-id]
  (let [cls (first (str/split (str card-id) #"/"))
        nodes (walk tree)]
    (or (first (filter #(= cls (:type %)) nodes))
        (apply max-key
               (fn [n] (let [[x0 y0 x1 y1] (:coords n)]
                         (if (nil? x0) 0 (* (- x1 x0) (- y1 y0)))))
               nodes))))

(defn- probe-one!
  [^bytes pb card-id dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try
      (let [raw (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
            tree (json/read-str (host/dump-tree! h) :key-fn keyword)
            node (target-node tree card-id)
            coords (:coords node)]
        (when (and coords (every? some? coords))
          (let [[x0 y0 x1 y1] (mapv int coords)
                x0 (max 0 x0) y0 (max 0 y0)
                x1 (min (dec (:w canvas)) x1) y1 (min (dec (:h canvas)) y1)]
            (when (and (< x0 x1) (< y0 y1))
              (let [hist (box-histogram raw (:w canvas) [x0 y0 x1 y1])
                    pair (effective-pair hist)]
                {:type (:type node)
                 :coords [x0 y0 x1 y1]
                 :colours (count hist)
                 :pair pair})))))
      (finally (host/close! h)))))

(defn -main
  [& args]
  (let [filters (if (seq args) (vec args) default-filter)
        spec (fixtures/load-spec)
        built (concat (fixtures/build-all spec)
                      (composition/build-all (composition/load-inventory)))
        picked (filterv (fn [{:keys [id]}]
                          (some #(str/includes? (str id) %) filters))
                        built)]
    (println (format "probing %d of %d cards; filters %s"
                     (count picked) (count built) (pr-str filters)))
    (println (format "\n%-34s %-6s %-9s %-9s %8s %8s %7s"
                     "card" "mode" "fill" "glyph" "fill-px" "glyph-px" "ratio"))
    (doseq [{:keys [id] ^bytes pb :bytes} (sort-by (comp str :id) picked)
            dark [true false]]
      (let [r (probe-one! pb id dark)
            [fill glyph fill-px glyph-px] (:pair r)]
        (println
         (if (nil? fill)
           (format "%-34s %-6s %-9s" (str id) (if dark "dark" "light")
                   (if r "SOLID (no second colour over the ink floor)" "NO COORDS"))
           (format "%-34s %-6s %-9s %-9s %8d %8d %6.2f:1"
                   (str id) (if dark "dark" "light")
                   (hexof fill) (hexof glyph) fill-px glyph-px
                   (contrast fill glyph))))))
    (println (str "\nratio is the EFFECTIVE rendered pair. Compare against the "
                  "AUTHORED pair\nin tools/devcards/dev/palette-audit.py "
                  "section 1: the opa ban "
                  "makes them equal for DISABLED TEXT,\nwhere recolor_opa is "
                  "also pinned TRANSP — it is not a theme-wide identity "
                  "(hover,\npressed and disabled_dim all recolor; see "
                  "UI-QUALITY-CONTRACTS.md 6.2).\nGoverning floor is 6:1 "
                  "(MIL-STD-1472H 5.2.2.7), NOT WCAG's 4.5:1."))))
