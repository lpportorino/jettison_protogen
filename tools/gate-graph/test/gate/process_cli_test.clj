(ns gate.process-cli-test
  "External signals must cancel actual children and allow bounded final EDN publication."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.process-batch :as batch]
            [gate.process-batch-test :as fixtures])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

(defn await-ready
  "Bound readiness acquisition and fail on an exited worker, rather than sleeping before sending a signal."
  [^Process worker file]
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (cond (.exists (io/file file)) true
            (or (not (.isAlive worker)) (> (System/nanoTime) deadline)) false
            :else (do (Thread/sleep 10) (recur))))))

(defn stop-pid!
  "Kill only a PID from this owned fixture, including cleanup after a deliberately broken shutdown."
  [file]
  (when (.exists (io/file file))
    (let [handle (ProcessHandle/of (Long/parseLong (slurp file)))]
      (when (.isPresent handle) (.destroyForcibly ^ProcessHandle (.get handle))))))

(defn signal-case
  "Start a fresh JVM, wait for real work, deliver the named signal and retain its exit and bounded report."
  [root mode signal timeout]
  (spit (str root "/work.sh") "printf '%s' \"$$\" > child.pid\n/bin/sleep 60 &\nprintf '%s' \"$!\" > descendant.pid\nwait\n")
  (let [command [(str (System/getProperty "java.home") "/bin/java") "-Dclojure.main.report=stderr" "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "gate.process-cli-fixture" root mode (str timeout)]
        log (io/file root "worker.log")
        worker (.start (doto (ProcessBuilder. ^java.util.List command) (.redirectErrorStream true) (.redirectOutput log)))]
    (try
      (is (await-ready worker (str root (if (= mode "process") "/descendant.pid" "/ready"))) (slurp log))
      ;; Give the process observer several polls to acquire the already-live descendant.
      (Thread/sleep 100)
      (let [signaller (.start (ProcessBuilder. ^java.util.List ["/bin/kill" (str "-" signal) (str (.pid worker))]))
            started (System/nanoTime)]
        (is (.waitFor signaller 5 TimeUnit/SECONDS))
        (is (zero? (.exitValue signaller)))
        (is (.waitFor worker 15 TimeUnit/SECONDS) (slurp log))
        (let [report-file (io/file root "report/report.edn")]
          {:exit (when-not (.isAlive worker) (.exitValue worker))
           :elapsed-ms (/ (- (System/nanoTime) started) 1000000)
           :live-pids (vec (for [file ["child.pid" "descendant.pid"]
                                 :let [path (io/file root file)] :when (.exists path)
                                 :let [handle (ProcessHandle/of (Long/parseLong (slurp path)))]
                                 :when (and (.isPresent handle) (.isAlive ^ProcessHandle (.get handle)))] file))
           :log (slurp log) :report (when (.exists report-file)
                                      (admission/decode (slurp report-file) :process-batch-report admission/default-limits))}))
      (finally
        (doseq [file ["descendant.pid" "child.pid"]] (stop-pid! (str root "/" file)))
        (when (.isAlive worker) (.destroyForcibly worker))
        (.waitFor worker 5 TimeUnit/SECONDS)))))

(deftest cli-signals-cancel-observed-children-and-publish-report
  (doseq [[signal code] [["TERM" 143] ["INT" 130]]]
    (fixtures/workspace
     (fn [root]
       (let [{:keys [exit report log live-pids]} (signal-case root "process" signal 10000)
             observed (get-in report [:captures 0 :process :observation])]
         (is (= code exit) log)
         (is (= :cancelled (:status report)) log)
         (is (= :cancelled (:status observed)))
         (is (true? (:observed-processes-stopped? observed)))
         (is (not (.exists (io/file root "second-ran"))))
         (is (empty? live-pids)))))))

(deftest shutdown-waits-for-cooperative-witness-and-report-publication
  (fixtures/workspace
   (fn [root]
     (let [{:keys [exit report elapsed-ms]} (signal-case root "witness" "TERM" 10000)]
       (is (= 143 exit))
       (is (= :cancelled (:status report)))
       (is (>= elapsed-ms 200))
       (is (= 1 (count (:captures report))))
       (is (not (.exists (io/file root "second-ran"))))))))

(deftest shutdown-budget-refuses-to-hang-on-uncooperative-witness
  (fixtures/workspace
   (fn [root]
     (let [{:keys [exit report log elapsed-ms]} (signal-case root "stuck" "TERM" 100)]
       (is (= 143 exit))
       (is (nil? report))
       (is (.contains ^String log ":process-shutdown-incomplete"))
       (is (< elapsed-ms 5000))))))

(deftest cli-scope-preserves-normal-result-and-exception
  (fixtures/workspace
   (fn [root]
     (let [result (batch/run-cli! 1000 #(fixtures/execute root [fixtures/gate] {"copy" (fixtures/adapter root)} %))]
       (is (= :passed (:status result)))
       (is (= result (fixtures/read-report root))))))
  (let [failure (ex-info "fixture" {:code :fixture})]
    (is (identical? failure (try (batch/run-cli! 1000 (fn [_] (throw failure)))
                                 (catch clojure.lang.ExceptionInfo e e))))))
