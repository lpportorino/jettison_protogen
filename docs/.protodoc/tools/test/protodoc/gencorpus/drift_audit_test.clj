(ns protodoc.gencorpus.drift-audit-test
  "DRIFT-AUDIT GATE — keeps the proto-db doc projection honest against the LIVE
   wire contract. proto-db.edn is generated from a JSON descriptor snapshot and
   can drift from the committed `.binpb` (the source the oracle + every codec
   read): a stale `min_items`, a removed bound left behind as a phantom, a field
   added to the proto but not yet re-synced. This gate re-extracts every field's
   buf.validate rules straight from the binpb and FAILS if proto-db's stored
   constraints diverge — so the next descriptor bump that forgets a proto-db
   resync trips the build instead of silently feeding stale constraints to the
   doc tooling / bench / manifests.

   The gencorpus tool itself is drift-IMMUNE (it sources constraints from the
   binpb via constraints/effective-db); this gate protects the OTHER proto-db
   consumers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.constraints :as constraints]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

;; the modelled constraint surface both sources express (proto-db's :example is
;; doc-only).
(def ^:private modelled
  [:gte :gt :lte :lt :min-len :max-len :min-items :max-items
   :pattern :in :not-in :defined-only :required])

(defn- canon
  "Normalize a constraints map for a TYPE-INSENSITIVE numeric compare. Clojure
   `=` treats -1 ≠ -1.0, and a FLOAT field's bound is float32 on the descriptor
   side (0.1f = 0.10000000149…) but the authored double on the proto-db side
   (0.1) — the SAME authored constraint. So coerce bounds to the field's WIRE
   width (float fields → float32, else double; counts → long); only a real VALUE
   drift then fails."
  [ftype c]
  (let [num (if (= :float ftype) #(double (float %)) double)]
    (reduce-kv (fn [m k v]
                 (assoc m k (cond
                              (#{:gte :gt :lte :lt} k) (num v)
                              (#{:min-len :max-len :min-items :max-items} k) (long v)
                              (vector? v) (vec v)
                              :else v)))
               {} (select-keys c modelled))))

(deftest proto-db-matches-the-live-descriptor
  (testing "every proto-db field's modelled constraints equal the live binpb's
            buf.validate rules — no stale/phantom/missing drift"
    (let [proto-db (edn/read-string (slurp db-path))
          live (constraints/extract binpb)
          divergences
          (for [[mid m] (:messages proto-db)
                f (:fields m)
                :let [fname (:name f)
                      ftype (:type f)
                      db-c (canon ftype (or (:constraints f) {}))
                      live-c (canon ftype (get-in live [mid fname] {}))]
                :when (not= db-c live-c)]
            {:msg mid :field fname :proto-db db-c :live live-c})]
      (is (empty? divergences)
          (str (count divergences) " proto-db field(s) drifted from the live "
               "descriptor — run `clojure -M:run sync-ir --descriptor "
               "../../../output/json-descriptors/descriptor-set.json --db-path "
               "../proto-db.edn` to resync (showing ≤15):\n"
               (str/join "\n" (map pr-str (take 15 divergences))))))))
