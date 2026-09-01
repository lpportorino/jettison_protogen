(ns protocol-gen.projection
  "The JOIN: descriptor database + locally-minted declarations + access policy
   -> one resolved projection per group.

   `minted` ARRIVES DATABASE-SHAPED — `{:messages … :enums …}` — because
   `numbering/apply-numbering` has already stamped every minted field with its
   registry number. So a minted message and a projected one are one shape from
   here on, and a minted ENUM resolves through the same lookup a projected one
   does; the only thing that still tells them apart is the `:origin` recorded
   per message, which the permission mirror carries.

   This is where the policy is applied, and it is applied over the EDN BEFORE
   anything is emitted. A group's projection contains only what that group was
   granted, so its schema does not NAME what it may not touch and a decoder
   generated from it cannot express a forbidden message.

   EVERY REFUSAL HERE IS LOUD. A grant naming a message that is not there, a
   field name that is not on the message, a field the emitter cannot express, a
   type the policy did not also grant — each throws, naming the subject. The
   silent alternative is the failure this whole tool is built against: a
   projection quietly missing a field a policy granted looks exactly like a
   correct one.

   A FIELD NAME THAT MATCHES NOTHING IS THE SHARPEST OF THOSE. Left unrefused,
   a typo in a grant produces a schema missing the field the author meant to
   grant — which reads as a policy that deliberately withheld it.

   THE ONE THING THIS NAMESPACE MUST NEVER DO is compute a number. A field
   projected out of the database carries the descriptor's number and is stamped
   `:number-source :descriptor`; a minted field carries the registry's and is
   stamped `:number-source :registry`. `numbering/assert-stamped!` refuses
   anything else, and the emitter has no way to invent one."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]
            [protocol-gen.numbering :as numbering]
            [protocol-gen.policy :as policy]))

(set! *warn-on-reflection* true)

(def projected-message
  "One message in a group's projection: where it came from, what the group may
   do with it, the name it is emitted under, and its granted fields."
  [:map {:closed true}
   [:id db/proto-qualified-name]
   [:proto-name db/proto-identifier]
   [:origin [:enum :descriptor :minted]]
   [:access [:set [:enum :read :write]]]
   [:fields [:vector db/field]]
   [:oneofs [:vector db/oneof]]])

(def projected-group
  "One group's whole projection — the value the `.proto` text, the permission
   mirror, the access module, the permission tree and the state subsystem table
   are all derived from, so no two of them can disagree.

   `:state-subsystems` IS AN AUTHORISATION FACT LIKE `:access`, which is why it
   is carried here rather than read back out of the policy by the emitter. It
   resolves against nothing in the database — a state subsystem names a SOURCE
   of state rather than a shape — so the policy's own declared set is what
   `project` validates it against, one level up where the whole policy is in
   hand."
  [:map {:closed true}
   [:id :keyword]
   [:package db/proto-qualified-name]
   [:state-subsystems [:vector db/proto-identifier]]
   [:messages [:vector projected-message]]
   [:enums [:vector [:map {:closed true}
                     [:id db/proto-qualified-name]
                     [:proto-name db/proto-identifier]
                     [:values [:vector db/enum-value]]]]]])

(defn proto-name-of
  "The name a fully-qualified id is emitted under: its dots replaced by
   underscores.

   WHY RENAME AT ALL. A group's schema is a NEW file in a package this policy
   chose, so nothing obliges it to reproduce the source's packages — and it
   cannot: the source ids span several packages and a proto file has one.
   Flattening them into legal identifiers is the smallest rule that keeps every
   id distinct, and it touches nothing on the wire, where only numbers and
   types are carried.

   NOT ASSUMED INJECTIVE. `a.b_c` and `a_b.c` both land here, so callers check
   for a collision rather than trusting this function."
  [id]
  (str/replace id "." "_"))

(m/=> proto-name-of [:=> [:cat db/proto-qualified-name] db/proto-identifier])

