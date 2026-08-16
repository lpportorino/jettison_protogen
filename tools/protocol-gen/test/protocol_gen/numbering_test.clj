(ns protocol-gen.numbering-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.numbering :as numbering]))

(def ^:private mints
  {"grp.Heartbeat" {:name "Heartbeat"
                    :fields [{:name "seq" :type :uint32}
                             {:name "sent_at_ns" :type :uint64}]}})

(deftest a-mint-declares-no-number-and-cannot
  ;; The schema is CLOSED and has no :number key, so a number cannot be written
  ;; into a mint even deliberately. That is what leaves the registry as the
  ;; only possible source.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Malformed minted-message declarations"
       (let [f (java.io.File/createTempFile "protocol-gen-mints" ".edn")]
         (try
           (spit f (pr-str {"grp.M" {:name "M" :fields [{:name "a" :type :int32
                                                         :number 1}]}}))
           (numbering/load-mints (java.io.File/.getPath f))
           (finally (io/delete-file f true)))))))

(deftest generating-throws-on-an-unpinned-field
  ;; THE assign-once property. Nothing on the generation path may invent a
  ;; number, so a policy edit cannot move one.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Message not in the field-number registry"
       (numbering/apply-numbering {} mints)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Field not in the field-number registry"
       (numbering/apply-numbering {"grp.Heartbeat" {"seq" 1}} mints))))

(deftest a-pinned-mint-is-stamped-with-the-registry-number-and-says-so
  (let [out (numbering/apply-numbering {"grp.Heartbeat" {"seq" 4 "sent_at_ns" 9}} mints)
        fields (get-in out ["grp.Heartbeat" :fields])]
    (is (= [4 9] (map :number fields)))
    (is (= [:registry :registry] (map :number-source fields)))
    (testing "the numbers are the registry's, not the declaration order"
      (is (not= [1 2] (map :number fields))))))

(deftest reconcile-is-append-only-and-idempotent
  (let [once (numbering/reconcile {} mints)]
    (is (= {"grp.Heartbeat" {"seq" 1 "sent_at_ns" 2}} once))
    (is (= once (numbering/reconcile once mints)))
    (testing "an existing pin is never moved"
      (let [pinned {"grp.Heartbeat" {"seq" 7}}]
        (is (= 7 (get-in (numbering/reconcile pinned mints) ["grp.Heartbeat" "seq"])))))))

(deftest a-retired-field-keeps-its-number-for-ever
  ;; The deliberate divergence from the enum registry beside this one: a number
  ;; reused for a DIFFERENT field is the hazard proto's `reserved` exists to
  ;; prevent, so next-free walks above every pin rather than into a gap.
  (let [with-retired {"grp.Heartbeat" {"seq" 1 "retired_field" 2}}
        grown (numbering/reconcile with-retired mints)]
    (is (= 3 (get-in grown ["grp.Heartbeat" "sent_at_ns"])))
    (is (= 2 (get-in grown ["grp.Heartbeat" "retired_field"])))))

(deftest next-free-starts-at-one-and-steps-over-the-reserved-range
  (is (= 1 (numbering/next-free-number {})))
  (is (= 6 (numbering/next-free-number {"a" 5})))
  (testing "18999 would step to 19000, which protoc refuses"
    (is (= 20000 (numbering/next-free-number {"a" 18999}))))
  (is (= 20001 (numbering/next-free-number {"a" 20000}))))

(deftest a-registry-pinning-an-illegal-number-is-refused-at-load
  (doseq [bad [0 19000 19999 536870912]]
    (let [f (java.io.File/createTempFile "protocol-gen-reg" ".edn")]
      (try
        (spit f (pr-str {"grp.M" {"a" bad}}))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Malformed field-number registry"
             (numbering/load-registry (java.io.File/.getPath f)))
            (str "registry pinning " bad " was accepted"))
        (finally (io/delete-file f true)))))
  (testing "and the legal extremes load"
    (let [f (java.io.File/createTempFile "protocol-gen-reg" ".edn")]
      (try
        (spit f (pr-str {"grp.M" {"a" 1 "b" 536870911 "c" 18999 "d" 20000}}))
        (is (map? (numbering/load-registry (java.io.File/.getPath f))))
        (finally (io/delete-file f true))))))

(deftest save-then-load-round-trips-and-sorts
  (let [f (java.io.File/createTempFile "protocol-gen-reg" ".edn")
        path (java.io.File/.getPath f)
        reg {"grp.Z" {"b" 2 "a" 1} "grp.A" {"x" 1}}]
    (try
      (numbering/save-registry! path reg)
      (is (= reg (numbering/load-registry path)))
      (testing "the file is written sorted, so a reconcile diffs cleanly"
        (let [text (slurp path)]
          (is (< (.indexOf text "grp.A") (.indexOf text "grp.Z")))
          (is (< (.indexOf text "\"a\"") (.indexOf text "\"b\"")))))
      (finally (io/delete-file f true)))))

(deftest emission-is-blocked-on-a-number-with-no-provenance
  (let [ok [{:id "grp.M" :fields [{:number 1 :name "a" :type :int32
                                   :number-source :descriptor}]}]]
    (is (= ok (numbering/assert-stamped! ok)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Field number has no recorded provenance"
         (numbering/assert-stamped!
          [{:id "grp.M" :fields [{:number 1 :name "a" :type :int32}]}])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Field number has no recorded provenance"
         (numbering/assert-stamped!
          [{:id "grp.M" :fields [{:number 1 :name "a" :type :int32
                                  :number-source :invented}]}])))))
