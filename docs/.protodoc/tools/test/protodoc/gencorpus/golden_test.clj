(ns protodoc.gencorpus.golden-test
  "Layer 5 (GOLDEN) + layer 6 (DETERMINISM, sha256) for the corpus generator.

   A small, committed golden corpus is the cross-run / cross-machine anchor:
   for a handful of representative messages at fixed seeds we store the wire
   `.bin`, its sha256, and the assembler's EDN value map (the sidecar
   `golden/golden.edn`). The default test run only ASSERTS against those
   committed fixtures — it NEVER regenerates them. Three guarantees are pinned:

   1. DETERMINISM — regenerating at the same seed reproduces the BYTE-IDENTICAL
      wire (same EDN value, same sha256, same bytes as the committed `.bin`). A
      divergence here is a determinism regression in the assembler/codec, not a
      flake (the whole tool's reproducibility contract — assemble/sample uses a
      seed-stable low-level call-gen).
   2. GOLDEN EDN ↔ WIRE — the stored `:edn-value` rebuilds the committed wire
      byte-for-byte (the sidecar and the `.bin` cannot drift apart).
   3. ROUNDTRIP FAITHFULNESS — each committed golden reparses + re-serializes
      byte-identically with NO silently-dropped top-level field (bug #5/#8).

   The corpus deliberately spans the fixed-shape edges the fixes introduced:
   ser.CvChannelMeta (fixed-size repeated floats 4/16/64 — bug #22; uint64
   BigInt — bug #1; float32 quantization — bug #3), cmd.Root (EXACTLY ONE oneof
   branch — bug #21; UUID strings — bug #7), ser.JonGuiDataGps (doubles + a
   nested message).

   REFRESH (a deliberate, declared event — NOT part of the test run): call
   `(write-goldens!)` from the comment block below, then COMMIT the diff."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.pool :as pool])
  (:import [java.io InputStream OutputStream]
           [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

;; Where the refresh WRITES (filesystem, cwd = the tools dir) and where the test
;; READS (classpath — `test` is on the :test alias path, so the goldens resolve
;; via io/resource: hermetic, committed-fixture access).
(def ^:private golden-fs-dir "test/protodoc/gencorpus/golden")
(def ^:private golden-res-dir "protodoc/gencorpus/golden")
(def ^:private sidecar-res (str golden-res-dir "/golden.edn"))

(def ^:private pool* (delay (pool/load-pool binpb-path)))
;; effective-db = the LIVE descriptor constraints (drift-immune), exactly what
;; the production gen-corpus path uses — so the golden anchor IS production.
(def ^:private db* (delay (constraints/effective-db @pool* binpb-path db-path)))

;; The golden manifest — which messages + seeds anchor the corpus. Tiny by
;; design (boundary/violating coverage lives in the other test namespaces); each
;; entry is chosen to exercise a distinct fix class (see the ns docstring).
(def ^:private golden-specs
  [{:msg-name "ser.JonGuiDataGps" :seed 1}
   {:msg-name "ser.JonGuiDataGps" :seed 42}
   {:msg-name "ser.CvChannelMeta" :seed 1}
   {:msg-name "cmd.Root" :seed 0}
   {:msg-name "cmd.Root" :seed 5}])

;; ── helpers ────────────────────────────────────────────────────────────

(defn- sha256-hex
  "Lowercase hex SHA-256 of `data` (java.security.MessageDigest)."
  ^String [^bytes data]
  (let [^MessageDigest md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md data)
         (map #(format "%02x" (bit-and (long %) 0xff)))
         (apply str))))

(defn- edn->wire
  "EDN value map → wire `.bin` bytes for `full-name` resolved in `pool`."
  ^bytes [pool full-name edn-value]
  (pool/->bin (pool/build-message pool full-name edn-value)))

(defn- bin-file-name [msg-name seed]
  (format "%s-seed%d.bin" (str/replace ^String msg-name "." "_") (long seed)))

(defn- resource-bytes
  "All bytes of a committed classpath resource (a golden `.bin`)."
  ^bytes [res-path]
  (with-open [^InputStream in (io/input-stream (io/resource res-path))]
    (.readAllBytes in)))

(defn- read-goldens
  "The committed golden sidecar (a vector of entry maps), read off the classpath."
  []
  (edn/read-string (slurp (io/resource sidecar-res))))

;; ── refresh (NOT run by the suite — a deliberate, declared regeneration) ──

(defn write-goldens!
  "Regenerate the golden corpus + sidecar from the live descriptor-set + proto-db
   and WRITE them under `test/.../golden/`. A deliberate refresh only — fire from
   the comment block, then COMMIT the diff. Returns the sidecar entries."
  []
  (let [pool @pool*
        db @db*
        ^java.io.File dir (io/file golden-fs-dir)]
    (.mkdirs dir)
    (let [entries (vec (for [{:keys [msg-name seed]} golden-specs]
                         (let [edn-value (assemble/generate pool db msg-name seed)
                               wire (edn->wire pool msg-name edn-value)
                               fname (bin-file-name msg-name seed)]
                           (with-open [^OutputStream os (io/output-stream (io/file dir fname))]
                             (.write os ^bytes wire))
                           {:msg-name msg-name
                            :seed seed
                            :file fname
                            :byte-count (alength wire)
                            :sha256 (sha256-hex wire)
                            :edn-value edn-value})))]
      ;; nil print bounds so the 160-element repeated arrays are written WHOLE.
      (binding [*print-length* nil *print-level* nil]
        (spit (io/file dir "golden.edn")
              (with-out-str (pprint/pprint entries))))
      entries)))

;; ── tests (ASSERT against the committed goldens) ──────────────────────────

(deftest goldens-are-committed
  (testing "the golden sidecar + every referenced .bin fixture are committed"
    (is (some? (io/resource sidecar-res))
        "golden/golden.edn must be committed (run (write-goldens!) to create it)")
    (let [goldens (read-goldens)]
      (is (= (count golden-specs) (count goldens))
          "sidecar entry count must match the golden manifest")
      (is (= (set (map (juxt :msg-name :seed) golden-specs))
             (set (map (juxt :msg-name :seed) goldens)))
          "sidecar (msg,seed) set must match the golden manifest")
      (doseq [{:keys [file]} goldens]
        (is (some? (io/resource (str golden-res-dir "/" file)))
            (str "missing committed .bin fixture: " file))))))

(deftest determinism-regeneration-is-byte-identical
  (testing "regenerating at the committed seed reproduces the BYTE-IDENTICAL golden"
    (doseq [{:keys [msg-name seed file sha256 edn-value]} (read-goldens)]
      (let [regen-edn (assemble/generate @pool* @db* msg-name seed)
            regen-wire (edn->wire @pool* msg-name regen-edn)
            committed (resource-bytes (str golden-res-dir "/" file))]
        (is (= edn-value regen-edn)
            (str msg-name " seed " seed
                 ": regenerated EDN diverged from the committed golden (determinism bug)"))
        (is (= sha256 (sha256-hex regen-wire))
            (str msg-name " seed " seed
                 ": regenerated wire sha256 != golden sha256 (determinism bug)"))
        (is (java.util.Arrays/equals regen-wire committed)
            (str msg-name " seed " seed
                 ": regenerated wire bytes != committed .bin (determinism bug)"))))))

(deftest golden-edn-reproduces-the-committed-wire
  (testing "the stored :edn-value rebuilds the committed wire byte-for-byte"
    (doseq [{:keys [msg-name seed file sha256 edn-value]} (read-goldens)]
      (let [wire (edn->wire @pool* msg-name edn-value)
            committed (resource-bytes (str golden-res-dir "/" file))]
        (is (= sha256 (sha256-hex committed))
            (str file ": committed .bin sha256 drifted from the sidecar"))
        (is (= sha256 (sha256-hex wire))
            (str msg-name " seed " seed
                 ": stored :edn-value no longer reproduces the golden sha256"))
        (is (java.util.Arrays/equals wire committed)
            (str msg-name " seed " seed
                 ": stored :edn-value wire != committed .bin"))))))

(deftest goldens-roundtrip-faithfully
  (testing "each committed golden reparses + re-serializes byte-identically, no field dropped"
    (doseq [{:keys [msg-name seed edn-value]} (read-goldens)]
      (let [d (get @pool* msg-name)
            {:keys [byte-identical? reparsed]} (gc/roundtrip d edn-value)
            dropped (gc/presence-violations d edn-value reparsed)]
        (is byte-identical?
            (str msg-name " seed " seed ": golden wire is not byte-faithful on reparse"))
        (is (empty? dropped)
            (str msg-name " seed " seed ": golden silently dropped fields " (vec dropped)))))))

(deftest cv-golden-pins-fixed-repeated-sizes
  (testing "the ser.CvChannelMeta golden honors the fixed repeated-float sizes (bug #22)
            and carries a uint64 BigInt (bug #1) — self-documenting, sha-independent"
    (let [{:keys [edn-value]} (->> (read-goldens)
                                   (filter #(= "ser.CvChannelMeta" (:msg-name %)))
                                   first)]
      (is (some? edn-value) "the CvChannelMeta golden is present")
      (is (= 4 (count (get edn-value "sharpness_level1"))))
      (is (= 16 (count (get edn-value "sharpness_level2"))))
      ;; level3 is the 16x10 grid (min_items 160) — the LIVE wire size the
      ;; proto-db resync corrected from the stale 64 (8x8 era).
      (is (= 160 (count (get edn-value "sharpness_level3"))))
      (is (instance? clojure.lang.BigInt (get edn-value "pts_ns"))
          "uint64 fields carry a BigInt (malli :int cannot hold the upper half)"))))

(comment
  ;; REFRESH the golden corpus (deliberate, declared event), then COMMIT the diff:
  (write-goldens!)
  ;; Inspect a regeneration without writing:
  (assemble/generate @pool* @db* "ser.CvChannelMeta" 1))
