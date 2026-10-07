(ns gate.inspection-contract
  "Closed portable query, aggregation and comparison records over execution graphs."
  (:require [gate.contract :as c]))

(def Filter
  [:map {:closed true}
   [:kind {:optional true} c/NodeKind]
   [:outcome {:optional true}
    [:enum :passed :failed :error :cancelled :running :cached :deselected :blocked :refused]]
   [:key {:optional true} c/Id]])
(def MeasurementFilter
  [:map {:closed true} [:node {:optional true} [:maybe c/Id]] [:resource {:optional true} c/Id]
   [:quantity {:optional true} c/Quantity] [:form {:optional true} [:enum :counter :delta :gauge :peak]]
   [:status {:optional true} [:enum :measured :partial :estimated :unavailable]]])
(def Selection
  [:multi {:dispatch :op}
   [:nodes [:map {:closed true} [:op [:= :nodes]] [:where {:optional true} Filter]]]
   [:measurements [:map {:closed true} [:op [:= :measurements]]
                   [:where {:optional true} MeasurementFilter]]]
   [:children [:map {:closed true} [:op [:= :children]] [:anchor [:maybe c/Id]]
               [:where {:optional true} Filter]]]
   [:window [:map {:closed true} [:op [:= :window]] [:interval c/Interval]
             [:where {:optional true} Filter]]]])
(def Cursor
  [:map {:closed true} [:version [:= 1]] [:artifact c/Digest] [:selection c/Digest]
   [:offset [:int {:min 0 :max 1000000}]]])
(def Budget
  [:map {:closed true} [:visits [:int {:min 1 :max 128000}]]
   [:rows [:int {:min 1 :max 1000}]] [:bytes [:int {:min 1024 :max 1048576}]]])
(def Request
  [:map {:closed true} [:select Selection] [:budget Budget] [:cursor {:optional true} Cursor]])
(def StopReason [:enum :complete :visit-limit :row-limit :byte-limit])
(def Row [:or c/Node c/Measurement])
(def Page
  [:map {:closed true} [:artifact c/Digest] [:rows [:vector {:max 1000} Row]]
   [:visited [:int {:min 0 :max 128000}]] [:stop StopReason] [:next [:maybe Cursor]]])
(def Prepared
  [:map {:closed true} [:artifact c/Digest] [:graph c/Graph]
   [:ordered-nodes [:vector {:max 128000} c/Node]] [:node-ids [:set {:max 128000} c/Id]]
   [:measurement-index c/MeasurementIndex] [:partition-index c/PartitionIndex]])
(def AggregateOp [:enum :sum :maximum-observation])
(def AggregateRequest
  [:map {:closed true} [:op AggregateOp]
   [:measurements [:vector {:min 1 :max 256} c/Id]]
   [:partitions {:optional true} [:vector {:max 32} c/Id]]
   [:budget [:map {:closed true} [:pairs [:int {:min 0 :max 32640}]]
             [:bytes [:int {:min 1024 :max 262144}]]]]])
(def AggregateBase
  [:map {:closed true} [:artifact c/Digest] [:op AggregateOp]
   [:scope [:= :selected-observations]] [:measurements [:vector {:min 1 :max 256} c/Id]]
   [:checked-pairs [:int {:min 0 :max 32640}]]])
(def AggregateRefusal
  [:map {:closed true}
   [:code [:enum :missing-measurement :missing-partition :incompatible-observations
           :uncertain-observation :unsupported-form :non-additive :overlap-unproven
           :pair-limit :quantity-overflow]]
   [:subjects [:vector {:max 2} c/Id]]])
(def AggregateResult
  [:multi {:dispatch :status}
   [:complete
    (into AggregateBase
          [[:status [:= :complete]] [:quantity c/Quantity] [:value c/Natural]
           [:basis [:enum :single-observation :time-disjoint :attested-disjointness
                    :observation-extremum]]
           [:partitions [:vector {:max 32} c/Id]]])]
   [:refused
    (into (conj AggregateBase [:status [:= :refused]]) (drop 2 AggregateRefusal))]])
(def TaskIdentity [:map {:closed true} [:kind c/NodeKind] [:key c/Id]])
(def TaskParent (conj TaskIdentity [:attempt c/Attempt]))
(def TaskSemanticsBase [:map {:closed true} [:label c/Label] [:parent [:maybe TaskParent]]])
(def TaskSemantics
  [:multi {:dispatch :record}
   [:execution (into TaskSemanticsBase [[:record [:= :execution]] [:attempt c/Attempt]
                                        [:outcome c/ExecutionOutcome]])]
   [:decision (into TaskSemanticsBase [[:record [:= :decision]] [:outcome c/DecisionOutcome]
                                       [:reason c/DecisionReason]])]])
(def TaskDuration
  [:map {:closed true} [:attempt c/Attempt] [:duration-ns [:maybe c/Natural]]])
(def TaskAssociation
  [:map {:closed true} [:semantics c/Digest] [:duration c/Digest]])
(def Fingerprint
  "Domain-separated multiset of observation digests; multiplicity remains significant."
  [:map {:closed true} [:version [:= 1]]
   [:domain [:enum :task-semantics :task-durations :task-associations]]
   [:members [:vector {:max 128000} c/Digest]]])
(def OutcomeCounts
  (into [:map {:closed true}]
        (map (fn [outcome] [outcome [:int {:min 0 :max 128000}]])
             (distinct (concat (rest c/ExecutionOutcome) (rest c/DecisionOutcome))))))
(def TaskProfile
  [:map {:closed true} [:identity TaskIdentity] [:declared? [:maybe :boolean]]
   [:observations [:int {:min 0 :max 128000}]] [:outcomes OutcomeCounts]
   [:semantics c/Digest] [:durations c/Digest] [:association c/Digest] [:examples [:vector {:max 4} c/Id]]])
(def DiffSelection
  [:map {:closed true} [:op [:= :tasks]] [:changed-only? :boolean]
   [:where {:optional true} [:map {:closed true}
                             [:kind {:optional true} c/NodeKind] [:key {:optional true} c/Id]]]])
(def DiffCursor
  [:map {:closed true} [:version [:= 1]] [:before c/Digest] [:after c/Digest] [:selection c/Digest]
   [:before-offset [:int {:min 0 :max 256000}]] [:after-offset [:int {:min 0 :max 256000}]]])
(def DiffRequest
  [:map {:closed true} [:select DiffSelection] [:budget Budget]
   [:cursor {:optional true} DiffCursor]])
(def DiffRow
  [:map {:closed true} [:identity TaskIdentity]
   [:change [:enum :unchanged :changed :before-only :after-only]]
   [:dimensions [:vector {:max 3} [:enum :semantics :durations :association]]]
   [:before [:maybe TaskProfile]] [:after [:maybe TaskProfile]]])
(def DiffPage
  [:map {:closed true} [:before c/Digest] [:after c/Digest]
   [:before-complete? :boolean] [:after-complete? :boolean] [:scope [:= :task-observations]]
   [:rows [:vector {:max 1000} DiffRow]] [:visited [:int {:min 0 :max 128000}]]
   [:stop StopReason] [:next [:maybe DiffCursor]]])
(def DiffPrepared
  [:map {:closed true} [:before c/Digest] [:after c/Digest]
   [:before-complete? :boolean] [:after-complete? :boolean]
   [:before-tasks [:vector {:max 256000} TaskProfile]]
   [:after-tasks [:vector {:max 256000} TaskProfile]]])
