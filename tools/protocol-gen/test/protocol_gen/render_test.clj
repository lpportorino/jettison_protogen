(ns protocol-gen.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [protocol-gen.render :as render]))

(def ^:private no-options (fn [_msg-id _fld] []))

(def ^:private group
  {:id :g
   :package "p.g"
   :messages
   [{:id "p.Cmd" :proto-name "p_Cmd" :origin :descriptor :access #{:write}
     :fields [{:number 2 :name "start" :type :message :type-ref "p.Start"
               :oneof "action" :number-source :descriptor}
              {:number 9 :name "flag" :type :bool :oneof nil
               :number-source :descriptor}]
     :oneofs [{:name "action" :required true :fields [2 5]}
              {:name "unused" :required false :fields [5]}]}
    {:id "p.Start" :proto-name "p_Start" :origin :descriptor :access #{:write}
     :fields [] :oneofs []}]
   :enums [{:id "p.Mode" :proto-name "p_Mode" :values [{:number 0 :name "MODE_U"}]}]})

(deftest a-reference-becomes-the-emitted-name-of-the-thing-it-points-at
  (let [f (render/render-group group no-options [])
        cmd (first (:messages f))]
    (is (= "p_Start" (:proto-type (first (:fields cmd)))))
    (testing "and a scalar is its own spelling"
      (is (= "bool" (:proto-type (second (:fields cmd))))))))

(deftest numbers-are-copied-not-recomputed
  (let [cmd (first (:messages (render/render-group group no-options [])))]
    (is (= [2 9] (map :number (:fields cmd))))))

(deftest a-oneof-with-no-surviving-member-is-not-declared
  ;; `unused` names only field 5, which this group was not granted. Declaring
  ;; it would put a name in the file for a construct that is not there.
  (let [cmd (first (:messages (render/render-group group no-options [])))]
    (is (= ["action"] (map :name (:oneofs cmd))))))

(deftest options-come-from-the-caller-and-reach-the-field
  (let [f (render/render-group group
                               (fn [msg-id fld] [(str msg-id "/" (:name fld))])
                               [])
        cmd (first (:messages f))]
    (is (= [["p.Cmd/start"] ["p.Cmd/flag"]] (map :options (:fields cmd))))))

(deftest an-unresolvable-reference-throws-rather-than-emitting-a-raw-id
  ;; The projection's closure check has already refused this case, so reaching
  ;; it means the two passes disagree — a defect in the tool, not in its input.
  (let [broken (assoc-in group [:messages 0 :fields 0 :type-ref] "p.Absent")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Reference survived the closure check unresolved"
         (render/render-group broken no-options [])))))
