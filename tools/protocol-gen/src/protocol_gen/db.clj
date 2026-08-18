(ns protocol-gen.db
  "The INPUT side of the generator: a descriptor database, read and validated.

   A descriptor database is `{:messages {id message} :enums {id enum}}` — the
   shape this repository's own proto-documentation tooling emits from a protoc
   descriptor set, and the shape this tool's fixtures are written in. It is
   CONSUMED here and never written: nothing in this namespace produces one,
   and the generator has no path that edits its source.

   WHAT THE SCHEMAS BELOW ARE FOR, since it is not what a first reader expects.
   They are a LOAD-TIME floor, not the expressibility judgement — that lives in
   `protocol-gen.constructs`, one pass later, and is per FIELD rather than per
   file. The split is deliberate and the reason is scope: a database is written
   by a producer this tool does not own, over a corpus far wider than any one
   group is granted, so refusing the whole file because some message nobody
   projected carries something new would make the generator hostage to protos
   it never emits. The floor asks only whether the file is a database at all;
   the refusal asks whether THIS grant can be emitted truthfully.

   Two consequences of that, both deliberate:

   - `:type` is `:keyword` here rather than a closed set. The producer has an
     `:unknown` fallback for a descriptor type it has no mapping for, and that
     value must reach `constructs` as a REFUSAL rather than die as a parse
     error in a message no group asked for.
   - `:constraints` is a map of keyword to scalar here. Closing it would fail
     the load on any constraint the producer learns next, anywhere in the
     corpus; `protocol-gen.constraints` closes it per granted field instead,
     where dropping one would silently weaken an emitted schema."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def scalar-types
  "Database `:type` values that name a proto scalar outright — no `:type-ref`,
   no resolution step, emitted as themselves.

   ONE MEMBER PER WIRE ENCODING, and the six beyond the obvious ones are why
   this set is worth reading twice. `sint32`/`sint64` are ZIGZAG,
   `fixed32`/`fixed64`/`sfixed32`/`sfixed64` are FIXED-WIDTH, and
   `int32`/`int64`/`uint32`/`uint64` are varint two's-complement: the three
   families DECODE THE SAME BYTES TO DIFFERENT VALUES. The producing parser
   folded them onto one another for a long while, so a database could not say
   which it meant and this generator could not have emitted the difference if
   it had wanted to. It can now, and dropping one of these members would not
   produce a visibly wrong schema — it would produce a REFUSAL, which is the
   safe direction and the reason the omission survived unnoticed."
  #{:bool :bytes :double :float :int32 :int64 :string :uint32 :uint64
    :sint32 :sint64 :fixed32 :fixed64 :sfixed32 :sfixed64})

