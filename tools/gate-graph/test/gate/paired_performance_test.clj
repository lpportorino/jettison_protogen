(ns gate.paired-performance-test
  "Independent exact arithmetic and falsification probes for the declared comparison protocol.
   Every refusal and inconclusive row asserts its EXACT reason vector, so each input is a witness
   for one clause alone rather than a red any neighbouring clause could also produce."
  (:require [clojure.test :refer [deftest is testing]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.catalog-io :as catalog]
            [gate.paired-performance :as paired]
            [gate.paired-performance-contract :as pc]
            [gate.record-view :as record]
            [gate.report-io :as report-io]
            [malli.core :as m])
  (:import [java.nio.file Files] [java.nio.file.attribute FileAttribute]))

(def raw-evaluate
  "The evaluator captured before test instrumentation, so its own shape guard is what answers."
  paired/evaluate)

(defn sha [value] (canonical/sha256 value))
(defn schedule-of [blocks]
  (mapv (fn [i] {:block i :order (if (even? i) :reference-first :candidate-first) :first-kind :comparison}) (range blocks)))
(def protocol
  {:profile :paired-wall-v1 :reference {:source (sha "reference") :build (sha "reference-build")}
   :candidate {:source (sha "candidate") :build (sha "candidate-build")}
   :runtime (sha "runtime") :work (sha "work") :output (sha "output")
   :warmup 10 :cache :disabled :timer :monotonic :resolution-ns "1"
   :policy {:minimum-per-order 3 :resolution-multiple 100 :control-tolerance-ppm 100000
            :order-tolerance-ppm 100000 :spread-tolerance-ppm 300000 :effect-floor-ppm 100000
            :median-bound-miss-ppm 25000}
   :schedule (schedule-of 6)})

(defn observation [variant index duration]
  (merge (get protocol variant) (select-keys protocol [:runtime :work :output :warmup :cache])
         {:index index :variant variant :status :succeeded :elapsed-ns duration}))

(defn fixture-of
  "A sealed document of one comparison then one A/A control pair per block, orders
   alternating from reference-first. Each list gives one candidate duration per
   block against a 1000000 ns reference, so a ratio reads as the candidate value."
  [comparison-ns control-ns]
  (let [declared (assoc protocol :schedule (schedule-of (count comparison-ns)))]
    {:schema/version 1 :protocol declared :protocol-digest (paired/protocol-digest declared)
     :resolution-evidence {:status :measured :method (sha "synthetic-resolution-method") :record (sha "synthetic-resolution-record") :observed-ns "1"}
     :declaration-evidence (sha "before-execution-declaration") :measurement-evidence (sha "raw-log")
     :pairs (vec (mapcat (fn [block comparison control]
                           (let [reference-first? (even? block)]
                             (for [[slot kind duration] [[0 :comparison comparison] [1 :control control]]
                                   :let [i (+ (* 2 block) slot) first-index (* 2 i)]]
                               {:id (str "pair-" i) :block block :kind kind
                                :order (if reference-first? :reference-first :candidate-first)
                                :reference (observation :reference (if reference-first? first-index (inc first-index)) "1000000")
                                :candidate (observation (if (= :control kind) :reference :candidate)
                                                        (if reference-first? (inc first-index) first-index) duration)})))
                         (range) comparison-ns control-ns))}))

(defn fixture [] (fixture-of (vec (repeat 6 "500000")) (vec (repeat 6 "1000000"))))
(defn ratio [candidate-ns] {:numerator candidate-ns :denominator "1000000"})

(defn reseal [document]
  (assoc document :protocol-digest (paired/protocol-digest (:protocol document))))

(defn evaluate*
  "Evaluate, reading a thrown contract error as a :threw status so an assertion FAILS instead of erroring."
  [document]
  (try (paired/evaluate document)
       (catch clojure.lang.ExceptionInfo error {:status :threw :reasons [:threw] :error (ex-message error)})))

