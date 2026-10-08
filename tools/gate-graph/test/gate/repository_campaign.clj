(ns gate.repository-campaign
  "Attributed repository identity and bounded Git protocol faults in disposable source copies."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def repository-faults
  [{:id "component-boundary-lost" :anchor "(str/starts-with? path (str ancestor \"/\"))"
    :replacement "(str/starts-with? path ancestor)" :test "malli-generated-prefix-boundaries"}
   {:id "protected-overlap-ignored"
    :anchor "(some (fn [omit] (some #(or (beneath? omit %) (beneath? % omit)) protected)) excluded)"
    :replacement "false" :test "exclusions-have-component-boundaries-and-protected-inputs"}
   {:id "membership-rescan-ignored"
    :anchor "(when-not (= before (listing! state root)) (git/refuse! :repository-unstable))"
    :replacement "nil" :test "membership-change-during-observation-refuses"}
   {:id "child-revision-replaced-by-gitlink" :anchor ":revision (:revision child)"
    :replacement ":revision (:object indexed)" :test "actual-child-checkout-and-dirty-content-are-bound"}
   {:id "child-content-not-bound" :anchor ":content (:content child)"
    :replacement ":content (apply str (repeat 64 \"0\"))" :test "actual-child-checkout-and-dirty-content-are-bound"}
   {:id "repository-budget-ignored"
    :anchor "(>= (count @(:repositories state)) (get-in state [:limits :repositories]))"
    :replacement "false" :test "checkout-boundaries-and-repository-budget"}
   {:id "file-depth-budget-ignored" :anchor "(> (count parts) depth-limit)"
    :replacement "false" :test "finite-budgets-and-log-location-refuse"}])

(def git-faults
  [{:id "duplicate-records-collapsed" :anchor "(when-not (= (count paths) (count (set paths))) (refuse! :repository-protocol))"
    :replacement "nil" :test "strict-git-records"}
   {:id "index-conflicts-admitted" :anchor "([0-9a-f]{40}|[0-9a-f]{64}) 0"
    :replacement "([0-9a-f]{40}|[0-9a-f]{64}) [0-3]" :test "strict-git-records"}
   {:id "malformed-utf8-replaced" :anchor "(.onMalformedInput CodingErrorAction/REPORT)"
    :replacement "(.onMalformedInput CodingErrorAction/REPLACE)" :test "malformed-output-timeouts-and-symlink-ancestors"}
   {:id "git-failure-ignored" :anchor "(= 0 (:exit result))"
    :replacement "true" :test "malformed-output-timeouts-and-symlink-ancestors"}])

(def faults
  (vec (concat
        (map #(assoc % :source (if (contains? #{"component-boundary-lost" "protected-overlap-ignored"} (:id %))
                                 "src/gate/repository_identity.cljc" "src/gate/repository.clj")
                     :namespace "gate.repository-test" :control "strict-git-records") repository-faults)
        (map #(assoc % :source "src/gate/repository_git.clj" :namespace "gate.repository-test"
                     :control "exclusions-have-component-boundaries-and-protected-inputs") git-faults))))

(defn -main
  "Freeze module sources, run full baselines and require attributed failures with passing controls."
  [output-parent]
  (binding [campaign/*scope* :repository campaign/*faults* faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
