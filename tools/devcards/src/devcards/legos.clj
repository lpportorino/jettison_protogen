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

   The legos — the authoritative set is the closed `devcards.composition/makers`
   map, and a new lego is a corpus + legos change that registers there:
   - `scrubber` — a media position bar: played + optional buffered bands,
     tap/drag seek via one host event. Anatomy: a bar underlay (track +
     buffered extent) beneath a transparent-track slider overlay (played
     extent + knob) — the composition renders on the stock vocabulary
     with zero renderer additions.
   - `dock-panel` — a foldable side panel of ordered processing-stage
     cards: per-stage enable / reorder-by-buttons / delete, an add
     dropdown, a staged-count badge. Stage BODIES are caller-supplied
     nodes; all stage semantics stay consumer-side."
  (:require [clojure.string :as str]
            [devcards.tokens :as tokens]))

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

(def ^:private lv-state-disabled
  "Wire lv_state_t DISABLED bit — the node vocabulary's :states-bits carries
   the mask verbatim (fixtures.clj node shape)."
  512)

(def centering-note
  "WHY EVERY CENTERED CONTAINER HERE SETS BOTH :cross-place AND :track-place.

   LVGL flex has TWO cross-axis knobs and they do different jobs. A flex
   container lays its children into TRACKS (one track per line; every lego
   here is single-track). Then:

     :cross-place  places each ITEM inside its track
     :track-place  places the TRACK inside the container

   A track is only as thick as its tallest item. So in a box that is TALLER
   than its content — every fixed-height row in this file — :cross-place
   alone changes nothing about where the content block sits: the track stays
   pinned at the container's start edge and the items merely centre against
   each other inside it. Only :track-place moves the block.

   Measured on the dock, which is how this was finally pinned down. The 48px
   caption row holds a 37px switch and 30px buttons. With :cross-place :center
   and no :track-place, the track sat at the row's top (y 80) and the buttons
   centred within it at y 83.5 — the framebuffer showed 84. Correct centring
   would have put them at 86. The 46px body row was worse: a 25px band pinned
   to the top edge, ~10px high, which is what reads as \"the entries are stuck
   to the top of their container\".

   The same applies transposed on a COLUMN, where the cross axis is
   horizontal: a 288px card stack inside a 320px panel needs :track-place to
   sit centred, not merely :cross-place."
  :see-the-docstring)

;; ── scrubber ────────────────────────────────────────────────────────────
(def scrubber-palette
  "The scrubber's authored look. Authored style groups are
   family-independent by construction — authored styling is not theme
   styling, so a scrubber looks the same in every theme family. Two entries
   resolve their colour from tokens.edn through design-tokens.json
   (devcards.tokens) — one home instead of an inline literal: :played from
   :media-accent (the media hue; its value is bit-identical to the former
   literal, so it moves no golden pixel), and :knob from :media-knob (the
   canonical bright-neutral). The :knob promotion collapses a near-duplicate
   off-white (#e6edf3, Δ 2/5/3 vs the token's #E8E8F0) onto the one home, so
   it is a real — if tiny — pixel change and re-mints the scrubber goldens.

   :knob reads :media-knob, NOT :accent-text, though both resolve to the same
   #E8E8F0 and this knob's pixels are unchanged by the split. The knob sits on
   the scrubber's OWN authored track, never on an accent fill, so it is
   genuinely mode-invariant — while :accent-text must fork with :accent-bg.
   One token served both roles once, and that shared identity is exactly what
   made forking the accent ink look impossible."
  {:track "#30363d"
   :buffered "#8a939b"
   :played (tokens/color :media-accent)
   :knob (tokens/color :media-knob)})

(def scrubber-halo
  "The scrubber's hit affordance, px per side. ONE home for two things that
   must be equal and used to be authored separately: the slider's
   `hit_slop` (the widened hit box) and the wrapper's transparent band (the
   layout space that widening is allowed to occupy). It was previously the
   wrapper alone, kept equal BY HAND to a renderer-side constant; hit_slop
   put the widening on the wire, so the same number now drives both and the
   two cannot drift.

   Both halves are load-bearing. LVGL's indev search descends into children
   only when the point is ON the parent's coords (lv_indev_search_obj), so
   an exact-fit wrapper structurally erases the widened click area
   (measured: every tap 1px past the wrapper missed). And a widening with
   no reserved band is exactly the hazard `hit_slop`'s authoring duty names
   — it would bleed into whatever sits beside the scrubber. The wrapper
   therefore extends `scrubber-halo` px beyond the track on every side, and
   its boundary coincides with the slider's hit boundary."
  24)

