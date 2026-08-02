(ns lvgl-codegen.proto-schema
  "Malli schemas for protobuf IR output — the EDN that emit-screen produces,
   mapping 1:1 to the protobuf ui.Screen message.
   Dual purpose: validation of emit output and generation of test data.
   Integer ranges match proto int32/uint32 (Java int via pronto's toIntExact).

   All fields carry :default annotations matching proto3 zero-value semantics,
   enabling mt/default-value-transformer for schema-driven normalization."
  (:require [lvgl-codegen.generated.enums :as gen-enums]
            [malli.core :as m]
            [malli.transform :as mt])
  (:import [com.google.protobuf ByteString]))

(set! *warn-on-reflection* true)

;; -- Reusable integer range schemas (matching proto field types) --
(def ^:private uint32
  "Proto uint32 range (stored as Java int via pronto)."
  [:int {:min 0 :max 2147483647}])

(def ^:private int32 "Proto int32 range." [:int {:min -2147483648 :max 2147483647}])

;; -- Leaf message schemas --
(def color
  "RGB color with 0-255 range per channel."
  [:map [:r [:int {:min 0 :max 255 :default 0 :gen/elements [0 1 127 128 254 255]}]]
   [:g [:int {:min 0 :max 255 :default 0 :gen/elements [0 1 127 128 254 255]}]]
   [:b [:int {:min 0 :max 255 :default 0 :gen/elements [0 1 127 128 254 255]}]]])

(def shadow-bundle
  "Shadow effect parameters."
  [:map [:width [:int {:min 0 :max 2147483647 :default 0}]]
   [:offset_x [:int {:min -2147483648 :max 2147483647 :default 0}]]
   [:offset_y [:int {:min -2147483648 :max 2147483647 :default 0}]]
   [:spread [:int {:min 0 :max 2147483647 :default 0}]]
   [:opa [:int {:min 0 :max 255 :default 0}]]])

(def point
  "2D point for line widgets."
  [:map [:x {:default 0} int32] [:y {:default 0} int32]])

;; -- Enum schemas --
(def widget-type
  "Widget type enum values."
  [:enum :WIDGET_OBJ :WIDGET_BUTTON :WIDGET_LABEL :WIDGET_SLIDER :WIDGET_IMAGE :WIDGET_ARC
   :WIDGET_BAR :WIDGET_SWITCH :WIDGET_CHECKBOX :WIDGET_DROPDOWN :WIDGET_ROLLER
   :WIDGET_TEXTAREA :WIDGET_SPINBOX :WIDGET_SPINNER :WIDGET_LED :WIDGET_LINE :WIDGET_SCALE
   :WIDGET_BUTTONMATRIX :WIDGET_TABLE :WIDGET_TABVIEW :WIDGET_CHART :WIDGET_HOST_PROXY])

;; LVGL-mirror enums — DERIVED from the factory-generated bindings
;; (`lvgl-codegen.generated.enums`, regenerated from LVGL's own headers via
;; the construct factory). One home per enum fact; these defs are the
;; proto-schema-facing names.
(def flex-flow "Flex flow direction enum values." gen-enums/flex-flow-proto-enum)

(def flex-align "Flex alignment enum values." gen-enums/flex-align-proto-enum)

(def grid-align "Grid alignment enum values." gen-enums/grid-align-proto-enum)

(def text-align "Text alignment enum values." gen-enums/text-align-proto-enum)

(def text-decor "Text decoration enum values." gen-enums/text-decor-proto-enum)

(def blend-mode "Blend mode enum values." gen-enums/blend-mode-proto-enum)

(def base-dir "Base direction enum values." gen-enums/base-dir-proto-enum)

(def grad-dir "Gradient direction enum values." gen-enums/grad-dir-proto-enum)

(def dir "Direction enum values (bitmask)." gen-enums/dir-proto-enum)

(def align-enum "Alignment enum values." gen-enums/align-proto-enum)

(def border-side "Border side enum values (bitmask)." gen-enums/border-side-proto-enum)

(def label-long-mode "Label long mode enum values." gen-enums/label-long-mode-proto-enum)

(def bar-mode "Bar/slider mode enum values." gen-enums/bar-mode-proto-enum)

(def arc-mode "Arc mode enum values." gen-enums/arc-mode-proto-enum)

(def roller-mode "Roller mode enum values." gen-enums/roller-mode-proto-enum)

(def scale-mode "Scale mode enum values (bitmask)." gen-enums/scale-mode-proto-enum)

(def chart-type "Chart type enum values." gen-enums/chart-type-proto-enum)

(def chart-axis "Chart axis enum values (sparse bitmask)." gen-enums/chart-axis-proto-enum)

(def compare-op
  "Comparison operator for visibility bindings."
  [:enum :COMPARE_EQ :COMPARE_NOT_EQ :COMPARE_GT :COMPARE_GTE :COMPARE_LT :COMPARE_LTE])

(def visibility-binding
  "Conditional visibility binding — show/hide based on subject value comparison."
  [:map [:subject {:default ""} string?] [:ref_value {:default 0} int32]
   [:compare {:default :COMPARE_EQ} compare-op]])

(def color-binding
  "Value-conditional text-color binding — the VisibilityBinding subject/range
   (`when`) plus the color applied to LV_PART_MAIN while the comparison holds
   (WidgetNode.color_when). Reverts to the theme/authored default otherwise."
  [:map [:when visibility-binding] [:color color]])

;; -- Style property type enum --
(def style-property-type
  "All 107 style property type enum values."
  [:enum
   ;; Existing 0-15
   :PROP_BG_COLOR :PROP_BG_OPA :PROP_TEXT_COLOR :PROP_TEXT_FONT :PROP_BORDER_COLOR
   :PROP_BORDER_WIDTH :PROP_RADIUS :PROP_PAD_ALL :PROP_PAD_GAP :PROP_WIDTH :PROP_HEIGHT
   :PROP_SHADOW :PROP_PAD_HOR :PROP_PAD_VER :PROP_MARGIN_ALL :PROP_BORDER_OPA
   ;; Size 16-20
   :PROP_MIN_WIDTH :PROP_MAX_WIDTH :PROP_MIN_HEIGHT :PROP_MAX_HEIGHT :PROP_LENGTH
   ;; Position 21-23
   :PROP_X :PROP_Y :PROP_ALIGN
   ;; Transform 24-34
   :PROP_TRANSFORM_WIDTH :PROP_TRANSFORM_HEIGHT :PROP_TRANSLATE_X :PROP_TRANSLATE_Y
   :PROP_SCALE_X :PROP_SCALE_Y :PROP_ROTATION :PROP_PIVOT_X :PROP_PIVOT_Y :PROP_SKEW_X
   :PROP_SKEW_Y
   ;; Padding individual 35-40
   :PROP_PAD_TOP :PROP_PAD_BOTTOM :PROP_PAD_LEFT :PROP_PAD_RIGHT :PROP_PAD_ROW
   :PROP_PAD_COLUMN
   ;; Margin individual 41-44
   :PROP_MARGIN_TOP :PROP_MARGIN_BOTTOM :PROP_MARGIN_LEFT :PROP_MARGIN_RIGHT
   ;; Background extended 45-55
   :PROP_BG_GRAD_COLOR :PROP_BG_GRAD_DIR :PROP_BG_MAIN_STOP :PROP_BG_GRAD_STOP
   :PROP_BG_MAIN_OPA :PROP_BG_GRAD_OPA :PROP_BG_IMAGE_SRC :PROP_BG_IMAGE_OPA
   :PROP_BG_IMAGE_RECOLOR :PROP_BG_IMAGE_RECOLOR_OPA :PROP_BG_IMAGE_TILED
   ;; Border extended 56-57
   :PROP_BORDER_SIDE :PROP_BORDER_POST
   ;; Outline 58-61
   :PROP_OUTLINE_WIDTH :PROP_OUTLINE_COLOR :PROP_OUTLINE_OPA :PROP_OUTLINE_PAD
   ;; Shadow individual 62-67
   :PROP_SHADOW_WIDTH :PROP_SHADOW_OFFSET_X :PROP_SHADOW_OFFSET_Y :PROP_SHADOW_SPREAD
   :PROP_SHADOW_COLOR :PROP_SHADOW_OPA
   ;; Image style 68-70
   :PROP_IMAGE_OPA :PROP_IMAGE_RECOLOR :PROP_IMAGE_RECOLOR_OPA
   ;; Line style 71-76
   :PROP_LINE_WIDTH :PROP_LINE_DASH_WIDTH :PROP_LINE_DASH_GAP :PROP_LINE_ROUNDED
   :PROP_LINE_COLOR :PROP_LINE_OPA
   ;; Arc style 77-80
   :PROP_ARC_WIDTH :PROP_ARC_ROUNDED :PROP_ARC_COLOR :PROP_ARC_OPA
   ;; Text extended 81-85
   :PROP_TEXT_OPA :PROP_TEXT_LETTER_SPACE :PROP_TEXT_LINE_SPACE :PROP_TEXT_DECOR
   :PROP_TEXT_ALIGN
   ;; Misc 86-93
   :PROP_CLIP_CORNER :PROP_OPA :PROP_OPA_LAYERED :PROP_COLOR_FILTER_OPA :PROP_ANIM_DURATION
   :PROP_BLEND_MODE :PROP_BASE_DIR :PROP_ROTARY_SENSITIVITY
   ;; Flex 94-98
   :PROP_FLEX_FLOW :PROP_FLEX_MAIN_PLACE :PROP_FLEX_CROSS_PLACE :PROP_FLEX_TRACK_PLACE
   :PROP_FLEX_GROW
   ;; Grid cell 99-106
   :PROP_GRID_COLUMN_ALIGN :PROP_GRID_ROW_ALIGN :PROP_GRID_CELL_COLUMN_POS
   :PROP_GRID_CELL_X_ALIGN :PROP_GRID_CELL_COLUMN_SPAN :PROP_GRID_CELL_ROW_POS
   :PROP_GRID_CELL_Y_ALIGN :PROP_GRID_CELL_ROW_SPAN])

;; -- Style property: multi-method dispatch on :type --
;; Each property has :type + exactly one value key from the oneof.
;; Properties organized by value type for DRY generation.
(def ^:private int-value-props
  "Style properties that use int_value."
  #{;; Size (non-WASM-handled, keep as int for forward compat)
    :PROP_MIN_WIDTH :PROP_MAX_WIDTH :PROP_MIN_HEIGHT :PROP_MAX_HEIGHT :PROP_LENGTH
    ;; Position
    :PROP_X :PROP_Y
    ;; Transform
    :PROP_TRANSFORM_WIDTH :PROP_TRANSFORM_HEIGHT :PROP_TRANSLATE_X :PROP_TRANSLATE_Y
    :PROP_SCALE_X :PROP_SCALE_Y :PROP_ROTATION :PROP_PIVOT_X :PROP_PIVOT_Y :PROP_SKEW_X
    :PROP_SKEW_Y
    ;; Padding individual
    :PROP_PAD_TOP :PROP_PAD_BOTTOM :PROP_PAD_LEFT :PROP_PAD_RIGHT :PROP_PAD_ROW
    :PROP_PAD_COLUMN
    ;; Margin individual
    :PROP_MARGIN_TOP :PROP_MARGIN_BOTTOM :PROP_MARGIN_LEFT :PROP_MARGIN_RIGHT
    ;; Background
    :PROP_BG_MAIN_STOP :PROP_BG_GRAD_STOP
    ;; Outline
    :PROP_OUTLINE_WIDTH :PROP_OUTLINE_PAD
    ;; Shadow individual
    :PROP_SHADOW_WIDTH :PROP_SHADOW_OFFSET_X :PROP_SHADOW_OFFSET_Y :PROP_SHADOW_SPREAD
    ;; Line
    :PROP_LINE_WIDTH :PROP_LINE_DASH_WIDTH :PROP_LINE_DASH_GAP
    ;; Arc
    :PROP_ARC_WIDTH
    ;; Text
    :PROP_TEXT_LETTER_SPACE :PROP_TEXT_LINE_SPACE
    ;; Grid cell
    :PROP_GRID_CELL_COLUMN_POS :PROP_GRID_CELL_COLUMN_SPAN :PROP_GRID_CELL_ROW_POS
    :PROP_GRID_CELL_ROW_SPAN})

