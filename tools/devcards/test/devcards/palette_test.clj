(ns devcards.palette-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.findings :as findings]
            [devcards.palette :as palette]))

(set! *warn-on-reflection* true)

(def ^:private test-palettes
  {:dark #{"#111111" "#ABCDEF"}
   :light #{"#EEEEEE" "#ABCDEF"}})

(defn- tree
  "The semantic tree the producer reads the rendered MODE from. The palette is
   deliberately NOT in here — it is a separate export."
  [dark]
  {:type "lv_obj" :theme_dark (if dark 1 0) :children []})

(defn- payload
  "A parsed `controls_dump_draw_palette` result. `:records` defaults to the
   colour count because the producer reads it to tell `nothing was drawn` from
   `nothing was watching`; the tests that exercise that distinction pass it
   explicitly."
  [colors & {:keys [overflow records]}]
  (cond-> {:colors colors :records (or records (count colors))}
    overflow (assoc :overflow true)))

(defn- judge*
  "Judge an explicit tree + payload."
  ([t draw-palette] (judge* t draw-palette test-palettes))
  ([t draw-palette palettes]
   (:live
    (findings/card-findings
     {:card-id "card"
      :tree t
      :draw-palette draw-palette
      :thresholds {:palette/colors-by-mode palettes}
      :producers [palette/producer]}))))

(defn- judge
  "The common case: a mode and a colour list."
  [dark colors]
  (judge* (tree dark) (payload colors)))

(defn- c-projected-colour-tokens
  "The colour tokens that reach the native theme, re-derived from the generated
   header rather than copied as a number — `theme.c` can only select these."
  []
  (->> (slurp (io/file "../../renderer/generated/theme_tokens.h"))
       (re-seq #"#define\s+THEME_([A-Z0-9_]+)_DARK\s+0x[0-9A-Fa-f]{6}")
       (map second)
       set))

(deftest producer-registers-and-judges-the-full-catalogue-not-the-c-projection
  (is (= [palette/producer]
         (findings/validate-producers! [palette/producer])))
  (is (palette/palette-table? palette/palette-by-mode))
  (testing "the choice recorded in the ns docstring, asserted as a RELATION so
            adding a token cannot red this for no defect: the emitted semantic
            catalogue must be strictly wider than what projects into C, since
            that gap is the whole reason the narrow table would over-claim"
    (let [projected (count (c-projected-colour-tokens))]
      (is (pos? projected) "the header parse must not silently yield nothing")
      (is (> (count (:dark palette/palette-by-mode)) projected))
      (is (> (count (:light palette/palette-by-mode)) projected)))))

(deftest a-drawn-token-value-is-clean-and-a-non-token-value-fires
  (is (empty? (judge true [{:hex "#111111"
                            :theme_recolor false}])))
  (let [fs (judge true [{:hex "#010203"
                         :theme_recolor false}])]
    (is (= 1 (count fs)))
    (is (= :drawn-colour-in-no-token-table (:invariant (first fs))))
    (is (= :palette (:producer (first fs))))
    (is (= :dark (:mode (first fs))))
    (is (str/includes? (:detail (first fs)) "#010203"))))

(deftest the-palette-is-mode-specific
  (testing "dark-only #111111 is accepted in dark and rejected in light.
            REVERT-TO-BREAK: make `valid` ignore `mode` — (into #{} (mapcat val
            palette)) — and this fails while every other test here stays green,
            because only this one uses a colour declared in exactly one mode."
    (is (empty? (judge true [{:hex "#111111"
                              :theme_recolor false}])))
    (is (= [:drawn-colour-in-no-token-table]
           (mapv :invariant
                 (judge false [{:hex "#111111"
                                :theme_recolor false}])))))
  (testing "CONTROL: #ABCDEF is in BOTH tables and stays clean in both, so the
            survivor above is the mode axis and not membership as such"
    (is (empty? (judge true [{:hex "#ABCDEF" :theme_recolor false}])))
    (is (empty? (judge false [{:hex "#ABCDEF" :theme_recolor false}])))))

(deftest declared-theme-recolors-are-separated-from-everything-else
  (testing "the exact same non-token hex is exempt only on the observer's
            positive theme-recolor classification.
            REVERT-TO-BREAK: delete the `(not (:theme_recolor observation))`
            conjunct. The first assertion below fails; the second is the
            CONTROL that must stay green, so the survivor is attributable to
            the recolor clause and not to token membership."
    (is (empty? (judge true [{:hex "#010203"
                              :theme_recolor true}])))
    (is (= [:drawn-colour-in-no-token-table]
           (mapv :invariant
                 (judge true [{:hex "#010203"
                               :theme_recolor false}])))))
  (testing "a declared recolor does not hide a same-hex ordinary draw — the
            exemption is per observation, never per colour"
    (is (= 1
           (count
            (judge true [{:hex "#010203" :theme_recolor true}
                         {:hex "#010203" :theme_recolor false}]))))))

(deftest duplicate-partial-strip-observations-do-not-weight-the-verdict
  (let [fs (judge true (repeat 8 {:hex "#010203" :theme_recolor false}))]
    (is (= 1 (count fs)))
    (is (= :drawn-colour-in-no-token-table (:invariant (first fs))))))

(deftest missing-or-incomplete-instrumentation-is-a-blocking-third-answer
  (testing "a payload with no record count — some other projection of this
            observer, whose empty-vs-unwatched distinction cannot be recovered"
    (let [fs (judge* (tree true) {:colors []})]
      (is (= [:draw-palette-unavailable] (mapv :invariant fs)))
      (is (= [:observer-not-exposed] (mapv :act/reason fs)))
      (is (= [:cantTell] (mapv :act/outcome fs)))))
  (testing "a caller that never plumbed the export supplies NO :draw-palette
            at all. The registry cannot catch that — its context-key set is
            closed, so :draw-palette is unlistable in :requires — and this is
            the branch that has to carry the refusal instead. It must not be an
            empty live vector, which is what a clean card returns."
    (let [fs (:live (findings/card-findings
                     {:card-id "card"
                      :tree (tree true)
                      :thresholds {:palette/colors-by-mode test-palettes}
                      :producers [palette/producer]}))]
      (is (= [:draw-palette-unavailable] (mapv :invariant fs)))
      (is (= [:cantTell] (mapv :act/outcome fs)))))
  (testing "an overflowed bounded buffer"
    (let [fs (judge* (tree true) (payload [] :overflow true :records 4096))]
      (is (= [:draw-palette-overflow] (mapv :invariant fs)))
      (is (= [:observer-buffer-overflow] (mapv :act/reason fs)))
      (is (= [:cantTell] (mapv :act/outcome fs)))))
  (testing "a dump that does not say which mode it rendered"
    (let [fs (judge* {:type "lv_obj" :children []} (payload [] :records 3))]
      (is (= [:draw-palette-mode-unknown] (mapv :invariant fs)))
      (is (= [:mode-not-serialised] (mapv :act/reason fs))))))

(deftest an-observer-that-saw-nothing-is-cantTell-not-clean
  (testing "measured over the shipped corpus in both modes, the smallest
            observed record count is 1 and no render produced an empty colour
            set — so zero records means the observer was not watching, which
            must not be reported with the same empty vector as a clean card.
            REVERT-TO-BREAK: delete the `(zero? (long (:records draw-palette)))`
            branch. This fails (an empty live vector); the CONTROL below stays
            green, so the survivor is the empty-observation clause and not the
            unavailable one above it."
    (let [fs (judge* (tree true) (payload [] :records 0))]
      (is (= [:draw-palette-empty] (mapv :invariant fs)))
      (is (= [:no-colour-bearing-task] (mapv :act/reason fs)))
      (is (= [:cantTell] (mapv :act/outcome fs)))))
  (testing "CONTROL: a positive record count with every colour in the table is
            genuinely clean, so the branch above cannot be firing on all input"
    (is (empty? (judge true [{:hex "#111111" :theme_recolor false}])))))

(deftest malformed-thresholds-fail-registration-resolution
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"rejected value"
       (judge* (tree true) (payload []) {:dark #{"#111111"}}))))

(deftest observer-source-pins-the-two-lvgl-traps
  (let [source (slurp (io/file "../../renderer/src/palette_observer.c"))]
    (testing "evaluate_cb never creates or takes a task — the trap
              .claude/rules/renderer.md records as invisible to the goldens"
      (doseq [forbidden ["lv_draw_add_task("
                         "lv_draw_finalize_task_creation("
                         "lv_draw_get_available_task("
                         "lv_draw_get_next_available_task("]]
        (is (not (str/includes? source forbidden)) forbidden)))
    (testing "the observer supplies a non-null idle dispatcher — a NULL one is
              an unguarded indirect call, which traps under wasm"
      (is (str/includes? source "observer_unit->dispatch_cb = dispatch_cb;"))
      (is (str/includes? source "return LV_DRAW_UNIT_IDLE;")))
    (testing "partial-strip records are merged on type/object/area, never
              summed — the repeat multiplier is position-dependent, so no
              single correction factor rescues a sum"
      (is (str/includes? source "same_task_key"))
      (doseq [member ["task_type" "object" "x1" "y1" "x2" "y2"]]
        (is (str/includes? source member) member)))))

(defn- fn-body
  "The text between a definition's opening brace and its MATCHING close, found
   by brace depth. A fixed-width substring window would run past the end and
   vouch for a call that lives in the next function."
  [^String source ^String signature]
  (let [start (str/index-of source signature)
        open (when start (str/index-of source "{" start))]
    (when open
      (loop [i open, depth 0]
        (when (< i (count source))
          (let [c (.charAt source i)
                d (case c \{ (inc depth) \} (dec depth) depth)]
            (if (and (= c \}) (zero? d))
              (subs source open (inc i))
              (recur (inc i) d))))))))

(deftest the-observation-window-is-cleared-wherever-the-composite-can-change
  (testing "the producer selects ONE token table from the single theme_dark the
            root reports, so a record surviving an in-place theme switch is
            judged against the wrong table. Measured before these clears
            existed: every probed card leaked dark-exclusive colours into a
            dump labelled theme_dark=0, and the record count exactly doubled.
            REVERT-TO-BREAK: delete any one palette_observer_clear() call named
            below — only that site's assertion fails, so each red names its own
            entry point rather than the clearing idea in general."
    (let [main (slurp (io/file "../../renderer/src/main.c"))]
      (doseq [[sig window]
              [["int32_t controls_load_ui(uint32_t ptr, uint32_t len) {"
                "a full load"]
               ["static int32_t update_composite(void) {"
                "a breakpoint or dark/light change"]
               ["int32_t controls_set_theme_family(int32_t family) {"
                "a theme-family change"]]]
        (let [body (fn-body main sig)]
          (is (some? body) (str "could not locate the body of: " sig))
          (is (str/includes? (str body) "palette_observer_clear()")
              (str sig " must clear the observer — window: " window)))))))
