(ns lint-gate.presence
  "SPEC PRESENCE — every `defn` / `defn-` in an ENROLLED NAMESPACE carries an
  `m/=>` arrow spec.

  WHY THIS CAN EXIST HERE AT ALL, given that the obvious version cannot.
  Presence is a BINARY per-function check, so the ordinary way to adopt one — a
  baseline of today's misses, or a percentage floor — is a list of individual
  findings each with an unambiguous fix, which
  `.claude/rules/gate-enforcement.md` §1 refuses however carefully it is
  generated. That section also names the move that IS permitted when a check
  cannot pass whole-tree: *narrow the declared scope to a population where it
  does pass and say what was left out, with the measured finding count stated*.
  This check is that move. `:enrolled` names NAMESPACES; inside one the check is
  TOTAL with zero tolerated misses and zero exemptions; and the un-enrolled
  remainder is not enumerated anywhere the gate reads — it is REPORTED as a
  count every run so nobody mistakes a green here for a statement about the
  tree.

  THE SHAPE IS NOT NOVEL IN THIS REPO. `lint-docstrings` declares scope by root
  and `lvgl-codegen.spec-coverage` declares it by NAMESPACE, seeded exactly this
  way — at the namespaces measured clean, so the gate is green on arrival with
  no waivers. Two vocabularies for one idea would be the silent fork these rules
  refuse everywhere else, so this reuses the namespace-keyed one.

  THE LIST GROWS, WHICH IS WHAT SEPARATES IT FROM A BASELINE. A parked-findings
  list SHRINKS as the tree improves and can absorb a NEW miss; an enrolment list
  GROWS as the tree improves and cannot absorb anything — a new `defn` inside an
  enrolled namespace is a finding the moment it lands. De-enrolling to silence
  one is a coverage regression wearing a config edit, and it is visible as a
  DELETION in `tools/lint/gates.edn`.

  WHAT A SPEC IS WORTH HERE, because the answer moved and the old one is still
  written down in places. `.claude/rules/malli-schemas.md` opens by saying no
  `instrument!` seam exists in this repo. It does:
  `lvgl-codegen.instrument/arm!` is wired as kaocha's `post-load` hook in
  `tools/renderer-gen/tests.edn`, and a run of that suite reports
  `armed 318 of 325 specced var(s)`. So inside the enrolled root a spec is a
  contract something actually checks wherever the suite reaches the function —
  not the pure prose that section describes.

  WHAT IT STILL CANNOT SEE, so no pass message over-claims. Presence is not
  truth: this check asks whether a spec was WRITTEN, never whether it is right,
  and `lint-spec-shape` asks only whether each position names a shape. A spec on
  a function the suite never exercises is checked by nothing at all —
  `lvgl-codegen.spec-coverage` is the gate for that half, and it is enrolled
  over four namespaces, not these thirty-one.

  READS SOURCE FORMS, NOT clj-kondo'S ANALYSIS — because the analysis cannot
  ATTRIBUTE a spec to its function, not because it cannot see one. Measured: an
  `m/=>` form DOES appear, as a var-usage `{:name => :to malli.core}`, once per
  spec. What that entry does not carry is which function the spec describes; the
  subject is a SEPARATE var-usage that shares only its `:row`, so building
  attribution on the analysis means inferring it from position. Reading the form
  gives the subject directly. Do not restate this as \"specs are invisible to the
  analysis\" — that claim is false and was measured false."
  (:require
   [clojure.string :as str]
   [lint-gate.fnsize :as fnsize]
   [lint-gate.specs :as specs]
   [lint-gate.util :as u])
  (:import
   (clojure.lang LineNumberingPushbackReader)
   (java.io StringReader)))

