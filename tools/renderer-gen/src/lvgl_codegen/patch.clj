(ns lvgl-codegen.patch
  "Tree-patch differ + pure patch applier (docs/lvgl-factory/
   10-TREE-PATCH-DESIGN.md). Diffs two proto-IR EDN screens (the
   emit-screen output, uid-bearing after identity assignment) into
   explicit node-level ops — the wire ships resolved, dumb ops; the C
   reconciler applies them mechanically. ALL matching and morph-vs-replace
   policy lives HERE, as data (the mutability tables below).

   Op vocabulary (wire PatchOpKind): UPDATE_PROPS (morph in place from
   the node's morphable prop set, children EMPTY), REPLACE_NODE (teardown
   target subtree, build the op's full subtree in its slot), INSERT_NODE
   (build under parent_uid at index), REMOVE_NODE, MOVE_NODE (same-parent
   reorder — a cross-parent move changes the node's identity path, hence
   its uid, so it always diffs as REMOVE+INSERT by construction).

   Determinism: per parent the emission order is REMOVEs by descending
   base index, then MOVEs, then INSERTs by ascending target index, then
   the kept children's recursive ops; ops apply strictly sequentially
   against the live tree."
  (:require [clojure.set :as set]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; ═══════════════════════════════════════════════════════════════════
;; Mutability policy — AS DATA (verified end-to-end by the morph-parity
;; dual oracle: a wrong :in-place produces a tree/pixel divergence; a
;; wrong :replace produces a tier-3 state-loss failure).
;; ═══════════════════════════════════════════════════════════════════
(def replace-on-change-keys
  "Node keys whose CHANGE forces REPLACE_NODE in v1: observer/event-cb
   detach has no renderer code path (bindings/event/visibility/
   checked_when/pending_when), grid templates are write-once pool slots, and
   bare/in_tab_bar are create-time structure.

   `:pending_when` is listed on the SAME ground as `:checked_when`, which it
   copies: `apply_compare_binding` attaches either a native state bind or a
   `lv_subject_add_observer_obj` observer, and nothing detaches either.

   OBSERVED ASYMMETRY, NOT RESOLVED HERE. `:enabled_when` and `:color_when`
   attach observers by the same mechanism and appear in NEITHER this set nor
   `morph-invariant-keys`, so on their face an UPDATE_PROPS morph carries them
   and re-enters the attach path on an already-live object. Whether that
   actually double-attaches was not driven to a verdict, and settling it needs
   a morph-parity card rather than a set edit — so this note records the
   question instead of pre-empting its answer. A new binding is added here
   deliberately rather than by following the two most recent siblings."
  #{:type :bare :in_tab_bar :event :bindings :bind_formats :visibility :checked_when
    :pending_when :grid_col_dsc :grid_row_dsc})

(def replace-always-types
  "Widget types whose OWN change (any key) forces REPLACE_NODE: their
   live LVGL subtree does not mirror the IR (tabview zips children into
   pages/bar; host-proxy appends renderer-owned affordances and carries
   registry state; target-overlay appends one renderer-owned object per
   TargetBox, so re-applying its props in place stacks a second set of boxes
   over the first), so in-place morphing of the node itself is off the
   table. Descendants inside them are still patchable by uid."
  #{:WIDGET_TABVIEW :WIDGET_HOST_PROXY :WIDGET_TARGET_OVERLAY})

(def replace-on-change-arms
  "*_props arms with no morph path in the renderer: scale allocates
   write-once pool slots (text_src, section styles); buttonmatrix/table
   are renderer-deferred; line allocates a write-once points slot that
   LVGL holds BY POINTER and never copies.

   Honest limit of the line rationale: apply_line_points runs on the
   UPDATE path as well as the REPLACE path, so the slot cost does NOT
   discriminate between them — both consume a fresh slot and neither
   reclaims the old one (the pool resets per LOAD only). Line stays
   listed here because nothing exercises in-place line morphing: the
   wasmtime morph-parity suite carries no line coverage, so replace-only
   is the arm with an oracle behind it. Moving line out of this set is a
   real option, and it earns a morph-parity card first."
  #{:scale_props :buttonmatrix_props :table_props :line_props})

(def morph-invariant-keys
  "Keys STRIPPED from UPDATE_PROPS payloads: a change in any of them
   forces REPLACE (replace-on-change-keys), so under UPDATE they are
   unchanged by definition — physically persistent on the live object
   (styles attached, observers wired, children present). The pure
   oracle restores them from the base node; the C reconciler's restore
   is that persistence."
  #{:children :event :bindings :bind_formats :visibility :checked_when :pending_when
    :grid_col_dsc :grid_row_dsc :bare :in_tab_bar})

(def carry-when-changed-keys
  "Keys carried in an UPDATE payload ONLY when changed (stripped when
   unchanged so the reconciler does not redo allocation/state work):
   :style_groups — carried ⇒ the reconciler removes the node's recorded
   styles and decodes the new groups into fresh pool slots (old slots
   are reclaimed only by full reload — accounted monotonic growth);
   :states — the LV_STATE_CHECKED carrier; re-adding an unchanged
   authored CHECKED would overwrite a live user toggle."
  #{:style_groups :states})

(def value-bearing-paths
  "THE FORM-STATE POLICY (authored value vs runtime user state), per
   value-bearing widget type. Each path is stripped from an UPDATE
   payload when base == target — the wire then carries the proto
   default, which the reconciler's morph-mode guards skip, so LIVE USER
   STATE (typed text, slider/arc/spinbox value, dropdown/roller
   selection, checked toggles) survives. When the AUTHORED value itself
   changed between base and target the path is carried and the
   reconciler applies it — an authored change always wins over user
   state (and an authored regression to the proto default is a one-way
   door ⇒ REPLACE, same outcome: the new authored value shows)."
  {:WIDGET_TEXTAREA #{[:text]}
   :WIDGET_SLIDER #{[:slider_props :value]}
   :WIDGET_ARC #{[:arc_props :value]}
   :WIDGET_SPINBOX #{[:spinbox_props :value]}
   :WIDGET_SWITCH #{[:switch_props :checked]}
   :WIDGET_CHECKBOX #{[:checkbox_props :checked]}
   :WIDGET_DROPDOWN #{[:dropdown_props :selected]}
   :WIDGET_ROLLER #{[:roller_props :selected]}})

(def guarded-arm-fields
  "Per *_props arm: fields the renderer applies through a '0/absent =
   keep the LVGL default' guard. A non-default → default transition is
   inexpressible by re-applying props in place (F7's one-way doors) ⇒
   REPLACE. Fields NOT listed are applied unconditionally (absolute
   setters), so any transition is morphable."
  ;; :value ON SLIDER, ARC AND SPINBOX — each mirrors a real
  ;; `if (!morph_in_progress || p->value != 0)` in renderer.c
  ;; (slider, arc and spinbox in apply_widget_props). Omitting them meant an
  ;; ordinary update that regressed an authored value to exactly 0 was classified
  ;; UPDATE_PROPS, the renderer then SKIPPED applying the 0, and the live widget kept
  ;; a stale value with no error anywhere.
  ;;
  ;; BAR IS DELIBERATELY DIFFERENT and must not be "made consistent": its
  ;; `lv_bar_set_value` is UNCONDITIONAL, so a bar value is morphable at every
  ;; transition. Only its `:start_value` is guarded, and only that is listed. The
  ;; asymmetry is the C source's, not an oversight here — check the guard before
  ;; adding a field.
  {:slider_props #{:mode :value}
   :bar_props #{:mode :start_value}
   :arc_props #{:mode :rotation :value}
   :label_props #{:long_mode}
   :dropdown_props #{:options :selected :direction}
   :roller_props #{:options :selected :visible_row_count :mode}
   :textarea_props #{:placeholder :max_length}
   :spinbox_props #{:step :digit_count :separator_position :value :max_value}
   :spinner_props #{:spin_time :arc_length}
   :led_props #{:color :brightness}
   :image_props #{:src :has_pivot :rotation}
   :switch_props #{:checked}
   :checkbox_props #{:checked}})

(def ^:private zero-values
  "Proto3 default values per carrier shape, including the 0-numbered
   member of every enum reachable from a morphable arm — the values the
   renderer's '!= 0' guards treat as 'keep the LVGL default'."
  #{0 "" false :BAR_MODE_NORMAL :ARC_MODE_NORMAL :ROLLER_MODE_NORMAL :LABEL_LONG_MODE_WRAP
    :DIR_NONE :CHART_TYPE_NONE :CHART_AXIS_PRIMARY_Y :FLEX_FLOW_NONE :FLEX_ALIGN_START})

(def ^:private props-arm-keys
  "Every widget_props oneof arm key in the IR."
  #{:obj_props :button_props :label_props :slider_props :image_props :arc_props :bar_props
    :switch_props :checkbox_props :dropdown_props :roller_props :textarea_props
    :spinbox_props :spinner_props :led_props :line_props :scale_props :buttonmatrix_props
    :table_props :tabview_props :chart_props :host_proxy_props :target_overlay_props})

;; ═══════════════════════════════════════════════════════════════════
;; File-local schemas
;; ═══════════════════════════════════════════════════════════════════
(def ^:private ir-node
  "A proto-IR widget node (emit-screen output shape) — open beyond the
   discriminating keys; the proto-schema backstop owns the full shape."
  [:map [:type :keyword] [:uid {:optional true} int?]])

(def ^:private screen-ir
  "A proto-IR screen: {:root <node> :subjects [...]}."
  [:map [:root ir-node]])

(def patch-op-kind
  "Wire PatchOpKind member spellings."
  [:enum :PATCH_OP_UPDATE_PROPS :PATCH_OP_REPLACE_NODE :PATCH_OP_INSERT_NODE
   :PATCH_OP_REMOVE_NODE :PATCH_OP_MOVE_NODE])

(def patch-op
  "One tree-patch op (wire TreePatchOp shape, EDN-side)."
  [:map [:kind patch-op-kind] [:target_uid {:optional true} int?]
   [:parent_uid {:optional true} int?] [:index {:optional true} nat-int?]
   [:node {:optional true} ir-node]])

(def diff-result
  "diff-screen verdict: :patch (ops to apply), :unchanged (no ops), or
   :full-reload (subject declarations changed — not patchable in v1)."
  [:map [:result [:enum :patch :unchanged :full-reload]]
   [:ops {:optional true} [:vector patch-op]] [:reason {:optional true} :keyword]])

;; ═══════════════════════════════════════════════════════════════════
;; Per-node morph-vs-replace classification
;; ═══════════════════════════════════════════════════════════════════
(defn- zero-value?
  "True when v is absent (nil) or a proto3-default carrier value the
   renderer's guards treat as 'keep'."
  [v]
  (or (nil? v) (contains? zero-values v)))

(defn- arm-one-way-door?
  "True when a changed (base-arm → target-arm) transition regresses a
   guarded field to its default — inexpressible in place."
  [arm-key base-arm target-arm]
  (let [guarded (get guarded-arm-fields arm-key #{})]
    (boolean (some (fn [field]
                     (and (not (zero-value? (get base-arm field)))
                          (zero-value? (get target-arm field))
                          (not= (get base-arm field) (get target-arm field))))
                   guarded))))

(defn- layout-one-way-door?
  "Exact mirror of the renderer's layout guards: flow regressing to
   NONE, or all three placements regressing to default while base had a
   non-default one."
  [base-layout target-layout]
  (boolean (or (and (not (zero-value? (:flow base-layout)))
                    (zero-value? (:flow target-layout)))
               (and (some #(not (zero-value? (get base-layout %)))
                          [:main_place :cross_place :track_place])
                    (every? #(zero-value? (get target-layout %))
                            [:main_place :cross_place :track_place])))))

(defn- chart-values-only-change?
  "True when two differing chart arms differ ONLY in per-series :values
   with identical series count — the one chart change the reconciler can
   morph (lv_chart_set_series_value_by_id over the live series)."
  [base-arm target-arm]
  (let [strip #(mapv (fn [s] (dissoc s :values)) (:series %))]
    (and (= (dissoc base-arm :series) (dissoc target-arm :series))
         (= (count (:series base-arm)) (count (:series target-arm)))
         (pos? (count (:series base-arm)))
         (= (strip base-arm) (strip target-arm)))))

(defn- bitmask-shrank?
  "True when target drops a bit base had — flag/state bitmasks are
   applied add-only, so a dropped bit is a one-way door."
  [base-bits target-bits]
  (not= 0 (bit-and (long (or base-bits 0)) (bit-not (long (or target-bits 0))))))

(defn- node-arm-key
  "The widget_props arm key present on a node (nil when none)."
  [node]
  (some props-arm-keys (keys node)))

(defn- needs-replace?
  "Morph-vs-replace verdict for a matched (same-uid) node pair whose
   shallow props differ. nil = morphable (UPDATE_PROPS); a keyword names
   the replace reason (kept in the differ for diagnosability)."
  [base target]
  (let [b (dissoc base :children)
        t (dissoc target :children)
        changed (into #{}
                      (filter #(not= (get b %) (get t %)))
                      (set/union (set (keys b)) (set (keys t))))
        base-arm-key (node-arm-key b)]
    (cond (contains? replace-always-types (:type t)) :replace-always-type
          (some replace-on-change-keys changed) :replace-on-change-key
          ;; An arm appeared/disappeared or switched — create-time structure.
          (and (contains? changed (or base-arm-key (node-arm-key t)))
               (not= base-arm-key (node-arm-key t)))
          :props-arm-switched
          (and base-arm-key
               (contains? changed base-arm-key)
               (contains? replace-on-change-arms base-arm-key))
          :no-morph-path-for-arm
          (and (= base-arm-key :chart_props)
               (contains? changed :chart_props)
               (not (chart-values-only-change? (get b :chart_props) (get t :chart_props))))
          :chart-structural-change
          (and base-arm-key
               (contains? changed base-arm-key)
               (not (#{:chart_props} base-arm-key))
               (arm-one-way-door? base-arm-key (get b base-arm-key) (get t base-arm-key)))
          :arm-one-way-door
          ;; x/y regressing to (0,0): the renderer re-applies position only when x!=0
          ;; OR y!=0 (finalize_widget guard), so an in-place UPDATE leaves the widget at
          ;; its stale coord — REPLACE instead. Gate on x OR y changing (an x-only gate
          ;; missed a y-only regression: the widget kept its stale y).
          (and (or (not (zero-value? (:x b))) (not (zero-value? (:y b))))
               (zero-value? (:x t))
               (zero-value? (:y t))
               (or (contains? changed :x) (contains? changed :y)))
          :xy-one-way-door
          (and (contains? changed :text)
               (not (zero-value? (:text b)))
               (zero-value? (:text t)))
          :text-one-way-door
          (and (contains? changed :layout) (layout-one-way-door? (:layout b) (:layout t)))
          :layout-one-way-door
          (and (contains? changed :scroll_dir)
               (not (zero-value? (:scroll_dir b)))
               (zero-value? (:scroll_dir t)))
          :scroll-dir-one-way-door
          (some #(and (contains? changed %) (bitmask-shrank? (get b %) (get t %)))
                [:obj_flags :obj_flags_clear :states])
          :bitmask-shrank
          :else nil)))

;; ═══════════════════════════════════════════════════════════════════
;; UPDATE_PROPS payload shaping (strip) + pure restore (the oracle's
;; inverse) — one coupled pair sharing the tables above.
;; ═══════════════════════════════════════════════════════════════════
(defn- strip-value-bearing
  "Drop value-bearing paths whose authored value is UNCHANGED between
   base and target — the form-state policy's wire shape."
  [node base target]
  (reduce (fn [n path]
            (if (and (= (get-in base path) (get-in target path)) (some? (get-in n path)))
              (if (= 1 (count path))
                (dissoc n (first path))
                (update n (first path) dissoc (second path)))
              n))
          node
          (get value-bearing-paths (:type target) #{})))

(defn update-payload
  "The UPDATE_PROPS node payload for a matched morphable pair: the full
   target node minus children, minus morph-invariants, minus
   carry-when-changed keys that did not change, minus unchanged
   value-bearing paths, minus an unchanged props arm."
  [base target]
  (let [arm (node-arm-key target)
        payload (apply dissoc target morph-invariant-keys)
        payload (reduce (fn [n k] (if (= (get base k) (get target k)) (dissoc n k) n))
                        payload
                        carry-when-changed-keys)
        payload
        (if (and arm (= (get base arm) (get target arm))) (dissoc payload arm) payload)]
    (strip-value-bearing payload base target)))

(defn- restore-update
  "Pure inverse of update-payload: reconstruct the full target node from
   the base node + an UPDATE payload (the oracle's morph semantics —
   what physical persistence does on the live object)."
  [base payload]
  (let [arm (node-arm-key base)
        restored (merge (select-keys base morph-invariant-keys) payload)
        restored (reduce (fn [n k]
                           (if (and (not (contains? payload k)) (contains? base k))
                             (assoc n k (get base k))
                             n))
                         restored
                         carry-when-changed-keys)
        restored (if (and arm (not (contains? payload arm)) (contains? base arm))
                   (assoc restored arm (get base arm))
                   restored)]
    (reduce (fn [n path]
              (if (and (nil? (get-in payload path))
                       (some? (get-in base path))
                       ;; restore only when the payload kept the arm/key
                       ;; absent for THIS path, not when the whole arm
                       ;; was restored above (already complete then)
                       (or (= 1 (count path)) (contains? payload (first path))))
                (assoc-in n path (get-in base path))
                n))
            restored
            (get value-bearing-paths (:type base) #{}))))

;; ═══════════════════════════════════════════════════════════════════
;; The differ
;; ═══════════════════════════════════════════════════════════════════
(declare diff-node)

(defn- move-ops
  "MOVE ops transforming the kept children (base relative order) into
   the target relative order. Index = the slot in the live tree at that
   point of the batch (sequential semantics)."
  [parent-uid kept desired]
  (loop [cur kept
         i 0
         acc []]
    (if (>= i (count desired))
      acc
      (let [want (nth desired i)]
        (if (= (nth cur i) want)
          (recur cur (inc i) acc)
          (let [j (java.util.List/.indexOf cur want)
                cur' (vec (concat (subvec cur 0 i) [want] (remove #{want} (subvec cur i))))]
            (when (neg? j)
              (throw (ex-info "MOVE source uid not in kept set" {:uid want :kept cur})))
            (recur cur'
                   (inc i)
                   (conj acc
                         {:kind :PATCH_OP_MOVE_NODE
                          :target_uid want
                          :parent_uid parent-uid
                          :index i}))))))))

(defn- diff-children
  "Structural + recursive ops for a matched parent's children lists.
   Emission order per parent: REMOVEs (descending base index), MOVEs,
   INSERTs (ascending target index), then kept-pair recursion."
  [parent-uid base-children target-children]
  (let [base-uids (mapv :uid base-children)
        target-uids (mapv :uid target-children)
        base-set (set base-uids)
        target-set (set target-uids)
        removes (->> (map-indexed vector base-uids)
                     (filter (fn [[_ uid]] (not (target-set uid))))
                     (sort-by first >)
                     (mapv (fn [[_ uid]] {:kind :PATCH_OP_REMOVE_NODE :target_uid uid})))
        kept (filterv target-set base-uids)
        desired (filterv base-set target-uids)
        moves (move-ops parent-uid kept desired)
        inserts
        (->> (map-indexed vector target-children)
             (filter (fn [[_ c]] (not (base-set (:uid c)))))
             (mapv
              (fn [[i c]]
                {:kind :PATCH_OP_INSERT_NODE :parent_uid parent-uid :index i :node c})))
        base-by-uid (into {} (map (juxt :uid identity)) base-children)
        recursions (into []
                         (mapcat (fn [c]
                                   (when-let [b (base-by-uid (:uid c))] (diff-node b c))))
                         target-children)]
    (-> []
        (into removes)
        (into moves)
        (into inserts)
        (into recursions))))

(defn- diff-node
  "Ops for a matched (same-uid) base/target node pair."
  [base target]
  (let [b (dissoc base :children)
        t (dissoc target :children)
        replace-reason (when (not= b t) (needs-replace? base target))
        tabview? (= :WIDGET_TABVIEW (:type target))]
    ;; A tabview's DIRECT children are zipped into pages/bar — the live
    ;; tree does not mirror the IR there, so any structural change at
    ;; that level replaces the whole tabview (the second arm). Same-shape
    ;; children recurse normally (their own subtrees DO mirror the IR).
    (if (or replace-reason
            (and tabview?
                 (not= (mapv :uid (:children base)) (mapv :uid (:children target)))))
      [{:kind :PATCH_OP_REPLACE_NODE :target_uid (:uid target) :node target}]
      (let [own (when (not= b t)
                  [{:kind :PATCH_OP_UPDATE_PROPS
                    :target_uid (:uid target)
                    :node (update-payload base target)}])
            child-ops (if tabview?
                        (let [base-by-uid
                              (into {} (map (juxt :uid identity)) (:children base))]
                          (into []
                                (mapcat (fn [c] (diff-node (base-by-uid (:uid c)) c)))
                                (:children target)))
                        (diff-children (:uid target) (:children base) (:children target)))]
        (into (vec own) child-ops)))))

(defn diff-screen
  "Diff two proto-IR screens into a patch verdict (see diff-result).
   Root identity change (the root's uid moved) and subject-declaration
   changes are not patchable — :full-reload."
  [base-ir target-ir]
  (cond (not= (:subjects base-ir) (:subjects target-ir)) {:result :full-reload
                                                          :reason :subjects-changed}
        (not= (get-in base-ir [:root :uid]) (get-in target-ir [:root :uid]))
        {:result :full-reload :reason :root-identity-changed}
        (= (:root base-ir) (:root target-ir)) {:result :unchanged}
        :else {:result :patch :ops (diff-node (:root base-ir) (:root target-ir))}))

;; ═══════════════════════════════════════════════════════════════════
;; Pure applier — the clj roundtrip oracle:
;; (apply-patch-ir base (diff base target)) ≡ target
;; ═══════════════════════════════════════════════════════════════════
(defn- edit-tree
  "Replace the node with `uid` by (f node); returns [tree' found?]."
  [node uid f]
  (if (= uid (:uid node))
    [(f node) true]
    (loop [i 0]
      (if (>= i (count (:children node)))
        [node false]
        (let [[child' found?] (edit-tree (nth (:children node) i) uid f)]
          (if found? [(assoc-in node [:children i] child') true] (recur (inc i))))))))

(defn- edit-tree!
  "edit-tree that throws when the uid is absent (a differ bug — the ops
   must always address live uids)."
  [node uid f]
  (let [[tree' found?] (edit-tree node uid f)]
    (when-not found? (throw (ex-info "patch op addresses unknown uid" {:uid uid})))
    tree'))

(defn- remove-from-tree
  "Remove the child node with `uid` from its parent's :children. A list
   emptied by the removal drops the :children key entirely — emit-screen
   never emits an empty :children, and the oracle must mirror that IR
   convention or a last-child REMOVE falsely fails the roundtrip."
  [node uid]
  (let [n (count (:children node))
        idx (first (keep-indexed (fn [i c] (when (= uid (:uid c)) i)) (:children node)))]
    (if idx
      (let [cs' (vec (concat (subvec (vec (:children node)) 0 idx)
                             (subvec (vec (:children node)) (inc idx) n)))]
        (if (seq cs') (assoc node :children cs') (dissoc node :children)))
      (let [[tree' found?] (loop [i 0]
                             (if (>= i n)
                               [node false]
                               (let [c' (remove-from-tree (nth (:children node) i) uid)]
                                 (if (identical? c' (nth (:children node) i))
                                   (recur (inc i))
                                   [(assoc-in node [:children i] c') true]))))]
        (if found? tree' node)))))

(defn- insert-at
  "Insert v into vector vs at index i."
  [vs i v]
  (vec (concat (subvec (vec vs) 0 i) [v] (subvec (vec vs) i))))

(defn- apply-op
  "Apply one op to the root tree (the oracle's sequential semantics)."
  [tree {:keys [kind target_uid parent_uid index node]}]
  (case kind
    :PATCH_OP_UPDATE_PROPS (edit-tree! tree target_uid #(restore-update % node))
    :PATCH_OP_REPLACE_NODE (edit-tree! tree target_uid (constantly node))
    :PATCH_OP_REMOVE_NODE (let [tree' (remove-from-tree tree target_uid)]
                            (when (= tree' tree)
                              (throw (ex-info "REMOVE op addresses unknown uid"
                                              {:uid target_uid})))
                            tree')
    :PATCH_OP_INSERT_NODE
    (edit-tree! tree
                parent_uid
                (fn [parent]
                  (update parent :children #(insert-at (vec (or % [])) index node))))
    :PATCH_OP_MOVE_NODE
    (edit-tree!
     tree
     parent_uid
     (fn [parent]
       (let [cs (vec (:children parent))
             moving (first (filter #(= target_uid (:uid %)) cs))]
         (when-not moving
           (throw (ex-info "MOVE op addresses unknown child uid"
                           {:uid target_uid :parent parent_uid})))
         (assoc parent
                :children
                (insert-at (vec (remove #(= target_uid (:uid %)) cs)) index moving)))))))

(defn apply-patch-ir
  "Pure patch application over a screen IR — the kaocha-tier oracle:
   (apply-patch-ir base (:ops (diff-screen base target))) ≡ target."
  [base-screen-ir ops]
  (update base-screen-ir :root #(reduce apply-op % ops)))

;; ═══════════════════════════════════════════════════════════════════
;; Wire shaping
;; ═══════════════════════════════════════════════════════════════════
(defn patch-ir
  "ScreenPatch IR map (pronto-serializable shape): the base/target .pb
   content hashes + the ops."
  [base-hash target-hash ops]
  {:base_hash base-hash :target_hash target-hash :ops ops})

;; -- Function schema registrations --
(m/=> zero-value? [:=> [:cat [:maybe some?]] :boolean])

(m/=> arm-one-way-door?
      [:=>
       [:cat :keyword [:maybe [:map-of :keyword some?]] [:maybe [:map-of :keyword some?]]]
       :boolean])

(m/=> layout-one-way-door?
      [:=> [:cat [:maybe [:map-of :keyword some?]] [:maybe [:map-of :keyword some?]]]
       :boolean])

(m/=> chart-values-only-change?
      [:=> [:cat [:map-of :keyword some?] [:map-of :keyword some?]] :boolean])

(m/=> bitmask-shrank? [:=> [:cat [:maybe int?] [:maybe int?]] :boolean])

(m/=> node-arm-key [:=> [:cat [:map-of :keyword some?]] [:maybe :keyword]])

(m/=> needs-replace? [:=> [:cat ir-node ir-node] [:maybe :keyword]])

(m/=> strip-value-bearing [:=> [:cat ir-node ir-node ir-node] ir-node])

(m/=> update-payload [:=> [:cat ir-node ir-node] ir-node])

(m/=> restore-update [:=> [:cat ir-node ir-node] ir-node])

(m/=> move-ops [:=> [:cat int? [:vector int?] [:vector int?]] [:vector patch-op]])

(m/=> diff-children
      [:=> [:cat int? [:maybe [:sequential ir-node]] [:maybe [:sequential ir-node]]]
       [:vector patch-op]])

(m/=> diff-node [:=> [:cat ir-node ir-node] [:vector patch-op]])

(m/=> diff-screen [:=> [:cat screen-ir screen-ir] diff-result])

(m/=> edit-tree [:=> [:cat ir-node int? ifn?] [:tuple ir-node :boolean]])

(m/=> edit-tree! [:=> [:cat ir-node int? ifn?] ir-node])

(m/=> remove-from-tree [:=> [:cat ir-node int?] ir-node])

(m/=> insert-at [:=> [:cat [:sequential some?] nat-int? some?] [:vector some?]])

(m/=> apply-op [:=> [:cat ir-node patch-op] ir-node])

(m/=> apply-patch-ir [:=> [:cat screen-ir [:sequential patch-op]] screen-ir])

(m/=> patch-ir
      [:=> [:cat int? int? [:sequential patch-op]]
       [:map [:base_hash int?] [:target_hash int?] [:ops [:sequential patch-op]]]])