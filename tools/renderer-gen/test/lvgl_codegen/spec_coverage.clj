(ns lvgl-codegen.spec-coverage
  "THE JOIN: for every function carrying an `m/=>`, was it CALLED during the suite,
  and did its contract HOLD? Enforced over a DECLARED SCOPE of namespaces.

  WHY THIS IS NOT A COVERAGE PERCENTAGE. Line coverage says a line ran. It cannot
  say a CONTRACT was checked, and the two come apart in both directions here: a
  function with a loose spec is `covered` while asserting nothing, and a function
  with a tight spec that nothing calls has a contract no run ever evaluates. The
  partition that matters is three-way — exercised AND contract-checked, exercised
  with nothing asserted, or not exercised at all — and only the third needs new
  tests written.

  WHY A DECLARED SCOPE AND NOT A CEILING. A count of unexercised specs would be a
  LIST OF INDIVIDUAL FINDINGS wearing a number: each entry is one function with one
  unambiguous fix (write a test), so a committed total would be parked findings, which
  `.claude/rules/gate-enforcement.md` §1 refuses however carefully it is generated.
  Enrolment is the sanctioned shape instead — the check is TOTAL inside the enrolled
  namespaces with ZERO tolerated misses, and a namespace that is not ready is left OUT
  BY NAME. That is exactly how `lint-docstrings` is scoped.

  THE MEASUREMENT BEHIND THE ENROLMENT, and the number that surprised me. Against
  `clojure -M:test` ALONE, 4 of 34 spec-carrying namespaces are fully exercised,
  holding 12 specs. Adding the E2E generator legs that `renderer.mk` actually drives —
  `:fixtures`, `:codegen`, `:morph-fixtures` — takes that to 8 namespaces and 60
  specs, and takes exercised specs overall from 50 to 165 of 318. So the unit suite
  understates real exercise by more than 3x, and a gate that measured only it would
  send someone writing tests for code the battery already drives.

  THIS GATE THEREFORE SCOPES ITSELF TO WHAT THE TEST SEAM CAN SEE, and says so. The
  larger 8-namespace scope needs a driver that runs the generator legs, which is a
  battery-level lane rather than a test hook. Recorded here so the boundary is a
  decision rather than an omission."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]
   [lvgl-codegen.instrument :as inst]
   [malli.core :as m]
   [malli.instrument :as mi]))

(def enrolled
  "Namespaces whose EVERY instrumentable `m/=>` must be exercised by `clojure -M:test`.

  DERIVED FROM A MEASUREMENT, not aspiration: each of these was measured at 100%
  exercised by the unit suite alone, so the gate is green on arrival with zero
  tolerated misses.

  NOT ENROLLED, and each for a reason rather than for convenience. Every other
  spec-carrying namespace is at less than 100% under the unit suite — most of them at
  ZERO, because the code is driven by the E2E generator legs and not by a test. Those
  are enrolled the day a test exercises them, and the honest way to grow this list is
  to write that test, never to widen the list.

  ADDING A NAMESPACE HERE IS A RATCHET: it only ever grows. Removing one is a coverage
  regression wearing a config edit, which is why the canary pins the list's size."
  #{"lvgl-codegen.construct.schema"
    "lvgl-codegen.font-metrics"
    "lvgl-codegen.pdl-t0"
    "lvgl-codegen.style-props"})

