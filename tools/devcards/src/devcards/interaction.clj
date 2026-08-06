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
   - GESTURE AFFORDANCES: what a mid-drag gesture surface DRAWS from the
     recognizer's own state — the anchor/band/aim set per registered
     GestureKind, the band being the same rect the ROI command carries,
     every way a gesture ends clearing it, and the affordance staying out
     of the pointer hit path. Every one of those is a phase transition, so
     none of it is reachable by rendering a card; the surfaces are built
     here and DRIVEN.

   Everything is findings-shaped ({:gate :interaction ...}; empty =
   green) so the runner folds it into the one corpus verdict. The
   wasmtime engine mirror of this lane is
   renderer/wasm_harness/tests/composition_interaction.rs — the same
   card bytes and taps, plus the cross-engine framebuffer byte-compare."
  (:require [devcards.fixtures :as fixtures]
            [devcards.geometry :as geometry]
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
(defn scrubber-track
  "The scrubber card's TRACK rect (canvas px): the corpus places the
   hit-halo WRAPPER, so the track sits at placement + scrubber-halo,
   sized by the lego opts.

   PUBLIC because the wasmtime mirror suite reads it too, through
   `geometry-declaration` — see that fn for why it may not carry its own
   copy."
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
   carry untruncated (UI_EVENT_NAME_BUF = 128, so 127 chars + the NUL). Sized to
   the BUFFER boundary rather than to any one struct's field, so a one-off
   narrowing of ANY buffer on the chain is caught, not merely a narrowing past
   some smaller landmark. A real collect-event
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

(defn- proxy-content-inert-findings
  "A NON-STATIC host_proxy is the interaction target and its content
   children are INERT — pinned here because nothing else can see it. The
   glass is full-bleed with LV_OBJ_FLAG_PRESS_LOCK, so it takes every
   press inside the proxy and a control underneath never fires. No
   framebuffer assertion can tell the two apart (identical pixels) and no
   event-log assertion can either (the whole symptom is that no event
   fires), so this canary INJECTS A POINTER.

   Both directions are asserted, because only the pair is evidence: in
   STATIC mode the proxy clears its own CLICKABLE and the content child
   MUST fire, and in every interactive mode it must NOT. A one-sided test
   would pass against a renderer that had simply stopped delivering events
   at all — which is exactly the failure it exists to catch."
  [boot! canvas]
  (let [card (fn [mode]
               {:id (str "proxy-content/" (name mode))
                :node {:type :WIDGET_OBJ :props {:w 400 :h 200}
                       :children
                       [{:type :WIDGET_HOST_PROXY :x 10 :y 10
                         :props {:w 200 :h 120
                                 :host_proxy_props {:proxy_id "px" :mode mode
                                                    :handle_size 16}}
                         :children [{:type :WIDGET_BUTTON :x 40 :y 40
                                     :props {:w 80 :h 30}
                                     :event {:name "inside" :trigger :clicked}
                                     :children [{:type :WIDGET_LABEL :text "IN"}]}]}
                        {:type :WIDGET_BUTTON :x 250 :y 40
                         :props {:w 80 :h 30}
                         :event {:name "outside" :trigger :clicked}
                         :children [{:type :WIDGET_LABEL :text "OUT"}]}]}})
        tap-tags (fn [^bytes pb which]
                   (probe/with-host boot!
                     (fn [h]
                       (render! h pb)
                       ;; centres come from the DUMP, never from guessed screen
                       ;; coordinates — a guessed point that misses reports the
                       ;; same empty vector as the defect.
                       (let [btns (vec (probe/find-type (probe/dump-tree h) "lv_button"))]
                         (pointer/tap! h (probe/center (btns (case which :inside 0 :outside 1)))
                                       1000)
                         (mapv :tag (probe/events h))))))]
    (into []
          (keep identity)
          (mapcat
           (fn [mode]
             (let [pb (fixtures/build-authored-card canvas (card mode))
                   id (str "proxy-content/" (name mode))
                   static? (= mode :static)]
               [(probe/finding id
                               (if static? :static-content-is-live
                                   :non-static-content-is-inert)
                               (if static? ["inside"] [])
                               (tap-tags pb :inside))
                ;; the control proves the harness delivers events at all
                (probe/finding id :control-outside-always-fires
                               ["outside"] (tap-tags pb :outside))]))
           [:static :draggable :resizable :alignable]))))

(defn- handle-hit-clearance-findings
  "Corner handles' GROWN hit areas must never overlap each other.

   `lv_obj_set_ext_click_area` grows a handle on every side, so two corner
   handles `gap` apart have their hit areas meet once each grows by gap/2 —
   while their DRAWN boxes are still clear. A fixed grow therefore makes the
   two handles on a short edge fight over a band in the middle, and
   `lv_indev_search_obj`'s reverse walk hands that band to whichever was
   created later; the earlier handle has a strip it cannot be pressed in.

   Driven at the CORPUS's tightest resizable proxy rather than a synthetic
   one, because the defect is a function of the authored size and a
   synthetic card would only prove the arithmetic against itself. Asserts
   the pairwise separation of the four grown areas is >= 0 (no shared
   pixel); the ext SHRINKING to achieve that is correct, and 0 is a
   legitimate value for a box with no room."
  [boot! canvas]
  (let [pb (fixtures/build-authored-card
            canvas
            {:id "handle-hit-clearance"
             :node {:type :WIDGET_OBJ :props {:w 400 :h 200}
                    :children
                    [{:type :WIDGET_HOST_PROXY :x 10 :y 10
                      :props {:w 96 :h 54
                              :host_proxy_props {:proxy_id "px" :mode :resizable
                                                 :handle_size 16 :min_w 96 :min_h 54}}
                      :children []}]}})]
    (probe/with-host boot!
      (fn [h]
        (render! h pb)
        (let [boxes (->> (probe/dump-tree h)
                         probe/node-seq
                         (keep #(when (:click_area %) (:click_area %)))
                         vec)
              worst (when (seq boxes)
                      (apply min (for [[i a] (map-indexed vector boxes)
                                       b (subvec boxes (inc i))]
                                   (geometry/separation a b))))]
          (into []
                (keep identity)
                [;; a proxy with no grown handles at all would vacuously pass
                 ;; the clearance check, so assert the drive target is real
                 (probe/finding "handle-hit-clearance" :four-handles-grown
                                4 (count boxes))
                 (when (and worst (neg? worst))
                   {:gate :interaction :card "handle-hit-clearance"
                    :check :grown-handles-share-no-pixel
                    :expected "separation >= 0"
                    :actual (str "separation " worst " between two grown handle hit areas")})]))))))

;; ── gesture affordances ─────────────────────────────────────────────────
;;
;; The recognizer's feedback: an ANCHOR at the retained down point plus ONE of
;; a rubber-band BAND (a surface whose completed drag becomes an ROI rect) or an
;; AIM line (a surface whose drag becomes a continuous slew). Every state here
;; is a PHASE TRANSITION, so none of it is reachable by rendering a card — the
;; whole contract only exists while a pointer is mid-drag, which is why it lives
;; in this lane and not in the golden corpus.
;;
;; The surfaces are built here rather than discovered from the composition
;; corpus because a gesture surface must register a GestureSpec, and the corpus
;; carries none: an ROI card and a slew card differ ONLY in the registered kind,
;; and pinning that difference is most of what these findings assert.

(def ^:private roi-slots
  "An ROI CmdSpec's four NDC slots over a 32-byte zero template. Read straight
   back out of the captured host_command bytes — nothing here decodes a command,
   because there is no command: the template is zeros and the slots are the
   renderer's own output (devcards.fixtures § Gesture specs)."
  [{:offset 0 :width 8 :kind :ndc-x}
   {:offset 8 :width 8 :kind :ndc-y}
   {:offset 16 :width 8 :kind :ndc-x2}
   {:offset 24 :width 8 :kind :ndc-y2}])

(defn- gesture-surface-card
  "A full-canvas STATIC host_proxy carrying `gestures` (possibly none). STATIC
   is what clears the proxy's own CLICKABLE, so a point over it resolves to no
   LVGL widget and the pointer is routed to the recognizer — the video
   gesture-surface shape. It is the card's ROOT node: an intermediate container
   would be clickable and would swallow the press before the FSM ever saw it."
  [id canvas gestures]
  {:id id
   :node {:type :WIDGET_HOST_PROXY :x 0 :y 0
          :props {:w (:w canvas) :h (:h canvas)
                  :host_proxy_props {:proxy_id "gs" :mode :static}}
          :gestures gestures}})

(defn- gesture-cards
  "The three surfaces the affordance contract is driven through: an ROI one, a
   slew one, and one with NO gesture spec at all — the last is the control that
   makes 'nothing is drawn' mean something."
  [canvas]
  {:roi (gesture-surface-card
         "gesture-affordance/roi" canvas
         [{:kind :GESTURE_KIND_ROI
           :cmd {:command-id "devcards.gesture.roi" :template-zeros 32
                 ;; A PROBE, not a device command: the template is zeros and
                 ;; nothing decodes it. The plane is still declared because the
                 ;; renderer refuses a y-patching spec that states none, and a
                 ;; card is bytes through the same loader as a screen. DOWN is
                 ;; the plane a real ROI rectangle is read in, so the card
                 ;; exercises the flipping path rather than the identity one.
                 :ndc-y-sense :NDC_Y_SENSE_DOWN
                 :patches roi-slots}}])
   :slew (gesture-surface-card
          "gesture-affordance/slew" canvas
          [{:kind :GESTURE_KIND_PAN_MOVE
            :cmd {:command-id "devcards.gesture.slew" :template-zeros 16
                  ;; A slew is the rotary pointer plane, so this probe declares
                  ;; UP — the two cards then differ in their plane as well as
                  ;; their kind, which is what keeps a green here from being
                  ;; green over one sense only.
                  :ndc-y-sense :NDC_Y_SENSE_UP
                  :patches (subvec roi-slots 0 2)}}])
   :bare (gesture-surface-card "gesture-affordance/no-spec" canvas [])})

(defn- affordance-nodes
  "Every node the dump declares as a gesture affordance, keyed by its part."
  [tree]
  (into {}
        (keep (fn [n] (when-let [p (:gesture_part n)] [p n])))
        (probe/node-seq tree)))

(defn- emitted-doubles
  "The little-endian doubles packed into a captured host_command payload."
  [^bytes payload]
  (let [bb (doto (java.nio.ByteBuffer/wrap payload)
             (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (vec (repeatedly (quot (alength payload) 8) #(.getDouble bb)))))

(defn- drag-to!
  "DOWN at `from` then MOVE to `to`, settling after each — a committed drag
   (the move crosses the recognizer's movePx threshold) left MID-GESTURE, with
   no release. Returns the host."
  [h from to]
  (pointer/pointer! h :down from 1000)
  (pointer/settle! h 3 16)
  (pointer/pointer! h :move to 1050)
  (pointer/settle! h 3 16)
  h)

(defn- drawn-states-findings
  "What each surface draws MID-DRAG, and what it draws when idle. The band/aim
   fork is the whole point: the same drag over two surfaces that differ only in
   the registered GestureKind must draw two different shapes, and the surface
   with no spec at all must draw nothing — feedback for a gesture that emits
   nothing would be a lie."
  [boot! pb-of from to]
  (let [parts (fn [id]
                (probe/with-host boot!
                  (fn [h]
                    (render! h (pb-of id))
                    (let [idle (set (keys (affordance-nodes (probe/dump-tree h))))]
                      (drag-to! h from to)
                      [idle (set (keys (affordance-nodes (probe/dump-tree h))))]))))
        [roi-idle roi-drag] (parts :roi)
        [slew-idle slew-drag] (parts :slew)
        [bare-idle bare-drag] (parts :bare)]
    (into []
          (keep identity)
          [(probe/finding "gesture-affordance/roi" :idle-draws-nothing #{} roi-idle)
           (probe/finding "gesture-affordance/slew" :idle-draws-nothing #{} slew-idle)
           (probe/finding "gesture-affordance/no-spec" :idle-draws-nothing #{} bare-idle)
           (probe/finding "gesture-affordance/roi" :drag-draws-anchor-and-band
                          #{"anchor" "band"} roi-drag)
           (probe/finding "gesture-affordance/slew" :drag-draws-anchor-and-aim
                          #{"anchor" "aim"} slew-drag)
           ;; the control: no registered gesture, so no feedback at all
           (probe/finding "gesture-affordance/no-spec" :drag-draws-nothing
                          #{} bare-drag)])))

(defn- geometry-findings
  "The anchor centres on the DOWN pixel, and the band spans exactly the two drag
   corners. Both expectations are DERIVED from the driven points, never
   authored, so moving the drag moves the expectation with it."
  [boot! pb-of from to]
  (probe/with-host boot!
    (fn [h]
      (render! h (pb-of :roi))
      (drag-to! h from to)
      (let [nodes (affordance-nodes (probe/dump-tree h))
            anchor (:coords (get nodes "anchor"))
            band (:coords (get nodes "band"))
            [fx fy] from
            [tx ty] to]
        (into []
              (keep identity)
              [;; the anchor is a 9px square centred on the down pixel
               (probe/finding "gesture-affordance/roi" :anchor-centres-on-down
                              [(- (long fx) 4) (- (long fy) 4)
                               (+ (long fx) 4) (+ (long fy) 4)]
                              anchor)
               ;; the band's INCLUSIVE box is the two corners, normalized
               (probe/finding "gesture-affordance/roi" :band-spans-the-drag
                              [(min (long fx) (long tx)) (min (long fy) (long ty))
                               (max (long fx) (long tx)) (max (long fy) (long ty))]
                              band)])))))

(defn- rect-identity-findings
  "THE DRAWN RECT AND THE EMITTED RECT ARE ONE RECT.

   Reads the band's px box mid-drag, releases at the point the drag is already
   at, then decodes the four NDC doubles the ROI command carried and maps them
   back through the renderer's own ndc_to_px. The two must be the same box —
   which is the claim worth making: at any instant the band drawn IS the rect a
   release at that instant would send.

   Both directions of the drag are driven, because a band is NORMALIZED (a box
   cannot have negative extent) while the command relays the corners in DRAG
   order. A single down-right drag would pass against an implementation that
   min/max-ordered the emitted corners too, and that is a real divergence: the
   consumer owns the ordering."
  [boot! canvas pb-of pairs]
  (vec
   (for [[from to] pairs
         :let [[fx fy] from
               [tx ty] to
               got (probe/with-host boot!
                     (fn [h]
                       (render! h (pb-of :roi))
                       (drag-to! h from to)
                       (let [band (:coords (get (affordance-nodes (probe/dump-tree h)) "band"))]
                         (pointer/pointer! h :up to 1100)
                         (pointer/settle! h 3 16)
                         (let [cmds (:commands @(:captured h))
                               ndc (when (= 1 (count cmds))
                                     (emitted-doubles (first cmds)))
                               ;; THROUGH THE PLANE THE CARD DECLARED, not the
                               ;; pointer's. The ROI card carries
                               ;; :ndc-y-sense :NDC_Y_SENSE_DOWN, so the emitted
                               ;; y is in the y-DOWN plane and its inverse has no
                               ;; flip. Reading it back with the pointer's
                               ;; converter is the mirror this whole contract
                               ;; exists to catch, and it would show up here as a
                               ;; band that disagrees with its own command.
                               [x1 y1] (when ndc (pointer/ndc-down->px (subvec ndc 0 2) canvas))
                               [x2 y2] (when ndc (pointer/ndc-down->px (subvec ndc 2 4) canvas))]
                           {:band band
                            :emitted (when ndc
                                       [(min (long x1) (long x2)) (min (long y1) (long y2))
                                        (max (long x1) (long x2)) (max (long y1) (long y2))])
                            :drag-order (when ndc [[x1 y1] [x2 y2]])}))))]
         f [(probe/finding "gesture-affordance/roi"
                           (keyword (str "band-is-the-emitted-rect-" fx "-" fy "-to-" tx "-" ty))
                           (:band got)
                           (:emitted got))
            ;; the emitted corners are in DRAG order, down first — the band's
            ;; normalization is the DRAWING's, never the command's
            (probe/finding "gesture-affordance/roi"
                           (keyword (str "emit-keeps-drag-order-" fx "-" fy "-to-" tx "-" ty))
                           [[(long fx) (long fy)] [(long tx) (long ty)]]
                           (:drag-order got))]
         :when f]
     f)))

(defn- teardown-findings
  "Every way a gesture ENDS clears the affordance — including the two that are
   not a release. Driven rather than reasoned about: each arm ends the gesture
   its own way and re-reads the dump.

   The stale-pointer arm is the 'pointer-up that never arrives' case. The GC is
   EVENT-clocked, not render-clocked, so ticking alone can never age it; the
   clock is advanced by a MOVE for an id the table does not know, which
   handle_pointer stamps into the high-water mark BEFORE it looks the id up and
   then reports as a benign orphan. That rc is asserted, because a push that
   silently did nothing would leave the arm passing for the wrong reason."
  [boot! pb-of from to]
  (let [after (fn [end!]
                (probe/with-host boot!
                  (fn [h]
                    (render! h (pb-of :roi))
                    (drag-to! h from to)
                    (let [mid (set (keys (affordance-nodes (probe/dump-tree h))))]
                      (end! h)
                      [mid (set (keys (affordance-nodes (probe/dump-tree h))))]))))
        arms {:release (fn [h] (pointer/pointer! h :up to 1100) (pointer/settle! h 3 16))
              :cancel (fn [h] (pointer/pointer! h :cancel to 1100) (pointer/settle! h 3 16))
              :second-pointer (fn [h]
                                (pointer/pointer! h :down [10 10] 1100 2)
                                (pointer/settle! h 3 16))}
        orphan-rc (atom nil)
        stale (fn [h]
                (reset! orphan-rc
                        ((:push! h) "controls_host_message"
                                    (pointer/pointer-bytes {:phase :move :pointer-id 99
                                                            :ndc-x 0.0 :ndc-y 0.0 :t 9000})))
                (pointer/settle! h 3 16))
        results (into {} (map (fn [[k f]] [k (after f)])) (assoc arms :stale-pointer stale))]
    (into []
          (keep identity)
          (concat
           ;; the non-vacuity guard: every arm must have had something to clear
           (for [[k [mid _]] results]
             (probe/finding "gesture-affordance/roi"
                            (keyword (str "drag-was-live-before-" (name k)))
                            #{"anchor" "band"} mid))
           (for [[k [_ end]] results]
             (probe/finding "gesture-affordance/roi"
                            (keyword (str (name k) "-clears-the-affordance"))
                            #{} end))
           [(probe/finding "gesture-affordance/roi" :stale-clock-push-is-an-orphan
                           2 @orphan-rc)]))))

(defn- hit-path-findings
  "THE AFFORDANCE IS NOT A HIT TARGET, asserted where it can actually be seen.

   `clickable false` in the dump is necessary and not sufficient — it says what
   the flag is, not what the routing does with it. The behavioural arm drives a
   SECOND pointer DOWN at the centre of the drawn band. If the affordance were
   hit-testable, that point would resolve to an LVGL widget, the pointer would
   be claimed by LVGL, the recognizer would never see a second contact, the pan
   would still be live at release and an ROI command WOULD be emitted. Because
   the affordance is transparent to the hit test the contact reaches the FSM
   instead, which silently aborts the pan into a pinch, and the release emits
   NOTHING.

   Three arms, because one proves nothing: a control with no second pointer
   (which must emit — otherwise the whole probe is measuring a broken harness),
   the second pointer ON the affordance, and the second pointer on BARE surface.
   The claim is that the last two agree: over the affordance is
   indistinguishable from over nothing."
  [boot! pb-of from to]
  (let [band (probe/with-host boot!
               (fn [h]
                 (render! h (pb-of :roi))
                 (drag-to! h from to)
                 (:coords (get (affordance-nodes (probe/dump-tree h)) "band"))))
        [bx1 by1 bx2 by2] band
        band-centre [(quot (+ (long bx1) (long bx2)) 2) (quot (+ (long by1) (long by2)) 2)]
        ;; the bare control point: inside the surface, OUTSIDE the band. Derived
        ;; from the band the run actually drew, and then asserted to be outside
        ;; it — a hand-picked constant that drifted INTO the band would turn the
        ;; control into a second copy of the affordance arm and both would agree
        ;; for the wrong reason.
        bare-pt [(quot (long bx1) 2) (quot (long by1) 2)]
        run (fn [second-pt]
              (probe/with-host boot!
                (fn [h]
                  (render! h (pb-of :roi))
                  (drag-to! h from to)
                  (when second-pt
                    (pointer/pointer! h :down second-pt 1100 2)
                    (pointer/settle! h 3 16))
                  (pointer/pointer! h :up to 1150)
                  (pointer/settle! h 3 16)
                  (count (:commands @(:captured h))))))]
    (into []
          (keep identity)
          [(probe/finding "gesture-affordance/roi" :control-point-is-outside-the-band
                          true
                          (not (and (<= (long bx1) (long (first bare-pt)) (long bx2))
                                    (<= (long by1) (long (second bare-pt)) (long by2)))))
           (probe/finding "gesture-affordance/roi" :control-drag-alone-emits 1 (run nil))
           (probe/finding "gesture-affordance/roi" :second-pointer-on-affordance-reaches-fsm
                          0 (run band-centre))
           (probe/finding "gesture-affordance/roi" :second-pointer-on-bare-surface-reaches-fsm
                          0 (run bare-pt))])))

(defn- clickable-flag-findings
  "Every affordance node the dump declares must carry `clickable false` — the
   FLAG half of the hit-path contract, over BOTH shapes (the band and the aim
   are different LVGL classes, and lv_line's constructor is not lv_obj's)."
  [boot! pb-of from to]
  (vec
   (for [id [:roi :slew]
         :let [nodes (probe/with-host boot!
                       (fn [h]
                         (render! h (pb-of id))
                         (drag-to! h from to)
                         (affordance-nodes (probe/dump-tree h))))
               card (str "gesture-affordance/" (name id))]
         f [(probe/finding card :every-affordance-node-is-unclickable
                           (into {} (map (fn [[k _]] [k false])) nodes)
                           (into {} (map (fn [[k n]] [k (:clickable n)])) nodes))
            ;; a card whose affordance set were EMPTY would satisfy the map
            ;; comparison above vacuously
            (probe/finding card :affordance-set-is-not-empty 2 (count nodes))]
         :when f]
     f)))

(defn gesture-affordance-findings
  "The whole gesture-affordance contract, driven. See each helper for its claim."
  [boot! canvas]
  (let [cards (gesture-cards canvas)
        built (update-vals cards #(fixtures/build-authored-card canvas %))
        pb-of (fn ^bytes [k] (get built k))
        ;; a drag whose two corners differ in BOTH axes, so a band that
        ;; collapsed an axis is visible, and well inside the canvas
        from [200 120]
        to [500 380]]
    (-> []
        (into (drawn-states-findings boot! pb-of from to))
        (into (geometry-findings boot! pb-of from to))
        (into (rect-identity-findings boot! canvas pb-of
                                      [[from to] [to from]]))
        (into (teardown-findings boot! pb-of from to))
        (into (hit-path-findings boot! pb-of from to))
        (into (clickable-flag-findings boot! pb-of from to)))))

;; ── entry ───────────────────────────────────────────────────────────────
(defn drive-targets
  "The two composition cards the interaction contract is driven through,
   DISCOVERED from the corpus rather than named: the first buffered
   scrubber composition card and the expanded dock card. A corpus
   without them is a corpus that lost its interaction contract, so their
   absence THROWS rather than passing vacuously.

   ONE home, because BOTH engines drive them — this lane directly, and
   the wasmtime mirror via `geometry-declaration`. Two copies of the
   discovery would let the engines drift onto different cards while both
   stayed green."
  [inventory]
  (let [cards (:cards inventory)]
    {:scrubber (or (first (filter #(and (= :scrubber (:lego %))
                                        (= :composition (:expect %))
                                        (some? (get-in % [:opts :buffered])))
                                  cards))
                   (throw (ex-info "no buffered scrubber composition card to drive" {})))
     :dock (or (first (filter #(and (= :dock-panel (:lego %))
                                    (not (get-in % [:opts :folded?])))
                              cards))
               (throw (ex-info "no expanded dock composition card to drive" {})))}))

(defn geometry-declaration
  "The pointer-contract DECLARATION the WASMTIME mirror suite
   (renderer/wasm_harness/tests/composition_interaction.rs) reads instead
   of carrying its own copies of these numbers.

   WHY THIS EXISTS. The mirror suite used to hard-code the track rect
   (100,200,600×20), the ext-click width (24) and the seek min/max — all
   projections of this corpus and of `legos/scrubber-halo`, which THIS
   lane derives. The two lanes then disagreed about which change is a
   defect: a corpus `:placement` edit is a legitimate authoring change
   that re-mints goldens and leaves this lane green, while the mirror
   suite went red on a stale copy. Worse, it went red LATE — the edit
   matches devcards.yml's `paths:` and not renderer.yml's, so the red
   landed on some later, unrelated `renderer/**` push whose author had
   no part in causing it (MEASURED: shifting `:placement` 20 px left
   devcards green and failed 3 of the mirror's 5 tests).

   So the numbers are DECLARED here, once, and READ there — never
   re-derived from what renders, which would make the mirror assert that
   the renderer does whatever it currently does. What is NOT in here is
   deliberate: the canvas and dpi stay compile-time constants in the
   mirror because `core/run-composition` already throws when the
   inventory canvas differs from the pinned render protocol, so that
   copy cannot go stale silently.

   Emitted as JSON beside the persisted cards by `devcards.core`."
  [inventory]
  (let [{:keys [scrubber dock]} (drive-targets inventory)
        track (scrubber-track scrubber)
        stages (get-in dock [:opts :stages])]
    {:scrubber {:card (:id scrubber)
                :track track
                ;; the seek envelope's stock mapping (see `seek-value`)
                :min (long (get-in scrubber [:opts :min]))
                :max (long (get-in scrubber [:opts :max]))
                :seek_event (get-in scrubber [:opts :seek-event-name])
                ;; the halo IS the reachable envelope: the wrapper's box
                ;; stops LVGL's descent at exactly the slider's widened
                ;; click boundary (see legos/scrubber-halo).
                :ext_click_px (long legos/scrubber-halo)}
     :dock {:card (:id dock)
            ;; the panel's depth-first button order: fold first, then
            ;; ▲▼✕ per stage — the same arithmetic `dock-findings` indexes with.
            :button_count (inc (* 3 (count stages)))}}))

(defn run-lane
  "Drive the whole interaction lane. `boot!` returns a fresh started
   host; `inventory` is the parsed composition inventory; `built` its
   built entries ({:id :bytes ...}). The drive targets come from
   `drive-targets` — the corpus itself, never a hard-coded id. Returns a
   findings vector (empty = green)."
  [boot! inventory built]
  (let [bytes-of (into {} (map (juxt :id :bytes)) built)
        {:keys [scrubber dock]} (drive-targets inventory)
        s-pb ^bytes (get bytes-of (:id scrubber))
        d-pb ^bytes (get bytes-of (:id dock))
        canvas-w (long (get-in inventory [:canvas :w]))]
    (-> []
        (into (scrubber-geometry-findings boot! scrubber s-pb canvas-w))
        (into (press-seek-findings boot! scrubber s-pb))
        (into (drag-findings boot! scrubber s-pb))
        (into (ext-click-findings boot! scrubber s-pb))
        (into (dock-findings boot! dock d-pb))
        (into (long-event-name-findings boot! (:canvas inventory)))
        (into (proxy-content-inert-findings boot! (:canvas inventory)))
        (into (handle-hit-clearance-findings boot! (:canvas inventory)))
        (into (gesture-affordance-findings boot! (:canvas inventory))))))
