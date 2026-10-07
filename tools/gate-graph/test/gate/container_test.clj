(ns gate.container-test
  "Explicit Docker acceptance lane: real read-only inputs, denied network and owned cleanup."
  (:require [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.contained :as contained]
            [gate.container :as container]
            [gate.diagnostic :as diagnostic]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.process :as process]
            [gate.run-test :as fixture]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *parent* nil)
(def ^:dynamic *image* nil)
(def ^:dynamic *records* nil)
(defn with-root [f]
  (let [root (Files/createTempDirectory (Path/of *parent* (make-array String 0)) "case-" (make-array FileAttribute 0))]
    (files/put! root "src/a.clj" "a")
    (f root)))
(defn request [root script]
  (let [gate (assoc fixture/gate :command ["/bin/sh" "-c" script] :environment ["MODE" "PATH"]
                    :toolchains ["image" "container-profile"])
        runtime (container/profile *image* container/default-limits ["out"])]
    {:directory (str root) :scratch-parent (str root) :docker "/usr/bin/docker" :socket "/var/run/docker.sock"
     :image *image* :gate gate :evidence (container/runtime-evidence gate runtime {"MODE" "fixture" "PATH" "/usr/bin:/bin"} {})
     :input-limits inputs/default-limits :process-limits (assoc process/default-limits :timeout-ms 10000)
     :limits container/default-limits :output-roots ["out"]}))
(defn execute [req cancellation]
  (let [result (container/run! req cancellation)
        observation (:observation result)
        encoded (canonical/encode observation 1048576)]
    (is (= observation (admission/decode encoded :container-observation admission/default-limits)))
    (spit (str (:directory result) "/observation.edn") (str encoded "\n"))
    (swap! *records* conj (:directory result))
    result))
(defn absent? [root id]
  (let [check (process/run! {:directory (str root) :cwd "."
                             :command ["/usr/bin/docker" "--host" "unix:///var/run/docker.sock" "ps" "-a" "--filter" (str "id=" id) "--format" "{{.ID}}"]
                             :environment {} :stdin nil :log-directory (str root) :log "absence.log" :limits process/default-limits} (atom false))]
    (and (= :exited (:status check)) (= 0 (:exit check)) (zero? (get-in check [:log :bytes])))))

(defn cleanup-records!
  "Independent test cleanup also removes containers left alive by the deliberate removal mutant."
  []
  (doseq [directory @*records*]
    (let [root (Path/of directory (make-array String 0)) path (.resolve root "container-name")]
      (when (Files/exists path (make-array java.nio.file.LinkOption 0))
        (when (> (Files/size path) 64) (throw (ex-info "Invalid owned container name" {})))
        (let [container-name (Files/readString path)]
          (when-not (re-matches #"gate-[0-9a-f-]{36}" container-name) (throw (ex-info "Invalid owned container name" {})))
          (process/run! {:directory directory :cwd "."
                         :command ["/usr/bin/docker" "--host" "unix:///var/run/docker.sock" "rm" "--force" "--volumes" container-name]
                         :environment {} :stdin nil :log-directory directory :log "acceptance-cleanup.log"
                         :limits (assoc process/default-limits :timeout-ms 10000 :log-bytes 4096)} (atom false))
          (let [check (process/run! {:directory directory :cwd "."
                                     :command ["/usr/bin/docker" "--host" "unix:///var/run/docker.sock" "ps" "-a" "--filter" (str "name=^/" container-name "$") "--format" "{{.ID}}"]
                                     :environment {} :stdin nil :log-directory directory :log "acceptance-absence.log"
                                     :limits (assoc process/default-limits :timeout-ms 10000 :log-bytes 4096)} (atom false))]
            (when-not (and (= :exited (:status check)) (= 0 (:exit check)) (zero? (get-in check [:log :bytes])))
              (throw (ex-info "Owned test container cleanup failed" {})))))))))

(defn with-runtime
  "Bind an explicit acceptance runtime and independently clean every recorded owned container."
  [scratch-parent image-digest f]
  (binding [*parent* scratch-parent *image* image-digest *records* (atom [])]
    (try (f) (finally (cleanup-records!)))))
(defn attempt-path [root suffix]
  (with-open [paths (Files/list root)]
    (some (fn [^Path path]
            (when (.startsWith (str (.getFileName path)) "container-")
              (let [candidate (.resolve path ^String suffix)]
                (when (Files/exists candidate (make-array java.nio.file.LinkOption 0)) candidate))))
          (iterator-seq (.iterator paths)))))
(defn await-path [root suffix]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (or (attempt-path root suffix)
          (when (< (System/nanoTime) deadline) (Thread/sleep 10) (recur))))))

