(ns spacing-canary
  "REAL-RENDER canary for the SPACING rule (`devcards.spacing`) and for the
   dump keys it consumes (`paint_box` / `paint_bound`).

   WHY IT EXISTS, and it is a sharper reason than the overlap canary's.
   `devcards.spacing-test` judges hand-written dump maps, so it asserts the
   AUTHOR'S MODEL of a vocabulary that did not exist until this change. The
   whole premise of the rule is a claim about the RENDERER — that a widget
   paints outside the box it reports — and a hand-written map can state that
   claim without it being true of anything. Only a real render can settle it,
   and if it were false the rule would be machinery for a defect class that
   does not occur.

   THE FIXTURE THE PREMISE NEEDS, and every clause here exists to make it
   unfakeable: a slider and a neighbour whose DECLARED boxes are clearly
   apart, where the slider's knob paints across the gap anyway. The knob is
   `position_knob` centring itself on the indicator end and growing by the
   LV_PART_KNOB pad (lv_slider.c), which the stock theme sets to
   `LV_DPX_CALC(dpi, 6)`.

   WHAT IS ASSERTED, in the order that makes the case:

   1. THE GAP IS REAL. `separation` over the two rendered `coords` is
      POSITIVE. This is what makes the card a node-box-clean card rather than
      an overlap dressed up — measured off the dump, never assumed from the
      authored x/y, because the layout engine is what decides where things
      land.

   2. THE OVERHANG IS REAL AND IS INVISIBLE TO `coords`. The slider emits
      `paint_box`, and it extends beyond its own `coords`. The magnitude is
      DERIVED from the dump and asserted for its SHAPE — an overhang on the
      knob axis, none across it — never restated from the C constant, which
      would agree with the renderer only until one of them moved.

   3. A NODE-BOX RULE REPORTS THE CARD CLEAN. `devcards.overlap`, armed with
      the lane's own table and threshold, returns NOTHING. This is the
      measurement the whole change rests on, and it is asserted rather than
      argued.

   4. THE SPACING RULE FIRES, once, naming both nodes, with the painted boxes
      OVERLAPPING. Exactness matters: the finding must be `:paint-crowding`
      and not `:unmeasurable-paint-extent`, because the latter would mean the
      rule reached its uncertainty arm and the demonstration would prove
      nothing about resolution.

   5. THE CONTROL IS SILENT. The identical two widgets with the gutter opened
      up report nothing — so clause 4 is a statement about the overhang and
      not about the rule firing on everything.

   6. ABSENCE IS EXACT. The neighbour, which paints nowhere outside itself,
      emits NEITHER paint key. That is the convention the rule's soundness
      rests on: absence means `lv_obj_get_ext_draw_size` is 0, so `refr_obj`
      clips the widget to its own coords, so `coords` IS the paint box. A
      renderer that emitted the key everywhere, or nowhere, would leave every
      other clause here green.

   7. THE BUDGET IS RESOLVED, AND IT IS PER-SIDE. The slider emits an
      `overflow_padding`, and it is NOT one reach: across the track it is
      the knob part's own pad, along it the whole reservation, because the
      knob's along-track escape is a function of the VALUE and a budget must
      claim both ends. Asserted for its SHAPE off the dump — the two axes
      differ, and the cross axis is the smaller — never restated from a
      constant, which would agree with the renderer only until one moved.

   8. CONTAINMENT HOLDS ON A REAL WIDGET, which is the clause the whole
      budget exists to make checkable. The slider's `paint_box` comes from
      the knob rect `draw_knob` stored; its budget comes from resolved
      styles and the reservation. Two independent computations over one
      object, and rule 1 is the assertion that they agree — so shrinking
      either one in the renderer turns this clause red while every other
      clause here stays green. The rule reports NO `:paint-escapes-budget`
      on either card.

   9. THE BUDGET CLAIMS WHAT THE SNAPSHOT DOES NOT. On the CONTROL card the
      slider draws clear of its neighbour, and its budget still reaches
      further than its pixels do — the along-track space this mid-range
      card does not paint into. This is asserted as an inequality between the two boxes,
      which is what makes clause 9 a statement about the budget being an
      ENTITLEMENT rather than a second name for the paint extent.

   WHAT THIS DOES NOT COVER. The exclusions — related nodes, hidden and
   snapped subtrees, the ancestor clip, the threshold arithmetic — are
   reducer behaviour over a walk and stay in `devcards.spacing-test`, where
   they can be driven exhaustively and cheaply. This file covers the one
   thing that file structurally cannot: that the vocabulary it reduces is the
   vocabulary the interpreter emits.

   Run from tools/devcards in the pinned container:
     clojure -M:bindings:spacing-canary"
  (:require [clojure.data.json :as json]
            [devcards.fixtures :as fixtures]
            [devcards.geometry :as geometry]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.overlap :as overlap]
            [devcards.spacing :as spacing]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private gap-px
  "Read from the producer's DECLARED default rather than restated, so a
   threshold change cannot leave this canary judging a rule nobody runs."
  (:default (:gap-px (:thresholds spacing/producer))))

