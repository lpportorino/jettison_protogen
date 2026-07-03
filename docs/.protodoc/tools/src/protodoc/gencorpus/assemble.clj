(ns protodoc.gencorpus.assemble
  "The whole-message assembler (the piece that had ZERO callers — bug #14): walks
   a message DESCRIPTOR (the wire truth, joined with proto-db for constraints)
   and builds a test.check generator of EDN VALUE MAPS the pool can serialize.

   Walking the descriptor (not proto-db alone) sidesteps proto-db↔gencode drift:
   only fields the descriptor actually has are generated, and structure
   (repeated, message, oneof, type) comes from the descriptor while constraints
   come from proto-db.

   Per field:
   - leaf scalar/enum/bytes → a constraint-honoring generator (reusing
     manifest/constraints->malli for int/double/string; custom BigInteger /
     octet / float32-quantized / enum-by-number generators where malli alone is
     wrong — bugs #1/#2/#3/#7).
   - repeated → a length-bounded vector honoring min_items/max_items (bug #22),
     capped for the random corpus (boundary injection covers the extremes).
   - message → the child's generator inline-expanded, CYCLE-GUARDED (bug #10).
   - oneof → EXACTLY ONE branch via gen/one-of (never MULTIPLE_PAYLOADS — bug
     #21).

   Enums generate the integer NUMBER (set by number on the builder), never a
   NAME — a name-based path silently drops an unknown name (bug #8). Floats are
   quantized to float32 so the generated EDN equals the 32-bit wire value
   (bug #3). Generation is seed-deterministic (bug-free reproducibility)."
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            [malli.core :as m]
            [malli.generator :as mg]
            [protodoc.manifest :as manifest])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$EnumDescriptor
            Descriptors$EnumValueDescriptor
            Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType
            Descriptors$FieldDescriptor$Type
            Descriptors$OneofDescriptor]))

(set! *warn-on-reflection* true)

(def ^:dynamic *pins*
  "Oneof-branch PINS for constrained corpus generation: a map
   `{oneof-name #{allowed-field-name ...}}`. When `message-gen` builds a oneof
   group whose descriptor name is a key here, it restricts the generated branch
   to the allowed field set (instead of the default: any branch). Applied across
   the whole recursive generator tree, so nested oneofs are pinned together
   (e.g. `{\"payload\" #{\"heat_camera\"} \"cmd\" #{\"set_dde_level\"}}` pins BOTH
   `cmd.Root`'s payload AND the nested `cmd.HeatCamera.Root`'s cmd). Bound once at
   the `gen-corpus` entry (via `--pin`). nil ⇒ unconstrained (the default).

   This is how a sensor-class differential constrains `cmd.Root` to a module's
   handled command subset — a generic corpus would (a) mostly select payloads the
   module routes to a no-op and (b) hit the dispatch's `exit()` landmines on
   unmapped sub-commands. Pinning keeps the boundary-injected generation but scopes
   the oneof selection to the mapped set."
  nil)

(def ^:private float-max (double Float/MAX_VALUE))

;; Cap the random corpus's repeated count / byte length so a max_items 256 or a
;; max_len 65536 field does not blow up corpus size — boundary injection covers
;; the declared extremes deterministically and separately.
(def ^:private repeated-cap 4)
(def ^:private bytes-cap 32)

;; ── descriptor proto type → proto-db type keyword ─────────────────────

(defn descriptor-type->kw
  "Map a FieldDescriptor's proto wire type to the proto-db :type keyword the
   constraint transpiler speaks. fixed/sfixed/sint fold into their signedness."
  [^Descriptors$FieldDescriptor f]
  (condp = (Descriptors$FieldDescriptor/.getType f)
    Descriptors$FieldDescriptor$Type/DOUBLE :double
    Descriptors$FieldDescriptor$Type/FLOAT :float
    Descriptors$FieldDescriptor$Type/INT32 :int32
    Descriptors$FieldDescriptor$Type/SINT32 :int32
    Descriptors$FieldDescriptor$Type/SFIXED32 :int32
    Descriptors$FieldDescriptor$Type/UINT32 :uint32
    Descriptors$FieldDescriptor$Type/FIXED32 :uint32
    Descriptors$FieldDescriptor$Type/INT64 :int64
    Descriptors$FieldDescriptor$Type/SINT64 :int64
    Descriptors$FieldDescriptor$Type/SFIXED64 :int64
    Descriptors$FieldDescriptor$Type/UINT64 :uint64
    Descriptors$FieldDescriptor$Type/FIXED64 :uint64
    Descriptors$FieldDescriptor$Type/BOOL :bool
    Descriptors$FieldDescriptor$Type/STRING :string
    Descriptors$FieldDescriptor$Type/BYTES :bytes
    Descriptors$FieldDescriptor$Type/ENUM :enum
    Descriptors$FieldDescriptor$Type/MESSAGE :message
    Descriptors$FieldDescriptor$Type/GROUP :message))

;; ── proto-db constraint join ──────────────────────────────────────────

(defn db-field-constraints
  "{field-name → constraints-map} for a message, from proto-db (the constraint
   source). A field absent from proto-db (descriptor drift) → no constraints."
  [db full-name]
  (into {} (for [f (get-in db [:messages full-name :fields])]
             [(:name f) (:constraints f)])))

;; ── custom leaf generators (where malli alone is wrong) ───────────────

(defn big-uint-gen
  "≈uniform BigInt generator over [lo hi] (inclusive, both BigInt). Assembles a
   bit vector of the span's width then folds into [lo hi] — covers the uint64
   upper half malli :int cannot represent."
  [lo hi]
  (let [span (inc (- hi lo))
        nbits (max 1 (.bitLength (biginteger span)))]
    (gen/fmap (fn [bits]
                (let [n (reduce (fn [acc b] (+ (* acc 2N) (if b 1N 0N))) 0N bits)]
                  (+ lo (mod n span))))
              (gen/vector gen/boolean nbits))))

(defn- uint64-bounds
  "[lo hi] BigInt for a uint64 field, reusing the transpiler's bound logic."
  [constraints]
  (let [s (manifest/constraints->malli {:type :uint64 :constraints constraints} {})
        {:keys [min max]} (second s)]
    [min max]))

(defn- float-bounds
  "[lo hi] as DOUBLES for a float field — the finite envelope, honoring
   gte/gt/lte/lt and clamped to ±FLT_MAX. Bounds are coerced to double so a
   downstream (max v lo)/(min v hi) cannot leak an integer-typed bound (a Long
   :gte 0 must not turn a float draw back into a Long)."
  [constraints]
  (let [{:keys [gte gt lte lt]} constraints
        lo (cond gte (double gte) gt (double gt) :else (- float-max))
        hi (cond lte (double lte) lt (double lt) :else float-max)]
    [(max (double lo) (- float-max)) (min (double hi) float-max)]))

(defn- float32-gen
  "Generate a double in the float field's range, then quantize to float32 and
   clamp with DOUBLE bounds so the EDN value is a DOUBLE (never an integer-typed
   leak from a Long bound) that equals the 32-bit wire value for the live
   float32-exact bounds. (Latent edge: a non-float32-exact inclusive bound could
   let the quantized value land one ULP outside; no live float field has such a
   bound, and the oracle post-filter drops any such entry.)"
  [constraints]
  (let [[lo hi] (float-bounds constraints)]
    (gen/fmap (fn [v] (-> (double (float v)) (max lo) (min hi)))
              (mg/generator (m/schema [:double {:min lo :max hi}])))))

(declare enum-numbers)

;; ── required-presence + zero-default handling ─────────────────────────
;;
;; A proto3 scalar with NO wire presence (`.hasPresence` false) that is set to
;; its zero default is OMITTED on the wire — so a buf.validate `required` field
;; generated as 0/false/""/[]/enum-0 serializes ABSENT and the oracle rejects it
;; (`value is required`). For such fields the generator must yield a PRESENT
;; (non-zero-default) value.

(defn- zero-default?
  "Is `v` the proto3 zero default for `type-kw` (serializes as an absent field)?
   NB protobuf treats -0.0 as PRESENT (raw-bits ≠ 0), so only +0.0 is a default."
  [type-kw v]
  (case type-kw
    (:double :float) (and (number? v) (zero? (Double/doubleToRawLongBits (double v))))
    (:int32 :uint32 :int64 :uint64 :enum) (and (number? v) (zero? v))
    :bool (false? v)
    :string (= "" v)
    :bytes (or (nil? v) (and (coll? v) (empty? v)))
    false))

(defn- non-default-gen
  "Wrap a leaf generator so a required no-presence field never yields its zero
   default (which would serialize ABSENT): bool → true, enum → a non-zero number,
   string → non-empty, bytes → ≥1 octet, numeric → a non-zero in-range draw."
  [type-kw ^Descriptors$FieldDescriptor f constraints g]
  (case type-kw
    :bool (gen/return true)
    :enum (gen/elements (or (seq (remove zero? (enum-numbers f constraints)))
                            (enum-numbers f constraints)))
    :string (gen/fmap (fn [s] (if (= "" s) "x" s)) g)
    :bytes (gen/fmap (fn [v] (if (seq v) v [1])) g)
    (gen/such-that #(not (zero-default? type-kw %)) g 100)))

(defn- distinct-doubles
  "Order-preserving distinct over doubles by RAW BIT PATTERN, so +0.0 and -0.0
   (distinct float wire values) both survive — Clojure `=`/`distinct` collapse
   them."
  [xs]
  (let [seen (volatile! #{})]
    (vec (for [x xs
               :let [b (Double/doubleToRawLongBits (double x))]
               :when (not (contains? @seen b))]
           (do (vswap! seen conj b) x)))))

(defn- octet-gen
  "Generate a vector of octets [0..255] for a bytes field, length honoring
   min_len and (capped) max_len."
  [constraints]
  (let [{:keys [min-len max-len]} constraints
        lo (or min-len 0)
        hi (max lo (min (or max-len 16) bytes-cap))]
    (gen/vector (gen/choose 0 255) lo hi)))

(defn enum-numbers
  "The defined enum NUMBERS for an enum field, minus any :not-in sentinel; falls
   back to all defined numbers if exclusion empties the set."
  [^Descriptors$FieldDescriptor f constraints]
  (let [^Descriptors$EnumDescriptor et (Descriptors$FieldDescriptor/.getEnumType f)
        all (mapv #(Descriptors$EnumValueDescriptor/.getNumber ^Descriptors$EnumValueDescriptor %)
                  (Descriptors$EnumDescriptor/.getValues et))
        not-in (set (:not-in constraints))
        allowed (remove not-in all)]
    (vec (or (seq allowed) all))))

(defn- enum-number-gen
  [^Descriptors$FieldDescriptor f constraints]
  (gen/elements (enum-numbers f constraints)))

(defn leaf-gen
  "test.check generator for a singular scalar/enum/bytes field value. Reuses
   manifest/constraints->malli for the types malli generates faithfully
   (int32/uint32/int64/double/bool/string/:re); custom generators for uint64
   (BigInt), bytes (octets), enum (number) and float (float32-quantized)."
  [type-kw constraints ^Descriptors$FieldDescriptor f enums]
  (case type-kw
    :uint64 (let [[lo hi] (uint64-bounds constraints)] (big-uint-gen lo hi))
    :bytes (octet-gen constraints)
    :enum (enum-number-gen f constraints)
    :float (float32-gen constraints)
    (mg/generator (m/schema (manifest/constraints->malli
                             {:type type-kw :constraints constraints} enums)))))

;; ── repeated count bounds ─────────────────────────────────────────────

(defn- repeated-bounds
  "[lo hi] element count for a repeated field — honoring min_items exactly
   (fixed-N arrays) and capping max_items for the random corpus."
  [constraints]
  (let [{:keys [min-items max-items]} constraints
        lo (or min-items 0)
        hi (if max-items
             (min max-items (max lo repeated-cap))
             (max lo repeated-cap))]
    [lo hi]))

;; ── message + field generators ────────────────────────────────────────

(declare message-gen)

(defn- field-entry-gen
  "Generator yielding a {field-name value} singleton for ONE field (or {} when a
   message field would recurse into a type already being expanded — the cycle
   guard). `seen` holds the message full-names on the current expansion path."
  [pool db ^Descriptors$FieldDescriptor f constraints enums seen]
  (let [fname (Descriptors$FieldDescriptor/.getName f)
        repeated? (Descriptors$FieldDescriptor/.isRepeated f)
        type-kw (descriptor-type->kw f)]
    (cond
      ;; proto map field — leave empty (a valid absent map); not a corpus target.
      (Descriptors$FieldDescriptor/.isMapField f)
      (gen/return {})

      (= :message type-kw)
      (let [child-name (Descriptors$Descriptor/.getFullName
                        (Descriptors$FieldDescriptor/.getMessageType f))]
        (if (contains? seen child-name)
          (gen/return {})                                   ; cycle guard: omit
          (let [child (message-gen pool db child-name seen)]
            (if repeated?
              (let [[lo hi] (repeated-bounds constraints)]
                (gen/fmap (fn [vs] {fname vs}) (gen/vector child lo hi)))
              (gen/fmap (fn [v] {fname v}) child)))))

      repeated?
      (let [[lo hi] (repeated-bounds constraints)
            ;; a required repeated field needs >=1 element to be present
            lo (if (and (:required constraints) (zero? lo)) 1 lo)]
        (gen/fmap (fn [vs] {fname vs})
                  (gen/vector (leaf-gen type-kw constraints f enums) lo (max lo hi))))

      :else
      (let [g (leaf-gen type-kw constraints f enums)
            ;; a required no-presence scalar MUST serialize present (non-default)
            g (if (and (:required constraints)
                       (not (Descriptors$FieldDescriptor/.hasPresence f)))
                (non-default-gen type-kw f constraints g)
                g)]
        (gen/fmap (fn [v] {fname v}) g)))))

(defn- pin-filter
  "Restrict oneof group `grp` (field descriptors sharing oneof `oneof-name`) to
   the branches `*pins*` allows; identity when the oneof is unpinned. Fail-loud
   when a pin names this oneof but selects NO available branch (a typo guard — a
   silently-empty group would drop the required oneof and emit an unset payload)."
  [oneof-name grp]
  (if-let [allowed (get *pins* oneof-name)]
    (let [kept (filterv #(contains? allowed (Descriptors$FieldDescriptor/.getName %)) grp)]
      (when (empty? kept)
        (throw (ex-info "gen-corpus --pin selects no available oneof branch"
                        {:oneof oneof-name
                         :allowed allowed
                         :available (mapv #(Descriptors$FieldDescriptor/.getName %) grp)})))
      kept)
    grp))

(defn message-gen
  "test.check generator of an EDN value map for `full-name`. Non-oneof fields are
   always present; each real oneof contributes EXACTLY ONE branch (gen/one-of),
   restricted by `*pins*` when the oneof is pinned. `seen` guards message
   recursion."
  [pool db full-name seen]
  (let [^Descriptors$Descriptor d (get pool full-name)]
    (when-not d
      (throw (ex-info "message not in descriptor pool" {:message full-name})))
    (let [seen' (conj seen full-name)
          constraints (db-field-constraints db full-name)
          enums (:enums db)
          fields (Descriptors$Descriptor/.getFields d)
          oneof? (fn [^Descriptors$FieldDescriptor f]
                   (some? (Descriptors$FieldDescriptor/.getRealContainingOneof f)))
          plain (remove oneof? fields)
          oneof-groups (group-by #(Descriptors$OneofDescriptor/.getName
                                    (Descriptors$FieldDescriptor/.getRealContainingOneof %))
                                  (filter oneof? fields))
          plain-gens (map #(field-entry-gen pool db % (constraints (Descriptors$FieldDescriptor/.getName %)) enums seen')
                          plain)
          oneof-gens (map (fn [[oneof-name grp]]
                            (gen/one-of
                             (mapv #(field-entry-gen pool db % (constraints (Descriptors$FieldDescriptor/.getName %)) enums seen')
                                   (pin-filter oneof-name grp))))
                          oneof-groups)
          all (concat plain-gens oneof-gens)]
      (if (seq all)
        (gen/fmap #(apply merge {} %) (apply gen/tuple all))
        (gen/return {})))))

;; ── seed-deterministic sampling ───────────────────────────────────────

(defn sample
  "Draw ONE deterministic value from generator `g` for `seed` (and `size`). Uses
   the low-level call-gen so the seed→value mapping is stable across runs and
   machines (the determinism property)."
  ([g seed] (sample g seed 30))
  ([g seed size]
   (rose/root (gen/call-gen g (random/make-random seed) size))))

(defn generate
  "Generate ONE deterministic EDN value map for message `full-name` at `seed`."
  [pool db full-name seed]
  (sample (message-gen pool db full-name #{}) seed))

;; ── deterministic boundary injection (TYPE-level + domain example seeds) ──
;;
;; The decoupling boundary (gen-corpus contract): the TOOL injects TYPE-level
;; boundaries (the integer/float/bytes endpoints uniform random draw never hits)
;; and the proto-db :example domain seeds (project data, flowing through
;; --db-path). DOMAIN-specific manifold boundaries (microdeg wrap 180e6/360e6,
;; %36000) are the CONSUMER's inputs — they live jettison-side, NOT hardcoded
;; from a jettison C header here.

(def ^:private int32-min -2147483648)
(def ^:private int32-max 2147483647)
(def ^:private uint32-max 4294967295)
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)
(def ^:private uint64-max 18446744073709551615N)
;; smallest positive float32 subnormal (~1.4e-45) — a float32-distinct edge.
(def ^:private flt-subnormal (double Float/MIN_VALUE))

(defn- int-type-bounds [type-kw]
  (case type-kw
    :int32 [int32-min int32-max]
    :uint32 [0 uint32-max]
    :int64 [int64-min int64-max]))

(defn- examples
  "proto-db :example seed values for a field (the domain boundary source)."
  [constraints]
  (filter number? (:example constraints)))

(defn field-boundaries
  "In-range boundary VALUES for a leaf field: type endpoints + constraint
   endpoints + proto-db example seeds, clamped to the field's valid range.
   nil for message/map fields (boundary injection targets leaf scalars)."
  [type-kw constraints ^Descriptors$FieldDescriptor f]
  (letfn [(in [lo hi vs] (->> vs distinct (filter #(and (some? %) (<= lo % hi))) vec))]
    (let [bs (case type-kw
      (:int32 :uint32 :int64)
      (let [[tlo thi] (int-type-bounds type-kw)
            {:keys [gte gt lte lt]} constraints
            lo (cond gte gte gt (inc' gt) :else tlo)
            hi (cond lte lte lt (dec' lt) :else thi)
            lo (max lo tlo) hi (min hi thi)]
        (in lo hi (concat [tlo -1 0 1 thi lo hi 2147483647 2147483648] (examples constraints))))

      :uint64
      (let [[lo hi] (uint64-bounds constraints)]
        (in lo hi (concat [0N 1N 2147483647N 2147483648N 4294967295N 4294967296N
                           9007199254740992N 9223372036854775807N 9223372036854775808N uint64-max
                           lo hi]
                          (map bigint (examples constraints)))))

      :float
      ;; float32-quantize every candidate, then keep only those that satisfy the
      ;; ACTUAL constraint (so an exclusive bound never emits the bound itself).
      (let [{:keys [gte gt lte lt]} constraints
            q #(double (float %))
            lo (if gte (double gte) (- float-max))
            hi (if lte (double lte) float-max)
            cands (map q (concat [0.0 -0.0 lo hi flt-subnormal (- flt-subnormal)]
                                 (map double (examples constraints))))]
        (->> cands distinct-doubles
             (filter #(and (or (nil? gte) (>= % (double gte))) (or (nil? gt) (> % (double gt)))
                           (or (nil? lte) (<= % (double lte))) (or (nil? lt) (< % (double lt)))
                           (<= (- float-max) % float-max)))
             vec))

      :double
      ;; exclusive bounds: the in-range extreme is one ULP inside (nextUp/nextDown),
      ;; NOT the bound itself (which the oracle rejects). Filter by the real predicate.
      (let [{:keys [gte gt lte lt]} constraints
            lo (cond gte (double gte) gt (Math/nextUp (double gt)) :else nil)
            hi (cond lte (double lte) lt (Math/nextDown (double lt)) :else nil)
            vs (concat [0.0 -0.0] (keep identity [lo hi]) (map double (examples constraints)))]
        (->> vs distinct-doubles
             (filter #(and (some? %)
                           (or (nil? gte) (>= % (double gte))) (or (nil? gt) (> % (double gt)))
                           (or (nil? lte) (<= % (double lte))) (or (nil? lt) (< % (double lt)))))
             vec))

      :bool [true false]
      :enum (enum-numbers f constraints)
      :string (let [{:keys [min-len max-len pattern in]} constraints]
                (cond
                  ;; the allowed set IS the boundary set (also covers bug #7 :in)
                  (seq in) (vec in)
                  ;; a regex has no clean length endpoints; the :re generator (in
                  ;; the random corpus) covers it — emitting "" or padding fails it.
                  pattern []
                  :else (distinct (filter some?
                                          [(when-not (and min-len (pos? min-len)) "")
                                           (when min-len (apply str (repeat min-len "a")))
                                           (when max-len (apply str (repeat max-len "a")))]))))
      :bytes (let [{:keys [min-len max-len]} constraints]
               (distinct (filter some?
                                 [(when-not (and min-len (pos? min-len)) [])
                                  (when min-len (vec (repeat min-len 0)))
                                  (vec (repeat (or max-len min-len 1) 255))])))
      nil)]
      ;; a required no-presence field's zero-default boundary serializes ABSENT
      ;; (→ oracle "required" reject); drop it from the POSITIVE boundary set.
      (if (and (:required constraints) f
               (not (Descriptors$FieldDescriptor/.hasPresence f)))
        (vec (remove #(zero-default? type-kw %) bs))
        bs))))

(defn boundary-corpus
  "Deterministic boundary entries for `full-name`: a base generation with each
   top-level LEAF field overridden, in turn, to each of its boundary values.
   Returns a seq of {:edn-value :field :boundary} — the type/constraint endpoints
   uniform random draw never reaches."
  [pool db full-name base-seed]
  (let [^Descriptors$Descriptor d (get pool full-name)
        constraints (db-field-constraints db full-name)
        base (generate pool db full-name base-seed)]
    (for [^Descriptors$FieldDescriptor f (Descriptors$Descriptor/.getFields d)
          :let [fname (Descriptors$FieldDescriptor/.getName f)
                type-kw (descriptor-type->kw f)]
          :when (and (not (= :message type-kw))
                     (not (Descriptors$FieldDescriptor/.isRepeated f))
                     (not (Descriptors$FieldDescriptor/.isMapField f)))
          bv (field-boundaries type-kw (constraints fname) f)]
      {:edn-value (assoc base fname bv) :field fname :boundary bv})))
