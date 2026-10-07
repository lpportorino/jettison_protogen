(ns gate.enrollment-canary
  "Regular gate canary: remove the coverage guard in a disposable copy and require an attributed failure."
  (:require [gate.admission-campaign :as campaign]
            [gate.coordinator-campaign :as coordinator-campaign]
            [malli.core :as m]))

(defn -main
  "Run full passing baselines around one coverage fault and an independent passing control.
   The campaign checks exact mutation bytes, assertion failures with zero errors, source
   fingerprints and closed EDN results. No tracked file is changed. This selected canary
   does not replace the broader manual mutation campaigns or prove whole-module coverage."
  [output-parent]
  (binding [campaign/*scope* :adapter-verdict
            campaign/*mutation-source* "src/gate/verdict.cljc"
            campaign/*test-namespace* "gate.coordinator-test"
            campaign/*control* "cached-coverage-is-historical-and-still-satisfies-a-prerequisite"
            campaign/*faults* coordinator-campaign/verdict-faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