(def ^:private defn-heads
  "Definition heads that owe an `m/=>`.

  `defmacro` is DELIBERATELY OUT, on a meaning ground rather than a convenient
  one: `m/=>` registers a FUNCTION schema and `malli.instrument` wraps the var to
  check argument VALUES, while a macro receives unevaluated forms — so a function
  schema cannot describe one, and demanding it would manufacture a schema that is
  false by construction. Measured, so the exclusion is not read as a hole waiting
  to be closed: the enrolled namespaces define NO macros today, so it changes no
  current verdict and exists only to stop a future macro becoming a false
  finding.

  `def` is out because a presence check cannot tell a function from a table
  without evaluating the file. A `def` bound to a composed fn genuinely does
  carry a spec here — `lvgl-codegen.normalize/normalize-with-schema` is one — so
  the cost is real and is stated rather than hidden: such a var is judged by
  `lint-spec-shape` if it has a spec, and by nothing if it does not."
  #{"defn" "defn-"})

(defn- head-of
  "The head symbol name of `form` as a string, or nil when it is not a list
  headed by a symbol.

  `name` strips any namespace alias, so a qualified head and a bare one are the
  same head — which they are."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- absorb
  "Fold one top-level `form` into the accumulating facts for a file.

  ONE PASS FOR THREE FACTS — the namespace, the definitions, and the names that
  carry a spec — because they are read from the same form stream and a second
  traversal is a second chance for the two populations to disagree about what
  the file contains."
  [acc form]
  (let [head (head-of form)
        subject (when (and head (>= (count form) 2)) (second form))]
    (cond
      (and (= "ns" head) (symbol? subject))
      (assoc acc :ns (str subject))

      (and (contains? defn-heads head) (symbol? subject))
      (update acc :defns conj {:name (str subject)
                               :line (or (:line (meta form)) 0)})

      ;; `specs/arrow-spec?` rather than a second predicate: what counts as a
      ;; spec is the DEFINITION both spec gates share, and two copies of it is
      ;; how one of them quietly starts judging a different population.
      (specs/arrow-spec? form)
      (update acc :specced conj (str (second form)))

      :else acc)))

(defn file-facts
  "Read `path` and return `{:ns :defns :specced}`, or `{:error msg}`.

  THE ERROR IS RETURNED RATHER THAN THROWN so the caller can report an unreadable
  file as a FINDING with its reason attached. Skipping it would make `clean` and
  `I could not look` print the same empty vector, which is the third answer this
  repo demands out loud everywhere else.

  `fnsize/normalize-source` runs first, and reusing it is deliberate on both
  counts. It rewrites `::alias/key`, which `clojure.core/read` cannot resolve
  from outside the file — measured: one gated file (`deadzone_census.clj`) is
  unreadable without it, so a strict reader would report a real source file as
  broken for a reason that has nothing to do with specs. Its docstring says the
  transform is disqualifying for a check that reads keyword VALUES; this check
  reads SYMBOL names in head and subject position only, so the caveat does not
  reach it.

  Line numbers come from the reader's own metadata rather than a text scan,
  which is exact for a multi-line `defn` where a scan for the opening would only
  ever find its first line."
  [path]
  (try
    (with-open [r (LineNumberingPushbackReader.
                   (StringReader. (fnsize/normalize-source (slurp path))))]
      (loop [acc {:ns nil :defns [] :specced #{}}]
        (let [form (read {:eof ::eof :read-cond :allow} r)]
          (if (= form ::eof)
            acc
            (recur (absorb acc form))))))
    (catch Exception e
      {:error (.getMessage e)})))

(defn- assert-enrolled-live!
  "Refuse when an enrolled namespace is missing, or defines no function.

  THE PER-NAMESPACE NON-VACUITY FLOOR, and it is per-namespace rather than a
  union for the reason `.claude/rules/gate-enforcement.md` §3 gives: any
  populated namespace satisfies a union floor while a sibling sits dark, so one
  namespace whose file was renamed, moved or emptied would be scored perfectly
  for having been measured not at all.

  TWO SEPARATE FAILURES, not one. `not found` means the enrolment names
  something discovery cannot see — a rename, a moved tree, a typo. `no
  functions` means the file is there and the population collapsed. Different
  causes and different fixes, and a combined message would report whichever it
  met first.

  THE SECOND CLAUSE IS RESTRICTED TO NAMESPACES THAT WERE FOUND, and that is not
  tidiness — it is what makes either clause provable. An absent namespace has no
  files, so an unrestricted `no functions` test also refuses it, and the two
  clauses then cover the same input: silencing `not found` changes no verdict,
  its canary cannot go green on the mutant, and a dead first clause would be
  indistinguishable from a live one. The canary caught exactly that here.
  Partitioning the inputs gives each clause an input only IT refuses, which is
  the precondition for attributing a red to either."
  [enrolled by-ns]
  (let [absent (remove #(contains? by-ns %) enrolled)]
    (when (seq absent)
      (u/cannot-run!
       (format "%d enrolled namespace(s) matched NO discovered file." (count absent))
       (concat (map #(str "  not found: " %) absent)
               ["An enrolled namespace that discovery cannot see is judged by"
                "nothing while still counting as declared scope. Fix the path"
                "list or the enrolment — never leave the name in place."]))))
  (let [empty-ns (filter #(and (contains? by-ns %)
                               (empty? (mapcat :defns (get by-ns %))))
                         enrolled)]
    (when (seq empty-ns)
      (u/cannot-run!
       (format "%d enrolled namespace(s) define NO function." (count empty-ns))
       (concat (map #(str "  no functions: " %) empty-ns)
               ["A namespace with an empty population reports full presence and"
                "exits 0 — a perfect score for measuring nothing. Enrol it when"
                "it defines a function, not before."])))))

(defn findings
  "Every enrolled `defn` carrying no `m/=>`, as `{:file :line :name}`.

  THE SPEC SET IS UNIONED PER NAMESPACE rather than per file: `m/=>` registers
  against the VAR, so a spec is present whether or not it was written in the same
  file as the definition. With one file per namespace here the two readings
  coincide, and the union is the one that stays correct if that ever stops being
  true."
  [enrolled by-ns]
  (for [nsname (sort enrolled)
        :let [facts (get by-ns nsname)
              specced (reduce into #{} (map :specced facts))]
        f facts
        d (:defns f)
        :when (not (contains? specced (:name d)))]
    {:file (:file f) :line (:line d) :name (:name d)}))

(defn- exempt-match?
  "True when `entry` covers `finding` — :file AND :name must both agree, so one
  entry can never become a whole-file skip, nor a licence for a same-named var
  anywhere in the tree."
  [entry finding]
  (and (= (:file entry) (:file finding))
       (= (str (:name entry)) (str (:name finding)))))

(defn check
  "Run the spec-presence check over `paths`. Returns the BLOCKING finding count.

  `paths` is the WHOLE gated allowlist rather than only the enrolled tree, and
  that is what makes the honest-scope report possible: the denominator this
  prints is every function the Clojure lanes judge, so the fraction it says it
  covers is measured rather than asserted. §3's closing clause — a floor proves
  the population is non-empty, never that it is the RIGHT population — is why
  that number is printed on every run instead of trusted once."
  [paths conf exemptions now]
  (let [enrolled (set (:enrolled conf))]
    (when (empty? enrolled)
      (u/cannot-run! "spec-presence has no enrolled namespaces."
                     ["`:enrolled` is the DECLARED SCOPE this check judges, so an"
                      "empty set means it judges nothing while still exiting 0 —"
                      "a green tick over zero coverage."]))
    (let [files (->> paths (mapcat u/clj-files) distinct sort vec)]
      (when (empty? files)
        (u/cannot-run! (format "no Clojure file discovered across %d path(s)." (count paths))
                       [(str "paths: " (str/join " " paths))
                        "Discovery is the CALLER's job here — lint.mk passes"
                        "LINT_CLJ_PATHS. An empty file list would judge nothing"
                        "and report clean."]))
      (let [read-results (map (fn [p] (assoc (file-facts p) :file p)) files)
            unreadable (for [{:keys [file error]} read-results :when error]
                         {:file file :line 0 :name "<unreadable>" :error error})
            parsed (remove :error read-results)
            by-ns (group-by :ns (filter :ns parsed))]
        (assert-enrolled-live! enrolled by-ns)
        (let [raw (concat unreadable (findings enrolled by-ns))
              judged (reduce + 0 (for [n enrolled, f (get by-ns n)] (count (:defns f))))
              total (reduce + 0 (map (comp count :defns) parsed))
              validated (u/validate-exemptions! exemptions #{:file :name} "spec-presence" now)
              used (set (filter (fn [e] (some #(exempt-match? e %) raw)) validated))
              kept (remove (fn [f] (some #(exempt-match? % f) validated)) raw)]
          (u/assert-no-stale! validated used "spec-presence")

          ;; HONEST SCOPE, PRINTED EVERY RUN. A green here is a statement about
          ;; the enrolled namespaces and nothing wider, and the only way that
          ;; stays true for the next reader is if the gate says so itself — a
          ;; declared scope nobody can see the size of reads exactly like
          ;; whole-tree coverage.
          (println (format "%s[clj-gate:spec-presence]%s %d function(s) judged across %d enrolled namespace(s)%s"
                           (if (seq kept) u/yellow u/green) u/off
                           judged (count enrolled)
                           (if (seq validated)
                             (format "; %d exemption(s) live" (count validated))
                             "")))
          (println (format "  UNJUDGED: %d of %d function(s) in the gated paths are outside the enrolled scope (%.1f%% judged)"
                           (- total judged) total
                           (if (pos? total) (* 100.0 (/ judged (double total))) 0.0)))
          (println "  Presence is not truth: this asks whether a spec was WRITTEN.")

          (u/report-findings!
           "spec-presence" kept
           (fn [{:keys [file line error] vname :name}]
             (if error
               (format "UNREADABLE %s — %s" file error)
               (format "%s:%d  %s" file line vname)))
           "with no m/=> in an enrolled namespace"))))))
