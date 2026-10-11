(ns press.doc.desc
  "The figure description, judged exactly as the browser half judges it.

   `tools/press/press/fig/desc.py` is the authority. This namespace
   mirrors it over the description's canonical JSON tree (string keys,
   keyword values already turned into strings), so `press.doc` accepts what
   `press` accepts and refuses what it refuses — a description is never
   accepted here and refused there, or the reverse.

   Two layers, both needed:
     `shape-spec`  — a Malli schema built from the house constants: every
                     map closed, every enum (text styles, box looks, edge
                     looks) taken from `house.json`, integers only
     `cross-problems` — what a schema cannot say: ids unique across nodes,
                     groups and buses; exactly one of `rows` / `cells`;
                     `cells` with a `column-set` of one width; a child in
                     one group only, no group containing itself, no cycle;
                     a tap or an edge end names a node or group, never a
                     bus; no self-edge; edge ids unique (the default id is
                     `<from>-<to>`); every constraint's name, arity and argument
                     kinds; `order-y` / `hang` gaps ordered; the `view`
                     rules (refs name groups, a collapsed group is
                     collapsible and has a ref, `depth` collapses deeper
                     collapsible groups, which then need refs too).

   `problems` returns every problem it finds as `{:rule :message}` — the
   rule a stable keyword (`:desc/unknown-key`, `:desc/duplicate-id`, …),
   the message naming the place; empty means valid. A view is APPLIED as
   `desc.py` applies it (`press.doc.view`) and the rewritten tree validated
   again, so a synthetic id colliding with a declared one is refused here
   too; `depth` is counted on the detailed tree when there is a `detail`.
   A `collapse` naming one group twice is refused (`:desc/view-collapse-twice`).
   Every pattern (ids) matches the WHOLE string.

   A chart (`kind \"chart\"`) is NOT a `press fig` description: it is
   `{id kind chart data}` with `chart` one of bar / stacked / timeline and
   `data` an object whose inner shape the chart renderer owns."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [press.doc.spec :as spec]
            [press.doc.view :as view]))

(set! *warn-on-reflection* true)

(def id-re
  "Every id — figure, node, group, bus, edge — matches this WHOLE (use
   `re-matches`, never a search)."
  #"[A-Za-z0-9][A-Za-z0-9_-]*")

(def int-max
  "Every integer in a diagram description lies within ±this (desc.py's
   `INT_MAX`): a gap or a pad beyond any page is a mistake."
  100000)

(def tree-spec
  "A canonical JSON tree as parsed: string keys."
  [:map-of spec/any-string spec/anything])

(def problem
  "One reason a description is refused: a stable rule and a sentence."
  [:map {:closed true} [:rule :keyword] [:message spec/ne-string]])

(def ^:private problems-spec [:vector problem])

(defn- p
  "A problem."
  [rule & msg]
  {:rule rule :message (str/join msg)})

(m/=> p [:=> [:cat :keyword [:* spec/anything]] problem])

(def house-spec
  "The house constants `press.doc` reads from `tools/press/press/house.json`."
  [:map
   ["canvas-width" :int]
   ["page-budget" [:tuple :int :int]]
   ["text-floor" number?]
   ["font-family" spec/ne-string]
   ["text" [:map-of spec/ne-string [:tuple number? :int spec/ne-string]]]
   ["looks" [:map-of spec/ne-string [:vector [:maybe some?]]]]
   ["edge-looks" [:map-of spec/ne-string [:vector [:maybe some?]]]]])

