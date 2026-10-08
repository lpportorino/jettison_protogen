(ns gate.repository
  "Git-visible working-tree provenance with bounded bytes, membership and recursive checkout identity."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.inputs :as inputs]
            [gate.repository-contract :as rc]
            [gate.repository-git :as git]
            [gate.repository-identity :as identity]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

(set! *warn-on-reflection* true)
(def Limits
  [:map {:closed true} [:inputs inputs/ReadLimits] [:repositories [:int {:min 1 :max 128}]]])
(def default-limits {:inputs inputs/default-limits :repositories 128})
(def State
  [:map {:closed true} [:policy rc/Policy] [:settings git/Settings] [:limits Limits]
   [:budget inputs/Budget] [:repositories inputs/Budget] [:cancellation inputs/Budget]])
(def Listing [:map {:closed true} [:revision rc/ObjectId] [:index rc/Index] [:untracked rc/Paths]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn beneath?
  "Compare whole relative path components; reports never excludes reports-source."
  [ancestor path]
  (identity/beneath? ancestor path))
(m/=> beneath? [:=> [:cat rc/Location rc/Location] :boolean])

(defn policy
  "Normalize explicit report exclusions and required input roots; reject overlap in either direction.
   Callers supply actual source/declaration/input roots, including the exclusion-policy file.
   This check cannot discover undeclared runtime reads. A protected whole root forbids exclusions."
  [excluded protected]
  (try (identity/require-policy! {:excluded (vec (sort (distinct excluded))) :protected (vec (sort (distinct protected)))})
       (catch clojure.lang.ExceptionInfo error
         (if (= :archive-policy (:code (ex-data error))) (git/refuse! :repository-policy) (throw error)))))
(m/=> policy [:=> [:cat rc/Exclusions rc/Protected] rc/Policy])

(defn- digest
  "Hash closed repository material using the same bounded canonical encoding as run graphs."
  [value]
  (canonical/sha256 (canonical/encode value 134217728)))
(m/=> digest [:=> [:cat rc/Encodable] c/Digest])

(defn- checkpoint!
  "Cooperatively stop between Git queries, file entries and bounded streaming reads."
  [state]
  (when @(:cancellation state) (git/refuse! :repository-cancelled))
  nil)
(m/=> checkpoint! [:=> [:cat State] :nil])

(defn- charge!
  "Charge aggregate work across all nested checkouts, including both Git membership passes."
  [state kind amount]
  (checkpoint! state)
  (let [used (get (swap! (:budget state) update kind + amount) kind)
        maximum (get-in state [:limits :inputs kind])]
    (when (> used maximum) (git/budget! kind used maximum)))
  nil)
(m/=> charge! [:=> [:cat State [:enum :files :entries :bytes] [:int {:min 0 :max 2147483647}]] :nil])

(defn- command!
  "Run one bounded Git query and charge its retained UTF-8 bytes against the total observation budget."
  [state root arguments]
  (checkpoint! state)
  (let [text (git/command! (str root) (:settings state) arguments (:cancellation state))]
    (charge! state :bytes (canonical/utf8-size text))
    text))
(m/=> command! [:=> [:cat State inputs/NativePath git/Arguments] git/Text])

(defn- listing!
  "Observe the actual checkout boundary, HEAD, index and nonignored untracked membership.
   An uninitialized submodule must not fall through to its parent's Git repository."
  [state root]
  (let [top (git/line (command! state root ["rev-parse" "--show-toplevel"]))
        revision (git/line (command! state root ["rev-parse" "--verify" "HEAD"]))
        limit (get-in state [:limits :inputs :files])]
    (when-not (= (str root) top) (git/refuse! :repository-unsupported))
    (when-not (m/validate rc/ObjectId revision) (git/refuse! :repository-protocol))
    (let [index (git/index (command! state root ["ls-files" "--stage" "-z"]) limit)
          untracked (git/untracked (command! state root ["ls-files" "--others" "--exclude-standard" "-z"]) limit)]
      (charge! state :entries (+ (count index) (count untracked)))
      (when (some (set (map :path index)) untracked) (git/refuse! :repository-protocol))
      {:revision revision :index index :untracked untracked})))
(m/=> listing! [:=> [:cat State inputs/NativePath] Listing])

(defn- entry-path
  "Resolve beneath a trusted root without following symlink ancestors; a symlink leaf is allowed."
  [root relative depth-limit]
  (let [parts (str/split relative #"/")]
    (when (> (count parts) depth-limit) (git/budget! :depth (count parts) depth-limit))
    (reduce (fn [^Path path part]
              (when (Files/isSymbolicLink path) (git/refuse! :repository-unsupported))
              (.resolve path ^String part)) root parts)))
(m/=> entry-path [:=> [:cat inputs/NativePath r/Path [:int {:min 1 :max 256}]] inputs/NativePath])

(defn- symlink!
  "Hash the symlink target without traversing it; refuse lossy native-name conversion or observed changes."
  [state path]
  (charge! state :files 1)
  (let [^BasicFileAttributes before (Files/readAttributes ^Path path ^Class BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow)
        target (Files/readSymbolicLink path) text (str target)]
    (when (or (> (count text) 4096) (not= target (Path/of text (make-array String 0))))
      (git/refuse! :repository-unsupported))
    (charge! state :bytes (canonical/utf8-size text))
    (let [^BasicFileAttributes after (Files/readAttributes ^Path path ^Class BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow)]
      (when-not (and (.isSymbolicLink after) (= (.fileKey before) (.fileKey after))
                     (= (.lastModifiedTime before) (.lastModifiedTime after))
                     (= target (Files/readSymbolicLink path)))
        (git/refuse! :repository-unstable)))
    (canonical/sha256 text)))
(m/=> symlink! [:=> [:cat State inputs/NativePath] c/Digest])

(declare observe-tree!)

(defn- file!
  "Use the shared streaming byte observer and expose the exhausted budget as a closed diagnostic."
  [state root relative]
  (try (inputs/read-file! root relative (get-in state [:limits :inputs]) (:budget state) #(checkpoint! state))
       (catch clojure.lang.ExceptionInfo error
         (when (= {:code :input-observation-refused :reason :budget-exhausted} (ex-data error))
           (doseq [kind [:files :entries :bytes]
                   :let [used (get @(:budget state) kind) maximum (get-in state [:limits :inputs kind])]
                   :when (> used maximum)]
             (git/budget! kind used maximum)))
         (throw error))))
(m/=> file! [:=> [:cat State inputs/NativePath r/Path] r/File])

(defn- observe-entry!
  "Observe worktree bytes regardless of index object; recurse only declared Git links."
  [state root location relative indexed depth]
  (let [path (entry-path root relative (get-in state [:limits :inputs :depth]))
        base {:path relative :index indexed}]
    (cond
      (= "160000" (:mode indexed))
      (do
        (when-not (Files/isDirectory path no-follow) (git/refuse! :repository-unsupported))
        (let [child (observe-tree! state path (if (= "." location) relative (str location "/" relative)) (inc depth))]
          (assoc base :kind :submodule :revision (:revision child) :content (:content child))))
      (Files/isSymbolicLink path) (assoc base :kind :symlink :digest (symlink! state path))
      (Files/notExists path no-follow)
      (if indexed (do (charge! state :files 1) (assoc base :kind :deleted)) (git/refuse! :repository-unstable))
      :else (merge base {:kind :file} (file! state root relative)))))
(m/=> observe-entry! [:=> [:cat State inputs/NativePath rc/Location r/Path [:maybe rc/IndexTerm]
                           [:int {:min 0 :max 256}]] rc/Entry])

(defn- observe-tree!
  "Observe one checkout and its children, rejecting membership/index/HEAD changes around byte reads."
  [state root location depth]
  (when (>= depth (get-in state [:limits :inputs :depth]))
    (git/budget! :depth (inc depth) (get-in state [:limits :inputs :depth])))
  (when (>= (count @(:repositories state)) (get-in state [:limits :repositories]))
    (git/budget! :repositories (inc (count @(:repositories state))) (get-in state [:limits :repositories])))
  ;; Reserve a slot before descending; a deep chain also consumes the repository budget.
  (swap! (:repositories state) assoc location nil)
  (let [before (listing! state root)
        indexed (into {} (map (fn [entry] [(:path entry) (dissoc entry :path)]) (:index before)))
        members (sort (concat (keys indexed) (:untracked before)))
        entries (into [] (comp
                          (remove (fn [relative]
                                    (let [full (if (= "." location) relative (str location "/" relative))]
                                      (some #(beneath? % full) (get-in state [:policy :excluded])))))
                          (map #(observe-entry! state root location % (get indexed %) depth))) members)
        tree {:path location :revision (:revision before) :entries entries}]
    (when-not (= before (listing! state root)) (git/refuse! :repository-unstable))
    (let [result (assoc tree :content (digest tree))]
      (swap! (:repositories state) assoc location result)
      result)))
(m/=> observe-tree! [:=> [:cat State inputs/NativePath rc/Location [:int {:min 0 :max 256}]] rc/Repository])

(defn observe!
  "Observe a committed Git checkout, dirty files and initialized nested Git links under explicit budgets.
   Policy exclusions name generated report prefixes; protected paths name actual declared inputs.
   Git logs must live in an existing directory outside the observed root. All native failures are
   closed Failure records in ex-data; no partial observation can earn a content identity.

   The identity includes HEAD, index modes/objects, actual file bytes and POSIX executable bits,
   symlink targets, tracked deletions, nonignored untracked membership and actual child checkouts.
   Ignored files, Git administration, toolchains and undeclared reads are outside this profile.
   Record separate evidence for them. This is a trusted-workspace observation, not a sandbox or
   atomic snapshot: pre/post equality cannot detect intervening edits and reverts. No cache memo.
   Optional cancellation cooperates between bounded reads and reaches running Git processes;
   filesystem I/O and canonical encoding are not a hard real-time deadline."
  ([directory requested-policy settings limits]
   (observe! directory requested-policy settings limits (atom false)))
  ([directory requested-policy settings limits cancellation]
   (try
     (when-not (and (m/validate inputs/Root directory) (m/validate rc/Policy requested-policy)
                    (m/validate git/Settings settings) (m/validate Limits limits))
       (git/refuse! :repository-policy))
     (let [root (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))
           logs (.toRealPath (Path/of (:log-directory settings) (make-array String 0)) (make-array LinkOption 0))
           checked (policy (:excluded requested-policy) (:protected requested-policy))
           state {:policy checked :settings settings :limits limits
                  :budget (atom {:files 0 :entries 0 :bytes 0}) :repositories (atom {}) :cancellation cancellation}]
       (when (or (.startsWith logs root) (not (Files/isDirectory root no-follow))
                 (not (Files/isDirectory logs no-follow))) (git/refuse! :repository-policy))
       (observe-tree! state root "." 0)
       (let [material {:schema/version 1 :profile :git-visible-posix-v1 :policy checked
                       :repositories (mapv val (sort-by key @(:repositories state)))}]
         (assoc material :content (digest material))))
     (catch clojure.lang.ExceptionInfo error
       (let [{:keys [code reason]} (ex-data error)]
         (cond
           (m/validate rc/Failure (ex-data error)) (throw error)
           (= code :input-observation-refused)
           (git/refuse! (case reason :budget-exhausted :repository-budget :unstable-input :repository-unstable
                              :unsupported-input :repository-unsupported :repository-unreadable))
           (= code :encoded-byte-limit) (git/refuse! :repository-budget)
           :else (throw error))))
     (catch UnsupportedOperationException _ (git/refuse! :repository-unsupported))
     (catch IllegalArgumentException _ (git/refuse! :repository-policy))
     (catch SecurityException _ (git/refuse! :repository-unreadable))
     (catch java.io.IOException _ (git/refuse! :repository-unreadable)))))
(m/=> observe! [:function [:=> [:cat inputs/Root rc/Policy git/Settings Limits] rc/Observation]
                [:=> [:cat inputs/Root rc/Policy git/Settings Limits inputs/Budget] rc/Observation]])
