(ns protodoc.gencorpus.contract-test
  "The CROSS-REPO OUTPUT CONTRACT (the literal interface the epic exists to
   produce): gencorpus/run → a directory of wire .bin files + a manifest.edn of
   entries {:msg-name :seed :edn-value :byte-count :verdict :kind :file}. A
   consumer (jettison's manifold differential) relies on exactly this shape — no
   per-language vectors, no Clojure glue. This pins it end-to-end."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus :as gc]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf Descriptors$Descriptor]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")
(def ^:private pool* (delay (pool/load-pool binpb)))

(defn- temp-dir ^String []
  (str (Files/createTempDirectory "gencorpus-contract" (make-array FileAttribute 0))))

(def ^:private contract-keys #{:msg-name :seed :edn-value :byte-count :verdict :kind :file})

(deftest run-writes-the-consumer-contract
  (testing "gencorpus/run writes .bin files + a manifest.edn whose every entry
            carries the documented shape, every :file exists + reparses, and
            :byte-count == the actual .bin size"
    (let [dir (temp-dir)
          summary (gc/run {:descriptor binpb :db-path db-path
                           :message "ser.JonGuiDataGps" :count 6 :seed 1 :output dir})
          manifest (edn/read-string (slurp (io/file dir "manifest.edn")))
          ^Descriptors$Descriptor d (get @pool* "ser.JonGuiDataGps")]
      (is (pos? (:positive summary)) "produced a positive corpus")
      (is (vector? manifest))
      (is (seq manifest))
      (testing "every entry carries the contract keys (violating/clamp entries may add
                :field/:bad-value/:clamp-value debug metadata — harmless extras the consumer ignores)"
        (is (every? #(set/subset? contract-keys (set (keys %))) manifest)
            (str "entries MISSING contract keys: "
                 (pr-str (remove #(set/subset? contract-keys (set (keys %))) manifest))))
        (is (every? #(set/subset? (set (keys %)) (into contract-keys [:field :bad-value :clamp-value])) manifest)
            "no surprise keys beyond the contract + documented violating/clamp extras"))
      (testing "every entry is internally consistent"
        (doseq [e manifest]
          (is (= "ser.JonGuiDataGps" (:msg-name e)))
          (is (contains? #{:random :boundary :violating :clamp} (:kind e)))
          (is (map? (:edn-value e)))
          (let [f (io/file dir (:file e))]
            (is (.exists f) (str "missing .bin: " (:file e)))
            (is (= (:byte-count e) (.length f)) ":byte-count matches the .bin size")
            ;; the .bin must reparse against the live descriptor (it is real wire)
            (is (pool/reparse d (.readAllBytes (io/input-stream f))))))))))

(deftest clamp-tier-injects-beyond-range
  (testing "gen-corpus writes a :clamp beyond-range tier over numeric fields — the
            Oracle-1 CLAMP+LOG sub-corpus (constrained: one step past the bound;
            unconstrained: representation extremes), each a real reparseable .bin"
    (let [dir      (temp-dir)
          summary  (gc/run {:descriptor binpb :db-path db-path
                            :message "ser.JonGuiDataGps" :count 4 :seed 1 :output dir})
          manifest (edn/read-string (slurp (io/file dir "manifest.edn")))
          clamps   (filterv #(= :clamp (:kind %)) manifest)]
      (is (pos? (:clamp summary)) "run summary counts the clamp tier")
      (is (seq clamps) "manifest carries :clamp entries (GPS has numeric fields)")
      (is (every? #(contains? % :clamp-value) clamps)
          "each clamp entry records the injected :clamp-value")
      (is (every? #(map? (:edn-value %)) clamps))
      (is (every? #(pos? (:byte-count %)) clamps) "each clamp entry is real wire"))))

(deftest manifest-verdict-partitions-positive-vs-violating
  (testing "manifest verdicts honestly partition: random/boundary entries are
            :valid, violating entries carry their :invalid violations"
    (let [dir (temp-dir)
          _ (gc/run {:descriptor binpb :db-path db-path
                     :message "ser.JonGuiDataGps" :count 6 :seed 1 :output dir})
          manifest (edn/read-string (slurp (io/file dir "manifest.edn")))
          by-kind (group-by :kind manifest)]
      (is (every? #(= :valid (:verdict %)) (concat (:random by-kind) (:boundary by-kind)))
          "positive (random + boundary) entries are oracle-valid")
      (when-let [vs (:violating by-kind)]
        (is (every? #(and (map? (:verdict %)) (seq (:invalid (:verdict %)))) vs)
            "violating entries carry non-empty :invalid violations")))))
