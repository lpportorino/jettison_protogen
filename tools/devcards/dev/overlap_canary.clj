(ns overlap-canary
  "REAL-RENDER canary for the OVERLAP rule (`devcards.overlap`).

   WHY IT EXISTS. `devcards.overlap-test` judges hand-written dump-tree
   maps, so every one of its cases asserts the AUTHOR'S MODEL of the dump
   vocabulary. That vocabulary's hazard is precisely which keys `dump_obj`
   OMITS and when: `click_area` only when it differs from coords,
   `clickable` only when the flag is CLEAR, `proxy_root`/`proxy_part`/
   `proxy_owner` only on the objects the renderer built itself. A hand map
   can spell any of those wrong — or spell one the renderer never emits —
   and the suite stays green. These cases build real cards through
   `fixtures/build-authored-card`, render them on the pinned wasm, and judge
   the resulting dump with the LANE'S OWN table and threshold
   (`devcards.lanes`), so the rule is measured against the vocabulary the
   interpreter actually produces.

   WHAT WAS ALREADY COVERED, so this does not duplicate it.
   `dev/dump_contract_probe.clj` (`renderer.mk dump-contracts`) already
   renders an overlap pair whose verdict turns on `descend_gate` — the
   OVERFLOW_VISIBLE ancestor gate. That key is therefore deliberately absent
   here; this file covers the clauses that probe does not reach.

   THE CLAUSES, and the conditionally-emitted key each one turns on:

   - SIBLINGS. Two independently-placed controls sharing pixels fire; the
     same pair moved clear does not. No conditional key — the base case,
     present so the rest are not the only thing proving the rule runs at all.

   - CLICK AREA (`click_area`, emitted only when it differs from coords).
     THREE cards, same neighbour in the same place, DRAWN clear of the
     slider. A slider with `WidgetNode.hit_slop` takes LV_DPX of it and must
     FIRE; a slider asking for nothing emits the key at all and must not;
     and a slider carrying `SliderProps.seek_on_press` and NO hit_slop must
     ALSO not, which is what pins the two as unfused — that flag used to
     carry a 24px widening as a side effect, so neither behaviour could be
     had alone. The verdict therefore turns on a PER-INSTANCE fact and never
     on the class (all three are lv_slider). A rule reading coords is silent
     on all three and so fails the slop half; a rule assuming a per-class
     pad fires on all three and so fails the other two. The slop and plain
     cards are also asserted to render BYTE-IDENTICAL framebuffers, which is
     the whole reason this rule exists: no pixel oracle can tell them apart.

   - LAYOUT GAP (`click_area` again, but read for its ABSENCE). Two rows of a
     generated column, separated by the smallest non-zero spacing token this
     repo publishes — the tightest gap a layout can produce. Measures whether
     a stock interactive widget's reach stays inside its drawn box, so that a
     gap the author honoured is still a gap the POINTER honours. The
     `hit_slop` twin at the identical geometry is the non-vacuity guard: a
     deliberately widened hit box must still fire there, so a silent plain
     twin cannot be a dead check.

   - NESTING. A control inside an interactive container overlaps it by
     construction. The container and the child must not fire against each
     other while both still fire against an independent outside control —
     the specific false gate that is the right colour for the wrong reason.

   - CLICKABLE (`clickable`, emitted only when the flag is CLEAR, so ABSENT
     MEANS CLICKABLE). A STATIC host_proxy has LV_OBJ_FLAG_CLICKABLE removed
     at runtime (renderer.c proxy_apply_mode) so presses fall through to the
     host. Its RESIZABLE twin — same authored tree, same geometry, one enum
     apart — keeps the flag. This is a per-INSTANCE fact no type-keyed table
     can express and no hand map can source, because the flag is set by the
     interpreter and not by the wire.

   - PROXY DECLARATION (`proxy_root` / `proxy_part` / `proxy_owner`, emitted
     only on the objects the renderer builds). Inside the resizable proxy the
     glass and the four corner handles are unrelated SIBLINGS whose reachable
     boxes overlap; `designed-proxy-stack?` must exclude every such pair while
     each of them still fires against the control OUTSIDE the proxy. The
     non-vacuity is structural: the outside control is the only pointer-taking
     node outside the proxy, so an intra-proxy pair is exactly a finding whose
     detail does not name it.

   - DESIGNED-OVERLAY DECLARATION (`designed_overlay`, emitted only where the
     AUTHOR set `WidgetNode.designed_overlay`). The one key here that starts on
     the WIRE rather than in the interpreter, which is exactly why it needs
     this file: a hand map proves the rule's reading of a key, never that the
     chain from an authored node through nanopb and `finalize_widget` to
     `dump_obj` produces it. Three cards — a scrim COVERING a control (silent),
     the identical pair with the one key dropped (fires, which is what makes
     the silence attributable), and a scrim that only PARTLY covers (fires
     while still declaring, which is the containment gate measured on rendered
     rects).

   WHAT THIS DOES NOT COVER. The `:unclassified-type` and `:unmeasurable-node`
   arms — the corpus renderer classifies every class it emits and gives every
   node coords, so neither is reachable from an authored card; both stay
   hand-tested. Thresholds above 0 are data and stay hand-tested. Transforms
   and ADV_HITTEST are the rule's declared blind spots, not clauses.

   Run from tools/devcards in the pinned container:
     clojure -M:bindings:overlap-canary"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.classify :as classify]
            [devcards.fixtures :as fixtures]
            [devcards.geometry :as geometry]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.overlap :as overlap])
  (:import [java.util Arrays]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private gap-px
  "Read from the ARMED lane rather than restated, so a threshold change in
   `devcards.lanes` cannot leave this canary judging a rule the gate no
   longer runs."
  (:overlap/gap-px lanes/overlap-thresholds))

