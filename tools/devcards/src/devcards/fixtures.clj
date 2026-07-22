(ns devcards.fixtures
  "The PUBLIC devcard fixture builder (T2.2) — turns the corpus spec
   (`corpus/spec.edn`, the T2.1 machine-readable card inventory) into
   `ui.UiAst` Screen protobuf BYTES via protogen's OWN generated Java
   bindings. No pronto, no private authoring layer (@-macros / tokens /
   class-defs are BANNED here — the T2.1 unstyled law): every card carries
   raw renderer-public vocabulary only, and everything unset falls through
   to the loaded wasm's theme, which is the object under test.

   The builder laws (spec header `:builder-laws`, all enforced here):
   - Root nodes set explicit full-canvas PROP_WIDTH/PROP_HEIGHT (an unstyled
     root defaults to 160x160 and clips almost anything — the F1 GOTCHA:
     `controls_load_ui` builds the root WidgetNode as a CHILD of the active
     screen, so it does not inherit the screen size).
   - The root also sets PROP_PAD_ALL 0 — harness geometry, never theme
     styling: the stock card PAD_DEF=24 on the root would clip full-bleed
     cells (table 480x240, tabview 460x320, scale vertical 56x360).
   - WIDGET_BUTTON requires an explicit child WIDGET_LABEL (renderer.c
     builds an EMPTY lv_button; text is a separate child) — asserted, not
     assumed.
   - State is applied ONLY via the wire `node.states` bitmask (the card's
     authored `:states-bits`), except where a state is a widget-prop bit by
     contract (switch/checkbox `:checked` ride their props messages; those
     cards carry the residual bits only — already encoded in the spec).
   - Widget slugs derive from the WidgetType enum (`:type` keywords are
     enum names verbatim).

   Flex on a node is expressed through the WidgetNode `Layout` message,
   NEVER the PROP_FLEX_FLOW style slot, for two verified renderer.c facts:
   (1) the style route indexes `flex_flow_lut` by the PROTO FlexFlow wire
   number and SKIPS value 0, and (2) `lv_style_set_flex_flow` alone never
   sets LV_STYLE_LAYOUT, so flex would not activate; only the Layout route
   (`apply_node_layout` -> `lv_obj_set_flex_flow`) resolves LV_LAYOUT_FLEX.
   A card `:flex-flow` prop (LVGL vocabulary per the spec: 0 = ROW) is
   therefore translated to the Layout message.

   Every shape here is CLOSED and hand-validated (the tool is
   dependency-lean — no malli): an unknown card key, props key, enum
   keyword, or a props-message/widget-type mismatch throws with a
   diagnostic payload naming the exact card. A spec card the builder cannot
   express must FAIL, never silently skip.

   Requires the `ui.UiAst` classes on the classpath: deps.edn `:bindings`
   (protogen's own output/java compiled by `tools/compile-bindings.sh`).
   `build.buf/protovalidate` is a class-init dependency:
   UiAst.java references `build.buf.validate.ValidateProto` while building
   its file descriptor, before any validation is ever invoked."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [ui UiAst$ArcProps UiAst$BarMode UiAst$BarProps UiAst$ButtonMatrixProps
            UiAst$ChartAxis UiAst$ChartProps UiAst$ChartSeries UiAst$ChartType
            UiAst$CheckboxProps UiAst$Color UiAst$DropdownProps UiAst$FlexFlow
            UiAst$HostProxyProps UiAst$ImageProps UiAst$LabelLongMode UiAst$LabelProps
            UiAst$Layout UiAst$LedProps UiAst$LineProps UiAst$Point UiAst$ProxyMode
            UiAst$RollerProps UiAst$ScaleMode UiAst$ScaleProps UiAst$ScaleSection
            UiAst$Screen UiAst$SliderProps UiAst$SpinboxProps UiAst$SpinnerProps
            UiAst$StyleGroup UiAst$StyleProperty UiAst$StylePropertyType UiAst$StyleVariant
            UiAst$SwitchProps UiAst$TableProps UiAst$TabviewProps UiAst$TextareaProps
            UiAst$WidgetNode UiAst$WidgetNode$Builder UiAst$WidgetType]))

(set! *warn-on-reflection* true)

(def spec-path
  "The corpus spec (tool-relative). Protogen home: tools/devcards/corpus/."
  "corpus/spec.edn")

(def pb-out-dir
  "Where `-main` writes the built .pb files (gitignored transient output;
   the protogen home commits fixtures deliberately — that call is L-phase)."
  "out/pbs")

;; ── Closed-shape guards ─────────────────────────────────────────────────
(defn- assert-closed
  "Throw unless every key of `m` is in the `allowed` set — the closed-shape
   guard every card / props / child map passes before any byte is built."
  [ctx allowed m]
  (when-not (map? m) (throw (ex-info (str ctx ": expected a map") {:ctx ctx :got m})))
  (let [extra (remove allowed (keys m))]
    (when (seq extra)
      (throw (ex-info (str ctx ": unknown keys (closed shape)")
                      {:ctx ctx :unknown (vec extra) :allowed (vec (sort allowed))})))))

(defn- enum-of
  "Closed keyword->enum lookup; throws naming the context on a miss."
  [table kw ctx]
  (or (get table kw)
      (throw (ex-info (str ctx ": unknown enum keyword")
                      {:ctx ctx :keyword kw :known (vec (sort (keys table)))}))))

