(ns lint-gate.fnsize
  "Function-grain structural metrics over hand-authored Clojure — LENGTH, NESTING
  DEPTH and DECISION COUNT — and the two-tier gate over them.

  WHY THE FORM TREE AND NOT clj-kondo'S ANALYSIS. The analysis carries
  `:row`/`:end-row`, which is enough for LENGTH and nothing else: nesting and
  decisions are properties of the SHAPE of a body, and the analysis does not
  expose bodies. Reading the source with `clojure.core/read` gets all three from
  one traversal, and `lint-gate.specs` already establishes that substrate here —
  so this costs no new dependency, which is the property that makes the
  `:lint-gate` alias cheap.

  WHY ONE NAMESPACE FOR THREE CHECKS. They share the traversal, the docstring
  subtraction and the population. Splitting them would mean parsing every file
  three times and, worse, three chances for the populations to drift apart — and a
  metric measured over a different corpus than it gates is the failure this repo
  keeps paying for.

  THE HEAD SETS ARE THE METRIC. Both `nesting-heads` and the decision table below
  are DEFINITIONS, not implementation detail: change one and every recorded
  ceiling becomes a number about a different quantity. Each carries the argument
  for what it includes and — more importantly — what it deliberately does not."
  (:require
   [clojure.string :as str]
   [lint-gate.util :as u])
  (:import
   (clojure.lang LineNumberingPushbackReader)
   (java.io StringReader)))

(def defn-heads
  "Heads that DEFINE a function whose body this gate measures.

  `def` is excluded even when its value is an `fn`: a `def`'d lambda is rare here
  and, more to the point, `lint-docstrings` scopes itself to the same three heads,
  so sharing the set keeps the two gates over ONE population. A population
  mismatch between two gates over the same corpus is how a finding ends up
  unreachable from either."
  #{"defn" "defn-" "defmacro"})

(def nesting-heads
  "Heads that add one level of NESTING DEPTH.

  WHAT THIS MEASURES: how many control decisions a reader must hold to know
  whether a given line runs. So the set is BRANCHING and ITERATION, plus `fn`
  because a closure body is a separate control context that executes elsewhere.

  WHAT IT DELIBERATELY EXCLUDES, and this is the whole difference from the donor
  survey's set: `let`, `do`, and the `with-*`/`binding` family. A `let` introduces
  names, not a condition — nothing about it changes WHETHER the next line runs. The
  donor counts `do`, `let` and `fn`, so in a `let`-heavy style a plain sequence of
  bindings costs depth before any branch exists, and its threshold reads far
  stricter than it is. Because the sets differ, the donor's NUMBER is not
  comparable to this one and is not borrowed even as a watchlist value; the
  watchlist here is seeded from this tree's own distribution instead, with its
  provenance recorded beside it in `tools/lint/gates.edn`.

  `catch` counts as well as `try`: a catch body is reached only on a throw, which
  is exactly a condition the reader must hold."
  #{"if" "if-not" "if-let" "if-some" "when" "when-not" "when-let" "when-some"
    "when-first" "cond" "condp" "case" "try" "catch" "loop" "doseq" "for"
    "dotimes" "while" "fn" "fn*"})

(defn- pair-arms
  "Decision count for a head whose body is a flat sequence of TEST/EXPR PAIRS."
  [args]
  (quot (count args) 2))

(defn- leading-plus-pairs
  "Arms of a dispatch head that takes `n` leading arguments, then TEST/EXPR pairs,
  then an OPTIONAL trailing default.

  Parameterised on `n` rather than written twice, because the two callers differ
  ONLY in that number and a copy is how they came to disagree."
  [n args]
  (let [tail (drop n args)]
    (+ (pair-arms tail) (rem (count tail) 2))))

