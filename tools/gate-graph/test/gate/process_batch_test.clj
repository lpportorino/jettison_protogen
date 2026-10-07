(ns gate.process-batch-test
  "Actual command DAGs prove source/input stability, nonvacuous witnesses and retained failure artifacts."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.process-batch :as batch]
            [gate.run-contract :as r]
            [gate.test-artifact :as artifact]
            [gate.trace-test :refer [with-directory]]
            [malli.core :as m])
  (:import [java.nio.file Files Path]))

(def digest (canonical/sha256 "independent-copy-check"))
(def coverage {:unit :checks :expected digest :observed digest :count 1})
(def options {:run "process-batch" :key "chain/example" :label "Example commands"
              :coordinator {:jobs 2 :claims {}} :input-limits inputs/default-limits
              :process-limits (assoc process/default-limits :timeout-ms 3000)})
(def gate {:id "copy" :label "Copy input" :command ["/bin/cat" "input.txt"] :cwd "."
           :inputs [{:id "input" :kind :file :path "input.txt" :required? true}]
           :outputs [] :environment [] :toolchains ["fixture"] :dependencies []
           :cache :always :network :allowed :coverage {:unit :checks :expected digest :minimum 1}})

(defn workspace
  "Create independent controller and command inputs; fixture roots deliberately isolate each observer."
  [f]
  (with-directory
    (fn [root]
      (spit (str root "/controller") "immutable-controller")
      (spit (str root "/input.txt") "expected-work")
      (f (str root)))))

(defn witness
  "Observe known actual command output; returning an expected digest without this check would be vacuous."
  [_ directory _]
  (when (= "expected-work" (slurp (str directory "/command.log"))) coverage))

(defn adapter
  "Bind the fixture's exact execution environment and its independent actual-output observer."
  [root]
  {:directory root :environment {} :toolchains {"fixture" digest} :stdin nil :witness witness})

(defn execute
  "Run the public batch entrypoint with a fresh report and actual controller bytes."
  [root gates adapters cancellation]
  (batch/run! options gates [(:id (last gates))] adapters [(str root "/controller")]
              (str root "/report") cancellation))

(defn read-report
  "Read only the bounded canonical record the actual publisher produced."
  [root]
  (admission/decode (slurp (str root "/report/report.edn")) :process-batch-report admission/default-limits))

(deftest actual-process-batch-publishes-roundtrippable-source-bound-evidence
  (workspace
   (fn [root]
     (let [result (execute root [gate] {"copy" (adapter root)} (atom false))
           captured (first (:captures result))
           local (str root "/report/gates/" (canonical/sha256 "copy"))]
       (is (m/validate r/ProcessBatchReport result))
       (is (= result (read-report root)))
       (is (= :passed (:status result)))
       (is (= coverage (:coverage captured)))
       (is (= :exited (get-in captured [:process :observation :status])))
       (is (= 0 (get-in captured [:process :observation :exit])))
       (is (get-in result [:graph :run :complete?]))
       (is (= captured (admission/decode (slurp (str local "/process.edn")) :process-capture admission/default-limits)))
       (doseq [[filename field] [["inputs-before.edn" :inputs-before] ["inputs-after.edn" :inputs-after]]]
         (let [snapshot (admission/decode (slurp (str local "/" filename)) :input-snapshot admission/default-limits)]
           (is (:complete? snapshot))
           (is (= (get-in captured [:validation field]) (canonical/sha256 (canonical/encode snapshot 67108864))))))))))

(deftest command-invocation-has-explicit-scope-and-requires-successful-process
  (doseq [[command expected] [[["/bin/true"] :passed]
                              [["/bin/sh" "-c" "exit 7"] :failed]
                              [["/missing-command"] :error]]]
    (workspace
     (fn [root]
       (let [subject (assoc gate :command command :coverage (batch/command-expectation command "."))
             result (execute root [subject] {"copy" (assoc (adapter root) :witness :command-invocation)} (atom false))
             captured (first (:captures result))]
         (is (= expected (get-in result [:batch :dispatches 0 :outcome])))
         (is (= (if (= expected :passed) :passed :failed) (:status result)))
         (is (= (if (= expected :error) nil :commands) (get-in captured [:coverage :unit])))
         (is (= (if (= expected :error) nil 1) (get-in captured [:coverage :count])))
         (is (empty? (get-in result [:graph :measurements])))
         (is (= result (read-report root))))))))

