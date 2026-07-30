(ns lvgl-codegen.instrument
  "THE ARMING SEAM for malli `m/=>` instrumentation during the test run.

  WHY THIS EXISTS. This tree carries hundreds of `m/=>` function schemas and
  nothing checked any of them: `.clj-kondo/config.edn` lints `malli.core/=>` as a
  no-op, and with no `instrument!` call anywhere a spec that MIS-DESCRIBED its
  function reddened nothing. Arming makes a refactor that changes what a function
  accepts a RED TEST instead of a silent behaviour change, which is the whole
  point — it is not runtime type safety, it is a regression net under the
  refactors the size and complexity gates will provoke.

  ONE SEAM, NOT A CALL PER NAMESPACE. `.claude/rules/gate-enforcement.md` §4:
  classify where the component is acquired, never at each caller, because a caller
  can forget and a seam cannot. This is wired as kaocha's `post-load` hook in
  `tests.edn`, so every namespace kaocha runs is covered whether or not its author
  knew this existed.

  WHY `post-load` AND NOT `pre-run` OR `pre-load`. `m/=>` registers its schema at
  LOAD time, and `instrument!` only instruments what is registered when it is
  called — a namespace required afterwards is silently UNINSTRUMENTED, which is
  the first nuance in `.claude/rules/malli-schemas.md` and the one that makes an
  arming seam look armed while covering nothing. `post-load` runs after kaocha has
  loaded every test namespace and therefore every source namespace they require.
  This namespace additionally requires the whole of `src` itself, so the covered
  set does not depend on which namespaces the tests happen to pull in.

  WHAT IT DELIBERATELY DOES NOT USE. `malli.dev/start!` would supply a genuine
  delta watcher for namespaces loaded LATER, and it is quadratic in the number of
  registered schemas — measured elsewhere in this fleet at hundreds of
  milliseconds and the dominant cost of the process it ran in. A test run loads
  everything up front, so the watcher buys nothing here and the cost is real.
  The residual gap is named rather than hidden: a namespace required lazily from
  INSIDE a `deftest` body would load after this hook and go uninstrumented.

  THE NON-VACUITY FLOOR IS THE POINT OF THE `arm!` RETURN VALUE. An arming seam
  that instruments nothing prints exactly what a working one prints, so it asserts
  that vars were measurably REPLACED — see `replaced-count`."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.instrument :as mi]))

(def spec-floor
  "The minimum number of `m/=>` schemas that must be REGISTERED for the run to be
  treated as armed.

  Provenance: 325 were registered across 34 namespaces when this seam landed, so
  200 is a floor that catches a COLLAPSE — a load order that registers almost
  nothing — without breaking on the ordinary churn of adding and removing
  individual specs. It is not a ratchet and must not be read as one: the number
  that matters is `replaced-count`, and this only rules out the vacuous case where
  there was nothing to arm."
  200)

(defn source-namespaces
  "Every namespace symbol under `src`, derived from the filesystem.

  DERIVED, never a literal list: a list beside a live source tree is a second
  source that diverges silently, and the divergence here would be a namespace
  whose specs are never armed."
  []
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
       (map #(-> (.getPath %)
                 (str/replace #"^src/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       sort
       vec))

(defn primitive-hinted?
  "True when `v`'s arglists carry a `long`/`double` type hint on an argument or on the
  arglist itself (the return position).

  MALLI REFUSES TO INSTRUMENT THESE, and a plain variadic wrapper breaks their
  `IFn$OL` invocation path — so they are excluded from every denominator that counts
  what instrumentation can observe. Counting one would make the miss permanent and
  unfixable rather than actionable. Seven functions in this tree are in this class.

  Shared with `lvgl-codegen.spec-coverage`, which must exclude exactly the same set:
  two copies of this predicate would let the two denominators drift, and a coverage
  gate whose denominator disagrees with what was instrumented is measuring nothing in
  particular."
  [v]
  (boolean (some (fn [al] (or (#{'long 'double} (:tag (meta al)))
                              (some #(#{'long 'double} (:tag (meta %))) al)))
                 (:arglists (meta v)))))

(defn replaced-count
  "How many vars in `before` now hold a DIFFERENT value than they did.

  IDENTITY AGAINST A CAPTURED PRE-STATE, and it has to be that rather than asking
  malli. `mi/-original` is not a witness of instrumentation — measured, it returns
  non-nil for all 325 specced vars here including the 7 primitive-hinted ones malli
  explicitly REFUSED to instrument, so a guard built on it reports full coverage
  over a set it is wrong about. Comparing the var's value before and after cannot
  be fooled that way: a var that still holds the same object was not wrapped, and a
  spec on an unwrapped var is one nothing can ever check.

  This is the non-vacuity guard `.claude/rules/gate-enforcement.md` §3 requires:
  `instrument!` returning normally is byte-identical to `instrument!` having read
  an empty registry."
  [before]
  (count (remove (fn [[v old]] (identical? old @v)) before)))

(defn specced-vars
  "The vars carrying a registered `m/=>` schema, resolved to var objects.

  A schema registered for a symbol that no longer resolves is dropped rather than
  throwing: the registry is keyed by symbol and can outlive a rename, and that is
  a stale-registry problem rather than an arming failure."
  []
  (keep (fn [[nsym vars]]
          (seq (keep #(resolve (symbol (str nsym) (str %))) (keys vars))))
        (m/function-schemas)))

(defn arm!
  "kaocha `post-load` hook: instrument every registered `m/=>` and PROVE it took.

  Returns the test-plan unchanged, as a kaocha hook must. Throws rather than
  warning when the population is vacuous or nothing was instrumented, because a
  warning here would leave every later green in the run meaning nothing —
  `.claude/rules/gate-enforcement.md` §4's test is whether any claim the run
  produces is still true without the component, and none of them is.

  REPORT MODE IS THE DEFAULT, i.e. THROWING. A non-throwing `:report` would turn
  a contract violation into a line of output the runner exits 0 on, which is the
  advisory tier §1 forbids."
  [test-plan]
  (doseq [n (source-namespaces)] (require n))
  (let [registered (reduce + 0 (map (comp count val) (m/function-schemas)))]
    (when (< registered spec-floor)
      (throw (ex-info (format "malli arming is VACUOUS: only %d schema(s) registered, floor is %d"
                              registered spec-floor)
                      {:registered registered :floor spec-floor})))
    (let [vars (vec (apply concat (specced-vars)))
          before (into {} (map (juxt identity deref) vars))
          _ (mi/instrument!)
          armed (replaced-count before)]
      (when (zero? armed)
        (throw (ex-info (format "instrument! replaced NO var of %d specced" (count vars))
                        {:specced (count vars)})))
      ;; Both numbers, never just the first: the gap is the primitive-hinted
      ;; functions malli refuses, and reporting only the armed count would hide a
      ;; population that grew silently un-checkable.
      (println (format "[malli] armed %d of %d specced var(s) (%d refused, primitive-hinted) from %d registered schema(s)"
                       armed (count vars) (- (count vars) armed) registered))
      test-plan)))

(defn uninstrumented
  "The fn behind `v` with any instrumentation wrapper removed.

  FOR NEGATIVE-PATH TESTS ONLY, and the reason it must exist is the sharp edge in
  `.claude/rules/malli-schemas.md`: a function that validates its own input and
  throws cannot be negative-tested through its var once armed, because malli
  refuses the argument before the body runs. Worse, malli's refusal is also an
  `ExceptionInfo`, so a bare `thrown?` passes either way — including with the
  guard deleted from the function.

  Falls back to the var's current value when instrumentation is NOT armed, so a
  test written against this reads identically in both states and does not silently
  become a different test depending on how the suite was invoked."
  [v]
  (or (mi/-original v) @v))
