(ns lvgl-codegen.emit-proto
  "Emit protobuf binary from expanded widget trees.
   Pure data->data transforms, with pronto serialization at the boundary."
  (:require [clojure.string :as str]
            [lvgl-codegen.generated.enums :as gen-enums]
            [lvgl-codegen.schema :as schema]
            [lvgl-codegen.style-props :as style-props]
            [malli.core :as m])
  (:import [com.google.protobuf ByteString]))

(set! *warn-on-reflection* true)

;; -- LVGL grid track coord encodings (lvgl/src/layouts/grid/lv_grid.h with
;; LV_COORD_MAX from lv_area.h): LV_COORD_MAX = (1 << 29) - 1;
;; LV_GRID_CONTENT = LV_COORD_MAX - 101; LV_GRID_FR(x) = LV_COORD_MAX - 100
;; + x. The renderer copies track values through unchanged and appends
;; LV_GRID_TEMPLATE_LAST itself.
(def ^:private lv-coord-max (dec (bit-shift-left 1 29)))

(def ^:private lv-grid-content (- lv-coord-max 101))

;; -- Node identity (tree patching) ------------------------------------------
;; Every node's identity is the root→node path of sibling-scoped segments:
;; the author :id when present, else <tag>#<k> where k is the ordinal among
;; UNKEYED same-tag siblings (keyed segments deliberately exclude position,
;; so adding an unkeyed sibling never shifts a keyed neighbor's identity).
;; uid = FNV-1a-32 of the canonical "/"-joined path string, carried as a
;; signed Java int (the proto uint32 two's-complement carrier).
(defn fnv1a-32-bytes
  "FNV-1a 32-bit hash of a byte array, returned as a SIGNED Java int (the
   two's-complement carrier of the unsigned 32-bit value — protobuf-java's
   uint32 representation). Also the .pb-content hash the patch wire's
   base_hash/target_hash carry (the C reconciler computes the same)."
  [^bytes bs]
  (loop [i 0
         h 2166136261]
    (if (< i (alength bs))
      (recur (inc i)
             (-> (bit-xor h (bit-and 255 (long (aget bs i))))
                 (unchecked-multiply 16777619)
                 (bit-and 0xFFFFFFFF)))
      (unchecked-int h))))

(defn fnv1a-32
  "FNV-1a-32 of a string's UTF-8 bytes (see fnv1a-32-bytes)."
  [^String s]
  (fnv1a-32-bytes (String/.getBytes s "UTF-8")))

(m/=> fnv1a-32-bytes [:=> [:cat bytes?] int?])

(m/=> fnv1a-32 [:=> [:cat [:string {:min 1}]] int?])

(defn- child-segments
  "Identity segments for one sibling list (see the identity note above)."
  [children]
  (loop [cs children
         counts {}
         out []]
    (if-let [c (first cs)]
      (if-let [id (:id c)]
        (recur (rest cs) counts (conj out id))
        (let [tag (name (:tag c))
              k (get counts tag 0)]
          (recur (rest cs) (assoc counts tag (inc k)) (conj out (str tag "#" k)))))
      out)))
(m/=> child-segments
      [:=> [:cat [:maybe [:sequential [:map [:tag keyword?]]]]]
       [:vector [:string {:min 1}]]])

(defn- assign-uids*
  "Recursive worker: assoc :uid onto node + descendants; `seen` is an atom
   of uid → path used for the screen-wide collision check."
  [node path seen]
  (let [uid (fnv1a-32 path)]
    (when (zero? uid)
      (throw (ex-info (str "uid hashed to 0 (the proto 'never assigned'"
                           " sentinel) for path "
                           path
                           " — rename an :id on this path")
                      {:path path})))
    (when-let [other (get @seen uid)]
      (throw (ex-info (str "uid hash collision between "
                           other
                           " and "
                           path
                           " — rename an :id on one of them")
                      {:uid uid :path path :collides-with other})))
    (swap! seen assoc uid path)
    (let [segs (child-segments (:children node))
          children (mapv (fn [c seg] (assign-uids* c (str path "/" seg) seen))
                         (:children node)
                         segs)]
      (cond-> (assoc node :uid uid) (seq children) (assoc :children children)))))
(m/=> assign-uids* [:=> [:cat [:map [:tag keyword?]] [:string {:min 1}] some?] :map])

(defn assign-uids
  "Assign a stable :uid to every node of an expanded widget tree —
   FNV-1a-32 of the root→node identity path, collision-checked screen-wide
   (a collision is a build error telling the author to rename an :id,
   never a runtime surprise)."
  [tree]
  (assign-uids* tree (first (child-segments [tree])) (atom {})))
(m/=> assign-uids [:=> [:cat [:map [:tag keyword?]]] :map])

(defn- encode-grid-track
  "Encode one authoring grid track (px int | :content | [:fr n]) to the LVGL
   coord int the renderer hands straight to lv_obj_set_grid_dsc_array."
  [track]
  (cond (pos-int? track) track
        (= :content track) lv-grid-content
        (and (vector? track) (= :fr (first track))) (+ lv-coord-max -100 (second track))
        :else (throw (ex-info (str "Unknown grid track form: "
                                   (pr-str track)
                                   " — expected a px int, :content, or [:fr n]")
                              {:track track}))))
(m/=> encode-grid-track [:=> [:cat schema/grid-track] pos-int?])

(defn- bitmask->int
  "OR a set of authoring keywords into an LVGL bitmask int via a
   factory-generated keyword→int map; throws naming the unknown keyword."
  [keyword->int field ks]
  (reduce (fn [acc kw]
            (bit-or acc
                    (or (keyword->int kw)
                        (throw (ex-info (str "Unknown " (name field)
                                             " keyword: " kw
                                             ". Valid: " (sort (keys keyword->int)))
                                        {:field field :value kw})))))
          0
          ks))
(m/=> bitmask->int [:=> [:cat [:map-of :keyword :int] :keyword [:set :keyword]] nat-int?])

(defn- resolve-scroll-dir
  "Resolve a :scroll-dir authoring keyword to its lv_dir_t value."
  [kw]
  (or (gen-enums/dir-keyword->int kw)
      (throw (ex-info (str "Unknown scroll-dir keyword: " kw
                           ". Valid: " (sort (keys gen-enums/dir-keyword->int)))
                      {:value kw}))))
(m/=> resolve-scroll-dir [:=> [:cat :keyword] nat-int?])

(def ^:private proxy-mode-keyword->member
  "Host-proxy mode authoring keywords → proto member spellings. Hand-rolled
   (not factory-generated): ProxyMode is OUR enum with no LVGL counterpart,
   so the factory's LVGL-header extraction can never produce it."
  {:static :PROXY_MODE_STATIC
   :draggable :PROXY_MODE_DRAGGABLE
   :resizable :PROXY_MODE_RESIZABLE
   :alignable :PROXY_MODE_ALIGNABLE})

(def ^:private props-enum-fields
  "Per `*_props` key: the enum-typed fields and the factory-generated
   authoring-keyword→proto-member map each translates through. The EDN layer
   authors keywords (`:symmetrical`); the proto-IR layer carries proto member
   spellings (`:BAR_MODE_SYMMETRICAL`); emit is the one-direction translation
   — proto spellings (and raw ints) are REJECTED on the authoring side."
  {:label_props {:long_mode gen-enums/label-long-mode-keyword->member}
   :slider_props {:mode gen-enums/bar-mode-keyword->member}
   :bar_props {:mode gen-enums/bar-mode-keyword->member}
   :arc_props {:mode gen-enums/arc-mode-keyword->member}
   :dropdown_props {:direction gen-enums/dir-keyword->member}
   :roller_props {:mode gen-enums/roller-mode-keyword->member}
   :scale_props {:mode gen-enums/scale-mode-keyword->member}
   :tabview_props {:tab_bar_position gen-enums/dir-keyword->member}
   :chart_props {:type gen-enums/chart-type-keyword->member}
   :host_proxy_props {:mode proxy-mode-keyword->member}})

(defn- resolve-props-enums
  "Translate the enum-typed fields of one `*_props` map from authoring
   keywords to proto member spellings (pass-through for props keys with no
   enum-typed fields). Throws on anything that is not an authoring keyword."
  [props-key props]
  (reduce-kv (fn [m field keyword->member]
               (if-some [v (get m field)]
                 (assoc m
                        field
                        (or (and (keyword? v) (keyword->member v))
                            (throw (ex-info (str "Unknown " props-key
                                                 " " field
                                                 " value: " (pr-str v)
                                                 ". Authoring keywords: "
                                                 (sort (keys keyword->member)))
                                            {:props props-key :field field :value v}))))
                 m))
             props
             (get props-enum-fields props-key {})))
(m/=> resolve-props-enums [:=> [:cat :keyword map?] :map])

(defn- resolve-chart-series
  "Translate one chart series' :axis authoring keyword to its proto member
   spelling (absent :axis = PRIMARY_Y, the proto default); proto spellings
   and raw ints are rejected like every props enum."
  [series]
  (if-some [axis (:axis series)]
    (assoc series
           :axis
           (or (and (keyword? axis) (gen-enums/chart-axis-keyword->member axis))
               (throw (ex-info (str "Unknown chart series :axis value: " (pr-str axis)
                                    ". Authoring keywords: "
                                    (sort (keys gen-enums/chart-axis-keyword->member)))
                               {:props :chart_props :field :axis :value axis}))))
    series))
(m/=> resolve-chart-series [:=> [:cat map?] :map])

(defn- resolve-chart-props
  "Translate a :chart_props authoring map to its proto-IR shape: :type via
   the factory keyword→member map, per-series :axis likewise, and the
   :div_lines [hdiv vdiv] tuple expanded to the wire's presence triple
   (has_div_lines + counts — 0 is a VALID explicit count, so presence is a
   flag, never inferred from the value). Authoring the wire presence keys
   directly is rejected: :div_lines is the one authoring surface."
  [props]
  (when (some #(contains? props %) [:has_div_lines :hdiv_count :vdiv_count])
    (throw (ex-info (str ":chart_props div lines are authored as :div_lines [hdiv vdiv]"
                         " — not via the wire keys :has_div_lines/:hdiv_count/"
                         ":vdiv_count")
                    {:props :chart_props :keys (keys props)})))
  (let [p (resolve-props-enums :chart_props props)
        p (if-some [dl (:div_lines p)]
            (do (when-not (and (vector? dl) (= 2 (count dl)) (every? nat-int? dl))
                  (throw (ex-info (str ":chart_props :div_lines must be"
                                       " [hdiv vdiv] (two non-negative ints), got: "
                                       (pr-str dl))
                                  {:props :chart_props :field :div_lines :value dl})))
                (-> p
                    (dissoc :div_lines)
                    (assoc :has_div_lines true
                           :hdiv_count (first dl)
                           :vdiv_count (second dl))))
            p)]
    (cond-> p (seq (:series p)) (update :series #(mapv resolve-chart-series %)))))
(m/=> resolve-chart-props [:=> [:cat map?] :map])

;; -- File-local argument schemas for arrow-spec tightening --
(def ^:private style-property-pair
  "A resolved style property entry [prop-key value].
   prop-key is always a keyword (dispatched in a `case`); value varies by
   property — color/font string, uint/int number, keyword (align), shadow map,
   or nil (a token that did not resolve, assoc'd as-is by expand->variants)."
  [:tuple keyword? [:maybe some?]])

(def ^:private expanded-style-group
  "An expanded style group consumed by emit-style-group: an LVGL state selector
   plus its composite variants (variant 0 = the base, hence at least one),
   each a {:props <prop-map>} wrapper.
   :props values may be nil (unresolved tokens), so the map is left open."
  [:map [:state-selector int?] [:variants [:sequential {:min 1} [:map [:props map?]]]]])

(def ^:private expanded-widget
  "An expanded widget tree node consumed by emit-widget. Always carries a :tag
   keyword; remaining keys (text, layout, style-groups, event, *_props, …) are
   optional and value-heterogeneous, so the map is open beyond :tag."
  [:map [:tag keyword?]])

(def ^:private subject-entry
  "A subject declaration entry [name decl] where decl is
   {:type :int/:string :default val}. :type drives SUBJECT_INT/SUBJECT_STRING."
  [:tuple keyword? [:map [:type {:optional true} [:enum :int :string :float]]]])

;; -- Pure data transforms (testable without pronto/protobuf-java) --
(defn- resolve-align
  "Resolve align value: keyword → int (via the factory-generated bindings),
   int passes through."
  [value]
  (if (keyword? value)
    (or (gen-enums/align-keyword->int value)
        (throw (ex-info (str "Unknown align keyword: " value
                             ". Valid: " (sort (keys gen-enums/align-keyword->int)))
                        {:value value})))
    value))

(defn hex->color
  "Parse hex color string to RGB map.
   \"#2D2D2D\" -> {:r 45 :g 45 :b 45}"
  [hex-str]
  (when-not (and (string? hex-str)
                 (= 7 (count hex-str))
                 (str/starts-with? hex-str "#")
                 (re-matches #"^#[0-9A-Fa-f]{6}$" hex-str))
    (throw (ex-info (str "Invalid hex color: " (pr-str hex-str) " — expected \"#RRGGBB\"")
                    {:input hex-str})))
  {:r (Integer/parseInt (subs hex-str 1 3) 16)
   :g (Integer/parseInt (subs hex-str 3 5) 16)
   :b (Integer/parseInt (subs hex-str 5 7) 16)})

(defn emit-style-property
  "Convert a resolved property [prop-key value] to proto StyleProperty map
   ({:type <StylePropertyType member> <value-slot> <value>}) — :type and the
   value slot come from the style-props registry; the WASM renderer checks
   the slot's oneof tag per property. All keys are snake_case to match
   protobuf field names."
  [[prop-key value]]
  (let [{:keys [proto slot xform]}
        (or (get style-props/props prop-key)
            (throw (ex-info (str "Unknown property type: " prop-key) {:prop prop-key})))
        v (case xform
            :align (resolve-align value)
            value)]
    (case slot
      :color_value {:type proto :color_value (hex->color v)}
      :string_value {:type proto :string_value v}
      :uint_value {:type proto :uint_value v}
      :int_value {:type proto :int_value v}
      :shadow_value {:type proto
                     :shadow_value {:width (:width v)
                                    :offset_x (:offset-x v 0)
                                    :offset_y (:offset-y v 0)
                                    :spread (:spread v 0)
                                    :opa (:opa v)}})))

(defn emit-style-group
  "Convert an expanded style group to the sparse wire StyleGroup map.
   Variant 0 (the base) is always emitted first; variant i (1-7) is
   emitted ONLY when its resolved prop set differs from the base — a
   present entry carries its COMPLETE prop set (full replacement, not a
   per-prop delta), an absent index renders exactly as the base. A
   fully-uniform group therefore collapses to one entry."
  [{:keys [state-selector variants]}]
  (let [base (first variants)
        sparse
        (into [{:variant_index 0 :properties (mapv emit-style-property (:props base))}]
              (keep-indexed (fn [i variant]
                              (when (not= (:props variant) (:props base))
                                {:variant_index (inc i)
                                 :properties (mapv emit-style-property (:props variant))}))
                            (rest variants)))]
    {:state_selector state-selector :variants sparse}))

(defn emit-cmd-spec
  "Convert an R5a CmdSpec IR map ({:command-id :root-template :patches [...]},
   built by uigen.cmd-spec) to the proto CmdSpec map: the deterministic cmd.Root
   template bytes + the fixed-width slot FieldPatch descriptors the renderer
   overwrites. The patch :kind is already the proto enum keyword; :byte-offset/
   :byte-width/:wire-scale carry verbatim."
  [{:keys [command-id root-template patches]}]
  {:command_id command-id
   ;; pronto's bytes field wants a ByteString (the proto wire carrier).
   :root_template (ByteString/copyFrom ^bytes root-template)
   :patches (mapv (fn [p]
                    {:byte_offset (:byte-offset p)
                     :byte_width (:byte-width p)
                     :kind (:kind p)
                     :wire_scale (:wire-scale p)})
                  patches)})
(m/=> emit-cmd-spec
      [:=>
       [:cat
        [:map [:command-id string?] [:root-template bytes?]
         [:patches [:sequential [:map-of :keyword :any]]]]] [:map-of :keyword :any]])

(defn emit-gesture-spec
  "Convert an R5a GestureSpec IR map ({:kind GestureKind :cmd CmdSpec}) to the
   proto GestureSpec map — the gesture-surface's per-gesture pre-encoded
   template, keyed by GestureKind."
  [{:keys [kind cmd]}]
  {:kind kind :cmd (emit-cmd-spec cmd)})
(m/=> emit-gesture-spec
      [:=> [:cat [:map [:kind :keyword] [:cmd [:map-of :keyword :any]]]]
       [:map-of :keyword :any]])

(defn- emit-event-binding
  "Convert an expanded event map to the proto EventBinding map: name, trigger,
   int/subject payload, and EITHER a single pre-encoded `:cmd` template OR a
   vector of FIXED `:cmd-by-value` templates the widget's int value index-selects
   among. The two cmd forms are mutually exclusive (a widget's value either
   patches ONE template or selects among fixed ones) — carrying both is a
   gen-time error, mirroring the schema-level and renderer-level guards."
  [event]
  (when (and (:cmd event) (:cmd-by-value event))
    (throw (ex-info (str "emit-event-binding: event `"
                         (name (:name event))
                         "` carries BOTH :cmd and :cmd-by-value (mutually exclusive)")
                    {:event (:name event)})))
  (cond-> {:name (name (:name event))}
    (:trigger event) (assoc :trigger
                            (case (:trigger event)
                              :value-changed :TRIGGER_VALUE_CHANGED
                              :long-press :TRIGGER_LONG_PRESSED
                              :TRIGGER_CLICKED))
    (:int-value event) (assoc :int_value (:int-value event))
    (:include-value event) (assoc :include_widget_value true)
    (:set event) (assoc :set_subject (name (:set event)))
    (contains? event :to) (assoc :set_value (:to event))
    (:toggle event) (assoc :toggle true :set_subject (name (:toggle event)))
    (:notify-host event) (assoc :notify_host true)
    ;; R5a: a pre-encoded cmd.* template + slot patches (uigen.cmd-spec) for a
    ;; value-widget device command; OR a vector of FIXED templates the widget's
    ;; int value index-selects among (bool-set/on-off/enum egress).
    (:cmd event) (assoc :cmd (emit-cmd-spec (:cmd event)))
    (:cmd-by-value event) (assoc :cmd_by_value (mapv emit-cmd-spec (:cmd-by-value event)))))
(m/=> emit-event-binding [:=> [:cat [:map [:name keyword?]]] :map])

(defn emit-widget
  "Convert an expanded widget node to proto WidgetNode map."
  [{:keys [tag text event layout style-groups children] :as node}]
  (cond-> {:type (case tag
                   :lv_obj :WIDGET_OBJ
                   :lv_button :WIDGET_BUTTON
                   :lv_label :WIDGET_LABEL
                   :lv_slider :WIDGET_SLIDER
                   :lv_image :WIDGET_IMAGE
                   :lv_arc :WIDGET_ARC
                   :lv_bar :WIDGET_BAR
                   :lv_switch :WIDGET_SWITCH
                   :lv_checkbox :WIDGET_CHECKBOX
                   :lv_dropdown :WIDGET_DROPDOWN
                   :lv_roller :WIDGET_ROLLER
                   :lv_textarea :WIDGET_TEXTAREA
                   :lv_spinbox :WIDGET_SPINBOX
                   :lv_spinner :WIDGET_SPINNER
                   :lv_led :WIDGET_LED
                   :lv_line :WIDGET_LINE
                   :lv_scale :WIDGET_SCALE
                   :lv_buttonmatrix :WIDGET_BUTTONMATRIX
                   :lv_table :WIDGET_TABLE
                   :lv_tabview :WIDGET_TABVIEW
                   :lv_chart :WIDGET_CHART
                   :lv_host_proxy :WIDGET_HOST_PROXY
                   (throw (ex-info
                           (str "Unknown widget type: :" (name tag)
                                ". Valid LVGL tags: "
                                (str/join ", " (sort (map name schema/valid-widget-tags))))
                           {:tag tag})))}
    (:uid node) (assoc :uid (:uid node))
    (:x node) (assoc :x (:x node))
    (:y node) (assoc :y (:y node))
    text (assoc :text text)
    (:bind node) (assoc :bindings
                        (into {} (map (fn [[k v]] [(name k) (name v)])) (:bind node)))
    (:bind-fmt node) (assoc :bind_formats
                            (into {} (map (fn [[k v]] [(name k) v])) (:bind-fmt node)))
    ;; Cross-cutting LVGL surface (demo-parity): keyword sets are OR'd to
    ;; the LVGL flag/state bitmasks here (factory-generated maps); the wire
    ;; ints are direct-cast renderer-side.
    (:flags node)
    (assoc :obj_flags (bitmask->int gen-enums/obj-flag-keyword->int :flags (:flags node)))
    (:flags-clear node)
    (assoc :obj_flags_clear
           (bitmask->int gen-enums/obj-flag-keyword->int :flags-clear (:flags-clear node)))
    (:states node)
    (assoc :states (bitmask->int gen-enums/state-keyword->int :states (:states node)))
    (:scroll-dir node) (assoc :scroll_dir (resolve-scroll-dir (:scroll-dir node)))
    (seq (:grid-cols node)) (assoc :grid_col_dsc (mapv encode-grid-track (:grid-cols node)))
    (seq (:grid-rows node)) (assoc :grid_row_dsc (mapv encode-grid-track (:grid-rows node)))
    (:bare node) (assoc :bare true)
    (:in-tab-bar node) (assoc :in_tab_bar true)
    ;; Reactive CHECKED binding — EQ-only authoring surface ({:subject
    ;; :value}); the wire reuses VisibilityBinding with compare default EQ.
    (:checked-when node) (assoc :checked_when
                                {:subject (name (get-in node [:checked-when :subject]))
                                 :ref_value (get-in node [:checked-when :value])})
    ;; Reactive ENABLED binding — the checked_when sibling with inverted polarity
    ;; (DISABLED while the comparison does not hold). Same EQ-only authoring
    ;; surface, same VisibilityBinding wire shape.
    (:enabled-when node) (assoc :enabled_when
                                {:subject (name (get-in node [:enabled-when :subject]))
                                 :ref_value (get-in node [:enabled-when :value])})
    ;; Value-conditional text-color binding — EQ-only authoring surface
    ;; ({:subject :value :color}); the color is an already-resolved "#RRGGBB"
    ;; (expand-screen baked the design token). The wire nests the comparison
    ;; under `when` (VisibilityBinding) alongside the Color.
    (:color-when node) (assoc :color_when
                              {:when {:subject (name (get-in node [:color-when :subject]))
                                      :ref_value (get-in node [:color-when :value])}
                               :color (hex->color (get-in node [:color-when :color]))})
    (:show-when node)
    (assoc :visibility
           (let [sw (:show-when node)
                 known {:eq :COMPARE_EQ
                        :neq :COMPARE_NOT_EQ
                        :gt :COMPARE_GT
                        :gte :COMPARE_GTE
                        :lt :COMPARE_LT
                        :lte :COMPARE_LTE}
                 cmp (select-keys sw (keys known))]
             ;; Fail loud on an unknown / missing / ambiguous comparator —
             ;; the old `:else :COMPARE_EQ` fallthrough shipped a wrong
             ;; VisibilityBinding (EQ ref 0) for a show-when the schema
             ;; never sanctioned. Exactly one of :eq/:neq/:gt/:gte/:lt/:lte.
             (when-not (= 1 (count cmp))
               (throw (ex-info (str "lvgl-codegen.emit-proto: show-when needs exactly one "
                                    "known comparator " (pr-str (vec (keys known)))
                                    " — got " (pr-str (dissoc sw :subject)))
                               {:show-when sw :known (vec (keys known))})))
             (let [[ck ref-value] (first cmp)
                   op (known ck)]
               (cond-> {:subject (name (:subject sw)) :ref_value ref-value}
                 (not= op :COMPARE_EQ) (assoc :compare op)))))
    event (assoc :event (emit-event-binding event))
    layout (assoc :layout
                  (cond-> {:flow (case layout
                                   :flex-row :FLEX_FLOW_ROW
                                   :flex-col :FLEX_FLOW_COLUMN
                                   :flex-row-wrap :FLEX_FLOW_ROW_WRAP
                                   :flex-row-reverse :FLEX_FLOW_ROW_REVERSE
                                   :flex-row-wrap-reverse :FLEX_FLOW_ROW_WRAP_REVERSE
                                   :flex-col-wrap :FLEX_FLOW_COLUMN_WRAP
                                   :flex-col-reverse :FLEX_FLOW_COLUMN_REVERSE
                                   :flex-col-wrap-reverse :FLEX_FLOW_COLUMN_WRAP_REVERSE
                                   :FLEX_FLOW_NONE)}
                    (:main-place node) (assoc :main_place (:main-place node))
                    (:cross-place node) (assoc :cross_place (:cross-place node))
                    (:track-place node) (assoc :track_place (:track-place node))))
    (seq style-groups) (assoc :style_groups (mapv emit-style-group style-groups))
    ;; Widget-specific props — pass through, with enum-typed fields
    ;; translated from authoring keywords to proto member spellings
    ;; (`props-enum-fields`).
    (:slider_props node) (assoc :slider_props
                                (resolve-props-enums :slider_props (:slider_props node)))
    (:arc_props node) (assoc :arc_props (resolve-props-enums :arc_props (:arc_props node)))
    (:bar_props node) (assoc :bar_props (resolve-props-enums :bar_props (:bar_props node)))
    (:switch_props node) (assoc :switch_props (:switch_props node))
    (:checkbox_props node) (assoc :checkbox_props (:checkbox_props node))
    (:dropdown_props node)
    (assoc :dropdown_props (resolve-props-enums :dropdown_props (:dropdown_props node)))
    (:roller_props node) (assoc :roller_props
                                (resolve-props-enums :roller_props (:roller_props node)))
    (:textarea_props node) (assoc :textarea_props (:textarea_props node))
    (:spinbox_props node) (assoc :spinbox_props (:spinbox_props node))
    (:spinner_props node) (assoc :spinner_props (:spinner_props node))
    (:led_props node) (assoc :led_props (:led_props node))
    (:line_props node) (assoc :line_props (:line_props node))
    (:scale_props node) (assoc :scale_props
                               (resolve-props-enums :scale_props (:scale_props node)))
    (:buttonmatrix_props node) (assoc :buttonmatrix_props (:buttonmatrix_props node))
    (:table_props node) (assoc :table_props (:table_props node))
    (:tabview_props node) (assoc :tabview_props
                                 (resolve-props-enums :tabview_props (:tabview_props node)))
    (:chart_props node) (assoc :chart_props (resolve-chart-props (:chart_props node)))
    (:host_proxy_props node) (assoc :host_proxy_props
                                    (resolve-props-enums :host_proxy_props
                                                         (:host_proxy_props node)))
    (:label_props node) (assoc :label_props
                               (resolve-props-enums :label_props (:label_props node)))
    (:image_props node) (assoc :image_props (:image_props node))
    ;; R5a: gesture-surface pre-encoded gesture→cmd templates (uigen.cmd-spec),
    ;; one GestureSpec per device gesture with a fixed-width slot.
    (seq (:gestures node)) (assoc :gestures (mapv emit-gesture-spec (:gestures node)))
    (seq children) (assoc :children (mapv emit-widget children))))

(defn- emit-subject
  "Convert a [name {:type :int/:string :default val}] entry to SubjectDeclaration."
  [[k v]]
  (let [sname (name k)
        stype (case (:type v)
                :int :SUBJECT_INT
                :string :SUBJECT_STRING
                :SUBJECT_INT)]
    (cond-> {:name sname :type stype}
      (and (= :int (:type v)) (contains? v :default)) (assoc :int_initial (:default v))
      (and (= :string (:type v)) (contains? v :default)) (assoc :string_initial
                                                                (:default v)))))

(defn emit-screen
  "Convert an expanded screen to proto Screen map (pure data, no protobuf).
   Assigns the stable :uid identity to every node first (assign-uids), so
   the emitted IR — the differ's input — always carries authoritative
   uids."
  [expanded-screen]
  (let [subjects (get expanded-screen :subjects)]
    (cond-> {:root (emit-widget (assign-uids (:tree expanded-screen)))}
      (seq subjects) (assoc :subjects (mapv emit-subject subjects)))))

;; -- Function schema registrations --
(m/=> resolve-align [:=> [:cat [:or keyword? int?]] int?])

(m/=> hex->color [:=> [:cat string?] [:map [:r int?] [:g int?] [:b int?]]])

(m/=> emit-style-property [:=> [:cat style-property-pair] :map])

(m/=> emit-style-group [:=> [:cat expanded-style-group] :map])

(m/=> emit-widget [:=> [:cat expanded-widget] :map])

;; emit-screen tolerates :subjects as either a map or a seq of [name decl]
;; tuples across callers (its body iterates `(for [[k v] subjects] …)`), so the
;; screen arg is map? — the proto encoding validates the real structure.
(m/=> emit-screen [:=> [:cat map?] :map])

(m/=> emit-subject [:=> [:cat subject-entry] :map])