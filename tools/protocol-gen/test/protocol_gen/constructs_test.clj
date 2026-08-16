(ns protocol-gen.constructs-test
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.constructs :as constructs]))

(defn- db-with
  "A one-message database carrying `fields`, plus whatever extra messages and
   enums a case needs to make a reference resolve."
  [fields & {:keys [messages enums oneofs]}]
  {:messages (merge {"p.M" {:id "p.M" :name "M" :fields (vec fields)
                            :oneofs (vec oneofs)}}
                    messages)
   :enums (or enums {})})

(defn- reasons
  [database]
  (set (map :reason (constructs/message-refusals database (get-in database [:messages "p.M"])))))

(deftest a-plain-scalar-field-is-expressible
  (is (empty? (reasons (db-with [{:number 1 :name "a" :type :int32}])))))

(deftest an-unmapped-type-is-refused-rather-than-guessed
  ;; :unknown is the producing parser's own fallback for a descriptor type it
  ;; has no mapping for. Emitting it as anything would be an invention.
  (is (contains? (reasons (db-with [{:number 1 :name "a" :type :unknown}]))
                 :unknown-field-type)))

(deftest a-referring-type-with-no-reference-is-refused
  (is (contains? (reasons (db-with [{:number 1 :name "a" :type :message}]))
                 :missing-type-ref)))

(deftest an-unresolvable-reference-is-refused
  (testing "this is the class that catches a map field's entry message and a
            nested enum — the producer drops both and leaves the reference"
    (is (contains? (reasons (db-with [{:number 1 :name "a" :type :message
                                       :type-ref "p.M.NotThere" :repeated true}]))
                   :unresolved-type-ref))))

(deftest a-resolvable-reference-is-expressible
  (is (empty? (reasons (db-with [{:number 1 :name "a" :type :message :type-ref "p.Other"}]
                                :messages {"p.Other" {:id "p.Other" :name "Other" :fields []}}))))
  (is (empty? (reasons (db-with [{:number 1 :name "a" :type :enum :type-ref "p.E"}]
                                :enums {"p.E" {:id "p.E" :name "E"
                                               :values [{:number 0 :name "E_U"}]}})))))

(deftest protocs-reserved-number-range-is-refused-at-both-ends
  (is (contains? (reasons (db-with [{:number 19000 :name "a" :type :int32}]))
                 :field-number-reserved))
  (is (contains? (reasons (db-with [{:number 19999 :name "a" :type :int32}]))
                 :field-number-reserved))
  (testing "and the numbers just outside it are not"
    (is (empty? (reasons (db-with [{:number 18999 :name "a" :type :int32}]))))
    (is (empty? (reasons (db-with [{:number 20000 :name "a" :type :int32}]))))))

(deftest numbers-outside-the-legal-range-are-refused
  (is (contains? (reasons (db-with [{:number 0 :name "a" :type :int32}]))
                 :field-number-out-of-range))
  (is (contains? (reasons (db-with [{:number 536870912 :name "a" :type :int32}]))
                 :field-number-out-of-range))
  (testing "and the extremes of the legal range are not"
    (is (empty? (reasons (db-with [{:number 1 :name "a" :type :int32}]))))
    (is (empty? (reasons (db-with [{:number 536870911 :name "a" :type :int32}]))))))

(deftest two-fields-sharing-a-number-are-refused
  (is (contains? (reasons (db-with [{:number 3 :name "a" :type :int32}
                                    {:number 3 :name "b" :type :int32}]))
                 :duplicate-field-number)))

(deftest a-oneof-naming-an-absent-member-is-refused
  (is (contains? (reasons (db-with [{:number 1 :name "a" :type :int32}]
                                   :oneofs [{:name "c" :required true :fields [1 2]}]))
                 :oneof-member-absent)))

(deftest every-reason-this-namespace-can-produce-is-in-the-closed-set
  ;; A free-text reason would let two defects wear one message, and a canary
  ;; could then attribute a red to the wrong clause.
  (let [database (db-with [{:number 19000 :name "a" :type :unknown}
                           {:number 19000 :name "b" :type :message}]
                          :oneofs [{:name "c" :required false :fields [42]}])]
    (is (every? constructs/refusal-reasons
                (map :reason (constructs/message-refusals
                              database (get-in database [:messages "p.M"])))))))

(deftest survey-counts-what-it-says-it-counts
  (let [database (db-with [{:number 1 :name "a" :type :int32}
                           {:number 2 :name "b" :type :string :repeated true}
                           {:number 19000 :name "c" :type :int32}])
        s (constructs/survey database)]
    (is (= 1 (:messages s)))
    (is (= 3 (:fields s)))
    (is (= 1 (:repeated-fields s)))
    (is (= {:int32 2 :string 1} (:types s)))
    (is (= 1 (:refusals s)))
    (is (= {:field-number-reserved 1} (:by-reason s)))
    (is (= ["p.M.c"] (get-in s [:subjects :field-number-reserved])))))
