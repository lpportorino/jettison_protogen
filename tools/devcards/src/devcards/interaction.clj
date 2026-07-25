(ns devcards.interaction
  "The GraalWasm interaction lane over the composition corpus — proves
   the public legos' HOST-EVENT contracts (the pixel/golden lanes own
   their pixels):

   - scrubber geometry + palette: the dump-tree slider/bar coords equal
     the track rect DERIVED from the corpus placement +
     devcards.legos/scrubber-halo (the halo placement arithmetic is the
     lego's public contract, so the runner re-derives it rather than
     trusting a copied constant), and the authored palette renders at the
     track's center row (samples parsed from
     devcards.legos/scrubber-palette — one home, no copied constants).
   - press-seek identity: the seek envelope arrives immediately after
     DOWN with the stock-mapped value; release adds NO duplicate.
   - drag stream: the press prepends exactly one immediate value to the
     stock MOVE stream.
   - ext-click envelope THROUGH the lego: taps up to scrubber-halo px
     below the track's bottom edge hit (the renderer's seek_on_press
     LV_DPX widening, reachable because the wrapper's own box extends to
     the halo boundary); taps beyond it miss. Fresh host per dy — a
     repeat tap at an unchanged value fires no VALUE_CHANGED and would
     fake a miss.
   - dock event identities: `<stage-id>-up`/`-delete`/`-toggle`
     (int_value = stage index) + `dock-fold`, each tap emitting exactly
     one envelope.

   Everything is findings-shaped ({:gate :interaction ...}; empty =
   green) so the runner folds it into the one corpus verdict. The
   wasmtime engine mirror of this lane is
   renderer/wasm_harness/tests/composition_interaction.rs — the same
   card bytes and taps, plus the cross-engine framebuffer byte-compare."
  (:require [devcards.fixtures :as fixtures]
            [devcards.legos :as legos]
            [devcards.pointer :as pointer]
            [devcards.probe :as probe]))

(set! *warn-on-reflection* true)

(defn- render!
  "Render `pb` dark at bp 0 on host `h`, drained (probe/render-drained!) —
   the lane's one render protocol, named once."
  ^bytes [h ^bytes pb]
  (probe/render-drained! h pb {:bp 0 :dark 1}))

;; ── scrubber ────────────────────────────────────────────────────────────
(defn- scrubber-track
  "The scrubber card's TRACK rect (canvas px): the corpus places the
   hit-halo WRAPPER, so the track sits at placement + scrubber-halo,
   sized by the lego opts."
  [{:keys [opts placement]}]
  {:x (+ (long (:x placement)) (long legos/scrubber-halo))
   :y (+ (long (:y placement)) (long legos/scrubber-halo))
   :w (long (:width opts))
   :h (long (:height opts))})

(defn- track-px
  "px point at `frac` of the track, `dy` px below its vertical center."
  [{:keys [x y w h]} frac dy]
  [(long (+ x (* (double frac) (long w)))) (+ (long y) (quot (long h) 2) (long dy))])

(defn- seek-value
  "The stock-mapped slider value for a tap at `frac` of the track."
  ^long [{:keys [opts]} frac]
  (Math/round (+ (long (:min opts))
                 (* (double frac) (- (long (:max opts)) (long (:min opts)))))))

(defn- scrubber-geometry-findings
  "Track coords (the halo placement arithmetic), bar≡slider overlay, and
   the authored palette at the track's center row. The sample fractions
   pin THIS corpus card's bands (:value 40 → played sampled at 30%,
   :buffered 85 → buffered at 55%, unplayed at 95%)."
  [boot! card ^bytes pb canvas-w]
  (probe/with-host boot!
    (fn [h]
      (let [fb (render! h pb)
            tree (probe/dump-tree h)
            track (scrubber-track card)
            slider (first (probe/find-type tree "lv_slider"))
            bar (first (probe/find-type tree "lv_bar"))
            expect-coords [(:x track) (:y track) (+ (:x track) (:w track) -1)
                           (+ (:y track) (:h track) -1)]]
        (into []
              (keep identity)
              [(probe/finding (:id card) :track-coords expect-coords (:coords slider))
               (probe/finding (:id card) :bar-overlays-slider (:coords slider) (:coords bar))
               (probe/finding (:id card)
                              :played-sample
                              (probe/hex-rgb (:played legos/scrubber-palette))
                              (probe/px-at fb canvas-w (track-px track 0.30 0)))
               (probe/finding (:id card)
                              :buffered-sample
                              (probe/hex-rgb (:buffered legos/scrubber-palette))
                              (probe/px-at fb canvas-w (track-px track 0.55 0)))
               (probe/finding (:id card)
                              :track-sample
                              (probe/hex-rgb (:track legos/scrubber-palette))
                              (probe/px-at fb canvas-w (track-px track 0.95 0)))])))))