;; ── Wire scalars ────────────────────────────────────────────────────────
(defn- ->color
  "A Color message from either a `{:r :g :b}` map (led/section/series
   colors) or a `#RRGGBB` hex string (the raw text/border color props)."
  ^UiAst$Color [v]
  (let [{:keys [r g b]} (cond (map? v) (do (assert-closed "color map" #{:r :g :b} v) v)
                              (and (string? v) (re-matches #"#[0-9a-fA-F]{6}" v))
                              (let [n (Long/parseLong (subs ^String v 1) 16)]
                                {:r (bit-and (bit-shift-right n 16) 0xFF)
                                 :g (bit-and (bit-shift-right n 8) 0xFF)
                                 :b (bit-and n 0xFF)})
                              :else (throw (ex-info "color: expected {:r :g :b} or #RRGGBB"
                                                    {:got v})))]
    (-> (UiAst$Color/newBuilder)
        (.setR (int r))
        (.setG (int g))
        (.setB (int b))
        .build)))

;; ── Raw style props (renderer-public vocabulary; no authoring registry) ─
(def ^:private style-slot-order
  "Card `:props` style keys -> [StylePropertyType slot, value flavor], in
   EMISSION order — a fixed order keeps the built bytes a deterministic
   function of the card (golden-hash prerequisite). Slots + flavors mirror
   the conventions manifest `:style-prop-slots` table verbatim."
  [[:w "PROP_WIDTH" :uint] [:h "PROP_HEIGHT" :uint] [:x "PROP_X" :int] [:y "PROP_Y" :int]
   [:pad-all "PROP_PAD_ALL" :uint] [:radius "PROP_RADIUS" :uint]
   [:border-width "PROP_BORDER_WIDTH" :uint] [:border-color "PROP_BORDER_COLOR" :color]
   [:text-color "PROP_TEXT_COLOR" :color] [:text-font "PROP_TEXT_FONT" :string]])

(defn- style-prop
  "One StyleProperty with the slot's declared value flavor."
  ^UiAst$StyleProperty [^String slot flavor v]
  (let [b (-> (UiAst$StyleProperty/newBuilder)
              (.setType (UiAst$StylePropertyType/valueOf slot)))]
    (case flavor
      :uint (.setUintValue b (int v))
      :int (.setIntValue b (int v))
      :string (.setStringValue b ^String v)
      :color (.setColorValue b (->color v)))
    (.build b)))

(defn- style-group
  "The ONE style_groups entry an unstyled card node carries: MAIN|DEFAULT
   (state_selector 0), base variant 0, the node's raw px/color/font props.
   No per-state variant is ever added — state-dependent appearance comes
   exclusively from the wire `states` bitmask, resolved by the loaded
   wasm's own theme (the object under test)."
  ^UiAst$StyleGroup [style-props]
  (let [vb (-> (UiAst$StyleVariant/newBuilder)
               (.setVariantIndex 0))]
    (doseq [[k slot flavor] style-slot-order
            :when (contains? style-props k)]
      (.addProperties vb (style-prop slot flavor (get style-props k))))
    (-> (UiAst$StyleGroup/newBuilder)
        (.setStateSelector 0)
        (.addVariants vb)
        .build)))

;; ── Enum tables (proto enum names, closed) ──────────────────────────────
(def ^:private label-long-modes
  {:wrap UiAst$LabelLongMode/LABEL_LONG_MODE_WRAP
   :dots UiAst$LabelLongMode/LABEL_LONG_MODE_DOTS
   :scroll UiAst$LabelLongMode/LABEL_LONG_MODE_SCROLL
   :scroll-circular UiAst$LabelLongMode/LABEL_LONG_MODE_SCROLL_CIRCULAR
   :clip UiAst$LabelLongMode/LABEL_LONG_MODE_CLIP})

(def ^:private bar-modes
  {:normal UiAst$BarMode/BAR_MODE_NORMAL
   :symmetrical UiAst$BarMode/BAR_MODE_SYMMETRICAL
   :range UiAst$BarMode/BAR_MODE_RANGE})

(def ^:private scale-modes
  {:horizontal-top UiAst$ScaleMode/SCALE_MODE_HORIZONTAL_TOP
   :horizontal-bottom UiAst$ScaleMode/SCALE_MODE_HORIZONTAL_BOTTOM
   :vertical-left UiAst$ScaleMode/SCALE_MODE_VERTICAL_LEFT
   :vertical-right UiAst$ScaleMode/SCALE_MODE_VERTICAL_RIGHT
   :round-inner UiAst$ScaleMode/SCALE_MODE_ROUND_INNER
   :round-outer UiAst$ScaleMode/SCALE_MODE_ROUND_OUTER})

(def ^:private chart-types
  {:line UiAst$ChartType/CHART_TYPE_LINE
   :curve UiAst$ChartType/CHART_TYPE_CURVE
   :bar UiAst$ChartType/CHART_TYPE_BAR
   :stacked UiAst$ChartType/CHART_TYPE_STACKED
   :scatter UiAst$ChartType/CHART_TYPE_SCATTER})

(def ^:private chart-axes
  {:primary-y UiAst$ChartAxis/CHART_AXIS_PRIMARY_Y
   :secondary-y UiAst$ChartAxis/CHART_AXIS_SECONDARY_Y
   :primary-x UiAst$ChartAxis/CHART_AXIS_PRIMARY_X
   :secondary-x UiAst$ChartAxis/CHART_AXIS_SECONDARY_X})

(def ^:private proxy-modes
  {:static UiAst$ProxyMode/PROXY_MODE_STATIC
   :draggable UiAst$ProxyMode/PROXY_MODE_DRAGGABLE
   :resizable UiAst$ProxyMode/PROXY_MODE_RESIZABLE
   :alignable UiAst$ProxyMode/PROXY_MODE_ALIGNABLE})

(def ^:private flex-flows
  "Layout `:flow` keyword -> proto FlexFlow (the Layout-message route —
   see the ns docstring for why flex never rides the style slot)."
  {:row UiAst$FlexFlow/FLEX_FLOW_ROW
   :column UiAst$FlexFlow/FLEX_FLOW_COLUMN
   :row-wrap UiAst$FlexFlow/FLEX_FLOW_ROW_WRAP
   :column-wrap UiAst$FlexFlow/FLEX_FLOW_COLUMN_WRAP})

(def ^:private lvgl-flex-flow->kw
  "The spec's `:flex-flow` prop values are LVGL `lv_flex_flow_t` vocabulary
   (spec note: \"flex-flow 0 = LV_FLEX_FLOW_ROW\"; LVGL: ROW=0, COLUMN=1).
   Only the unambiguous base values are mapped; a wrap/reverse composite
   would be a new deliberate entry."
  {0 :row 1 :column})

(def ^:private obj-flags
  "LV_OBJ_FLAG_* authoring keywords -> LVGL header values (mirrors the
   codegen seam's `obj-flag-keyword->int`; only the flags the corpus uses).
   `obj_flags_clear` is direct-cast by renderer.c (`lv_obj_remove_flag`)."
  {:scrollable 16})

;; ── Widget-props messages (closed per-message field sets) ───────────────
;; Each builder accepts exactly the fields the corpus spec uses — a spec
;; key outside the set throws, so an unsupported field STOPS its card
;; loudly instead of silently dropping wire content. Extend deliberately.
(defn- label-props
  ^UiAst$LabelProps [m]
  (assert-closed "label_props" #{:long_mode} m)
  (let [b (UiAst$LabelProps/newBuilder)]
    (when-some [v (:long_mode m)]
      (.setLongMode b (enum-of label-long-modes v "label_props :long_mode")))
    (.build b)))

(defn- slider-props
  ^UiAst$SliderProps [m]
  (assert-closed "slider_props" #{:min_value :max_value :value} m)
  (let [b (UiAst$SliderProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (.build b)))

(defn- image-props
  ^UiAst$ImageProps [m]
  (assert-closed "image_props" #{:src} m)
  (let [b (UiAst$ImageProps/newBuilder)]
    (when-some [v (:src m)] (.setSrc b ^String v))
    (.build b)))

(defn- arc-props
  ^UiAst$ArcProps [m]
  (assert-closed "arc_props"
                 #{:start_angle :end_angle :bg_start_angle :bg_end_angle :min_value
                   :max_value :value}
                 m)
  (let [b (UiAst$ArcProps/newBuilder)]
    (when-some [v (:start_angle m)] (.setStartAngle b (int v)))
    (when-some [v (:end_angle m)] (.setEndAngle b (int v)))
    (when-some [v (:bg_start_angle m)] (.setBgStartAngle b (int v)))
    (when-some [v (:bg_end_angle m)] (.setBgEndAngle b (int v)))
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (.build b)))

(defn- bar-props
  ^UiAst$BarProps [m]
  (assert-closed "bar_props" #{:min_value :max_value :value :start_value :mode} m)
  (let [b (UiAst$BarProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (when-some [v (:start_value m)] (.setStartValue b (int v)))
    (when-some [v (:mode m)] (.setMode b (enum-of bar-modes v "bar_props :mode")))
    (.build b)))

(defn- switch-props
  ^UiAst$SwitchProps [m]
  (assert-closed "switch_props" #{:checked} m)
  (let [b (UiAst$SwitchProps/newBuilder)]
    (when (contains? m :checked) (.setChecked b (boolean (:checked m))))
    (.build b)))

(defn- checkbox-props
  ^UiAst$CheckboxProps [m]
  (assert-closed "checkbox_props" #{:checked} m)
  (let [b (UiAst$CheckboxProps/newBuilder)]
    (when (contains? m :checked) (.setChecked b (boolean (:checked m))))
    (.build b)))

(defn- dropdown-props
  ^UiAst$DropdownProps [m]
  (assert-closed "dropdown_props" #{:options :selected} m)
  (let [b (UiAst$DropdownProps/newBuilder)]
    (when-some [v (:options m)] (.setOptions b ^String v))
    (when-some [v (:selected m)] (.setSelected b (int v)))
    (.build b)))

(defn- roller-props
  ^UiAst$RollerProps [m]
  (assert-closed "roller_props" #{:options :selected :visible_row_count} m)
  (let [b (UiAst$RollerProps/newBuilder)]
    (when-some [v (:options m)] (.setOptions b ^String v))
    (when-some [v (:selected m)] (.setSelected b (int v)))
    (when-some [v (:visible_row_count m)] (.setVisibleRowCount b (int v)))
    (.build b)))

(defn- textarea-props
  ^UiAst$TextareaProps [m]
  (assert-closed "textarea_props" #{:placeholder :one_line} m)
  (let [b (UiAst$TextareaProps/newBuilder)]
    (when-some [v (:placeholder m)] (.setPlaceholder b ^String v))
    (when (contains? m :one_line) (.setOneLine b (boolean (:one_line m))))
    (.build b)))

(defn- spinbox-props
  ^UiAst$SpinboxProps [m]
  (assert-closed "spinbox_props" #{:min_value :max_value :value :step :digit_count} m)
  (let [b (UiAst$SpinboxProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (when-some [v (:step m)] (.setStep b (int v)))
    (when-some [v (:digit_count m)] (.setDigitCount b (int v)))
    (.build b)))

(defn- spinner-props
  ^UiAst$SpinnerProps [m]
  (assert-closed "spinner_props" #{:spin_time :arc_length} m)
  (let [b (UiAst$SpinnerProps/newBuilder)]
    (when-some [v (:spin_time m)] (.setSpinTime b (int v)))
    (when-some [v (:arc_length m)] (.setArcLength b (int v)))
    (.build b)))

(defn- led-props
  ^UiAst$LedProps [m]
  (assert-closed "led_props" #{:color :brightness} m)
  (let [b (UiAst$LedProps/newBuilder)]
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:brightness m)] (.setBrightness b (int v)))
    (.build b)))

(defn- line-props
  ^UiAst$LineProps [m]
  (assert-closed "line_props" #{:points} m)
  (let [b (UiAst$LineProps/newBuilder)]
    (doseq [p (:points m)]
      (assert-closed "line point" #{:x :y} p)
      (.addPoints b
                  (-> (UiAst$Point/newBuilder)
                      (.setX (int (:x p)))
                      (.setY (int (:y p)))
                      .build)))
    (.build b)))

(defn- scale-section
  ^UiAst$ScaleSection [m]
  (assert-closed "scale section" #{:range_min :range_max :color :width} m)
  (let [b (UiAst$ScaleSection/newBuilder)]
    (when-some [v (:range_min m)] (.setRangeMin b (int v)))
    (when-some [v (:range_max m)] (.setRangeMax b (int v)))
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:width m)] (.setWidth b (int v)))
    (.build b)))

(defn- scale-props
  ^UiAst$ScaleProps [m]
  (assert-closed "scale_props"
                 #{:mode :total_tick_count :major_tick_every :label_show :min_value
                   :max_value :angle_range :text_src :sections}
                 m)
  (let [b (UiAst$ScaleProps/newBuilder)]
    (when-some [v (:mode m)] (.setMode b (enum-of scale-modes v "scale_props :mode")))
    (when-some [v (:total_tick_count m)] (.setTotalTickCount b (int v)))
    (when-some [v (:major_tick_every m)] (.setMajorTickEvery b (int v)))
    (when (contains? m :label_show) (.setLabelShow b (boolean (:label_show m))))
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:angle_range m)] (.setAngleRange b (int v)))
    (when-some [v (:text_src m)] (.setTextSrc b ^String v))
    (doseq [s (:sections m)] (.addSections b (scale-section s)))
    (.build b)))

(defn- buttonmatrix-props
  ^UiAst$ButtonMatrixProps [m]
  (assert-closed "buttonmatrix_props" #{:map_str} m)
  (let [b (UiAst$ButtonMatrixProps/newBuilder)]
    (when-some [v (:map_str m)] (.setMapStr b ^String v))
    (.build b)))

(defn- table-props
  ^UiAst$TableProps [m]
  (assert-closed "table_props" #{:row_count :column_count} m)
  (let [b (UiAst$TableProps/newBuilder)]
    (when-some [v (:row_count m)] (.setRowCount b (int v)))
    (when-some [v (:column_count m)] (.setColumnCount b (int v)))
    (.build b)))

(defn- tabview-props
  ^UiAst$TabviewProps [m]
  (assert-closed "tabview_props" #{:tab_names :tab_bar_size :active_index} m)
  (let [b (UiAst$TabviewProps/newBuilder)]
    (doseq [n (:tab_names m)] (.addTabNames b ^String n))
    (when-some [v (:tab_bar_size m)] (.setTabBarSize b (int v)))
    (when-some [v (:active_index m)] (.setActiveIndex b (int v)))
    (.build b)))

(defn- chart-series
  ^UiAst$ChartSeries [m]
  (assert-closed "chart series" #{:color :axis :values} m)
  (let [b (UiAst$ChartSeries/newBuilder)]
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:axis m)] (.setAxis b (enum-of chart-axes v "chart series :axis")))
    (doseq [v (:values m)] (.addValues b (int v)))
    (.build b)))

(defn- chart-props
  ^UiAst$ChartProps [m]
  (assert-closed "chart_props" #{:series :type :point_count :div_lines} m)
  (let [b (UiAst$ChartProps/newBuilder)]
    (when-some [v (:type m)] (.setType b (enum-of chart-types v "chart_props :type")))
    (when-some [v (:point_count m)] (.setPointCount b (int v)))
    (when-some [dl (:div_lines m)]
      ;; [hdiv vdiv] — presence rides has_div_lines (0 is a VALID count;
      ;; renderer.c: lv_chart_set_div_line_count(obj, hdiv, vdiv)).
      (when-not (and (sequential? dl) (= 2 (count dl)))
        (throw (ex-info "chart_props :div_lines must be [hdiv vdiv]" {:got dl})))
      (.setHasDivLines b true)
      (.setHdivCount b (int (first dl)))
      (.setVdivCount b (int (second dl))))
    (doseq [s (:series m)] (.addSeries b (chart-series s)))
    (.build b)))

(defn- host-proxy-props
  ^UiAst$HostProxyProps [m]
  (assert-closed "host_proxy_props"
                 #{:proxy_id :mode :min_w :min_h :max_w :max_h :handle_size :z}
                 m)
  (let [b (UiAst$HostProxyProps/newBuilder)]
    (when-some [v (:proxy_id m)] (.setProxyId b ^String v))
    (when-some [v (:mode m)] (.setMode b (enum-of proxy-modes v "host_proxy_props :mode")))
    (when-some [v (:min_w m)] (.setMinW b (int v)))
    (when-some [v (:min_h m)] (.setMinH b (int v)))
    (when-some [v (:max_w m)] (.setMaxW b (int v)))
    (when-some [v (:max_h m)] (.setMaxH b (int v)))
    (when-some [v (:handle_size m)] (.setHandleSize b (int v)))
    (when-some [v (:z m)] (.setZ b (int v)))
    (.build b)))

(def ^:private widget-props-keys
  "The `:props` keys that are WidgetNode widget-props oneof messages
   (field names verbatim from ui_ast.proto)."
  #{:label_props :slider_props :image_props :arc_props :bar_props :switch_props
    :checkbox_props :dropdown_props :roller_props :textarea_props :spinbox_props
    :spinner_props :led_props :line_props :scale_props :buttonmatrix_props :table_props
    :tabview_props :chart_props :host_proxy_props})

(def ^:private type->props-key
  "Widget type -> its ONE legal widget-props oneof key. WIDGET_OBJ and
   WIDGET_BUTTON carry none (ObjProps/ButtonProps are empty messages the
   corpus never sets)."
  {:WIDGET_LABEL :label_props
   :WIDGET_SLIDER :slider_props
   :WIDGET_IMAGE :image_props
   :WIDGET_ARC :arc_props
   :WIDGET_BAR :bar_props
   :WIDGET_SWITCH :switch_props
   :WIDGET_CHECKBOX :checkbox_props
   :WIDGET_DROPDOWN :dropdown_props
   :WIDGET_ROLLER :roller_props
   :WIDGET_TEXTAREA :textarea_props
   :WIDGET_SPINBOX :spinbox_props
   :WIDGET_SPINNER :spinner_props
   :WIDGET_LED :led_props
   :WIDGET_LINE :line_props
   :WIDGET_SCALE :scale_props
   :WIDGET_BUTTONMATRIX :buttonmatrix_props
   :WIDGET_TABLE :table_props
   :WIDGET_TABVIEW :tabview_props
   :WIDGET_CHART :chart_props
   :WIDGET_HOST_PROXY :host_proxy_props})

(defn- set-widget-props!
  "Set the node builder's widget-props oneof arm for `props-key`."
  [^UiAst$WidgetNode$Builder b props-key m]
  (case props-key
    :label_props (.setLabelProps b (label-props m))
    :slider_props (.setSliderProps b (slider-props m))
    :image_props (.setImageProps b (image-props m))
    :arc_props (.setArcProps b (arc-props m))
    :bar_props (.setBarProps b (bar-props m))
    :switch_props (.setSwitchProps b (switch-props m))
    :checkbox_props (.setCheckboxProps b (checkbox-props m))
    :dropdown_props (.setDropdownProps b (dropdown-props m))
    :roller_props (.setRollerProps b (roller-props m))
    :textarea_props (.setTextareaProps b (textarea-props m))
    :spinbox_props (.setSpinboxProps b (spinbox-props m))
    :spinner_props (.setSpinnerProps b (spinner-props m))
    :led_props (.setLedProps b (led-props m))
    :line_props (.setLineProps b (line-props m))
    :scale_props (.setScaleProps b (scale-props m))
    :buttonmatrix_props (.setButtonmatrixProps b (buttonmatrix-props m))
    :table_props (.setTableProps b (table-props m))
    :tabview_props (.setTabviewProps b (tabview-props m))
    :chart_props (.setChartProps b (chart-props m))
    :host_proxy_props (.setHostProxyProps b (host-proxy-props m))))

;; ── Node building (the internal node shape) ─────────────────────────────
;; A node map is the card children shape plus harness extras:
;;   {:type :WIDGET_*            ; required, WidgetType enum name
;;    :props {...}               ; style slots + at most one *_props key
;;                               ;   + :flex-flow (LVGL vocab, -> Layout)
;;    :text "..."                ; node.text (labels/checkbox/textarea)
;;    :children [node ...]       ; recursive
;;    :bare true                 ; lv_obj_remove_style_all before styles
;;    :flags-clear [:scrollable] ; obj_flags_clear bits
;;    :states-bits 512           ; wire lv_state_t bitmask, verbatim
;;    :layout {:flow :row}}      ; the Layout message (kitchen sinks)
(def ^:private node-keys
  #{:type :props :text :children :bare :flags-clear :states-bits :layout})

(def ^:private prop-keys
  "Every key a node `:props` map may carry."
  (into (conj widget-props-keys :flex-flow) (map first style-slot-order)))

(defn- split-props
  "Split a node's `:props` into {:style {...} :widget [key m] :flow kw}.
   Throws on an unknown key, on two widget-props arms, or on a `:flex-flow`
   value outside the closed LVGL base set."
  [ctx props]
  (assert-closed (str ctx " :props") prop-keys props)
  (let [wkeys (filterv widget-props-keys (keys props))]
    (when (< 1 (count wkeys))
      (throw (ex-info (str ctx ": more than one widget-props arm (oneof)")
                      {:ctx ctx :arms wkeys})))
    {:style (select-keys props (map first style-slot-order))
     :widget (when-let [k (first wkeys)] [k (get props k)])
     :flow (when-some [ff (:flex-flow props)]
             (or (get lvgl-flex-flow->kw ff)
                 (throw (ex-info (str ctx
                                      ": :flex-flow value outside the closed"
                                      " LVGL base set (0=row, 1=column)")
                                 {:ctx ctx :flex-flow ff}))))}))

(declare build-node)

(defn- assert-button-label!
  "The button builder law: renderer.c builds an EMPTY lv_button — text is a
   separate child WIDGET_LABEL. A labelless button card renders a blank
   chip and asserts nothing, so it fails HERE."
  [ctx node]
  (when (and (= :WIDGET_BUTTON (:type node))
             (not (some #(= :WIDGET_LABEL (:type %)) (:children node))))
    (throw (ex-info
            (str ctx ": WIDGET_BUTTON without a child WIDGET_LABEL" " (builder law)")
            {:ctx ctx :node node}))))

(defn- build-node
  "One internal node map -> a built WidgetNode message (recursive)."
  ^UiAst$WidgetNode [ctx node]
  (assert-closed (str ctx " node") node-keys node)
  (let [type-kw (:type node)
        _ (when-not (keyword? type-kw)
            (throw (ex-info (str ctx ": node :type missing") {:node node})))
        _ (assert-button-label! ctx node)
        {:keys [style widget flow]} (split-props ctx (:props node {}))
        [wkey wprops] widget
        _ (when (and wkey (not= wkey (type->props-key type-kw)))
            (throw
             (ex-info
              (str ctx
                   ": widget-props arm does not match node type"
                   " — a mismatched arm decodes as silence")
              {:ctx ctx :type type-kw :arm wkey :expected (type->props-key type-kw)})))
        flow (or flow (get-in node [:layout :flow]))
        _ (when (and (get-in node [:layout :flow])
                     (:flex-flow (:props node))
                     (not= (get lvgl-flex-flow->kw (:flex-flow (:props node)))
                           (get-in node [:layout :flow])))
            (throw (ex-info (str ctx ": :flex-flow prop conflicts with :layout")
                            {:ctx ctx :node node})))
        b (UiAst$WidgetNode/newBuilder)]
    (.setType b (UiAst$WidgetType/valueOf (name type-kw)))
    (when-some [t (:text node)] (.setText b ^String t))
    (when flow
      (.setLayout b
                  (-> (UiAst$Layout/newBuilder)
                      (.setFlow (enum-of flex-flows flow (str ctx " :layout :flow")))
                      .build)))
    (when (seq style) (.addStyleGroups b (style-group style)))
    (when wkey (set-widget-props! b wkey wprops))
    (when (:bare node) (.setBare b true))
    (when-some [fc (:flags-clear node)]
      (.setObjFlagsClear
       b
       (int (reduce (fn [acc k]
                      (bit-or acc (long (enum-of obj-flags k (str ctx " :flags-clear")))))
                    0
                    fc))))
    (when-some [s (:states-bits node)]
      (when-not (<= 0 s 0xFFFF)
        (throw (ex-info (str ctx ": :states-bits outside lv_state_t range")
                        {:ctx ctx :states-bits s})))
      (.setStates b (int s)))
    (doseq [c (:children node)] (.addChildren b (build-node ctx c)))
    (.build b)))

;; ── Card -> Screen bytes ────────────────────────────────────────────────
(def ^:private card-keys
  #{:id :state :states-bits :size :props :children :wrapper :expect :notes :text :value
    :variant})

(def ^:private wrapper-keys #{:props :flags-clear :notes})

(defn- root-wrap
  "The harness root law: a WIDGET_OBJ sized to the FULL canvas with
   PROP_PAD_ALL 0 AND PROP_BORDER_WIDTH 0 (harness geometry, never theme
   styling of the subject), wrapping the card's node. Border-zeroing is as
   load-bearing as pad-zeroing: the asgard panel border (1px) shrinks the
   root content box to canvas-2, so any full-bleed cell (the 800-wide
   flex-fit wrapper) reads clipped and the root reports scroll extent —
   measured, not hypothetical."
  [canvas node]
  {:type :WIDGET_OBJ
   :props {:w (:w canvas) :h (:h canvas) :pad-all 0 :border-width 0}
   :children [node]})

(defn- screen-bytes
  "Serialize a root-wrapped node tree to Screen protobuf bytes."
  ^bytes [canvas node]
  (-> (UiAst$Screen/newBuilder)
      (.setRoot (build-node (str "screen") (root-wrap canvas node)))
      .build
      .toByteArray))

(defn build-card
  "Build ONE atomic card into Screen protobuf bytes. `canvas` is the spec's
   `:render :canvas` map ({:w :h}); `widget-type` the class's `:type`
   keyword; `card` one `:cards` entry. The card's optional `:wrapper`
   becomes an intermediate WIDGET_OBJ between root and subject."
  ^bytes [canvas widget-type card]
  (assert-closed (str "card " (:id card)) card-keys card)
  (let [ctx (str "card " (:id card))
        node (cond-> {:type widget-type}
               (:props card) (assoc :props (:props card))
               (:text card) (assoc :text (:text card))
               (:children card) (assoc :children (:children card))
               (pos? (long (:states-bits card 0))) (assoc :states-bits (:states-bits card)))
        node (if-some [w (:wrapper card)]
               (do (assert-closed (str ctx " :wrapper") wrapper-keys w)
                   (cond-> {:type :WIDGET_OBJ :children [node]}
                     (:props w) (assoc :props (:props w))
                     (:flags-clear w) (assoc :flags-clear (:flags-clear w))))
               node)]
    (screen-bytes canvas node)))

;; ── Kitchen sinks (authored compositions) ───────────────────────────────
;; The spec declares the 6 sinks by id + widget list + prose description;
;; the node trees are AUTHORED here in the same unstyled raw vocabulary
;; (explicit px sizes, flex via the Layout message, zero style tokens).
;; `build-entry` cross-checks each tree against the spec's declared widget
;; list, so a drifted sink fails loudly instead of quietly thinning.
(def ^:private sink-green {:r 16 :g 185 :b 129})

(def kitchen-sink-trees
  "Sink id -> authored node tree (the container card; the harness root is
   added at build time). Compositions follow the spec descriptions:
   real theme cards (NO pad-zeroing — sink containers are themed subjects,
   unlike the harness root), medium-tier member widgets from the atomic
   corpus so a sink defect cross-references its atomic twin."
  {"kitchen-sink/form-row"
   {:type :WIDGET_OBJ
    :props {:w 760 :h 110}
    :layout {:flow :row}
    :children
    [{:type :WIDGET_LABEL :text "Preset" :props {:w 100 :label_props {:long_mode :wrap}}}
     {:type :WIDGET_TEXTAREA
      :props {:w 160 :textarea_props {:placeholder "Search..." :one_line true}}}
     {:type :WIDGET_DROPDOWN
      :props {:w 120 :dropdown_props {:options "Auto\nManual\nOff" :selected 1}}}
     {:type :WIDGET_BUTTON
      :props {:w 80 :h 36}
      :children [{:type :WIDGET_LABEL :text "Save"}]}]}
   "kitchen-sink/toolbar-strip"
   ;; Heights sized for BOTH families: stock (the vanilla family) spends
   ;; PAD_DEF=24 per edge on the matrix bg AND the sink card, so a 60px
   ;; matrix left 12px button slivers under family 1 (measured). 96px keeps
   ;; a 48px stock button (96 - 2*24) and the 152px card keeps a 104px
   ;; stock content box (152 - 2*24) that holds the matrix plus the 64px
   ;; spinbox; under family 0 the tighter asgard pads only gain room.
   {:type :WIDGET_OBJ
    :props {:w 760 :h 152}
    :layout {:flow :row}
    :children [{:type :WIDGET_BUTTONMATRIX
                :props {:w 200 :h 96 :buttonmatrix_props {:map_str "A\nB\nC\n"}}}
               {:type :WIDGET_SWITCH :props {:switch_props {:checked true}}}
               {:type :WIDGET_DROPDOWN
                :props {:w 120 :dropdown_props {:options "Auto\nManual\nOff" :selected 1}}}
               {:type :WIDGET_SPINBOX
                :props
                {:w 120
                 :h 64
                 :spinbox_props
                 {:min_value -99999 :max_value 99999 :step 1 :digit_count 3 :value 500}}}]}
   "kitchen-sink/gauge-cluster"
   {:type :WIDGET_OBJ
    :props {:w 360 :h 460}
    :layout {:flow :column}
    :children [{:type :WIDGET_ARC
                :props {:w 80
                        :h 80
                        :arc_props {:start_angle 0
                                    :end_angle 270
                                    :bg_start_angle 0
                                    :bg_end_angle 360
                                    :min_value 0
                                    :max_value 100
                                    :value 50}}}
               {:type :WIDGET_SCALE
                :props {:w 96
                        :h 96
                        :scale_props {:min_value 0
                                      :max_value 100
                                      :total_tick_count 11
                                      :major_tick_every 5
                                      :label_show true
                                      :mode :round-outer}}}
               {:type :WIDGET_CHART
                :props {:w 200
                        :h 130
                        :chart_props {:type :line
                                      :point_count 8
                                      :div_lines [0 12]
                                      :series [{:color {:r 239 :g 68 :b 68}
                                                :axis :primary-y
                                                :values [10 25 18 40 32 48 27 35]}]}}}
               {:type :WIDGET_LED
                :props {:w 32 :h 32 :led_props {:color sink-green :brightness 255}}}]}
   "kitchen-sink/settings-panel"
   {:type :WIDGET_OBJ
    :props {:w 360 :h 440}
    :layout {:flow :column}
    :children [{:type :WIDGET_CHECKBOX
                :text "Enable telemetry"
                :props {:checkbox_props {:checked true}}}
               {:type :WIDGET_SLIDER
                :props {:w 128 :h 16 :slider_props {:min_value 0 :max_value 100 :value 50}}}
               {:type :WIDGET_SPINBOX
                :props
                {:w 120
                 :h 64
                 :spinbox_props
                 {:min_value -99999 :max_value 99999 :step 1 :digit_count 3 :value 500}}}
               {:type :WIDGET_ROLLER
                :props {:w 120
                        :roller_props
                        {:options "One\nTwo\nThree" :visible_row_count 3 :selected 1}}}]}
   "kitchen-sink/hud-overlay"
   {:type :WIDGET_OBJ
    :props {:w 760 :h 240}
    :layout {:flow :row}
    :children
    [{:type :WIDGET_HOST_PROXY
      :props {:w 320
              :h 180
              :host_proxy_props {:proxy_id "px"
                                 :mode :draggable
                                 :min_w 96
                                 :min_h 54
                                 :max_w 400
                                 :max_h 300
                                 :handle_size 16
                                 :z 1}}
      :children [{:type :WIDGET_OBJ
                  :bare true
                  :props {:w 80 :h 40}
                  :children [{:type :WIDGET_LABEL :text "LIVE"}]}]}
     {:type :WIDGET_LABEL :text "CAM-04" :props {:w 100 :label_props {:long_mode :wrap}}}
     {:type :WIDGET_LED :props {:w 32 :h 32 :led_props {:color sink-green :brightness 255}}}
     {:type :WIDGET_BUTTON
      :props {:w 80 :h 36}
      :children [{:type :WIDGET_LABEL :text "Mark"}]}]}
   "kitchen-sink/tabview-with-content"
   {:type :WIDGET_TABVIEW
    :props {:w 460
            :h 320
            :tabview_props
            {:tab_names ["Alpha" "Beta" "Gamma"] :tab_bar_size 48 :active_index 0}}
    :children (vec (for [label ["Alpha" "Beta" "Gamma"]]
                     {:type :WIDGET_OBJ
                      :props {:w 360 :h 160}
                      :layout {:flow :row}
                      :children [{:type :WIDGET_BUTTON
                                  :props {:w 80 :h 36}
                                  :children [{:type :WIDGET_LABEL :text label}]}
                                 {:type :WIDGET_SLIDER
                                  :props {:w 128
                                          :h 16
                                          :slider_props
                                          {:min_value 0 :max_value 100 :value 50}}}]}))}})

(defn- tree-types
  "The set of widget :type keywords appearing anywhere in a node tree."
  [node]
  (into #{(:type node)} (mapcat tree-types) (:children node)))

(defn- assert-sink-coverage!
  "Every widget tag the spec declares for a sink must appear in its
   authored tree (tag->type resolved through the spec — one home)."
  [spec sink tree]
  (let [tag->type (into {} (map (juxt :tag :type)) (:widgets spec))
        present (tree-types tree)
        missing (remove #(contains? present
                                    (or (tag->type %)
                                        (throw (ex-info "sink declares unknown widget tag"
                                                        {:sink (:id sink) :tag %}))))
                        (:widgets sink))]
    (when (seq missing)
      (throw (ex-info "kitchen sink tree missing spec-declared widget classes"
                      {:sink (:id sink) :missing (vec missing)})))))

;; ── Spec loading + the build surface ────────────────────────────────────
(defn load-spec
  "Read + hand-validate `corpus/spec.edn`. Asserts the spec's own
   self-description: `:card-count` equals the actual card total,
   `:kitchen-sink-count` the sink total, and the render canvas is present.
   (The mission-level expected totals live in the smoke, not here — the
   spec is the one home for its own counts.)"
  []
  (let [spec (edn/read-string (slurp spec-path))
        actual (count (mapcat :cards (:widgets spec)))]
    (when-not (and (pos? actual) (= (:card-count spec) actual))
      (throw (ex-info "spec :card-count disagrees with its own cards"
                      {:card-count (:card-count spec) :actual actual})))
    (when-not (= (:kitchen-sink-count spec) (count (:kitchen-sinks spec)))
      (throw (ex-info "spec :kitchen-sink-count disagrees with :kitchen-sinks"
                      {:kitchen-sink-count (:kitchen-sink-count spec)
                       :actual (count (:kitchen-sinks spec))})))
    (when-not (and (pos? (long (get-in spec [:render :canvas :w] 0)))
                   (pos? (long (get-in spec [:render :canvas :h] 0))))
      (throw (ex-info "spec :render :canvas missing" {:render (:render spec)})))
    spec))

(defn entries
  "The full ordered build inventory: one entry per atomic card
   ({:kind :atomic :id :widget :type :expect :card}) followed by one per
   kitchen sink ({:kind :sink :id :widgets :sink}). Spec order throughout —
   deterministic output order is part of the contract."
  [spec]
  (into (vec (for [w (:widgets spec)
                   c (:cards w)]
               {:kind :atomic
                :id (:id c)
                :widget (:tag w)
                :type (:type w)
                :expect (:expect c)
                :card c}))
        (for [s (:kitchen-sinks spec)]
          {:kind :sink :id (:id s) :widgets (:widgets s) :sink s})))

(defn build-entry
  "Build one inventory entry (atomic card or kitchen sink) to Screen
   bytes. Throws with the entry id on any law/shape violation."
  ^bytes [spec entry]
  (let [canvas (get-in spec [:render :canvas])]
    (case (:kind entry)
      :atomic (build-card canvas (:type entry) (:card entry))
      :sink (let [tree (or (get kitchen-sink-trees (:id entry))
                           (throw (ex-info "no authored tree for kitchen sink"
                                           {:id (:id entry)
                                            :authored (vec (sort (keys
                                                                  kitchen-sink-trees)))})))]
              (assert-sink-coverage! spec (:sink entry) tree)
              (screen-bytes canvas tree)))))

(defn build-all
  "Build the WHOLE corpus: every atomic card + every kitchen sink, in spec
   order. Returns a vector of {:kind :id :widget :expect :bytes}. Throws on
   the first failing entry (callers wanting per-card error collection walk
   `entries` + `build-entry` themselves — the smoke does)."
  ([] (build-all (load-spec)))
  ([spec]
   (mapv (fn [e]
           (assoc (select-keys e [:kind :id :widget :expect]) :bytes (build-entry spec e)))
         (entries spec))))

(defn write-pbs!
  "Write built entries to `<dir>/<id>.pb` (ids contain '/': subdirs are
   created). Returns the file count."
  ^long [dir built]
  (doseq [{:keys [id ^bytes bytes]} built
          :let [f (io/file dir (str id ".pb"))]]
    (io/make-parents f)
    (with-open [out (io/output-stream f)] (.write out bytes)))
  (count built))

(defn -main
  "Build the full corpus and write the .pb files to `out/pbs/`."
  [& _]
  (let [spec (load-spec)
        built (build-all spec)
        atomic (count (filter #(= :atomic (:kind %)) built))
        sinks (count (filter #(= :sink (:kind %)) built))]
    (write-pbs! pb-out-dir built)
    (println (format "built %d screens (%d atomic + %d kitchen-sinks) -> %s"
                     (count built)
                     atomic
                     sinks
                     pb-out-dir))
    (println (format "total bytes: %d"
                     (reduce + (map #(alength ^bytes (:bytes %)) built))))))