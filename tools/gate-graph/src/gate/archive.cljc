(ns gate.archive
  "Canonical run archives bind graph, declared CI scope and before/after repository provenance."
  (:require [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.graph :as graph]
            [gate.repository-identity :as repository]
            [malli.core :as m]))

(defn- refuse!
  "Return a closed archive failure without serializing rejected records."
  [code]
  (throw (ex-info "Run archive refused" {:code code})))
(m/=> refuse! [:=> [:cat ac/Code] :nil])

(defn stability
  "Classify before/after observations; equal hashes with different summaries are inconsistent evidence."
  [before after]
  (doseq [acquisition [before after] :when (= :observed (:state acquisition))]
    (repository/require-summary! (:snapshot acquisition)))
  (let [a (:snapshot before) b (:snapshot after)]
    (cond
      (or (nil? a) (nil? b)) :unavailable
      (and (= (:content a) (:content b)) (not= a b)) (refuse! :archive-provenance)
      (not= (:policy a) (:policy b)) :policy-changed
      (= a b) :unchanged
      :else :changed)))
(m/=> stability [:=> [:cat ac/Acquisition ac/Acquisition] ac/Stability])

(defn status
  "Derive graph/provenance status without converting failures or missing acquisition into success.
   A passing archive describes its declared scope; it does not establish complete CI enrollment."
  [value provenance]
  (let [outcomes (set (map :outcome (:nodes value)))]
    (cond
      (or (contains? outcomes :cancelled)
          (some #(= :repository-cancelled (get-in provenance [% :failure :code])) [:before :after])) :cancelled
      (some outcomes [:failed :error :blocked :refused]) :failed
      (or (not (get-in value [:run :complete?])) (contains? outcomes :running)
          (not-any? #(= :execution (:record %)) (:nodes value))
          (not= :unchanged (:stability provenance))) :incomplete
      :else :passed)))
(m/=> status [:=> [:cat c/Graph ac/Provenance] ac/Status])

(defn- require-scope!
  "A full-CI declaration cannot simultaneously list known omissions; producer inventory remains trusted."
  [scope]
  (when (or (and (= :full-ci (:kind scope)) (seq (:omissions scope)))
            (not= (:omissions scope) (vec (sort (distinct (:omissions scope))))))
    (refuse! :archive-scope))
  nil)
(m/=> require-scope! [:=> [:cat ac/Scope] :nil])

(defn create
  "Build a normalized archive from a validated graph and explicit before/after acquisition records.
   Observed summaries must come from repository-identity/summarize at the producer boundary.
   Unavailable acquisition stays visible; equality is not isolation or absence of edit/revert cycles."
  [scope value before after]
  (when-not (and (m/validate ac/Scope scope) (m/validate ac/Acquisition before)
                 (m/validate ac/Acquisition after)) (refuse! :archive-shape))
  (require-scope! scope)
  (graph/require-valid! value)
  (let [provenance {:before before :after after :stability (stability before after)}
        material {:schema/version 1 :scope scope :graph (canonical/normalize-graph value)
                  :provenance provenance :status (status value provenance)}]
    (assoc material :artifact (canonical/sha256 (canonical/encode material 134217728)))))
(m/=> create [:=> [:cat ac/Scope c/Graph ac/Acquisition ac/Acquisition] ac/Document])

(defn require-valid!
  "Recompute graph invariants, provenance consistency, status and archive digest on admitted records.
   Consistent metadata is not an independent attestation of declared CI coverage or source acquisition."
  [document]
  (when-not (m/validate ac/Document document) (refuse! :archive-shape))
  (let [expected (create (:scope document) (:graph document)
                         (get-in document [:provenance :before]) (get-in document [:provenance :after]))]
    (when-not (= (:provenance expected) (:provenance document)) (refuse! :archive-provenance))
    (when-not (= (:status expected) (:status document)) (refuse! :archive-status))
    (when-not (= expected document) (refuse! :archive-identity)))
  document)
(m/=> require-valid! [:=> [:cat ac/Document] ac/Document])
