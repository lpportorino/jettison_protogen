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
  "The staged-count badge: a pill obj with the count label."
  [badge]
  {:type :WIDGET_OBJ
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
   :layout {:flow :row}
   :flags-clear [:scrollable]
   ;; WIDGET_SWITCH, not WIDGET_CHECKBOX: a checkbox with no authored text
   ;; renders the stock default "Check box" label (the renderer skips empty
   ;; text, so the wire cannot express an icon-only checkbox).
   :children [{:type :WIDGET_SWITCH
               :props {:switch_props {:checked (boolean enabled?)}}
               :event {:name (str id "-toggle") :trigger :value-changed :int-value idx}}
              {:type :WIDGET_LABEL :text label :props {:w 52}}
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
     :layout {:flow :column}
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