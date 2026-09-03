(ns protocol-gen.permission-tree-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [protocol-gen.db :as db]
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
              :fields [{:number 1 :name "next" :type :message :type-ref "p.Node"}]}
    ;; A GRANTED MESSAGE THAT DECLARES NO FIELDS — the case the kind marker
    ;; exists for. Every other shape in this database expands into something,
    ;; so without this one a leaf and an empty message are never told apart by
    ;; any assertion here.
    "p.Empty" {:id "p.Empty" :name "Empty"
               :fields []}
    ;; ONE FIELD PER KIND-BEARING SOURCE TYPE: a message, a scalar, an enum and
    ;; a REPEATED scalar. The last is the one a reader is most likely to expect
    ;; to be a message — it is not, and the wire agrees: a repeated scalar is
    ;; scalar bytes, not a nested length-delimited shape a scanner descends.
    "p.Holder" {:id "p.Holder" :name "Holder"
                :fields [{:number 1 :name "empty" :type :message :type-ref "p.Empty"}
                         {:number 2 :name "count" :type :uint32}
                         {:number 4 :name "mode" :type :enum :type-ref "p.Mode"}
                         {:number 7 :name "history" :type :int32 :repeated true}]}
    ;; A TYPE THIS GENERATOR CANNOT NAME, on a field no grant reaches. `:group`
    ;; is proto2's delimited encoding and is deliberately outside
    ;; `db/known-types`; the projection never judges it here, because it only
    ;; judges GRANTED fields, so the tree is the first pass that meets it.
    "p.Odd" {:id "p.Odd" :name "Odd"
             :fields [{:number 1 :name "ok" :type :bool}
                      {:number 2 :name "leg" :type :group :type-ref "p.Detail"}]}}
   :enums {"p.Mode" {:id "p.Mode" :name "Mode"
                     :values [{:number 0 :name "MODE_UNSPECIFIED"}
                              {:number 1 :name "MODE_ON"}]}}})

(def ^:private no-mints
  {:messages {} :enums {}})

(defn- project
  ([id grants] (project id grants []))
  ([id grants enums]
   (projection/project-group database no-mints
                             {:id id :package "p.g" :grants (vec grants)
                              :enums (vec enums)})))

(defn- tree-of
  ([id grants] (tree-of id grants []))
  ([id grants enums]
   (first (permission-tree/trees database [(project id grants enums)]))))

(def ^:private holder-grants
  "A grant of `p.Holder` whole, with the closure the projection demands: the
   message its `empty` field names and the enum its `mode` field names."
  [{:message "p.Holder" :access #{:read} :fields :all}
   {:message "p.Empty" :access #{:read} :fields :all}])

(defn- holder-tree
  []
  (tree-of :g holder-grants ["p.Mode"]))

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
  ;; THE EXPECTATION IS THE WHOLE NODE, so a key added to the model without a
  ;; value derived for it fails here rather than passing unnoticed. `:kind` was
  ;; added to this map when the marker landed; before that the assertion read
  ;; the four-key shape.
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"detail"}}
                       {:message "p.Detail" :access #{:read} :fields :all}])
        note (node-at (:roots t) ["p.Reading" "detail" "note"])]
    (is (= {:tag 6 :name "note" :kind :leaf :permission :inherit :children []} note))))

