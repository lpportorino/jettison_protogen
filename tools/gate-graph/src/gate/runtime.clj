(ns gate.runtime
  "Bounded local runtime identity for contained cache decisions; unavailable evidence disables reuse."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.container :as container]
            [gate.contract :as c]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute]))

(def Failure [:map {:closed true} [:code [:enum :runtime-controller :runtime-daemon :runtime-machine :runtime-policy]]])
(def Result [:map {:closed true} [:directory inputs/Root] [:observation r/RuntimeObservation]])
(def Paths [:vector {:min 1 :max 1024} inputs/Root])
(def Terms [:vector {:max 10000} [:string {:max 2097152}]])
(def controller-limits {:files 100000 :entries 1000000 :bytes 1073741824 :depth 64})

(defn- refuse!
  "Return only a stable capability reason; local paths and daemon details stay in local scratch."
  [code]
  (throw (ex-info "Runtime identity unavailable" {:code code})))
(m/=> refuse! [:=> [:cat [:enum :runtime-controller :runtime-daemon :runtime-machine :runtime-policy]] :nil])

(defn terms-digest
  "Hash ordered terms with fixed-width member hashes, preserving boundaries and duplicate terms."
  [terms]
  (canonical/sha256 (str "gate-runtime-terms-v1\n" (str/join "\n" (map canonical/sha256 terms)))))
(m/=> terms-digest [:=> [:cat Terms] c/Digest])

(defn- bounded-text
  "Bound actual bytes even for procfs files whose reported size is zero."
  [path limit]
  (with-open [stream (Files/newInputStream (Path/of path (make-array String 0)) (make-array java.nio.file.OpenOption 0))]
    (let [content (.readNBytes stream (inc limit))]
      (when (> (alength content) limit) (refuse! :runtime-machine))
      (String. content java.nio.charset.StandardCharsets/UTF_8))))
(m/=> bounded-text [:=> [:cat inputs/Root [:int {:min 1 :max 2097152}]] [:string {:max 2097152}]])

