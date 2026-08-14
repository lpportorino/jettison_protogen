(ns paint-census
  "Empirical probe: what does the tree dump's PAINT EXTENT actually say about
   this corpus, and what would a spacing rule built on it report?

   Exists because every number a spacing rule needs is a property of THIS
   corpus, THIS theme and THIS renderer, and three of them were assumed
   before this probe existed:

   1. HOW MANY nodes paint outside their own box at all. `dump_obj` emits a
      paint key only where the extent differs from `coords`, so the answer
      also says how much of the tree the distinction costs nothing for.

   2. WHETHER the exact/bound split is worth having. If nothing resolves
      exactly the split is theatre; if nothing needs the bound the bound is
      dead weight. Both populations are printed.

   3. WHAT SEPARATION a real spacing rule would see. The interesting figure
      is not the paint separation alone but the DROP from the node
      separation to it — the pixels a widget took out of clearance the
      layout had allocated to something else. That drop is what
      `devcards.spacing` fires on, so the distribution here is what its
      threshold has to be chosen against.

   Read-only: renders, dumps, counts, prints. Writes nothing, gates nothing,
   and is not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:paint-census"
  (:require [clojure.data.json :as json]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.geometry :as geometry]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.spacing :as spacing]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render+dump!
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark 1})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- overhang
  "Per-side pixels `box` extends beyond `coords`, as [left top right bottom]."
  [coords box]
  (let [[cx1 cy1 cx2 cy2] coords
        [bx1 by1 bx2 by2] box]
    [(- cx1 bx1) (- cy1 by1) (- bx2 cx2) (- by2 cy2)]))

(defn- paint-census
  [dumps]
  (reduce
   (fn [acc {:keys [id tree]}]
     (reduce (fn [a node]
               (let [k (cond (:paint_box node) :exact
                             (:paint_bound node) :bound
                             :else :none)]
                 (cond-> (update-in a [k (:type node)] (fnil inc 0))
                   (not= k :none)
                   (update :detail conj
                           {:card id
                            :type (:type node)
                            :kind k
                            :coords (:coords node)
                            :paint (or (:paint_box node) (:paint_bound node))
                            :over (overhang (:coords node)
                                            (or (:paint_box node)
                                                (:paint_bound node)))}))))
             acc
             (walk tree)))
   {:exact {} :bound {} :none {} :detail []}
   dumps))

(defn- pair-drops
  "Every judged pair's [node-sep paint-sep] on one card, using the SAME
   candidate selection and exclusions the spacing producer applies — so the
   distribution printed here is the one the rule actually sees, not a
   looser sweep that would flatter or frighten for no reason."
  [{:keys [id tree]}]
  (let [nodes (invariants/annotate-tree tree)
        cands (spacing/candidates nodes)]
    (for [[i a] (map-indexed vector cands)
          b (subvec cands (inc i))
          :when (not (invariants/related? a b))
          :let [na (spacing/node-box a)
                nb (spacing/node-box b)
                pa (spacing/paint-outer a)
                pb (spacing/paint-outer b)]
          :when (and na nb pa pb)
          :let [nsep (geometry/separation na nb)
                psep (geometry/separation pa pb)]
          :when (not= nsep psep)]
      {:card id
       :pair (str (spacing/label-of a) " vs " (spacing/label-of b))
       :node-sep nsep
       :paint-sep psep
       :drop (- nsep psep)})))

(defn -main
  [& _]
  (let [spec (fixtures/load-spec)
        atomic (fixtures/build-all spec)
        comp-built (composition/build-all (composition/load-inventory))
        built (concat atomic comp-built)
        _ (println (format "rendering %d cards (%d atomic + %d composition)…"
                           (count built) (count atomic) (count comp-built)))
        dumps (mapv (fn [{:keys [id] ^bytes pb :bytes}]
                      {:id (str id) :tree (render+dump! pb)})
                    built)
        {:keys [exact bound none detail]} (paint-census dumps)
        total (fn [m] (reduce + 0 (vals m)))]

    (println "\n══ PAINT-EXTENT CENSUS ══")
    (println (format "nodes with paint == coords (neither key) : %d" (total none)))
    (println (format "nodes with an EXACT paint_box            : %d" (total exact)))
    (println (format "nodes with only a paint_bound            : %d" (total bound)))
    (doseq [[label m] [["EXACT" exact] ["BOUND" bound]]
            :when (seq m)]
      (println (format "\n  %s by class:" label))
      (doseq [[cls n] (sort-by key m)]
        (println (format "    %-24s %5d" cls n))))

    (println "\n══ PER-SIDE OVERHANG (every node carrying a paint key) ══")
    (println (format "%-34s %-7s %-22s %s" "card" "kind" "type" "[l t r b]"))
    (doseq [d (sort-by (juxt :card :type) detail)]
      (println (format "%-34s %-7s %-22s %s"
                       (:card d) (name (:kind d)) (:type d) (pr-str (:over d)))))

    (println "\n══ WHAT devcards.spacing REPORTS, PER THRESHOLD ══")
    (println "(the arming evidence for gap-px — a threshold is chosen against")
    (println " this table, never against a number written in prose)")
    (doseq [gap [1 2 3 4 6 8]]
      (let [fs (into [] (mapcat (fn [{:keys [id tree]}]
                                  (spacing/findings
                                   {:card-id id
                                    :nodes (invariants/annotate-tree tree)
                                    :thresholds {:gap-px gap}})))
                     dumps)
            by-inv (frequencies (map :invariant fs))]
        (println (format "  gap-px %d : %-4d finding(s) over %-3d card(s)  %s"
                         gap (count fs) (count (distinct (map :card fs)))
                         (pr-str by-inv)))))

    (println "\n══ EVERY FINDING AT THE DECLARED DEFAULT ══")
    (doseq [f (into [] (mapcat (fn [{:keys [id tree]}]
                                 (spacing/findings
                                  {:card-id id
                                   :nodes (invariants/annotate-tree tree)
                                   :thresholds
                                   {:gap-px (:default (:gap-px (:thresholds
                                                                spacing/producer)))}})))
                    dumps)]
      (println (format "  [%s] %s | %s" (name (:invariant f)) (:card f) (:node f)))
      (println (format "        %s" (:detail f))))

    (println "\n══ PAIRS WHOSE PAINT SEPARATION DIFFERS FROM THEIR NODE SEPARATION ══")
    (let [drops (into [] (mapcat pair-drops) dumps)]
      (println (format "%d such pair(s) over %d card(s)"
                       (count drops) (count (distinct (map :card drops)))))
      (println "\n  paint-sep distribution among them:")
      (doseq [[psep n] (sort-by key (frequencies (map :paint-sep drops)))]
        (println (format "    paint-sep %4d : %d pair(s)" psep n)))
      (println "\n  the pairs, worst paint-sep first:")
      (doseq [d (take 40 (sort-by :paint-sep drops))]
        (println (format "    %-30s node-sep %4d -> paint-sep %4d (drop %d)  %s"
                         (:card d) (:node-sep d) (:paint-sep d) (:drop d)
                         (:pair d)))))))
