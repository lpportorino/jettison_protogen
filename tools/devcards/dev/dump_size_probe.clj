(ns dump-size-probe
  "Empirical probe: how much of `dump_obj`'s SHARED output buffer does the real
   corpus actually use?

   `renderer/src/main.c` writes every card's tree into ONE static
   `TREE_BUF_SIZE` buffer; overflow does not fail the render, it appends the
   `,\"truncated\":true` sentinel that `devcards.invariants` turns into a
   `:dump-truncated` finding. So ANY always-emitted key added to `dump_obj`
   spends headroom on EVERY node of EVERY card, and the failure it eventually
   causes looks like a corpus defect rather than like a budget overrun.

   That makes the headroom a number a reviewer needs, not a feeling. This probe
   prints it: per-card raw dump bytes, the worst card, and the margin against
   the C buffer. Run it BEFORE and AFTER any change to `dump_obj`'s key set and
   put both numbers in the change.

   It also tallies how many nodes each conditional key actually lands on,
   because a key emitted only when it is informative costs nothing on the nodes
   it skips — and that ratio IS the argument for the emit-only-when-it-differs
   convention, so it should be a count rather than a claim. On a dump predating
   a key, its count is simply 0.

   Read-only: renders, dumps, counts, prints. Writes nothing, gates nothing,
   and is not part of the battery.

   Any arguments are treated as card-id SUBSTRINGS whose raw dump is printed in
   full, so a claim about one card's keys can be checked against the bytes the
   renderer actually produced rather than against a reading of the C.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:dump-size-probe
     clojure -M:bindings:dump-size-probe lv_buttonmatrix/default/small"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private tree-buf-size
  "Mirror of TREE_BUF_SIZE in renderer/src/main.c. Not imported from anywhere —
   the C macro is the single home; this copy exists so the probe can print a
   margin, and it is the probe that goes stale, never the gate."
  131072)

(defn- render+dump!
  "One hermetic render of `pb`, returning the RAW dump string (the bytes the C
   buffer actually held, before host normalisation or parsing). This probe is
   the one diagnostic caller of dump-tree-raw!; production callers use
   dump-tree!, which turns a truncation suffix into parseable root JSON."
  ^String [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark 1})
         (host/dump-tree-raw! h)
         (finally (host/close! h)))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(def ^:private conditional-keys
  "Every TOP-LEVEL dump key that is emitted only when it carries information.
   Counting them is how the buffer-cost argument stops being a claim.

   TOP-LEVEL is load-bearing, not a hedge: the counter below is
   `(filter k nodes)` over the flat node maps, so a key nested inside another
   object cannot be seen by it. `text_on` carries conditional `font` and
   `font_unnamed` spellings of its own; they are real conditional keys and they
   are NOT counted here. Listing them would report a constant zero, which reads
   as 'never emitted' rather than 'not measured' — the exact confusion this
   probe exists to remove. Widen the walk before widening the list."
  [:text :opa :text_color :text_opa :bg_color :bg_opa :text_on
   :backdrop_unresolved :click_area :descend_gate :vis_px :hidden :disabled
   :text_font :text_font_unnamed :paint_box :paint_bound])

(defn -main
  [& show]
  (let [spec (fixtures/load-spec)
        atomic (fixtures/build-all spec)
        comp-inv (composition/load-inventory)
        comp-built (composition/build-all comp-inv)
        built (concat atomic comp-built)
        _ (println (format "rendering %d cards (%d atomic + %d composition)…"
                           (count built) (count atomic) (count comp-built)))
        rows (mapv (fn [{:keys [id] ^bytes pb :bytes}]
                     (let [s (render+dump! pb)
                           truncated? (str/ends-with? s ",\"truncated\":true")
                           tree (when-not truncated?
                                  (json/read-str s :key-fn keyword))
                           nodes (if tree (walk tree) [])]
                       (when (some #(str/includes? (str id) %) show)
                         (println (format "\n══ RAW DUMP: %s (%d bytes) ══" id
                                          (count (.getBytes s "UTF-8"))))
                         (println s))
                       {:id (str id)
                        :bytes (count (.getBytes s "UTF-8"))
                        :nodes (count nodes)
                        :truncated? truncated?
                        :key-hits (into {}
                                        (map (fn [k]
                                               [k (count (filter k nodes))]))
                                        conditional-keys)}))
                   built)
        worst (apply max (map :bytes rows))
        total-nodes (reduce + (map :nodes rows))
        key-hits (apply merge-with + (map :key-hits rows))]

    (println "\n══ DUMP BYTES PER CARD (top 15) ══")
    (println (format "%-52s %9s %7s %7s" "card" "bytes" "nodes" "b/node"))
    (doseq [{:keys [id nodes] n-bytes :bytes} (take 15 (sort-by (comp - :bytes) rows))]
      (println (format "%-52s %9d %7d %7.1f"
                       id n-bytes nodes (double (/ n-bytes (max 1 nodes))))))

    (println "\n══ BUFFER HEADROOM ══")
    (println (format "TREE_BUF_SIZE          = %d bytes" tree-buf-size))
    (println (format "worst card             = %d bytes" worst))
    (println (format "headroom               = %d bytes (%.1f%% used)"
                     (- tree-buf-size worst)
                     (* 100.0 (/ worst (double tree-buf-size)))))
    (println (format "cards reporting :truncated = %d"
                     (count (filter :truncated? rows))))

    (println "\n══ NODE POPULATION ══")
    (println (format "cards            = %d" (count rows)))
    (println (format "nodes (total)    = %d" total-nodes))
    (println (format "worst card nodes = %d"
                     (:nodes (first (sort-by (comp - :bytes) rows)))))

    (println "\n══ CONDITIONAL KEYS: nodes carrying each ══")
    (doseq [k conditional-keys]
      (let [n (get key-hits k 0)]
        (println (format "%-22s %6d  (%.1f%% of nodes)"
                         (name k) n (* 100.0 (/ n (double total-nodes)))))))))
