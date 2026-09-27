(ns protodoc.gencorpus.repeated-items-test
  "ITEM-LEVEL rules on repeated scalar fields — `repeated.items.<type>.<rule>`.

   A repeated field carries two rule tiers: the LIST tier (`min_items`,
   `max_items`) and the ELEMENT tier (`items`, a whole FieldRules applied to
   every element). Losing the element tier is silent everywhere except at the
   oracle: the generator draws each element from the type's full envelope, the
   oracle still enforces the element bound, and every message that reaches such
   a field stops producing oracle-valid positives. Measured on
   `ser.JonGuiDataScene.heat_scores` (`max_items: 2, items.float {gte 0, lte
   1}`): the seed-1 base drew `[-0.0 2.17]`, item 1 was rejected as
   `float.gte_lte`, and `ser.JonGUIState` — which embeds the scene — produced
   ZERO positives.

   Exercised against the LIVE descriptor, through BOTH constraint sources the
   generator is driven with: `effective-db` (the production gen-corpus path) and
   raw proto-db (the doc projection other suites generate against). Both speak
   the same `{:items {<rule-set> {...}}}` shape."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")

(def ^:private pool* (delay (pool/load-pool binpb)))
(def ^:private live-db* (delay (constraints/effective-db @pool* binpb db-path)))
(def ^:private doc-db* (delay (edn/read-string (slurp db-path))))

(def ^:private scene "ser.JonGuiDataScene")

;; Enough seeds that every item index up to max_items is drawn many times over;
;; the non-vacuity assertion below proves index 1 actually was.
(def ^:private seeds (range 64))

(defn- oracle-valid?
  "Does `edn-value` for message `full-name` pass the protovalidate oracle?"
  [full-name edn-value]
  (oracle/valid? (pool/build-msg (get @pool* full-name) edn-value)))

(defn- field-of
  "The FieldDescriptor for `field` of message `full-name` in the live pool."
  ^com.google.protobuf.Descriptors$FieldDescriptor [full-name field]
  (.findFieldByName ^com.google.protobuf.Descriptors$Descriptor (get @pool* full-name) field))

