(ns lint-gate.specs
  "SPEC SHAPE — an `m/=>` argument or return position may not be a NAKED schema.

  WHAT THIS IS NOT. It does not check that a spec EXISTS (presence is a different
  and much larger question, and `.claude/rules/malli-schemas.md` records why it is
  not gated here). It does not check that a spec is TRUE of its function — nothing
  in this repo can, because no `instrument!` seam exists and clj-kondo maps
  `malli.core/=>` to a literal no-op. **So a green here means every position names
  a shape, never that any shape is correct.** A pass message that implied otherwise
  would be the over-claim `docs/UI-QUALITY-CONTRACTS.md` §0 forbids everywhere.

  WHAT NAKED MEANS, and why the set is what it is. `:any` says nothing at all.
  Bare `:map` and bare `:string` say only the container, which for a codegen
  pipeline is the part a reader already knew — the question is always WHICH keys or
  WHICH bounds. `:keyword` and `:int` are deliberately NOT in the set: for a
  function whose argument genuinely is an arbitrary keyword, `:keyword` is the
  tight answer, and flagging it would push authors toward a narrower type that is
  false. The surveyed donor repo draws the line in the same place.

  THE POPULATION IS THE SPECS THAT EXIST, which is what makes this adoptable at
  all: it judges the `m/=>` forms already written, not every function that lacks
  one.

  READING IS BY THE CLOJURE READER, not by regex, so a spec broken across lines or
  carrying a nested schema is parsed as data. A file the reader CANNOT parse is a
  FINDING and never a skip — `CLAUDE.md`'s *an unjudged element is a FINDING* one
  level up. Silently skipping an unparseable file is how a gate reports clean over
  the one file somebody broke."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [lint-gate.util :as u])
  (:import
   (java.io PushbackReader)))

(def ^:private naked
  "Schemas that name a container or nothing, and so teach a reader nothing.

  Held in the config rather than here would be worse, not better: this is the
  DEFINITION of the check, and a definition that varies per invocation is a
  different gate each run."
  #{:any :map :string})

(defn read-forms
  "Read every top-level form in `path`. Returns `{:forms [...]}` or `{:error msg}`.

  The error is RETURNED rather than thrown so the caller can report an unparseable
  file as a finding with its reason attached. `:read-cond :allow` is needed for
  reader conditionals; a tagged literal the reader does not know still fails, and
  that failure is the finding."
  [path]
  (try
    (with-open [r (PushbackReader. (io/reader path))]
      (loop [acc []]
        (let [v (read {:eof ::eof :read-cond :allow} r)]
          (if (= v ::eof)
            {:forms acc}
            (recur (conj acc v))))))
    (catch Exception e
      {:error (.getMessage e)})))

(defn arrow-spec?
  "True for `(m/=> name schema)` under ANY alias for malli.core.

  Matched on the SYMBOL NAME rather than a resolved alias, deliberately: the alias
  is per-file (`m`, `malli`, `mc`) and resolving it would mean tracking requires
  for no gain, while `=>` is not a name anything else here defines. The cost is
  stated rather than hidden: a hypothetical unrelated `foo/=>` would be judged as a
  spec."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "=>" (name (first form)))
       (>= (count form) 3)))

(defn spec-positions
  "The ARGUMENT and RETURN schemas of a `[:=> [:cat A B …] Ret]` spec.

  Returns nil for a shape this does not understand — a `:function` spec with
  several arities, say — rather than guessing at its positions. That is a
  deliberate under-reach: judging a shape wrongly is worse than not judging it,
  and the totality report below counts what was skipped so the gap is visible
  rather than silent."
  [spec]
  (when (and (vector? spec) (= :=> (first spec)) (>= (count spec) 2))
    ;; `args-cat`, not `cat`: a local named `cat` shadows clojure.core/cat, which
    ;; clj-kondo refuses — and the failure mode if it did not is that a missed
    ;; reference silently resolves to the core var instead.
    (let [args-cat (second spec)
          ret (nth spec 2 ::none)]
      (concat (when (and (vector? args-cat) (= :cat (first args-cat))) (rest args-cat))
              (when-not (= ret ::none) [ret])))))

(defn locate-line
  "1-based line in `lines` where the spec for `vname` is written, or 0.

  `read` discards position for a seq form, and the alternative — adding a reader
  dependency to recover it — is not worth a line number. So the line is found by
  scanning the source for the spec's own opening, which is exact for this shape
  because `=>` followed by the var name appears nowhere else.

  IT IS A LOCATOR, NOT A CLAIM ABOUT THE FORM'S EXTENT: a multi-line spec reports
  its first line. 0 means the scan found nothing, which is reported as-is rather
  than guessed at — a fabricated line number sends a reader to the wrong place
  with confidence."
  [lines vname]
  (let [needle (re-pattern (str "=>\\s+" (java.util.regex.Pattern/quote (str vname))
                                "(\\s|$)"))]
    (or (some (fn [[i line]] (when (re-find needle line) (inc i)))
              (map-indexed vector lines))
        0)))

