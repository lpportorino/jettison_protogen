(ns gate.run-test
  "Input-class mutation controls, receipt lifecycle and independent dependency-closure oracles."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.cache :as cache]
            [gate.canonical :as canonical]
            [gate.plan :as plan]
            [gate.run-contract :as r]
            [malli.core :as m]))

(def digest-a (canonical/sha256 "a"))
(def digest-b (canonical/sha256 "b"))
(def work-digest (canonical/sha256 "expected test inventory"))
(def gate
  {:id "compile" :label "Compile" :command ["clojure" "-M:test"] :cwd "."
   :inputs [{:id "source" :kind :tree :path "src" :required? true}]
   :outputs ["out/result.edn"] :environment ["MODE"] :toolchains ["image"]
   :dependencies [] :cache :content :network :denied
   :coverage {:expected work-digest :minimum 2}})
(def snapshot
  {:files [{:path "src/a.clj" :digest digest-a :executable? false}]
   :membership [{:selector "source" :paths ["src/a.clj"]}]
   :environment [{:name "MODE" :value nil}]
   :toolchains [{:name "image" :digest digest-a}] :complete? true :reason nil})
(def outputs [{:path "out/result.edn" :digest digest-a :executable? false}])
(def coverage {:expected work-digest :observed work-digest :count 2})
(defn earn
  ([] (earn gate snapshot []))
  ([definition inputs deps]
   (:receipt (cache/admit definition inputs inputs deps deps :passed coverage outputs "run-1" "attempt-1"))))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest path-contracts-reject-escape-and-alias-spellings
  (doseq [path ["/tmp/x" "../x" "a/../x" "a/./x" "a//x" "a/" "a\\x" "a\u0000x" "." ".."]]
    (is (not (m/validate r/Path path)) path))
  (doseq [path ["src/a.clj" "x" "src/**/*.clj" "src/λ.clj"]]
    (is (m/validate r/Path path)) path))

(deftest pass-receipt-needs-stable-inputs-coverage-and-outputs
  (let [receipt (earn)]
    (is (m/validate r/Receipt receipt))
    (is (= {:gate "compile" :action :cached :reason :cache-hit :key (:key receipt)}
           (cache/decide gate snapshot [] receipt outputs false)))
    (doseq [[outcome actual-coverage actual-outputs after expected]
            [[:failed coverage outputs snapshot :failed-execution]
             [:cancelled coverage outputs snapshot :failed-execution]
             [:passed (assoc coverage :count 1) outputs snapshot :coverage-mismatch]
             [:passed (assoc coverage :observed digest-a) outputs snapshot :coverage-mismatch]
             [:passed coverage [] snapshot :outputs-changed]
             [:passed coverage outputs (assoc-in snapshot [:files 0 :digest] digest-b) :input-unstable]]]
      (is (= {:status :refused :reason expected}
             (cache/admit gate snapshot after [] [] outcome actual-coverage actual-outputs "run-1" "attempt-1"))))))

(deftest independently-mutated-key-terms-all-invalidate
  (let [receipt (earn)]
    (doseq [[definition inputs]
            [[(assoc gate :command ["clojure" "-M:other"]) snapshot]
             [(assoc gate :cwd "subproject") snapshot]
             [gate (assoc-in snapshot [:files 0 :digest] digest-b)]
             [gate (assoc-in snapshot [:files 0 :executable?] true)]
             [gate (-> snapshot (assoc-in [:files 0 :path] "src/renamed.clj")
                       (assoc-in [:membership 0 :paths] ["src/renamed.clj"]))]
             [gate (-> snapshot (update :files conj {:path "src/new.clj" :digest digest-b :executable? false})
                       (update-in [:membership 0 :paths] conj "src/new.clj"))]
             [gate (assoc-in snapshot [:environment 0 :value] "")]
             [gate (assoc-in snapshot [:toolchains 0 :digest] digest-b)]]]
      (is (not= (:key receipt) (cache/input-key definition inputs [])))
      (is (= :input-changed (:reason (cache/decide definition inputs [] receipt outputs false)))))))

