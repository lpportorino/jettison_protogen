(ns devcards.legos
  "Public palette legos — one-call builder fns composing MULTI-WIDGET
   capabilities from the existing widget vocabulary, expressed as
   authored-composition node maps (devcards.fixtures/build-authored-card
   compiles them to Screen bytes; the authored lane is the one gate into
   events / absolute position / part-selector styling).

   A lego returns a NODE MAP (data), not bytes: callers place it (absolute
   :x/:y or a flex parent), wrap it, or embed it in a larger authored
   card. Each lego owns a bounded, documented set of chrome constants
   (proven geometry at dpi 160); everything semantic stays caller-side —
   a lego emits host-event IDENTITIES, never behavior.

   The two legos:
   - `scrubber` — a media position bar: played + optional buffered bands,
     tap/drag seek via one host event. Anatomy: a bar underlay (track +
     buffered extent) beneath a transparent-track slider overlay (played
     extent + knob) — the composition renders on the stock vocabulary
     with zero renderer additions.
   - `dock-panel` — a foldable side panel of ordered processing-stage
     cards: per-stage enable / reorder-by-buttons / delete, an add
     dropdown, a staged-count badge. Stage BODIES are caller-supplied
     nodes; all stage semantics stay consumer-side."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- assert-closed
  "Throw unless every key of `m` is in `allowed` — the same closed-shape
   law the fixtures builder enforces, applied at the lego boundary so a
   misspelled option fails HERE, naming the lego."
  [ctx allowed m]
  (when-not (map? m)
    (throw (ex-info (str ctx ": expected an options map") {:ctx ctx :got m})))
  (let [extra (remove allowed (keys m))]
    (when (seq extra)
      (throw (ex-info (str ctx ": unknown keys (closed shape)")
                      {:ctx ctx :unknown (vec extra) :allowed (vec (sort allowed))})))))

(defn- require-int!
  [ctx k v]
  (when-not (int? v)
    (throw (ex-info (str ctx ": " k " must be an integer") {:ctx ctx k v}))))

(defn- require-ne-string!
  [ctx k v]
  (when-not (and (string? v) (seq v))
    (throw (ex-info (str ctx ": " k " must be a non-empty string") {:ctx ctx k v}))))

;; ── scrubber ────────────────────────────────────────────────────────────
(def scrubber-palette
  "The scrubber's authored look. Authored style groups are
   family-independent by construction — authored styling is not theme
   styling, so a scrubber looks the same in every theme family."
  {:track "#30363d" :buffered "#8a939b" :played "#2f81f7" :knob "#e6edf3"})

(def scrubber-halo
  "The wrapper's transparent hit-halo band, px per side — equal to the
   renderer's seek_on_press ext-click widening (LV_DPX(24) at the pinned
   dpi 160). LVGL's indev search descends into children only when the
   point is ON the parent's coords (lv_indev_search_obj), so an exact-fit
   wrapper structurally erases the slider's widened click area (measured:
   every tap 1px past the wrapper missed). The wrapper therefore extends
   `scrubber-halo` px beyond the track on every side; its boundary then
   coincides exactly with the slider's ext-click boundary."
  24)

