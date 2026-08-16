(ns protocol-gen.instrument
  "THE ARMING SEAM for this tool's malli `m/=>` schemas during the test run.

   An unarmed `m/=>` in this repository is checked by nothing:
   `.clj-kondo/config.edn` lints `malli.core/=>` as a no-op, so a spec that
   MIS-DESCRIBES its function reddens nothing and rots. This hook makes the
   specs in `src` real for the duration of the suite, which is the only state
   in which writing them is worth the lines.

   ONE SEAM, NOT A CALL PER NAMESPACE — `.claude/rules/gate-enforcement.md` §4:
   a caller can forget, a seam cannot. Wired as kaocha's `post-load` hook in
   `tests.edn`.

   `post-load` RATHER THAN `pre-run` OR `pre-load`, and the ordering is
   load-bearing: `m/=>` registers its schema at LOAD time and `instrument!`
   covers only what is registered when it runs, so arming earlier instruments
   nothing while printing exactly what a working seam prints.

   THE NON-VACUITY FLOOR IS THE POINT OF THE RETURN VALUE. `instrument!`
   returning normally is byte-identical to `instrument!` having read an empty
   registry, so this asserts that vars were measurably REPLACED rather than
   trusting the call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def spec-floor
  "The minimum number of registered `m/=>` schemas for the run to count as
   armed.

   PROVENANCE: derive it, do not trust it. `make protocol-gen-test` prints the
   registered count on every run; this floor is set well below it so ordinary
   churn does not trip it, and it exists only to rule out the vacuous case
   where load order registered almost nothing. It is NOT a ratchet."
  10)

(defn source-namespaces
  "Every namespace symbol under `src`, derived from the filesystem.

   DERIVED, never a literal list: a list beside a live source tree diverges
   silently, and here the divergence would be a namespace whose specs are
   never armed."
  []
  (->> (file-seq (io/file "src"))
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))
       (map #(-> (java.io.File/.getPath %)
                 (str/replace #"^src/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       sort
       vec))

(defn specced-vars
  "The vars carrying a registered `m/=>` schema, resolved to var objects.

   A schema registered for a symbol that no longer resolves is dropped rather
   than throwing — the registry is keyed by symbol and can outlive a rename,
   which is a stale-registry problem rather than an arming failure."
  []
  (vec (mapcat (fn [[nsym vars]]
                 (keep #(resolve (symbol (str nsym) (str %))) (keys vars)))
               (m/function-schemas))))

(defn replaced-count
  "How many vars in `before` (a var->value map captured before instrumenting)
   now hold a DIFFERENT value.

   IDENTITY AGAINST A CAPTURED PRE-STATE, deliberately not `mi/-original`:
   that returns non-nil for vars malli refused to instrument, so a guard built
   on it reports coverage it does not have. A var still holding the same object
   was not wrapped, and a spec on an unwrapped var is one nothing can check."
  [before]
  (count (remove (fn [[v old]] (identical? old @v)) before)))

(defn arm!
  "kaocha `post-load` hook: instrument every registered `m/=>` and PROVE it
   took. Returns the test plan unchanged, as a kaocha hook must.

   THROWS rather than warning on a vacuous population or a zero replacement
   count: §4's test is whether any claim the run produces is still true without
   the component, and none of them is."
  [test-plan]
  (run! require (source-namespaces))
  (let [registered (reduce + 0 (map (comp count val) (m/function-schemas)))]
    (when (< registered spec-floor)
      (throw (ex-info "malli arming is VACUOUS — too few schemas registered"
                      {:registered registered :floor spec-floor})))
    (let [vars (specced-vars)
          before (into {} (map (juxt identity deref)) vars)
          _ (mi/instrument!)
          armed (replaced-count before)]
      (when (zero? armed)
        (throw (ex-info "instrument! replaced NO var" {:specced (count vars)})))
      (println (format "[malli] armed %d of %d specced var(s) from %d registered schema(s)"
                       armed (count vars) registered))
      test-plan)))

(defn uninstrumented
  "The fn behind `v` with any instrumentation wrapper removed.

   FOR NEGATIVE-PATH TESTS ONLY. A function that validates its own input and
   throws cannot be negative-tested through its var once armed: malli refuses
   the argument before the body runs, and malli's refusal is itself an
   `ExceptionInfo`, so a bare `thrown?` passes with the guard DELETED. Falls
   back to the var's value when arming is off, so a test reads identically in
   both states."
  [v]
  (or (mi/-original v) @v))
