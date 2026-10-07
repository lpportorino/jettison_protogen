(ns gate.admission-campaign
  "Declarative manual fault assessment with closed EDN results and fresh JVM workers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.mutation-runner :as runner]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def Text [:string {:max 4096}])
(def PathName [:string {:min 1 :max 4096}])
(def Fault
  [:map {:closed true} [:id c/Id] [:anchor Text] [:replacement Text] [:test runner/TestName]])
(def Execution
  [:map {:closed true} [:command [:vector {:min 1 :max 12} PathName]]
   [:exit :int] [:timed-out? :boolean] [:elapsed-ns c/Natural]
   [:log PathName] [:log-digest c/Digest]])
(def FaultResult
  [:map {:closed true} [:fault Fault] [:source PathName]
   [:original-digest c/Digest] [:mutant-digest c/Digest] [:execution Execution]
   [:control runner/TestName] [:counters [:maybe runner/Result]]
   [:outcome [:enum :killed :survived :invalid]]])
(def Baseline [:map {:closed true} [:execution Execution] [:passed? :boolean]])
(def Fingerprints [:map-of {:max 256} PathName c/Digest])
(def Report
  [:map {:closed true} [:schema/version [:= 1]] [:scope [:enum :edn-admission :coordinator :adapter-verdict :live-inputs :cache-attempt :process :process-verdict :process-projection :process-batch :process-cli :process-capture-verdict :coverage-unit-verdict :coverage-unit-cache :container :output-publication :contained-completion :runtime :runtime-admission :output-ownership :test-observer :test-projection :test-artifact :native-clock :batch-projection :native-test-batch :graph-evidence :engine-identity]]
   [:fingerprints Fingerprints]
   [:runtime [:map {:closed true} [:java-version Text] [:java-vm Text] [:clojure-version Text]]]
   [:initial Baseline] [:final [:maybe Baseline]]
   [:faults [:vector {:max 64} FaultResult]] [:unchanged? :boolean] [:passed? :boolean]])
(def ^:dynamic *mutation-source* "src/gate/admission.cljc")
(def ^:dynamic *control* "canonical-and-human-edn-have-the-same-meaning")
(def ^:dynamic *test-namespace* "gate.admission-test")
(def ^:dynamic *scope* :edn-admission)

(def faults
  "Selected behavioral faults; each anchor is required to occur exactly once."
  [{:id "matching-delimiter" :anchor "(and (= :vector (:kind frame)) (= 93 unit))"
    :replacement "(= :vector (:kind frame))" :test "grammar-refusals-never-evaluate-reader-forms"}
   {:id "complete-map-pair" :anchor "(and (= :map (:kind frame)) (= 125 unit) (nil? (:field frame)))"
    :replacement "(and (= :map (:kind frame)) (= 125 unit))" :test "grammar-refusals-never-evaluate-reader-forms"}
   {:id "byte-limit" :anchor "(> (+ size width) max-bytes)" :replacement "false"
    :test "byte-limit-counts-source-utf8-including-comments"}
   {:id "unicode" :anchor "(or low? (and high? (not (<= 56320 (unit-at text (inc offset)) 57343))))"
    :replacement "false" :test "source-surrogates-refuse-without-replacement-character-conversion"}
   {:id "utf8-width" :anchor "high? 4 :else 3" :replacement "high? 3 :else 3"
    :test "byte-limit-counts-source-utf8-including-comments"}
   {:id "depth-limit" :anchor "(> depth (get-in context [:limits :depth]))" :replacement "false"
    :test "collection-and-depth-bounds-admit-the-exact-boundary"}
   {:id "value-limit" :anchor "(>= used (get-in context [:limits :values]))" :replacement "false"
    :test "total-values-counts-map-keys-as-well-as-values"}
   {:id "vector-limit" :anchor "(>= (count (:items frame)) limit)" :replacement "false"
    :test "nested-vectors-spend-limits-before-growing"}
   {:id "map-limit" :anchor "(>= (count (:entries frame)) limit)" :replacement "false"
    :test "collection-and-depth-bounds-admit-the-exact-boundary"}
   {:id "string-limit" :anchor "(>= (count parts) limit)" :replacement "false"
    :test "strings-and-tokens-are-bounded-before-retention"}
   {:id "token-limit" :anchor "(>= (- position offset) limit)" :replacement "false"
    :test "strings-and-tokens-are-bounded-before-retention"}
   {:id "integer-limit" :anchor "(compare token \"2147483647\")"
    :replacement "(compare token \"2147483648\")" :test "portable-integers-do-not-overflow-or-round"}
   {:id "duplicate-key" :anchor "(contains? (:entries frame) item)" :replacement "false"
    :test "duplicate-keys-are-rejected-before-replacement-values"}
   {:id "trailing-input" :anchor "(< trailing (count text))" :replacement "false"
    :test "unknown-keywords-and-trailing-input-have-named-refusals"}
   {:id "closed-shape" :anchor "(when-not (m/validate (get target-schemas target) value)"
    :replacement "(when false" :test "lexical-admission-does-not-authorize-open-normalized-maps"}
   {:id "graph-invariants" :anchor "(graph/require-valid! value)" :replacement "value"
    :test "graph-admission-enforces-references-beyond-map-shape"}])

(def ^:dynamic *faults* faults)

(defn fingerprints
  "Freeze source, test and dependency bytes; build caches and reports are not inputs."
  [root]
  (let [base (.toPath (io/file root))]
    (into (sorted-map)
          (for [file (file-seq (io/file root))
                :when (.isFile ^File file)
                :let [path (str (.relativize base (.toPath ^File file)))]
                :when (or (= path "deps.edn")
                          (and (or (str/starts-with? path "src/") (str/starts-with? path "test/"))
                               (re-find #"\.(?:clj|cljc|edn)$" path)))]
            [path (canonical/sha256 (slurp file))]))))
(m/=> fingerprints [:=> [:cat PathName] Fingerprints])

(defn execute
  "Bound one fresh Linux process session and preserve its unfiltered diagnostic log."
  [command cwd output]
  (let [started (System/nanoTime)
        builder (doto (ProcessBuilder. ^java.util.List (into ["setsid"] command))
                  (.directory (io/file cwd)) (.redirectErrorStream true)
                  (.redirectOutput (io/file output)))
        process (.start builder)
        finished? (.waitFor process 180 TimeUnit/SECONDS)]
    (when-not finished?
      ;; setsid runs the CLI in a new session whose process group has this PID.
      ;; Kill the whole group, including children that outlive the CLI wrapper.
      (let [killer (.start (ProcessBuilder. ^java.util.List
                            ["/bin/kill" "-KILL" "--" (str "-" (.pid process))]))]
        (when-not (.waitFor killer 5 TimeUnit/SECONDS) (.destroyForcibly killer)))
      (.destroyForcibly process)
      (when-not (.waitFor process 5 TimeUnit/SECONDS)
        (throw (ex-info "Worker did not terminate" {:code :worker-stuck}))))
    {:command command :exit (.exitValue process) :timed-out? (not finished?)
     :elapsed-ns (str (- (System/nanoTime) started))
     :log (.getName (io/file output)) :log-digest (canonical/sha256 (slurp output))}))
(m/=> execute [:=> [:cat [:vector {:min 1 :max 12} PathName] PathName PathName] Execution])

(defn baseline
  "Require the full instrumented suite, including its cold-start regression, to pass."
  [module output label]
  (let [log-path (str (io/file output (str label ".log")))
        execution (execute ["clojure" "-M:test"] module log-path)
        [_ tests assertions] (re-find #"Ran (\d+) tests containing (\d+) assertions\.\n0 failures, 0 errors\."
                                      (slurp log-path))]
    {:execution execution
     :passed? (boolean (and (zero? (:exit execution)) (not (:timed-out? execution))
                            tests (pos? (parse-long tests)) (pos? (parse-long assertions))))}))
(m/=> baseline [:=> [:cat PathName PathName [:enum "initial" "final"]] Baseline])

(defn classify
  "Pure verdict: errors, missing tests and failing controls can never be kills."
  [execution counters]
  (if (and (zero? (:exit execution)) (not (:timed-out? execution)) counters
           (every? #(and (= 1 (:test %)) (pos? (+ (:pass %) (:fail %))) (zero? (:error %)))
                   (vals counters))
           (zero? (get-in counters [:control :fail])))
    (if (pos? (get-in counters [:target :fail])) :killed :survived)
    :invalid))
(m/=> classify [:=> [:cat Execution [:maybe runner/Result]] [:enum :killed :survived :invalid]])

(defn read-counters
  "Admit the worker's bounded, schema-checked EDN summary independently of log text."
  [path]
  (let [file (io/file path)]
    (when (and (.isFile file) (<= (.length file) 16384))
      (try
        (let [value (edn/read-string (slurp file))]
          (when (m/validate runner/Result value) value))
        (catch RuntimeException _ nil)))))
(m/=> read-counters [:=> [:cat PathName] [:maybe runner/Result]])

(defn assess
  "Apply one exact fault in the disposable copy, then restore and record both judged hashes."
  [module output fault]
  (let [path (io/file module *mutation-source*) original (slurp path)
        {:keys [id anchor replacement]} fault
        occurrences (count (re-seq (re-pattern (java.util.regex.Pattern/quote anchor)) original))]
    (when-not (= 1 occurrences) (throw (ex-info "Fault anchor is not unique" {:code :invalid-fault :id id})))
    (let [changed (str/replace-first original anchor replacement)
          result-path (str (io/file output (str id ".edn")))]
      (try
        (spit path changed)
        (when (or (= changed original) (str/includes? changed anchor) (not= changed (slurp path)))
          (throw (ex-info "Fault bytes did not land" {:code :invalid-fault :id id})))
        (let [execution (execute ["clojure" "-M:test:mutation" (:test fault) *control* result-path *test-namespace*]
                                 module (str (io/file output (str id ".log"))))
              counters (read-counters result-path)
              outcome (classify execution counters)]
          (println id outcome)
          (flush)
          {:fault fault :source *mutation-source* :original-digest (canonical/sha256 original)
           :mutant-digest (canonical/sha256 changed) :execution execution
           :control *control* :counters counters :outcome outcome})
        (finally (spit path original))))))
(m/=> assess [:=> [:cat PathName PathName Fault] FaultResult])

(defn assess-all
  "Compose immutable campaign evidence from selected faults and independently checked baselines."
  [module output original-root frozen]
  (let [initial (baseline module output "initial")
        results (if (:passed? initial) (mapv #(assess module output %) *faults*) [])
        final (when (:passed? initial) (baseline module output "final"))
        unchanged? (= frozen (fingerprints module) (fingerprints original-root))]
    {:schema/version 1 :scope *scope* :fingerprints frozen
     :runtime {:java-version (System/getProperty "java.version")
               :java-vm (System/getProperty "java.vm.name") :clojure-version (clojure-version)}
     :initial initial :final final :faults results :unchanged? unchanged?
     :passed? (boolean (and (:passed? initial) (:passed? final) unchanged?
                            (= (count *faults*) (count results)) (every? #(= :killed (:outcome %)) results)))}))
(m/=> assess-all [:=> [:cat PathName PathName PathName Fingerprints] Report])

(defn -main
  "Run the manual campaign in an isolated copy; keep full logs and closed EDN evidence."
  [output-parent]
  (mi/instrument!)
  (when-not (m/validate [:vector {:min 1 :max 64} Fault] *faults*)
    (throw (ex-info "Invalid fault declarations" {:code :invalid-faults})))
  (let [root (.getCanonicalPath (io/file ".")) frozen (fingerprints root)
        parent (.toPath (io/file output-parent))]
    (Files/createDirectories parent (make-array FileAttribute 0))
    (let [output (.toFile (Files/createTempDirectory parent (str (name *scope*) "-") (make-array FileAttribute 0)))
          module (io/file output "work")]
      (println "report:" (.getCanonicalPath output))
      (doseq [path (keys frozen)]
        (let [destination (io/file module path)]
          (io/make-parents destination)
          (io/copy (io/file root path) destination)))
      (try
        (let [report (assess-all (.getCanonicalPath module) (.getCanonicalPath output) root frozen)]
          (when-not (m/validate Report report)
            (throw (ex-info "Invalid campaign report" {:code :invalid-campaign-report})))
          (spit (io/file output "report.edn") (pr-str report))
          (when-not (:passed? report)
            (throw (ex-info "Campaign did not pass" {:code :campaign-failed}))))
        (finally
          (doseq [file (reverse (file-seq module))] (io/delete-file file))
          (shutdown-agents))))))
(m/=> -main [:=> [:cat PathName] :nil])
