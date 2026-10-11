(ns gate.catalog-contract
  "Closed local directory inventories; unopened entries are metadata, never admitted report content."
  (:require [gate.contract :as c]))
(def ReportKind [:enum :run-archive :graph :process-batch-report :test-batch-report
                 :gate-definitions :coordinator-batch :repository-observation :artifact-catalog])
(def Name [:and [:string {:min 1 :max 4096}] [:re #"^(?!\.{1,2}(?![\s\S]))[^/\u0000]+(?![\s\S])"]])
(def Directory [:and [:string {:min 1 :max 4096}] [:re #"^/[^\u0000]*(?![\s\S])"]])
(def Entry [:map {:closed true} [:name Name] [:path Directory]
            [:type [:enum :file :directory :symlink :other]] [:status [:= :unopened]]
            [:hints [:vector {:max 2} ReportKind]]])
(def Material [:map {:closed true} [:schema/version [:= 1]] [:profile [:= :posix-directory-v1]]
               [:directory Directory] [:entries [:vector {:max 100000} Entry]]])
(def Document (conj Material [:artifact c/Digest]))
(def Limits [:map {:closed true} [:entries [:int {:min 1 :max 100000}]]
             [:bytes [:int {:min 1024 :max 16777216}]]])
(def Failure [:map {:closed true} [:code [:enum :catalog-policy :catalog-unreadable :catalog-entry-limit
                                          :catalog-byte-limit :catalog-shape :catalog-identity]]])
(def Encodable [:or Entry Material Document])
(def registry {::entry Entry ::material Material ::document Document ::limits Limits ::failure Failure})