(defn- press-seek-findings
  "Press-seek identity: tap-DOWN at 70% of the track seeks immediately to
   the stock-mapped value; the release adds NO duplicate."
  [boot! card ^bytes pb]
  (let [track (scrubber-track card)
        tag (get-in card [:opts :seek-event-name])
        v (seek-value card 0.70)
        pt (track-px track 0.70 0)]
    (probe/with-host boot!
      (fn [h]
        (render! h pb)
        (pointer/pointer! h :down pt 1000)
        (pointer/settle! h 4 16)
        (let [after-down (mapv :value (probe/events-tagged h tag))]
          (pointer/pointer! h :up pt 1080)
          (pointer/settle! h 4 16)
          (into []
                (keep identity)
                [(probe/finding (:id card) :press-seek-immediate [v] after-down)
                 (probe/finding (:id card)
                                :no-duplicate-at-release
                                [v]
                                (mapv :value (probe/events-tagged h tag)))]))))))

(defn- drag-findings
  "Drag stream: DOWN at 30% then MOVE through 45/55/70% — the press
   prepends exactly one immediate value to the stock MOVE stream."
  [boot! card ^bytes pb]
  (let [track (scrubber-track card)
        tag (get-in card [:opts :seek-event-name])]
    (probe/with-host boot!
      (fn [h]
        (render! h pb)
        (pointer/drag! h
                       (track-px track 0.30 0)
                       [(track-px track 0.45 0) (track-px track 0.55 0)
                        (track-px track 0.70 0)]
                       1000)
        (into []
              (keep identity)
              [(probe/finding (:id card)
                              :drag-stream
                              (mapv #(seek-value card %) [0.30 0.45 0.55 0.70])
                              (mapv :value (probe/events-tagged h tag)))])))))

(defn- ext-click-findings
  "The ext-click envelope through the lego: HIT iff dy <=
   scrubber-halo (the expectation DERIVES from the halo constant, so a
   halo change moves the probe with it). Fresh host per dy (ns
   docstring)."
  [boot! card ^bytes pb]
  (let [track (scrubber-track card)
        tag (get-in card [:opts :seek-event-name])
        y2 (+ (:y track) (:h track) -1)]
    (vec
     (for [dy [2 8 16 23 24 25 26 30]
           :let [expect-hit (<= (long dy) (long legos/scrubber-halo))
                 pt [(long (+ (:x track) (* 0.70 (:w track)))) (+ (long y2) (long dy))]
                 hit (probe/with-host boot!
                       (fn [h]
                         (render! h pb)
                         (pointer/tap! h pt 1000)
                         (pos? (count (probe/events-tagged h tag)))))
                 f (probe/finding (:id card) (keyword (str "ext-click-dy-" dy)) expect-hit hit)]
           :when f]
       f))))

;; ── dock ────────────────────────────────────────────────────────────────
(defn- dock-tap-findings
  "Tap the node `pick`ed from the dock card's dump tree on a fresh host;
   the captured envelopes must be EXACTLY [expected]."
  [boot! card ^bytes pb check pick expected]
  (probe/with-host boot!
    (fn [h]
      (render! h pb)
      (let [tree (probe/dump-tree h)]
        (pointer/tap! h (probe/center (pick tree)) 1000)
        (if-let [f (probe/finding (:id card)
                                  check
                                  [expected]
                                  (mapv #(select-keys % [:tag :value]) (probe/events h)))]
          [f]
          [])))))

(defn- dock-findings
  "The proven identities over the expanded dock card: the last stage's
   ▲/✕ (`<id>-up`/`<id>-delete` @ its index), the first stage's switch
   (`<id>-toggle` @ 0), and the header fold (`dock-fold` @ 0). Button
   indices follow the panel's depth-first order — fold first, then ▲▼✕
   per stage (three each); switches one per stage in stage order."
  [boot! card ^bytes pb]
  (let [stages (get-in card [:opts :stages])
        last-i (dec (count stages))
        last-id (:id (nth stages last-i))
        first-id (:id (first stages))]
    (-> []
        (into (dock-tap-findings boot! card pb
                                 :stage-up-identity
                                 #(nth (probe/find-type % "lv_button") (+ 1 (* 3 last-i)))
                                 {:tag (str last-id "-up") :value last-i}))
        (into (dock-tap-findings boot! card pb
                                 :stage-delete-identity
                                 #(nth (probe/find-type % "lv_button") (+ 3 (* 3 last-i)))
                                 {:tag (str last-id "-delete") :value last-i}))
        (into (dock-tap-findings boot! card pb
                                 :stage-toggle-identity
                                 #(nth (probe/find-type % "lv_switch") 0)
                                 {:tag (str first-id "-toggle") :value 0}))
        (into (dock-tap-findings boot! card pb
                                 :dock-fold-identity
                                 #(nth (probe/find-type % "lv_button") 0)
                                 {:tag "dock-fold" :value 0})))))

;; ── long EventBinding.name (command-id truncation regression) ─────────────
(def ^:private long-command-id
  "A 127-char dotted command-id — the MAXIMUM the EventBinding.name chain can
   carry untruncated (UI_EVENT_NAME_BUF = 128, so 127 chars + the NUL). Widened
   from the old 73-char probe (which only cleared the retired char[64] ceiling)
   to the true buffer boundary, so a one-off narrowing of ANY buffer on the chain
   below the full 128 — not merely below 64 — is caught. A real collect-event
   PREFIX (cmd.Heater.SetAutomaticControlParams…) padded to the 127-char boundary;
   all chars are alnum/./_ so no JSON escaping alters the emitted length."
  "cmd.Heater.SetAutomaticControlParams.collect.channel_0_target_temperature_setpoint_high_alarm_hysteresis_band_millikelvin_calib")

(defn- long-event-name-findings
  "End-to-end guard for the EventBinding.name chain (UI_EVENT_NAME_BUF): a
   127-char command-id (the buffer's max, 128 − NUL) must survive UNTRUNCATED
   from decode → the per-widget
   cache → the host_event serializer to the emitted tag. Builds a one-button
   authored screen whose event name is `long-command-id`, taps it, and asserts
   the captured host_event :tag is the FULL id. RED when any buffer on the chain
   clips it (a short serializer cap shortens the tag; a short decode/cache buffer
   rejects the load) — the probe the three-buffer drift slipped through."
  [boot! canvas]
  (let [pb (fixtures/build-authored-card
            canvas
            {:id "long-event-name"
             :node {:type :WIDGET_BUTTON :x 100 :y 100 :props {:w 240 :h 80}
                    :event {:name long-command-id :trigger :clicked}
                    :children [{:type :WIDGET_LABEL :text "Go"}]}})]
    (probe/with-host boot!
      (fn [h]
        (render! h pb)
        (let [tree (probe/dump-tree h)
              btn (first (probe/find-type tree "lv_button"))
              _ (pointer/tap! h (probe/center btn) 1000)
              tag (:tag (first (probe/events h)))]
          (into [] (keep identity)
                [(probe/finding "long-event-name" :event-tag-untruncated
                                long-command-id tag)
                 (probe/finding "long-event-name" :event-tag-length
                                (count long-command-id) (count (str tag)))]))))))

;; ── entry ───────────────────────────────────────────────────────────────
(defn run-lane
  "Drive the whole interaction lane. `boot!` returns a fresh started
   host; `inventory` is the parsed composition inventory; `built` its
   built entries ({:id :bytes ...}). The drive targets come from the
   corpus itself: the first buffered scrubber composition card and the
   expanded dock card — a corpus without them is a corpus that lost its
   interaction contract, so their absence THROWS rather than passing
   vacuously. Returns a findings vector (empty = green)."
  [boot! inventory built]
  (let [bytes-of (into {} (map (juxt :id :bytes)) built)
        cards (:cards inventory)
        scrubber (or (first (filter #(and (= :scrubber (:lego %))
                                          (= :composition (:expect %))
                                          (some? (get-in % [:opts :buffered])))
                                    cards))
                     (throw (ex-info "no buffered scrubber composition card to drive" {})))
        dock (or (first (filter #(and (= :dock-panel (:lego %))
                                      (not (get-in % [:opts :folded?])))
                                cards))
                 (throw (ex-info "no expanded dock composition card to drive" {})))
        s-pb ^bytes (get bytes-of (:id scrubber))
        d-pb ^bytes (get bytes-of (:id dock))
        canvas-w (long (get-in inventory [:canvas :w]))]
    (-> []
        (into (scrubber-geometry-findings boot! scrubber s-pb canvas-w))
        (into (press-seek-findings boot! scrubber s-pb))
        (into (drag-findings boot! scrubber s-pb))
        (into (ext-click-findings boot! scrubber s-pb))
        (into (dock-findings boot! dock d-pb))
        (into (long-event-name-findings boot! (:canvas inventory))))))
