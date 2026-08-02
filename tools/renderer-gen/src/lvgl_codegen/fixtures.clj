(ns lvgl-codegen.fixtures
  "Generate .pb fixture files for visual regression tests.
   Each fixture is a minimal screen definition targeting one test scenario.
   Runs through the full codegen pipeline (validate → resolve → expand → serialize).
   Sibling generator: `lvgl-codegen.morph-fixtures` emits (base, target,
   patch) TRIPLES for the morph-parity dual oracle — deliberately separate
   (different test suites, different fixture shapes); add static
   visual-regression scenes HERE, patch scenarios THERE."
  (:require [clojure.java.io :as io]
            [lvgl-codegen.component :as component]
            [lvgl-codegen.core :as core]
            [lvgl-codegen.egress-fixtures :as egress]
            [lvgl-codegen.proto-ser :as proto-ser]
            [lvgl-codegen.schema :as schema]
            [malli.core :as m]
            [uigen.cmd-spec :as cmd-spec]
            [uigen.resolve :as res]))

(set! *warn-on-reflection* true)

;; R5b cmd-out: pre-encoded cmd.* templates the controller/routing fixtures
;; carry so the harness can decode the OPAQUE bytes the renderer relays via
;; host_command (uigen.cmd-spec — the same pre-encode the real screens use).
;; A value-widget click patches the WIDGET_VALUE varint slot; a video gesture
;; patches the NDC double slot (tap → RotateToNDC) or the DELTA varint (pinch →
;; SetZoomTableValue ±1).
(def ^:private zoom-value-cmd
  "cmd.DayCamera.SetZoomTableValue patched by a widget's int VALUE (the canonical
   single-int value command — a button/slider click ships its value)."
  (cmd-spec/cmd-spec "cmd.DayCamera.SetZoomTableValue" :PATCH_KIND_WIDGET_VALUE))

(def ^:private tap-rotate-cmd
  "cmd.RotaryPlatform.RotateToNDC patched by the tap NDC point (x/y verbatim),
   pinned to the DAY pane channel (RotateToNDC carries a `channel` enum leaf the
   pane selects — the fixture exercises a real non-default channel)."
  (cmd-spec/cmd-spec "cmd.RotaryPlatform.RotateToNDC"
                     :PATCH_KIND_NDC_X
                     {:fixed {:channel (res/video-channel-value "DAY")}}))

(def ^:private pinch-zoom-cmd
  "cmd.DayCamera.SetZoomTableValue patched by a pinch ±1 DELTA step."
  (cmd-spec/cmd-spec "cmd.DayCamera.SetZoomTableValue" :PATCH_KIND_DELTA))

(def ^:private roi-focus-cmd
  "cmd.DayCamera.FocusROI patched by an ROI rubber-band drag's TWO NDC corners:
   x1/y1 (down corner) → the NDC_X/Y slots, x2/y2 (up corner) → the NDC_X2/Y2
   slots, all verbatim. FocusROI has no value/channel leaf, so the varint-kind
   arg is unused (as with tap-rotate-cmd). frame_time/state_time stay 0 — the
   frame-timestamp stamping jettison applies is a named follow-on, not wired
   here (the root_template bakes 0)."
  (cmd-spec/cmd-spec "cmd.DayCamera.FocusROI" :PATCH_KIND_NDC_X))

(defn- make-screen
  "Wrap a widget tree in a minimal valid screen with a fullscreen root container.
   The root container provides a visible background so widgets render against
   a non-transparent canvas."
  [child-or-children]
  (let [children (if (vector? child-or-children) child-or-children [child-or-children])]
    {:type :screen
     :subjects {}
     :events {}
     :tree {:tag :lv_obj :class "w-pct-100 h-pct-100 bg-surface-0" :children children}}))

(defn- make-screen-raw
  "Wrap a raw root tree in a minimal valid screen (no extra container)."
  [tree]
  {:type :screen :subjects {} :events {} :tree tree})

;; ═══════════════════════════════════════════════════════════════════
;; Per-widget fixture definitions
;; ═══════════════════════════════════════════════════════════════════
(def ^:private per-widget-fixtures
  {"vr_obj" (make-screen {:tag :lv_obj :class "w-200 h-100 bg-surface-1"})
   "vr_label" (make-screen {:tag :lv_label :text "Hello" :class "font-font-body text-fg-0"})
   "vr_button" (make-screen {:tag :lv_button
                             :class "bg-accent-bg w-80 h-36 rounded-radius-button"
                             :children [{:tag :lv_label
                                         :text "Click"
                                         :class "text-accent-text font-font-body"}]})
   ;; Sized but otherwise UNSTYLED — every visual (fill, radius, pads, label
   ;; placement) comes from the theme. The theme-regression geometry tests
   ;; need this: vr_button's rounded-* class is a LOCAL override that would
   ;; mask a theme-default radius regression.
   "vr_button_unstyled" (make-screen {:tag :lv_button
                                      :class "w-80 h-36"
                                      :children [{:tag :lv_label :text "Click"}]})
   "vr_slider" (make-screen {:tag :lv_slider
                             :class "w-120"
                             :slider_props {:min_value 0 :max_value 100 :value 50}})
   "vr_arc" (make-screen {:tag :lv_arc
                          :class "w-80 h-80"
                          :arc_props {:start_angle 0
                                      :end_angle 270
                                      :bg_start_angle 0
                                      :bg_end_angle 360
                                      :min_value 0
                                      :max_value 100
                                      :value 75}})
   "vr_bar" (make-screen {:tag :lv_bar
                          :class "w-120"
                          :bar_props {:min_value 0 :max_value 100 :value 40}})
   "vr_switch" (make-screen {:tag :lv_switch :switch_props {:checked true}})
   "vr_checkbox"
   (make-screen
    {:tag :lv_checkbox :text "Check" :class "text-fg-0" :checkbox_props {:checked true}})
   "vr_spinner" (make-screen {:tag :lv_spinner
                              :class "w-48 h-48"
                              :spinner_props {:spin_time 1000 :arc_length 60}})
   "vr_led" (make-screen {:tag :lv_led
                          :led_props {:color {:r 0 :g 200 :b 0} :brightness 255}})
   "vr_scale" (make-screen {:tag :lv_scale
                            :class "w-120 h-48"
                            :scale_props {:total_tick_count 11
                                          :major_tick_every 5
                                          :label_show true
                                          :min_value 0
                                          :max_value 100}})
   ;; Scale extensions: custom text_src labels (one per major tick),
   ;; post_draw, and two colored sections (green low / red high tick band,
   ;; each with a distinct MAIN-part band — blue low / orange high — so a
   ;; main_color/main_width decode regression breaks the render).
   "vr_scale_sections" (make-screen
                        {:tag :lv_scale
                         :class "w-140 h-48 px-spacing-xl"
                         :scale_props {:total_tick_count 11
                                       :major_tick_every 5
                                       :label_show true
                                       :min_value 0
                                       :max_value 100
                                       :text_src "L\nM\nH"
                                       :post_draw true
                                       :sections [{:range_min 0
                                                   :range_max 40
                                                   :color {:r 0 :g 200 :b 0}
                                                   :width 4
                                                   :main_color {:r 0 :g 80 :b 230}
                                                   :main_width 4}
                                                  {:range_min 60
                                                   :range_max 100
                                                   :color {:r 230 :g 50 :b 50}
                                                   :width 4
                                                   :main_color {:r 255 :g 140 :b 0}
                                                   :main_width 4}]}})
   "vr_spinbox" (make-screen
                 {:tag :lv_spinbox
                  :id "spinbox"
                  :class "w-120"
                  :spinbox_props
                  {:min_value 0 :max_value 999 :value 42 :step 5 :digit_count 3}})
   "vr_dropdown" (make-screen {:tag :lv_dropdown
                               :class "w-120"
                               :dropdown_props {:options "A\nB\nC" :selected 1}})
   "vr_roller" (make-screen {:tag :lv_roller
                             :class "w-120"
                             :roller_props
                             {:options "One\nTwo\nThree" :selected 0 :visible_row_count 3}})
   "vr_textarea" (make-screen {:tag :lv_textarea
                               :id "textarea"
                               :class "w-120"
                               :textarea_props {:placeholder "Type" :one_line true}})
   "vr_line" (make-screen {:tag :lv_line
                           :class "w-120 h-48"
                           :line_props {:points [{:x 0 :y 0} {:x 50 :y 30}
                                                 {:x 100 :y 10}]}})
   "vr_btnmatrix" (make-screen {:tag :lv_buttonmatrix
                                :class "w-200 h-80"
                                :buttonmatrix_props {:map_str "A\nB\nC\n"}})
   "vr_table" (make-screen {:tag :lv_table
                            :class "w-200 h-80"
                            :table_props {:row_count 2 :column_count 2}})
   ;; Props-reached-LVGL twins: each differs from the fixture above ONLY in
   ;; the widget_props payload, so a renderer that creates the right class
   ;; but drops the props renders the pair IDENTICALLY (widget_identity in
   ;; wasm_harness/tests/visual_regression.rs asserts they differ).
   "vr_btnmatrix_map2" (make-screen {:tag :lv_buttonmatrix
                                     :class "w-200 h-80"
                                     :buttonmatrix_props {:map_str "X\nY\n"}})
   "vr_table_3x1" (make-screen {:tag :lv_table
                                :class "w-200 h-80"
                                :table_props {:row_count 3 :column_count 1}})})

