(ns protocol-gen.numbering
  "The assign-once FIELD-number registry for locally-minted messages.

   A message projected out of a descriptor database already has its numbers —
   they are the descriptor's, and copying them is the whole of that case. A
   message MINTED here has none, and inventing one per run would make the wire
   a function of whatever the generator happened to do last. So a mint declares
   no number at all, and the only thing that can supply one is this registry:
   `{message-id {field-name number}}`, append-only, committed beside the mint.

   WHY THIS IS A SECOND REGISTRY AND NOT THE ONE THIS REPOSITORY ALREADY HAS.
   `lvgl-codegen.construct.registry` carries the same DISPOSITION — pinned
   wins, new gets the next free number, a retired name keeps its number, an
   unpinned member throws — and this namespace is written against it
   deliberately rather than in ignorance of it. Three things separate them, and
   only the first is about ownership:

   1. Generalising it means EDITING that tree, which is a live producer of
      committed projections that a freshness gate byte-compares. Changing it
      for the benefit of a new tool is the wrong direction of risk.
   2. The part that could be shared is I/O — read a map, write it sorted. The
      part that carries the SEMANTICS is typed to an LVGL enum construct and
      keyed on `:cast-class` and a value probed out of a vendored header;
      neither has any meaning for a message field.
   3. The numeric domain is genuinely different, and getting it wrong is not a
      style question. Enum numbers start at ZERO; field numbers start at ONE,
      stop at 2^29-1, and protoc REFUSES 19000..19999 outright. A next-free
      walk borrowed from the enum side would hand out zero for an empty entry
      and would step into the reserved range without noticing.

   ONE DELIBERATE DIVERGENCE FROM IT, and it is a tightening. The enum registry
   permits a RENAME TAKEOVER: a new member may take a retired name's number,
   because upstream renaming a constant leaves the value meaning what it meant.
   A message field has no such guarantee — a number reused for a different
   field is exactly the hazard proto's `reserved` keyword exists to prevent —
   so here a retired pin reserves its number for ever and a collision with ANY
   pin, live or retired, throws.

   RECONCILE IS NOT PART OF GENERATING. `apply-numbering` throws on an unpinned
   field; growing the registry is a separate, deliberate act with its own
   reviewed commit. A generator that could grow its own registry would assign a
   number as a side effect of being run, which is the one thing assign-once
   exists to prevent.

   WHAT A MINT MAY DECLARE, and where each part gets its number. A mint file is
   `{id declaration}`, and a declaration is a MESSAGE or an ENUM, tagged by
   `:kind` so the two are told apart by a value rather than by which key a
   reader happens to find. A message's fields are numbered by the registry and
   by nothing else; its oneofs name their members by FIELD NAME and are
   resolved here against the numbers the registry just supplied; an enum's
   members carry their own numbers inline, for the reasons `minted-enum-values`
   states. Nothing in this file derives a number from a position, and
   `apply-numbering` yields the same shape a descriptor database uses, so from
   there on a minted declaration and a projected one are indistinguishable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]))

(set! *warn-on-reflection* true)

(def field-number
  "A legal proto field number: 1..2^29-1, excluding protoc's reserved range.
   Declared as a schema rather than checked ad hoc so a hand-edited registry is
   refused at LOAD, before anything can be emitted from it."
  [:and
   [:int {:min constructs/min-field-number :max constructs/max-field-number}]
   [:fn {:error/message "inside protoc's reserved 19000..19999 range"}
    (fn [n] (not (constructs/reserved-number? n)))]])

(def registry-schema
  "`{message-id {field-name number}}` — e.g. `{\"grp.Heartbeat\" {\"seq\" 1}}`."
  [:map-of db/proto-qualified-name [:map-of db/proto-identifier field-number]])

(def mint-field
  "One field of a locally-minted message, AS DECLARED.

   CLOSED, AND WITH NO `:number` KEY. That absence is the point: a number
   cannot be written into a mint even by accident, so the registry is the only
   thing that can supply one."
  [:map {:closed true}
   [:name db/proto-identifier]
   [:type [:and :keyword [:fn {:error/message "not a type this generator emits"}
                          (fn [t] (contains? db/known-types t))]]]
   [:type-ref {:optional true} db/proto-qualified-name]
   [:repeated {:optional true} :boolean]
   [:constraints {:optional true} [:map-of :keyword db/constraint-value]]])

