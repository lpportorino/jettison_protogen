(ns devcards.state-mirror
  "MIRROR GATE — the conventions manifest's `:widget-states` against the corpus
   spec's per-widget `:committed-states`.

   WHAT IT ASSERTS. Both files declare, per WidgetType, the states the asgard
   theme commits to rendering visually distinct. `corpus/README.md` calls the
   two lists MIRRORS; the manifest's own header adds that any state NOT listed
   must render IDENTICAL to `:default`. Neither is derived from the other, and
   neither can be: the corpus list rides beside the cards that prove it, the
   manifest list rides beside the render protocol a consumer vendors, and each
   is authored where its own readers are. What IS available is the equality,
   so the equality is what this gate holds.

   WHY IT IS A GATE AND NOT A CONVENTION. `devcards.docs` publishes the
   MANIFEST's copy as every widget page's \"Committed states\" list, and
   `devcards.conventions` projects the same map into the consumer-facing
   `conventions/ui-style-conventions.json`. Nothing compared the two, and the
   divergence that produced this gate had reached both published surfaces:
   three classes declared `[:default]` in the manifest while the corpus
   declared `[:default :disabled]` and carried five `:expect :distinct`
   disabled cards whose committed goldens differ from their baselines in both
   asgard families. The export therefore told a downstream producer that a
   state the theme really does style is inert — the manifest's inertness
   clause asserted over pixels that contradict it.

   TOTAL IN BOTH DIRECTIONS. A class in one file and not the other is a
   FINDING naming its direction, never a skip; so is a corpus class declaring
   no `:committed-states` at all. An unjudged element reported as clean is the
   silence this repo refuses everywhere else.

   ORDER IS PART OF THE MIRROR. The manifest header calls its value an ORDERED
   list and `devcards.docs` renders it in order into a published page, so a
   reordering on one side alone is real drift and is reported — as its own
   finding kind, distinct from a membership difference, because the two have
   different fixes.

   EXIT CODES — A FINDING AND A BROKEN GATE ARE DIFFERENT REDS. The split is
   `lint-gate.util`'s, unchanged, because a second exit vocabulary is a silent
   fork of the first:

     0  clean
     1  FINDINGS — the gate ran and reached a verdict about the tree. A FAIL.
     3  CANNOT RUN — a precondition failed: a file missing or unreadable, a
        side that parsed to an EMPTY map, a corpus class with no `:type`, a
        `:type` declared twice.

   Both block. They are split because a canary that accepted any non-zero code
   would accept a crash as proof that a clause fired.

   AN EMPTY SIDE IS A REFUSAL, NOT A PASS. Every clause here reports the
   subset that disagrees, so the clean value — no findings — is byte-identical
   to the nothing-was-read value. `verdict` therefore reports the COUNT it
   compared on every run, in both colours, so a reader can tell a real run
   from a collapsed one without reading the exit code.

   NO REQUIRE OUTSIDE `clojure.*`, deliberately. A namespace loads BEFORE
   `-main` runs, so a require that throws exits 1 — the FINDINGS code — and
   the gate's own red would then be a lie about the tree. `devcards.fixtures`
   is the case that matters: it owns the corpus-spec path AND drags the
   generated protobuf bindings, so requiring it here would both re-introduce
   that hazard and make this gate unloadable under the `:test` alias, which is
   the one suite that can name it. `corpus-spec-path` below therefore restates
   that literal, and `devcards.state-mirror-test` pins the two together by
   reading `fixtures.clj` as TEXT — the same instrument the suite already uses
   for `devcards.core`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def exit-ok
  "Clean: both declarations agree over a non-empty population."
  0)

(def exit-findings
  "A verdict ABOUT THE TREE: the two declarations disagree. A FAIL."
  1)

(def exit-cannot-run
  "A verdict about the GATE: a precondition failed, so no comparison happened.
   Blocks like a FAIL and is deliberately a different code, so a canary cannot
   accept a broken harness as proof that a clause fired."
  3)

(def conventions-path
  "The authored conventions manifest (tool-relative). Mirrors
   `devcards.conventions/edn-home`, which is its one home; the ns docstring
   records why this gate may not require that namespace, and
   `state-mirror-test` asserts the two values are equal."
  "conventions/ui-render-conventions.edn")

(def corpus-spec-path
  "The corpus spec (tool-relative). Mirrors `devcards.fixtures/spec-path`,
   which is its one home; see the ns docstring, and `state-mirror-test` pins
   the two by reading that file as text.

   THE DUPLICATION FAILS LOUD IN BOTH HOMES rather than silently: each side
   slurps its own path, so a moved corpus throws in `fixtures/load-spec` and
   refuses here with CANNOT RUN. Neither can go quietly green."
  "corpus/spec.edn")

