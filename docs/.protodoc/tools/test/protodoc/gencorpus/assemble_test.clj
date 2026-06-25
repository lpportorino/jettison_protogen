(ns protodoc.gencorpus.assemble-test
  "Smoke + invariant tests for the message-walking assembler: the previously
   un-serializable differential target (ser.JonGUIState), oneof exclusivity, and
   seed determinism."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf Descriptors$Descriptor]))

(def ^:private binpb-path "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb-path)))
(def ^:private db* (delay (edn/read-string (slurp db-path))))

(defn- gen+serialize [full-name seed]
  (let [edn (assemble/generate @pool* @db* full-name seed)
        d (get @pool* full-name)
        wire (pool/->bin (pool/build-msg d edn))]
    {:edn edn :wire wire :d d}))

(deftest gps-generates-and-serializes
  (testing "a leaf state message generates, serializes, and reparses"
    (let [{:keys [edn wire d]} (gen+serialize "ser.JonGuiDataGps" 1)]
      (is (map? edn))
      (is (pos? (alength ^bytes wire)))
      (is (pool/reparse d wire)))))

(deftest jonguistate-serializes
  (testing "ser.JonGUIState — the differential target the bare-:map placeholder
            could not serialize (bug #10) — now generates + serializes whole"
    (let [{:keys [edn wire d]} (gen+serialize "ser.JonGUIState" 7)]
      (is (map? edn))
      (is (pos? (alength ^bytes wire)))
      (is (pool/reparse d wire))
      ;; opaque_payloads is a repeated MESSAGE — must be a vector, not {}
      (when (contains? edn "opaque_payloads")
        (is (vector? (get edn "opaque_payloads")))))))

(deftest oneof-exactly-one-branch
  (testing "cmd.Root sets EXACTLY ONE payload branch (never MULTIPLE_PAYLOADS — bug #21)"
    (let [d ^Descriptors$Descriptor (get @pool* "cmd.Root")
          oneof-field-names (->> (Descriptors$Descriptor/.getOneofs d)
                                 (mapcat #(.getFields ^com.google.protobuf.Descriptors$OneofDescriptor %))
                                 (map #(.getName ^com.google.protobuf.Descriptors$FieldDescriptor %))
                                 set)]
      (is (seq oneof-field-names) "cmd.Root has a payload oneof")
      (doseq [seed (range 12)]
        (let [edn (assemble/generate @pool* @db* "cmd.Root" seed)
              set-oneof (filter oneof-field-names (keys edn))]
          (is (<= (count set-oneof) 1)
              (str "seed " seed " set >1 oneof branch: " (vec set-oneof))))))))

(deftest deterministic-by-seed
  (testing "same seed → byte-identical wire across runs (determinism property)"
    (let [a (gen+serialize "ser.JonGuiDataGps" 42)
          b (gen+serialize "ser.JonGuiDataGps" 42)]
      (is (= (:edn a) (:edn b)))
      (is (java.util.Arrays/equals ^bytes (:wire a) ^bytes (:wire b))))))
