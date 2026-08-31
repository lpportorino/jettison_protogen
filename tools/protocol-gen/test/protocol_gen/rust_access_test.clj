(ns protocol-gen.rust-access-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.instrument :as instrument]
            [protocol-gen.rust-access :as rust-access]))

(defn- msg
  "One projected message, with only the keys this emitter reads spelled out."
  [id access]
  {:id id
   :proto-name (str/replace id "." "_")
   :origin :descriptor
   :access access
   :fields [{:number 3 :name "value" :type :double :number-source :descriptor :oneof nil}]
   :oneofs []})

(def ^:private group
  ;; Grants deliberately NOT in source-id order, and one of each direction, so
  ;; the ordering assertion below is about the emitter rather than about the
  ;; order this literal happens to be written in.
  {:id :operator
   :package "p.operator"
   :messages [(msg "p.Stop" #{:write})
              (msg "p.Reading" #{:read})
              (msg "p.SetMode" #{:read :write})]
   :enums []})

(defn- access-arm
  "The `Access::…` variant the emitted `access` match maps `proto-name` to."
  [module proto-name]
  (second (re-find (re-pattern (str "Self::" proto-name " => Access::(\\w+),")) module)))

(deftest every-direction-reaches-the-emitted-module
  ;; The whole reason this artefact exists: `.proto` describes a shape and has
  ;; nowhere to put a direction, so this is the fact only this module carries.
  (let [module (rust-access/module group)]
    (is (= "Read" (access-arm module "p_Reading")))
    (is (= "Write" (access-arm module "p_Stop")))
    (testing "including the combination a single-direction group never renders"
      (is (= "ReadWrite" (access-arm module "p_SetMode"))))))

(deftest the-direction-is-the-grant-and-not-a-default
  ;; A renderer that mapped every access set onto one variant would satisfy the
  ;; presence assertions above, so the three must be DISTINCT.
  (let [module (rust-access/module group)]
    (is (= 3 (count (set (map #(access-arm module %)
                              ["p_Reading" "p_Stop" "p_SetMode"])))))))

(deftest an-access-set-the-emitter-cannot-name-throws
  ;; The empty set is unreachable through the policy schema, which closes
  ;; `:access` to a non-empty subset. It is asserted here because a DEFAULT in
  ;; its place would render some direction for a set nobody anticipated, and a
  ;; direction rendered from a guess is the failure this module exists to
  ;; prevent.
  (let [f (instrument/uninstrumented #'rust-access/access-variant)]
    (is (thrown? clojure.lang.ExceptionInfo (f #{})))
    (is (thrown? clojure.lang.ExceptionInfo (f #{:execute})))
    (testing "and the sets it CAN name still work, so the throw is not total"
      (is (= "Read" (f #{:read}))))))

(deftest the-module-names-messages-and-never-fields
  ;; MESSAGE LEVEL AND NO LOWER: proto3 field presence is absent from the
  ;; descriptor database, so a per-field access surface would claim a
  ;; distinction its input cannot supply.
  ;;
  ;; ASSERTED OVER THE STRING LITERALS THE MODULE CARRIES AS DATA, not over the
  ;; whole text. A bare `str/includes?` for the field name "value" reads the
  ;; word out of the emitted PROSE — `Access`'s own doc comment says "every
  ;; value a grant can hold" — and reports a leak that is not there. That
  ;; false positive fired on the first run of this test, which is the reason
  ;; the probe is shaped this way.
  (let [module (rust-access/module group)
        literals (set (map second (re-seq #"\"([^\"]*)\"" module)))]
    (is (= #{"operator" "p.operator"
             "p.Reading" "p.SetMode" "p.Stop"
             "p_Reading" "p_SetMode" "p_Stop"}
           literals)
        "the module carries a string literal that is not a group, a package or a message")
    (testing "CONTROL: the probe collects literals at all, so the set is a measurement"
      (is (seq literals)))))

(deftest the-enum-carries-exactly-the-granted-messages
  ;; The property that makes the enum worth having over a string-keyed table:
  ;; an ungranted message has no variant, so naming one is a compile error at
  ;; the consumer rather than a lookup that returns nothing.
  ;;
  ;; ASSERTED AS AN EQUALITY OVER THE VARIANT SET, not as the absence of a name
  ;; nothing granted. A probe for a name the input never carried — "Secret",
  ;; say — cannot go red under ANY emitter mutation, because no function of
  ;; this group can produce it; its pass value and its nothing-ran value are the
  ;; same. An equality reds on a dropped variant AND on an added one.
  (let [module (rust-access/module group)
        block (second (re-find #"(?s)pub enum Message \{\n(.*?)\n\}" module))
        variants (map second (re-seq #"(?m)^    (p_\w+),$" block))]
    (is (= ["p_Reading" "p_SetMode" "p_Stop"] variants))))

(deftest the-messages-table-is-in-source-id-order-not-grant-order
  ;; The grants in `group` are written Stop, Reading, SetMode on purpose, so a
  ;; renderer that emitted them in projection order fails this.
  ;;
  ;; THE TWO-RUN BYTE CLAIM IS NOT MADE HERE. Calling a pure function twice on
  ;; one immutable value in one JVM has no non-determinism to catch, so such an
  ;; assertion could not go red; the claim that two RUNS write identical bytes
  ;; is the canary's, which diffs the files two separate processes wrote.
  (let [variants (map second (re-seq #"Message::(p_\w+),\n" (rust-access/module group)))]
    (is (= ["p_Reading" "p_SetMode" "p_Stop"] variants))))

(deftest the-module-says-it-is-generated
  (let [module (rust-access/module group)]
    (is (str/starts-with? module "// GENERATED by protocol-gen — DO NOT EDIT."))
    (is (str/includes? module "Hand edits are destroyed by the next run."))))

(deftest the-module-carries-its-group-and-package
  (let [module (rust-access/module group)]
    (is (str/includes? module "pub const GROUP: &str = \"operator\";"))
    (is (str/includes? module "pub const PACKAGE: &str = \"p.operator\";"))))

(deftest a-group-that-granted-nothing-emits-the-uninhabited-shapes
  ;; Unreachable through the policy schema, which requires at least one grant,
  ;; and reachable through this function, which takes a projection. `match self
  ;; {}` with no arms is how Rust spells a match on an uninhabited type — an
  ;; empty `match self { }` body with the usual arms would not compile.
  ;;
  ;; THE NAME SAYS SHAPES AND NOT `COMPILES`, DELIBERATELY. Nothing committed
  ;; compiles a zero-message module: no policy can produce one, so the canary
  ;; cannot reach the case, and this suite has no Rust toolchain. That the
  ;; shape does compile was observed while authoring and is NOT committed
  ;; evidence — an authoring-side observation is the floor, not the finish.
  (let [module (rust-access/module {:id :empty :package "p.e" :messages [] :enums []})]
    (is (str/includes? module "pub enum Message {\n}"))
    (is (str/includes? module "match self {}"))
    (is (str/includes? module "pub const MESSAGES: [Message; 0] = [\n];"))))

(deftest a-name-that-cannot-be-quoted-into-rust-stops-the-run
  ;; Unreachable through `db`'s name schemas, which admit neither a quote nor a
  ;; backslash. Asserted because the alternative to this throw is quoting an
  ;; unescaped value into emitted source, and a generator that writes source it
  ;; did not check is the one failure a `.proto` emitter and a Rust emitter
  ;; share.
  (let [f (instrument/uninstrumented #'rust-access/module)
        bad (update-in group [:messages 0 :proto-name] str "\"")]
    (is (thrown? clojure.lang.ExceptionInfo (f bad)))))

(deftest the-granted-message-list-is-the-projections-and-nothing-added
  (is (= ["p.Reading" "p.SetMode" "p.Stop"]
         (mapv :id (rust-access/granted-messages group)))))
