(ns lint-gate.docstrings
  "DOCSTRINGS — every `defn` / `defn-` / `defmacro` in an ENROLLED tree carries a
  non-empty docstring.

  WHY IT IS WORTH A GATE. A reader arriving at a function from a stack trace, a
  grep or a caller needs one paragraph answering *what does this promise?* before
  the body is worth reading. The signature already says what it TAKES; nothing but
  prose says what it is FOR.

  AN EMPTY DOCSTRING COUNTS AS MISSING. The gate is for orientation, not
  box-ticking, and `\"\"` is the shape a box-ticking pass produces.

  PRIVATE VARS ARE IN SCOPE, and that is the widest of the surveyed choices,
  adopted deliberately: a private helper is exactly where a reader lands with no
  context, and one orientation line is cheap. `def` / `defonce` / `declare` are
  OUT — a Malli registry entry is a `def`, and requiring a docstring on one would
  fire on every schema in the tree.

  ENROLMENT IS A DECLARED SCOPE, NOT A BASELINE, and the distinction is the whole
  reason this check can exist here at all. `.claude/rules/gate-enforcement.md` §1
  refuses a list of individual grandfathered findings; it permits declaring WHICH
  POPULATION a check judges, provided the check is TOTAL inside it. So the enrolled
  roots are named in config, every function inside them owes a docstring with zero
  parked findings, and the roots left out are named in the same file with the
  reason. What is NOT permitted, and what this deliberately cannot express, is an
  enrolled root with a tolerated miss count.

  TESTS ARE OUT OF SCOPE ON A PRINCIPLED GROUND rather than a convenient one: a
  test is a VERIFICATION surface, not a contract surface. Its name and its
  assertions are its documentation, and a docstring on a fixture adds noise without
  catching drift. The `dev/` probe trees are out on a different ground — they are
  research artifacts whose value is the measurement, not the API."
  (:require
   [clojure.string :as str]
   [lint-gate.util :as u]))

(def ^:private defn-family
  "Var-definition forms that owe a docstring.

  These are clj-kondo's `:defined-by` values, which are fully-qualified symbols —
  matching on the bare name would also catch a local macro called `defn`."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmacro})

(defn enrolled-root
  "The enrolled root `path` belongs to, or nil.

  LONGEST PREFIX WINS, because one root can nest inside another and a first-match
  scan would attribute a file to whichever root happened to be listed first. A
  trailing slash is normalised away so `tools/x` and `tools/x/` behave alike; the
  `/` in the prefix test is what stops `tools/lint` claiming `tools/lint-extra`."
  [path roots]
  (->> roots
       (map #(str/replace % #"/+$" ""))
       (filter #(or (= path %) (str/starts-with? path (str % "/"))))
       (sort-by count)
       last))

(defn findings
  "Every enrolled defn-family var with no docstring.

  Generated projections are excluded here rather than by the caller because the
  reason is specific to this check: a docstring cannot be added to a file whose
  bytes are pinned to a generator's output, so the finding could never be
  satisfied."
  [analysis roots]
  (for [v (:var-definitions analysis)
        :let [path (:filename v)]
        :when (contains? defn-family (:defined-by v))
        :when (not (u/generated? path))
        :when (some? (enrolled-root path roots))
        :when (str/blank? (str (:doc v)))]
    {:file path :line (:row v) :name (:name v)}))

(defn- exempt-match?
  "True when `entry` covers `finding` — :file AND :name must both agree, so one
  entry can never become a whole-file skip."
  [entry finding]
  (and (= (:file entry) (:file finding))
       (= (str (:name entry)) (str (:name finding)))))

(defn check
  "Run the docstring check. Returns the BLOCKING finding count."
  [analysis conf exemptions now]
  (let [roots (:enrolled conf)]
    (when (empty? roots)
      (u/cannot-run! "docstrings has no enrolled roots."
                     ["`:enrolled` is the DECLARED SCOPE this check judges, so an"
                      "empty list means it judges nothing while still exiting 0 —"
                      "a green tick over zero coverage."]))
    (let [population (for [v (:var-definitions analysis)
                           :let [path (:filename v)]
                           :when (contains? defn-family (:defined-by v))
                           :when (not (u/generated? path))
                           :when (some? (enrolled-root path roots))]
                       v)]

      ;; NON-VACUITY, and note the shape: the vacuous value here is a PASSING one.
      ;; With no population there are no misses, so the check prints full coverage
      ;; and exits 0 — a perfect score for having measured nothing. A wrong root, a
      ;; renamed tree or a `:defined-by` vocabulary change all land here.
      (when (empty? population)
        (u/cannot-run!
         (format "docstrings found ZERO defn-family vars across %d enrolled root(s)."
                 (count roots))
         [(str "enrolled: " (str/join " " roots))
          "These roots hold hand-authored functions, so an empty population means"
          "the enrolment paths or the form filter stopped matching — not that there"
          "is nothing to document. A 0/0 corpus reports 100% and exits 0."]))

      (let [raw (findings analysis roots)
            validated (u/validate-exemptions! exemptions #{:file :name} "docstrings" now)
            used (set (filter (fn [e] (some #(exempt-match? e %) raw)) validated))
            kept (remove (fn [f] (some #(exempt-match? % f) validated)) raw)]
        (u/assert-no-stale! validated used "docstrings")

        (println (format "%s[clj-gate:docstrings]%s %d/%d documented across %d enrolled root(s)%s"
                         (if (seq kept) u/yellow u/green) u/off
                         (- (count population) (count raw)) (count population)
                         (count roots)
                         (if (seq validated)
                           (format "; %d exemption(s) live" (count validated))
                           "")))

        (u/report-findings!
         "docstrings" kept
         (fn [{:keys [file line] vname :name}]
           (format "%s:%s  %s" file line vname)))))))
