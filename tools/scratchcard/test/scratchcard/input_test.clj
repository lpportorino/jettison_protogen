(ns scratchcard.input-test
  "Pins the authoring path: EDN screen -> validated -> `ui.Screen` bytes.

  Runs on a stock JDK. The authoring pipeline is pure Clojure; only RENDERING
  needs GraalVM, so these do not need the container.

  THE ERROR-SIZE TEST IS NOT COSMETIC. `lvgl-codegen`'s validator returns a
  raw `malli.core/explain`, whose `:schema` entry is the entire screen schema.
  Measured: one missing key produced 427 KB of predicates and function
  objects. This tool's reader exists to serve a model with a bounded context,
  so an unbounded error is a correctness problem, not a formatting one."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.input :as input]
   [scratchcard.scope :as scope])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private repo-root
  (scope/discover-repo-root (System/getProperty "user.dir")))

(def ^:private example
  (str repo-root "/tools/scratchcard/example/hello.edn"))

(defn- tmp-dir
  ^File []
  (.toFile (Files/createTempDirectory "scratchcard-input-test"
                                      (into-array FileAttribute []))))

(defn- write-screen!
  [form]
  (let [f (io/file (tmp-dir) "screen.edn")]
    (spit f (pr-str form))
    f))

(defn- build-error
  "The ex-data from building `form`, or nil when it built cleanly."
  [form]
  (let [dir (tmp-dir)]
    (try
      (input/build! {:repo-root repo-root
                     :screen-path (str (write-screen! form))
                     :out-pb (str (io/file dir "out.pb"))})
      nil
      (catch clojure.lang.ExceptionInfo e (ex-data e)))))

(deftest the-shipped-example-builds
  ;; The example is what an author copies. If it stops building, the first
  ;; thing every new user does fails.
  (let [dir (tmp-dir)
        r (input/build! {:repo-root repo-root
                         :screen-path example
                         :out-pb (str (io/file dir "hello.pb"))})]
    (is (pos? (:bytes r)))
    (is (.isFile (io/file (:pb-path r))))
    (is (re-matches #"[0-9a-f]{64}" (:sha256 r)))
    (is (re-matches #"[0-9a-f]{64}" (:screen-sha256 r)))
    (testing "the emitted bytes are stable for identical input"
      (let [again (input/build! {:repo-root repo-root
                                 :screen-path example
                                 :out-pb (str (io/file (tmp-dir) "hello.pb"))})]
        (is (= (:sha256 r) (:sha256 again))
            "same screen must emit byte-identical .pb, or every run diffs")))))

(deftest screens-are-data-and-are-never-evaluated
  ;; `clojure.edn/read` cannot evaluate. If this ever became `read-string` or
  ;; `load-file`, anything able to write the screen file would gain code
  ;; execution inside a long-lived daemon.
  (let [f (io/file (tmp-dir) "evil.edn")]
    (spit f "#=(java.lang.System/exit 1)")
    (let [d (try (input/read-screen (str f)) nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= "INPUT_READ_FAILED" (:error d))
          "an eval form must be REFUSED, never executed"))))

(deftest a-missing-screen-is-typed-not-a-nil
  (is (= "INPUT_MISSING"
         (:error (try (input/read-screen "/nonexistent/scratch/screen.edn")
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(deftest malformed-edn-is-typed
  (let [f (io/file (tmp-dir) "torn.edn")]
    (spit f "{:type :screen :tree {")
    (is (= "INPUT_READ_FAILED"
           (:error (try (input/read-screen (str f))
                        (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest a-schema-failure-is-typed-attributed-and-BOUNDED
  (let [d (build-error {:type :screen :events {} :subjects {}
                        :tree {:tag :lv_nope}})]
    (is (= "INPUT_SCHEMA_INVALID" (:error d)))
    (is (= "Malli schema" (:stage d)))
    (testing "the explanation is SMALL — the raw explain is ~427 KB"
      (is (< (count (pr-str (:errors d))) input/max-error-chars)))
    (testing "and it is ACTIONABLE — it names the bad tag and the valid ones"
      (let [s (pr-str (:errors d))]
        (is (str/includes? s "lv_slider"))
        (is (str/includes? s "tag"))))
    (testing "the pipeline's own message is carried through verbatim"
      (is (str/includes? (str (:pipeline-message d)) "validation failed")))))

(deftest every-error-carries-a-code-from-the-declared-vocabulary
  (let [d (build-error {:type :screen :events {} :subjects {} :tree {:tag :lv_nope}})]
    (is (string? (:error d)))
    (is (str/starts-with? (:error d) "INPUT_"))))

(deftest classification-is-data-and-falls-back-loudly-rather-than-wrongly
  (testing "the table is well-formed: pattern, code, stage"
    (is (seq input/stage-classification))
    (doseq [[pattern code stage] input/stage-classification]
      (is (instance? java.util.regex.Pattern pattern))
      (is (and (string? code) (str/starts-with? code "INPUT_")))
      (is (and (string? stage) (not (str/blank? stage))))))
  (testing "every declared code is distinct — two stages sharing one code\n           would make attribution a coin flip"
    (let [codes (map second input/stage-classification)]
      (is (= (count codes) (count (set codes))))))
  (testing "an unrecognised message still yields a code and SAYS it is unattributed"
    ;; Degraded attribution with the real message beats a confident wrong code.
    (let [c (input/classify (ex-info "something nobody predicted" {}))]
      (is (= input/fallback-code (:code c)))
      (is (= "unattributed" (:stage c))))))

(deftest readable-errors-truncates-rather-than-flooding
  (let [huge (apply str (repeat (* 3 input/max-error-chars) "x"))
        out (pr-str (input/readable-errors huge))]
    (is (<= (count out) (+ input/max-error-chars 200)))
    (is (str/includes? out "TRUNCATED")))
  (testing "nil in, nil out — an absent explanation is not an empty one"
    (is (nil? (input/readable-errors nil)))))

(deftest defs-paths-are-absolute-and-do-not-depend-on-cwd
  ;; renderer-gen's pipeline reads edn/ and assets/ CWD-relative; a long-lived
  ;; daemon cannot rely on process CWD, so every input path is explicit.
  (let [p (input/defs-paths repo-root)]
    (is (str/starts-with? (:tokens p) repo-root))
    (is (str/starts-with? (:components p) repo-root))
    (is (.isFile (io/file (:tokens p))) "the pinned design-tokens manifest must exist")
    (is (.isFile (io/file (:components p))) "the components file must exist")))

(deftest defs-cache-is-content-keyed
  ;; A path-keyed cache would serve a warm daemon stale tokens forever, and the
  ;; symptom — a screen rendering with yesterday's palette — reads as a
  ;; renderer bug.
  (let [a (input/load-defs repo-root)
        b (input/load-defs repo-root)]
    (is (identical? a b) "an unchanged input set must hit the cache")))
