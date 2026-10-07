(ns gate.runtime-campaign
  "Attributed cache-runtime and cross-process ownership faults with independent clean controls."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def content-faults
  [{:id "empty-classpath-refused" :anchor ":required? (not directory?)" :replacement ":required? true"
    :test "existing-empty-classpath-directories-have-content-identity"}
   {:id "classpath-order-erased" :anchor "[resolved (mapv" :replacement "[paths (vec (sort paths)) resolved (mapv"
    :test "classpath-order-membership-and-total-byte-budget-are-effective"}
   {:id "controller-content-ignored" :anchor "(terms-digest [(terms-digest resolved) encoded])"
    :replacement "(terms-digest [(terms-digest resolved)])" :test "runtime-content-is-not-a-size-or-mtime-memo"}])
(def admission-faults
  [{:id "unavailable-runtime-attested" :anchor ":isolation-verified? (= :verified (:status before))"
    :replacement ":isolation-verified? true" :test "unavailable-runtime-evidence-executes-without-a-reuse-attestation"}
   {:id "changed-runtime-publishes-artifacts" :anchor "(= (:identity before) (:identity @after))"
    :replacement "true" :test "runtime-change-during-coverage-refuses-artifacts-and-receipts"}
   {:id "runtime-key-constant" :anchor "(canonical/sha256 (canonical/encode (:identity observation) 65536))"
    :replacement "(canonical/sha256 \"constant-runtime\")" :test "runtime-policy-identity-is-part-of-cache-material"}])
(def ownership-faults
  [{:id "independent-thread-mutexes" :anchor "(get @pool key {:mutex (ReentrantLock.) :users 0})"
    :replacement "{:mutex (ReentrantLock.) :users (get-in @pool [key :users] 0)}"
    :test "same-root-contends-and-distinct-root-remains-independent"}
   {:id "os-lock-released-before-work" :anchor "{:key key :mutex mutex :channel @channel :lock file-lock}"
    :replacement "{:key key :mutex mutex :channel @channel :lock (do (.release ^FileLock file-lock) file-lock)}"
    :test "independent-jvm-cannot-acquire-a-held-output-root"}])

(def engine-faults
  [{:id "clock-source-omitted" :anchor "\"clock.clj\" " :replacement ""
    :test "every-shipped-jvm-source-changes-the-runtime-profile-key"}
   {:id "verdict-source-omitted" :anchor " \"verdict.cljc\"" :replacement ""
    :test "every-shipped-jvm-source-changes-the-runtime-profile-key"}
   {:id "engine-source-budget-ignored" :anchor "(> (alength source-bytes) 1048576)" :replacement "false"
    :test "engine-source-absence-and-byte-budget-fail-closed"}])

(defn -main
  "Require landed single faults, assertion failures, passing controls and unchanged full-suite baselines."
  [output-parent]
  (binding [campaign/*scope* :engine-identity campaign/*mutation-source* "src/gate/container.clj"
            campaign/*test-namespace* "gate.snapshot-test"
            campaign/*control* "engine-source-identity-hashes-bytes-before-decoding"
            campaign/*faults* engine-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :runtime campaign/*mutation-source* "src/gate/runtime.clj"
            campaign/*test-namespace* "gate.runtime-test"
            campaign/*control* "unavailable-runtime-evidence-executes-without-a-reuse-attestation"
            campaign/*faults* content-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :runtime-admission campaign/*mutation-source* "src/gate/contained.clj"
            campaign/*test-namespace* "gate.runtime-test"
            campaign/*control* "runtime-content-is-not-a-size-or-mtime-memo"
            campaign/*faults* admission-faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :output-ownership campaign/*mutation-source* "src/gate/ownership.clj"
            campaign/*test-namespace* "gate.ownership-test"
            campaign/*control* "cancelled-wait-does-not-retain-a-jvm-mutex"
            campaign/*faults* ownership-faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
