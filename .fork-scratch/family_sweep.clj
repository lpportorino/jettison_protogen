;; Does the recolor exemption behave correctly under the OTHER theme families?
;;
;; asgard_theme_recolor_is_declared returns false for anything unless the live
;; family is ASGARD. If VANILLA/STOCK still attached recolor styles, every
;; colour they produced would be reported as a finding — a whole family
;; mis-judged. The claim is that they attach none (hover / pressed /
;; disabled_dim are asgard-only), so `false` is correct rather than a blind
;; spot. That is the difference between a guard and a gap, so measure it.
(require '[clojure.data.json :as json]
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

(def built (fixtures/build-all (fixtures/load-spec)))

(defn- row [{:keys [id bytes]} family]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w 800 :h 480})]
    (try
      (host/set-theme-family! h family)
      (host/render-card! h {:pb bytes :bp 0 :dark 1})
      (let [payload (draw-palette! h)
            tree (json/read-str (host/dump-tree-raw! h) :key-fn keyword)]
        {:id (str id)
         :family family
         :colors (:colors payload)
         :findings (palette/palette-findings
                    {:card-id (str id) :tree tree :draw-palette payload
                     :thresholds {:colors-by-mode palette/palette-by-mode}})})
      (finally (host/close! h)))))

(doseq [family [0 1 2]]
  (let [rows (mapv #(row % family) built)
        colors (mapcat :colors rows)]
    (println "family" family
             {:cards (count rows)
              :colour-emissions (count colors)
              :tagged-declared-recolor (count (filter :theme_recolor colors))
              :findings (count (mapcat :findings rows))
              :distinct-finding-hexes
              (count (into #{} (map #(subs (:detail %) 0 7))
                           (mapcat :findings rows)))})))
