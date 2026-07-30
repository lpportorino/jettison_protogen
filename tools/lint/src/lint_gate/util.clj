(ns lint-gate.util
  "Shared machinery for the structural Clojure gates — reporting, refusal, the
  clj-kondo analysis seam, the generated-projection predicate, and the
  proof-carrying exemption contract.

  WHY A SHARED NAMESPACE RATHER THAN A COPY PER CHECK. Every one of these pieces
  is a place a gate can silently stop checking: a refusal that exits 0, an
  analysis that came back empty, an exemption that outlived its finding. Two
  copies of any of them is one copy that quietly stops refusing, and the failure
  mode is a green run.

  EXIT CODES — A FINDING AND A BROKEN GATE ARE DIFFERENT REDS.

    0  clean
    1  FINDINGS — the gate ran and reached a verdict about the tree. A FAIL.
    3  CANNOT RUN — a precondition failed (no clj-kondo, empty analysis, missing
       or malformed config). Still blocks, but it is not a verdict.

  Both block. They are split because a canary that accepted any non-zero code
  would accept a crash as proof that a clause fired, which is the
  ERROR-wearing-a-FAIL's-colour confusion `.claude/rules/gate-enforcement.md` §2
  forbids."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader)
   (java.lang ProcessBuilder$Redirect)
   (java.time LocalDate)
   (java.time.format DateTimeParseException)))

(def red "\033[31m")
(def green "\033[32m")
(def yellow "\033[33m")
(def off "\033[0m")

(def exit-ok 0)
(def exit-findings 1)
(def exit-cannot-run 3)

(defn cannot-run!
  "Print a CANNOT RUN diagnosis and exit 3.

  The headline is separate from the detail lines so every caller produces the same
  shape, which is what lets a canary match on a stable substring rather than on a
  whole message."
  [headline detail]
  (binding [*out* *err*]
    (println (str red "[clj-gate] CANNOT RUN" off " — " headline))
    (doseq [line detail]
      (println (str "  " line))))
  (System/exit exit-cannot-run))

(defn read-edn-config
  "Read a committed EDN config, or refuse.

  A gate's config IS the gate, so its absence can never read as 'no limit' — that
  would turn a deleted file into a silently disabled check, which is the same
  class as a gate passing because its tool is missing. `what` names the config in
  the diagnosis so the operator learns WHICH one went missing."
  [path what]
  (let [f (io/file path)]
    (when-not (.exists f)
      (cannot-run! (str "no " what " at " path ".")
                   [(str "The " what " IS the gate; without it there is nothing to")
                    "compare against, and defaulting to 'anything goes' would turn a"
                    "deleted config file into a silently disabled check."]))
    (try
      (edn/read-string (slurp f))
      (catch Exception e
        (cannot-run! (str what " unreadable at " path ": " (.getMessage e)) [])))))