(defn reasons [document] (:reasons (evaluate* document)))
(defn reason? [document reason] (some #{reason} (reasons document)))

(defn with-kind
  "Set every pair of one kind to the given candidate and reference durations."
  [document kind candidate-ns reference-ns]
  (update document :pairs (fn [pairs] (mapv #(if (= kind (:kind %))
                                               (-> % (assoc-in [:candidate :elapsed-ns] candidate-ns)
                                                   (assoc-in [:reference :elapsed-ns] reference-ns))
                                               %) pairs))))

(defn with-policy [document k v] (reseal (assoc-in document [:protocol :policy k] v)))

(deftest exact-pairs-are-qualified-only-under-declared-guards
  (let [result (evaluate* (fixture))]
    (is (m/validate pc/Result result))
    (is (= :qualified (:status result)))
    (is (= :candidate-faster (:direction result)))
    (is (= (ratio "500000") (get-in result [:comparison :median-upper-bound])))
    (is (= 12 (count (:ratios result))))
    (is (= :unavailable (:status (evaluate* nil))))
    (is (= :candidate-slower (:direction (evaluate* (with-kind (fixture) :comparison "2000000" "1000000")))))))

(deftest binding-corruption-and-failed-runs-cannot-qualify
  (doseq [[document reason]
          [[(assoc (fixture) :protocol-digest (sha "wrong")) :protocol-binding]
           [(assoc-in (fixture) [:pairs 1 :id] "pair-0") :duplicate-pair]
           [(assoc-in (fixture) [:pairs 0 :candidate :work] (sha "different work")) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :output] (sha "different result")) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :source] (sha "wrong source")) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :build] (sha "wrong build")) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :runtime] (sha "wrong runtime")) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :warmup] 9) :observation-binding]
           [(assoc-in (fixture) [:pairs 1 :candidate :variant] :candidate) :observation-binding]
           [(assoc-in (fixture) [:pairs 0 :candidate :status] :failed) :failed-observation]
           [(assoc-in (fixture) [:pairs 0 :candidate :cache] :hit) :cached-observation]
           [(assoc-in (fixture) [:pairs 0 :candidate :elapsed-ns] "0") :invalid-duration]
           ;; The window and the order still match; only the pair's own index order is reversed.
           [(-> (fixture) (assoc-in [:pairs 0 :reference :index] 1) (assoc-in [:pairs 0 :candidate :index] 0)) :invalid-order]]]
    (testing reason
      (let [result (evaluate* document)]
        (is (= :refused (:status result)))
        (is (= [reason] (:reasons result)))
        (is (not (contains? result :direction)))))))

(deftest valid-but-insufficient-or-noisy-data-retains-raw-fractions
  (doseq [[document reason]
          [[(-> (fixture) (assoc :pairs []) (assoc-in [:protocol :schedule] []) reseal) :insufficient-pairs]
           [(fixture-of (vec (repeat 4 "500000")) (vec (repeat 4 "1000000"))) :insufficient-pairs]
           [(fixture-of (vec (repeat 7 "500000")) (vec (repeat 7 "1000000"))) :unbalanced-order]
           [(reseal (-> (fixture) (assoc-in [:protocol :resolution-ns] "10000") (assoc-in [:resolution-evidence :observed-ns] "10000"))) :timer-resolution]
           [(fixture-of ["300000" "300000" "450000" "450000" "600000" "600000"] (vec (repeat 6 "1000000"))) :pair-spread]
           [(fixture-of (vec (repeat 6 "950000")) (vec (repeat 6 "1000000"))) :effect-unresolved]]]
    (testing reason
      (let [result (evaluate* document)]
        (is (= :inconclusive (:status result)))
        (is (= [reason] (:reasons result)))
        (is (= (count (:pairs document)) (count (:ratios result))))
        (is (not (contains? result :direction)))))))

(deftest loose-control-policy-never-hides-control-envelope
  ;; 1.2 clears the 10 % floor, but not the A/A median at 1.4 that a 50 % tolerance admits.
  (let [document (-> (fixture-of (vec (repeat 6 "1200000")) (vec (repeat 6 "1400000")))
                     (with-policy :control-tolerance-ppm 500000))]
    (is (= [:effect-unresolved] (reasons document)))))

