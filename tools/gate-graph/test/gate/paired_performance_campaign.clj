(ns gate.paired-performance-campaign
  "Selected comparison faults must fail their assertions while a neighbouring guard's test stays green."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def contract-source "src/gate/paired_performance_contract.cljc")

(defn- fault
  "One exact replacement, the test it must fail and a different test that must stay green."
  ([id anchor replacement target control] {:id id :anchor anchor :replacement replacement :test target :control control})
  ([id source anchor replacement target control] (assoc (fault id anchor replacement target control) :source source)))
(m/=> fault [:function [:=> [:cat :string :string :string :string :string] campaign/Fault]
             [:=> [:cat :string :string :string :string :string :string] campaign/Fault]])

(def binding-table "binding-corruption-and-failed-runs-cannot-qualify")
(def sampling-table "valid-but-insufficient-or-noisy-data-retains-raw-fractions")
(def schedule-table "grouped-controls-and-unbound-schedules-refuse")

(def faults
  [(fault "timer-evidence-unchecked" "(when (= :declared-unit-only (get-in document [:resolution-evidence :status]))"
          "(when false" "timer-unit-is-not-observed-granularity" sampling-table)
   (fault "local-controls-unchecked" "(when-not (schedule-valid? document)" "(when-not true" schedule-table binding-table)
   (fault "protocol-binding-unchecked" "(when-not (= (:protocol-digest document) (protocol-digest protocol))"
          "(when-not true" binding-table schedule-table)
   (fault "observation-work-unchecked" "(select-keys observation [:runtime :work :output :warmup])"
          "(select-keys protocol [:runtime :work :output :warmup])" binding-table schedule-table)
   (fault "observation-variant-unchecked" "(= expected (:variant observation))" "true" binding-table schedule-table)
   (fault "observation-identity-unchecked" "(= variant-identity (select-keys observation [:source :build]))" "true"
          binding-table schedule-table)
   (fault "duplicate-pair-unchecked" "(when-not (= (count pairs) (count (distinct (map :id pairs)))) [:duplicate-pair])"
          "(when-not true [:duplicate-pair])" binding-table schedule-table)
   (fault "failed-observation-unchecked" "(not= :succeeded (:status observation))" "false" binding-table schedule-table)
   (fault "cached-observation-unchecked" "(not= :disabled (:cache observation))" "false" binding-table schedule-table)
   (fault "zero-elapsed-accepted" "(= \"0\" (:elapsed-ns observation))" "false" binding-table
          "zero-timer-resolution-is-an-invalid-duration")
   (fault "pair-index-order-unchecked" "(not= (if (= :reference-first (:order pair)) (inc a) (inc b))"
          "(not= (if (= :reference-first (:order pair)) b a)" binding-table schedule-table)
   (fault "zero-resolution-accepted" "(when (= \"0\" (:resolution-ns protocol)) [:invalid-duration])"
          "(when false [:invalid-duration])" "zero-timer-resolution-is-an-invalid-duration" binding-table)
   (fault "resolution-binding-unchecked"
          "(pos? (decimal/compare (get-in document [:resolution-evidence :observed-ns]) (:resolution-ns protocol)))"
          "false" "timer-unit-is-not-observed-granularity" "zero-timer-resolution-is-an-invalid-duration")
   (fault "resolution-binding-refuses-any-mismatch"
          "(pos? (decimal/compare (get-in document [:resolution-evidence :observed-ns]) (:resolution-ns protocol)))"
          "(not (zero? (decimal/compare (get-in document [:resolution-evidence :observed-ns]) (:resolution-ns protocol))))"
          "conservative-timer-declaration-is-accepted" "timer-unit-is-not-observed-granularity")
   (fault "effect-floor-quantum-unchecked"
          "(when (< (* (:effect-floor-ppm policy) (:resolution-multiple policy)) 1000000) [:effect-floor-unresolvable])"
          "(when false [:effect-floor-unresolvable])" "effect-floor-finer-than-a-timer-quantum-is-refused"
          "replication-too-small-for-the-declared-miss-is-refused")
   (fault "effect-floor-quantum-exclusive" "(< (* (:effect-floor-ppm policy) (:resolution-multiple policy)) 1000000)"
          "(<= (* (:effect-floor-ppm policy) (:resolution-multiple policy)) 1000000)"
          "effect-floor-finer-than-a-timer-quantum-is-refused" "replication-too-small-for-the-declared-miss-is-refused")
   (fault "replication-bound-unchecked"
          "(when (zero? (bound-depth (* 2 (:minimum-per-order policy)) (:median-bound-miss-ppm policy))) [:replication-unresolvable])"
          "(when false [:replication-unresolvable])" "replication-too-small-for-the-declared-miss-is-refused"
          "effect-floor-finer-than-a-timer-quantum-is-refused")
   (fault "replication-bound-overstates-blocks" "(* 2 (:minimum-per-order policy))" "(* 3 (:minimum-per-order policy))"
          "replication-too-small-for-the-declared-miss-is-refused" "effect-floor-finer-than-a-timer-quantum-is-refused")
   (fault "pair-count-unchecked" "(= (count pairs) (* 2 (count schedule)))" "true"
          "schedule-counts-every-recorded-pair" "first-kind-binds-the-within-block-kind-order")
   (fault "first-kind-unchecked" "(= expected-kinds (mapv :kind ordered))" "true"
          "first-kind-binds-the-within-block-kind-order" "schedule-order-binds-every-member")
   (fault "member-order-unchecked" "(every? #(= (:order entry) (:order %)) members)" "true"
          "schedule-order-binds-every-member" "schedule-counts-every-recorded-pair")
   (fault "index-window-unchecked" "(= expected-indexes (vec (sort (mapcat #(map :index [(:reference %) (:candidate %)]) members))))"
          "true" schedule-table "schedule-counts-every-recorded-pair")
   (fault "replication-unchecked" "(some #(< % (:minimum-per-order policy)) per-order)" "false"
          sampling-table "timer-unit-is-not-observed-granularity")
   (fault "unbalanced-order-unchecked" "(apply not= per-order)" "false" sampling-table "timer-unit-is-not-observed-granularity")
   (fault "resolution-unchecked" "(multiply (:resolution-ns protocol) (str (:resolution-multiple policy)))" "\"0\""
          sampling-table "timer-unit-is-not-observed-granularity")
   (fault "bound-depth-strict" "(<= (* next-tail 1000000) scaled-miss)" "(< (* next-tail 1000000) scaled-miss)"
          "median-bound-depth-is-an-exact-binomial-tail" "median-bounds-trim-by-the-binomial-depth")
   (fault "bound-depth-coefficient" "(quot (* coefficient (- n k)) (inc k))" "(* coefficient (- n k))"
          "median-bound-depth-is-an-exact-binomial-tail" "replication-too-small-for-the-declared-miss-is-refused")
   (fault "median-lower-bound-index" "(nth values (dec k))" "(nth values k)"
          "median-bounds-trim-by-the-binomial-depth" "summaries-keep-both-central-order-statistics")
   (fault "median-upper-bound-index" "(nth values (- n k))" "(nth values (- n k 1))"
          "median-bounds-trim-by-the-binomial-depth" "summaries-keep-both-central-order-statistics")
   (fault "median-low-is-high" ":median-low (nth values (quot (dec n) 2))" ":median-low (nth values (quot n 2))"
          "summaries-keep-both-central-order-statistics" "faster-direction-requires-the-control-median")
   (fault "median-high-is-low" ":median-high (nth values (quot n 2))" ":median-high (nth values (quot (dec n) 2))"
          "summaries-keep-both-central-order-statistics" "slower-direction-requires-the-effect-floor")
   (fault "order-strata-unfiltered" "(or (nil? order) (= order (:order %)))" "(or (nil? order) true)"
          "summaries-keep-both-central-order-statistics" "median-bounds-trim-by-the-binomial-depth")
   (fault "lower-bound-additive" "{:numerator \"1000000\" :denominator (str (+ 1000000 ppm))}"
          "{:numerator (str (- 1000000 ppm)) :denominator \"1000000\"}"
          "effect-floor-is-symmetric-in-ratio-and-reciprocal" "control-tolerance-includes-its-upper-bound")
   (fault "upper-bound-halved" "{:numerator (str (+ 1000000 ppm)) :denominator \"1000000\"}"
          "{:numerator (str (+ 1000000 (quot ppm 2))) :denominator \"1000000\"}"
          "effect-floor-is-symmetric-in-ratio-and-reciprocal" "control-tolerance-includes-its-lower-bound")
   (fault "scaled-compare-ignores-bound" "[(:numerator a) (:denominator b) (:denominator bound)]"
          "[(:numerator a) (:denominator b) \"1000000\"]"
          "order-tolerance-is-symmetric-in-ratio-and-reciprocal" "order-effect-compares-the-upper-reference-bound")
   (fault "scaled-compare-lexicographic" "(if (zero? length-order) (compare left right) length-order)" "(compare left right)"
          "exact-arithmetic-above-js-safe-integer-range" "median-bound-depth-is-an-exact-binomial-tail")
   (fault "bound-checks-skipped" "(boolean (and (get-in observed [:comparison :median-lower-bound])"
          "(boolean (and false (get-in observed [:comparison :median-lower-bound])" sampling-table schedule-table)
   (fault "faster-effect-floor-ignored" "(neg? (fraction-compare (:median-upper-bound comparison) (lower-bound floor)))" "true"
          "effect-floor-is-symmetric-in-ratio-and-reciprocal" "slower-direction-requires-the-effect-floor")
   (fault "faster-control-median-ignored" "(neg? (fraction-compare (:median-upper-bound comparison) (:median-low control)))" "true"
          "faster-direction-requires-the-control-median" "slower-direction-requires-the-effect-floor")
   (fault "faster-control-median-high" "(:median-upper-bound comparison) (:median-low control)"
          "(:median-upper-bound comparison) (:median-high control)"
          "direction-uses-the-conservative-control-median" "faster-direction-requires-the-control-median")
   (fault "slower-effect-floor-ignored" "(pos? (fraction-compare (:median-lower-bound comparison) (upper-bound floor)))" "true"
          "slower-direction-requires-the-effect-floor" "exact-pairs-are-qualified-only-under-declared-guards")
   (fault "slower-control-median-ignored" "(pos? (fraction-compare (:median-lower-bound comparison) (:median-high control)))" "true"
          "loose-control-policy-never-hides-control-envelope" "faster-direction-requires-the-control-median")
   (fault "slower-control-median-low" "(:median-lower-bound comparison) (:median-high control)"
          "(:median-lower-bound comparison) (:median-low control)"
          "direction-uses-the-conservative-control-median" "loose-control-policy-never-hides-control-envelope")
   (fault "control-lower-bound-unchecked"
          "(neg? (fraction-compare (:median-lower-bound control) (lower-bound (:control-tolerance-ppm policy))))" "false"
          "control-tolerance-includes-its-lower-bound" "control-tolerance-includes-its-upper-bound")
   (fault "control-lower-bound-exclusive"
          "(neg? (fraction-compare (:median-lower-bound control) (lower-bound (:control-tolerance-ppm policy))))"
          "(not (pos? (fraction-compare (:median-lower-bound control) (lower-bound (:control-tolerance-ppm policy)))))"
          "control-tolerance-includes-its-lower-bound" "control-tolerance-includes-its-upper-bound")
   (fault "control-upper-bound-unchecked"
          "(pos? (fraction-compare (:median-upper-bound control) (upper-bound (:control-tolerance-ppm policy))))" "false"
          "control-tolerance-includes-its-upper-bound" "control-tolerance-includes-its-lower-bound")
   (fault "control-upper-bound-exclusive"
          "(pos? (fraction-compare (:median-upper-bound control) (upper-bound (:control-tolerance-ppm policy))))"
          "(not (neg? (fraction-compare (:median-upper-bound control) (upper-bound (:control-tolerance-ppm policy)))))"
          "control-tolerance-includes-its-upper-bound" "control-tolerance-includes-its-lower-bound")
   (fault "order-effect-unchecked" "(when (and reference-first candidate-first"
          "(when (and false reference-first candidate-first"
          "order-effect-compares-the-lower-reference-bound" "control-tolerance-includes-its-lower-bound")
   (fault "order-lower-uses-reference-high" "(scaled-compare (:median-low reference-first) (:median-high candidate-first)"
          "(scaled-compare (:median-high reference-first) (:median-high candidate-first)"
          "order-effect-compares-the-lower-reference-bound" "order-effect-compares-the-upper-reference-bound")
   (fault "order-lower-uses-candidate-low" "(scaled-compare (:median-low reference-first) (:median-high candidate-first)"
          "(scaled-compare (:median-low reference-first) (:median-low candidate-first)"
          "order-effect-compares-the-lower-reference-bound" "order-effect-compares-the-upper-reference-bound")
   (fault "order-upper-uses-reference-low" "(scaled-compare (:median-high reference-first) (:median-low candidate-first)"
          "(scaled-compare (:median-low reference-first) (:median-low candidate-first)"
          "order-effect-compares-the-upper-reference-bound" "order-effect-compares-the-lower-reference-bound")
   (fault "order-upper-uses-candidate-high" "(scaled-compare (:median-high reference-first) (:median-low candidate-first)"
          "(scaled-compare (:median-high reference-first) (:median-high candidate-first)"
          "order-effect-compares-the-upper-reference-bound" "order-effect-compares-the-lower-reference-bound")
   (fault "pair-spread-unchecked" "(when (and bounded (pos? (scaled-compare (:median-upper-bound comparison)"
          "(when (and false (pos? (scaled-compare (:median-upper-bound comparison)"
          sampling-table "order-effect-compares-the-lower-reference-bound")
   (fault "effect-unresolved-unchecked" "(and bounded (nil? (direction observed)))" "(and false (nil? (direction observed)))"
          sampling-table "timer-unit-is-not-observed-granularity")
   (fault "qualified-direction-fixed" ":direction (direction summarized)" ":direction :candidate-faster"
          "exact-pairs-are-qualified-only-under-declared-guards" "summaries-keep-both-central-order-statistics")
   (fault "ratio-denominator-replaced" ":denominator (get-in pair [:reference :elapsed-ns])"
          ":denominator (get-in pair [:candidate :elapsed-ns])"
          "exact-pairs-are-qualified-only-under-declared-guards" "zero-timer-resolution-is-an-invalid-duration")
   (fault "shape-guard-removed" "(not (m/validate pc/Document document))" "false"
          "malformed-shape-refuses-with-its-own-code" "file-discovery-and-admission-do-not-trust-names-or-stale-identities")
   (fault "qualified-direction-optional" contract-source "[:direction Direction]]" "[:direction {:optional true} Direction]]"
          "result-shape-ties-direction-to-qualification" "exact-pairs-are-qualified-only-under-declared-guards")
   (fault "inconclusive-admits-direction" contract-source "[:status [:= :inconclusive]] [:reasons"
          "[:status [:= :inconclusive]] [:direction {:optional true} Direction] [:reasons"
          "result-shape-ties-direction-to-qualification" "exact-pairs-are-qualified-only-under-declared-guards")
   (fault "miss-limit-above-maximum" contract-source "[:median-bound-miss-ppm [:int {:min 1 :max 250000}]]"
          "[:median-bound-miss-ppm [:int {:min 1 :max 500000}]]"
          "replication-too-small-for-the-declared-miss-is-refused" "effect-floor-finer-than-a-timer-quantum-is-refused")
   (fault "pair-limit-below-maximum" contract-source "[:vector {:max 64} Pair]" "[:vector {:max 63} Pair]"
          "maximum-sized-evidence-is-admitted" "file-discovery-and-admission-do-not-trust-names-or-stale-identities")
   (fault "index-limit-below-maximum" contract-source "[:index [:int {:min 0 :max 127}]]" "[:index [:int {:min 0 :max 126}]]"
          "maximum-sized-evidence-is-admitted" "file-discovery-and-admission-do-not-trust-names-or-stale-identities")
   (fault "block-limit-below-maximum" contract-source "(def Block [:int {:min 0 :max 31}])" "(def Block [:int {:min 0 :max 30}])"
          "maximum-sized-evidence-is-admitted" "file-discovery-and-admission-do-not-trust-names-or-stale-identities")
   (fault "schedule-limit-below-maximum" contract-source "[:schedule [:vector {:max 32} ScheduleEntry]]"
          "[:schedule [:vector {:max 31} ScheduleEntry]]"
          "maximum-sized-evidence-is-admitted" "file-discovery-and-admission-do-not-trust-names-or-stale-identities")])

(defn -main
  "Run source-bound mutations in a disposable copy, including full instrumented before/after suites.
   Refuses to start unless every fault names a control test other than the test it must fail."
  [output-parent]
  (when-not (every? #(and (:control %) (not= (:control %) (:test %))) faults)
    (throw (ex-info "Every paired fault needs a control other than its own test" {:code :invalid-faults})))
  (binding [campaign/*scope* :paired-performance campaign/*mutation-source* "src/gate/paired_performance.cljc"
            campaign/*test-namespace* "gate.paired-performance-test" campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