(defn- render!
  "One hermetic render of `pb`: the raw framebuffer and the parsed dump."
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try
      (let [fb (host/render-card! h {:pb pb :bp 0 :dark 1})]
        {:fb fb
         :tree (json/read-str (host/dump-tree! h) :key-fn keyword)})
      (finally (host/close! h)))))

;; ── card construction, all through the public authored-fixture builder ────

(defn- labelled-button
  "WIDGET_BUTTON carries no text of its own — the builder law requires a
   child WIDGET_LABEL."
  [x y w h text]
  {:type :WIDGET_BUTTON
   :x x :y y
   :props {:w w :h h}
   :children [{:type :WIDGET_LABEL :text text}]})

(defn- switch-at
  [x y]
  {:type :WIDGET_SWITCH :x x :y y :props {:w 80 :h 40}})

(defn- slider-at
  "A slider at a fixed place. `slop` rides WidgetNode.hit_slop, the wire's
   only route to a hit box larger than the drawn box; 0 omits the key so the
   node asks for nothing."
  [x y slop]
  (cond-> {:type :WIDGET_SLIDER
           :x x :y y
           :props {:w 120 :h 20
                   :slider_props {:min_value 0 :max_value 100 :value 50}}}
    (pos? (long slop)) (assoc :hit-slop slop)))

(defn- stacked-slider
  "A 120x16 slider, the thin-track shape a form column produces."
  [x y slop]
  (cond-> {:type :WIDGET_SLIDER
           :x x :y y
           :props {:w 120 :h 16
                   :slider_props {:min_value 0 :max_value 100 :value 50}}}
    (pos? (long slop)) (assoc :hit-slop slop)))

(defn- seek-slider-at
  "A slider carrying SliderProps.seek_on_press and NO hit_slop. The prop used
   to fuse press-seek behaviour with a 24px hit widening; this node is what
   proves the two are separable."
  [x y]
  {:type :WIDGET_SLIDER
   :x x :y y
   :props {:w 120 :h 20
           :slider_props {:min_value 0 :max_value 100 :value 50
                          :seek_on_press true}}})

(defn- dropdown-below
  "The next interactive row in the column."
  [x y]
  {:type :WIDGET_DROPDOWN
   :x x :y y
   :props {:w 176 :h 48
           :dropdown_props {:options "Auto\nManual\nOff" :selected 1}}})

(defn- proxy-at
  [x y mode]
  {:type :WIDGET_HOST_PROXY
   :x x :y y
   :props {:w 160 :h 60
           :host_proxy_props {:proxy_id "px" :mode mode}}})

