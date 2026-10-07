(ns gate.decimal
  "Exact bounded decimal arithmetic shared by JVM and browser graph operations."
  (:refer-clojure :exclude [compare])
  (:require [gate.contract :as c]
            [malli.core :as m]))

(defn compare
  "Compare canonical natural-number strings without floating-point conversion."
  [a b]
  (let [length-order (#?(:clj clojure.core/compare :cljs cljs.core/compare) (count a) (count b))]
    (if (zero? length-order)
      (#?(:clj clojure.core/compare :cljs cljs.core/compare) a b)
      length-order)))
(m/=> compare [:=> [:cat c/Natural c/Natural] :int])

(defn- digit
  "Read one ASCII digit at an index, or zero beyond the leading edge."
  [s i]
  (if (neg? i) 0
      (- #?(:clj (int (.charAt ^String s i))
            :cljs (.charCodeAt s i)) 48)))
(m/=> digit [:=> [:cat c/Natural [:int {:min -40 :max 39}]] [:int {:min 0 :max 9}]])

(defn add
  "Add exact quantities; refuse a result beyond the documented forty-digit bound."
  [a b]
  (loop [i (dec (count a)) j (dec (count b)) carry 0 digits ()]
    (if (and (neg? i) (neg? j))
      (let [result (apply str (if (pos? carry) (conj digits carry) digits))]
        (when (> (count result) 40)
          (throw (ex-info "Exact quantity overflow" {:code :quantity-overflow})))
        result)
      (let [value (+ (digit a i) (digit b j) carry)]
        (recur (dec i) (dec j) (quot value 10) (conj digits (mod value 10)))))))
(m/=> add [:=> [:cat c/Natural c/Natural] c/Natural])

(defn subtract
  "Subtract exact quantities, refusing negative or reset-like differences."
  [a b]
  (when (neg? (compare a b))
    (throw (ex-info "Negative quantity difference" {:code :negative-quantity})))
  (loop [i (dec (count a)) j (dec (count b)) borrow 0 digits ()]
    (if (neg? i)
      (let [trimmed (drop-while zero? digits)]
        (if (seq trimmed) (apply str trimmed) "0"))
      (let [value (- (digit a i) (digit b j) borrow)]
        (recur (dec i) (dec j) (if (neg? value) 1 0)
               (conj digits (mod value 10)))))))
(m/=> subtract [:=> [:cat c/Natural c/Natural] c/Natural])

(defn before-or-equal?
  "Compare two instants in an already-established common clock domain."
  [a b]
  (not (pos? (compare a b))))
(m/=> before-or-equal? [:=> [:cat c/Natural c/Natural] :boolean])