(defn decisions
  "How many decision OUTCOMES the form headed by `head` with `args` contributes.

  ARMS, NOT HEADS — and that correction is the reason this metric exists in this
  shape. The donor survey's algorithm counts a decision head ONCE, so a
  forty-arm `case` scores 1 and a twelve-clause `cond` scores 1. This tree's
  largest namespaces are codegen dispatch tables, which is precisely the shape
  that scoring calls trivial: the metric would have been blind to the code it
  most needed to see. Counting arms is McCabe's own reading and it makes the
  number mean `how many distinct paths run through here`.

  The consequence is stated rather than hidden: counting arms makes this a
  DIFFERENT quantity from the surveyed donor's, so its threshold of 10 cannot be
  borrowed in either tier. See `nesting-heads` for the same argument at depth.

  Returns 0 for a head that is not a decision, so the caller can fold this over
  every form unconditionally."
  [head args]
  (case head
    ("if" "if-not" "if-let" "if-some"
          "when" "when-not" "when-let" "when-some" "when-first") 1
    ("cond" "cond->" "cond->>") (pair-arms args)
    ;; `case` takes ONE leading dispatch expression, `condp` takes TWO (a predicate
    ;; AND an expression), then pairs, then an optional default. An ODD remainder
    ;; after the leading args IS that default, and it is a reachable path, so it
    ;; counts.
    ;;
    ;; THE TWO CANNOT SHARE A CLAUSE, and sharing one is a bug this gate shipped
    ;; with: stripping a single leading arg from a `condp` leaves its expression in
    ;; the pair stream, so a no-default `condp` came out ONE TOO HIGH while a
    ;; with-default one was coincidentally right — which is why a canary over only
    ;; the defaulted form would have gone green.
    "case" (leading-plus-pairs 1 args)
    "condp" (leading-plus-pairs 2 args)
    ;; Short-circuiting: n arguments are n-1 places control can stop early.
    ("and" "or") (max 0 (dec (count args)))
    ;; Each `->`-threaded step of a `some->` is a nil test.
    ("some->" "some->>") (max 0 (dec (count args)))
    ;; A `catch` clause is one reachable path; `finally` is not a decision.
    "try" (count (filter #(and (seq? %) (= "catch" (str (first %)))) args))
    ("loop" "doseq" "for" "dotimes" "while") 1
    0))

(defn- head-name
  "The head symbol of `form` as a plain string, or nil when `form` is not a
  list headed by a symbol.

  `name` strips any namespace alias, so a qualified `clojure.core/when` and a bare
  `when` are the same head — which they are."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn walk-body
  "Fold `nesting` and `decisions` over the tree at `form`. Returns
  `{:depth n :decisions n}`, where `:depth` is the deepest nesting-head chain.

  `depth` is a MAXIMUM over branches and `decisions` is a SUM, because that is
  what each quantity means: depth is the worst place in the function, decision
  count is the whole function's path count.

  Traverses maps and vectors as well as lists — a `when` inside a map literal
  inside a vector is still a branch, and a list-only walk would miss the
  data-heavy shape this tree is full of."
  [form]
  (let [head (head-name form)
        here (if (contains? nesting-heads head) 1 0)
        dec-here (if head (decisions head (rest form)) 0)
        children (cond
                   (map? form) (concat (keys form) (vals form))
                   (coll? form) (seq form)
                   :else nil)
        sub (map walk-body children)]
    {:depth (+ here (apply max 0 (map :depth sub)))
     :decisions (+ dec-here (reduce + 0 (map :decisions sub)))}))

(defn- span-loc
  "Code lines in `lines` (0-indexed vector) over the inclusive 1-indexed row span
  `[from to]`, excluding blank and comment-only lines.

  The same filter `lint-gate.util/code-loc` applies at namespace grain, so the two
  size gates measure the same quantity at two scales rather than two quantities."
  [lines from to]
  (->> (subvec lines (dec from) (min (count lines) to))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % ";")))
       count))

(defn- docstring-of
  "The docstring of a `defn`-family `form`, or nil.

  Position 2 by convention, matching `lint-gate.util/docstring-lines`. A
  multi-arity `defn` puts its docstring in the same place, so no special case."
  [form]
  (let [x (nth (vec form) 2 nil)]
    (when (string? x) x)))

(defn form-metrics
  "Metrics for one `defn`-family `form` spanning rows `from`..`to` of `lines`.

  DOCSTRING LINES ARE SUBTRACTED, exactly as `lint-gate.util/code-loc` now does at
  namespace grain, and for the identical reason: `lint-docstrings` REQUIRES a
  docstring on every function in an enrolled root, so a length metric that charges
  for one puts two gates of this repo in direct conflict — satisfying either
  breaks the other. That conflict is not hypothetical; it reddened `lint-ns-size`
  once already."
  [path lines from to form]
  ;; `n-decisions` rather than `decisions`: a `:keys` destructure of that name would
  ;; SHADOW this namespace's own `decisions` fn. The keyword key stays `:decisions`
  ;; because it is data — `walk-body`'s return shape — and renaming a key to dodge a
  ;; shadow is the wrong half to move (`.claude/rules/lint-gates.md`).
  (let [doc (docstring-of form)
        {:keys [depth] n-decisions :decisions} (walk-body form)]
    {:file path
     :name (str (nth (vec form) 1 "?"))
     :line from
     :loc (max 0 (- (span-loc lines from to)
                    (if doc (count (str/split-lines doc)) 0)))
     :nesting depth
     :decisions n-decisions}))

