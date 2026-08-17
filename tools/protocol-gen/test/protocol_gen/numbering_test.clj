(ns protocol-gen.numbering-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.numbering :as numbering]))

(def ^:private mints
  {"grp.Heartbeat" {:kind :message
                    :name "Heartbeat"
                    :fields [{:name "seq" :type :uint32}
                             {:name "sent_at_ns" :type :uint64}]}})

(defn- load-mint-file
  "Write `value` as a mint file and load it, so a case exercises the real
   LOAD-time schema rather than `m/validate` called directly. The file is
   removed whatever happens."
  [value]
  (let [f (java.io.File/createTempFile "protocol-gen-mints" ".edn")]
    (try
      (spit f (pr-str value))
      (numbering/load-mints (java.io.File/.getPath f))
      (finally (io/delete-file f true)))))

(deftest a-mint-declares-no-number-and-cannot
  ;; The schema is CLOSED and has no :number key, so a number cannot be written
  ;; into a mint even deliberately. That is what leaves the registry as the
  ;; only possible source.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Malformed minted declarations"
       (load-mint-file {"grp.M" {:kind :message
                                 :name "M"
                                 :fields [{:name "a" :type :int32 :number 1}]}}))))

(deftest a-declaration-with-no-kind-is-refused-rather-than-guessed
  ;; The sum is tagged, so a declaration is never resolved by whichever key a
  ;; reader happens to test for first. A missing tag, an unknown one, and a
  ;; message body under the enum tag are all refused at load.
  (doseq [[label value]
          [["no :kind at all" {"grp.M" {:name "M" :fields [{:name "a" :type :int32}]}}]
           ["an unknown :kind" {"grp.M" {:kind :service :name "M" :fields []}}]
           ["a message body tagged :enum" {"grp.M" {:kind :enum :name "M"
                                                    :fields [{:name "a" :type :int32}]}}]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Malformed minted declarations"
         (load-mint-file value))
        (str "accepted a declaration with " label))))

(deftest generating-throws-on-an-unpinned-field
  ;; THE assign-once property. Nothing on the generation path may invent a
  ;; number, so a policy edit cannot move one.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Message not in the field-number registry"
       (numbering/apply-numbering {} mints)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Field not in the field-number registry"
       (numbering/apply-numbering {"grp.Heartbeat" {"seq" 1}} mints))))

(deftest an-unpinned-field-throws-BEFORE-any-oneof-is-resolved
  ;; ORDERING, and it is what keeps assign-once untouched by the oneof work: a
  ;; oneof member is resolved against the fields once they are STAMPED, so a
  ;; message with an unpinned field must die on the registry rather than
  ;; reporting a member it could not resolve. The two diagnoses point at
  ;; different files, and only one of them is the defect.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Field not in the field-number registry"
       (numbering/apply-numbering
        {"grp.M" {"a" 3}}
        {"grp.M" {:kind :message
                  :name "M"
                  :fields [{:name "a" :type :int32} {:name "unpinned" :type :int32}]
                  :oneofs [{:name "pick" :required false :fields ["a" "unpinned"]}]}}))))

(deftest a-pinned-mint-is-stamped-with-the-registry-number-and-says-so
  (let [out (numbering/apply-numbering {"grp.Heartbeat" {"seq" 4 "sent_at_ns" 9}} mints)
        fields (get-in out [:messages "grp.Heartbeat" :fields])]
    (is (= [4 9] (map :number fields)))
    (is (= [:registry :registry] (map :number-source fields)))
    (testing "the numbers are the registry's, not the declaration order"
      (is (not= [1 2] (map :number fields))))
    (testing "and the result is database-shaped, so a projection cannot tell it apart"
      (is (= #{:messages :enums} (set (keys out))))
      (is (= {} (:enums out))))))

(defn- mint-with-oneofs
  "A one-message mint carrying `field-names` and `oneofs`."
  [field-names oneofs]
  {"grp.M" {:kind :message
            :name "M"
            :fields (mapv (fn [n] {:name n :type :int32}) field-names)
            :oneofs (vec oneofs)}})

(deftest a-minted-oneof-names-its-members-by-name-and-gets-registry-numbers
  ;; A mint declares no numbers, so a name is the only identifier it can name a
  ;; member with. The stamping pass turns each name into the number the
  ;; REGISTRY supplied for that field — nothing else may supply one.
  (let [out (numbering/apply-numbering
             {"grp.M" {"a" 11 "b" 4}}
             (mint-with-oneofs ["a" "b"] [{:name "pick" :required true :fields ["b" "a"]}]))
        msg (get-in out [:messages "grp.M"])]
    (is (= [{:name "pick" :required true :fields [4 11]}] (:oneofs msg)))
    (testing "the members are the registry's numbers, not positions"
      (is (not= [1 2] (:fields (first (:oneofs msg))))))
    (testing "and the emitted shape is the database's own, so nothing downstream differs"
      (is (every? int? (:fields (first (:oneofs msg))))))))

(deftest a-minted-oneof-naming-a-member-the-message-does-not-carry-is-refused
  ;; Dropped instead, it emits a oneof one arm short — or none at all when that
  ;; was its only arm — and the file then reads as a policy that withheld it.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"oneof-member-absent"
       (numbering/apply-numbering
        {"grp.M" {"a" 3}}
        (mint-with-oneofs ["a"] [{:name "pick" :required false :fields ["a" "typo"]}])))))

