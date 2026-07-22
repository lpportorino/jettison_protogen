(ns protodoc.gencorpus.parity-test
  "Message-AGNOSTIC malli ⟺ buf.validate PARITY battery.

   This is the crown-jewel proof that `manifest/constraints->malli` (the FIXED
   transpiler) is FAITHFUL to buf.validate across the modelled constraint
   surface — derived structurally from the LIVE descriptor pool + proto-db, with
   NO compiled gencode and NO per-message literals. For every leaf-buildable,
   constraint-bearing leaf field across the whole pool it asserts three things:

   - W2 (rep agreement, per field, deterministic): a representative generated
     VALID value is accepted by BOTH the malli verdict-schema AND the protovalidate
     oracle on the built message — they agree VALID.
   - W3 (valid direction, defspec): values generated from each field's malli /
     leaf-gen, built into the message, PASS the oracle (and the malli schema).
   - W4 (invalid direction, complete sweep + defspec): a value perturbed one step
     past a modelled boundary is REJECTED by BOTH the malli verdict-schema AND the
     oracle. The complete sweep additionally pins two faithfulness invariants —
     malli NEVER under-models a constraint the oracle enforces, and the ONLY
     over-models (malli rejects / oracle accepts) are the documented proto-db
     altitude drift fields.

   `leaf-buildable` is derived structurally: a NON-router message (no `.Root`
   suffix, no `cmd` oneof) whose assembled base is oracle-VALID — so a single
   leaf-field override is a clean, isolated perturbation. (The assembler fills
   nested messages cycle-guarded, so 'no required nested message' is subsumed by
   base-validity, which also admits the UUID `:pattern` fields the battery must
   cover — bugs #7/#9.)

   The malli 'verdict' is a predicate over the marker schema: native types
   (`:int`/`:double`/`:float`/`:re`/`:string`/`:enum`/`:and`+`:fn`) validate via
   `m/validate`; the two gencorpus markers malli does not natively model —
   `[:uint64 ...]` (BigInt, malli :int is Long-backed — bug #1) and `[:bytes ...]`
   (octet-vector, NOT a byte-array — bug #2) — validate via a small predicate that
   reads the marker's bounds; enums map NUMBER→NAME against the modelled name set
   (bug #8). Generators are reused verbatim from `assemble/leaf-gen`.

   Hermetic + deterministic: reads only the committed descriptor-set.binpb +
   proto-db; every draw is seeded; no sleeps, no host/network deps."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool]
            [protodoc.manifest :as manifest])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$EnumDescriptor
            Descriptors$EnumValueDescriptor
            Descriptors$FieldDescriptor
            Descriptors$OneofDescriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (edn/read-string (slurp db-path))))
(def ^:private enums* (delay (:enums @db*)))

;; proto-type integer ceilings (mirror manifest.clj / assemble.clj — used only to
;; decide whether a one-step-past perturbation stays representable in the type).
(def ^:private int32-min -2147483648)
(def ^:private int32-max 2147483647)
(def ^:private uint32-max 4294967295)
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)
(def ^:private uint64-max 18446744073709551615N)

;; Documented proto-db OVER-MODEL drift: the ser-side altitude fields carry
;; proto-db bounds the live .proto lacks; the oracle (live descriptor) is the
;; arbiter, so a malli-reject / oracle-accept on these is reclassified as drift,
;; NOT a faithfulness failure. Any NEW field that drifts reds the W4 sweep.
(def ^:private drift-field-names #{"altitude" "manual_altitude"})

;; Conservative regression FLOORS — derived from the runtime census (350 usable
;; fields / 324 perturbable, see the coverage test), set well below current truth
;; so ordinary proto evolution does not false-red but a discovery/transpiler
;; collapse does. NOT a hardcoded external count — the live count is computed and
;; asserted >= these floors.
(def ^:private min-usable-fields 300)
(def ^:private min-perturbable-fields 280)
;; types that MUST stay covered (each currently has many fields; a whole-type
;; handling regression to zero would drop one of these from the census).
(def ^:private required-types
  #{:int32 :uint32 :int64 :uint64 :double :float :enum :bytes :string})

;; ── structural classification (descriptor-derived) ────────────────────

(defn- oneof-names [^Descriptors$Descriptor d]
  (set (map #(Descriptors$OneofDescriptor/.getName ^Descriptors$OneofDescriptor %)
            (Descriptors$Descriptor/.getOneofs d))))

(defn- router?
  "A routing container (cmd Root / group) — produces, never carries, leaf data."
  [full-name ^Descriptors$Descriptor d]
  (or (str/ends-with? full-name ".Root")
      (contains? (oneof-names d) "cmd")))

(defn- leaf-field?
  "A singular, non-oneof, non-map, non-message field — the only shape a single
   value override perturbs cleanly (oneof branches and repeated/message fields are
   out of the single-perturbation scope)."
  [^Descriptors$FieldDescriptor f]
  (and (not (Descriptors$FieldDescriptor/.isRepeated f))
       (not (Descriptors$FieldDescriptor/.isMapField f))
       (nil? (Descriptors$FieldDescriptor/.getRealContainingOneof f))
       (not (= :message (assemble/descriptor-type->kw f)))))

;; ── malli verdict (the predicate side of the parity) ──────────────────

(defn- enum-name-by-number
  "The proto enum constant NAME for `n` on field `f`, or nil when `n` is not a
   defined value of the enum (the open-enum passthrough)."
  [^Descriptors$FieldDescriptor f n]
  (let [^Descriptors$EnumDescriptor et (Descriptors$FieldDescriptor/.getEnumType f)]
    (when-let [^Descriptors$EnumValueDescriptor v
               (Descriptors$EnumDescriptor/.findValueByNumber et (int n))]
      (Descriptors$EnumValueDescriptor/.getName v))))

(defn- field-malli-data
  "The malli schema DATA `manifest/constraints->malli` emits for this field — the
   transpiler output under test. Enums need the type-ref + the proto-db enums map."
  [{ftype :type, :keys [constraints ^Descriptors$FieldDescriptor field-desc]}]
  (if (= :enum ftype)
    (manifest/constraints->malli
     {:type :enum :constraints constraints
      :type-ref (Descriptors$EnumDescriptor/.getFullName
                 (Descriptors$FieldDescriptor/.getEnumType field-desc))}
     @enums*)
    (manifest/constraints->malli {:type ftype :constraints constraints} @enums*)))

(defn- enum-allowed-names
  "The allowed enum constant NAMES carried by an `[:enum ...]` / `[:enum {props}
   ...]` schema."
  [md]
  (set (filter string? (if (map? (second md)) (drop 2 md) (rest md)))))

(defn- modelled?
  "Is this field inside the MODELLED constraint surface? Every type is, EXCEPT an
   enum whose type is absent from proto-db `:enums` (constraints->malli degrades to
   the documented `:string` fallback — there is no faithful enum schema to test)."
  [{ftype :type :as spec}]
  (if (= :enum ftype)
    (let [md (field-malli-data spec)] (and (vector? md) (= :enum (first md))))
    true))

(defn- verdict
  "The malli side of the parity: does the transpiler's schema ACCEPT value `v`?
   Native malli types validate directly; the `[:uint64]`/`[:bytes]` markers and
   enums (number→name) use the gencorpus interpretation the marker stands for.

   A :float value is normalized to its 32-bit WIRE value `(double (float v))`
   before validation. Parity is a claim about the float32 the message actually
   carries (bug #3), and malli's `:float` schema is type-strict (it rejects a Long)
   — so feeding the raw EDN would conflate a CONSTRAINT-faithfulness check with the
   `float32-gen` integer-bound type-leak that `float32-gen-emits-float-typed-edn`
   pins separately. Normalizing to the wire value keeps this battery focused on
   constraint faithfulness; it does NOT mask a bound disagreement (float32
   quantization is far smaller than any modelled bound step)."
  [{ftype :type :keys [^Descriptors$FieldDescriptor field-desc] :as spec} v]
  (let [md (field-malli-data spec)]
    (case ftype
      :enum (let [nm (enum-name-by-number field-desc v)]
              (boolean (and nm (contains? (enum-allowed-names md) nm))))
      :uint64 (let [{mn :min mx :max} (second md)]
                (boolean (and (integer? v)
                              (<= (or mn 0N) (bigint v))
                              (<= (bigint v) (or mx uint64-max)))))
      :bytes (let [{mn :min mx :max} (when (vector? md) (second md))]
               (boolean (and (vector? v)
                             (every? #(and (integer? %) (<= 0 % 255)) v)
                             (or (nil? mn) (>= (count v) mn))
                             (or (nil? mx) (<= (count v) mx)))))
      :float (m/validate (m/schema md) (double (float v)))
      (m/validate (m/schema md) v))))

(defn- value-gen
  "The field's VALID-value generator — reused verbatim from the tool."
  [{ftype :type, :keys [constraints ^Descriptors$FieldDescriptor field-desc]}]
  (assemble/leaf-gen ftype constraints field-desc @enums*))

;; ── invalid-direction perturbation (one step past a modelled boundary) ──

(defn- int-type-bounds [type-kw]
  (case type-kw :int32 [int32-min int32-max] :uint32 [0 uint32-max] :int64 [int64-min int64-max]))

(defn- perturb
  "A single value just OUTSIDE the field's modelled valid range, or nil when the
   field has no cleanly single-perturbable value-level boundary (e.g. an
   unconstrained uint64, a uint64/uint32 whose only bound is the unsigned floor
   gte:0, or a presence-only `:required` scalar). The perturbed integer is kept
   REPRESENTABLE in its proto type so it serializes — a boundary AT the type edge
   cannot be stepped past and is left unperturbed (nil)."
  [{ftype :type, :keys [constraints ^Descriptors$FieldDescriptor field-desc] :as spec}]
  (let [{:keys [gte gt lte lt min-len max-len pattern in defined-only not-in]} constraints]
    (case ftype
      (:int32 :uint32 :int64)
      (let [[tlo thi] (int-type-bounds ftype)]
        (cond lt (when (> lt tlo) lt)
              lte (when (< lte thi) (inc' lte))
              gt (when (< gt thi) gt)
              gte (when (> gte tlo) (dec' gte))
              :else nil))

      :uint64
      (cond lt (when (> (bigint lt) 0N) (bigint lt))
            lte (when (< (bigint lte) uint64-max) (inc (bigint lte)))
            gt (when (< (bigint gt) uint64-max) (bigint gt))
            gte (when (> (bigint gte) 0N) (dec (bigint gte)))
            :else nil)

      (:double :float)
      (cond lt (double lt) gt (double gt)
            lte (+ (double lte) 1.0) gte (- (double gte) 1.0)
            :else nil)

      :enum
      (let [defined (set (assemble/enum-numbers field-desc nil))]
        (cond (seq not-in) (first not-in)              ; an excluded number
              defined-only (inc (apply max 0 defined)) ; an UNDEFINED number
              :else nil))

      :bytes
      (cond (and min-len (pos? min-len)) []                       ; too short
            max-len (vec (repeat (inc max-len) 0))                ; too long
            :else nil)

      :string
      (when (or pattern (seq in) min-len max-len)
        ;; find the first candidate the transpiler's own schema rejects.
        (let [md (field-malli-data spec)
              cands (concat [""]
                            (when max-len [(apply str (repeat (inc max-len) "a"))])
                            [" " "!" "___not_a_modelled_value___" "x"])]
          (first (filter #(not (m/validate (m/schema md) %)) cands))))

      nil)))

;; ── field-spec discovery (the leaf-buildable, constraint-bearing surface) ──

(defn- collect-field-specs
  "Walk the live pool and proto-db; yield {:specs [...] :unmodelled [...]}. A spec
   is a constraint-bearing leaf field of a NON-router message whose assembled base
   (seed 1) is oracle-VALID — carrying everything the parity checks need."
  [pool db]
  (let [unmodelled (volatile! [])
        specs
        (vec (for [[full-name ^Descriptors$Descriptor d] (sort-by key pool)
                   :when (not (router? full-name d))
                   :let [cmap (assemble/db-field-constraints db full-name)
                         cand (filter #(and (leaf-field? %)
                                            (seq (cmap (Descriptors$FieldDescriptor/.getName %))))
                                      (Descriptors$Descriptor/.getFields d))]
                   :when (seq cand)
                   :let [base (assemble/generate pool db full-name 1)]
                   :when (oracle/valid? (pool/build-msg d base))
                   ^Descriptors$FieldDescriptor f cand
                   :let [fname (Descriptors$FieldDescriptor/.getName f)
                         spec {:msg full-name
                               :field fname
                               :type (assemble/descriptor-type->kw f)
                               :constraints (cmap fname)
                               :descriptor d
                               :field-desc f
                               :base base}]
                   :when (or (modelled? spec)
                             (do (vswap! unmodelled conj [full-name fname (:type spec)]) false))]
               spec))]
    {:specs specs :unmodelled @unmodelled}))

(def ^:private corpus* (delay (collect-field-specs @pool* @db*)))
(def ^:private field-specs* (delay (:specs @corpus*)))
(def ^:private perturbable-specs* (delay (vec (filter #(some? (perturb %)) @field-specs*))))

(defn- spec-label [{ftype :type, :keys [msg field]}] (str msg "/" field " (" (name ftype) ")"))

(defn- oracle-valid-with?
  "Build the message with field overridden to `v`, on the spec's valid base, and
   ask the oracle."
  [{:keys [^Descriptors$Descriptor descriptor field base]} v]
  (oracle/valid? (pool/build-msg descriptor (assoc base field v))))

;; ── W2 — representative-value agreement (per field, deterministic) ─────

(deftest w2-representative-value-agreement
  (testing "every leaf-buildable constraint-bearing field: a representative
            generated VALID value is accepted by BOTH the malli verdict-schema and
            the oracle (they agree VALID)"
    (let [specs @field-specs*
          malli-rejects (atom [])
          oracle-rejects (atom [])]
      (doseq [spec specs]
        (let [v (assemble/sample (value-gen spec) 7)]
          (cond
            (not (verdict spec v)) (swap! malli-rejects conj [(spec-label spec) v])
            (not (oracle-valid-with? spec v)) (swap! oracle-rejects conj [(spec-label spec) v]))))
      (is (>= (count specs) min-usable-fields)
          (str "regression floor: expected >= " min-usable-fields
               " leaf-buildable constraint-bearing fields, got " (count specs)))
      (is (empty? @malli-rejects)
          (str "leaf-gen produced a value its OWN malli schema rejects (generator↔schema "
               "inconsistency): " (vec (take 12 @malli-rejects))))
      (is (empty? @oracle-rejects)
          (str "malli-VALID value the oracle REJECTS (valid-direction parity gap): "
               (vec (take 12 @oracle-rejects)))))))

;; ── W3 — valid direction (generative, ~200 trials, fixed seed) ─────────

(defn- spec+value-gen
  "Pick a field spec, then a VALID value from its generator → [spec value]."
  []
  (gen/bind (gen/elements @field-specs*)
            (fn [spec] (gen/fmap (fn [v] [spec v]) (value-gen spec)))))

(defspec w3-valid-direction-passes-oracle
  {:num-tests 200 :seed 3735928559}
  (prop/for-all [pair (spec+value-gen)]
                (let [[spec v] pair]
      ;; both directions of the valid claim: the transpiler schema accepts the
      ;; value AND the oracle accepts the built message.
                  (and (verdict spec v)
                       (oracle-valid-with? spec v)))))

;; ── W4 — invalid direction (complete sweep + faithfulness invariants) ──

(deftest w4-invalid-direction-and-faithfulness
  (testing "every perturbable field: a value one step past a modelled boundary is
            REJECTED by BOTH the malli verdict-schema and the oracle — and malli
            NEVER under-models, while the only over-models are the documented
            proto-db altitude drift"
    (let [specs @field-specs*
          under-model (atom [])      ; malli ACCEPTS an out-of-range value (bug)
          over-model (atom [])       ; malli rejects, oracle accepts, NOT known drift
          drift (atom [])            ; malli rejects, oracle accepts, KNOWN drift
          both-reject (atom 0)
          no-perturb (atom 0)]
      (doseq [spec specs]
        (let [p (perturb spec)]
          (if (nil? p)
            (swap! no-perturb inc)
            (let [malli-rej (not (verdict spec p))
                  oracle-rej (not (oracle-valid-with? spec p))]
              (cond
                (not malli-rej) (swap! under-model conj [(spec-label spec) (:constraints spec) p])
                oracle-rej (swap! both-reject inc)
                (contains? drift-field-names (:field spec)) (swap! drift conj (spec-label spec))
                :else (swap! over-model conj [(spec-label spec) (:constraints spec) p]))))))
      (is (>= @both-reject min-perturbable-fields)
          (str "regression floor: expected >= " min-perturbable-fields
               " fields rejected past their boundary by BOTH, got " @both-reject))
      (is (empty? @under-model)
          (str "malli ACCEPTS an out-of-range value the oracle enforces "
               "(under-model — transpiler unfaithful): " (vec (take 12 @under-model))))
      (is (empty? @over-model)
          (str "malli REJECTS a value the oracle ACCEPTS and it is NOT a documented "
               "altitude-drift field (NEW over-model): " (vec (take 12 @over-model))))
      ;; the documented drift is expected to be present but bounded to altitude.
      (is (every? #(re-find #"/(altitude|manual_altitude) " %) @drift)
          (str "drift set must be the altitude fields only, got: " (vec @drift))))))

;; ── W4 — invalid direction (generative defspec over the perturbable set) ──

(defspec w4-invalid-direction-generative
  {:num-tests 200 :seed 195939070}
  (prop/for-all [spec (gen/elements @perturbable-specs*)]
                (let [p (perturb spec)]
      ;; the transpiler schema rejects the out-of-range value, and the oracle
      ;; agrees UNLESS this is the documented altitude over-model drift (oracle is
      ;; the live-truth arbiter there).
                  (and (not (verdict spec p))
                       (or (not (oracle-valid-with? spec p))
                           (contains? drift-field-names (:field spec)))))))

;; ── focused regression pins for named fixes ────────────────────────────

(deftest pattern-uuid-generates-valid-and-rejects-nonmatching
  (testing "a string :pattern field (UUID) generates a value that BOTH the [:re]
            schema and the oracle ACCEPT (bugs #7/#9), and a non-matching string is
            rejected by both"
    (let [uuid-specs (filter #(and (= :string (:type %)) (:pattern (:constraints %)))
                             @field-specs*)]
      (is (seq uuid-specs)
          "at least one :pattern (UUID) field must be exercised (pattern coverage)")
      (doseq [spec uuid-specs]
        (let [v (assemble/sample (value-gen spec) 7)]
          (is (verdict spec v) (str (spec-label spec) ": generated value matches [:re]"))
          (is (oracle-valid-with? spec v)
              (str (spec-label spec) ": oracle accepts the generated UUID"))
          (is (not (oracle-valid-with? spec ""))
              (str (spec-label spec) ": oracle rejects an empty (non-UUID) string")))))))

(deftest uint64-marker-and-bytes-marker-are-exercised
  (testing "the post-fix [:uint64] BigInt marker (bug #1) and [:bytes] octet marker
            (bug #2) are present in the parity surface and exercised in BOTH
            directions where perturbable"
    (let [by-type (group-by :type @field-specs*)]
      (is (seq (:uint64 by-type)) "uint64 fields are modelled + exercised")
      (is (seq (:bytes by-type)) "bytes fields are modelled + exercised")
      ;; a uint64 field with an upper bound accepts a value above Long/MAX_VALUE
      ;; that a Long-backed :int could not represent — the marker's whole point.
      (when-let [spec (first (filter #(and (= :uint64 (:type %))
                                           (let [{mx :max} (second (field-malli-data %))]
                                             (and mx (> mx int64-max))))
                                     (:uint64 by-type)))]
        (is (verdict spec (bigint int64-max))
            (str (spec-label spec) ": uint64 marker accepts a value past Long/MAX"))))))

;; ── regression pin for a bug THIS battery surfaced (red→green) ─────────

(deftest float32-gen-emits-float-typed-edn
  (testing "REGRESSION (found by the W3 parity defspec): leaf-gen for a :float
            field with an INTEGER buf.validate bound (e.g. :gte 0) must produce a
            FLOAT-typed EDN value — never an integer/Long.

            `assemble/float32-gen` clamps with `(-> (double (float v)) (max lo)
            (min hi))`, where lo/hi come from `float-bounds` and carry the integer
            constraint bound verbatim (`:gte 0` ⇒ lo = the Long 0). Clojure
            `(max 0.0 0)` returns the Long 0 and `(min 0 hi)` keeps it, so a float
            field gets a Long EDN value. That value (a) FAILS the field's own
            `[:float ...]` schema and (b) breaks bug #3 — it does not `=` the
            reparsed Float wire value (`(= 0 (float 0.0))` is false). The wire
            bytes are correct (build-msg re-coerces `(float v)`); the defect is the
            EDN value-type the corpus manifest records. Fix: coerce the clamp
            result back to double (`... (min hi) double`) or double lo/hi in
            float-bounds. RED until that lands."
    (let [float-specs (filter #(= :float (:type %)) @field-specs*)
          offenders (atom [])]
      (is (seq float-specs) "float fields are present to exercise")
      (doseq [spec float-specs
              seed (range 40)]
        (let [v (assemble/sample (value-gen spec) seed)]
          (when (integer? v)
            (swap! offenders conj [(spec-label spec) seed (pr-str v)]))))
      (is (empty? @offenders)
          (str (count (distinct (map first @offenders)))
               " float field(s) produced an INTEGER-typed EDN value "
               "(float32-gen integer-bound type-leak; bug #3 EDN==wire violated). "
               "Sample: " (vec (take 8 @offenders)))))))

;; ── coverage / floor tripwire (derived at runtime, never an external count) ──

(deftest coverage-and-floor-tripwire
  (testing "the parity surface stays broad: a conservative field floor, every
            modelled type present, the perturbable subset large, and the unmodelled
            exclusion small"
    (let [{:keys [specs unmodelled]} @corpus*
          types-present (set (map :type specs))
          perturbable (count @perturbable-specs*)]
      (is (>= (count specs) min-usable-fields)
          (str "usable field floor " min-usable-fields ", got " (count specs)))
      (is (>= (count (distinct (map :msg specs))) 40)
          (str "expected the surface to span many messages, got "
               (count (distinct (map :msg specs)))))
      (is (every? types-present required-types)
          (str "a modelled type dropped out of the census; required=" required-types
               " present=" types-present))
      (is (>= perturbable min-perturbable-fields)
          (str "perturbable floor " min-perturbable-fields ", got " perturbable))
      ;; the unmodelled exclusion (enums absent from proto-db) is a tiny, bounded
      ;; set — a spike here means a swath of fields silently fell out of scope.
      (is (<= (count unmodelled) 5)
          (str "too many fields fell OUTSIDE the modelled surface: " (vec unmodelled))))))
