(ns gate.process-test
  "Actual subprocess controls for bounded output, input/environment fidelity and cancellation."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.inputs-test :as files]
            [gate.process :as process]
            [gate.run-test :as fixture]
            [gate.trace-test :refer [with-directory]]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn request [root command]
  {:directory (str root) :cwd "." :command command :environment {}
   :stdin nil :log-directory (str root) :log "command.log"
   :limits (assoc process/default-limits :timeout-ms 3000)})
(defn invoke [root command] (process/run! (request root command) (atom false)))
(defn log-text [root] (slurp (str (.resolve ^Path root "command.log"))))

(deftest argv-environment-cwd-and-stdin-remain-explicit
  (with-directory
    (fn [root]
      (files/put! root "nested/input.txt" "input\n")
      (let [command ["/bin/sh" "-c" "printf '%s|%s|%s|%s|' \"$1\" \"${EMPTY-set}\" \"${ABSENT-unset}\" \"${HOME-unset}\"; /bin/cat; /bin/pwd" "label" "a ; $(false)"]
            result (process/run! (assoc (request root command) :cwd "nested" :stdin "nested/input.txt"
                                        :environment {"EMPTY" "" "ABSENT" nil}) (atom false))]
        (is (m/validate process/Observation result))
        (is (= result (admission/decode (canonical/encode result 65536) :process-observation admission/default-limits)))
        (is (= :exited (:status result)))
        (is (= 0 (:exit result)))
        (is (= (str "a ; $(false)||unset|unset|input\n" root "/nested\n") (log-text root)))
        (is (= (canonical/sha256 (log-text root)) (get-in result [:log :digest])))
        (is (:observed-processes-stopped? result))
        (is (= :none (:containment result)))))))

(deftest exact-log-limit-is-complete-and-one-more-byte-refuses
  (doseq [[text status truncated?] [["12345" :exited false] ["123456" :output-limit true]]]
    (with-directory
      (fn [root]
        (let [result (process/run! (assoc-in (request root ["/usr/bin/printf" "%s" text]) [:limits :log-bytes] 5) (atom false))]
          (is (= status (:status result)))
          (is (= truncated? (get-in result [:log :truncated?])))
          (is (= 5 (Files/size (.resolve ^Path root "command.log"))))
          (is (= "12345" (log-text root)))
          (is (= (canonical/sha256 "12345") (get-in result [:log :digest]))))))))

(deftest noisy-output-cannot-grow-the-log-or-block-cancellation
  (with-directory
    (fn [root]
      (let [result (process/run! (assoc-in (request root ["/bin/sh" "-c" "while :; do printf 'abcdefghij'; done"]) [:limits :log-bytes] 1000) (atom false))]
        (is (= :output-limit (:status result)))
        (is (= 1000 (Files/size (.resolve ^Path root "command.log"))))
        (is (:observed-processes-stopped? result))
        (is (= :error (:outcome (process/work-result fixture/gate result fixture/coverage))))))))

(deftest exit-status-and-independent-work-witness-both-matter
  (doseq [[exit outcome] [[0 :passed] [7 :failed]]]
    (with-directory
      (fn [root]
        (let [result (invoke root ["/bin/sh" "-c" (str "printf 'work'; exit " exit)])]
          (is (= exit (:exit result)))
          (is (= outcome (:outcome (process/work-result fixture/gate result fixture/coverage))))
          (when (zero? exit)
            (is (= :coverage-mismatch (:reason (process/work-result fixture/gate result nil))))))))))

(deftest absent-stdin-is-closed-and-logs-are-create-only
  (with-directory
    (fn [root]
      (let [first-result (invoke root ["/bin/cat"])
            second-result (invoke root ["/usr/bin/printf" "replacement"])]
        (is (= :exited (:status first-result)))
        (is (= :launch-failed (:status second-result)))
        (is (nil? (:pid second-result)))
        (is (nil? (:log second-result)))
        (is (= "" (log-text root)))))))

(deftest missing-executable-relative-executable-and-jobserver-refuse
  (doseq [command [["/nonexistent-fixture-tool"] ["sh" "-c" "exit 0"]]]
    (with-directory
      (fn [root]
        (let [result (invoke root command)]
          (is (= :launch-failed (:status result)))
          (is (nil? (:pid result)))
          (is (= :error (:outcome (process/work-result fixture/gate result fixture/coverage))))))))
  (with-directory
    (fn [root]
      (let [result (process/run! (assoc (request root ["/bin/true"]) :environment {"MAKEFLAGS" "-j --jobserver-auth=3,4"}) (atom false))]
        (is (= :unsupported-jobserver (:status result)))
        (is (nil? (:pid result)))
        (is (nil? (:log result)))))))

