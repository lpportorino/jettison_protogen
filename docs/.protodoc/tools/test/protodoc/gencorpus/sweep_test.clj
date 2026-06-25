(ns protodoc.gencorpus.sweep-test
  "Whole-pool generative round-trip sweep. The per-field parity battery proves
   constraint faithfulness pool-wide, but only a handful of messages get a
   WHOLE-message round-trip — so an assembler/serialize defect in a message with
   an unusual nesting, a map field, or recursion would slip. This sweeps EVERY
   non-router, non-map-entry message in the live 420-message pool: generate →
   build → ->bin → reparse → ->bin must be BYTE-IDENTICAL with no silent field
   drop (type-fidelity + no-silent-field-drop, pool-wide)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf Descriptors$Descriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")
(def ^:private pool* (delay (pool/load-pool binpb)))
;; effective-db = live descriptor constraints (drift-immune), exactly what the
;; production gen-corpus path uses.
(def ^:private db* (delay (constraints/effective-db @pool* binpb db-path)))

;; Messages that CANNOT generate a valid positive at seed 1 — each needs a
;; recorded reason (e.g. an un-satisfiable message-level CEL the field-wise
;; generator cannot meet). EMPTY: after descriptor-sourcing + required-aware
;; generation, every non-router message is generatable. A new entry here is a
;; proof-carrying debt, not a convenience.
(def ^:private no-positive-allowlist {})

(defn- map-entry? [^Descriptors$Descriptor d]
  (-> d (.getOptions) (.getMapEntry)))

(defn- sweepable
  "Every message in the pool except synthetic map-entry types (which are not
   independently buildable). Sorted for determinism."
  []
  (sort (for [[nm ^Descriptors$Descriptor d] @pool*
              :when (not (map-entry? d))]
          nm)))

(deftest whole-pool-roundtrip-is-byte-identical
  (let [failures
        (for [nm (sweepable)
              :let [d (get @pool* nm)
                    res (try
                          (let [edn (assemble/generate @pool* @db* nm 1)
                                rt (gc/roundtrip d edn)
                                drops (gc/presence-violations d edn (:reparsed rt))]
                            (cond
                              (not (:byte-identical? rt)) {:msg nm :why :not-byte-identical}
                              (seq drops) {:msg nm :why :silent-drop :fields (vec drops)}
                              :else nil))
                          (catch Throwable e
                            {:msg nm :why :threw :error (.getMessage e)}))]
              :when res]
          res)]
    (is (empty? failures)
        (str (count failures) " message(s) failed the whole-message round-trip (showing <=15):\n"
             (str/join "\n" (map pr-str (take 15 failures)))))
    ;; a real regression floor — the sweep must actually cover the schema, not
    ;; silently iterate an empty set.
    (is (<= 350 (count (sweepable)))
        (str "expected the sweep to cover the bulk of the pool; got " (count (sweepable))))))

(deftest whole-pool-generates-a-valid-positive
  (testing "EVERY non-router message generates at least one ORACLE-VALID value
            (seed 1) — or is allowlisted with a reason. Catches a message that
            silently yields only oracle-invalid output (the coverage hole where a
            broken constraint/required path would produce an empty positive
            corpus unnoticed)."
    (let [edb @db*
          invalid (for [nm (sweepable)
                        :when (not (contains? no-positive-allowlist nm))
                        :let [ok? (try
                                    (oracle/valid?
                                     (pool/build-msg (get @pool* nm)
                                                     (assemble/generate @pool* edb nm 1)))
                                    (catch Throwable _ false))]
                        :when (not ok?)]
                    nm)]
      (is (empty? invalid)
          (str (count invalid) " message(s) cannot generate an oracle-valid positive "
               "at seed 1 — fix the generator or allowlist with a reason (showing <=15): "
               (vec (take 15 invalid)))))))