(def ^:private scrubber-keys #{:min :max :value :buffered :width :height :seek-event-name})

(defn- scrubber-slider
  "The slider overlay: played indicator + knob; MAIN transparent when a
   bar underlay carries the track, else MAIN is the track itself.

   `seek_on_press` is set UNCONDITIONALLY: press-seek is the scrubber's
   contract (a media scrubber seeks the moment the finger lands), and the
   renderer couples the widened tap target (ext_click_area, LV_DPX(24)) to
   the same prop. The prop is pixel-inert — the scrubber's look never
   depends on it."
  [{:keys [min max value width height seek-event-name]} buffered?]
  {:type :WIDGET_SLIDER
   :props {:slider_props {:min_value min :max_value max :value value :seek_on_press true}}
   :event {:name seek-event-name :trigger :value-changed :include-widget-value true}
   :styles (into [(if buffered?
                    {:part :main :props {:w width :h height :bg-opa 0}}
                    {:part :main
                     :props
                     {:w width :h height :bg-color (:track scrubber-palette) :radius 4}})]
                 [{:part :indicator :props {:bg-color (:played scrubber-palette) :radius 4}}
                  {:part :knob :props {:bg-color (:knob scrubber-palette)}}])})

(defn- scrubber-bar
  "The underlay bar: MAIN = the unplayed track, INDICATOR = the buffered
   extent. Sits beneath the transparent-track slider overlay."
  [{:keys [min max buffered width height]}]
  {:type :WIDGET_BAR
   :props {:bar_props {:min_value min :max_value max :value buffered}}
   :styles [{:part :main
             :props {:w width :h height :bg-color (:track scrubber-palette) :radius 4}}
            {:part :indicator :props {:bg-color (:buffered scrubber-palette) :radius 4}}]})

(defn scrubber
  "A media scrubber node: `{:min :max :value :buffered :width :height
   :seek-event-name}` (all required ints except optional :buffered;
   :seek-event-name a non-empty string). Emits ONE host-event identity:
   `seek-event-name` with the slider's value — fired the moment the
   pointer lands (seek_on_press: press-seek + the renderer's LV_DPX(24)
   ext-click widening), then per value change during a drag, with no
   duplicate at release.

   With :buffered — a bar underlay (track + buffered band) under a
   transparent-track slider overlay (played band + knob), overlapping on
   one rect (both children at the wrapper's content origin; z-order =
   child order). Without — a single styled slider.

   PLACEMENT: the returned node is a transparent hit-halo wrapper of
   (:width + 2×scrubber-halo) × (:height + 2×scrubber-halo); the TRACK
   rect sits inset `scrubber-halo` px from the wrapper origin (the
   wrapper pads by the halo). To place the track at (tx, ty), place the
   wrapper at (tx - scrubber-halo, ty - scrubber-halo). The halo is what
   keeps the widened tap target alive through the composition — LVGL's
   hit-test descent stops at the wrapper's own box (see scrubber-halo).

   The wrapper is a STYLED transparent obj, deliberately not `bare`: the
   renderer applies node x/y before the bare strip, so a bare wrapper
   silently loses an authored position (lv_obj_set_pos writes local
   styles lv_obj_remove_style_all erases — measured)."
  [{:keys [min max value buffered width height seek-event-name] :as opts}]
  (assert-closed "scrubber" scrubber-keys opts)
  (doseq [[k v] {:min min :max max :value value :width width :height height}]
    (require-int! "scrubber" k v))
  (require-ne-string! "scrubber" :seek-event-name seek-event-name)
  (when-not (< (long min) (long max))
    (throw (ex-info "scrubber: :min must be < :max" {:min min :max max})))
  (doseq [[k v] (cond-> {:value value} (some? buffered) (assoc :buffered buffered))]
    (require-int! "scrubber" k v)
    (when-not (<= (long min) (long v) (long max))
      (throw (ex-info (str "scrubber: " k " outside [:min :max]")
                      {k v :min min :max max}))))
  (when-not (and (pos? (long width)) (pos? (long height)))
    (throw (ex-info "scrubber: :width/:height must be positive"
                    {:width width :height height})))
  {:type :WIDGET_OBJ
   :styles [{:part :main
             :props {:w (+ (long width) (* 2 scrubber-halo))
                     :h (+ (long height) (* 2 scrubber-halo))
                     :pad-all scrubber-halo
                     :border-width 0
                     :bg-opa 0}}]
   :flags-clear [:scrollable]
   :children (if (some? buffered)
               [(scrubber-bar opts) (scrubber-slider opts true)]
               [(scrubber-slider opts false)])})

;; ── scrubber-rich ───────────────────────────────────────────────────────
;; The plain `scrubber` is the position bar and nothing else. A media player
;; wants more around it, but WHICH more is product-specific — so this lego is
;; strictly ADDITIVE and every part is opt-in: with no optional key it is the
;; plain scrubber plus a wrapper, and with all of them it is the rich player.
;; The base `scrubber` node is EMBEDDED unchanged, so its proven halo /
;; press-seek / placement contract keeps holding here.

;; LVGL FontAwesome transport glyphs (lv_symbol_def.h). Private-use codepoints
;; carried by the theme font's merged symbol range — no image assets.
(def ^:private sym-prev "")

(def ^:private sym-play "")

(def ^:private sym-pause "")

(def ^:private sym-stop "")

(def ^:private sym-next "")

(def transport-glyphs
  "Transport key → glyph. The key is also the host-event suffix, so a caller
   reading `<transport-event-name>-play` needs no glyph knowledge."
  {:prev sym-prev :play sym-play :pause sym-pause :stop sym-stop :next sym-next})

(def scrubber-rich-chrome
  "Chrome geometry for the optional parts, px at dpi 160. One documented home
   so the derived wrapper height and the proof's expectations agree.

   Each row's height budgets MORE than its nominal content, deliberately. A
   box sized to exactly what it holds overflows, because the theme adds its
   own padding inside the row and LVGL draws past the nominal extent — a
   labelled scale renders its tick TEXT below the tick marks, and a button
   sized to the row height has no room left once the row is padded. The
   surplus here is what keeps the :clipped / :overflow invariants green."
  {:btn-w 40 :btn-h 32 :transport-h 44 :tick-h 46 :readout-h 24})

(def ^:private scrubber-rich-keys
  #{:min :max :value :buffered :width :height :seek-event-name
    :ticks :labels :readout :transport :transport-event-name})

(defn- tick-scale
  "The tick strip under the track: a horizontal-bottom scale spanning the same
   value range as the bar, so a tick sits under the position it denotes.
   `labels` (optional, \"\\n\"-joined into :text_src) supplies the MAJOR-tick
   texts — that is how a timecode ruler is expressed without any new
   vocabulary; without it the ticks are drawn unlabelled."
  [{:keys [min max width ticks labels x y]}]
  (let [{:keys [total major-every]} ticks]
    {:type :WIDGET_SCALE
     :props {:w width
             :h (:tick-h scrubber-rich-chrome)
             :x x
             :y y
             :scale_props
             (cond-> {:mode :horizontal-bottom
                      :min_value min
                      :max_value max
                      :total_tick_count total
                      :major_tick_every major-every
                      :label_show (boolean (seq labels))}
               (seq labels) (assoc :text_src (str/join "\n" labels)))}}))

(defn- transport-row
  "The transport button group: one symbol button per requested key, in the
   caller's order. Each emits `<transport-event-name>-<key>` so the group is
   one event family the host can switch on."
  [{:keys [transport transport-event-name width x y]}]
  (let [{:keys [btn-w btn-h transport-h]} scrubber-rich-chrome]
    {:type :WIDGET_OBJ
     ;; Full TRACK width with main-place CENTER: the theme owns the inter-button
     ;; gap and a lego cannot pin it, so instead of sizing to an exact sum (which
     ;; clipped the buttons) the row is given abundant horizontal slack and the
     ;; group is centered inside it. Overflow becomes structurally impossible.
     ;; Transparent + borderless: a themed lv_obj would draw a panel box around
     ;; the buttons, which reads as chrome the scrubber does not have.
     :styles [{:part :main
               :props {:w width :h transport-h :pad-all 0 :x x :y y
                       :border-width 0 :bg-opa 0}}]
     :layout {:flow :row :main-place :center :cross-place :center}
     :flags-clear [:scrollable]
     :children (mapv (fn [k]
                       {:type :WIDGET_BUTTON
                        :props {:w btn-w :h btn-h}
                        :event {:name (str transport-event-name "-" (name k))
                                :trigger :clicked}
                        :children [{:type :WIDGET_LABEL :text (transport-glyphs k)}]})
                     transport)}))

(defn- readout-row
  "Elapsed / total readout. Kept OFF the track deliberately: labels drawn on a
   narrow track collide at small widths, while a readout beside it stays
   legible at any width."
  [{:keys [width readout x y]}]
  (let [{:keys [elapsed total]} readout]
    {:type :WIDGET_OBJ
     ;; transparent + borderless for the same reason as the transport row
     :styles [{:part :main
               :props {:w width :h (:readout-h scrubber-rich-chrome)
                       :pad-all 0 :x x :y y :border-width 0 :bg-opa 0}}]
     :layout {:flow :row :main-place :space-between :cross-place :center}
     :flags-clear [:scrollable]
     :children [{:type :WIDGET_LABEL :text (str elapsed)}
                {:type :WIDGET_LABEL :text (str total)}]}))

(defn scrubber-rich
  "A configurable media scrubber. Required keys are the plain `scrubber`'s;
   every other key adds one part, so the same lego spans a bare position bar
   and a full transport player:

     :ticks    {:total N :major-every M}  — a tick ruler under the track
     :labels   [\"0:00\" \"1:00\" …]          — MAJOR-tick texts (needs :ticks)
     :readout  {:elapsed \"1:12\" :total \"3:40\"} — elapsed/total beside the bar
     :transport [:prev :play :pause :stop :next] — a grouped button row
     :transport-event-name \"tp\"           — required with :transport

   Parts stack in a column: transport, track, ticks, readout. The embedded
   `scrubber` keeps its own hit-halo, so the track's tap target is unchanged
   by anything stacked around it."
  [{:keys [width height ticks labels readout transport transport-event-name]
    :as opts}]
  (assert-closed "scrubber-rich" scrubber-rich-keys opts)
  (when (and (seq labels) (not ticks))
    (throw (ex-info "scrubber-rich: :labels needs :ticks" {:labels labels})))
  (when ticks
    (doseq [k [:total :major-every]]
      (require-int! "scrubber-rich :ticks" k (get ticks k))))
  (when transport
    (when-not (and (vector? transport) (seq transport))
      (throw (ex-info "scrubber-rich: :transport must be a non-empty vector"
                      {:transport transport})))
    (let [unknown (remove (set (keys transport-glyphs)) transport)]
      (when (seq unknown)
        (throw (ex-info "scrubber-rich: unknown :transport keys"
                        {:unknown (vec unknown)
                         :allowed (vec (sort (keys transport-glyphs)))}))))
    (require-ne-string! "scrubber-rich" :transport-event-name transport-event-name))
  (when readout
    (doseq [k [:elapsed :total]]
      (require-ne-string! "scrubber-rich :readout" k (str (get readout k)))))
  (let [base (scrubber (select-keys opts scrubber-keys))
        {:keys [tick-h readout-h transport-h]} scrubber-rich-chrome
        halo (long scrubber-halo)
        ;; the embedded scrubber carries its halo on BOTH sides, which also
        ;; supplies the visual breathing room above/below the track — so the
        ;; stacked parts butt directly against it with no extra gap.
        base-h (+ (long height) (* 2 halo))
        t-h (if transport (long transport-h) 0)
        k-h (if ticks (long tick-h) 0)
        r-h (if readout (long readout-h) 0)
        y-base t-h
        y-ticks (+ t-h base-h)
        y-readout (+ t-h base-h k-h)
        wrapper-w (+ (long width) (* 2 halo))]
    ;; The vertical stack is positioned ABSOLUTELY, deliberately not with a
    ;; column flex: flex's inter-child gap comes from the THEME and is not
    ;; settable from the authored vocabulary (no pad-row), so any exact height
    ;; a lego computes under-measures and trips the :overflow invariant. With
    ;; explicit :y the arithmetic here IS the layout, in every theme family.
    {:type :WIDGET_OBJ
     ;; Same shape as the embedded scrubber's own wrapper, and for the same
     ;; reason: an lv_obj left to the theme picks up a BORDER (and bg), and the
     ;; border shrinks the content box the children are laid out in — enough to
     ;; push the bottom-most part out of it and trip :overflow/:clipped. Zero
     ;; border + transparent bg makes the box exactly the arithmetic above.
     :styles [{:part :main
               :props {:w wrapper-w
                       :h (+ t-h base-h k-h r-h)
                       :pad-all 0
                       :border-width 0
                       :bg-opa 0}}]
     :flags-clear [:scrollable]
     :children (cond-> []
                 transport (conj (transport-row (assoc opts :x halo :y 0)))
                 ;; position INTO the embedded scrubber's existing :main style
                 ;; group — a node may carry a :main styles group OR :props
                 ;; style slots, never both (they would emit two MAIN groups).
                 :always (conj (update-in base [:styles 0 :props]
                                          assoc :x 0 :y y-base))
                 ticks (conj (tick-scale (assoc opts :x halo :y y-ticks)))
                 readout (conj (readout-row (assoc opts :x halo :y y-readout))))}))

;; ── dock-panel ──────────────────────────────────────────────────────────
;; LVGL built-in FontAwesome symbol glyphs (lv_symbol_def.h) — plain UTF-8
;; text on the wire; the theme font carries the merged symbol range, so
;; the chrome icons need NO image assets.
(def ^:private sym-list "")

(def ^:private sym-up "")

(def ^:private sym-down "")

(def ^:private sym-close "")

(def dock-chrome
  "The dock's proven chrome geometry at dpi 160 (px). One documented home
   so the derived panel/rail heights and the proof's expectations read
   from the same constants."
  {:panel-w 320
   :card-w 288
   :row-w 272
   :header-h 46
   :caption-h 48
   ;; The stage-name label's width inside the 272px caption row. The row is at
   ;; capacity: the switch + 3 icon buttons + the THEME's flex gaps (not
   ;; settable from here — no pad-column) consume the rest, and widening this
   ;; overflows the row and CLIPS the delete button out of existence, taking
   ;; its event with it. A long stage name therefore wraps by design; the
   ;; caption row centers it on the cross axis so a wrapped label reads as
   ;; centered rather than clipped.
   :caption-label-w 52
   :body-h 46
   :card-collapsed-h 64
   :card-expanded-h 118
   :icon-btn 30
   :rail-w 64
   :rail-btn-w 36
   :rail-btn-h 34
   :badge-w 34
   :badge-h 26
   :flex-gap 14
   :panel-pad 9
   :dropdown-h 50})

(def ^:private dock-keys #{:folded? :badge :stages})

(def ^:private stage-keys #{:id :label :enabled? :collapsed? :body-nodes})

(defn- validate-dock!
  [{:keys [badge stages] :as opts}]
  (assert-closed "dock-panel" dock-keys opts)
  (require-int! "dock-panel" :badge badge)
  (when-not (and (vector? stages) (seq stages))
    (throw (ex-info "dock-panel: :stages must be a non-empty vector" {:stages stages})))
  (doseq [s stages]
    (assert-closed "dock-panel stage" stage-keys s)
    (require-ne-string! "dock-panel stage" :id (:id s))
    (require-ne-string! "dock-panel stage" :label (:label s)))
  (let [ids (mapv :id stages)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "dock-panel: stage :id values must be distinct" {:ids ids})))))

(defn- icon-button
  "A 30x30 symbol button; `event` (optional) is the authored event map."
  [glyph event]
  (cond-> {:type :WIDGET_BUTTON
           :props {:w (:icon-btn dock-chrome) :h (:icon-btn dock-chrome)}
           :children [{:type :WIDGET_LABEL :text glyph}]}
    event (assoc :event event)))

(defn- badge-node
  "The staged-count badge: a pill obj with the count label.

   The label is CENTERED on both axes. Without a layout it lands at the content
   box's top-left, which on a 34x26 pill with a 1px border and 2px pad puts the
   digit hard against the border's inner curve — it reads as touching."
  [badge]
  {:type :WIDGET_OBJ
   :layout {:flow :row :main-place :center :cross-place :center}
   :props {:w (:badge-w dock-chrome)
           :h (:badge-h dock-chrome)
           :radius 13
           :border-width 1
           :pad-all 2}
   :flags-clear [:scrollable]
   :children [{:type :WIDGET_LABEL :text (str badge)}]})

(defn- stage-caption
  "One stage's caption row: enable switch + label + reorder/delete
   buttons. Event identities: `<stage-id>-toggle/-up/-down/-delete`, each
   with int_value = the stage's index (the switch fires on VALUE_CHANGED;
   the host flips its own model — the widget value would clobber the index
   in the single-value envelope, so it is deliberately NOT included)."
  [{:keys [id label enabled?]} idx]
  {:type :WIDGET_OBJ
   :props {:w (:row-w dock-chrome) :h (:caption-h dock-chrome) :pad-all 2}
   ;; cross-place CENTER: flex's cross axis defaults to START, which top-aligns
   ;; every child against the row box — a taller-than-text switch then leaves
   ;; the label riding the top edge and reading as clipped.
   :layout {:flow :row :cross-place :center}
   :flags-clear [:scrollable]
   ;; WIDGET_SWITCH, not WIDGET_CHECKBOX: a checkbox with no authored text
   ;; renders the stock default "Check box" label (the renderer skips empty
   ;; text, so the wire cannot express an icon-only checkbox).
   :children [{:type :WIDGET_SWITCH
               :props {:switch_props {:checked (boolean enabled?)}}
               :event {:name (str id "-toggle") :trigger :value-changed :int-value idx}}
              ;; :caption-label-w, not 52: at 52 a stage name as ordinary as
              ;; "Sharpen" wraps to two lines and overflows :caption-h.
              {:type :WIDGET_LABEL :text label :props {:w (:caption-label-w dock-chrome)}}
              (icon-button sym-up {:name (str id "-up") :int-value idx})
              (icon-button sym-down {:name (str id "-down") :int-value idx})
              (icon-button sym-close {:name (str id "-delete") :int-value idx})]})

(defn- stage-card
  "One stage card: caption row (+ body row unless collapsed). `body-nodes`
   are caller-supplied authored nodes laid out in a 272px row; a collapsed
   stage keeps them in the caller's data but renders caption-only."
  [{:keys [collapsed? body-nodes] :as stage} idx]
  (let [body? (and (not collapsed?) (seq body-nodes))]
    {:type :WIDGET_OBJ
     :props {:w (:card-w dock-chrome)
             :h (if body? (:card-expanded-h dock-chrome) (:card-collapsed-h dock-chrome))
             :pad-all 4}
     ;; main-place CENTER: the card box is a fixed height from dock-chrome, so
     ;; whatever it holds is shorter than the box — flex's default START pins
     ;; that content to the top edge and pools all the slack underneath, which
     ;; reads as "the entry is stuck to the top of its container".
     :layout {:flow :column :main-place :center :cross-place :center}
     :flags-clear [:scrollable]
     :children (cond-> [(stage-caption stage idx)]
                 body? (conj {:type :WIDGET_OBJ
                              :props
                              {:w (:row-w dock-chrome) :h (:body-h dock-chrome) :pad-all 2}
                              :layout {:flow :row}
                              :flags-clear [:scrollable]
                              :children (vec body-nodes)}))}))

(defn- dock-header
  "The panel header: fold toggle (`dock-fold`) + title + badge."
  [badge]
  {:type :WIDGET_OBJ
   :props {:w (:card-w dock-chrome) :h (:header-h dock-chrome) :pad-all 2}
   :layout {:flow :row}
   :flags-clear [:scrollable]
   :children [(icon-button sym-list {:name "dock-fold"})
              {:type :WIDGET_LABEL :text "STAGES" :props {:w 130}} (badge-node badge)]})

(defn- stack-h
  "Height of a column-flex stack: children + inter-child gaps + pads."
  [child-hs]
  (let [{:keys [flex-gap panel-pad]} dock-chrome]
    (+ (reduce + 0 child-hs) (* flex-gap (dec (count child-hs))) (* 2 panel-pad))))

(defn- expanded-panel
  [{:keys [badge stages]}]
  (let [cards (vec (map-indexed (fn [i s] (stage-card s i)) stages))
        heights (into [(:header-h dock-chrome)]
                      (conj (mapv #(get-in % [:props :h]) cards)
                            (:dropdown-h dock-chrome)))]
    {:type :WIDGET_OBJ
     :props {:w (:panel-w dock-chrome) :h (stack-h heights)}
     :layout {:flow :column}
     :children (-> [(dock-header badge)]
                   (into cards)
                   ;; The add control: fires `dock-add` with the selected
                   ;; option index. The option LIST is consumer content the
                   ;; ratified lego shape does not yet carry — the closed
                   ;; prompt renders until the shape grows an option key.
                   (conj {:type :WIDGET_DROPDOWN
                          :props {:w (:card-w dock-chrome)
                                  :dropdown_props {:options "Add stage" :selected 0}}
                          :event {:name "dock-add"
                                  :trigger :value-changed
                                  :include-widget-value true}}))}))

(defn- folded-rail
  "The folded state: an icon rail — fold toggle + one letter button per
   stage (chrome only; rail taps are a consumer-side concern) + badge."
  [{:keys [badge stages]}]
  (let [heights (into [(:icon-btn dock-chrome)]
                      (conj (mapv (constantly (:rail-btn-h dock-chrome)) stages)
                            (:badge-h dock-chrome)))]
    {:type :WIDGET_OBJ
     :props {:w (:rail-w dock-chrome) :h (stack-h heights)}
     :layout {:flow :column}
     :children (-> [(icon-button sym-list {:name "dock-fold"})]
                   (into (for [s stages]
                           {:type :WIDGET_BUTTON
                            :props {:w (:rail-btn-w dock-chrome)
                                    :h (:rail-btn-h dock-chrome)}
                            :children [{:type :WIDGET_LABEL
                                        :text (str/upper-case (subs (:label s) 0 1))}]}))
                   (conj (badge-node badge)))}))

(defn dock-panel
  "A foldable stage-manager side panel node: `{:folded? <bool> :badge
   <int> :stages [{:id :label :enabled? :collapsed? :body-nodes} ...]}`.

   Expanded — header (fold toggle + title + count badge), one card per
   stage (enable switch, label, ▲▼✕ buttons; caller-supplied body nodes
   in a row unless :collapsed?), and an add dropdown. Folded — a narrow
   icon rail (fold toggle + per-stage letter buttons + badge); the fold
   is two authored states, re-emitted (or patched) by the caller.

   Host-event identities (int_value = stage index where present):
   `dock-fold`, `dock-add` (selected option index as the widget value),
   `<stage-id>-toggle`, `<stage-id>-up`, `<stage-id>-down`,
   `<stage-id>-delete`. All stage semantics live with the caller.

   Panel height derives from the stage count via `dock-chrome`; a stack
   too tall for its screen is the caller's scroll/size decision."
  [{:keys [folded?] :as opts}]
  (validate-dock! opts)
  (if folded? (folded-rail opts) (expanded-panel opts)))