(ns gate.cache
  "Pure content keys and pass-receipt admission. Filesystem observation and atomic storage are adapters."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.plan :as plan]
            [gate.run-contract :as r]
            [malli.core :as m]))

(defn normalize-files
  "Canonical path ordering preserves content and executable mode, never timestamps."
  [files]
  (vec (sort-by :path files)))
(m/=> normalize-files [:=> [:cat r/Files] r/Files])

(defn- normalize-gate
  "Sort unordered declaration terms; argument order stays semantically significant."
  [gate]
  (-> gate
      (update :inputs #(mapv (fn [input] (if (seq (:exclude input)) (update input :exclude (comp vec sort)) (dissoc input :exclude)))
                             (sort-by :id %)))
      (update :outputs #(vec (sort %)))
      (update :environment #(vec (sort %)))
      (update :toolchains #(vec (sort %)))
      (update :dependencies #(vec (sort-by (juxt :gate :relation) %)))))
(m/=> normalize-gate [:=> [:cat r/Gate] r/Gate])

(defn- normalize-snapshot
  "Hash explicit membership, including empty optional selections and absent environment values."
  [snapshot]
  (-> snapshot
      (update :files normalize-files)
      (update :membership #(mapv (fn [entry] (update entry :paths (fn [paths] (vec (sort paths)))))
                                 (sort-by :selector %)))
      (update :environment #(vec (sort-by :name %)))
      (update :toolchains #(vec (sort-by :name %)))))
(m/=> normalize-snapshot [:=> [:cat r/Snapshot] r/Snapshot])

(defn- validate-snapshot!
  "A complete observation must cover every declared selector, environment name and toolchain."
  [gate snapshot]
  (let [files (map :path (:files snapshot))
        members (map :selector (:membership snapshot))
        by-selector (into {} (map (juxt :selector :paths) (:membership snapshot)))
        environment (map :name (:environment snapshot))
        toolchains (map :name (:toolchains snapshot))]
    (when (or (not= (:complete? snapshot) (nil? (:reason snapshot)))
              (not (every? plan/unique? [files members environment toolchains]))
              (some #(not (plan/unique? (:paths %))) (:membership snapshot))
              (and (:complete? snapshot)
                   (or (not= (set members) (set (map :id (:inputs gate))))
                       (not= (set environment) (set (:environment gate)))
                       (not= (set toolchains) (set (:toolchains gate)))
                       (not= (set files) (set (mapcat :paths (:membership snapshot))))
                       (some (fn [{:keys [id kind path required?]}]
                               (let [paths (get by-selector id)]
                                 (or (and required? (empty? paths))
                                     (and (= :file kind) (some #(not= path %) paths))
                                     (and (= :tree kind) (some #(not (str/starts-with? % (str path "/"))) paths)))))
                             (:inputs gate)))))
      (plan/refuse! :invalid-snapshot (:id gate) nil))))
(m/=> validate-snapshot! [:=> [:cat r/Gate r/Snapshot] :nil])

(defn validate-dependencies!
  "Key evidence is exactly the invalidating declaration set; ordering-only work cannot taint a key."
  [gate dependencies]
  (let [identity-of (juxt :gate :relation)
        declared (set (map identity-of (remove #(= :after (:relation %)) (:dependencies gate))))
        observed (map identity-of dependencies)]
    (when (or (not= (count observed) (count (set observed))) (not= declared (set observed))
              (some #(and (= :invalidates (:relation %)) (:result %)) dependencies))
      (plan/refuse! :invalid-dependency-terms (:id gate) nil))))
(m/=> validate-dependencies! [:=> [:cat r/Gate r/DependencyTerms] :nil])

(defn input-key
  "Return an exact key, or nil for unresolved observations/dependencies; nil is never reusable."
  [gate snapshot dependencies]
  (validate-snapshot! gate snapshot)
  (validate-dependencies! gate dependencies)
  (when (and (:complete? snapshot)
             (every? #(and (:key %) (or (= :invalidates (:relation %)) (:result %))) dependencies))
    (canonical/sha256
     (canonical/encode {:schema/version 1 :gate (dissoc (normalize-gate gate) :label) :snapshot (normalize-snapshot snapshot)
                        :dependencies (vec (sort-by (juxt :gate :relation) dependencies))} 134217728))))
(m/=> input-key [:=> [:cat r/Gate r/Snapshot r/DependencyTerms] [:maybe c/Digest]])

(defn result-identity
  "Stable successful work identity excludes occurrence IDs, duration and cache-hit history."
  [coverage outputs]
  (canonical/sha256 (canonical/encode {:schema/version 1 :status :passed :coverage coverage
                                       :outputs (normalize-files outputs)} 134217728)))
(m/=> result-identity [:=> [:cat r/Coverage r/Files] c/Digest])

(defn- covered?
  "A count alone cannot certify the expected work inventory."
  [gate coverage]
  (and (= (get-in gate [:coverage :expected]) (:expected coverage) (:observed coverage))
       (>= (:count coverage) (get-in gate [:coverage :minimum]))))
(m/=> covered? [:=> [:cat r/Gate r/Coverage] :boolean])

(defn- outputs-complete?
  "Outputs must exist at exactly their declared paths; duplicates cannot stand in for missing files."
  [gate outputs]
  (and (plan/unique? (map :path outputs)) (= (set (:outputs gate)) (set (map :path outputs)))))
(m/=> outputs-complete? [:=> [:cat r/Gate r/Files] :boolean])

(defn decide
  "Classify current evidence without reading files or executing work. A hit requires every proof."
  [gate snapshot dependencies receipt outputs force?]
  (let [input-digest (input-key gate snapshot dependencies)
        reason (cond
                 force? :forced
                 (= :always (:cache gate)) :always-run
                 (= :allowed (:network gate)) :network-unbounded
                 (not (:complete? snapshot)) :incomplete-snapshot
                 (nil? input-digest) :dependency-unknown
                 (nil? receipt) :missing-receipt
                 (or (not= (:id gate) (:gate receipt))
                     (not= (:result receipt) (result-identity (:coverage receipt) (:outputs receipt)))) :invalid-receipt
                 (not= input-digest (:key receipt)) :input-changed
                 (not (covered? gate (:coverage receipt))) :coverage-mismatch
                 (or (not (outputs-complete? gate outputs))
                     (not= (normalize-files outputs) (normalize-files (:outputs receipt)))) :outputs-changed
                 :else :cache-hit)]
    {:gate (:id gate) :action (if (= :cache-hit reason) :cached :run) :reason reason :key input-digest}))
(m/=> decide [:=> [:cat r/Gate r/Snapshot r/DependencyTerms [:maybe r/Receipt] r/Files :boolean] r/Decision])

(defn admit
  "Earn a pass receipt only after stable pre/post evidence and independently supplied work coverage."
  [gate before after dependencies-before dependencies-after outcome coverage outputs run-id attempt-id]
  (let [before-key (input-key gate before dependencies-before)
        after-key (input-key gate after dependencies-after)
        reason (cond
                 (not= :passed outcome) :failed-execution
                 (= :always (:cache gate)) :always-run
                 (= :allowed (:network gate)) :network-unbounded
                 (or (not (:complete? before)) (not (:complete? after))) :incomplete-snapshot
                 (or (nil? before-key) (nil? after-key)) :dependency-unknown
                 (not= before-key after-key) :input-unstable
                 (not (covered? gate coverage)) :coverage-mismatch
                 (not (outputs-complete? gate outputs)) :outputs-changed)]
    (if reason {:status :refused :reason reason}
        {:status :recorded
         :receipt {:schema/version 1 :gate (:id gate) :key before-key :run run-id :attempt attempt-id
                   :status :passed :coverage coverage :outputs (normalize-files outputs)
                   :result (result-identity coverage outputs)}})))
(m/=> admit [:=> [:cat r/Gate r/Snapshot r/Snapshot r/DependencyTerms r/DependencyTerms
                  [:enum :passed :failed :error :cancelled] r/Coverage r/Files c/Id c/Id] r/Admission])