(def ^:private uint-value-props
  "Style properties that use uint_value (opacity, enum flags, booleans, dimensions)."
  #{;; Dimensions, padding, radii — WASM renderer (f8bc9d3) checks uint_value_tag
    :PROP_BORDER_WIDTH :PROP_RADIUS :PROP_PAD_ALL :PROP_PAD_GAP :PROP_WIDTH :PROP_HEIGHT
    :PROP_PAD_HOR :PROP_PAD_VER :PROP_MARGIN_ALL
    ;; Opacity
    :PROP_BG_OPA :PROP_BORDER_OPA
    ;; Position
    :PROP_ALIGN
    ;; Background
    :PROP_BG_GRAD_DIR :PROP_BG_MAIN_OPA :PROP_BG_GRAD_OPA :PROP_BG_IMAGE_OPA
    :PROP_BG_IMAGE_RECOLOR_OPA :PROP_BG_IMAGE_TILED
    ;; Border
    :PROP_BORDER_SIDE :PROP_BORDER_POST
    ;; Outline
    :PROP_OUTLINE_OPA
    ;; Shadow
    :PROP_SHADOW_OPA
    ;; Image
    :PROP_IMAGE_OPA :PROP_IMAGE_RECOLOR_OPA
    ;; Line
    :PROP_LINE_ROUNDED :PROP_LINE_OPA
    ;; Arc
    :PROP_ARC_ROUNDED :PROP_ARC_OPA
    ;; Text
    :PROP_TEXT_OPA :PROP_TEXT_DECOR :PROP_TEXT_ALIGN
    ;; Misc
    :PROP_CLIP_CORNER :PROP_OPA :PROP_OPA_LAYERED :PROP_COLOR_FILTER_OPA :PROP_ANIM_DURATION
    :PROP_BLEND_MODE :PROP_BASE_DIR :PROP_ROTARY_SENSITIVITY
    ;; Flex
    :PROP_FLEX_FLOW :PROP_FLEX_MAIN_PLACE :PROP_FLEX_CROSS_PLACE :PROP_FLEX_TRACK_PLACE
    :PROP_FLEX_GROW
    ;; Grid cell
    :PROP_GRID_COLUMN_ALIGN :PROP_GRID_ROW_ALIGN :PROP_GRID_CELL_X_ALIGN
    :PROP_GRID_CELL_Y_ALIGN})

