(ns lvgl-codegen.egress-fixtures
  "The command-EGRESS fixtures: pre-encoded `cmd.*` templates a widget ships,
   and the screens that ship them. Two egress shapes live here, and they are
   the two that source their value from something other than a pointer
   gesture — a vector of FIXED templates the widget's int value index-selects
   among, and a multi-field FORM whose slots are patched from NAMED SUBJECTS.

   Split out of `lvgl-codegen.fixtures` rather than added to it: that namespace
   sat EXACTLY at the ns-size ceiling, so anything added to it pushed it over,
   and the ceiling only ever moves down. The grouping is not merely a size
   remedy — the form trio covers the three PatchEncoding arms (padded varint,
   double LE, float LE) across three real commands, each carrying the leaf type
   that forced its encoding, and the by-value trio covers the :bool-set / :enum
   / :action shapes. One subject either way.

   A THIRD shape rides here too, and it is named rather than folded into the
   sentence above: `zoom-value-cmd`, the single-int template a widget's own
   VALUE patches. It is the same subject — a template a widget ships, sourced
   from something other than a pointer gesture — and it lives here rather than
   in either consumer because BOTH `lvgl-codegen.fixtures` and
   `lvgl-codegen.gesture-fixtures` need it, and a namespace that required the
   other back would be a cycle.

   Add an egress fixture HERE; a gesture-surface screen goes in
   `lvgl-codegen.gesture-fixtures`, a static visual-regression scene in
   `lvgl-codegen.fixtures`, and a morph triple in
   `lvgl-codegen.morph-fixtures`."
  (:require [uigen.cmd-spec :as cmd-spec]
            [uigen.resolve :as res]))

(set! *warn-on-reflection* true)

(def zoom-value-cmd
  "cmd.DayCamera.SetZoomTableValue patched by a widget's int VALUE (the canonical
   single-int value command — a button/slider click ships its value)."
  (cmd-spec/cmd-spec "cmd.DayCamera.SetZoomTableValue" :PATCH_KIND_WIDGET_VALUE))

;; R5a cmd-by-value: EventBinding.cmd_by_value carries a vector of FIXED
;; templates (patch_count 0) the widget's INT value index-selects among — the
;; :bool-set / :enum / :action egress shapes. The renderer memcpy-relays the
;; selected template verbatim; an out-of-range index emits nothing (fail-loud).
(def auto-focus-by-value
  "cmd.CV.SetAutoFocus [false, true] FIXED templates (channel DAY), index-selected
   by an lv_switch's checked state (0→false, 1→true) — the :bool-set egress."
  (let [ch (res/video-channel-value "DAY")]
    [(cmd-spec/fixed-cmd-spec {:command-id "cmd.CV.SetAutoFocus"
                               :field "value"
                               :raw-value false
                               :fixed {:channel ch}})
     (cmd-spec/fixed-cmd-spec {:command-id "cmd.CV.SetAutoFocus"
                               :field "value"
                               :raw-value true
                               :fixed {:channel ch}})]))

