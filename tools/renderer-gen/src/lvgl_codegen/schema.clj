(ns lvgl-codegen.schema
  "Malli schemas for design tokens and screen definitions."
  (:require [clojure.string :as str]
            [lvgl-codegen.generated.enums :as gen-enums]
            [lvgl-codegen.style-props :as style-props]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- Authoring keyword enums DERIVED from the factory-generated bindings
;; (`lvgl-codegen.generated.enums`, regenerated from LVGL's own headers via
;; protogen tools/renderer-gen) — one home per keyword set, never a hand-copy.
(def ^:private obj-flag-enum
  "LV_OBJ_FLAG_* authoring keywords (`:flags` / `:flags-clear` set members)."
  (into [:enum] (sort (keys gen-enums/obj-flag-keyword->int))))

(def ^:private state-enum
  "LV_STATE_* authoring keywords (`:states` set members)."
  (into [:enum] (sort (keys gen-enums/state-keyword->int))))

(def ^:private scroll-dir-enum
  "`:scroll-dir` authoring keywords — the dir keyword set minus `:none`:
   the renderer skips `scroll_dir == 0`, so `:none` would be a silent no-op
   (omit the key instead)."
  (into [:enum] (sort (remove #{:none} (keys gen-enums/dir-keyword->int)))))

(def ^:private cell-align-enum
  "Grid CELL alignment keywords — the `lv_obj_set_grid_cell`-valid subset of
   grid-align (START/CENTER/END/STRETCH per lv_grid.h; the spread aligns are
   track-level only)."
  (into [:enum]
        (sort (filter #{:start :center :end :stretch}
                      (keys gen-enums/grid-align-keyword->int)))))

(def grid-track
  "One authoring grid track: a px int, `:content` (size to children), or
   `[:fr n]` (proportional free space). Encoded by emit-proto to the LVGL
   coord ints from lv_grid.h; the px bound keeps plain tracks clear of the
   special-coord range."
  [:or [:int {:min 1 :max 10000}] [:= :content] [:tuple [:= :fr] [:int {:min 1 :max 16}]]])

;; Whitelist of valid LVGL widget tags
(def valid-widget-tags
  #{:lv_obj :lv_label :lv_button :lv_image :lv_slider :lv_arc :lv_bar :lv_switch
    :lv_checkbox :lv_spinner :lv_led :lv_scale :lv_spinbox :lv_dropdown :lv_roller
    :lv_textarea :lv_line :lv_buttonmatrix :lv_table :lv_tabview :lv_chart :lv_host_proxy
    :lv_target_overlay})

(def stateful-widget-tags
  "Widget tags carrying RUNTIME user state a tree patch must not churn
   (typed text, spinbox value, host-proxy placement, chart frame). The
   type#ordinal identity fallback breaks on sibling reorder and silently
   loses that state, so a stateful node without an author :id is a HARD
   codegen error (operator decision in
   docs/lvgl-factory/10-TREE-PATCH-DESIGN.md)."
  #{:lv_textarea :lv_spinbox :lv_host_proxy :lv_chart})

(def ^:private content-sizeable-leaf-tags
  "Childless widgets that COLLAPSE to ~0px when content-sized: none creates a
   child lv_obj (the parts-widgets draw main/knob/indicator/ticks from style;
   lv_buttonmatrix draws its buttons from a map; lv_chart draws series from data
   linked-lists) AND none has an `LV_EVENT_GET_SELF_SIZE` handler, so
   `w-content`/`h-content` installs LV_SIZE_CONTENT which resolves to 0 + padding
   and the widget silently never renders. This is EVERY such leaf in
   valid-widget-tags: lv_bar, lv_slider (a bar subclass), lv_led, lv_arc,
   lv_switch, lv_spinner (an arc subclass), lv_scale, lv_buttonmatrix, lv_chart.
   A non-content width_def default (lv_led's fixed px, lv_chart's LV_PCT(100))
   does not save them — content-sizing overrides it. Content-sizing any of these
   is a hard codegen error; give it an explicit size. (A parent grid `:stretch`
   cell would override the collapse, but that is a separate mechanism.)

   NOT guarded, in three classes: (a) SELF-SIZING leaves that answer
   GET_SELF_SIZE from their own content — lv_image (source dims), lv_line (points
   bbox), lv_label (text), lv_dropdown / lv_roller (option list), lv_table
   (cells), lv_checkbox (text + box); (b) CONTAINER widgets that hold authored
   child nodes and measure them — lv_obj, lv_button, lv_tabview, lv_textarea /
   lv_spinbox (an internal label child); (c) lv_host_proxy (the video-proxy
   node). (A sourceless image or pointless line collapses too, but that is a
   missing-CONTENT fault, a different check.)"
  #{:lv_bar :lv_slider :lv_led :lv_arc :lv_switch :lv_spinner :lv_scale
    :lv_buttonmatrix :lv_chart})

(def author-id
  "Author :id — sibling-scoped identity segment for tree patching."
  [:string {:min 1 :max 63}])

;; Primitive schemas — plain vars (no global Malli registry; named primitives
;; are plain schemas referenced directly, the way uigen's schemas are).
(def ne-string
  "A non-empty string."
  [:string {:min 1 :error/message "should be a non-empty string"}])

(def hex-color
  "A \"#RRGGBB\" hex colour."
  [:re {:error/message "should be a \"#RRGGBB\" hex colour"} #"^#[0-9A-Fa-f]{6}$"])

(def shadow-def
  [:map [:width nat-int?] [:opa [:int {:min 0 :max 255}]] [:offset-x {:optional true} int?]
   [:offset-y {:optional true} int?] [:spread {:optional true} nat-int?]])

(defn- resolved-token-entry
  "One design-tokens manifest entry schema for a value-shape: closed
   {:kind <kind> :dark <shape> :light <shape>} — both modes concrete
   (protogen's resolver emits them; equal when mode-invariant)."
  [kind value-shape]
  [:map {:closed true} [:kind [:= kind]] [:dark value-shape] [:light value-shape]])
(m/=> resolved-token-entry [:=> [:cat :keyword some?] [:vector some?]])

(def resolved-token
  "A pinned design-tokens manifest entry, dispatched on its :kind — the
   closed kind set mirrors the style-props registry's :resolve kinds
   (minus :size, which is literal-only and never a token)."
  [:multi {:dispatch :kind} [:color (resolved-token-entry :color hex-color)]
   [:font (resolved-token-entry :font [:string {:min 1}])]
   [:spacing (resolved-token-entry :spacing nat-int?)]
   [:radius (resolved-token-entry :radius nat-int?)]
   [:opacity (resolved-token-entry :opacity [:int {:min 0 :max 255}])]
   [:border-width (resolved-token-entry :border-width nat-int?)]
   [:shadow (resolved-token-entry :shadow shadow-def)]])

(def tokens-schema
  "The pipeline's UI-definition map: the pinned design-tokens manifest
   under :tokens (protogen owns tokens.edn + the resolver; this repo
   consumes the resolved projection) merged with the authored
   components.edn keys."
  [:map [:tokens [:map-of keyword? resolved-token]]
   [:class-defs {:optional true} [:map-of keyword? string?]]
   [:components {:optional true} [:map-of keyword? [:map-of :keyword some?]]]])

;; -- :style schema, DERIVED from the style-props registry (one home for
;; the prop roster + value shapes; schema, emit, and the class DSL all
;; read the same entries).
(def ^:private style-prop-rows
  "Closed [prop {:optional true} value-shape] rows, one per registry prop.
   The literal shape follows the proto value slot; a keyword arm is added
   only where the registry declares token resolution (:resolve) or an
   emit-side translation (:xform, e.g. :align's keyword names)."
  (mapv (fn [[prop {:keys [slot] resolve-kind :resolve xform :xform}]]
          (let [base (case slot
                       :color_value hex-color
                       :string_value [:string {:min 1}]
                       :uint_value nat-int?
                       :int_value int?
                       :shadow_value shadow-def)]
            [prop {:optional true} (if (or resolve-kind xform) [:or base keyword?] base)]))
        (sort-by key style-props/props)))

(defn- closed-style-map
  "A closed :style map schema over the registry prop rows plus the given
   extra (nesting) rows."
  [extra-rows]
  (into [:map {:closed true}] (concat style-prop-rows extra-rows)))

(def style-schema
  "The :style authoring surface: registry props at the top level, plus one
   nesting level per axis — a state key (:pressed/:focused/:disabled) or
   breakpoint key (:sm/:md/:lg/:xl) scoping a nested prop map, combinable
   once in either order (mirrors the class DSL's `md:`/`pressed:`
   prefixes; desugared by expand/style-map->tokens into the same token
   stream). A widget-part key (:indicator/:knob/:items/… — see
   style-props/part-selector) scopes a LEAF prop map to that LVGL part
   (top level only; parts don't combine with states/breakpoints)."
  (let [state-keys (sort (keys style-props/state-selector))
        bp-keys (sort (keys style-props/bp-min-index))
        part-keys (sort (keys style-props/part-selector))
        leaf (closed-style-map nil)]
    (closed-style-map
     (concat (for [s state-keys]
               [s {:optional true}
                (closed-style-map (for [b bp-keys] [b {:optional true} leaf]))])
             (for [b bp-keys]
               [b {:optional true}
                (closed-style-map (for [s state-keys] [s {:optional true} leaf]))])
             (for [p part-keys] [p {:optional true} leaf])))))

(m/=> closed-style-map [:=> [:cat [:maybe [:sequential [:vector some?]]]] [:vector some?]])

;; R5a cmd-out pre-encode (CmdSpec IR — uigen.cmd-spec). The deep WIRE shape
;; (byte-offset/width/kind/wire-scale, the cmd.Root template bytes) is enforced
;; at emit by the proto-IR backstop (proto-schema/cmd-spec); this authoring-EDN
;; layer pins only the carried key set, like the *_props passthroughs.
(def cmd-spec-edn
  "An R5a CmdSpec IR map: a source command-id, the pre-encoded cmd.Root template
   bytes, and the fixed-width slot patch descriptors."
  [:map {:closed true} [:command-id [:string {:min 1}]] [:root-template bytes?]
   [:patches [:vector [:map-of keyword? some?]]]])

(def gesture-spec-edn
  "An R5a GestureSpec IR map: a ui_ast GestureKind keyword, its CmdSpec, and
   optionally which SIGN of that gesture's step the entry answers (a ui_ast
   GestureDeltaSign keyword; absent means ANY — every step). Two entries share a
   :kind only as the POSITIVE/NEGATIVE pair; the renderer refuses any other
   repeat at load."
  [:map {:closed true} [:kind keyword?]
   [:delta-sign {:optional true} keyword?] [:cmd cmd-spec-edn]])

;; Widget / screen schemas (recursive)
(def event-def
  "Event definition. All fields optional — a bare {} means 'send event name on click'.
   :trigger — :value-changed or :long-press (default: clicked)
   :int-value — static int payload
   :include-value — inject widget value as int_value at fire time
   :set/:to — mutate a local subject to a fixed value
   :toggle — flip a subject 0↔1
   :notify-host — also send to host when mutating subject
   :cmd — R5a pre-encoded cmd.* template (uigen.cmd-spec) for a value command
   :cmd-by-value — R5a pre-encoded FIXED cmd.* templates the widget's int value
     index-selects among (bool-set/on-off/enum); mutually exclusive with :cmd"
  [:and
   [:map [:trigger {:optional true} [:enum :value-changed :long-press]]
    [:int-value {:optional true} int?] [:include-value {:optional true} boolean?]
    [:set {:optional true} keyword?] [:to {:optional true} int?]
    [:toggle {:optional true} keyword?] [:notify-host {:optional true} boolean?]
    [:cmd {:optional true} cmd-spec-edn]
    [:cmd-by-value {:optional true} [:vector {:min 1 :max 16} cmd-spec-edn]]]
   [:fn {:error/message ":set and :toggle are mutually exclusive — use one or the other"}
    (fn [m] (not (and (contains? m :set) (contains? m :toggle))))]
   ;; :to NAMES NO SUBJECT ON ITS OWN, and without this clause `{:to 5}` validated
   ;; clean and produced a control that silently did something else. The emitter
   ;; writes set_value=5 with an EMPTY set_subject; renderer.c then skips the
   ;; mutation outright (`if (data->set_subject[0] != '\0')`) and the very next
   ;; branch (`set_subject[0] == '\0' || notify_host`) reclassifies the press as a
   ;; HOST event. So the authored intent — mutate this subject to this value — is
   ;; dropped and replaced by a host notification, with no error at either end.
   ;;
   ;; The docstring above has always read ":set/:to" as a PAIR. This makes the
   ;; pairing checkable instead of conventional.
   [:fn {:error/message ":to needs :set — a value with no subject to write it to is dropped by the renderer and the press degrades to a host event"}
    (fn [m] (not (and (contains? m :to) (not (contains? m :set)))))]
   [:fn
    {:error/message
     ":cmd and :cmd-by-value are mutually exclusive — a widget value either patches ONE template or index-selects among fixed ones"}
    (fn [m] (not (and (contains? m :cmd) (contains? m :cmd-by-value))))]])

(def widget-node
  [:schema
   {:registry
    {::widget-node
     [:map {:closed true}
      [:tag
       [:fn
        {:error/message (str "Invalid widget tag — lv_* tags must be one of: "
                             (str/join ", " (sort (map name valid-widget-tags)))
                             ". Non-lv_ names are component references.")}
        #(or (valid-widget-tags %) (not (str/starts-with? (name %) "lv_")))]]
      ;; Sibling-scoped identity segment (tree patching):
      ;; full identity is the root→node path of segments
      ;; (:id when present, else type#ordinal). Duplicate
      ;; :id among siblings is rejected by
      ;; validate-screen-semantics.
      [:id {:optional true} author-id]
      ;; Class DSL: one string of tokens, or the vector
      ;; form (one token-run per element) — joined into
      ;; the canonical string at the pipeline boundary
      ;; (core/join-class-vectors).
      [:class {:optional true}
       [:or [:string {:min 1}] [:vector {:min 1} [:string {:min 1}]]]]
      [:children {:optional true} [:vector [:ref ::widget-node]]] [:x {:optional true} int?]
      [:y {:optional true} int?] [:text {:optional true} string?]
      [:bind {:optional true} [:map-of keyword? keyword?]]
      [:bind-fmt {:optional true} [:map-of keyword? string?]]
      [:event {:optional true} keyword?] [:style {:optional true} style-schema]
      ;; LVGL flag/state keyword sets (OR'd to the
      ;; LV_OBJ_FLAG_* / lv_state_t bitmasks at emit via the
      ;; factory-generated maps) + scroll/grid/bare surface.
      [:flags {:optional true} [:set {:min 1} obj-flag-enum]]
      [:flags-clear {:optional true} [:set {:min 1} obj-flag-enum]]
      [:states {:optional true} [:set {:min 1} state-enum]]
      [:scroll-dir {:optional true} scroll-dir-enum]
      [:grid-cols {:optional true} [:vector {:min 1 :max 12} grid-track]]
      [:grid-rows {:optional true} [:vector {:min 1 :max 12} grid-track]]
      ;; Grid placement sugar — desugared by expand into the
      ;; grid-cell-* style props.
      [:cell {:optional true}
       [:map {:closed true} [:col [:int {:min 0 :max 11}]] [:row [:int {:min 0 :max 11}]]
        [:col-span {:optional true} [:int {:min 1 :max 12}]]
        [:row-span {:optional true} [:int {:min 1 :max 12}]]
        [:x-align {:optional true} cell-align-enum]
        [:y-align {:optional true} cell-align-enum]]] [:bare {:optional true} [:= true]]
      ;; Tab-bar slot selector — valid only on a direct
      ;; child of an :lv_tabview node (cross-checked by
      ;; validate-screen-semantics).
      [:in-tab-bar {:optional true} [:= true]]
      ;; The parsed layout form (flow/placements) — the
      ;; class DSL produces it, and generated fixtures
      ;; (coverage matrix) author it directly.
      [:layout {:optional true}
       [:enum :flex-row :flex-col :flex-row-wrap :flex-row-reverse :flex-row-wrap-reverse
        :flex-col-wrap :flex-col-reverse :flex-col-wrap-reverse]]
      [:main-place {:optional true} keyword?] [:cross-place {:optional true} keyword?]
      [:track-place {:optional true} keyword?]
      ;; Component usage arguments (non-lv_ tags)
      [:props {:optional true} [:map-of keyword? some?]]
      ;; Widget-specific props. The *_props family is a
      ;; deliberate proto-IR PASSTHROUGH: keys and values
      ;; are the WIRE spellings (snake_case fields, with
      ;; enum fields authored as keywords that emit
      ;; translates) — unlike the kebab node-level keys
      ;; above. The EDN layer keeps them open (map?); the
      ;; per-key shapes are enforced AT EMIT by the
      ;; proto-IR backstop (proto-ser/validate-ir!): a
      ;; deep typo like :slider_props {:valeu 50} fails
      ;; the codegen loudly with the humanized Malli
      ;; explain path ({:root {:slider_props {:valeu
      ;; ["disallowed key"]}}}), in WIRE spelling — not
      ;; at this authoring schema.
      [:obj_props {:optional true} map?] [:button_props {:optional true} map?]
      [:label_props {:optional true} map?] [:slider_props {:optional true} map?]
      [:image_props {:optional true} map?] [:arc_props {:optional true} map?]
      [:bar_props {:optional true} map?] [:switch_props {:optional true} map?]
      [:checkbox_props {:optional true} map?] [:dropdown_props {:optional true} map?]
      [:roller_props {:optional true} map?] [:textarea_props {:optional true} map?]
      [:spinbox_props {:optional true} map?] [:spinner_props {:optional true} map?]
      [:led_props {:optional true} map?] [:line_props {:optional true} map?]
      [:scale_props {:optional true} map?] [:buttonmatrix_props {:optional true} map?]
      [:table_props {:optional true} map?] [:tabview_props {:optional true} map?]
      [:chart_props {:optional true} map?] [:host_proxy_props {:optional true} map?]
      [:target_overlay_props {:optional true} map?]
      ;; R5a: pre-encoded gesture→cmd templates on the
      ;; gesture-surface host-proxy (one GestureSpec per
      ;; device gesture with a fixed-width slot).
      [:gestures {:optional true} [:vector gesture-spec-edn]]
      ;; Reactive LV_STATE_CHECKED binding (EQ): checked
      ;; while subject == :value, cleared otherwise. The
      ;; reactive sibling of :states — authoring both
      ;; CHECKED sources on one node is rejected by
      ;; validate-screen-semantics.
      [:checked-when {:optional true}
       [:map {:closed true} [:subject keyword?] [:value int?]]]
      ;; Reactive ENABLED binding (EQ): enabled while subject == :value,
      ;; DISABLED otherwise (checked-when's inverted-polarity sibling).
      [:enabled-when {:optional true}
       [:map {:closed true} [:subject keyword?] [:value int?]]]
      ;; Value-conditional text-color binding (EQ): LV_PART_MAIN text is
      ;; recolored to the :color design token while subject == :value. The
      ;; token bakes to one mode-invariant Color at expand.
      [:color-when {:optional true}
       [:map {:closed true} [:subject keyword?] [:value int?] [:color keyword?]]]
      [:show-when {:optional true}
       [:and
        [:map [:subject keyword?] [:eq {:optional true} int?] [:neq {:optional true} int?]
         [:gt {:optional true} int?] [:gte {:optional true} int?]
         [:lt {:optional true} int?] [:lte {:optional true} int?]]
        [:fn
         {:error/message
          "show-when requires exactly one comparison operator (:eq, :neq, :gt, :gte, :lt, :lte)"}
         (fn [m] (= 1 (count (select-keys m [:eq :neq :gt :gte :lt :lte]))))]]]]}}
   ::widget-node])

(def screen-schema
  [:map [:type [:= :screen]]
   [:subjects
    [:map-of keyword?
     [:and
      [:map [:type [:enum :int :string]]
       [:default {:optional true}
        [:or
         {:tight-schema/exempt
          {:grounds :tag-discriminated
           :rationale
           "Arms are discriminated by the sibling :type enum (cross-checked by the :and :fn below); an empty string is a valid :string subject default, so the [:string {:min 1}] floor would reject legitimate authoring."
           :proof-expires-when
           "Subject defaults become tagged values, or empty-string defaults are banned."}}
         int? string?]]]
      [:fn
       {:error/message
        "subject :default must match :type (:int -> integer, :string -> string)"}
       (fn [{subject-type :type subject-default :default}]
         (or (nil? subject-default)
             (case subject-type
               :int (int? subject-default)
               :string (string? subject-default))))]]]]
   [:events [:map-of keyword? event-def]] [:tree widget-node]])

;; -- Cross-field semantic validation --
(defn- walk-widgets
  "Walk a widget tree, calling f on each widget node."
  [widget f]
  (when (map? widget) (f widget) (doseq [child (:children widget)] (walk-widgets child f))))

(defn- check-tabview-node!
  "Tabview cross-field contract, collected into the errors atom:
   tab_names must zip 1:1 with the content (non-:in-tab-bar) children, and
   :in-tab-bar is only meaningful directly under an :lv_tabview node."
  [widget errors]
  (when (= :lv_tabview (:tag widget))
    (let [tab-names (get-in widget [:tabview_props :tab_names])
          content-count (count (remove :in-tab-bar (:children widget)))]
      (when-not (vector? tab-names)
        (swap! errors conj {:type :tabview-missing-tab-names :widget (:tag widget)}))
      (when (and (vector? tab-names) (not= (count tab-names) content-count))
        (swap! errors conj
               {:type :tabview-tab-count-mismatch
                :tab-names tab-names
                :content-children content-count}))
      (when-let [active (get-in widget [:tabview_props :active_index])]
        (when (and (vector? tab-names) (>= active (count tab-names)))
          (swap! errors conj
                 {:type :tabview-active-index-out-of-range
                  :active-index active
                  :tab-names tab-names})))))
  (doseq [child (:children widget)]
    (when (and (:in-tab-bar child) (not= :lv_tabview (:tag widget)))
      (swap! errors conj
             {:type :in-tab-bar-outside-tabview :parent (:tag widget) :child (:tag child)}))))

(def reactive-state-bindings
  "Each REACTIVE state binding paired with the create-time `:states` bit it
  competes with, as `[binding-key state-bit error-type]`.

  A TABLE, NOT TWO CLAUSES, and that is the fix rather than an implementation
  detail. The `:checked-when` conflict was checked and `:enabled-when` was not,
  even though renderer.c documents the latter as \"the reactive sibling of
  checked_when with INVERTED polarity\" — two clauses hand-written from one contract
  drift, and the missing one is invisible because nothing enumerates the pair set.
  Keyed on the abstraction, a new `*-when` binding added without an entry here is a
  visible omission instead of a silent one.

  WHY THE COMBINATION IS REJECTED RATHER THAN ORDERED. Both sources write the same
  state bit and the reactive one WINS by construction: create-time states are applied
  while the node is built (`lv_obj_add_state(obj, node->states)`), whereas
  `apply_checked_when` / `apply_enabled_when` run in a DEFERRED post-subjects pass, so
  the observer's first evaluation immediately overwrites whatever the author asked
  for. Silently. There is no ordering an author could rely on, so the pairing is an
  authoring error and not a precedence question."
  [[:checked-when :checked :checked-when-states-conflict]
   [:enabled-when :disabled :enabled-when-states-conflict]])

(defn- check-host-proxy-node!
  "Host-proxy cross-field contract, collected into the errors atom:
   an :lv_host_proxy node carries :host_proxy_props with a non-empty
   :proxy_id (the stable host-side join key); :host_proxy_props on any
   other tag is author error; a :checked-when paired with a :states set
   containing :checked is two CHECKED sources on one node (the wire's
   create-time states vs the reactive binding — reject the combination)."
  [widget errors]
  (let [proxy? (= :lv_host_proxy (:tag widget))
        props (:host_proxy_props widget)]
    (when (and proxy? (not (seq (:proxy_id props))))
      (swap! errors conj {:type :host-proxy-missing-proxy-id :widget (:tag widget)}))
    (when (and props (not proxy?))
      (swap! errors conj
             {:type :host-proxy-props-outside-host-proxy :widget (:tag widget)}))
    (doseq [[binding-key state-bit err-type] reactive-state-bindings
            :when (and (binding-key widget) (contains? (:states widget) state-bit))]
      (swap! errors conj {:type err-type :widget (:tag widget)
                          :binding binding-key :state state-bit}))))

(defn- check-identity-node!
  "Tree-patch identity contract, collected into the errors atom:
   (a) a stateful widget (stateful-widget-tags) without an author :id is a
   HARD error — its positional identity fallback breaks on sibling reorder
   and silently loses user runtime state; (b) duplicate :id among one
   node's children breaks sibling-scoped identity."
  [widget errors]
  (when (and (contains? stateful-widget-tags (:tag widget)) (not (:id widget)))
    (swap! errors conj {:type :stateful-widget-missing-id :widget (:tag widget)}))
  (let [dup-ids (->> (:children widget)
                     (keep :id)
                     frequencies
                     (filter #(> (val %) 1))
                     (mapv key))]
    (when (seq dup-ids)
      (swap! errors conj
             {:type :duplicate-sibling-id :parent (:tag widget) :ids dup-ids}))))

(defn- check-conditional-binding!
  "Reference + subject-type checks for one value-conditional binding map
   (:show-when / :checked-when / :enabled-when / :color-when), collected into
   the errors atom. The undeclared check mirrors :bind; the type check exists
   because these bindings ride the wire as an int32 ref_value compared via
   lv_subject_get_int — against a :string subject the comparison reads the wrong
   union member and NEVER matches, silently. Only :int subjects are comparable."
  [widget cond-bind subject-decls undeclared-type mismatch-type errors]
  (when cond-bind
    (let [sk (:subject cond-bind)
          decl (get subject-decls sk)]
      (cond (nil? decl) (swap! errors conj
                               {:type undeclared-type :ref sk :widget (:tag widget)})
            (not= :int (:type decl)) (swap! errors conj
                                            {:type mismatch-type
                                             :subject sk
                                             :declared-type (:type decl)
                                             :widget (:tag widget)})))))

(defn- content-sizing-dimensions
  "The dimensions (:width/:height) a class string content-sizes, honoring bp/state
   prefixes (a `md:w-content` still collapses a leaf at that breakpoint). Empty
   when the class content-sizes nothing (or is nil)."
  [class-str]
  (into #{}
        (keep (fn [tok]
                (case (last (str/split tok #":"))
                  "w-content" :width
                  "h-content" :height
                  nil)))
        (when class-str (str/split (str/trim class-str) #"\s+"))))

(defn- check-leaf-sizing!
  "Leaf-widget sizing contract, collected into the errors atom: a childless leaf
   (content-sizeable-leaf-tags) has nothing to measure, so `w-content`/`h-content`
   collapses it to ~0px and it never renders (audit #12). Fail-fast at codegen —
   an author must give the leaf an explicit size, never content-size it."
  [widget errors]
  (when (contains? content-sizeable-leaf-tags (:tag widget))
    (doseq [dim (content-sizing-dimensions (:class widget))]
      (swap! errors conj
             {:type :leaf-content-sizing :widget (:tag widget) :dimension dim}))))

(defn- supported-bind-keys
  "Binding keys the renderer dispatches for the given widget tag — :mode is
   the host-proxy mode-subject binding, meaningless elsewhere."
  [tag]
  (if (= :lv_host_proxy tag) #{:text :value :checked :mode} #{:text :value :checked}))

(defn validate-screen-semantics
  "Cross-field validation: references must point to declared names.
   Returns nil on success, vector of errors on failure."
  [screen]
  (let [subject-decls (:subjects screen)
        subjects (set (keys subject-decls))
        events (set (keys (:events screen)))
        errors (atom [])]
    ;; Screen-level: every event's :set/:toggle must name a declared
    ;; subject — an undeclared one is a dead control on the device
    ;; (find_subject returns NULL and the press does nothing).
    (doseq [[evt-name evt] (:events screen)
            :let [target (or (:set evt) (:toggle evt))]
            :when (and target (not (contains? subjects target)))]
      (swap! errors conj {:type :undeclared-event-subject :event evt-name :ref target}))
    (walk-widgets
     (:tree screen)
     (fn [widget]
       (check-tabview-node! widget errors)
       (check-host-proxy-node! widget errors)
       (check-identity-node! widget errors)
       (check-leaf-sizing! widget errors)
       (doseq [[k v] (:bind widget)]
         (when-not (contains? (supported-bind-keys (:tag widget)) k)
           (swap! errors conj
                  {:type :unsupported-bind-key
                   :key k
                   :widget (:tag widget)
                   :supported (supported-bind-keys (:tag widget))}))
         (when-not (contains? subjects v)
           (swap! errors conj {:type :undeclared-subject :ref v :widget (:tag widget)})))
       (doseq [[k _fmt] (:bind-fmt widget)]
         (when-not (contains? (:bind widget) k)
           (swap! errors conj {:type :orphan-bind-fmt :key k :widget (:tag widget)})))
       (when-let [evt (:event widget)]
         (when-not (contains? events evt)
           (swap! errors conj {:type :undeclared-event :ref evt :widget (:tag widget)})))
       (check-conditional-binding! widget
                                   (:show-when widget)
                                   subject-decls
                                   :undeclared-show-when-subject
                                   :show-when-subject-not-int
                                   errors)
       (check-conditional-binding! widget
                                   (:checked-when widget)
                                   subject-decls
                                   :undeclared-checked-when-subject
                                   :checked-when-subject-not-int
                                   errors)
       (check-conditional-binding! widget
                                   (:enabled-when widget)
                                   subject-decls
                                   :undeclared-enabled-when-subject
                                   :enabled-when-subject-not-int
                                   errors)
       (check-conditional-binding! widget
                                   (:color-when widget)
                                   subject-decls
                                   :undeclared-color-when-subject
                                   :color-when-subject-not-int
                                   errors)))
    (when (seq @errors) @errors)))

(defn validate-tokens
  "Validate tokens map against tokens-schema. Returns nil on success, explanation on failure."
  [tokens]
  (when-not (m/validate tokens-schema tokens) (m/explain tokens-schema tokens)))

(def components-file-schema
  "Shape of edn/components.edn — class macros + component definitions,
   evicted from tokens.edn (tokens carry layer 1-2 design VALUES only)."
  [:map {:closed true} [:class-defs {:optional true} [:map-of keyword? [:string {:min 1}]]]
   [:components {:optional true}
    [:map-of keyword? [:map [:tree [:map-of :keyword some?]]]]]])

(defn validate-components-file
  "Validate a components.edn map. Returns nil on success, explanation on
   failure."
  [comps]
  (when-not (m/validate components-file-schema comps)
    (m/explain components-file-schema comps)))

(defn validate-screen
  "Validate screen map against screen-schema. Returns nil on success, explanation on failure."
  [screen]
  (when-not (m/validate screen-schema screen) (m/explain screen-schema screen)))

;; -- Function schema registrations --
(m/=> walk-widgets [:=> [:cat map? ifn?] :nil])

(m/=> check-tabview-node! [:=> [:cat [:map [:tag {:optional true} keyword?]] some?] :nil])

(m/=> check-host-proxy-node!
      [:=> [:cat [:map [:tag {:optional true} keyword?]] some?] [:maybe [:vector map?]]])

(m/=> check-identity-node!
      [:=> [:cat [:map [:tag {:optional true} keyword?]] some?] [:maybe [:vector map?]]])

(m/=> content-sizing-dimensions [:=> [:cat [:maybe string?]] [:set :keyword]])

(m/=> check-leaf-sizing! [:=> [:cat [:map [:tag {:optional true} keyword?]] some?] :nil])

(m/=> check-conditional-binding!
      [:=>
       [:cat [:map [:tag {:optional true} keyword?]] [:maybe [:map [:subject keyword?]]]
        [:maybe [:map-of keyword? [:map [:type {:optional true} keyword?]]]] :keyword
        :keyword some?] [:maybe [:vector map?]]])

(m/=> supported-bind-keys [:=> [:cat [:maybe :keyword]] [:set :keyword]])

(m/=> validate-screen-semantics [:=> [:cat map?] [:maybe [:sequential :any]]])

(m/=> validate-tokens [:=> [:cat map?] [:maybe map?]])

(m/=> validate-screen [:=> [:cat map?] [:maybe map?]])

(m/=> validate-components-file [:=> [:cat map?] [:maybe map?]])