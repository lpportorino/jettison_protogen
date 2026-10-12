(ns gate.make-battery-test
  "Make targets as observed gates: declarations, the environment allowlist and a real two-target battery."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [gate.make-battery :as battery]
            [gate.repository-test :as git-fixture]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio.file Path]))

(def makefile "ok:\n\t@echo ok-ran\nfail:\n\t@echo fail-ran; exit 3\nunused:\n\t@echo unused-ran\n")

(defn context [root run targets]
  {:repository (str root) :output (str (io/file (str root) "ignored/battery" run))
   :jobs 2 :make "make" :makefile "Makefile" :key "fixture" :label "fixture battery" :targets targets})

(deftest declarations-name-each-target-and-read-the-makefile
  (let [gates (battery/declarations (context (Path/of "/tmp" (make-array String 0)) "run" ["ok" "fail"])
                                    {"PATH" "/usr/bin"})]
    (is (m/validate r/Gates gates))
    (is (= ["fixture/ok" "fixture/fail"] (mapv :id gates)))
    (is (= ["/usr/bin/env" "make" "--no-print-directory" "-f" "Makefile" "ok"] (:command (first gates))))
    (is (= [{:id "makefile" :kind :file :path "Makefile" :required? true}] (:inputs (first gates))))
    (is (= ["PATH"] (:environment (first gates))))))

(deftest only-allowlisted-variables-are-acquired
  (let [acquired (battery/environment)]
    (is (= (set battery/environment-names) (set (keys acquired))))
    (is (contains? acquired "GIT_CONFIG_GLOBAL") "a container launcher's safe.directory must reach the lanes")
    (is (not-any? #{"MAKEFLAGS" "MAKELEVEL" "SSH_AUTH_SOCK" "AWS_SECRET_ACCESS_KEY" "JAVA_TOOL_OPTIONS"
                    "DOCKER_HOST" "https_proxy" "HTTPS_PROXY"} (keys acquired)))))

(deftest a-real-battery-runs-only-selected-targets-and-publishes-an-archive
  (git-fixture/with-repository
    (fn [root _]
      (spit (str (io/file (str root) "Makefile")) makefile)
      (let [failed (battery/run! (context root "run-1" ["ok" "fail"]) (atom false))
            outcomes (into {} (map (juxt :gate :outcome)) (get-in failed [:batch :dispatches]))
            published (io/file (str root) "ignored/battery/run-1")
            passed (battery/run! (context root "run-2" ["ok"]) (atom false))]
        (is (= :failed (:status failed)))
        (is (= {"fixture/ok" :passed "fixture/fail" :failed} outcomes) "the unselected target never runs")
        (doseq [file ["run.edn" "graph.edn" "index.html" "repository-before.edn" "repository-after.edn"]]
          (is (.isFile (io/file published file)) file))
        (is (= 1 (battery/exit-code failed)))
        (let [printed (with-out-str (battery/print-failures! (str published) failed))]
          (is (re-find #"fixture/fail \(failed, command-failed\)" printed))
          (is (re-find #"fail-ran" printed) "the failing target's own log tail reaches the console")
          (is (not (re-find #"fixture/ok" printed))))
        (is (= :passed (:status passed)))
        (is (= 0 (battery/exit-code passed)))))))
