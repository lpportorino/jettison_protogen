(ns gate.inputs
  "Bounded live filesystem input observation with explicit membership and streaming byte hashes.
   Trusted local workspace, no symlink following; an observation is not an immutable execution snapshot."
  (:require [clojure.string :as str]
            [gate.contract :as c]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption PathMatcher]
           [java.nio.file.attribute BasicFileAttributes PosixFilePermission]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def Root [:string {:min 1 :max 4096}])
(def Limits r/InputLimits)
(def ReadLimits
  "In-memory streaming budgets can exceed the persisted cache-input contract's 2 GiB ceiling."
  (into (subvec Limits 0 2)
        (map (fn [field] (if (= :bytes (first field))
                           [:bytes [:int {:min 1 :max 1099511627776}]] field)))
        (subvec Limits 2)))
(def default-limits {:files 100000 :entries 1000000 :bytes 1073741824 :depth 64})
(def Environment [:map-of {:max 256} r/EnvName [:maybe r/Text]])
(def Toolchains [:map-of {:max 64} c/Id c/Digest])
(def Evidence [:map {:closed true} [:environment Environment] [:toolchains Toolchains]])
(def Reason [:enum :missing-input :unreadable-input :unstable-input :unknown-toolchain :unsupported-input :budget-exhausted])
(def NativePath [:fn #(instance? Path %)])
(def Attributes [:fn #(instance? BasicFileAttributes %)])
(def Budget [:fn #(instance? clojure.lang.Atom %)])
(def Checkpoint [:=> [:cat] :nil])
(def Selectors [:vector {:max 1024} r/Input])
(def Memberships [:vector {:max 1024} r/Membership])
(def Matchers [:vector {:max 256} [:fn #(instance? PathMatcher %)]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Abort observation with a stable reason, without serializing paths, environment or platform errors."
  [reason]
  (throw (ex-info "Input observation refused" {:code :input-observation-refused :reason reason})))
(m/=> refuse! [:=> [:cat Reason] :nil])

(defn- spend!
  "Charge actual traversal/read work, including repeated membership scans, before retaining it."
  [budget limits kind amount]
  (when (> (get (swap! budget update kind + amount) kind) (get limits kind))
    (refuse! :budget-exhausted)))
(m/=> spend! [:=> [:cat Budget ReadLimits [:enum :files :entries :bytes] [:int {:min 0 :max 2147483647}]] :nil])

(defn- attributes
  "Read final-entry metadata without following a symlink; inaccessible entries are not absent."
  [path]
  (Files/readAttributes ^Path path BasicFileAttributes no-follow))
(m/=> attributes [:=> [:cat NativePath] Attributes])

(defn- absent?
  "Only a positively absent entry is optional; permission and other I/O failures still refuse."
  [path]
  (Files/notExists ^Path path no-follow))
(m/=> absent? [:=> [:cat NativePath] :boolean])

(defn- safe-path
  "Check every existing component beneath a real workspace root; refuse link traversal.
   This is not a race-proof sandbox against an adversarial concurrent directory replacement."
  [root relative]
  (loop [path root parts (str/split relative #"/")]
    (if-let [part (first parts)]
      (let [next-path (.resolve ^Path path ^String part)]
        (when (Files/isSymbolicLink next-path) (refuse! :unsupported-input))
        (recur next-path (next parts)))
      path)))
(m/=> safe-path [:=> [:cat NativePath r/Path] NativePath])

(defn- matcher
  "Compile filesystem glob syntax on the current JVM provider; malformed patterns refuse."
  [root pattern]
  (.getPathMatcher (.getFileSystem ^Path root) (str "glob:" pattern)))
(m/=> matcher [:=> [:cat NativePath r/Path] [:fn #(instance? PathMatcher %)]])

(defn- glob-base
  "Walk only the literal directory prefix before glob metacharacters, never the whole repository by default."
  [pattern]
  (let [parts (str/split pattern #"/")
        literal (take-while #(not (re-find #"[*?\[\]{]" %)) (butlast parts))]
    (str/join "/" literal)))
(m/=> glob-base [:=> [:cat r/Path] [:string {:max 4096}]])

(defn- excluded?
  "Exclusions are explicit root-relative globs in the keyed declaration, not hidden defaults."
  [matchers relative]
  (boolean (some #(.matches ^PathMatcher % ^Path relative) matchers)))
(m/=> excluded? [:=> [:cat Matchers NativePath] :boolean])

(defn- selected-paths
  "Enumerate one selector with a bounded lazy filesystem walk; preserve optional empty membership.
   File/tree selectors are exact kinds. Glob syntax is Java NIO glob, not Git pathspec or shell expansion.
   Included symlinks and special files refuse; excluded directories are pruned before descent."
  [root selector limits budget]
  (let [{:keys [kind path required? exclude]} selector
        base-relative (if (= :glob kind) (glob-base path) path)
        base (if (empty? base-relative) root (safe-path root base-relative))
        exclusions (mapv #(matcher root %) exclude)
        selection (when (= :glob kind) (matcher root path))]
    (if (absent? base)
      (if required? (refuse! :missing-input) [])
      (let [attrs (attributes base)]
        (when (or (and (= :file kind) (not (.isRegularFile ^BasicFileAttributes attrs)))
                  (and (not= :file kind) (not (.isDirectory ^BasicFileAttributes attrs))))
          (refuse! :unsupported-input))
        ;; Explicit stack allows exclusions to prune directories before opening them.
        (loop [pending [[base 0]] result []]
          (if-let [[current depth] (peek pending)]
            (let [relative (.relativize ^Path root ^Path current)
                  relative-text (str relative)
                  _ (spend! budget limits :entries 1)
                  attrs (attributes current)]
              (cond
                (excluded? exclusions relative) (recur (pop pending) result)
                (.isSymbolicLink ^BasicFileAttributes attrs) (refuse! :unsupported-input)
                (.isDirectory ^BasicFileAttributes attrs)
                (do
                  (when (>= depth (:depth limits)) (refuse! :budget-exhausted))
                  (let [next-pending (with-open [children (Files/newDirectoryStream ^Path current)]
                                       (reduce (fn [stack child]
                                                 (spend! budget limits :entries 1)
                                                 (conj stack [child (inc depth)])) (pop pending) children))]
                    (recur next-pending result)))
                (not (.isRegularFile ^BasicFileAttributes attrs)) (refuse! :unsupported-input)
                (or (not= :glob kind) (.matches ^PathMatcher selection relative))
                (do (when-not (m/validate r/Path relative-text) (refuse! :unsupported-input))
                    (when (>= (count result) (:files limits)) (refuse! :budget-exhausted))
                    (recur (pop pending) (conj result relative-text)))
                :else (recur (pop pending) result)))
            (if (and required? (empty? result)) (refuse! :missing-input) (vec (sort result)))))))))
(m/=> selected-paths [:=> [:cat NativePath r/Input Limits Budget] [:vector {:max 1000000} r/Path]])

(defn- resolve-membership
  "Keep every selector, including empty optional/excluded selections; never collapse duplicate IDs."
  [root selectors limits budget]
  (mapv (fn [selector] {:selector (:id selector) :paths (selected-paths root selector limits budget)}) selectors))
(m/=> resolve-membership [:=> [:cat NativePath Selectors Limits Budget] Memberships])

(defn- unchanged-attributes?
  "Detect observed identity/size/mtime changes around a byte read; equality is not an ABA proof."
  [before after]
  (and (.isRegularFile ^BasicFileAttributes after)
       (= (.fileKey ^BasicFileAttributes before) (.fileKey ^BasicFileAttributes after))
       (= (.size ^BasicFileAttributes before) (.size ^BasicFileAttributes after))
       (= (.lastModifiedTime ^BasicFileAttributes before) (.lastModifiedTime ^BasicFileAttributes after))))
(m/=> unchanged-attributes? [:=> [:cat Attributes Attributes] :boolean])

(defn executable-mode?
  "Observe POSIX execute permission bits, independent of noexec mounts and caller credentials.
   Unsupported permission providers refuse through the caller's incomplete-observation boundary."
  [path]
  (let [permissions (Files/getPosixFilePermissions path no-follow)]
    (boolean (some #(.contains permissions %) [PosixFilePermission/OWNER_EXECUTE PosixFilePermission/GROUP_EXECUTE PosixFilePermission/OTHERS_EXECUTE]))))
(m/=> executable-mode? [:=> [:cat NativePath] :boolean])

(defn- hash-file-checked
  "Stream bytes through a fixed 64 KiB buffer. Every observation rehashes; size/mtime never memoize SHA.
   Open the final component no-follow, and reject observed changes in identity, size, mtime or execution mode."
  [root relative limits budget checkpoint]
  (checkpoint)
  (spend! budget limits :files 1)
  (let [path (safe-path root relative) before (attributes path)
        executable? (executable-mode? path) digest (MessageDigest/getInstance "SHA-256")]
    (when-not (.isRegularFile ^BasicFileAttributes before) (refuse! :unsupported-input))
    (with-open [channel (Files/newByteChannel path (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
      (let [buffer (ByteBuffer/allocate 65536)]
        (loop []
          (checkpoint)
          (let [n (.read channel buffer)]
            (when (not (neg? n))
              (spend! budget limits :bytes n)
              (.flip buffer) (.update digest buffer) (.clear buffer)
              (recur))))))
    (when (or (not (unchanged-attributes? before (attributes path)))
              (not= executable? (executable-mode? path)))
      (refuse! :unstable-input))
    {:path relative :digest (.formatHex (HexFormat/of) (.digest digest)) :executable? executable?}))
(m/=> hash-file-checked [:=> [:cat NativePath r/Path ReadLimits Budget Checkpoint] r/File])

(defn- hash-file
  "Hash one file with the normal uninterrupted input observer; preserve its shared budget."
  [root relative limits budget]
  (hash-file-checked root relative limits budget (fn [] nil)))
(m/=> hash-file [:=> [:cat NativePath r/Path ReadLimits Budget] r/File])

(defn read-file!
  "Hash one root-relative regular file, spending a shared initialized observation budget.
   The root must be a real trusted directory; budget starts with :files/:entries/:bytes
   zero and may be shared across roots. Refuses links and observed byte-read changes.
   An optional cooperative checkpoint runs before each 64 KiB read; regular filesystem reads
   themselves are not forcibly interruptible. Failures and checkpoint exceptions reach the caller."
  ([root relative limits budget] (hash-file root relative limits budget))
  ([root relative limits budget checkpoint] (hash-file-checked root relative limits budget checkpoint)))
(m/=> read-file! [:function [:=> [:cat NativePath r/Path ReadLimits Budget] r/File]
                  [:=> [:cat NativePath r/Path ReadLimits Budget Checkpoint] r/File]])

(defn system-environment
  "Observe declared environment names, preserving absent versus empty; values stay local to the consumer.
   A process adapter must launch with this same restricted environment before claiming complete input coverage."
  [names]
  (into {} (map (fn [env-name] [env-name (System/getenv ^String env-name)]) names)))
(m/=> system-environment [:=> [:cat [:vector {:max 256} r/EnvName]] Environment])

(defn observe!
  "Return a closed Snapshot of declared filesystem inputs, or explicit incomplete evidence.
   Bounds cover unique files, bytes actually read, traversal entries (both membership passes)
   and directory depth. Exclusions are explicit keyed globs. File bytes, membership and
   executable state are observed anew; no metadata-only digest cache is used.

   Evidence supplies the effective environment and attested toolchain identities. Missing
   toolchains or environment coverage refuse reuse. The caller still owns process isolation,
   toolchain attestation and complete declarations. Symlinks, special files, unsupported
   globs, I/O errors and exhausted budgets never produce a complete snapshot. A trusted
   workspace is assumed; this is neither an adversarial filesystem sandbox nor an immutable
   input tree. Pre/post equality does not prove no edits/reverts during execution."
  [directory gate evidence limits]
  (let [empty-snapshot {:files [] :membership [] :environment [] :toolchains [] :complete? false :reason :unsupported-input}]
    (try
      (when-not (every? #(contains? (:environment evidence) %) (:environment gate)) (refuse! :unsupported-input))
      (when-not (every? #(contains? (:toolchains evidence) %) (:toolchains gate)) (refuse! :unknown-toolchain))
      (let [root (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))
            _ (when-not (Files/isDirectory root no-follow) (refuse! :unsupported-input))
            budget (atom {:files 0 :entries 0 :bytes 0})
            membership (resolve-membership root (:inputs gate) limits budget)
            paths (sort (set (mapcat :paths membership)))
            files (mapv #(hash-file root % limits budget) paths)
            after (resolve-membership root (:inputs gate) limits budget)]
        (when-not (= membership after) (refuse! :unstable-input))
        {:files files :membership membership
         :environment (mapv (fn [env-name] {:name env-name :value (get-in evidence [:environment env-name])}) (:environment gate))
         :toolchains (mapv (fn [tool-name] {:name tool-name :digest (get-in evidence [:toolchains tool-name])}) (:toolchains gate))
         :complete? true :reason nil})
      (catch clojure.lang.ExceptionInfo error
        (if (= :input-observation-refused (:code (ex-data error)))
          (assoc empty-snapshot :reason (:reason (ex-data error))) (throw error)))
      (catch java.util.regex.PatternSyntaxException _ (assoc empty-snapshot :reason :unsupported-input))
      (catch UnsupportedOperationException _ (assoc empty-snapshot :reason :unsupported-input))
      (catch java.nio.file.InvalidPathException _ (assoc empty-snapshot :reason :unsupported-input))
      (catch java.nio.file.DirectoryIteratorException _ (assoc empty-snapshot :reason :unreadable-input))
      (catch SecurityException _ (assoc empty-snapshot :reason :unreadable-input))
      (catch java.io.IOException _ (assoc empty-snapshot :reason :unreadable-input)))))
(m/=> observe! [:=> [:cat Root r/Gate Evidence Limits] r/Snapshot])

(defn outputs!
  "Observe exactly the declared regular output files with the same bounded hashing rules.
   Return nil if any output cannot be established; an empty vector means no outputs were declared."
  [directory gate evidence limits]
  (let [selectors (mapv (fn [i path] {:id (str "output-" i) :kind :file :path path :required? true})
                        (range) (:outputs gate))
        snapshot (observe! directory (assoc gate :inputs selectors) evidence limits)]
    (when (:complete? snapshot) (:files snapshot))))
(m/=> outputs! [:=> [:cat Root r/Gate Evidence Limits] [:maybe r/Files]])