(defn file-findings
  "Findings for one file: `{:file :line :name :naked}` per offending spec."
  [path forms]
  (let [lines (try (str/split-lines (slurp path)) (catch Exception _ []))]
    (for [form forms
          :when (arrow-spec? form)
          :let [[_ vname spec] form
                bad (filter naked (spec-positions spec))]
          :when (seq bad)]
      {:file path :line (locate-line lines vname) :name vname :naked (vec bad)})))

(defn- exempt-match?
  "True when `entry` covers `finding` — both :file and :name must agree.

  Matching on :file alone would make one entry a whole-file skip wearing a
  per-var entry's clothes; matching on :name alone would excuse a same-named var
  in every file."
  [entry finding]
  (and (= (:file entry) (:file finding))
       (= (str (:name entry)) (str (:name finding)))))

(defn check
  "Run the spec-shape check over `roots`. Returns the BLOCKING finding count."
  [roots exemptions now]
  (let [files (mapcat u/clj-files roots)
        read-results (map (juxt identity read-forms) files)
        unparseable (for [[p {:keys [error]}] read-results
                          :when error]
                      {:file p :line 0 :name '<unparseable> :naked [error]})
        parsed (remove (comp :error second) read-results)
        specs (for [[p {:keys [forms]}] parsed
                    f forms
                    :when (arrow-spec? f)]
                [p f])
        understood (filter (fn [[_ f]] (some? (spec-positions (nth f 2 nil)))) specs)
        raw (concat unparseable
                    (mapcat (fn [[p {:keys [forms]}]] (file-findings p forms)) parsed))]

    ;; NON-VACUITY. This check's denominator is the specs that EXIST, so zero of
    ;; them is not "all tight" — it is a filter that stopped matching, and it would
    ;; report a perfect score for having measured nothing.
    (when (empty? specs)
      (u/cannot-run!
       (format "spec-shape found ZERO m/=> forms across %d file(s)." (count files))
       [(str "roots: " (str/join " " roots))
        "The enrolled trees carry arrow specs, so an empty population means the"
        "form matcher or the file walk stopped matching — not that every spec is"
        "tight. A zero denominator scores 100% for measuring nothing."]))

    ;; ── PER-ROOT COUNTS, because the floor above is a UNION and cannot see a
    ;; SINGLE root going dark. Measured: of the three enrolled roots, ONE carries
    ;; all 325 specs and the other two carry ZERO — so that floor was satisfied by
    ;; renderer-gen alone while two thirds of the declared scope contributed nothing
    ;; and said nothing. `.claude/rules/gate-enforcement.md` §3 requires flooring
    ;; each root individually for exactly this reason.
    ;;
    ;; REPORTED, NOT REFUSED, and the distinction is the whole design. A root with
    ;; no `m/=>` at all is not a defect in the tree — it genuinely has no specs, and
    ;; failing on it would manufacture a red for a non-defect and invite someone to
    ;; de-enrol the root, which is the coverage loss this is meant to prevent: a spec
    ;; added there tomorrow must be judged. So the third answer is printed out loud
    ;; every run — "an unjudged element is a FINDING, never a skip" applied to the
    ;; ROOT — and a root that silently stops matching is now visible instead of
    ;; masked by a populated neighbour.
    (let [per-root (for [r roots
                         :let [rf (set (u/clj-files r))]]
                     [r (count (filter (fn [[p _]] (contains? rf p)) specs))])
          dark (keep (fn [[r n]] (when (zero? n) r)) per-root)]
      (println (format "  per root: %s"
                       (str/join "  " (map (fn [[r n]] (format "%s=%d" r n)) per-root))))
      (when (seq dark)
        (println (format "%s  UNMEASURED: %d enrolled root(s) carry no m/=> at all — %s%s"
                         u/yellow (count dark) (str/join ", " dark) u/off))
        (println "  Not a finding: they have no specs to judge. Enrolled so a spec")
        (println "  added there IS judged, and named here so a root that stops")
        (println "  matching cannot hide behind a populated one.")))

    (let [validated (u/validate-exemptions! exemptions #{:file :name} "spec-shape" now)
          used (set (filter (fn [e] (some #(exempt-match? e %) raw)) validated))
          kept (remove (fn [f] (some #(exempt-match? % f) validated)) raw)]
      (u/assert-no-stale! validated used "spec-shape")

      ;; TOTALITY, reported every run: how many specs exist, how many this check
      ;; could actually judge, and therefore how many it passed over. A gate that
      ;; printed only its findings would let the unjudged remainder grow silently.
      (println (format "%s[clj-gate:spec-shape]%s %d m/=> form(s) in %d file(s); %d judged, %d of a shape this check does not read"
                       (if (seq kept) u/yellow u/green) u/off
                       (count specs) (count files) (count understood)
                       (- (count specs) (count understood))))
      (when (seq validated)
        (println (format "  %d exemption(s) live" (count validated))))

      (u/report-findings!
       "spec-shape" kept
       ;; The destructuring renames `:name` and `:naked` away from
       ;; clojure.core/name and this namespace's own `naked` set. The KEYWORD keys
       ;; stay as they are — they are the finding's vocabulary; only the locals move.
       (fn [{:keys [file line] vname :name bad :naked}]
         (format "%s:%d  %-28s %s" file line vname (str/join " " bad)))))))
