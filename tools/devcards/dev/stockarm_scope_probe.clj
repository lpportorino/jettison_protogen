(ns stockarm-scope-probe
  "Empirical probe: if the DOM invariant lanes judged the vanilla (1) and stock
   (2) theme families, WHAT WOULD THEY REPORT — and is a glyph missing from a
   vanilla render absent from the DOM or merely invisible in it?

   Exists because `devcards.core` runs the invariant lanes over family 0 only;
   families 1/2 get per-card hash EQUALITY to each other and nothing else. The
   argument for leaving it that way is that the stock arm's defects belong to
   vendored upstream and would become a permanent exemption ratchet. That is a
   claim about a COUNT, and a count nobody has taken is not evidence. This is
   how it gets taken — the same role `opa_text_probe` plays for the opacity
   clause and `class_census` for the overlap lane.

   THREE ARMS, because the scope decision needs three different answers.

   `census` (default) is the ARMING measurement. It runs the REAL
   `lanes/atomic-findings` over every atomic card in all three families and
   prints per-family, per-invariant counts. The question is not `does the stock
   arm have defects` but `would arming report anything protogen can act on`, so
   it also prints the NOVEL set: findings in family 1/2 whose [card invariant
   node] key has no family-0 twin. That is precisely the set a consumer would
   have to fix or exempt, and its size is what decides scope.

   `dom <substring>...` is the DIAGNOSIS. For each matching card it renders all
   three families and prints every `lv_roller`/`lv_roller_label`/`lv_label`
   node with its coords, its :text and every layout-defect flag the invariant
   lane knows, then a run-length ink profile of the roller's own coords box,
   SrcOver-flattened onto black exactly as `devcards.jpeg` does (so the hexes
   describe the gallery sheet, and are comparable digit-for-digit with
   `roller_bounds_probe`'s).

   `band <substring>...` removes the ink threshold from that diagnosis. It
   prints every scanline's raw non-modal count and distinct-colour count, so a
   narrow glyph that falls below the `dom` arm's ink floor cannot be mistaken
   for a glyph that contributes no pixels.

   WHY THE DIAGNOSIS NEEDS ALL THREE FAMILIES AND BOTH PIXEL ARMS. Text painted
   in exactly the fill's own colour contributes ZERO non-modal pixels, and so
   does text that was never painted — the two are byte-identical in the
   framebuffer. The internal roller label also exposes no :text value in ANY
   family, including the good Asgard control, so DOM absence is not evidence
   of raster absence. The dump can report layout flags and declared part
   colours; the family control plus the threshold-free profile says whether
   glyph pixels actually reached the band. No one half can carry that verdict.

   RAW FLAGS ARE NOT LIVE FINDINGS. The flag report lists positive dump keys;
   `lanes/atomic-findings` is printed separately because its designed-geometry
   rules may correctly exclude one. Conversely, a post-draw operation with no
   dump node can fail without setting any object flag. Empty findings mean only
   that the current DOM lane reported nothing.

   ALL THREE FAMILIES ARE RENDERED IN EVERY ARM. A family-0-only run would
   reproduce exactly the blind spot under measurement.

   Read-only: renders, counts, prints. Writes nothing, gates nothing, and is
   not part of the battery.

   Run (in the toolchain container, from tools/devcards/) — it needs no alias
   of its own, so it cannot collide with the corpus CLI's:
     clojure -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' -M:bindings:p \\
       -m stockarm-scope-probe
     ... -m stockarm-scope-probe dom lv_roller/default/small"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private families
  "family id → label. The ids are `asgard_theme_family_t` in
   renderer/src/theme.h; 0 is the shipped look and the only family whose DOM
   the gate judges today."
  (sorted-map 0 "asgard" 1 "vanilla" 2 "stock"))

(def ^:private ink-floor
  "Minimum non-modal pixel count for a scanline to count as carrying a GLYPH
   rather than an AA fringe or a one-pixel border. Same value and same
   reasoning as `roller_bounds_probe`'s, so the two profiles are comparable."
  8)

(def ^:private defect-keys
  "The invariant lane's own layout-defect vocabulary, plus the root-level
   truncation key that `host/dump-tree!` normalises before parsing."
  (conj invariants/defect-flags :truncated))

(defn- render+dump!
  "One hermetic render at `family`, returning {:fb :tree}."
  [^bytes pb family dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try (when (pos? (long family)) (host/set-theme-family! h family))
         (let [fb (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})]
           {:fb fb :tree (json/read-str (host/dump-tree! h) :key-fn keyword)})
         (finally (host/close! h)))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

;; ── the census arm ──────────────────────────────────────────────────────

(defn- census
  []
  (let [spec (fixtures/load-spec)
        built (fixtures/build-all spec)
        t0 (System/nanoTime)
        _ (println (format "rendering %d atomic cards x %d families…"
                           (count built) (count families)))
        by-family
        (into (sorted-map)
              (for [[fam label] families]
                [fam
                 {:label label
                  :findings
                  (vec (mapcat
                        (fn [{:keys [id expect] ^bytes pb :bytes}]
                          (lanes/atomic-findings
                           id expect (:tree (render+dump! pb fam true))))
                        built))}]))]

    (println (format "\n%.1fs elapsed" (/ (- (System/nanoTime) t0) 1e9)))
    (println "\n== FINDINGS PER FAMILY (the real lanes/atomic-findings) ==")
    (println (format "%-12s %7s  %s" "family" "total" "by invariant"))
    (doseq [[fam {:keys [label findings]}] by-family]
      (println (format "%-12s %7d  %s"
                       (str fam " " label)
                       (count findings)
                       (pr-str (into (sorted-map)
                                     (update-vals (group-by :invariant findings)
                                                  count))))))

    (println "\n== WHAT ARMING WOULD ADD — findings with NO family-0 twin ==")
    (println "(keyed by [card invariant node]: the set a consumer would have")
    (println " to fix or exempt, and the number that decides the scope call)")
    (let [key-of (juxt :card :invariant :node)
          base (set (map key-of (:findings (get by-family 0))))]
      (doseq [[fam {:keys [label findings]}] by-family
              :when (pos? (long fam))]
        (let [novel (remove (comp base key-of) findings)]
          (println (format "\n  family %d (%s): %d NOVEL of %d total"
                           fam label (count novel) (count findings)))
          (doseq [[inv group] (sort-by (comp str key) (group-by :invariant novel))]
            (println (format "    %-30s %d" (str inv) (count group)))
            (doseq [f (take 8 group)]
              (println (format "      %-46s %s" (:card f) (:node f))))))))

    (println (str "\n0 NOVEL means arming would report nothing new and the\n"
                  "ratchet argument has no referent; a large NOVEL count names\n"
                  "the reference-control population the scope decision must\n"
                  "either own or decline. Either way the number is the evidence,\n"
                  "not the intuition."))))

;; ── the dom arm ─────────────────────────────────────────────────────────

(defn- hexof [[r g b]] (format "#%02X%02X%02X" r g b))

(defn- scanline
  "[modal-colour non-modal-count] of one scanline, SrcOver-flattened onto
   black — the `devcards.jpeg` convention."
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
  [pairs]
  (reduce (fn [acc [y v]]
            (let [[pv _ _ :as prev] (peek acc)]
              (if (and prev (= pv v))
                (conj (pop acc) [pv (nth prev 1) y])
                (conj acc [v y y]))))
          []
          pairs))

(defn- profile
  "fill + ink run-length profiles down the roller's own coords box."
  [^bytes raw coords]
  (let [[rx0 ry0 rx1 ry1] (mapv int coords)
        x0 (max 0 rx0) y0 (max 0 ry0)
        x1 (min (dec (long (:w canvas))) rx1)
        y1 (min (dec (long (:h canvas))) ry1)
        lines (mapv (fn [y] [y (scanline raw (:w canvas) y x0 x1)])
                    (range y0 (inc y1)))]
    {:fill (rle (map (fn [[y [m _]]] [y m]) lines))
     :ink (rle (map (fn [[y [_ n]]] [y (>= (long n) ink-floor)]) lines))}))

(defn- flags-of
  "Every defect key this node actually carries. Empty means the interpreter
   reports the node undamaged — NOT that it had nothing to say."
  [node]
  (into (sorted-map) (keep (fn [k] (when-some [v (get node k)] [k v]))) defect-keys))

(defn- dom-arm
  [filters]
  (let [spec (fixtures/load-spec)
        built (fixtures/build-all spec)
        picked (filterv (fn [{:keys [id]}]
                          (some #(str/includes? (str id) %) filters))
                        built)]
    (println (format "probing %d of %d cards; filters %s"
                     (count picked) (count built) (pr-str filters)))
    (doseq [{:keys [id expect] ^bytes pb :bytes} (sort-by (comp str :id) picked)]
      (println (format "\n=== %s  (:expect %s) ===" (str id) (pr-str expect)))
      (doseq [[fam label] families]
        (let [{:keys [fb tree]} (render+dump! pb fam true)
              nodes (walk tree)
              texty (filter #(contains? #{"lv_roller" "lv_roller_label" "lv_label"}
                                        (:type %))
                            nodes)
              roller (first (filter #(= "lv_roller" (:type %)) nodes))]
          (println (format "\n  -- family %d (%s) --" fam label))
          (doseq [n texty]
            (println (format "    %-16s coords=%-22s flags=%s"
                             (:type n) (pr-str (:coords n)) (pr-str (flags-of n))))
            (println (format "    %-16s text=%s" "" (pr-str (:text n)))))
          (when-let [coords (:coords roller)]
            (let [{:keys [fill ink]} (profile fb coords)]
              (println (str "    fill  "
                            (str/join "  " (map (fn [[v a b]]
                                                  (format "%d-%d %s" a b (hexof v)))
                                                fill))))
              (println (str "    ink   "
                            (str/join "  "
                                      (map (fn [[_ a b]] (format "%d-%d GLYPH" a b))
                                           (filter first ink)))))))
          (println (format "    lanes/atomic-findings: %s"
                           (pr-str (mapv (juxt :invariant :node)
                                         (lanes/atomic-findings id expect tree))))))))
    (println (str "\nink runs are GLYPH scanlines inside the roller's coords box.\n"
                  "No band ink plus no LIVE lane finding is the case at issue:\n"
                  "the DOM cannot distinguish same-colour text from a post-draw\n"
                  "operation that contributed no glyph pixels."))))

;; ── the band arm ────────────────────────────────────────────────────────

(defn- band-arm
  "RAW per-scanline evidence over the roller's coords box: the modal colour,
   the UNTHRESHOLDED non-modal pixel count, and the number of distinct colours
   on that scanline.

   Exists to kill one specific confound in the `dom` arm. That arm thresholds
   at `ink-floor`, so a glyph too thin to clear the floor and a glyph that was
   never drawn both print as `no ink`. A digit in a 48px-wide roller is about
   eight pixels of stroke, which is exactly where that floor sits — so the
   small-roller reading MUST be re-taken without it before anything is
   concluded from it. `n` here is a count, not a verdict."
  [filters]
  (let [spec (fixtures/load-spec)
        built (fixtures/build-all spec)
        picked (filterv (fn [{:keys [id]}]
                          (some #(str/includes? (str id) %) filters))
                        built)]
    (doseq [{:keys [id] ^bytes pb :bytes} (sort-by (comp str :id) picked)]
      (println (format "\n=== %s ===" (str id)))
      (doseq [[fam label] families]
        (let [{:keys [fb tree]} (render+dump! pb fam true)
              roller (first (filter #(= "lv_roller" (:type %)) (walk tree)))
              [rx0 ry0 rx1 ry1] (mapv int (:coords roller))
              x0 (max 0 rx0) x1 (min (dec (long (:w canvas))) rx1)
              y0 (max 0 ry0) y1 (min (dec (long (:h canvas))) ry1)]
          (println (format "\n  -- family %d (%s)  roller=%s --"
                           fam label (pr-str (:coords roller))))
          (doseq [y (range y0 (inc y1))]
            (let [[modal n] (scanline fb (:w canvas) y x0 x1)
                  distinct-n (count (into #{}
                                          (map (fn [x]
                                                 (let [i (* 4 (+ (* y (long (:w canvas))) x))
                                                       a (bit-and (aget ^bytes fb (+ i 3)) 0xFF)]
                                                   [(quot (* (bit-and (aget ^bytes fb i) 0xFF) a) 255)
                                                    (quot (* (bit-and (aget ^bytes fb (+ i 1)) 0xFF) a) 255)
                                                    (quot (* (bit-and (aget ^bytes fb (+ i 2)) 0xFF) a) 255)])))
                                          (range x0 (inc x1))))]
              (when (pos? (long n))
                (println (format "    y=%-4d modal=%s  non-modal=%-4d distinct=%d"
                                 y (hexof modal) n distinct-n)))))
          (println "    (scanlines with non-modal=0 omitted; they are uniform fill)"))))))

(defn -main
  [& args]
  (case (first args)
    "dom" (dom-arm (vec (rest args)))
    "band" (band-arm (vec (rest args)))
    (census)))
