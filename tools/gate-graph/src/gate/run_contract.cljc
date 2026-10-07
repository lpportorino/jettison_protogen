(ns gate.run-contract
  "Closed declarations and evidence for shared gate planning and content-addressed reuse."
  (:require [gate.contract :as c]))

(def Text [:string {:max 4096}])
(def Path
  [:and [:string {:min 1 :max 4096}]
   [:re #"^(?!/)(?!.*(?:^|/)\.{1,2}(?:/|$))(?!.*//)(?!.*[\x00\\]).*[^/](?![\s\S])"]])
(def EnvName [:re #"^[A-Za-z_][A-Za-z0-9_]{0,127}(?![\s\S])"])
(def Relation [:enum :requires :after :produces :invalidates])
(def Dependency [:map {:closed true} [:gate c/Id] [:relation Relation]])
(def Input
  [:map {:closed true} [:id c/Id] [:kind [:enum :file :tree :glob]]
   [:path Path] [:required? :boolean]
   [:exclude {:optional true} [:vector {:max 256} Path]]])
(def Gate
  [:map {:closed true} [:id c/Id] [:label c/Label]
   [:command [:vector {:min 1 :max 256} Text]] [:cwd [:or [:= "."] Path]]
   [:inputs [:vector {:max 1024} Input]] [:outputs [:vector {:max 1024} Path]]
   [:environment [:vector {:max 256} EnvName]] [:toolchains [:vector {:min 1 :max 64} c/Id]]
   [:dependencies [:vector {:max 1024} Dependency]]
   [:cache [:enum :always :content]] [:network [:enum :denied :allowed]]
   [:coverage [:map {:closed true} [:expected c/Digest]
               [:minimum [:int {:min 1 :max 1000000}]]]]])
(def Gates [:vector {:min 1 :max 10000} Gate])
(def GateIndex [:map-of {:min 1 :max 10000} c/Id Gate])
(def File
  [:map {:closed true} [:path Path] [:digest c/Digest] [:executable? :boolean]])
(def Files [:vector {:max 1000000} File])
(def Membership
  [:map {:closed true} [:selector c/Id] [:paths [:vector {:max 1000000} Path]]])
(def Snapshot
  [:map {:closed true}
   [:files Files] [:membership [:vector {:max 1024} Membership]]
   [:environment [:vector {:max 256} [:map {:closed true} [:name EnvName] [:value [:maybe Text]]]]]
   [:toolchains [:vector {:max 64} [:map {:closed true} [:name c/Id] [:digest c/Digest]]]]
   [:complete? :boolean]
   [:reason [:maybe [:enum :missing-input :unreadable-input :unstable-input :unknown-toolchain
                     :unsupported-input :budget-exhausted]]]])
(def DependencyTerm
  [:map {:closed true} [:gate c/Id] [:relation Relation] [:key [:maybe c/Digest]]
   [:result [:maybe c/Digest]]])
(def DependencyTerms [:vector {:max 1024} DependencyTerm])
(def KeyGate (into (subvec Gate 0 2) (remove #(= :label (first %)) (subvec Gate 2))))
(def Material
  [:map {:closed true} [:schema/version [:= 1]] [:gate KeyGate] [:snapshot Snapshot]
   [:dependencies DependencyTerms]])
(def Coverage
  [:map {:closed true} [:expected c/Digest] [:observed c/Digest]
   [:count [:int {:min 0 :max 1000000}]]])
(def ResultIdentity
  [:map {:closed true} [:schema/version [:= 1]] [:status [:= :passed]]
   [:coverage Coverage] [:outputs Files]])
(def Receipt
  [:map {:closed true} [:schema/version [:= 1]] [:gate c/Id] [:key c/Digest]
   [:run c/Id] [:attempt c/Id] [:status [:= :passed]] [:coverage Coverage]
   [:outputs Files] [:result c/Digest]])
(def Reason
  [:enum :cache-hit :forced :always-run :network-unbounded :incomplete-snapshot
   :missing-receipt :input-changed :invalid-receipt :coverage-mismatch :outputs-changed
   :dependency-unknown :failed-execution :input-unstable :unproven-isolation :cancellation-requested])
(def Decision
  [:map {:closed true} [:gate c/Id] [:action [:enum :run :cached]] [:reason Reason]
   [:key [:maybe c/Digest]]])
(def Admission
  [:multi {:dispatch :status}
   [:recorded [:map {:closed true} [:status [:= :recorded]] [:receipt Receipt]]]
   [:refused [:map {:closed true} [:status [:= :refused]] [:reason Reason]]]])
(def Schedule
  [:map {:closed true} [:order [:vector {:min 1 :max 10000} c/Id]]
   [:selected [:vector {:min 1 :max 10000} c/Id]]
   [:deselected [:vector {:max 10000} c/Id]]])
(def Failure
  [:map {:closed true}
   [:code [:enum :invalid-gate-definition :duplicate-gate :duplicate-dependency
           :missing-dependency :dependency-cycle :unknown-root :empty-selection
           :invalid-snapshot :invalid-dependency-terms]]
   [:subject [:maybe c/Id]] [:related [:maybe c/Id]]])
(def WorkResult
  [:map {:closed true} [:outcome [:enum :passed :failed :error :cancelled :cached]]
   [:coverage [:maybe Coverage]]
   [:reason [:maybe [:enum :command-failed :adapter-exception :invalid-adapter-result
                     :coverage-mismatch :cancellation-requested :cache-hit :input-unstable :output-publication :runtime-unstable :test-observation-error]]]])
(def Dispatch
  [:map {:closed true} [:gate c/Id]
   [:outcome [:enum :passed :failed :error :cancelled :cached :blocked :deselected]]
   [:at-ns c/Natural] [:adapter-interval [:maybe c/Interval]]
   [:work [:maybe WorkResult]] [:blocked-by [:vector {:max 1024} c/Id]]])
(def Dispatches [:vector {:min 1 :max 10000} Dispatch])
(def Batch
  [:map {:closed true} [:schema/version [:= 1]]
   [:status [:enum :passed :failed :cancelled]] [:schedule Schedule]
   [:dispatches Dispatches] [:duration-ns c/Natural] [:clock c/Id]])
(def CoordinatorOptions
  [:map {:closed true} [:jobs [:int {:min 1 :max 256}]]
   [:claims [:map-of {:max 10000} c/Id [:vector {:min 1 :max 64} c/Id]]]])
(def AttemptObservation
  [:map {:closed true} [:schema/version [:= 1]] [:gate c/Id] [:run c/Id] [:attempt c/Id]
   [:decision Decision] [:lookup [:enum :hit :absent :invalid :io-error :conflict :not-attempted]]
   [:work WorkResult] [:before Snapshot] [:after [:maybe Snapshot]] [:outputs Files]
   [:dependencies-before DependencyTerms] [:dependencies-after [:maybe DependencyTerms]]
   [:admission [:maybe Admission]]
   [:publication [:enum :stored :existing :conflict :invalid :io-error :not-attempted]]
   [:elapsed-ns c/Natural]])
(def ProcessStatus [:enum :exited :cancelled :timed-out :output-limit :process-limit :launch-failed :io-failed :unsupported-jobserver])
(def LogFileName [:re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,159}(?![\s\S])"])
(def ContainerLimits
  [:map {:closed true} [:pids [:int {:min 1 :max 4096}]]
   [:memory-mib [:int {:min 16 :max 65536}]]
   [:tmp-mib [:int {:min 1 :max 2048}]] [:cpus [:int {:min 1 :max 256}]]])
(def OutputPublication
  [:map {:closed true} [:status [:enum :installed :partial :refused :not-attempted]]
   [:installed [:vector {:max 1024} Path]] [:reason [:maybe [:enum :output-verification :output-io :source-changed]]]])
(def ContainerProfile
  [:map {:closed true} [:schema/version [:= 1]] [:backend [:= :docker-readonly-v1]]
   [:image c/Digest] [:engine c/Digest] [:kernel Text] [:architecture Text]
   [:limits ContainerLimits] [:output-roots [:vector {:max 64} Path]]])
(def InputLimits
  [:map {:closed true} [:files [:int {:min 1 :max 1000000}]]
   [:entries [:int {:min 1 :max 10000000}]] [:bytes [:int {:min 1 :max 2147483647}]]
   [:depth [:int {:min 1 :max 256}]]])
(def ProcessLimits
  [:map {:closed true} [:timeout-ms [:int {:min 1 :max 86400000}]]
   [:cleanup-ms [:int {:min 1 :max 10000}]] [:log-bytes [:int {:min 1 :max 67108864}]]
   [:processes [:int {:min 1 :max 4096}]] [:stdin-bytes [:int {:min 0 :max 4194304}]]])
(def RuntimeIdentity
  [:map {:closed true} [:schema/version [:= 1]] [:profile ContainerProfile]
   [:input-limits InputLimits] [:process-limits ProcessLimits]
   [:controller c/Digest] [:daemon c/Digest] [:machine c/Digest]])
(def RuntimeObservation
  [:map {:closed true} [:status [:enum :verified :unavailable]]
   [:identity [:maybe RuntimeIdentity]] [:elapsed-ns c/Natural]
   [:reason [:maybe [:enum :runtime-policy :runtime-controller :runtime-daemon :runtime-machine :runtime-io :runtime-cancelled]]]])
(def ProcessObservation
  [:map {:closed true} [:schema/version [:= 1]] [:status ProcessStatus] [:exit [:maybe :int]]
   [:pid [:maybe c/Natural]] [:elapsed-ns c/Natural] [:containment [:= :none]]
   [:cleanup-required? :boolean] [:observed-processes [:int {:min 0 :max 2147483647}]] [:observed-processes-stopped? :boolean]
   [:log [:maybe [:map {:closed true} [:file LogFileName]
                  [:bytes [:int {:min 0 :max 67108864}]] [:digest c/Digest] [:truncated? :boolean]]]]])
(def ContainerObservation
  [:map {:closed true} [:schema/version [:= 1]] [:profile ContainerProfile]
   [:container [:maybe c/Digest]] [:snapshot [:maybe Snapshot]]
   [:status [:enum :exited :cancelled :refused :runtime-failed :cleanup-unknown]]
   [:reason [:maybe [:enum :snapshot-incomplete :snapshot-changed :snapshot-budget :snapshot-io :snapshot-layout
                     :container-policy :container-image :container-create :container-start :container-cleanup]]]
   [:execution [:maybe ProcessObservation]] [:removed? :boolean]
   [:outputs [:maybe Files]] [:elapsed-ns c/Natural]])
(def ContainedObservation
  [:map {:closed true} [:schema/version [:= 1]] [:attempt AttemptObservation]
   [:container [:maybe ContainerObservation]] [:output-publication OutputPublication]
   [:runtime-before {:optional true} RuntimeObservation] [:runtime-after {:optional true} [:maybe RuntimeObservation]]
   [:ownership-wait-ns {:optional true} c/Natural] [:elapsed-ns {:optional true} c/Natural]])
(def TestCounts
  [:map {:closed true} [:pass [:int {:min 0 :max 1000000}]]
   [:fail [:int {:min 0 :max 1000000}]] [:error [:int {:min 0 :max 1000000}]]])
(def TestIdentity [:map {:closed true} [:key c/Id] [:label c/Label]])
(def TestInventory [:vector {:min 1 :max 10000} TestIdentity])
(def TestProblem [:enum :test-limit :assertion-limit :runner-error :missing-tests :unexpected-tests
                  :unattributed-assertions :empty-assertions :source-changed])
(def TestExecution
  [:map {:closed true} [:id c/Id] [:key c/Id] [:label c/Label] [:parent [:maybe c/Id]]
   [:attempt c/Attempt] [:interval c/Interval] [:counts TestCounts]
   [:returned? :boolean]])
(def TestObservation
  [:map {:closed true} [:schema/version [:= 1]] [:run c/Id] [:gate c/Id] [:label c/Label]
   [:source-digest c/Digest] [:inventory TestInventory]
   [:executions [:vector {:max 10000} TestExecution]] [:unattributed TestCounts]
   [:problems [:vector {:max 8} TestProblem]] [:duration-ns c/Natural]
   [:complete? :boolean] [:status [:enum :passed :failed :error]]
   [:coverage [:maybe Coverage]]])
(def ClockedTests
  [:map {:closed true} [:schema/version [:= 1]] [:clock c/Id] [:offset-ns c/Natural]
   [:observation TestObservation]])
(def TestBatchReport
  [:map {:closed true} [:schema/version [:= 1]] [:status [:enum :passed :failed :cancelled]]
   [:batch Batch] [:graph c/Graph] [:captures [:vector {:max 10000} ClockedTests]]])
(def Encodable [:or Gate Gates Snapshot Material ResultIdentity Receipt Decision Admission Schedule Batch AttemptObservation ProcessObservation ContainerProfile ContainerObservation OutputPublication ContainedObservation RuntimeIdentity RuntimeObservation TestObservation TestInventory ClockedTests TestBatchReport])
(def registry
  {::gate Gate ::input Input ::dependency Dependency ::file File ::snapshot Snapshot
   ::material Material ::coverage Coverage ::receipt Receipt ::decision Decision
   ::admission Admission ::schedule Schedule ::failure Failure
   ::work-result WorkResult ::dispatch Dispatch ::batch Batch ::coordinator-options CoordinatorOptions
   ::attempt-observation AttemptObservation ::process-observation ProcessObservation
   ::container-profile ContainerProfile ::container-observation ContainerObservation
   ::output-publication OutputPublication ::contained-observation ContainedObservation
   ::runtime-identity RuntimeIdentity ::runtime-observation RuntimeObservation
   ::test-counts TestCounts ::test-identity TestIdentity ::test-inventory TestInventory
   ::test-problem TestProblem ::test-execution TestExecution ::test-observation TestObservation
   ::clocked-tests ClockedTests ::test-batch-report TestBatchReport})
