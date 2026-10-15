(ns gate.paired-performance
  "Exact, bounded descriptive comparisons; never noise subtraction or causal performance attribution."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.decimal :as decimal]
            [gate.paired-performance-contract :as pc]
            [malli.core :as m]))

(defn protocol-digest
  "Bind the full predeclared work, variants, runtime and policy; this is not proof of declaration time."
  [protocol]
  (canonical/sha256 (canonical/encode protocol 16384)))
(m/=> protocol-digest [:=> [:cat pc/Protocol] c/Digest])

(defn- multiply
  "Multiply at most eighteen-digit naturals exactly with bounded portable decimal operations."
  [a b]
  (reduce (fn [acc character]
            (let [digit #?(:clj (- (int character) 48) :cljs (- (.charCodeAt character 0) 48))
                  shifted (if (= acc "0") "0" (str acc "0"))]
              (reduce (fn [value _] (decimal/add value a)) shifted (range digit))))
          "0" b))
(m/=> multiply [:=> [:cat pc/Duration pc/Duration] c/Natural])

(defn- fraction-compare
  "Compare ratios by exact cross products, without binary floating point or rounding."
  [a b]
  (decimal/compare (multiply (:numerator a) (:denominator b))
                   (multiply (:numerator b) (:denominator a))))
(m/=> fraction-compare [:=> [:cat pc/Fraction pc/Fraction] :int])

