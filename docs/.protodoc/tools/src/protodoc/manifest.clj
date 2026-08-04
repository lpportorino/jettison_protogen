(ns protodoc.manifest
  "Generate machine-readable JSON manifests from proto-db.edn.

   Produces:
   - endpoints.json  — REST endpoint manifest for each leaf command
   - signals.json    — Signal manifest for each scalar field in JonGUIState subsystems
   - sub-signals.json — Signals for deeply nested fields (power modules, etc.)
   - reverse-index.json — Links endpoints/signals to doc files"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [protodoc.schema :as schema]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Name Derivation Functions (no csk dependency)
;; ============================================================================

(defn snake->camel
  "Convert snake_case to camelCase.
   Examples:
     \"camera_day\" → \"cameraDay\"
     \"focus_pos\" → \"focusPos\"
     \"s0\" → \"s0\"
     \"is_on\" → \"isOn\""
  [^String s]
  (let [parts (str/split s #"_")]
    (str (first parts)
         (str/join (map str/capitalize (rest parts))))))

(defn snake->kebab
  "Convert snake_case to kebab-case.
   Examples:
     \"set_iris\" → \"set-iris\"
     \"DayCamera\" → \"day-camera\"
     \"Lrf_calib\" → \"lrf-calib\""
  [^String s]
  ;; Insert hyphens before uppercase runs, then lowercase everything
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      str/lower-case
      (str/replace "_" "-")))

;; ============================================================================
;; Root / Group Identification
;; ============================================================================

(defn root-message?
  "Is this message a Root routing container?"
  [msg]
  (str/ends-with? (:id msg) ".Root"))

(defn group-message?
  "Is this a nested group container (has oneof named 'cmd')?
   Excludes Root messages. Includes Lrf_calib.Offsets-style groups."
  [msg]
  (and (not (root-message? msg))
       (some #(= "cmd" (:name %)) (:oneofs msg))))

(defn routing-container?
  "Is this message a routing container (Root or group)?"
  [msg]
  (or (root-message? msg)
      (group-message? msg)))

;; ============================================================================
;; Constraint → Malli Schema Conversion
;; ============================================================================

;; Proto fixed-width integer ceilings. Every integer field is finite-bounded to
;; its TYPE range so malli's generator stays finite AND never over-generates a
;; value that overflows the wire (e.g. a Long past UINT32_MAX into a uint32
;; field). uint64-max exceeds Long/MAX_VALUE, so uint64 is a BigInt with its own
;; custom schema (malli :int is Long-backed — it cannot hold the upper half).
(def ^:private int32-min -2147483648)
(def ^:private int32-max 2147483647)
(def ^:private uint32-max 4294967295)
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)
(def ^:private uint64-max 18446744073709551615N)

;; IEEE-754 single-precision maximum (3.4028235e38). A float field whose value
;; exceeds this saturates to ±Inf on the 32-bit wire, so :float ranges are
;; clamped here.
(def ^:private float-max (double Float/MAX_VALUE))

(defn- int-range
  "Build the {:min .. :max ..} props map for an integer field from its
   buf.validate constraints, clamped to the type's representable range
   [floor, ceil]. dec/inc is CORRECT for integers (the bound is a whole number,
   so exclusive `gt a` is exactly `min (inc a)` and `lt b` is exactly
   `max (dec b)`); inc'/dec' auto-promote so a bound at the type edge cannot
   overflow Long. An UNCONSTRAINED end defaults to the type bound, so every
   integer field is finite AND in-type (an unconstrained uint32 is
   [:int {:min 0 :max 4294967295}], not an open [:int {:min 0}]).

   Fails loud on an empty range — an exclusive `gt` at the type maximum (or `lt`
   at the minimum) is unsatisfiable; emitting a clamped schema there would
   silently ACCEPT a value the constraint forbids, so we throw instead."
  [constraints floor ceil]
  (let [{:keys [gte gt lte lt]} constraints
        lo (cond gte gte gt (inc' gt) :else floor)
        hi (cond lte lte lt (dec' lt) :else ceil)]
    (when (or (> lo hi) (> lo ceil) (< hi floor))
      (throw (ex-info "integer constraint yields an empty value range (e.g. gt at the type maximum)"
                      {:constraints constraints :type-range [floor ceil] :lo lo :hi hi})))
    {:min (max lo floor) :max (min hi ceil)}))

(defn- no-default?
  "Does this constraint set encode the proto3 \"reject the zero default\"
   intent? Two patterns mean exactly that: gte:1 or gt:0 on a scalar. (The
   enum not_in:[0] case is handled in the :enum branch.) A general exclusion
   like not_in:[3 7] is a literal exclusion, NOT this marker."
  [{:keys [gte gt]}]
  (or (= gte 1) (= gt 0)))

(defn- with-no-default
  "Mark a schema with the :no-default property (a proto3 zero-reject marker).
   The marker lives inside the schema's own malli properties map so the result
   stays a valid, generatable, edn-round-trippable schema. A bare-keyword
   schema (e.g. :int) is promoted to its vector form to carry the property."
  [schema]
  (cond
    (keyword? schema) [schema {:no-default true}]
    ;; [:tag {props} ...] — merge into existing props map
    (and (vector? schema) (map? (second schema)))
    (update schema 1 assoc :no-default true)
    ;; [:tag child ...] with no props map — insert one
    (vector? schema)
    (into [(first schema) {:no-default true}] (rest schema))
    :else schema))

(defn- int-schema
  "Malli schema for a Long-representable integer field (int32/uint32/int64),
   finite-bounded to [floor, ceil] (the proto type range). Surfaces a
   :no-default marker for the proto3 zero-reject patterns (gte:1 / gt:0)."
  [constraints floor ceil]
  (let [base [:int (int-range constraints floor ceil)]]
    (if (and constraints (no-default? constraints))
      (with-no-default base)
      base)))

(defn- uint64-schema
  "Malli schema for a uint64 field — a custom [:uint64 {:min .. :max ..}] marker
   carrying a BigInt value space. malli :int is Long-backed and CANNOT represent
   the uint64 upper half [2^63, 2^64-1] (m/validate :int 2^64-1 ⇒ false), so a
   uint64 needs a BigInteger-backed schema. manifest.clj emits only the DATA
   marker (so the doc-gen path needs no generator); the gencorpus layer supplies
   the validation pred + a BigInteger generator for [:uint64 ...]. The implicit
   floor is 0 (unsigned); gte/gt/lte/lt are honored and clamped to [0, 2^64-1].
   Bounds are BigInt (the N literals round-trip through EDN; a BigInteger would
   print as a bare Long and mis-read)."
  [constraints]
  (let [{:keys [gte gt lte lt]} constraints
        lo (cond gte (bigint gte) gt (inc (bigint gt)) :else 0N)
        hi (cond lte (bigint lte) lt (dec (bigint lt)) :else uint64-max)
        lo (max 0N lo)
        hi (min uint64-max hi)]
    (when (> lo hi)
      (throw (ex-info "uint64 constraint yields an empty value range"
                      {:constraints constraints :lo lo :hi hi})))
    [:uint64 {:min lo :max hi}]))

(defn- bytes-schema
  "Malli schema for a proto bytes field — a custom [:bytes {:min .. :max ..}]
   marker (min/max are OCTET-count bounds from min_len/max_len). The gencorpus
   layer generates a vector of octets (0-255) and sets it as a ByteString on the
   DynamicMessage; base64 is never involved. The old :bytes→bare-:string mapping
   either base64-MISENCODED (a generated string was base64-DECODED to different
   bytes) or hard-CRASHED the serializer on a non-base64 charset."
  [constraints]
  (let [{:keys [min-len max-len]} constraints
        props (cond-> {}
                (some? min-len) (assoc :min min-len)
                (some? max-len) (assoc :max max-len))]
    (if (seq props) [:bytes props] :bytes)))

(defn- double-schema
  "Malli schema for a double/float field. Inclusive bounds (gte/lte) map
   straight onto :min/:max. EXCLUSIVE double bounds must NOT use dec/inc (a
   double {lt 360} would still accept 359.9999): the bound stays inclusive in
   the props and an :fn guard excludes exactly the open boundary. A
   pure-inclusive range needs no :fn. Surfaces a :no-default marker for the
   proto3 zero-reject patterns (gte:1 / gt:0).

   FLOAT FINITE-BOUNDING: a :float field is clamped to [-FLT_MAX, FLT_MAX] —
   including the UNCONSTRAINED and open-ended cases — because malli's :float is a
   Double generator with no 32-bit ceiling, and a value past FLT_MAX saturates to
   ±Inf on the fixed32 wire. :double keeps malli's default (already finite — no
   NaN/Inf in its draw); NaN/±Inf are a deliberate violating-corpus injection,
   not a positive-corpus value. Float32 QUANTIZATION (so EDN == wire) is the
   gencorpus generator's job, not the validation schema's."
  [base-tag constraints]
  (let [flt? (= :float base-tag)
        {:keys [gte gt lte lt]} constraints
        excl-lo? (some? gt)
        excl-hi? (some? lt)
        lo0 (or gte gt (when flt? (- float-max)))
        hi0 (or lte lt (when flt? float-max))
        lo (when (some? lo0) (if flt? (max lo0 (- float-max)) lo0))
        hi (when (some? hi0) (if flt? (min hi0 float-max) hi0))
        props (cond-> {}
                (some? lo) (assoc :min lo)
                (some? hi) (assoc :max hi))
        base (if (seq props) [base-tag props] base-tag)
        schema (if (or excl-lo? excl-hi?)
                 [:and base
                  [:fn (fn [x]
                         (and (or (not excl-lo?) (> x lo))
                              (or (not excl-hi?) (< x hi))))]]
                 base)]
    (if (and constraints (no-default? constraints))
      (with-no-default schema)
      schema)))

(defn- string-schema
  "Malli schema for a string field. A :pattern emits [:re pattern] (the regex
   subsumes any length bound — e.g. the UUID fields); a non-empty :in set emits
   [:enum allowed...]; otherwise min-len/max-len map onto [:string {:min :max}].
   NOTE: buf.validate min_len/max_len are UTF-8 BYTE counts while malli :string
   bounds are CHAR counts. They agree for the single-byte ASCII the default
   :string generator produces, so length-constrained fields stay ASCII; widening
   the charset (non-ASCII coverage) must first re-derive the byte budget."
  [constraints]
  (let [{:keys [min-len max-len pattern in]} constraints]
    (cond
      (seq in) (into [:enum] in)
      pattern  [:re pattern]
      :else (let [props (cond-> {}
                          (some? min-len) (assoc :min min-len)
                          (some? max-len) (assoc :max max-len))]
              (if (seq props) [:string props] :string)))))

(defn- enum-schema
  "Malli schema for an enum field: the EXPLICIT allowed subset
   [:enum name ...], excluding any sentinel listed in the field's :not-in (the
   exclusion joins the enum's :number against :not-in). When :not-in is the
   proto3 zero-default [0], surface a :no-default marker. Enum values are the
   constant NAMES (the manifest serializes enum names; there is no :value key).
   Falls back to :string when the enum-def is not in enums-map."
  [constraints type-ref enums-map]
  (if-let [enum-def (get enums-map type-ref)]
    (let [not-in (set (:not-in constraints))
          names (->> (:values enum-def)
                     (remove #(contains? not-in (:number %)))
                     (mapv :name))]
      (if (= not-in #{0})
        (into [:enum {:no-default true}] names)
        (into [:enum] names)))
    :string))

(defn constraints->malli
  "Convert buf.validate constraints + field type to a Malli schema as DATA
   (vectors / keywords / maps; an :fn only where a double-exclusive bound
   requires it). The result is a generatable, constrained schema — never a
   string.

   Encoding highlights:
   - every integer is finite-bounded to its proto TYPE range (int32/uint32/
     int64), honoring gte/gt/lte/lt (dec/inc is correct: bounds are whole);
     an empty range (gt at the type max) fails loud. uint64 needs a custom
     [:uint64 ...] BigInt schema — malli :int cannot hold its upper half.
   - doubles keep exclusive bounds inclusive in props and add an :fn guard
     that excludes exactly the open boundary (dec/inc is WRONG for doubles);
     :float is finite-bounded to ±FLT_MAX (a wider value overflows the wire).
   - enums emit the explicit allowed subset with :not-in sentinels removed.
   - strings honor :pattern ([:re]) and :in ([:enum]); bytes are a custom
     [:bytes ...] octet schema (NOT a string — base64 would misencode).
   - the proto3 zero-reject patterns (enum not_in:[0], scalar gte:1 / gt:0)
     carry a :no-default marker in the schema's malli properties map."
  [{:keys [constraints type-ref], ftype :type} enums-map]
  (case ftype
    :double (double-schema :double constraints)
    ;; :float kept distinct from :double so the field type survives.
    :float (double-schema :float constraints)
    :int32 (int-schema constraints int32-min int32-max)
    :uint32 (int-schema constraints 0 uint32-max)
    :int64 (int-schema constraints int64-min int64-max)
    :uint64 (uint64-schema constraints)
    :bool :boolean
    :string (string-schema constraints)
    :enum (enum-schema constraints type-ref enums-map)
    :bytes (bytes-schema constraints)
    :message :map
    :any))

;; ============================================================================
;; Endpoint Extraction
;; ============================================================================

(defn- endpoint-field
  "Project a proto-db field of message `owner-id` into one entry of an endpoint's
   `:fields` vector.

   THE PROJECTION IS ONE LEVEL DEEP AND STAYS THAT WAY. A MESSAGE-typed field
   publishes no interior, because the interior is not expressible in a flat field
   vector: `cmd.RotaryPlatform.Azimuth` is the measured case — a REQUIRED six-arm
   oneof whose arms are themselves messages — so flattening it here would publish
   six MUTUALLY EXCLUSIVE arms as though they were co-settable. A manifest that
   asserts something false is worse than one that stops short, and minting that
   structure is the same objection `resolve-leaf-commands` raises against recursing
   on the depth axis.

   What the entry may NOT do is decline to say what it stopped short OF, so a
   message-typed field carries `:type-ref` — the id of the message its value is.
   That is a REFERENCE, not an expansion: it names a message the consumer already
   holds in `proto-db.edn`, mints no route, and invents no shape. Every other field
   is unchanged; a scalar publishes its whole value space inline in `:constraints`,
   and an enum resolves by the name lookup its consumers already fail loud on.

   FAILS LOUD on a message-typed field carrying no `:type-ref` — the one field this
   projection can express neither inline NOR by reference. proto-db's own `Field`
   schema declares `:type-ref` \"For enum/message refs\", so its absence is a
   malformed db rather than a shape to publish, and emitting `{name, type}` there
   would republish exactly the opaque entry `:type-ref` exists to retire. Latent
   rather than live: all 327 message-typed fields in the current db carry one, so
   the guard cannot fire on this tree — it adds no data path of its own, and the
   only bytes this projection moves are the `:type-ref` keys above."
  [owner-id f]
  (let [message? (= :message (:type f))]
    (when (and message? (nil? (:type-ref f)))
      (throw (ex-info "message-typed command field names no message; :type-ref is absent, so the endpoint manifest can neither publish its interior nor point at it"
                      {:message owner-id :field (:name f) :type (:type f)})))
    (cond-> {:name (:name f)
             :type (name (:type f))}
      message? (assoc :type-ref (:type-ref f))
      (:constraints f) (assoc :constraints (:constraints f))
      (:interaction f) (assoc :interaction (:interaction f)))))

(defn- resolve-leaf-commands
  "Walk a Root or group message and collect leaf commands.
   Returns sequence of {:id :subsystem :command :path :root-id :oneof-field :fields :interaction :proto-source :doc-file}."
  [msgs root-msg subsystem-kebab parent-path]
  (for [field (:fields root-msg)
        :let [child (get msgs (:type-ref field))]
        :when child
        cmd (if (group-message? child)
              ;; Nested group — recurse one level deeper
              (let [group-kebab (snake->kebab (:name field))
                    group-path (str parent-path "/" group-kebab)]
                (for [gf (:fields child)
                      :let [leaf (get msgs (:type-ref gf))]
                      ;; FAIL LOUD ON DEPTH THIS RESOLVER CANNOT EXPRESS. The
                      ;; recursion is ONE level, so a group reached THROUGH a group
                      ;; has no branch here — and the `:when` below would simply
                      ;; filter it out, dropping every leaf command beneath it from
                      ;; the manifest with no diagnostic. A command that silently
                      ;; does not exist is worse than a build that stops.
                      ;;
                      ;; NOT fixed by recursing, deliberately: recursion would start
                      ;; emitting endpoints at paths nobody designed, silently
                      ;; changing a TRACKED manifest. A throw forces the path scheme
                      ;; to be decided by a person when the proto actually nests.
                      ;;
                      ;; Measured: ZERO groups sit at depth >= 2 relative to any of
                      ;; the 14 subsystem roots in the current tree, so this is
                      ;; latent and the guard costs nothing today. `Lrf_calib` is NOT
                      ;; an instance — `resolve-lrf-calib-commands` exists for a
                      ;; different ROOT SHAPE (channel dispatch), at the same depth.
                      :let [_ (when (and leaf (group-message? leaf))
                                (throw (ex-info "command group nested deeper than resolve-leaf-commands recurses; its leaf commands would be dropped from the manifest"
                                                {:root (:id root-msg)
                                                 :group (:id child)
                                                 :nested-group (:id leaf)
                                                 :field (:name gf)})))]
                      :when (and leaf (not (routing-container? leaf)))]
                  {:id (:id leaf)
                   :subsystem subsystem-kebab
                   :command (snake->kebab (:name gf))
                   :path (str group-path "/" (snake->kebab (:name gf)))
                   :root-id (:id root-msg)
                   :oneof-field (:name gf)
                   :proto-source (:source leaf)
                   :doc-file (str "proto/" (:id leaf) ".md")
                   :fields (mapv #(endpoint-field (:id leaf) %) (:fields leaf))
                   :interaction (:interaction leaf)}))
              ;; Direct leaf command
              (when-not (routing-container? child)
                [{:id (:id child)
                  :subsystem subsystem-kebab
                  :command (snake->kebab (:name field))
                  :path (str parent-path "/" (snake->kebab (:name field)))
                  :root-id (:id root-msg)
                  :oneof-field (:name field)
                  :proto-source (:source child)
                  :doc-file (str "proto/" (:id child) ".md")
                  :fields (mapv #(endpoint-field (:id child) %) (:fields child))
                  :interaction (:interaction child)}]))]
    cmd))

(defn- resolve-lrf-calib-commands
  "Special handling for cmd.Lrf_calib.Root which has channel dispatch (day/heat)
   instead of the standard cmd oneof."
  [msgs root-msg]
  ;; Lrf_calib.Root has oneof 'channel' with 'day' and 'heat' fields,
  ;; both pointing to cmd.Lrf_calib.Offsets (a group with oneof 'cmd')
  (for [channel-field (:fields root-msg)
        :let [offsets-msg (get msgs (:type-ref channel-field))]
        :when (and offsets-msg (group-message? offsets-msg))
        leaf-field (:fields offsets-msg)
        :let [leaf (get msgs (:type-ref leaf-field))]
        :when (and leaf (not (routing-container? leaf)))]
    {:id (:id leaf)
     :subsystem "lrf-calib"
     :command (str (snake->kebab (:name channel-field))
                   "/" (snake->kebab (:name leaf-field)))
     :path (str "cmd/lrf-calib/"
                (snake->kebab (:name channel-field))
                "/" (snake->kebab (:name leaf-field)))
     :root-id (:id root-msg)
     :oneof-field (str (:name channel-field) "." (:name leaf-field))
     :proto-source (:source leaf)
     :doc-file (str "proto/" (:id leaf) ".md")
     :fields (mapv #(endpoint-field (:id leaf) %) (:fields leaf))
     :interaction (:interaction leaf)}))

(defn extract-endpoints
  "Extract all leaf command endpoints from the database."
  [db config]
  (let [msgs (:messages db)
        cmd-root (get msgs "cmd.Root")
        cmd-skip (:cmd-skip-fields config #{})
        endpoints (for [root-field (:fields cmd-root)
                        :when (not (cmd-skip (:name root-field)))
                        :let [subsystem-root (get msgs (:type-ref root-field))]
                        :when (and subsystem-root (root-message? subsystem-root))
                        :let [subsystem-kebab (snake->kebab (:name root-field))
                              base-path (str "cmd/" subsystem-kebab)]
                        ep (if (= "cmd.Lrf_calib.Root" (:id subsystem-root))
                             (resolve-lrf-calib-commands msgs subsystem-root)
                             (resolve-leaf-commands msgs subsystem-root
                                                    subsystem-kebab base-path))]
                    ep)
        ;; Build subsystem summary
        by-subsystem (group-by :subsystem endpoints)
        subsystems (for [[sub eps] (sort by-subsystem)]
                     {:name sub
                      :root-id (:root-id (first eps))
                      :endpoint-count (count eps)})]
    {:endpoints (vec (sort-by :path endpoints))
     :subsystems (vec subsystems)}))

;; ============================================================================
;; Signal Extraction
;; ============================================================================

(defn make-signal-name
  "Create a camelCase signal name from subsystem prefix and field name.
   prefix: \"cameraDay\", field: \"focus_pos\" → \"cameraDayFocusPos\""
  [prefix field-name]
  (let [field-camel (snake->camel field-name)
        ;; Capitalize first letter of field camel for concat
        capitalized (str (str/upper-case (subs field-camel 0 1))
                         (subs field-camel 1))]
    (str prefix capitalized)))

(defn extract-signals
  "Extract all signals from JonGUIState subsystem fields."
  [db config]
  (let [msgs (:messages db)
        state-msg (get msgs "ser.JonGUIState")
        skip-fields (:root-skip-fields config #{})
        results
        (for [f (:fields state-msg)
              :when (and (= :message (:type f))
                         (not (skip-fields (:name f))))
              :let [sub-msg (get msgs (:type-ref f))
                    prefix (snake->camel (:name f))]]
          {:subsystem-field (:name f)
           :prefix prefix
           :sub-msg sub-msg
           :signals
           (for [sf (:fields sub-msg)
                 :when (not= :message (:type sf))]
             {:signal-name (make-signal-name prefix (:name sf))
              :subsystem-field (:name f)
              :subsystem-prefix prefix
              :field-name (:name sf)
              :proto-message (:id sub-msg)
              :proto-field-number (:number sf)
              :type (name (:type sf))
              :constraints (:constraints sf)
              :doc-file (str "proto/" (:id sub-msg) ".md")})})
        all-signals (mapcat :signals results)
        subsystems (for [r results]
                     {:field-in-root (:subsystem-field r)
                      :signal-prefix (:prefix r)
                      :proto-message (:id (:sub-msg r))
                      :signal-count (count (:signals r))})]
    {:signals (vec (sort-by :signal-name all-signals))
     :subsystems (vec subsystems)
     :derived-signals (or (:derived-signals config) [])}))

;; ============================================================================
;; Sub-Signal Extraction (deeply nested: power modules, heater channels, etc.)
;; ============================================================================

(defn extract-sub-signals
  "Extract signals from nested message fields (power s0-s7, heater channels, etc.)."
  [db config]
  (let [msgs (:messages db)
        state-msg (get msgs "ser.JonGUIState")
        skip-fields (:root-skip-fields config #{})
        nested-entries
        (for [sf (:fields state-msg)
              :when (and (= :message (:type sf))
                         (not (skip-fields (:name sf))))
              :let [sub-msg (get msgs (:type-ref sf))
                    sub-prefix (snake->camel (:name sf))]
              nf (:fields sub-msg)
              :when (= :message (:type nf))
              :let [nested-msg (get msgs (:type-ref nf))]
              :when nested-msg]
          {:parent-subsystem (:name sf)
           :parent-field (:name nf)
           :parent-message (:id sub-msg)
           :nested-message (:id nested-msg)
           :signals
           (vec (for [leaf (:fields nested-msg)
                      :when (not= :message (:type leaf))]
                  {:signal-name (make-signal-name
                                 (str sub-prefix
                                      (str/upper-case (subs (snake->camel (:name nf)) 0 1))
                                      (subs (snake->camel (:name nf)) 1))
                                 (:name leaf))
                   :field-name (:name leaf)
                   :type (name (:type leaf))
                   :constraints (:constraints leaf)}))})]
    {:nested-signals (vec nested-entries)}))

;; ============================================================================
;; Reverse Index
;; ============================================================================

(defn state-ref-doc-file
  "Vault path of the page a `:related-state` reference points into.

   The FIELD half is dropped on purpose: a page exists per message, so
   `proto/ser.Foo#bar.md` names nothing. Every doc-file the reverse index emits
   goes through here rather than interpolating the raw reference, which is what
   the field grain would otherwise silently break."
  [ref-str]
  (str "proto/" (first (schema/split-state-ref ref-str)) ".md"))

(defn state-ref-matches-signal?
  "Does a `:related-state` reference name this signal?

   A field-grained reference matches ONE signal; a message-grained one matches
   every signal derived from that message, which is the imprecision the field
   grain exists to remove and the reason both arms are here rather than one."
  [ref-str sig]
  (let [[target-id field-name] (schema/split-state-ref ref-str)]
    (and (= target-id (:proto-message sig))
         (or (nil? field-name)
             (= field-name (:field-name sig))))))

(defn build-reverse-index
  "Build reverse index linking doc files to endpoints and signals."
  [endpoints-data signals-data]
  (let [;; By doc file
        ep-by-doc (group-by :doc-file (:endpoints endpoints-data))
        sig-by-doc (group-by :doc-file (:signals signals-data))

        ;; Build by-doc-file map
        all-doc-files (set (concat (keys ep-by-doc) (keys sig-by-doc)))
        by-doc-file
        (into {}
              (for [df all-doc-files]
                [df (cond-> {}
                      (seq (get ep-by-doc df))
                      (assoc :endpoints (mapv :path (get ep-by-doc df)))

                      (seq (get sig-by-doc df))
                      (assoc :signals (mapv :signal-name (get sig-by-doc df)))

                      ;; Cross-link: endpoints → related signals via interaction.related-state
                      (seq (get ep-by-doc df))
                      (as-> m
                            (let [related-state-docs
                                  (->> (get ep-by-doc df)
                                       (mapcat #(get-in % [:interaction :related-state]))
                                       (map state-ref-doc-file)
                                       distinct)]
                              (if (seq related-state-docs)
                                (assoc m :related-signal-docs (vec related-state-docs))
                                m)))

                      ;; Cross-link: signals → related endpoints via endpoint interaction.related-state
                      (seq (get sig-by-doc df))
                      (as-> m
                            (let [related-eps
                                  (->> (:endpoints endpoints-data)
                                       (filter #(some (fn [rs]
                                                        (= df (state-ref-doc-file rs)))
                                                      (get-in % [:interaction :related-state])))
                                       (map :path)
                                       distinct)]
                              (if (seq related-eps)
                                (assoc m :related-endpoints (vec related-eps))
                                m))))]))

        ;; By endpoint
        by-endpoint
        (into {}
              (for [ep (:endpoints endpoints-data)]
                [(:path ep)
                 (cond-> {:doc-file (:doc-file ep)}
                   (seq (get-in ep [:interaction :related-state]))
                   (assoc :related-state-docs
                          (into [] (distinct)
                                (map state-ref-doc-file
                                     (get-in ep [:interaction :related-state])))))]))

        ;; By signal
        by-signal
        (into {}
              (for [sig (:signals signals-data)]
                [(:signal-name sig)
                 (cond-> {:doc-file (:doc-file sig)}
                   ;; Find endpoints whose related-state includes this signal's message
                   true
                   (as-> m
                         (let [related-eps
                               (->> (:endpoints endpoints-data)
                                    (filter #(some (fn [rs]
                                                     (state-ref-matches-signal? rs sig))
                                                   (get-in % [:interaction :related-state])))
                                    (map :doc-file)
                                    distinct)]
                           (if (seq related-eps)
                             (assoc m :related-cmd-docs (vec related-eps))
                             m))))]))]

    {:by-doc-file by-doc-file
     :by-endpoint by-endpoint
     :by-signal by-signal}))

;; ============================================================================
;; Manifest Generation (top-level)
;; ============================================================================

(defn load-config
  "Load manifest config from EDN file."
  [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    (do (t/log! :warn ["Config not found, using defaults:" path])
        {:root-skip-fields #{}
         :cmd-skip-fields #{}
         :derived-signals []})))

(defn generate-manifests
  "Generate all manifest files from proto-db.edn.

   Options:
   - :db-path     — path to proto-db.edn
   - :config-path — path to manifest-config.edn
   - :output-dir  — path to output manifests directory
   - :git-sha     — optional protogen source commit SHA; stamped as the
                    deterministic provenance of every manifest (:protogen-commit
                    and :generated-at), defaulting to \"unknown\" when absent"
  [{:keys [db-path config-path output-dir git-sha]
    :or {db-path "docs/.protodoc/proto-db.edn"
         config-path "docs/.protodoc/manifest-config.edn"
         output-dir "output/manifests"}}]
  (let [db (edn/read-string (slurp db-path))
        config (load-config config-path)
        ;; Provenance stamp — the protogen source commit these manifests were
        ;; generated from. Deliberately NOT a wall clock: a wall-clock stamp made
        ;; every regeneration byte-different on identical input, so a re-generated
        ;; manifest could never byte-match the committed copy (defeating any
        ;; freshness diff). A commit SHA moves only when the source actually
        ;; moves, so regeneration is idempotent for a fixed pin.
        provenance (or git-sha "unknown")

        ;; Extract
        endpoints-data (extract-endpoints db config)
        signals-data (extract-signals db config)
        sub-signals-data (extract-sub-signals db config)
        reverse-index-data (build-reverse-index endpoints-data signals-data)

        ;; Add metadata
        endpoints-manifest {:version "1.0.0"
                            :generated-at provenance
                            :protogen-commit provenance
                            :endpoints (:endpoints endpoints-data)
                            :subsystems (:subsystems endpoints-data)}

        signals-manifest {:version "1.0.0"
                          :generated-at provenance
                          :signals (:signals signals-data)
                          :subsystems (:subsystems signals-data)
                          :derived-signals (:derived-signals signals-data)}

        sub-signals-manifest {:version "1.0.0"
                              :generated-at provenance
                              :nested-signals (:nested-signals sub-signals-data)}

        reverse-index-manifest {:version "1.0.0"
                                :generated-at provenance
                                :by-doc-file (:by-doc-file reverse-index-data)
                                :by-endpoint (:by-endpoint reverse-index-data)
                                :by-signal (:by-signal reverse-index-data)}

        ;; Write
        out-dir (io/file output-dir)]
    (.mkdirs out-dir)

    (doseq [[filename data] [["endpoints.json" endpoints-manifest]
                             ["signals.json" signals-manifest]
                             ["sub-signals.json" sub-signals-manifest]
                             ["reverse-index.json" reverse-index-manifest]]]
      (let [f (io/file out-dir filename)]
        (spit f (json/write-str data :key-fn #(if (keyword? %)
                                                (cond-> (name %)
                                                  (namespace %)
                                                  (->> (str (namespace %) "/")))
                                                (str %))
                                :escape-slash false))
        (t/log! :info ["Wrote" (.getPath f)])))

    ;; Summary
    (t/log! :info ["Generated manifests:"
                   (count (:endpoints endpoints-data)) "endpoints,"
                   (count (:signals signals-data)) "signals,"
                   (count (:nested-signals sub-signals-data)) "sub-signal groups"])

    {:endpoints endpoints-manifest
     :signals signals-manifest
     :sub-signals sub-signals-manifest
     :reverse-index reverse-index-manifest}))