(deftest a-DENIED-message-typed-field-is-terminal
  ;; The bound on the one disclosure this artefact makes: it names the fields of
  ;; messages the group holds a grant for, and never the interior of a denial.
  (let [t (tree-of :g [{:message "p.Reading" :access #{:read} :fields #{"value"}}])
        detail (node-at (:roots t) ["p.Reading" "detail"])]
    (is (= :deny (:permission detail)))
    (is (= [] (:children detail)))
    (testing "and it keeps the kind its SOURCE TYPE has — denial is carried by
              the permission, never by pretending the field is a scalar"
      (is (= :message (:kind detail))))))

(deftest a-node-carries-the-kind-its-SOURCE-TYPE-has
  ;; THE DERIVATION, read off one message that carries one field per kind-bearing
  ;; source type. A ROOT is a message by construction — it is the message itself
  ;; — and every field takes the kind of the type the database records for it.
  (let [t (holder-tree)
        root (node-at (:roots t) ["p.Holder"])]
    (is (= :message (:kind root)))
    (is (= {"empty" :message "count" :leaf "mode" :leaf "history" :leaf}
           (into {} (map (juxt :name :kind)) (:children root))))
    (testing "a message the policy grants and that declares nothing is STILL a message"
      (is (= :message (:kind (node-at (:roots t) ["p.Empty"])))))))

(deftest a-granted-message-with-no-fields-is-a-MESSAGE-node-and-not-a-leaf
  ;; THE DEFECT THE MARKER CLOSES, stated as the shape rather than as prose. A
  ;; scanner steps over a leaf's bytes unread and refuses every undescribed tag
  ;; inside a message; a zero-field message emitted as a leaf therefore grants
  ;; whatever a hostile peer smuggles into it. Empty children alone cannot tell
  ;; the two apart, which is why the kind is carried and not inferred.
  (let [t (holder-tree)
        node (node-at (:roots t) ["p.Holder" "empty"])]
    (is (= :message (:kind node)))
    (is (= [] (:children node)))
    (testing "and the scalar beside it is a leaf, so the two are distinguishable"
      (is (= :leaf (:kind (node-at (:roots t) ["p.Holder" "count"])))))))

(deftest the-rendered-fragment-uses-message-and-leaf-and-never-new
  ;; THE EMITTED TEXT, which is the only surface a consumer sees. The two calls
  ;; below are the exact pair the defect turns on: an empty MESSAGE and a scalar
  ;; LEAF, which the retired single constructor rendered identically apart from
  ;; the name.
  (let [rust (permission-tree/module (permission-tree/trees database
                                                            [(project :g holder-grants ["p.Mode"])]))]
    (is (str/includes? rust "PermissionNode::message(1, \"empty\", Permission::Inherit, &[]),"))
    (is (str/includes? rust "PermissionNode::leaf(2, \"count\", Permission::Inherit),"))
    (testing "a repeated scalar and an enum are leaves too"
      (is (str/includes? rust "PermissionNode::leaf(4, \"mode\", Permission::Inherit),"))
      (is (str/includes? rust "PermissionNode::leaf(7, \"history\", Permission::Inherit),")))
    (testing "a leaf takes NO children argument at all"
      (is (not (str/includes? rust "PermissionNode::leaf(2, \"count\", Permission::Inherit, &[])"))))
    (testing "the retired constructor is gone, with no fallback and no third arm"
      (is (not (str/includes? rust "PermissionNode::new("))))))

(deftest a-DENIED-message-renders-as-a-message-node-carrying-no-children
  ;; Denial is terminal through the PERMISSION, so the kind still says what the
  ;; source type is. A scanner refuses the node on `Deny` before the kind ever
  ;; decides anything, and the emitted shape says nothing false in the meantime.
  (let [rust (permission-tree/module
              (permission-tree/trees database
                                     [(project :g [{:message "p.Reading"
                                                    :access #{:read}
                                                    :fields #{"value"}}])]))]
    (is (str/includes? rust "PermissionNode::message(14, \"detail\", Permission::Deny, &[]),"))
    (is (str/includes? rust "PermissionNode::leaf(9, \"label\", Permission::Deny),"))))

(deftest every-type-the-database-can-carry-has-a-kind-and-no-other-does
  ;; BOTH DIRECTIONS, because each fails a different way. A type the database
  ;; can carry and this map cannot name would be a refusal at generation time —
  ;; loud, but a refusal a reader meets with no way to see it coming. A kind
  ;; named for a type the database cannot carry is a dead entry claiming
  ;; coverage. The map is written out rather than derived precisely so a new
  ;; database type fails HERE and its kind is decided by a person.
  (is (= db/known-types (set (keys permission-tree/node-kinds)))))

(deftest a-field-whose-type-this-generator-cannot-name-is-REFUSED
  ;; REACHABLE, and only from here: the projection judges GRANTED fields, so an
  ;; unnameable type on a DENIED field of a granted message reaches no other
  ;; pass. The tree names every source field, so it is the artefact that has to
  ;; have an answer — and a guess about whether a `:group` is a message is
  ;; exactly the claim the emitted data cannot support.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-field-type"
       (tree-of :g [{:message "p.Odd" :access #{:read} :fields #{"ok"}}]))))

(deftest a-kind-outside-the-closed-set-throws-rather-than-rendering
  ;; UNINSTRUMENTED, for the reason the permission case below records: the arrow
  ;; spec is the OUTER guard and refuses the call before the body runs, so an
  ;; instrumented call would prove the spec is armed and say nothing about the
  ;; function. The body's throw is what stands in production.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Not a node kind this emitter can name"
       ((instrument/uninstrumented #'permission-tree/node-constructor) :scalar))))

(deftest a-leaf-carrying-children-is-not-a-node-this-model-admits
  ;; THE ONE CONTRADICTION THE TWO KEYS CAN EXPRESS, and it is silent: the
  ;; renderer emits `leaf(tag, name, permission)`, which has nowhere to put a
  ;; child, so a leaf that carried any would drop them and the tree would stop
  ;; being total with nothing to show for it. `expand` cannot build one — a node
  ;; descends only where its kind is `:message` — so this is a defensive
  ;; invariant over the generator's own output, in the shape `assert-reachable!`
  ;; already uses.
  (let [leaf {:tag 3 :name "value" :kind :leaf :permission :inherit :children []}]
    (is (m/validate permission-tree/node leaf))
    (is (not (m/validate permission-tree/node (assoc leaf :children [leaf]))))
    (testing "while a message may carry either"
      (let [msg (assoc leaf :kind :message)]
        (is (m/validate permission-tree/node msg))
        (is (m/validate permission-tree/node (assoc msg :children [leaf])))))))

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
    (is (str/includes? rust "PermissionNode::message(0, \"p.Detail\", Permission::Allow, &["))
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