(deftest deletion-and-optional-empty-membership-are-not-the-old-snapshot
  (let [definition (assoc-in gate [:inputs 0 :required?] false)
        empty-snapshot (assoc snapshot :files [] :membership [{:selector "source" :paths []}])
        receipt (earn definition snapshot [])]
    (is (= :input-changed (:reason (cache/decide definition empty-snapshot [] receipt outputs false))))))

(deftest cosmetic-labels-and-unrelated-work-do-not-invalidate
  (let [receipt (earn)]
    (is (= :cache-hit (:reason (cache/decide (assoc gate :label "New display label") snapshot [] receipt outputs false))))
    (is (= :cache-hit (:reason (cache/decide gate snapshot [] receipt outputs false))))
    (is (= :forced (:reason (cache/decide gate snapshot [] receipt outputs true))))))

(deftest reordering-unordered-policy-and-manifest-terms-keeps-the-key
  (let [definition (-> gate
                       (update :inputs conj {:id "script" :kind :file :path "check.sh" :required? true})
                       (assoc :environment ["MODE" "PATH"]) (assoc :toolchains ["image" "compiler"]))
        inputs (-> snapshot
                   (update :files conj {:path "check.sh" :digest digest-b :executable? true})
                   (update :membership conj {:selector "script" :paths ["check.sh"]})
                   (update :environment conj {:name "PATH" :value "/bin"})
                   (update :toolchains conj {:name "compiler" :digest digest-b}))
        reordered-gate (reduce #(update %1 %2 (comp vec reverse)) definition [:inputs :environment :toolchains])
        reordered-inputs (reduce #(update %1 %2 (comp vec reverse)) inputs [:files :membership :environment :toolchains])]
    (is (= (cache/input-key definition inputs []) (cache/input-key reordered-gate reordered-inputs [])))))

(deftest incomplete-evidence-and-unbounded-network-never-hit
  (let [receipt (earn) incomplete (assoc snapshot :complete? false :reason :unreadable-input)]
    (is (= :incomplete-snapshot (:reason (cache/decide gate incomplete [] receipt outputs false))))
    (is (nil? (cache/input-key gate incomplete [])))
    (is (= :always-run (:reason (cache/decide (assoc gate :cache :always) snapshot [] receipt outputs false))))
    (is (= :network-unbounded (:reason (cache/decide (assoc gate :network :allowed) snapshot [] receipt outputs false))))
    (is (= :missing-receipt (:reason (cache/decide gate snapshot [] nil outputs false))))))

(deftest forged-or-stale-receipts-do-not-reuse
  (let [receipt (earn)]
    (doseq [[altered current-outputs reason]
            [[(assoc receipt :gate "other") outputs :invalid-receipt]
             [(assoc receipt :result digest-b) outputs :invalid-receipt]
             [receipt [] :outputs-changed]
             [receipt (assoc-in outputs [0 :digest] digest-b) :outputs-changed]
             [receipt (assoc-in outputs [0 :executable?] true) :outputs-changed]]]
      (is (= reason (:reason (cache/decide gate snapshot [] altered current-outputs false)))))))

(deftest incomplete-or-duplicated-manifests-refuse-with-closed-errors
  (doseq [inputs [(assoc snapshot :files []) (assoc snapshot :membership [])
                  (assoc snapshot :environment []) (assoc snapshot :toolchains [])
                  (update snapshot :files conj (first (:files snapshot)))
                  (assoc snapshot :reason :missing-input)
                  (assoc-in snapshot [:membership 0 :paths] ["src/a.clj" "src/a.clj"])]]
    (let [failure (error #(cache/input-key gate inputs []))]
      (is (= :invalid-snapshot (:code failure)))
      (is (m/validate r/Failure failure)))))

(deftest dependency-keys-and-success-evidence-propagate-without-occurrence-noise
  (let [definition (assoc gate :dependencies [{:gate "generate" :relation :produces}])
        deps [{:gate "generate" :relation :produces :key digest-a :result digest-a}]
        receipt (earn definition snapshot deps)]
    (is (= :cache-hit (:reason (cache/decide definition snapshot deps receipt outputs false))))
    (doseq [field [:key :result]]
      (is (= :input-changed (:reason (cache/decide definition snapshot (assoc-in deps [0 field] digest-b) receipt outputs false))))
      (is (= :dependency-unknown (:reason (cache/decide definition snapshot (assoc-in deps [0 field] nil) receipt outputs false)))))
    (is (= :invalid-dependency-terms (:code (error #(cache/input-key definition snapshot [])))))
    (let [second-receipt (:receipt (cache/admit definition snapshot snapshot deps deps :passed coverage outputs "run-2" "attempt-2"))]
      (is (not= (:run receipt) (:run second-receipt)))
      (is (= (:result receipt) (:result second-receipt))))))

(deftest ordering-only-edges-do-not-require-cache-evidence
  (let [definition (assoc gate :dependencies [{:gate "warm" :relation :after}])]
    (is (some? (cache/input-key definition snapshot [])))
    (is (= :invalid-dependency-terms
           (:code (error #(cache/input-key definition snapshot
                                           [{:gate "warm" :relation :after :key digest-a :result digest-a}])))))))

(defn declaration [id dependencies]
  (assoc gate :id id :dependencies dependencies))

(deftest schedule-has-a-deterministic-prerequisite-closure
  (let [definitions [(declaration "test" [{:gate "build" :relation :requires} {:gate "warm" :relation :after}
                                          {:gate "policy" :relation :invalidates}])
                     (declaration "build" []) (declaration "warm" []) (declaration "policy" [])
                     (declaration "unused" [])]
        result (plan/schedule definitions ["test"])]
    (is (= ["build" "warm" "test"] (:selected result)))
    (is (= ["policy" "unused"] (:deselected result)))
    (is (= result (plan/schedule (vec (reverse definitions)) ["test"])))))

(deftest invalid-dependency-graphs-refuse-before-work-starts
  (doseq [[definitions roots code]
          [[[(declaration "a" [{:gate "missing" :relation :after}])] ["a"] :missing-dependency]
           [[(declaration "a" [{:gate "a" :relation :invalidates}])] ["a"] :dependency-cycle]
           [[(declaration "a" []) (declaration "a" [])] ["a"] :duplicate-gate]
           [[(declaration "a" [])] [] :empty-selection]
           [[(declaration "a" [])] ["b"] :unknown-root]
           [[(declaration "a" [{:gate "b" :relation :requires}])
             (declaration "b" [{:gate "a" :relation :after}])] ["a"] :dependency-cycle]]]
    (let [failure (error #(plan/schedule definitions roots))]
      (is (= code (:code failure)))
      (is (m/validate r/Failure failure)))))

(deftest generated-dag-closure-matches-an-independent-fixed-point-oracle
  (let [property
        (prop/for-all [edges (gen/vector (gen/tuple (gen/choose 0 11) (gen/choose 0 11)
                                                    (gen/elements [:requires :after :produces :invalidates])) 0 50)]
                      (let [edges (set (filter (fn [[parent child _]] (< parent child)) edges))
                            names (mapv #(str "gate-" %) (range 12))
                            definitions (mapv (fn [i] (declaration (nth names i)
                                                                   (mapv (fn [[parent _ kind]] {:gate (nth names parent) :relation kind})
                                                                         (filter #(= i (second %)) edges)))) (range 12))
                            expected (loop [seen #{11}]
                                       (let [next-seen (into seen (for [[parent child kind] edges
                                                                        :when (and (contains? seen child) (not= :invalidates kind))] parent))]
                                         (if (= seen next-seen) (set (map #(nth names %) seen)) (recur next-seen))))
                            result (plan/schedule definitions ["gate-11"])
                            position (zipmap (:order result) (range))]
                        (and (= expected (set (:selected result)))
                             (every? (fn [[parent child _]] (< (get position (nth names parent)) (get position (nth names child)))) edges))))
        result (tc/quick-check 150 property :seed 20261007)]
    (is (:pass? result) (pr-str result))))
