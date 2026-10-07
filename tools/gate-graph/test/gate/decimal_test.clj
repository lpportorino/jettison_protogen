(ns gate.decimal-test
  "Independent JVM integer oracles for portable decimal arithmetic."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.contract :as c]
            [gate.decimal :as d]
            [malli.core :as m]))

(def decimal-generator
  (gen/fmap #(str (bigint (apply str %)))
            (gen/vector (gen/choose 0 9) 1 36)))

(deftest exact-past-javascript-integer-range
  (is (= "9007199254740994" (d/add "9007199254740993" "1")))
  (is (= "1" (d/subtract "9007199254740994" "9007199254740993")))
  (is (pos? (d/compare "10" "9")))
  (is (= "0" (d/subtract "12345678901234567890" "12345678901234567890"))))

(deftest arithmetic-agrees-with-independent-bigint-oracle
  (is (:pass?
       (tc/quick-check
        500
        (prop/for-all [a decimal-generator b decimal-generator]
                      (let [x (bigint a) y (bigint b)
                            larger (if (>= x y) a b) smaller (if (>= x y) b a)]
                        (and (= (str (+ x y)) (d/add a b))
                             (= (str (- (max x y) (min x y))) (d/subtract larger smaller))
                             (= (compare x y) (compare (d/compare a b) 0)))))
        :seed 314159))))

(deftest overflow-and-negative-differences-are-refusals
  (is (= :quantity-overflow
         (try (d/add (apply str (repeat 40 "9")) "1")
              (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
  (is (= :negative-quantity
         (try (d/subtract "1" "2")
              (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))

(deftest natural-grammar-is-canonical-and-closed
  (doseq [value ["" "01" "-1" "+1" "1.0" "1e3" "1\n" 1 true nil
                 (apply str (repeat 41 "1"))]]
    (is (false? (m/validate c/Natural value)) (pr-str value))))
