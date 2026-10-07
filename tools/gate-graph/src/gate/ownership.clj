(ns gate.ownership
  "Cooperative output claims across JVM threads and local processes; independent top-level roots may run concurrently."
  (:refer-clojure :exclude [key])
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio.channels FileChannel FileLock]
           [java.nio.file Files Path LinkOption StandardOpenOption OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def Failure [:map {:closed true} [:code [:enum :output-ownership]]
              [:reason [:enum :cancelled :timed-out :io :reserved-output :capacity]]])
(def Mutex [:fn #(instance? ReentrantLock %)])
(def Channel [:fn #(instance? FileChannel %)])
(def Lock [:fn #(instance? FileLock %)])
(def Held [:map {:closed true} [:key inputs/Root] [:mutex Mutex] [:channel Channel] [:lock Lock]])
(def Lease [:map {:closed true} [:held [:vector {:max 1024} Held]] [:released? inputs/Budget]])
(def ^:private pool (atom {}))
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Expose bounded ownership reasons without embedding workspace paths or OS exception messages."
  [reason]
  (throw (ex-info "Output ownership unavailable" {:code :output-ownership :reason reason})))
(m/=> refuse! [:=> [:cat [:enum :cancelled :timed-out :io :reserved-output :capacity]] :nil])

(defn roots
  "Claim top-level output roots in sorted order so overlapping/nested declarations cannot deadlock.
   This conservatively serializes sibling outputs in one top-level directory; distinct roots and
   output-free gates remain independent. The fixed ownership directory is reserved from outputs."
  [outputs]
  (let [names (vec (sort (set (map #(first (str/split % #"/")) outputs))))]
    (when (some #{".gate-output-locks"} names) (refuse! :reserved-output))
    names))
(m/=> roots [:=> [:cat [:vector {:max 1024} r/Path]] [:vector {:max 1024} r/Path]])

(defn- borrow!
  "Reference-count a JVM mutex before opening a file channel; one process never closes a peer's lock descriptor."
  [key]
  (locking pool
    (when (and (not (contains? @pool key)) (>= (count @pool) 4096)) (refuse! :capacity))
    (let [entry (get @pool key {:mutex (ReentrantLock.) :users 0})]
      (swap! pool assoc key (update entry :users inc))
      (:mutex entry))))
(m/=> borrow! [:=> [:cat inputs/Root] Mutex])

(defn- return!
  "Drop a borrowed reference only after channel closure and mutex release; remove idle pool entries."
  [key]
  (locking pool
    (if (= 1 (get-in @pool [key :users])) (swap! pool dissoc key)
        (swap! pool update-in [key :users] dec)))
  nil)
(m/=> return! [:=> [:cat inputs/Root] :nil])

(defn- check-wait!
  "Bound contention waiting with a monotonic deadline and cooperative cancellation."
  [deadline cancellation]
  (when (or @cancellation (.isInterrupted (Thread/currentThread))) (refuse! :cancelled))
  (when (>= (System/nanoTime) deadline) (refuse! :timed-out)))
(m/=> check-wait! [:=> [:cat :int coordinator/Cancellation] :nil])

(defn- acquire-one!
  "Take the JVM mutex first, then an OS lock, keeping its inode permanently at the fixed workspace path."
  [key deadline cancellation]
  (let [mutex (borrow! key) locked? (atom false) channel (atom nil)]
    (try
      (when (.isHeldByCurrentThread ^ReentrantLock mutex) (refuse! :io))
      (loop []
        (check-wait! deadline cancellation)
        (if (.tryLock ^ReentrantLock mutex 25 TimeUnit/MILLISECONDS) (reset! locked? true) (recur)))
      (let [path (Path/of key (make-array String 0))]
        (when (or (Files/isSymbolicLink path)
                  (and (Files/exists path no-follow) (not (Files/isRegularFile path no-follow)))) (refuse! :io))
        (reset! channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])))
        (loop []
          (check-wait! deadline cancellation)
          (if-let [file-lock (.tryLock ^FileChannel @channel)]
            {:key key :mutex mutex :channel @channel :lock file-lock}
            (do (Thread/sleep 25) (recur)))))
      (catch Throwable error
        (try (when @channel (.close ^FileChannel @channel))
             (finally
               (when @locked? (.unlock ^ReentrantLock mutex))
               (return! key)))
        (when (instance? InterruptedException error) (.interrupt (Thread/currentThread)))
        (throw error)))))
(m/=> acquire-one! [:=> [:cat inputs/Root :int coordinator/Cancellation] Held])

(defn release!
  "Release a lease exactly once on its acquiring thread, in reverse order, retaining lock files/inodes.
   Deleting ownership files while runners are alive would split cross-process exclusion and is forbidden."
  [lease]
  (when (compare-and-set! (:released? lease) false true)
    (let [failure (atom nil)]
      (doseq [{:keys [key mutex channel lock]} (reverse (:held lease))]
        (try
          (try (.release ^FileLock lock) (finally (.close ^FileChannel channel)))
          (catch java.io.IOException error (reset! failure error))
          (finally (.unlock ^ReentrantLock mutex) (return! key))))
      (when @failure (refuse! :io))))
  nil)
(m/=> release! [:=> [:cat Lease] :nil])

(defn acquire!
  "Acquire cooperative local output claims through cache lookup, execution, copyback and receipt admission.
   Uses a fixed .gate-output-locks directory in the canonical workspace, independent of cache locations.
   Trusted local filesystem with working advisory locks required; all writers must use this protocol.
   Claims are conservative top-level roots, not a hostile-writer sandbox. Call release! in finally
   on the acquiring thread. Waiting is bounded and cancellable; failure releases partial claims."
  [directory outputs timeout-ms cancellation]
  (let [names (roots outputs) held (atom []) deadline (+ (System/nanoTime) (* 1000000 timeout-ms))]
    (try
      (when (seq names)
        (let [root (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))
              locks (.resolve root ".gate-output-locks")]
          (when (Files/notExists locks no-follow)
            (try (Files/createDirectory locks (make-array FileAttribute 0))
                 (catch java.nio.file.FileAlreadyExistsException _ nil)))
          (when (or (Files/isSymbolicLink locks) (not (Files/isDirectory locks no-follow))) (refuse! :io))
          (doseq [output names]
            (swap! held conj (acquire-one! (str (.resolve locks (str (canonical/sha256 output) ".lock"))) deadline cancellation)))))
      {:held @held :released? (atom false)}
      (catch Throwable error
        (release! {:held @held :released? (atom false)})
        (cond
          (instance? InterruptedException error) (do (.interrupt (Thread/currentThread)) (refuse! :cancelled))
          (or (instance? java.io.IOException error) (instance? SecurityException error)
              (instance? java.nio.channels.OverlappingFileLockException error)) (refuse! :io)
          :else (throw error))))))
(m/=> acquire! [:=> [:cat inputs/Root [:vector {:max 1024} r/Path] [:int {:min 1 :max 86400000}] coordinator/Cancellation] Lease])
