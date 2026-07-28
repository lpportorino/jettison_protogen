(ns devcards.cascade-test
  "Contract tests for the fg/bg resolver.

   WHAT THESE PIN, AND WHAT THEY DO NOT — said out loud because the fixtures
   below are HAND-WRITTEN dump maps, which is the known way a canary ends up
   asserting the author's model of the dump vocabulary rather than the
   vocabulary. Every one of these fixtures was derived from a node the census
   probe actually observed (`.fork-scratch/cascade_census.clj`, which renders
   the real corpus on the real wasm); the shapes are transcriptions, not
   inventions. What they pin is the RESOLVER's reduction of those shapes. What
   they cannot pin is dump_obj's emission conditions changing underneath them
   — that is the census probe's job, and the `backdrop_unresolved` consistency
   throw is the resolver's own tripwire for it.

   `devcards.host/normalize-dump` is used for the truncated case rather than a
   hand-written stand-in, so at least that one input arrives through the real
   production membrane."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.cascade :as cascade]
            [devcards.host :as host]
            [devcards.invariants :as invariants]
            [devcards.opa :as opa]))

;; ── fixtures, transcribed from observed corpus nodes ────────────────────────

(defn- by-path
  [entries path]
  (first (filter #(= path (:path %)) entries)))

(def ^:private label-on-panel
  "`lv_obj/default/medium`: a screen root that emits text_color, a panel that
   changes it and paints a covering fill, and a label that inherits both the
   colour and — nothing at all for its backdrop."
  {:type "lv_obj" :coords [0 0 799 479] :bp 0 :theme_dark 1
   :clickable false :text_color "#fafafa"
   :children
   [{:type "lv_obj" :coords [0 0 799 479] :clickable false
     :text_color "#e8e8f0" :bg_color "#12121f"
     :children
     [{:type "lv_label" :coords [9 9 54 26] :text "Panel"
       :clickable false :backdrop_unresolved true :children []}]}]})

(def ^:private roller
  "`lv_roller/default/small/mid`, verbatim: the roller reports its SELECTED
   row through `text_on`, and its child `lv_roller_label` — which draws the
   unbanded option rows — reports no text, no fill and no
   `backdrop_unresolved`. That child is the silent-hole canary."
  {:type "lv_obj" :coords [0 0 799 479] :text_color "#fafafa"
   :children
   [{:type "lv_roller" :coords [0 0 47 115] :overflow true
     :text_color "#fafafa" :bg_color "#12121f"
     :text_on {:part "selected" :color "#ffffff" :bg "#0e7490"}
     :children
     [{:type "lv_roller_label" :coords [18 -27 28 142] :vis_px 1276
       :clipped true :offscreen true :clickable false :children []}]}]})

(def ^:private scale
  "`lv_scale/default/small/horizontal`: text_on whose PART paints no fill, so
   `bg` is absent inside it and the interpreter declares the backdrop
   unresolved."
  {:type "lv_obj" :coords [0 0 799 479] :text_color "#fafafa"
   :children
   [{:type "lv_scale" :coords [16 16 175 45]
     :text_on {:part "indicator" :color "#e8e8f0"}
     :backdrop_unresolved true :children []}]})

(defn- root-with
  [child]
  {:type "lv_obj" :coords [0 0 399 199] :text_color "#fafafa"
   :bg_color "#101010" :children [child]})

;; ── the three answers, each as its own assertion ────────────────────────────

(deftest a-resolved-pair-is-both-ends-determined
  (testing "an lv_label whose own fill covers resolves: ink from the
            inheritance chain, fill from its own bg_color, and NO reasons"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [10 10 50 30]
                                  :text "Ready" :bg_color "#1f2937"
                                  :children []}))
                     [0])]
      (is (= :yes (:glyphs e)))
      (is (= :resolved (:outcome e)))
      (is (= [] (:reasons e)))
      (is (= {:hex "#fafafa" :opa 255 :covers? true :from :inherited} (:fg e)))
      (is (= {:hex "#1f2937" :opa 255 :covers? true :from :bg-color} (:bg e))))))

