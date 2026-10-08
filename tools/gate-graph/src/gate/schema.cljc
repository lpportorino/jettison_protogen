(ns gate.schema
  "Discoverable graph and inspection schemas; persisted schema names remain stable."
  (:require [gate.api-contract :as api]
            [gate.archive-contract :as archive]
            [gate.contract :as c]
            [gate.inspection-contract :as ic]
            [gate.repository-contract :as repository]
            [gate.view.contract :as view]
            [gate.view.opportunity :as opportunity]
            [gate.viewer-contract :as vc]))

(def Encodable
  [:or api/Encodable opportunity/Candidate opportunity/Candidates opportunity/Failure view/Encodable archive/Encodable repository/Encodable vc/Manifest c/Graph ic/Selection ic/Request ic/Page c/Node c/Measurement ic/AggregateRequest ic/AggregateResult
   ic/TaskSemantics ic/TaskDuration ic/TaskAssociation ic/Fingerprint ic/DiffSelection ic/DiffRequest ic/DiffRow ic/DiffPage])

(def registry
  "Named schemas for discovery; the normalized boundary contains no open metadata."
  (merge api/registry view/registry archive/registry repository/registry {:gate.view.opportunity/candidate opportunity/Candidate
                                                                          :gate.view.opportunity/candidates opportunity/Candidates
                                                                          :gate.view.opportunity/failure opportunity/Failure
                                                                          :gate.viewer/manifest vc/Manifest
                                                                          :gate.contract/id c/Id :gate.contract/natural c/Natural :gate.contract/digest c/Digest :gate.contract/label c/Label :gate.contract/node-kind c/NodeKind :gate.contract/attempt c/Attempt
                                                                          :gate.contract/execution-outcome c/ExecutionOutcome :gate.contract/decision-outcome c/DecisionOutcome :gate.contract/decision-reason c/DecisionReason
                                                                          :gate.contract/interval c/Interval
                                                                          :gate.contract/open-interval c/OpenInterval :gate.contract/source c/Source :gate.contract/resource c/Resource :gate.contract/execution c/Execution
                                                                          :gate.contract/decision c/Decision :gate.contract/node c/Node :gate.contract/endpoint c/Endpoint :gate.contract/edge c/Edge :gate.contract/quantity c/Quantity
                                                                          :gate.contract/uncertainty c/Uncertainty :gate.contract/measurement c/Measurement :gate.contract/partition c/Partition :gate.contract/run c/Run :gate.contract/graph c/Graph
                                                                          :gate.contract/issue c/Issue :gate.contract/findings c/Findings :gate.contract/failure c/Failure :gate.contract/filter ic/Filter
                                                                          :gate.contract/measurement-filter ic/MeasurementFilter :gate.contract/selection ic/Selection :gate.contract/row ic/Row
                                                                          :gate.contract/cursor ic/Cursor :gate.contract/budget ic/Budget :gate.contract/request ic/Request :gate.contract/page ic/Page
                                                                          :gate.contract/aggregate-request ic/AggregateRequest :gate.contract/aggregate-refusal ic/AggregateRefusal
                                                                          :gate.contract/aggregate-result ic/AggregateResult :gate.contract/task-identity ic/TaskIdentity :gate.contract/task-parent ic/TaskParent
                                                                          :gate.contract/task-semantics ic/TaskSemantics :gate.contract/task-duration ic/TaskDuration :gate.contract/task-association ic/TaskAssociation
                                                                          :gate.contract/fingerprint ic/Fingerprint
                                                                          :gate.contract/outcome-counts ic/OutcomeCounts :gate.contract/task-profile ic/TaskProfile :gate.contract/diff-selection ic/DiffSelection
                                                                          :gate.contract/diff-cursor ic/DiffCursor :gate.contract/diff-request ic/DiffRequest :gate.contract/diff-row ic/DiffRow :gate.contract/diff-page ic/DiffPage
                                                                          :gate.contract/admission-code c/AdmissionCode :gate.contract/admission-failure c/AdmissionFailure
                                                                          :gate.contract/admission-target c/AdmissionTarget :gate.contract/admission-limits c/AdmissionLimits}))
