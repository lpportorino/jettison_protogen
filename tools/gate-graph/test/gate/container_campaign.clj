(ns gate.container-campaign
  "Real Docker faults against isolated disposable module copies and independent cleanup."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  [{:id "jobserver-capability-lost"
    :anchor "(not (re-find #\"--jobserver-(?:auth|fds)=\" (or (get-in evidence [:environment \"MAKEFLAGS\"]) \"\")))"
    :replacement "true" :test "inherited-jobserver-capabilities-refuse-before-container-creation"}
   {:id "writable-input-mount" :anchor ",target=/gate,readonly,bind-recursive=disabled"
    :replacement ",target=/gate,bind-recursive=disabled"
    :test "real-container-enforces-input-network-and-environment-profile"}
   {:id "network-enabled" :anchor "\"--network\" \"none\"" :replacement "\"--network\" \"bridge\""
    :test "real-container-enforces-input-network-and-environment-profile"}
   {:id "image-environment-inherited" :anchor "[(str \"sha256:\" image) \"-i\"]" :replacement "[(str \"sha256:\" image) \"--\"]"
    :test "real-container-enforces-input-network-and-environment-profile"}
   {:id "removal-only-inspects" :anchor "[\"rm\" \"--force\" \"--volumes\" container-name]"
    :replacement "[\"inspect\" \"--format\" \"{{.Id}}\" container-name]"
    :test "cancellation-removes-container-with-detached-child"}])

(defn -main
  "Run with the same local-daemon test controller as container acceptance; environment names its owned scratch."
  [output-parent]
  (binding [campaign/*scope* :container
            campaign/*mutation-source* "src/gate/container.clj"
            campaign/*test-namespace* "gate.container-test"
            campaign/*control* "mismatched-runtime-policy-refuses-before-container-creation"
            campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
