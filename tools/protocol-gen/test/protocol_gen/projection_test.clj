(ns protocol-gen.projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.projection :as projection]))

(def ^:private database
  {:messages
   {"p.Reading" {:id "p.Reading" :name "Reading"
                 :fields [{:number 3 :name "value" :type :double}
                          {:number 9 :name "label" :type :string}
                          {:number 14 :name "mode" :type :enum :type-ref "p.Mode"}]}
    "p.Cmd" {:id "p.Cmd" :name "Cmd"
             :fields [{:number 2 :name "start" :type :message :type-ref "p.Start"}
                      {:number 5 :name "stop" :type :message :type-ref "p.Stop"}]
             :oneofs [{:name "action" :required true :fields [2 5]}]}
    "p.Start" {:id "p.Start" :name "Start" :fields []}
    "p.Stop" {:id "p.Stop" :name "Stop" :fields []}}
   :enums {"p.Mode" {:id "p.Mode" :name "Mode"
                     :values [{:number 0 :name "MODE_U"}]}}})

(defn- one-group
  [grants & {:keys [enums]}]
  {:id :g :package "p.g" :grants (vec grants) :enums (vec enums)})

(defn- project
  [grants & {:keys [enums minted]}]
  (projection/project-group database (or minted {}) (one-group grants :enums enums)))

(deftest a-granted-field-keeps-the-descriptors-number-and-says-so
  (let [g (project [{:message "p.Reading" :access #{:read} :fields #{"value" "mode"}}]
                   :enums ["p.Mode"])
        fields (get-in g [:messages 0 :fields])]
    (is (= [3 14] (map :number fields)))
    (is (= [:descriptor :descriptor] (map :number-source fields)))
    (testing "the numbers are the source's, not the granted set's ordering"
      (is (not= [1 2] (map :number fields))))))

(deftest a-withheld-field-is-simply-absent
  ;; This is the projection property. The group's schema does not name what the
  ;; policy did not grant, so a decoder generated from it cannot express it.
  (let [g (project [{:message "p.Reading" :access #{:read} :fields #{"value"}}])]
    (is (= ["value"] (map :name (get-in g [:messages 0 :fields]))))))

(deftest a-message-nobody-granted-is-not-in-the-projection
  (let [g (project [{:message "p.Start" :access #{:write} :fields :all}])]
    (is (= ["p.Start"] (map :id (:messages g))))))

(deftest a-grant-naming-a-field-the-message-does-not-carry-is-refused
  ;; Left unrefused, a typo produces a schema missing the field the author
  ;; meant to grant — which reads as a policy that deliberately withheld it.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"field-not-in-message"
       (project [{:message "p.Reading" :access #{:read} :fields #{"valu"}}]))))

(deftest a-grant-naming-a-message-nothing-carries-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"message-not-in-database"
       (project [{:message "p.Nope" :access #{:read} :fields :all}]))))

(deftest a-granted-field-whose-type-was-not-granted-is-refused
  (testing "an enum the group did not list"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"type-not-granted"
         (project [{:message "p.Reading" :access #{:read} :fields #{"mode"}}]))))
  (testing "a message the group did not grant"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"type-not-granted"
         (project [{:message "p.Cmd" :access #{:write} :fields :all}]))))
  (testing "and granting the type makes the same projection legal"
    (is (some? (project [{:message "p.Cmd" :access #{:write} :fields :all}
                         {:message "p.Start" :access #{:write} :fields :all}
                         {:message "p.Stop" :access #{:write} :fields :all}])))))

(deftest an-enum-the-database-does-not-carry-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"enum-not-in-database"
       (project [{:message "p.Start" :access #{:write} :fields :all}]
                :enums ["p.NoSuchEnum"]))))

(deftest granting-one-message-twice-is-refused
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"message-not-in-database"
       (project [{:message "p.Start" :access #{:write} :fields :all}
                 {:message "p.Start" :access #{:read} :fields :all}]))))

(deftest two-groups-sharing-an-id-are-refused
  ;; They would write the same path twice and the second write would silently
  ;; be the one that survived.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"name-collision"
       (projection/project database {}
                           {:version 1
                            :groups [(one-group [{:message "p.Start"
                                                  :access #{:write} :fields :all}])
                                     (one-group [{:message "p.Stop"
                                                  :access #{:write} :fields :all}])]}))))

(deftest ids-that-flatten-onto-one-emitted-name-are-refused
  ;; `a.b_c` and `a_b.c` both flatten to `a_b_c`; the rule is not injective and
  ;; is not assumed to be.
  (let [colliding {:messages {"a.b_c" {:id "a.b_c" :name "b_c" :fields []}
                              "a_b.c" {:id "a_b.c" :name "c" :fields []}}
                   :enums {}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"name-collision"
         (projection/project-group
          colliding {}
          (one-group [{:message "a.b_c" :access #{:read} :fields :all}
                      {:message "a_b.c" :access #{:read} :fields :all}]))))))

(deftest a-minted-message-projects-beside-a-descriptor-one
  (let [minted {"p.Beat" {:id "p.Beat" :name "Beat"
                          :fields [{:number 1 :name "seq" :type :uint32
                                    :number-source :registry}]}}
        g (project [{:message "p.Start" :access #{:write} :fields :all}
                    {:message "p.Beat" :access #{:write} :fields :all}]
                   :minted minted)]
    (is (= [:descriptor :minted] (map :origin (:messages g))))))

(deftest a-minted-message-with-no-provenance-cannot-be-projected
  ;; The last guard before emission: a number with no recorded source is
  ;; refused even though it is present and legal.
  (let [minted {"p.Beat" {:id "p.Beat" :name "Beat"
                          :fields [{:number 1 :name "seq" :type :uint32}]}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Field number has no recorded provenance"
         (project [{:message "p.Beat" :access #{:write} :fields :all}]
                  :minted minted)))))

(deftest oneof-membership-is-resolved-by-number
  (let [g (project [{:message "p.Cmd" :access #{:write} :fields :all}
                    {:message "p.Start" :access #{:write} :fields :all}
                    {:message "p.Stop" :access #{:write} :fields :all}])
        cmd (first (filter #(= "p.Cmd" (:id %)) (:messages g)))]
    (is (= ["action" "action"] (map :oneof (:fields cmd))))))

(deftest flattening-is-what-it-says
  (is (= "cmd_Gps_SetManualPosition" (projection/proto-name-of "cmd.Gps.SetManualPosition")))
  (is (= "Simple" (projection/proto-name-of "Simple"))))