(def constraints
  "Constraint name → argument kinds, as `desc.py` declares them:
   `:ids` a list of ≥ 2 distinct element ids, `:id` one element id, `:bus`
   a bus id, `:int` an integer ≥ 0 (`:int?` optional, trailing), `[:side
   …]` one of a fixed set, `:edges` a non-empty list of edge ids."
  {"order-x" [:ids :int]
   "order-y" [:ids :int :int?]
   "same-x" [:ids]
   "same-y" [:ids]
   "equal-width" [:ids]
   "equal-height" [:ids]
   "same-shape" [:ids]
   "span-x" [:id]
   "fill-x" [:ids :int]
   "hang" [:id :bus :int :int [:side #{"free" "above" "below" "left" "right"}]]
   "follow" [:id :id :bus :int]
   "beside" [:id :id :int]
   "within-y" [:id :id]
   "span-y" [:id :id :id]
   "near" [:id :edges [:side #{"n" "s" "e" "w"}] :int]})

;; ------------------------------------------------------------------ shape

(defn- enum-of
  "An `:enum` over the keys of house table `k`."
  [h k]
  (into [:enum] (sort (keys (get h k)))))

(m/=> enum-of [:=> [:cat house-spec spec/ne-string] vector?])

(def ^:private ne spec/ne-string)

(def ^:private eid (spec/whole id-re))

(def ^:private nat [:int {:min 0}])

(def ^:private any-val [:maybe some?])

(defn- opt
  "An optional entry `k` of schema `s`."
  [k s]
  [k {:optional true} s])

(m/=> opt [:=> [:cat spec/ne-string spec/anything] vector?])

(defn shape-spec
  "The diagram description's shape as a Malli schema over the JSON tree,
   with the text styles and looks of the house constants `h`."
  [h]
  (let [row [:tuple (enum-of h "text") ne]
        rows [:vector {:min 1} row]
        look (enum-of h "looks")
        edge-look (enum-of h "edge-looks")
        side [:enum "n" "s" "e" "w"]
        align [:enum "left" "center"]]
    [:map {:closed true}
     ["id" eid]
     ["kind" [:= "diagram"]]
     ["nodes" [:vector {:min 1}
               [:map {:closed true}
                ["id" eid] ["look" look] (opt "rows" rows) (opt "cells" rows)
                (opt "column-set" ne) (opt "pad" nat) (opt "align" align)]]]
     (opt "groups" [:vector [:map {:closed true}
                             ["id" eid] ["look" look] ["children" [:vector {:min 1} any-val]]
                             (opt "title" [:vector row]) (opt "align" align)
                             (opt "layout" [:enum "stack" "row" "free"]) (opt "collapsible" :boolean)]])
     (opt "buses" [:vector [:map {:closed true}
                            ["id" eid] ["orient" [:enum "h" "v"]]
                            ["taps" [:vector {:min 1} [:or :string [:map {:closed true} ["node" any-val]
                                                                    (opt "look" edge-look)]]]]
                            (opt "mode" [:= "spine"]) (opt "label" rows) (opt "look" edge-look)
                            (opt "span" [:enum "taps" "canvas"])]])
     (opt "edges" [:vector [:map {:closed true}
                            ["from" any-val] ["to" any-val] ["kind" [:enum "arrow" "tap" "skip"]]
                            (opt "id" eid) (opt "from-side" side) (opt "to-side" side)
                            (opt "look" edge-look) (opt "arrow" [:enum "end" "both" "none"])]])
     (opt "constraints" [:vector [:and [:vector {:min 1} any-val] [:fn #(string? (first %))]]])
     (opt "soft" [:map {:closed true} (opt "height" nat) (opt "edges" nat)])
     (opt "view" [:map {:closed true}
                  (opt "collapse" [:vector any-val]) (opt "depth" [:int {:min 1}])
                  (opt "detail" any-val) (opt "refs" [:map-of spec/any-string ne])])]))

(m/=> shape-spec [:=> [:cat house-spec] vector?])

(def chart-spec
  "A chart description's shape. `data` is an object whose inner schema the
   chart renderer (`press chart`) owns; numbers in it are integers and a
   fraction is written as a decimal string (\"19.5\")."
  [:map {:closed true}
   ["id" eid]
   ["kind" [:= "chart"]]
   ["chart" [:enum "bar" "stacked" "timeline"]]
   ["data" [:map-of spec/any-string any-val]]])

;; ----------------------------------------------------------- cross-checks

(defn- id-kinds
  "Every element id with its kind, and every repeat as a problem."
  [t]
  (reduce (fn [{:keys [ids] :as acc} [kind it]]
            (let [i (get it "id")]
              (if (contains? ids i)
                (update acc :problems conj (p :desc/duplicate-id kind " id " i " is already used by a " (ids i)))
                (assoc-in acc [:ids i] kind))))
          {:ids {} :problems []}
          (for [[kind k] [["node" "nodes"] ["group" "groups"] ["bus" "buses"]] it (get t k)] [kind it])))

(m/=> id-kinds [:=> [:cat tree-spec] [:map [:ids [:map-of spec/ne-string spec/ne-string]] [:problems problems-spec]]])

(defn- node-problems
  "rows XOR cells; a column-set exactly with cells; one width per set."
  [t]
  (let [nodes (get t "nodes")
        own (for [n nodes :let [w (str "node " (get n "id")) r? (contains? n "rows") c? (contains? n "cells")]
                  x [(when (= r? c?) (p :desc/rows-xor-cells w ": give exactly one of rows or cells"))
                     (when (and r? (contains? n "column-set")) (p :desc/column-set w ": column-set belongs with cells, not rows"))
                     (when (and c? (not (contains? n "column-set"))) (p :desc/column-set w ": cells need a column-set"))]
                  :when x]
              x)
        sets (group-by #(get % "column-set") (filter #(contains? % "cells") nodes))]
    (vec (concat own (for [[k xs] sets :when (> (count (set (map #(count (get % "cells")) xs))) 1)]
                       (p :desc/column-set "column-set " k ": every node in it needs the same number of cells"))))))

(m/=> node-problems [:=> [:cat tree-spec] problems-spec])

(defn- child-problem
  "Why group `gid` may not hold child `c`, or nil."
  [ids parent gid c]
  (cond (not (contains? ids c)) (p :desc/group-child "group " gid ": child " (pr-str c) " is not a node, group or bus")
        (= c gid) (p :desc/group-self "group " gid " cannot contain itself")
        (contains? parent c) (p :desc/group-parent "group " gid ": " c " is already a child of " (parent c))
        :else nil))

(m/=> child-problem [:=> [:cat [:map-of spec/ne-string spec/ne-string] [:map-of spec/anything spec/anything] spec/anything spec/anything]
                     [:maybe problem]])

(defn group-tree
  "The child → group map of `t` and every group problem: children exist, are
   not the group itself, sit in one group only; no cycle."
  [t ids]
  (let [{:keys [parent problems]}
        (reduce (fn [{:keys [parent] :as acc} [gid c]]
                  (if-let [x (child-problem ids parent gid c)]
                    (update acc :problems conj x)
                    (assoc-in acc [:parent c] gid)))
                {:parent {} :problems []}
                (for [g (get t "groups") c (get g "children")] [(get g "id") c]))
        cyc (for [g (get t "groups")
                  :let [gid (get g "id")]
                  :when (loop [x gid seen #{}] (cond (not (contains? parent x)) false
                                                     (seen x) true
                                                     :else (recur (parent x) (conj seen x))))]
              (p :desc/group-cycle "group " gid " sits inside itself"))]
    {:parent parent :problems (vec (concat problems cyc))}))

(m/=> group-tree [:=> [:cat tree-spec [:map-of spec/ne-string spec/ne-string]]
                  [:map [:parent [:map-of spec/anything spec/anything]] [:problems problems-spec]]])

(defn- element?
  "Does `x` name a node or a group (not a bus)?"
  [ids x]
  (contains? #{"node" "group"} (get ids x)))

(m/=> element? [:=> [:cat [:map-of spec/ne-string spec/ne-string] spec/anything] :boolean])

(defn- bus-problems
  "Each tap names a node or group, once."
  [t ids]
  (vec (for [b (get t "buses")
             :let [nodes (map #(if (string? %) % (get % "node")) (get b "taps"))]
             x (concat (for [n nodes :when (not (element? ids n))]
                         (p :desc/tap "bus " (get b "id") ": tap " (pr-str n) " is not a node or group"))
                       (when (not= (count nodes) (count (distinct nodes)))
                         [(p :desc/tap "bus " (get b "id") ": a node is tapped twice")]))]
         x)))

(m/=> bus-problems [:=> [:cat tree-spec [:map-of spec/ne-string spec/ne-string]] problems-spec])

(defn- edge-problems
  "Ends name nodes or groups, never a bus or the same box; ids unique."
  [t ids]
  (let [es (get t "edges")]
    (vec (concat
          (for [[i e] (map-indexed vector es)
                end ["from" "to"]
                :when (not (element? ids (get e end)))]
            (p :desc/edge-end "edges[" i "]." end ": " (pr-str (get e end)) " is not a node or group"))
          (for [[i e] (map-indexed vector es) :when (= (get e "from") (get e "to"))]
            (p :desc/self-edge "edges[" i "]: an edge from " (get e "from") " to itself"))
          (for [[k n] (frequencies (map view/edge-id es)) :when (> n 1)]
            (p :desc/edge-id "edge id " k " is used " n " times"))))))

(m/=> edge-problems [:=> [:cat tree-spec [:map-of spec/ne-string spec/ne-string]] problems-spec])

(defn- ids-problem
  "Why `a` is not a list of at least two distinct element ids, or nil."
  [a ids]
  (cond (not (and (vector? a) (>= (count a) 2))) "expected a list of at least two ids"
        (some #(not (contains? ids %)) a) (str (pr-str (first (remove #(contains? ids %) a))) " is not a node, group or bus")
        (not= (count a) (count (distinct a))) "an id appears twice"
        :else nil))

(m/=> ids-problem [:=> [:cat spec/anything [:map-of spec/ne-string spec/ne-string]] [:maybe spec/ne-string]])

(def ^:private arg-checks
  "Argument kind → `(fn [a env] problem-or-nil)`."
  {:ids (fn [a {:keys [ids]}] (ids-problem a ids))
   :id (fn [a {:keys [ids]}] (when-not (contains? ids a) (str (pr-str a) " is not a node, group or bus")))
   :bus (fn [a {:keys [buses]}] (when-not (contains? buses a) (str (pr-str a) " is not a bus")))
   :int (fn [a _] (when-not (and (int? a) (>= (long a) 0)) (str "expected an integer >= 0, got " (pr-str a))))
   :edges (fn [a {:keys [eids]}] (when-not (and (vector? a) (seq a) (every? #(contains? eids %) a))
                                   "expected a non-empty list of edge ids"))})

(defn- arg-problem
  "Why argument `a` is not of kind `k`, or nil."
  [k a env]
  (if (vector? k)
    (when-not (contains? (second k) a) (str (pr-str a) " is not one of " (sort (second k))))
    ((get arg-checks (if (= k :int?) :int k)) a env)))

(m/=> arg-problem [:=> [:cat spec/anything spec/anything [:map-of :keyword spec/anything]] [:maybe spec/ne-string]])

(defn- constraint-problems
  "One constraint: a known name, its arity, each argument's kind, ordered
   gaps."
  [i c env]
  (let [nm (first c) kinds (get constraints nm) args (vec (rest c))
        w (str "constraints[" i "] " nm)
        need (count (remove #{:int?} kinds))]
    (cond (nil? kinds) [(p :desc/constraint-name "constraints[" i "]: unknown constraint " (pr-str nm))]
          (not (<= need (count args) (count kinds)))
          [(p :desc/constraint-arity w ": expected " need ".." (count kinds) " arguments, got " (count args))]
          :else (let [bad (vec (keep identity (map (fn [k a] (some->> (arg-problem k a env) (p :desc/constraint-arg w ": "))) kinds args)))
                      gap (fn [lo hi] (when (and (empty? bad) (< (long (args hi)) (long (args lo))))
                                        [(p :desc/gap-order w ": the largest gap is below the smallest")]))]
                  (into bad (concat (when (and (= nm "order-y") (= 3 (count args))) (gap 1 2))
                                    (when (= nm "hang") (gap 2 3))))))))

(m/=> constraint-problems [:=> [:cat :int vector? [:map-of :keyword spec/anything]] problems-spec])

(defn- base-problems
  "Every cross-check of a shape-valid tree, the view aside; with the
   element ids and the child → group map they were computed from."
  [t]
  (let [{:keys [ids] dup :problems} (id-kinds t)
        {:keys [parent] gp :problems} (group-tree t ids)
        env {:ids ids :buses (set (map #(get % "id") (get t "buses")))
             :eids (set (map view/edge-id (get t "edges")))}]
    {:ids ids :parent parent
     :problems (vec (concat dup (node-problems t) gp (bus-problems t ids) (edge-problems t ids)
                            (mapcat (fn [[i c]] (constraint-problems i c env)) (map-indexed vector (get t "constraints")))))}))

(m/=> base-problems [:=> [:cat tree-spec] [:map [:problems problems-spec]]])

;; ------------------------------------------------------------------ view

(defn- depth-of
  "How deep group `g` sits (a group directly in the figure is 1)."
  [parent g]
  (loop [k 1 x (parent g)] (if (nil? x) k (recur (inc k) (parent x)))))

(m/=> depth-of [:=> [:cat [:map-of spec/anything spec/anything] spec/anything] :int])

(defn- view-static
  "The view's own rules before anything is rewritten: refs name groups, the
   detail names a group with a ref, no group listed twice in `collapse`."
  [v groups refs]
  (let [d (get v "detail")
        c (get v "collapse" [])]
    (vec (concat
          (for [k (keys refs) :when (not (contains? groups k))] (p :desc/view-refs "view.refs: " k " is not a group"))
          (when (contains? v "detail")
            (cond (not (contains? groups d)) [(p :desc/view-detail "view.detail: " (pr-str d) " is not a group")]
                  (not (contains? refs d)) [(p :desc/view-detail "view.refs: the detail of " d " needs a ref")]
                  :else nil))
          (for [[g n] (frequencies c) :when (> n 1)]
            (p :desc/view-collapse-twice "view.collapse: " (pr-str g) " is listed " n " times"))))))

(m/=> view-static [:=> [:cat tree-spec [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]] problems-spec])

(defn- collapse-problems
  "Every group to collapse is a group of the description, collapsible, with
   a ref."
  [gids groups refs]
  (vec (for [gid gids
             :let [why (cond (not (contains? groups gid)) "is not a group"
                             (not (get-in groups [gid "collapsible"])) "is not marked collapsible"
                             (not (contains? refs gid)) "needs a ref (the text naming its detail figure)"
                             :else nil)]
             :when why]
         (p :desc/view-collapse "view.collapse: " (pr-str gid) " " why))))

(m/=> collapse-problems [:=> [:cat [:sequential spec/anything] [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]]
                         problems-spec])

(declare tree-problems)

(defn- view-tree
  "The tree `desc.py` goes on with after the view's own rules: the detail
   cut out of `base` when there is one, else `base`."
  [base v refs ids parent]
  (if (contains? v "detail") (view/detail base (get v "detail") refs ids parent) base))

(m/=> view-tree [:=> [:cat tree-spec tree-spec [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]
                      [:map-of spec/anything spec/anything]]
                 tree-spec])

(defn- collapse-set
  "The groups to collapse: those named, then (with `depth`) every
   collapsible group of `tree` nested deeper — depth counted on `tree`
   itself, the detailed tree when there is a detail (desc.py's `cur`)."
  [v tree parent]
  (let [cur-parent (if (contains? v "detail") (:parent (group-tree tree (:ids (id-kinds tree)))) parent)
        deep (when-let [n (get v "depth")]
               (for [g (get tree "groups")
                     :let [gid (get g "id")]
                     :when (and (> (depth-of cur-parent gid) (long n)) (get g "collapsible"))]
                 gid))]
    (vec (distinct (concat (get v "collapse" []) deep)))))

(m/=> collapse-set [:=> [:cat tree-spec tree-spec [:map-of spec/anything spec/anything]] [:vector spec/anything]])

(defn- collapse-and-validate
  "The collapse set's own rules, then the tree with it collapsed, validated."
  [tree collapse groups refs h]
  (let [cp (collapse-problems collapse groups refs)]
    (cond (seq cp) cp
          (seq collapse) (tree-problems (view/collapse tree collapse refs) h)
          :else (tree-problems tree h))))

(m/=> collapse-and-validate [:=> [:cat tree-spec [:vector spec/anything] [:map-of spec/anything spec/anything]
                                  [:map-of spec/anything spec/anything] house-spec]
                             problems-spec])

(defn- apply-view
  "The problems of `t`'s view, as `desc.py`'s `view.apply` raises them: its
   own rules, then (detail with depth) the detailed tree validated, then
   the collapse set, then the rewritten tree validated again.
   `ids`/`parent` belong to the full description."
  [t h ids parent]
  (let [v (get t "view")
        groups (into {} (map (juxt #(get % "id") identity)) (get t "groups"))
        refs (get v "refs" {})
        own (view-static v groups refs)
        tree (when (empty? own) (view-tree (view/with-edge-ids (dissoc t "view")) v refs ids parent))
        cur (when (and tree (contains? v "detail") (contains? v "depth")) (tree-problems tree h))]
    (cond (seq own) own
          (seq cur) cur
          :else (collapse-and-validate tree (collapse-set v tree parent) groups refs h))))

(m/=> apply-view [:=> [:cat tree-spec house-spec [:map-of spec/anything spec/anything] [:map-of spec/anything spec/anything]]
                  problems-spec])

(defn- shape-problems
  "One problem per place `t` breaks the shape schema `s`, by error type."
  [s t]
  (vec (for [[in es] (group-by :in (:errors (m/explain s t)))
             :let [e (first es)
                   rule (case (:type e)
                          :malli.core/extra-key :desc/unknown-key
                          :malli.core/missing-key :desc/missing-key
                          :desc/bad-value)]]
         (p rule (pr-str in) ": " (me/error-message e) " — got " (let [x (pr-str (:value e))] (subs x 0 (min 80 (count x))))))))

(m/=> shape-problems [:=> [:cat vector? spec/anything] problems-spec])

(defn- value-problems
  "desc.py's read-time refusals over the whole tree: a float, an integer
   beyond ±`int-max`, a string holding a lone surrogate."
  [t]
  (vec (for [x (tree-seq coll? seq t)
             :let [x (when-not (map-entry? x) x)
                   prob (cond (or (float? x) (decimal? x)) (p :desc/float x " is a float; a description carries integers only")
                              (and (integer? x) (> (abs (bigint x)) int-max))
                              (p :desc/int-range x " is out of range (integers lie within ±" int-max ")")
                              (and (string? x) (not (.canEncode (.newEncoder java.nio.charset.StandardCharsets/UTF_8) ^String x)))
                              (p :desc/surrogate (pr-str x) " holds a lone surrogate; not UTF-8 text")
                              :else nil)]
             :when prob]
         prob)))

(m/=> value-problems [:=> [:cat spec/anything] problems-spec])

(defn- tree-problems
  "Every problem of diagram tree `t`: its shape, then its cross-checks, then
   its view applied."
  [t h]
  (let [s (shape-spec h)]
    (if-not (m/validate s t)
      (shape-problems s t)
      (let [{:keys [ids parent problems]} (base-problems t)]
        (cond (seq problems) problems
              (contains? t "view") (apply-view t h ids parent)
              :else [])))))

(m/=> tree-problems [:=> [:cat tree-spec house-spec] problems-spec])

(defn problems
  "Every reason the description tree `t` (canonical JSON, parsed) is
   refused, given the house constants `h`; empty when valid. Each is
   `{:rule :message}`. A float, an out-of-range integer or a lone surrogate
   anywhere — in a chart too — refuses first; a chart is then checked
   against `chart-spec`, anything else is a diagram."
  [t h]
  (let [vp (value-problems t)]
    (cond (seq vp) vp
          (= "chart" (get t "kind")) (if (m/validate chart-spec t) [] (shape-problems chart-spec t))
          :else (tree-problems t h))))

(m/=> problems [:=> [:cat tree-spec house-spec] problems-spec])