;; Through the VAR, so a redefinition (a mutation proof) reaches these tests.
(def ^:private item-constraints #'assemble/item-constraints)

(defn- on-unit-bound?
  "Is `x` numerically 0.0 (either sign) or 1.0? `==`, not a set lookup, so -0.0
   counts as the bound it equals rather than hashing apart from 0.0."
  [x]
  (let [d (double x)] (or (== d 0.0) (== d 1.0))))

(defn- on-bound-count
  "How many of the elements across `lists` sit EXACTLY on 0.0 or 1.0 — the two
   bounds a CLAMPING generator piles its out-of-range draws onto."
  [lists]
  (count (filter on-unit-bound? (apply concat lists))))

(defn- score-lists
  "Every `heat_scores` and `day_scores` list drawn across `seeds` with `db`."
  [db]
  (for [s seeds
        :let [v (assemble/generate @pool* db scene s)]
        field ["heat_scores" "day_scores"]]
    (get v field)))

(defn- out-of-unit-interval
  "The elements of `xs` outside [0, 1] — the bound both scene score lists
   declare on every item."
  [xs]
  (vec (remove #(<= 0.0 (double %) 1.0) xs)))

(deftest element-rules-reach-the-live-constraints
  (testing "the extractor carries `repeated.items` as :items keyed by rule set,
            beside the list-tier bound, rather than dropping it"
    (let [live (constraints/extract binpb)]
      (doseq [[field max-items] [["heat_scores" 2] ["day_scores" 5]]
              :let [c (get-in live [scene field])]]
        (is (= max-items (:max-items c)) (str field " keeps its list-tier max_items"))
        (is (= [0.0 1.0] (mapv #(some-> % double)
                               ((juxt :gte :lte) (get-in c [:items :float]))))
            (str field " must carry items.float {gte 0, lte 1}; got " (pr-str c))))
      (is (= 31 (some-> (get-in live ["ui.TabviewProps" "tab_names" :items :string :max-len])
                        long))
          "a STRING element rule reaches the constraints too, not only float"))))

(deftest bounded-repeated-float-items-are-drawn-inside-their-bounds
  (doseq [[source db*] [[:effective-db live-db*] [:proto-db doc-db*]]]
    (testing (str "every item of a bounded repeated float, at every index ("
                  (name source) ")")
      (let [draws (for [s seeds] (assemble/generate @pool* @db* scene s))]
        (doseq [[field max-items] [["heat_scores" 2] ["day_scores" 5]]]
          (let [vs (map #(get % field) draws)]
            (is (every? #(<= (count %) max-items) vs)
                (str field " never exceeds max_items " max-items))
            (is (empty? (mapcat out-of-unit-interval vs))
                (str field " items outside [0,1]: "
                     (vec (take 8 (mapcat out-of-unit-interval vs)))))
            ;; non-vacuity: the defect showed at item INDEX 1, so a run whose
            ;; draws never reached a second element would prove nothing.
            (is (some #(<= 2 (count %)) vs)
                (str field ": no seed drew a second element — index 1 unexercised"))))
        (testing "the draw is FAIR across the interval, not a clamp of the envelope"
          (let [lists (score-lists @db*)
                total (count (apply concat lists))
                on-bound (on-bound-count lists)]
            (is (< (* 2 on-bound) total)
                (str on-bound "/" total " score elements sit exactly on 0.0 or 1.0 — "
                     "a generator drawing the whole float envelope and clamping it "
                     "into [0,1] piles most draws onto the bounds"))))))))

(deftest the-fairness-assertion-can-fail
  (testing "POSITIVE CONTROL: a clamping generator — the whole float envelope
            drawn, then clamped into [0,1] — puts MOST elements on a bound, so
            the fair-draw assertion above is not vacuous"
    (let [leaf-gen assemble/leaf-gen
          clamping (fn [type-kw c f enums]
                     (if (= :float type-kw)
                       (gen/fmap #(-> (double %) (max 0.0) (min 1.0))
                                 (leaf-gen type-kw (dissoc c :gte :lte) f enums))
                       (leaf-gen type-kw c f enums)))
          lists (with-redefs [assemble/leaf-gen clamping] (doall (score-lists @live-db*)))
          total (count (apply concat lists))]
      (is (pos? total))
      (is (< total (* 2 (on-bound-count lists)))
          (str (on-bound-count lists) "/" total " on a bound under the clamp mutant")))))

(deftest only-the-rule-set-for-the-fields-own-type-applies
  (let [f (field-of scene "day_scores")]
    (testing "a rule set naming ANOTHER type is ignored, never folded onto a float"
      (is (= {} (item-constraints {:max-items 5 :items {:int32 {:gte 7 :lte 9}}} f))))
    (testing "the matching rule set is applied, and the list tier is removed"
      (is (= {:gte 0 :lte 1}
             (item-constraints {:max-items 5 :required true
                                :items {:float {:gte 0 :lte 1} :int32 {:gte 7}}}
                               f))))
    (testing "a string field takes the :string rule set"
      (is (= {:max-len 3}
             (item-constraints {:items {:string {:max-len 3} :float {:lte 1}}}
                               (field-of "ui.TabviewProps" "tab_names")))))))

;; At the default generation size (30) the string generator never draws past
;; ~30 characters, so a 31-character element bound can never bite there. Both
;; tests below generate where the bound DOES bite, and prove it by dropping the
;; element tier and watching the over-long elements appear.

(def ^:private big-size 100)

(defn- tab-name-lists
  "Every `tab_names` list drawn across `seeds` at `size`, with the field's
   constraints replaced by `constraints` when given."
  ([size] (for [s seeds]
            (get (assemble/sample (assemble/message-gen @pool* @live-db* "ui.TabviewProps" #{})
                                  s size)
                 "tab_names")))
  ([size constraints]
   (let [entry-gen @#'assemble/field-entry-gen
         g (entry-gen @pool* @live-db* (field-of "ui.TabviewProps" "tab_names")
                      constraints (:enums @live-db*) #{})]
     (for [s seeds] (get (assemble/sample g s size) "tab_names")))))

(defn- longer-than
  "The elements across `lists` longer than `n` characters."
  [n lists]
  (remove #(<= (count %) n) (apply concat lists)))

(deftest bounded-repeated-string-items-honor-max-len
  (testing "every element of ui.TabviewProps.tab_names honours items.string.max_len
            31, at a size where an unbounded draw exceeds it"
    (let [vs (tab-name-lists big-size)]
      (is (some seq vs) "non-vacuous: some seed drew a tab name")
      (is (empty? (longer-than 31 vs))
          (str "over-long tab names: " (vec (take 4 (longer-than 31 vs)))))))
  (testing "a small element max_len bites at the default size too"
    (let [vs (tab-name-lists 30 {:max-items 4 :items {:string {:max-len 3}}})]
      (is (some #(some seq %) vs) "non-vacuous: some seed drew a non-empty name")
      (is (empty? (longer-than 3 vs))
          (str "names over 3 chars: " (vec (take 4 (longer-than 3 vs)))))))
  (testing "POSITIVE CONTROL: with the element tier gone the same draws DO exceed
            both bounds, so neither assertion above is vacuous"
    (is (seq (longer-than 31 (tab-name-lists big-size {:max-items 8}))))
    (is (seq (longer-than 3 (tab-name-lists 30 {:max-items 4}))))))

(defn- boundary-lists
  "The `field` values of every boundary entry `boundary-corpus` injects for it."
  [full-name field]
  (->> (assemble/boundary-corpus @pool* @live-db* full-name 1)
       (filter #(= field (:field %)))
       (map :boundary)))

(deftest boundary-injection-reaches-repeated-extremes
  (testing "day_scores (max_items 5, above the random cap of 4) gets a list AT
            max_items, and lists holding both element bounds"
    (let [ls (boundary-lists scene "day_scores")]
      (is (some #(= 5 (count %)) ls) (str "no 5-element list among " (pr-str ls)))
      (is (every? #(<= 1 (count %) 5) ls))
      (is (some #(== 0.0 (double %)) (apply concat ls)))
      (is (some #(== 1.0 (double %)) (apply concat ls)))))
  (testing "heat_scores (max_items 2) packs its element bounds two to a list"
    (let [ls (boundary-lists scene "heat_scores")]
      (is (seq ls))
      (is (every? #(<= 1 (count %) 2) ls))
      (is (some #(== 0.0 (double %)) (apply concat ls)))
      (is (some #(== 1.0 (double %)) (apply concat ls)))))
  (testing "tab_names reaches a name of exactly max_len 31"
    (is (some #(= 31 (count %)) (apply concat (boundary-lists "ui.TabviewProps" "tab_names")))))
  (testing "every injected repeated boundary is oracle-valid"
    (doseq [[m field] [[scene "day_scores"] [scene "heat_scores"]
                       ["ui.TabviewProps" "tab_names"]]
            {:keys [edn-value boundary] injected :field} (assemble/boundary-corpus @pool* @live-db* m 1)
            :when (= field injected)]
      (is (oracle-valid? m edn-value) (str m "." field " rejected at " (pr-str boundary))))))

(deftest scene-draws-are-oracle-valid
  (testing "POSITIVE CONTROL: the oracle REJECTS an out-of-bounds item, so the
            validity assertion below can fail"
    (let [base (assemble/generate @pool* @live-db* scene 1)]
      (is (not (oracle-valid? scene (assoc base "heat_scores" [0.5 2.0]))))
      (is (not (oracle-valid? scene (assoc base "day_scores" [-0.5]))))))
  (testing "every seeded draw of the scene message passes the oracle"
    (let [invalid (for [s seeds
                        :let [v (assemble/generate @pool* @live-db* scene s)]
                        :when (not (oracle-valid? scene v))]
                    s)]
      (is (empty? invalid) (str "oracle-rejected seeds: " (vec (take 8 invalid))))))
  (testing "the enclosing ser.JonGUIState draws oracle-valid too (seed 1, the
            gen-corpus base seed)"
    (is (oracle-valid? "ser.JonGUIState"
                       (assemble/generate @pool* @live-db* "ser.JonGUIState" 1)))))