(defn- render!
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark 1})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- slider-at
  "A 16px-track slider — the thin shape a form column produces, and the one
   this repo's own corpus notes describe as carrying a knob of roughly 28px
   visual diameter."
  [x y]
  {:type :WIDGET_SLIDER
   :x x :y y
   :props {:w 120 :h 16
           :slider_props {:min_value 0 :max_value 100 :value 50}}})

(defn- plain-box
  "The neighbour: a bare, border-free, shadow-free box. Chosen for what it
   does NOT have — nothing that paints outside its own coords — so the pair
   resolves EXACTLY and clause 4 can demand `:paint-crowding` rather than
   the uncertainty arm."
  [x y]
  {:type :WIDGET_OBJ
   :x x :y y
   :bare true
   :props {:w 120 :h 40 :border-width 0}})

(defn- card
  ^bytes [id children]
  (fixtures/build-authored-card
   canvas
   {:id id
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 700 :h 400 :pad-all 0 :border-width 0}
           :children children}}))

(defn- node-at
  "The node map at an exact child-index path — a structural address, never a
   type search, so a card that grows a node fails loudly rather than quietly
   matching a different one."
  [tree path]
  (or (:node (first (filter #(= path (:path %))
                            (invariants/annotate-tree tree))))
      (throw (ex-info "no node at path" {:path path}))))

(defn- spacing-of
  [card-id tree]
  (spacing/findings {:card-id card-id
                     :nodes (invariants/annotate-tree tree)
                     :thresholds {:gap-px gap-px}}))

(defn- overlap-of
  [card-id tree]
  (overlap/findings {:card-id card-id
                     :nodes (invariants/annotate-tree tree)
                     :classes lanes/overlap-classes
                     :thresholds {:gap-px (:overlap/gap-px
                                           lanes/overlap-thresholds)}}))

(defn- overhang
  "Per-side pixels `box` extends beyond `coords`, as [left top right bottom],
   or nil when either box is absent."
  [coords box]
  (when (and coords box)
    (let [[cx1 cy1 cx2 cy2] coords
          [bx1 by1 bx2 by2] box]
      [(- cx1 bx1) (- cy1 by1) (- bx2 cx2) (- by2 cy2)])))

(defn- sep
  "`geometry/separation`, or nil when a box is ABSENT.

   ABSENCE IS A VERDICT HERE, NOT A CRASH, and that distinction is the whole
   reason this wrapper exists. `geometry/check-box!` throws on a nil box —
   correctly, since a rule must never let a missing box compare as 'far
   apart' — but this canary's whole subject is a key that may be absent, so
   the very mutation it exists to catch is the one that hands it a nil.
   Measured: suppressing the emit in `dump_obj` made every clause here die
   with an ExceptionInfo instead of failing, and an ERROR is indistinguishable
   from a broken canary. Returning nil lets each clause report a FAIL that
   names the missing key."
  [a b]
  (when (and a b) (geometry/separation a b)))

;; ── the cases ────────────────────────────────────────────────────────────
;; Each returns [[ok? message] ...]. Every message states the OBSERVED value,
;; so a red says what happened rather than only that something did.

