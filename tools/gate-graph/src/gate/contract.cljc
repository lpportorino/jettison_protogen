(ns gate.contract
  "Closed, portable execution-graph contracts. Version 1 is not yet released."
  (:require [malli.core :as m]))

(def Id
  "Stable bounded identifiers; labels and private paths are separate data."
  [:re #"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}(?![\s\S])"])

(def Natural
  "Exact portable nonnegative integers, encoded in canonical decimal form."
  [:and [:string {:min 1 :max 40}] [:re #"^(?:0|[1-9][0-9]*)(?![\s\S])"]])

(def Digest
  "SHA-256 hex content identity; no ambiguous abbreviated identifiers."
  [:re #"^[0-9a-f]{64}(?![\s\S])"])

(def Label [:string {:min 1 :max 512}])
(def NodeKind [:enum :chain :gate :test :step :boundary])
(def ExecutionOutcome [:enum :passed :failed :error :cancelled :running])
(def DecisionOutcome [:enum :cached :deselected :blocked :refused :cancelled])
(def Attempt [:int {:min 1 :max 1000000}])
(def DecisionReason
  [:map {:closed true}
   [:code [:enum :cache-hit :unchanged :prerequisite-failed :unsupported :invalid-input :missing-evidence :cancellation-requested]]
   [:detail [:string {:min 1 :max 2048}]]])
(def Interval [:map {:closed true} [:start-ns Natural] [:end-ns Natural]])
(def OpenInterval
  [:map {:closed true} [:start-ns Natural] [:end-ns [:maybe Natural]]])
(def Source
  [:map {:closed true} [:id Id] [:kind [:enum :capture :declaration :import :receipt]]
   [:digest Digest] [:label Label]])
(def Resource
  [:map {:closed true} [:id Id] [:parent [:maybe Id]] [:source Id]
   [:kind [:enum :host :cgroup :process :thread :gpu :logical]] [:label Label]])
(def NodeBase
  [:map {:closed true} [:id Id] [:key Id] [:label Label] [:parent [:maybe Id]]
   [:source Id] [:kind NodeKind]])
(def Execution
  (into NodeBase
        [[:record [:= :execution]] [:attempt Attempt]
         [:outcome ExecutionOutcome]
         [:interval OpenInterval]]))
(def Decision
  (into NodeBase
        [[:record [:= :decision]] [:at-ns Natural]
         [:outcome DecisionOutcome] [:reason DecisionReason]]))
(def Node [:multi {:dispatch :record} [:execution Execution] [:decision Decision]])
(def Endpoint [:map {:closed true} [:node Id] [:phase [:enum :start :finish :decision]]])
(def Edge
  [:map {:closed true} [:id Id] [:from Endpoint] [:to Endpoint] [:source Id]
   [:kind [:enum :requires :after :spawn :join :produces :flow :invalidates]]
   [:evidence [:enum :observed :declared]]])

(def quantity-registry
  "Units and permitted observation forms; arithmetic never guesses from a label."
  {:cpu-user-ns {:unit :ns :forms #{:counter :delta} :additive? true}
   :cpu-system-ns {:unit :ns :forms #{:counter :delta} :additive? true}
   :instructions {:unit :instruction :forms #{:counter :delta} :additive? true}
   :bytes-read {:unit :byte :forms #{:counter :delta} :additive? true}
   :bytes-written {:unit :byte :forms #{:counter :delta} :additive? true}
   :rss-peak-bytes {:unit :byte :forms #{:peak} :additive? false}
   :memory-current-bytes {:unit :byte :forms #{:gauge} :additive? false}
   :pressure-cpu-some-us {:unit :us :forms #{:counter :delta} :additive? false}
   :pressure-cpu-full-us {:unit :us :forms #{:counter :delta} :additive? false}
   :pressure-memory-some-us {:unit :us :forms #{:counter :delta} :additive? false}
   :pressure-memory-full-us {:unit :us :forms #{:counter :delta} :additive? false}
   :pressure-io-some-us {:unit :us :forms #{:counter :delta} :additive? false}
   :pressure-io-full-us {:unit :us :forms #{:counter :delta} :additive? false}
   :gpu-utilization-percent {:unit :percent :forms #{:gauge} :additive? false}
   :tests {:unit :test :forms #{:delta} :additive? true}
   :assertions {:unit :assertion :forms #{:delta} :additive? true}
   :assertions-passed {:unit :assertion :forms #{:delta} :additive? true}
   :assertions-failed {:unit :assertion :forms #{:delta} :additive? true}
   :assertions-errored {:unit :assertion :forms #{:delta} :additive? true}})

(def Quantity (into [:enum] (sort (keys quantity-registry))))
(def Uncertainty
  [:enum :unsupported :not-requested :not-collected :interrupted :permission-denied
   :counter-reset :partial-coverage :multiplexed :sampled-lower-bound])
(def Measurement
  "Intervals cover the attributed work; acquisition-only brackets cannot support disjointness."
  [:map {:closed true} [:id Id] [:node [:maybe Id]] [:resource Id] [:source Id]
   [:quantity Quantity] [:form [:enum :counter :delta :gauge :peak]]
   [:accounting [:enum :exclusive :inclusive :shared]] [:interval Interval]
   [:status [:enum :measured :partial :estimated :unavailable]]
   [:value [:maybe Natural]] [:reason [:maybe Uncertainty]]
   [:method [:enum :wait4 :proc-stat :psi :cgroup :pmu :nvidia-smi :test-runner :declared]]
   [:pmu {:optional true}
    [:map {:closed true} [:event Id] [:domain [:enum :user :kernel :user-kernel]]
     [:enabled-ns [:maybe Natural]] [:running-ns [:maybe Natural]]
     [:raw-value [:maybe Natural]]]]])
(def Partition
  "A producer's provenance-bearing assertion of disjoint accounting, not inferred topology."
  [:map {:closed true} [:id Id] [:source Id] [:quantity Quantity]
   [:members [:vector {:min 2 :max 256} Id]]])
(def Run
  [:map {:closed true} [:id Id] [:source-digest Digest] [:complete? :boolean]
   [:clock [:map {:closed true} [:id Id] [:origin-ns Natural]]]
   [:end-ns [:maybe Natural]]
   [:expected-keys [:vector {:min 1 :max 128000} Id]]])
(def Graph
  [:map {:closed true} [:schema/version [:= 1]] [:run Run]
   [:sources [:vector {:min 1 :max 4096} Source]]
   [:resources [:vector {:max 128000} Resource]]
   [:nodes [:vector {:max 128000} Node]]
   [:edges [:vector {:max 512000} Edge]]
   [:measurements [:vector {:max 1000000} Measurement]]
   [:partitions {:optional true} [:vector {:max 4096} Partition]]])
(def IssueCode
  [:enum :shape :duplicate-id :duplicate-key :missing-source :missing-parent
   :parent-cycle :resource-cycle :parent-kind :interval-order :parent-interval
   :run-interval :run-incomplete :missing-expected-key :unexpected-key
   :execution-state :decision-reason :missing-node :missing-resource :endpoint-phase
   :edge-time :edge-unobserved :edge-evidence :edge-cycle :measurement-state :measurement-form
   :measurement-value :measurement-pmu :measurement-interval :host-cpu-full
   :partition-member :partition-quantity :partition-accounting :partition-overlap])
(def Issue
  [:map {:closed true} [:code IssueCode] [:subject Id] [:related [:maybe Id]]])
(def Findings [:vector {:max 128} Issue])
(def AdmissionCode
  [:enum :input-byte-limit :input-depth-limit :input-value-limit :input-collection-limit
   :input-token-limit :input-string-limit :input-integer-limit :invalid-input-unicode
   :invalid-edn :duplicate-map-key :unknown-keyword :trailing-input :invalid-input-shape])
(def AdmissionFailure
  [:map {:closed true} [:code AdmissionCode]
   [:offset [:int {:min 0 :max 134217728}]] [:offset-unit [:= :utf16]]])
(def Failure
  "Closed ex-data for library refusals; Malli argument errors belong to the caller boundary."
  [:or
   AdmissionFailure
   [:map {:closed true} [:code [:= :invalid-graph]] [:findings Findings]]
   [:map {:closed true}
    [:code [:enum :quantity-overflow :negative-quantity :invalid-interval
            :invalid-encoding-input :encoded-byte-limit :invalid-cursor :invalid-query :missing-anchor
            :invalid-aggregation :invalid-comparison]]]])
(def NodeIndex [:map-of {:max 128000} Id Node])
(def ResourceIndex [:map-of {:max 128000} Id Resource])
(def MeasurementIndex [:map-of {:max 1000000} Id Measurement])
(def PartitionIndex [:map-of {:max 4096} Id Partition])
(def Vertex [:string {:min 1 :max 180}])
(def Arcs [:vector {:max 640000} [:tuple Vertex Vertex]])
(def AdmissionTarget [:enum :graph :query-request :aggregate-request :diff-request :value
                      :gate-definitions :cache-receipt :input-snapshot :coordinator-batch :attempt-observation :process-observation :clocked-process :process-capture :container-observation :output-publication :contained-observation :runtime-observation :test-observation :clocked-tests])
(def AdmissionLimits
  [:map {:closed true} [:bytes [:int {:min 1 :max 134217728}]]
   [:depth [:int {:min 1 :max 64}]] [:values [:int {:min 1 :max 16777216}]]
   [:collection [:int {:min 1 :max 1000000}]] [:string-units [:int {:min 1 :max 4096}]]
   [:token-units [:int {:min 1 :max 256}]]])
(defn quantity-info
  "Return declared quantity semantics; unknown names violate the function contract."
  [quantity]
  (get quantity-registry quantity))
(m/=> quantity-info
      [:=> [:cat Quantity]
       [:map {:closed true}
        [:unit [:enum :ns :us :byte :instruction :percent :test :assertion]]
        [:forms [:set {:min 1 :max 4} [:enum :counter :delta :gauge :peak]]]
        [:additive? :boolean]]])
