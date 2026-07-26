(ns lvgl-codegen.morph-fixtures
  "Morph-parity fixture generator (docs/lvgl-factory/10-TREE-PATCH-DESIGN.md
   D7 tier 2/3): emits (base.pb, target.pb, patch.pb) triples — one per
   op kind / mutability class, pairwise op-kind combos, overlap/storm
   batches, raw op-chained sequences the differ cannot emit in one
   patch, real-corpus pairs (demo_widgets, kitchen_sink), and the
   form-state scenario screens — consumed by the pocs/02 dual-oracle
   harness test (`make morph-parity`). A `degenerate/` subdir carries
   RAW patches that must ABORT, each with an `.expect` sidecar (the
   exact PATCH_ERR_* code) and a `.valid.patch.pb` companion (a real
   diff from the same base, used to prove the post-abort INDETERMINATE
   refusal and the post-reload recovery — doc 10 § D6a).

   Usage: --tokens <path> --output <dir>"
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [lvgl-codegen.component :as component]
            [lvgl-codegen.core :as core]
            [lvgl-codegen.emit-proto :as emit-proto]
            [lvgl-codegen.expand :as expand]
            [lvgl-codegen.patch :as patch]
            [lvgl-codegen.proto-ser :as proto-ser]
            [lvgl-codegen.schema :as schema]
            [malli.core :as m]
            [uigen.cmd-spec :as cmd-spec])
  (:gen-class))

(set! *warn-on-reflection* true)

;; -- Pipeline --
(defn- screen->ir
  "The real pipeline (validate → resolve → semantic validate → expand →
   emit) for one screen map."
  [tokens components screen]
  (when-let [errors (schema/validate-screen screen)]
    (throw (ex-info "morph fixture screen invalid" {:errors errors})))
  (let [resolved (component/resolve-components components (core/join-class-vectors screen))]
    (when-let [errors (schema/validate-screen-semantics resolved)]
      (throw (ex-info "morph fixture semantics invalid" {:errors errors})))
    (emit-proto/emit-screen (expand/expand-screen tokens resolved))))
(m/=> screen->ir
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?]
        [:map [:tree [:map [:tag :keyword]]]]] :map])

;; -- Fixture screens (authored against the real edn/tokens.edn) --
(defn- scr
  "Screen wrapper with optional subjects/events."
  ([tree] (scr tree {} {}))
  ([tree subjects events] {:type :screen :subjects subjects :events events :tree tree}))
(m/=> scr
      [:function [:=> [:cat [:map [:tag :keyword]]] :map]
       [:=> [:cat [:map [:tag :keyword]] [:map-of :keyword map?] [:map-of :keyword map?]]
        :map]])

(def ^:private form-tree
  "The tier-3 form scenario base: a styled panel holding a textarea (the
   user types here), a bound slider + readout label (user value rides
   the subject), a switch (user toggle), and a SIBLING status label the
   'touch another node' patches target."
  {:tag :lv_obj
   :id "root"
   :class "w-380 h-280"
   :children [{:tag :lv_obj
               :id "form"
               :class "w-360 h-200"
               :style {:bg-color "#1A1A2E" :pad-all 8}
               :layout :flex-col
               :children [{:tag :lv_textarea
                           :id "name"
                           :text "seed"
                           :textarea_props {:one_line true :placeholder "Name"}
                           :class "w-320 h-48"}
                          {:tag :lv_slider
                           :id "volume"
                           :slider_props {:min_value 0 :max_value 100 :value 25}
                           :bind {:value :vol}
                           :class "w-320"}
                          {:tag :lv_label :id "vol-readout" :text "0" :bind {:text :vol}}
                          {:tag :lv_switch :id "notify"}]}
              {:tag :lv_label :id "status" :text "ready"}]})

(def ^:private form-subjects {:vol {:type :int :default 25}})

(defn- keyed-row
  "An lv_obj with keyed labels — the structural-op playground."
  [& ids]
  {:tag :lv_obj
   :id "row"
   :class "w-300 h-60"
   :layout :flex-row
   :children (mapv (fn [k] {:tag :lv_label :id k :text k}) ids)})
(m/=> keyed-row [:=> [:cat [:* [:string {:min 1}]]] [:map [:tag :keyword]]])

(defn- chart-screen
  [series]
  (scr {:tag :lv_obj
        :class "w-320 h-220"
        :children [{:tag :lv_chart
                    :id "ch"
                    :class "w-280 h-160"
                    :chart_props
                    {:type :line :point_count 4 :div_lines [2 2] :series series}}]}))
(m/=> chart-screen [:=> [:cat [:vector map?]] :map])

(defn- tabview-screen
  [active]
  (scr
   {:tag :lv_obj
    :class "w-320 h-220"
    :children
    [{:tag :lv_tabview
      :id "tabs"
      :tabview_props {:tab_names ["One" "Two"] :tab_bar_size 30 :active_index active}
      :children
      [{:tag :lv_obj :id "p1" :children [{:tag :lv_label :id "l1" :text "page one"}]}
       {:tag :lv_obj :id "p2" :children [{:tag :lv_label :id "l2" :text "page two"}]}]}]}))
(m/=> tabview-screen [:=> [:cat nat-int?] :map])

(defn- proxy-screen
  [with-proxy?]
  (scr {:tag :lv_obj
        :class "w-320 h-220"
        :children (cond-> [{:tag :lv_label :id "anchor" :text "anchor"}]
                    with-proxy? (conj {:tag :lv_host_proxy
                                       :id "px"
                                       :x 40
                                       :y 40
                                       :class "w-160 h-100"
                                       :host_proxy_props {:proxy_id "px"
                                                          :mode :draggable}}))}))