(defn- card
  "Wrap children in a bare, pad-free container and build Screen bytes. The
   harness root that `build-authored-card` adds has CLICKABLE cleared
   (fixtures/scaffolding-flags-clear), so no scaffolding node enters the
   pairing; this inner container does NOT, which is deliberate — it is an
   ancestor of everything below it and so is excluded by `related?`, which
   keeps that exclusion exercised on every card here."
  ^bytes [id children]
  (fixtures/build-authored-card
   canvas
   {:id id
    :node {:type :WIDGET_OBJ
           :bare true
           :props {:w 700 :h 400 :pad-all 0 :border-width 0}
           :children children}}))

;; ── reading the dump ─────────────────────────────────────────────────────

(defn- nodes-of [tree] (invariants/annotate-tree tree))

(defn- findings-of
  "The overlap rule over one dump, with the lane's own table and threshold."
  [card-id tree]
  (overlap/findings {:card-id card-id
                     :nodes (nodes-of tree)
                     :classes lanes/overlap-classes
                     :thresholds {:gap-px gap-px}}))

(defn- node-at
  "The node map at an exact child-index path — a structural address, never a
   type search, so a card that grows a node fails loudly instead of silently
   matching a different one."
  [tree path]
  (or (:node (first (filter #(= path (:path %)) (nodes-of tree))))
      (throw (ex-info "no dump node at that path"
                      {:path path
                       :paths (mapv :path (nodes-of tree))}))))

(defn- box-str
  "How a box appears in a finding's :detail — `geometry/describe` is the
   formatter the rule itself uses, so this decodes participants exactly."
  [box]
  (geometry/describe box))

(defn- names? [finding box] (str/includes? (:detail finding) (box-str box)))

(defn- invariants-of [fs] (set (map :invariant fs)))

;; ── the cases ────────────────────────────────────────────────────────────
;; Each returns [[ok? message] ...]. Every message states the OBSERVED value,
;; so a red says what happened rather than only that something did.

(defn- siblings-checks
  []
  (let [fired-id "canary/overlap-siblings-share"
        clear-id "canary/overlap-siblings-clear"
        fired (:tree (render! (card fired-id
                                    [(labelled-button 100 100 160 60 "under")
                                     (switch-at 200 100)])))
        clear (:tree (render! (card clear-id
                                    [(labelled-button 100 100 160 60 "under")
                                     (switch-at 300 100)])))
        fb-fs (findings-of fired-id fired)
        cl-fs (findings-of clear-id clear)
        fb-btn (:coords (node-at fired [0 0 0]))
        fb-sw (:coords (node-at fired [0 0 1]))
        cl-btn (:coords (node-at clear [0 0 0]))
        cl-sw (:coords (node-at clear [0 0 1]))]
    [[(neg? (long (geometry/separation fb-btn fb-sw)))
      (format "control geometry: the rendered boxes really do share pixels — %s vs %s, separation %d"
              (pr-str fb-btn) (pr-str fb-sw) (geometry/separation fb-btn fb-sw))]
     [(and (= #{:overlap} (invariants-of fb-fs))
           (= 1 (count fb-fs))
           (= "lv_button vs lv_switch" (:node (first fb-fs))))
      (format "two independently-placed controls sharing pixels fire exactly once: %s"
              (pr-str (mapv (juxt :invariant :node) fb-fs)))]
     [(and (seq fb-fs) (names? (first fb-fs) fb-btn) (names? (first fb-fs) fb-sw))
      (format "the finding names BOTH reachable boxes, so a reader is sent to real pixels: %s"
              (pr-str (:detail (first fb-fs))))]
     [(pos? (long (geometry/separation cl-btn cl-sw)))
      (format "control geometry: the twin's boxes really are clear — %s vs %s, separation %d"
              (pr-str cl-btn) (pr-str cl-sw) (geometry/separation cl-btn cl-sw))]
     [(empty? cl-fs)
      (format "the SAME two widget types moved clear report nothing: %s"
              (pr-str (mapv (juxt :invariant :node) cl-fs)))]]))

(defn- click-area-checks
  []
  (let [seek-id "canary/overlap-click-area-slop"
        plain-id "canary/overlap-click-area-plain"
        unfused-id "canary/overlap-click-area-seek-unfused"
        seek (render! (card seek-id [(slider-at 100 100 24) (switch-at 230 100)]))
        plain (render! (card plain-id [(slider-at 100 100 0) (switch-at 230 100)]))
        unfused (render! (card unfused-id
                               [(seek-slider-at 100 100) (switch-at 230 100)]))
        seek-fs (findings-of seek-id (:tree seek))
        plain-fs (findings-of plain-id (:tree plain))
        unfused-fs (findings-of unfused-id (:tree unfused))
        seek-sl (node-at (:tree seek) [0 0 0])
        plain-sl (node-at (:tree plain) [0 0 0])
        unfused-sl (node-at (:tree unfused) [0 0 0])
        seek-sw (node-at (:tree seek) [0 0 1])
        drawn-sep (geometry/separation (:coords seek-sl) (:coords seek-sw))
        hit-sep (geometry/separation (:click_area seek-sl) (:coords seek-sw))]
    [[(= (:coords seek-sl) (:coords plain-sl))
      (format "controlled pair: both sliders DRAW the same box — %s vs %s"
              (pr-str (:coords seek-sl)) (pr-str (:coords plain-sl)))]
     [(and (some? (:click_area seek-sl)) (nil? (:click_area plain-sl)))
      (format "one wire field apart, the two sliders REACH differently: the hit_slop twin emits click_area %s, the plain one emits none (so its hit box is its drawn box)"
              (pr-str (:click_area seek-sl)))]
     ;; THE UNFUSING PIN. seek_on_press once carried the widening as a side
     ;; effect, so a scrubber could not ask for the seek without the
     ;; envelope or the envelope without the seek, and no other widget could
     ;; ask for either. A refusal to widen is the whole assertion.
     [(nil? (:click_area unfused-sl))
      (format "seek_on_press with NO hit_slop widens NOTHING — the flag is behaviour only: click_area %s, coords %s"
              (pr-str (:click_area unfused-sl)) (pr-str (:coords unfused-sl)))]
     [(and (empty? unfused-fs) (= (:coords unfused-sl) (:coords seek-sl)))
      (format "…so at the geometry where the hit_slop twin fires, the press-seek slider is silent: %s"
              (pr-str (mapv (juxt :invariant :node) unfused-fs)))]
     ;; Derived from the dump, never restated from the C constant: a
     ;; restated number agrees with the renderer only until one of them
     ;; moves. What is asserted is the SHAPE ext_click_pad must have —
     ;; one value, grown on all four sides.
     [(let [[cx1 cy1 cx2 cy2] (:coords seek-sl)
            [kx1 ky1 kx2 ky2] (:click_area seek-sl)
            grow [(- cx1 kx1) (- cy1 ky1) (- kx2 cx2) (- ky2 cy2)]]
        (and (apply = grow) (pos? (long (first grow)))))
      (format "…and that widening is one uniform pad on all four sides, as ext_click_pad is: coords %s -> click_area %s"
              (pr-str (:coords seek-sl)) (pr-str (:click_area seek-sl)))]
     [(nil? (:click_area seek-sw))
      (format "the neighbour emits NO click_area, so absence really is the ordinary case here: %s"
              (pr-str (:click_area seek-sw)))]
     [(pos? (long drawn-sep))
      (format "control geometry: the DRAWN boxes are clear, so a coords-only rule is silent — separation %d"
              drawn-sep)]
     [(neg? (long hit-sep))
      (format "but the seek slider's CLICK AREA reaches the neighbour — separation %d"
              hit-sep)]
     [(and (= #{:overlap} (invariants-of seek-fs))
           (= 1 (count seek-fs))
           (= "lv_slider vs lv_switch" (:node (first seek-fs))))
      (format "the rule fires on the widened hit box: %s"
              (pr-str (mapv (juxt :invariant :node) seek-fs)))]
     [(and (seq seek-fs) (names? (first seek-fs) (:click_area seek-sl)))
      (format "and reports the CLICK AREA, not the drawn box — %s"
              (pr-str (:detail (first seek-fs))))]
     [(empty? plain-fs)
      (format "the stock-pad twin, same class and same geometry, reports nothing — so the verdict turns on the EMITTED VALUE, not on the class or on the key's presence: %s"
              (pr-str (mapv (juxt :invariant :node) plain-fs)))]
     [(Arrays/equals ^bytes (:fb seek) ^bytes (:fb plain))
      (format "the two cards render BYTE-IDENTICAL framebuffers (%d bytes) — no pixel oracle can see this hazard, which is why the geometry rule has to"
              (alength ^bytes (:fb seek)))]]))

(def ^:private smallest-spacing-gap
  "The smallest NON-ZERO spacing token this repo publishes, read from the
   manifest rather than restated, so a token change cannot leave this canary
   proving a gap the design system no longer offers. That token is the
   tightest gap a generated column can put between two rows, so it is the
   gap a hit box must not cross."
  (->> (get (json/read-str (slurp "../../output/manifests/design-tokens.json")
                           :key-fn keyword)
            :tokens)
       (keep (fn [[_ v]] (when (= "spacing" (:kind v)) (:dark v))))
       (filter pos?)
       (reduce min)))

(defn- layout-gap-checks
  "THE REGRESSION CASE. A generated column puts a thin slider one spacing
   token above the next interactive row. Nothing in that layout is
   misbehaving — the gap is honoured exactly — so if the slider is reachable
   inside its neighbour, the reach came from the widget and not from the
   author, and the neighbour's top band is dead: `lv_indev_search_obj`
   returns the FIRST hit in reverse child order, so exactly one of the two
   takes a press there.

   The twin is the non-vacuity guard and it is the whole reason this case can
   be trusted: `seek_on_press` is the one wire route to a DELIBERATELY
   widened hit box, and that twin must still FIRE at the identical geometry.
   A silent plain twin therefore means the stock reach was withdrawn, never
   that the rule stopped looking."
  []
  (let [plain-id "canary/overlap-layout-gap-plain"
        seek-id "canary/overlap-layout-gap-seek"
        row-x 27
        slider-y 136
        ;; the next row starts one spacing token below the slider's last pixel
        drop-y (+ slider-y 16 smallest-spacing-gap)
        plain (render! (card plain-id [(stacked-slider row-x slider-y 0)
                                       (dropdown-below row-x drop-y)]))
        seek (render! (card seek-id [(stacked-slider row-x slider-y 24)
                                     (dropdown-below row-x drop-y)]))
        plain-fs (findings-of plain-id (:tree plain))
        seek-fs (findings-of seek-id (:tree seek))
        p-sl (node-at (:tree plain) [0 0 0])
        p-dd (node-at (:tree plain) [0 0 1])
        s-sl (node-at (:tree seek) [0 0 0])
        s-dd (node-at (:tree seek) [0 0 1])
        drawn-sep (geometry/separation (:coords p-sl) (:coords p-dd))
        seek-hit-sep (geometry/separation (or (:click_area s-sl) (:coords s-sl))
                                          (:coords s-dd))]
    [[(= (long drawn-sep) (long smallest-spacing-gap))
      (format "control geometry: the two rows are DRAWN exactly one spacing token apart — separation %d, smallest spacing token %d"
              drawn-sep smallest-spacing-gap)]
     [(nil? (:click_area p-sl))
      (format "a plain slider emits NO click_area, so its hit box IS its drawn box and the drawn gap above is also the REACH gap — click_area %s, coords %s"
              (pr-str (:click_area p-sl)) (pr-str (:coords p-sl)))]
     [(empty? plain-fs)
      (format "the column reports nothing — a layout that honours the smallest spacing token is reachable as authored: %s"
              (pr-str (mapv (juxt :invariant :node) plain-fs)))]
     [(and (some? (:click_area s-sl)) (neg? (long seek-hit-sep)))
      (format "NON-VACUITY: the seek twin DOES emit a widened click_area %s and it crosses the same gap — separation %d"
              (pr-str (:click_area s-sl)) seek-hit-sep)]
     [(and (= #{:overlap} (invariants-of seek-fs))
           (= 1 (count seek-fs))
           (= "lv_slider vs lv_dropdown" (:node (first seek-fs))))
      (format "…and the rule still FIRES on it, so the silence above is a withdrawn reach and not a dead check: %s"
              (pr-str (mapv (juxt :invariant :node) seek-fs)))]
     [(Arrays/equals ^bytes (:fb plain) ^bytes (:fb seek))
      (format "the two columns render BYTE-IDENTICAL framebuffers (%d bytes) — the entire difference is reach, which no pixel oracle can see"
              (alength ^bytes (:fb plain)))]]))

(defn- nesting-checks
  []
  (let [id "canary/overlap-nesting"
        tree (:tree (render! (card id
                                   [{:type :WIDGET_OBJ
                                     :x 100 :y 100
                                     :props {:w 240 :h 120}
                                     :children [(labelled-button 0 0 160 60 "inner")]}
                                    (switch-at 200 110)])))
        fs (findings-of id tree)
        container (:coords (node-at tree [0 0 0]))
        button (:coords (node-at tree [0 0 0 0]))
        switch (:coords (node-at tree [0 0 1]))
        both? (fn [f] (and (names? f container) (names? f button)))]
    [[(every? #(:interactive? (classify/classify lanes/overlap-classes %))
              ["lv_obj" "lv_button"])
      "control: the shipped table really does classify BOTH the container and the child interactive, so the silence below is the exclusion and not an unpaired type"]
     [(neg? (long (geometry/separation container button)))
      (format "control geometry: the child really does overlap its container — %s vs %s, separation %d"
              (pr-str container) (pr-str button) (geometry/separation container button))]
     [(not-any? both? fs)
      (format "no finding pairs the container with its own child — containment is composition, not a hazard: %s"
              (pr-str (mapv :detail fs)))]
     [(and (= #{:overlap} (invariants-of fs)) (= 2 (count fs)))
      (format "and the two INDEPENDENT pairs still fire: %s"
              (pr-str (mapv (juxt :invariant :node) fs)))]
     [(and (some #(and (names? % container) (names? % switch)) fs)
           (some #(and (names? % button) (names? % switch)) fs))
      (format "…named explicitly: container-vs-switch AND child-vs-switch both present, so the exclusion keys on ANCESTRY and not on either node being unpaired: %s"
              (pr-str (mapv :node fs)))]]))

(defn- proxy-checks
  []
  (let [static-id "canary/overlap-proxy-static"
        live-id "canary/overlap-proxy-resizable"
        static (:tree (render! (card static-id
                                     [(labelled-button 100 100 160 60 "under")
                                      (proxy-at 200 100 :static)])))
        live (:tree (render! (card live-id
                                   [(labelled-button 100 100 160 60 "under")
                                    (proxy-at 200 100 :resizable)])))
        s-fs (findings-of static-id static)
        l-fs (findings-of live-id live)
        s-btn (:coords (node-at static [0 0 0]))
        s-box (node-at static [0 0 1])
        l-btn (:coords (node-at live [0 0 0]))
        l-box (node-at live [0 0 1])
        l-glass (node-at live [0 0 1 0])]
    [[(= (:coords s-box) (:coords l-box))
      (format "controlled pair: the proxy box occupies the SAME rendered rect in both modes — %s vs %s"
              (pr-str (:coords s-box)) (pr-str (:coords l-box)))]
     [(neg? (long (geometry/separation s-btn (:coords s-box))))
      (format "control geometry: the outside button really does overlap the static proxy — separation %d"
              (geometry/separation s-btn (:coords s-box)))]
     [(and (= false (:clickable s-box)) (nil? (:clickable l-box)))
      (format "the interpreter DECLARES the difference: static box clickable=%s, resizable box clickable=%s (absent means CLICKABLE)"
              (pr-str (:clickable s-box)) (pr-str (:clickable l-box)))]
     [(empty? s-fs)
      (format "a STATIC proxy is out of the pointer path, so an overlapping control under it is not a hazard: %s"
              (pr-str (mapv (juxt :invariant :node) s-fs)))]
     [(and (= #{:overlap} (invariants-of l-fs)) (<= 3 (count l-fs)))
      (format "the resizable twin — one enum apart, same geometry — fires: %d finding(s) %s"
              (count l-fs) (pr-str (mapv :node l-fs)))]
     [(every? #(names? % l-btn) l-fs)
      (format "EVERY finding names the outside button. It is the only pointer-taking node outside the proxy, so an intra-proxy pair would be a finding without it — the designed-stack exclusion, stated as an absence: %s"
              (pr-str (mapv :detail l-fs)))]
     [(and (some #(names? % (:coords l-box)) l-fs)
           (some #(names? % (:coords l-glass)) l-fs))
      (format "…and it is NOT vacuous: the proxy box %s and its glass %s each appear in a finding, so both are in the pairing and their mutual overlap was excluded rather than never considered"
              (pr-str (:coords l-box)) (pr-str (:coords l-glass)))]
     [(and (= "px" (:proxy_root l-box))
           (= "glass" (:proxy_part l-glass))
           (= "px" (:proxy_owner l-glass)))
      (format "the exclusion reads the renderer's OWN declaration, not paint order: proxy_root=%s, proxy_part=%s, proxy_owner=%s"
              (pr-str (:proxy_root l-box))
              (pr-str (:proxy_part l-glass))
              (pr-str (:proxy_owner l-glass)))]
     ;; The glass and a corner handle are SIBLINGS under the proxy box, so
     ;; `related?` cannot be what silenced them. Both boxes are clipped to
     ;; the box's descent gate first, exactly as the rule does, so this
     ;; control measures the same pixels the verdict was formed from.
     (let [handle (node-at live [0 0 1 1])
           gate (:coords l-box)
           glass-reach (geometry/intersection (:coords l-glass) gate)
           handle-reach (geometry/intersection
                         (or (:click_area handle) (:coords handle)) gate)]
       [(neg? (long (geometry/separation glass-reach handle-reach)))
        (format "control geometry: the glass %s and a corner handle %s — unrelated SIBLINGS, not an ancestor pair — really do share reachable pixels, so their absence above is the proxy clause and not the ancestry one (separation %d)"
                (pr-str glass-reach)
                (pr-str handle-reach)
                (geometry/separation glass-reach handle-reach))])]))

(defn- scrim-at
  "A modal SCRIM: a plain clickable box declared `designed_overlay`. The
   `:designed-overlay` key is authored-lane only, so this node can only be
   built through `build-authored-card` — which is what these cases use."
  [x y w h]
  {:type :WIDGET_OBJ
   :x x :y y
   :designed-overlay true
   :props {:w w :h h}})

(defn- designed-overlay-checks
  "The AUTHORED declaration, end to end through the real interpreter.

   This case exists for the half `devcards.overlap-test` structurally cannot
   reach. That suite judges hand-written dump maps, so it asserts the
   AUTHOR'S MODEL of the key — it would stay green if the renderer emitted a
   different name, emitted it on the wrong object, or never emitted it at
   all. Here the flag travels the whole chain: authored node ->
   `WidgetNode.designed_overlay` on the wire -> nanopb decode ->
   `finalize_widget` -> the renderer's overlay registry -> `dump_obj` -> the
   key the rule reads.

   Three cards, one geometry family. The scrim COVERING the control must be
   silent; the identical pair with the declaration removed must fire (which
   is what makes the silence attributable rather than merely quiet); and a
   scrim that only PARTLY covers the control must fire even though it
   declares — the containment gate, measured on the rendered rects rather
   than on rects this file made up."
  []
  (let [covered-id "canary/overlap-scrim-covers"
        undeclared-id "canary/overlap-scrim-undeclared"
        partial-id "canary/overlap-scrim-partial"
        ;; The button is wholly inside the scrim; the scrim is LATER in child
        ;; order, so it also wins the reverse hit-test walk — the modal shape.
        covered (:tree (render! (card covered-id
                                      [(labelled-button 120 120 160 60 "under")
                                       (scrim-at 100 100 240 120)])))
        ;; Byte-identical geometry with the ONE key dropped.
        undeclared (:tree (render! (card undeclared-id
                                         [(labelled-button 120 120 160 60 "under")
                                          (dissoc (scrim-at 100 100 240 120)
                                                  :designed-overlay)])))
        ;; Same declaration, and the button now runs past the scrim's right
        ;; edge: a press inside the overlap is absorbed, a press outside it
        ;; lands, and the two are indistinguishable in the framebuffer.
        partial (:tree (render! (card partial-id
                                      [(labelled-button 120 120 260 60 "under")
                                       (scrim-at 100 100 200 120)])))
        c-fs (findings-of covered-id covered)
        u-fs (findings-of undeclared-id undeclared)
        p-fs (findings-of partial-id partial)
        c-btn (:coords (node-at covered [0 0 0]))
        c-scrim (node-at covered [0 0 1])
        u-scrim (node-at undeclared [0 0 1])
        p-btn (:coords (node-at partial [0 0 0]))
        p-scrim (node-at partial [0 0 1])]
    [[(true? (:designed_overlay c-scrim))
      (format "the INTERPRETER emits the key, on the declaring object — this is the assertion no hand-written dump map can make: designed_overlay=%s"
              (pr-str (:designed_overlay c-scrim)))]
     [(nil? (:designed_overlay (node-at covered [0 0 0])))
      (format "…and only there: the ordinary control beside it carries no such key, so the emit-only-where-it-applies convention holds and an absent key really does mean 'not declared' (control's key=%s)"
              (pr-str (:designed_overlay (node-at covered [0 0 0]))))]
     [(nil? (:designed_overlay u-scrim))
      (format "the undeclared twin's box carries no key either, so the pair below differs by the DECLARATION and not by anything the renderer did on its own: %s"
              (pr-str (:designed_overlay u-scrim)))]
     [(= (:coords c-scrim) (:coords u-scrim))
      (format "controlled pair: declared and undeclared scrims occupy the SAME rendered rect — %s vs %s"
              (pr-str (:coords c-scrim)) (pr-str (:coords u-scrim)))]
     [(= c-btn (geometry/intersection (:coords c-scrim) c-btn))
      (format "control geometry: the scrim really does CONTAIN the control it covers — button %s inside scrim %s"
              (pr-str c-btn) (pr-str (:coords c-scrim)))]
     [(empty? c-fs)
      (format "a declared overlay wholly covering a control is composition, not a collision: %s"
              (pr-str (mapv (juxt :invariant :node) c-fs)))]
     [(and (= #{:overlap} (invariants-of u-fs))
           (some #(names? % c-btn) u-fs))
      (format "THE NON-VACUITY CONTROL — the same geometry without the declaration fires, and names the covered control, so the silence above is the clause and not an unpaired or unclassified node: %s"
              (pr-str (mapv :node u-fs)))]
     [(not= p-btn (geometry/intersection (:coords p-scrim) p-btn))
      (format "control geometry: the partial scrim %s does NOT contain the control %s — it reaches only part of it"
              (pr-str (:coords p-scrim)) (pr-str p-btn))]
     [(true? (:designed_overlay p-scrim))
      (format "…while still carrying the declaration, so the verdict below turns on CONTAINMENT and not on the key being absent: designed_overlay=%s"
              (pr-str (:designed_overlay p-scrim)))]
     [(= #{:overlap} (invariants-of p-fs))
      (format "THE CONTAINMENT GATE — a partial cover fires however it is declared, because half a live control is exactly the dead zone this rule exists for: %s"
              (pr-str (mapv :node p-fs)))]]))

(def ^:private cases
  "Case id -> its checks. A case that contributes NO check is itself a
   failure (see -main): a suite whose bodies are suppressed prints the same
   green as one that ran."
  {:siblings siblings-checks
   :designed-overlay designed-overlay-checks
   :click-area click-area-checks
   :layout-gap layout-gap-checks
   :nesting nesting-checks
   :proxy proxy-checks})

(defn -main
  [& requested]
  (let [selected (if (seq requested) (map keyword requested) (sort (keys cases)))
        unknown (remove cases selected)]
    (when (seq unknown)
      (println (format "unknown case selector(s): %s — known: %s"
                       (pr-str (vec unknown)) (pr-str (vec (sort (keys cases))))))
      (System/exit 2))
    (println "\n══ OVERLAP RULE — REAL-RENDER CANARY ══")
    (println (format "judging with devcards.lanes/overlap-classes at gap-px %d" gap-px))
    (let [results
          (into []
                (mapcat (fn [id]
                          (let [checks ((get cases id))]
                            (println (format "\n  ── %s ──" (name id)))
                            (doseq [[ok message] checks]
                              (println (format "    %s  %s" (if ok "PASS" "FAIL") message)))
                            (map (fn [[ok message]] {:case-id id :ok ok :msg message})
                                 checks))))
                selected)
          silent (remove (set (map :case-id results)) selected)
          bad (remove :ok results)]
      (println (format "\n%d/%d checks passed across %d case(s)"
                       (- (count results) (count bad)) (count results) (count selected)))
      (when (seq silent)
        (println (format "FATAL: case(s) contributed no check at all: %s"
                         (pr-str (vec silent)))))
      (doseq [{:keys [case-id msg]} bad]
        (println (format "  FAILED [%s] %s" (name case-id) msg)))
      (println (format "\noverlap real-render canary: %s"
                       (if (and (empty? bad) (empty? silent)) "PASS" "FAIL")))
      (System/exit (if (and (empty? bad) (empty? silent)) 0 1)))))
