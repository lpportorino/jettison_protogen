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
  [id subject-groups]
  (projection/project-group
   database no-mints
   (cond-> {:id id :package "p.g"
            :grants [{:message "p.Stop" :access #{:read} :fields :all}]}
     subject-groups (assoc :subject-groups subject-groups))))

(deftest the-table-is-the-cross-product-of-groups-and-declared-subject-groups
  ;; TOTALITY. A table of only the permitted entries would be well-formed and
  ;; smaller, and nothing else the generator emits could contradict it.
  (let [[row] (state-table/rows declared [(project :g ["telemetry"])])]
    (is (= ["diagnostics" "telemetry" "thermal"] (map :subject-group (:entries row))))
    (is (= [false true false] (map :permitted (:entries row))))))

(deftest a-group-that-receives-nothing-is-rows-of-false-not-an-absence
  ;; The case a policy is most likely to want and an emission most likely to
  ;; render as silence.
  (let [[row] (state-table/rows declared [(project :g nil)])]
    (is (= 3 (count (:entries row))))
    (is (every? (complement :permitted) (:entries row)))))

(deftest rows-are-emitted-in-group-id-order-and-subject-group-order
  (let [rows (state-table/rows declared [(project :zulu ["thermal"])
                                         (project :alpha ["thermal"])])]
    (is (= [:alpha :zulu] (map :id rows)))
    (is (= declared (map :subject-group (:entries (first rows)))))))

(deftest the-emitted-fragment-declares-nothing-and-carries-the-universe
  ;; A read path narrows against this file, so it must be able to see the set
  ;; its rows are total over — a narrower table is still a well-formed one.
  (let [rust (state-table/module declared
                                 (state-table/rows declared [(project :g ["telemetry"])]))]
    (is (str/includes? rust "pub static SUBJECT_GROUPS: &[&str]"))
    (is (str/includes? rust "pub static GROUP_SUBJECT_GROUPS: &[(&str, &[(&str, bool)])]"))
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
    (is (str/includes? rust "pub static SUBJECT_GROUPS: &[&str] = &[\n];"))
    (is (str/includes? rust "(\"g\", &[\n    ]),"))))

(deftest a-group-naming-an-undeclared-subject-group-is-reported
  (let [p {:version 1
           :subject-groups ["telemetry"]
           :groups [{:id :g :package "p.g"
                     :grants [{:message "p.Stop" :access #{:read} :fields :all}]
                     :subject-groups ["telemetry" "thermla"]}]}]
    (is (= ["telemetry"] (policy/declared-subject-groups p)))
    (is (= ["thermla"] (policy/undeclared-subject-groups (policy/declared-subject-groups p)
                                                         (first (:groups p)))))
    (testing "and the projection refuses it by name"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"subject-group-not-declared"
           (projection/project database no-mints p))))))

(deftest the-policy-shape-refuses-a-repeated-subject-group
  ;; A repeat names one subject group twice and means nothing; folding it
  ;; silently is how a policy stops saying what it looks like it says.
  (let [p {:version 1
           :subject-groups ["telemetry" "telemetry"]
           :groups [{:id :g :package "p.g"
                     :grants [{:message "p.Stop" :access #{:read} :fields :all}]}]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Not an access policy"
         ((instrument/uninstrumented #'policy/validate!) p "<inline>")))))

(deftest the-emitted-banner-calls-the-flat-artefact-the-transcript
  ;; THE WORD MIRROR IS THE NESTED TREE'S ALONE. `protocol-gen.mirror` and
  ;; `protocol-gen.permission-tree` both state the split in their docstrings,
  ;; and the tree's own banner — emitted into the same run directory as this
  ;; one — already spells the flat artefact TRANSCRIPT. A banner here calling
  ;; it a mirror republishes the retired vocabulary to every consumer on every
  ;; regeneration, and leaves two banners in one output directory naming one
  ;; artefact two ways.
  ;;
  ;; THE POSITIVE ASSERTION CARRIES THE NON-VACUITY. It is a non-zero
  ;; expectation, so it reddens over an empty module; the absence assertion
  ;; beside it structurally cannot, since its pass value equals its
  ;; nothing-ran value.
  ;;
  ;; THE ABSENCE TOKEN IS THE RETIRED BYTES AND NOT THE BARE WORD, because
  ;; `permission mirror` is a SANCTIONED name here — `protocol-gen.permission-tree`
  ;; opens `The NESTED permission mirror`. A banner widened to name the tree
  ;; that way would be correct, and a bare-word probe would redden it.
  (let [rust (state-table/module declared
                                 (state-table/rows declared [(project :g ["telemetry"])]))]
    (is (str/includes? rust "the permission transcript"))
    (is (not (str/includes? rust "the flat permission mirror")))))