(m/=> proxy-screen [:=> [:cat :boolean] :map])

;; -- Keyed node helpers (the combo/storm/degenerate playground) --
(defn- node-lbl [id text] {:tag :lv_label :id id :text text})
(m/=> node-lbl [:=> [:cat [:string {:min 1}] [:string {:min 1}]] [:map [:tag :keyword]]])

(defn- node-btn [id] {:tag :lv_button :id id :class "w-60 h-30"})
(m/=> node-btn [:=> [:cat [:string {:min 1}]] [:map [:tag :keyword]]])

(defn- row-scr
  "Keyed-row screen whose children are explicit node maps."
  [kids]
  (scr {:tag :lv_obj :id "row" :class "w-300 h-60" :layout :flex-row :children (vec kids)}))
(m/=> row-scr [:=> [:cat [:sequential [:map [:tag :keyword]]]] :map])

(defn- event-btn-scr
  "Screen holding a cmd-emitting button (fixed coords — the rs P0 test clicks
   its center) whose OWN bg-color is the morph axis: the differ emits an UPDATE
   targeting the BUTTON itself with the unchanged event STRIPPED from the
   payload (morph-invariant-keys). The reconciler must ACCEPT that stripped
   UPDATE (the reject arm fires only on a payload that CARRIES an event) — this
   pair is the accept-direction oracle; the axis deliberately sits on the event
   node, not its parent, so the event node actually rides the wire."
  [bg]
  (scr {:tag :lv_obj
        :id "panel"
        :class "w-300 h-200"
        :children [{:tag :lv_button
                    :id "fire"
                    :event :fire
                    :x 100
                    :y 60
                    :class "w-100 h-60"
                    :style {:bg-color bg}}]}
       {}
       {:fire {:int-value 7
               :notify-host true
               :cmd (cmd-spec/cmd-spec "cmd.DayCamera.SetZoomTableValue"
                                       :PATCH_KIND_WIDGET_VALUE)}}))
(m/=> event-btn-scr [:=> [:cat [:string {:min 1}]] :map])

(def ^:private synthetic-cases
  "name → [base-screen target-screen] (op-kind + mutability coverage)."
  (let [lbl (fn [t]
              (scr {:tag :lv_obj
                    :class "w-300 h-100"
                    :children [{:tag :lv_label :id "msg" :text t}]}))
        styled (fn [c]
                 (scr {:tag :lv_obj
                       :id "panel"
                       :class "w-300 h-100"
                       :style {:bg-color c :pad-all 8}
                       :children [{:tag :lv_label :id "msg" :text "styled"}]}))
        ;; A label whose style carries a TTF-resolved font (b612mono_bold_26
        ;; is NOT in resolve_font's compiled-in ladder — it rasterizes from
        ;; P:fonts/b612mono_bold.ttf) alongside the morph-axis color: the
        ;; differ carries style_groups WHOLE, so every UPDATE re-carries the
        ;; unchanged font name and re-resolves it on apply.
        ttf-styled (fn [c]
                     (scr {:tag :lv_obj
                           :id "panel"
                           :class "w-300 h-100"
                           :children [{:tag :lv_label
                                       :id "msg"
                                       :text "ttf styled"
                                       :style {:text-font "b612mono_bold_26"
                                               :text-color c}}]}))
        slider (fn [mx v m]
                 (scr {:tag :lv_slider
                       :id "sl"
                       :class "w-200"
                       :slider_props (cond-> {:min_value 0 :max_value mx :value v}
                                       m (assoc :mode m))}))
        ta (fn [ph txt]
             (scr {:tag :lv_textarea
                   :id "ta"
                   :class "w-200 h-60"
                   :text txt
                   :textarea_props {:one_line true :placeholder ph}}))]
    {"text_update" [(lbl "Hello") (lbl "World")]
     ;; The reverse triple exists for the base-hash NON-poisoning test:
     ;; after a -2 refusal the loaded state is still patchable.
     "text_update_rev" [(lbl "World") (lbl "Hello")]
     "style_morph" [(styled "#FF0000") (styled "#00AA55")]
     ;; fwd/rev pair (same trick as text_update/_rev: identical screen maps ⇒
     ;; byte-identical .pb ⇒ chainable hashes) — the rs binfont-registry test
     ;; alternates them >MAX_BINFONTS times over ONE live instance to pin the
     ;; resolve_font per-name cache (pre-cache, each morph consumed a registry
     ;; slot and patch_pools_low refused with rc -4 after ~14 morphs).
     "font_style_morph" [(ttf-styled "#FF0000") (ttf-styled "#00AA55")]
     "font_style_morph_rev" [(ttf-styled "#00AA55") (ttf-styled "#FF0000")]
     "slider_range" [(slider 100 30 nil) (slider 200 30 nil)]
     "slider_value" [(slider 100 30 nil) (slider 100 70 nil)]
     "replace_mode_door" [(slider 100 30 :symmetrical) (slider 100 30 :normal)]
     "insert_keyed" [(scr (keyed-row "a" "b" "c")) (scr (keyed-row "a" "x" "b" "c"))]
     "remove_keyed" [(scr (keyed-row "a" "b" "c")) (scr (keyed-row "a" "c"))]
     "move_keyed" [(scr (keyed-row "a" "b" "c")) (scr (keyed-row "c" "a" "b"))]
     "replace_type" [(scr {:tag :lv_obj :children [{:tag :lv_label :id "k" :text "x"}]})
                     (scr {:tag :lv_obj :children [{:tag :lv_button :id "k"}]})]
     "chart_values" [(chart-screen [{:values [10 20 30 40]}])
                     (chart-screen [{:values [40 10 35 5]}])]
     "chart_replace" [(chart-screen [{:values [10 20 30 40]}])
                      (chart-screen [{:values [10 20 30 40]} {:values [5 15 25 35]}])]
     "tabview_replace" [(tabview-screen 0) (tabview-screen 1)]
     "proxy_remove" [(proxy-screen true) (proxy-screen false)]
     "proxy_insert" [(proxy-screen false) (proxy-screen true)]
     "textarea_placeholder" [(ta "Type here" "seed") (ta "Write" "seed")]
     "multi_batch" [(scr (keyed-row "a" "b" "c" "d"))
                    (scr (assoc-in (keyed-row "d" "a" "x" "c") [:children 3 :text] "C!"))]
     "form_touch_other"
     [(scr form-tree form-subjects {})
      (scr (assoc-in form-tree [:children 1 :text] "saved!") form-subjects {})]
     "scroll_preserve"
     [(scr {:tag :lv_obj
            :class "w-300 h-260"
            :children
            [{:tag :lv_obj
              :id "list"
              :class "w-200 h-160"
              :layout :flex-col
              :children
              (mapv (fn [i]
                      {:tag :lv_label :id (str "row" i) :text (str "row " i) :class "h-40"})
                    (range 8))} {:tag :lv_label :id "status" :text "ready"}]})
      (scr {:tag :lv_obj
            :class "w-300 h-260"
            :children
            [{:tag :lv_obj
              :id "list"
              :class "w-200 h-160"
              :layout :flex-col
              :children
              (mapv (fn [i]
                      {:tag :lv_label :id (str "row" i) :text (str "row " i) :class "h-40"})
                    (range 8))} {:tag :lv_label :id "status" :text "scrolled"}]})]
     "form_container_style"
     [(scr form-tree form-subjects {})
      (scr (assoc-in form-tree [:children 0 :style :bg-color] "#27374D") form-subjects {})]
     "form_authored_text" [(scr form-tree form-subjects {})
                           (scr
                            (assoc-in form-tree [:children 0 :children 0 :text] "authored2")
                            form-subjects
                            {})]
     ;; A cmd-emitting button whose STYLE morphs (event unchanged → the differ
     ;; emits UPDATE with the event stripped, per morph-invariant-keys). The rs
     ;; P0 test clicks it post-morph and asserts EXACTLY ONE host_command — the
     ;; duplicate-attach guard's end-to-end oracle.
     "style_morph_event_btn" [(event-btn-scr "#FF0000") (event-btn-scr "#00AA55")]}))

