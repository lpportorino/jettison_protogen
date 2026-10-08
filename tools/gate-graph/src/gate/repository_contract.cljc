(ns gate.repository-contract
  "Closed Git-visible working-tree evidence; ignored runtime inputs require separate evidence."
  (:require [gate.contract :as c]
            [gate.run-contract :as r]))

(def ObjectId [:re #"^(?:[0-9a-f]{40}|[0-9a-f]{64})(?![\s\S])"])
(def Location [:or [:= "."] r/Path])
(def IndexTerm
  [:map {:closed true} [:mode [:enum "100644" "100755" "120000" "160000"]] [:object ObjectId]])
(def IndexedPath (conj IndexTerm [:path r/Path]))
(def Index [:vector {:max 1000000} IndexedPath])
(def Paths [:vector {:max 1000000} r/Path])
(def Base [:map {:closed true} [:path r/Path] [:index [:maybe IndexTerm]]])
(def Entry
  [:multi {:dispatch :kind}
   [:file (into Base [[:kind [:= :file]] [:digest c/Digest] [:executable? :boolean]])]
   [:symlink (into Base [[:kind [:= :symlink]] [:digest c/Digest]])]
   [:deleted [:map {:closed true} [:path r/Path] [:index IndexTerm] [:kind [:= :deleted]]]]
   [:submodule (into Base [[:kind [:= :submodule]] [:revision ObjectId] [:content c/Digest]])]])
(def Entries [:vector {:max 1000000} Entry])
(def Tree
  [:map {:closed true} [:path Location] [:revision ObjectId] [:entries Entries]])
(def Repository (conj Tree [:content c/Digest]))
(def Exclusions [:vector {:max 32} r/Path])
(def Protected [:vector {:min 1 :max 1024} Location])
(def Policy [:map {:closed true} [:excluded Exclusions] [:protected Protected]])
(def Material
  [:map {:closed true} [:schema/version [:= 1]] [:profile [:= :git-visible-posix-v1]]
   [:policy Policy] [:repositories [:vector {:min 1 :max 128} Repository]]])
(def Observation (conj Material [:content c/Digest]))
(def Encodable [:or Tree Repository Material Observation])
(def Code
  [:enum :repository-policy :repository-git :repository-protocol :repository-budget
   :repository-unsupported :repository-unreadable :repository-unstable :repository-cancelled])
(def BudgetKind [:enum :files :entries :bytes :depth :repositories :git-records])
(def Failure
  [:or [:map {:closed true} [:code Code]]
   [:map {:closed true} [:code [:= :repository-budget]] [:limit BudgetKind]
    [:used c/Natural] [:maximum c/Natural]]])
(def registry
  {::object-id ObjectId ::location Location ::index-term IndexTerm ::indexed-path IndexedPath
   ::entry Entry ::entries Entries ::tree Tree ::repository Repository ::policy Policy
   ::material Material ::observation Observation ::failure Failure})