(deftest command-witness-refuses-other-units-and-invocation-identities
  (let [expectation (batch/command-expectation (:command gate) ".")]
    (doseq [wrong [(assoc expectation :unit :tests) (assoc expectation :unit :checks)
                   (batch/command-expectation ["/bin/cat input.txt"] ".")
                   (batch/command-expectation (:command gate) "other")]]
      (workspace
       (fn [root]
         (let [failure (try (execute root [(assoc gate :coverage wrong)]
                                     {"copy" (assoc (adapter root) :witness :command-invocation)} (atom false))
                            nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
           (is (= {:code :process-binding :subject "copy"} failure))
           (is (not (Files/exists (Path/of (str root "/report") (make-array String 0)) (make-array java.nio.file.LinkOption 0))))))))))

(deftest changed-input-preserves-the-executed-command-and-refuses-success
  (workspace
   (fn [root]
     (let [changed (assoc gate :command ["/bin/sh" "-c" "/bin/cat input.txt; printf changed > input.txt"])
           result (execute root [changed] {"copy" (adapter root)} (atom false))
           capture (first (:captures result))]
       (is (= :failed (:status result)))
       (is (= :input-unstable (get-in result [:batch :dispatches 0 :work :reason])))
       (is (= 0 (get-in capture [:process :observation :exit])))
       (is (not= (get-in capture [:validation :inputs-before]) (get-in capture [:validation :inputs-after])))
       (is (false? (get-in result [:graph :run :complete?])))
       (is (= result (read-report root)))))))

(deftest changed-controller-retains-first-execution-and-refuses-the-next-launch
  (workspace
   (fn [root]
     (let [first-gate (assoc gate :command ["/bin/sh" "-c" "/bin/cat input.txt; printf changed > controller"])
           second-gate (assoc gate :id "next" :command ["/bin/sh" "-c" "printf launched > marker"]
                              :dependencies [{:gate "copy" :relation :after}])
           result (execute root [first-gate second-gate] {"copy" (adapter root) "next" (adapter root)} (atom false))
           captures (into {} (map (juxt :gate identity) (:captures result)))]
       (is (= :failed (:status result)))
       (is (= 2 (count (:captures result))))
       (is (= 0 (get-in captures ["copy" :process :observation :exit])))
       (is (nil? (get-in captures ["next" :process])))
       (is (every? #(= :input-unstable (get-in % [:work :reason])) (get-in result [:batch :dispatches])))
       (is (not (Files/exists (Path/of (str root "/marker") (make-array String 0)) (make-array java.nio.file.LinkOption 0))))
       (is (= result (read-report root)))))))

(deftest missing-input-and-unlisted-stdin-refuse-before-any-process
  (doseq [mode [:missing-input :unlisted-stdin]]
    (workspace
     (fn [root]
       (let [calls (atom 0)
             subject (if (= mode :missing-input) (assoc-in gate [:inputs 0 :path] "missing") gate)
             configured (cond-> (assoc (adapter root) :witness (fn [& _] (swap! calls inc) coverage))
                          (= mode :unlisted-stdin) (assoc :stdin "controller"))
             result (execute root [subject] {"copy" configured} (atom false))]
         (is (= :failed (:status result)))
         (is (zero? @calls))
         (is (nil? (get-in result [:captures 0 :process])))
         (is (= :input-unstable (get-in result [:batch :dispatches 0 :work :reason])))
         (is (= result (read-report root))))))))

(deftest witness-failure-keeps-process-evidence-and-omits-exception-values
  (workspace
   (fn [root]
     (let [configured (assoc (adapter root) :witness (fn [& _] (throw (ex-info "private-secret" {:secret "private-secret"}))))
           result (execute root [gate] {"copy" configured} (atom false))]
       (is (= :failed (:status result)))
       (is (= 0 (get-in result [:captures 0 :process :observation :exit])))
       (is (true? (get-in result [:captures 0 :validation :coverage-error?])))
       (is (false? (get-in result [:graph :run :complete?])))
       (is (not (.contains ^String (slurp (str root "/report/report.edn")) "private-secret")))))))

(deftest failed-publication-is-retained-in-the-enclosing-report
  (workspace
   (fn [root]
     (let [original artifact/write-record!
           result (with-redefs [artifact/write-record! (fn [directory filename value target]
                                                         (if (= filename "process.edn") (throw (java.io.IOException.))
                                                             (original directory filename value target)))]
                    (execute root [gate] {"copy" (adapter root)} (atom false)))]
       (is (= :failed (:status result)))
       (is (= :output-publication (get-in result [:batch :dispatches 0 :work :reason])))
       (is (get-in result [:captures 0 :validation :publication-error?]))
       (is (false? (get-in result [:graph :run :complete?])))
       (is (= result (read-report root)))))))

(deftest failed-prerequisite-and-pre-cancellation-never-acquire-execution-spans
  (doseq [cancelled? [false true]]
    (workspace
     (fn [root]
       (let [bad (assoc gate :command ["/bin/sh" "-c" "exit 7"])
             next-gate (assoc gate :id "next" :dependencies [{:gate "copy" :relation :requires}])
             result (execute root [bad next-gate] {"copy" (adapter root) "next" (adapter root)} (atom cancelled?))]
         (is (= (if cancelled? :cancelled :failed) (:status result)))
         (is (= (if cancelled? [:cancelled :cancelled] [:failed :blocked]) (mapv :outcome (get-in result [:batch :dispatches]))))
         (is (= (if cancelled? 0 1) (count (:captures result))))
         (is (= result (read-report root))))))))

(deftest invalid-enrollment-and-policy-refuse-before-creating-reports
  (doseq [mode [:missing :extra :environment :toolchain :cache :network :outputs]]
    (workspace
     (fn [root]
       (let [subject (case mode :cache (assoc gate :cache :content) :network (assoc gate :network :denied)
                           :outputs (assoc gate :outputs ["result"]) gate)
             bindings (case mode :missing {"unexpected" (adapter root)}
                            :extra {"copy" (adapter root) "unexpected" (adapter root)}
                            :environment {"copy" (assoc (adapter root) :environment {"UNDECLARED" "value"})}
                            :toolchain {"copy" (assoc (adapter root) :toolchains {})}
                            {"copy" (adapter root)})
             failure (try (execute root [subject] bindings (atom false)) nil
                          (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= (if (contains? #{:cache :network :outputs} mode) :unsupported-process-policy :process-binding) (:code failure)))
         (is (= (when-not (contains? #{:missing :extra} mode) "copy") (:subject failure)))
         (is (m/validate batch/Failure failure))
         (is (not (Files/exists (Path/of (str root "/report") (make-array String 0)) (make-array java.nio.file.LinkOption 0)))))))))