(def minted-oneof
  "One oneof of a locally-minted message, AS DECLARED.

   MEMBERS ARE NAMED BY FIELD NAME, and that is forced rather than preferred. A
   database oneof identifies its members by NUMBER — the wire identity, and
   what the projection re-resolves membership by — while a mint declares no
   number at all, because `mint-field` above is closed and has no `:number`
   key. The one identifier a mint does give a field is its NAME, so a name is
   the only thing a minted oneof can name a member with. `stamp-oneof` turns
   each name into the number the registry supplied for that field, which is why
   this is the ONLY place the two forms differ: everything downstream sees a
   `db/oneof`, members and all.

   THE ALTERNATIVE CONSIDERED, and why it lost. A `:oneof \"name\"` key on
   `mint-field` would make double membership unrepresentable — a field can
   carry only one such key — but `:required` is a real emitted option
   (`option (buf.validate.oneof).required`) with nowhere to live on a field, so
   a message-level declaration would still be needed and one oneof would then
   be declared in two places at once. That is worse than one place plus a
   refusal, and it would make a mint describe a construct differently from the
   database for no gain."
  [:map {:closed true}
   [:name db/proto-identifier]
   [:required :boolean]
   [:fields [:vector {:min 1} db/proto-identifier]]])

(def minted-message
  "One locally-minted MESSAGE as declared."
  [:map {:closed true}
   [:kind [:= :message]]
   [:name db/proto-identifier]
   [:fields [:vector {:min 1} mint-field]]
   [:oneofs {:optional true} [:vector {:min 1} minted-oneof]]])

(def minted-enum-values
  "The members of a locally-minted enum, each carrying its own number.

   NUMBERS ARE DECLARED INLINE AND DO NOT COME FROM THE REGISTRY — the one
   place a mint departs from how a minted FIELD is numbered. Three reasons, and
   the first alone settles it:

   - `field-number`, the registry's value schema, EXCLUDES 0 and protoc's
     reserved range, and proto3 REQUIRES an enum member numbered 0. A registry
     entry could not hold a legal enum value set at all.
   - the registry exists to stop generation INVENTING a number. A number
     written down in the mint is invented by nothing, so there is no assignment
     step for assign-once to protect; changing it is exactly as deliberate as
     changing a registry pin, and the two files are committed side by side.
   - a database enum carries its numbers inline for the same reason, so
     `db/enum-value` is reused verbatim here rather than mirrored, and a minted
     enum reaches the projection in the shape a projected one already has.

   THE THREE PREDICATES ARE THE FLOOR protoc WOULD OTHERWISE HAVE SUPPLIED. A
   descriptor database is produced from protoc's own output and so cannot carry
   an enum proto3 rejects; a mint has no such upstream, and a value the
   language cannot express must not reach a pass that assumes it can. They are
   LOAD-time shape rules rather than refusals carrying a reason because each
   would be caught LOUDLY by protoc on the emitted file — where the oneof
   faults `apply-numbering` refuses would be approximated SILENTLY instead,
   which is the distinction that decides where a check belongs."
  [:and
   [:vector {:min 1} db/enum-value]
   [:fn {:error/message "proto3 requires a member numbered 0"}
    (fn [vs] (boolean (some (comp zero? :number) vs)))]
   [:fn {:error/message "two members share a number, which needs allow_alias — not emittable here"}
    (fn [vs] (= (count vs) (count (distinct (map :number vs)))))]
   [:fn {:error/message "two members share a name"}
    (fn [vs] (= (count vs) (count (distinct (map :name vs)))))]])

(def minted-enum
  "One locally-minted ENUM as declared."
  [:map {:closed true}
   [:kind [:= :enum]]
   [:name db/proto-identifier]
   [:values minted-enum-values]])

(def minted-declaration
  "One locally-minted declaration: a message or an enum.

   TAGGED BY `:kind`, dispatched on that value. A sum told apart by which key
   happens to be present is decided by the reader guessing at a carrier's
   shape, so a declaration carrying neither `:fields` nor `:values` — or both —
   would be resolved by whichever test ran first. `:multi` with no default arm
   refuses an absent or unknown `:kind` outright, at load, which is where a
   malformed declaration is cheapest to refuse."
  [:multi {:dispatch :kind}
   [:message minted-message]
   [:enum minted-enum]])

(def mints-schema
  "`{id declaration}` — the declared mints, keyed by fully-qualified name."
  [:map-of db/proto-qualified-name minted-declaration])

(defn- load-edn!
  "Read `path`, validate it against `schema`, return it. Throws on a missing or
   malformed file: both are hard prerequisite failures of numbering, and a
   default here would let a run emit numbers nobody pinned.

   NO `m/=>`: its first argument is a malli schema, for which the only true
   spec is `:any` — a slot that constrains nothing, which this repository's
   spec-shape gate refuses and is right to. The two public readers below carry
   real specs instead."
  [schema path what]
  (when-not (java.io.File/.isFile (io/file path))
    (throw (ex-info (str what " not found") {:path path})))
  (let [value (edn/read-string (slurp path))]
    (if (m/validate schema value)
      value
      (throw (ex-info (str "Malformed " what)
                      {:path path :explain (m/explain schema value)})))))

