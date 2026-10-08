(ns gate.api
  "Portable API identities and explicit adoption checks over independently observed export evidence."
  (:require [gate.api-contract :as ac]
            [gate.canonical :as canonical]
            [malli.core :as m]))

(defn- refuse!
  "Refuse with bounded data only; never serialize rejected source or consumer configuration."
  [code phase]
  (throw (ex-info "API acceptance refused" {:code code :phase phase})))
(m/=> refuse! [:=> [:cat ac/Code ac/Phase] :nil])

(defn- unique!
  "Reject duplicate identities instead of silently discarding observations or decisions."
  [names phase]
  (when-not (= (count names) (count (set names))) (refuse! :api-duplicates phase))
  nil)
(m/=> unique! [:=> [:cat [:sequential :any] ac/Phase] :nil])

(defn- canonical-entries
  "Sort set-like fields after rejecting duplicates; the public export inventory remains explicit."
  [entries]
  (unique! (map :id entries) :manifest)
  (mapv (fn [entry]
          (unique! (:platforms entry) :manifest)
          (unique! (:capabilities entry) :manifest)
          (-> entry (update :platforms #(vec (sort %))) (update :capabilities #(vec (sort %)))))
        (sort-by :id entries)))
(m/=> canonical-entries [:=> [:cat ac/Entries] ac/Entries])

(defn manifest
  "Canonicalize admitted manifest material and bind all declarations to one SHA-256 identity.
   Source discovery must independently prove completeness and contract fingerprints. This pure
   constructor cannot prove the truth of a caller-supplied inventory. The profile is conservative
   source/contract identity, never a semantic compatibility certificate. Encoding is capped at 16 MiB;
   even a schema-valid inventory may exceed that budget and returns a closed :api-budget refusal."
  [material]
  (when-not (m/validate ac/Material material) (refuse! :api-shape :manifest))
  (let [material (update material :entries canonical-entries)]
    (try
      (assoc material :artifact (canonical/sha256 (canonical/encode material 16777216)))
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
        (if (= :encoded-byte-limit (:code (ex-data error)))
          (refuse! :api-budget :manifest)
          (throw error))))))
(m/=> manifest [:=> [:cat ac/Material] ac/Manifest])

(defn verify!
  "Require canonical entry order and a matching manifest digest; reject tampering before use."
  [document]
  (when-not (m/validate ac/Manifest document) (refuse! :api-shape :manifest))
  (let [expected (manifest (dissoc document :artifact))]
    (when-not (= (:entries expected) (:entries document)) (refuse! :api-order :manifest))
    (when-not (= (:artifact expected) (:artifact document)) (refuse! :api-identity :manifest)))
  nil)
(m/=> verify! [:=> [:cat ac/Manifest] :nil])

(defn verify-upgrade!
  "Refuse API version regression or changed export declarations without a strictly greater version.
   Source-only changes still alter the manifest and require consumer acknowledgement. Comparing
   independently retained manifests is the caller's responsibility; this function does not read Git."
  [before after]
  (verify! before)
  (verify! after)
  (when (< (:api-version after) (:api-version before)) (refuse! :api-version-regressed :upgrade))
  (when (and (not= (:entries before) (:entries after))
             (<= (:api-version after) (:api-version before))) (refuse! :api-version-required :upgrade))
  nil)
(m/=> verify-upgrade! [:=> [:cat ac/Manifest ac/Manifest] :nil])

(defn- canonical-acceptance!
  "Require unique sorted export/decision identities and sorted unique adopted wrappers."
  [record phase]
  (unique! (:exports record) phase)
  (unique! (map :id (:decisions record)) phase)
  (doseq [decision (:decisions record)] (unique! (:wrappers decision) phase))
  (when-not (and (= (:exports record) (vec (sort (:exports record))))
                 (= (:decisions record) (vec (sort-by :id (:decisions record))))
                 (every? #(= (:wrappers %) (vec (sort (:wrappers %)))) (:decisions record)))
    (refuse! :api-order phase))
  nil)
(m/=> canonical-acceptance! [:=> [:cat ac/Acceptance ac/Phase] :nil])

(defn- check-decisions!
  "Every declared tool needs adopt/defer; every observed consumer wrapper must have an adoption.
   Trusted helpers, schemas and constants remain covered by the whole-manifest acknowledgement.
   Wrapper membership is evidence of exposure, not proof that the wrapper implements the operation."
  [document record]
  (let [tools (set (map :id (filter #(and (= :operation (:kind %)) (= :tool (:role %))) (:entries document))))
        decisions (:decisions record)
        adopted (filter #(= :adopt (:action %)) decisions)
        wrappers (set (mapcat :wrappers adopted))]
    (when-not (= tools (set (map :id decisions))) (refuse! :api-decisions :acceptance))
    (when-not (= (set (:exports record)) wrappers) (refuse! :api-wrapper-coverage :acceptance)))
  nil)
(m/=> check-decisions! [:=> [:cat ac/Manifest ac/Acceptance] :nil])

(defn- check-versions!
  "Require monotone versions and explicit acknowledgement of source, manifest or decision changes."
  [record previous]
  (if previous
    (let [api (:consumer-api-version record) old-api (:consumer-api-version previous)
          ack (:acceptance-version record) old-ack (:acceptance-version previous)]
      (when (or (< api old-api) (< ack old-ack)) (refuse! :api-version-regressed :acceptance))
      (when (and (not= (dissoc record :consumer-api-version :acceptance-version)
                       (dissoc previous :consumer-api-version :acceptance-version))
                 (not (or (> api old-api) (> ack old-ack)))) (refuse! :api-ack-required :acceptance))
      (when (and (not= (:exports record) (:exports previous)) (<= api old-api))
        (refuse! :api-export-version-required :acceptance)))
    (when-not (= 1 (:consumer-api-version record) (:acceptance-version record))
      (refuse! :api-initial-version :acceptance)))
  nil)
(m/=> check-versions! [:=> [:cat ac/Acceptance [:maybe ac/Acceptance]] :nil])

(defn check!
  "Validate an admitted request against independently observed consumer source/export identities.
   The caller must acquire inputs with bounded data admission, supply real export observations,
   and retain committed history: nil previous means first adoption, not unavailable history.
   Returns adopted/deferred operation IDs; throws closed Failure for all semantic refusals."
  [{document :manifest record :acceptance :keys [previous surface exports] :as request}]
  (when-not (m/validate ac/Request request) (refuse! :api-shape :acceptance))
  (verify! document)
  (canonical-acceptance! record :acceptance)
  (when previous (canonical-acceptance! previous :previous))
  (unique! exports :observation)
  (when-not (= (:artifact document) (:manifest record)) (refuse! :api-unacknowledged-manifest :acceptance))
  (when-not (= surface (:surface record)) (refuse! :api-surface-drift :observation))
  (when-not (= (vec (sort exports)) (:exports record)) (refuse! :api-export-drift :observation))
  (check-decisions! document record)
  (check-versions! record previous)
  {:status :accepted :manifest (:artifact document)
   :consumer-api-version (:consumer-api-version record) :acceptance-version (:acceptance-version record)
   :adopted (mapv :id (filter #(= :adopt (:action %)) (:decisions record)))
   :deferred (mapv :id (filter #(= :defer (:action %)) (:decisions record)))})
(m/=> check! [:=> [:cat ac/Request] ac/Result])
