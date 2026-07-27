(ns roller-bounds-probe
  "Empirical probe: WHERE does a roller put the selected option, and does that
   position depend on the selected INDEX?

   Exists to settle one claim by measurement rather than by reading. The
   `disabled_fill`-at-LV_PART_SELECTED fix argued that a disabled roller stays
   readable because `the selected row is the centred one` — the band drains to
   surface-2, so GEOMETRY becomes the only cue for which option is current. A
   post-fix review read `lv_roller_label` y0 moving one row-height per index
   step (49 / 11 / -27 for min / mid / max) and concluded the roller stops
   centring the selection at a list boundary, which would leave a disabled
   boundary roller with no cue at all.

   THOSE TWO READINGS OF THE SAME NUMBER ARE OPPOSITE, and only the
   framebuffer can say which is right. The label is the SCROLLED content: if
   the selection is pinned to the centre, the label MUST move exactly one row
   per step, so the observed shift is equally consistent with either story.
   What discriminates them is where the ink and the band land INSIDE the
   roller's own box, which is what this probe reports.

   METHOD. Two vertical profiles over the roller's dump `:coords` box, both
   run-length encoded so a 116-row box prints in one line:

     fill  — the modal colour of each scanline. The selected band, while it
             is a distinct fill, shows up as its own run; when the disabled
             swap drains it to surface-2 the run merges into the field and
             the profile correctly reports one fill, which is the post-fix
             state and not a probe failure.
     ink   — scanlines carrying at least `ink-floor` pixels that are not the
             scanline's own modal colour. These are the glyph rows. The ink
             runs are the measurement that matters, because they survive the
             band draining away.

   Pixels are SrcOver-flattened onto black exactly as `devcards.jpeg` does, so
   the hexes describe the gallery sheet rather than a straight-alpha
   intermediate — same convention as `disabled_pair_probe`, so the two are
   comparable digit for digit.

   THE ONE THING IT CANNOT SEE. It reports where ink IS, never which run a
   reader would call selected. On an enabled card the band fill settles that
   independently; on a disabled card the answer has to come from comparing the
   ink runs against the SAME card's enabled twin, which is why the default
   filter renders `default` and `disabled` at the same size side by side.

   Read-only: renders, measures, prints. Writes nothing, gates nothing, and is
   not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:roller-bounds
     clojure -M:bindings:roller-bounds lv_roller/disabled   ; substring filter"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

;; The value axis at one size, in both states. Enabled first so its band gives
;; the disabled card's ink runs a reference to be read against.
(def ^:private default-filter
  ["lv_roller/default/medium/min"
   "lv_roller/default/medium/mid"
   "lv_roller/default/medium/max"
   "lv_roller/disabled/medium/min"
   "lv_roller/disabled/medium/mid"
   "lv_roller/disabled/medium/max"])

(def ^:private ink-floor
  "Minimum count of non-modal pixels for a scanline to count as carrying a
   GLYPH rather than an anti-aliased fringe or a one-pixel border artefact.
   Same reasoning and same order of magnitude as `disabled_pair_probe`'s."
  8)

(defn- hexof [[r g b]] (format "#%02X%02X%02X" r g b))

(defn- scanline
  "The [modal-colour non-modal-count] of one scanline of the box, SrcOver-
   flattened onto black."
  [^bytes raw w y x0 x1]
  (let [hist (persistent!
              (reduce (fn [acc x]
                        (let [i (* 4 (+ (* (long y) (long w)) (long x)))
                              a (bit-and (aget raw (+ i 3)) 0xFF)
                              px [(quot (* (bit-and (aget raw i) 0xFF) a) 255)
                                  (quot (* (bit-and (aget raw (+ i 1)) 0xFF) a) 255)
                                  (quot (* (bit-and (aget raw (+ i 2)) 0xFF) a) 255)]]
                          (assoc! acc px (inc (long (get acc px 0))))))
                      (transient {})
                      (range x0 (inc x1))))
        [modal modal-n] (apply max-key val hist)]
    [modal (- (inc (- (long x1) (long x0))) (long modal-n))]))

(defn- rle
  "[[v y-start y-end] ...] over a seq of [y v] pairs."
  [pairs]
  (reduce (fn [acc [y v]]
            (let [[pv _ _ :as prev] (peek acc)]
              (if (and prev (= pv v))
                (conj (pop acc) [pv (nth prev 1) y])
                (conj acc [v y y]))))
          []
          pairs))

(defn- fmt-runs [runs f]
  (str/join "  " (map (fn [[v a b]] (format "%d-%d %s" a b (f v))) runs)))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- probe-one!
  [^bytes pb dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try
      (let [raw (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
            tree (json/read-str (host/dump-tree! h) :key-fn keyword)
            nodes (walk tree)
            roller (first (filter #(= "lv_roller" (:type %)) nodes))
            label (first (filter #(= "lv_roller_label" (:type %)) nodes))]
        (when-let [coords (:coords roller)]
          (let [[rx0 ry0 rx1 ry1] (mapv int coords)
                x0 (max 0 rx0) y0 (max 0 ry0)
                x1 (min (dec (long (:w canvas))) rx1)
                y1 (min (dec (long (:h canvas))) ry1)
                lines (mapv (fn [y] [y (scanline raw (:w canvas) y x0 x1)])
                            (range y0 (inc y1)))]
            {:roller coords
             :label (:coords label)
             :fill-runs (rle (map (fn [[y [m _]]] [y m]) lines))
             :ink-runs (rle (map (fn [[y [_ n]]] [y (>= (long n) ink-floor)]) lines))})))
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
    (doseq [{:keys [id] ^bytes pb :bytes} (sort-by (comp str :id) picked)
            dark [true false]]
      (if-let [r (probe-one! pb dark)]
        (do
          (println (format "\n%s  [%s]" (str id) (if dark "dark" "light")))
          (println (format "  roller %s   label %s"
                           (pr-str (:roller r)) (pr-str (:label r))))
          (println (str "  fill  " (fmt-runs (:fill-runs r) hexof)))
          (println (str "  ink   "
                        (fmt-runs (filterv (fn [[v _ _]] v) (:ink-runs r))
                                  (constantly "GLYPH")))))
        (println (format "\n%s  [%s]  NO lv_roller NODE" (str id) (if dark "dark" "light")))))
    (println (str "\nink runs are GLYPH scanlines inside the roller's own coords box.\n"
                  "If the selection is pinned to the centre, the band run (enabled)\n"
                  "and the corresponding ink run sit at the SAME y for every index."))))