;; ═══════════════════════════════════════════════════════════════════
;; Class reorder invariance fixtures (must render identically)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private reorder-fixtures
  {"vr_reorder_a" (make-screen
                   {:tag :lv_obj
                    :class "bg-surface-1 p-spacing-md w-200 h-100 rounded-radius-card"})
   "vr_reorder_b" (make-screen
                   {:tag :lv_obj
                    :class "rounded-radius-card w-200 h-100 p-spacing-md bg-surface-1"})
   "vr_reorder_text_a" (make-screen {:tag :lv_label
                                     :text "Reorder"
                                     :class "font-font-body text-fg-0 w-200 h-content"})
   "vr_reorder_text_b" (make-screen {:tag :lv_label
                                     :text "Reorder"
                                     :class "w-200 h-content text-fg-0 font-font-body"})
   "vr_reorder_multi_a"
   (make-screen-raw
    {:tag :lv_obj
     :class
     "flex flex-col gap-spacing-sm bg-surface-2 p-spacing-lg w-200 h-200
             border-edge-0 border-w-border-width-default rounded-radius-default"
     :children [{:tag :lv_label :text "A" :class "text-fg-0 font-font-body"}
                {:tag :lv_label :text "B" :class "text-fg-0 font-font-body"}]})
   "vr_reorder_multi_b"
   (make-screen-raw
    {:tag :lv_obj
     :class
     "rounded-radius-default border-w-border-width-default border-edge-0
             h-200 w-200 p-spacing-lg bg-surface-2 gap-spacing-sm flex-col flex"
     :children [{:tag :lv_label :text "A" :class "text-fg-0 font-font-body"}
                {:tag :lv_label :text "B" :class "text-fg-0 font-font-body"}]})
   ;; Breakpoint-prefixed tokens: tier precedence must NOT depend on
   ;; class-string order — _b lists the tiers high-to-low.
   "vr_reorder_bp_a"
   (make-screen
    {:tag :lv_obj
     :class
     "w-64 h-48 bg-status-error
                         md:w-96 md:bg-status-warning
                         xl:w-160 xl:bg-accent-bg"
     :style {:border-width 0 :radius 0}})
   "vr_reorder_bp_b"
   (make-screen
    {:tag :lv_obj
     :class
     "xl:bg-accent-bg xl:w-160
                         md:bg-status-warning md:w-96
                         bg-status-error h-48 w-64"
     :style {:border-width 0 :radius 0}})})

;; ═══════════════════════════════════════════════════════════════════
;; Class modification fixtures (adding a class must change image)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private mod-child {:tag :lv_label :text "Content" :class "text-fg-0 font-font-body"})

(def ^:private modification-fixtures
  {"vr_mod_base" (make-screen
                  {:tag :lv_obj :class "w-200 h-120 bg-surface-1" :children [mod-child]})
   "vr_mod_bg" (make-screen {:tag :lv_obj
                             :class "w-200 h-120 bg-surface-1 bg-accent-bg"
                             :children [mod-child]})
   "vr_mod_padding" (make-screen {:tag :lv_obj
                                  :class "w-200 h-120 bg-surface-1 p-spacing-xl"
                                  :children [mod-child]})
   "vr_mod_border"
   (make-screen {:tag :lv_obj
                 :class "w-200 h-120 bg-surface-1 border-edge-0 border-w-border-width-focus"
                 :children [mod-child]})
   ;; The radius modifier must NOT restate a theme-tier default (the theme
   ;; already rounds panel-tier containers with :radius-card): a class equal to
   ;; the ambient default renders 0 changed pixels and the differ-assert has
   ;; nothing to see. Square-off is the honest override: theme 4px -> 0px.
   "vr_mod_radius" (make-screen {:tag :lv_obj
                                 :class "w-200 h-120 bg-surface-1 rounded-radius-none"
                                 :children [mod-child]})})

;; ═══════════════════════════════════════════════════════════════════
;; Value change fixtures (different prop values must change image)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private value-change-fixtures
  {"vr_slider_50" (make-screen {:tag :lv_slider
                                :class "w-120"
                                :slider_props {:min_value 0 :max_value 100 :value 50}})
   "vr_slider_90" (make-screen {:tag :lv_slider
                                :class "w-120"
                                :slider_props {:min_value 0 :max_value 100 :value 90}})
   "vr_arc_75" (make-screen {:tag :lv_arc
                             :class "w-80 h-80"
                             :arc_props {:start_angle 0
                                         :end_angle 270
                                         :bg_start_angle 0
                                         :bg_end_angle 360
                                         :min_value 0
                                         :max_value 100
                                         :value 75}})
   "vr_arc_25" (make-screen {:tag :lv_arc
                             :class "w-80 h-80"
                             :arc_props {:start_angle 0
                                         :end_angle 270
                                         :bg_start_angle 0
                                         :bg_end_angle 360
                                         :min_value 0
                                         :max_value 100
                                         :value 25}})
   "vr_bar_40" (make-screen {:tag :lv_bar
                             :class "w-120"
                             :bar_props {:min_value 0 :max_value 100 :value 40}})
   "vr_bar_80" (make-screen {:tag :lv_bar
                             :class "w-120"
                             :bar_props {:min_value 0 :max_value 100 :value 80}})
   "vr_switch_on" (make-screen {:tag :lv_switch :switch_props {:checked true}})
   "vr_switch_off" (make-screen {:tag :lv_switch :switch_props {:checked false}})})

