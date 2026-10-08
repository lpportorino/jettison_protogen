(ns gate.repository-identity
  "Validate repository content identities and project compact portable provenance for run archives."
  (:require [clojure.string :as str]
            [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.repository-contract :as rc]
            [malli.core :as m]))

(def RepositoryIndex [:map-of {:min 1 :max 128} rc/Location ac/Repository])
(def ContainsPath [:=> [:cat rc/Location] :boolean])
(defn- refuse!
  "Return a closed identity failure without copying paths, filenames or rejected evidence."
  [code]
  (throw (ex-info "Repository identity refused" {:code code})))
(m/=> refuse! [:=> [:cat [:enum :archive-repository :archive-policy]] :nil])

(defn beneath?
  "Compare whole relative path components; similarly prefixed siblings remain distinct."
  [ancestor path]
  (or (= ancestor ".") (= ancestor path) (str/starts-with? path (str ancestor "/"))))
(m/=> beneath? [:=> [:cat rc/Location rc/Location] :boolean])

(defn- administrative?
  "Identify Git administrative components anywhere in a relative path."
  [path]
  (boolean (some #{".git"} (str/split path #"/"))))
(m/=> administrative? [:=> [:cat rc/Location] :boolean])

(defn- ordered?
  "Require sorted unique identities rather than silently normalizing ambiguous evidence."
  [paths]
  (= paths (vec (sort (distinct paths)))))
(m/=> ordered? [:=> [:cat [:vector {:max 1000000} rc/Location]] :boolean])

(defn require-policy!
  "Require normalized exclusions/protected roots with no administrative names or overlap."
  [{:keys [excluded protected] :as policy}]
  (when (or (not (m/validate rc/Policy policy)) (not (ordered? excluded)) (not (ordered? protected))
            (some administrative? (concat excluded protected))
            (some (fn [omit] (some #(or (beneath? omit %) (beneath? % omit)) protected)) excluded))
    (refuse! :archive-policy))
  policy)
(m/=> require-policy! [:=> [:cat rc/Policy] rc/Policy])

(defn- full-path
  "Join a checkout-relative path without adding a dot prefix to root entries."
  [location relative]
  (if (= "." location) relative (str location "/" relative)))
(m/=> full-path [:=> [:cat rc/Location rc/Location] [:string {:min 1 :max 8193}]])

(defn- verify-entry!
  "Reject administrative/excluded entries and reconcile every Git link with exactly its child record."
  [excluded location entry child]
  (let [full (full-path location (:path entry))
        gitlink? (= "160000" (get-in entry [:index :mode]))
        submodule? (= :submodule (:kind entry))]
    (when (or (> (count full) 4096) (administrative? (:path entry))
              (some #(beneath? % full) excluded) (not= gitlink? submodule?))
      (refuse! :archive-repository))
    (when (and submodule?
               (not (and child (= (:revision entry) (:revision child)) (= (:content entry) (:content child)))))
      (refuse! :archive-repository)))
  nil)
(m/=> verify-entry! [:=> [:cat rc/Exclusions rc/Location rc/Entry [:maybe ac/Repository]] :nil])

(defn- ancestor-entry?
  "Check each slash boundary against the entry set; lexical neighbors need not share ancestry."
  [contains-path? path]
  (loop [start 0]
    (if-let [separator (str/index-of path "/" start)]
      (if (contains-path? (subs path 0 separator)) true (recur (inc separator)))
      false)))
(m/=> ancestor-entry? [:=> [:cat ContainsPath rc/Location] :boolean])

(defn- verify-tree!
  "Check sorted disjoint entry paths, nested bindings and the exact canonical tree digest."
  [excluded index tree]
  (let [paths (mapv :path (:entries tree)) membership (set paths)
        contains-path? #(contains? membership %)]
    (when (or (not (ordered? paths))
              (some #(ancestor-entry? contains-path? %) paths))
      (refuse! :archive-repository))
    (doseq [entry (:entries tree)]
      (verify-entry! excluded (:path tree) entry (get index (full-path (:path tree) (:path entry)))))
    (when-not (= (:content tree) (canonical/sha256 (canonical/encode (dissoc tree :content) 134217728)))
      (refuse! :archive-repository)))
  nil)
(m/=> verify-tree! [:=> [:cat rc/Exclusions RepositoryIndex rc/Repository] :nil])

(defn- header
  "Project a typed checkout header; per-entry validation must not rescan all file inventories."
  [tree]
  (assoc (select-keys tree [:path :revision :content]) :entries (count (:entries tree))))
(m/=> header [:=> [:cat rc/Repository] ac/Repository])

(defn require-observation!
  "Recompute all content hashes and reconcile the complete flattened checkout hierarchy.
   This validates evidence consistency, not filesystem truth or producer trust. Every nonroot
   checkout needs one owning Git link; excluded paths cannot appear in observed entries."
  [observation]
  (when-not (m/validate rc/Observation observation) (refuse! :archive-repository))
  (require-policy! (:policy observation))
  (let [repositories (:repositories observation) paths (mapv :path repositories)
        index (into {} (map (juxt :path header) repositories))
        children (into [] (comp (mapcat (fn [tree] (map #(assoc % :owner (:path tree)) (:entries tree))))
                                (filter #(= :submodule (:kind %)))
                                (map #(full-path (:owner %) (:path %))) (take 129)) repositories)]
    (when-not (and (= "." (first paths)) (ordered? paths)
                   (= (vec (sort children)) (subvec paths 1)))
      (refuse! :archive-repository))
    (doseq [tree repositories] (verify-tree! (get-in observation [:policy :excluded]) index tree))
    (when-not (= (:content observation)
                 (canonical/sha256 (canonical/encode (dissoc observation :content) 134217728)))
      (refuse! :archive-repository)))
  observation)
(m/=> require-observation! [:=> [:cat rc/Observation] rc/Observation])

(defn summarize
  "Validate the full private observation and retain bounded checkout headers for a committed archive.
   Content digests bind the omitted file inventory. Keep that inventory separately when per-file
   auditing is needed; the summary cannot independently reconstruct or attest those bytes."
  [observation]
  (require-observation! observation)
  (assoc (select-keys observation [:profile :policy :content])
         :repositories (mapv header (:repositories observation))))
(m/=> summarize [:=> [:cat rc/Observation] ac/Snapshot])

(defn require-summary!
  "Validate compact header ordering and policy; full content attestation needs the original observation."
  [summary]
  (when-not (m/validate ac/Snapshot summary) (refuse! :archive-repository))
  (require-policy! (:policy summary))
  (let [paths (mapv :path (:repositories summary))]
    (when-not (and (= "." (first paths)) (ordered? paths)
                   (not-any? administrative? paths)
                   (not-any? (fn [path] (some #(beneath? % path) (get-in summary [:policy :excluded]))) paths))
      (refuse! :archive-repository)))
  summary)
(m/=> require-summary! [:=> [:cat ac/Snapshot] ac/Snapshot])
