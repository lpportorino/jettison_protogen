(ns gate.run-lens-contract
  "Closed optional projections of one verified archive; full records remain separately navigable."
  (:require [gate.archive-contract :as archive-contract]
            [gate.contract :as graph-contract]
            [gate.inspection-contract :as contract]
            [gate.record-view-contract :as record]
            [gate.repository-contract :as repository-contract]))

(def digest-budget
  [:map {:closed true} [:top [:int {:min 1 :max 100}]] [:bytes [:int {:min 1024 :max 65536}]]])
(def digest-failure [:map {:closed true} [:code [:= :invalid-digest-budget]]])
(def observation-counts-schema
  [:map {:closed true} [:observations [:int {:min 0 :max 128000}]]
   [:unique-keys [:int {:min 0 :max 128000}]]
   [:executions [:int {:min 0 :max 128000}]] [:decisions [:int {:min 0 :max 128000}]]
   [:outcomes contract/OutcomeCounts]])
(def acquisition-summary-schema
  [:multi {:dispatch :state}
   [:observed [:map {:closed true} [:state [:= :observed]]
               [:profile [:= :git-visible-posix-v1]] [:content graph-contract/Digest]
               [:repository-count [:int {:min 1 :max 128}]]
               [:root [:maybe archive-contract/Repository]]]]
   [:unavailable [:map {:closed true} [:state [:= :unavailable]]
                  [:failure repository-contract/Failure]]]])
(def run-summary-schema
  (into graph-contract/Run [[:elapsed-ns [:maybe graph-contract/Natural]] [:elapsed-basis [:= :run-relative-end]]]))
;; The run's potentially large declaration vector is replaced by counts below.
(def compact-run-schema
  (into [:map {:closed true}]
        (remove #(= :expected-keys (first %))) (drop 2 run-summary-schema)))
(def gate-duration-schema
  [:map {:closed true} [:id graph-contract/Id] [:key graph-contract/Id]
   [:label graph-contract/Label] [:attempt graph-contract/Attempt]
   [:outcome graph-contract/ExecutionOutcome]
   [:start-ns graph-contract/Natural] [:end-ns graph-contract/Natural]
   [:elapsed-ns graph-contract/Natural] [:path record/Path]])
(def measurement-summary-schema
  [:map {:closed true} [:resources [:int {:min 0 :max 128000}]]
   [:observations [:int {:min 0 :max 1000000}]] [:collection [:enum :none :present]]
   [:statuses [:map {:closed true}
               [:measured [:int {:min 0 :max 1000000}]] [:partial [:int {:min 0 :max 1000000}]]
               [:estimated [:int {:min 0 :max 1000000}]] [:unavailable [:int {:min 0 :max 1000000}]]]]
   [:cpu [:= :unknown]] [:critical-path [:= :unknown]] [:task-attribution [:= :unknown]]])
(def producer-counts-schema
  [:map {:closed true} [:expected-keys [:int {:min 1 :max 128000}]]
   [:observed-expected-keys [:int {:min 0 :max 128000}]]
   [:missing-expected-keys [:int {:min 0 :max 128000}]]
   [:other-observed-keys [:int {:min 0 :max 128000}]]])
(def ranked-gates-schema
  [:map {:closed true} [:basis [:= :closed-gate-execution-intervals]]
   [:closed [:int {:min 0 :max 128000}]] [:open [:int {:min 0 :max 128000}]]
   [:omitted [:int {:min 0 :max 128000}]] [:rows [:vector {:max 100} gate-duration-schema]]])
(def attention-row
  [:map {:closed true} [:id graph-contract/Id] [:key graph-contract/Id]
   [:label graph-contract/Label] [:kind graph-contract/NodeKind] [:record [:enum :execution :decision]]
   [:outcome [:or graph-contract/ExecutionOutcome graph-contract/DecisionOutcome]] [:path record/Path]])
(def attention-summary
  [:map {:closed true} [:observations [:int {:min 0 :max 128000}]]
   [:gates [:int {:min 0 :max 128000}]] [:omitted [:int {:min 0 :max 128000}]]
   [:rows [:vector {:max 100} attention-row]]])
(def section-summary
  [:map {:closed true} [:path record/Path] [:kind [:enum :map :vector :leaf]]
   [:children [:int {:min 0 :max 1000000}]]])
(def digest-result
  [:map {:closed true} [:artifact graph-contract/Digest] [:graph-artifact graph-contract/Digest]
   [:scope archive-contract/Scope] [:status archive-contract/Status] [:run compact-run-schema]
   [:provenance [:map {:closed true} [:stability archive-contract/Stability]
                 [:before acquisition-summary-schema] [:after acquisition-summary-schema]]]
   [:producer producer-counts-schema]
   [:observations observation-counts-schema] [:gates observation-counts-schema]
   [:longest-gates ranked-gates-schema]
   [:measurements measurement-summary-schema]
   [:attention attention-summary] [:sections [:vector {:max 16} section-summary]]])

(def Encodable [:or digest-budget digest-result digest-failure])
(def registry {::digest-budget digest-budget ::digest-result digest-result ::digest-failure digest-failure})