(deftest exact-arithmetic-above-js-safe-integer-range
  (doseq [a ["0" "1" "999999999999999999" "9007199254740993"]
          b ["1" "123456789012345678" "999999999999999999"]]
    (is (= (str (*' (bigint a) (bigint b))) (#'paired/multiply a b)))
    (is (= (str (*' (bigint a) (bigint b) 1300000)) (#'paired/wide-product [a b "1300000"]))))
  (let [a {:numerator "9007199254740993" :denominator "9007199254740992"}
        b {:numerator "1" :denominator "1"}]
    (is (pos? (#'paired/fraction-compare a b)))
    (is (neg? (#'paired/scaled-compare a b (#'paired/upper-bound 1))))
    (is (pos? (#'paired/scaled-compare a b (#'paired/lower-bound 1)))))
  ;; Products of different lengths order by length: 10/1 against 1.000001.
  (is (pos? (#'paired/scaled-compare {:numerator "10" :denominator "1"} {:numerator "1" :denominator "1"}
                                     (#'paired/upper-bound 1)))))

(defn- independent-depth
  "The largest k with sum_{j<k} C(n, j) * 10^6 <= miss * 2^n, in arbitrary-precision integers."
  [n miss]
  (let [binomial (fn [j] (reduce (fn [acc i] (/ (* acc (- n i)) (inc i))) 1N (range j)))]
    (count (take-while #(<= (* 1000000 (reduce + (map binomial (range %)))) (* miss (.pow (biginteger 2) (int n)))) (range 1 (inc n))))))

(deftest median-bound-depth-is-an-exact-binomial-tail
  (doseq [n (range 1 33) miss [1 15625 15626 25000 100000 250000]]
    (is (= (independent-depth n miss) (#'paired/bound-depth n miss)) (str "n=" n " miss=" miss)))
  (is (= [0 1 1 2 3 6] (mapv #(#'paired/bound-depth % 25000) [5 6 8 9 12 20]))))

(deftest median-bounds-trim-by-the-binomial-depth
  (let [values (mapv #(str (+ 500000 (* 10000 %))) (range 12))
        twelve (evaluate* (fixture-of values (vec (repeat 12 "1000000"))))
        nine (evaluate* (fixture-of (vec (take 9 values)) (vec (repeat 9 "1000000"))))]
    (is (= [(ratio "520000") (ratio "590000")]
           ((juxt :median-lower-bound :median-upper-bound) (:comparison twelve))))
    (is (= [(ratio "510000") (ratio "570000")]
           ((juxt :median-lower-bound :median-upper-bound) (:comparison nine))))))

(deftest companion-admission-and-bounded-zipper-preserve-raw-data
  (let [document (fixture)
        encoded (canonical/encode document 65536)
        admitted (admission/decode encoded :paired-performance admission/default-limits)
        context (record/prepare :paired-performance admitted)
        page (record/page context {:select {:path [:pairs] :op :children} :budget {:rows 2 :visits 2 :bytes 4096}})]
    (is (= document admitted))
    (is (= 2 (count (:rows page))))
    (is (= :row-limit (:stop page)))
    (is (= 12 (:children page)))
    (is (= (:protocol-digest document) (paired/protocol-digest (:protocol document))))))

(deftest file-discovery-and-admission-do-not-trust-names-or-stale-identities
  (let [directory (Files/createTempDirectory "paired-catalog-" (make-array FileAttribute 0))
        file (.resolve directory "paired-performance.edn")
        unknown (.resolve directory "unknown.edn")]
    (try
      (spit (str file) (canonical/encode (fixture) 65536))
      (spit (str unknown) "not an admitted document")
      (let [entries (:entries (catalog/open! (str directory) {:entries 2 :bytes 65536}))
            original (record/prepare :paired-performance (report-io/read-record! (str file) :paired-performance admission/default-limits))]
        (is (= [[:paired-performance] []] (mapv :hints entries)))
        (is (= [:unopened :unopened] (mapv :status entries)))
        (is (thrown? clojure.lang.ExceptionInfo (catalog/open! (str directory) {:entries 1 :bytes 65536})))
        (spit (str file) (canonical/encode (assoc-in (fixture) [:pairs 0 :candidate :elapsed-ns] "500001") 65536))
        (let [changed (record/prepare :paired-performance (report-io/read-record! (str file) :paired-performance admission/default-limits))]
          (is (thrown? clojure.lang.ExceptionInfo (record/require-artifact! changed (:artifact original)))))
        (is (thrown? clojure.lang.ExceptionInfo (report-io/read-record! (str unknown) :paired-performance admission/default-limits)))
        (is (not (m/validate pc/Document (update (fixture) :pairs #(vec (take 65 (cycle %))))))))
      (finally (Files/deleteIfExists file) (Files/deleteIfExists unknown) (Files/deleteIfExists directory)))))

(deftest malformed-shape-refuses-with-its-own-code
  (is (= :paired-shape (try (raw-evaluate {:unrecognized true}) nil
                            (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))

(deftest grouped-controls-and-unbound-schedules-refuse
  (is (= [:block-schedule] (reasons (update (fixture) :pairs pop))))
  (is (= [:block-schedule] (reasons (assoc-in (fixture) [:pairs 0 :block] 1))))
  (is (= [:block-schedule] (reasons (reseal (assoc-in (fixture) [:protocol :schedule 1 :block] 0)))))
  ;; Repeating pair 0's indexes in order: a duplicate index refuses through the window alone.
  (is (= [:block-schedule] (reasons (-> (fixture) (assoc-in [:pairs 1 :reference :index] 0)
                                        (assoc-in [:pairs 1 :candidate :index] 1)))))
  (let [document (update (fixture) :pairs
                         (fn [pairs]
                           (mapv (fn [i pair]
                                   (let [reference-first? (= :reference-first (:order pair))]
                                     (-> pair
                                         (assoc-in [:reference :index] (+ (* 2 i) (if reference-first? 0 1)))
                                         (assoc-in [:candidate :index] (+ (* 2 i) (if reference-first? 1 0))))))
                                 (range) (sort-by #(if (= :comparison (:kind %)) 0 1) pairs))))]
    (is (= [:block-schedule] (reasons document)))))

(deftest timer-unit-is-not-observed-granularity
  (let [document (assoc (fixture) :resolution-evidence {:status :declared-unit-only :source (sha "nanoTime-unit-only")})
        result (evaluate* document)]
    (is (= :inconclusive (:status result)))
    (is (= [:timer-resolution-unverified] (:reasons result)))
    (is (= 12 (count (:ratios result))))
    (is (not (contains? result :direction)))
    (is (= [:duplicate-pair] (reasons (assoc-in document [:pairs 1 :id] "pair-0")))))
  (is (= [:resolution-binding] (reasons (assoc-in (fixture) [:resolution-evidence :observed-ns] "2")))))

(deftest conservative-timer-declaration-is-accepted
  ;; Declaring a coarser resolution than was observed only makes the timer check stricter.
  (let [document (reseal (-> (fixture) (assoc-in [:protocol :resolution-ns] "100")
                             (assoc-in [:resolution-evidence :observed-ns] "32")))]
    (is (= :qualified (:status (evaluate* document))))
    (is (= [:resolution-binding] (reasons (assoc-in document [:resolution-evidence :observed-ns] "101"))))))

(deftest faster-direction-requires-the-control-median
  ;; 0.75 clears the 10 % effect floor but not the A/A median at 0.7.
  (let [document (-> (fixture-of (vec (repeat 6 "750000")) (vec (repeat 6 "700000")))
                     (with-policy :control-tolerance-ppm 500000))]
    (is (= [:effect-unresolved] (reasons document)))))

(deftest direction-uses-the-conservative-control-median
  ;; Split A/A controls: a direction must clear the central value on its own side.
  (let [faster (-> (fixture-of (vec (repeat 6 "850000")) ["800000" "800000" "800000" "1000000" "1000000" "1000000"])
                   (with-policy :control-tolerance-ppm 300000))
        slower (-> (fixture-of (vec (repeat 6 "1150000")) ["1000000" "1000000" "1000000" "1200000" "1200000" "1200000"])
                   (with-policy :control-tolerance-ppm 300000))]
    (is (= [:effect-unresolved] (reasons faster)))
    (is (= [:effect-unresolved] (reasons slower)))))

(deftest slower-direction-requires-the-effect-floor
  ;; 1.05 clears the A/A median at 1.0 but not the 10 % effect floor.
  (is (= [:effect-unresolved] (reasons (fixture-of (vec (repeat 6 "1050000")) (vec (repeat 6 "1000000")))))))

(deftest effect-floor-is-symmetric-in-ratio-and-reciprocal
  ;; 1.105 and 1/1.105 sit equally far outside a 10 % floor; 1.0999 and 1/1.0999 equally inside.
  (is (= :candidate-slower (:direction (evaluate* (with-kind (fixture) :comparison "1105000" "1000000")))))
  (is (= :candidate-faster (:direction (evaluate* (with-kind (fixture) :comparison "1000000" "1105000")))))
  (is (= [:effect-unresolved] (reasons (with-kind (fixture) :comparison "1099900" "1000000"))))
  (is (= [:effect-unresolved] (reasons (with-kind (fixture) :comparison "1000000" "1099900")))))

(deftest summaries-keep-both-central-order-statistics
  (let [even (evaluate* (fixture-of ["500000" "510000" "520000" "530000" "540000" "550000" "560000" "570000"]
                                    (vec (repeat 8 "1000000"))))
        odd (evaluate* (fixture-of ["500000" "510000" "520000" "530000" "540000" "550000"] (vec (repeat 6 "1000000"))))
        summary (fn [& values] (zipmap [:minimum :median-low :median-high :maximum] (map ratio values)))
        central (fn [summary] (select-keys summary [:minimum :median-low :median-high :maximum]))]
    (is (= :qualified (:status even)))
    (is (= (summary "500000" "530000" "540000" "570000") (central (:comparison even))))
    (is (= (summary "500000" "520000" "540000" "560000") (central (:reference-first even))))
    (is (= (summary "510000" "530000" "550000" "570000") (central (:candidate-first even))))
    (is (= (summary "500000" "520000" "530000" "550000") (central (:comparison odd))))
    (is (= (summary "500000" "520000" "520000" "540000") (central (:reference-first odd))))))

(defn order-strata
  "Evaluate eight blocks: reference-first comparisons on even blocks, candidate-first on odd."
  [reference-first candidate-first]
  (evaluate* (fixture-of (vec (interleave reference-first candidate-first)) (vec (repeat 8 "1000000")))))

(deftest order-effect-compares-the-lower-reference-bound
  ;; Median bounds [0.5 0.6] vs [0.548 0.6]: only 0.5/0.6 falls below 1/1.1.
  (let [result (order-strata ["500000" "500000" "600000" "600000"] ["548000" "548000" "600000" "600000"])]
    (is (= :inconclusive (:status result)))
    (is (= [:order-effect] (:reasons result)))))

(deftest order-effect-compares-the-upper-reference-bound
  ;; Median bounds [0.548 0.6] vs [0.5 0.6]: only 0.6/0.5 rises above 1.1.
  (let [result (order-strata ["548000" "548000" "600000" "600000"] ["500000" "500000" "600000" "600000"])]
    (is (= :inconclusive (:status result)))
    (is (= [:order-effect] (:reasons result)))))

(deftest order-tolerance-is-symmetric-in-ratio-and-reciprocal
  ;; Strata at a ratio of exactly 1.1 either way agree; at 1.1001 either way they do not.
  (doseq [[high low] [["550000" "500000"] ["500000" "550000"]]]
    (is (= :qualified (:status (order-strata (repeat 4 high) (repeat 4 low))))))
  (doseq [[high low] [["550000" "499950"] ["499950" "550000"]]]
    (is (= [:order-effect] (:reasons (order-strata (repeat 4 high) (repeat 4 low)))))))

(deftest more-replication-with-in-band-controls-keeps-the-direction
  ;; Two extra A/A controls inside a 20 % band no longer veto an effect the median resolves.
  (let [six (-> (fixture-of (vec (repeat 6 "850000")) (vec (repeat 6 "1000000"))) (with-policy :control-tolerance-ppm 200000))
        eight (-> (fixture-of (vec (repeat 8 "850000")) (into (vec (repeat 6 "1000000")) ["840000" "840000"]))
                  (with-policy :control-tolerance-ppm 200000))]
    (is (= :candidate-faster (:direction (evaluate* six))))
    (is (= :candidate-faster (:direction (evaluate* eight))))))

(deftest schedule-counts-every-recorded-pair
  ;; Block 5's pairs stay recorded but unscheduled; every scheduled block is intact.
  (is (= [:block-schedule] (reasons (reseal (update-in (fixture) [:protocol :schedule] pop))))))

(deftest first-kind-binds-the-within-block-kind-order
  (let [declared (reseal (assoc-in (fixture) [:protocol :schedule 0 :first-kind] :control))
        control-first (-> declared
                          (assoc-in [:pairs 0 :reference :index] 2) (assoc-in [:pairs 0 :candidate :index] 3)
                          (assoc-in [:pairs 1 :reference :index] 0) (assoc-in [:pairs 1 :candidate :index] 1))]
    (is (= [:block-schedule] (reasons declared)))
    (is (= :qualified (:status (evaluate* control-first))))))

(deftest schedule-order-binds-every-member
  ;; Each pair's indexes agree with its own order; only the scheduled order differs.
  (is (= [:block-schedule] (reasons (reseal (assoc-in (fixture) [:protocol :schedule 0 :order] :candidate-first))))))

(deftest zero-timer-resolution-is-an-invalid-duration
  (is (= [:invalid-duration] (reasons (reseal (-> (fixture) (assoc-in [:protocol :resolution-ns] "0")
                                                  (assoc-in [:resolution-evidence :observed-ns] "0")))))))

(defn with-control-fraction
  "Set the first A/A control pair's candidate and reference durations."
  [candidate-ns reference-ns]
  (-> (fixture) (assoc-in [:pairs 1 :candidate :elapsed-ns] candidate-ns) (assoc-in [:pairs 1 :reference :elapsed-ns] reference-ns)))

(deftest control-tolerance-includes-its-lower-bound
  ;; 100000 ppm: a control bound at exactly 1/1.1 is inside, one nanosecond less is not.
  (is (= :qualified (:status (evaluate* (with-control-fraction "1000000" "1100000")))))
  (is (= [:control-noise] (reasons (with-control-fraction "999999" "1100000")))))

(deftest control-tolerance-includes-its-upper-bound
  (is (= :qualified (:status (evaluate* (with-control-fraction "1100000" "1000000")))))
  (is (= [:control-noise] (reasons (with-control-fraction "1100001" "1000000")))))

(deftest effect-floor-finer-than-a-timer-quantum-is-refused
  ;; Observations reach at least 100 quanta, so a floor below 1/100 cannot be resolved.
  (is (= :qualified (:status (evaluate* (with-policy (fixture) :effect-floor-ppm 10000)))))
  (is (= [:effect-floor-unresolvable] (reasons (with-policy (fixture) :effect-floor-ppm 9999)))))

(deftest replication-too-small-for-the-declared-miss-is-refused
  ;; Six blocks bound a median only at a miss of at least 1/64 = 15625 ppm.
  (is (= :qualified (:status (evaluate* (with-policy (fixture) :median-bound-miss-ppm 15625)))))
  (is (= [:replication-unresolvable] (reasons (with-policy (fixture) :median-bound-miss-ppm 15624))))
  ;; A miss of one half or more would let k pass the median; the contract stops well short.
  (is (m/validate pc/Document (with-policy (fixture) :median-bound-miss-ppm 250000)))
  (is (not (m/validate pc/Document (assoc-in (fixture) [:protocol :policy :median-bound-miss-ppm] 250001)))))

(deftest result-shape-ties-direction-to-qualification
  (let [qualified (evaluate* (fixture))
        inconclusive (evaluate* (fixture-of (vec (repeat 6 "950000")) (vec (repeat 6 "1000000"))))]
    (is (m/validate pc/Result qualified))
    (is (m/validate pc/Result inconclusive))
    (is (not (m/validate pc/Result (dissoc qualified :direction))))
    (is (not (m/validate pc/Result (assoc inconclusive :direction :candidate-faster))))
    (is (not (m/validate pc/Result (assoc qualified :status :inconclusive))))))

(deftest maximum-sized-evidence-is-admitted
  ;; 32 blocks: 64 pairs, blocks 0..31, observation indexes 0..127, 32 schedule entries.
  ;; An instrumented digest refuses an over-limit protocol by throwing; that is a
  ;; failed admission here, not an error in the test.
  (let [document (try (fixture-of (vec (repeat 32 "500000")) (vec (repeat 32 "1000000")))
                      (catch clojure.lang.ExceptionInfo _ nil))
        pairs (:pairs document)
        valid? (boolean (and document (m/validate pc/Document document)))
        result (when valid? (evaluate* document))]
    (is (some? document))
    (is valid?)
    (is (= [64 31 127 32] (when document
                            [(count pairs) (apply max (map :block pairs))
                             (apply max (mapcat #(map :index [(:reference %) (:candidate %)]) pairs))
                             (count (get-in document [:protocol :schedule]))])))
    (is (= :qualified (:status result)))
    (is (= 64 (count (:ratios result))))))
