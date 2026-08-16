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
   exists to prevent."
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

(def mint
  "One locally-minted message as declared."
  [:map {:closed true}
   [:name db/proto-identifier]
   [:fields [:vector {:min 1} mint-field]]])

(def mints-schema
  "`{message-id mint}` — the declared mints, keyed by fully-qualified name."
  [:map-of db/proto-qualified-name mint])

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
  "Read and validate the locally-minted message declarations at `path`."
  [path]
  (load-edn! mints-schema path "minted-message declarations"))

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
  "Fold every field of `mints` into `registry`, returning the grown registry.

   Append-only: an existing pin is never changed and never removed, so a second
   reconcile of the same declarations is a no-op. Deliberately NOT called by
   generation — see the namespace docstring."
  [registry mints]
  (reduce (fn [reg [msg-id {:keys [fields]}]]
            (assoc reg msg-id (reduce #(reconcile-field msg-id %1 %2)
                                      (get reg msg-id {})
                                      fields)))
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

(defn apply-numbering
  "Stamp the registry's numbers over `mints`, yielding message maps in the same
   shape a descriptor database uses — so a projection treats a minted message
   and a projected one identically from here on.

   Every message and every field must ALREADY be pinned; a miss throws. That
   throw is the assign-once property: nothing on the generation path can invent
   a number, so a policy edit cannot move one."
  [registry mints]
  (into {}
        (map (fn [[msg-id {msg-name :name :keys [fields]}]]
               (let [entry (or (get registry msg-id)
                               (throw (ex-info "Message not in the field-number registry"
                                               {:message msg-id})))]
                 [msg-id {:id msg-id
                          :name msg-name
                          :fields (mapv #(stamp-field msg-id entry %) fields)
                          :oneofs []}])))
        mints))

(m/=> apply-numbering
      [:=> [:cat registry-schema mints-schema]
       [:map-of db/proto-qualified-name db/message]])

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