(def ^:private scrubber-keys #{:min :max :value :buffered :width :height :seek-event-name})

(defn- scrubber-slider
  "The slider overlay: played indicator + knob; MAIN transparent when a
   bar underlay carries the track, else MAIN is the track itself.

   `seek_on_press` is set UNCONDITIONALLY: press-seek is the scrubber's
   contract — a media scrubber seeks the moment the finger lands. It is
   BEHAVIOUR ONLY; the widened tap target is `:hit-slop` below, which the
   two used to share as one fused flag. Both are pixel-inert — the
   scrubber's look never depends on either."
  [{mn :min mx :max :keys [value width height seek-event-name]} buffered?]
  {:type :WIDGET_SLIDER
   :hit-slop scrubber-halo
   :props {:slider_props {:min_value mn :max_value mx :value value :seek_on_press true}}
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
  [{mn :min mx :max :keys [buffered width height]}]
  {:type :WIDGET_BAR
   ;; DECORATION, and it must say so: this bar sits UNDER a transparent-track
   ;; slider that owns the interaction. Left clickable it stays in the pointer
   ;; path, competing for presses it must never win — a dead element by paint
   ;; order rather than by intent. Clearing the flag is the by-construction
   ;; resolution; it moves no pixel.
   :flags-clear [:clickable]
   :props {:bar_props {:min_value mn :max_value mx :value buffered}}
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
  [{mn :min mx :max :keys [value buffered width height seek-event-name] :as opts}]
  (assert-closed "scrubber" scrubber-keys opts)
  (doseq [[k v] {:min mn :max mx :value value :width width :height height}]
    (require-int! "scrubber" k v))
  (require-ne-string! "scrubber" :seek-event-name seek-event-name)
  (when-not (< (long mn) (long mx))
    (throw (ex-info "scrubber: :min must be < :max" {:min mn :max mx})))
  (doseq [[k v] (cond-> {:value value} (some? buffered) (assoc :buffered buffered))]
    (require-int! "scrubber" k v)
    (when-not (<= (long mn) (long v) (long mx))
      (throw (ex-info (str "scrubber: " k " outside [:min :max]")
                      {k v :min mn :max mx}))))
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
  "Transport key → glyph, for the keys whose glyph is FIXED. The key is also
   the host-event suffix, so a caller reading `<transport-event-name>-prev`
   needs no glyph knowledge.

   `:play-pause` is deliberately NOT here: it is the one STATEFUL control, so
   its glyph is not a function of the key alone (see `transport-glyph`)."
  {:prev sym-prev :play sym-play :pause sym-pause :stop sym-stop :next sym-next})

(def transport-keys
  "Every accepted `:transport` key — the fixed-glyph ones plus `:play-pause`."
  (conj (set (keys transport-glyphs)) :play-pause))

(defn- transport-glyph
  "The glyph for one transport key. `:play-pause` is a single button carrying
   TWO icons: a media player has one play/pause control whose glyph shows the
   action available, so a playing transport offers ⏸ and a paused one ⏵. It
   still emits ONE event identity (`…-play-pause`) in both states — the host
   toggles its own state and re-renders with `:playing?` flipped, exactly as
   it would for any other stateful widget."
  [k playing?]
  (if (= k :play-pause)
    (if playing? sym-pause sym-play)
    (get transport-glyphs k)))

(def scrubber-rich-chrome
  "Chrome geometry for the optional parts, px at dpi 160. One documented home
   so the derived wrapper height and the proof's expectations agree.

   Each row's height budgets MORE than its nominal content, deliberately. A
   box sized to exactly what it holds overflows, because the theme adds its
   own padding inside the row and LVGL draws past the nominal extent — a
   labelled scale renders its tick TEXT below the tick marks, and a button
   sized to the row height has no room left once the row is padded. The
   surplus here is what keeps the :clipped / :overflow invariants green.

   `:readout-w` is the width of EACH flanking readout box on the transport
   row. Both flanks are given the SAME width on purpose: LVGL's
   `space-between` splits the leftover evenly BETWEEN children, so a 3-child
   row centers its middle child exactly when the two outer children are equal
   width — and only then. Equal boxes make the button group's centering a
   property of the layout rather than of how long the timecodes happen to be."
  {:btn-w 40 :btn-h 32 :transport-h 44 :tick-h 46 :readout-h 24 :readout-w 64})

(def ^:private scrubber-rich-keys
  #{:min :max :value :buffered :width :height :seek-event-name
    :ticks :labels :readout :transport :transport-event-name :playing?})

(defn- tick-scale
  "The tick strip under the track: a horizontal-bottom scale spanning the same
   value range as the bar, so a tick sits under the position it denotes.
   `labels` (optional, \"\\n\"-joined into :text_src) supplies the MAJOR-tick
   texts — that is how a timecode ruler is expressed without any new
   vocabulary; without it the ticks are drawn unlabelled."
  [{mn :min mx :max :keys [width ticks labels x y]}]
  (let [{:keys [total major-every]} ticks]
    {:type :WIDGET_SCALE
     :props {:w width
             :h (:tick-h scrubber-rich-chrome)
             :x x
             :y y
             :scale_props
             (cond-> {:mode :horizontal-bottom
                      :min_value mn
                      :max_value mx
                      :total_tick_count total
                      :major_tick_every major-every
                      :label_show (boolean (seq labels))}
               (seq labels) (assoc :text_src (str/join "\n" labels)))}}))

(defn- readout-label
  "One flanking timecode. Fixed `:readout-w` box (see `scrubber-rich-chrome`)
   with the text aligned to the edge it hugs, so the label reads as pinned to
   the row's end no matter how long the timecode is."
  [text align]
  {:type :WIDGET_LABEL
   :text (str text)
   :styles [{:part :main
             :props {:w (:readout-w scrubber-rich-chrome) :text-align align}}]})

(defn- transport-group
  "The buttons themselves: one symbol button per requested key, in the
   caller's order. Each emits `<transport-event-name>-<key>` so the group is
   one event family the host can switch on — `:play-pause` included, which
   keeps one identity across both of its glyphs."
  [{:keys [transport transport-event-name playing?]} width]
  (let [{:keys [btn-w btn-h transport-h]} scrubber-rich-chrome]
    {:type :WIDGET_OBJ
     ;; main-place CENTER over abundant width: rather than sizing to an exact
     ;; sum (which clipped the buttons) the group is given horizontal slack and
     ;; centred inside it, so overflow is structurally impossible whatever the
     ;; gap is. The gap IS authorable now — `:pad-gap`, added with the wire's
     ;; existing PROP_PAD_GAP — so this is a choice rather than the necessity
     ;; this comment used to claim; slack plus centring survives a theme whose
     ;; gap we did not predict, which an exact sum does not.
     ;; Transparent + borderless: a themed lv_obj would draw a panel box around
     ;; the buttons, which reads as chrome the scrubber does not have.
     :styles [{:part :main
               :props {:w width :h transport-h :pad-all 0
                       :border-width 0 :bg-opa 0}}]
     :layout {:flow :row :main-place :center
              :cross-place :center :track-place :center}
     :flags-clear [:scrollable]
     ;; :pad-all 0 + explicit centring, the same reason the dock's
     ;; `icon-button` carries: a button whose box the lego SIZES must own its
     ;; padding too. Inherited, the content box is btn-w minus twice the
     ;; theme's pad — negative under any theme padding wider than half the
     ;; button — and the glyph is then laid out outside its own button
     ;; (:overflow on the button, :clipped on the label, on every transport
     ;; card under families 1 and 2 while family 0 stayed clean).
     :children (mapv (fn [k]
                       {:type :WIDGET_BUTTON
                        :props {:w btn-w :h btn-h :pad-all 0}
                        :layout {:flow :row :main-place :center
                                 :cross-place :center :track-place :center}
                        :event {:name (str transport-event-name "-" (name k))
                                :trigger :clicked}
                        :children [{:type :WIDGET_LABEL
                                    :text (transport-glyph k playing?)}]})
                     transport)}))

(defn- transport-row
  "The transport row: the button group, optionally FLANKED by the elapsed and
   total timecodes. Putting the readout here rather than on its own strip is
   what the row's own empty shoulders are for — the group only ever occupies
   the middle, and a separate readout line buys a second row of height to say
   two short words."
  [{:keys [readout width x y] :as opts}]
  (let [{:keys [elapsed total]} readout
        {:keys [transport-h readout-w]} scrubber-rich-chrome
        ;; equal flanks => `space-between` centers the group exactly; see the
        ;; `:readout-w` note in scrubber-rich-chrome.
        group-w (if readout (- (long width) (* 2 (long readout-w))) (long width))]
    {:type :WIDGET_OBJ
     ;; transparent + borderless for the same reason as the group itself
     :styles [{:part :main
               :props {:w width :h transport-h :pad-all 0 :x x :y y
                       :border-width 0 :bg-opa 0}}]
     :layout {:flow :row :main-place :space-between
              :cross-place :center :track-place :center}
     :flags-clear [:scrollable]
     :children (if readout
                 [(readout-label elapsed :left)
                  (transport-group opts group-w)
                  (readout-label total :right)]
                 [(transport-group opts group-w)])}))

(defn- readout-row
  "Elapsed / total readout as its OWN strip — the shape used only when there
   is no transport row to flank. Kept OFF the track deliberately: labels drawn
   on a narrow track collide at small widths, while a readout beside it stays
   legible at any width."
  [{:keys [width readout x y]}]
  (let [{:keys [elapsed total]} readout]
    {:type :WIDGET_OBJ
     ;; transparent + borderless for the same reason as the transport row
     :styles [{:part :main
               :props {:w width :h (:readout-h scrubber-rich-chrome)
                       :pad-all 0 :x x :y y :border-width 0 :bg-opa 0}}]
     :layout {:flow :row :main-place :space-between
              :cross-place :center :track-place :center}
     :flags-clear [:scrollable]
     :children [(readout-label elapsed :left)
                (readout-label total :right)]}))

(defn scrubber-rich
  "A configurable media scrubber. Required keys are the plain `scrubber`'s;
   every other key adds one part, so the same lego spans a bare position bar
   and a full transport player:

     :ticks    {:total N :major-every M}  — a tick ruler under the track
     :labels   [\"0:00\" \"1:00\" …]          — MAJOR-tick texts (needs :ticks)
     :readout  {:elapsed \"1:12\" :total \"3:40\"} — elapsed/total timecodes
     :transport [:prev :play-pause :stop :next] — a grouped button row
     :transport-event-name \"tp\"           — required with :transport
     :playing? true|false                  — required with :play-pause

   Parts stack in a column: transport, track, ticks, readout. WITH a transport
   row the readout is not a row of its own — it FLANKS the buttons, which is
   what that row's otherwise-empty shoulders are for; the standalone readout
   strip is what a readout-without-transport falls back to. The embedded
   `scrubber` keeps its own hit-halo, so the track's tap target is unchanged
   by anything stacked around it."
  [{:keys [width height ticks labels readout transport transport-event-name
           playing?]
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
    (let [unknown (remove transport-keys transport)]
      (when (seq unknown)
        (throw (ex-info "scrubber-rich: unknown :transport keys"
                        {:unknown (vec unknown)
                         :allowed (vec (sort transport-keys))}))))
    ;; :play-pause draws one of two glyphs, so leaving the state implicit would
    ;; silently pick one. Demand it instead.
    (when (and (some #{:play-pause} transport) (not (boolean? playing?)))
      (throw (ex-info "scrubber-rich: :play-pause needs an explicit :playing? boolean"
                      {:playing? playing?})))
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
        ;; a flanked readout costs no vertical strip of its own
        standalone-readout? (and readout (not transport))
        r-h (if standalone-readout? (long readout-h) 0)
        y-base t-h
        y-ticks (+ t-h base-h)
        y-readout (+ t-h base-h k-h)
        wrapper-w (+ (long width) (* 2 halo))]
    ;; The vertical stack is positioned ABSOLUTELY, deliberately not with a
    ;; column flex, so the arithmetic here IS the layout in every theme family.
    ;; The original reason was that the inter-child gap came from the THEME with
    ;; no way to author it; that is no longer true (`:pad-gap` now carries the
    ;; wire's PROP_PAD_GAP), so the absolute placement is a CHOICE. It is kept
    ;; because these :y values are already the contract the goldens and the
    ;; interaction geometry pin, and because absolute placement needs no
    ;; agreement with a gap at all — the dock stack, which does use flex, has
    ;; to keep `stack-h` and `:pad-gap` in step by hand.
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
                 standalone-readout?
                 (conj (readout-row (assoc opts :x halo :y y-readout))))}))

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
   ;; The stage-name label's width inside the 272px caption row.
   ;;
   ;; IT USED TO BE 52 AND A 7-CHARACTER NAME WRAPPED MID-WORD ("Sharpe/n").
   ;; That was recorded here as wrapping BY DESIGN, on the evidence that 76
   ;; and 58 had both been tried and both overflowed the row. The evidence was
   ;; real and the conclusion was wrong: every attempt had spent the row's
   ;; slack the same way, on FOUR INTER-CHILD GAPS AT :flex-gap. The row holds
   ;; 64 (switch) + 3*30 (icons) = 154px of fixed chrome in a 266px content
   ;; box (272 less the 3px space a side: :pad-all 2 plus :border-w 1), so the
   ;; gap is the only free variable, and :caption-gap below buys 16px of it back
   ;; for the label at no cost to anything else.
   ;; 68px fits a 7-char name at the 12px face with margin, so the wrap is
   ;; gone rather than centred. Consumers with longer names still size their
   ;; own dock — but they now inherit a row that does not wrap by default.
   :caption-label-w 68
   ;; The CAPTION row's inter-child gap, tighter than :flex-gap on purpose:
   ;; the 16px difference is exactly what :caption-label-w spends. Set
   ;; EXPLICITLY on the row (never inherited) for the reason `icon-button`
   ;; carries — an inherited gap makes the label width theme-dependent, and
   ;; the wrap would come back on any theme whose gap is wider.
   :caption-gap 10
   ;; Small caption face: it fits more characters per px than the 16px
   ;; default, which is what lets a 7-char name fit :caption-label-w at all.
   :caption-font "b612mono_bold_12"
   :body-h 46
   :card-collapsed-h 64
   ;; :card-expanded-h is the ONE-body-row height, kept as the pin the proof
   ;; battery already knows. `card-h` below derives every other row count from
   ;; it rather than adding a constant per shape.
   :card-expanded-h 118
   :icon-btn 30
   :rail-w 64
   :rail-btn-w 36
   :rail-btn-h 34
   :badge-w 34
   :badge-h 26
   :flex-gap 14
   ;; :panel-pad IS THE SPACE, NOT THE PAD — the whole inset from the box edge
   ;; to the content box, which for LVGL is pad PLUS border
   ;; (lv_obj_get_style_space_top). `stack-h` needs the space, and the value
   ;; was measured as one: asgard's pad 8 + border 1.
   ;;
   ;; The distinction is worth a paragraph because collapsing it costs exactly
   ;; one pixel per edge and that pixel is a FINDING. Authoring `:pad-all 9`
   ;; makes the space 10, the stack's children then miss the content box by 1
   ;; at each end, and the rail dumps :clipped on its first button, :clipped on
   ;; its badge and :scrollable_overflow on itself — measured, on family 0,
   ;; where the rail had been clean.
   :panel-pad 9
   ;; Authored EXPLICITLY on every container the lego sizes, for the same
   ;; reason as the pad and the gap: the border is part of the space, so an
   ;; inherited one makes every derived height theme-dependent. 1 is asgard's,
   ;; so family 0 geometry is unchanged and the other families are brought into
   ;; line with it rather than the reverse.
   :border-w 1
   :dropdown-h 50})

(defn- card-h
  "Height of a stage card holding `n` body rows.

   ANCHORED on the two committed constants rather than rebuilt from parts:
   n=0 is :card-collapsed-h and n=1 is :card-expanded-h exactly, so adding
   multi-row support cannot perturb the single-row cards the goldens already
   pin. Each row beyond the first costs :body-h PLUS :flex-gap — measured:
   deriving the increment as (expanded - collapsed) omitted that gap and
   tripped :overflow + :clipped on the two-row card. The gap is now AUTHORED
   on the card (`stage-card` sets :pad-gap), so this arithmetic is exact
   rather than an estimate of the theme's; it WAS the theme's when that
   measurement was taken, which is what made an omitted gap a real defect."
  [n]
  (let [{:keys [card-collapsed-h card-expanded-h body-h flex-gap]} dock-chrome
        n (long n)]
    (if (zero? n)
      card-collapsed-h
      (+ card-expanded-h (* (dec n) (+ body-h flex-gap))))))

(def ^:private dock-keys #{:folded? :badge :stages})

(def ^:private stage-keys #{:id :label :enabled? :collapsed? :body-nodes})

(defn- validate-dock!
  [{:keys [badge stages] :as opts}]
  (assert-closed "dock-panel" dock-keys opts)
  (require-int! "dock-panel" :badge badge)
  (when-not (vector? stages)
    (throw (ex-info "dock-panel: :stages must be a vector" {:stages stages})))
  (doseq [s stages]
    (assert-closed "dock-panel stage" stage-keys s)
    (require-ne-string! "dock-panel stage" :id (:id s))
    (require-ne-string! "dock-panel stage" :label (:label s)))
  (let [ids (mapv :id stages)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "dock-panel: stage :id values must be distinct" {:ids ids})))))

(defn- icon-button
  "A 30x30 symbol button; `event` (optional) is the authored event map.

   :pad-all 0 AND AN EXPLICIT CENTRING LAYOUT, because 30px is SMALLER THAN
   SOME THEMES' OWN BUTTON PADDING and the failure is silent under the one
   theme we look at. Left to inherit, the content box is 30 minus twice the
   theme's pad — comfortably positive under asgard, NEGATIVE under stock — and
   a degenerate content box puts the glyph label outside its own button
   (measured: the label at x 118..133 inside a button spanning 94..123, which
   dumps :overflow on the button and :clipped on the label under families 1
   and 2 while family 0 stayed clean). Sizing the box and inheriting the pad
   is what makes a lego theme-dependent; the box is ours, so the pad is too.
   `badge-node` carries the same argument for the same reason."
  [glyph event]
  (cond-> {:type :WIDGET_BUTTON
           :props {:w (:icon-btn dock-chrome)
                   :h (:icon-btn dock-chrome)
                   :pad-all 0}
           :layout {:flow :row :main-place :center
                    :cross-place :center :track-place :center}
           :children [{:type :WIDGET_LABEL :text glyph}]}
    event (assoc :event event)))

(defn- badge-node
  "The staged-count badge: a pill obj with the count label.

   The label is CENTERED on both axes. Without a layout it lands at the content
   box's top-left, which on a 34x26 pill with a 1px border and 2px pad puts the
   digit hard against the border's inner curve — it reads as touching."
  [badge]
  {:type :WIDGET_OBJ
   :layout {:flow :row :main-place :center
            :cross-place :center :track-place :center}
   :props {:w (:badge-w dock-chrome)
           :h (:badge-h dock-chrome)
           :radius 13
           :border-width 1
           :pad-all 2}
   :flags-clear [:scrollable :clickable]
   :children [{:type :WIDGET_LABEL :text (str badge)}]})

(defn- stage-caption
  "One stage's caption row: enable switch + label + reorder/delete
   buttons. Event identities: `<stage-id>-toggle/-up/-down/-delete`, each
   with int_value = the stage's index (the switch fires on VALUE_CHANGED;
   the host flips its own model — the widget value would clobber the index
   in the single-value envelope, so it is deliberately NOT included)."
  [{:keys [id label enabled?]} idx]
  {:type :WIDGET_OBJ
   :props {:w (:row-w dock-chrome)
           :h (:caption-h dock-chrome)
           :pad-all 2
           :pad-gap (:caption-gap dock-chrome)
           :border-width (:border-w dock-chrome)}
   ;; BOTH cross knobs, and they are not the same knob (see `centering-note`):
   ;; :cross-place centres the shorter children against the tall switch;
   ;; :track-place is what centres that whole band inside the 48px row.
   :layout {:flow :row :cross-place :center :track-place :center}
   :flags-clear [:scrollable :clickable]
   ;; WIDGET_SWITCH, not WIDGET_CHECKBOX: a checkbox with no authored text
   ;; renders the stock default "Check box" label (the renderer skips empty
   ;; text, so the wire cannot express an icon-only checkbox).
   :children [{:type :WIDGET_SWITCH
               :props {:switch_props {:checked (boolean enabled?)}}
               :event {:name (str id "-toggle") :trigger :value-changed :int-value idx}}
              {:type :WIDGET_LABEL
               :text label
               :props {:text-font (:caption-font dock-chrome)
                       :w (:caption-label-w dock-chrome)}}
              (icon-button sym-up {:name (str id "-up") :int-value idx})
              (icon-button sym-down {:name (str id "-down") :int-value idx})
              (icon-button sym-close {:name (str id "-delete") :int-value idx})]})

(defn- body-rows
  "Normalise `:body-nodes` to a VECTOR OF ROWS. A flat vector of nodes is one
   row (the original shape, still valid); a vector whose first element is
   itself a vector is already a list of rows. Detecting on the first element
   keeps both spellings working without a second option key."
  [body-nodes]
  (cond
    (empty? body-nodes) []
    (vector? (first body-nodes)) (mapv vec body-nodes)
    :else [(vec body-nodes)]))

(defn- body-row
  "One body row inside a stage card. `disabled?` threads the stage's
   `:enabled?` state onto every row node (states-bits DISABLED, OR-ed over
   any authored bits), so a disabled stage's widgets render their theme
   disabled arms — slider dim, and the label dim via the theme's
   label-disabled arm."
  [nodes disabled?]
  {:type :WIDGET_OBJ
   :props {:w (:row-w dock-chrome)
           :h (:body-h dock-chrome)
           :pad-all 2
           :pad-gap (:flex-gap dock-chrome)
           :border-width (:border-w dock-chrome)}
   ;; :track-place centres the content BAND vertically (the knob :cross-place
   ;; cannot reach — see `centering-note`); :main-place centres it
   ;; horizontally, because a row narrower than its box otherwise pools every
   ;; spare pixel on the right and reads as left-aligned in a centred card.
   :layout {:flow :row :main-place :center
            :cross-place :center :track-place :center}
   :flags-clear [:scrollable :clickable]
   :children (if disabled?
               (mapv #(update % :states-bits (fnil bit-or 0) lv-state-disabled) nodes)
               nodes)})

(defn- stage-card
  "One stage card: caption row, plus zero or more BODY ROWS unless collapsed.

   `:body-nodes` is either a flat vector of nodes (one row) or a vector of
   vectors (one row each) — see `body-rows`. The card's height is DERIVED from
   the row count, so a two-row stage is not silently clipped by a fixed
   `:card-expanded-h`; a collapsed stage keeps its nodes in the caller's data
   and renders caption-only."
  [{:keys [collapsed? body-nodes enabled?] :as stage} idx]
  (let [rows (if collapsed? [] (body-rows body-nodes))
        disabled? (not enabled?)]
    {:type :WIDGET_OBJ
     :props {:w (:card-w dock-chrome)
             :h (card-h (count rows))
             :pad-all 4
             ;; `card-h` derives the height from :flex-gap, so the gap it
             ;; assumes is set here rather than inherited — otherwise the
             ;; derivation is correct only under the theme it was measured on.
             ;; Same for the border: :card-expanded-h 118 is exactly
             ;; 48 + 14 + 46 + 2*(4+1), so the 1 is part of the arithmetic.
             :pad-gap (:flex-gap dock-chrome)
             :border-width (:border-w dock-chrome)}
     ;; main-place CENTER: the card box is a fixed height from dock-chrome, so
     ;; whatever it holds is shorter than the box — flex's default START pins
     ;; that content to the top edge and pools all the slack underneath, which
     ;; reads as "the entry is stuck to the top of its container".
     :layout {:flow :column :main-place :center
              :cross-place :center :track-place :center}
     :flags-clear [:scrollable :clickable]
     :children (into [(stage-caption stage idx)]
                     (map #(body-row % disabled?))
                     rows)}))

(defn- dock-header
  "The panel header: fold toggle (`dock-fold`) + title + badge."
  [badge]
  {:type :WIDGET_OBJ
   :props {:w (:card-w dock-chrome)
           :h (:header-h dock-chrome)
           :pad-all 2
           :pad-gap (:flex-gap dock-chrome)
           :border-width (:border-w dock-chrome)}
   ;; a 30px icon, a text label and a 26px badge in a 46px row: :cross-place
   ;; evens the three heights against each other, :track-place drops the
   ;; resulting band into the middle of the row, :main-place centres it
   ;; horizontally. The title carries NO explicit width: its only purpose was
   ;; to shove the badge toward the right edge, which centring makes moot —
   ;; and a hardcoded 130 was dead space the moment the band was centred.
   :layout {:flow :row :main-place :center
            :cross-place :center :track-place :center}
   :flags-clear [:scrollable :clickable]
   :children [(icon-button sym-list {:name "dock-fold"})
              {:type :WIDGET_LABEL :text "STAGES"} (badge-node badge)]})

(defn- stack-h
  "Height of a column-flex stack: children + inter-child gaps + pads.

   EXACT rather than an estimate, but only because both terms it reads are
   now SET on the stack it measures (`stack-props` below) instead of being
   the theme's. Inherited, :flex-gap and :panel-pad were an assumption about
   asgard: under families 1 and 2 the real gap and pad differ, the computed
   height came out short, and the panel's own content overflowed it — which
   is a SCROLLING panel with two scrollbars, dumped as :scrollable_overflow
   and invisible to a lane that only ever judged family 0."
  [child-hs]
  (let [{:keys [flex-gap panel-pad]} dock-chrome]
    (+ (reduce + 0 child-hs) (* flex-gap (dec (count child-hs))) (* 2 panel-pad))))

(defn- stack-props
  "The space + gap `stack-h` measures, as PROPS on the stack rather than as an
   assumption about the theme. One home so the arithmetic and the authored
   value cannot drift: a `stack-h` caller that forgot these is back to
   measuring a box whose real gap it does not know.

   :pad-all is :panel-pad MINUS the border, because :panel-pad is the SPACE
   and LVGL adds the border to the pad to get there — see the constant's own
   note for the pixel this arithmetic buys."
  []
  {:pad-all (- (:panel-pad dock-chrome) (:border-w dock-chrome))
   :pad-gap (:flex-gap dock-chrome)
   :border-width (:border-w dock-chrome)})

(defn- expanded-panel
  [{:keys [badge stages]}]
  (let [cards (vec (map-indexed (fn [i s] (stage-card s i)) stages))
        heights (into [(:header-h dock-chrome)]
                      (conj (mapv #(get-in % [:props :h]) cards)
                            (:dropdown-h dock-chrome)))]
    {:type :WIDGET_OBJ
     ;; LAYOUT BOX, not a control. LVGL makes every lv_obj CLICKABLE by default
     ;; (`obj->flags = LV_OBJ_FLAG_CLICKABLE`, lv_obj.c), so a container sits in
     ;; the pointer path unless something takes it out — that DEFAULT, not any
     ;; authored intent, is why a disabled stage's widgets find an enabled
     ;; hit-testable ancestor here and `devcards.deadzone` reports
     ;; :disabled-covers-ancestor. Every dock event belongs to a LEAF (the
     ;; caption switch, the icon buttons, the fold toggle), never to a box, so
     ;; clearing it denies no press anyone was listening for. Resolving this by
     ;; construction is what the standard requires; a per-card exemption is the
     ;; ratchet it refuses.
     :flags-clear [:clickable]
     :props (assoc (stack-props)
                   :w (:panel-w dock-chrome)
                   :h (stack-h heights))
     ;; A column's CROSS axis is horizontal, and it defaults to START: the
     ;; 288px cards inside a 320px panel would sit hard against the left edge
     ;; with every pixel of slack pooled on the right. main-place CENTER for
     ;; the same reason vertically — and it stays CENTER now that `stack-h` is
     ;; exact, because centring is what keeps a rounding pixel from pooling as
     ;; a visible gap at one edge.
     :layout {:flow :column :main-place :center
              :cross-place :center :track-place :center}
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
     ;; Same reasoning as `expanded-panel`: a layout box, and every rail event
     ;; belongs to a leaf button (`dock-fold`, the per-stage buttons).
     :flags-clear [:clickable]
     :props (assoc (stack-props)
                   :w (:rail-w dock-chrome)
                   :h (stack-h heights))
     ;; same as the expanded panel: the rail mixes a 30px icon button, 36px
     ;; letter buttons and a 34px badge, so cross-START would align their LEFT
     ;; edges and let the differing widths ragged-edge on the right.
     :layout {:flow :column :main-place :center
              :cross-place :center :track-place :center}
     :children (-> [(icon-button sym-list {:name "dock-fold"})]
                   (into (for [s stages]
                           (cond-> {:type :WIDGET_BUTTON
                                    ;; :pad-all 0 + explicit centring for the
                                    ;; reason `icon-button` carries: a 36x34
                                    ;; box that inherits the theme's button pad
                                    ;; is theme-dependent, and the letter
                                    ;; escaped it under families 1 and 2.
                                    :props {:w (:rail-btn-w dock-chrome)
                                            :h (:rail-btn-h dock-chrome)
                                            :pad-all 0}
                                    :layout {:flow :row :main-place :center
                                             :cross-place :center
                                             :track-place :center}
                                    :children [{:type :WIDGET_LABEL
                                                :text (str/upper-case
                                                       (subs (:label s) 0 1))}]}
                             ;; A disabled stage's letter button dims like its
                             ;; expanded rows — the rail has no switch, so the
                             ;; dim is the ONLY disabled cue in the folded state.
                             (not (:enabled? s))
                             (assoc :states-bits lv-state-disabled))))
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