(def ^:private color-value-props
  "Style properties that use color_value."
  #{:PROP_BG_COLOR :PROP_TEXT_COLOR :PROP_BORDER_COLOR :PROP_BG_GRAD_COLOR
    :PROP_BG_IMAGE_RECOLOR :PROP_OUTLINE_COLOR :PROP_SHADOW_COLOR :PROP_IMAGE_RECOLOR
    :PROP_LINE_COLOR :PROP_ARC_COLOR})

(def ^:private string-value-props
  "Style properties that use string_value."
  #{:PROP_TEXT_FONT :PROP_BG_IMAGE_SRC})

(def style-property
  "A single style property with type + value (oneof: uint, int, color, string, shadow).
   107 property types dispatched by :type."
  (into
   [:multi {:dispatch :type}]
   (concat
    (for [p (sort-by name int-value-props)] [p [:map [:type [:= p]] [:int_value int32]]])
    (for [p (sort-by name uint-value-props)] [p [:map [:type [:= p]] [:uint_value uint32]]])
    (for [p (sort-by name color-value-props)]
      [p [:map [:type [:= p]] [:color_value color]]])
    (for [p (sort-by name string-value-props)]
      [p [:map [:type [:= p]] [:string_value {:default ""} string?]]])
    [[:PROP_SHADOW [:map [:type [:= :PROP_SHADOW]] [:shadow_value shadow-bundle]]]])))

(def style-variant
  "One sparse composite variant: variant_index (breakpoint_tier * 2 +
   theme_dark, range 0-7) + the COMPLETE fully-resolved prop set for that
   composite index."
  [:map [:variant_index {:default 0} [:int {:min 0 :max 7}]]
   [:properties {:default []} [:vector style-property]]])

