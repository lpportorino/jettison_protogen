(ns lvgl-codegen.gesture-fixtures
  "The pointer-GESTURE fixtures: the video gesture-surface screens and the
   pre-encoded `cmd.*` templates a recognized gesture ships. The complement of
   `lvgl-codegen.egress-fixtures`, whose subject is the templates sourced from
   something OTHER than a pointer gesture — so between them the two cover the
   cmd-out surface by where the value comes from.

   Split out of `lvgl-codegen.fixtures` rather than added to it, for the reason
   that namespace's sibling already records: it sits at the ns-size ceiling, so
   anything added to it pushes it over, and the ceiling only ever moves down.
   The grouping is not merely a size remedy — every screen here mounts a STATIC
   host_proxy with CLICKABLE cleared, which is what keeps a point VIDEO-owned
   and routed to the recognizer, and every template here is selected by a
   gesture DECISION rather than by a widget value.

   Add a gesture-surface screen HERE; a widget-egress template goes in
   `lvgl-codegen.egress-fixtures`, a static visual-regression scene in
   `lvgl-codegen.fixtures`, and a morph triple in
   `lvgl-codegen.morph-fixtures`."
  (:require [lvgl-codegen.egress-fixtures :as egress]
            [uigen.cmd-spec :as cmd-spec]
            [uigen.resolve :as res]))

(set! *warn-on-reflection* true)

(def tap-rotate-cmd
  "cmd.RotaryPlatform.RotateToNDC patched by the tap NDC point (x/y verbatim),
   pinned to the DAY pane channel (RotateToNDC carries a `channel` enum leaf the
   pane selects — the fixture exercises a real non-default channel)."
  (cmd-spec/cmd-spec "cmd.RotaryPlatform.RotateToNDC"
                     :PATCH_KIND_NDC_X
                     {:fixed {:channel (res/video-channel-value "DAY")}}))

(def pinch-zoom-cmd
  "cmd.DayCamera.SetZoomTableValue patched by a pinch ±1 DELTA step — the DELTA
   slot path itself, which is what this fixture exists to exercise end to end.

   IT IS NOT A MODEL FOR A ZOOM-TABLE BINDING, and the distinction is the whole
   reason GestureSpec.delta_sign exists: `SetZoomTableValue.value` declares
   `gte: 0`, so patching the negative half of a ±1 step into it produces a value
   its own schema forbids. A real zoom-table pinch is the direction-selected
   PAIR below. Kept as-is because the DELTA slot is a real renderer path with
   real consumers (a genuinely signed leaf), and this is its only end-to-end
   coverage through the pointer pipeline."
  (cmd-spec/cmd-spec "cmd.DayCamera.SetZoomTableValue" :PATCH_KIND_DELTA))

(def pinch-zoom-next-cmd
  "cmd.DayCamera.NextZoomTablePos — a patch-free FIXED template (an EMPTY
   message: no leaf, so `fixed-cmd-spec` bakes the whole cmd.Root and the
   renderer relays it verbatim over patch_count 0). The POSITIVE half of a
   direction-selected pinch."
  (cmd-spec/fixed-cmd-spec {:command-id "cmd.DayCamera.NextZoomTablePos"}))

(def pinch-zoom-prev-cmd
  "cmd.DayCamera.PrevZoomTablePos — the NEGATIVE half of the same pair, and the
   reason a sign cannot ride as a patched value here: the two directions are two
   different messages, not two values of one leaf."
  (cmd-spec/fixed-cmd-spec {:command-id "cmd.DayCamera.PrevZoomTablePos"}))

(def roi-focus-cmd
  "cmd.DayCamera.FocusROI patched by an ROI rubber-band drag's TWO NDC corners:
   x1/y1 (down corner) → the NDC_X/Y slots, x2/y2 (up corner) → the NDC_X2/Y2
   slots, all verbatim. FocusROI has no value/channel leaf, so the varint-kind
   arg is unused (as with tap-rotate-cmd). frame_time/state_time stay 0 — the
   frame-timestamp stamping jettison applies is a named follow-on, not wired
   here (the root_template bakes 0)."
  (cmd-spec/cmd-spec "cmd.DayCamera.FocusROI" :PATCH_KIND_NDC_X))

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
(def fixtures
  {"vr_route" {:type :screen
               :subjects {}
               ;; The button click relays a SetZoomTableValue cmd carrying its int-value
               ;; (7) via host_command (R5b cmd-out — the LVGL-owned pointer route).
               :events {:hit-btn {:int-value 7 :notify-host true :cmd egress/zoom-value-cmd}}
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
                                    {:kind :GESTURE_KIND_ROI :cmd roi-focus-cmd}]}}
   ;; R5b direction-selected pinch: ONE GestureKind carrying TWO templates, told
   ;; apart by :delta-sign. The zoom table is the case the DELTA slot cannot
   ;; serve at all — its two directions are NextZoomTablePos and
   ;; PrevZoomTablePos, both EMPTY messages, so there is no leaf for a sign to
   ;; be patched into and no ordering of one template that could send both. A
   ;; STATIC full-screen host_proxy video-surface (CLICKABLE cleared, like
   ;; vr_route) so every point is VIDEO-owned and reaches the FSM. Consumed by
   ;; the pointer_routing pinch-direction tests.
   "vr_pinch_pair" {:type :screen
                    :subjects {}
                    :events {}
                    :tree {:tag :lv_host_proxy
                           :id "pinch-surface"
                           :class "w-pct-100 h-pct-100"
                           :host_proxy_props {:proxy_id "pinch-surface"
                                              :mode :static
                                              :min_w 40
                                              :min_h 40
                                              :max_w 400
                                              :max_h 300
                                              :handle_size 16
                                              :z 1}
                           :gestures [{:kind :GESTURE_KIND_PINCH
                                       :delta-sign :GESTURE_DELTA_SIGN_POSITIVE
                                       :cmd pinch-zoom-next-cmd}
                                      {:kind :GESTURE_KIND_PINCH
                                       :delta-sign :GESTURE_DELTA_SIGN_NEGATIVE
                                       :cmd pinch-zoom-prev-cmd}]}}})
