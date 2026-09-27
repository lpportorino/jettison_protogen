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
;; doc-only). `:items` is a repeated field's ELEMENT tier, `{<rule-set> {...}}` —
;; compared per rule set, so an element bound that drifts or vanishes fails here
;; exactly like a list-tier one.
(def ^:private modelled
  [:gte :gt :lte :lt :min-len :max-len :min-items :max-items
   :pattern :in :not-in :defined-only :required :items])

(declare canon)

(defn- canon-items
  "The element tier `{<rule-set> {...}}` with each rule set canonicalised by
   `canon` under its OWN name as the wire type. A rule set left with nothing
   modelled is dropped, exactly as `constraints/extract` drops it, so a rule set
   carrying only unmodelled rules is not reported as drift."
  [items]
  (into {} (for [[rule-set c] items
                 :let [cc (canon rule-set c)]
                 :when (seq cc)]
             [rule-set cc])))

(defn- canon
  "Normalize a constraints map for a TYPE-INSENSITIVE numeric compare. Clojure
   `=` treats -1 ≠ -1.0, and a FLOAT field's bound is float32 on the descriptor
   side (0.1f = 0.10000000149…) but the authored double on the proto-db side
   (0.1) — the SAME authored constraint. So coerce bounds to the field's WIRE
   width (float fields → float32, else double; counts → long); only a real VALUE
   drift then fails.

   `:items` recurses: each element rule set is canonicalised under ITS OWN
   name as the wire type (`:float` → float32), which is what lets proto-db's
   long-typed `{:gte 0 :lte 1}` equal the descriptor's `{:gte 0.0 :lte 1.0}`."
  [ftype c]
  (let [numify (if (= :float ftype) #(double (float %)) double)
        m (reduce-kv (fn [m k v]
                       (assoc m k (cond
                                    (#{:gte :gt :lte :lt} k) (numify v)
                                    (#{:min-len :max-len :min-items :max-items} k) (long v)
                                    (= :items k) (canon-items v)
                                    (vector? v) (vec v)
                                    :else v)))
                     {} (select-keys c modelled))]
    (if (empty? (:items m)) (dissoc m :items) m)))

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
