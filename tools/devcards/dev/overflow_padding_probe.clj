(ns overflow-padding-probe
  "Read-only probe: what OVERFLOW PADDING a widget actually needs, per side,
   measured against what the resolved styles alone can account for.

   `devcards.spacing` reads one of two dump keys per node — `paint_box` (the
   exact drawn extent) or `paint_bound` (a conservative box, and the rule's
   uncertainty arm). This probe exists to answer the three questions a
   per-side BUDGET has to be designed against, and every one of them is a
   property of THIS theme, THIS renderer and THIS corpus dpi rather than of
   LVGL in general:

   1. IS THE ESCAPE PER-SIDE OR PER-AXIS? A single scalar reach is only
      honest if the four sides really move together. A slider knob is the
      standing case, and it is driven here at the range EXTREMES, across
      both orientations — a sweep the corpus samples at only a few cells.

   2. DOES THE KNOB PART'S OWN PADDING ACCOUNT FOR THE HORIZONTAL ESCAPE?
      The vertical one it plainly does: `position_knob` grows the knob rect
      by the LV_PART_KNOB pad on the cross axis. Along the track the knob is
      CENTRED on the indicator end first, so half the knob diameter is in
      play before any padding is added, and whether the pad alone covers it
      is arithmetic nobody should take on trust.

   3. WHAT DOES A STYLES-ONLY BUDGET LEAVE UNEXPLAINED? For each probed
      widget the probe prints the LVGL reservation (`ext`, the scalar
      `refr_obj` clips with) beside the per-side escape actually observed,
      so a class whose reservation is a widget-code blanket is visible as a
      gap rather than inferred from reading widget sources.

   Renders, dumps, prints. Writes nothing, gates nothing, is in no battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:overflow-padding-probe"
  (:require [clojure.data.json :as json]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render!
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark 1})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- card
  ^bytes [id children]
  (fixtures/build-authored-card
   canvas
   {:id id
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 780 :h 460 :pad-all 0 :border-width 0}
           :children children}}))

(defn- overhang
  "Per-side pixels `box` extends beyond `coords`, as [left top right bottom]."
  [coords box]
  (when (and coords box)
    (let [[cx1 cy1 cx2 cy2] coords
          [bx1 by1 bx2 by2] box]
      [(- cx1 bx1) (- cy1 by1) (- bx2 cx2) (- by2 cy2)])))

(defn- row
  [label node]
  (let [box (or (:paint_box node) (:paint_bound node))]
    (format "  %-26s %-16s coords %-22s %-11s %-22s over %s"
            label
            (:type node)
            (pr-str (:coords node))
            (cond (:paint_box node) "paint_box"
                  (:paint_bound node) "paint_bound"
                  :else "(neither)")
            (pr-str box)
            (str (pr-str (overhang (:coords node) box))
                 "  budget " (pr-str (:overflow_padding node))))))

(defn- node-at
  [tree path]
  (or (:node (first (filter #(= path (:path %)) (invariants/annotate-tree tree))))
      (throw (ex-info "no node at path" {:path path}))))

(defn- slider
  [x y w h value]
  {:type :WIDGET_SLIDER
   :x x :y y
   :props {:w w :h h
           :slider_props {:min_value 0 :max_value 100 :value value}}})

(defn -main
  [& _]
  (println "\n══ SLIDER KNOB ESCAPE ACROSS THE VALUE RANGE ══")
  (println "(a 120x16 horizontal slider, swept across its whole range)")
  (let [tree (render! (card "probe/slider-range"
                            [(slider 100 40 120 16 0)
                             (slider 100 100 120 16 50)
                             (slider 100 160 120 16 100)
                             (slider 400 40 16 120 0)
                             (slider 460 40 16 120 50)
                             (slider 520 40 16 120 100)]))]
    (doseq [[label idx] [["horizontal value 0" 0]
                         ["horizontal value 50" 1]
                         ["horizontal value 100" 2]
                         ["vertical value 0" 3]
                         ["vertical value 50" 4]
                         ["vertical value 100" 5]]]
      (println (row label (node-at tree [0 0 idx])))))

  (println "\n══ OTHER CLASSES THAT RESERVE EXT DRAW SIZE ══")
  (let [tree (render! (card "probe/classes"
                            [{:type :WIDGET_SWITCH :x 100 :y 40
                              :props {:w 60 :h 30 :switch_props {:checked false}}}
                             {:type :WIDGET_LABEL :x 200 :y 40 :text "Aq"
                              :props {:w 60}}
                             {:type :WIDGET_BUTTON :x 300 :y 40 :props {:w 90 :h 36}
                              :children [{:type :WIDGET_LABEL :text "Go"}]}
                             {:type :WIDGET_BAR :x 420 :y 40
                              :props {:w 120 :h 16
                                      :bar_props {:min_value 0 :max_value 100
                                                  :value 50}}}
                             {:type :WIDGET_SCALE :x 100 :y 140
                              :props {:w 200 :h 60}}
                             {:type :WIDGET_ARC :x 340 :y 140
                              :props {:w 120 :h 120
                                      :arc_props {:min_value 0 :max_value 100
                                                  :value 50}}}]))]
    (doseq [[label idx] [["switch" 0] ["label" 1] ["button" 2]
                         ["bar" 3] ["scale" 4] ["arc" 5]]]
      (println (row label (node-at tree [0 0 idx]))))))
