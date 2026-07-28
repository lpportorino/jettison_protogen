;; Checks three claims the brief makes, rather than repeating them.
;;
;; 1. "Only 8 of 21 semantic colour tokens project to C at all."
;;    -> re-derive both populations from their live sources.
;; 2. "a substantial share of [the non-token colours] come from the theme's OWN
;;    recolor styles" -> of the emissions that match no token, what fraction
;;    carries the observer's declared-recolor tag?
;; 3. The brief says "the token palette", singular, and never mentions MODE.
;;    -> how many findings does a mode-blind union table lose?
(require '[clojure.data.json :as json]
         '[clojure.string :as str]
         '[devcards.composition :as composition]
         '[devcards.fixtures :as fixtures]
         '[devcards.host :as host]
         '[devcards.palette :as palette])

(defn- draw-palette! [{:keys [mem call!]}]
  (let [ptr (.asLong ^org.graalvm.polyglot.Value (call! "controls_dump_draw_palette"))
        sb (StringBuilder.)]
    (loop [i ptr]
      (let [b (.readBufferByte ^org.graalvm.polyglot.Value mem i)]
        (when-not (zero? b)
          (.append sb (char (bit-and b 0xff)))
          (recur (inc i)))))
    (json/read-str (.toString sb) :key-fn keyword)))

(def built
  (concat (fixtures/build-all (fixtures/load-spec))
          (composition/build-all (composition/load-inventory))))

(defn- render [{:keys [bytes]} mode]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w 800 :h 480})]
    (try
      (host/render-card! h {:pb bytes :bp 0 :dark (if (= mode :dark) 1 0)})
      (:colors (draw-palette! h))
      (finally (host/close! h)))))

(def emissions
  (vec (for [entry built
             mode [:dark :light]
             c (render entry mode)]
         (assoc c :mode mode))))

;; ── claim 1 ────────────────────────────────────────────────────────────────
(def manifest (json/read-str (slurp palette/manifest-path)))
(def colour-tokens
  (filter #(= "color" (get % "kind")) (vals (get manifest "tokens"))))
(def c-projected
  (->> (slurp "../../renderer/generated/theme_tokens.h")
       (re-seq #"#define\s+THEME_([A-Z0-9_]+)_DARK\s+0x[0-9A-Fa-f]{6}")
       (map second) set))

(println "CLAIM 1 — the two declared tables")
(println "  colour-kind tokens in design-tokens.json  " (count colour-tokens))
(println "  distinct dark values                      " (count (:dark palette/palette-by-mode)))
(println "  distinct light values                     " (count (:light palette/palette-by-mode)))
(println "  colour tokens reaching theme_tokens.h     " (count c-projected))

;; ── claim 2 ────────────────────────────────────────────────────────────────
(defn- token? [{:keys [hex mode]}]
  (contains? (get palette/palette-by-mode mode) (str/upper-case hex)))
(def non-token (remove token? emissions))
(def non-token-recolor (filter :theme_recolor non-token))

(println "CLAIM 2 — where the non-token colours come from")
(println "  total colour emissions                    " (count emissions))
(println "  emissions matching NO token value         " (count non-token))
(println "  ... of those, tagged a declared recolor   " (count non-token-recolor))
(println "  ... i.e. share exempted by the recolor tag"
         (format "%.1f%%" (* 100.0 (/ (count non-token-recolor)
                                      (double (max 1 (count non-token)))))))
(println "  emissions tagged recolor that ARE tokens  "
         (count (filter #(and (:theme_recolor %) (token? %)) emissions)))

;; ── claim 3 ────────────────────────────────────────────────────────────────
(def union-table (into #{} (mapcat val palette/palette-by-mode)))
(def mode-specific (remove :theme_recolor non-token))
(def under-union
  (remove #(contains? union-table (str/upper-case (:hex %))) mode-specific))

(println "CLAIM 3 — what a mode-blind table would lose")
(println "  findings with the mode-specific tables    " (count mode-specific))
(println "  findings with one union table             " (count under-union))
(println "  findings a union table would MISS         "
         (- (count mode-specific) (count under-union)))
(println "  distinct hexes only a mode-split can catch"
         (count (into #{} (map :hex)
                      (remove (set under-union) mode-specific))))
