;; Discriminator for the in-place theme-switch contamination hazard.
;;
;; The observer accumulates across the module's lifetime.  `controls_load_ui`
;; clears it, and the pinned render protocol always ends with a load, so the
;; corpus census is unaffected.  But `controls_set_theme_dark` /
;; `controls_set_theme_family` / `controls_set_breakpoint` restyle IN PLACE
;; without a load, and the producer selects ONE token table from the single
;; `theme_dark` the root reports.  If the buffer still holds the previous
;; mode's colours, they are judged against the wrong table.
;;
;; Measures, per card:
;;   D  = palette of a fresh dark render
;;   L  = palette of a fresh light render
;;   S  = palette after rendering dark then switching to light IN PLACE
;; Contamination = S ∩ (D \ L)  — dark-exclusive colours surviving into a
;; render the dump labels light.  Clean = empty, and S = L.
(require '[clojure.data.json :as json]
         '[clojure.set :as set]
         '[devcards.fixtures :as fixtures]
         '[devcards.host :as host])

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

(def built (fixtures/build-all (fixtures/load-spec)))

(defn- palette [h]
  (let [payload (draw-palette! h)
        tree (json/read-str (host/dump-tree-raw! h) :key-fn keyword)]
    {:mode (:theme_dark tree)
     :hexes (into #{} (map :hex) (:colors payload))
     :records (:records payload)}))

(defn- ticks! [h]
  (dotimes [_ host/render-ticks] (host/tick! h host/tick-ms)))

(defn- with-host [f]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w 800 :h 480})]
    (try (f h) (finally (host/close! h)))))

(defn- fresh [pb dark]
  (with-host (fn [h] (host/render-card! h {:pb pb :bp 0 :dark dark}) (palette h))))

(defn- switched [pb]
  (with-host
    (fn [h]
      (host/render-card! h {:pb pb :bp 0 :dark 1})
      (host/set-theme-dark! h 0)
      (ticks! h)
      (palette h))))

(def rows
  (vec (for [{:keys [id bytes]} built
             :let [d (fresh bytes 1)
                   l (fresh bytes 0)
                   s (switched bytes)
                   dark-only (set/difference (:hexes d) (:hexes l))]]
         {:id (str id)
          :dark-only (count dark-only)
          :leaked (set/intersection (:hexes s) dark-only)
          :s=l (= (:hexes s) (:hexes l))
          :s-mode (:mode s)
          :s-records (:records s)
          :l-records (:records l)})))

(def contaminated (filter (comp seq :leaked) rows))

(println "cards probed            " (count rows))
(println "dump labels the switched render light (theme_dark=0)"
         (= #{0} (set (map :s-mode rows))))
(println "cards leaking a dark-exclusive colour into a light-labelled dump"
         (count contaminated))
(println "cards where S = L (switched palette equals a fresh light palette)"
         (count (filter :s=l rows)))
(println "distinct leaked hexes   "
         (count (reduce into #{} (map :leaked rows))))
(doseq [r (take 6 contaminated)]
  (println "  " (:id r) "leaked" (sort (:leaked r))
           "records S/L" (:s-records r) "/" (:l-records r)))
