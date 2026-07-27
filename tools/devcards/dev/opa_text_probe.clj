(ns opa-text-probe
  "Empirical probe: WHERE does a whole-widget fade land on the real corpus,
   and does it land on glyphs?

   Exists because `devcards.opa` was armed on a count, and a count in a
   docstring goes stale the first time the corpus, the theme or the renderer
   moves. This is how that count is re-derived rather than trusted — the same
   role `class-census` plays for the overlap lane.

   It prints three things, in the order the arming argument needs them:

   1. EVERY node carrying `:opa`, grouped by class, with the distinct opa
      values and how many are `disabled`. This is the population the ban is
      about, and the whole safety argument is that its intersection with the
      glyph-bearing classes is empty.
   2. Which of those the clause calls GLYPHS / TEXT-FREE / UNKNOWN. A row
      landing in UNKNOWN is not noise — it is a class nobody has decided,
      and it BLOCKS, so it should be seen here first.
   3. What `devcards.opa/findings` actually reports, per invariant.

   BOTH THEMES ARE RENDERED, because the fade is a theme decision: the
   asgard family attaches the disabled styles and vanilla attaches none, so
   a dark-only run would judge half the population.

   Read-only: renders, counts, prints. Writes nothing, gates nothing, and is
   not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:opa-text-probe"
  (:require [clojure.data.json :as json]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.opa :as opa]))

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

(defn- bucket
  "Which of the clause's three answers this class gets, WITHOUT re-deriving
   the logic: the sets are read straight off `devcards.opa`."
  [cls]
  (cond
    (= "lv_label" cls) "label (decided from :text)"
    (contains? opa/glyph-classes cls) "GLYPHS"
    (contains? opa/text-free-classes cls) "text-free"
    (contains? opa/ambiguous-classes cls) "ambiguous"
    :else "UNKNOWN"))

(defn -main
  [& _]
  (let [spec (fixtures/load-spec)
        atomic (fixtures/build-all spec)
        comp-built (composition/build-all (composition/load-inventory))
        built (concat atomic comp-built)
        _ (println (format "rendering %d cards x 2 themes…" (count built)))
        dumps (into []
                    (for [{:keys [id] ^bytes pb :bytes} built
                          dark [true false]]
                      {:id (str id "|" (if dark "dark" "light"))
                       :tree (render+dump! pb dark)}))
        all-nodes (for [{:keys [id tree]} dumps, node (walk tree)]
                    {:card id :node node})
        faded (filter #(contains? (:node %) :opa) all-nodes)]

    (println (format "\n%d node(s) of %d carry :opa, over %d renders"
                     (count faded) (count all-nodes) (count dumps)))

    (println "\n══ THE FADED POPULATION ══")
    (println (format "%-22s %5s %-12s %6s  %s"
                     "class" "n" "opa" "disab" "clause says"))
    (doseq [[cls es] (sort-by key (group-by (comp :type :node) faded))]
      (println (format "%-22s %5d %-12s %6d  %s"
                       cls (count es)
                       (pr-str (vec (sort (distinct (map #(:opa (:node %)) es)))))
                       (count (filter #(:disabled (:node %)) es))
                       (bucket cls))))

    (println "\n══ WHAT THE CLAUSE REPORTS ══")
    (let [fs (into []
                   (mapcat (fn [{:keys [id tree]}]
                             (opa/findings {:card-id id
                                            :nodes (invariants/annotate-tree tree)
                                            :thresholds {}})))
                   dumps)]
      (println (format "%d finding(s)" (count fs)))
      (doseq [[inv group] (sort-by key (group-by :invariant fs))]
        (println (format "  %-28s %d" (str inv) (count group)))
        (doseq [f (take 8 group)]
          (println (format "    %-52s %s" (:card f) (:node f))))))

    (println "\n══ TOTALITY vs the emitted class set ══")
    (let [emitted (set lvgl-classes/emitted-classes)
          covered opa/emitted-class-coverage]
      (println (format "emitted=%d covered-by-clause=%d" (count emitted)
                       (count covered)))
      (println (format "emitted but UNDECIDED: %s"
                       (pr-str (vec (sort (remove covered emitted))))))
      (println (format "decided but not emitted: %s"
                       (pr-str (vec (sort (remove emitted covered)))))))))
