(ns gate.plan
  "Validate the whole declaration DAG, then derive deterministic entrypoint prerequisite closure."
  (:require [gate.contract :as c]
            [gate.run-contract :as r]
            [malli.core :as m]))

(defn refuse!
  "Return stable structured identity diagnostics without commands or environment values."
  [code subject related]
  (throw (ex-info "Gate plan refused" {:code code :subject subject :related related})))
(m/=> refuse! [:=> [:cat (second (nth r/Failure 2)) [:maybe c/Id] [:maybe c/Id]] :nil])

(defn unique?
  "Check identity/membership declarations without collapsing duplicates silently."
  [values]
  (= (count values) (count (set values))))
(m/=> unique? [:=> [:cat [:sequential {:max 1000000} :string]] :boolean])

(defn- validate-gate!
  "Disallow duplicate policy terms and vacuous executable names before scheduling."
  [gate]
  (when (or (= "" (first (:command gate)))
            (not (unique? (map :id (:inputs gate))))
            (not (unique? (:outputs gate)))
            (not (unique? (:environment gate)))
            (not (unique? (:toolchains gate))))
    (refuse! :invalid-gate-definition (:id gate) nil))
  (when-not (= (count (:dependencies gate)) (count (set (:dependencies gate))))
    (refuse! :duplicate-dependency (:id gate) nil)))
(m/=> validate-gate! [:=> [:cat r/Gate] :nil])

(defn- topological-order
  "Kahn traversal with a sorted ready set; all dependency kinds participate in cycle checks."
  [index]
  (let [prerequisites (into {} (map (fn [[id gate]] [id (set (map :gate (:dependencies gate)))]) index))
        children (reduce-kv (fn [result child deps]
                              (reduce #(update %1 %2 (fnil conj #{}) child) result deps)) {} prerequisites)
        counts (into {} (map (fn [[id deps]] [id (count deps)]) prerequisites))]
    (loop [ready (into (sorted-set) (keep (fn [[id n]] (when (zero? n) id)) counts))
           remaining counts result []]
      (if-let [id (first ready)]
        (let [[updated next-ready]
              (reduce (fn [[pending available] child]
                        (let [n (dec (get pending child))]
                          [(assoc pending child n) (cond-> available (zero? n) (conj child))]))
                      [(dissoc remaining id) (disj ready id)] (get children id))]
          (recur next-ready updated (conj result id)))
        (if (seq remaining)
          (refuse! :dependency-cycle (first (sort (keys remaining))) nil)
          result)))))
(m/=> topological-order [:=> [:cat r/GateIndex] [:vector {:min 1 :max 10000} c/Id]])

(defn schedule
  "Select execution prerequisites; invalidation-only edges affect keys but do not force execution."
  [gates roots]
  (when-not (unique? (map :id gates)) (refuse! :duplicate-gate nil nil))
  (doseq [gate gates] (validate-gate! gate))
  (let [index (into {} (map (juxt :id identity) gates))]
    (doseq [gate gates dependency (:dependencies gate)]
      (when-not (contains? index (:gate dependency))
        (refuse! :missing-dependency (:id gate) (:gate dependency))))
    (when (empty? roots) (refuse! :empty-selection nil nil))
    (doseq [id roots] (when-not (contains? index id) (refuse! :unknown-root id nil)))
    (let [order (topological-order index)
          selected (loop [pending (vec roots) seen #{}]
                     (if-let [id (peek pending)]
                       (if (contains? seen id) (recur (pop pending) seen)
                           (recur (into (pop pending)
                                        (comp (remove #(= :invalidates (:relation %))) (map :gate))
                                        (:dependencies (get index id)))
                                  (conj seen id)))
                       seen))]
      {:order order :selected (filterv selected order) :deselected (filterv #(not (contains? selected %)) order)})))
(m/=> schedule [:=> [:cat r/Gates [:vector {:max 10000} c/Id]] r/Schedule])