(def style-group
  "A group of sparse style variants for one LVGL state selector. The
   entry with variant_index 0 (the base) is always present and first; an
   absent composite index renders exactly as the base."
  [:map [:state_selector {:default 0} uint32]
   [:variants [:vector {:min 1 :gen/max 8} style-variant]]])

;; -- Event + Layout --
(def event-trigger
  "Event trigger enum values."
  [:enum :TRIGGER_CLICKED :TRIGGER_VALUE_CHANGED :TRIGGER_LONG_PRESSED])

;; -- R5a cmd-out pre-encode (CmdSpec / FieldPatch / GestureSpec) --
(def patch-kind
  "WHERE the patcher reads the value it writes into a slot — the SOURCE axis
   only; `patch-encoding` carries how it is written. NDC_X2/NDC_Y2 carry an ROI
   rubber-band's 2nd corner (verbatim double slots). SUBJECT_VALUE reads the
   named local subject in the patch's `:subject`, which is what lets a control
   with no value of its own send a multi-field form."
  [:enum :PATCH_KIND_UNSPECIFIED :PATCH_KIND_NDC_X :PATCH_KIND_NDC_Y :PATCH_KIND_DELTA
   :PATCH_KIND_WIDGET_VALUE :PATCH_KIND_NDC_X2 :PATCH_KIND_NDC_Y2
   :PATCH_KIND_SUBJECT_VALUE])

