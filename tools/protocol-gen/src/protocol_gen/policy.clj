(ns protocol-gen.policy
  "The declarative access policy: which messages and fields each group may see
   and send.

   A policy is DATA, applied at the JOIN — over the EDN, before anything is
   emitted. It is deliberately not carried as proto annotations, and the reason
   is what the whole projection is for: an annotation would have to be present
   in the emitted file to be read, so every group's schema would then NAME the
   things it is not allowed to touch and rely on a consumer honouring a marker.
   Applying the policy before emission means a group's schema does not name
   what the policy did not grant, so a decoder generated from it cannot express
   a forbidden message at all. That is a consequence of projecting rather than
   a separate feature, and it survives a careless consumer.

   WHAT A POLICY CANNOT SAY, stated here because the schema below looks as
   though it says it. `:access` records whether a group may READ a message or
   SEND it. A `.proto` file has nowhere to put that — proto describes a shape,
   not a direction — so the emitted schema is the same either way and the
   direction reaches a consumer through the PERMISSION MIRROR emitted beside
   it. Read `:access` as an authorisation fact the mirror carries, never as
   something the generated code enforces."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [protocol-gen.db :as db]))

(set! *warn-on-reflection* true)

(def access-modes
  "The closed set of access modes a grant may carry. `:read` is \"this group
   may receive this message\", `:write` is \"this group may send it\"."
  #{:read :write})

(def grant
  "One grant: a message id, what the group may do with it, and WHICH of its
   fields.

   `:fields` is either an explicit set of names or the keyword `:all`. Both are
   supported and the difference matters: an explicit set is a decision that
   stays made, while `:all` follows the database, so a field added upstream
   joins the group without anyone deciding it should. `:all` must therefore be
   SPELLED — there is no default — so choosing it is visible in review."
  [:map {:closed true}
   [:message db/proto-qualified-name]
   [:access [:and [:set (into [:enum] (sort access-modes))]
             [:fn {:error/message "at least one access mode"} seq]]]
   [:fields [:or [:= :all] [:set {:min 1} db/proto-identifier]]]])

(def subject-group
  "One subject group id. A stable name, in the shape every other name in this
   generator takes, so it quotes into emitted source without escaping.

   WHAT A SUBJECT GROUP IS: a named set of state SUBJECTS a consumer's own
   producer emits — a screen, a service, a projection it publishes — and never a
   partition of the device state a descriptor database describes. Device state
   is not narrowed per group, so nothing here thins it; this axis exists for the
   state that has no descriptor at all.

   IT IS NOT A MESSAGE ID AND RESOLVES AGAINST NOTHING. The subject groups a
   group may receive are an axis the descriptor database knows nothing about —
   they name emitters of state rather than shapes — so the only thing that can
   say which ones exist is the policy itself, which declares the closed set
   below."
  db/proto-identifier)

(def ^:private distinct-vector
  "A vector whose members are distinct. A repeat is a policy-authoring typo with
   no meaning: it names one subject group twice, and folding it silently is how
   a policy stops saying what it looks like it says."
  [:fn {:error/message "entries must be distinct"}
   #(= (count %) (count (set %)))])

(def group
  "One access group: its id, the proto package its schema is emitted into, its
   grants, the enums it may name, and the subject groups it may receive.

   `:subject-groups` is OPTIONAL and an omitted key means the EMPTY set — the
   same reading `:enums` already has. It is not a silent default: the emitted
   table carries a row for every subject group the policy declares, so a group
   that names none is a group whose every row says `false`, which is a decision
   on the record rather than an absence a reader has to interpret.

   ENUMS ARE GRANTED EXPLICITLY rather than pulled in by whatever a granted
   field happens to reference. Auto-inclusion would mean a field grant silently
   widened the group's vocabulary — an enum names its members, so it is a
   disclosure — and the refusal a missing entry produces names exactly what to
   add, which costs one line and buys an explicit decision."
  [:map {:closed true}
   ;; SIMPLE keyword: a group id becomes the emitted file's name, and a
   ;; namespaced one carries a slash — a path separator, not a filename.
   [:id [:and :keyword [:fn {:error/message "not a simple keyword"} simple-keyword?]]]
   [:package db/proto-qualified-name]
   [:grants [:vector {:min 1} grant]]
   [:enums {:optional true} [:vector db/proto-qualified-name]]
   [:subject-groups {:optional true} [:and [:vector subject-group] distinct-vector]]])

(def policy
  "The whole policy. `:version` is the POLICY FORMAT's version and exists so a
   reader of an emitted mirror can tell which vocabulary produced it.

   `:subject-groups` HERE IS THE CLOSED UNIVERSE, and a group's list is a subset
   of it. Declaring it at the top is what makes the emitted table TOTAL: without
   a set to be total OVER, `this group receives none of them` and `this policy
   has no state axis` emit the same nothing, and a subject group dropped from a
   group would be indistinguishable from one nobody has declared yet.

   IT IS OPTIONAL, because a policy that does not use the axis should not have
   to declare an empty one — and its absence is not a silent skip: the emitted
   table is then empty, the run says so, and the oracle refuses to judge an
   empty population rather than reporting it clean."
  [:map {:closed true}
   [:version [:= 1]]
   [:subject-groups {:optional true}
    [:and [:vector {:min 1} subject-group] distinct-vector]]
   [:groups [:vector {:min 1} group]]])

(defn declared-subject-groups
  "The closed set of subject groups `p` declares, as a sorted vector.

   SORTED HERE AND NOWHERE ELSE, so the emitted table's row order is a function
   of the policy's content rather than of its authoring order and two runs write
   identical bytes."
  [p]
  (vec (sort (:subject-groups p []))))

(m/=> declared-subject-groups [:=> [:cat policy] [:vector subject-group]])

(defn undeclared-subject-groups
  "The subject groups `g` names that `declared` does not carry.

   Reported rather than refused here, for the reason `duplicate-group-ids` gives
   for its own shape: this namespace owns what a policy IS, and the refusal
   belongs where the policy is applied."
  [declared g]
  (vec (sort (remove (set declared) (:subject-groups g [])))))

(m/=> undeclared-subject-groups
      [:=> [:cat [:sequential subject-group] group] [:vector subject-group]])

(defn validate!
  "Return `p` when it conforms to `policy`; throw naming `path` otherwise.

   NO `m/=>`, for the reason `protocol-gen.db/validate!` gives for its twin:
   the argument is whatever EDN the file held, and `:any` is a slot that
   constrains nothing. The enforcement is this call, which throws."
  [p path]
  (if (m/validate policy p)
    p
    (throw (ex-info "Not an access policy"
                    {:path path :explain (m/explain policy p)}))))

(defn load-policy
  "Read and validate the EDN access policy at `path`. A missing file throws —
   an absent policy projects nothing, and a generator that emitted nothing
   would exit 0 having written no schema at all."
  [path]
  (when-not (java.io.File/.isFile (io/file path))
    (throw (ex-info "Access policy not found" {:path path})))
  (validate! (edn/read-string (slurp path)) path))

(m/=> load-policy [:=> [:cat [:string {:min 1}]] policy])

(defn duplicate-group-ids
  "The group ids `p` declares more than once.

   A repeat is refused rather than merged: two groups sharing an id would write
   the same output path twice, and the second write would silently be the one
   that survived."
  [p]
  (vec (sort (for [[id gs] (group-by :id (:groups p))
                   :when (> (count gs) 1)]
               id))))

(m/=> duplicate-group-ids [:=> [:cat policy] [:vector :keyword]])

(defn duplicate-grants
  "The message ids `g` grants more than once.

   Same reason: two grants for one message are ambiguous about which field set
   applies, and taking the last one silently is how a policy edit stops meaning
   what it says."
  [g]
  (vec (sort (for [[msg-id gs] (group-by :message (:grants g))
                   :when (> (count gs) 1)]
               msg-id))))

(m/=> duplicate-grants [:=> [:cat group] [:vector [:string {:min 1}]]])