(deftest no-glyphs-is-a-POSITIVE-determination
  (testing "an EMPTY lv_label draws nothing, and that is an answer — its own
            fill is still reported, because a fill is a fact whether or not
            anything is written on it, while NO foreground is: `text_color`
            resolves on every object and here it colours nothing, so handing
            it back would be UI-QUALITY-CONTRACTS §6.9's over-coverage half —
            a pair that cannot occur, scored anyway.
            REVERT-TO-BREAK: in `entry`, drop the `(when owes-pair? …)`
            wrapper around `fg`."
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [10 10 50 30]
                                  :text "" :bg_color "#1f2937" :children []}))
                     [0])]
      (is (= :no (:glyphs e)))
      (is (= :no-glyphs (:outcome e)))
      (is (= [] (:reasons e)))
      (is (nil? (:fg e)))
      (is (= "#1f2937" (:hex (:bg e))))))
  (testing "and a class devcards.opa lists as text-free is the same answer
            without reading any node key"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_switch" :coords [10 10 50 30]
                                  :bg_color "#1f2937" :children []}))
                     [0])]
      (is (= :no-glyphs (:outcome e))))))

(deftest an-undetermined-pair-comes-back-UNKNOWN-and-never-as-clean
  (testing "THE MANDATORY THIRD ANSWER. Every non-blank label in the shipped
            corpus is this case: glyphs on a fill the node does not paint, so
            what is behind them is not named by this node and no ancestor walk
            may supply it."
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0 0])]
      (is (= :yes (:glyphs e)))
      (is (= :unknown (:outcome e)))
      (is (= [:backdrop-unresolved] (:reasons e)))
      (is (= "#e8e8f0" (:hex (:fg e)))
          "the ink IS determined — an UNKNOWN pair still hands back the end
           it has")
      (is (nil? (:bg e)))))
  (testing "CONTROL: the panel it sits on, same tree same call, resolves
            nothing about glyphs but reports its covering fill — so the
            UNKNOWN above is about the label's backdrop and not about the
            call failing wholesale"
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0])]
      (is (= :no-glyphs (:outcome e)))
      (is (= {:hex "#12121f" :opa 255 :covers? true :from :bg-color}
             (:bg e))))))

;; ── the silent hole: the case a naive resolver reports clean ────────────────

(deftest a-glyph-bearing-node-the-dump-says-NOTHING-about-is-UNKNOWN
  (testing "`lv_roller_label` draws the roller's unbanded option rows and
            emits no `text`, no `bg_color`, no `text_on` and no
            `backdrop_unresolved` — `obj_draws_text` gates that flag on
            `lv_obj_check_type`, an EXACT class-pointer comparison, which a
            label SUBCLASS fails. So the flag's absence must never be read as
            'the backdrop is resolved': the backdrop is DERIVED from the fill
            keys instead.
            REVERT-TO-BREAK: in `entry`, replace the derived conjunct
            `(not (and (some? bg) (:covers? bg)))` with
            `(contains? node :backdrop_unresolved)`."
    (let [e (by-path (cascade/resolve-tree roller) [0 0])]
      (is (= "lv_roller_label" (:type e)))
      (is (= :yes (:glyphs e)) "devcards.opa lists it as glyph-bearing")
      (is (= :unknown (:outcome e)))
      (is (= [:backdrop-unresolved] (:reasons e)))))
  (testing "CONTROL: a node that DOES carry the flag is UNKNOWN for the same
            reason under either implementation, so the red above is
            attributable to the derivation and not to the flag being ignored"
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0 0])]
      (is (= :unknown (:outcome e)))
      (is (= [:backdrop-unresolved] (:reasons e)))))
  (testing "CONTROL: the roller ITSELF is judged on its text_on part, whose
            fill covers, so it resolves — the silence is the child's"
    (let [e (by-path (cascade/resolve-tree roller) [0])]
      (is (= :resolved (:outcome e)))
      (is (= {:hex "#ffffff" :opa 255 :covers? true :from :text-on} (:fg e)))
      (is (= {:hex "#0e7490" :opa 255 :covers? true :from :text-on-bg}
             (:bg e))))))