(defn load-registry
  "Read and validate the committed field-number registry at `path`."
  [path]
  (load-edn! registry-schema path "field-number registry"))

(m/=> load-registry [:=> [:cat [:string {:min 1}]] registry-schema])

(defn load-mints
  "Read and validate the locally-minted declarations at `path`.

   The word MESSAGE is deliberately absent from the failure text below: a mint
   file carries enums too, and a message-shaped diagnosis on a malformed enum
   would point the reader at the wrong half of the file."
  [path]
  (load-edn! mints-schema path "minted declarations"))

(m/=> load-mints [:=> [:cat [:string {:min 1}]] mints-schema])

(defn save-registry!
  "Write `registry` to `path` deterministically — nested sorted maps — so the
   committed file diffs cleanly across reconciles."
  [path registry]
  (spit path
        (with-out-str
          (pp/pprint (into (sorted-map)
                           (map (fn [[id entry]] [id (into (sorted-map) entry)]))
                           registry))))
  nil)

(m/=> save-registry! [:=> [:cat [:string {:min 1}] registry-schema] :nil])

(defn next-free-number
  "The smallest number above every pin in `entry`, skipping protoc's reserved
   range. 1 for an empty entry — NOT 0, which is not a field number at all.

   Above EVERY pin rather than the smallest unused one, so a retired field's
   number is reserved by construction and can never be handed to a different
   field."
  [entry]
  (let [n (if (empty? entry) constructs/min-field-number (inc (reduce max (vals entry))))]
    (if (constructs/reserved-number? n) (inc (second constructs/reserved-number-range)) n)))

(m/=> next-free-number
      [:=> [:cat [:map-of db/proto-identifier field-number]] field-number])

(defn- reconcile-field
  "Fold one declared field into a message's registry entry: a pinned name keeps
   its number, a new one takes the next free, and a number already held —
   whether by a live field or a retired one — throws."
  [msg-id entry {field-name :name}]
  (if (contains? entry field-name)
    entry
    (let [n (next-free-number entry)]
      (when-let [holder (some (fn [[nm pin]] (when (= pin n) nm)) entry)]
        (throw (ex-info "Field-number collision"
                        {:message msg-id :field field-name :number n :held-by holder})))
      (assoc entry field-name n))))

(defn reconcile
  "Fold every field of every minted MESSAGE in `mints` into `registry`,
   returning the grown registry.

   Append-only: an existing pin is never changed and never removed, so a second
   reconcile of the same declarations is a no-op. Deliberately NOT called by
   generation — see the namespace docstring.

   AN ENUM DECLARATION IS SKIPPED ENTIRELY, not merely left unnumbered. Folding
   one would write an EMPTY entry under its id, putting a name in the registry
   that pins nothing — and a later reader could not tell that from a message
   whose fields had all been retired, which is a state the registry is supposed
   to record faithfully."
  [registry mints]
  (reduce (fn [reg [msg-id {:keys [kind fields]}]]
            (if (not= :message kind)
              reg
              (assoc reg msg-id (reduce #(reconcile-field msg-id %1 %2)
                                        (get reg msg-id {})
                                        fields))))
          registry
          mints))

(m/=> reconcile [:=> [:cat registry-schema mints-schema] registry-schema])

(defn- stamp-field
  "The declared field with its registry number stamped on, or a throw naming
   the pin that is missing. THIS IS THE ONLY PLACE A MINTED FIELD GETS A
   NUMBER, and it has no fallback."
  [msg-id entry {field-name :name :as fld}]
  (if-let [n (get entry field-name)]
    (assoc fld :number n :number-source :registry)
    (throw (ex-info "Field not in the field-number registry"
                    {:message msg-id
                     :field field-name
                     :remedy "run the reconcile subcommand and commit the registry"}))))

(defn- assert-oneof-members-distinct!
  "Refuse a field name claimed more than once across `oneofs`.

   A proto field belongs to at most ONE oneof, so such a declaration cannot be
   emitted as written — and nothing downstream would say so.
   `projection/oneof-of` takes the FIRST oneof carrying a number, so the losing
   block emits one member short, or vanishes entirely when that was its only
   member. Approximating a construct that cannot be expressed is the failure
   this generator exists to refuse, so it is refused here instead of shipped.

   This has no counterpart on the database side because protoc cannot produce
   the shape: a descriptor's fields carry a oneof INDEX, one apiece, so the
   producer of a real database has nothing to read that could name a field
   twice. It is reachable only from a hand-written mint."
  [msg-id oneofs]
  (doseq [[member claims] (sort-by key (group-by first (for [o oneofs
                                                             member (:fields o)]
                                                         [member (:name o)])))
          :when (> (count claims) 1)]
    (constructs/refuse! :oneof-member-shared (str msg-id "." member)
                        (str "field " (pr-str member) " is claimed as a oneof member more "
                             "than once, by " (pr-str (vec (sort (map second claims))))
                             "; a proto field belongs to at most one oneof"))))

