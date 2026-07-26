(ns devcards.layers
  "The LAYER CONTRACT — deliberate stacking is declared; anything else is a
   defect. Specified in docs/UI-QUALITY-CONTRACTS.md §1, which is the
   authority for the reasoning; this ns is the mechanism.

   The base rule (no two elements overlap or touch within a threshold) is
   too strict for real interfaces, because stacking is how interfaces are
   built. A LAYER is how a design says which stacking is deliberate: a
   declaration carries a z, applies to a subtree, and two elements that
   are too close are judged by their NEAREST DECLARING ANCESTOR.

   ── z IS DECLARED, STACKING IS OBSERVED ──────────────────────────────
   The contract asserts that those two AGREE. That is what makes it a
   contract rather than a tautology: the declaration is authored
   independently of the render, so it can disagree, so the check can fail.

   A checker that derived z from observed paint order could never fail.
   The known-bad case is the proof: infer z from the fact that the video
   proxy currently covers the chrome, and the checker ratifies the bug.
   Declare chrome above video by INTENT and the same checker correctly
   fails, because the compositor punches video over chrome after LVGL has
   finished.

   ── THE INPUTS ───────────────────────────────────────────────────────
   :nodes        the shared annotated walk (ancestry as :path)
   :declaration  {:layers {uid {:z n :id \"name\"}}} — INTENT, per subtree
   :proxy-rects  [{:uid n :proxy-id s :coords [..]}] — compositor rects,
                 NOT derivable from the tree, because the host-proxy punch
                 happens after LVGL and actual stacking is therefore not
                 readable from widget child order

   An undeclared subtree is layer :root at z 0, so strict applies by
   default rather than absence being an exemption.

   ── LOUD FAILURE, because its silent version already happened ────────
   A proxy rect naming a uid that matches no node THROWS. In the POC this
   defect was a keep-nil lookup: the unresolved proxy became nil, was
   dropped, counted as ordinary chrome, and the whole z-inversion check
   reported CLEAN — with output byte-identical to its nothing-to-report
   output. Resolution failure is not a quiet zero here."
  (:require [devcards.geometry :as geometry]
            [devcards.invariants :as invariants]))

(set! *warn-on-reflection* true)

(def root-layer
  "The implicit layer every undeclared element belongs to. z 0 so an
   undeclared subtree gets the STRICT base rule — absence of a
   declaration is never an exemption."
  {:key :root :z 0 :id "(undeclared)"})

(defn- declaration-for
  "The nearest declaring ancestor of an annotated entry, or `root-layer`.
   Walks the entry's own uid first (a node may declare its own layer),
   then ancestors nearest-first."
  [layers entry uid-at]
  (or (first
       (keep (fn [path]
               (when-let [uid (uid-at path)]
                 (when-let [d (get layers uid)]
                   {:key uid :z (:z d) :id (or (:id d) (str "uid " uid))})))
             (->> (:path entry)
                  count
                  inc
                  range
                  (map #(subvec (:path entry) 0 %))
                  reverse)))
      root-layer))

(defn- uid-index
  "{path -> uid} over every node carrying one."
  [nodes]
  (into {} (for [e nodes :when (:uid (:node e))] [(:path e) (:uid (:node e))])))

(defn- resolve-proxies!
  "{path -> proxy-rect} for every declared proxy. A proxy whose :uid
   matches NO node throws — see the ns docstring."
  [nodes proxy-rects]
  (let [by-uid (into {} (for [e nodes :when (:uid (:node e))] [(:uid (:node e)) e]))]
    (into {}
          (for [p proxy-rects]
            (let [e (or (get by-uid (:uid p))
                        (throw (ex-info
                                (str "proxy rect names uid " (pr-str (:uid p))
                                     " which matches NO node in the tree — "
                                     "refusing to drop it silently (a dropped "
                                     "proxy makes the whole z-inversion check "
                                     "report CLEAN)")
                                {:proxy p :known-uids (vec (sort (keys by-uid)))})))]
              [(:path e) p])))))

(defn- check-declaration!
  "Every uid a declaration names must exist in the tree. A uid that matches
   nothing was silently ignored, which is the SAME defect the proxy side
   throws for and for the same reason: the subtree it was meant to lift
   into its own layer stays at root z 0, so a real z-inversion collapses
   into a same-layer pair or into nothing at all, and the run reports CLEAN
   with output byte-identical to its nothing-to-report output. Renaming or
   renumbering a widget is exactly how a consumer reaches this state."
  [nodes layers]
  (when-not (or (nil? layers) (map? layers))
    (throw (ex-info (str ":declaration :layers must be a map of uid -> "
                         "{:z n :id s}, got " (pr-str (type layers)))
                    {:layers layers})))
  (let [known (into #{} (keep (comp :uid :node)) nodes)]
    (when-let [missing (seq (remove known (keys layers)))]
      (throw (ex-info (str "layer declaration names uid(s) "
                           (vec (sort missing))
                           " which match NO node in the tree — refusing to "
                           "drop them silently (a dropped declaration leaves "
                           "its subtree at root z 0 and the inversion it was "
                           "meant to catch disappears)")
                      {:missing (vec (sort missing))
                       :known-uids (vec (sort known))})))
    ;; VALUES too, not just keys. A missing or misspelled :z left `(:z d)`
    ;; nil, which either threw a context-free NPE deep in the comparison or —
    ;; when BOTH sides were nil, the realistic typo — compared equal and
    ;; silently relabelled a genuine inversion as :layer-ambiguous. A
    ;; declaration is INTENT; intent that does not parse is not intent.
    (doseq [[uid d] layers]
      (when-not (map? d)
        (throw (ex-info (str "layer declaration for uid " uid
                             " must be a map {:z n :id s}, got " (pr-str d))
                        {:uid uid :declaration d})))
      (when-not (number? (:z d))
        (throw (ex-info (str "layer declaration for uid " uid
                             " has :z " (pr-str (:z d))
                             " — z must be a NUMBER. A nil z compares equal to "
                             "another nil z, which turns a real inversion into "
                             ":layer-ambiguous rather than reporting it")
                        {:uid uid :declaration d})))))
  layers)

(defn- proxy-surface?
  "Is this entry ITSELF a host-proxy surface? Deliberately not `under?`: the
   compositor punches video INTO the proxy's rect after LVGL has finished,
   so anything LVGL drew inside that rect — the proxy's own widget children
   included — ends up UNDER the video, not on top of it with it. Treating
   descendants as compositor-painted claimed the opposite and would have
   ruled a proxy's own children above every widget they overlap."
  [proxy-paths entry]
  (boolean (some #(= (:path entry) %) proxy-paths)))

(defn- observed-top
  "Which of the two entries is painted LAST, hence on top? `a` and `b` MUST
   arrive in pre-order (`a` before `b`), which is what the caller's pair
   loop guarantees.

   A proxy surface wins outright — the compositor punch lands after LVGL.
   Otherwise TREE ORDER decides, and given the pre-order precondition the
   answer is unconditionally `b`: LVGL paints a parent then its children in
   index order, so the later node in a depth-first pre-order traversal
   paints later.

   This used to call `(compare (:path a) (:path b))` and describe it as
   lexicographic. Clojure's vector `compare` is COUNT-first — `(compare
   [0 0] [1])` is 1, though [0 0] paints first — so it inverted the verdict
   for every pair whose earlier node is DEEPER than its later one, which is
   the ordinary shape of nested chrome. It was also redundant: the caller
   already enumerates pairs in pre-order, so the comparison could only ever
   disagree with the order it was being asked to reproduce. Returning `b`
   is both the fix and the whole of it."
  [proxy-paths a b]
  (let [pa (proxy-surface? proxy-paths a)
        pb (proxy-surface? proxy-paths b)]
    (cond (and pa (not pb)) a
          (and pb (not pa)) b
          :else b)))

(defn- label-of
  [{:keys [node]}]
  (str (:type node) (when-let [uid (:uid node)] (str "#" uid))))

(defn findings
  "Layer-contract findings for one card. Reads ctx :card-id, :nodes,
   :declaration, :proxy-rects and :thresholds {:gap-px n}.

   Judges every pair of non-related nodes closer than the threshold, per
   the outcome matrix in docs/UI-QUALITY-CONTRACTS.md §1.4."
  [{:keys [card-id nodes declaration proxy-rects thresholds]}]
  (let [gap-px (:gap-px thresholds 0)
        layers (:layers declaration)
        _ (check-declaration! nodes layers)
        proxy-paths (keys (resolve-proxies! nodes proxy-rects))
        uid-at (uid-index nodes)
        visible (into []
                      (filter (fn [e]
                                (and (not (:hidden (:node e)))
                                     (not (:hidden-under? e)))))
                      nodes)
        ;; A visible node with no usable :coords cannot be judged, and
        ;; silence would report it as clear of everything. CLAUDE.md makes
        ;; that a finding, never a skip — the same rule devcards.overlap
        ;; follows; this lane skipped it, which was the mandate applying to
        ;; everything except the code that shipped alongside it.
        unmeasurable (for [e visible :when (nil? (:coords (:node e)))]
                       {:card card-id
                        :invariant :unmeasurable-node
                        :node (label-of e)
                        :detail (str "visible node has no :coords, so the layer "
                                     "contract could not judge it — it is NOT "
                                     "thereby clear of everything else")})
        judged (filterv #(:coords (:node %)) visible)
        ;; Keyed on :path, which is unique per node. The hazard is NOT two
        ;; identical siblings — those resolve through the same self-uid or the
        ;; same shared ancestor prefix, so a shared memo entry gives the same
        ;; (correct) answer. It is the SAME node map appearing under two
        ;; DIFFERENT declaring ancestors: equal as values, but belonging to
        ;; different layers. Keying on the node map hands the second one the
        ;; first one's layer, collapsing a real :layer-inversion into
        ;; :layer-same — a true defect reported as the wrong defect.
        layer-cache (atom {})
        layer-of (fn [e]
                   (or (@layer-cache (:path e))
                       (let [l (declaration-for layers e uid-at)]
                         (swap! layer-cache assoc (:path e) l)
                         l)))]
    (into
     (vec unmeasurable)
     (for [[i a] (map-indexed vector judged)
           b (subvec judged (inc i))
           :when (not (invariants/related? a b))
           :let [sep (geometry/separation (:coords (:node a)) (:coords (:node b)))]
           :when (< sep gap-px)
           :let [la (layer-of a)
                 lb (layer-of b)
                 pair (str (label-of a) " vs " (label-of b))
                 where (str " — " (geometry/describe (:coords (:node a)))
                            " vs " (geometry/describe (:coords (:node b))))
                 how (if (neg? sep)
                       (str "overlap depth " (- sep) "px")
                       (str sep "px apart, under the " gap-px "px minimum"))
                 overlapping? (neg? sep)
                 finding
                 (cond
                   (= (:key la) (:key lb))
                   {:invariant :layer-same
                    :detail (str "both in layer " (pr-str (:id la))
                                 " (" how ") — same-layer stacking is never "
                                 "declared, so it is a defect" where)}

                   ;; §1.4: "different layer, merely touching = OK". Only the
                   ;; SAME-layer clause is a proximity rule; every clause
                   ;; below reasons about which element is painted over the
                   ;; other, and boxes that do not share a pixel are not
                   ;; painted over each other at all. Firing here at
                   ;; gap-px >= 1 contradicted the matrix this ns cites as its
                   ;; authority AND emitted a message asserting one layer "is
                   ;; painted OVER" another when neither covered the other.
                   (not overlapping?) nil

                   ;; Two compositor surfaces sharing pixels cannot be
                   ;; ordered from this data at all: both are punched after
                   ;; LVGL, and their relative order lives in the compositor,
                   ;; not the widget tree. Falling through to tree order here
                   ;; would use exactly the ordering this ns declares invalid
                   ;; for proxies, and would ratify whichever declaration
                   ;; happened to match it.
                   (and (proxy-surface? proxy-paths a) (proxy-surface? proxy-paths b))
                   {:invariant :layer-proxy-ambiguous
                    :detail (str "two host-proxy surfaces overlap (" how
                                 ") — both are punched by the compositor after "
                                 "LVGL, so their order is not readable from "
                                 "the widget tree and this contract cannot "
                                 "judge the stack. Separate them, or declare "
                                 "their order to the compositor" where)}

                   (= (:z la) (:z lb))
                   {:invariant :layer-ambiguous
                    :detail (str "layers " (pr-str (:id la)) " and "
                                 (pr-str (:id lb)) " both declare z "
                                 (:z la) " (" how ") — which paints on top "
                                 "is unspecified, so the stack is a coin "
                                 "flip rather than a design" where)}

                   :else
                   (let [hi (if (> (:z la) (:z lb)) a b)
                         hi-layer (if (> (:z la) (:z lb)) la lb)
                         lo-layer (if (> (:z la) (:z lb)) lb la)
                         top (observed-top proxy-paths a b)]
                     (when-not (= top hi)
                       {:invariant :layer-inversion
                        :detail (str "layer " (pr-str (:id lo-layer)) " (z "
                                     (:z lo-layer) ") is painted OVER layer "
                                     (pr-str (:id hi-layer)) " (z "
                                     (:z hi-layer) ") — the declaration says "
                                     "the opposite (" how ")" where
                                     (when (proxy-surface? proxy-paths top)
                                       (str ". The top surface is a host "
                                            "proxy: the compositor punches it "
                                            "after LVGL finishes, so no widget "
                                            "z-order can hold it back")))})))]
           :when finding]
       (assoc finding :card card-id :node pair)))))

(def producer
  "The registry entry. Opt-in: a consumer arms it by appending this map and
   supplying :declaration and :proxy-rects. Both are REQUIRED context —
   supplying `[]` for proxy rects is the claim 'this card has no proxy
   surfaces', while omitting the key is an oversight the registry refuses."
  {:id :layers
   :fn findings
   :requires #{:nodes :declaration :proxy-rects}
   :thresholds {:gap-px {:pred nat-int?
                         :default 0
                         :doc (str "minimum clear pixels between elements "
                                   "before the layer contract judges them; "
                                   "0 = overlap only, 1 = touching counts")}}})