(deftest real-container-enforces-input-network-and-environment-profile
  (with-root
    (fn [root]
      (let [script (str "test \"$MODE\" = fixture && test \"${HOME-unset}\" = unset && test \"${JAVA_HOME-unset}\" = unset || exit 11; "
                        "test ! -e /var/run/docker.sock || exit 12; "
                        "/bin/chmod u+w src/a.clj 2>/dev/null || true; if printf changed >src/a.clj 2>/dev/null; then exit 13; fi; "
                        "/usr/bin/python3 -c 'import socket,errno; s=socket.socket(); s.settimeout(1); assert s.connect_ex((\"192.0.2.1\",443))==errno.ENETUNREACH' || exit 14; "
                        "/bin/cp src/a.clj out/result.edn; printf work")
            result (execute (request root script) (atom false)) observation (:observation result)]
        (is (= :exited (:status observation)))
        (is (= 0 (get-in observation [:execution :exit])))
        (is (:removed? observation))
        (is (= fixture/outputs (:outputs observation)))
        (is (= "a" (slurp (str root "/src/a.clj"))))
        (is (absent? root (:container observation)))))))

(deftest workspace-change-does-not-change-the-bytes-read-by-the-container
  (with-root
    (fn [root]
      (let [cancellation (atom false)
            work (future (execute (request root "printf ready >out/ready; while [ ! -e out/continue ]; do /bin/sleep 0.01; done; /bin/cp src/a.clj out/result.edn") cancellation))]
        (try
          (let [ready (await-path root "outputs/out/ready")]
            (is (some? ready))
            (when ready
              (files/put! root "src/a.clj" "changed")
              (spit (str (.resolveSibling ^Path ready "continue")) "go"))
            (let [result (deref work 15000 :timeout)]
              (is (map? result))
              (when (map? result)
                (is (= fixture/outputs (get-in result [:observation :outputs])))
                (is (get-in result [:observation :removed?])))))
          (finally (reset! cancellation true) (deref work 15000 nil)))))))

(deftest cancellation-removes-container-with-detached-child
  (with-root
    (fn [root]
      (let [cancellation (atom false)
            work (future (execute (request root "/usr/bin/setsid /bin/sleep 30 & printf ready >out/ready; wait") cancellation))]
        (try
          (is (some? (await-path root "outputs/out/ready")))
          (reset! cancellation true)
          (let [result (deref work 15000 :timeout)]
            (is (map? result))
            (when (map? result)
              (is (= :cancelled (get-in result [:observation :status])))
              (is (get-in result [:observation :removed?]))
              (is (absent? root (get-in result [:observation :container])))))
          (finally (reset! cancellation true) (deref work 15000 nil)))))))

(deftest output-overflow-and-timeout-remove-the-owned-container
  (doseq [[script limits expected] [["while :; do printf abcdefghij; done" {:log-bytes 128} :output-limit]
                                    ["/bin/sleep 30" {:timeout-ms 200} :timed-out]]]
    (with-root
      (fn [root]
        (let [req (update (request root script) :process-limits merge limits)
              result (execute req (atom false)) observation (:observation result)]
          (is (= expected (get-in observation [:execution :status])))
          (is (= :runtime-failed (:status observation)))
          (is (:removed? observation))
          (is (absent? root (:container observation))))))))

(deftest nonzero-container-exit-is-preserved
  (with-root
    (fn [root]
      (let [observation (:observation (execute (request root "exit 7") (atom false)))]
        (is (= :exited (:status observation)))
        (is (= 7 (get-in observation [:execution :exit])))
        (is (:removed? observation))
        (is (nil? (:outputs observation)))))))

(deftest mismatched-runtime-policy-refuses-before-container-creation
  (with-root
    (fn [root]
      (let [result (execute (assoc-in (request root "exit 0") [:limits :cpus] 1) (atom false))]
        (is (= :container-policy (get-in result [:observation :reason])))
        (is (nil? (get-in result [:observation :container])))
        (is (nil? (attempt-path root "create.log")))))))