(defn- bound-depth
  "The largest k whose order statistics X(k) and X(n+1-k) each miss the median of n independent
   continuous ratios with probability at most miss-ppm parts per million (an exact binomial(n, 1/2)
   tail), or 0 when even the extremes miss more often. A miss below one half keeps k at or below n/2;
   every integer stays below 2^53 for n <= 32."
  [n miss-ppm]
  (let [scaled-miss (* miss-ppm #?(:clj (Math/pow 2 n) :cljs (js/Math.pow 2 n)))]
    (loop [k 0 tail 0 coefficient 1]
      (let [next-tail (+ tail coefficient)]
        (if (<= (* next-tail 1000000) scaled-miss)
          (recur (inc k) next-tail (quot (* coefficient (- n k)) (inc k)))
          k)))))
(m/=> bound-depth [:=> [:cat [:int {:min 1 :max 32}] [:int {:min 1 :max 250000}]] [:int {:min 0 :max 16}]])

(defn- summarize
  "Keep extremes and both central order statistics, plus the median's order-statistic bounds when n
   is large enough for the declared miss; bounds are distribution-free only for independent ratios."
  [ratios miss-ppm]
  (let [values (vec (sort fraction-compare ratios)) n (count values) k (bound-depth n miss-ppm)]
    (cond-> {:minimum (first values) :median-low (nth values (quot (dec n) 2))
             :median-high (nth values (quot n 2)) :maximum (peek values)}
      (pos? k) (assoc :median-lower-bound (nth values (dec k)) :median-upper-bound (nth values (- n k))))))
(m/=> summarize [:=> [:cat [:vector {:min 1 :max 32} pc/Fraction] [:int {:min 1 :max 250000}]] pc/Summary])

(defn- upper-bound
  "One plus a parts-per-million tolerance, as an exact fraction."
  [ppm]
  {:numerator (str (+ 1000000 ppm)) :denominator "1000000"})
(m/=> upper-bound [:=> [:cat [:int {:min 1 :max 1000000}]] pc/Fraction])

(defn- lower-bound
  "The reciprocal of upper-bound, so a band is symmetric in ratio and its inverse."
  [ppm]
  {:numerator "1000000" :denominator (str (+ 1000000 ppm))})
(m/=> lower-bound [:=> [:cat [:int {:min 1 :max 1000000}]] pc/Fraction])

(defn- wide-product
  "Multiply three bounded decimal strings with exact small-integer digit convolution."
  [factors]
  (let [digits (fn [value] (mapv #(- #?(:clj (int %) :cljs (.charCodeAt % 0)) 48) (reverse value)))
        product (reduce
                 (fn [left factor]
                   (let [right (digits factor)
                         cells (reduce (fn [cells [i j]] (update cells (+ i j) + (* (nth left i) (nth right j))))
                                       (vec (repeat (+ (count left) (count right)) 0))
                                       (for [i (range (count left)) j (range (count right))] [i j]))]
                     (loop [i 0 carry 0 result []]
                       (if (= i (count cells)) result
                           (let [value (+ carry (nth cells i))]
                             (recur (inc i) (quot value 10) (conj result (mod value 10))))))))
                 [1] factors)
        trimmed (drop-while zero? (reverse product))]
    (if (seq trimmed) (apply str trimmed) "0")))
(m/=> wide-product [:=> [:cat [:vector {:min 3 :max 3} pc/Duration]] [:string {:min 1 :max 54}]])

(defn- scaled-compare
  "Compare the ratio of two fractions, a/b, with an exact bound fraction using decimal digit products."
  [a b bound]
  (let [left (wide-product [(:numerator a) (:denominator b) (:denominator bound)])
        right (wide-product [(:denominator a) (:numerator b) (:numerator bound)])
        length-order (compare (count left) (count right))]
    (if (zero? length-order) (compare left right) length-order)))
(m/=> scaled-compare [:=> [:cat pc/Fraction pc/Fraction pc/Fraction] :int])

(defn- schedule-valid?
  "Require one comparison and local A/A pair in each predeclared four-observation block.
   The kinds and the four-index window each need exactly two members, and a block scheduled twice
   selects the same pairs for two different windows, so neither needs a check of its own."
  [document]
  (let [schedule (get-in document [:protocol :schedule]) pairs (:pairs document)]
    (and (= (count pairs) (* 2 (count schedule)))
         (every?
          true?
          (map-indexed
           (fn [position entry]
             (let [members (filterv #(= (:block entry) (:block %)) pairs)
                   ordered (sort-by #(min (get-in % [:reference :index]) (get-in % [:candidate :index])) members)
                   expected-kinds [(:first-kind entry) (if (= :control (:first-kind entry)) :comparison :control)]
                   expected-indexes (vec (range (* 4 position) (+ 4 (* 4 position))))]
               (and (= expected-kinds (mapv :kind ordered))
                    (every? #(= (:order entry) (:order %)) members)
                    (= expected-indexes (vec (sort (mapcat #(map :index [(:reference %) (:candidate %)]) members)))))))
           schedule)))))
(m/=> schedule-valid? [:=> [:cat pc/Document] :boolean])

(defn- policy-reasons
  "Refuse a policy the evidence could never satisfy honestly: an effect floor finer than one timer
   quantum of the shortest admitted observation, or a replication floor too small to bound a median."
  [policy]
  (vec (concat
        (when (< (* (:effect-floor-ppm policy) (:resolution-multiple policy)) 1000000) [:effect-floor-unresolvable])
        (when (zero? (bound-depth (* 2 (:minimum-per-order policy)) (:median-bound-miss-ppm policy))) [:replication-unresolvable]))))
(m/=> policy-reasons [:=> [:cat pc/Policy] [:vector {:max 2} pc/Reason]])

(defn- binding-reasons
  "Check evidence consistency without claiming to authenticate a producer or its workload/output witness.
   A valid schedule already gives every observation a distinct index (disjoint four-index windows that
   together hold every pair), so a repeated index always refuses as :block-schedule."
  [document]
  (let [protocol (:protocol document) pairs (:pairs document)
        observations (vec (mapcat (juxt :reference :candidate) pairs))]
    (vec (distinct
          (concat
           (when (and (= :measured (get-in document [:resolution-evidence :status]))
                      (pos? (decimal/compare (get-in document [:resolution-evidence :observed-ns]) (:resolution-ns protocol))))
             [:resolution-binding])
           (policy-reasons (:policy protocol))
           (when-not (schedule-valid? document) [:block-schedule])
           (when-not (= (:protocol-digest document) (protocol-digest protocol)) [:protocol-binding])
           (when-not (= (count pairs) (count (distinct (map :id pairs)))) [:duplicate-pair])
           (when (= "0" (:resolution-ns protocol)) [:invalid-duration])
           (for [pair pairs [slot expected] [[:reference :reference] [:candidate (if (= :control (:kind pair)) :reference :candidate)]]
                 :let [observation (get pair slot) variant-identity (get protocol expected)]
                 :when (not (and (= expected (:variant observation))
                                 (= variant-identity (select-keys observation [:source :build]))
                                 (= (select-keys protocol [:runtime :work :output :warmup])
                                    (select-keys observation [:runtime :work :output :warmup]))))] :observation-binding)
           (for [observation observations :when (not= :succeeded (:status observation))] :failed-observation)
           (for [observation observations :when (not= :disabled (:cache observation))] :cached-observation)
           (for [observation observations :when (= "0" (:elapsed-ns observation))] :invalid-duration)
           (for [pair pairs :let [a (get-in pair [:reference :index]) b (get-in pair [:candidate :index])]
                 :when (not= (if (= :reference-first (:order pair)) (inc a) (inc b))
                             (if (= :reference-first (:order pair)) b a))] :invalid-order))))))
(m/=> binding-reasons [:=> [:cat pc/Document] [:vector {:max 20} pc/Reason]])

(defn- ratio-rows
  "Retain every observed pair fraction, including invalid zero durations in refused evidence."
  [pairs]
  (mapv (fn [pair] {:id (:id pair) :kind (:kind pair) :order (:order pair)
                    :ratio {:numerator (get-in pair [:candidate :elapsed-ns])
                            :denominator (get-in pair [:reference :elapsed-ns])}}) pairs))
(m/=> ratio-rows [:=> [:cat [:vector {:max 64} pc/Pair]] [:vector {:max 64} pc/RatioRow]])

(defn- with-summaries
  "Add descriptive summaries and median bounds per group, keeping empty groups explicitly absent."
  [observed]
  (reduce (fn [value [field kind order]]
            (let [rows (filterv #(and (= kind (:kind %)) (or (nil? order) (= order (:order %)))) (:ratios observed))]
              (if (seq rows)
                (assoc value field (summarize (mapv :ratio rows) (get-in observed [:policy :median-bound-miss-ppm])))
                value)))
          observed [[:comparison :comparison nil] [:control :control nil]
                    [:reference-first :comparison :reference-first] [:candidate-first :comparison :candidate-first]]))
(m/=> with-summaries [:=> [:cat pc/Observed] pc/Observed])

(defn- sampling-reasons
  "Check prespecified replication, balanced orders and timer resolution; no inferred missing observations.
   A valid schedule gives every block one comparison and one control in the block's order, so
   counting comparison pairs per order counts blocks per order for both kinds."
  [document]
  (let [protocol (:protocol document) policy (:policy protocol) pairs (:pairs document)
        per-order (for [order [:reference-first :candidate-first]]
                    (count (filter #(and (= :comparison (:kind %)) (= order (:order %))) pairs)))]
    (vec (concat
          (when (= :declared-unit-only (get-in document [:resolution-evidence :status])) [:timer-resolution-unverified])
          (when (some #(< % (:minimum-per-order policy)) per-order) [:insufficient-pairs])
          (when (apply not= per-order) [:unbalanced-order])
          (when (some #(neg? (decimal/compare (:elapsed-ns %)
                                              (multiply (:resolution-ns protocol) (str (:resolution-multiple policy)))))
                      (mapcat (juxt :reference :candidate) pairs)) [:timer-resolution])))))
(m/=> sampling-reasons [:=> [:cat pc/Document] [:vector {:max 20} pc/Reason]])

(defn- bounded?
  "True once both the comparison and the A/A control carry median bounds."
  [observed]
  (boolean (and (get-in observed [:comparison :median-lower-bound])
                (get-in observed [:control :median-lower-bound]))))
(m/=> bounded? [:=> [:cat pc/Observed] :boolean])

(defn- direction
  "Require the comparison median's whole bound interval to clear the effect floor and the A/A median.
   The floor clause alone caps each false direction at the declared miss whenever the true median sits
   inside the floor; the A/A clause keeps an effect smaller than the control's own offset unresolved."
  [observed]
  (let [comparison (:comparison observed) control (:control observed) floor (get-in observed [:policy :effect-floor-ppm])]
    (cond
      (and (neg? (fraction-compare (:median-upper-bound comparison) (lower-bound floor)))
           (neg? (fraction-compare (:median-upper-bound comparison) (:median-low control)))) :candidate-faster
      (and (pos? (fraction-compare (:median-lower-bound comparison) (upper-bound floor)))
           (pos? (fraction-compare (:median-lower-bound comparison) (:median-high control)))) :candidate-slower
      :else nil)))
(m/=> direction [:=> [:cat pc/Observed] [:maybe pc/Direction]])

(defn- uncertainty-reasons
  "Apply descriptive median-bound, order and precision checks; none is a confidence interval on causes.
   Bound checks run only once both groups are bounded, which a sufficient replication always gives."
  [observed]
  (let [policy (:policy observed) comparison (:comparison observed) control (:control observed)
        reference-first (:reference-first observed) candidate-first (:candidate-first observed)
        bounded (bounded? observed)]
    (vec (concat
          (when (and bounded
                     (or (neg? (fraction-compare (:median-lower-bound control) (lower-bound (:control-tolerance-ppm policy))))
                         (pos? (fraction-compare (:median-upper-bound control) (upper-bound (:control-tolerance-ppm policy))))))
            [:control-noise])
          (when (and reference-first candidate-first
                     (or (neg? (scaled-compare (:median-low reference-first) (:median-high candidate-first) (lower-bound (:order-tolerance-ppm policy))))
                         (pos? (scaled-compare (:median-high reference-first) (:median-low candidate-first) (upper-bound (:order-tolerance-ppm policy))))))
            [:order-effect])
          (when (and bounded (pos? (scaled-compare (:median-upper-bound comparison) (:median-lower-bound comparison)
                                                   (upper-bound (:spread-tolerance-ppm policy)))))
            [:pair-spread])
          (when (and bounded (nil? (direction observed))) [:effect-unresolved])))))
(m/=> uncertainty-reasons [:=> [:cat pc/Observed] [:vector {:max 20} pc/Reason]])

(defn- consistent-result
  "Project checked observations without altering raw times or inventing resource measurements."
  [document observed]
  (let [summarized (with-summaries observed)
        reasons (into (sampling-reasons document) (uncertainty-reasons summarized))]
    (if (seq reasons)
      (assoc summarized :status :inconclusive :reasons reasons)
      (assoc summarized :status :qualified :reasons [:protocol-satisfied] :direction (direction summarized)))))
(m/=> consistent-result [:=> [:cat pc/Document pc/Observed] pc/Result])

(defn evaluate
  "Keep exact raw pair fractions and descriptive spreads beside finite protocol checks.
   Nil means no companion. Qualified means only that declared checks passed; source and
   witness digests are producer assertions, not proof of comparability, independence or causality."
  [document]
  (cond
    (nil? document) {:status :unavailable :reasons [:no-companion] :ratios []
                     :interpretation :descriptive-declared-protocol}
    (not (m/validate pc/Document document)) (throw (ex-info "Invalid paired evidence shape" {:code :paired-shape}))
    :else
    (let [invalid (binding-reasons document)
          observed {:protocol-digest (:protocol-digest document) :policy (get-in document [:protocol :policy])
                    :resolution-evidence (:resolution-evidence document) :ratios (ratio-rows (:pairs document))
                    :interpretation :descriptive-declared-protocol}]
      (if (seq invalid)
        (assoc observed :status :refused :reasons invalid)
        (consistent-result document observed)))))
(m/=> evaluate [:=> [:cat [:maybe pc/Document]] pc/Result])
