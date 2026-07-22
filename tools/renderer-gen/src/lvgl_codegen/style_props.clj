(ns lvgl-codegen.style-props
  "The style-property registry — ONE home for per-prop knowledge.

   Every style property the pipeline understands has exactly one entry here;
   the three consumers all DERIVE from it (cohesion: one home per fact):

   - `emit-proto/emit-style-property` — :proto (StylePropertyType member) +
     :slot (the proto oneof value field) + :xform (emit-side value
     translation, e.g. align keyword → int).
   - the closed `:style` key schema in `lvgl-codegen.schema` — key roster +
     per-slot value shapes (+ keyword token-ref arm where :resolve exists).
   - the class-DSL prefix parser in `lvgl-codegen.expand` — :prefixes rows,
     matched longest-prefix-first (replaces the order-sensitive cond chain).

   Entry shape:
     {:proto    :PROP_BG_COLOR   ; proto StylePropertyType enum member
      :slot     :color_value     ; proto StyleProperty value slot
      :resolve  :color           ; token-resolution kind for keyword refs
                                 ; (absent = literal-only prop)
      :prefixes [[\"bg-\" :ref]] ; class-DSL prefixes + parse kind
                                 ; (:ref token-ref | :pct LV_PCT | :int)
      :xform    :align}          ; emit-side value translation (absent = none)"
  (:require [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- LVGL state selectors + breakpoint tiers (the class-DSL prefix surface,
;; shared with the nested :style state/breakpoint maps) --
;; LVGL state selector constants (lv_state_t in lvgl/src/core/lv_obj_style.h).
;; Guarded by the lv_state_t parity assertions in lvgl-parity-test: these
;; renumbered across LVGL majors, and a stale value silently binds the
;; style to the WRONG state.
(def ^:const lv-state-default 0x0000)

(def ^:const lv-state-pressed 0x0080)

(def ^:const lv-state-focused 0x0008)

(def ^:const lv-state-disabled 0x0200)

(def bp-min-index
  "Breakpoint name → minimum composite index (bp * 2)."
  {:sm 0 :md 2 :lg 4 :xl 6})

(def state-selector
  "State name → LVGL state selector."
  {:pressed lv-state-pressed :focused lv-state-focused :disabled lv-state-disabled})

;; LVGL widget-part selectors (lv_part_t in lvgl/src/core/lv_obj_style.h).
;; Hand-carried constants like the state selectors above, guarded by the
;; lv_part_t parity assertions in lvgl-parity-test: a stale value would
;; silently bind a style to the WRONG widget part. The wire's
;; StyleGroup.state_selector is a full lv_style_selector_t, so part bits
;; ride the existing field — no proto or renderer change.
(def ^:const lv-part-scrollbar 0x010000)

(def ^:const lv-part-indicator 0x020000)

(def ^:const lv-part-knob 0x030000)

(def ^:const lv-part-selected 0x040000)

(def ^:const lv-part-items 0x050000)

(def ^:const lv-part-cursor 0x060000)

(def part-selector
  "Widget-part name → LVGL part selector, OR'd into the style selector by
   expand (LV_PART_MAIN = 0 is the implicit default and has no key).

   Deliberate asymmetry with the class DSL: parts have NO class-string
   spelling — only the nested `:style {:indicator {…}}` map form scopes
   props to a part. Parts are rare, and a `indicator:`-style prefix would
   multiply the DSL's prefix combinatorics for no authoring win (decided
   in the EDN audits, docs/lvgl-factory/07 + 11)."
  {:scrollbar lv-part-scrollbar
   :indicator lv-part-indicator
   :knob lv-part-knob
   :selected lv-part-selected
   :items lv-part-items
   :cursor lv-part-cursor})

;; -- The registry --
(def props
  "Style prop key → registry entry (see ns docstring for the shape)."
  {;; Color properties (slot :color_value)
   :bg-color
   {:proto :PROP_BG_COLOR :slot :color_value :resolve :color :prefixes [["bg-" :ref]]}
   :text-color
   {:proto :PROP_TEXT_COLOR :slot :color_value :resolve :color :prefixes [["text-" :ref]]}
   :border-color {:proto :PROP_BORDER_COLOR
                  :slot :color_value
                  :resolve :color
                  :prefixes [["border-color-" :ref] ["border-" :ref]]}
   :bg-grad-color {:proto :PROP_BG_GRAD_COLOR :slot :color_value :resolve :color}
   :bg-image-recolor {:proto :PROP_BG_IMAGE_RECOLOR :slot :color_value :resolve :color}
   :outline-color {:proto :PROP_OUTLINE_COLOR :slot :color_value :resolve :color}
   :shadow-color {:proto :PROP_SHADOW_COLOR :slot :color_value :resolve :color}
   :image-recolor {:proto :PROP_IMAGE_RECOLOR :slot :color_value :resolve :color}
   :line-color {:proto :PROP_LINE_COLOR :slot :color_value :resolve :color}
   :arc-color {:proto :PROP_ARC_COLOR :slot :color_value :resolve :color}
   ;; String properties
   :text-font
   {:proto :PROP_TEXT_FONT :slot :string_value :resolve :font :prefixes [["font-" :ref]]}
   :bg-image-src {:proto :PROP_BG_IMAGE_SRC :slot :string_value}
   ;; uint_value properties (opacity, boolean-like, enum-like)
   :bg-opa
   {:proto :PROP_BG_OPA :slot :uint_value :resolve :opacity :prefixes [["opa-" :ref]]}
   :border-opa {:proto :PROP_BORDER_OPA :slot :uint_value}
   :align {:proto :PROP_ALIGN :slot :uint_value :xform :align}
   :bg-grad-dir {:proto :PROP_BG_GRAD_DIR :slot :uint_value}
   :bg-main-opa {:proto :PROP_BG_MAIN_OPA :slot :uint_value}
   :bg-grad-opa {:proto :PROP_BG_GRAD_OPA :slot :uint_value}
   :bg-image-opa {:proto :PROP_BG_IMAGE_OPA :slot :uint_value}
   :bg-image-recolor-opa {:proto :PROP_BG_IMAGE_RECOLOR_OPA :slot :uint_value}
   :bg-image-tiled {:proto :PROP_BG_IMAGE_TILED :slot :uint_value}
   :border-side {:proto :PROP_BORDER_SIDE :slot :uint_value}
   :border-post {:proto :PROP_BORDER_POST :slot :uint_value}
   :outline-opa {:proto :PROP_OUTLINE_OPA :slot :uint_value}
   :shadow-opa {:proto :PROP_SHADOW_OPA :slot :uint_value}
   :image-opa {:proto :PROP_IMAGE_OPA :slot :uint_value}
   :image-recolor-opa {:proto :PROP_IMAGE_RECOLOR_OPA :slot :uint_value}
   :line-rounded {:proto :PROP_LINE_ROUNDED :slot :uint_value}
   :line-opa {:proto :PROP_LINE_OPA :slot :uint_value}
   :arc-rounded {:proto :PROP_ARC_ROUNDED :slot :uint_value}
   :arc-opa {:proto :PROP_ARC_OPA :slot :uint_value}
   :text-opa {:proto :PROP_TEXT_OPA :slot :uint_value}
   :text-decor {:proto :PROP_TEXT_DECOR :slot :uint_value}
   :text-align {:proto :PROP_TEXT_ALIGN :slot :uint_value}
   :clip-corner {:proto :PROP_CLIP_CORNER :slot :uint_value}
   :opa {:proto :PROP_OPA :slot :uint_value}
   :opa-layered {:proto :PROP_OPA_LAYERED :slot :uint_value}
   :color-filter-opa {:proto :PROP_COLOR_FILTER_OPA :slot :uint_value}
   :anim-duration {:proto :PROP_ANIM_DURATION :slot :uint_value}
   :blend-mode {:proto :PROP_BLEND_MODE :slot :uint_value}
   :base-dir {:proto :PROP_BASE_DIR :slot :uint_value}
   :rotary-sensitivity {:proto :PROP_ROTARY_SENSITIVITY :slot :uint_value}
   :flex-flow {:proto :PROP_FLEX_FLOW :slot :uint_value}
   :flex-main-place {:proto :PROP_FLEX_MAIN_PLACE :slot :uint_value}
   :flex-cross-place {:proto :PROP_FLEX_CROSS_PLACE :slot :uint_value}
   :flex-track-place {:proto :PROP_FLEX_TRACK_PLACE :slot :uint_value}
   :flex-grow {:proto :PROP_FLEX_GROW :slot :uint_value :prefixes [["flex-grow-" :int]]}
   :grid-column-align {:proto :PROP_GRID_COLUMN_ALIGN :slot :uint_value}
   :grid-row-align {:proto :PROP_GRID_ROW_ALIGN :slot :uint_value}
   :grid-cell-x-align {:proto :PROP_GRID_CELL_X_ALIGN :slot :uint_value}
   :grid-cell-y-align {:proto :PROP_GRID_CELL_Y_ALIGN :slot :uint_value}
   ;; uint_value properties — dimensions, padding, radii
   ;; (the WASM renderer checks uint_value_tag for these)
   :border-width {:proto :PROP_BORDER_WIDTH
                  :slot :uint_value
                  :resolve :border-width
                  :prefixes [["border-w-" :ref]]}
   :radius
   {:proto :PROP_RADIUS :slot :uint_value :resolve :radius :prefixes [["rounded-" :ref]]}
   :pad-all
   {:proto :PROP_PAD_ALL :slot :uint_value :resolve :spacing :prefixes [["p-" :ref]]}
   :pad-gap
   {:proto :PROP_PAD_GAP :slot :uint_value :resolve :spacing :prefixes [["gap-" :ref]]}
   :margin-all
   {:proto :PROP_MARGIN_ALL :slot :uint_value :resolve :spacing :prefixes [["m-" :ref]]}
   :width {:proto :PROP_WIDTH
           :slot :uint_value
           :resolve :size
           :prefixes [["w-pct-" :pct] ["w-" :ref]]}
   :height {:proto :PROP_HEIGHT
            :slot :uint_value
            :resolve :size
            :prefixes [["h-pct-" :pct] ["h-" :ref]]}
   :pad-hor
   {:proto :PROP_PAD_HOR :slot :uint_value :resolve :spacing :prefixes [["px-" :ref]]}
   :pad-ver
   {:proto :PROP_PAD_VER :slot :uint_value :resolve :spacing :prefixes [["py-" :ref]]}
   ;; int_value properties
   :min-width
   {:proto :PROP_MIN_WIDTH :slot :int_value :resolve :size :prefixes [["min-w-" :ref]]}
   :max-width
   {:proto :PROP_MAX_WIDTH :slot :int_value :resolve :size :prefixes [["max-w-" :ref]]}
   :min-height
   {:proto :PROP_MIN_HEIGHT :slot :int_value :resolve :size :prefixes [["min-h-" :ref]]}
   :max-height
   {:proto :PROP_MAX_HEIGHT :slot :int_value :resolve :size :prefixes [["max-h-" :ref]]}
   :length {:proto :PROP_LENGTH :slot :int_value}
   :style-x {:proto :PROP_X :slot :int_value}
   :style-y {:proto :PROP_Y :slot :int_value}
   :transform-width {:proto :PROP_TRANSFORM_WIDTH :slot :int_value}
   :transform-height {:proto :PROP_TRANSFORM_HEIGHT :slot :int_value}
   :translate-x {:proto :PROP_TRANSLATE_X :slot :int_value}
   :translate-y {:proto :PROP_TRANSLATE_Y :slot :int_value}
   :scale-x {:proto :PROP_SCALE_X :slot :int_value}
   :scale-y {:proto :PROP_SCALE_Y :slot :int_value}
   :rotation {:proto :PROP_ROTATION :slot :int_value}
   :pivot-x {:proto :PROP_PIVOT_X :slot :int_value}
   :pivot-y {:proto :PROP_PIVOT_Y :slot :int_value}
   :skew-x {:proto :PROP_SKEW_X :slot :int_value}
   :skew-y {:proto :PROP_SKEW_Y :slot :int_value}
   :pad-top {:proto :PROP_PAD_TOP :slot :int_value}
   :pad-bottom {:proto :PROP_PAD_BOTTOM :slot :int_value}
   :pad-left {:proto :PROP_PAD_LEFT :slot :int_value}
   :pad-right {:proto :PROP_PAD_RIGHT :slot :int_value}
   :pad-row {:proto :PROP_PAD_ROW :slot :int_value}
   :pad-column {:proto :PROP_PAD_COLUMN :slot :int_value}
   :margin-top {:proto :PROP_MARGIN_TOP :slot :int_value}
   :margin-bottom {:proto :PROP_MARGIN_BOTTOM :slot :int_value}
   :margin-left {:proto :PROP_MARGIN_LEFT :slot :int_value}
   :margin-right {:proto :PROP_MARGIN_RIGHT :slot :int_value}
   :bg-main-stop {:proto :PROP_BG_MAIN_STOP :slot :int_value}
   :bg-grad-stop {:proto :PROP_BG_GRAD_STOP :slot :int_value}
   :outline-width {:proto :PROP_OUTLINE_WIDTH :slot :int_value}
   :outline-pad {:proto :PROP_OUTLINE_PAD :slot :int_value}
   :shadow-width {:proto :PROP_SHADOW_WIDTH :slot :int_value}
   :shadow-offset-x {:proto :PROP_SHADOW_OFFSET_X :slot :int_value}
   :shadow-offset-y {:proto :PROP_SHADOW_OFFSET_Y :slot :int_value}
   :shadow-spread {:proto :PROP_SHADOW_SPREAD :slot :int_value}
   :line-width {:proto :PROP_LINE_WIDTH :slot :int_value}
   :line-dash-width {:proto :PROP_LINE_DASH_WIDTH :slot :int_value}
   :line-dash-gap {:proto :PROP_LINE_DASH_GAP :slot :int_value}
   :arc-width {:proto :PROP_ARC_WIDTH :slot :int_value}
   :text-letter-space {:proto :PROP_TEXT_LETTER_SPACE :slot :int_value}
   :text-line-space {:proto :PROP_TEXT_LINE_SPACE :slot :int_value}
   :grid-cell-column-pos {:proto :PROP_GRID_CELL_COLUMN_POS :slot :int_value}
   :grid-cell-column-span {:proto :PROP_GRID_CELL_COLUMN_SPAN :slot :int_value}
   :grid-cell-row-pos {:proto :PROP_GRID_CELL_ROW_POS :slot :int_value}
   :grid-cell-row-span {:proto :PROP_GRID_CELL_ROW_SPAN :slot :int_value}
   ;; Shadow bundle (composite)
   :shadow
   {:proto :PROP_SHADOW :slot :shadow_value :resolve :shadow :prefixes [["shadow-" :ref]]}})

;; -- Derived: longest-prefix-first class-DSL parse table --
(def prefix-table
  "All [prefix prop parse-kind] rows from the registry, longest prefix
   first — a table lookup cannot be order-sensitive the way the old cond
   chain was (\"px-\" had to precede \"p-\" by hand)."
  (->> props
       (mapcat (fn [[prop entry]]
                 (for [[prefix parse-kind] (:prefixes entry)] [prefix prop parse-kind])))
       (sort-by (fn [[prefix]] [(- (count prefix)) prefix]))
       vec))

(defn match-prefix
  "Longest-prefix-first lookup of a class-token base string.
   Returns [prop parse-kind prefix] or nil when no registry prefix matches."
  [base]
  (some (fn [[prefix prop parse-kind]]
          (when (String/.startsWith ^String base prefix) [prop parse-kind prefix]))
        prefix-table))

;; -- Function schema registrations --
(m/=> match-prefix
      [:=> [:cat [:string {:min 1}]]
       [:maybe [:tuple :keyword [:enum :ref :pct :int] [:string {:min 1}]]]])