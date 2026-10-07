(ns gate.contained-test
  "Completion boundary tests with explicit synthetic execution evidence; Docker acceptance is separate."
  (:require [clojure.test :refer [deftest is]]
            [gate.contained :as contained]
            [gate.container :as container]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.process :as process]
            [gate.run-test :as fixture]
            [gate.trace-test :refer [with-directory]]))

(defn request [root]
  {:directory (str root) :scratch-parent (str root) :docker "/fixture/docker" :socket "/fixture/socket"
   :image fixture/digest-a :gate fixture/gate :evidence files/evidence :input-limits inputs/default-limits
   :process-limits process/default-limits :limits container/default-limits :output-roots ["out"]})
(def execution
  {:schema/version 1 :status :exited :exit 0 :pid "1" :elapsed-ns "1" :containment :none
   :cleanup-required? false :observed-processes 1 :observed-processes-stopped? true :log nil})
(defn result [root]
  {:directory (str root "/owned")
   :observation {:schema/version 1 :profile (container/profile fixture/digest-a container/default-limits ["out"])
                 :container fixture/digest-a :snapshot fixture/snapshot :status :exited :reason nil
                 :execution execution :removed? true :outputs fixture/outputs :elapsed-ns "1"}})
(defn setup [root]
  (files/put! root "src/a.clj" "a")
  (files/put! root "out/result.edn" "old")
  (files/put! root "owned/outputs/out/result.edn" "a"))
(defn complete [root evidence]
  (contained/complete! (request root) fixture/snapshot evidence (fn [_ _] fixture/coverage) (atom false)))

(deftest keyed-snapshot-and-live-workspace-must-both-match-executed-inputs
  (with-directory
    (fn [root]
      (setup root)
      (let [other (assoc-in (result root) [:observation :snapshot :files 0 :digest] fixture/digest-b)]
        (is (= :input-unstable (get-in (complete root other) [:work :reason]))
            "A different executed snapshot refuses even when live pre/post bytes match."))
      (files/put! root "src/a.clj" "b")
      (is (= :input-unstable (get-in (complete root (result root)) [:work :reason]))
          "A changed live workspace refuses even when the executed snapshot matches the key.")
      (is (= "old" (slurp (str root "/out/result.edn")))))))

(deftest incomplete-keyed-snapshot-cannot-authorize-publication
  (with-directory
    (fn [root]
      (setup root)
      (let [before (assoc fixture/snapshot :complete? false :reason :missing-input)
            evidence (assoc-in (result root) [:observation :snapshot] before)]
        (with-redefs [inputs/observe! (constantly before)]
          (is (= :input-unstable
                 (get-in (contained/complete! (request root) before evidence (fn [_ _] fixture/coverage) (atom false)) [:work :reason]))))))))

(deftest successful-execution-needs-coverage-and-verified-output-publication
  (with-directory
    (fn [root]
      (setup root)
      (let [evidence (result root)
            vacuous (contained/complete! (request root) fixture/snapshot evidence (fn [_ _] nil) (atom false))]
        (is (= :coverage-mismatch (get-in vacuous [:work :reason])))
        (is (= :not-attempted (get-in vacuous [:publication :status])))
        (is (= "old" (slurp (str root "/out/result.edn"))))
        (files/put! root "owned/outputs/out/result.edn" "different")
        (is (= :output-publication (get-in (complete root evidence) [:work :reason])))
        (is (= "old" (slurp (str root "/out/result.edn"))))
        (files/put! root "owned/outputs/out/result.edn" "a")
        (is (= {:work {:outcome :passed :coverage fixture/coverage :reason nil}
                :publication {:status :installed :installed ["out/result.edn"] :reason nil}}
               (complete root evidence)))
        (is (= "a" (slurp (str root "/out/result.edn"))))))))

(deftest failed-or-unremoved-container-never-asks-for-coverage-or-publishes
  (doseq [[path value expected] [[[:observation :removed?] false :error]
                                 [[:observation :container] nil :error]
                                 [[:observation :status] :cleanup-unknown :error]
                                 [[:observation :execution :exit] 7 :failed]
                                 [[:observation :execution :cleanup-required?] true :error]]]
    (with-directory
      (fn [root]
        (setup root)
        (let [called (atom false)
              completion (contained/complete! (request root) fixture/snapshot (assoc-in (result root) path value)
                                              (fn [_ _] (reset! called true) fixture/coverage) (atom false))]
          (is (= expected (get-in completion [:work :outcome])))
          (is (false? @called))
          (is (= :not-attempted (get-in completion [:publication :status])))
          (is (= "old" (slurp (str root "/out/result.edn")))))))))

(deftest cancellation-from-coverage-observer-prevents-output-publication
  (with-directory
    (fn [root]
      (setup root)
      (let [token (atom false)
            completion (contained/complete! (request root) fixture/snapshot (result root)
                                            (fn [_ _] (reset! token true) fixture/coverage) token)]
        (is (= :cancelled (get-in completion [:work :outcome])))
        (is (= :not-attempted (get-in completion [:publication :status])))
        (is (= "old" (slurp (str root "/out/result.edn"))))))))
