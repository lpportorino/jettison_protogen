(ns protocol-gen.constructs
  "What a descriptor database can and cannot be emitted from — the REFUSAL pass.

   An emitter that silently drops a construct it cannot express produces a
   schema that is wrong in a way no test finds: it compiles, it round-trips
   against itself, and every check that does not compare it to the source
   passes. So every field the projection was asked for is classified here
   first, and anything not expressible is named and refused rather than
   approximated.

   THREE CLASSES, and only the first is this namespace's to catch:

   1. DETECTABLE IN THE DATABASE — a type the generator has no emission for, a
      `:type-ref` that resolves to nothing, a number outside the wire's legal
      range or inside the range protoc reserves, two fields sharing a number, a
      oneof naming a member that is not there. Each is a refusal with a reason
      from `refusal-reasons`, and `survey` counts them over a whole database.

   2. DETECTABLE ONLY AGAINST THE POLICY — a granted field whose type is a
      message or enum the policy did not grant. That needs the grant to decide
      and is refused in `protocol-gen.projection`, with a reason from this same
      closed set so a caller reads one vocabulary.

   3. NOT DETECTABLE AT ALL, because the database never carried the fact. These
      are the ones worth writing down, since no gate here can ever find them:

      - `sint32`/`sint64` and the `fixed`/`sfixed` family are folded onto
        `int32`/`int64`/`uint32`/`uint64` by the producing parser. Those are
        DIFFERENT WIRE ENCODINGS — zigzag and fixed-width against varint — so a
        schema re-emitted from the database would decode the same bytes
        differently, and nothing in the database says so.
      - proto2 groups arrive as `:message`.
      - explicit field presence (`optional` in proto3), proto2 defaults,
        `reserved` ranges and names, extension ranges, `allow_alias`, services,
        `json_name` and every file-level option are absent from the database
        entirely.

      A generator cannot refuse what it cannot see. What it CAN do is refuse to
      pretend otherwise, which is why this list is in the source rather than in
      a commit message: the honest scope of a green run is \"nothing in class 1
      or 2\", never \"the emitted schema is wire-identical to the source\"."
  (:require [malli.core :as m]
            [protocol-gen.db :as db]))

(set! *warn-on-reflection* true)

(def min-field-number
  "The lowest legal proto field number. Zero is not a field number; it is the
   absence of one, which is exactly why a positional scheme starting at zero
   would be caught here and one starting at one would not."
  1)

(def max-field-number
  "The highest legal proto field number (2^29 - 1)."
  536870911)

(def reserved-number-range
  "protoc REFUSES 19000..19999 outright — the range the protobuf
   implementation reserves for itself. Inclusive at both ends."
  [19000 19999])

(def refusal-reasons
  "Every reason this generator may refuse to emit something, closed.

   Closed because a refusal is read by a canary that has to attribute a red to
   its own clause: a free-text reason lets two different defects wear the same
   message, and then a green after a fix proves nothing about which one moved."
  #{:unknown-field-type
    :missing-type-ref
    :unresolved-type-ref
    :field-number-out-of-range
    :field-number-reserved
    :duplicate-field-number
    :oneof-member-absent
    :type-not-granted
    :field-not-in-message
    :message-not-in-database
    :enum-not-in-database
    :unknown-constraint
    :constraint-type-mismatch
    :unnumbered-field
    :name-collision})

(def refusal
  "One refusal: which thing, why, and enough detail to fix it without reading
   the generator."
  [:map
   [:reason (into [:enum] (sort refusal-reasons))]
   [:subject [:string {:min 1}]]
   [:detail [:string {:min 1}]]])

(defn reserved-number?
  "True when `n` falls in the range protoc reserves for the implementation."
  [n]
  (let [[lo hi] reserved-number-range]
    (and (>= n lo) (<= n hi))))

(m/=> reserved-number? [:=> [:cat :int] :boolean])

(defn- number-refusal
  "The refusal for `n` as a field number on `subject`, or nil when it is legal."
  [subject n]
  (cond
    (reserved-number? n)
    {:reason :field-number-reserved
     :subject subject
     :detail (str "field number " n " is inside protoc's reserved range "
                  (first reserved-number-range) ".." (second reserved-number-range))}

    (or (< n min-field-number) (> n max-field-number))
    {:reason :field-number-out-of-range
     :subject subject
     :detail (str "field number " n " is outside " min-field-number ".."
                  max-field-number)}))

