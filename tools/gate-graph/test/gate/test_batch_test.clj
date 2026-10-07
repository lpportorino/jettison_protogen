(ns gate.test-batch-test
  "Real native execution with synthetic input bytes; full module commands hash their real classpath."
  (:require [clojure.test :as test :refer [deftest is]]
            [example.observed-suite :as fixture]
            [gate.admission :as admission]
            [gate.batch-graph :as projection]
            [gate.decimal :as d]
            [gate.test-batch :as batch])
  (:import [java.io StringWriter]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def options {:run "native-example" :key "chain/native" :label "Native test controls"
              :coordinator {:jobs 2 :claims {}} :max-tests 20 :max-assertions 100})
(def namespaces '[example.observed-suite])

(defn with-directory
  "Own synthetic input/output fixtures, deleting only the completed test's tree."
  [f]
  (let [directory (Files/createTempDirectory "test-batch-control-" (make-array FileAttribute 0))
        source (Files/createDirectory (.resolve directory "source") (make-array FileAttribute 0))]
    (spit (str source "/input") "synthetic immutable input")
    (try (f directory source)
         (finally (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
                    (doseq [path (reverse (sort-by str (iterator-seq (.iterator paths))))]
                      (Files/deleteIfExists ^Path path)))))))

(defn suite
  "Use the real observer over an independently inventoried synthetic namespace, retaining its normal hooks."
  [mode]
  {:namespaces namespaces
   :runner (fn [] (binding [fixture/*mode* mode test/*test-out* (StringWriter.)]
                    (test/run-tests 'example.observed-suite)) nil)})

(defn failure
  "Return structured refusal data; test errors are not hidden as expected failures."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest native-concurrent-suites-share-one-clock-and-save-complete-joined-evidence
  (with-directory
    (fn [directory source]
      (let [ready (CountDownLatch. 2)
            make-suite (fn [] (update (suite :normal) :runner
                                      (fn [runner] (fn [] (.countDown ready)
                                                     (when-not (.await ready 10 TimeUnit/SECONDS)
                                                       (throw (ex-info "Synthetic barrier expired" {})))
                                                     (runner)))))
            gates [(batch/declaration "a" "A" namespaces []) (batch/declaration "b" "B" namespaces [])]
            report (batch/run! options gates ["a" "b"] {"a" (make-suite) "b" (make-suite)}
                               [(str source)] (str directory "/report") (atom false))
            [a b] (:captures report)
            graph (admission/decode (slurp (str directory "/report/graph.edn")) :graph admission/default-limits)
            saved (admission/decode (slurp (str directory "/report/batch.edn")) :coordinator-batch admission/default-limits)]
        (is (= :passed (:status report)))
        (is (= saved (:batch report)))
        (is (= graph (:graph report)))
        (is (get-in graph [:run :complete?]))
        (is (= 4 (count (filter #(= :test (:kind %)) (:nodes graph)))))
        (is (= (:clock saved) (:clock a) (:clock b)))
        (is (neg? (d/compare (:offset-ns b) (d/add (:offset-ns a) (get-in a [:observation :duration-ns])))))
        (is (neg? (d/compare (:offset-ns a) (d/add (:offset-ns b) (get-in b [:observation :duration-ns])))))
        (is (= #{:gate :chain :test :boundary} (set (map :kind (:nodes graph)))))))))

(deftest failed-test-gates-block-required-work-and-order-only-work-still-runs
  (with-directory
    (fn [directory source]
      (let [calls (atom [])
            tracked (fn [id mode] (update (suite mode) :runner (fn [runner] (fn [] (swap! calls conj id) (runner)))))
            gates [(batch/declaration "a" "A" namespaces [])
                   (batch/declaration "b" "B" namespaces [{:gate "a" :relation :requires}])
                   (batch/declaration "c" "C" namespaces [{:gate "a" :relation :after}])]
            report (batch/run! options gates ["b" "c"] {"a" (tracked "a" :failure) "b" (tracked "b" :normal) "c" (tracked "c" :normal)}
                               [(str source)] (str directory "/report") (atom false))
            outcomes (into {} (map (juxt :gate :outcome) (get-in report [:batch :dispatches])))]
        (is (= :failed (:status report)))
        (is (= ["a" "c"] @calls))
        (is (= {"a" :failed "b" :blocked "c" :passed} outcomes))
        (is (get-in report [:graph :run :complete?]))
        (is (= 2 (count (:captures report))))))))

(deftest enrollment-and-native-policy-refuse-before-any-work-or-output
  (with-directory
    (fn [directory source]
      (let [calls (atom 0) native (assoc (suite :normal) :runner (fn [] (swap! calls inc) nil))
            a (batch/declaration "a" "A" namespaces []) b (batch/declaration "b" "B" namespaces [])
            output (str directory "/report")]
        (doseq [[gates suites code] [[[a b] {"a" native} :test-suite-binding]
                                     [[(assoc-in a [:coverage :expected] (apply str (repeat 64 "a")))] {"a" native} :test-suite-binding]
                                     [[(assoc a :cache :content)] {"a" native} :unsupported-test-policy]
                                     [[(assoc a :network :denied)] {"a" native} :unsupported-test-policy]]]
          (is (= code (:code (failure #(batch/run! options gates ["a"] suites [(str source)] output (atom false)))))))
        (is (zero? @calls))
        (is (not (Files/exists (Path/of output (make-array String 0)) (make-array java.nio.file.LinkOption 0))))))))

(deftest pre-cancellation-is-a-complete-decision-without-running-tests
  (with-directory
    (fn [directory source]
      (let [calls (atom 0) native (assoc (suite :normal) :runner (fn [] (swap! calls inc) nil))
            gate (batch/declaration "a" "A" namespaces [])
            report (batch/run! options [gate] ["a"] {"a" native} [(str source)] (str directory "/report") (atom true))
            node (first (filter #(= :gate (:kind %)) (get-in report [:graph :nodes])))]
        (is (= :cancelled (:status report)))
        (is (zero? @calls))
        (is (= :decision (:record node)))
        (is (= :cancelled (:outcome node)))
        (is (nil? (:interval node)))
        (is (empty? (:captures report)))
        (is (get-in report [:graph :run :complete?]))))))

(deftest incomplete-acquisition-cannot-be-promoted-by-a-passing-batch
  (with-directory
    (fn [directory source]
      (let [project projection/project
            gate (batch/declaration "a" "A" namespaces [])
            report (with-redefs [projection/project (fn [& arguments] (assoc-in (apply project arguments) [:run :complete?] false))]
                     (batch/run! options [gate] ["a"] {"a" (suite :normal)} [(str source)] (str directory "/report") (atom false)))]
        (is (= :passed (get-in report [:batch :status])))
        (is (= :failed (:status report)))
        (is (false? (get-in report [:graph :run :complete?])))))))

(deftest changed-source-retains-real-test-work-and-blocks-dependent-work
  (with-directory
    (fn [directory source]
      (let [normal (suite :normal) calls (atom [])
            changing (update normal :runner (fn [runner] (fn [] (runner) (spit (str source "/input") "changed") nil)))
            skipped (update normal :runner (fn [runner] (fn [] (swap! calls conj "b") (runner))))
            gates [(batch/declaration "a" "A" namespaces [])
                   (batch/declaration "b" "B" namespaces [{:gate "a" :relation :requires}])]
            report (batch/run! options gates ["b"] {"a" changing "b" skipped} [(str source)] (str directory "/report") (atom false))]
        (is (= :failed (:status report)))
        (is (= [:error :blocked] (mapv :outcome (get-in report [:batch :dispatches]))))
        (is (empty? @calls))
        (is (= :input-unstable (get-in report [:batch :dispatches 0 :work :reason])))
        (is (= 2 (count (get-in report [:captures 0 :observation :executions]))))
        (is (nil? (get-in report [:captures 0 :observation :coverage])))
        (is (false? (get-in report [:graph :run :complete?])))))))