;; ── the inheritance walk ────────────────────────────────────────────────────

(def ^:private deep-inherit
  "The root emits `text_color`; the container between emits NOTHING, which is
   the ordinary case (the key is emitted only where it CHANGES); the label
   emits nothing either. The colour therefore has to travel two levels."
  {:type "lv_obj" :coords [0 0 399 199] :text_color "#fafafa"
   :children [{:type "lv_obj" :coords [0 0 199 99] :bg_color "#101010"
               :children [{:type "lv_label" :coords [1 1 9 9] :text "x"
                           :bg_color "#1f2937" :children []}]}]})

(deftest text_color-ABSENT-means-the-nearest-ancestor-that-emitted-one
  (testing "the colour travels PAST a node that emits nothing, so the walk
            must thread what is in force and not merely each node's own key.
            REVERT-TO-BREAK: in `walk`, pass `self-hex` instead of `in-force`
            down to the children."
    (let [e (by-path (cascade/resolve-tree deep-inherit) [0 0])]
      (is (= {:hex "#fafafa" :opa 255 :covers? true :from :inherited}
             (:fg e)))
      (is (= :resolved (:outcome e)))))
  (testing "and the NEAREST emitter wins, not the outermost.
            REVERT-TO-BREAK: in `walk`, swap `(or self-hex inherited-hex)` to
            `(or inherited-hex self-hex)`."
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0 0])]
      (is (= "#e8e8f0" (:hex (:fg e)))
          "the panel's, not the root's #fafafa")
      (is (= :inherited (:from (:fg e))))))
  (testing ":from separates a colour the node RESTATES from one it inherits,
            so a caller can tell where a value came from"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :text_color "#00ff00" :bg_color "#1f2937"
                                  :children []}))
                     [0])]
      (is (= {:hex "#00ff00" :opa 255 :covers? true :from :self} (:fg e)))))
  (testing "a tree in which nothing emits text_color yields the third answer
            rather than a default — the shape a detached subtree arrives in.
            REVERT-TO-BREAK: in `entry`, drop the `(nil? fg)` conjunct."
    (let [e (by-path (cascade/resolve-tree
                      {:type "lv_label" :coords [0 0 9 9] :text "x"
                       :bg_color "#101010" :children []})
                     [])]
      (is (= :unknown (:outcome e)))
      (is (= [:text-color-unrooted] (:reasons e)))
      (is (nil? (:fg e)))
      (is (some? (:bg e)) "the end that IS determined is still reported")))
  (testing "CONTROL: the identical node under a root that emits text_color
            resolves, so the red above keys on the missing source and not on
            the node"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :bg_color "#101010" :children []}))
                     [0])]
      (is (= :resolved (:outcome e))))))

;; ── the absence conventions, one assertion each ─────────────────────────────

(deftest an-absent-bg_color-means-NO-FILL-and-never-a-default
  (testing "nothing was drawn, so there is no :bg at all — a resolver that
            substituted a theme default here would be confidently wrong on
            every label in the corpus"
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0 0])]
      (is (not (contains? e :bg))))))

(deftest an-absent-bg_opa-means-the-fill-COVERS-and-a-present-one-does-not
  (testing "absent => COVER"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :bg_color "#1f2937" :children []}))
                     [0])]
      (is (true? (:covers? (:bg e))))
      (is (= 255 (:opa (:bg e))))
      (is (= :resolved (:outcome e)))))
  (testing "present => a PARTIAL fill, so what is drawn is a composite this
            resolver refuses to compute.
            REVERT-TO-BREAK: in `fill`, replace `(= cover o)` with `true`."
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :bg_color "#1f2937" :bg_opa 128
                                  :children []}))
                     [0])]
      (is (false? (:covers? (:bg e))))
      (is (= 128 (:opa (:bg e))))
      (is (= :unknown (:outcome e)))
      (is (= [:backdrop-unresolved] (:reasons e))))))