(deftest stdin-byte-limit-and-symlink-cwd-refuse
  (with-directory
    (fn [root]
      (files/put! root "input" "abc")
      (let [result (process/run! (-> (request root ["/bin/cat"]) (assoc :stdin "input") (assoc-in [:limits :stdin-bytes] 2)) (atom false))]
        (is (= :launch-failed (:status result)))
        (is (nil? (:pid result))))))
  (with-directory
    (fn [root]
      (Files/createSymbolicLink (.resolve ^Path root "linked") root (make-array java.nio.file.attribute.FileAttribute 0))
      (let [result (process/run! (assoc (request root ["/bin/true"]) :cwd "linked") (atom false))]
        (is (= :launch-failed (:status result)))
        (is (nil? (:pid result)))))))

(deftest timeout-kills-observed-waiting-child-and-keeps-its-reason
  (with-directory
    (fn [root]
      (let [result (process/run! (assoc-in (request root ["/bin/sh" "-c" "/bin/sleep 1 & wait"]) [:limits :timeout-ms] 150) (atom false))]
        (is (= :timed-out (:status result)))
        (is (>= (:observed-processes result) 2))
        (is (:observed-processes-stopped? result))
        (is (= :error (:outcome (process/work-result fixture/gate result fixture/coverage))))))))

(deftest direct-timeout-must-actually-stop-the-command
  (with-directory
    (fn [root]
      (let [result (process/run! (update (request root ["/bin/sleep" "1"]) :limits assoc :timeout-ms 100 :cleanup-ms 100) (atom false))]
        (try
          (is (= :timed-out (:status result)))
          (is (:observed-processes-stopped? result))
          (is (= 1 (:observed-processes result)))
          (finally
            ;; Also clean the controlled mutant that disables direct-process termination.
            (when-let [pid (:pid result)]
              (let [handle (java.lang.ProcessHandle/of (Long/parseLong pid))]
                (when (.isPresent handle) (.destroyForcibly ^java.lang.ProcessHandle (.get handle)))))))))))

(deftest pre-cancelled-work-never-launches-or-creates-a-log
  (with-directory
    (fn [root]
      (let [result (process/run! (request root ["/bin/true"]) (atom true))]
        (is (= :cancelled (:status result)))
        (is (nil? (:pid result)))
        (is (nil? (:log result)))
        (is (= :cancelled (:outcome (process/work-result fixture/gate result nil))))))))

(deftest process-budget-bounds-live-handles-not-finished-sequential-children
  (with-directory
    (fn [root]
      (let [result (process/run! (assoc-in (request root ["/bin/sh" "-c" "i=0; while [ $i -lt 10 ]; do /bin/sleep 0.05; i=$((i+1)); done"])
                                           [:limits :processes] 2) (atom false))]
        (is (= :exited (:status result)))
        (is (= 0 (:exit result)))
        (is (> (:observed-processes result) 2))
        (is (:observed-processes-stopped? result))))))

(deftest interruption-cancels-running-work-and-is-restored-after-cleanup
  (with-directory
    (fn [root]
      (let [entered (CountDownLatch. 1) result (promise) cancelled (atom false)
            worker (Thread. ^Runnable (fn [] (.countDown entered)
                                        (let [observation (process/run! (request root ["/bin/sh" "-c" "printf started >ready; exec /bin/sleep 30"]) cancelled)]
                                          (deliver result [observation (.isInterrupted (Thread/currentThread))]))))]
        (.start worker)
        (is (.await entered 1 TimeUnit/SECONDS))
        (let [deadline (+ (System/nanoTime) 3000000000)
              ready (.resolve ^Path root "ready")]
          (loop [] (when (and (not (Files/exists ready (make-array java.nio.file.LinkOption 0)))
                              (< (System/nanoTime) deadline))
                     (Thread/sleep 5) (recur)))
          (is (Files/exists ready (make-array java.nio.file.LinkOption 0))))
        (.interrupt worker)
        (let [answer (deref result 5000 :timeout)]
          (is (not= :timeout answer))
          (when (vector? answer)
            (is (= :cancelled (:status (first answer))))
            (is (:observed-processes-stopped? (first answer)))
            (is (true? (second answer)))))
        (.join worker 5000)
        (is (not (.isAlive worker)))))))