(defn normalize-source
  "Rewrite `::alias/name` to `:alias/name` in Clojure source text.

  WHY THIS IS NEEDED AT ALL. `clojure.core/read` resolves an auto-resolved
  namespaced keyword against the aliases of the CURRENT namespace, so a file using
  `::some-alias/key` is unreadable from outside itself — `Invalid token`. The
  alternative substrates both cost more than this transform: resolving properly
  means creating a namespace and loading everything the file aliases, and declining
  the file means a gate that reports a real source file as UNJUDGED for a reason
  that has nothing to do with the metric.

  WHY IT IS SAFE HERE, stated as a property of what this gate reads rather than as
  a hope. Every metric in this namespace is a function of TREE SHAPE and LINE
  NUMBERS. This substitution changes neither: it removes one character from a token
  on the line it already occupied, so no newline is added or removed and no form
  boundary moves. Keyword IDENTITY does change — the result is a different keyword
  — and that is why this transform must never be reused by a check that reads
  keyword VALUES. `lint-gate.specs` reads schema values and correctly does not use
  it.

  It also fires inside string and regex literals. That likewise changes content and
  not shape, so it is harmless for this gate and, again, disqualifying for one that
  reads literals."
  [src]
  (str/replace src #"::([A-Za-z][A-Za-z0-9._*+!'?<>=-]*/)" ":$1"))

(defn file-metrics
  "Every `defn`-family definition in `path`, with its metrics. Throws on an
  unreadable file so the caller can report it as a FINDING rather than a skip.

  THE END ROW COMES FROM THE READER'S POSITION, not from metadata, because
  `clojure.core/read` attaches only `:line` and `:column` to a form — there is no
  `:end-line` to read, and assuming one yields nil and a crash.
  `LineNumberingPushbackReader/getLineNumber` after the read is the last line the
  form occupied, which is exactly the span wanted. `lint-gate.specs` documents the
  same absence and works around it differently, by grepping for the form's text.

  ONLY TOP-LEVEL DEFINITIONS ARE COLLECTED. A `defn` nested inside another form
  would otherwise be measured twice — once alone and once as part of its
  enclosing body — and the double count reads as a real finding. Nesting a
  `defn` is not idiomatic here, so the population lost is empty; the caller
  reports the total judged so an emptied population cannot pass silently."
  [path]
  (let [src (slurp path)
        lines (vec (str/split-lines src))]
    (with-open [r (LineNumberingPushbackReader.
                   (StringReader. (normalize-source src)))]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :allow} r)
              to (.getLineNumber r)]
          (if (= form ::eof)
            acc
            (recur (if-let [from (and (contains? defn-heads (head-name form))
                                      (:line (meta form)))]
                     (conj acc (form-metrics path lines from to form))
                     acc))))))))

(def axes
  "The three axes, each `[metric-key block-config-key warn-config-key label]`.

  ONE CHECK OVER THREE AXES rather than three checks, matching what `ns-size`
  already does with its two. They share the traversal, the docstring subtraction and
  the population, so splitting them would parse every file three times and — the
  part that actually bites — give the three populations three chances to drift
  apart. A metric measured over a different corpus than it gates is the failure this
  repo keeps paying for."
  [[:loc :loc-block :loc-warn "loc"]
   [:nesting :nesting-block :nesting-warn "nesting"]
   [:decisions :decisions-block :decisions-warn "decisions"]])

(defn breaches
  "The axis labels of `row` at or over `tier-key` in `conf`.

  BLOCK is asked with strictly-greater-than and WARN with at-or-over. That
  asymmetry is load-bearing rather than sloppy: a ceiling seeded at the measured
  maximum leaves the worst function AT the ceiling rather than over it, which is
  what makes a seeded ratchet green on arrival with ZERO exemptions."
  [row conf tier-key]
  (let [over? (if (= tier-key :block) > >=)]
    (for [[mk bk wk label] axes
          :let [bound (get conf (if (= tier-key :block) bk wk))]
          :when (and bound (over? (get row mk 0) bound))]
      label)))

