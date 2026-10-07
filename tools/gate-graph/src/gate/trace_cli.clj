(ns gate.trace-cli
  "Local import command: caller supplies expected gate names/keys independently of the journal."
  (:require [gate.canonical :as canonical]
            [gate.report :as report]
            [gate.trace-contract :as t]
            [gate.trace-import :as importer]
            [gate.trace-io :as trace-io]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn -main
  "Import JOURNAL NEW-OUTPUT-DIR RELEASE-JS NAME KEY [NAME KEY ...]; print machine-readable EDN."
  [& args]
  (when-not (and (>= (count args) 5) (odd? (count args)))
    (throw (ex-info "Expected JOURNAL NEW-OUTPUT-DIR RELEASE-JS NAME KEY [NAME KEY ...]"
                    {:code :trace-cli-arguments})))
  (let [[root destination bundle-path & declarations] args
        inventory (mapv (fn [[label task-key]] {:name label :key task-key}) (partition 2 declarations))]
    (when-not (m/validate t/Inventory inventory)
      (trace-io/refuse! :trace-inventory "inventory" []))
    (let [graph (importer/project (trace-io/load-journal root) inventory)
          edn (canonical/encode graph 134217728)
          bundle-file (Path/of bundle-path (make-array String 0))]
      (when (> (Files/size bundle-file) 16777216)
        (throw (ex-info "Viewer bundle exceeds size limit" {:code :invalid-viewer-bundle})))
      (let [html (report/render graph (slurp bundle-path :encoding "UTF-8"))
            output (Files/createDirectory (Path/of destination (make-array String 0)) (make-array FileAttribute 0))]
        (spit (str (.resolve output "graph.edn")) (str edn "\n") :encoding "UTF-8")
        (spit (str (.resolve output "report.html")) html :encoding "UTF-8")
        (prn {:status :written :artifact (canonical/sha256 edn) :complete? (get-in graph [:run :complete?])
              :nodes (count (:nodes graph)) :measurements (count (:measurements graph))}))))
  (shutdown-agents))
(m/=> -main [:=> [:cat [:* :string]] :nil])