(defn kondo-analysis
  "Run clj-kondo over `paths` and return its `:analysis` map.

  `--cache false` is not optional. clj-kondo's cache directory is shared with any
  editor or LSP that has run over this tree, and an analysis-shaped cache entry
  lacks var bodies — so the next run emits findings that describe the CACHE rather
  than the code. `lint.mk`'s lint-clj lane carries the same flag for the same
  reason.

  clj-kondo's own EXIT CODE IS IGNORED, and that is correct rather than lax: it
  exits non-zero when it has FINDINGS, which is `lint-clj`'s verdict to deliver,
  not this gate's. What cannot be tolerated is not being able to read an analysis
  at all, and that is caught by the parse here and by the vacuity guard below.

  stderr is INHERITED rather than captured, so a clj-kondo diagnosis reaches the
  operator verbatim instead of being swallowed into a parse error."
  [paths]
  (when-not (some #(.canExecute (io/file % "clj-kondo"))
                  (map io/file (str/split (or (System/getenv "PATH") "") #":")))
    (cannot-run! "clj-kondo is not on PATH."
                 ["This gate reads clj-kondo's --analysis output, so without the"
                  "binary there is nothing to judge — and a gate that passes"
                  "because its tool is absent is the defect it exists to prevent."
                  ""
                  "Install it: https://github.com/clj-kondo/clj-kondo#installation"
                  "CI pins it in .github/workflows/lint.yml's setup step."]))
  (let [pb (ProcessBuilder.
            (into ["clj-kondo" "--cache" "false"
                   "--config" "{:output {:analysis true :format :edn}}"
                   "--lint"]
                  paths))
        _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
        proc (.start pb)
        out (slurp (.getInputStream proc))]
    (.waitFor proc)
    (try
      (:analysis (edn/read-string out))
      (catch Exception e
        (cannot-run! (str "could not parse clj-kondo's analysis: " (.getMessage e))
                     ["The gate asked for `:format :edn`; if clj-kondo printed"
                      "something else, its own stderr is above."])))))

(defn assert-analysis-ran!
  "NON-VACUITY GUARD — an empty analysis must FAIL, never read as clean.

  Every check here has the same shape: enumerate a corpus, report the subset that
  violates something. So the clean value ('no findings') is byte-identical to the
  nothing-ran value, and an empty corpus buys a green tick over zero coverage.
  This repo tracks hand-authored Clojure, so an empty analysis means DISCOVERY
  BROKE — a wrong path list, a moved tree, a checkout git cannot resolve.

  BOTH HALVES ARE ASSERTED SEPARATELY rather than as a union: a corpus carrying
  namespaces but no var definitions would leave a var-oriented check vacuous while
  a namespace-oriented one still looked busy, and a union floor cannot see that."
  [analysis paths]
  (let [nsd (:namespace-definitions analysis)
        vds (:var-definitions analysis)]
    (when (or (empty? nsd) (empty? vds))
      (cannot-run!
       (format "analysis is EMPTY (namespaces=%d, var-definitions=%d)."
               (count nsd) (count vds))
       [(str "paths handed to clj-kondo: " (str/join " " paths))
        "This repo tracks hand-authored Clojure, so an empty analysis means"
        "DISCOVERY BROKE — a wrong or moved path — not that there is nothing"
        "to check. An empty corpus is a green tick over zero coverage."]))))

(defn generated?
  "True for a path inside a GENERATED projection.

  Emitter output whose bytes a battery lane asserts equal to a fresh extraction IS
  handed to cljfmt and clj-kondo on purpose — a projection must stay canonical and
  lint-clean, and regenerating it satisfies both. It is out of scope for the
  STRUCTURAL checks because no finding against it can be SATISFIED: you cannot
  hand-shrink, hand-document or hand-spec a file whose bytes are pinned to a
  generator's output, so the fix would always be in the generator.

  DERIVED FROM THE PATH, never a committed list of generated files: a list beside
  a live source is a second source that diverges silently."
  [path]
  (str/includes? path "/generated/"))

(defn clj-files
  "Every hand-authored Clojure file under `root`, generated projections excluded.

  `root` may be a FILE as well as a directory — `docs/.protodoc/tools/build.clj` is
  in the gated path list as a bare file, and a directory-only walk would drop it
  silently, which is a hole rather than a scope decision.

  `file-seq` over a MISSING root yields nothing rather than throwing, so this cannot
  refuse on its own. Every caller therefore floors the total it builds from this —
  an empty discovery is the vacuous-pass shape `.claude/rules/gate-enforcement.md`
  §3 forbids, and the floor is where it is caught."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(re-find #"\.clj[cs]?$" %))
       (remove generated?)
       sort))

(def ^:private docstring-bearing
  "Forms whose docstring sits immediately after the name symbol.

  `ns` is handled separately because its docstring may follow the name OR an
  attribute map."
  #{"defn" "defn-" "defmacro" "def" "defonce" "defprotocol" "defmulti"})

(defn docstring-lines
  "How many LINES the docstrings in `path` span, in total.

  Read with the Clojure reader rather than matched with a regex: a docstring is a
  STRING in a specific position, and a regex cannot tell one from a string
  argument, a string inside a collection literal, or a `;` that happens to sit
  inside one.

  WHICH WAY A READ FAILURE FAILS, because it must be said rather than assumed: an
  unparseable file yields 0, so its docstrings go uncounted and its LOC is
  OVER-stated. That can only produce a FALSE FAIL, never a false pass — and a
  false fail is loud and investigable, which is the safe direction for a ceiling.
  `lint-gate.specs` reports an unparseable file as a finding in its own lane."
  [path]
  (try
    (with-open [r (PushbackReader. (io/reader path))]
      (loop [n 0]
        (let [v (read {:eof ::eof :read-cond :allow} r)]
          (if (= v ::eof)
            n
            (recur
             (+ n (if (and (seq? v) (symbol? (first v)))
                    (let [head (name (first v))
                          doc (cond
                                (= head "ns") (first (filter string? (take 3 (rest v))))
                                (contains? docstring-bearing head)
                                (let [x (nth v 2 nil)] (when (string? x) x))
                                :else nil)]
                      (if doc (count (str/split-lines doc)) 0))
                    0)))))))
    (catch Exception _ 0)))

(defn code-loc
  "Lines in `path` that are neither blank, nor comment-only, NOR DOCSTRING.

  NARRATION IS FREE, deliberately — and that now includes docstrings, which it did
  not before. A size limit bounds how much BEHAVIOUR sits in one place; counting
  prose penalises the explanatory headers this repo relies on and pushes authors to
  delete the very thing that makes a large namespace survivable.

  DOCSTRINGS WERE THE OMISSION THAT MATTERED, because it put two gates in direct
  conflict: `lint-docstrings` REQUIRES a docstring on every function, and this
  metric then charged the namespace for it — so satisfying one gate could break the
  other. It did: adding the docstrings this repo's own gate demanded pushed the
  largest namespace 39 lines past a ceiling seeded at its own previous size.
  Measured, and it is not a rounding effect: that file carries 442 docstring lines
  against 1258 raw, so excluding prose moves it from the largest namespace in the
  tree to the fifth. The old metric was ranking documentation rather than behaviour.

  A missing file yields 0 rather than throwing: the analysis is the authority on
  what exists, and a race between it and the filesystem must not crash a gate."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      0
      (let [raw (with-open [r (io/reader f)]
                  (->> (line-seq r)
                       (map str/trim)
                       (remove #(or (str/blank? %) (str/starts-with? % ";")))
                       count))]
        (max 0 (- raw (docstring-lines path)))))))

;; ── the exemption contract ────────────────────────────────────────────────
;;
;; An exemption is a WAIVER for a TRUE FALSE POSITIVE, never a disabled rule, and
;; the four proof fields are what separate the two. The set and the horizon are
;; the ones `devcards.invariants/exemption-proof-keys` already enforces for the
;; ui_ast lanes — reused rather than re-invented, because a second exemption
;; vocabulary is a silent fork of the first.

(def proof-keys
  "The PROOF an exemption owes, independent of what it matches. All four are
  mandatory non-blank strings.

  :rationale and :retires-when are the ARGUMENT — why this is not a defect, and
  what EVENT makes the entry unnecessary. :owner and :expires are the
  ACCOUNTABILITY: an entry carrying only the first two is a decision with no
  author and no end.

  :retires-when survives the presence of :expires and merging them would lose the
  half that matters. The retirement CONDITION is a real-world event no machine
  here can evaluate; the DATE is the outer bound at which the decision is re-taken
  whether or not that event happened. Keeping only the date says when to look and
  not what for; keeping only the prose is what lets an exemption be permanent
  while claiming otherwise.

  WHAT :owner CANNOT SEE, said plainly so no pass message over-claims: the check
  is `non-blank string`. `:owner \"TODO\"` passes. What it buys is not
  verification — it is that the expiry failure has a name in it to route to."
  #{:rationale :retires-when :owner :expires})

(def horizon-days
  "The outer bound on :expires, in days from today. An exemption further out than
  this is refused: a waiver whose review date is beyond any plausible planning
  horizon is a permanent exemption wearing a date."
  90)

(defn- blank-string?
  "True when `v` is not a non-blank string — the test every proof field must pass.

  Non-strings are folded in on purpose: `:owner 42` and `:owner \"\"` are the same
  defect (a field carrying no answer), and splitting them would produce two
  diagnoses for one mistake."
  [v]
  (or (not (string? v)) (str/blank? v)))

(defn validate-exemptions!
  "Shape-check every exemption entry, or refuse. Returns the entries.

  `now` is a PARAMETER rather than a lookup because this is otherwise pure and its
  caller is a gate: a canary that could not pin the date would have to assert
  against a moving answer, which is how an expiry canary quietly stops being able
  to go red.

  EXPIRY AND HORIZON ARE SEPARATE FAILURES so neither masks the other — an entry
  can be both expired and beyond the horizon, and collapsing them would report
  one and hide the other."
  [entries match-keys what now]
  (let [allowed (into match-keys proof-keys)]
    (doseq [e entries]
      (when-not (map? e)
        (cannot-run! (str what ": an entry is not a map: " (pr-str e))
                     ["A bare value cannot carry the four proof fields."]))
      (let [missing (filter #(blank-string? (get e %)) proof-keys)
            unknown (remove allowed (keys e))]
        (when (seq missing)
          (cannot-run! (str what ": entry missing mandatory proof: "
                            (str/join ", " (sort (map name missing))))
                       [(str "entry: " (pr-str e))
                        "Every exemption owes :rationale, :retires-when, :owner and"
                        ":expires, each a non-blank string. An entry without them is"
                        "a disabled rule, not a waiver."]))
        (when (seq unknown)
          (cannot-run! (str what ": entry carries unknown key(s): "
                            (str/join ", " (sort (map str unknown))))
                       [(str "entry: " (pr-str e))
                        "The key set is CLOSED so a typo is refused rather than"
                        "silently widening what the entry swallows."]))
        (let [d (try (LocalDate/parse (str (:expires e)))
                     (catch DateTimeParseException _
                       (cannot-run! (str what ": :expires is not an ISO-8601 date: "
                                         (pr-str (:expires e)))
                                    [(str "entry: " (pr-str e))])))]
          (when (.isBefore d now)
            (cannot-run! (str what ": entry EXPIRED on " d)
                         [(str "entry: " (pr-str e))
                          "Re-take the decision: fix the finding, or renew the entry"
                          "with a fresh rationale. An expired waiver is not a waiver."]))
          (when (.isAfter d (.plusDays now horizon-days))
            (cannot-run! (str what ": :expires is beyond the " horizon-days
                              "-day horizon: " d)
                         [(str "entry: " (pr-str e))
                          "A review date beyond any plausible planning horizon is a"
                          "permanent exemption wearing a date."])))))
    entries))

(defn assert-no-stale!
  "Refuse when an exemption matched no live finding.

  This is the DOWN-ONLY half of the ratchet, and without it the whole contract
  leaks: an entry outlives the finding it excused, silently widening the unchecked
  surface, and an offender can be fixed while its excuse stays behind. `used` is
  the set of entries that matched something this run."
  [entries used what]
  (let [stale (remove used entries)]
    (when (seq stale)
      (cannot-run!
       (format "%s: %d exemption(s) matched NO live finding." what (count stale))
       (concat
        (map #(str "  stale: " (pr-str (select-keys % [:file :name]))) stale)
        ["Each excuses something that no longer exists, so it is now widening the"
         "unchecked surface for nothing. DELETE it. This is the down-only half of"
         "the ratchet: an offender cannot be fixed while its excuse stays behind."])))))

(defn report-findings!
  "Print findings under a heading and return the count. Empty prints nothing.

  `detail` is appended to the headline and is not decoration: it is where a check
  says WHICH threshold or clause was breached and what its value is. Without it a
  reader gets a count and has to go read the config to learn what the bound was —
  and a canary can only assert that something failed, not that the right clause
  did. The 3-arity exists for checks whose per-finding line already carries that."
  ([label findings render] (report-findings! label findings render nil))
  ([label findings render detail]
   (when (seq findings)
     (binding [*out* *err*]
       (println (str red "[clj-gate:" label "] FAIL" off
                     (format " — %d finding(s)" (count findings))
                     (if detail (str " " detail) "") ":"))
       (doseq [f (sort-by (juxt :file :line) findings)]
         (println (str "    " (render f))))))
   (count findings)))
