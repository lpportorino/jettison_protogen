(ns lint-gate.core
  "Structural quality gates over this repo's HAND-AUTHORED Clojure — the dispatch,
  and the namespace-size check.

  WHAT THESE ADD THAT THE OTHER LANES CANNOT. `lint.mk` gates hand-authored
  Clojure for FORMATTING (cljfmt) and for LINT (clj-kondo's own rule set). Neither
  can answer whether a namespace has grown too big to hold in one head, whether a
  function says what it is for, or whether a declared schema names any shape at
  all. Those are separate questions over the same corpus, so they are separate
  `--check` names over one analysis.

  WHY CLOJURE AND NOT A SCRIPT LANGUAGE. A gate belongs in a language this repo
  already gates. Under `LINT_CLJ_PATHS` these files are covered by cljfmt, by
  clj-kondo at the zero-warning floor in `.clj-kondo/config.edn`, and by this
  namespace's own ceiling — they dogfood, and a regression in the gate reddens the
  lanes the gate enforces. `.claude/rules/gate-enforcement.md` §5 is the law; the
  evidence that it earns its keep is that clj-kondo caught a dead private var and
  three `clojure.core` shadowings in these very files on their first runs.

  IT COSTS NO DEPENDENCY. clj-kondo emits its analysis as EDN and `clojure.edn` is
  standard library, so the `:lint-gate` alias declares no `:extra-deps` at all.

  Shared machinery, the exemption contract and the exit-code split live in
  `lint-gate.util`; each check is its own namespace so none of them grows into the
  thing this gate exists to prevent."
  (:require
   [clojure.string :as str]
   [lint-gate.docstrings :as docstrings]
   [lint-gate.fnsize :as fnsize]
   [lint-gate.presence :as presence]
   [lint-gate.specs :as specs]
   [lint-gate.util :as u])
  (:import
   (java.time LocalDate)))

(def ^:private config-path "tools/lint/gates.edn")
(def ^:private exemptions-path "tools/lint/exemptions.edn")

(defn namespace-rows
  "One row per hand-authored namespace: `{:ns :file :loc :publics}`.

  The public-var count comes from the analysis rather than from the source, so it
  counts what clj-kondo RESOLVED rather than what a regex guessed at."
  [analysis]
  (let [publics (->> (:var-definitions analysis)
                     (remove :private)
                     (group-by :filename))]
    (for [nd (:namespace-definitions analysis)
          :let [file (:filename nd)]
          :when (not (u/generated? file))]
      {:ns (:name nd)
       :file file
       :loc (u/code-loc file)
       :publics (count (get publics file))})))

(defn tier
  "Classify one row against `conf` — `:block`, `:warn` or `:ok`.

  The STRICTER of the two axes wins. BLOCK is strictly-greater-than, so a ceiling
  seeded at the measured maximum leaves that namespace AT the ceiling rather than
  over it — which is what makes a seeded ratchet green on arrival with zero
  exemptions, and is therefore load-bearing rather than incidental."
  [{:keys [loc publics]} conf]
  (cond
    (or (> loc (:loc-block conf)) (> publics (:publics-block conf))) :block
    (or (>= loc (:loc-warn conf)) (>= publics (:publics-warn conf))) :warn
    :else :ok))

(defn- report-watchlist!
  "Print the non-blocking DEGRADED tier.

  Reported EVERY run and never blocking. That is what keeps the distance between
  today's worst and where the bar should be visible, instead of a seeded ceiling
  quietly redefining the current maximum as fine."
  [rows conf]
  (let [watch (filter #(= :warn (tier % conf)) rows)]
    (when (seq watch)
      (println (format "%s[clj-gate:ns-size]%s DEGRADED watchlist — %d namespace(s) at/over %d LOC or %d publics (does not block):"
                       u/yellow u/off (count watch) (:loc-warn conf) (:publics-warn conf)))
      (doseq [{:keys [file loc publics]} (take 10 (sort-by (comp - :loc) watch))]
        (println (format "    loc=%5d publics=%3d  %s" loc publics file)))
      (when (> (count watch) 10)
        (println (format "    … and %d more" (- (count watch) 10)))))))

(defn check-ns-size
  "Namespace size — two axes, two tiers. Returns the BLOCKING finding count.

  TWO AXES, because they fail differently. `code-LOC` bounds how much there is to
  read; the public-var count bounds how wide the namespace's contract is. A
  300-line namespace exporting 60 functions is a different problem from a
  1200-line one exporting 2, and one number cannot express both.

  TWO TIERS, and the seeding is the design decision. Each blocking ceiling is
  SEEDED AT THIS TREE'S MEASURED MAXIMUM, with its provenance recorded beside it in
  the config, so the gate is green on arrival with ZERO exemptions and nothing may
  become worse than today's worst. The alternative — a borrowed aspirational
  threshold that lands red — needs a proof-carrying waiver per outlier, and a dozen
  invented `:rationale` strings is strictly worse than one honest number because it
  corrupts the field the whole exemption machinery depends on being true.
  `.claude/rules/gate-enforcement.md` §1 carries the enumerability test that makes
  this a legitimate ratchet rather than a parked finding."
  [analysis conf]
  (let [rows (vec (namespace-rows analysis))]
    (when (empty? rows)
      (u/cannot-run! "no hand-authored namespaces survived the generated filter."
                     ["The analysis was non-empty, so an empty row set means the"
                      "generated-path exclusion swallowed the whole corpus. A gate"
                      "over zero namespaces reports clean and proves nothing."]))
    (report-watchlist! rows conf)
    (let [over (filter #(= :block (tier % conf)) rows)
          findings (u/report-findings!
                    "ns-size" (map #(assoc % :line 0) over)
                    (fn [{:keys [file loc publics]}]
                      (format "loc=%5d publics=%3d  %s" loc publics file))
                    (format "over ceiling (loc>%d or publics>%d)"
                            (:loc-block conf) (:publics-block conf)))]
      (when (pos? findings)
        (binding [*out* *err*]
          (println "  SPLIT THE NAMESPACE. Do NOT raise the ceiling — it only ever")
          (println (str "  moves DOWN, and raising one in " config-path))
          (println "  is a gate bypass in source form.")))
      (when (zero? findings)
        (println (format "%s[clj-gate:ns-size]%s %d namespace(s) under the ceiling (max loc=%d, max publics=%d; ceilings %d/%d)"
                         u/green u/off (count rows)
                         (apply max (map :loc rows))
                         (apply max (map :publics rows))
                         (:loc-block conf) (:publics-block conf))))
      findings)))

(def checks
  "Check name -> a fn of the assembled context, returning a finding count.

  A MAP rather than a `case`, so adding a check is a map entry and the argv
  validator below can name the known set without a second list to drift from it."
  {"ns-size" (fn [{:keys [analysis config]}]
               (check-ns-size analysis (:ns-size config)))
   "docstrings" (fn [{:keys [analysis config exemptions now]}]
                  (docstrings/check analysis (:docstrings config)
                                    (:docstrings exemptions) now))
   "spec-shape" (fn [{:keys [config exemptions now]}]
                  (specs/check (get-in config [:spec-shape :enrolled])
                               (:spec-shape exemptions) now))
   "spec-presence" (fn [{:keys [config exemptions paths now]}]
                     (presence/check paths (:spec-presence config)
                                     (:spec-presence exemptions) now))
   "fn-size" (fn [{:keys [config paths]}]
               (fnsize/check paths (:fn-size config)))})

(def ^:private form-reading-checks
  "Checks that read SOURCE FORMS rather than clj-kondo's analysis.

  Running clj-kondo for one of these would cost seconds and buy nothing — and,
  more importantly, `assert-analysis-ran!` floors NAMESPACES and VAR-DEFINITIONS,
  which is the wrong question for a check whose population is specs or functions.
  Each of these floors its own population instead.

  `spec-presence` is here for a second reason that is not a preference: the
  analysis records that an `m/=>` was CALLED but not which function it
  specifies. The spec appears as a var-usage `{:name => :to malli.core}` — once
  per spec, measured — while the subject var is a separate entry sharing only
  its `:row`. Attribution would therefore be positional inference; reading the
  source form yields the subject directly."
  #{"spec-shape" "fn-size" "spec-presence"})

(defn- needs-analysis?
  "True when `check-name` reads clj-kondo's analysis."
  [check-name]
  (not (contains? form-reading-checks check-name)))

(defn- dispatch!
  "Parse argv, assemble the context, run the named check, exit with its verdict.

  An UNKNOWN check name is a CANNOT RUN rather than a silent no-op, because a
  typo'd name that exits 0 is a gate someone believes is armed."
  [args]
  (let [[flag check-name & paths] args]
    (when-not (= "--check" flag)
      (u/cannot-run! "usage: --check <name> <path>..." []))
    (when-not (contains? checks check-name)
      (u/cannot-run! (str "unknown check " (pr-str check-name) ".")
                     [(str "known: " (str/join ", " (sort (keys checks))))
                      "An unknown name exits 3 rather than 0: a typo that passed"
                      "would be a gate everyone believed was armed."]))
    (when (empty? paths)
      (u/cannot-run! "no paths given."
                     ["Discovery is the CALLER's job here — lint.mk passes"
                      "LINT_CLJ_PATHS, the positive allowlist every Clojure lane"
                      "shares. An empty path list would analyse nothing and report"
                      "clean, so it refuses instead."]))
    (let [analysis (when (needs-analysis? check-name)
                     (let [a (u/kondo-analysis paths)]
                       (u/assert-analysis-ran! a paths)
                       a))
          findings ((get checks check-name)
                    {:analysis analysis
                     :paths (vec paths)
                     :config (u/read-edn-config config-path "gate config")
                     :exemptions (u/read-edn-config exemptions-path "exemptions file")
                     :now (LocalDate/now)})]
      (System/exit (if (pos? findings) u/exit-findings u/exit-ok)))))

(defn -main
  "Entry point. Wraps `dispatch!` so a CRASH cannot wear a FINDING's exit code.

  The JVM exits 1 on an uncaught exception, and 1 is the FINDINGS code — so without
  this trap a crash and a verdict about the tree are indistinguishable from
  outside, and `the gate went red` would prove nothing. That is the ERROR-vs-FAIL
  confusion `.claude/rules/gate-enforcement.md` §2 forbids, and it is reachable
  rather than theoretical: with the vacuity guards removed, the ns-size summary
  takes `max` over an empty corpus and throws.

  THIS IS NOT MASKING. The stack trace is printed in full and the exit code still
  BLOCKS; it is re-labelled from `verdict about the tree` to `this gate is broken`,
  which is the more accurate of the two."
  [& args]
  (try
    (dispatch! args)
    (catch Exception e
      (.printStackTrace e)
      (binding [*out* *err*]
        (println (str u/red "[clj-gate] CANNOT RUN" u/off
                      " — the gate itself crashed (stack trace above)."))
        (println "  Reported as exit 3, not 1: an internal error is not a finding")
        (println "  about the tree, and conflating them makes every red ambiguous."))
      (System/exit u/exit-cannot-run))))
