(ns gate.make-battery
  "Run Make targets as one observed gate battery and publish an inspectable archive.
   A project names a Makefile and its targets; each target becomes a gate with its exact argv,
   exit status, bounded command log and duration, bracketed by before/after repository observations.
   The archive is the shape the report readers already accept (run.edn, graph.edn, index.html)."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gate.archive-run :as archive]
            [gate.canonical :as canonical]
            [gate.coordinator :as coordinator]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.process-batch :as batch]
            [gate.repository :as repository]
            [gate.run-contract :as r]
            [gate.runtime :as runtime]
            [gate.test-artifact :as artifact]
            [gate.viewer-asset :as viewer]
            [malli.core :as m])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(def Target [:re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"])
(def Context
  [:map {:closed true}
   [:repository inputs/Root] [:output inputs/Root] [:jobs [:int {:min 1 :max 256}]]
   [:make [:re #"^[A-Za-z0-9_./+-]+$"]] [:makefile r/Path] [:key [:re #"^[a-z][a-z0-9-]{0,63}$"]]
   [:label [:string {:min 1 :max 160}]] [:targets [:vector {:min 1 :max 512} Target]]])

(def environment-names
  "Variables a Make lane may read. Only these reach a command, and their VALUES are recorded in
   each gate's local input snapshot (home and PATH included), so the list holds no variable that
   commonly carries a secret: JAVA_TOOL_OPTIONS, DOCKER_HOST and the proxy variables are left out.
   GIT_CONFIG_GLOBAL stays in: a container launcher declares safe.directory through it.
   Make's own MAKEFLAGS/MAKELEVEL are left out: a parent's jobserver cannot reach a separate make."
  ["PATH" "HOME" "USER" "LOGNAME" "LANG" "LC_ALL" "LC_CTYPE" "TMPDIR" "TERM" "SHELL" "CI"
   "GIT_CONFIG_GLOBAL" "JAVA_HOME" "CLJ_CONFIG" "CLJ_CACHE" "XDG_CACHE_HOME" "XDG_CONFIG_HOME"
   "GOPATH" "GOCACHE" "GOMODCACHE" "CARGO_HOME" "RUSTUP_HOME" "NODE_OPTIONS"])

(defn environment
  "Acquire the allowlisted variables from this process; absent ones are recorded as absent."
  []
  (into {} (map (fn [variable] [variable (System/getenv variable)])) environment-names))
(m/=> environment [:=> [:cat] inputs/Environment])

(defn command
  "One Make invocation per target, as an argv vector; never a shell program."
  [context target]
  ["/usr/bin/env" (:make context) "--no-print-directory" "-f" (:makefile context) target])
(m/=> command [:=> [:cat Context Target] [:vector {:min 6 :max 6} r/Text]])

(defn declarations
  "Declare every target as an always-run gate reading the Makefile; selection stays separate."
  [context effective]
  (mapv (fn [target]
          (let [argv (command context target)]
            {:id (str (:key context) "/" target) :label target :command argv :cwd "."
             :inputs [{:id "makefile" :kind :file :path (:makefile context) :required? true}]
             :outputs [] :environment (vec (sort (keys effective))) :toolchains ["make-battery"]
             :dependencies [] :cache :always :network :allowed
             :coverage (batch/command-expectation argv ".")}))
        (:targets context)))
(m/=> declarations [:=> [:cat Context inputs/Environment] r/Gates])

(defn witness
  "A launched target counts as one command invocation; exit status decides pass or fail.
   This counts commands, never the checks a target runs internally."
  [gate observation]
  (when (:command gate)
    (let [expectation (batch/command-expectation (:command gate) (:cwd gate))]
      (when (contains? observation :exit)
        {:unit :commands :expected (:expected expectation) :observed (:expected expectation) :count 1}))))
(m/=> witness [:=> [:cat r/Gate r/ProcessObservation] [:maybe r/Coverage]])

(defn- relative
  "Path of the output directory relative to the repository, refusing one outside it."
  [repository output]
  (let [root (.toPath (.getCanonicalFile (io/file repository)))
        target (.toPath (.getCanonicalFile (io/file output)))]
    (when-not (.startsWith target root)
      (throw (ex-info "Battery output must lie inside the repository" {:code :make-battery-output})))
    (str (.relativize root target))))
(m/=> relative [:=> [:cat inputs/Root inputs/Root] r/Path])

(defn options
  "Archive options: the report directory is excluded from the observed tree, the Makefile is protected,
   and git query logs live in a temporary directory outside the checkout. The caller owns the checkout
   it runs, so its directories are trusted even when a container observes them through another uid."
  [context log-directory]
  {:repository (:repository context) :output (:output context)
   :policy (repository/policy [(relative (:repository context) (str (.getParent (io/file (:output context)))))]
                              [(:makefile context)])
   :limits repository/default-limits
   :scope {:id (str "make-battery/" (:key context)) :label (:label context) :kind :battery
           :omissions ["Checks a target runs internally (one command per gate)"
                       "Each target's own read set beyond the Makefile"
                       "Ignored files and runtime inputs"]}
   :git {:git "/usr/bin/git" :timeout-ms 30000 :output-bytes 16777216 :log-directory log-directory
         :trusted-owner? true}})
(m/=> options [:=> [:cat Context inputs/Root] archive/Options])

(defn- run-batch!
  "Run the declared targets under the shared scheduler and return the process-batch report."
  [context effective gates roots cancellation]
  (let [adapter {:directory (:repository context) :environment effective
                 :toolchains {"make-battery" (artifact/source-digest roots)} :stdin nil
                 :witness (fn [gate _ observation] (witness gate observation))}]
    (batch/run! {:run (str (:key context) "-" (UUID/randomUUID)) :key (str "make-battery/" (:key context))
                 :label (:label context) :coordinator {:jobs (:jobs context) :claims {}}
                 :input-limits inputs/default-limits :process-limits process/default-limits}
                gates (mapv :id gates) (into {} (map (fn [gate] [(:id gate) adapter]) gates))
                roots (:output context) cancellation)))
(m/=> run-batch! [:=> [:cat Context inputs/Environment r/Gates runtime/Paths coordinator/Cancellation]
                  r/ProcessBatchReport])

(defn run!
  "Observe the tree, run every target, observe again and publish run.edn + index.html.
   Returns the process-batch report; the archive status is printed and a non-passing archive
   turns a passing batch into a failure. The git query logs are diagnostics, deleted afterwards."
  [context cancellation]
  (let [effective (environment) gates (declarations context effective)
        roots (into (artifact/classpath) [(str (io/file (:repository context) (:makefile context)))])
        logs (str (Files/createTempDirectory "make-battery-git-" (make-array FileAttribute 0)))
        result (atom nil)]
    (try
      (let [document (archive/run! (options context logs) (viewer/load!) cancellation
                                   (fn [token]
                                     (let [report (run-batch! context effective gates roots token)]
                                       (reset! result report)
                                       (:graph report))))]
        (println (str "make-battery archive " (name (:status document)) ": " (:output context) "/index.html"))
        (doseq [phase [:before :after]
                :let [failure (get-in document [:provenance phase :failure])] :when failure]
          (println (str "make-battery: the " (name phase) " repository observation is unavailable: " (pr-str failure))))
        (when (= :changed (get-in document [:provenance :stability]))
          (println (str "make-battery: the checkout changed while the targets ran, so the run cannot pass;"
                        " compare repository-before.edn with repository-after.edn in " (:output context))))
        (if (and (= :passed (:status @result)) (not= :passed (:status document)))
          (assoc @result :status :failed)
          @result))
      (finally
        (doseq [^File file (reverse (file-seq (io/file logs)))] (.delete file))))))
(m/=> run! [:=> [:cat Context coordinator/Cancellation] r/ProcessBatchReport])

(defn- tail
  "The last lines of a bounded command log, for the console; the full log stays in the archive."
  [path lines]
  (let [text (try (slurp path) (catch java.io.IOException _ ""))]
    (str/join "\n" (take-last lines (str/split-lines text)))))
(m/=> tail [:=> [:cat r/Text [:int {:min 1 :max 200}]] [:string {:max 4194304}]])

(defn print-failures!
  "Name each target that did not pass, its outcome and reason, its retained log path and the end of that log."
  [output report]
  (doseq [dispatch (get-in report [:batch :dispatches])
          :when (not= :passed (:outcome dispatch))
          :let [log (str (io/file output "gates" (canonical/sha256 (:gate dispatch)) "command.log"))]]
    (println (str "\n✗ " (:gate dispatch) " (" (name (:outcome dispatch))
                  (when-let [reason (get-in dispatch [:work :reason])] (str ", " (name reason))) ") — " log))
    (println (tail log 25)))
  nil)
(m/=> print-failures! [:=> [:cat inputs/Root r/ProcessBatchReport] :nil])

(defn exit-code
  "A passing report is zero, cancellation 130, anything else 1."
  [report]
  (case (:status report) :passed 0 :cancelled 130 1))
(m/=> exit-code [:=> [:cat r/ProcessBatchReport] [:enum 0 1 130]])

(defn -main
  "repository output jobs make makefile key label target... — run, publish, print, exit."
  [repository output jobs make makefile battery-key label & targets]
  (let [context {:repository (.getCanonicalPath (io/file repository)) :output output
                 :jobs (or (parse-long jobs) 0) :make make :makefile makefile :key battery-key :label label
                 :targets (vec targets)}]
    (if-not (m/validate Context context)
      (do (binding [*out* *err*] (println "make-battery: refused arguments (see gate.make-battery/Context)"))
          (System/exit 2))
      (let [report (batch/run-cli! 30000 #(run! context %))]
        (print-failures! output report)
        (println (str "make-battery " (name (:status report)) ": "
                      (frequencies (map :outcome (get-in report [:batch :dispatches])))))
        (shutdown-agents)
        (System/exit (exit-code report))))))
(m/=> -main [:=> [:cat r/Text r/Text r/Text r/Text r/Text r/Text r/Text [:* r/Text]] :nil])