(defn machine-digest
  "Bind boot, kernel architecture and stable CPU model/features; never turn load or MHz into work ticks."
  []
  (let [cpu (bounded-text "/proc/cpuinfo" 2097152)
        features (->> (str/split-lines cpu)
                      (filter #(re-find #"^(?:vendor_id|cpu family|model|model name|stepping|microcode|flags|Features|CPU implementer|CPU architecture|CPU variant|CPU part|CPU revision)\s*:" %))
                      (map str/trim) distinct sort vec)
        boot (str/trim (bounded-text "/proc/sys/kernel/random/boot_id" 256))]
    (when (or (empty? features) (not (re-matches #"[0-9a-f-]{36}" boot))) (refuse! :runtime-machine))
    (terms-digest (into ["machine-v1" boot (System/getProperty "os.version") (System/getProperty "os.arch")] features))))
(m/=> machine-digest [:=> [:cat] c/Digest])

(defn content-digest
  "Hash real bytes/membership/modes for ordered local classpath or runtime entries with one shared budget.
   Real absolute entry names affect identity conservatively but are only exposed inside this hash.
   Existing empty classpath directories contribute empty membership, not missing work.
   Root entry resolution must agree before/after observation; nested symlinks refuse.
   No metadata memo is used. Loaded controller code still requires immutable inputs."
  [paths limits]
  (let [resolved (mapv #(str (.toRealPath (Path/of % (make-array String 0)) (make-array LinkOption 0))) paths)
        marker (canonical/sha256 "runtime-byte-observer-v1")
        gate {:id "runtime-bytes" :label "Runtime bytes" :command ["/unused"] :cwd "."
              :inputs (mapv (fn [i path]
                              (let [directory? (Files/isDirectory (Path/of path (make-array String 0)) (make-array LinkOption 0))]
                                {:id (str "entry-" i) :kind (if directory? :tree :file)
                                 :path (subs path 1) :required? (not directory?)})) (range) resolved)
              :outputs [] :environment [] :toolchains ["observer"] :dependencies [] :cache :always :network :denied
              :coverage {:expected marker :minimum 1}}
        snapshot (inputs/observe! "/" gate {:environment {} :toolchains {"observer" marker}} limits)]
    (when-not (:complete? snapshot) (refuse! :runtime-controller))
    (when-not (= resolved (mapv #(str (.toRealPath (Path/of % (make-array String 0)) (make-array LinkOption 0))) paths))
      (refuse! :runtime-controller))
    (let [encoded (canonical/encode snapshot 134217728)]
      (when (> (count encoded) 2097152) (refuse! :runtime-controller))
      (terms-digest [(terms-digest resolved) encoded]))))
(m/=> content-digest [:=> [:cat Paths inputs/Limits] c/Digest])

(defn- jdk-files
  "Enumerate bounded JDK runtime/config bytes. Resolve each top-level library/config root,
   including distro config-directory links; reject nested directory links and depth truncation."
  [directory]
  (let [home (Path/of directory (make-array String 0))
        result (vec
                (concat [(str (.resolve home "bin/java")) (str (.resolve home "release"))]
                        (mapcat (fn [directory]
                                  (let [root (.toRealPath (.resolve home ^String directory) (make-array LinkOption 0))]
                                    (with-open [paths (Files/walk root 32 (make-array java.nio.file.FileVisitOption 0))]
                                      (let [entries (vec (take 2049 (iterator-seq (.iterator paths))))]
                                        (when (> (count entries) 2048) (refuse! :runtime-controller))
                                        (when (some #(and (Files/isSymbolicLink ^Path %) (Files/isDirectory ^Path % (make-array LinkOption 0))) entries)
                                          (refuse! :runtime-controller))
                                        (when (some #(and (= 32 (.getNameCount (.relativize root ^Path %)))
                                                          (Files/isDirectory ^Path % (make-array LinkOption 0))) entries)
                                          (refuse! :runtime-controller))
                                        (mapv str (sort-by str (remove #(Files/isDirectory ^Path % (make-array LinkOption 0)) entries)))))))
                                ["lib" "conf"])))]
    (when (> (count result) 1024) (refuse! :runtime-controller))
    result))
(m/=> jdk-files [:=> [:cat inputs/Root] Paths])

(defn- native-libraries
  "Include actual mapped JVM native-library bytes outside JAVA_HOME; missing/deleted mappings refuse."
  []
  (let [mapped (bounded-text "/proc/self/maps" 2097152)
        paths (->> (str/split-lines mapped)
                   (keep #(second (re-find #"\s(/.*)$" %)))
                   (filter #(re-find #"/(?:[^/]*\.so(?:\.[^/]*)?|ld-[^/]+)(?: \(deleted\))?$" %))
                   distinct sort vec)]
    (when (or (empty? paths) (> (count paths) 1024) (some #(str/ends-with? % " (deleted)") paths))
      (refuse! :runtime-controller))
    paths))
(m/=> native-libraries [:=> [:cat] Paths])

(defn controller-digest
  "Fingerprint Docker executable, JDK runtime/config and ordered JVM classpath bytes and launch arguments.
   Controller source/classpath must remain immutable while loaded; dynamic unlisted load-file/eval and
   externally replaced loaded libraries are outside this trusted controller contract. Broad classpaths
   can refuse the explicit 1 GiB budget rather than being silently truncated."
  [docker]
  (let [arguments (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
        ;; The first MXBean access loads native management libraries. Include them in the same probe.
        classpath (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
        jdk (jdk-files (System/getProperty "java.home"))
        covered (set (map #(str (.toRealPath (Path/of % (make-array String 0)) (make-array LinkOption 0))) jdk))
        paths (vec (concat [docker] jdk (remove covered (native-libraries)) classpath))
        properties (mapv #(str % "=" (System/getProperty %))
                         ["java.version" "java.vm.version" "java.vendor" "java.vm.name" "file.encoding" "user.language" "user.country"])]
    (when (or (> (count paths) 1024) (some str/blank? paths) (> (count arguments) 256)) (refuse! :runtime-controller))
    (terms-digest [(content-digest paths controller-limits) (terms-digest arguments) (terms-digest properties)])))
(m/=> controller-digest [:=> [:cat inputs/Root] c/Digest])

(defn- protocol!
  "Run a bounded Docker protocol query with cleared environment and a fresh private config directory."
  [request directory step arguments cancellation]
  (let [log (str step ".log")
        result (process/run! {:directory directory :cwd "."
                              :command (into [(:docker request) "--host" (str "unix://" (:socket request))
                                              "--config" (str directory "/docker-config")] arguments)
                              :environment {} :stdin nil :log-directory directory :log log
                              :limits (assoc process/default-limits :timeout-ms 10000 :log-bytes 65536)} cancellation)]
    (when-not (and (= :exited (:status result)) (= 0 (:exit result))
                   (not (:cleanup-required? result)) (:observed-processes-stopped? result)) (refuse! :runtime-daemon))
    (str/trim (bounded-text (str directory "/" log) 65536))))
(m/=> protocol! [:=> [:cat container/Request inputs/Root [:enum "version" "info" "image"]
                      [:vector {:min 1 :max 16} process/Argument] coordinator/Cancellation] [:string {:max 65536}]])

(defn probe!
  "Observe the effective local execution runtime before reuse or receipt admission.
   Portable records contain digests, not raw daemon IDs, root paths or controller filenames.
   A failure records :unavailable so callers can still execute uncached. A trusted same-kernel
   local daemon and immutable loaded controller are required, as for the container backend.
   Per-probe logs are retained in owned scratch. No probe failure becomes a guessed identity."
  [request cancellation]
  (let [origin (System/nanoTime)
        directory (str (Files/createTempDirectory (Path/of (:scratch-parent request) (make-array String 0)) "runtime-" (make-array FileAttribute 0)))
        base {:status :unavailable :identity nil :elapsed-ns "0" :reason :runtime-io}
        observation
        (try
          (let [profile (container/profile (:image request) (:limits request) (:output-roots request))]
            (when-not (container/policy-valid? request profile) (refuse! :runtime-policy))
            (if @cancellation (assoc base :reason :runtime-cancelled)
                (let [version (protocol! request directory "version" ["version" "--format" "{{json .}}"] cancellation)
                      info (protocol! request directory "info"
                                      ["info" "--format" "{{.ID}}|{{.OSType}}|{{.KernelVersion}}|{{.Architecture}}|{{.NCPU}}|{{.MemTotal}}|{{.CgroupDriver}}|{{.CgroupVersion}}|{{.Driver}}|{{json .SecurityOptions}}|{{json .Runtimes}}|{{.DefaultRuntime}}|{{.DockerRootDir}}"] cancellation)
                      image (protocol! request directory "image"
                                       ["image" "inspect" "--format" "{{.Id}} {{.Os}} {{json (index .Config \"Volumes\")}}" (str "sha256:" (:image request))] cancellation)
                      fields (str/split info #"\|" 4)]
                  (when-not (and (= "linux" (get fields 1)) (= (System/getProperty "os.version") (get fields 2))
                                 (not (str/blank? (first fields)))
                                 (contains? #{(str "sha256:" (:image request) " linux null") (str "sha256:" (:image request) " linux {}")} image))
                    (refuse! :runtime-daemon))
                  {:status :verified :reason nil :elapsed-ns "0"
                   :identity {:schema/version 1 :profile profile :input-limits (:input-limits request)
                              :process-limits (:process-limits request) :controller (controller-digest (:docker request))
                              :daemon (terms-digest [version info image]) :machine (machine-digest)}})))
          (catch clojure.lang.ExceptionInfo error
            (if (m/validate Failure (ex-data error)) (assoc base :reason (:code (ex-data error))) (throw error)))
          (catch java.io.IOException _ base)
          (catch SecurityException _ base)
          (catch UnsupportedOperationException _ base))]
    {:directory directory :observation (assoc observation :elapsed-ns (str (- (System/nanoTime) origin)))}))
(m/=> probe! [:=> [:cat container/Request coordinator/Cancellation] Result])