(defn- report-watchlist!
  "Print the non-blocking DEGRADED tier. Reported EVERY run, never blocking.

  This is what keeps the distance between today's worst and where the bar should be
  visible, instead of a seeded ceiling quietly redefining the current maximum as
  fine. `.claude/rules/gate-enforcement.md` §1 makes it a condition of the ratchet
  being legitimate at all."
  [rows conf]
  (let [watch (filter #(and (empty? (breaches % conf :block))
                            (seq (breaches % conf :warn)))
                      rows)]
    (when (seq watch)
      (println (format "%s[clj-gate:fn-size]%s DEGRADED watchlist — %d function(s) at/over loc %d, nesting %d or decisions %d (does not block):"
                       u/yellow u/off (count watch)
                       (:loc-warn conf) (:nesting-warn conf) (:decisions-warn conf)))
      (doseq [r (take 10 (sort-by (comp - :loc) watch))]
        (println (format "    loc=%4d nest=%2d dec=%3d  [%s]  %s:%d %s"
                         (:loc r) (:nesting r) (:decisions r)
                         (str/join "," (breaches r conf :warn))
                         (:file r) (:line r) (:name r))))
      (when (> (count watch) 10)
        (println (format "    … and %d more" (- (count watch) 10)))))))

(defn check
  "Function-grain size gate over `roots`. Returns the BLOCKING finding count.

  AN UNPARSEABLE FILE IS A FINDING, NEVER A SKIP. A file the reader cannot handle
  contributes no functions, so passing over it would report `clean` and `I could not
  look` as the same empty vector — the third answer this repo's standard demands out
  loud everywhere else.

  THE NON-VACUITY FLOOR IS OVER FUNCTIONS, and it is per-root rather than a union:
  any populated root would satisfy a union floor, so one root going dark is
  invisible to it."
  [roots conf]
  (when (empty? roots)
    (u/cannot-run! "no roots configured for fn-size."
                   ["Discovery is the caller's job; an empty root list would judge"
                    "nothing and report clean."]))
  (let [by-root (into {} (for [r roots] [r (u/clj-files r)]))
        dark (keep (fn [[r fs]] (when (empty? fs) r)) by-root)]
    (when (seq dark)
      (u/cannot-run! (format "%d configured root(s) matched NO Clojure file."
                             (count dark))
                     (concat (map #(str "  dark root: " %) dark)
                             ["Floored PER ROOT, not as a union: any populated root"
                              "would satisfy a union floor while this one sat dark,"
                              "and a gate over zero files reports a perfect score."])))
    (let [files (mapcat val by-root)
          read-results (map (fn [p] [p (try {:rows (file-metrics p)}
                                            (catch Exception e {:error (.getMessage e)}))])
                            files)
          unparseable (for [[p {:keys [error]}] read-results :when error]
                        {:file p :line 0 :name "<unreadable>" :error error})
          rows (vec (mapcat (comp :rows second) read-results))]
      (when (empty? rows)
        (u/cannot-run! (format "no functions found in %d file(s)." (count files))
                       ["This repo tracks hand-authored Clojure, so zero functions"
                        "means DISCOVERY BROKE, not that there is nothing to check."]))
      (report-watchlist! rows conf)
      (let [over (filter #(seq (breaches % conf :block)) rows)
            findings (u/report-findings!
                      "fn-size"
                      (concat unparseable (map #(assoc % :line (:line %)) over))
                      (fn [r]
                        (if (:error r)
                          (format "UNREADABLE %s — %s" (:file r) (:error r))
                          (format "loc=%4d nest=%2d dec=%3d  [%s over]  %s:%d %s"
                                  (:loc r) (:nesting r) (:decisions r)
                                  (str/join "," (breaches r conf :block))
                                  (:file r) (:line r) (:name r))))
                      (format "over ceiling (loc>%d, nesting>%d or decisions>%d)"
                              (:loc-block conf) (:nesting-block conf)
                              (:decisions-block conf)))]
        (when (pos? findings)
          (binding [*out* *err*]
            (println "  SPLIT THE FUNCTION. Do NOT raise the ceiling — it only ever")
            (println "  moves DOWN, and raising one is a gate bypass in source form.")))
        (when (zero? findings)
          (println (format "%s[clj-gate:fn-size]%s %d function(s) in %d file(s) under the ceiling (max loc=%d, nesting=%d, decisions=%d; ceilings %d/%d/%d)"
                           u/green u/off (count rows) (count files)
                           (apply max 0 (map :loc rows))
                           (apply max 0 (map :nesting rows))
                           (apply max 0 (map :decisions rows))
                           (:loc-block conf) (:nesting-block conf)
                           (:decisions-block conf))))
        findings))))
