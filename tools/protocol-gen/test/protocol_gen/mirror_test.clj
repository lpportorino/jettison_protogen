(ns protocol-gen.mirror-test
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.mirror :as mirror]))

;; THE ARTEFACT UNDER TEST IS THE PERMISSION TRANSCRIPT — the flat EDN file.
;; `mirror` here is the namespace and the function, which a consumer
;; references; the deftest names below move with those identifiers rather than
;; ahead of them. `protocol-gen.mirror`'s docstring carries the split and
;; reserves `mirror` for the NESTED tree.

(def ^:private groups
  [{:id :b
    :package "p.b"
    :subject-groups []
    :messages [{:id "p.Beat" :proto-name "p_Beat" :origin :minted :access #{:write}
                :fields [{:number 1 :name "seq" :type :uint32
                          :number-source :registry :oneof nil}]
                :oneofs []}]
    :enums []}
   {:id :a
    :package "p.a"
    :subject-groups []
    :messages [{:id "p.Reading" :proto-name "p_Reading" :origin :descriptor
                :access #{:read}
                :fields [{:number 3 :name "value" :type :double
                          :number-source :descriptor :oneof nil}]
                :oneofs []}]
    :enums [{:id "p.Mode" :proto-name "p_Mode" :values [{:number 0 :name "MODE_U"}]}]}])

(deftest the-mirror-carries-what-a-proto-file-cannot
  (let [m (mirror/mirror groups)]
    (testing "direction, which proto has nowhere to put"
      (is (= #{:read} (get-in m [:groups :a :messages "p.Reading" :access])))
      (is (= #{:write} (get-in m [:groups :b :messages "p.Beat" :access]))))
    (testing "and where each number came from, which the text cannot say"
      (is (= :descriptor (get-in m [:groups :a :messages "p.Reading"
                                    :fields "value" :number-source])))
      (is (= :registry (get-in m [:groups :b :messages "p.Beat"
                                  :fields "seq" :number-source]))))))

(deftest the-numbers-in-the-mirror-are-the-numbers-in-the-projection
  ;; Both artefacts are derived from one value in one run, so there is nothing
  ;; for the transcript to be wrong about that the schema could be right about.
  (let [m (mirror/mirror groups)]
    (is (= 3 (get-in m [:groups :a :messages "p.Reading" :fields "value" :number])))
    (is (= 1 (get-in m [:groups :b :messages "p.Beat" :fields "seq" :number])))))

(deftest the-mirror-is-sorted-so-two-runs-write-identical-bytes
  (let [text (pr-str (mirror/mirror groups))]
    (is (< (.indexOf text ":a") (.indexOf text ":b"))
        "groups are emitted in id order, not in policy order")
    (is (= text (pr-str (mirror/mirror groups))))))

(deftest a-group-that-granted-nothing-still-appears
  ;; An empty entry is a fact — "this group was declared and got nothing" — and
  ;; is not the same as the group being absent from the policy.
  (let [m (mirror/mirror [{:id :empty :package "p.e" :subject-groups []
                           :messages [] :enums []}])]
    (is (= {} (get-in m [:groups :empty :messages])))))
