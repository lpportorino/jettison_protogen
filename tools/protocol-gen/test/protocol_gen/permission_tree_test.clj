(ns protocol-gen.permission-tree-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.instrument :as instrument]
            [protocol-gen.permission-tree :as permission-tree]
            [protocol-gen.projection :as projection]))

(def ^:private database
  {:messages
   {"p.Reading" {:id "p.Reading" :name "Reading"
                 :fields [{:number 3 :name "value" :type :double}
                          {:number 9 :name "label" :type :string}
                          {:number 14 :name "detail" :type :message :type-ref "p.Detail"}]}
    "p.Detail" {:id "p.Detail" :name "Detail"
                :fields [{:number 6 :name "note" :type :string}]}
    "p.Node" {:id "p.Node" :name "Node"
              :fields [{:number 1 :name "next" :type :message :type-ref "p.Node"}]}}
   :enums {}})

(def ^:private no-mints
  {:messages {} :enums {}})

(defn- project
  [id grants]
  (projection/project-group database no-mints
                            {:id id :package "p.g" :grants (vec grants) :enums []}))

(defn- tree-of
  [id grants]
  (first (permission-tree/trees database [(project id grants)])))

(defn- node-at
  "The node reached by following `names` from `roots`."
  [roots names]
  (reduce (fn [level nm]
            (or (first (filter #(= nm (:name %)) (if (map? level) (:children level) level)))
                (throw (ex-info "no such node" {:name nm}))))
          roots
          names))

(deftest a-message-node-carries-one-child-per-SOURCE-field
  ;; TOTALITY. The group is granted one of three fields, and a tree listing only
  ;; the grant would be internally consistent with the group's own `.proto` —
  ;; which never names the other two either.
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"value"}}])
        root (node-at (:roots t) ["p.Reading"])]
    (is (= ["value" "label" "detail"] (map :name (:children root))))
    (is (= [3 9 14] (map :tag (:children root))))
    (testing "the root is not a field, so it carries the one tag no field can"
      (is (= 0 (:tag root))))))

(deftest a-grant-is-message-grained-so-a-kept-field-INHERITS
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"value"}}])
        root (node-at (:roots t) ["p.Reading"])]
    (is (= :allow (:permission root)))
    (is (= [:inherit :deny :deny] (map :permission (:children root))))))

(deftest a-granted-message-typed-field-expands
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"detail"}}
                       {:message "p.Detail" :access #{:read} :fields :all}])
        note (node-at (:roots t) ["p.Reading" "detail" "note"])]
    (is (= {:tag 6 :name "note" :permission :inherit :children []} note))))

(deftest a-DENIED-message-typed-field-is-terminal
  ;; The bound on the one disclosure this artefact makes: it names the fields of
  ;; messages the group holds a grant for, and never the interior of a denial.
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"value"}}])
        detail (node-at (:roots t) ["p.Reading" "detail"])]
    (is (= :deny (:permission detail)))
    (is (= [] (:children detail)))))

(deftest a-self-referential-grant-is-refused
  ;; A static tree is finite and a cycle is not; without the clause the
  ;; expansion runs until the stack is gone.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"permission-cycle"
       (tree-of :g [{:message "p.Node" :access #{:read} :fields :all}]))))

(deftest two-group-ids-that-flatten-onto-one-static-are-refused
  (let [grants [{:message "p.Detail" :access #{:read} :fields :all}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"name-collision"
         (permission-tree/trees database [(project :relay-a grants)
                                          (project :relay_a grants)])))))

(deftest static-name-is-derived-and-is-not-injective
  ;; The reason the refusal above exists, stated as a fact rather than inferred
  ;; from it.
  (is (= "SENSOR_READER" (permission-tree/static-name :sensor-reader)))
  (is (= (permission-tree/static-name :relay-a) (permission-tree/static-name :relay_a))))

(deftest a-permission-outside-the-closed-set-throws-rather-than-rendering
  ;; UNINSTRUMENTED, because the arrow spec is the OUTER guard and refuses the
  ;; call before the body runs — so an instrumented call would prove that the
  ;; spec is armed and say nothing about the function. The body's own throw is
  ;; what stands in production, where nothing is instrumented, and this is the
  ;; only way to reach it.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Not a permission this emitter can name"
       ((instrument/uninstrumented #'permission-tree/permission-variant) :maybe))))

(deftest the-emitted-fragment-names-no-crate-and-declares-no-type
  ;; The contract a consumer's `include!` rests on: the file assumes
  ;; `Permission` and `PermissionNode` are in scope and brings in nothing.
  (let [rust (permission-tree/module
              (permission-tree/trees database
                                     [(project :g [{:message "p.Detail"
                                                    :access #{:read}
                                                    :fields :all}])]))]
    (is (str/includes? rust "pub static G: &[PermissionNode]"))
    (is (str/includes? rust "PermissionNode::new(0, \"p.Detail\", Permission::Allow, &["))
    (is (str/includes? rust "pub static GROUPS: &[(&str, &[PermissionNode])]"))
    (testing "no use, no extern crate, no type declaration"
      (is (not (str/includes? rust "use ")))
      (is (not (str/includes? rust "enum Permission")))
      (is (not (str/includes? rust "struct PermissionNode"))))))

(deftest two-runs-over-one-projection-render-identical-bytes
  (let [groups [(project :b [{:message "p.Detail" :access #{:read} :fields :all}])
                (project :a [{:message "p.Detail" :access #{:read} :fields :all}])]
        once (permission-tree/module (permission-tree/trees database groups))]
    (is (= once (permission-tree/module (permission-tree/trees database groups))))
    (testing "and the statics are emitted in group-id order, not policy order"
      (is (< (str/index-of once "pub static A:") (str/index-of once "pub static B:"))))))
