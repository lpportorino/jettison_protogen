(ns gate.coordinator-campaign
  "Manual attributed faults for coordinator outcome, concurrency, coverage and cancellation."
  (:require [gate.admission-campaign :as campaign]
            [malli.core :as m]))

(def faults
  "Each fault names a behavior-level test and uses an unaffected success control."
  [{:id "assertion-false-pass"
    :anchor "(catch Throwable _ (failure :adapter-exception))"
    :replacement "(catch Throwable _ {:outcome :passed :coverage nil :reason nil})"
    :test "assertions-exceptions-and-invalid-coverage-cannot-be-success"}
   {:id "lost-parallelism" :anchor "(Executors/newFixedThreadPool (:jobs options))"
    :replacement "(Executors/newFixedThreadPool 1)"
    :test "worker-limit-is-enforced-before-submission"}
   {:id "lost-resource-exclusion"
    :anchor "(not-any? (set (mapcat #(get claims %) (keys active))) (get claims (:id gate)))"
    :replacement "true" :test "exclusive-claims-do-not-serialize-independent-resources"}
   {:id "lost-required-success"
    :anchor "(filter #(contains? #{:requires :produces} (:relation %)))"
    :replacement "(filter #(contains? #{:produces} (:relation %)))"
    :test "hard-prerequisites-block-but-order-only-work-and-independent-work-continue"}
   {:id "ordering-becomes-required-success"
    :anchor "(filter #(contains? #{:requires :produces} (:relation %)))"
    :replacement "(filter #(contains? #{:requires :produces :after} (:relation %)))"
    :test "hard-prerequisites-block-but-order-only-work-and-independent-work-continue"}
   {:id "cancellation-hidden-as-deselection"
    :anchor "(decision id :cancelled [] origin)" :replacement "(decision id :deselected [] origin)"
    :test "cancellation-stops-pending-work-and-waits-for-cooperative-cleanup"}
   {:id "success-mutates-cancellation-token"
    :anchor "(.shutdown executor)"
    :replacement "(do (reset! cancellation true) (.shutdown ^java.util.concurrent.ExecutorService executor))"
    :test "pre-cancelled-work-never-invokes-an-adapter-and-success-does-not-request-cancellation"}])

(def verdict-faults
  "Erase only the nonvacuous coverage guard; the neighboring valid cache result must still pass."
  [{:id "vacuous-success"
    :anchor "(and (contains? #{:passed :cached} (:outcome result))"
    :replacement "(and false"
    :test "assertions-exceptions-and-invalid-coverage-cannot-be-success"}])

(defn -main
  "Run source/test fingerprints, fresh workers, passing baselines and independent controls.
   An error, timeout or missing test is invalid evidence rather than a killed fault."
  [output-parent]
  (binding [campaign/*scope* :coordinator
            campaign/*mutation-source* "src/gate/coordinator.clj"
            campaign/*test-namespace* "gate.coordinator-test"
            campaign/*control* "cached-coverage-is-historical-and-still-satisfies-a-prerequisite"
            campaign/*faults* faults]
    (campaign/-main output-parent))
  (binding [campaign/*scope* :adapter-verdict
            campaign/*mutation-source* "src/gate/verdict.cljc"
            campaign/*test-namespace* "gate.coordinator-test"
            campaign/*control* "cached-coverage-is-historical-and-still-satisfies-a-prerequisite"
            campaign/*faults* verdict-faults]
    (campaign/-main output-parent)))
(m/=> -main [:=> [:cat campaign/PathName] :nil])