(deftest a-oneof-member-resolves-against-the-fields-and-never-the-registry
  ;; THE REASON THAT DISTINCTION EXISTS. A registry entry outlives the field it
  ;; pinned — a retired pin keeps its number for ever — so resolving a member
  ;; name against the ENTRY would hand "retired" the number 8 and emit a oneof
  ;; member naming no live field. Resolving against the declared fields refuses
  ;; it under the name the author wrote.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"oneof-member-absent"
       (numbering/apply-numbering
        {"grp.M" {"a" 5 "retired" 8}}
        (mint-with-oneofs ["a"] [{:name "pick" :required false :fields ["retired"]}])))))

(deftest one-field-claimed-by-two-oneofs-is-refused
  ;; A proto field belongs to at most one oneof, and the approximation is
  ;; SILENT: `projection/oneof-of` takes the first oneof carrying the number, so
  ;; the losing block emits short or vanishes and the file still compiles.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"oneof-member-shared"
       (numbering/apply-numbering
        {"grp.M" {"a" 3 "b" 6}}
        (mint-with-oneofs ["a" "b"] [{:name "one" :required false :fields ["a"]}
                                     {:name "two" :required false :fields ["a" "b"]}]))))
  (testing "including one oneof naming the same field twice"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"oneof-member-shared"
         (numbering/apply-numbering
          {"grp.M" {"a" 3}}
          (mint-with-oneofs ["a"] [{:name "one" :required false :fields ["a" "a"]}])))))
  (testing "and two oneofs over DIFFERENT fields are fine"
    (is (some? (numbering/apply-numbering
                {"grp.M" {"a" 3 "b" 6}}
                (mint-with-oneofs ["a" "b"] [{:name "one" :required false :fields ["a"]}
                                             {:name "two" :required false :fields ["b"]}]))))))

(deftest a-minted-enum-carries-its-own-numbers-and-the-registry-is-not-consulted
  ;; The registry's value schema excludes 0, and proto3 requires an enum member
  ;; numbered 0 — so a registry could not hold a legal enum value set at all.
  (let [out (numbering/apply-numbering
             {}
             {"grp.E" {:kind :enum :name "E"
                       :values [{:number 0 :name "E_UNSPECIFIED"}
                                {:number 7 :name "E_ON"}]}})]
    (is (= {"grp.E" {:id "grp.E" :name "E"
                     :values [{:number 0 :name "E_UNSPECIFIED"}
                              {:number 7 :name "E_ON"}]}}
           (:enums out)))
    (testing "an empty registry is no obstacle — nothing about an enum is pinned"
      (is (= {} (:messages out))))))

(deftest a-minted-enum-is-held-to-the-floor-protoc-would-have-supplied
  ;; A descriptor database comes from protoc and so cannot carry an enum proto3
  ;; rejects. A mint has no such upstream, so the schema supplies the floor.
  (doseq [[label values]
          [["no member numbered 0" [{:number 1 :name "E_ON"}]]
           ["an empty value set" []]
           ["two members sharing a number" [{:number 0 :name "E_U"} {:number 0 :name "E_ON"}]]
           ["two members sharing a name" [{:number 0 :name "E_U"} {:number 1 :name "E_U"}]]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Malformed minted declarations"
         (load-mint-file {"grp.E" {:kind :enum :name "E" :values values}}))
        (str "accepted an enum with " label)))
  (testing "and a well-formed one loads"
    (is (map? (load-mint-file {"grp.E" {:kind :enum :name "E"
                                        :values [{:number 0 :name "E_U"}
                                                 {:number 4 :name "E_ON"}]}})))))

(deftest reconcile-is-append-only-and-idempotent
  (let [once (numbering/reconcile {} mints)]
    (is (= {"grp.Heartbeat" {"seq" 1 "sent_at_ns" 2}} once))
    (is (= once (numbering/reconcile once mints)))
    (testing "an existing pin is never moved"
      (let [pinned {"grp.Heartbeat" {"seq" 7}}]
        (is (= 7 (get-in (numbering/reconcile pinned mints) ["grp.Heartbeat" "seq"])))))))

(deftest reconcile-skips-an-enum-declaration-rather-than-emptying-an-entry
  ;; An empty entry under an enum's id would put a name in the registry pinning
  ;; nothing, and a later reader could not tell it from a message whose fields
  ;; had all been retired — a state the registry is meant to record faithfully.
  (let [with-enum (assoc mints "grp.E" {:kind :enum :name "E"
                                        :values [{:number 0 :name "E_U"}]})
        grown (numbering/reconcile {} with-enum)]
    (is (= {"grp.Heartbeat" {"seq" 1 "sent_at_ns" 2}} grown))
    (is (not (contains? grown "grp.E")))))

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
