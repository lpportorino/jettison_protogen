(ns lvgl-codegen.pdl-t0-test
  "Guards for the T0 input-surface manifest.

   TWO LEGS, AND THE SPLIT IS DELIBERATE. Hand-built surface maps pin the
   PRICING rules — they are fast, and they can construct a case the tree does
   not contain (an all-inputs-present clause with no in-tree floor). But a
   suite built only on hand maps asserts the author's model of the dump
   vocabulary rather than the vocabulary, which is the failure mode this repo
   names explicitly. So every structural rule is ALSO exercised against the
   surface parsed from the real `renderer/src/main.c` and `renderer/wasm.mk`,
   and the two legs are labelled so a red says which one moved.

   NO EXPECTED VALUE IS PINNED BY HAND. The suite asserts relationships —
   a listed-missing input really is absent, an exercisable clause's inputs
   really are present — never a count or a key list transcribed from a run.
   A transcribed set would go green on a mirror and could not detect the drift
   the parse exists to catch.

   The suite never writes a file and never touches the renderer tree."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.pdl-t0 :as t0])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private repo-root t0/repo-root-default)

(defn- tokens []
  (edn/read-string (slurp (File. "edn/tokens.edn"))))

;; Parsed ONCE: `font-metric-fields` reads every compiled table, so a per-test
;; call would re-parse them for each assertion.
(def ^:private real-surface
  (delay (t0/input-surface {:repo-root repo-root :tokens (tokens)})))

(def ^:private real-manifest
  (delay (t0/t0-manifest {:repo-root repo-root :tokens (tokens)})))

(defn- by-id [manifest id]
  (first (filter #(= id (:id %)) (:clauses manifest))))

;; ── leg 1: the parse is a parse, not a mirror ───────────────────────────────

(deftest dump-vocabulary-is-parsed-from-the-interpreter
  (testing "the real dump_obj yields a non-trivial key set"
    (let [v (:dump-keys @real-surface)]
      (is (< 1 (count v))
          "dump_obj emits more than one key; a singleton means the parse collapsed")
      (is (contains? v "coords")
          "`coords` is emitted by every node and must survive the parse")
      (is (contains? v "children")
          "`children` is the recursion key and must survive the parse")))

  (testing "keys that ONLY reach the dump through tree_append_color/opa are found"
    ;; REVERT-TO-BREAK: in `dump-key-vocabulary`, change
    ;;   (concat inline helper)  ->  inline
    ;; These keys never appear in a format string — a format-string-only parse
    ;; reports every colour and opacity quantity absent, which reads exactly
    ;; like an honest negative. CONTROL: `exported-symbols` below stays green,
    ;; so the red is attributable to this union and not to file access.
    (let [v (:dump-keys @real-surface)]
      (doseq [k ["text_color" "bg_color" "opa" "text_opa" "bg_opa"]]
        (is (contains? v k)
            (str "`" k "` is passed as an ARGUMENT to tree_append_color/opa and "
                 "appears in no format string; it is absent from the parsed "
                 "vocabulary, so the helper-call pattern stopped matching")))))

  (testing "the boundary is dump_obj's BODY — `truncated` is emitted outside it"
    ;; The docstring records this as a known, deliberate edge. Pinning it here
    ;; keeps the two halves of the claim together: the key really is absent
    ;; from the vocabulary AND it really is in the file, so its absence is the
    ;; function cut and not a parse that stopped matching. If a future
    ;; `dump_obj` emits it directly, this reds and the docstring is stale.
    ;; The discriminator must be the KEY `\"truncated\":`, never the bare word:
    ;; dump_obj emits `text_truncated`, so a `#"truncated"` search matches the
    ;; body and the assertion would pass with the boundary claim untested.
    (let [sentinel #"\\\"truncated\\\":"
          whole-file (slurp (File. (str repo-root "/" t0/main-c-path)))]
      (is (not (contains? (:dump-keys @real-surface) "truncated"))
          "`truncated` is written by controls_dump_tree, after the walk returns")
      (is (re-find sentinel whole-file)
          (str "main.c emits no `truncated` key at all — the truncation membrane "
               "moved and the docstring's account of the boundary is wrong"))
      (is (not (re-find sentinel (t0/dump-obj-body repo-root)))
          (str "dump_obj now emits `truncated` itself, so the key is no longer "
               "outside the parse boundary and the docstring is stale")))
    (is (not-any? (:dump-keys @real-surface) ["records" "colors" "hex" "theme_recolor"])
        (str "draw-palette keys leaked into the TREE vocabulary; the palette is a "
             "property of the route to a screen and rides its own export")))

  (testing "a renamed dump_obj is refused, never silently emptied"
    (let [tmp (doto (File/createTempFile "pdl-t0-root" "") .delete)
          src (File. tmp "renderer/src")]
      (.mkdirs src)
      (spit (File. src "main.c") "static void dump_something_else(void) {\n}\n")
      (is (thrown? clojure.lang.ExceptionInfo (t0/dump-key-vocabulary (.getPath tmp)))
          "a main.c without the dump_obj signature must throw, not return #{}"))))

(deftest export-surface-is-the-linkers-list
  ;; CONTROL for the vocabulary mutation above: this leg reads a different file
  ;; through a different pattern, so it stays green when the union breaks.
  (testing "wasm.mk yields the exported symbols"
    (let [e (:exports @real-surface)]
      (is (< 1 (count e)) "the link line exports more than one symbol")
      (is (contains? e "controls_dump_tree")
          "the tree export is the surface every dump-key clause ultimately reads")))

  (testing "a wasm.mk with no --export is refused"
    (let [tmp (doto (File/createTempFile "pdl-t0-root" "") .delete)
          rend (File. tmp "renderer")]
      (.mkdirs rend)
      (spit (File. rend "wasm.mk") "LDFLAGS := -Wl,--initial-memory=8388608\n")
      (is (thrown? clojure.lang.ExceptionInfo (t0/exported-symbols (.getPath tmp)))
          "an export list that parsed to nothing must throw, not return #{}"))))

(deftest font-metric-fields-are-measurements-not-provenance
  (let [f (:font-metrics @real-surface)]
    (is (contains? f :line-height)
        "a field the emitter measures on compiled tables must be offered as an input")
    (is (not (contains? f :metrics-from))
        (str "`metrics-from` is a PATH, not a measurement — offering it as an input "
             "would let a clause satisfy a metric requirement with provenance"))
    (is (not (contains? f :resolution))
        "`resolution` names which arm answered, not what the face measures")))

;; ── leg 2: the pricing rules, on constructed surfaces ───────────────────────

(def ^:private full-surface
  "A surface where every input a synthetic clause below names DOES resolve, so
   a NOT-EXERCISABLE verdict can only come from the rule under test."
  {:dump-keys #{"present_key"}
   :exports #{"present_export"}
   :font-metrics #{:present-metric}})

(defn- clause [& {:as overrides}]
  (merge {:id :synthetic
          :quantity "q"
          :floor "f"
          :provenance :in-tree
          :source "docs/UI-QUALITY-CONTRACTS.md §0"
          :status :in-scope
          :verdict-shape :exact
          :inputs [{:kind :dump-key :name "present_key"}]}
         overrides))

(deftest a-floor-with-no-in-tree-source-can-never-be-exercisable
  ;; REVERT-TO-BREAK: in `price-clause`, change
  ;;   unsourced? (not= :in-tree provenance)  ->  unsourced? false
  ;; The clause below has EVERY input present, so nothing else can hold it
  ;; back — the red is attributable to the floor rule alone and not to a
  ;; neighbouring input check that would have refused the same clause anyway.
  (testing "all inputs present, floor sourced only outside this tree"
    (let [p (t0/price-clause full-surface (clause :provenance :brief :source nil))]
      (is (= :not-exercisable (:disposition p))
          (str "a clause whose number exists only outside this repository priced "
               "as exercisable; a gate built on it would check an unsourced constant"))
      (is (= [] (:missing p))
          "no input is missing — the refusal must come from the floor, not the inputs")
      (is (some #{:floor-not-sourced-in-tree} (:reasons p))
          "the refusal must NAME the floor as its reason")))

  (testing "CONTROL: the same clause with an in-tree floor is exercisable"
    (is (= :exercisable (:disposition (t0/price-clause full-surface (clause)))))))

(deftest a-declared-absence-never-resolves
  ;; REVERT-TO-BREAK: in `resolve-input`, change the `:none` branch
  ;;   :none false  ->  :none true
  ;; CONTROL: the in-tree/dump-key clause in the previous deftest stays
  ;; exercisable, so this red is the `:none` branch and not the pricing chain.
  (testing ":kind :none is a declared absence, not a lookup that failed"
    (let [p (t0/price-clause full-surface
                             (clause :inputs [{:kind :none :name "nothing reports it"}]))]
      (is (= :not-exercisable (:disposition p))
          "a clause whose only input is a declared absence priced as exercisable")
      (is (= 1 (count (:missing p)))
          "the declared absence must be REPORTED as missing, not silently dropped")
      (is (some #{:inputs-absent} (:reasons p))))))

(deftest out-of-scope-is-a-third-answer-not-a-blocked-one
  (testing "an out-of-scope clause never reports as not-exercisable"
    (let [p (t0/price-clause full-surface (clause :status :out-of-scope))]
      (is (= :out-of-scope (:disposition p))
          (str "out-of-scope collapsed into a blocked verdict; the quality contract "
               "is explicit that PENDING and OUT are different answers")))))

(deftest each-input-kind-resolves-against-its-own-surface
  (testing "a kind resolves only against the surface that owns it"
    (is (= :exercisable
           (:disposition (t0/price-clause
                          full-surface
                          (clause :inputs [{:kind :dump-export :name "present_export"}])))))
    (is (= :exercisable
           (:disposition (t0/price-clause
                          full-surface
                          (clause :inputs [{:kind :font-metric :name :present-metric}])))))
    (is (= :not-exercisable
           (:disposition (t0/price-clause
                          full-surface
                          ;; an export name looked up on the dump-key surface
                          (clause :inputs [{:kind :dump-key :name "present_export"}]))))
        "surfaces must not be pooled — a symbol is not a dump key")))

(deftest malformed-clauses-are-refused-loudly
  ;; REVERT-TO-BREAK (empty-inputs leg): in `validate-clause!`, delete the
  ;; `(when (empty? inputs) …)` form. The clause then prices EXERCISABLE by
  ;; naming nothing, which is the silent direction.
  (testing "a clause that declares no inputs is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (t0/price-clause full-surface (clause :inputs [])))
        "an empty :inputs vector would price as exercisable by default"))

  (testing "an unknown input kind is refused rather than resolving to absent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (t0/price-clause full-surface
                                  (clause :inputs [{:kind :dump-keys :name "present_key"}])))
        "a typo'd kind must throw; as a missing input it would silently retire a clause"))

  (testing "an in-tree floor with no citation is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (t0/price-clause full-surface (clause :source "  ")))
        "an uncitable in-tree floor is an unsourced constant wearing a source field"))

  (testing "an unknown provenance or status is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (t0/price-clause full-surface (clause :provenance :vibes))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (t0/price-clause full-surface (clause :status :maybe))))))

;; ── leg 3: the shipped register, against the real surface ───────────────────

(deftest shipped-clauses-price-against-the-real-interpreter
  (let [m @real-manifest]
    (testing "every shipped clause carries a disposition from the closed set"
      (doseq [c (:clauses m)]
        (is (contains? #{:exercisable :not-exercisable :out-of-scope} (:disposition c))
            (str (:id c) " priced outside the closed disposition set"))))

    (testing "an EXERCISABLE clause's inputs really are on their surfaces"
      ;; This is the anti-drift assertion: the register cannot claim an input
      ;; the interpreter stopped emitting and still price the clause green.
      (doseq [c (:clauses m) :when (= :exercisable (:disposition c))
              {:keys [kind] input-name :name} (:inputs c)]
        (is (case kind
              :dump-key (contains? (:dump-keys @real-surface) (str input-name))
              :dump-export (contains? (:exports @real-surface) (str input-name))
              :font-metric (contains? (:font-metrics @real-surface) (keyword input-name))
              :none false)
            (str (:id c) " prices EXERCISABLE but its " kind " input `" input-name
                 "` is not on that surface — the interpreter moved and this "
                 "register is stale"))))

    (testing "an input reported MISSING really is absent from its surface"
      ;; The other direction, and the one that keeps the manifest's own
      ;; evidence honest: a `:missing` entry that has since appeared would
      ;; leave the clause's stated reason describing a tree that no longer
      ;; exists.
      (doseq [c (:clauses m)
              {:keys [kind] input-name :name} (:missing c)]
        (is (not (case kind
                   :dump-key (contains? (:dump-keys @real-surface) (str input-name))
                   :dump-export (contains? (:exports @real-surface) (str input-name))
                   :font-metric (contains? (:font-metrics @real-surface) (keyword input-name))
                   :none false))
            (str (:id c) " reports its " kind " input `" input-name "` missing, but it "
                 "is present — revisit the clause; its recorded evidence is stale"))))

    (testing "the flash-rate clause can never report a pass"
      (let [p (by-id m :flash-rate)]
        (is (= :not-exercisable (:disposition p))
            "the flash-rate clause priced as something other than NOT-EXERCISABLE")
        (is (= 2 (count (:reasons p)))
            (str "flash-rate is refused for TWO independent reasons — no input AND "
                 "no in-tree floor — and either alone must be sufficient"))))

    (testing "the two refusal reasons are INDEPENDENT, not one reason twice"
      ;; A register whose only not-exercisable clause tripped both reasons could
      ;; not tell them apart, and either could quietly become load-bearing alone.
      ;; This clause has a properly sourced in-tree floor and fails on inputs
      ;; ONLY, so the reason vectors must differ.
      (let [border (by-id m :non-text-boundary-contrast)
            flash (by-id m :flash-rate)]
        (is (= :not-exercisable (:disposition border)))
        (is (= [:inputs-absent] (:reasons border))
            (str "the non-text boundary clause cites an in-tree floor, so its ONLY "
                 "refusal reason must be its absent inputs"))
        (is (not= (:reasons border) (:reasons flash))
            "both not-exercisable clauses give the same reasons — they are not independent")))

    (testing "the manifest reports its own surfaces, so a reader can re-derive"
      (is (seq (get-in m [:surface :dump-keys])))
      (is (seq (get-in m [:surface :dump-exports])))
      (is (seq (get-in m [:surface :font-metrics]))))))