(defn copy-tree! [from to]
  (with-open [paths (Files/walk from (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path path (iterator-seq (.iterator paths))]
      (let [target (.resolve ^Path to (.relativize ^Path from path))]
        (if (Files/isDirectory path (make-array java.nio.file.LinkOption 0))
          (Files/createDirectories target (make-array FileAttribute 0))
          (Files/copy path target (make-array java.nio.file.CopyOption 0)))))))

(deftest inherited-jobserver-capabilities-refuse-before-container-creation
  (doseq [flags ["-j --jobserver-auth=3,4" "-j --jobserver-auth=fifo:/fixture-jobserver"]]
    (with-root
      (fn [root]
        (let [req (-> (request root "exit 0")
                      (update-in [:gate :environment] conj "MAKEFLAGS")
                      (assoc-in [:evidence :environment "MAKEFLAGS"] flags))
              observation (:observation (execute req (atom false)))]
          (is (= :container-policy (:reason observation)))
          (is (nil? (:container observation)))
          (is (nil? (attempt-path root "create.log"))))))))

(deftest full-public-module-suite-runs-with-read-only-classpath-and-no-network
  (with-root
    (fn [root]
      (let [project (Path/of (System/getProperty "user.dir") (make-array String 0))
            staged (.resolve ^Path root "public-suite")
            _ (Files/createDirectory staged (make-array FileAttribute 0))
            _ (doseq [directory ["src" "test"]] (copy-tree! (.resolve project ^String directory) (.resolve staged ^String directory)))
            jars (.resolve staged "jars")
            _ (Files/createDirectory jars (make-array FileAttribute 0))
            dependencies (filter #(str/ends-with? % ".jar") (str/split (System/getProperty "java.class.path") #":"))
            _ (doseq [[i jar] (map-indexed vector dependencies)]
                (Files/copy (Path/of jar (make-array String 0)) (.resolve jars (str i ".jar")) (make-array java.nio.file.CopyOption 0)))
            gate (assoc fixture/gate :id "public-module"
                        :outputs ["out/module-report/graph.edn"]
                        :environment ["PATH"] :toolchains ["image" "container-profile"]
                        :inputs (mapv #(hash-map :id % :kind :tree :path % :required? true) ["src" "test" "jars"])
                        :command [(str (System/getProperty "java.home") "/bin/java") "-cp"
                                  (str/join ":" (into ["/gate/src" "/gate/test"] (map-indexed (fn [i _] (str "/gate/jars/" i ".jar")) dependencies)))
                                  "clojure.main" "-m" "gate.test-runner" "/gate/out/module-report"])
            runtime (container/profile *image* container/default-limits ["out"])
            req (assoc (request root "") :directory (str staged) :gate gate :output-roots ["out"]
                       :process-limits (assoc process/default-limits :timeout-ms 120000)
                       :evidence (container/runtime-evidence gate runtime {"PATH" "/usr/bin:/bin"} {}))
            result (execute req (atom false)) observation (:observation result)
            log-text (slurp (str (:directory result) "/start.log"))]
        (is (= :exited (:status observation)))
        (is (= 0 (get-in observation [:execution :exit])))
        (is (:removed? observation))
        (is (some? (re-find #"Ran [1-9][0-9]* tests containing [1-9][0-9]* assertions\." log-text)))
        (is (str/includes? log-text "0 failures, 0 errors."))
        (let [graph (admission/decode (slurp (str (:directory result) "/outputs/out/module-report/graph.edn"))
                                      :graph admission/default-limits)]
          (is (get-in graph [:run :complete?]))
          (is (= 1 (count (filter #(= :chain (:kind %)) (:nodes graph)))))
          (is (seq (:measurements graph))))))))

(def contained-script "/bin/cp src/a.clj out/result.edn && printf 'unit-a\\nunit-b\\n'")
(def contained-roster "unit-a\nunit-b")
(defn contained-request [root script]
  (assoc-in (request root script) [:gate :coverage :expected] (canonical/sha256 contained-roster)))
(defn witnessed-coverage [gate result]
  (let [text (str/trim (slurp (str (:directory result) "/start.log")))
        lines (str/split-lines text)]
    (when (= ["unit-a" "unit-b"] lines)
      {:unit :checks :expected (get-in gate [:coverage :expected]) :observed (canonical/sha256 (str/join "\n" lines)) :count (count lines)})))
(defn execute-contained [req observer force?]
  (let [result (contained/run! req {:cache-directory (str (:directory req) "/cache")
                                    :run "contained-fixture" :attempt "contained-attempt" :dependencies [] :force? force?}
                               observer (constantly []) (atom false))
        encoded (canonical/encode (:observation result) 1048576)]
    (let [record (Files/createTempDirectory (Path/of (:directory req) (make-array String 0)) "contained-result-" (make-array FileAttribute 0))]
      (spit (str record "/observation.edn") (str encoded "\n")))
    (when (:directory result)
      (swap! *records* conj (:directory result))
      (spit (str (:directory result) "/contained.edn") (str encoded "\n")))
    (is (= (:observation result) (admission/decode encoded :contained-observation admission/default-limits)))
    (is (not (str/includes? encoded (:directory req))))
    result))

(deftest contained-cold-hit-unrelated-hit-changed-miss-forced-and-policy-changed-runs
  (with-root
    (fn [root]
      (let [req (contained-request root contained-script) calls (atom 0)
            observer (fn [gate result] (swap! calls inc) (witnessed-coverage gate result))
            cold (execute-contained req observer false)
            repeated (execute-contained req observer false)]
        (is (= :verified (get-in cold [:observation :runtime-before :status])))
        (is (= :passed (get-in cold [:observation :attempt :work :outcome])))
        (is (= :installed (get-in cold [:observation :output-publication :status])))
        (is (= :stored (get-in cold [:observation :attempt :publication])))
        (is (= :cached (get-in repeated [:observation :attempt :work :outcome])))
        (is (nil? (get-in repeated [:observation :container])))
        (is (= 1 @calls))
        (files/put! root "unrelated/readme" "unrelated")
        (is (= :cached (get-in (execute-contained req observer false) [:observation :attempt :work :outcome])))
        (is (= 1 @calls))
        (is (= "a" (slurp (str root "/out/result.edn"))))
        (files/put! root "src/a.clj" "b")
        (let [changed (execute-contained req observer false)]
          (is (= :passed (get-in changed [:observation :attempt :work :outcome])))
          (is (not= (get-in cold [:observation :attempt :decision :key]) (get-in changed [:observation :attempt :decision :key])))
          (is (= "b" (slurp (str root "/out/result.edn")))))
        (let [forced (execute-contained req observer true)]
          (is (= :forced (get-in forced [:observation :attempt :decision :reason])))
          (is (= :existing (get-in forced [:observation :attempt :publication])))
          (is (= 3 @calls)))
        (Files/delete (.resolve ^Path root "out/result.edn"))
        (let [restored (execute-contained req observer false)
              changed-policy (execute-contained (update-in req [:process-limits :timeout-ms] inc) observer false)]
          (is (= :outputs-changed (get-in restored [:observation :attempt :decision :reason])))
          (is (= :existing (get-in restored [:observation :attempt :publication])))
          (is (= :passed (get-in changed-policy [:observation :attempt :work :outcome])))
          (is (not= (get-in restored [:observation :attempt :decision :key]) (get-in changed-policy [:observation :attempt :decision :key])))
          (is (= 5 @calls)))))))

(deftest contained-vacuous-work-preserves-existing-output
  (with-root
    (fn [root]
      (files/put! root "out/result.edn" "preserved")
      (let [result (execute-contained (contained-request root "/bin/cp src/a.clj out/result.edn") witnessed-coverage false)]
        (is (= :coverage-mismatch (get-in result [:observation :attempt :work :reason])))
        (is (= :not-attempted (get-in result [:observation :output-publication :status])))
        (is (= "preserved" (slurp (str root "/out/result.edn"))))))))

(deftest contained-workspace-change-refuses-successful-child-outputs
  (with-root
    (fn [root]
      (files/put! root "out/result.edn" "preserved")
      (let [observer (fn [gate result]
                       (files/put! root "src/a.clj" "changed")
                       (witnessed-coverage gate result))
            result (execute-contained (contained-request root contained-script) observer false)]
        (is (= 0 (get-in result [:observation :container :execution :exit])))
        (is (= :input-unstable (get-in result [:observation :attempt :work :reason])))
        (is (= :source-changed (get-in result [:observation :output-publication :reason])))
        (is (= "preserved" (slurp (str root "/out/result.edn"))))))))

(defn -main
  "Run explicit Docker acceptance against the supplied pinned image and same-path owned scratch root."
  [scratch-parent image-digest]
  (when-not (m/validate [:re #"^[0-9a-f]{64}$"] image-digest) (throw (ex-info "Image digest required" {})))
  (diagnostic/install!)
  (let [result (with-runtime scratch-parent image-digest
                 (fn [] (let [result (test/run-tests 'gate.container-test)]
                          (spit (str scratch-parent "/summary.edn") (pr-str (assoc result :records @*records*))) result)))]
    (shutdown-agents)
    (System/exit (if (and (pos? (:test result)) (zero? (:fail result)) (zero? (:error result))) 0 1))))
