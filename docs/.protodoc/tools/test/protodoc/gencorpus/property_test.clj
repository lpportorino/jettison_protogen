(ns protodoc.gencorpus.property-test
  "Generative properties over MANY messages — the wire-faithfulness contract the
   26 audited fixes promise, asserted across a representative leaf/whole-message
   set with seeded test.check generators (hermetic + deterministic).

   The four breadth properties (each a defspec, fixed :seed) walk a generated EDN
   value against its descriptor + the independently-reparsed wire:

   - ROUND-TRIP byte-identity — build → ->bin → reparse → ->bin is stable
     (encode/decode has no hidden state; pins the bare-:string bytes crash + the
     two's-complement integer path, bugs #1/#2/#12).
   - DECODED == EDN at wire width — every scalar leaf the EDN set equals the
     reparsed wire value at its wire width: float at float32 granularity (bug #3),
     uint64 compared as UNSIGNED (bug #1), enum by NUMBER with no silent field
     drop (bugs #5/#8), bytes byte-exact (bug #2). The generative heart.
   - FLOAT32-exactness — a generated float value already equals its own float32
     narrowing (bug #3: EDN == wire, not a wider double the wire truncates).
   - DETERMINISM — same seed ⇒ equal EDN ⇒ byte-identical wire (reproducibility).

   Plus targeted regressions: positive-corpus validity + non-emptiness, NO NaN/Inf
   in the positive corpus and in the unconstrained-float generator (bug #4), uint64
   never-negative + 2^64-1 reachable (bug #1), and bytes/UUID round-trip (bugs
   #2/#7).

   Curated message set — leaf scalars/enums (Gps, Compass, ObjectDetection, Time,
   CvChannelMeta), the bytes+UUID+nested OpaquePayload, the oneof container cmd.Root
   and the whole differential target ser.JonGUIState."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf
            ByteString
            Descriptors$Descriptor
            Descriptors$EnumValueDescriptor
            Descriptors$FieldDescriptor
            DynamicMessage]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (edn/read-string (slurp db-path))))

(def ^:private flt-max (double Float/MAX_VALUE))
(def ^:private uint64-max 18446744073709551615N)

;; ── message sets ──────────────────────────────────────────────────────

;; Round-trip / decoded==edn / determinism / float are ORACLE-INDEPENDENT, so the
;; whole curated set participates (incl. CvChannelMeta, which exercises uint64 +
;; repeated float + the fixed-N arrays).
(def ^:private structural-messages
  ["ser.JonGuiDataGps" "ser.JonGuiDataCompass" "ser.ObjectDetection"
   "ser.JonGuiDataTime" "ser.CvChannelMeta" "ser.JonOpaquePayload"
   "cmd.Root" "ser.JonGUIState"])

;; gen-corpus :positive must be VALID + non-empty. CvChannelMeta is EXCLUDED: the
;; live .proto requires sharpness_level3 min_items:160 while proto-db carries 64
;; (a documented proto-db↔proto drift, like the ser-side altitude phantom bounds)
;; — the oracle is the arbiter, so every generated CvChannelMeta is rejected and
;; its positive corpus is legitimately empty. Surfaced in bugs_found, not asserted.
(def ^:private positive-corpus-messages
  ["ser.JonGuiDataGps" "ser.JonGuiDataCompass" "ser.ObjectDetection"
   "ser.JonGuiDataTime" "ser.JonOpaquePayload" "cmd.Root" "ser.JonGUIState"])

(def ^:private message-gen (gen/elements structural-messages))
(def ^:private seed-gen (gen/choose 0 2000000))

;; ── EDN-value ↔ reparsed-wire leaf comparison (walks descriptor + EDN) ──

(defn- u->big
  "Reinterpret a signed Java long as the unsigned 64-bit BigInt the wire carries
   (protobuf-java hands back the signed long view of a uint64)."
  [^long l]
  (bigint (BigInteger. ^String (Long/toUnsignedString l))))

(defn- leaf-equal?
  "Does the EDN scalar/enum/bytes value `ev` equal the reparsed wire value `wv`
   at the field's WIRE width? float→float32 granularity, uint32/uint64→unsigned,
   enum→NUMBER, bytes→octet-exact."
  [type-kw ev wv]
  (case type-kw
    :double (= (double ev) (double wv))
    ;; ev is float32-quantized; (double wv) re-widens the stored Float losslessly.
    :float  (= (double ev) (double wv))
    :int32  (= (unchecked-int (long ev)) (int wv))
    :uint32 (= (long ev) (Integer/toUnsignedLong (int wv)))
    :int64  (= (long ev) (long wv))
    :uint64 (= (bigint ev) (u->big (long wv)))
    :bool   (= (boolean ev) (boolean wv))
    :string (= (str ev) (str wv))
    :enum   (= (int ev) (Descriptors$EnumValueDescriptor/.getNumber
                         ^Descriptors$EnumValueDescriptor wv))
    :bytes  (= (mapv #(bit-and (long %) 0xff) ev)
               (mapv #(bit-and (long %) 0xff)
                     (ByteString/.toByteArray ^ByteString wv)))))

(declare msg-mismatches)

(defn- value-mismatches
  "Mismatches comparing ONE singular element `ev` of field `f` against its
   reparsed wire value `wv` — recursing on message fields."
  [^Descriptors$FieldDescriptor f path ev wv]
  (let [type-kw (assemble/descriptor-type->kw f)]
    (if (= :message type-kw)
      (msg-mismatches (Descriptors$FieldDescriptor/.getMessageType f)
                      ev ^DynamicMessage wv path)
      (when-not (leaf-equal? type-kw ev wv)
        [{:path path :type type-kw :edn ev :wire wv}]))))

(defn- msg-mismatches
  "Every leaf the EDN map `edn` set that DISAGREES with the reparsed message
   `msg` (a seq of mismatch maps; empty ⇒ decoded == edn at wire width). Walks
   nested messages + repeated fields. Only fields the EDN set are compared (an
   absent field is the proto3 default, which `.getField` returns to match)."
  [^Descriptors$Descriptor d edn ^DynamicMessage msg path]
  (apply concat
         (for [[fname v] edn
               :let [f (Descriptors$Descriptor/.findFieldByName d (name fname))]
               :when f
               :let [p (str path "/" (name fname))
                     wire (DynamicMessage/.getField msg f)]]
           (if (Descriptors$FieldDescriptor/.isRepeated f)
             (if (not= (count v) (count wire))
               [{:path p :repeated-count-mismatch [(count v) (count wire)]}]
               (apply concat
                      (map (fn [ev wv] (value-mismatches f (str p "[]") ev wv))
                           v wire)))
             (value-mismatches f p v wire)))))

(defn- doubles-in
  "Every Double/Float leaf reachable in an EDN value (recursing maps + vectors).
   NaN/±Inf can only appear as a Double/Float, so this is the BUG-#4 probe."
  [x]
  (cond
    (map? x) (mapcat doubles-in (vals x))
    (sequential? x) (mapcat doubles-in x)
    (or (instance? Double x) (instance? Float x)) [x]
    :else nil))

;; ── 1. round-trip byte-identity ───────────────────────────────────────

(defspec round-trip-is-byte-identical
  {:num-tests 150 :seed 4242}
  (prop/for-all [m message-gen
                 seed seed-gen]
    (let [d (get @pool* m)
          edn (assemble/generate @pool* @db* m seed)]
      (:byte-identical? (gc/roundtrip d edn)))))

;; ── 2. decoded == edn at wire width (the generative heart) ─────────────

(defspec decoded-equals-edn-at-wire-width
  {:num-tests 150 :seed 808080}
  (prop/for-all [m message-gen
                 seed seed-gen]
    (let [d (get @pool* m)
          edn (assemble/generate @pool* @db* m seed)
          {:keys [reparsed]} (gc/roundtrip d edn)]
      (empty? (msg-mismatches d edn reparsed "")))))

(deftest decoded-equals-edn-grid
  (testing "decoded==edn over a fixed message×seed grid (readable diagnostics)"
    (doseq [m structural-messages
            seed (range 25)]
      (let [d (get @pool* m)
            edn (assemble/generate @pool* @db* m seed)
            {:keys [reparsed byte-identical?]} (gc/roundtrip d edn)
            mm (vec (msg-mismatches d edn reparsed ""))]
        (is byte-identical? (str m " seed " seed " is not byte-identical"))
        (is (empty? mm)
            (str m " seed " seed " decoded≠edn: " (pr-str (take 4 mm))))))))

;; ── 3. float32 exactness (BUG #3) ──────────────────────────────────────

(defspec float-leaves-are-float32-exact
  {:num-tests 120 :seed 31337}
  ;; a generated float value EQUALS its own float32 narrowing — so the corpus
  ;; EDN never carries a wider double than the 32-bit wire stores.
  (prop/for-all [m (gen/elements ["ser.ObjectDetection" "ser.CvChannelMeta"])
                 seed seed-gen]
    (let [edn (assemble/generate @pool* @db* m seed)]
      (every? (fn [^double x] (= x (double (float x))))
              (filter #(instance? Double %) (doubles-in edn))))))

;; ── 4. determinism: same seed ⇒ equal EDN ⇒ byte-identical wire ────────

(defspec same-seed-is-deterministic
  {:num-tests 100 :seed 99991}
  (prop/for-all [m message-gen
                 seed seed-gen]
    (let [d (get @pool* m)
          a (assemble/generate @pool* @db* m seed)
          b (assemble/generate @pool* @db* m seed)]
      (and (= a b)
           (let [^bytes wa (pool/->bin (pool/build-msg d a))
                 ^bytes wb (pool/->bin (pool/build-msg d b))]
             (java.util.Arrays/equals wa wb))))))

;; ── 5. positive corpus: valid + non-empty (independently re-validated) ─

(deftest positive-corpus-is-valid-and-nonempty
  (testing "gen-corpus :positive entries are non-empty and EACH independently
            re-validates against the oracle (the filter is honest)"
    (doseq [m positive-corpus-messages]
      (let [d (get @pool* m)
            {:keys [positive]} (gc/gen-corpus {:pool @pool* :db @db*
                                               :message m :seed 1 :count 12})]
        (is (seq positive) (str m " produced an EMPTY positive corpus"))
        (doseq [e positive]
          (is (= :valid (:verdict e))
              (str m " positive entry verdict is not :valid"))
          (is (oracle/valid? (pool/build-msg d (:edn-value e)))
              (str m " positive entry fails independent re-validation: "
                   (pr-str (:edn-value e)))))))))

;; ── 6. NO NaN/Inf in the positive corpus, NOR in the unconstrained-float
;;       generator (BUG #4) ───────────────────────────────────────────────

(deftest no-nan-or-inf-in-positive-corpus
  (testing "every float/double leaf in a positive-corpus entry is finite (bug #4)"
    (doseq [m positive-corpus-messages]
      (let [{:keys [positive]} (gc/gen-corpus {:pool @pool* :db @db*
                                               :message m :seed 1 :count 12})]
        (doseq [e positive
                ^double x (filter #(instance? Double %)
                                  (doubles-in (:edn-value e)))]
          (is (Double/isFinite x)
              (str m " positive corpus carries a non-finite float: " x)))))))

(defspec unconstrained-float-is-finite-and-bounded
  {:num-tests 120 :seed 24680}
  ;; CvChannelMeta sharpness_level1 is an UNCONSTRAINED repeated float — exactly
  ;; the field bug #4 fixed: malli's bare-:double generator emits NaN/±Inf, the
  ;; finite ±FLT_MAX bounding does not. A revert would surface a NaN/Inf here.
  (prop/for-all [seed seed-gen]
    (let [edn (assemble/generate @pool* @db* "ser.CvChannelMeta" seed)]
      (every? (fn [^double x] (and (Double/isFinite x)
                                   (<= (- flt-max) x flt-max)))
              (filter #(instance? Double %)
                      (concat (get edn "sharpness_level1")
                              (get edn "sharpness_level2")
                              (get edn "sharpness_level3")))))))

;; ── 7. uint64 never negative + 2^64-1 reachable + round-trips (BUG #1) ─

(deftest uint64-is-never-negative-and-reaches-max
  (let [m "ser.CvChannelMeta"
        d (get @pool* m)]
    (testing "generated uint64 (pts_ns) is never negative and stays in range"
      (doseq [seed (range 200)]
        (let [v (get (assemble/generate @pool* @db* m seed) "pts_ns")]
          (is (and (>= v 0) (<= v uint64-max))
              (str "pts_ns out of [0, 2^64-1] at seed " seed ": " v)))))
    (testing "2^64-1 is reachable via boundary injection (the endpoint a uniform
              draw never hits) and round-trips to the -1 two's-complement pattern"
      (let [boundaries (->> (assemble/boundary-corpus @pool* @db* m 1)
                            (filter #(= "pts_ns" (:field %)))
                            (map :boundary)
                            set)]
        (is (contains? boundaries uint64-max)
            "boundary corpus must reach uint64 max (2^64-1)"))
      (let [edn (assoc (assemble/generate @pool* @db* m 1) "pts_ns" uint64-max)
            {:keys [reparsed]} (gc/roundtrip d edn)
            f (Descriptors$Descriptor/.findFieldByName d "pts_ns")
            back (DynamicMessage/.getField reparsed f)]
        (is (= -1 back) "uint64 max stores as the -1 signed-long bit pattern")
        (is (= uint64-max (u->big (long back)))
            "and reads back as 2^64-1 unsigned")))))

;; ── 8. bytes + UUID round-trip (BUGs #2 / #7) ──────────────────────────

(defn- uuid-pattern
  "The buf.validate UUID :pattern proto-db declares for ser.JonOpaquePayload's
   type_uuid (looked up by field NAME, not position)."
  []
  (->> (get-in @db* [:messages "ser.JonOpaquePayload" :fields])
       (some #(when (= "type_uuid" (:name %)) (get-in % [:constraints :pattern])))
       re-pattern))

(deftest opaque-payload-bytes-and-uuid-round-trip
  (let [m "ser.JonOpaquePayload"
        d (get @pool* m)
        uuid-re (uuid-pattern)]
    (testing "arbitrary octets (NUL, 0xFF) survive byte-exact — never base64 (bug #2)"
      (let [octets [0 255 0 127 128 1 254]
            edn {"type_uuid" "00000000-0000-0000-0000-000000000000"
                 "payload" octets
                 "version" {}}
            {:keys [reparsed byte-identical?]} (gc/roundtrip d edn)
            f (Descriptors$Descriptor/.findFieldByName d "payload")
            ^ByteString back (DynamicMessage/.getField reparsed f)]
        (is byte-identical?)
        (is (= octets (mapv #(bit-and (long %) 0xff) (ByteString/.toByteArray back)))
            "the base64 path would have mangled these octets")))
    (testing "generated UUIDs match the :pattern and PASS the oracle (bug #7)"
      (doseq [seed (range 60)]
        (let [edn (assemble/generate @pool* @db* m seed)
              uuid (get edn "type_uuid")]
          (is (re-matches uuid-re uuid)
              (str "seed " seed " generated a non-UUID type_uuid: " uuid))
          (is (oracle/valid? (pool/build-msg d edn))
              (str "seed " seed " OpaquePayload is oracle-invalid: " (pr-str edn))))))))