(defn- stamp-oneof
  "One declared oneof with its member NAMES resolved to the numbers the
   registry supplied for this message's fields, yielding a `db/oneof`.

   RESOLVED AGAINST `by-name` — the fields just stamped — and never against the
   registry entry, which is the whole safety of this function. A registry entry
   legitimately outlives the field it pinned, because a retired pin keeps its
   number for ever; a member naming a retired field would therefore RESOLVE
   against the entry and yield a member number naming no live field. Resolving
   against the declared fields refuses it here, under the name the author
   actually wrote."
  [msg-id by-name {oneof-name :name :keys [required fields]}]
  {:name oneof-name
   :required required
   :fields (mapv (fn [member]
                   (or (get by-name member)
                       (constructs/refuse!
                        :oneof-member-absent (str msg-id "." oneof-name)
                        (str "oneof member " (pr-str member) " names no field of this "
                             "message; its fields are "
                             (pr-str (vec (sort (keys by-name))))))))
                 fields)})

(defn- stamp-message
  "One minted message with every field's registry number stamped on and every
   oneof member resolved to one of those numbers."
  [registry msg-id {msg-name :name :keys [fields oneofs]}]
  (let [entry (or (get registry msg-id)
                  (throw (ex-info "Message not in the field-number registry"
                                  {:message msg-id})))
        stamped (mapv #(stamp-field msg-id entry %) fields)
        by-name (into {} (map (juxt :name :number)) stamped)]
    (assert-oneof-members-distinct! msg-id oneofs)
    {:id msg-id
     :name msg-name
     :fields stamped
     :oneofs (mapv #(stamp-oneof msg-id by-name %) oneofs)}))

(defn- minted-enum->db
  "One minted enum in the shape a descriptor database records an enum in. Its
   members already carry their numbers, so nothing is stamped and nothing may
   be: see `minted-enum-values` for why the registry has no part in this."
  [enum-id {enum-name :name :keys [values]}]
  {:id enum-id :name enum-name :values values})

(defn apply-numbering
  "Stamp the registry's numbers over `mints`, yielding the same shape a
   DESCRIPTOR DATABASE uses — `{:messages {id message} :enums {id enum}}` — so
   a projection treats a minted declaration and a projected one identically
   from here on.

   Every minted MESSAGE and every one of its fields must ALREADY be pinned; a
   miss throws. That throw is the assign-once property: nothing on the
   generation path can invent a number, so a policy edit cannot move one. A
   minted ENUM is passed through carrying the numbers it declared, and the
   registry is not consulted for it at all.

   THE `case` HAS NO DEFAULT ARM ON PURPOSE. `minted-declaration` is a `:multi`
   with none either, so an unknown `:kind` cannot survive `load-mints`, and
   writing an arm here for a value that cannot arrive would be a branch nothing
   can reach and nothing can test."
  [registry mints]
  (reduce (fn [acc [id decl]]
            (case (:kind decl)
              :message (assoc-in acc [:messages id] (stamp-message registry id decl))
              :enum (assoc-in acc [:enums id] (minted-enum->db id decl))))
          {:messages {} :enums {}}
          mints))

(m/=> apply-numbering [:=> [:cat registry-schema mints-schema] db/database])

(def numbered-message
  "The shape `assert-stamped!` judges: anything carrying an id and fields. It
   deliberately does not name a fuller message schema, because both a projected
   message and a freshly-stamped mint pass through here and they carry
   different surrounding keys."
  [:map [:id db/proto-qualified-name] [:fields [:vector db/field]]])

(defn assert-stamped!
  "Throw unless every field of every message in `messages` carries a number AND
   says where it came from; return `messages` unchanged.

   THE LAST GUARD BEFORE EMISSION, and it exists because the emitter cannot
   defend itself: it reads `:number` and has no way to tell a descriptor's
   number from one a future pass invented. `:number-source` makes provenance a
   value rather than an assumption, and this is where the assumption is
   checked."
  [messages]
  (doseq [msg messages
          fld (:fields msg)]
    (when-not (contains? #{:descriptor :registry} (:number-source fld))
      (throw (ex-info "Field number has no recorded provenance"
                      {:message (:id msg)
                       :field (:name fld)
                       :number-source (:number-source fld)
                       :legal #{:descriptor :registry}})))
    (when-not (int? (:number fld))
      (throw (ex-info "Field reached emission with no number"
                      {:message (:id msg) :field (:name fld)}))))
  messages)

(m/=> assert-stamped!
      [:=> [:cat [:sequential numbered-message]] [:sequential numbered-message]])