(deftest an-absent-text_opa-means-COVER-and-a-present-one-is-a-BLEND
  (testing "a translucent ink is not the styled colour; both ends come back
            and the blend is not computed.
            REVERT-TO-BREAK: in `entry`, drop the `(not (:covers? fg))`
            conjunct."
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :text_opa 127 :bg_color "#1f2937"
                                  :children []}))
                     [0])]
      (is (= :unknown (:outcome e)))
      (is (= [:ink-not-opaque] (:reasons e)))
      (is (= {:hex "#fafafa" :opa 127 :covers? false :from :inherited}
             (:fg e)))))
  (testing "FULLY transparent ink lands here too, deliberately: invisible
            text is worse than translucent text, so a rail carve-out would be
            a hole in the one predicate"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                                  :text_opa 0 :bg_color "#1f2937"
                                  :children []}))
                     [0])]
      (is (= [:ink-not-opaque] (:reasons e)))
      (is (= 0 (:opa (:fg e))))))
  (testing "CONTROL: the same node without the key is :resolved, so the red
            keys on the opacity and not on the fixture"
    (is (= :resolved
           (:outcome (by-path (cascade/resolve-tree
                               (root-with {:type "lv_label" :coords [0 0 9 9]
                                           :text "x" :bg_color "#1f2937"
                                           :children []}))
                              [0]))))))

(deftest text_on-answers-for-the-NODE-while-MAIN-still-feeds-the-CHILDREN
  (testing "the roller's own glyphs ride on LV_PART_SELECTED, so its pair is
            text_on's — and its MAIN text_color is nonetheless what its child
            label wears, which is why the two reads are not the same one.
            REVERT-TO-BREAK: in `walk`, use `(:hex fg)` of the entry as the
            inherited colour instead of the MAIN `text_color`."
    (let [es (cascade/resolve-tree roller)]
      (is (= "#ffffff" (:hex (:fg (by-path es [0])))) "text_on's, for itself")
      (is (= "#fafafa" (:hex (:fg (by-path es [0 0]))))
          "MAIN's, for the child")))
  (testing "a text_on PART that paints no fill has no `bg` member, and that
            absence carries the same meaning as an absent bg_color"
    (let [e (by-path (cascade/resolve-tree scale) [0])]
      (is (= "lv_scale" (:type e)))
      (is (nil? (:bg e)))
      (is (= :unknown (:outcome e)))
      (is (= [:backdrop-unresolved] (:reasons e))))))

;; ── the model check, and the disagreement it must NOT swallow ───────────────

(deftest a-backdrop_unresolved-that-contradicts-the-fill-keys-THROWS
  (testing "dump_obj emits the flag only when the glyph fill is below COVER,
            so the flag plus a covering fill means this resolver's model of
            the emission conditions is stale. Picking one answer silently is
            the failure the namespace exists to refuse.
            REVERT-TO-BREAK: delete the `(when (and declared bg (:covers? bg))
            (throw …))` form in `entry`."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"model of the emission conditions is stale"
         (cascade/resolve-tree
          (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                      :bg_color "#1f2937" :backdrop_unresolved true
                      :children []})))))
  (testing "CONTROL: the same flag over a PARTIAL fill is the ordinary case
            and returns normally, so the throw keys on the contradiction and
            not on the flag"
    (is (= [:backdrop-unresolved]
           (:reasons (by-path (cascade/resolve-tree
                               (root-with {:type "lv_label" :coords [0 0 9 9]
                                           :text "x" :bg_color "#1f2937"
                                           :bg_opa 128
                                           :backdrop_unresolved true
                                           :children []}))
                              [0]))))))

