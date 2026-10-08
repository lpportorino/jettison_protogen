(ns gate.api-contract
  "Closed API identity and explicit consumer adoption records; fingerprints do not prove compatibility."
  (:require [gate.contract :as c]))

(def Name
  "Bounded qualified export identity, including ordinary Clojure function punctuation."
  [:and [:string {:min 3 :max 256}]
   [:re #"^[A-Za-z][A-Za-z0-9._-]*/[A-Za-z*+!_?<>=$%&-][A-Za-z0-9*+!_?<>=$%&.-]*(?![\s\S])"]])
(def Names [:vector {:max 4096} Name])
(def Version [:int {:min 1 :max 2147483647}])
(def Platforms [:vector {:min 1 :max 2} [:enum :clj :cljs]])
(def Capabilities [:vector {:max 32} c/Id])
(def EntryBase
  [:map {:closed true} [:id Name] [:platforms Platforms]
   [:definition c/Digest] [:capabilities Capabilities]])
(def Entry
  [:multi {:dispatch :kind}
   [:operation (into EntryBase [[:kind [:= :operation]] [:role [:enum :tool :trusted]]
                                [:invocation [:enum :function :macro]]
                                [:arguments c/Digest] [:result c/Digest]])]
   [:schema (into EntryBase [[:kind [:= :schema]] [:contract c/Digest]])]
   [:value (conj EntryBase [:kind [:= :value]])]])
(def Entries [:vector {:min 1 :max 4096} Entry])
(def Material
  [:map {:closed true} [:schema/version [:= 1]] [:profile [:= :source-closure-v1]]
   [:api-version Version] [:source c/Digest] [:entries Entries]])
(def Manifest (conj Material [:artifact c/Digest]))
(def Reason [:and [:string {:min 1 :max 2048}] [:re #"[\s\S]*\S[\s\S]*"]])
(def DecisionBase [:map {:closed true} [:id Name] [:reason Reason]])
(def Decision
  [:multi {:dispatch :action}
   [:adopt (into DecisionBase [[:action [:= :adopt]]
                               [:wrappers [:vector {:min 1 :max 256} Name]]])]
   [:defer (into DecisionBase [[:action [:= :defer]] [:wrappers [:vector {:max 0} Name]]])]])
(def Decisions [:vector {:max 4096} Decision])
(def Acceptance
  [:map {:closed true} [:schema/version [:= 1]] [:consumer-api-version Version]
   [:acceptance-version Version] [:manifest c/Digest] [:surface c/Digest]
   [:exports Names] [:decisions Decisions]])
(def Request
  [:map {:closed true} [:manifest Manifest] [:acceptance Acceptance]
   [:previous [:maybe Acceptance]] [:surface c/Digest] [:exports Names]])
(def Result
  [:map {:closed true} [:status [:= :accepted]] [:manifest c/Digest]
   [:consumer-api-version Version] [:acceptance-version Version]
   [:adopted Names] [:deferred Names]])
(def Code
  [:enum :api-shape :api-budget :api-duplicates :api-order :api-identity :api-version-regressed
   :api-version-required :api-unacknowledged-manifest :api-surface-drift
   :api-export-drift :api-decisions :api-wrapper-coverage :api-initial-version
   :api-ack-required :api-export-version-required])
(def Phase [:enum :manifest :upgrade :acceptance :previous :observation])
(def Failure
  [:map {:closed true} [:code Code] [:phase Phase]])
(def Encodable [:or Material Manifest Acceptance Request Result Failure])
(def registry
  {::name Name ::entry Entry ::material Material ::manifest Manifest
   ::decision Decision ::acceptance Acceptance ::request Request ::result Result ::failure Failure})