(def referring-types
  "Database `:type` values whose real type is named by `:type-ref` and has to be
   resolved against the database before anything can be emitted."
  #{:enum :message})

(def known-types
  "Every `:type` value this generator can emit. A field carrying anything else
   is refused by `protocol-gen.constructs`, never guessed at.

   TWO VALUES A DATABASE CAN CARRY ARE DELIBERATELY ABSENT:

   - `:unknown`, the producer's fallback for a descriptor type it has no
     mapping for. It exists so an unmappable type reaches a refusal here rather
     than dying as a parse error in a message nobody projected.
   - `:group`, the proto2 delimited encoding. proto3 HAS NO GROUP SYNTAX, so a
     group has no truthful emission from this generator and refusing is the
     only honest outcome — where folding it onto `:message`, which the
     producing parser used to do, silently substituted a length-delimited field
     for a tag-delimited one. Its absence here is what turns that fold into a
     refusal a caller can read."
  (into scalar-types referring-types))

(def proto-identifier
  "A proto identifier: a letter or underscore then letters, digits and
   underscores. Message names, field names and enum value names all conform,
   and a database value that does not is a refusal rather than something to
   quote into emitted text and hope."
  [:re #"^[A-Za-z_][A-Za-z0-9_]*$"])

(def proto-qualified-name
  "A dotted proto path — a package, a message id, a type reference. At least
   one identifier; dots separate, never lead or trail."
  [:re #"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$"])

(def constraint-scalar
  "One validation-constraint value where the constraint judges a value
   directly: a bound, a length, a pattern, a flag, or a vector of any of those
   (`in`, `not_in`, `example`)."
  [:or number? :string :boolean [:vector [:or number? :string]]])

(def constraint-value
  "One validation-constraint value as a database records it — either a scalar,
   or the ELEMENT RULES a repeated field's `:items` carries.

   THE NESTED ARM IS A LOAD-TIME NECESSITY, not a convenience. A repeated
   field may declare rules that judge each ELEMENT rather than the list, and
   the producer records them as `{rule-set {constraint value}}` — the rule set
   kept rather than folded onto the field's own type, so a consumer can tell a
   truthful re-emission from a silent substitution. A schema admitting only
   scalars refuses such a database at LOAD, in a message no group projects,
   which is precisely the corpus-wide failure this namespace's docstring says
   the floor must not cause.

   OPEN BY VALUE, CLOSED BY KEY LATER. It names the value shapes rather than
   `:any` so a reader learns what a constraint holds; which KEYS are legal —
   at either level — is `protocol-gen.constraints`' question, asked per granted
   field."
  [:or constraint-scalar
   [:map-of :keyword [:map-of :keyword constraint-scalar]]])

(def field
  "One field as the database records it.

   OPEN ON PURPOSE. A database carries documentation and interaction metadata
   this generator never reads, and closing the map would make an unrelated
   editorial addition upstream a hard failure here."
  [:map
   [:number :int]
   [:name proto-identifier]
   [:type :keyword]
   [:type-ref {:optional true} proto-qualified-name]
   [:repeated {:optional true} :boolean]
   [:constraints {:optional true} [:map-of :keyword constraint-value]]])

(def oneof
  "One oneof declaration: its name, whether the source declared it required,
   and the NUMBERS of its member fields. Numbers rather than names is the
   producer's shape and the right one — a number is the wire identity, and it
   is what the projection re-resolves members by."
  [:map
   [:name proto-identifier]
   [:required :boolean]
   [:fields [:vector :int]]])

(def message
  "One message as the database records it. `:id` is its fully-qualified name
   and is the key every reference resolves through."
  [:map
   [:id proto-qualified-name]
   [:name proto-identifier]
   [:fields [:vector field]]
   [:oneofs {:optional true} [:vector oneof]]])

(def enum-value
  "One enum member: its wire number and its name. The number is never derived
   from position — an enum's members may be declared in any order and a proto3
   enum's first member is pinned to zero by the LANGUAGE, not by this tool."
  [:map
   [:number :int]
   [:name proto-identifier]])

(def proto-enum
  "One enum as the database records it."
  [:map
   [:id proto-qualified-name]
   [:name proto-identifier]
   [:values [:vector enum-value]]])

(def database
  "The whole database. `:search-index` and any other producer-side key is
   tolerated and ignored — this tool reads messages and enums."
  [:map
   [:messages [:map-of proto-qualified-name message]]
   [:enums [:map-of proto-qualified-name proto-enum]]])

(defn validate!
  "Return `db` when it conforms to `database`; throw naming `path` and the
   explanation otherwise. A malformed database is a hard prerequisite failure,
   never something to work around with a default.

   NO `m/=>` ON PURPOSE, and the omission is the honest one. Its first argument
   is whatever `edn/read-string` produced — the tightest true schema for it is
   `:any`, which `lint-gate.specs` refuses outright and is right to: a slot
   constraining nothing either says why or comes out. The contract this
   function has is ENFORCED rather than annotated — the `m/validate` call below
   is a real call site that throws, which is a stronger guarantee than an arrow
   spec in this repository ever gets."
  [db path]
  (if (m/validate database db)
    db
    (throw (ex-info "Not a descriptor database"
                    {:path path :explain (m/explain database db)}))))

(defn load-database
  "Read and validate the EDN descriptor database at `path`.

   A missing file throws rather than yielding an empty database: an empty one
   projects to an empty schema, which every downstream check would then pass
   over. That is the vacuous green `.claude/rules/gate-enforcement.md` §3
   refuses, and it is cheapest to refuse here."
  [path]
  (when-not (java.io.File/.isFile (io/file path))
    (throw (ex-info "Descriptor database not found" {:path path})))
  (validate! (edn/read-string (slurp path)) path))

(m/=> load-database [:=> [:cat [:string {:min 1}]] database])

(defn message-ref?
  "True when `id` names a message in `db`."
  [db id]
  (contains? (:messages db) id))

(m/=> message-ref? [:=> [:cat database proto-qualified-name] :boolean])

(defn enum-ref?
  "True when `id` names an enum in `db`."
  [db id]
  (contains? (:enums db) id))

(m/=> enum-ref? [:=> [:cat database proto-qualified-name] :boolean])

(defn field-by-number
  "The field of `msg` carrying wire number `n`, or nil.

   Fields are looked up by NUMBER and never by position anywhere in this tool;
   this is the one lookup helper, so there is a single place that could ever
   have got it wrong."
  [msg n]
  (first (filter #(= n (:number %)) (:fields msg))))

(m/=> field-by-number [:=> [:cat message :int] [:maybe field]])
