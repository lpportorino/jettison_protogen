(ns protocol-gen.state-table-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.instrument :as instrument]
            [protocol-gen.policy :as policy]
            [protocol-gen.projection :as projection]
            [protocol-gen.state-table :as state-table]))

(def ^:private database
  {:messages {"p.Stop" {:id "p.Stop" :name "Stop" :fields []}}
   :enums {}})

(def ^:private no-mints
  {:messages {} :enums {}})

(def ^:private declared ["diagnostics" "telemetry" "thermal"])

(defn- project
  [id subsystems]
  (projection/project-group
   database no-mints
   (cond-> {:id id :package "p.g"
            :grants [{:message "p.Stop" :access #{:read} :fields :all}]}
     subsystems (assoc :state-subsystems subsystems))))

(deftest the-table-is-the-cross-product-of-groups-and-declared-subsystems
  ;; TOTALITY. A table of only the permitted entries would be well-formed and
  ;; smaller, and nothing else the generator emits could contradict it.
  (let [[row] (state-table/rows declared [(project :g ["telemetry"])])]
    (is (= ["diagnostics" "telemetry" "thermal"] (map :subsystem (:entries row))))
    (is (= [false true false] (map :permitted (:entries row))))))

(deftest a-group-that-receives-nothing-is-rows-of-false-not-an-absence
  ;; The case a policy is most likely to want and an emission most likely to
  ;; render as silence.
  (let [[row] (state-table/rows declared [(project :g nil)])]
    (is (= 3 (count (:entries row))))
    (is (every? (complement :permitted) (:entries row)))))

(deftest rows-are-emitted-in-group-id-order-and-subsystem-order
  (let [rows (state-table/rows declared [(project :zulu ["thermal"])
                                         (project :alpha ["thermal"])])]
    (is (= [:alpha :zulu] (map :id rows)))
    (is (= declared (map :subsystem (:entries (first rows)))))))

(deftest the-emitted-fragment-declares-nothing-and-carries-the-universe
  ;; A read path narrows against this file, so it must be able to see the set
  ;; its rows are total over — a narrower table is still a well-formed one.
  (let [rust (state-table/module declared
                                 (state-table/rows declared [(project :g ["telemetry"])]))]
    (is (str/includes? rust "pub static STATE_SUBSYSTEMS: &[&str]"))
    (is (str/includes? rust "pub static GROUP_STATE_SUBSYSTEMS: &[(&str, &[(&str, bool)])]"))
    (is (str/includes? rust "(\"telemetry\", true),"))
    (is (str/includes? rust "(\"diagnostics\", false),"))
    (testing "no use, no type declaration, nothing assumed in scope"
      (is (not (str/includes? rust "use ")))
      (is (not (str/includes? rust "enum ")))
      (is (not (str/includes? rust "struct "))))))

(deftest a-policy-with-no-state-axis-emits-an-empty-universe
  ;; Honest rather than absent: the group tuples are still all there, and the
  ;; universe's own length is what tells a vacuous table from a narrow one.
  (let [rust (state-table/module [] (state-table/rows [] [(project :g nil)]))]
    (is (str/includes? rust "pub static STATE_SUBSYSTEMS: &[&str] = &[\n];"))
    (is (str/includes? rust "(\"g\", &[\n    ]),"))))

(deftest a-group-naming-an-undeclared-subsystem-is-reported
  (let [p {:version 1
           :state-subsystems ["telemetry"]
           :groups [{:id :g :package "p.g"
                     :grants [{:message "p.Stop" :access #{:read} :fields :all}]
                     :state-subsystems ["telemetry" "thermla"]}]}]
    (is (= ["telemetry"] (policy/declared-subsystems p)))
    (is (= ["thermla"] (policy/undeclared-subsystems (policy/declared-subsystems p)
                                                     (first (:groups p)))))
    (testing "and the projection refuses it by name"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"state-subsystem-not-declared"
           (projection/project database no-mints p))))))

(deftest the-policy-shape-refuses-a-repeated-subsystem
  ;; A repeat names one subsystem twice and means nothing; folding it silently
  ;; is how a policy stops saying what it looks like it says.
  (let [p {:version 1
           :state-subsystems ["telemetry" "telemetry"]
           :groups [{:id :g :package "p.g"
                     :grants [{:message "p.Stop" :access #{:read} :fields :all}]}]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Not an access policy"
         ((instrument/uninstrumented #'policy/validate!) p "<inline>")))))
