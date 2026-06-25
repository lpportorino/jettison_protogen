(ns protodoc.gencorpus.regression-test
  "Red→green regression pins for the audited bugs in the generative proto-corpus
   tool. Each deftest asserts the CORRECT post-fix behavior so it goes RED if the
   matching fix is reverted — covering bugs 1, 2, 3, 4, 6, 7, 8, 10, 16, 21, 22,
   25.

   Exercised against the LIVE committed descriptor pool (descriptor-set.binpb) +
   proto-db.edn, generatively over SEEDED inputs (hermetic + deterministic: no
   wall-clock, no network, every generator seeded). The protovalidate oracle runs
   off the live descriptor, so it — not proto-db — is the arbiter of validity."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool]
            [protodoc.manifest :as manifest])
  (:import [com.google.protobuf
            ByteString
            Descriptors$Descriptor
            Descriptors$EnumValueDescriptor
            Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$Type
            Descriptors$OneofDescriptor
            DynamicMessage]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (edn/read-string (slurp db-path))))

;; Proto fixed-width endpoints the fixes must reach / bound to.
(def ^:private flt-max (double Float/MAX_VALUE))
(def ^:private uint64-max 18446744073709551615N)
(def ^:private int32-min -2147483648)
(def ^:private int32-max 2147483647)
(def ^:private uint32-max 4294967295)

;; ── helpers ───────────────────────────────────────────────────────────

(defn- desc
  "The descriptor for `full-name` in the live pool."
  ^Descriptors$Descriptor [full-name]
  (get @pool* full-name))

(defn- fld
  "The FieldDescriptor `fname` of message `full-name`."
  ^Descriptors$FieldDescriptor [full-name fname]
  (Descriptors$Descriptor/.findFieldByName (desc full-name) fname))

(defn- find-typed-field
  "First SINGULAR field in the pool whose proto wire type is `ty-name` (a
   Descriptors$FieldDescriptor$Type enum name). Pool key-set iteration is
   deterministic for a fixed binpb; callers below depend only on the TYPE (the
   int/uint/float field-boundaries branches ignore the descriptor), so the choice
   of field is order-independent."
  ^Descriptors$FieldDescriptor [ty-name]
  (let [ty (Descriptors$FieldDescriptor$Type/valueOf ^String ty-name)]
    (first (for [[_ d] @pool*
                 ^Descriptors$FieldDescriptor f (Descriptors$Descriptor/.getFields d)
                 :when (and (= ty (Descriptors$FieldDescriptor/.getType f))
                            (not (Descriptors$FieldDescriptor/.isRepeated f)))]
             f))))

