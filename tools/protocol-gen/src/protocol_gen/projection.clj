(ns protocol-gen.projection
  "The JOIN: descriptor database + locally-minted messages + access policy ->
   one resolved projection per group.

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
  "One group's whole projection — the value both the `.proto` text and the
   permission mirror are derived from, so the two cannot disagree."
  [:map {:closed true}
   [:id :keyword]
   [:package db/proto-qualified-name]
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
   its origin. Refuses when neither carries it."
  [database minted grant]
  (let [id (:message grant)]
    (cond
      (contains? (:messages database) id) [:descriptor (get-in database [:messages id])]
      (contains? minted id) [:minted (get minted id)]
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
  "The enums a group listed, resolved against the database."
  [database g]
  (mapv (fn [id]
          (if-let [e (get-in database [:enums id])]
            {:id id :proto-name (proto-name-of id) :values (:values e)}
            (constructs/refuse! :enum-not-in-database id
                                "the policy lists this enum and the database does not carry it")))
        (:enums g [])))

(defn universe
  "The database with the minted messages merged in — everything that EXISTS, as
   opposed to everything a group was granted. Reference resolution asks this;
   the closure check asks the grants."
  [database minted]
  (update database :messages merge minted))

(m/=> universe
      [:=> [:cat db/database [:map-of db/proto-qualified-name db/message]] db/database])

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
        enums (project-enums database g)]
    (assert-types-granted! (:id g) messages enums)
    (assert-names-distinct! (:id g) messages enums)
    (numbering/assert-stamped! messages)
    {:id (:id g) :package (:package g) :messages messages :enums enums}))

(m/=> project-group
      [:=> [:cat db/database [:map-of db/proto-qualified-name db/message] policy/group]
       projected-group])

(defn project
  "Every group's projection, in policy order. Refuses a duplicated group id —
   two groups sharing one would write the same path twice and the second write
   would silently be the one that survived."
  [database minted p]
  (when-let [dups (seq (policy/duplicate-group-ids p))]
    (constructs/refuse! :name-collision "policy/groups"
                        (str "these group ids are declared more than once: " (pr-str dups))))
  (mapv #(project-group database minted %) (:groups p)))

(m/=> project
      [:=> [:cat db/database [:map-of db/proto-qualified-name db/message] policy/policy]
       [:vector projected-group]])