(def ^:private combo-cases
  "name → [base target]: every pairwise op-kind combination, the
   all-five full mix, the overlap/storm batches (multi-op patches over
   different tree elements with and without overlap), and the tier-3
   form scenarios under combo classes. Differ-driven — the C sweep
   re-verifies each through the dual oracle (tree + pixels)."
  (let [a (node-lbl "a" "a")
        b (node-lbl "b" "b")
        c (node-lbl "c" "c")
        d (node-lbl "d" "d")
        e (node-lbl "e" "e")
        a' (node-lbl "a" "A!")
        rb (node-btn "b")
        x (node-lbl "x" "x")
        boxes (fn [a-kids b-kids]
                (scr
                 {:tag :lv_obj
                  :id "wrap"
                  :class "w-320 h-160"
                  :children
                  [{:tag :lv_obj :id "boxA" :class "w-150 h-140" :children (vec a-kids)}
                   {:tag :lv_obj :id "boxB" :class "w-150 h-140" :children (vec b-kids)}]}))
        panel (fn [tag txt]
                (scr {:tag :lv_obj
                      :id "wrap"
                      :class "w-300 h-100"
                      :children [{:tag tag :id "panel" :children [(node-lbl "k" txt)]}]}))
        banner (node-lbl "banner" "banner")
        footer (node-lbl "footer" "footer")
        form-restyled (assoc-in form-tree [:children 0 :style :bg-color] "#27374D")]
    {"pair_update_insert" [(row-scr [a b]) (row-scr [a' x b])]
     "pair_update_remove" [(row-scr [a b c]) (row-scr [a' c])]
     "pair_update_move" [(row-scr [a b c]) (row-scr [a' c b])]
     "pair_update_replace" [(row-scr [a b]) (row-scr [a' rb])]
     "pair_replace_insert" [(row-scr [a b]) (row-scr [a rb x])]
     "pair_replace_remove" [(row-scr [a b c]) (row-scr [rb c])]
     "pair_replace_move" [(row-scr [a b c]) (row-scr [c rb a])]
     "pair_insert_remove" [(row-scr [a b c]) (row-scr [a x c])]
     "pair_insert_move" [(row-scr [a b c]) (row-scr [c x a b])]
     "pair_remove_move" [(row-scr [a b c]) (row-scr [c a])]
     "full_mix" [(row-scr [a b c d e]) (row-scr [c a' x (node-btn "d")])]
     "overlap_update_move" [(row-scr [a b c]) (row-scr [(node-lbl "b" "B!") a c])]
     "disjoint_subtrees"
     [(boxes [(node-lbl "a1" "a1")] [(node-lbl "b1" "b1") (node-lbl "b2" "b2")])
      (boxes [(node-lbl "a1" "a1") (node-lbl "a2" "a2")] [(node-lbl "b1" "B1!")])]
     "replace_absorbs_children" [(panel :lv_obj "x") (panel :lv_button "y")]
     "sibling_storm" [(row-scr [a b c d e])
                      (row-scr [(node-lbl "f" "f") b d (node-lbl "g" "g")])]
     "storm_with_moves" [(row-scr [a b c d e]) (row-scr [e x a d])]
     "form_sibling_storm"
     [(scr form-tree form-subjects {})
      (scr (assoc form-tree :children [banner (nth (:children form-restyled) 0) footer])
           form-subjects
           {})]
     "form_move_under_storm" [(scr form-tree form-subjects {})
                              (scr (assoc form-tree
                                          :children
                                          [(node-lbl "status" "moved!")
                                           (nth (:children form-tree) 0)])
                                   form-subjects
                                   {})]}))

(defn- real-corpus-cases
  "Pairs derived from real screens: a text tweak in demo_widgets and a
   label tweak in kitchen_sink — patch machinery exercised against
   full-complexity corpus screens."
  []
  (let [demo (core/load-edn "edn/screens/demo_widgets.edn")
        kitchen (core/load-edn "edn/screens/kitchen_sink.edn")
        retext (fn [screen from to]
                 (walk/postwalk
                  (fn [form]
                    (if (and (map? form) (= from (:text form))) (assoc form :text to) form))
                  screen))]
    {"demo_widgets_text" [demo (retext demo "User name" "Login name")]
     "kitchen_sink_text" [kitchen (retext kitchen "Heading Text" "Patched Heading")]}))
(m/=> real-corpus-cases [:=> [:cat] [:map-of [:string {:min 1}] vector?]])

;; -- Raw op-chained cases (sequences the differ cannot emit in ONE
;;    patch: duplicate ops on a uid, ops addressing nodes inside a
;;    subtree another op in the same patch replaces, remove-then-insert
;;    slot reuse). Built by CHAINING differ outputs — each link is
;;    differ-emitted, the concatenation is the raw patch (doc 10 § D6a
;;    rules 7–9) — and verified by the FULL dual oracle.
(defn- chain-ops
  "Concatenate the diffs along a screen-IR chain into one raw op vector."
  [irs]
  (into []
        (mapcat (fn [[a b]]
                  (let [{:keys [result ops reason]} (patch/diff-screen a b)]
                    (case result
                      :patch ops
                      :unchanged []
                      (throw (ex-info "chain link is not patchable" {:reason reason}))))))
        (partition 2 1 irs)))
(m/=> chain-ops
      [:=> [:cat [:sequential [:map [:root map?]]]] [:vector [:map [:kind :keyword]]]])

(def ^:private raw-chain-cases
  "name → [screen … screen]: base is first, target is last, the patch
   is the concatenation of the per-link diffs."
  (let [k (node-lbl "k" "k")
        z (node-lbl "z" "z")
        panel (fn [tag kids]
                (scr {:tag :lv_obj
                      :id "wrap"
                      :class "w-300 h-100"
                      :children [{:tag tag :id "panel" :children (vec kids)}]}))
        arow (fn [t] (row-scr [(node-lbl "a" t) (node-lbl "b" "b")]))]
    {"raw_duplicate_update" [(arow "one") (arow "two") (arow "three")]
     "raw_insert_under_replaced" [(panel :lv_obj [k]) (panel :lv_button [k])
                                  (panel :lv_button [k z])]
     "raw_replace_after_insert" [(panel :lv_obj [k]) (panel :lv_obj [k z])
                                 (panel :lv_button [k])]
     "raw_remove_then_insert_pos"
     [(row-scr [(node-lbl "a" "a") (node-lbl "b" "b") (node-lbl "c" "c")])
      (row-scr [(node-lbl "a" "a") (node-lbl "c" "c")])
      (row-scr [(node-lbl "a" "a") (node-lbl "x" "x") (node-lbl "c" "c")])]}))

;; -- Degenerate raw patches (must ABORT — doc 10 § D6a) --
(defn- find-uid
  "uid of the first node in the IR tree matching pred."
  [ir pred]
  (or (some #(when (pred %) (:uid %)) (tree-seq map? :children (:root ir)))
      (throw (ex-info "degenerate fixture: node not found" {}))))
(m/=> find-uid [:=> [:cat [:map [:root map?]] ifn?] int?])

(def ^:private degenerate-target-hash
  "Arbitrary fixed target_hash for degenerate raw patches — distinct
   from any real base hash so the empty-ops vacuous apply observably
   advances the state hash (the rs sweep re-applies and expects -2)."
  305419896)

(def ^:private degenerate-cases
  "name → {:base screen, :ops (fn [base-ir] raw ops), :expect rc,
   :valid target-screen}. Each raw patch must return exactly :expect
   from controls_apply_patch; :valid is a real differ patch from the
   same base, used by the rs sweep to prove the post-abort INDETERMINATE
   refusal (-6) and the post-reload recovery. \"empty_ops\" is the one
   expected-success degenerate (wire-valid — the proto carries no
   min_items on ops — applying vacuously)."
  (let [a (node-lbl "a" "a")
        b (node-lbl "b" "b")
        row (row-scr [a b])
        row-valid (row-scr [a (node-lbl "b" "B!")])
        uid-of-text (fn [ir t] (find-uid ir #(= t (:text %))))]
    {"update_removed_uid" {:base row
                           :valid row-valid
                           :expect -3
                           :ops (fn [ir] [{:kind :PATCH_OP_REMOVE_NODE
                                           :target_uid (uid-of-text ir "a")}
                                          {:kind :PATCH_OP_UPDATE_PROPS
                                           :target_uid (uid-of-text ir "a")
                                           :node {:type :WIDGET_LABEL :text "ghost"}}])}
     "unknown_uid" {:base row
                    :valid row-valid
                    :expect -3
                    :ops (fn [_ir] [{:kind :PATCH_OP_UPDATE_PROPS
                                     :target_uid 19
                                     :node {:type :WIDGET_LABEL :text "ghost"}}])}
     "update_uid_zero" {:base row
                        :valid row-valid
                        :expect -3
                        :ops (fn [_ir] [{:kind :PATCH_OP_UPDATE_PROPS
                                         :target_uid 0
                                         :node {:type :WIDGET_LABEL :text "ghost"}}])}
     "insert_no_payload" {:base row
                          :valid row-valid
                          :expect -5
                          :ops (fn [ir] [{:kind :PATCH_OP_INSERT_NODE
                                          :parent_uid (get-in ir [:root :uid])
                                          :index 0}])}
     "update_with_children" {:base row
                             :valid row-valid
                             :expect -5
                             :ops (fn [ir] [{:kind :PATCH_OP_UPDATE_PROPS
                                             :target_uid (uid-of-text ir "a")
                                             :node {:type :WIDGET_LABEL
                                                    :text "ghost"
                                                    :children [{:type :WIDGET_LABEL
                                                                :text "stowaway"}]}}])}
     "update_type_mismatch"
     ;; "a" is registered a WIDGET_LABEL; the UPDATE claims WIDGET_SLIDER — the
     ;; renderer's widget-class check rejects (-5) before decoding slider props
     ;; onto a label (a differ/codegen contract violation that would corrupt the
     ;; tree). Without the check the reconciler decodes vacuously and returns 0.
     {:base row
      :valid row-valid
      :expect -5
      :ops (fn [ir] [{:kind :PATCH_OP_UPDATE_PROPS
                      :target_uid (uid-of-text ir "a")
                      :node {:type :WIDGET_SLIDER}}])}
     "update_with_event"
     ;; The differ contract forbids event-carrying UPDATEs (`:event` is a
     ;; morph-invariant — a change forces REPLACE, unchanged is stripped).
     ;; Applying one would lv_obj_add_event_cb AGAIN on the live object → one
     ;; click emits TWO host_commands. The renderer rejects (-5), never
     ;; re-attaches. Without the check the attach happens and returns 0.
     {:base row
      :valid row-valid
      :expect -5
      :ops (fn [ir] [{:kind :PATCH_OP_UPDATE_PROPS
                      :target_uid (uid-of-text ir "a")
                      :node {:type :WIDGET_LABEL
                             :text "ghost"
                             :event {:name "phantom" :notify_host true :int_value 1}}}])}
     "update_grid_dsc"
     ;; Grid track templates are write-once pool slots — the differ puts
     ;; grid_col_dsc/grid_row_dsc in replace-on-change-keys (a grid change forces
     ;; REPLACE) AND strips them from UPDATE payloads (morph-invariant). A crafted
     ;; UPDATE carrying grid dsc would re-key the pool against a live object; the
     ;; renderer rejects (-5), defense-in-depth mirroring the event guard, rather
     ;; than relying on generator discipline. Without the guard the UPDATE decodes
     ;; the grid dsc onto the live node (allocating pool slots) and returns 0.
     {:base row
      :valid row-valid
      :expect -5
      :ops (fn [ir] [{:kind :PATCH_OP_UPDATE_PROPS
                      :target_uid (uid-of-text ir "a")
                      :node {:type :WIDGET_LABEL
                             :text "ghost"
                             :grid_col_dsc [100 100]
                             :grid_row_dsc [50 50]}}])}
     "update_replace_only_type"
     {:base (tabview-screen 0)
      :valid (tabview-screen 1)
      :expect -5
      :ops (fn [ir] [{:kind :PATCH_OP_UPDATE_PROPS
                      :target_uid (find-uid ir #(= :WIDGET_TABVIEW (:type %)))
                      :node {:type :WIDGET_TABVIEW}}])}
     "move_wrong_parent"
     {:base (scr {:tag :lv_obj
                  :id "wrap"
                  :class "w-300 h-140"
                  :children [{:tag :lv_obj :id "n0" :children [a]}
                             {:tag :lv_obj :id "n1" :children [b]}]})
      :valid (scr {:tag :lv_obj
                   :id "wrap"
                   :class "w-300 h-140"
                   :children [{:tag :lv_obj :id "n0" :children [a]}
                              {:tag :lv_obj :id "n1" :children [(node-lbl "b" "B!")]}]})
      :expect -5
      :ops (fn [ir] [{:kind :PATCH_OP_MOVE_NODE
                      :target_uid (uid-of-text ir "a")
                      :parent_uid (get-in ir [:root :children 1 :uid])
                      :index 0}])}
     "duplicate_insert_uid" {:base row
                             :valid row-valid
                             :expect -5
                             ;; the payload IS the base's own live "a" subtree (same uid) —
                             ;; the build succeeds, the registration fails: a MUTATING abort.
                             :ops (fn [ir] [{:kind :PATCH_OP_INSERT_NODE
                                             :parent_uid (get-in ir [:root :uid])
                                             :index 0
                                             :node (get-in ir [:root :children 0])}])}
     "empty_ops" {:base row :valid row-valid :expect 0 :ops (fn [_ir] [])}}))

;; -- Emission --
(defn- write-triple!
  "Write base/target/patch artifacts for one fixture, enforcing the
   tier-1 pure oracle first — a fixture that fails tier 1 must never
   reach tier 2."
  [^String out-dir nm base-ir target-ir ops]
  (when-not (= target-ir (patch/apply-patch-ir base-ir ops))
    (throw (ex-info (str "morph fixture " nm " fails the pure apply-patch-ir oracle")
                    {:case nm})))
  (let [^bytes base-bytes (proto-ser/ir->bytes base-ir)
        ^bytes target-bytes (proto-ser/ir->bytes target-ir)
        wire (patch/patch-ir (emit-proto/fnv1a-32-bytes base-bytes)
                             (emit-proto/fnv1a-32-bytes target-bytes)
                             ops)]
    (proto-ser/write-bytes! base-bytes (str out-dir "/" nm ".base.pb"))
    (proto-ser/write-bytes! target-bytes (str out-dir "/" nm ".target.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes wire) (str out-dir "/" nm ".patch.pb"))
    (println (str "  " nm ": " (count ops) " op(s)"))))
(m/=> write-triple!
      [:=>
       [:cat [:string {:min 1}] [:string {:min 1}] [:map [:root map?]] [:map [:root map?]]
        [:sequential [:map [:kind :keyword]]]] :nil])

(defn- emit-case!
  "Write base/target/patch artifacts for one differ-driven fixture pair;
   throws when the pair is not patchable (a fixture must exercise the
   patch path)."
  [tokens components ^String out-dir nm [base-screen target-screen]]
  (let [base-ir (screen->ir tokens components base-screen)
        target-ir (screen->ir tokens components target-screen)
        {:keys [result ops reason]} (patch/diff-screen base-ir target-ir)]
    (when-not (= :patch result)
      (throw (ex-info (str "morph fixture " nm " is not patchable: " (or reason result))
                      {:case nm :result result})))
    (write-triple! out-dir nm base-ir target-ir ops)))
(m/=> emit-case!
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?] [:string {:min 1}]
        [:string {:min 1}] vector?] :nil])

(defn- emit-raw-case!
  "Write a raw op-chained fixture: base = first screen, target = last,
   patch = the concatenated per-link diffs."
  [tokens components ^String out-dir nm screens]
  (let [irs (mapv #(screen->ir tokens components %) screens)]
    (write-triple! out-dir nm (first irs) (peek irs) (chain-ops irs))))
(m/=> emit-raw-case!
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?] [:string {:min 1}]
        [:string {:min 1}] [:sequential [:map [:tree map?]]]] :nil])

(defn- emit-degenerate-case!
  "Write a degenerate raw-patch fixture into <out>/degenerate/: base.pb,
   patch.pb (the raw ops, hashes computed from the real base bytes),
   .expect (the exact PATCH_ERR_* / 0 the reconciler must return), and
   .valid.patch.pb (a real differ patch from the same base — the
   indeterminate-refusal + recovery probe)."
  [tokens components ^String out-dir nm {:keys [base valid expect ops]}]
  (let [base-ir (screen->ir tokens components base)
        valid-ir (screen->ir tokens components valid)
        ^bytes base-bytes (proto-ser/ir->bytes base-ir)
        base-hash (emit-proto/fnv1a-32-bytes base-bytes)
        wire (patch/patch-ir base-hash degenerate-target-hash (ops base-ir))
        {:keys [result] vops :ops} (patch/diff-screen base-ir valid-ir)
        _ (when-not (= :patch result)
            (throw (ex-info (str "degenerate " nm ": valid pair must be " "patchable")
                            {:case nm :result result})))
        ^bytes valid-bytes (proto-ser/ir->bytes valid-ir)
        valid-wire (patch/patch-ir base-hash (emit-proto/fnv1a-32-bytes valid-bytes) vops)
        dir (str out-dir "/degenerate")]
    (java.io.File/.mkdirs (io/file dir))
    (proto-ser/write-bytes! base-bytes (str dir "/" nm ".base.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes wire) (str dir "/" nm ".patch.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes valid-wire)
                            (str dir "/" nm ".valid.patch.pb"))
    (spit (str dir "/" nm ".expect") (str expect))
    (println (str "  degenerate/" nm ": expect " expect))))
(m/=> emit-degenerate-case!
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?] [:string {:min 1}]
        [:string {:min 1}]
        [:map [:base [:map [:tree map?]]] [:valid [:map [:tree map?]]] [:expect int?]
         [:ops ifn?]]] :nil])

;; -- Full-load adversarial screens (controls_load_ui entry, NOT patch) --
;; The mirror of the degenerate PATCH cases on the FULL-LOAD side. A screen
;; carrying a DUPLICATE uid (a slider and an unrelated sibling button-label
;; forced onto one uid): assign-uids structurally refuses to mint an in-tree
;; collision, so it is stamped by hand — exactly the shape a future codegen
;; regression, a fuzzed payload, or a non-Clojure producer of the public
;; ui_ast contract could send. The renderer C must refuse a colliding uid
;; CLEANLY (no cross-attributed uid/style bookkeeping, load returns non-OK):
;; the collided node stays unidentified (no duplicate uid in dump_tree) rather
;; than claiming another node's identity and mis-owning its style/gesture slot.
(def ^:private dup-uid-screen
  "Slider + a sibling button whose 'GO' child label carries a TTF-resolved
   font (b612mono_bold_26) — the custom-font style group is what makes
   record_added_styles cross-attribute the label's style onto the slider's
   registry entry once their uids collide."
  {:type :screen
   :subjects {}
   :events {}
   :tree {:tag :lv_obj
          :id "root"
          :class "w-380 h-280"
          :children [{:tag :lv_slider
                      :id "scrub"
                      :slider_props {:min_value 0 :max_value 100 :value 30}
                      :class "w-320"}
                     {:tag :lv_button
                      :id "btn"
                      :class "w-100 h-40"
                      :children [{:tag :lv_label
                                  :id "btnlbl"
                                  :text "GO"
                                  :style {:text-font "b612mono_bold_26"}}]}
                     {:tag :lv_label :id "status" :text "ready"}]}})

(def ^:private dup-uid-reload-target
  "A clean screen to reload INTO — teardown of the dup tree happens inside
   this load's lv_obj_clean; the reload path the colliding screen is exercised
   against across repeated load cycles."
  {:type :screen
   :subjects {}
   :events {}
   :tree {:tag :lv_obj
          :id "root2"
          :class "w-380 h-280"
          :children [{:tag :lv_label :id "only" :text "second screen"}]}})

(defn- dup-uid-ir
  "Build the duplicate-uid screen IR: the real pipeline emits a
   collision-free IR, then the slider's minted uid is stamped onto the
   unrelated 'GO' button-label — the in-tree duplicate assign-uids will not
   mint on its own. Throws if no duplicate results (the collision is the
   whole point of the fixture)."
  [tokens components]
  (let [ir (screen->ir tokens components dup-uid-screen)
        slider-uid (find-uid ir #(= :WIDGET_SLIDER (:type %)))
        dup-ir (update ir :root
                       (fn [root]
                         (walk/postwalk
                          (fn [n]
                            (if (and (map? n) (= "GO" (:text n)))
                              (assoc n :uid slider-uid)
                              n))
                          root)))
        uids (keep :uid (tree-seq map? :children (:root dup-ir)))
        dups (for [[k v] (frequencies uids) :when (> v 1)] k)]
    (when (empty? dups)
      (throw (ex-info "dup-uid fixture produced no duplicate uid" {:uids uids})))
    dup-ir))
(m/=> dup-uid-ir
      [:=> [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?]]
       [:map [:root map?]]])

(defn- emit-full-load-case!
  "Write the two full-load screens: dup_uid_screen.pb (the colliding screen)
   and dup_uid_reload_target.pb (the clean reload target). Plain screen .pb
   payloads (controls_load_ui consumes these), NOT patch triples."
  [tokens components ^String out-dir]
  (let [dup-ir (dup-uid-ir tokens components)
        target-ir (screen->ir tokens components dup-uid-reload-target)
        ^bytes dup-bytes (proto-ser/ir->bytes dup-ir)
        ^bytes target-bytes (proto-ser/ir->bytes target-ir)]
    (proto-ser/write-bytes! dup-bytes (str out-dir "/dup_uid_screen.pb"))
    (proto-ser/write-bytes! target-bytes (str out-dir "/dup_uid_reload_target.pb"))
    (println "  full-load/dup_uid_screen + dup_uid_reload_target")))
(m/=> emit-full-load-case!
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?] [:string {:min 1}]]
       :nil])

;; -- Grid-pool exhaustion (T18 / D-WB9): a REPLACE whose new subtree's grid
;;    demand OUTRUNS the write-once template pool. patch_pools_low's fixed
;;    PATCH_GRID_HEADROOM is a margin checked once at the op top, BEFORE the
;;    (arbitrarily large) subtree decodes — so a single REPLACE with more grid
;;    containers than the margin blows straight through it, deleting the old
;;    subtree first and half-building the new one. The demand-aware check counts
;;    the incoming subtree's grid containers and refuses (-4) BEFORE any delete,
;;    leaving the pre-patch tree unchanged. Raw ops — the differ would never emit
;;    a pathological screen.
(def ^:private grid-pool-exhaust-cells
  "Grid containers in the over-demand REPLACE subtree: 33 * 2 = 66 pool slots >
   MAX_GRID_TEMPLATES (64), while the base leaves the pool empty so
   patch_pools_low's fixed margin PASSES — only the demand-aware check catches
   it (proving the check is demand-aware, not the fixed margin)."
  33)

(def ^:private grid-pool-ok-cells
  "Grid containers in the within-pool REPLACE subtree (5 * 2 = 10 slots — well
   under the pool)."
  5)

(def ^:private grid-pool-base
  "Base screen with a replaceable target label + a sibling that must survive."
  {:type :screen
   :subjects {}
   :events {}
   :tree {:tag :lv_obj
          :id "root"
          :class "w-380 h-280"
          :children [{:tag :lv_label :id "target" :text "REPLACE ME"}
                     {:tag :lv_label :id "keep" :text "stays"}]}})

(defn- grid-cell-node
  "A raw IR grid-container node — BOTH col+row track templates, so it costs two
   grid-template pool slots."
  []
  {:type :WIDGET_OBJ :grid_col_dsc [100 100] :grid_row_dsc [50 50]})
(m/=> grid-cell-node [:=> [:cat] [:map [:type :keyword]]])

(defn- grid-wall-node
  "A non-grid container holding `n` grid-container children (demand = n)."
  [n]
  {:type :WIDGET_OBJ :children (vec (repeatedly n grid-cell-node))})
(m/=> grid-wall-node [:=> [:cat nat-int?] [:map [:type :keyword]]])

(defn- emit-grid-pool-case!
  "Write the grid-pool fixtures: base.pb (replaceable target), exhaust.patch.pb
   (an over-demand REPLACE that must refuse -4 before any mutation), ok.patch.pb
   (a within-pool REPLACE that succeeds), and insert_over_demand.pb (the same
   over-demand subtree arriving via INSERT). Base hash computed from the real
   base bytes; target hash is the arbitrary degenerate constant (the tests never
   chain past the single op).

   INSERT carries the SAME demand-aware guard REPLACE does, placed before both
   the parent lookup and the node decode. Existing INSERT fixtures already run
   THROUGH that guard (duplicate_insert_uid depends on passing it), but none
   had ever made it REFUSE — and a branch nobody has watched fire is
   indistinguishable from an absent one."
  [tokens components ^String out-dir]
  (let [base-ir (screen->ir tokens components grid-pool-base)
        ^bytes base-bytes (proto-ser/ir->bytes base-ir)
        base-hash (emit-proto/fnv1a-32-bytes base-bytes)
        target-uid (find-uid base-ir #(= "REPLACE ME" (:text %)))
        replace-op (fn [n] [{:kind :PATCH_OP_REPLACE_NODE
                             :target_uid target-uid
                             :node (grid-wall-node n)}])
        insert-op (fn [n] [{:kind :PATCH_OP_INSERT_NODE
                            :parent_uid (get-in base-ir [:root :uid])
                            :index 0
                            :node (grid-wall-node n)}])
        exhaust-wire (patch/patch-ir base-hash degenerate-target-hash
                                     (replace-op grid-pool-exhaust-cells))
        ok-wire (patch/patch-ir base-hash degenerate-target-hash
                                (replace-op grid-pool-ok-cells))
        insert-exhaust-wire (patch/patch-ir base-hash degenerate-target-hash
                                            (insert-op grid-pool-exhaust-cells))
        ;; The DISCRIMINATOR for the INSERT path. Two sites return -4 on that
        ;; path — patch_pools_low's fixed margin and the demand-aware check —
        ;; and an over-demand test asserting only `rc == -4` cannot say which
        ;; fired. A WITHIN-pool INSERT must SUCCEED, and only the demand-aware
        ;; check lets it: if the fixed margin were the site refusing, it would
        ;; refuse this one too. The REPLACE path already had its twin.
        insert-ok-wire (patch/patch-ir base-hash degenerate-target-hash
                                       (insert-op grid-pool-ok-cells))]
    ;; Single-file names (NOT the `.base.pb`/`.target.pb`/`.patch.pb` triple
    ;; suffixes) — the morph-parity dual oracle scans every `*.base.pb` as a
    ;; full triple, so a lone base under that suffix would demand a phantom
    ;; `.target.pb`.
    (proto-ser/write-bytes! base-bytes (str out-dir "/grid_pool_base.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes exhaust-wire)
                            (str out-dir "/grid_pool_over_demand.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes ok-wire)
                            (str out-dir "/grid_pool_within_pool.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes insert-exhaust-wire)
                            (str out-dir "/grid_pool_insert_over_demand.pb"))
    (proto-ser/write-bytes! (proto-ser/patch->bytes insert-ok-wire)
                            (str out-dir "/grid_pool_insert_within_pool.pb"))
    (println (str "  grid-pool/base + over_demand(" grid-pool-exhaust-cells
                  " cells) + within_pool(" grid-pool-ok-cells " cells)"
                  " + insert_over_demand(" grid-pool-exhaust-cells " cells)"
                  " + insert_within_pool(" grid-pool-ok-cells " cells)"))))
(m/=> emit-grid-pool-case!
      [:=>
       [:cat schema/tokens-schema [:map-of [:string {:min 1}] map?] [:string {:min 1}]]
       :nil])

(defn -main
  "Generate every morph fixture triple into --output."
  [& args]
  (let [arg-map (apply hash-map args)
        tokens-path (get arg-map "--tokens")
        out-dir (get arg-map "--output")]
    (when-not (and tokens-path out-dir)
      (binding [*out* *err*] (println "Usage: --tokens <path> --output <dir>"))
      (System/exit 1))
    (java.io.File/.mkdirs (io/file out-dir))
    (let [tokens (core/load-ui-defs tokens-path nil)
          components (component/load-components (:components tokens))
          cases (merge synthetic-cases combo-cases (real-corpus-cases))]
      (doseq [[nm pair] (sort-by key cases)] (emit-case! tokens components out-dir nm pair))
      (doseq [[nm chain] (sort-by key raw-chain-cases)]
        (emit-raw-case! tokens components out-dir nm chain))
      (doseq [[nm spec] (sort-by key degenerate-cases)]
        (emit-degenerate-case! tokens components out-dir nm spec))
      (emit-full-load-case! tokens components out-dir)
      (emit-grid-pool-case! tokens components out-dir)
      (println (str "Done. " (+ (count cases) (count raw-chain-cases))
                    " morph fixture pair(s) + " (count degenerate-cases)
                    " degenerate(s) + full-load dup-uid + grid-pool screens → "
                    out-dir)))))
(m/=> -main [:=> [:cat [:* [:string {:min 1}]]] :any])