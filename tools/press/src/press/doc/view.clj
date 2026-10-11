(ns press.doc.view
  "A figure view applied the way `tools/press/press/fig/view.py` applies
   it, so `press.doc.desc` can re-validate the tree a view PRODUCES.

   A view rewrites the description before it is drawn: `detail` keeps one
   group, its members, the buses that tap it, a note node `<g>-part-of`
   and one marker node `ref-<id>` per box an edge leaves for; `collapse`
   (and `depth`) turns each named group into a single box. `desc.py`
   validates the rewritten tree again, so a synthetic id that collides
   with a declared one, or a rule the rewrite breaks, refuses the
   description. These functions reproduce the rewrite on the canonical
   JSON tree (string keys) — the texts of the synthetic boxes too, though
   only their ids and shapes can decide validity.

   The input tree must already be valid without its view (the caller has
   checked), with edge ids filled in (`with-edge-ids`)."
  (:require [malli.core :as m]
            [press.doc.spec :as spec]))

(set! *warn-on-reflection* true)

(def ^:private tree [:map-of spec/any-string spec/anything])

(def ^:private orders #{"order-x" "order-y"})

(def ^:private lists #{"same-x" "same-y" "equal-width" "equal-height" "same-shape" "fill-x"})

(defn edge-id
  "An edge's id: its own, or `<from>-<to>` (desc.py's `default_edge_id`)."
  [e]
  (or (get e "id") (str (get e "from") "-" (get e "to"))))

(m/=> edge-id [:=> [:cat tree] spec/ne-string])

(defn with-edge-ids
  "`t` with every edge carrying its id explicitly, as `desc.py`'s validated
   tree does before a view is applied."
  [t]
  (if (contains? t "edges")
    (update t "edges" (fn [es] (mapv #(assoc % "id" (edge-id %)) es)))
    t))

(m/=> with-edge-ids [:=> [:cat tree] tree])

(defn descendants-of
  "Every member of group `gid` in `t`, depth first (desc.py's `descendants`)."
  [t gid]
  (let [groups (into {} (map (juxt #(get % "id") identity)) (get t "groups"))]
    (letfn [(walk [g] (mapcat (fn [c] (cons c (when (contains? groups c) (walk c))))
                              (get-in groups [g "children"])))]
      (vec (walk gid)))))

(m/=> descendants-of [:=> [:cat tree spec/anything] [:vector spec/anything]])

(defn- tap-node
  "The node a tap names."
  [tp]
  (if (string? tp) tp (get tp "node")))

(m/=> tap-node [:=> [:cat spec/anything] spec/anything])

(defn- label
  "The text a reader knows box `x` by: its first row or cell, a group's
   first title line, else its id (view.py's `_label`)."
  [t x]
  (or (some #(when (= x (get % "id")) (second (first (or (get % "rows") (get % "cells"))))) (get t "nodes"))
      (some #(when (and (= x (get % "id")) (seq (get % "title"))) (second (first (get % "title")))) (get t "groups"))
      x))

(m/=> label [:=> [:cat tree spec/anything] spec/anything])

(defn- where
  "The ref of the figure that draws box `x`: its innermost group with a
   ref other than `gid`, else `gid`'s own ref."
  [parent refs gid x]
  (loop [p (parent x)]
    (cond (nil? p) (refs gid)
          (and (contains? refs p) (not= p gid)) (refs p)
          :else (recur (parent p)))))

(m/=> where [:=> [:cat [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything] spec/anything spec/anything]
             spec/anything])

(defn- detail-edges
  "The edges of a detail: kept whole inside, cut to a marker when one end
   leaves the group. Returns `[edges markers]`."
  [t members parent refs gid]
  (reduce (fn [[es mk] e]
            (let [ins [(contains? members (get e "from")) (contains? members (get e "to"))]]
              (cond (every? true? ins) [(conj es e) mk]
                    (some true? ins)
                    (let [out-end (if (first ins) "to" "from")
                          other (get e out-end)
                          mid (str "ref-" other)]
                      [(conj es (-> e (assoc out-end mid) (dissoc (if (= out-end "from") "from-side" "to-side"))))
                       (if (contains? mk mid) mk
                           (assoc mk mid {"id" mid "look" "note"
                                          "rows" [["m" (str (label t other) " · " (where parent refs gid other))]]}))])
                    :else [es mk])))
          [[] (array-map)] (get t "edges")))

(m/=> detail-edges [:=> [:cat tree [:set spec/anything] [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything] spec/anything]
                    [:tuple vector? [:map-of spec/anything spec/anything]]])

(defn- constraint-ids
  "Every string argument of constraint `c` and every string inside a list
   argument."
  [c]
  (concat (filter string? (rest c)) (mapcat #(when (vector? %) %) (rest c))))

(m/=> constraint-ids [:=> [:cat vector?] [:sequential spec/anything]])

(defn detail
  "`t` reduced to group `gid` (view.py's `_detail`); `ids` are the element
   ids of the full description, `parent` its child → group map."
  [t gid refs ids parent]
  (let [members (conj (set (descendants-of t gid)) gid)
        note (str gid "-part-of")
        buses (vec (keep (fn [b] (if (contains? members (get b "id")) b
                                     (let [taps (filterv #(contains? members (tap-node %)) (get b "taps"))]
                                       (when (seq taps) (assoc b "taps" taps "span" "taps")))))
                         (get t "buses")))
        [es markers] (detail-edges t members parent refs gid)
        keep-ids (into (conj members note) (concat (map #(get % "id") buses) (keys markers)))
        eids (set (map edge-id es))
        kept? (fn [c] (and (not= "span-x" (first c))
                           (if (= "near" (first c))
                             (and (contains? keep-ids (second c)) (every? eids (nth c 2)))
                             (every? keep-ids (filter #(contains? ids %) (constraint-ids c))))))]
    (assoc t
           "nodes" (-> (filterv #(contains? members (get % "id")) (get t "nodes"))
                       (conj {"id" note "look" "note" "rows" [["m" (str "part of: " (refs gid))]]})
                       (into (vals markers)))
           "groups" (filterv #(contains? members (get % "id")) (get t "groups"))
           "buses" buses
           "edges" es
           "constraints" (conj (filterv kept? (get t "constraints")) ["order-y" [gid note] 4]))))

(m/=> detail [:=> [:cat tree spec/anything [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]
                   [:map-of spec/anything spec/anything]]
              tree])

(defn- collapse-node
  "The single box a collapsed group becomes."
  [t g refs]
  (let [nodes (set (map #(get % "id") (get t "nodes")))
        n (count (filter nodes (descendants-of t (get g "id"))))]
    {"id" (get g "id") "look" (get g "look")
     "rows" (conj (vec (get g "title")) ["m" (str n " parts · detail: " (refs (get g "id")))])}))

(m/=> collapse-node [:=> [:cat tree tree [:map-of spec/anything spec/anything]] tree])

(defn- collapse-one
  "`t` with group `gid` collapsed; `rep` records member → group."
  [[t rep] gid refs]
  (let [g (first (filter #(= gid (get % "id")) (get t "groups")))
        members (set (descendants-of t gid))
        box (collapse-node t g refs)]
    [(assoc t
            "nodes" (conj (filterv #(not (contains? members (get % "id"))) (get t "nodes")) box)
            "groups" (filterv #(not (contains? (conj members gid) (get % "id"))) (get t "groups"))
            "buses" (filterv #(not (contains? members (get % "id"))) (get t "buses")))
     (into rep (map #(vector % gid)) members)]))

(m/=> collapse-one [:=> [:cat [:tuple tree [:map-of spec/anything spec/anything]] spec/anything [:map-of spec/anything spec/anything]]
                    [:tuple tree [:map-of spec/anything spec/anything]]])

(defn- remap-edges
  "Edges with ends moved onto their collapsed boxes: swallowed when both
   ends land on one box, merged when a pair repeats. Returns `[edges emap]`."
  [es m]
  (let [[out _ emap] (reduce (fn [[out kept emap] e]
                               (let [a (m (get e "from")) z (m (get e "to")) eid (edge-id e)]
                                 (if (or (= a z) (contains? kept [a z]))
                                   [out kept (assoc emap eid nil)]
                                   [(conj out (assoc e "from" a "to" z "id" eid)) (conj kept [a z]) (assoc emap eid eid)])))
                             [[] #{} {}] es)]
    [out emap]))

(m/=> remap-edges [:=> [:cat [:sequential tree] ifn?] [:tuple vector? [:map-of spec/anything spec/anything]]])

(defn- remap-order
  "An order constraint with its boxes moved onto collapsed boxes; nil when
   fewer than two distinct boxes are left."
  [[nm ids & more] m]
  (let [boxes (vec (distinct (map m ids)))]
    (when (>= (count boxes) 2) (into [nm boxes] more))))

(m/=> remap-order [:=> [:cat vector? ifn?] [:maybe vector?]])

(defn- remap-near
  "A `near` constraint with its edge ids remapped; nil when its box or any
   of its edges vanished in the collapse."
  [[nm box es & more] rep emap]
  (when-not (or (contains? rep box) (some #(nil? (get emap %)) es))
    (into [nm box (mapv emap es)] more)))

(m/=> remap-near [:=> [:cat vector? [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]] [:maybe vector?]])

(defn- remap-constraint
  "Constraint `c` after a collapse (`rep` member → box), or nil when it is
   dropped (view.py's rules: orders move, lists of a hidden box drop, near
   follows its edges, anything else naming a hidden box drops)."
  [c rep emap]
  (let [nm (first c)]
    (cond (orders nm) (remap-order c #(get rep % %))
          (lists nm) (when-not (some #(contains? rep %) (second c)) c)
          (= nm "near") (remap-near c rep emap)
          :else (when-not (some #(and (string? %) (contains? rep %)) (rest c)) c))))

(m/=> remap-constraint [:=> [:cat vector? [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]]
                        [:maybe vector?]])

(defn collapse
  "`t` with the groups `gids` collapsed to single boxes (view.py's
   `_collapse`); a group already inside another collapsed one is skipped."
  [t gids refs]
  (let [present (set (map #(get % "id") (get t "groups")))
        top (filter (fn [g] (and (present g) (not-any? #(and (not= % g) (some #{g} (descendants-of t %))) gids))) gids)
        [t' rep] (reduce #(collapse-one %1 %2 refs) [t {}] top)
        m #(get rep % %)
        buses (mapv (fn [b] (assoc b "taps" (vec (vals (reduce (fn [acc tp]
                                                                 (let [tp (if (string? tp) {"node" tp} tp)
                                                                       k (m (get tp "node"))]
                                                                   (if (contains? acc k) acc (assoc acc k (assoc tp "node" k)))))
                                                               (array-map) (get b "taps"))))))
                    (get t' "buses"))
        [es emap] (remap-edges (get t' "edges") m)]
    (assoc t' "buses" buses "edges" es
           "constraints" (vec (keep #(remap-constraint % rep emap) (get t' "constraints"))))))

(m/=> collapse [:=> [:cat tree [:sequential spec/anything] [:map-of spec/anything spec/anything]] tree])
