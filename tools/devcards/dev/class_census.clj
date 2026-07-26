(ns class-census
  "Empirical probe: what LVGL classes does the renderer ACTUALLY emit as
   dump `type`, and what does the overlap rule say about the real corpus?

   Exists because two claims in the finding-producer work were reasoned
   rather than measured, and reasoning is not evidence:

   1. The classification table's key set. `devcards.classify` makes an
      undeclared type a FINDING, so a starter table shipped from this repo
      has to cover the classes that really appear — including the ones no
      corpus spec lists, because the renderer builds them internally
      (roller labels, dropdown lists). A hand-guessed
      list would be wrong in exactly the places that matter.

   2. The snapped-carousel question. `devcards.overlap` deliberately does
      NOT exempt a tabview page the carousel has snapped out of view, on
      the argument that over-exempting is the worse failure. That argument
      has never met a real lv_tabview. If snapped pages do collide here,
      the exemption is owed a proof-carrying entry — or the rule is owed a
      fix. Either way it is decided on output, not on the argument.

   Read-only: renders, dumps, counts, prints. Writes nothing, gates
   nothing, and is not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:class-census"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.classify :as classify]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render+dump!
  "One hermetic render of `pb`, returning the parsed dump tree."
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark 1})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- census
  "{class -> {:nodes n :cards #{id}}} plus the per-node property tallies the
   overlap rule depends on."
  [dumps]
  (reduce (fn [acc {:keys [id tree]}]
            (reduce (fn [a node]
                      (-> a
                          (update-in [:classes (:type node) :nodes] (fnil inc 0))
                          (update-in [:classes (:type node) :cards] (fnil conj #{}) id)
                          (cond->
                           (:disabled node) (update :disabled (fnil inc 0))
                           (:hidden node) (update :hidden (fnil inc 0))
                           (nil? (:coords node)) (update :no-coords (fnil inc 0)))))
                    acc
                    (walk tree)))
          {:classes {} :disabled 0 :hidden 0 :no-coords 0}
          dumps))

(def ^:private probe-table
  "A DELIBERATELY TOTAL table for the probe only: every class classified
   :interactive? true. This is not the starter table — it is the maximally
   noisy setting, chosen so the probe reports every geometric collision the
   rule COULD ever report. A quiet run under this table is a strong result
   (nothing collides at all); a loud one localises exactly which classes
   and cards need the real table's judgement."
  {:default {:interactive? true :role :interactive}
   :types {}})

(defn- overlap-report
  [dumps]
  (into []
        (mapcat (fn [{:keys [id tree]}]
                  (overlap/findings {:card-id id
                                     :nodes (invariants/annotate-tree tree)
                                     :classes probe-table
                                     :thresholds {:gap-px 0}})))
        dumps))

(defn -main
  [& _]
  (let [spec (fixtures/load-spec)
        atomic (fixtures/build-all spec)
        comp-inv (composition/load-inventory)
        comp-built (composition/build-all comp-inv)
        built (concat atomic comp-built)
        _ (println (format "rendering %d cards (%d atomic + %d composition)…"
                           (count built) (count atomic) (count comp-built)))
        dumps (mapv (fn [{:keys [id] ^bytes pb :bytes}]
                      {:id (str id) :tree (render+dump! pb)})
                    built)
        {:keys [classes disabled hidden no-coords]} (census dumps)]

    (println "\n══ CLASS CENSUS ══")
    (println (format "%-28s %8s  %s" "class" "nodes" "example card"))
    (doseq [[cls {:keys [nodes cards]}] (sort-by key classes)]
      (println (format "%-28s %8d  %s" cls nodes (first (sort cards)))))
    (println (format "\n%d distinct classes over %d cards"
                     (count classes) (count dumps)))
    (println (format "nodes carrying :disabled = %d, :hidden = %d, no :coords = %d"
                     disabled hidden no-coords))

    (println "\n══ EDN key set (paste target for the starter table) ══")
    (println (pr-str (vec (sort (keys classes)))))

    (println "\n══ OVERLAP PROBE (every class forced :interactive?) ══")
    (let [fs (overlap-report dumps)
          overlaps (filterv #(= :overlap (:invariant %)) fs)
          by-card (group-by :card overlaps)]
      (println (format "%d overlap finding(s) across %d card(s)"
                       (count overlaps) (count by-card)))
      (doseq [[card cfs] (sort-by key by-card)]
        (println (format "\n  %s  (%d)" card (count cfs)))
        (doseq [f (take 6 cfs)]
          (println (format "    %s" (:node f)))))
      (let [tabview (filter #(str/includes? (str (:card %)) "tabview") overlaps)]
        (println (format "\n  of which lv_tabview cards: %d" (count tabview)))))

    (println "\n══ classify/validate-table! on the probe table ══")
    (println (if (classify/validate-table! probe-table) "table valid" "?"))))
