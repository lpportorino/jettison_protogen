(ns gate.demo
  "Runnable public synthetic example; no repository or machine discovery."
  (:require [gate.fixtures :as fixtures]
            [gate.query :as query]
            [malli.core :as m]))

(defn -main
  "Print a bounded child query over the synthetic execution graph."
  [& _]
  (prn (query/page (query/prepare (fixtures/example))
                   {:select {:op :children :anchor "root"}
                    :budget {:visits 100 :rows 10 :bytes 4096}})))
(m/=> -main [:=> [:cat [:* :string]] :nil])
