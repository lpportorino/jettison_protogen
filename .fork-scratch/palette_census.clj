(require '[clojure.data.json :as json]
         '[devcards.composition :as composition]
         '[devcards.fixtures :as fixtures]
         '[devcards.host :as host]
         '[devcards.palette :as palette])

;; The palette now has its own export. Read it exactly the way host/dump-tree-raw!
;; reads the tree: call, then copy the C string out of linear memory before the
;; next call into the module. Done here rather than in devcards.host because
;; this fork does not own that file — which is itself the arming step the report
;; names for the lift.
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

(defn render-row [{:keys [id] :as entry} mode]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w 800
                        :h 480})]
    (try
      (host/render-card! h {:pb (:bytes entry)
                            :bp 0
                            :dark (if (= mode :dark) 1 0)})
      (let [payload (draw-palette! h)
            raw (host/dump-tree-raw! h)
            tree (json/read-str raw :key-fn keyword)
            findings (palette/palette-findings
                      {:card-id (str id)
                       :tree tree
                       :draw-palette payload
                       :thresholds {:colors-by-mode palette/palette-by-mode}})]
        {:id (str id)
         :mode mode
         :dump-bytes (count (.getBytes raw "UTF-8"))
         :records (:records payload)
         :colors (:colors payload)
         :overflow (boolean (:overflow payload))
         :findings findings})
      (finally (host/close! h)))))

(def rows
  (vec (for [entry built
             mode [:dark :light]]
         (render-row entry mode))))

(def worst-records (apply max-key :records rows))
(def worst-dump (apply max-key :dump-bytes rows))
(def all-colors (mapcat :colors rows))
(def ordinary (remove :theme_recolor all-colors))
(def theme-recolors (filter :theme_recolor all-colors))
(def outside (mapcat :findings rows))

(println "renders" (count rows))
(println "task records (deduped sum)" (reduce + (map :records rows)))
(println "max task records" (select-keys worst-records [:id :mode :records]))
(println "unique colour-pair emissions (sum per render)" (count all-colors))
(println "declared-theme-recolor emissions" (count theme-recolors))
(println "ordinary emissions" (count ordinary))
(println "outside-palette findings" (count outside))
(println "cards/modes with findings" (count (filter (comp seq :findings) rows)))
(println "distinct outside hexes"
         (count (set (map #(subs (:detail %) 0 7) outside))))
(println "overflows" (count (filter :overflow rows)))
(println "min task records" (select-keys (apply min-key :records rows) [:id :mode :records]))
(println "renders with ZERO colour-bearing records" (count (filter #(zero? (long (:records %))) rows)))
(println "renders with an EMPTY colour set" (count (filter #(empty? (:colors %)) rows)))
(println "worst dump" (select-keys worst-dump [:id :mode :dump-bytes]))
(doseq [mode [:dark :light]]
  (let [mode-rows (filter #(= mode (:mode %)) rows)
        mode-colors (mapcat :colors mode-rows)
        mode-outside (mapcat :findings mode-rows)]
    (println mode
             {:records (reduce + (map :records mode-rows))
              :colour-pairs (count mode-colors)
              :distinct-hexes (count (set (map :hex mode-colors)))
              :theme-recolors (count (filter :theme_recolor mode-colors))
              :findings (count mode-outside)})))