;; ═══════════════════════════════════════════════════════════════════
;; Composite fixtures (nested hierarchies)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private composite-fixtures
  {"vr_flex_col" (make-screen-raw
                  {:tag :lv_obj
                   :class "flex flex-col gap-spacing-sm w-200 h-content bg-surface-1"
                   :children
                   [{:tag :lv_label :text "One" :class "text-fg-0 font-font-body"}
                    {:tag :lv_label :text "Two" :class "text-fg-0 font-font-body"}
                    {:tag :lv_label :text "Three" :class "text-fg-0 font-font-body"}]})
   "vr_flex_row" (make-screen-raw
                  {:tag :lv_obj
                   :class "flex flex-row gap-spacing-sm w-content h-content bg-surface-1"
                   :children
                   [{:tag :lv_label :text "One" :class "text-fg-0 font-font-body"}
                    {:tag :lv_label :text "Two" :class "text-fg-0 font-font-body"}
                    {:tag :lv_label :text "Three" :class "text-fg-0 font-font-body"}]})
   "vr_nested_deep" (make-screen-raw
                     {:tag :lv_obj
                      :class "w-200 h-200 bg-surface-0 p-spacing-sm"
                      :children [{:tag :lv_obj
                                  :class "w-pct-100 h-content bg-surface-1 p-spacing-sm"
                                  :children [{:tag :lv_label
                                              :text "Deep"
                                              :class "text-fg-0 font-font-heading"}]}]})})

;; ═══════════════════════════════════════════════════════════════════
;; Font fixtures — verify custom fonts render correctly
;; ═══════════════════════════════════════════════════════════════════
(def ^:private font-fixtures
  {"vr_font_body"
   (make-screen {:tag :lv_label :text "B612 Body 16px" :class "font-font-body text-fg-0"})
   "vr_font_heading"
   (make-screen {:tag :lv_label :text "Orbitron 22px" :class "font-font-heading text-fg-0"})
   "vr_font_14" (make-screen {:tag :lv_label
                              :text "B612 Small 14px"
                              :class "font-font-body-sm text-fg-0"})
   "vr_font_22" (make-screen {:tag :lv_label
                              :text "Orbitron Heading 22px"
                              :class "font-font-heading text-fg-0"})})

;; ═══════════════════════════════════════════════════════════════════
;; SVG image fixtures — verify ThorVG image rendering
;; ═══════════════════════════════════════════════════════════════════
(def ^:private svg-fixtures
  {;; TTF rasterized at runtime: size 26 is NOT compiled in and has no
   ;; .bin — only the TinyTTF P:fonts/ path can satisfy it. The paired
   ;; body-font label is the differ baseline (fallback would render 16
   ;; and match it too closely).
   "vr_ttf_font" (make-screen
                  {:tag :lv_label :text "TTF 26 Aa" :class "font-font-ttf-probe text-fg-0"})
   "vr_ttf_font_base" (make-screen
                       {:tag :lv_label :text "TTF 26 Aa" :class "font-font-body text-fg-0"})
   ;; PNG via the same P: WASI drive (lodepng decode path)
   "vr_png_image" (make-screen {:tag :lv_image
                                :class "w-64 h-64"
                                :image_props {:src "P:images/test_square.png"}})
   ;; Image pivot + rotation (0.1° units): the test square rotated 45°
   ;; about its top-left corner — a strong pixel signature vs vr_png_image.
   "vr_image_pivot"
   (make-screen
    {:tag :lv_image
     :class "w-64 h-64"
     :image_props
     {:src "P:images/test_square.png" :has_pivot true :pivot_x 0 :pivot_y 0 :rotation 450}})
   "vr_svg_icon" (make-screen {:tag :lv_image
                               :class "w-64 h-64"
                               :image_props {:src "P:icons/test_icon.svg"}})
   "vr_svg_circle" (make-screen {:tag :lv_image
                                 :class "w-64 h-64"
                                 :image_props {:src "P:icons/test_circle.svg"}})
   "vr_svg_rect" (make-screen {:tag :lv_image
                               :class "w-64 h-64"
                               :image_props {:src "P:icons/test_rect.svg"}})
   "vr_svg_shapes" (make-screen {:tag :lv_image
                                 :class "w-96 h-96"
                                 :image_props {:src "P:icons/test_shapes.svg"}})
   "vr_no_image" (make-screen {:tag :lv_obj :class "w-64 h-64 bg-surface-1"})})

;; ═══════════════════════════════════════════════════════════════════
;; HUD breakpoint fixtures — verify responsive @hud-btn styling
;; ═══════════════════════════════════════════════════════════════════
(def ^:private hud-btn-child {:tag :lv_label :text "+" :class "text-fg-0 font-font-body"})

(def ^:private hud-breakpoint-fixtures
  {;; Single HUD button — used to verify size/border changes across breakpoints
   "vr_hud_btn" (make-screen-raw {:tag :lv_obj
                                  :class "w-pct-100 h-pct-100 bg-surface-0"
                                  :children [{:tag :lv_button
                                              :class "@hud-btn"
                                              :style {:radius 0}
                                              :children [hud-btn-child]}]})
   ;; Two HUD buttons in a column — verify flex-col layout and gap scaling
   "vr_hud_col"
   (make-screen-raw
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [{:tag :lv_obj
       :class
       "flex flex-col gap-spacing-xs w-content h-content
               md:gap-spacing-sm lg:gap-spacing-md"
       :style {:bg-opa 0 :border-width 0 :radius 0 :pad-all 0}
       :children [{:tag :lv_button
                   :class "@hud-btn"
                   :style {:radius 0}
                   :children [{:tag :lv_label :text "+" :class "text-fg-0 font-font-body"}]}
                  {:tag :lv_button
                   :class "@hud-btn"
                   :style {:radius 0}
                   :children
                   [{:tag :lv_label :text "-" :class "text-fg-0 font-font-body"}]}]}]})})