(def ^:private patch-encoding
  "HOW a value-sourced slot is written. Orthogonal to `patch-kind` because ONE
   integer source targets three different wire shapes. UNSPECIFIED belongs to
   the four NDC kinds, whose encoding IS their kind; every value-sourced kind
   must name one, and the renderer refuses the mismatch either way."
  [:enum :PATCH_ENCODING_UNSPECIFIED :PATCH_ENCODING_PADDED_VARINT
   :PATCH_ENCODING_DOUBLE_LE :PATCH_ENCODING_FLOAT_LE])

(def gesture-kind
  "Recognized gesture kind, mirroring gesture_kind_t (src/gesture.h). ROI is a
   registry lookup key for a rubber-band rect surface, never an FSM decision."
  [:enum :GESTURE_KIND_PAN_MOVE :GESTURE_KIND_PAN_END :GESTURE_KIND_TAP :GESTURE_KIND_TRACK
   :GESTURE_KIND_PINCH :GESTURE_KIND_WHEEL :GESTURE_KIND_ROI])

(def field-patch
  "One fixed-width slot in a CmdSpec.root_template the renderer overwrites:
   byte-offset + width + the patch SOURCE (`:kind`) + the gen-time wire-scale
   (sint32) + the slot ENCODING +, for a SUBJECT_VALUE slot only, the name of
   the subject it reads."
  [:map [:byte_offset {:default 0} uint32] [:byte_width {:default 0} uint32]
   [:kind {:default :PATCH_KIND_UNSPECIFIED} patch-kind]
   [:wire_scale {:optional true :default 0} int32]
   [:subject {:optional true :default ""} :string]
   [:encoding {:optional true :default :PATCH_ENCODING_UNSPECIFIED} patch-encoding]])

