(ns gate.extension-view
  "Bounded structural pages over a verified pair; continuation hashes bind content, not authentication."
  (:require [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.extension :as extension]
            [gate.extension-contract :as ec]
            [gate.portable :as p]
            [malli.core :as m]))

(defn- locate
  "Resolve an existing path only; the sealed sidecar has already passed complete contract validation."
  [pair path]
  (reduce (fn [value step]
            (cond
              (and (map? value) (keyword? step) (contains? value step)) (get value step)
              (and (vector? value) (integer? step) (<= 0 step) (< step (count value))) (nth value step)
              :else (ec/refuse! :extension-path))) (:sidecar pair) path))
(m/=> locate [:=> [:cat extension/Pair ec/Path] p/Value])

(defn- describe
  "Return a container's size or an exact scalar without copying unvisited descendants."
  [pair path]
  (let [value (locate pair path)]
    (cond (map? value) {:path path :kind :map :children (count value)}
          (vector? value) {:path path :kind :vector :children (count value)}
          :else {:path path :kind :leaf :value value})))
(m/=> describe [:=> [:cat extension/Pair ec/Path] ec/Descriptor])

(defn- cursor
  "Bind both artifacts, selection and frontier; callers must still supply the verified pair."
  [pair selection offset]
  (let [value {:core/artifact (get-in pair [:core :artifact]) :artifact (get-in pair [:sidecar :artifact])
               :selection selection :offset offset}]
    (assoc value :position (canonical/sha256 (canonical/encode-portable value 16384)))))
(m/=> cursor [:=> [:cat extension/Pair c/Digest [:int {:min 0 :max 1000000}]] ec/Cursor])

(defn- fits?
  "Judge the entire encoded envelope, including continuation, before retaining another row."
  [value limit]
  (try (canonical/encode-portable value limit) true
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
         (if (= :encoded-byte-limit (:code (ex-data error))) false (throw error)))))
(m/=> fits? [:=> [:cat ec/Page [:int {:min 1024 :max 65536}]] :boolean])

(defn page
  "Inspect every admitted field through bounded pages; byte-stopped rows remain at the same frontier."
  [pair request]
  (extension/require-pair! pair)
  (p/require-bounded! request)
  (when-not (m/validate ec/Request request) (ec/refuse! :extension-request))
  (let [path (:path request) value (locate pair path)
        steps (cond (map? value) (vec (sort-by str (keys value))) (vector? value) nil :else [])
        total (if (vector? value) (count value) (count steps))
        selection (canonical/sha256 (canonical/encode-portable path 32768))
        start (or (get-in request [:cursor :offset]) 0)
        _ (when (and (:cursor request)
                     (or (> start total) (not= (:cursor request) (cursor pair selection start))))
            (ec/refuse! :extension-cursor))
        {:keys [rows visits] byte-budget :bytes} (:budget request)
        result (fn [offset found visited stop]
                 {:core/artifact (get-in pair [:core :artifact]) :artifact (get-in pair [:sidecar :artifact])
                  :container (describe pair path) :start start :end offset :visited visited :rows found :stop stop
                  :next (when-not (= stop :complete) (cursor pair selection offset))})
        answer (loop [offset start found [] visited 0]
                 (cond (= offset total) (result offset found visited :complete)
                       (= (count found) rows) (result offset found visited :row-limit)
                       (= visited visits) (result offset found visited :visit-limit)
                       :else (let [row (describe pair (conj path (if (vector? value) offset (nth steps offset))))
                                   next-offset (inc offset)
                                   candidate (result next-offset (conj found row) (inc visited)
                                                     (if (= next-offset total) :complete :byte-limit))]
                               (if (fits? (if (= next-offset total) candidate (assoc candidate :visited 1000000)) byte-budget)
                                 (recur next-offset (conj found row) (inc visited))
                                 (result offset found (inc visited) :byte-limit)))))]
    (canonical/encode-portable answer byte-budget)
    answer))
(m/=> page [:=> [:cat extension/PairInput p/Input] ec/Page])
