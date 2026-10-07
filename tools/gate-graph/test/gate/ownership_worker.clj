(ns gate.ownership-worker
  "Separate JVM witness for OS-level output claim contention."
  (:require [gate.ownership :as ownership]))

(defn -main [root output]
  (spit (str root "/worker-started") "started")
  (let [lease (ownership/acquire! root [output] 200 (atom false))]
    (try (spit (str root "/worker-acquired") "acquired")
         (finally (ownership/release! lease)))))