;; ═══════════════════════════════════════════════════════════════════
;; Breakpoint color fixtures — verify bg color changes per breakpoint
;; Uses large filled boxes (easy to detect) with distinct colors:
;;   bp0/sm: red (status-error)
;;   bp1/md: amber (status-warning)
;;   bp2/lg: green (status-success)
;;   bp3/xl: violet-deep (accent-bg)
;; ═══════════════════════════════════════════════════════════════════
;; ═══════════════════════════════════════════════════════════════════
;; Tabview fixtures — the real lv_tabview arm: tab_names zip with the
;; node's children (child i = tab i's page content); active_index picks
;; the visible page (the inactive pages sit offscreen, scroll-snapped);
;; an :in-tab-bar child lands in the tab bar slot.
;; ═══════════════════════════════════════════════════════════════════
(def ^:private tabview-fixtures
  {"vr_tabview" (make-screen
                 {:tag :lv_tabview
                  :tabview_props {:tab_names ["Alpha" "Beta" "Gamma"]
                                  :tab_bar_size 48
                                  :active_index 1
                                  ;; Demo-parity surface: shifts the tab
                                  ;; buttons AND the bar's content origin
                                  ;; (the cyan decor lands at x = pad).
                                  :tab_bar_pad_left 60}
                  :children [{:tag :lv_obj :class "w-pct-100 h-pct-100 bg-status-error"}
                             {:tag :lv_obj :class "w-pct-100 h-pct-100 bg-status-success"}
                             {:tag :lv_obj :class "w-pct-100 h-pct-100 bg-status-warning"}
                             {:tag :lv_obj
                              :in-tab-bar true
                              :class "w-16 h-16"
                              :style {:bg-color "#22D3EE"}
                              :flags #{:ignore-layout}}]})})

;; ═══════════════════════════════════════════════════════════════════
;; Chart fixtures — the real lv_chart arm: LINE type, explicit div lines
;; ([0 12] pins the explicit-zero hdiv path), two fixed-color PRIMARY_Y
;; series written by index. The _fade twin enables the demo fader
;; draw-event (gradient area under each line segment) — the harness
;; asserts it changes pixels vs the plain render.
;; ═══════════════════════════════════════════════════════════════════
(def ^:private chart-series-data
  [{:color {:r 239 :g 68 :b 68} :axis :primary-y :values [10 25 18 40 32 48 27 35]}
   {:color {:r 59 :g 130 :b 246} :axis :primary-y :values [45 12 30 8 38 22 41 15]}])

(def ^:private chart-fixtures
  {"vr_chart" (make-screen
               {:tag :lv_chart
                :id "chart"
                :class "w-260 h-160"
                :chart_props
                {:type :line :point_count 8 :div_lines [0 12] :series chart-series-data}})
   "vr_chart_fade" (make-screen {:tag :lv_chart
                                 :id "chart"
                                 :class "w-260 h-160"
                                 :chart_props {:type :line
                                               :point_count 8
                                               :div_lines [0 12]
                                               :series chart-series-data
                                               :fade_area true}})})

(def ^:private cross-cutting-fixtures
  ;; New WidgetNode surface: grid templates (px + [:fr n] encodings), bare,
  ;; states (keyword sets OR'd to lv_state_t at emit), :cell sugar.
  {"vr_grid" (make-screen {:tag :lv_obj
                           :grid-cols [100 [:fr 1]]
                           :grid-rows [60 60]
                           :class "w-220 h-140 bg-surface-1"
                           :children [{:tag :lv_obj
                                       :class "bg-status-error"
                                       :style {:grid-cell-column-pos 0
                                               :grid-cell-row-pos 0
                                               :grid-cell-column-span 1
                                               :grid-cell-row-span 1}}
                                      {:tag :lv_obj
                                       :class "bg-status-success"
                                       :style {:grid-cell-column-pos 1
                                               :grid-cell-row-pos 1
                                               :grid-cell-column-span 1
                                               :grid-cell-row-span 1}}]})
   ;; :cell sugar mirror of vr_grid — must render identically (the harness
   ;; asserts framebuffer identity between the two).
   "vr_cell" (make-screen
              {:tag :lv_obj
               :grid-cols [100 [:fr 1]]
               :grid-rows [60 60]
               :class "w-220 h-140 bg-surface-1"
               :children [{:tag :lv_obj :class "bg-status-error" :cell {:col 0 :row 0}}
                          {:tag :lv_obj :class "bg-status-success" :cell {:col 1 :row 1}}]})
   "vr_bare" (make-screen {:tag :lv_obj :bare true :class "w-200 h-100"})
   "vr_bare_base" (make-screen {:tag :lv_obj :class "w-200 h-100"})
   "vr_state_disabled" (make-screen {:tag :lv_button
                                     :states #{:disabled}
                                     :class "w-120 h-48 bg-accent-bg"
                                     :children [{:tag :lv_label
                                                 :text "Off"
                                                 :class "text-fg-0 font-font-body"}]})
   "vr_state_base" (make-screen {:tag :lv_button
                                 :class "w-120 h-48 bg-accent-bg"
                                 :children [{:tag :lv_label
                                             :text "Off"
                                             :class "text-fg-0 font-font-body"}]})})

(def ^:private aa-probe-fixtures
  ;; Anti-aliasing regression probe: a red arc + diagonal line on the
  ;; dark surface. AA edges carry INTERMEDIATE red levels between bg and
  ;; fg; a config change that disables AA makes edges hard and the
  ;; intermediate-pixel count collapses (the harness asserts a floor).
  {"vr_aa_probe" (make-screen {:tag :lv_arc
                               :class "w-80 h-80"
                               :arc_props {:start_angle 0
                                           :end_angle 270
                                           :bg_start_angle 0
                                           :bg_end_angle 360
                                           :min_value 0
                                           :max_value 100
                                           :value 75}
                               :style {:arc-color :status-error :arc-width 6}})})

(def ^:private breakpoint-color-fixtures
  {"vr_bp_color"
   (make-screen-raw
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [{:tag :lv_obj
       :class
       "w-96 h-96 bg-status-error
               md:bg-status-warning lg:bg-status-success xl:bg-accent-bg"
       :style {:border-width 0 :radius 0 :opa 255}}]})
   ;; Breakpoint × state composition: the pressed-state bg differs PER
   ;; TIER (combined "lg:pressed:" prefix) — pressed is red below lg,
   ;; green at lg and up.
   "vr_bp_state"
   (make-screen
    {:tag :lv_button
     :class
     "w-160 h-64 bg-surface-2
                         pressed:bg-status-error
                         lg:pressed:bg-status-success"
     :children [{:tag :lv_label :text "Press" :class "text-fg-0 font-font-body"}]})
   ;; Size + padding + font tiers (color per tier is vr_bp_color's job —
   ;; the tier color here only tags the box so tests can MEASURE it):
   ;; width 64→96→128→160, pad xs→sm→md→xl, font 12→16→22→32.
   "vr_bp_matrix"
   (make-screen-raw
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [{:tag :lv_obj
       :class "flex flex-col gap-spacing-sm p-spacing-sm w-pct-100 h-pct-100"
       :children
       [{:tag :lv_obj
         :class
         "w-64 h-48 bg-status-error
                 md:w-96 md:bg-status-warning
                 lg:w-128 lg:bg-status-success
                 xl:w-160 xl:bg-accent-bg"
         :style {:border-width 0 :radius 0 :opa 255}}
        {:tag :lv_obj
         :class
         "w-content h-content p-spacing-xs
                 border-edge-0 border-w-border-width-default
                 md:p-spacing-sm lg:p-spacing-md xl:p-spacing-xl"
         :children
         [{:tag :lv_obj :class "w-48 h-24 bg-fg-0" :style {:border-width 0 :radius 0}}]}
        {:tag :lv_label
         :text "BP TIER"
         :class
         "text-fg-0 font-font-caption
                 md:font-font-body
                 lg:font-font-heading
                 xl:font-font-heading-xl"}]}]})})

;; ═══════════════════════════════════════════════════════════════════
;; Controller bindings: subjects, :bind/:bind-fmt, :show-when, events
;; (consumed by the harness's controller_bindings test module together
;; with the su_* StateUpdate payloads below)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private controller-binding-fixtures
  (merge
   egress/form-screens
   {"vr_bind_slider" {:type :screen
                      :subjects {:volume {:type :int :default 30}}
                      :events {}
                      :tree {:tag :lv_obj
                             :class "w-pct-100 h-pct-100 bg-surface-0"
                             :children [{:tag :lv_slider
                                         :bind {:value :volume}
                                         :style {:width 200 :height 20 :align :center}}]}}
   ;; A value-bound spinbox: lv_spinbox has no lv_*_bind_value, so the subject
   ;; must drive lv_spinbox_set_value through a custom observer (mirroring the
   ;; bar arm). Regression fixture for that binding arm — the subject default
   ;; (30) differs from the widget default (0), so a state update visibly moves
   ;; the rendered digits only when the observer is actually wired.
    "vr_bind_spinbox" {:type :screen
                       :subjects {:volume {:type :int :default 30}}
                       :events {}
                       :tree {:tag :lv_obj
                              :class "w-pct-100 h-pct-100 bg-surface-0"
                              :children [{:tag :lv_spinbox
                                          :id "spinbox"
                                          :bind {:value :volume}
                                          :spinbox_props {:min_value 0 :max_value 999 :digit_count 3}
                                          :style {:width 200 :height 40 :align :center}}]}}
    "vr_bind_label_fmt" {:type :screen
                         :subjects {:volume {:type :int :default 30}}
                         :events {}
                         :tree {:tag :lv_obj
                                :class "w-pct-100 h-pct-100 bg-surface-0"
                                :children [{:tag :lv_label
                                            :bind {:text :volume}
                                            :bind-fmt {:text "Vol %d%%"}
                                            :style {:width 200 :align :center}}]}}
   ;; Bindings on the SCREEN ROOT node itself: the root finalizes AFTER
   ;; streaming decode (children finalize during it), so the deferred
   ;; binding flush must run after the root's finalize — a flush placed
   ;; before it attaches to a half-initialized widget. Regression fixture
   ;; for that ordering.
    "vr_root_bind" {:type :screen
                    :subjects {:volume {:type :int :default 30}}
                    :events {}
                    :tree {:tag :lv_label
                           :bind {:text :volume}
                           :bind-fmt {:text "Root %d"}
                           :style {:width 200 :align :center}}}
    "vr_show_when" {:type :screen
                    :subjects {:show {:type :int :default 1}}
                    :events {}
                    :tree {:tag :lv_obj
                           :class "w-pct-100 h-pct-100 bg-surface-0"
                           :children [{:tag :lv_label :text "anchor" :x 10 :y 10}
                                      {:tag :lv_obj
                                       :show-when {:subject :show :eq 1}
                                       :class "bg-status-error"
                                       :style {:width 120 :height 60 :align :center}}]}}
   ;; A button at FIXED coords (clicked via controls_host_message at its
   ;; center, 110/70) that sets a subject a formatted label displays.
    "vr_event_set"
    {:type :screen
     :subjects {:val {:type :int :default 0}}
     :events {:bump {:set :val :to 7}}
     :tree {:tag :lv_obj
            :class "w-pct-100 h-pct-100 bg-surface-0"
            :children
            [{:tag :lv_button
              :event :bump
              :x 60
              :y 50
              :style {:width 100 :height 40}
              :children [{:tag :lv_label :text "Go"}]}
             {:tag :lv_label :bind {:text :val} :bind-fmt {:text "v=%d"} :x 60 :y 120}]}}
   ;; Toggle 0↔1 wired to a show-when box — two clicks must round-trip
   ;; the screen back to its initial pixels.
    "vr_event_toggle" {:type :screen
                       :subjects {:flag {:type :int :default 1}}
                       :events {:flip {:toggle :flag}}
                       :tree {:tag :lv_obj
                              :class "w-pct-100 h-pct-100 bg-surface-0"
                              :children [{:tag :lv_button
                                          :event :flip
                                          :x 60
                                          :y 50
                                          :style {:width 100 :height 40}
                                          :children [{:tag :lv_label :text "Flip"}]}
                                         {:tag :lv_obj
                                          :show-when {:subject :flag :eq 1}
                                          :class "bg-status-error"
                                          :style {:width 120 :height 60 :align :center}}]}}
   ;; Subject mutation that ALSO notifies the host: the click mutates :val AND
   ;; relays a SetZoomTableValue cmd carrying the static int-value (3) via
   ;; host_command (R5b cmd-out — the harness decodes the OPAQUE bytes).
    "vr_event_notify"
    {:type :screen
     :subjects {:val {:type :int :default 0}}
     :events {:ping {:set :val :to 3 :notify-host true :int-value 3 :cmd zoom-value-cmd}}
     :tree {:tag :lv_obj
            :class "w-pct-100 h-pct-100 bg-surface-0"
            :children [{:tag :lv_button
                        :event :ping
                        :x 60
                        :y 50
                        :style {:width 100 :height 40}
                        :children [{:tag :lv_label :text "Ping"}]}]}}
   ;; A slider whose VALUE_CHANGED event ships the widget's current value to
   ;; the host (include-value): clicking the track sets a value, fires the
   ;; event, and relays a SetZoomTableValue cmd whose WIDGET_VALUE slot carries
   ;; the live slider value (R5b cmd-out — the harness decodes the bytes).
    "vr_event_widget_value"
    {:type :screen
     :subjects {}
     :events {:vol {:trigger :value-changed :include-value true :cmd zoom-value-cmd}}
     :tree {:tag :lv_obj
            :class "w-pct-100 h-pct-100 bg-surface-0"
            :children
            [{:tag :lv_slider :event :vol :x 60 :y 60 :style {:width 200 :height 20}}]}}
   ;; R5a cmd-by-value :bool-set — an lv_switch whose VALUE_CHANGED relays the
   ;; FIXED template its checked state index-selects: unchecked(0)→SetAutoFocus
   ;; {value false}, checked(1)→{value true}, both channel DAY. The harness
   ;; clicks the switch and decodes the OPAQUE bytes (include-value → the
   ;; checked state IS the index).
    "vr_event_by_value_bool"
    {:type :screen
     :subjects {}
     :events {:af {:trigger :value-changed
                   :include-value true
                   :notify-host true
                   :cmd-by-value egress/auto-focus-by-value}}
     :tree {:tag :lv_obj
            :class "w-pct-100 h-pct-100 bg-surface-0"
            :children
            [{:tag :lv_switch :event :af :x 60 :y 60 :style {:width 80 :height 40}}]}}
   ;; R5a cmd-by-value :enum — an lv_slider (0..2) standing in for a dropdown
   ;; index (the C path only reads the widget int; driving a real lv_dropdown
   ;; via synthetic pointer is fragile). Value i relays the i-th SetFxMode
   ;; option's FIXED template (value 0→mode A, 1→mode B, 2→mode C).
    "vr_event_by_value_index" {:type :screen
                               :subjects {}
                               :events {:fx {:trigger :value-changed
                                             :include-value true
                                             :notify-host true
                                             :cmd-by-value egress/fx-mode-by-value}}
                               :tree {:tag :lv_obj
                                      :class "w-pct-100 h-pct-100 bg-surface-0"
                                      :children [{:tag :lv_slider
                                                  :event :fx
                                                  :x 60
                                                  :y 60
                                                  :style {:width 200 :height 20}
                                                  :slider_props
                                                  {:min_value 0 :max_value 2 :value 1}}]}}
   ;; R5a cmd-by-value OUT-OF-RANGE — an lv_slider (0..5) with only 3 templates.
   ;; Driving it to value 4 selects a non-existent index → the renderer emits
   ;; NOTHING and does NOT trap (the bounds-check fail-loud). The harness asserts
   ;; an empty host-command channel and a completed (non-panicking) run.
    "vr_event_by_value_oob" {:type :screen
                             :subjects {}
                             :events {:fx {:trigger :value-changed
                                           :include-value true
                                           :notify-host true
                                           :cmd-by-value egress/fx-mode-by-value}}
                             :tree {:tag :lv_obj
                                    :class "w-pct-100 h-pct-100 bg-surface-0"
                                    :children [{:tag :lv_slider
                                                :event :fx
                                                :x 60
                                                :y 60
                                                :style {:width 200 :height 20}
                                                :slider_props
                                                {:min_value 0 :max_value 5 :value 1}}]}}
   ;; R5a :action fixed template — a parameterless Lrf.Measure baked at gen time
   ;; (patches []) and relayed verbatim on click via the existing single-cmd
   ;; path. Proves fixed-cmd-spec end-to-end through the renderer's cmd_patch
   ;; patch_count-0 emit.
    "vr_event_action_fixed" {:type :screen
                             :subjects {}
                             :events {:measure {:notify-host true :cmd egress/measure-action-cmd}}
                             :tree {:tag :lv_obj
                                    :class "w-pct-100 h-pct-100 bg-surface-0"
                                    :children [{:tag :lv_button
                                                :event :measure
                                                :x 60
                                                :y 50
                                                :style {:width 100 :height 40}
                                                :children [{:tag :lv_label
                                                            :text "Measure"}]}]}}}))

;; ═══════════════════════════════════════════════════════════════════
;; Host proxy: registry + reports + gestures + mode radio (consumed by
;; the harness's host_proxy test module with the su_proxy_* payloads)
;; ═══════════════════════════════════════════════════════════════════
(def ^:private host-proxy-fixtures
  {;; A draggable proxy over a host-event button (static click-through
   ;; oracle), a visibility subject (hidden-report oracle), and the mode
   ;; radio-group (one INT subject drives everything — the jettison
   ;; edit-mode-palette pattern).
   ;; Authored-override oracle: wire-authored style props on a host_proxy
   ;; ROOT must beat the renderer's C default look (pad/bg/radius are
   ;; AUTHORABLE, not renderer-owned). Authored pad-all 0 + border-width 0
   ;; place the content chip flush with the proxy origin — inexpressible if
   ;; the defaults were LVGL local styles (local outranks every added
   ;; style, so the authored pad would silently lose).
   ;; Bare-root oracle: `bare` on a host_proxy ROOT strips the renderer's
   ;; default look along with the theme (bare means bare — the same strip a
   ;; bare obj applies to the panel card). The chip sits flush because NO
   ;; pad applies at all, pinning "bare proxy = raw frame" as the contract.
   "vr_proxy_bare"
   {:type :screen
    :subjects {}
    :events {}
    :tree
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [{:tag :lv_host_proxy
       :id "pb"
       :bare true
       :x 40
       :y 260
       :style {:width 152 :height 92}
       :host_proxy_props {:proxy_id "pb"
                          :mode :draggable
                          :min_w 80
                          :min_h 60
                          :max_w 360
                          :max_h 240
                          :handle_size 16
                          :z 7}
       :children [{:tag :lv_obj
                   :style {:width 60 :height 30 :border-width 0 :radius 0}}]}]}}
   "vr_proxy_pad"
   {:type :screen
    :subjects {}
    :events {}
    :tree
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [{:tag :lv_host_proxy
       :id "pp"
       :x 40
       :y 40
       :style {:width 160 :height 100 :pad-all 0 :border-width 0}
       :host_proxy_props {:proxy_id "pp"
                          :mode :draggable
                          :min_w 80
                          :min_h 60
                          :max_w 360
                          :max_h 240
                          :handle_size 16
                          :z 7}
       :children [{:tag :lv_obj
                   :style {:width 60 :height 30 :border-width 0 :radius 0}}]}]}}
   "vr_proxy"
   {:type :screen
    :subjects {:proxy-mode {:type :int :default 1} :proxy-show {:type :int :default 1}}
    :events {:mode-static {:set :proxy-mode :to 0}
             :mode-draggable {:set :proxy-mode :to 1}
             :mode-resizable {:set :proxy-mode :to 2}
             :mode-alignable {:set :proxy-mode :to 3}
             ;; The static-mode click-through oracle relays a SetZoomTableValue
             ;; cmd carrying 42 via host_command (R5b — the harness decodes it).
             :under {:int-value 42 :cmd zoom-value-cmd}}
    ;; Geometry fits the harness's 400x300 framebuffer WITH headroom for
    ;; the +40/+25 drag and the +30/+20 resize — the parent-content clamp
    ;; must never engage in the gesture tests (it has its own semantics).
    :tree
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100 bg-surface-0"
     :children
     [;; Behind the proxy in z (earlier sibling): receives the click in
      ;; static mode (the proxy clears CLICKABLE + hides its glass).
      {:tag :lv_button
       :event :under
       :x 80
       :y 80
       :style {:width 100 :height 50}
       :children [{:tag :lv_label :text "Under"}]}
      {:tag :lv_host_proxy
       :id "px"
       :x 60
       :y 60
       :style {:width 160 :height 100}
       :host_proxy_props {:proxy_id "px"
                          :mode :draggable
                          :min_w 80
                          :min_h 60
                          :max_w 360
                          :max_h 240
                          :handle_size 16
                          :z 7}
       :bind {:mode :proxy-mode}
       :show-when {:subject :proxy-show :eq 1}}
      ;; The radio card must stay CLEAR of the proxy's gesture areas AND
      ;; its top-right snap target — a later sibling is hit-tested first
      ;; and would swallow the cell clicks.
      {:tag :lv_obj
       :x 250
       :y 120
       :class "@card gap-spacing-xs w-content h-content"
       :children
       [{:tag :radio-group
         :children
         [{:tag :radio-option
           :props {:label "Static" :event :mode-static :subject :proxy-mode :value 0}}
          {:tag :radio-option
           :props {:label "Draggable" :event :mode-draggable :subject :proxy-mode :value 1}}
          {:tag :radio-option
           :props {:label "Resizable" :event :mode-resizable :subject :proxy-mode :value 2}}
          {:tag :radio-option
           :props {:label "Alignable"
                   :event :mode-alignable
                   :subject :proxy-mode
                   :value 3}}]}]}]}}})

;; ═══════════════════════════════════════════════════════════════════
;; R4/R5b pointer routing: a STATIC host_proxy root (the video gesture-surface,
;; CLICKABLE cleared) with a clickable button child. The capture-on-claim
;; hit-test (controls_host_message) routes a DOWN over the button to LVGL
;; (button_event_cb fires → host_command relays the widget's SetZoomTableValue),
;; and a DOWN over the empty proxy area to the VIDEO FSM (gesture.c fed; the
;; controls_tick drain relays the gesture's cmd via host_command — TAP →
;; RotateToNDC, PINCH → SetZoomTableValue). Consumed by the harness's
;; pointer_routing module.
;; ═══════════════════════════════════════════════════════════════════
(def ^:private routing-fixtures
  {"vr_route" {:type :screen
               :subjects {}
               ;; The button click relays a SetZoomTableValue cmd carrying its int-value
               ;; (7) via host_command (R5b cmd-out — the LVGL-owned pointer route).
               :events {:hit-btn {:int-value 7 :notify-host true :cmd zoom-value-cmd}}
               ;; The root IS a STATIC host_proxy filling the 400x300 framebuffer — its
               ;; box has CLICKABLE cleared, so a point that misses the button falls
               ;; through to the (init-cleared) screen = NULL = the video surface. The
               ;; surface carries the pre-encoded gesture→cmd templates (R5b): a video
               ;; TAP relays RotateToNDC at the tap NDC point, a 2-finger PINCH relays
               ;; SetZoomTableValue ±1 — the VIDEO-owned pointer route.
               :tree {:tag :lv_host_proxy
                      :id "surface"
                      :class "w-pct-100 h-pct-100"
                      :host_proxy_props {:proxy_id "surface"
                                         :mode :static
                                         :min_w 40
                                         :min_h 40
                                         :max_w 400
                                         :max_h 300
                                         :handle_size 16
                                         :z 1}
                      :gestures [{:kind :GESTURE_KIND_TAP :cmd tap-rotate-cmd}
                                 {:kind :GESTURE_KIND_PINCH :cmd pinch-zoom-cmd}]
                      :children [{:tag :lv_button
                                  :event :hit-btn
                                  :x 20
                                  :y 20
                                  :style {:width 80 :height 40}
                                  :children [{:tag :lv_label :text "Hit"}]}]}}
   ;; R5b ITEM 7 (gesture-spec ownership): unlike vr_route (the gesture surface
   ;; IS the un-removable screen ROOT), here a STATIC host_proxy video-surface
   ;; ROOT (CLICKABLE cleared, like vr_route — keeps a tap VIDEO-owned via
   ;; hit_test_owner → NULL → FSM, both BEFORE and AFTER removal) carries a
   ;; REMOVABLE gesture-bearing child proxy. A PATCH_OP_REMOVE_NODE of that child
   ;; must clear its owned gesture template — else the drain keeps matching the
   ;; stale TAP spec and emits a PHANTOM RotateToNDC on a post-removal tap.
   ;; Consumed by the pointer_routing gesture_specs_cleared_on_removal test.
   "vr_gesture_removable" {:type :screen
                           :subjects {}
                           :events {}
                           :tree {:tag :lv_host_proxy
                                  :id "surface"
                                  :class "w-pct-100 h-pct-100"
                                  :host_proxy_props {:proxy_id "surface"
                                                     :mode :static
                                                     :min_w 40
                                                     :min_h 40
                                                     :max_w 400
                                                     :max_h 300
                                                     :handle_size 16
                                                     :z 1}
                                  :children [{:tag :lv_host_proxy
                                              :id "gsurface"
                                              :class "w-pct-100 h-pct-100"
                                              :host_proxy_props {:proxy_id "gsurface"
                                                                 :mode :static
                                                                 :min_w 40
                                                                 :min_h 40
                                                                 :max_w 400
                                                                 :max_h 300
                                                                 :handle_size 16
                                                                 :z 1}
                                              :gestures [{:kind :GESTURE_KIND_TAP
                                                          :cmd tap-rotate-cmd}]}]}}
   ;; R5b ROI rubber-band: a STATIC full-screen host_proxy video-surface
   ;; (CLICKABLE cleared, like vr_route → every point is VIDEO-owned → the FSM)
   ;; carrying BOTH a TAP spec (point-select → RotateToNDC) and an ROI spec
   ;; (rubber-band rect → FocusROI, 4 NDC slots). A completed drag (PAN_END) is
   ;; mode-gated into a 4-corner FocusROI carrying (down.x,down.y,up.x,up.y); a
   ;; plain TAP still routes to the point-select spec — mirroring jettison's
   ;; tap→handlePointSelection vs pan→handleROISelection split. Consumed by the
   ;; pointer_routing roi_gesture test.
   "vr_roi_rect" {:type :screen
                  :subjects {}
                  :events {}
                  :tree {:tag :lv_host_proxy
                         :id "roi-surface"
                         :class "w-pct-100 h-pct-100"
                         :host_proxy_props {:proxy_id "roi-surface"
                                            :mode :static
                                            :min_w 40
                                            :min_h 40
                                            :max_w 400
                                            :max_h 300
                                            :handle_size 16
                                            :z 1}
                         :gestures [{:kind :GESTURE_KIND_TAP :cmd tap-rotate-cmd}
                                    {:kind :GESTURE_KIND_ROI :cmd roi-focus-cmd}]}}})

;; ═══════════════════════════════════════════════════════════════════
;; V-C layout-defect fixtures — exercise the controls_dump_tree layout
;; flags (clipped / overflow / scrollable_overflow / text_truncated) the
;; vc_layout_gate test parses. Each targets one flag, positive or negative;
;; the test asserts the exact flag set seen per fixture. Geometry, not
;; pixels: both renderer oracles agree, so these ride harness-test only.
;; ═══════════════════════════════════════════════════════════════════
(def ^:private vc-no-box
  "Zero padding + border so a fixture's content box equals its outer box and
   the layout-defect geometry is exact (the default theme insets both)."
  {:pad-all 0 :border-width 0})

(def ^:private vc-fixtures
  {;; POSITIVE truncated: a tree whose dump exceeds the renderer's 128KB
   ;; tree_buf — the dump must END with the loud ,"truncated":true tail
   ;; sentinel instead of silently handing the host cut JSON (fail-fast:
   ;; a flooded dump that still LOOKS well-formed is the failure mode).
   ;; Sized under the renderer headroom caps (MAX_UID_NODES x 80%) but past the
   ;; byte cap: 780 labels x ~190 dump bytes (64-char text + clipped/offscreen
   ;; flag bytes from their off-display placement) = ~148KB.
   "vc_trunc" (make-screen {:tag :lv_obj
                            :class "w-80 h-60"
                            :style vc-no-box
                            :flags-clear #{:scrollable}
                            :children (mapv (fn [i]
                                              {:tag :lv_label
                                               :class "text-fg-0 font-font-body"
                                               :x 500
                                               :y (* i 5)
                                               :text (str "truncation probe label " i
                                                          " padding padding padding"
                                                          " padding padding pad")})
                                            (range 780))})
   ;; POSITIVE clipped + overflow: an oversized child in a fixed,
   ;; non-scrollable parent — the child's box escapes the parent content
   ;; (clipped on the child) and the parent's content is clipped away
   ;; (overflow on the parent). Scrollable cleared so it is a hard clip.
   "vc_clip" (make-screen {:tag :lv_obj
                           :class "w-80 h-60 bg-surface-2"
                           :style vc-no-box
                           :flags-clear #{:scrollable}
                           :children [{:tag :lv_obj
                                       :class "w-160 h-120 bg-status-error"
                                       :style vc-no-box}]})
   ;; NEGATIVE: the child fits inside the parent content box — nothing fires.
   "vc_fits" (make-screen {:tag :lv_obj
                           :class "w-80 h-60 bg-surface-2"
                           :style vc-no-box
                           :flags-clear #{:scrollable}
                           :children [{:tag :lv_obj
                                       :class "w-40 h-30 bg-status-success"
                                       :style vc-no-box}]})
   ;; POSITIVE scrollable_overflow: same oversized content but the parent
   ;; KEEPS its (default) SCROLLABLE flag — the content is reachable by
   ;; scroll, not clipped. So the parent reports scrollable_overflow (NOT
   ;; overflow), and the child still escapes the content box (clipped).
   "vc_scroll" (make-screen {:tag :lv_obj
                             :class "w-80 h-60 bg-surface-2"
                             :style vc-no-box
                             :children [{:tag :lv_obj
                                         :class "w-160 h-120 bg-status-error"
                                         :style vc-no-box}]})
   ;; POSITIVE text_truncated: a DOTS-mode label whose wrapped text overflows
   ;; its fixed height — LVGL ellipsizes and sets dot_begin.
   "vc_dots" (make-screen {:tag :lv_label
                           :style vc-no-box
                           :class "w-120 h-40 text-fg-0 font-font-body"
                           :label_props {:long_mode :dots}
                           :text (str "A very long label that wraps across several lines"
                                      " and overflows the fixed height of its box here")})
   ;; NEGATIVE: a DOTS-mode label whose short text fits — dot_begin stays at
   ;; its sentinel, no text_truncated.
   "vc_dots_fits" (make-screen {:tag :lv_label
                                :style vc-no-box
                                :class "w-120 h-40 text-fg-0 font-font-body"
                                :label_props {:long_mode :dots}
                                :text "Short"})
   ;; POSITIVE for `text_wrapped`, negative for everything else: a WRAP-mode
   ;; label (content-sized height) GROWS to fit its text — no truncation, no
   ;; clipping, no overflow, and every glyph drawn.
   ;;
   ;; It was filed as a pure negative while no dump key could express a reflow.
   ;; `text_wrapped` now can, and this fixture is the clean POSITIVE case for
   ;; it: the author sized a width, asked for WRAP and let the height grow, so
   ;; the reflow is intended. The flag reports that a reflow HAPPENED; whether
   ;; that is a defect is the corpus's to declare, which is why
   ;; `devcards.invariants/designed-flag-keys` has a `:subject` kind at all.
   "vc_wrap" (make-screen {:tag :lv_label
                           :style vc-no-box
                           :class "w-120 h-content text-fg-0 font-font-body"
                           :label_props {:long_mode :wrap}
                           :text "A long label that simply wraps across lines"})
   ;; BREAKPOINT-DEPENDENT: the child fits at sm (bp0) but the md+ override
   ;; blows it past the non-scrollable parent — so the defect set is EMPTY at
   ;; bp0 and {overflow, clipped} at bp1..3. Proves the tier restyle is caught.
   "vc_clip_bp" (make-screen {:tag :lv_obj
                              :class "w-80 h-60 bg-surface-2"
                              :style vc-no-box
                              :flags-clear #{:scrollable}
                              :children [{:tag :lv_obj
                                          :class
                                          "w-40 h-30 bg-status-error md:w-160 md:h-120"
                                          :style vc-no-box}]})
   ;; POSITIVE text_clipped (V-C3): a CLIP-long-mode label whose single line
   ;; is wider than its fixed box. CLIP keeps the box and clips glyphs out of
   ;; it WITHOUT ellipsizing — so dot_begin never moves (no text_truncated) and
   ;; the measurement flag is the only signal. lv_text_get_size at COORD_MAX +
   ;; EXPAND measures the natural line width, which exceeds the content box.
   "vc_clip_text" (make-screen {:tag :lv_label
                                :style vc-no-box
                                :class "w-80 h-20 text-fg-0 font-font-body"
                                :flags-clear #{:scrollable}
                                :label_props {:long_mode :clip}
                                :text
                                "This single CLIP line is far wider than its 80px box"})
   ;; NEGATIVE: a CLIP-long-mode label whose short text fits the box — the
   ;; natural extent is within the content box, so no text_clipped.
   "vc_clip_text_fits" (make-screen {:tag :lv_label
                                     :style vc-no-box
                                     :class "w-200 h-20 text-fg-0 font-font-body"
                                     :label_props {:long_mode :clip}
                                     :text "ok"})
   ;; POSITIVE offscreen (V-C3): a child positioned past the display's right
   ;; edge inside a non-scrollable root. The box escapes both the display
   ;; (offscreen) and the parent content (clipped); the non-scrollable parent
   ;; reports overflow. NOT excluded as a scroll-snap child — the root is not a
   ;; scroll region (scrollable cleared).
   "vc_offscreen"
   (make-screen
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100"
     :style vc-no-box
     :flags-clear #{:scrollable}
     :children
     [{:tag :lv_obj :class "w-60 h-40 bg-status-error" :style vc-no-box :x 360 :y 10}]})
   ;; NEGATIVE: the same child fully on the display inside the same
   ;; non-scrollable root — nothing fires.
   "vc_onscreen"
   (make-screen
    {:tag :lv_obj
     :class "w-pct-100 h-pct-100"
     :style vc-no-box
     :flags-clear #{:scrollable}
     :children
     [{:tag :lv_obj :class "w-60 h-40 bg-status-success" :style vc-no-box :x 10 :y 10}]})
   ;; POSITIVE squished (V-C3): a flex-row whose fixed-width first child fills
   ;; (and overflows) the whole track, leaving the grow child no room — it
   ;; collapses to zero width. grow>0 + a zero extent is the squished signal;
   ;; the parent's layout is FLEX, the gate condition.
   "vc_squish" (make-screen {:tag :lv_obj
                             :class "w-80 h-40 flex-row"
                             :style vc-no-box
                             :flags-clear #{:scrollable}
                             :children [{:tag :lv_obj
                                         :class "w-100 h-30 bg-status-error"
                                         :style vc-no-box}
                                        {:tag :lv_obj
                                         :class "h-30 bg-status-success"
                                         :style (assoc vc-no-box :flex-grow 1)}]})
   ;; NEGATIVE: the same flex-row but the fixed child leaves room — the grow
   ;; child resolves to a positive width, so nothing is squished.
   "vc_squish_fits" (make-screen {:tag :lv_obj
                                  :class "w-200 h-40 flex-row"
                                  :style vc-no-box
                                  :flags-clear #{:scrollable}
                                  :children [{:tag :lv_obj
                                              :class "w-60 h-30 bg-status-error"
                                              :style vc-no-box}
                                             {:tag :lv_obj
                                              :class "h-30 bg-status-success"
                                              :style (assoc vc-no-box :flex-grow 1)}]})
   ;; EDGE: a minimal screen (one fitting label) — no defect flags anywhere,
   ;; only the root bp/theme_dark.
   "vc_empty"
   (make-screen
    {:tag :lv_label :text "ok" :class "text-fg-0 font-font-body" :style vc-no-box})})

(def ^:private state-update-payloads
  "Raw StateUpdate payloads (`su_*.bin`) the controller tests push through
   `controls_update_state`."
  {"su_volume_70" {:values [{:name "volume" :int_value 70}]}
   "su_show_0" {:values [{:name "show" :int_value 0}]}
   "su_show_1" {:values [{:name "show" :int_value 1}]}
   "su_proxy_mode_0" {:values [{:name "proxy-mode" :int_value 0}]}
   "su_proxy_mode_1" {:values [{:name "proxy-mode" :int_value 1}]}
   "su_proxy_mode_2" {:values [{:name "proxy-mode" :int_value 2}]}
   "su_proxy_mode_3" {:values [{:name "proxy-mode" :int_value 3}]}
   "su_proxy_hide" {:values [{:name "proxy-show" :int_value 0}]}
   "su_proxy_show" {:values [{:name "proxy-show" :int_value 1}]}})

;; ═══════════════════════════════════════════════════════════════════
;; All fixtures combined
;; ═══════════════════════════════════════════════════════════════════
(def ^:private all-fixtures
  (merge per-widget-fixtures
         reorder-fixtures
         modification-fixtures
         value-change-fixtures
         composite-fixtures
         font-fixtures
         svg-fixtures
         hud-breakpoint-fixtures
         tabview-fixtures
         chart-fixtures
         breakpoint-color-fixtures
         aa-probe-fixtures
         cross-cutting-fixtures
         controller-binding-fixtures
         host-proxy-fixtures
         routing-fixtures
         vc-fixtures))

(defn generate-all!
  "Generate all fixture .pb files from inline screen definitions.
   Runs each through the full codegen pipeline."
  [tokens-path output-dir]
  (java.io.File/.mkdirs (io/file output-dir))
  (let [tokens (core/load-ui-defs tokens-path nil)
        components (component/load-components (:components tokens))
        sorted-names (sort (keys all-fixtures))]
    (doseq [fixture-name sorted-names]
      (let [screen (get all-fixtures fixture-name)
            output-path (str output-dir "/" fixture-name ".pb")
            byte-count (core/process-screen tokens components screen output-path)]
        (println (str "  " fixture-name ".pb (" byte-count " bytes)"))))
    (doseq [[payload-name update-ir] (sort state-update-payloads)]
      (let [^bytes payload (proto-ser/state-update->bytes update-ir)]
        (with-open [out (io/output-stream (io/file
                                           (str output-dir "/" payload-name ".bin")))]
          (java.io.OutputStream/.write out payload))
        (println (str "  " payload-name ".bin (" (alength payload) " bytes)"))))
    (println (str "Done. Generated "
                  (count sorted-names)
                  " fixture(s) + "
                  (count state-update-payloads)
                  " state-update payload(s)."))))

(defn -main
  "CLI entry: --tokens <path> --output <dir>"
  [& args]
  (let [arg-map (apply hash-map args)
        tokens-path (get arg-map "--tokens")
        output-dir (get arg-map "--output")]
    (when-not (and tokens-path output-dir)
      (binding [*out* *err*] (println "Usage: --tokens <path> --output <dir>"))
      (System/exit 1))
    (generate-all! tokens-path output-dir)))
(m/=> -main [:=> [:cat [:* [:string {:min 1}]]] :nil])

;; -- Function schema registrations --
;;
;; Divergence from the upstream tooling repo: it EXCLUDES this file from its
;; arrow-spec checker ("Test fixture generators, not library API"); the
;; source repo's checker has no such exclusion, so the generators carry
;; specs here.
(def ^:private widget-tree
  "Authoring widget node (a :tag map, children optional)."
  [:map [:tag :keyword]])

(m/=> make-screen [:=> [:cat [:or widget-tree [:vector widget-tree]]] schema/screen-schema])

(m/=> make-screen-raw [:=> [:cat widget-tree] schema/screen-schema])

(m/=> generate-all! [:=> [:cat [:string {:min 1}] [:string {:min 1}]] :nil])