(m/=> number-refusal [:=> [:cat [:string {:min 1}] :int] [:maybe refusal]])

(defn- type-refusal
  "The refusal for `fld`'s type on `subject`, or nil when it is expressible."
  [database subject fld]
  (let [t (:type fld)
        type-ref (:type-ref fld)]
    (cond
      (not (contains? db/known-types t))
      {:reason :unknown-field-type
       :subject subject
       :detail (str "type " t " has no emission; the database's producer uses "
                    ":unknown for a descriptor type it cannot map")}

      (and (contains? db/referring-types t) (nil? type-ref))
      {:reason :missing-type-ref
       :subject subject
       :detail (str "type " t " must carry a :type-ref and carries none")}

      (and (contains? db/referring-types t)
           (not (or (db/message-ref? database type-ref) (db/enum-ref? database type-ref))))
      {:reason :unresolved-type-ref
       :subject subject
       :detail (str ":type-ref " type-ref " names neither a message nor an enum in "
                    "the database — a map field's entry message and a nested "
                    "enum both land here, because the producer drops both")})))

(m/=> type-refusal
      [:=> [:cat db/database [:string {:min 1}] db/field] [:maybe refusal]])

(defn field-refusals
  "Every reason `fld` of message `msg-id` cannot be emitted from `database`
   (empty when it can).

   A vector rather than the first hit: a field can be wrong in more than one
   way, and reporting one at a time turns a fix into a sequence of surprises."
  [database msg-id fld]
  (let [subject (str msg-id "." (:name fld))]
    (into []
          (remove nil?)
          [(type-refusal database subject fld)
           (number-refusal subject (:number fld))])))

(m/=> field-refusals
      [:=> [:cat db/database [:string {:min 1}] db/field] [:vector refusal]])

(defn- duplicate-number-refusals
  "One refusal per field number `msg` uses more than once."
  [msg]
  (for [[n fields] (group-by :number (:fields msg))
        :when (> (count fields) 1)]
    {:reason :duplicate-field-number
     :subject (str (:id msg) "." n)
     :detail (str "field number " n " is carried by "
                  (pr-str (mapv :name fields)))}))

(defn- oneof-refusals
  "One refusal per oneof member number `msg` declares but does not carry."
  [msg]
  (for [o (:oneofs msg)
        n (:fields o)
        :when (nil? (db/field-by-number msg n))]
    {:reason :oneof-member-absent
     :subject (str (:id msg) "." (:name o))
     :detail (str "oneof member number " n " names no field of this message")}))

(defn message-refusals
  "Every reason `msg` cannot be emitted whole from `database` — its own
   structural faults plus each field's."
  [database msg]
  (into (vec (concat (duplicate-number-refusals msg) (oneof-refusals msg)))
        (mapcat #(field-refusals database (:id msg) %))
        (:fields msg)))

(m/=> message-refusals [:=> [:cat db/database db/message] [:vector refusal]])

(defn survey
  "The construct enumeration over a whole `database`: what it holds, and what
   of it cannot be emitted, by reason.

   This is a REPORT, not a gate — a database is written over a wider corpus
   than any group is granted, so a refusal here is a fact about the corpus and
   becomes a failure only when a policy grants the thing that carries it. Read
   the counts from a run; a number written into prose rots on the next
   database."
  [database]
  (let [msgs (vals (:messages database))
        refusals (into [] (mapcat #(message-refusals database %)) msgs)]
    {:messages (count msgs)
     :enums (count (:enums database))
     :fields (reduce + 0 (map (comp count :fields) msgs))
     :oneofs (reduce + 0 (map (comp count :oneofs) msgs))
     :repeated-fields (count (filter :repeated (mapcat :fields msgs)))
     :types (into (sorted-map) (frequencies (map :type (mapcat :fields msgs))))
     :refusals (count refusals)
     :by-reason (into (sorted-map) (frequencies (map :reason refusals)))
     :subjects (into (sorted-map)
                     (map (fn [[reason rs]] [reason (vec (sort (map :subject rs)))]))
                     (group-by :reason refusals))}))

(m/=> survey
      [:=> [:cat db/database]
       [:map
        [:messages :int]
        [:enums :int]
        [:fields :int]
        [:oneofs :int]
        [:repeated-fields :int]
        [:types [:map-of :keyword :int]]
        [:refusals :int]
        [:by-reason [:map-of :keyword :int]]
        [:subjects [:map-of :keyword [:vector [:string {:min 1}]]]]]])