(deftest the-interpreter-claiming-glyphs-over-a-text-free-class-is-RECORDED
  (testing "`obj_text_part` matches lv_table by class, so the interpreter
            declares a backdrop for it, while devcards.opa lists it text-free
            because renderer.c never sets a cell value and every cell renders
            EMPTY. opa's verdict stands — it is the repo's one home for the
            question — and the disagreement is recorded rather than dropped,
            so wiring cell text is visible the next time someone looks.
            REVERT-TO-BREAK: drop the `:interpreter-claims-glyphs?` assoc in
            `entry`."
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_table" :coords [0 0 99 99]
                                  :text_on {:part "items" :color "#e8e8f0"}
                                  :backdrop_unresolved true :children []}))
                     [0])]
      (is (= :no-glyphs (:outcome e)))
      (is (true? (:interpreter-claims-glyphs? e)))))
  (testing "CONTROL: the flag on a class opa AGREES is glyph-bearing adds
            nothing and is not recorded, so the key marks a disagreement
            rather than restating every flag"
    (let [e (by-path (cascade/resolve-tree label-on-panel) [0 0])]
      (is (= :yes (:glyphs e)))
      (is (not (contains? e :interpreter-claims-glyphs?))))))

;; ── the truncated dump, through the real membrane ───────────────────────────

(deftest the-canonical-TRUNCATED-root-is-UNKNOWN-twice-over
  (testing "`host/normalize-dump` substitutes a root with no `type` and no
            `text_color`. Both gaps must surface: the class was never
            classified AND the foreground has no source in this tree. A
            resolver that shrugged at either would report a flooded dump as
            having nothing to say."
    (let [root (json/read-str (host/normalize-dump
                               "{\"type\":\"lv_obj\",\"truncated\":true")
                              :key-fn keyword)
          es (cascade/resolve-tree root)]
      (is (= 1 (count es)))
      (is (= :unknown (:glyphs (first es))))
      (is (= :unknown (:outcome (first es))))
      (is (= [:backdrop-unresolved :class-not-declared :text-color-unrooted]
             (:reasons (first es))))))
  (testing "CONTROL: the same call on a NON-truncated string returns the real
            root, so the reasons above come from the membrane's substitute
            and not from resolve-tree failing on any JSON"
    (let [root (json/read-str
                (host/normalize-dump
                 "{\"type\":\"lv_obj\",\"text_color\":\"#ffffff\",\"children\":[]}")
                :key-fn keyword)]
      (is (= :no-glyphs (:outcome (first (cascade/resolve-tree root))))))))

;; ── the reason vocabulary, and the class sets it leans on ───────────────────

(deftest every-DECLARED-reason-is-REACHABLE
  (testing "a closed vocabulary acquires dead entries as the code under it
            moves, and a reason nothing can emit names a gap that does not
            exist. Each input below is the smallest tree that produces one."
    (let [emitted
          (into #{}
                (mapcat :reasons)
                (concat
                 (cascade/resolve-tree label-on-panel)             ; backdrop
                 (cascade/resolve-tree
                  (root-with {:type "lv_image" :coords [0 0 9 9]   ; unserialised
                              :bg_color "#101010" :children []}))
                 (cascade/resolve-tree
                  (root-with {:type "acme_gauge" :coords [0 0 9 9] ; undeclared
                              :bg_color "#101010" :children []}))
                 (cascade/resolve-tree                             ; unrooted
                  {:type "lv_label" :coords [0 0 9 9] :text "x"
                   :bg_color "#101010" :children []})
                 (cascade/resolve-tree                             ; ink
                  (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                              :text_opa 127 :bg_color "#101010"
                              :children []}))))]
      (is (= (set (keys cascade/reasons)) emitted))))
  (testing "and every reason carries a non-blank doc, the same shape
            devcards.findings demands of a producer's :reasons"
    (is (every? (fn [[k v]] (and (simple-keyword? k)
                                 (string? v)
                                 (not (str/blank? v))))
                cascade/reasons))))

