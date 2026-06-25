(ns protodoc.gencorpus.oracle-test
  "Test layer 3 — the protovalidate oracle cross-check + violating-corpus
   correctness, using the LIVE descriptor (the protovalidate engine reading the
   committed descriptor-set.binpb) as ground truth.

   The oracle is the arbiter, NOT proto-db: a candidate the live oracle ACCEPTS
   is not a violation but proto-db↔gencode DRIFT (the ser-side altitude fields
   carry phantom proto-db bounds the live .proto lacks). These tests pin:
   - a directly-constructed out-of-range value is rejected (the [-90,90] latitude
     bound lives in the LIVE descriptor — verified, not assumed);
   - an UNDEFINED enum NUMBER on a defined_only enum is rejected (bug #8 — enums
     set by number, so an out-of-set number reaches the oracle instead of being
     silently dropped);
   - the violating sub-corpus is non-empty for constrained messages and EVERY
     entry is independently oracle-REJECTED;
   - the positive corpus is independently oracle-VALID;
   - the drift sub-corpus is correctly separated (oracle-VALID, altitude-only);
   - gen-corpus :boundary-failures is EMPTY — a valid boundary the oracle rejects
     is a real faithfulness bug, surfaced not papered over."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$FieldDescriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (edn/read-string (slurp db-path))))

;; A fixed seed/count keeps every gen-corpus call deterministic + hermetic.
(def ^:private seed 1)
(def ^:private cnt 16)

;; Leaf state/command messages whose proto-db carries ONLY inclusive bounds
;; (gte/lte) — their type/constraint boundaries are all in-range, so a faithful
;; tool yields ZERO boundary-failures.
(def ^:private inclusive-bound-msgs
  ["ser.JonGuiDataGps" "ser.ObjectDetection" "cmd.DayCamera.SetIris"])

;; Leaf messages whose proto-db carries an EXCLUSIVE upper bound (:lt) on a
;; double field (azimuth lt 360; bank/offsetAzimuth/magneticDeclination lt 180;
;; longitude lt 180) — the LIVE oracle enforces these (double.gte_lt).
(def ^:private exclusive-bound-msgs
  ["ser.JonGuiDataCompass" "cmd.Gps.SetManualPosition"])

(defn- corpus [full-name]
  (gc/gen-corpus {:pool @pool* :db @db* :message full-name :seed seed :count cnt}))

(defn- rebuild
  "Independently rebuild a corpus entry's :edn-value into a Message for an
   oracle re-check (does not trust gen-corpus's own :verdict)."
  [full-name edn-value]
  (pool/build-msg (get @pool* full-name) edn-value))

;; ── sanity ─────────────────────────────────────────────────────────────

(deftest pool-and-oracle-sanity
  (testing "the messages under test resolve from the binpb and the oracle builds"
    (doseq [m (concat inclusive-bound-msgs exclusive-bound-msgs)]
      (is (get @pool* m) (str m " resolves in the descriptor pool")))
    ;; the validator builds + answers (a default ser.JonGuiDataGps is rejected:
    ;; fix_type defaults to 0 which is not_in [0]) — proves the oracle is live.
    (is (boolean? (oracle/valid? (pool/build-msg (get @pool* "ser.JonGuiDataGps") {}))))))

;; ── directly-constructed violation (LIVE bound, not proto-db) ────────────

(deftest constrained-field-violation-rejected
  (testing "ser.JonGuiDataGps latitude=200 is rejected by the LIVE oracle"
    (let [d (get @pool* "ser.JonGuiDataGps")
          msg (pool/build-msg d {"latitude" 200.0})
          vios (oracle/violations msg)]
      (is (false? (oracle/valid? msg))
          "latitude=200 violates the live [-90,90] double bound")
      (is (seq vios) "violations are non-empty")
      (is (some #(re-find #"latitude" %) vios)
          (str "a violation references the latitude field; got " (vec vios))))))

;; ── enum-by-number faithfulness (bug #8) ─────────────────────────────────

(deftest enum-number-faithfulness
  (testing "enums are set by NUMBER: a defined number passes, an undefined one is rejected"
    (let [d (get @pool* "ser.JonGuiDataGps")
          f ^Descriptors$FieldDescriptor (Descriptors$Descriptor/.findFieldByName d "fix_type")
          defined (assemble/enum-numbers f nil)
          good (first (remove zero? defined))          ; defined, non-zero (not_in [0])
          undefined (inc (apply max defined))]         ; one past the top → undefined
      (is (some? good) "fix_type has a defined non-zero value to set")
      (is (false? (oracle/valid? (pool/build-msg d {"fix_type" undefined})))
          (str "an UNDEFINED enum number (" undefined ") is rejected (defined_only) "
               "— it reached the oracle instead of being silently dropped"))
      (is (seq (oracle/violations (pool/build-msg d {"fix_type" undefined}))))
      (is (true? (oracle/valid? (pool/build-msg d {"fix_type" good})))
          (str "a DEFINED enum number (" good ") passes the oracle")))))

;; ── positive corpus is oracle-VALID (layer-3 filter) ─────────────────────

(deftest positive-corpus-all-valid
  (testing "every :positive entry independently passes the live oracle"
    (doseq [m (concat inclusive-bound-msgs exclusive-bound-msgs)]
      (let [{:keys [positive]} (corpus m)]
        (is (seq positive) (str m " has a non-empty positive corpus"))
        (doseq [e positive]
          (is (true? (oracle/valid? (rebuild m (:edn-value e))))
              (str m " positive entry must be oracle-valid; verdict " (:verdict e))))))))

;; ── violating corpus is non-empty + every entry oracle-REJECTED ──────────

(deftest violating-corpus-all-rejected
  (testing "every constrained message yields a non-empty violating corpus, all rejected"
    (doseq [m (concat inclusive-bound-msgs exclusive-bound-msgs)]
      (let [{:keys [violating]} (corpus m)]
        (is (seq violating)
            (str m " (constrained) must produce at least one oracle-rejected violation"))
        (doseq [e violating]
          ;; gen-corpus already removed oracle-accepted candidates, but re-check
          ;; independently from the entry's :edn-value (the bytes on disk).
          (is (not= :valid (:verdict e))
              (str m "/" (:field e) " violating entry must carry a reject verdict"))
          (is (false? (oracle/valid? (rebuild m (:edn-value e))))
              (str m "/" (:field e) " (bad=" (pr-str (:bad-value e))
                   ") must be independently rejected by the oracle")))))))

;; ── proto-db drift has been ELIMINATED (resync + descriptor-sourcing) ─────

(deftest gps-drift-eliminated
  (testing "after the proto-db resync to the live wire (phantom altitude bounds
            removed), ser.JonGuiDataGps produces ZERO drift candidates — the
            generator no longer emits values that violate a constraint the live
            .proto lacks. (The reclassification mechanism — gen-corpus moving an
            oracle-ACCEPTED candidate to :violating-drift rather than counting it
            a violation — stays in place as a defensive guard against future
            drift; there is simply no live drift to feed it now.)"
    (let [{:keys [violating-drift]} (corpus "ser.JonGuiDataGps")]
      (is (empty? violating-drift)
          (str "expected zero drift after the resync; got "
               (vec (map :field violating-drift)))))))

;; ── boundary-failures: a valid boundary the oracle rejects = faithfulness bug ─

(defn- oracle-rejected-boundaries
  "Independent breakdown: each type/constraint boundary value the tool injects
   that the LIVE oracle rejects, as {:field :boundary :rule}. A faithful boundary
   corpus produces NONE."
  [full-name]
  (let [d (get @pool* full-name)]
    (for [{:keys [edn-value field boundary]} (assemble/boundary-corpus @pool* @db* full-name seed)
          :let [msg (pool/build-msg d edn-value)]
          :when (not (oracle/valid? msg))]
      {:field field :boundary boundary
       :rule (str/join " " (take 2 (str/split-lines (first (oracle/violations msg)))))})))

(deftest boundary-corpus-faithful-inclusive-bounds
  (testing "inclusive-bound messages produce ZERO boundary-failures (faithful)"
    (doseq [m inclusive-bound-msgs]
      (is (zero? (count (:boundary-failures (corpus m))))
          (str m " boundary corpus must be wholly oracle-valid; rejected: "
               (pr-str (oracle-rejected-boundaries m)))))))

(deftest boundary-corpus-faithful-exclusive-bounds
  ;; CONTRACT: for an EXCLUSIVE double/float bound, field-boundaries injects the
  ;; one-ULP-inside value (Math/nextUp gt / Math/nextDown lt), never the bound
  ;; itself, and filters every candidate by the real gt/lt/gte/lte predicate — so
  ;; an exclusive-bound message (azimuth, longitude, bank …) produces a wholly
  ;; oracle-valid boundary corpus, exactly like the inclusive case.
  (testing "exclusive-double-bound messages must ALSO produce zero boundary-failures"
    (doseq [m exclusive-bound-msgs]
      (is (zero? (count (:boundary-failures (corpus m))))
          (str m " injects an out-of-range exclusive bound as a 'boundary'; rejected: "
               (pr-str (oracle-rejected-boundaries m)))))))