(def byte-string
  "A protobuf ByteString — the wire carrier emit-cmd-spec wraps the cmd.Root
   template bytes in (pronto's bytes field wants a ByteString, not a byte[]).
   :gen/fmap wraps a generated byte vector so the generative roundtrip test can
   synthesize a bytes field (opaque on the wire — any bytes round-trip)."
  [:fn
   {:error/message "should be a com.google.protobuf.ByteString"
    :gen/fmap (fn [bs] (ByteString/copyFrom (byte-array (map unchecked-byte bs))))
    :gen/schema [:vector {:max 16} [:int {:min 0 :max 255}]]} #(instance? ByteString %)])

(def cmd-spec
  "A pre-encoded cmd.Root template + the slots the renderer overwrites: the
   source command-id, the deterministic cmd.Root bytes (ByteString), and up to
   8 FieldPatch slots — bounded by the widest command this vocabulary must send
   in one shot (the rotary scan-node commands carry 7 operator-facing fields).
   The gesture shapes that set the earlier bound of 4 are unaffected: an NDC x/y
   pair is 2, an ROI rubber-band's two corners are 4."
  [:map [:command_id {:default ""} string?] [:root_template byte-string]
   [:patches {:optional true :default []} [:vector {:max 8} field-patch]]])

(def gesture-spec
  "One gesture → its pre-encoded cmd template, keyed by GestureKind."
  [:map [:kind {:default :GESTURE_KIND_PAN_MOVE} gesture-kind] [:cmd cmd-spec]])

(def event-binding
  "Event binding — named event with trigger, payload, optional subject mutation,
   an optional R5a pre-encoded cmd.* template (`:cmd`), OR a vector of FIXED
   templates (`:cmd_by_value`, up to 16) the widget's int value index-selects
   among (mutually exclusive with `:cmd`)."
  [:map [:name {:default ""} string?]
   [:trigger {:optional true :default :TRIGGER_CLICKED} event-trigger]
   [:int_value {:optional true :default 0} int32]
   [:include_widget_value {:optional true :default false} boolean?]
   [:set_subject {:optional true :default ""} string?]
   [:set_value {:optional true :default 0} int32]
   [:toggle {:optional true :default false} boolean?]
   [:notify_host {:optional true :default false} boolean?]
   [:cmd {:optional true :default nil} [:maybe cmd-spec]]
   [:cmd_by_value {:optional true :default []} [:vector {:max 16} cmd-spec]]])

(def layout
  "Layout flow direction wrapper with flex alignment."
  [:map [:flow {:default :FLEX_FLOW_NONE} flex-flow]
   [:main_place {:optional true :default :FLEX_ALIGN_START} flex-align]
   [:cross_place {:optional true :default :FLEX_ALIGN_START} flex-align]
   [:track_place {:optional true :default :FLEX_ALIGN_START} flex-align]])

;; -- Widget props schemas --
(def obj-props
  "Obj widget properties — the proto message carries no fields yet, so the
   honest shape is the closed empty map (a field lands here when the proto
   grows one)."
  [:map {:closed true}])

(def button-props
  "Button widget properties — the proto message carries no fields yet, so
   the honest shape is the closed empty map (a field lands here when the
   proto grows one)."
  [:map {:closed true}])

(def label-props
  "Label widget properties."
  [:map [:long_mode {:default :LABEL_LONG_MODE_WRAP} label-long-mode]])

(def slider-props
  "Slider widget properties."
  [:map [:min_value {:default 0} int32] [:max_value {:default 0} int32]
   [:value {:default 0} int32] [:mode {:default :BAR_MODE_NORMAL} bar-mode]
   [:seek_on_press {:optional true :default false} boolean?]])

(def image-props
  "Image widget properties."
  [:map [:src {:default ""} string?] [:has_pivot {:optional true :default false} boolean?]
   [:pivot_x {:optional true :default 0} int32] [:pivot_y {:optional true :default 0} int32]
   [:rotation {:optional true :default 0} int32]])

(def arc-props
  "Arc widget properties."
  [:map [:start_angle {:default 0} uint32] [:end_angle {:default 0} uint32]
   [:bg_start_angle {:default 0} uint32] [:bg_end_angle {:default 0} uint32]
   [:rotation {:default 0} int32] [:mode {:default :ARC_MODE_NORMAL} arc-mode]
   [:min_value {:default 0} int32] [:max_value {:default 0} int32]
   [:value {:default 0} int32]])

(def bar-props
  "Bar widget properties."
  [:map [:min_value {:default 0} int32] [:max_value {:default 0} int32]
   [:value {:default 0} int32] [:start_value {:default 0} int32]
   [:mode {:default :BAR_MODE_NORMAL} bar-mode]])

(def switch-props "Switch widget properties." [:map [:checked {:default false} boolean?]])

(def checkbox-props
  "Checkbox widget properties."
  [:map [:checked {:default false} boolean?]])

(def dropdown-props
  "Dropdown widget properties."
  [:map [:options {:default ""} string?] [:selected {:default 0} uint32]
   [:direction {:default :DIR_NONE} dir]])

(def roller-props
  "Roller widget properties."
  [:map [:options {:default ""} string?] [:selected {:default 0} uint32]
   [:visible_row_count {:default 0} uint32]
   [:mode {:default :ROLLER_MODE_NORMAL} roller-mode]])

(def textarea-props
  "Textarea widget properties."
  [:map [:placeholder {:default ""} string?] [:max_length {:default 0} uint32]
   [:one_line {:default false} boolean?] [:password_mode {:default false} boolean?]])

(def spinbox-props
  "Spinbox widget properties."
  [:map [:min_value {:default 0} int32] [:max_value {:default 0} int32]
   [:value {:default 0} int32] [:step {:default 0} int32] [:digit_count {:default 0} uint32]
   [:separator_position {:default 0} uint32]])

(def spinner-props
  "Spinner widget properties."
  [:map [:spin_time {:default 0} uint32] [:arc_length {:default 0} uint32]])

(def led-props
  "LED widget properties."
  [:map [:color {:optional true :default nil} [:maybe color]]
   [:brightness {:default 0} uint32]])

(def line-props
  "Line widget properties."
  [:map [:points {:default []} [:vector {:gen/max 5} point]]
   [:y_invert {:default false} boolean?]])

(def scale-props
  "Scale widget properties."
  [:map [:mode {:default :SCALE_MODE_HORIZONTAL_TOP} scale-mode]
   [:total_tick_count {:default 0} uint32] [:major_tick_every {:default 0} uint32]
   [:label_show {:default false} boolean?] [:min_value {:default 0} int32]
   [:max_value {:default 0} int32] [:rotation {:default 0} int32]
   [:angle_range {:default 0} uint32] [:text_src {:optional true :default ""} string?]
   [:post_draw {:optional true :default false} boolean?]
   [:sections {:optional true :default []}
    [:vector {:max 4}
     [:map [:range_min {:default 0} int32] [:range_max {:default 0} int32]
      [:color {:optional true :default nil} [:maybe color]]
      [:width {:optional true :default 0} uint32]
      ;; MAIN-part section style (the demo's colored arc bands on round
      ;; scales); absent color + zero width = no MAIN section style.
      [:main_color {:optional true :default nil} [:maybe color]]
      [:main_width {:optional true :default 0} uint32]]]]])

(def buttonmatrix-props
  "ButtonMatrix widget properties."
  [:map [:map_str {:default ""} string?] [:one_check {:default false} boolean?]])

(def table-props
  "Table widget properties."
  [:map [:row_count {:default 0} uint32] [:column_count {:default 0} uint32]])

(def tabview-props
  "Tabview widget properties. tab_names zip per-index with the node's
   non-tab-bar children (child i becomes tab i's page content)."
  [:map [:tab_names {:default []} [:vector {:max 8} [:string {:max 31}]]]
   [:tab_bar_size {:default 0} int32] [:active_index {:default 0} uint32]
   [:tab_bar_position {:default :DIR_NONE} dir]
   ;; Extra pad_left (px) on the tab bar itself — the demo pushes its tab
   ;; buttons into the right half and floats logo decor over the left.
   [:tab_bar_pad_left {:default 0} int32]])

(def chart-series
  "One chart data series: color (absent = theme primary), target Y axis,
   and the frozen-frame values written by index."
  [:map [:color {:optional true :default nil} [:maybe color]]
   [:axis {:optional true :default :CHART_AXIS_PRIMARY_Y} chart-axis]
   [:values {:default []} [:vector {:max 32} int32]]])

(def chart-props
  "Chart widget properties. Div-line presence rides has_div_lines (0 is a
   VALID explicit count — the demo sets 0,12); type NONE keeps the LVGL
   default (LINE)."
  [:map [:type {:default :CHART_TYPE_NONE} chart-type] [:point_count {:default 0} uint32]
   [:has_div_lines {:optional true :default false} boolean?]
   [:hdiv_count {:optional true :default 0} [:int {:min 0 :max 255}]]
   [:vdiv_count {:optional true :default 0} [:int {:min 0 :max 255}]]
   [:series {:optional true :default []} [:vector {:max 8} chart-series]]
   [:fade_area {:optional true :default false} boolean?]])

(def proxy-mode
  "Host-proxy positioning mode enum values. OUR enum (no LVGL counterpart —
   not parity-gated); the ints are the mode-subject wire contract."
  [:enum :PROXY_MODE_STATIC :PROXY_MODE_DRAGGABLE :PROXY_MODE_RESIZABLE
   :PROXY_MODE_ALIGNABLE])

(def host-proxy-props
  "Host-proxy widget properties: the stable host-side join key, the initial
   mode (a \"mode\" binding's subject wins after attach), resize clamps in
   framebuffer px (0 = unconstrained), the corner-handle edge (0 =
   DPI-derived default), and the opaque z stacking hint echoed verbatim in
   every host_proxy_report."
  [:map [:proxy_id {:default ""} string?] [:mode {:default :PROXY_MODE_STATIC} proxy-mode]
   [:min_w {:optional true :default 0} int32] [:min_h {:optional true :default 0} int32]
   [:max_w {:optional true :default 0} int32] [:max_h {:optional true :default 0} int32]
   [:handle_size {:optional true :default 0} uint32]
   [:z {:optional true :default 0} int32]])

;; -- Subject declaration --
(def subject-declaration
  "A reactive subject declaration with optional initial value.
   Uses multi-dispatch on :type to enforce proto oneof mutual exclusivity
   (int_initial and string_initial cannot coexist).
   Oneof fields use [:maybe ...] to accept nil from pronto deserialization
   and have no :default to avoid schema-normalizer divergence."
  [:multi {:dispatch :type :default :SUBJECT_INT}
   [:SUBJECT_INT
    [:map [:name {:default ""} string?] [:type {:default :SUBJECT_INT} [:= :SUBJECT_INT]]
     [:int_initial {:optional true} [:maybe int32]]]]
   [:SUBJECT_STRING
    [:map [:name {:default ""} string?] [:type [:= :SUBJECT_STRING]]
     [:string_initial {:optional true} [:maybe string?]]]]])

;; -- Recursive WidgetNode + Screen --
(def widget-node
  "Recursive widget node — matches proto WidgetNode message."
  [:schema
   {:registry
    {::widget-node
     [:map [:type {:default :WIDGET_OBJ} widget-type]
      ;; Stable node identity (tree patching): FNV-1a-32 of the identity
      ;; path as a SIGNED int (the uint32 two's-complement carrier, so the
      ;; full int32 range is legitimate). 0 = never assigned.
      [:uid {:optional true :default 0} int32] [:x {:optional true :default 0} int32]
      [:y {:optional true :default 0} int32] [:text {:optional true :default ""} string?]
      [:bindings {:optional true :default {}} [:map-of string? string?]]
      [:bind_formats {:optional true :default {}} [:map-of string? string?]]
      [:obj_flags {:optional true :default 0} uint32]
      [:obj_flags_clear {:optional true :default 0} uint32]
      [:states {:optional true :default 0} uint32]
      [:scroll_dir {:optional true :default 0} uint32]
      [:grid_col_dsc {:optional true :default []} [:vector {:max 12} int32]]
      [:grid_row_dsc {:optional true :default []} [:vector {:max 12} int32]]
      [:bare {:optional true :default false} boolean?]
      [:in_tab_bar {:optional true :default false} boolean?]
      [:event {:optional true :default nil} [:maybe event-binding]]
      [:visibility {:optional true :default nil} [:maybe visibility-binding]]
      ;; Reactive LV_STATE_CHECKED binding — the VisibilityBinding shape
      ;; reused (subject + ref_value + compare); the reactive sibling of
      ;; the create-time `states` bitmask.
      [:checked_when {:optional true :default nil} [:maybe visibility-binding]]
      ;; Reactive enabled-state binding — the VisibilityBinding shape reused with
      ;; INVERTED polarity: LV_STATE_DISABLED while the comparison against the
      ;; subject does NOT hold, cleared (enabled) while it holds.
      [:enabled_when {:optional true :default nil} [:maybe visibility-binding]]
      ;; Value-conditional text-color binding — LV_PART_MAIN text color is set to
      ;; color_when.color while the comparison holds and reverted otherwise.
      [:color_when {:optional true :default nil} [:maybe color-binding]]
      [:layout {:optional true :default nil} [:maybe layout]]
      [:children {:optional true :default []} [:vector {:gen/max 3} [:ref ::widget-node]]]
      [:style_groups {:optional true :default []} [:vector {:gen/max 4} style-group]]
      ;; Widget props — all optional, only one should be present per widget type
      [:obj_props {:optional true :default nil} [:maybe obj-props]]
      [:button_props {:optional true :default nil} [:maybe button-props]]
      [:label_props {:optional true :default nil} [:maybe label-props]]
      [:slider_props {:optional true :default nil} [:maybe slider-props]]
      [:image_props {:optional true :default nil} [:maybe image-props]]
      [:arc_props {:optional true :default nil} [:maybe arc-props]]
      [:bar_props {:optional true :default nil} [:maybe bar-props]]
      [:switch_props {:optional true :default nil} [:maybe switch-props]]
      [:checkbox_props {:optional true :default nil} [:maybe checkbox-props]]
      [:dropdown_props {:optional true :default nil} [:maybe dropdown-props]]
      [:roller_props {:optional true :default nil} [:maybe roller-props]]
      [:textarea_props {:optional true :default nil} [:maybe textarea-props]]
      [:spinbox_props {:optional true :default nil} [:maybe spinbox-props]]
      [:spinner_props {:optional true :default nil} [:maybe spinner-props]]
      [:led_props {:optional true :default nil} [:maybe led-props]]
      [:line_props {:optional true :default nil} [:maybe line-props]]
      [:scale_props {:optional true :default nil} [:maybe scale-props]]
      [:buttonmatrix_props {:optional true :default nil} [:maybe buttonmatrix-props]]
      [:table_props {:optional true :default nil} [:maybe table-props]]
      [:tabview_props {:optional true :default nil} [:maybe tabview-props]]
      [:chart_props {:optional true :default nil} [:maybe chart-props]]
      [:host_proxy_props {:optional true :default nil} [:maybe host-proxy-props]]
      ;; R5a: gesture-surface pre-encoded gesture→cmd templates (up to 5
      ;; device gestures), meaningful only on the gesture-surface host-proxy.
      [:gestures {:optional true :default []} [:vector {:max 5} gesture-spec]]]}}
   ::widget-node])

(def screen
  "Top-level screen — wraps a root widget node with optional subject declarations."
  [:map [:root widget-node]
   [:subjects {:optional true :default []} [:vector subject-declaration]]])

;; -- Public API --
(def ^:private fill-defaults
  "Schema-driven normalization: fill proto3 defaults for missing keys.
   Emitted IR is legitimately SPARSE (proto3 absent == default); the
   schema keeps defaulted keys required so generators always produce
   complete maps — validation therefore normalizes first."
  (m/decoder screen (mt/default-value-transformer {:malli.transform/add-optional-keys true})))

(defn explain-proto-ir
  "Explain a (default-normalized) proto IR map against the Screen schema.
   Returns nil when valid, the Malli explanation otherwise."
  [screen-map]
  (let [normalized (fill-defaults screen-map)]
    (when-not (m/validate screen normalized) (m/explain screen normalized))))
(m/=> explain-proto-ir
      [:=>
       [:cat map?]
       [:maybe [:map [:schema :any] [:value :any] [:errors [:sequential :any]]]]])