(def fx-mode-by-value
  "cmd.DayCamera.SetFxMode [A,B,C] FIXED templates — the first 3 enum options in
   dropdown-option order (res/enum-options is the single source), index-selected
   by a slider position (value i → option i's enum number) — the :enum egress."
  (let [cid "cmd.DayCamera.SetFxMode"
        mode-field (first (filter #(= "mode" (:name %)) (res/all-fields cid)))]
    (mapv (fn [{:keys [value]}]
            (cmd-spec/fixed-cmd-spec {:command-id cid :field "mode" :raw-value value}))
          (take 3 (res/enum-options cid mode-field)))))

(def measure-action-cmd
  "cmd.Lrf.Measure — a parameterless :action baked as a FIXED template (patches [];
   the renderer emits it verbatim via the existing single-cmd path)."
  (cmd-spec/fixed-cmd-spec {:command-id "cmd.Lrf.Measure"}))

(def power-form-cmd
  "cmd.Power.SetAlertThreshold patched from TWO named subjects — the multi-field
   FORM egress. Both leaves are uint32 at wire-scale 1, so each slot is a
   PADDED_VARINT of its subject's live int, and the two slots carry INDEPENDENT
   values: the property no widget-value patch can have, since one widget
   contributes exactly one number."
  (cmd-spec/subject-form-cmd-spec
   {:command-id "cmd.Power.SetAlertThreshold"
    :field->subject {"channel" "form_ch" "threshold_ma" "form_ma"}}))

(def gps-form-cmd
  "cmd.Gps.SetManualPosition patched from two named subjects. Its leaves are
   DOUBLES at the 1e7 geo wire-scale, so each slot is DOUBLE_LE — the encoding
   that did not exist before, and without which these fields could only be
   written as a padded varint over a fixed64 slot and decode as garbage."
  (cmd-spec/subject-form-cmd-spec
   {:command-id "cmd.Gps.SetManualPosition"
    :field->subject {"latitude" "form_lat" "longitude" "form_lon"}}))

(def heater-form-cmd
  "cmd.Heater.SetHeating patched from two named subjects. Its leaves are FLOATS
   (fixed32) at the deci-degree temperature scale, so each slot is FLOAT_LE —
   the third encoding, and the one a 6-field heater form needs."
  (cmd-spec/subject-form-cmd-spec
   {:command-id "cmd.Heater.SetHeating"
    :field->subject {"target_0" "form_t0" "target_1" "form_t1"}}))

(def form-screens
  "The three subject-form SCREENS, one per PatchEncoding arm. They live beside
   the templates they submit rather than in the fixture registry, so the whole
   form egress — command shape and the screen that sends it — reads in one
   place; `lvgl-codegen.fixtures` merges this map into its controller-binding
   registry."
  {   ;; A multi-field FORM's submit: two sliders each BOUND to their own subject,
   ;; and a button whose cmd reads BOTH subjects. The button has no value of its
   ;; own and the form has two fields, so neither a widget-value patch nor a
   ;; fixed template can express it — this is the shape that was unrepresentable.
   ;; Clicking each slider writes its subject (lv_slider_bind_value is
   ;; bidirectional), then the button ships both live values in ONE command.
   "vr_event_subject_form"
   {:type :screen
    :subjects {:form_ch {:type :int :default 0}
               :form_ma {:type :int :default 0}}
    :events {:apply {:notify-host true :cmd power-form-cmd}}
    :tree {:tag :lv_obj
           :class "w-pct-100 h-pct-100 bg-surface-0"
           :children [{:tag :lv_slider :bind {:value :form_ch}
                       :x 20 :y 30 :style {:width 200 :height 20}}
                      {:tag :lv_slider :bind {:value :form_ma}
                       :x 20 :y 80 :style {:width 200 :height 20}}
                      {:tag :lv_button :event :apply :x 20 :y 130
                       :style {:width 120 :height 40}
                       :children [{:tag :lv_label :text "Apply"}]}]}}
   ;; The same submit over DOUBLE leaves at the 1e7 geo wire-scale: the subject
   ;; defaults ARE the form values here (a slider cannot ride a 1e7 range), so
   ;; the click proves DOUBLE_LE + the scale DIVISION recovers 55.5 / 37.3.
   "vr_event_subject_form_double"
   {:type :screen
    :subjects {:form_lat {:type :int :default 555000000}
               :form_lon {:type :int :default 373000000}}
    :events {:apply {:notify-host true :cmd gps-form-cmd}}
    :tree {:tag :lv_obj
           :class "w-pct-100 h-pct-100 bg-surface-0"
           :children [{:tag :lv_button :event :apply :x 20 :y 30
                       :style {:width 120 :height 40}
                       :children [{:tag :lv_label :text "Send"}]}]}}
   ;; And over FLOAT leaves at the deci-degree temperature scale — the third
   ;; encoding, which a 6-field heater form needs. 235 -> 23.5 °C.
   "vr_event_subject_form_float"
   {:type :screen
    :subjects {:form_t0 {:type :int :default 235}
               :form_t1 {:type :int :default 191}}
    :events {:apply {:notify-host true :cmd heater-form-cmd}}
    :tree {:tag :lv_obj
           :class "w-pct-100 h-pct-100 bg-surface-0"
           :children [{:tag :lv_button :event :apply :x 20 :y 30
                       :style {:width 120 :height 40}
                       :children [{:tag :lv_label :text "Set"}]}]}}})