(def ^:private ok-tag "[state-mirror] OK")
(def ^:private fail-tag "[state-mirror] FAIL")
(def ^:private refuse-tag "[state-mirror] CANNOT RUN")

(defn- read-edn
  "Read `path` as EDN. Returns `{:value form}` or `{:refusal headline :detail
   lines}`.

   A read failure is returned rather than thrown so the whole gate stays
   TOTAL: nothing above this can escape as an uncaught exception wearing the
   FINDINGS exit code."
  [path what]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:refusal (str "no " what " at " path)
       :detail ["This gate compares two AUTHORED declarations. With one of them"
                "absent there is nothing to compare, and a clean report would be"
                "a green tick over zero comparisons."]}
      (try
        {:value (edn/read-string (slurp f))}
        (catch Exception e
          {:refusal (str what " is unreadable at " path ": " (.getMessage e))
           :detail ["An unparseable declaration is not an empty one. Refusing keeps"
                    "a syntax error from reading as agreement."]})))))

(defn conventions-side
  "`{WidgetType -> ordered state vector}` read from the AUTHORED manifest at
   `path`, or a refusal.

   Read WITHOUT `devcards.conventions/load-conventions` on purpose: this gate
   judges what the FILE declares, and the loader folds LVGL-derived sections
   over it. `:widget-states` is authored-only, so the two agree today — but a
   gate satisfied by a loader's merge is a gate that has stopped reading its
   own subject."
  [path]
  (let [{:keys [value refusal detail]} (read-edn path "conventions manifest")
        states (when (map? value) (:widget-states value))]
    (cond
      refusal {:refusal refusal :detail detail}

      (not (map? states))
      {:refusal (str path " carries no :widget-states MAP")
       :detail ["The section IS this gate's left-hand side; without it there is"
                "nothing to compare against the corpus."]}

      (empty? states)
      {:refusal (str path "'s :widget-states is EMPTY")
       :detail ["An empty side compares nothing and would report clean. That is the"
                "vacuous pass this gate exists to make impossible."]}

      :else {:value states})))

(defn corpus-side
  "`{WidgetType -> committed state vector}` read from the corpus spec at
   `path`, or a refusal.

   A class declaring NO `:committed-states` key is carried as `::absent`
   rather than as nil, so the comparison can report it. Reading a missing
   declaration as nil would let it collide with a legitimately-empty list and
   be judged as if it had been declared."
  [path]
  (let [{:keys [value refusal detail]} (read-edn path "corpus spec")
        widgets (when (map? value) (:widgets value))
        types (map :type widgets)]
    (cond
      refusal {:refusal refusal :detail detail}

      (not (sequential? widgets))
      {:refusal (str path " carries no :widgets sequence")
       :detail ["The widget classes ARE this gate's right-hand side."]}

      (empty? widgets)
      {:refusal (str path "'s :widgets is EMPTY")
       :detail ["An empty side compares nothing and would report clean."]}

      (some nil? types)
      {:refusal (str path " has a widget class with no :type")
       :detail ["The gate keys both sides on WidgetType, so an untyped class cannot"
                "be compared — and dropping it silently would shrink the population"
                "this gate reports as its own coverage."]}

      (not= (count types) (count (distinct types)))
      {:refusal (str path " declares a WidgetType twice: "
                     (str/join ", " (sort (map name (keys (filter #(< 1 (val %))
                                                                  (frequencies types)))))))
       :detail ["Keying a map on a duplicated type silently collapses two classes"
                "into one, which understates the compared count rather than failing."]}

      :else {:value (into {} (for [w widgets] [(:type w) (get w :committed-states ::absent)]))})))

(defn- pair-finding
  "The finding for one class present in BOTH declarations, or nil when they
   agree. Three kinds, because the three have different fixes: an undeclared
   corpus list, a membership difference, and an order-only difference."
  [widget conv-states corpus-states]
  (cond
    (= ::absent corpus-states)
    {:kind :committed-states-undeclared
     :widget widget
     :direction "corpus -> conventions"
     :detail (str "conventions declares " (pr-str (vec conv-states))
                  "; the corpus widget class declares no :committed-states key at all"
                  " — an unjudgeable pair is a finding, never a skip")}

    (= (vec conv-states) (vec corpus-states)) nil

    (not= (set conv-states) (set corpus-states))
    {:kind :committed-states-differ
     :widget widget
     :direction "both"
     :detail (str "conventions " (pr-str (vec conv-states))
                  " vs corpus " (pr-str (vec corpus-states))
                  "; only in conventions "
                  (pr-str (vec (sort (set/difference (set conv-states) (set corpus-states)))))
                  ", only in corpus "
                  (pr-str (vec (sort (set/difference (set corpus-states) (set conv-states))))))}

    :else
    {:kind :committed-states-reordered
     :widget widget
     :direction "both"
     :detail (str "same states, different ORDER: conventions "
                  (pr-str (vec conv-states)) " vs corpus " (pr-str (vec corpus-states))
                  " — the manifest header calls this an ORDERED list and devcards.docs"
                  " publishes it in order, so the order is part of the mirror")}))

(defn findings
  "Every disagreement between the two declarations, both directions. PURE, and
   sorted so a diff of two runs is readable.

   The one-sided arms come FIRST because they are the ones a reader is most
   likely to have caused by adding a widget class to one file only."
  [conv corpus]
  (let [ck (set (keys conv))
        sk (set (keys corpus))]
    (vec
     (concat
      (for [w (sort (set/difference ck sk))]
        {:kind :class-absent-from-corpus
         :widget w
         :direction "conventions -> corpus"
         :detail (str "the conventions manifest declares " (pr-str (vec (get conv w)))
                      ", and the corpus spec declares no widget class of that type")})
      (for [w (sort (set/difference sk ck))]
        {:kind :class-absent-from-conventions
         :widget w
         :direction "corpus -> conventions"
         :detail (str "the corpus spec declares " (pr-str (get corpus w))
                      ", and the conventions manifest has no :widget-states row for it")})
      (keep #(pair-finding % (get conv %) (get corpus %))
            (sort (set/intersection ck sk)))))))

(defn- finding-line
  "One finding, rendered for the console."
  [{:keys [kind widget direction detail]}]
  (format "    %-32s %-24s [%s] %s" (name kind) (str widget) direction detail))

(defn verdict
  "THE GATE'S WHOLE DECISION — `{:lines [...] :exit n :compared n :findings
   [...]}`.

   TOTAL, and it neither prints nor exits. That is the whole reason it exists
   as its own function: a decision made inside `-main` is one no test can
   name, which this suite has already been bitten by once (see
   `devcards.lanes/run-verdict`). `-main` below only prints what this returns
   and exits with the code it returns.

   `:compared` is the number of classes present in BOTH declarations — the
   population actually compared, which is NOT the same as the number judged.
   It is reported in every colour so a collapsed run is visible without
   reading the exit code."
  [conv-path spec-path]
  (let [c (conventions-side conv-path)
        s (corpus-side spec-path)
        refused (or (when (:refusal c) c) (when (:refusal s) s))]
    (if refused
      {:exit exit-cannot-run
       :compared 0
       :findings []
       :lines (into [(str refuse-tag " — " (:refusal refused))]
                    (map #(str "  " %))
                    (:detail refused))}
      (let [conv (:value c)
            corpus (:value s)
            fs (findings conv corpus)
            compared (count (set/intersection (set (keys conv)) (set (keys corpus))))
            scope (format "%d class(es) compared (conventions %d, corpus %d)"
                          compared (count conv) (count corpus))]
        {:exit (if (seq fs) exit-findings exit-ok)
         :compared compared
         :findings fs
         :lines (if (seq fs)
                  (into [(format "%s — %d finding(s); %s" fail-tag (count fs) scope)]
                        (map finding-line)
                        fs)
                  [(format "%s — %s; :widget-states == :committed-states" ok-tag scope)])}))))

(defn run
  "The gate over `args`: zero paths compares the committed pair, two compares
   the pair given. Returns `verdict`'s map.

   ONE argument is REFUSED rather than half-defaulted. A gate that silently
   compared a fixture against the live corpus would return a verdict about
   neither pair, and its colour would be unattributable."
  [args]
  (let [[conv spec & extra] args]
    (if (or (seq extra) (and (some? conv) (nil? spec)))
      {:exit exit-cannot-run
       :compared 0
       :findings []
       :lines [(str refuse-tag " — usage: [<conventions.edn> <corpus-spec.edn>]")
               "  Zero arguments compares the committed pair; TWO compares the pair"
               "  given. One is refused: half-defaulting would compare a fixture"
               "  against the live corpus and report a verdict about neither."]}
      (verdict (or conv conventions-path) (or spec corpus-spec-path)))))

(defn -main
  "CLI entry. Holds NO decision: it prints what `run` returned and exits with
   the code `run` returned, so the whole verdict is nameable by a test.

   The catch re-labels a CRASH from `1` to `3`. The JVM exits 1 on an uncaught
   exception and 1 is the FINDINGS code, so without it a broken gate and a
   verdict about the tree are indistinguishable from outside. That is not
   masking — the stack trace is printed in full and the exit still BLOCKS; it
   is renamed from `verdict about the tree` to `this gate is broken`, which is
   the more accurate of the two."
  [& args]
  (try
    (let [{:keys [lines exit]} (run args)]
      (doseq [l lines] (println l))
      (System/exit exit))
    (catch Exception e
      (.printStackTrace e)
      (binding [*out* *err*]
        (println (str refuse-tag " — the gate itself crashed (stack trace above).")))
      (System/exit exit-cannot-run))))
