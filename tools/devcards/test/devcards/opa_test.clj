(ns devcards.opa-test
  "Canaries for the opacity ban (`devcards.opa`) — pure dump-tree maps in,
   findings out.

   THE TWO HALVES ARE EQUALLY LOAD-BEARING, and the second is the one that
   catches a false gate. A clause that fires on a faded label is easy; a
   clause that ALSO fires on a faded arc is a gate that goes red for the
   wrong reason and looks exactly as correct as one that did not. The theme
   fades slider, arc, bar, led and the table's line-art grid ON
   PURPOSE — the critical content there is a shape, with no self-contrast
   for a fade to collapse — so `text-free-classes-do-NOT-fire`
   below is not a nicety. It is the specific defect this clause reproduces
   first if the precondition is ever read as 'DISABLED' rather than as
   'glyphs'.

   Every empty-result assertion here is paired with a control that MUST be
   non-empty for the same input class, because `(is (empty? fs))` also passes
   when the rule threw, never ran, or classified nothing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lanes :as lanes]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.opa :as opa]))

(defn- judge
  ([root] (judge root {}))
  ([root thresholds]
   (opa/findings {:card-id "c"
                  :nodes (invariants/annotate-tree root)
                  :thresholds thresholds})))

(defn- invariants-of [fs] (set (map :invariant fs)))

(defn- root-with
  "A screen root that is itself unfaded, so every fade below is the child's."
  [children]
  {:type "lv_obj" :coords [0 0 199 199] :children children})

(defn- node
  [t extra]
  (merge {:type t :coords [10 10 89 39] :children []} extra))

;; ── the canary: a fade over glyphs ──────────────────────────────────────