(def hits
  "Symbols observed CALLED through the instrumented path this run.

  A set rather than a counter: the question is exercised-or-not, and a count would
  invite a threshold, which is the parked-findings shape this gate exists to avoid."
  (atom #{}))

(def wrapped-count
  "How many enrolled vars the recorder wrapped, stashed for the post-run floor.

  Zero here means the recorder never ran or the registry was empty — a different
  failure from `hits` being empty, which would mean the suite exercised nothing."
  (atom 0))

(defn specced-syms
  "Fully-qualified symbols for every registered `m/=>`, as `[sym var]` pairs.

  Reads the live malli registry rather than scanning source, because the registry is
  what instrumentation actually acts on — a source scan could name a spec that never
  registered and the gate would then demand coverage of something uninstrumentable."
  []
  (for [[nsym vars] (m/function-schemas)
        [vname _] vars
        :let [sym (symbol (str nsym) (str vname))
              v (resolve sym)]
        :when v]
    [sym v]))

(defn record-hits!
  "Wrap every enrolled, instrumentable var so a call registers in `hits`.

  WRAPPED AFTER `instrument!`, so a recorded hit means the call went THROUGH the
  instrumented path — a hit therefore witnesses both halves of the join at once,
  rather than only that the function ran.

  PRIMITIVE-HINTED VARS ARE SKIPPED, and that exclusion is the one malli itself
  makes: it refuses to instrument them, and a plain variadic wrapper breaks their
  `IFn$OL` invocation path. They are excluded from the DENOMINATOR too — counting a
  var this gate cannot observe would make the miss permanent and unfixable.

  Returns the number of vars wrapped, so the caller can floor it."
  []
  (let [wrapped (atom 0)]
    (doseq [[sym v] (specced-syms)
            :when (and (contains? enrolled (namespace sym))
                       (not (inst/primitive-hinted? v)))]
      (let [f @v]
        (swap! wrapped inc)
        (alter-var-root v (fn [_] (fn [& args]
                                    (swap! hits conj sym)
                                    (apply f args))))))
    @wrapped))

(defn findings
  "Enrolled specs never exercised this run, as sorted symbols.

  Pure — takes the observed hit set, so the canary can drive it without running a
  suite."
  [observed]
  (->> (specced-syms)
       (keep (fn [[sym v]]
               (when (and (contains? enrolled (namespace sym))
                          (not (inst/primitive-hinted? v))
                          (not (contains? observed sym)))
                 sym)))
       sort
       vec))

(defn check!
  "Assert every enrolled spec was exercised. Throws with the misses named.

  NON-VACUITY FIRST, because this check's clean output and its nothing-ran output are
  the same empty vector. Two separate floors, not a union: an empty ENROLMENT means
  the config was gutted, and a zero WRAPPED count means instrumentation or the
  registry never came up — different failures with different fixes, and a union floor
  would report whichever it met first."
  [n-wrapped]
  (when (empty? enrolled)
    (throw (ex-info "spec-coverage enrolment is EMPTY — the gate would judge nothing"
                    {:enrolled enrolled})))
  (when (zero? n-wrapped)
    (throw (ex-info "spec-coverage wrapped ZERO vars; the enrolled namespaces carry specs, so the registry or instrumentation did not come up"
                    {:enrolled enrolled :wrapped n-wrapped})))
  (let [missed (findings @hits)]
    (when (seq missed)
      (throw (ex-info (format "%d enrolled spec(s) were never exercised: %s"
                              (count missed) (str/join ", " missed))
                      {:missed missed :wrapped n-wrapped})))
    (println (format "[spec-coverage] %d/%d enrolled spec(s) exercised across %d namespace(s)"
                     n-wrapped n-wrapped (count enrolled)))
    nil))

(defn -main
  "Run the unit suite with instrumentation + hit recording armed, then CHECK.
  Exits 1 on any test failure or any unexercised enrolled spec.

  A SELF-CONTAINED DRIVER, NOT A KAOCHA HOOK, and that is a correction rather than a
  preference. `:kaocha.hooks/post-run` was tried first and MEASURED NOT TO FIRE: with a
  namespace enrolled at 0% exercised, the suite stayed green — the gate silently checked
  nothing, which is the exact failure this repo refuses. `post-load` fires (the malli
  arming seam proves it), so the hooks plugin works; `post-run` specifically did not,
  and rather than reverse-engineer why, this drives the run itself where the ordering
  is explicit and verifiable.

  A `deftest` would not work either: kaocha randomises test order, so a test asserting
  a property of the WHOLE run could execute before the tests that exercise the specs.

  ORDER IS LOAD-BEARING and is why it lives in one function: require sources so `m/=>`
  registers, instrument, THEN wrap for recording so a hit witnesses a call through the
  checked path, then run, then check."
  [& _]
  (doseq [n (inst/source-namespaces)] (require n))
  (let [registered (reduce + 0 (map (comp count val) (m/function-schemas)))]
    (when (< registered inst/spec-floor)
      (binding [*out* *err*]
        (println (format "[spec-coverage] CANNOT RUN — only %d schema(s) registered, floor is %d"
                         registered inst/spec-floor)))
      (System/exit 3)))
  (mi/instrument!)
  (let [n-wrapped (record-hits!)
        test-nses (->> (file-seq (io/file "test"))
                       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
                       (map #(-> (.getPath %)
                                 (str/replace #"^test/" "")
                                 (str/replace #"\.clj$" "")
                                 (str/replace "_" "-")
                                 (str/replace "/" ".")
                                 symbol))
                       sort vec)]
    (doseq [n test-nses] (require n))
    (let [res (apply t/run-tests test-nses)
          suite-bad (+ (:fail res 0) (:error res 0))]
      (println (format "[spec-coverage] suite: %d tests, %d assertions, %d failure(s), %d error(s)"
                       (:test res) (:pass res) (:fail res) (:error res)))
      (try
        (check! n-wrapped)
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*]
            (println (str "[spec-coverage] FAIL — " (ex-message e)))
            (println "  Every spec in an ENROLLED namespace must be exercised. Either write")
            (println "  the test that exercises it, or — if the namespace is not ready —")
            (println "  remove it from `enrolled` DELIBERATELY, which is a coverage")
            (println "  regression and should be argued in the commit message."))
          (System/exit 1)))
      (when (pos? suite-bad)
        (binding [*out* *err*]
          (println "[spec-coverage] FAIL — the suite itself is red; coverage is moot."))
        (System/exit 1))
      (System/exit 0))))
