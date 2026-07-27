(ns devcards.deadzone
  "The ordered DISABLED pointer dead-zone rule required by
   UI-QUALITY-CONTRACTS §2.2.

   ARM 1, the armed rule, reports two unrelated pointer-taking nodes whose
   reachable boxes overlap when the node reached FIRST by
   `lv_indev_search_obj` is disabled and the node beneath it is enabled.
   The disabled winner claims the pointer, receives no LV_EVENT_PRESSED, and
   prevents the enabled control underneath from hearing anything. Pixels and
   the event log are both unchanged, so neither shipped oracle can see it.

   This is deliberately an ordered specialization of `devcards.overlap`.
   It reuses overlap's pointer reachability, descent-gate clipping, proxy
   declarations, and labels; it does not copy that arithmetic. Ordering comes
   from the dump's declared child paths, not observed paint: LVGL searches
   children from the largest index down and returns the first hit.

   ARM 2, `containment-findings`, measures disabled clickable descendants over
   enabled clickable ancestors for the census only. It is NOT part of
   `producer`. The current dump cannot distinguish an ancestor with press
   intent from harness/layout scaffolding, and arming that mechanically true
   but intent-empty class would block on ordinary containers."
  (:require [devcards.classify :as classify]
            [devcards.geometry :as geometry]
            [devcards.invariants :as invariants]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(defn winner
  "Return [first-hit other] for two unrelated annotated entries.

   `lv_indev_search_obj` visits children in reverse index order, so the path
   with the larger child index at the first divergence wins. An
   ancestor-related pair has no divergence and is a caller error."
  [a b]
  (let [pa (:path a)
        pb (:path b)
        n (min (count pa) (count pb))
        i (first (remove #(= (nth pa %) (nth pb %)) (range n)))]
    (when (nil? i)
      (throw (ex-info (str "deadzone/winner got an ancestor-related pair — "
                           "exclude it with invariants/related? before "
                           "asking which unrelated node wins")
                      {:a-path pa :b-path pb})))
    (if (> (long (nth pa i)) (long (nth pb i))) [a b] [b a])))

(defn- disabled?
  [entry]
  (boolean (get-in entry [:node :disabled])))

(defn candidates
  "Pointer-reachable, interactive annotated entries, each carrying
   `::reach [status box]`. The population and arithmetic are overlap's."
  [path->node classes nodes]
  (into []
        (comp (filter (fn [entry]
                        (and (overlap/pointer-reachable? entry)
                             (:interactive?
                              (classify/classify
                               classes
                               (:type (:node entry)))))))
              (map #(assoc % ::reach (overlap/reachability path->node %))))
        nodes))

(defn- prepared
  [{:keys [card-id nodes classes]}]
  (classify/validate-table! classes)
  (let [path->node (into {} (map (juxt :path :node)) nodes)
        judged (candidates path->node classes nodes)
        unclassified
        (classify/unclassified-findings card-id classes nodes :deadzone)
        unmeasurable
        (into []
              (comp
               (filter #(= :unmeasurable (first (::reach %))))
               (map (fn [entry]
                      (let [[_ why] (::reach entry)]
                        {:card card-id
                         :invariant :unmeasurable-node
                         :node (overlap/label-of entry)
                         :detail (str "interactive node could not be judged "
                                      "for a disabled dead zone: " why
                                      " — it is NOT thereby clear of "
                                      "everything else")}))))
              judged)]
    {:coverage-findings (into (vec unclassified) unmeasurable)
     :path->node path->node
     :pairable (filterv #(= :box (first (::reach %))) judged)
     :reach-of (fn [entry] (second (::reach entry)))}))

(defn sibling-findings
  "ARM 1 findings for one card.

   Reads :card-id, :nodes, :classes and :thresholds {:gap-px n}. Coverage
   failures are findings, never silent skips. At the default gap 0 the
   reachable boxes must share a pixel; touching alone does not fire."
  [{:keys [card-id thresholds] :as ctx}]
  (let [{:keys [coverage-findings path->node pairable reach-of]}
        (prepared ctx)
        gap-px (:gap-px thresholds 0)]
    (into coverage-findings
          (for [[i a] (map-indexed vector pairable)
                b (subvec pairable (inc i))
                :when (not (invariants/related? a b))
                :let [ra (reach-of a)
                      rb (reach-of b)]
                :when (< (geometry/separation ra rb) gap-px)
                :let [[top bottom] (winner a b)]
                :when (and (disabled? top) (not (disabled? bottom)))]
            {:card card-id
             :invariant :disabled-dead-zone
             :node (str (overlap/label-of top)
                        " OVER "
                        (overlap/label-of bottom))
             :detail
             (str "DISABLED " (overlap/label-of top)
                  " is reached first by lv_indev_search_obj and covers "
                  "ENABLED " (overlap/label-of bottom)
                  " over "
                  (geometry/describe
                   (or (geometry/intersection ra rb) ra))
                  " — the press is claimed by the disabled node, no "
                  "LV_EVENT_PRESSED is sent, and the enabled control "
                  "beneath never fires. No pixel differs and no event is "
                  "emitted, so no other oracle can see it"
                  (when (overlap/designed-proxy-stack? path->node a b)
                    (str " (both sit in one host_proxy and one is a "
                         "renderer-built affordance — designed stacking, "
                         "but a disabled participant makes it a dead zone "
                         "inside the design)")))}))))

(defn containment-findings
  "Census-only ARM 2 findings for one card.

   A disabled clickable descendant always wins over an enabled clickable
   ancestor because LVGL searches children before the node itself. These
   findings deliberately carry whether the nearest hit-testable ancestor is
   harness scaffolding, but do not infer press intent or exempt anything.
   This function is not the armed producer."
  [{:keys [card-id] :as ctx}]
  (let [{:keys [pairable reach-of]} (prepared ctx)
        path->pairable (into {} (map (juxt :path identity)) pairable)
        nearest-pairable-ancestor
        (fn [path]
          (first
           (keep #(get path->pairable (subvec path 0 %))
                 (range (dec (count path)) -1 -1))))
        scaffolding? (fn [ancestor]
                       (contains? #{[] [0]} (:path ancestor)))]
    (into []
          (for [child pairable
                :let [path (:path child)]
                :when (and (seq path) (disabled? child))
                :let [ancestor (nearest-pairable-ancestor path)]
                :when (and ancestor (not (disabled? ancestor)))
                :let [hit (geometry/intersection
                           (reach-of child)
                           (reach-of ancestor))]
                :when hit]
            {:card card-id
             :invariant :disabled-covers-ancestor
             :node (str (overlap/label-of child)
                        " INSIDE "
                        (overlap/label-of ancestor))
             :scaffolding-ancestor? (scaffolding? ancestor)
             :ancestor-path (:path ancestor)
             :detail
             (str "DISABLED " (overlap/label-of child)
                  " is a CLICKABLE descendant of ENABLED "
                  (overlap/label-of ancestor)
                  " (the nearest hit-testable ancestor), and "
                  "lv_indev_search_obj tests children before the node "
                  "itself, so the child always wins over "
                  (geometry/describe hit)
                  " — the press is absorbed and dropped"
                  (if (scaffolding? ancestor)
                    (str ". The ancestor is the harness root/screen, which "
                         "takes no press by intent; mechanism says hazard "
                         "while the dump cannot express that intent")
                    ". The ancestor is a real screen element"))}))))

(defn census-findings
  "Both measured arms for the read-only census. ARM 1 includes the coverage
   findings; ARM 2 contributes only containment findings."
  [ctx]
  (into (sibling-findings ctx) (containment-findings ctx)))

(def producer
  "The registry entry for ARM 1 only. ARM 2 remains reachable through
  `containment-findings`/`census-findings` but cannot block a lane."
  {:id :deadzone
   :fn sibling-findings
   :requires #{:nodes :classes}
   :thresholds
   {:gap-px
    {:pred nat-int?
     :default 0
     :doc (str "minimum clear pixels between the disabled winner and the "
               "enabled node beneath it; 0 = they must share a pixel")}}})
