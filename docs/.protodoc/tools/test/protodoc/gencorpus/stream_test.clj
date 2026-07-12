(ns protodoc.gencorpus.stream-test
  "Contract tests for the command-stream synthesizer. Five guarantees:

   1. DETERMINISM — same seed+profile reproduces the identical manifest (EDN
      values + delays) AND byte-identical `.bin` sequence.
   2. ORACLE VALIDITY — every message of every profile (across seeds) passes the
      protovalidate oracle.
   3. TEMPORAL CONTRACT — delays are positive; `client_time_ms` is monotonic
      nondecreasing across the sequence; swept values sit inside the LIVE
      constraint bounds (read from `effective-db`, never hardcoded).
   4. MANIFEST CONTRACT — every entry carries the corpus keys PLUS
      `:profile :sequence-index :delay-ms`.
   5. REGISTRY — every registered profile produces a non-empty sequence.

   Hermetic: the pool/db are `delay`-shared and read from the committed
   descriptor-set + proto-db; wire bytes are compared IN MEMORY (no disk)."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.pool :as pool]
            [protodoc.gencorpus.stream :as stream]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (constraints/effective-db @pool* binpb-path db-path)))

;; The corpus contract keys every manifest entry must carry, plus the temporal
;; keys the stream adds.
(def ^:private corpus-keys #{:msg-name :seed :edn-value :byte-count :verdict :kind})
(def ^:private stream-extra-keys #{:profile :sequence-index :delay-ms})

(def ^:private seeds [1 2 7])

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- synth
  "Synthesize the profile's step seq (no disk) for a seed."
  [profile seed]
  (stream/synthesize @pool* @db* (get stream/profiles profile) seed))

(defn- step->wire
  "Wire `.bin` bytes for one synthesized step's EDN value."
  ^bytes [{:keys [edn-value]}]
  (pool/->bin (pool/build-message @pool* "cmd.Root" edn-value)))

;; ── 1. determinism ──────────────────────────────────────────────────────────

(deftest determinism-same-seed-reproduces-identical-session
  (testing "same seed+profile reproduces identical EDN values, delays, and wire bytes"
    (doseq [profile (stream/profile-names)]
      (let [a (synth profile 1)
            b (synth profile 1)]
        (is (= (mapv :edn-value a) (mapv :edn-value b))
            (str profile ": EDN values diverged across identical runs (determinism bug)"))
        (is (= (mapv :delay-ms a) (mapv :delay-ms b))
            (str profile ": delays diverged across identical runs (determinism bug)"))
        (is (every? true?
                    (map (fn [sa sb] (java.util.Arrays/equals (step->wire sa) (step->wire sb)))
                         a b))
            (str profile ": wire bytes diverged across identical runs (determinism bug)"))))))

;; ── 2. oracle validity ──────────────────────────────────────────────────────

(deftest every-message-is-oracle-valid
  (testing "every message of every profile passes the protovalidate oracle"
    ;; synthesize itself throws on an invalid message, so a green synth already
    ;; proves validity — this asserts it explicitly (no throw) and non-empty.
    (doseq [profile (stream/profile-names), seed seeds]
      (let [steps (synth profile seed)]
        (is (seq steps) (str profile " seed " seed ": produced no messages"))
        (is (every? #(= :valid (:verdict %))
                    (map-indexed (fn [i s] (stream/entry @pool* profile i s)) steps))
            (str profile " seed " seed ": a message failed the oracle"))))))

;; ── 3. temporal contract ─────────────────────────────────────────────────────

(defn- client-time [{:keys [edn-value]}]
  (bigint (get edn-value "client_time_ms")))

(deftest delays-are-positive
  (testing "every step's delay-ms is strictly positive"
    (doseq [profile (stream/profile-names), seed seeds]
      (is (every? pos? (map :delay-ms (synth profile seed)))
          (str profile " seed " seed ": a non-positive delay-ms")))))

(deftest client-time-is-monotonic-nondecreasing
  (testing "client_time_ms never goes backwards across the sequence"
    (doseq [profile (stream/profile-names), seed seeds]
      (let [ts (mapv client-time (synth profile seed))]
        (is (apply <= ts)
            (str profile " seed " seed ": client_time_ms is not monotonic: " (pr-str ts)))))))

(deftest swept-values-stay-within-live-constraint-bounds
  (testing "each profile's swept leaf values sit inside the LIVE constraint range"
    ;; Read the bound from effective-db (not hardcoded) for each sweep phase and
    ;; assert every step's swept leaf value is within it.
    (doseq [profile (stream/profile-names), seed seeds]
      (let [steps (synth profile seed)
            sweeps (filter #(= :sweep (:kind %)) (get stream/profiles profile))]
        (doseq [{:keys [path field-msg field-name]} sweeps]
          (let [[lo hi] (stream/field-range @db* field-msg field-name)
                vals (keep #(get-in (:edn-value %) path) steps)]
            (is (seq vals)
                (str profile ": sweep on " (pr-str path) " produced no values"))
            (is (every? #(<= lo (double %) hi) vals)
                (str profile ": a swept value on " (pr-str path)
                     " escaped the live bounds [" lo " " hi "]: " (pr-str (vec vals))))))))))

;; ── 4. manifest contract ─────────────────────────────────────────────────────

(deftest manifest-entries-carry-the-required-keys
  (testing "every manifest entry carries the corpus keys + the stream temporal keys"
    (let [required (set/union corpus-keys stream-extra-keys)]
      (doseq [profile (stream/profile-names)]
        (let [steps (synth profile 1)
              entries (map-indexed (fn [i s] (-> (stream/entry @pool* profile i s)
                                                 (dissoc :bin)))
                                   steps)]
          (is (every? #(set/subset? required (set (keys %))) entries)
              (str profile ": an entry is missing required keys"))
          (is (every? #(= :stream (:kind %)) entries)
              (str profile ": an entry is not :kind :stream"))
          (is (= (range (count entries)) (map :sequence-index entries))
              (str profile ": :sequence-index is not a 0.. run")))))))

;; ── 5. registry ──────────────────────────────────────────────────────────────

(deftest every-registered-profile-generates-a-non-empty-sequence
  (testing "each registered profile produces at least one message"
    (is (seq (stream/profile-names)) "the registry is non-empty")
    (doseq [profile (stream/profile-names)]
      (is (pos? (count (synth profile 1)))
          (str profile ": generated an empty sequence")))))

;; ── run.write round-trip (the on-disk manifest matches the in-memory synth) ──

(deftest write-stream-persists-the-manifest-contract
  (testing "write-stream! persists .bin files + a manifest whose entries match synth"
    (let [dir (str (java.nio.file.Files/createTempDirectory
                    "gencorpus-stream" (make-array java.nio.file.attribute.FileAttribute 0)))
          steps (synth :idle 1)
          entries (map-indexed (fn [i s] (stream/entry @pool* :idle i s)) steps)
          manifest (stream/write-stream! dir :idle entries)]
      (is (= (count steps) (count manifest)))
      (is (every? #(.exists (clojure.java.io/file dir (:file %))) manifest)
          "every manifest .bin exists on disk")
      (is (every? #(= (:byte-count %)
                      (.length (clojure.java.io/file dir (:file %))))
                  manifest)
          ":byte-count matches the written .bin size")
      (is (not (some :bin manifest)) "the written manifest carries no :bin bytes"))))
