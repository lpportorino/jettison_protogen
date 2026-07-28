(ns deadzone-census
  "Read-only census for the ordered DISABLED pointer dead-zone rule.

   Renders the full atomic and composition corpus at family 0 / dark / bp 0
   through the pinned wasm, reports the searched population, and runs four
   planted cases built through `fixtures/build-authored-card`.

   Run from tools/devcards in the pinned container:
     clojure -M:bindings:deadzone-census"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.classify :as classify]
            [devcards.composition :as composition]
            [devcards.deadzone :as deadzone]
            [devcards.fixtures :as fixtures]
            [devcards.geometry :as geometry]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})
(def ^:private classes lanes/overlap-classes)
(def ^:private thresholds
  {:gap-px (parse-long
            (or (System/getProperty "deadzone.gap-px") "0"))})

(defn- render+dump!
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try
      (host/render-card! h {:pb pb :bp 0 :dark 1})
      (json/read-str (host/dump-tree! h) :key-fn keyword)
      (finally
        (host/close! h)))))

(defn- card-population
  [{:keys [id tree]}]
  (let [nodes (invariants/annotate-tree tree)
        path->node (into {} (map (juxt :path :node)) nodes)
        cands (deadzone/candidates path->node classes nodes)
        boxed (filterv #(= :box (first (::deadzone/reach %))) cands)
        reach-of (fn [entry] (second (::deadzone/reach entry)))
        pairs (for [[i a] (map-indexed vector boxed)
                    b (subvec boxed (inc i))
                    :when (not (invariants/related? a b))
                    :when (< (geometry/separation
                              (reach-of a)
                              (reach-of b))
                             (:gap-px thresholds))]
                [a b])
        disabled? #(boolean (get-in % [:node :disabled]))]
    {:id id
     :nodes (count nodes)
     :disabled-nodes
     (count (filter #(get-in % [:node :disabled]) nodes))
     :hidden-nodes
     (count (filter #(or (get-in % [:node :hidden])
                         (:hidden-under? %))
                    nodes))
     :unclassified
     (count
      (classify/unclassified-findings id classes nodes :deadzone))
     :pointer-taking (count boxed)
     :disabled-pointer-taking (count (filter disabled? boxed))
     :unreachable
     (count
      (filter #(= :unreachable (first (::deadzone/reach %))) cands))
     :unmeasurable
     (count
      (filter #(= :unmeasurable (first (::deadzone/reach %))) cands))
     :unreachable-types
     (frequencies
      (map (comp :type :node)
           (filter #(= :unreachable (first (::deadzone/reach %)))
                   cands)))
     :overlapping-pairs (count pairs)
     :overlapping-pairs-with-disabled
     (count
      (filter (fn [[a b]] (or (disabled? a) (disabled? b))) pairs))
     :both-disabled
     (count
      (filter (fn [[a b]] (and (disabled? a) (disabled? b))) pairs))
     :disabled-underneath
     (count
      (filter
       (fn [[a b]]
         (let [[top bottom] (deadzone/winner a b)]
           (and (disabled? bottom) (not (disabled? top)))))
       pairs))
     :pairs
     (vec
      (for [[a b] pairs
            :let [[top bottom] (deadzone/winner a b)]]
        {:top (overlap/label-of top)
         :bottom (overlap/label-of bottom)
         :top-disabled? (disabled? top)
         :bottom-disabled? (disabled? bottom)
         :proxy-stack?
         (overlap/designed-proxy-stack? path->node a b)
         :rect
         (geometry/intersection (reach-of top) (reach-of bottom))}))
     :findings
     (deadzone/census-findings
      {:card-id id
       :nodes nodes
       :classes classes
       :thresholds thresholds})}))

(def ^:private total-keys
  [:nodes :disabled-nodes :hidden-nodes :unclassified :pointer-taking
   :disabled-pointer-taking :unreachable :unmeasurable
   :overlapping-pairs :overlapping-pairs-with-disabled :both-disabled
   :disabled-underneath])

(defn- totals
  [pops]
  (reduce
   (fn [acc population]
     (reduce #(update %1 %2 + (get population %2 0)) acc total-keys))
   (zipmap total-keys (repeat 0))
   pops))

(def ^:private disabled-bits 512)

(defn- stacked-buttons
  [top-states bottom-states]
  {:type :WIDGET_OBJ
   :props {:w 400 :h 200}
   :x 100
   :y 100
   :children
   [(cond-> {:type :WIDGET_BUTTON
             :x 0
             :y 0
             :props {:w 200 :h 80}
             :children [{:type :WIDGET_LABEL :text "under"}]}
      (pos? (long bottom-states))
      (assoc :states-bits bottom-states))
    (cond-> {:type :WIDGET_BUTTON
             :x 160
             :y 0
             :props {:w 200 :h 80}
             :children [{:type :WIDGET_LABEL :text "over"}]}
      (pos? (long top-states))
      (assoc :states-bits top-states))]})

(defn- nested-button
  [child-states parent-states]
  (cond-> {:type :WIDGET_OBJ
           :props {:w 400 :h 200}
           :x 100
           :y 100
           :children
           [(cond-> {:type :WIDGET_BUTTON
                     :x 40
                     :y 40
                     :props {:w 200 :h 80}
                     :children
                     [{:type :WIDGET_LABEL :text "inner"}]}
              (pos? (long child-states))
              (assoc :states-bits child-states))]}
    (pos? (long parent-states))
    (assoc :states-bits parent-states)))

(def ^:private planted
  [{:id "planted/disabled-over-enabled"
    :expect {:sibling 1 :containment-real 1 :containment-scaffold 0}
    :node (stacked-buttons disabled-bits 0)}
   {:id "planted/enabled-over-disabled"
    :expect {:sibling 0 :containment-real 1 :containment-scaffold 0}
    :node (stacked-buttons 0 disabled-bits)}
   {:id "planted/disabled-child-in-enabled-parent"
    :expect {:sibling 0 :containment-real 1 :containment-scaffold 0}
    :node (nested-button disabled-bits 0)}
   ;; ENABLED child inside a DISABLED parent. Its own parent is disabled, so it
   ;; is not the nearest ENABLED hit-testable ancestor; the walk continues up.
   ;; This expected a SCAFFOLD-attributed finding while the corpus wrapper was
   ;; clickable — and the wrapper is now cleared, because it emits nothing and
   ;; had no business in the pointer path. With no enabled ancestor above the
   ;; disabled parent, there is nothing being covered and NO finding is the
   ;; right answer. The case keeps its place as the NEGATIVE arm: it proves the
   ;; rule does not fire on an enabled child merely for being nested.
   ;;
   ;; ⚠️ NOTHING NOW EXERCISES `:scaffolding-ancestor? true`. That marker is
   ;; still produced by `devcards.deadzone`, and no planted case can reach it
   ;; while every corpus container is out of the pointer path. Recorded as an
   ;; UNEXERCISED path rather than deleted, because deletion would also remove
   ;; the diagnosis a consumer needs the day their own scaffolding IS clickable
   ;; — which is LVGL's default, so it is the state they start from.
   {:id "planted/enabled-child-in-disabled-parent"
    :expect {:sibling 0 :containment-real 0 :containment-scaffold 0}
    :node (nested-button 0 disabled-bits)}])

(defn- planted-row
  [{:keys [id node expect]}]
  (let [pb (fixtures/build-authored-card canvas {:id id :node node})
        population (card-population {:id id :tree (render+dump! pb)})
        fs (:findings population)
        containment
        (filter #(= :disabled-covers-ancestor (:invariant %)) fs)
        got
        {:sibling
         (count
          (filter #(= :disabled-dead-zone (:invariant %)) fs))
         :containment-real
         (count (remove :scaffolding-ancestor? containment))
         :containment-scaffold
         (count (filter :scaffolding-ancestor? containment))}]
    {:id id
     :expect expect
     :got got
     :ok? (= expect got)
     :population population
     :details
     (mapv :detail
           (filter
            #(#{:disabled-dead-zone :disabled-covers-ancestor}
              (:invariant %))
            fs))}))

(defn- run-planted!
  []
  (println "\n══ PLANTED-CASE PROOF ══")
  (let [rows (mapv planted-row planted)]
    (doseq [{:keys [id expect got ok? population details]} rows]
      (println (format "\n  %s" id))
      (println
       (format
        (str "    population  : %d nodes, %d pointer-taking, "
             "%d overlapping pair(s), %d disabled node(s)")
        (:nodes population)
        (:pointer-taking population)
        (:overlapping-pairs population)
        (:disabled-nodes population)))
      (println
       (format
        (str "    expected    : sibling=%d containment(real)=%d "
             "containment(scaffold)=%d")
        (:sibling expect)
        (:containment-real expect)
        (:containment-scaffold expect)))
      (println
       (format
        (str "    observed    : sibling=%d containment(real)=%d "
             "containment(scaffold)=%d -> %s")
        (:sibling got)
        (:containment-real got)
        (:containment-scaffold got)
        (if ok? "PASS" "FAIL")))
      (doseq [detail details]
        (println (format "    detail      : %s" detail))))
    (let [ok? (every? :ok? rows)]
      (println
       (format "\n  planted-case verdict: %s"
               (if ok? "PASS" "FAIL")))
      ok?)))

(defn- print-population!
  [pops totals-]
  (println "\n══ POPULATION SEARCHED ══")
  (doseq [[label k]
          [["cards rendered + dumped" ::cards]
           ["dump nodes total" :nodes]
           ["nodes carrying LV_STATE_DISABLED" :disabled-nodes]
           ["nodes HIDDEN or under a hidden ancestor" :hidden-nodes]
           ["pointer-taking nodes (judged)" :pointer-taking]
           ["  of which DISABLED" :disabled-pointer-taking]
           ["pointer-taking but UNREACHABLE (excluded)" :unreachable]
           ["pointer-taking but UNMEASURABLE (finding)" :unmeasurable]
           ["overlapping pointer-taking pairs" :overlapping-pairs]
           ["  of which a participant is DISABLED"
            :overlapping-pairs-with-disabled]
           ["  of which BOTH disabled" :both-disabled]
           ["  of which DISABLED is UNDERNEATH (benign)"
            :disabled-underneath]
           ["unclassified nodes (findings)" :unclassified]]]
    (println
     (format "%-50s %8d"
             label
             (if (= ::cards k) (count pops) (get totals- k)))))
  (println "\n══ EXCLUDED BY POSITIVE UNREACHABILITY ══")
  (doseq [[node-type n]
          (sort-by key
                   (apply merge-with
                          +
                          {}
                          (map :unreachable-types pops)))]
    (println (format "  %-24s %3d node(s)" node-type n)))
  (println "\n══ EVERY OVERLAPPING POINTER-TAKING PAIR ══")
  (doseq [population (sort-by :id pops)
          pair (:pairs population)]
    (println
     (format
      (str "  %-34s %-20s OVER %-20s top-dis=%-5s "
           "bot-dis=%-5s proxy-stack=%-5s %s")
      (:id population)
      (:top pair)
      (:bottom pair)
      (:top-disabled? pair)
      (:bottom-disabled? pair)
      (:proxy-stack? pair)
      (geometry/describe (:rect pair))))))

(defn -main
  [& [mode]]
  (if (= "--canary-only" mode)
    (System/exit (if (run-planted!) 0 1))
    (let [spec (fixtures/load-spec)
          atomic (fixtures/build-all spec)
          comp-inventory (composition/load-inventory)
          compositions (composition/build-all comp-inventory)
          built (concat atomic compositions)
          _ (println
             (format
              (str "rendering %d cards (%d atomic + %d composition) "
                   "at family 0 / dark / bp 0…")
              (count built)
              (count atomic)
              (count compositions)))
          pops
          (mapv
           (fn [{:keys [id] ^bytes pb :bytes}]
             (card-population
              {:id (str id) :tree (render+dump! pb)}))
           built)
          totals- (totals pops)
          findings (mapcat :findings pops)
          sibling
          (filter #(= :disabled-dead-zone (:invariant %)) findings)
          containment
          (filter #(= :disabled-covers-ancestor (:invariant %)) findings)
          real (remove :scaffolding-ancestor? containment)
          scaffold (filter :scaffolding-ancestor? containment)]
      (print-population! pops totals-)
      (println
       "\n══ ARM 1: DISABLED SIBLING ON TOP OF ENABLED NODE ══")
      (if (empty? sibling)
        (println "  0 findings.")
        (doseq [finding sibling]
          (println
           (format "  %s\n    %s\n    %s"
                   (:card finding)
                   (:node finding)
                   (:detail finding)))))
      (println
       "\n══ ARM 2: DISABLED CHILD OVER ENABLED ANCESTOR (CENSUS ONLY) ══")
      (println
       (format "  %d finding(s): %d real + %d harness root/screen"
               (count containment)
               (count real)
               (count scaffold)))
      (doseq [[depth fs]
              (sort-by key
                       (group-by (comp count :ancestor-path) containment))]
        (println
         (format "    depth %d  %3d finding(s)  e.g. %s"
                 depth
                 (count fs)
                 (:card (first fs)))))
      (println (format "\n%s" (str/join "" (repeat 72 "─"))))
      (println
       (format
        (str "CENSUS: %d sibling dead zone(s); containment arm: "
             "%d real + %d scaffolding")
        (count sibling)
        (count real)
        (count scaffold)))
      (println
       (format
        (str "        over %d cards / %d nodes / %d pointer-taking / "
             "%d disabled across %d cards / %d overlapping pairs")
        (count pops)
        (:nodes totals-)
        (:pointer-taking totals-)
        (:disabled-nodes totals-)
        (count (filter #(pos? (long (:disabled-nodes %))) pops))
        (:overlapping-pairs totals-)))
      (let [ok? (run-planted!)]
        (println
         (format "\ncensus can find every planted case: %s"
                 (if ok? "YES" "NO")))
        (System/exit (if ok? 0 1))))))