(deftest an-unclassified-class-is-a-REASON-and-not-a-shrug
  (testing "a consumer widget nobody declared cannot be cleared, and the
            distinct reason says what would close it.
            REVERT-TO-BREAK: in `glyph-verdict`, make the `:else` branch
            return `:no`."
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "acme_gauge" :coords [0 0 9 9]
                                  :bg_color "#1f2937" :children []}))
                     [0])]
      (is (= :unknown (:glyphs e)))
      (is (= :unknown (:outcome e)))
      (is (= [:class-not-declared] (:reasons e)))))
  (testing "lv_image is the DIFFERENT non-answer: the class can draw glyphs
            and the dump carries no src, so it is unserialised rather than
            undeclared"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "lv_image" :coords [0 0 9 9]
                                  :bg_color "#1f2937" :children []}))
                     [0])]
      (is (= [:glyph-source-unserialised] (:reasons e)))))
  (testing "CONTROL: declaring it text-free resolves it, additively, without
            editing either namespace"
    (let [e (by-path (cascade/resolve-tree
                      (root-with {:type "acme_gauge" :coords [0 0 9 9]
                                  :bg_color "#1f2937" :children []})
                      {:text-free-classes #{"acme_gauge"}})
                     [0])]
      (is (= :no-glyphs (:outcome e))))))

(deftest a-class-declared-BOTH-ways-is-refused
  (testing "the pair would be owed and not owed for the same node"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"declared BOTH"
         (cascade/resolve-tree label-on-panel
                               {:glyph-classes #{"lv_switch"}})))))

(deftest the-lv_label-special-case-is-NOT-a-fork-of-devcards-opa
  (testing "devcards.opa decides lv_label from the node's own `text` key and
            therefore lists it in NONE of its class sets. This file makes the
            same call, so the two must agree about that — otherwise one of
            them silently starts answering for the other."
    (is (not-any? #(contains? % cascade/label-class)
                  [opa/glyph-classes opa/text-free-classes
                   opa/ambiguous-classes]))
    (is (contains? opa/emitted-class-coverage cascade/label-class)))
  (testing "and an lv_label with no `text` key is refused rather than read as
            empty — dump_obj emits it unconditionally for exactly that class,
            so its absence means the tree is not this interpreter's"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"carries no `text` key"
         (cascade/resolve-tree
          (root-with {:type "lv_label" :coords [0 0 9 9] :children []}))))))

;; ── the walk itself ─────────────────────────────────────────────────────────

(deftest the-walk-agrees-with-the-registry-s-SHARED-walk
  (testing "`:path` and depth-first order are annotate-tree's, so a caller can
            zip the two rather than re-deriving ancestry a second, different
            way"
    (doseq [tree [label-on-panel roller scale]]
      (is (= (mapv :path (invariants/annotate-tree tree))
             (mapv :path (cascade/resolve-tree tree))))
      (is (= (mapv (comp :type :node) (invariants/annotate-tree tree))
             (mapv :type (cascade/resolve-tree tree)))))))

(deftest malformed-inputs-throw-rather-than-propagate
  (testing "a colour that is not #rrggbb"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not a #rrggbb colour"
         (cascade/resolve-tree {:type "lv_obj" :text_color "red"
                                :children []}))))
  (testing "an opacity outside 0..255"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not an opacity in 0\.\.255"
         (cascade/resolve-tree
          (root-with {:type "lv_label" :coords [0 0 9 9] :text "x"
                      :bg_color "#1f2937" :bg_opa 999 :children []})))))
  (testing "a text_on with no colour member"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"carries no `color`"
         (cascade/resolve-tree
          (root-with {:type "lv_scale" :coords [0 0 9 9]
                      :text_on {:part "indicator"} :children []})))))
  (testing "and a non-map root"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"needs a parsed dump_tree root map"
         (cascade/resolve-tree [])))))
