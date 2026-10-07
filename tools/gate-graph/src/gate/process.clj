(ns gate.process
  "Bounded JVM process execution for trusted local commands. This is not an isolation boundary."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.io InputStream OutputStream]
           [java.lang ProcessHandle]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.util.concurrent TimeUnit]))

(def FileName r/LogFileName)
(def Argument [:string {:max 16384}])
(def Limits r/ProcessLimits)
(def default-limits {:timeout-ms 600000 :cleanup-ms 2000 :log-bytes 4194304 :processes 1024 :stdin-bytes 4194304})
(def Request
  [:map {:closed true} [:directory inputs/Root] [:cwd [:or [:= "."] r/Path]]
   [:command [:vector {:min 1 :max 2048} Argument]] [:environment inputs/Environment]
   [:stdin [:maybe r/Path]] [:log-directory inputs/Root] [:log FileName] [:limits Limits]])
(def Status r/ProcessStatus)
(def Observation r/ProcessObservation)
(def NativeProcess [:fn #(instance? Process %)])
(def HandleMap [:map-of {:max 4096} :int [:fn #(instance? ProcessHandle %)]])
(def State [:fn #(instance? clojure.lang.Atom %)])
(def DigestState [:fn #(instance? MessageDigest %)])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- local-path
  "Resolve a declared path beneath a trusted root, refusing existing symlink components.
   This prevents accidental escape; it is not safe against adversarial directory replacement."
  [directory relative]
  (let [root (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))]
    (reduce (fn [^Path path part]
              (let [next-path (.resolve path ^String part)]
                (when (Files/isSymbolicLink next-path) (throw (java.io.IOException. "Unsupported path")))
                next-path)) root (if (= "." relative) [] (str/split relative #"/")))))
(m/=> local-path [:=> [:cat inputs/Root [:or [:= "."] r/Path]] inputs/NativePath])

(defn- start!
  "Launch exact argv with a cleared environment. Close absent stdin; file stdin is bounded and regular.
   Require an absolute executable so parent PATH cannot silently choose an unkeyed program."
  [{:keys [directory cwd command environment stdin limits]}]
  (let [executable (Path/of (first command) (make-array String 0))
        _ (when-not (.isAbsolute executable) (throw (java.io.IOException. "Absolute executable required")))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (.toFile (local-path directory cwd))) (.redirectErrorStream true))
        effective (.environment builder)]
    (.clear effective)
    (doseq [[k v] environment :when (some? v)] (.put effective k v))
    (when stdin
      (let [path (local-path directory stdin)]
        (when (or (not (Files/isRegularFile path no-follow)) (> (Files/size path) (:stdin-bytes limits)))
          (throw (java.io.IOException. "Unsupported stdin")))
        (.redirectInput builder (.toFile path))))
    (.start builder)))
(m/=> start! [:=> [:cat Request] NativeProcess])

(defn- remember!
  "Bound retained live handles, counting observed processes separately from active concurrency.
   Keep still-live observed descendants even when they are subsequently reparented.
   A snapshot can miss a short-lived parent and detached child: this is not containment."
  [process handles observed limit]
  (swap! handles #(into {} (filter (fn [[_ handle]] (.isAlive ^ProcessHandle handle))) %))
  (with-open [stream (.descendants ^Process process)]
    (let [iterator (.iterator stream)]
      (loop []
        (if (.hasNext iterator)
          (let [^ProcessHandle child (.next iterator) pid (.pid child)]
            (cond
              (or (contains? @handles pid) (not (.isAlive child))) (recur)
              (and (< (count @handles) limit) (< @observed 2147483647))
              (do (swap! handles assoc pid child) (swap! observed inc) (recur))
              :else false)) true)))))
(m/=> remember! [:=> [:cat NativeProcess State State [:int {:min 1 :max 4096}]] :boolean])

(defn- drain!
  "Read only currently available pipe bytes, at most 64 KiB per dispatch poll.
   Store at most the byte budget; the first excess byte marks truncation. No text decoding.
   Single-reader process pipes are assumed; do not reuse this for arbitrary InputStreams."
  [stream output digest state limit]
  (let [buffer (byte-array 8192)]
    (loop [remaining 65536]
      (let [available (.available ^InputStream stream)]
        (when (and (pos? remaining) (pos? available) (not (:truncated? @state)))
          (let [n (.read ^InputStream stream buffer 0 (int (min available remaining (alength buffer))))]
            (when (pos? n)
              (let [retained (min n (- limit (:bytes @state)))]
                (.write ^OutputStream output buffer 0 (int retained))
                (.update ^MessageDigest digest buffer 0 (int retained))
                (swap! state #(-> % (update :bytes + retained) (assoc :truncated? (> n retained)))))
              (recur (- remaining n)))))))))
(m/=> drain! [:=> [:cat [:fn #(instance? InputStream %)] [:fn #(instance? OutputStream %)]
                   DigestState State [:int {:min 1 :max 67108864}]] :nil])

(defn- wait-short!
  "Yield at most ten milliseconds, recording interruption as cooperative cancellation."
  [process cancellation interrupted]
  (try (.waitFor ^Process process 10 TimeUnit/MILLISECONDS)
       (catch InterruptedException _ (reset! interrupted true) (reset! cancellation true)))
  nil)
(m/=> wait-short! [:=> [:cat NativeProcess coordinator/Cancellation State] :nil])

(defn- stopped?
  "Only claim termination of handles actually observed, never the full process tree."
  [handles]
  (every? #(not (.isAlive ^ProcessHandle %)) (vals handles)))
(m/=> stopped? [:=> [:cat HandleMap] :boolean])

(defn- cleanup!
  "Kill observed children before their parent so a waiting parent can reap them.
   Bound cleanup latency and report remaining observed handles rather than claiming success."
  [process handles limits cancellation interrupted]
  (let [deadline (+ (System/nanoTime) (* 1000000 (:cleanup-ms limits)))]
    (doseq [^ProcessHandle child (vals @handles) :when (not= (.pid child) (.pid ^Process process))]
      (when (.isAlive child) (.destroyForcibly child)))
    ;; Allow an ordinary waiting parent to reap terminated children before killing it.
    (dotimes [_ 5] (when (and (.isAlive ^Process process) (< (System/nanoTime) deadline))
                     (wait-short! process cancellation interrupted)))
    (when (.isAlive ^Process process) (.destroyForcibly ^Process process))
    (loop []
      (if (or (stopped? @handles) (>= (System/nanoTime) deadline))
        (stopped? @handles)
        (do
          (try (Thread/sleep 5) (catch InterruptedException _ (reset! interrupted true) (reset! cancellation true)))
          (recur))))))
(m/=> cleanup! [:=> [:cat NativeProcess State Limits coordinator/Cancellation State] :boolean])

(defn- supervise!
  "Drain output and poll work, cancellation and deadline without blocking on pipe EOF."
  [process output digest state handles observed limits cancellation interrupted]
  (let [deadline (+ (System/nanoTime) (* 1000000 (:timeout-ms limits)))
        stream (.getInputStream ^Process process)]
    (loop []
      (let [within-process-limit? (remember! process handles observed (:processes limits))]
        (drain! stream output digest state (:log-bytes limits))
        (cond
          @cancellation :cancelled
          (:truncated? @state) :output-limit
          (not within-process-limit?) :process-limit
          (>= (System/nanoTime) deadline) :timed-out
          (not (.isAlive ^Process process))
          (if (pos? (.available stream)) (recur) :exited)
          :else (do (wait-short! process cancellation interrupted) (recur)))))))
(m/=> supervise! [:=> [:cat NativeProcess [:fn #(instance? OutputStream %)] DigestState State State State
                       Limits coordinator/Cancellation State] Status])

(defn run!
  "Run trusted local argv with exact supplied environment, bounded combined logs and monotonic timeout.
   The log directory must exist; the filename is create-only, never overwritten. Stdin is
   closed or redirected from a bounded regular file beneath directory. Absolute executable
   required; no shell is inserted. Numeric Make jobserver descriptors are refused because
   ProcessBuilder does not preserve them. FIFO jobserver policy belongs to the caller.

   Cancellation and caller interruption kill the direct process and observed descendants,
   then restore interruption. Observation snapshots cannot contain detached/reparented
   children; result explicitly says containment :none. No filesystem/network isolation,
   effective toolchain attestation or cache eligibility is established. Use an owned cgroup
   or container adapter for those guarantees. Cleanup can remain incomplete and is recorded.
   A zero exit is process evidence only; a separate work witness is required for gate success.
   Logs remain consumer-local; returned errors never echo argv, environment or native messages."
  [{:keys [log-directory log limits environment] :as request} cancellation]
  (let [origin (System/nanoTime) process (atom nil) handles (atom {}) observed (atom 0) interrupted (atom false)
        state (atom {:bytes 0 :truncated? false}) digest (MessageDigest/getInstance "SHA-256")
        log-created? (atom false) cleaned? (atom true) cleanup-required? (atom false)
        status (try
                 (cond
                   @cancellation :cancelled
                   (re-find #"--jobserver-(?:auth|fds)=[0-9]+,[0-9]+" (or (get environment "MAKEFLAGS") "")) :unsupported-jobserver
                   :else
                   (with-open [output (Files/newOutputStream (local-path log-directory log)
                                                             (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))]
                     (reset! log-created? true)
                     (let [p (start! request)]
                       (reset! process p) (reset! cleaned? false) (reset! handles {(.pid p) (.toHandle p)}) (reset! observed 1)
                       (try
                         (when-not (:stdin request) (.close (.getOutputStream p)))
                         (supervise! p output digest state handles observed limits cancellation interrupted)
                         (finally
                           (reset! cleanup-required? (not (stopped? @handles)))
                           (reset! cleaned? (cleanup! p handles limits cancellation interrupted)))))))
                 (catch java.io.IOException _ (if @process :io-failed :launch-failed))
                 (catch IllegalArgumentException _ :launch-failed)
                 (catch SecurityException _ (if @process :io-failed :launch-failed))
                 (finally
                   (when-let [^Process p @process]
                     (.close (.getInputStream p)) (.close (.getErrorStream p)) (.close (.getOutputStream p)))
                   (when @interrupted (.interrupt (Thread/currentThread)))))]
    {:schema/version 1 :status status :exit (when (and @process (not (.isAlive ^Process @process))) (.exitValue ^Process @process))
     :pid (when @process (str (.pid ^Process @process))) :elapsed-ns (str (- (System/nanoTime) origin))
     :containment :none :cleanup-required? @cleanup-required?
     :observed-processes @observed :observed-processes-stopped? @cleaned?
     :log (when @log-created? (assoc @state :file log :digest (.formatHex (HexFormat/of) (.digest digest))))}))
(m/=> run! [:=> [:cat Request coordinator/Cancellation] Observation])

(defn work-result
  "Combine process termination with separately observed coverage; zero exit alone never proves work.
   Keep detailed timeout/output/cleanup reasons in the process observation beside this coarse gate result."
  [gate observation coverage]
  (coordinator/judged-result
   gate
   (cond
     (= :cancelled (:status observation)) {:outcome :cancelled :coverage nil :reason :cancellation-requested}
     (or (not= :exited (:status observation)) (:cleanup-required? observation) (not (:observed-processes-stopped? observation)))
     {:outcome :error :coverage nil :reason :adapter-exception}
     (not= 0 (:exit observation)) {:outcome :failed :coverage nil :reason :command-failed}
     :else {:outcome :passed :coverage coverage :reason nil})))
(m/=> work-result [:=> [:cat r/Gate Observation [:maybe r/Coverage]] r/WorkResult])
