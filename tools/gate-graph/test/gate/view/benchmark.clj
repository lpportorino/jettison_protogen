(ns gate.view.benchmark
  "Finite synthetic preparation and warm-page observations; no universal timing promises."
  (:require [gate.view.fixtures :as fixtures]
            [gate.view.model :as view]
            [gate.viewer-asset :as asset]))

(defn -main
  "Write bounded public-safe measurements with workload, artifact and bundle identities."
  [output]
  (let [records (for [[label value] [["dense" (fixtures/dense)] ["signals" (fixtures/signals)]]]
                  (let [start (System/nanoTime) context (view/prepare value) prepared (System/nanoTime)
                        request {:select {:op :search :text "check"} :budget {:visits 128 :rows 16 :bytes 8192}}
                        result (loop [i 0 total 0]
                                 (if (= i 100) total (recur (inc i) (+ total (:visited (view/page context request))))))
                        end (System/nanoTime)]
                    {:fixture label :artifact (get-in context [:query :artifact])
                     :nodes (count (:nodes value)) :edges (count (:edges value)) :measurements (count (:measurements value))
                     :prepare-ns (str (- prepared start)) :warm-100-pages-ns (str (- end prepared))
                     :visited result :budget (:budget request) :instrumented? false}))]
    (spit output (pr-str {:bundle (:digest (asset/load!)) :jvm (System/getProperty "java.version") :records (vec records)})))
  (shutdown-agents))
