(ns cascade-probe
  "SCRATCH, not a deliverable. Renders the shipped corpus and censuses the
   resolved-style keys dump_obj emits, so the fg/bg question is answered from
   measurement rather than from reading the C."
  (:require [clojure.data.json :as json]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render+dump!
  [^bytes pb dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn -main [& args]
  (let [limit (when (seq args) (Long/parseLong (first args)))
        spec (fixtures/load-spec)
        built (cond->> (concat (fixtures/build-all spec)
                               (composition/build-all (composition/load-inventory)))
                limit (take limit))
        trees (doall (for [{:keys [id] ^bytes pb :bytes} built
                           mode [:dark :light]]
                       [id mode (render+dump! pb (= mode :dark))]))
        nodes (for [[id mode t] trees n (walk t)] (assoc n :card (str id "|" (name mode))))
        n-total (count nodes)]
    (println (format "cards=%d renders=%d nodes=%d" (count built) (count trees) n-total))

    (println "\n══ KEY PRESENCE (resolved-style + related) ══")
    (doseq [k [:text :text_color :text_opa :bg_color :bg_opa :opa :text_on
               :backdrop_unresolved :hidden :disabled :vis_px :type]]
      (println (format "  %-22s %6d / %d" (str k) (count (filter #(contains? % k) nodes)) n-total)))

    (println "\n══ ROOT always emits text_color? ══")
    (println (format "  roots=%d  roots with text_color=%d"
                     (count trees)
                     (count (filter #(contains? (nth % 2) :text_color) trees))))

    (println "\n══ backdrop_unresolved: by class ══")
    (doseq [[c n] (sort-by (comp - val) (frequencies (map :type (filter :backdrop_unresolved nodes))))]
      (println (format "  %-22s %5d" c n)))

    (println "\n══ text_on: by class, with which part ══")
    (doseq [[[c p] n] (sort-by (comp - val)
                               (frequencies (map (juxt :type (comp :part :text_on))
                                                 (filter :text_on nodes))))]
      (println (format "  %-22s part=%-12s %5d" c p n)))

    (println "\n══ text_on member presence (of nodes that HAVE text_on) ══")
    (let [ts (map :text_on (filter :text_on nodes))]
      (doseq [k [:part :color :text_opa :bg :bg_opa]]
        (println (format "  %-10s %5d / %d" (str k) (count (filter #(contains? % k) ts)) (count ts)))))

    (println "\n══ NODES WITH bg_color BUT NO bg_opa (=> fill fully covers) ══")
    (println (format "  %d" (count (filter #(and (contains? % :bg_color) (not (contains? % :bg_opa))) nodes))))
    (println "══ NODES WITH bg_opa BUT NO bg_color (must be ZERO by construction) ══")
    (println (format "  %d" (count (filter #(and (contains? % :bg_opa) (not (contains? % :bg_color))) nodes))))

    (println "\n══ CLASSES that draw glyphs per devcards.opa but emit NO text key ══")
    (let [glyphy #{"lv_buttonmatrix" "lv_checkbox" "lv_dropdown" "lv_dropdown-list"
                   "lv_roller" "lv_roller_label" "lv_scale" "lv_spinbox" "lv_textarea"}]
      (doseq [[c n] (sort-by (comp - val) (frequencies (map :type (filter #(glyphy (:type %)) nodes))))]
        (let [sub (filter #(= c (:type %)) nodes)]
          (println (format "  %-22s n=%-5d text=%-5d text_on=%-5d backdrop_unresolved=%-5d bg_color=%-5d"
                           c n
                           (count (filter #(contains? % :text) sub))
                           (count (filter #(contains? % :text_on) sub))
                           (count (filter :backdrop_unresolved sub))
                           (count (filter #(contains? % :bg_color) sub)))))))

    (println "\n══ lv_label: how many have NO bg_color (glyphs on an ancestor fill) ══")
    (let [labels (filter #(= "lv_label" (:type %)) nodes)
          nonblank (filter #(seq (str (:text %))) labels)]
      (println (format "  lv_label=%d  non-blank text=%d  no bg_color=%d  backdrop_unresolved=%d"
                       (count labels) (count nonblank)
                       (count (remove #(contains? % :bg_color) nonblank))
                       (count (filter :backdrop_unresolved nonblank)))))

    (println "\n══ SAMPLE: first card, pretty ══")
    (let [[id mode t] (first trees)]
      (println (str id "|" (name mode)))
      (println (subs (json/write-str t) 0 (min 3000 (count (json/write-str t))))))

    (println "\n══ SAMPLE: a node carrying text_on ══")
    (when-let [n (first (filter :text_on nodes))]
      (println (json/write-str (dissoc n :children))))
    (println "\n══ SAMPLE: a node carrying backdrop_unresolved ══")
    (when-let [n (first (filter :backdrop_unresolved nodes))]
      (println (:card n) (json/write-str (dissoc n :children))))
    (println "\n══ SAMPLE: an lv_roller_label ══")
    (when-let [n (first (filter #(= "lv_roller_label" (:type %)) nodes))]
      (println (:card n) (json/write-str (dissoc n :children))))
    (println "\n══ SAMPLE: an lv_roller ══")
    (when-let [n (first (filter #(= "lv_roller" (:type %)) nodes))]
      (println (:card n) (json/write-str (update n :children #(map (fn [c] (dissoc c :children)) %)))))
    (println "\n══ DISTINCT top-level keys observed across all nodes ══")
    (println (pr-str (vec (sort (map str (distinct (mapcat keys nodes)))))))
    (println "done")))