(deftest a-faded-label-with-text-FIRES
  (let [fs (judge (root-with [(node "lv_label" {:uid 1 :text "ARMED"
                                                :opa 127})]))]
    (testing "the whole subject of the ban: LVGL has no per-run text
              exemption, so a whole-widget opa scales the glyph and its
              fill alike"
      (is (= #{:opa-over-text} (invariants-of fs)))
      (is (= 1 (count fs)))
      (is (= "lv_label#1" (:node (first fs)))))
    (testing "the finding carries the opa value, so a reader can tell a
              near-neutral fade from a heavy one"
      (is (re-find #"opacity 127" (:detail (first fs)))))
    (testing "a violation is :failed — the DEFAULT outcome, carried by
              absence, which is what keeps it blocking under the shipped
              policy without the finding saying so"
      (is (not (contains? (first fs) :act/outcome))))))

(deftest the-same-label-WITHOUT-opa-is-silent
  (testing "the control for the canary above: dump_obj emits `opa` only
            when the accumulated value differs from LV_OPA_COVER, so an
            absent key is a POSITIVE statement that no fade reaches this
            node — the rule must key on presence and nothing else"
    (let [with-fade (judge (root-with [(node "lv_label" {:text "ARMED"
                                                         :opa 127})]))
          without (judge (root-with [(node "lv_label" {:text "ARMED"})]))]
      (is (= 1 (count with-fade)))
      (is (empty? without)))))

(deftest a-fade-on-an-ANCESTOR-fires-at-the-glyphs
  (testing "`obj_effective_opa` accumulates self -> root, so a fade
            declared on a container is emitted on EVERY descendant. That is
            what makes a per-node rule express the contract's 'no fade over
            a SUBTREE containing text' without walking anything: the
            violation is reported where the glyphs actually are."
    (let [fs (judge {:type "lv_obj" :coords [0 0 199 199] :opa 127
                     :children [(node "lv_obj" {:opa 127
                                                :children
                                                [(node "lv_label"
                                                       {:uid 7 :text "hi"
                                                        :opa 127})]})]})]
      (is (= #{:opa-over-text} (invariants-of fs)))
      (is (= ["lv_label#7"] (mapv :node fs))))))

;; ── the half that catches a FALSE gate ──────────────────────────────────

(def ^:private text-free-subjects
  "Every class the theme deliberately fades. `disabled_dim` covers slider,
   arc, bar, led and the checkbox INDICATOR part; `disabled_flat` covers the
   table grid. A clause that condemned any of them would turn the gate red on
   a design decision.

   `lv_switch` IS DELIBERATELY ABSENT and was once here. Its value is
   knob-vs-track contrast, which a fade collapses, so the theme gives it the
   pair swap instead and it carries no layered `:opa` — see
   docs/UI-QUALITY-CONTRACTS.md §6.4. The fixtures below are hand-written
   dump maps, so a stale entry here would have kept passing on a planted
   `:opa` the shipped corpus no longer produces; re-derive membership from
   the corpus rather than trusting this vector."
  ["lv_slider" "lv_arc" "lv_bar" "lv_led" "lv_table"])

(deftest text-free-classes-do-NOT-fire
  (testing "the precondition is GLYPHS, not DISABLED and not a class list.
            A clause that reds the arc is a false gate that happens to be
            the right colour."
    (let [fs (judge (root-with
                     (map-indexed (fn [i t] (node t {:uid i :opa 127
                                                     :disabled true}))
                                  text-free-subjects)))]
      (is (empty? fs))))
  (testing "THE REASON FOR THAT EMPTINESS, which an `empty?` alone does not
            supply: the identical tree with one glyph-bearing sibling added
            reports exactly one finding, so the rule ran, walked these
            nodes, and cleared them"
    (let [fs (judge (root-with
                     (conj (mapv (fn [t] (node t {:opa 127 :disabled true}))
                                 text-free-subjects)
                           (node "lv_label" {:uid 99 :text "x" :opa 127
                                             :disabled true}))))]
      (is (= 1 (count fs)))
      (is (= "lv_label#99" (:node (first fs)))))))

(deftest a-label-attached-to-faded-geometry-STILL-fires
  (testing "the theme's own comment records this: the precondition is the
            EMPTY subtree, not the class name. Attach a label to a slider
            and the hazard is live again — and because the fade accumulates
            down, the label's own node carries it."
    (let [fs (judge (root-with
                     [(node "lv_slider" {:uid 1 :opa 127
                                         :children [(node "lv_label"
                                                          {:uid 2 :text "50%"
                                                           :opa 127})]})]))]
      (is (= #{:opa-over-text} (invariants-of fs)))
      (is (= ["lv_label#2"] (mapv :node fs))))))

(deftest an-EMPTY-faded-label-is-silent-and-a-full-one-is-not
  (testing "an empty label draws nothing, so condemning it would be a
            finding with no subject — the same ground on which
            `obj_draws_text` excludes it C-side"
    (is (empty? (judge (root-with [(node "lv_label" {:text "" :opa 127})])))))
  (testing "the control: one character of text and the same node fires"
    (is (= 1 (count (judge (root-with [(node "lv_label"
                                             {:text "x" :opa 127})])))))))

;; ── the THIRD answer ────────────────────────────────────────────────────

(deftest a-faded-IMAGE-is-the-third-answer
  (let [fs (judge (root-with [(node "lv_image" {:uid 3 :opa 127})]))]
    (testing "lv_image draws a label dsc iff its src is a SYMBOL, and the
              dump carries no src — so neither 'violation' nor 'clean' is a
              verdict the measurement can support"
      (is (= #{:opa-over-possible-text} (invariants-of fs)))
      (is (= :cantTell (:act/outcome (first fs))))
      (is (= :glyph-source-unserialised (:act/reason (first fs)))))
    (testing "and it BLOCKS: the shipped policy fails on :cantTell, so the
              third answer is a red gate rather than a footnote"
      (is (contains? (:fail-outcomes lanes/verdict-policy) :cantTell)))))

(deftest an-UNDECLARED-class-is-a-finding-never-a-skip
  (let [fs (judge (root-with [(node "acme_gauge" {:uid 4 :opa 127})]))]
    (is (= #{:opa-unclassified-class} (invariants-of fs)))
    (is (= :cantTell (:act/outcome (first fs))))
    (is (= :class-not-declared (:act/reason (first fs)))))
  (testing "and an undeclared class WITHOUT a fade is not this rule's
            business — the clause judges fades, not vocabulary"
    (is (empty? (judge (root-with [(node "acme_gauge" {:uid 4})]))))))

;; ── the sets themselves ─────────────────────────────────────────────────

(deftest the-clause-is-TOTAL-over-every-class-the-renderer-emits
  (testing "a widget added to the corpus owes a row here. Without this the
            first card that fades it reports :opa-unclassified-class and
            blocks — correct, but discovered at render time instead of at
            test time."
    (is (empty? (remove opa/emitted-class-coverage
                        lvgl-classes/emitted-classes)))))

(deftest the-shipped-sets-do-not-overlap
  (is (true? (opa/validate-sets!)))
  (testing "the sets are DISJOINT, so no class can be both condemned and
            cleared with the winner decided by clause ordering"
    (is (empty? (filter opa/glyph-classes opa/text-free-classes)))
    (is (empty? (filter opa/glyph-classes opa/ambiguous-classes)))
    (is (empty? (filter opa/text-free-classes opa/ambiguous-classes)))))

(deftest a-consumer-may-ADD-classes-but-not-contradict-them
  (testing "a consumer's own widget joins the decided set through a
            threshold, so an unknown class is fixable without forking"
    (is (empty? (judge (root-with [(node "acme_gauge" {:opa 127})])
                       {:text-free-classes #{"acme_gauge"}})))
    (is (= #{:opa-over-text}
           (invariants-of (judge (root-with [(node "acme_meter" {:opa 127})])
                                 {:glyph-classes #{"acme_meter"}})))))
  (testing "and a class declared BOTH ways throws rather than letting
            whichever arm is tested first decide"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"declared BOTH"
         (judge (root-with [(node "acme_gauge" {:opa 127})])
                {:glyph-classes #{"acme_gauge"}
                 :text-free-classes #{"acme_gauge"}}))))
  (testing "a consumer cannot DELETE one of ours: the sets are unioned, so
            naming lv_label's neighbours does not un-condemn them"
    (is (= 1 (count (judge (root-with [(node "lv_dropdown" {:opa 127})])
                           {:text-free-classes #{"acme_gauge"}}))))))

;; ── the RETIRES-WHEN, made executable ───────────────────────────────────

(def ^:private renderer-source
  "`renderer/src/renderer.c` as TEXT. The suite already reads repo files
   this way (`lvgl-classes-test` reads corpus/spec.edn, `lanes-test` reads
   core.clj); it is the only instrument that reaches a C decode arm from
   here."
  (slurp (io/file "../../renderer/src/renderer.c")))

(def ^:private renderer-code
  "The same file with COMMENTS REMOVED, which this test cannot do without:
   renderer.c's table arm carries the sentence 'WIRING
   lv_table_set_cell_value HERE VOIDS THAT', so a bare substring search for
   the identifier matches the warning about the call and fails on a tree
   that is correct. Measured — that is exactly how this canary failed
   first. Naive by design (it does not model a `/*` inside a string
   literal); the two assertions below pin that it removed the comments and
   kept the code."
  (-> renderer-source
      (str/replace #"(?s)/\*.*?\*/" " ")
      (str/replace #"//[^\n]*" " ")))

(deftest table-carve-out-still-holds
  (testing "the instrument first: the stripper really removed comments and
            really kept code, so neither half of the verdict below is an
            artefact of a regex that matched nothing"
    (is (re-find #"WIRING lv_table_set_cell_value HERE" renderer-source))
    (is (not (re-find #"WIRING lv_table_set_cell_value HERE" renderer-code)))
    (is (re-find #"ui_WidgetNode_table_props_tag" renderer-code)))
  (testing "`lv_table` sits in `text-free-classes` on ONE condition — the
            renderer never sets cell text, so every cell renders empty and
            the faded subtree is a grid of lines. renderer.c, theme.c and
            the corpus notes all say 'wiring cell text voids this' and
            renderer.c adds 'Nothing mechanical catches this yet'. This is
            the mechanism. When it fails, move lv_table to `glyph-classes`
            and move theme.c's table arm off `disabled_flat` in the same
            change."
    (is (not (re-find #"lv_table_set_cell_value" renderer-code)))))

;; ── the ARM ─────────────────────────────────────────────────────────────

(deftest the-clause-is-ARMED-on-both-lanes
  (testing "a rule nothing runs is prose. Both lanes carry it, and
            `armed-producers` is derived from them."
    (is (some #{opa/producer} lanes/atomic-producers))
    (is (some #{opa/producer} lanes/composition-producers))
    (is (some #{opa/producer} lanes/armed-producers))))

(deftest the-producer-registers-and-its-cantTell-survives-the-registry
  (testing "`validate-producers!` is what proves the :outcomes / :reasons
            declaration is well-formed; without it a :cantTell finding
            throws at `check-outcome!` instead of being reported"
    (is (= lanes/armed-producers
           (findings/validate-producers! lanes/armed-producers))))
  (testing "and the whole path — registry in, findings out — carries the
            axes through, which a direct call to `opa/findings` cannot show"
    (let [live (:live (findings/card-findings
                       {:card-id "c"
                        :tree (root-with [(node "lv_image" {:uid 3
                                                            :opa 127})])
                        :producers [opa/producer]
                        :armed-producers lanes/armed-producers
                        :exemptions []}))]
      (is (= 1 (count live)))
      (is (= :cantTell (:act/outcome (first live))))
      (is (= :opa (:producer (first live)))))))