(defn- granted-fields
  "The fields of `msg` that `grant` names, in the message's own order.

   A named field that the message does not carry is REFUSED rather than
   skipped: skipping it turns a typo into a schema silently missing a field
   somebody granted."
  [grant msg]
  (let [wanted (:fields grant)]
    (if (= :all wanted)
      (:fields msg)
      (let [have (into #{} (map :name) (:fields msg))]
        (doseq [nm (sort wanted)]
          (when-not (contains? have nm)
            (constructs/refuse! :field-not-in-message (str (:id msg) "." nm)
                                (str "the grant names this field and the message does not "
                                     "carry it; its fields are " (pr-str (vec (sort have)))))))
        (filterv #(contains? wanted (:name %)) (:fields msg))))))

(defn- resolve-message
  "The message `grant` names, from the database or the minted set, tagged with
   its origin. Refuses when neither carries it.

   `minted` is itself database-shaped, so both lookups read `:messages`; the
   two are kept apart rather than merged here because the ORIGIN is what
   distinguishes them, and it is a fact the permission mirror carries."
  [database minted grant]
  (let [id (:message grant)]
    (cond
      (contains? (:messages database) id) [:descriptor (get-in database [:messages id])]
      (contains? (:messages minted) id) [:minted (get-in minted [:messages id])]
      :else (constructs/refuse! :message-not-in-database id
                                (str "the policy grants this message and neither the "
                                     "database nor the minted declarations carry it")))))

(defn- oneof-of
  "The name of the oneof `msg` puts field number `n` in, or nil."
  [msg n]
  (some (fn [o] (when (some #{n} (:fields o)) (:name o))) (:oneofs msg)))

(defn- project-field
  "One granted field, carrying its wire number and that number's provenance.

   `universe` is the database with the minted messages MERGED IN, so a minted
   field referring to a minted type resolves. Expressibility is judged against
   that union rather than against the database alone, which would refuse a
   perfectly good reference for not being in a file it was never in.

   The `:number-source` for a database field is stamped HERE and nowhere else,
   which is what makes `numbering/assert-stamped!` able to tell a projected
   number from an invented one."
  [universe origin msg fld]
  (when-let [r (first (constructs/field-refusals universe (:id msg) fld))]
    (constructs/refuse! (:reason r) (:subject r) (:detail r)))
  (cond-> (assoc fld :oneof (oneof-of msg (:number fld)))
    (= :descriptor origin) (assoc :number-source :descriptor)))

(defn- assert-types-granted!
  "Refuse any granted field whose type names a message or enum this group did
   not also grant.

   THE CLOSURE CHECK, and it is not a nicety. Without it a projection can emit
   a field whose type is not in the file, which either fails to compile or —
   worse, if a name happens to resolve — silently means something else."
  [group-id messages enums]
  (let [granted-msgs (into #{} (map :id) messages)
        granted-enums (into #{} (map :id) enums)]
    (doseq [msg messages
            fld (:fields msg)
            :let [type-ref (:type-ref fld)]
            :when (contains? db/referring-types (:type fld))]
      (when-not (if (= :enum (:type fld))
                  (contains? granted-enums type-ref)
                  (contains? granted-msgs type-ref))
        (constructs/refuse! :type-not-granted (str (:id msg) "." (:name fld))
                            (str "group " group-id " grants this field, whose type is " type-ref
                                 ", and does not grant " type-ref " itself — add it to the "
                                 (if (= :enum (:type fld)) ":enums list" ":grants")))))))

(defn- assert-names-distinct!
  "Refuse when two projected ids flatten onto one emitted name."
  [group-id messages enums]
  (doseq [[nm items] (group-by :proto-name (concat messages enums))
          :when (> (count items) 1)]
    (constructs/refuse! :name-collision (str group-id "/" nm)
                        (str "these ids flatten onto one emitted name: "
                             (pr-str (vec (sort (map :id items))))))))

(defn- project-enums
  "The enums a group listed, resolved against `all` — the UNIVERSE, not the
   database.

   The universe is what makes a minted enum resolve the way a minted message
   already does. Asking the database alone refused a locally-minted enum for
   not being in a file it was never in, which is the same mistake
   `project-field` avoids by judging expressibility against the union.

   NO `:origin` IS RECORDED HERE, and a reader should know that rather than
   infer it. A projected MESSAGE carries one and the permission mirror reports
   it; an enum's entry in that mirror is its emitted name and nothing else, so
   a minted enum and a descriptor enum are indistinguishable there. Recording
   an origin the mirror has no field for would put a fact in the projection
   that nothing reads."
  [all g]
  (mapv (fn [id]
          (if-let [e (get-in all [:enums id])]
            {:id id :proto-name (proto-name-of id) :values (:values e)}
            (constructs/refuse! :enum-not-in-database id
                                (str "the policy lists this enum and neither the database "
                                     "nor the minted declarations carry it"))))
        (:enums g [])))

(defn universe
  "The database with the minted declarations merged in — everything that
   EXISTS, as opposed to everything a group was granted. Reference resolution
   asks this; the closure check asks the grants.

   BOTH MAPS, not `:messages` alone. `constructs/type-refusal` asks
   `db/enum-ref?` of this value, so with only messages merged a minted field
   referring to a minted enum was refused `:unresolved-type-ref` — a reference
   that resolves perfectly well, reported as one that resolves to nothing."
  [database minted]
  (-> database
      (update :messages merge (:messages minted))
      (update :enums merge (:enums minted))))

(m/=> universe [:=> [:cat db/database db/database] db/database])

(defn project-group
  "The resolved projection for one group."
  [database minted g]
  (when-let [dups (seq (policy/duplicate-grants g))]
    (constructs/refuse! :message-not-in-database (str (:id g))
                        (str "these messages are granted more than once: " (pr-str dups))))
  (let [all (universe database minted)
        messages (mapv (fn [grant]
                         (let [[origin msg] (resolve-message database minted grant)]
                           {:id (:id msg)
                            :proto-name (proto-name-of (:id msg))
                            :origin origin
                            :access (:access grant)
                            :fields (mapv #(project-field all origin msg %)
                                          (granted-fields grant msg))
                            :oneofs (vec (:oneofs msg))}))
                       (:grants g))
        enums (project-enums all g)]
    (assert-types-granted! (:id g) messages enums)
    (assert-names-distinct! (:id g) messages enums)
    (numbering/assert-stamped! messages)
    {:id (:id g)
     :package (:package g)
     :state-subsystems (vec (sort (:state-subsystems g [])))
     :messages messages
     :enums enums}))

(m/=> project-group
      [:=> [:cat db/database db/database policy/group] projected-group])

(defn- assert-subsystems-declared!
  "Refuse a group naming a state subsystem the policy's closed set does not
   carry.

   IT IS ASKED HERE AND NOT IN `project-group`, because the closed set is a
   POLICY-level declaration and a single group cannot see it. Left unrefused, a
   typo in a group's list would emit a table row nothing declared — or, worse
   under a table that is total over the DECLARED set, no row at all, which reads
   exactly like a group that deliberately receives nothing."
  [p]
  (let [declared (policy/declared-subsystems p)]
    (doseq [g (:groups p)
            :let [unknown (policy/undeclared-subsystems declared g)]
            :when (seq unknown)]
      (constructs/refuse! :state-subsystem-not-declared (str (:id g))
                          (str "this group names state subsystem(s) the policy does not "
                               "declare: " (pr-str unknown) "; the declared set is "
                               (pr-str declared))))))

(defn project
  "Every group's projection, in policy order. Refuses a duplicated group id —
   two groups sharing one would write the same path twice and the second write
   would silently be the one that survived."
  [database minted p]
  (when-let [dups (seq (policy/duplicate-group-ids p))]
    (constructs/refuse! :name-collision "policy/groups"
                        (str "these group ids are declared more than once: " (pr-str dups))))
  (assert-subsystems-declared! p)
  (mapv #(project-group database minted %) (:groups p)))

(m/=> project
      [:=> [:cat db/database db/database policy/policy] [:vector projected-group]])
