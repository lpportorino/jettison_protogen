(ns protocol-gen.rust-access-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [protocol-gen.db :as db]
            [protocol-gen.instrument :as instrument]
            [protocol-gen.projection :as projection]
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
   :subject-groups []
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
  (let [module (rust-access/module {:id :empty :package "p.e" :subject-groups []
                                    :messages [] :enums []})]
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

;; ── SCHEMA_VERSION — the projection fingerprint ─────────────────────────────
;;
;; Three properties, one test each, and each one is a MEASUREMENT rather than a
;; presence check. The shape they share: every case compares two fingerprints
;; taken in one run, so nothing here can pass because the value happened to be
;; there — an assertion that merely found a number would be satisfied by a
;; constant, which is exactly the emitter this section must be able to reject.

(defn- regroup
  "`group` with `f` applied to its message vector — the one axis these tests
   move, spelled once so each case below is the change and nothing else."
  [f]
  (update group :messages f))

(deftest the-fingerprint-does-not-move-with-how-the-value-was-BUILT
  ;; PROPERTY 1 — byte-reproducible. The hazard is not clock time or a path; it
  ;; is ITERATION ORDER, which is observable inside one run.
  ;;
  ;; EACH CASE HERE NAMES THE CLAUSE IT DEFENDS, because the three orderings
  ;; the encoder imposes fail in three different places and a case that reds on
  ;; a whole-value `pr-str` attributes to none of them.
  (testing "a CONSTRAINTS map written in another key order — the map sort"
    ;; The map the encoder actually meets with a foreign insertion order: a
    ;; field's `:constraints` is carried through from the database VERBATIM, so
    ;; its order is whatever the EDN author wrote. Both maps here are
    ;; array-maps, so they are `=` and print differently.
    (let [bounded #(assoc-in group [:messages 0 :fields 0 :constraints] %)
          one (bounded {:gte 1 :lte 2})
          other (bounded {:lte 2 :gte 1})]
      (is (= one other) "precondition: the two groups are the same value")
      (is (not= (seq (get-in one [:messages 0 :fields 0 :constraints]))
                (seq (get-in other [:messages 0 :fields 0 :constraints])))
          "precondition: their iteration order really does differ")
      (is (= (rust-access/schema-version one)
             (rust-access/schema-version other)))))
  (testing "a message's FIELDS listed in another order — the field sort"
    ;; Declaration order is a fact about the source message; the emitter writes
    ;; fields in NUMBER order, so declaration order reaches no emitted byte and
    ;; must reach no fingerprint.
    (let [two-fields (assoc-in group [:messages 0 :fields]
                               [{:number 3 :name "value" :type :double
                                 :number-source :descriptor :oneof nil}
                                {:number 9 :name "count" :type :uint32
                                 :number-source :descriptor :oneof nil}])
          reversed (update-in two-fields [:messages 0 :fields] (comp vec reverse))]
      (is (not= (:messages two-fields) (:messages reversed))
          "precondition: the field order really did move")
      (is (= (rust-access/schema-version two-fields)
             (rust-access/schema-version reversed)))))
  (testing "a group whose GRANTS are listed in another order — the message sort"
    (let [shuffled (regroup (comp vec reverse))]
      (is (not= (:messages group) (:messages shuffled))
          "precondition: the grant order really did move")
      (is (= (rust-access/schema-version group)
             (rust-access/schema-version shuffled)))))
  (testing "and a group rebuilt key-by-key, which no encoder clause defends"
    ;; KEPT, AND ITS WEAKNESS STATED. `fingerprint-input` rebuilds every map it
    ;; reads as a literal, so a top-level insertion-order difference is already
    ;; normalised before the encoder sees it — this assertion therefore cannot
    ;; red on any encoder mutation, and it is here for the whole-value hash a
    ;; future author might reach for, not as evidence about `canonical`.
    (let [rebuilt (into {} (reverse (seq group)))]
      (is (= group rebuilt) "precondition: the two are the same value")
      (is (= (rust-access/schema-version group)
             (rust-access/schema-version rebuilt))))))

(deftest the-encoding-cannot-be-read-two-ways
  ;; The injectivity clause — the length prefix on every string and keyword.
  ;; Without it the separator inside a string is indistinguishable from the
  ;; separator between two, so `["a" "b"]` and `["a,sb"]` both render `[sa,sb]`
  ;; and two DIFFERENT projections share a fingerprint. Reachable rather than
  ;; theoretical: a validation constraint carries arbitrary strings.
  (let [constrained #(assoc-in group [:messages 0 :fields 0 :constraints] {:in %})
        two-values (rust-access/schema-version (constrained ["a" "b"]))
        one-value (rust-access/schema-version (constrained ["a,sb"]))]
    (is (not= two-values one-value))
    (testing "CONTROL: the two inputs really are different projections"
      (is (not= (constrained ["a" "b"]) (constrained ["a,sb"]))))))

(deftest the-enum-axis-reaches-the-fingerprint
  ;; The branch the message-only fixture above never executes. A granted enum
  ;; is part of what a group's `.proto` declares, so its members are part of
  ;; what the group's decoder can express.
  (let [enum #(assoc group :enums [{:id "p.Mode" :proto-name "p_Mode" :values %}])
        unspecified {:number 0 :name "UNSPECIFIED"}
        fast {:number 2 :name "FAST"}
        slow {:number 5 :name "SLOW"}
        base (rust-access/schema-version group)]
    (testing "granting an enum at all moves the value"
      (is (not= base (rust-access/schema-version (enum [unspecified fast])))))
    (testing "and so does a MEMBER added to it"
      (is (not= (rust-access/schema-version (enum [unspecified fast]))
                (rust-access/schema-version (enum [unspecified fast slow])))))
    (testing "while the order its members are declared in does NOT"
      ;; The same clause as the field sort, one axis over: a proto3 enum's
      ;; members may be declared in any order and the number is the wire fact.
      (is (= (rust-access/schema-version (enum [unspecified fast slow]))
             (rust-access/schema-version (enum [slow unspecified fast])))))))

(defn- declared-keys
  "Every key `schema` DECLARES, as a set — derived, never spelled out here."
  [schema]
  (into #{} (map key) (m/entries schema)))

(defn- enum-entry-schema
  "The anonymous CLOSED map `projection/projected-group` declares for one
   granted enum, reached through its children.

   REACHED RATHER THAN COPIED. It has no name of its own, so a test that spelled
   its three keys out would BE the second hand-list this file exists to refuse —
   and it would go on agreeing with itself after a fourth key landed."
  []
  (->> (m/children (m/schema projection/projected-group))
       (some (fn [[k _props child]] (when (= k :enums) child)))
       m/children
       first))

(defn- projection-field-stamps
  "The keys `projection/project-field` ADDS to a database field, derived by
   projecting one and diffing it against its own input.

   NO SCHEMA CAN SUPPLY THIS ONE, which is why it is derived the hard way:
   `projected-message` declares `:fields [:vector db/field]` and `db/field` is
   OPEN, so there is no closed projected-field schema to read and the only
   authority on what the projection stamps is the projection. A
   DESCRIPTOR-origin field is used because it is the maximal case —
   `:number-source` is stamped on that path and not on the minted one."
  []
  (let [source {:number 3 :name "value" :type :double}
        database {:messages {"p.Reading" {:id "p.Reading" :name "Reading"
                                          :fields [source] :oneofs []}}
                  :enums {}}
        projected (projection/project-group
                   database {:messages {} :enums {}}
                   {:id :probe :package "p.probe"
                    :grants [{:message "p.Reading" :access #{:read} :fields :all}]})]
    (set/difference (set (keys (first (:fields (first (:messages projected))))))
                    (set (keys source)))))

(deftest every-key-the-projection-declares-is-read
  ;; The half of the derivation argument the code cannot make for itself. The
  ;; field, oneof and enum-value key sets are DERIVED from their schemas, so a
  ;; key added there joins the fingerprint by itself. FOUR key sets are written
  ;; out instead, because each key needs its own normalisation — and every one
  ;; of the four can gain a member legally and fall outside the fingerprint
  ;; SILENTLY, which is the accept-quietly direction. This is what notices.
  ;;
  ;; DERIVED ON BOTH SIDES, so none of these is a roster: each compares what the
  ;; emitter reads against what its authority declares, and reds when the two
  ;; diverge in either direction.
  (let [input (@#'rust-access/fingerprint-input group)
        enum-input (@#'rust-access/fingerprint-input
                    (assoc group :enums [{:id "p.Mode" :proto-name "p_Mode"
                                          :values [{:number 0 :name "UNSPECIFIED"}]}]))]
    (testing "the GROUP level, against its closed schema"
      (is (= (declared-keys projection/projected-group) (set (keys input)))))
    (testing "the MESSAGE level, against its closed schema"
      (is (= (declared-keys projection/projected-message)
             (set (keys (first (:messages input)))))))
    (testing "the ENUM level, against the anonymous closed schema nested in the group's"
      (is (= (declared-keys (enum-entry-schema))
             (set (keys (first (:enums enum-input)))))))
    (testing "and the two STAMPS, against what the projection actually adds"
      ;; The one with no schema behind it: `field-keys` is `db/field`'s declared
      ;; keys PLUS a hand-written pair, and this is the only thing that can tell
      ;; that pair from what `project-field` really stamps. Reds in both
      ;; directions — a third stamp nobody added here, and an extra here that
      ;; nothing stamps.
      (is (= (projection-field-stamps)
             (set/difference @#'rust-access/field-keys (declared-keys db/field)))))
    (testing "CONTROL: every side is non-empty, so the four equalities mean something"
      (is (seq (declared-keys projection/projected-group)))
      (is (seq (:messages input)))
      (is (seq (:enums enum-input)))
      (is (seq (projection-field-stamps))))))

(deftest the-fingerprint-tells-two-different-projections-apart
  ;; PROPERTY 2 — distinct per group. Asserted as a COUNT over a set, so it
  ;; reds on any pair colliding rather than only on the one pair a hand-written
  ;; chain of `not=` happened to name.
  (let [variants [group
                  (assoc group :id :other-group)
                  (assoc group :package "p.other")
                  (assoc group :subject-groups ["telemetry"])
                  (regroup #(vec (butlast %)))
                  (regroup #(conj % (msg "p.Extra" #{:read})))]
        versions (map rust-access/schema-version variants)]
    (is (= (count variants) (count (set versions)))
        "two projections that differ share a fingerprint")
    (testing "CONTROL: the probe produced a value per variant, so the count means something"
      (is (every? nat-int? versions))
      (is (= (count variants) (count versions))))))

(deftest the-fingerprint-moves-when-the-projection-does
  ;; PROPERTY 3 — a stale client must be catchable, so every axis of the
  ;; projection has to move it. One assertion per axis, because a single
  ;; combined case cannot say WHICH axis stopped being read.
  (let [base (rust-access/schema-version group)]
    (testing "a message ADDED"
      (is (not= base (rust-access/schema-version
                      (regroup #(conj % (msg "p.Extra" #{:read})))))))
    (testing "a message REMOVED"
      (is (not= base (rust-access/schema-version (regroup #(vec (butlast %)))))))
    (testing "a message RE-ACCESSED, with the same messages granted"
      (let [re-accessed (regroup #(assoc-in % [0 :access] #{:read :write}))]
        (is (= (mapv :id (:messages group)) (mapv :id (:messages re-accessed)))
            "precondition: only the direction moved")
        (is (not= base (rust-access/schema-version re-accessed)))))
    (testing "a FIELD filtered out of a granted message"
      ;; The axis a fingerprint over message ids, names and directions alone
      ;; could not see — and the one that decides what a decoder can express:
      ;; two groups granted the same messages under different field filters
      ;; emit different `.proto` files and generate different Rust types, with
      ;; every message id, emitted name and direction identical.
      (is (not= base (rust-access/schema-version
                      (regroup #(assoc-in % [0 :fields] []))))))
    (testing "a field RENUMBERED inside a granted message"
      (is (not= base (rust-access/schema-version
                      (regroup #(assoc-in % [0 :fields 0 :number] 4))))))))

(deftest the-fingerprint-ignores-what-no-schema-DECLARES
  ;; The deliberate exclusion, and the reason it is not simply a hash of the
  ;; whole value. `protocol-gen.db/field` is OPEN on purpose — a database
  ;; carries documentation and interaction metadata this generator never reads
  ;; — and none of it reaches emitted text, so an editorial edit upstream must
  ;; not move a constant a consumer compares at a routing boundary.
  (let [base (rust-access/schema-version group)
        annotated (assoc-in group [:messages 0 :fields 0 :description]
                            "prose the emitter never reads")]
    (is (= base (rust-access/schema-version annotated)))
    (testing "CONTROL: a DECLARED key on that same field does move it"
      ;; Without this the case above passes under a fingerprint that reads no
      ;; field at all, which is the mutation it exists to catch.
      (is (not= base (rust-access/schema-version
                      (assoc-in group [:messages 0 :fields 0 :name] "other")))))))

(deftest a-value-with-no-canonical-rendering-stops-the-run
  ;; Unreachable through the database schemas, which admit no such value into a
  ;; constraint. Asserted because the alternative to this throw is `str` over
  ;; an unforeseen object, which renders an identity hash — environmental, and
  ;; so a fingerprint that silently stops being reproducible. A generator that
  ;; cannot render a value must stop rather than render something.
  (let [f (instrument/uninstrumented #'rust-access/schema-version)
        bad (assoc-in group [:messages 0 :fields 0 :constraints]
                      {:example (Object.)})]
    (is (thrown? clojure.lang.ExceptionInfo (f bad)))
    (testing "and a group it CAN render still works, so the throw is not total"
      (is (nat-int? (f group))))))

(deftest the-module-carries-the-fingerprint-as-a-u32
  (let [module (rust-access/module group)
        emitted (second (re-find #"pub const SCHEMA_VERSION: u32 = (\d+);" module))]
    (is (some? emitted) "the module emits no SCHEMA_VERSION at all")
    (is (= (str (rust-access/schema-version group)) emitted)
        "the emitted literal is not the fingerprint this group's projection gives")
    (testing "and it is inside the range the emitted type can hold"
      ;; The fallback keeps a MISSING constant a clean failure rather than a
      ;; NullPointerException: an erroring test names the parse and not the
      ;; line that was never emitted, and it stops the assertions after it.
      (is (<= 0 (parse-long (or emitted "-1")) 4294967295)))
    (testing "beside the two consts it belongs with, before the Access enum"
      (is (re-find #"(?s)pub const PACKAGE: &str = .*pub const SCHEMA_VERSION: u32 = .*pub enum Access"
                   module)))))
