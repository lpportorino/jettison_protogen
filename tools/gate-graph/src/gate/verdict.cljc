(ns gate.verdict
  "Portable admission of adapter verdicts; passing summaries need matching nonvacuous coverage."
  (:require [gate.run-contract :as r]
            [malli.core :as m]))

(defn- failure
  "Return compact failed admission data without rejected values or exception strings."
  [reason]
  {:outcome :error :coverage nil :reason reason})
(m/=> failure [:=> [:cat [:enum :invalid-adapter-result :coverage-mismatch]] r/WorkResult])

(defn judge
  "Admit an untrusted adapter return against the exact declaration and outcome/reason semantics.
   Passed and cached results require declared/observed inventory equality and minimum work. Cached
   coverage is historical. This checks verdict structure and binding, not acquisition truth or isolation."
  [gate result]
  (cond
    (not (m/validate r/WorkResult result)) (failure :invalid-adapter-result)
    (and (contains? #{:passed :cached} (:outcome result))
         (not (and (= (get-in gate [:coverage :expected])
                      (get-in result [:coverage :expected]) (get-in result [:coverage :observed]))
                   (>= (get-in result [:coverage :count] -1) (get-in gate [:coverage :minimum])))))
    (failure :coverage-mismatch)
    (not (case (:outcome result)
           :passed (nil? (:reason result))
           :cached (= :cache-hit (:reason result))
           :cancelled (= :cancellation-requested (:reason result))
           :failed (= :command-failed (:reason result))
           :error (contains? #{:adapter-exception :invalid-adapter-result :coverage-mismatch :input-unstable
                               :output-publication :runtime-unstable :test-observation-error} (:reason result))))
    (failure :invalid-adapter-result)
    :else result))
(m/=> judge [:=> [:cat r/Gate [:fn (constantly true)]] r/WorkResult])

(defn process-result
  "Judge process termination and an independently supplied work witness with the same portable rules.
   A launched process, exit zero and elapsed time do not themselves prove test or check coverage.
   Timeout, output/handle limits and incomplete cleanup remain errors, with detail in the observation."
  [gate observation coverage]
  (judge
   gate
   (cond
     (= :cancelled (:status observation)) {:outcome :cancelled :coverage nil :reason :cancellation-requested}
     (or (not= :exited (:status observation)) (nil? (:pid observation))
         (:cleanup-required? observation) (not (:observed-processes-stopped? observation)))
     {:outcome :error :coverage nil :reason :adapter-exception}
     (not= 0 (:exit observation)) {:outcome :failed :coverage nil :reason :command-failed}
     :else {:outcome :passed :coverage coverage :reason nil})))
(m/=> process-result [:=> [:cat r/Gate r/ProcessObservation [:maybe r/Coverage]] r/WorkResult])
