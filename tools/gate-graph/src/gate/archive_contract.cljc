(ns gate.archive-contract
  "Closed portable run archives with explicit coverage scope and repository observation summaries."
  (:require [gate.contract :as c]
            [gate.repository-contract :as rc]))

(def Repository
  [:map {:closed true} [:path rc/Location] [:revision rc/ObjectId] [:content c/Digest]
   [:entries [:int {:min 0 :max 1000000}]]])
(def Snapshot
  [:map {:closed true} [:profile [:= :git-visible-posix-v1]] [:policy rc/Policy]
   [:content c/Digest] [:repositories [:vector {:min 1 :max 128} Repository]]])
(def Acquisition
  [:multi {:dispatch :state}
   [:observed [:map {:closed true} [:state [:= :observed]] [:snapshot Snapshot]]]
   [:unavailable [:map {:closed true} [:state [:= :unavailable]] [:failure rc/Failure]]]])
(def Stability [:enum :unchanged :changed :policy-changed :unavailable])
(def Provenance
  [:map {:closed true} [:before Acquisition] [:after Acquisition] [:stability Stability]])
(def Scope
  "Producer-declared coverage; an archive cannot infer a repository's complete CI inventory."
  [:map {:closed true} [:id c/Id] [:label c/Label] [:kind [:enum :battery :full-ci]]
   [:omissions [:vector {:max 128} c/Label]]])
(def Status [:enum :passed :failed :cancelled :incomplete])
(def Material
  [:map {:closed true} [:schema/version [:= 1]] [:scope Scope] [:status Status]
   [:graph c/Graph] [:provenance Provenance]])
(def Document (conj Material [:artifact c/Digest]))
(def Metadata
  (into (subvec Document 0 2) (remove #(= :graph (first %))) (subvec Document 2)))
(def Code
  [:enum :archive-shape :archive-repository :archive-policy :archive-scope
   :archive-provenance :archive-status :archive-identity])
(def Failure [:map {:closed true} [:code Code]])
(def Encodable [:or Repository Snapshot Acquisition Provenance Scope Material Document Metadata Failure])
(def registry
  {::repository Repository ::snapshot Snapshot ::acquisition Acquisition ::provenance Provenance
   ::scope Scope ::material Material ::document Document ::metadata Metadata ::failure Failure})