(defn- field-constraints
  "The proto-db :constraints map for field `fname` of `full-name`."
  [full-name fname]
  (->> (get-in @db* [:messages full-name :fields])
       (filter #(= fname (:name %)))
       first
       :constraints))

(defn- float-field-names
  "Names of the singular FLOAT fields of `full-name` (in descriptor order)."
  [full-name]
  (->> (Descriptors$Descriptor/.getFields (desc full-name))
       (filter #(= Descriptors$FieldDescriptor$Type/FLOAT
                   (Descriptors$FieldDescriptor/.getType ^Descriptors$FieldDescriptor %)))
       (mapv #(Descriptors$FieldDescriptor/.getName ^Descriptors$FieldDescriptor %))))

;; ── BUG 1 — uint64 is a BigInt marker, never a negative-prone bare :int ──

(deftest bug1-uint64-bigint-marker-and-full-range
  (testing "constraints->malli emits a [:uint64 {:min :max}] BigInt MARKER, not :int"
    (let [s (manifest/constraints->malli {:type :uint64 :constraints {:gte 0}} {})]
      (is (= [:uint64 {:min 0N :max uint64-max}] s)
          "uint64 → BigInt-bounded marker reaching 2^64-1 (a bare :int cannot hold the upper half)")
      (is (not= :int (first s)))))
  (testing "big-uint-gen over the full uint64 range is NEVER negative (the bug generated negatives)"
    (let [g (assemble/big-uint-gen 0N uint64-max)
          vals (mapv #(assemble/sample g %) (range 200))]
      (is (every? #(instance? clojure.lang.BigInt %) vals) "values are BigInt")
      (is (not-any? neg? vals) "no negative uint64 ever generated")
      (is (every? #(<= 0N % uint64-max) vals) "all within [0, 2^64-1]")))
  (testing "2^64-1 is producible AND serializes byte-exact through a real uint64 field"
    (let [d (desc "ser.CvChannelMeta")
          f (fld "ser.CvChannelMeta" "pts_ns")
          wire (pool/->bin (pool/build-msg d {"pts_ns" uint64-max}))
          ^DynamicMessage reparsed (pool/reparse d wire)
          back (.getField reparsed f)
          unsigned (BigInteger. (Long/toUnsignedString (.longValue ^Long back)))]
      (is (= -1 back) "uint64-max stored as the two's-complement -1 bit pattern (never thrown)")
      (is (= unsigned (biginteger uint64-max))
          "the unsigned wire view is exactly 2^64-1"))))

;; ── BUG 2 / 12 — bytes are octets, not a base64 string ──────────────────

(deftest bug2-bytes-octet-marker-and-byte-exact-roundtrip
  (testing "constraints->malli maps :bytes to a [:bytes ...] / :bytes marker, NEVER :string"
    (let [s0 (manifest/constraints->malli {:type :bytes} {})
          s1 (manifest/constraints->malli {:type :bytes :constraints {:min-len 1 :max-len 4}} {})]
      (is (= :bytes (if (vector? s0) (first s0) s0)) "bare bytes → :bytes marker")
      (is (= :bytes (first s1)) "constrained bytes → [:bytes {...}]")
      (is (not= :string (if (vector? s0) (first s0) s0))
          "NOT a string — the old bare-:string path base64-misencoded or crashed")))
  (testing "arbitrary octets (NUL / 0xFF) survive byte-exact through a real bytes field"
    (let [d (desc "ser.JonOpaquePayload")
          f (fld "ser.JonOpaquePayload" "payload")
          octets [0 255 16 0 127 128 1 254]
          wire (pool/->bin (pool/build-msg d {"payload" octets}))
          ^DynamicMessage reparsed (pool/reparse d wire)
          ^ByteString back (.getField reparsed f)]
      (is (= octets (mapv #(bit-and (long %) 0xff) (ByteString/.toByteArray back)))
          "every octet round-trips exactly, including NUL and 0xFF (base64 would mangle them)"))))

;; ── BUG 3 / 5 / 13 — float32 quantization: EDN == wire ──────────────────

(deftest bug3-float32-quantization-edn-equals-wire
  (testing "every generated float-field EDN value EQUALS its reparsed 32-bit wire value exactly"
    (let [full "ser.ObjectDetection"
          d (desc full)
          float-fields (->> (Descriptors$Descriptor/.getFields d)
                            (filter #(= Descriptors$FieldDescriptor$Type/FLOAT
                                        (Descriptors$FieldDescriptor/.getType ^Descriptors$FieldDescriptor %)))
                            vec)
          saw-fractional (atom false)]
      (is (seq float-fields) "ObjectDetection has float fields to exercise")
      (doseq [seed (range 30)]
        (let [edn (assemble/generate @pool* @db* full seed)
              wire (pool/->bin (pool/build-msg d edn))
              ^DynamicMessage reparsed (pool/reparse d wire)]
          (doseq [^Descriptors$FieldDescriptor f float-fields]
            (let [fname (Descriptors$FieldDescriptor/.getName f)
                  edn-v (double (get edn fname))
                  wire-v (double (.getField reparsed f))]
              (when (not= edn-v (Math/rint edn-v)) (reset! saw-fractional true))
              (is (= edn-v wire-v)
                  (str full "/" fname " seed " seed ": EDN " edn-v
                       " must equal wire " wire-v " (float32 granularity)"))))))
      (is @saw-fractional
          "non-vacuous: at least one generated float had a fractional part (a full-double bug would diverge)"))))

;; ── BUG 4 — float finite-bounded; no NaN/Inf in the positive corpus ─────

(deftest bug4-float-finite-bounded-no-nan-inf
  (testing "an unconstrained :float is finite-bounded to [-FLT_MAX, FLT_MAX]"
    (is (= [:float {:min (- flt-max) :max flt-max}]
           (manifest/constraints->malli {:type :float} {}))
        "no open-ended float (a value past FLT_MAX saturates to ±Inf on the wire)"))
  (testing "the positive corpus for a float-bearing message contains NO NaN / ±Inf"
    (let [full "ser.ObjectDetection"
          fnames (float-field-names full)]
      (is (seq fnames))
      (doseq [seed (range 50)]
        (let [edn (assemble/generate @pool* @db* full seed)]
          (doseq [fname fnames]
            (let [v (double (get edn fname))]
              (is (not (Double/isNaN v)) (str fname " seed " seed " is not NaN"))
              (is (not (Double/isInfinite v)) (str fname " seed " seed " is not ±Inf"))
              (is (<= -1.0 v 1.0) (str fname " seed " seed " stays within its declared bound")))))))))

;; ── BUG 6 — integers are TYPE-bounded (uint32 full range, int32 signed) ─

(deftest bug6-uint32-int32-type-bounded
  (testing "uint32 → [:int {:min 0 :max UINT32_MAX}], validating the FULL unsigned range"
    (let [schema (manifest/constraints->malli {:type :uint32} {})
          v (m/validator (m/schema schema))]
      (is (= [:int {:min 0 :max uint32-max}] schema))
      (is (v uint32-max) "UINT32_MAX (4294967295) is valid — not capped at int32-max")
      (is (not (v (inc uint32-max))) "4294967296 (past UINT32_MAX) is rejected")
      (is (not (v -1)) "negatives rejected (unsigned)")))
  (testing "int32 → bounded to the signed int32 range"
    (let [schema (manifest/constraints->malli {:type :int32} {})
          v (m/validator (m/schema schema))]
      (is (= [:int {:min int32-min :max int32-max}] schema))
      (is (v int32-max))
      (is (v int32-min))
      (is (not (v (inc int32-max))) "INT32_MAX+1 rejected")
      (is (not (v (dec int32-min))) "INT32_MIN-1 rejected"))))

;; ── BUG 7 / 9 — string :pattern → [:re]; UUIDs generate valid ───────────

(deftest bug7-string-pattern-uuid-generates-valid
  (let [full "ser.JonGuiDataTrackedObject"
        c (field-constraints full "uuid")
        pattern (:pattern c)
        re (re-pattern pattern)
        schema (manifest/constraints->malli {:type :string :constraints c} {})]
    (testing "a :pattern constraint becomes [:re pattern] (the bug left :pattern unhandled)"
      (is (some? pattern) "the uuid field carries a :pattern constraint")
      (is (= :re (first schema)) "pattern → [:re ...]")
      (is (= pattern (second schema)))
      (is (= [:re pattern] schema)))
    (testing "values from the :re schema match the UUID pattern; whole messages pass the oracle"
      (doseq [seed (range 10)]
        (let [s (mg/generate (m/schema schema) {:seed seed})]
          (is (re-matches re s) (str "seed " seed " generated a non-UUID: " s))))
      (let [d (desc full)
            valids (atom 0)]
        (doseq [seed (range 10)]
          (let [edn (assemble/generate @pool* @db* full seed)
                msg (pool/build-msg d edn)]
            (is (re-matches re (get edn "uuid"))
                (str "seed " seed " assembler produced a non-UUID uuid: " (get edn "uuid")))
            (when (oracle/valid? msg) (swap! valids inc))))
        (is (pos? @valids)
            "at least one generated TrackedObject passes the oracle (a random-ASCII uuid would fail string.pattern)")))))

;; ── BUG 8 — enums set by NUMBER; no silent field drop ───────────────────

(deftest bug8-enum-by-number-no-silent-field-drop
  (testing "an enum set by NUMBER + non-zero scalars all survive reparse (no silent drop)"
    (let [full "ser.JonGuiDataGps"
          d (desc full)
          enum-f (fld full "fix_type")
          edn {"fix_type" 1 "latitude" 51.5 "longitude" -0.5 "use_manual" true}
          wire (pool/->bin (pool/build-msg d edn))
          ^DynamicMessage reparsed (pool/reparse d wire)
          ^Descriptors$EnumValueDescriptor back (.getField reparsed enum-f)]
      (is (= 1 (Descriptors$EnumValueDescriptor/.getNumber back))
          "the enum field reparses to the NUMBER it was set to (set by number, not a droppable name)")
      (is (empty? (gc/presence-violations d edn reparsed))
          "no field set in the EDN is silently absent after reparse"))))

;; ── BUG 10 / 14 — the whole-message assembler (ser.JonGUIState) ─────────

(deftest bug10-jonguistate-assembles-and-serializes-whole
  (testing "ser.JonGUIState (previously had no assembler) generates + serializes WHOLE"
    (doseq [seed [0 7 42]]
      (let [d (desc "ser.JonGUIState")
            edn (assemble/generate @pool* @db* "ser.JonGUIState" seed)
            wire (pool/->bin (pool/build-msg d edn))]
        (is (map? edn))
        (is (pos? (alength ^bytes wire)) (str "seed " seed " serializes to non-empty wire"))
        (is (some? (pool/reparse d wire)) "reparses cleanly")
        (is (contains? edn "opaque_payloads") "the repeated message field is present")
        (is (vector? (get edn "opaque_payloads"))
            "a repeated message field is a VECTOR (inline-expanded, cycle-guarded)")))))

;; ── BUG 16 — deterministic boundary injection reaches TYPE endpoints ────

(deftest bug16-boundary-injection-reaches-type-endpoints
  (testing "int32 boundaries reach INT32_MIN and INT32_MAX (a uniform draw never hits them)"
    (let [bs (set (assemble/field-boundaries :int32 nil (find-typed-field "INT32")))]
      (is (contains? bs int32-min) "INT32_MIN reached")
      (is (contains? bs int32-max) "INT32_MAX reached")))
  (testing "uint32 boundaries reach 0 and UINT32_MAX"
    (let [bs (set (assemble/field-boundaries :uint32 nil (find-typed-field "UINT32")))]
      (is (contains? bs 0) "0 reached")
      (is (contains? bs uint32-max) "UINT32_MAX reached")))
  (testing "uint64 boundaries reach 2^64-1 (the upper-half endpoint)"
    (let [bs (set (assemble/field-boundaries :uint64 nil (find-typed-field "UINT64")))]
      (is (contains? bs uint64-max) "2^64-1 reached")))
  (testing "float boundaries include the zero edge and ±FLT_MAX"
    (let [bs (assemble/field-boundaries :float nil (find-typed-field "FLOAT"))]
      (is (some zero? bs) "0.0 reached")
      (is (some #(= flt-max (double %)) bs) "+FLT_MAX reached")
      (is (some #(= (- flt-max) (double %)) bs) "-FLT_MAX reached"))))

;; ── BUG 21 — a oneof sets EXACTLY ONE branch ────────────────────────────

(deftest bug21-oneof-at-most-one-branch
  (testing "cmd.Root sets AT MOST ONE payload-oneof branch (never MULTIPLE_PAYLOADS)"
    (let [d (desc "cmd.Root")
          oneof-names (->> (Descriptors$Descriptor/.getOneofs d)
                          (mapcat #(Descriptors$OneofDescriptor/.getFields ^Descriptors$OneofDescriptor %))
                          (map #(Descriptors$FieldDescriptor/.getName ^Descriptors$FieldDescriptor %))
                          set)
          exactly-one (atom 0)]
      (is (seq oneof-names) "cmd.Root has a payload oneof")
      (doseq [seed (range 16)]
        (let [edn (assemble/generate @pool* @db* "cmd.Root" seed)
              set-branches (filter oneof-names (keys edn))]
          (is (<= (count set-branches) 1)
              (str "seed " seed " set >1 oneof branch: " (vec set-branches)))
          (when (= 1 (count set-branches)) (swap! exactly-one inc))))
      (is (pos? @exactly-one)
          "non-vacuous: at least one seed actually populated the oneof"))))

;; ── BUG 22 — repeated honors fixed min/max items ────────────────────────

(deftest bug22-repeated-honors-fixed-min-max-items
  (testing "a fixed 4-item repeated field (CvChannelMeta/sharpness_level1) is a vector of EXACTLY 4"
    (doseq [seed (range 6)]
      (let [edn (assemble/generate @pool* @db* "ser.CvChannelMeta" seed)
            v (get edn "sharpness_level1")]
        (is (vector? v) (str "seed " seed " sharpness_level1 is a vector"))
        (is (= 4 (count v))
            (str "seed " seed " min_items=max_items=4 honored, got " (count v)))))))

;; ── BUG 25 — an unsatisfiable integer range fails LOUD ──────────────────

(deftest bug25-empty-int-range-fails-loud
  (testing "an unsatisfiable integer range THROWS ex-info instead of a malli crash / silent accept"
    (is (thrown? clojure.lang.ExceptionInfo
                 (manifest/constraints->malli {:type :int32 :constraints {:gt int32-max}} {}))
        "gt INT32_MAX yields an empty range → ex-info (never a clamped schema that accepts forbidden values)")
    (is (thrown? clojure.lang.ExceptionInfo
                 (manifest/constraints->malli {:type :int32 :constraints {:lt int32-min}} {}))
        "lt INT32_MIN is also empty → throws")))