(defn- checks
  []
  (let [tight-id "canary/spacing-paint-only"
        clear-id "canary/spacing-paint-only-clear"
        ;; The slider's 16px track lands at y 100..115; the neighbour at y=120
        ;; therefore leaves a FOUR pixel gutter between the declared boxes,
        ;; which a 6px knob overhang crosses. The control opens that to a
        ;; gutter wider than the overhang.
        tight (render! (card tight-id [(slider-at 100 100) (plain-box 100 120)]))
        clear (render! (card clear-id [(slider-at 100 100) (plain-box 100 132)]))
        t-sl (node-at tight [0 0 0])
        t-nb (node-at tight [0 0 1])
        c-sl (node-at clear [0 0 0])
        c-nb (node-at clear [0 0 1])
        node-sep (sep (:coords t-sl) (:coords t-nb))
        paint-sep (sep (:paint_box t-sl) (:coords t-nb))
        ov-fs (overlap-of tight-id tight)
        sp-fs (spacing-of tight-id tight)
        cl-fs (spacing-of clear-id clear)]
    [;; 1. THE GAP IS REAL
     [(and node-sep (pos? (long node-sep)))
      (format "the DECLARED boxes are clear: slider %s vs neighbour %s, separation %d"
              (pr-str (:coords t-sl)) (pr-str (:coords t-nb)) node-sep)]

     ;; 2. THE OVERHANG IS REAL, AND ITS SHAPE IS THE KNOB'S
     [(some? (:paint_box t-sl))
      (format "the slider emits an EXACT paint_box: %s (coords %s)"
              (pr-str (:paint_box t-sl)) (pr-str (:coords t-sl)))]
     [(when-let [[l t r b] (overhang (:coords t-sl) (:paint_box t-sl))]
        (and (zero? (long l)) (zero? (long r)) (pos? (long t)) (= t b)))
      (format "…and it overhangs symmetrically across the track and not along it — [l t r b] = %s, which is what a knob grown by a uniform LV_PART_KNOB pad does to a horizontal slider"
              (pr-str (overhang (:coords t-sl) (:paint_box t-sl))))]
     [(and paint-sep (neg? (long paint-sep)))
      (format "…so what is DRAWN crosses the gutter: painted separation %s where the declared one is %s"
              (pr-str paint-sep) (pr-str node-sep))]

     ;; 3. A NODE-BOX RULE REPORTS THE CARD CLEAN
     [(empty? ov-fs)
      (format "devcards.overlap — the node-box lane, at its armed threshold — reports NOTHING on this card: %s"
              (pr-str (mapv (juxt :invariant :node) ov-fs)))]

     ;; 4. THE SPACING RULE FIRES, EXACTLY
     [(and (= 1 (count sp-fs))
           (= :paint-crowding (:invariant (first sp-fs))))
      (format "devcards.spacing fires exactly once, and on the EXACT arm rather than the uncertainty arm: %s"
              (pr-str (mapv (juxt :invariant :node) sp-fs)))]
     [(and (seq sp-fs) (= "lv_slider vs lv_obj" (:node (first sp-fs))))
      (format "…naming both participants: %s" (pr-str (:node (first sp-fs))))]

     ;; 5. THE CONTROL IS SILENT
     [(when-let [s (sep (:paint_box c-sl) (:coords c-nb))] (pos? (long s)))
      (format "control geometry: with the gutter opened up, even the PAINTED boxes are clear — separation %s"
              (pr-str (sep (:paint_box c-sl) (:coords c-nb))))]
     [(empty? cl-fs)
      (format "…and the same two widgets there report nothing: %s"
              (pr-str (mapv (juxt :invariant :node) cl-fs)))]

     ;; 6. ABSENCE IS EXACT
     [(and (nil? (:paint_box t-nb)) (nil? (:paint_bound t-nb)))
      (format "the neighbour emits NEITHER paint key, so absence really is the ordinary case and means 'paints nowhere outside coords' — paint_box %s, paint_bound %s"
              (pr-str (:paint_box t-nb)) (pr-str (:paint_bound t-nb)))]

     ;; 7. THE BUDGET IS RESOLVED, AND PER-SIDE
     [(some? (:overflow_padding t-sl))
      (format "the slider publishes an overflow_padding budget: %s"
              (pr-str (:overflow_padding t-sl)))]
     [(when-let [[l t r b] (:overflow_padding t-sl)]
        (and (= l r) (= t b) (< (long t) (long l))))
      (format "…and it is PER-SIDE rather than one reach — [l t r b] = %s, the two axes differing, with the cross-track pair the smaller: a horizontal knob escapes by its own pad across the track and by half a knob diameter along it"
              (pr-str (:overflow_padding t-sl)))]
     [(and (nil? (:overflow_padding t-nb))
           (= [0 0 0 0] (spacing/overflow-padding t-nb)))
      (format "…while the neighbour publishes none, which the rule reads as the [0 0 0 0] default — so its budget box IS its coords: %s"
              (pr-str (spacing/budget-box t-nb)))]

     ;; 8. CONTAINMENT HOLDS ON A REAL WIDGET
     [(when-let [budget (spacing/budget-box t-sl)]
        (and (:paint_box t-sl)
             (spacing/contains-box? budget (:paint_box t-sl))))
      (format "what the slider DRAWS is inside what its budget entitles it to — paint %s within budget %s, two independent computations over one object agreeing"
              (pr-str (:paint_box t-sl))
              (pr-str (spacing/budget-box t-sl)))]
     [(empty? (filterv #(= :paint-escapes-budget (:invariant %)) sp-fs))
      (format "…and rule 1 reports no escape on this card: %s"
              (pr-str (mapv (juxt :invariant :node)
                            (filterv #(= :paint-escapes-budget (:invariant %))
                                     sp-fs))))]

     ;; 9. THE BUDGET CLAIMS WHAT THE SNAPSHOT DOES NOT
     [(let [budget (spacing/budget-box c-sl)
            paint (:paint_box c-sl)]
        (and budget paint (not= budget paint)
             (spacing/contains-box? budget paint)))
      (format "on the control card the budget reaches strictly further than the pixels — budget %s vs paint %s — the along-track space this mid-range card does not paint into"
              (pr-str (spacing/budget-box c-sl))
              (pr-str (:paint_box c-sl)))]]))

(defn -main
  [& _]
  (let [results (checks)
        failed (remove first results)]
    (doseq [[ok? msg] results]
      (println (format "  %s %s" (if ok? "ok  " "FAIL") msg)))
    (println (format "\nspacing-canary: %d/%d checks passed"
                     (- (count results) (count failed)) (count results)))
    (when (seq failed)
      (println "spacing-canary: FAILED")
      (System/exit 1))
    (println "spacing-canary: GREEN")))
